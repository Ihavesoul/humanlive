package dreamteam.domain.safety

import dreamteam.domain.EvidenceId
import dreamteam.domain.EvidenceLinked
import kotlinx.serialization.Serializable

/**
 * What the engine is proposing to surface to the user. Minimal by design: the
 * rule engine only needs the ids it must vet (the exercise + its evidence).
 */
@Serializable
data class Recommendation(
    val exerciseId: String,
    override val evidenceRefs: List<EvidenceId>,
) : dreamteam.domain.EvidenceLinked

/**
 * The user's screening context at evaluation time — the facts rules test
 * against. Populated from the user's medical context + the active allowlists
 * ([dreamteam.domain.user.User]; ADR 0001 §2).
 *
 * `redFlags` here is the domain enum view; the wire DTO [MedicalSafety] carries
 * the raw schema strings. DRE-7 bridges the two.
 */
@Serializable
data class ScreeningContext(
    val redFlags: Set<RedFlag> = emptySet(),
    val sideSpecificLockEngaged: Boolean = false,
    val allowedExerciseIds: Set<String> = emptySet(),
    val allowedEvidenceIds: Set<EvidenceId> = emptySet(),
    val clinicianCurveSpecificPlanAvailable: Boolean = false,
)

/**
 * Applies a list of [SafetyRule]s to a [Recommendation] and returns a binding
 * [SafetyVerdict]. Distinct from the deterministic pre-LLM [SafetyGate]
 * (DRE-8), which vets the overall medical-safety DTO before generation starts;
 * this engine vets each *specific* recommendation against the registered rules.
 *
 * The first matching rule wins; an explicit [SafetyVerdict.Block] is returned
 * with **no override path** — there is no "dismiss" / "skip" parameter anywhere
 * in this API, by construction. Rule *content* (which flags/ids actually block)
 * is supplied by the caller — the Safety Reviewer's ruleset, wired in DRE-7.
 * With an empty ruleset it allows; the load-bearing block rules are registered
 * upstream, never defaulted here.
 *
 * DRE-6 done-when: "a SafetyRule that can block a recommendation" — this engine
 * is that mechanism; [SafetyGateTest] pins it.
 */
object SafetyRuleEngine {
    fun evaluate(
        recommendation: Recommendation,
        context: ScreeningContext,
        rules: List<SafetyRule>,
    ): SafetyVerdict {
        for (rule in rules) {
            if (matches(rule.trigger, recommendation, context)) {
                return SafetyVerdict.Block(reason = rule.description, ruleIds = listOf(rule.id))
            }
        }
        return SafetyVerdict.Allow
    }

    private fun matches(
        trigger: RuleTrigger,
        rec: Recommendation,
        ctx: ScreeningContext,
    ): Boolean = when (trigger) {
        is RuleTrigger.RedFlagPresent -> trigger.flag in ctx.redFlags
        is RuleTrigger.SideSpecificLockEngaged -> ctx.sideSpecificLockEngaged
        is RuleTrigger.ExerciseNotInAllowlist -> rec.exerciseId !in ctx.allowedExerciseIds
        is RuleTrigger.EvidenceNotInAllowlist -> rec.evidenceRefs.any { it !in ctx.allowedEvidenceIds }
        is RuleTrigger.ClinicianCurveSpecificPlanRequired -> !ctx.clinicianCurveSpecificPlanAvailable
    }
}
