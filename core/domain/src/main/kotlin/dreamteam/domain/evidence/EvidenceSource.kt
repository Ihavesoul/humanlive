package dreamteam.domain.evidence

import dreamteam.domain.EvidenceId
import kotlinx.serialization.Serializable

/**
 * A traceable evidence source. Every [dreamteam.domain.ExerciseLinked] entity
 * references one or more of these by [id].
 *
 * Fields mirror data/evidence_catalog.json verbatim. The model never invents
 * citations: ids resolve to entries maintained by the Evidence & Research
 * Analyst, served from a backend allowlist only (ADR 0001 non-negotiable #2: the
 * model never emits DOI/PMID/URL; citations come from the allowlist).
 *
 * [evidenceLevel] is a controlled-vocabulary string — NOT an appraisal of study
 * quality. Values present today: `high`, `moderate_high`, `moderate`,
 * `moderate_context`, `low_moderate`, `low`, `guideline`, `validated_equation`.
 * Downstream may order it; this type does not vet studies.
 */
@Serializable
data class EvidenceSource(
    val id: EvidenceId,
    val domain: String,
    val citation: String,
    val design: String,
    val keyFinding: String,
    val application: String,
    val limitations: String,
    val pmid: String? = null,
    val doi: String? = null,
    val url: String? = null,
    val evidenceLevel: String,
)
