package dreamteam.domain.training

import dreamteam.domain.UserId
import dreamteam.domain.adaptation.AdaptationSignal
import dreamteam.domain.adaptation.deriveAdaptationSignal
import dreamteam.domain.nutrition.NutritionTarget
import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.safety.Recommendation
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.SurfacedPlan
import dreamteam.domain.symptom.Symptom

/**
 * The deterministic plan generator. Produces the PoC baseline training plan
 * ([BaselineProgram]) **and vets every assignment through the
 * [SafetyGuardedGateway]** before anything can be returned to a caller. This is
 * the structural integration the milestone title names ("deterministic plan-gen
 * through safety gateway"): there is no code path from a plan to the user that
 * does not pass [SafetyGuardedGateway.surface].
 *
 * Flow:
 *  1. Build the deterministic [TrainingPlan] + baseline [NutritionTarget].
 *  2. Project every assignment to a [Recommendation] (exerciseId + evidence refs
 *     + movement-set tags from the library, so a contraindication rule can match).
 *  3. [SafetyGuardedGateway.surface] — all-or-nothing: if *any* assignment is
 *     blocked, *nothing* is surfaced (a partial plan with a silently-dropped
 *     contraindicated exercise is a footgun; see [SurfacedPlan]).
 *  4. On [SurfacedPlan.Ok] return the full, vetted plan; on
 *     [SurfacedPlan.Blocked] return the block detail (the caller maps that to a
 *     409, never a 200 with a hole in it).
 *
 * No LLM, no I/O, no clinical authoring: the baseline is self-sourced from the
 * PoC. The generator is intentionally deterministic — same inputs, same plan —
 * so the offline-first client and the backend produce byte-identical baseline
 * plans through the same gate.
 */
class DeterministicPlanGenerator(private val gateway: SafetyGuardedGateway) {

    fun generate(
        userId: UserId,
        createdAt: String,
        planId: String = "baseline-12w",
        adaptation: AdaptationSignal = AdaptationSignal.None,
    ): GeneratedPlan {
        val plan = BaselineProgram.baselineTrainingPlan(userId, planId, createdAt, adaptation)
        val nutrition = BaselineNutrition.baselineTarget(userId, createdAt)

        // Every assignment becomes a candidate recommendation; the gateway is
        // the sole chokepoint that can promote them to a surfaced plan.
        val candidates: List<Recommendation> = plan.weeks
            .flatMap { it.sessions }
            .flatMap { it.assignments }
            .map { toRecommendation(it) }

        return when (val surfaced = gateway.surface(candidates)) {
            is SurfacedPlan.Ok -> GeneratedPlan.Ok(plan, nutrition)
            is SurfacedPlan.Blocked -> GeneratedPlan.Blocked(
                blockedExerciseIds = surfaced.items.map { it.recommendation.exerciseId },
                reasons = surfaced.items.map { it.verdict.reason },
                ruleIds = surfaced.items.flatMap { it.verdict.ruleIds }.distinct(),
            )
        }
    }

    /**
     * The weekly recalculation ([DRE-51](/DRE/issues/DRE-51), M3-B): derive the
     * fresh [AdaptationSignal] from a user's logged progress + symptoms, then
     * regenerate the plan **through the same [generate] / safety gate** — under a
     * **new versioned [planId]** so the prior plan is retained, not overwritten.
     *
     * This is the log-driven counterpart to baseline [generate]: same gate, same
     * determinism, de-load-only (the signal sealed type has no increase variant).
     * [progress] / [symptoms] are the caller's recent window for this user
     * (e.g. `progress.recentFor(user, n)`); [deriveAdaptationSignal] decides the
     * cut from their contents. Default [planId] is `"{userId}@{createdAt}"` —
     * distinct from the baseline `"baseline-12w"` id, so a recalc never collides
     * with or overwrites the baseline version.
     *
     * ponytail: weekly cadence assumption — a date-granularity id can collide if
     * two recalcs run the same day with *different* logs (same logs → identical
     * plan → harmless no-op overwrite). The "weekly" cadence is a caller policy
     * (out of scope: no scheduler here); if same-day multi-recalc ever becomes
     * real, switch the id to a monotonic version derived from history size.
     */
    fun recalculate(
        userId: UserId,
        createdAt: String,
        progress: List<ProgressEntry>,
        symptoms: List<Symptom>,
        planId: String = "${userId}@${createdAt}",
    ): GeneratedPlan {
        val signal = deriveAdaptationSignal(progress, symptoms)
        return generate(userId, createdAt, planId, signal)
    }
}

/**
 * Projects an [ExerciseAssignment] to a candidate [Recommendation], carrying the
 * movement-set tags from the library record ([BaselineProgram.exercises]) so a
 * contraindication rule
 * ([dreamteam.domain.safety.RuleTrigger.ContraindicationStub]) can match on
 * them (DRE-18: tags flow library → exerciseTags → rule). An unknown id
 * (should not happen — the baseline is self-sourced) yields an empty tag set and
 * is then caught by the structural allowlist rule.
 */
internal fun toRecommendation(assignment: ExerciseAssignment): Recommendation =
    Recommendation(
        exerciseId = assignment.exerciseId,
        evidenceRefs = assignment.evidenceRefs,
        exerciseTags = BaselineProgram.exercises[assignment.exerciseId]?.movementTags ?: emptySet(),
    )

/** Outcome of deterministic generation: a fully-vetted plan, or a block (nothing surfaced). */
sealed interface GeneratedPlan {
    /** Every assignment passed the gate — the plan + nutrition may reach the user. */
    data class Ok(
        val plan: TrainingPlan,
        val nutrition: NutritionTarget,
    ) : GeneratedPlan

    /**
     * At least one assignment was blocked; nothing is surfaced. Carries the
     * blocked exercise ids + rule ids for transparency/audit (logged server-side,
     * never rendered to the user as guidance).
     */
    data class Blocked(
        val blockedExerciseIds: List<String>,
        val reasons: List<String>,
        val ruleIds: List<String>,
    ) : GeneratedPlan
}
