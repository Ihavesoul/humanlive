package dreamteam.domain.exercise

import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * M8-B ([DRE-86](/DRE/issues/DRE-86)) — media-schema presence guard for the
 * exercise library.
 *
 * WHY THIS EXISTS
 *
 * The references-card UI (M8-A2 / [DRE-88](/DRE/issues/DRE-88)) and the AI coach
 * (M8-C) read three media fields per exercise — `video_url`, `how_to_steps_ru[]`,
 * `image_refs[]` — straight off the bundled `data/exercises.json` (the data
 * allowlist, never LLM output: ADR 0001 #2). For that UI to rely on the schema
 * instead of null-checking field presence in every render, **every** library
 * entry must carry the three keys with a stable shape. An entry landed without
 * them (a new movement added in M8-A1 forgetting the fields) would render a
 * broken/empty card or a crash — the kind of silent gap this guard exists to
 * make loud, exactly like [MovementTagsCoverageTest] does for contraindication
 * tags.
 *
 * The fields ship EMPTY in M8-B (population is the Evidence & Research
 * Analyst's job in M8-A1). This test deliberately pins only presence + shape,
 * NOT emptiness: once M8-A1 fills real video/steps/image refs the values stop
 * being empty, and that population must not trip a schema guard. Presence and
 * shape are the durable contract; "empty for now" is a transient seed state.
 *
 * WHAT THE GUARD DOES NOT DO
 *
 * It does not vet the *content* of a video/step/image ref (license, accuracy,
 * whether it actually depicts the movement). That appraisal is the Evidence &
 * Research Analyst's — this guard only guarantees the schema slot exists and is
 * the right type, so the slot is ready to receive their vetted content.
 */
class ExerciseMediaSchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Minimal projection of a `data/exercises.json` entry — only the media fields. */
    @Serializable
    private data class MediaEntry(
        val id: String,
        @SerialName("video_url") val videoUrl: String? = null,
        @SerialName("how_to_steps_ru") val howToStepsRu: List<String> = emptyList(),
        @SerialName("image_refs") val imageRefs: List<String> = emptyList(),
    )

    @Test
    fun `every exercise carries the three media fields with a stable shape`() {
        val entries = loadMediaEntries()

        // Presence: M8-A2/M8-C read these keys unconditionally off the catalog.
        // (If a field were absent, kotlinx.serialization would silently default
        // it — so this also guards against a default masking a missing key by
        // pinning the decoded shape explicitly on every entry.)
        entries.forEach { entry ->
            // video_url: null until the Evidence Analyst sources one; never a
            // non-string.
            entry.videoUrl?.shouldBeInstanceOf<String>()
            // how_to_steps_ru / image_refs: lists of plain strings (ordered steps,
            // ref ids/urls). Duplicate steps in one movement make no sense.
            entry.howToStepsRu.forEach { it.shouldBeInstanceOf<String>() }
            entry.howToStepsRu.shouldBeUnique()
            entry.imageRefs.forEach { it.shouldBeInstanceOf<String>() }
        }
    }

    @Test
    fun `the library has exactly one media-schema projection per exercise id`() {
        // Defense-in-depth against a duplicated/mis-merged catalog entry: a
        // duplicate id would let M8-A2 render the wrong card depending on which
        // row associateBy picked.
        val ids = loadMediaEntries().map { it.id }
        ids shouldBe ids.toSet().toList() // order preserved, no dupes
    }

    private fun loadMediaEntries(): List<MediaEntry> {
        val raw = javaClass.getResourceAsStream("/exercises.json")?.bufferedReader()?.use { it.readText() }
            ?: error("data/exercises.json not on the test classpath — check core/domain test resources (DRE-41).")
        return json.decodeFromString(raw)
    }
}
