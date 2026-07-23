package dreamteam.domain.nutrition

import dreamteam.domain.EvidenceId
import dreamteam.domain.EvidenceLinked
import dreamteam.domain.NutritionPlanId
import dreamteam.domain.UserId
import kotlinx.serialization.Serializable

/**
 * Daily nutrition target for a recomposition plan. Mirrors
 * data/derived_metrics.json -> `derived`. Estimates for planning *support*, not
 * a prescription or diagnosis.
 *
 * [evidenceRefs] carries the strength-rated source (e.g. energy_estimation /
 * nutrition entries from the evidence catalog) — no target ships without one.
 */
@Serializable
data class NutritionTarget(
    val userId: UserId,
    val targetKcal: Int,
    val proteinG: Int,
    val fatG: Int,
    val carbohydrateG: Int,
    override val evidenceRefs: List<EvidenceId>,
    val recordedOn: String,
) : EvidenceLinked

/**
 * The recomposition *goal* a nutrition plan is built for. Drives the deterministic
 * energy target (maintenance vs a conservative deficit). A *support* framing —
 * not a medical outcome claim; the [CalibrationRule] still governs trend-based
 * adjustment, never a single weigh-in.
 */
@Serializable
enum class NutritionGoal { RECOMP, MAINTAIN, FAT_LOSS }

/**
 * One meal slot's deterministic share of the daily target. Macros are rounded
 * from the day's totals by a fixed distribution fraction; evidence lives at the
 * [NutritionTarget] / [NutritionPlan] level, not per slot.
 */
@Serializable
data class Meal(
    val slot: String,
    val label: String,
    val fraction: Double,
    val targetKcal: Int,
    val proteinG: Int,
    val fatG: Int,
    val carbohydrateG: Int,
)

/**
 * A user's nutrition plan: a daily macro [target] projected into a deterministic
 * meal [structure]. Mirrors the M4 milestone spec (M4-A): derived from body
 * metrics + [goal] via deterministic, evidence-linked math — no LLM, no I/O.
 *
 * A plan is *support*, never a diagnosis or prescription. As with the training
 * plan, a nutrition plan is surfaced through the same deterministic generator
 * that runs behind the [dreamteam.domain.safety.SafetyGuardedGateway]; nutrition
 * advice never overrides a medical/allergy contraindication.
 *
 * [id] is a deterministic, versioned plan id ("{userId}@{recordedOn}") so prior
 * versions are retained for audit/rollback, mirroring the training-plan history
 * (M4-B will persist + version this alongside [dreamteam.domain.training.TrainingPlan]).
 */
@Serializable
data class NutritionPlan(
    val id: NutritionPlanId,
    val userId: UserId,
    val goal: NutritionGoal,
    val target: NutritionTarget,
    val structure: List<Meal>,
    override val evidenceRefs: List<EvidenceId>,
    val createdAt: String,
) : EvidenceLinked

/**
 * Conservative weekly adjustment heuristic. Mirrors
 * data/derived_metrics.json -> `calibration_rule`. Adjustments act on multi-day
 * *trends*, never a single weigh-in, and are framed as support, not a diagnosis.
 */
@Serializable
data class CalibrationRule(
    val minimumDaysBeforeAdjustment: Int,
    val targetLossFractionPerWeek: List<Double>,
    val lowLossThresholdFractionPerWeek: Double,
    val highLossThresholdFractionPerWeek: Double,
    val minimumAdherence: Double,
    val adjustmentKcalTooSlow: Int,
    val adjustmentKcalTooFastOrRecoveryDeclines: Int,
)
