package dreamteam.domain.user

import dreamteam.domain.UserId
import dreamteam.domain.profile.Anthropometrics
import kotlinx.serialization.Serializable

@Serializable
data class UserPreferences(
    val strengthSessionsPerWeek: Int,
    val motorControlSessionsPerWeek: Int,
    val sessionMinutes: Int,
    val primaryStyle: String? = null,
)

/**
 * User-reported medical context that drives the safety gate. These are *support*
 * inputs, never a diagnosis: [condition] is the user's words, [currentCobbAnglesDeg]
 * is null until current imaging + a clinician supplies it.
 *
 * [currentCurveDataAvailable] / [clinicianCurveSpecificPlanAvailable] are the
 * toggles behind the side-specific lock (ADR 0001 §3): without them, directional
 * / curve-specific guidance is blocked, not warned.
 */
@Serializable
data class MedicalContext(
    val condition: String? = null,
    val scoliosisReported: Boolean = false,
    val currentCurveDataAvailable: Boolean = false,
    val currentCobbAnglesDeg: List<Double>? = null,
    val curveClassification: String? = null,
    val clinicianCurveSpecificPlanAvailable: Boolean = false,
    val dominantSymptomPattern: String? = null,
    val redFlagsPresent: Boolean = false,
    val safetyGateStatus: String? = null,
)

/**
 * The app user. Aggregates the inputs the engine needs: body data ([anthropometrics]),
 * available [equipment], scheduling [preferences], and the [medicalContext] that
 * drives the safety gate.
 */
@Serializable
data class User(
    val id: UserId,
    val anthropometrics: Anthropometrics,
    val equipment: List<String>,
    val preferences: UserPreferences,
    val medicalContext: MedicalContext,
    val occupation: String? = null,
)
