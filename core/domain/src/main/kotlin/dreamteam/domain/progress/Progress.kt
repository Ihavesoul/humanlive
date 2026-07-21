package dreamteam.domain.progress

import dreamteam.domain.ProgressId
import dreamteam.domain.UserId
import kotlinx.serialization.Serializable

/**
 * A single body-composition / performance measurement point. The product treats
 * the *trend* of these as the signal; a single point is noise (consumer BIA,
 * daily water shifts) — see data/derived_metrics.json calibration_rule.
 *
 * Fields mirror `derived_metrics.input` + selected `derived` estimates captured
 * at recording time. Consumer-BIA body-fat % is a rough trend, never diagnostic.
 */
@Serializable
data class ProgressEntry(
    val id: ProgressId,
    val userId: UserId,
    val recordedOn: String,
    val weightKg: Double,
    val bodyFatPercent: Double? = null,
    val waistCm: Double? = null,
    val restingHeartRate: Int? = null,
    /** Estimates derived at capture time, not diagnoses. */
    val bmrKcal: Int? = null,
    val maintenanceKcalLow: Int? = null,
    val maintenanceKcalHigh: Int? = null,
)
