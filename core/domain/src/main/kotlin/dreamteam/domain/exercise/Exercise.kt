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
     */
    val movementTags: Set<String> = emptySet(),
) : EvidenceLinked
