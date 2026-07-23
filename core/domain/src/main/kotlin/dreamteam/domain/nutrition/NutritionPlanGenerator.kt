package dreamteam.domain.nutrition

import dreamteam.domain.EvidenceId
import dreamteam.domain.NutritionPlanId
import dreamteam.domain.UserId
import dreamteam.domain.profile.Anthropometrics
import dreamteam.domain.profile.BasalMetabolicRate
import dreamteam.domain.safety.Recommendation
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.SurfacedPlan
import kotlin.math.roundToInt

/**
 * The deterministic nutrition-plan generator (M4-A). Turns body metrics + a
 * recomposition goal into a fully evidence-linked [NutritionPlan]: resting
 * energy (Katch-McArdle on logged weight + composition, Mifflin-St Jeor
 * fallback) → activity-adjusted target → macro split → meal structure. Pure
 * domain: no LLM, no I/O, no Android/UI.
 *
 * Same invariants as the training half: deterministic (identical inputs →
 * identical plan), evidence-linked (every target carries cataloged refs), behind
 * the [SafetyGuardedGateway] (the same single chokepoint training uses), and
 * never a diagnosis or medical claim. The output is *support* framing; the
 * [CalibrationRule] (not this generator) governs trend-based adjustment — never
 * a reaction to a single weigh-in.
 *
 * **Safety-gate posture (M4 invariant).** The built plan is projected to a
 * candidate [Recommendation] (id [NUTRITION_ITEM_ID], its cataloged
 * [evidenceRefs][NutritionPlan.evidenceRefs], and a food/allergen tag set that
 * is empty today) and surfaced through [SafetyGuardedGateway.surface] exactly as
 * [dreamteam.domain.training.DeterministicPlanGenerator] does. This makes the
 * gate the structural chokepoint for nutrition *now*, so that when the Safety
 * Reviewer adds a medical/allergy contraindication (a
 * [dreamteam.domain.safety.RuleTrigger.ContraindicationStub] on a food tag + an
 * allergy condition flag), it blocks a contraindicated plan the same way it
 * blocks a contraindicated exercise — nutrition advice can never override a
 * contraindication. Provision the gateway with the **evidence allowlist** +
 * nutrition contraindications (NOT the exercise allowlist: a nutrition plan is
 * not a library exercise, so [NUTRITION_ITEM_ID] is not an exercise id).
 */
class NutritionPlanGenerator(private val gateway: SafetyGuardedGateway) {
    // Energy (kcal) per gram of each macro.
    private val kcalPerGProtein = 4
    private val kcalPerGCarb = 4
    private val kcalPerGFat = 9

    /**
     * Physical activity level (PAL). A deterministic, conservative *sedentary*
     * baseline (WHO-ACTIVITY-2020). The seed profile is desk-bound with planned
     * training; the [CalibrationRule] adjusts the resulting target on observed
     * multi-day trends, so the generator starts conservative rather than chasing
     * a noisy single point.
     * ponytail: single fixed PAL — a per-user activity tier is a future input
     * (new onboarding field); add when a tiered PAL measurably beats trend-based
     * calibration. Ceiling: underestimates very-active users until calibration runs.
     */
    private val pal = 1.4

    /** Protein dose (g/kg body weight) — upper-mid of the ISSN/Morton hypertrophy range. */
    private val proteinGPerKg = 2.0

    /** Target fraction of energy from fat (ISSN-DIETS-2017). */
    private val fatKcalFraction = 0.25

    /** Conservative deterministic deficit for an explicit fat-loss goal. */
    private val fatLossDeficitKcal = 400

    /**
     * Deterministic 4-meal structure (content language is RU per ADR 0001; ids
     * stay English). Fractions sum to 1.0; macros per slot are the day's totals
     * scaled by [MealTemplate.fraction].
     */
    private val mealTemplate: List<MealTemplate> = listOf(
        MealTemplate("breakfast", "Завтрак", 0.30),
        MealTemplate("lunch", "Обед", 0.30),
        MealTemplate("dinner", "Ужин", 0.25),
        MealTemplate("snack", "Перекус", 0.15),
    )

    private data class MealTemplate(val slot: String, val label: String, val fraction: Double)

    /**
     * Builds the plan and vets it through [SafetyGuardedGateway.surface]. Same
     * inputs (including [recordedOn]) always produce a byte-identical plan, and
     * the plan reaches the caller only via [GeneratedNutritionPlan.Ok] — i.e.
     * only when the gate allows it. A blocked plan surfaces nothing but the
     * block reasons/rule ids (for logging/audit, never rendered as guidance).
     *
     * [planId] defaults to "{userId}@{recordedOn}" — distinct per day, mirroring
     * the training-plan versioning
     * ([dreamteam.domain.training.DeterministicPlanGenerator.recalculate]),
     * so a recalculation saves under a new id and prior plans are retained.
     */
    fun generate(
        userId: UserId,
        body: Anthropometrics,
        goal: NutritionGoal,
        recordedOn: String,
        planId: NutritionPlanId = "${userId}@${recordedOn}",
    ): GeneratedNutritionPlan {
        val plan = build(userId, body, goal, recordedOn, planId)
        val candidate = Recommendation(
            exerciseId = NUTRITION_ITEM_ID,
            evidenceRefs = plan.evidenceRefs,
            exerciseTags = emptySet(), // ponytail: no food/allergen taxonomy yet; future allergen tags plug in here
        )
        return when (val surfaced = gateway.surface(listOf(candidate))) {
            is SurfacedPlan.Ok -> GeneratedNutritionPlan.Ok(plan)
            is SurfacedPlan.Blocked -> GeneratedNutritionPlan.Blocked(
                reasons = surfaced.items.map { it.verdict.reason },
                ruleIds = surfaced.items.flatMap { it.verdict.ruleIds }.distinct(),
            )
        }
    }

    /**
     * The deterministic math, ungated. [generate] wraps this in the safety gate;
     * kept separate so the math is pinnable independently of the gate's provisioning.
     */
    private fun build(
        userId: UserId,
        body: Anthropometrics,
        goal: NutritionGoal,
        recordedOn: String,
        planId: NutritionPlanId,
    ): NutritionPlan {
        val resting = BasalMetabolicRate.estimateResting(body)
        val tdee = (resting.kcal * pal).roundToInt()
        val targetKcal = tdee - if (goal == NutritionGoal.FAT_LOSS) fatLossDeficitKcal else 0

        val proteinG = (proteinGPerKg * body.weightKg).roundToInt()
        val fatG = (fatKcalFraction * targetKcal / kcalPerGFat).roundToInt()
        // Carbohydrate = energy remaining after protein + fat are placed.
        val carbohydrateG =
            ((targetKcal - proteinG * kcalPerGProtein - fatG * kcalPerGFat)
                .toDouble() / kcalPerGCarb)
                .roundToInt()
                .coerceAtLeast(0) // ponytail: guards an extreme deficit; real inputs stay well positive.

        val evidenceRefs: List<EvidenceId> =
            listOf(resting.evidenceId, "MORTON-PROTEIN-2018", "ISSN-DIETS-2017", "WHO-ACTIVITY-2020").distinct()

        val target = NutritionTarget(
            userId = userId,
            targetKcal = targetKcal,
            proteinG = proteinG,
            fatG = fatG,
            carbohydrateG = carbohydrateG,
            evidenceRefs = evidenceRefs,
            recordedOn = recordedOn,
        )

        val structure = mealTemplate.map { t ->
            Meal(
                slot = t.slot,
                label = t.label,
                fraction = t.fraction,
                targetKcal = (targetKcal * t.fraction).roundToInt(),
                proteinG = (proteinG * t.fraction).roundToInt(),
                fatG = (fatG * t.fraction).roundToInt(),
                carbohydrateG = (carbohydrateG * t.fraction).roundToInt(),
            )
        }

        return NutritionPlan(
            id = planId,
            userId = userId,
            goal = goal,
            target = target,
            structure = structure,
            evidenceRefs = evidenceRefs,
            createdAt = recordedOn,
        )
    }
}

/**
 * The surfaced-item id a nutrition plan projects to for gateway vetting. Not an
 * exercise id — provision the nutrition gateway with the evidence allowlist +
 * nutrition contraindications, not the exercise allowlist.
 */
const val NUTRITION_ITEM_ID: String = "nutrition_plan"

/** Outcome of deterministic nutrition generation, mirroring training's GeneratedPlan. */
sealed interface GeneratedNutritionPlan {
    /** The plan passed the gate and may reach the user. */
    data class Ok(val plan: NutritionPlan) : GeneratedNutritionPlan

    /**
     * The gate blocked the plan; nothing is surfaced. Carries the block reasons +
     * rule ids for transparency/audit (logged server-side, never rendered to the
     * user as guidance).
     */
    data class Blocked(val reasons: List<String>, val ruleIds: List<String>) : GeneratedNutritionPlan
}
