package dreamteam.domain.nutrition

import dreamteam.domain.EvidenceId
import dreamteam.domain.EvidenceLinked
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
