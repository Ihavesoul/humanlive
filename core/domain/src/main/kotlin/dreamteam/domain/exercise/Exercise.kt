package dreamteam.domain.exercise

import dreamteam.domain.EvidenceId
import dreamteam.domain.EvidenceLinked
import dreamteam.domain.ExerciseId
import kotlinx.serialization.Serializable

/**
 * A movement in the exercise library. Mirrors data/exercises.json.
 *
 * `category` and `equipment` are controlled-vocabulary strings (see
 * data/exercises.json) rather than enums: the catalog grows, and a brittle enum
 * would break deserialization on a new entry. Downstream may parse the strings.
 *
 * [evidenceRefs] is the evidence linkage required by DRE-6 — no Exercise reaches
 * a plan without at least one resolvable source. An empty list means "blocked
 * until sourced", not "safe to ship".
 */
@Serializable
data class Exercise(
    val id: ExerciseId,
    val name: String,
    val category: String,
    val equipment: String,
    val defaultSets: Int,
    val repScheme: String,
    val rir: Int? = null,
    val instructions: String,
    val progression: String,
    val regression: String,
    val scoliosisRule: String,
    override val evidenceRefs: List<EvidenceId>,
    /**
     * Movement-set tags a contraindication rule may match on (mirrors
     * `movement_tags` in data/exercises.json; e.g. "heavy_axial_loading"). Empty
     * by default; flows into
     * [dreamteam.domain.safety.Recommendation.exerciseTags] at plan generation.
     *
     * M8-B ([DRE-86](/DRE/issues/DRE-86)): the optional media fields below carry
     * the references-card content (video / how-to / images) for M8-A2 (UI) and
     * M8-C (coach). They are sourced from the data allowlist (the Evidence &
     * Research Analyst populates `data/exercises.json`), NEVER LLM-generated
     * (ADR 0001 #2 — citations/media resolve only from the catalog). They default
     * empty so the library loads before population; empty = "not yet sourced",
     * never "invent a link" (a surfaced ref must still resolve, EvidenceLinked).
     */
    val movementTags: Set<String> = emptySet(),
    /** Single public video reference (`video_url`); null until the catalog carries one. */
    val videoUrl: String? = null,
    /** Ordered Russian how-to steps (`how_to_steps_ru[]`); empty until populated. */
    val howToStepsRu: List<String> = emptyList(),
    /** Image/schematic references (`image_refs[]`); empty until populated. */
    val imageRefs: List<String> = emptyList(),
) : EvidenceLinked
