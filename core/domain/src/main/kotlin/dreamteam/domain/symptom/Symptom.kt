package dreamteam.domain.symptom

import dreamteam.domain.SymptomId
import dreamteam.domain.UserId
import kotlinx.serialization.Serializable

/**
 * A user-recorded symptom / screening entry. Mirrors
 * data/safety_screening.json + profile.medical_context.current_symptoms.
 *
 * This is the user's self-report of how they feel — *input data*, not a
 * diagnosis. [interpretation] frames it as a support gate, never medical
 * clearance. Red-flag escalation is handled by the safety layer
 * ([dreamteam.domain.safety]); this type carries the raw self-report.
 */
@Serializable
data class Symptom(
    val id: SymptomId,
    val userId: UserId,
    val recordedOn: String,
    val source: String,
    /** Free-form or controlled-vocab symptom notes (e.g. "left lumbar tension"). */
    val currentSymptoms: List<String> = emptyList(),
    val context: String? = null,
    val interpretation: String? = null,
)
