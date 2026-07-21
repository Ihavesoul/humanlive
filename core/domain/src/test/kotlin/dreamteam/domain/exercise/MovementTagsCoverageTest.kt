package dreamteam.domain.exercise

import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * DRE-41 — movement_tags coverage guard for the exercise library.
 *
 * WHY THIS EXISTS
 *
 * The ACTIVE contraindication rules
 * ([dreamteam.domain.safety.ContraindicationStubs.heavyAxialLoadingForFlaggedScoliosis]
 * and [dreamteam.domain.safety.ContraindicationStubs.loadedFlexionRotationForFlaggedScoliosis])
 * match *only* on a movement's `movement_tags` entry. A qualifying movement
 * (loaded Russian twist, cable/landmine woodchop, barbell back squat, ...) that
 * lands in the library **without** its tag silently slips the rule — the gate
 * stays open and unsafe guidance reaches the user with no warning. That is the
 * one realistic under-block path left now the rules are ACTIVE + sourced.
 *
 * This test makes that omission visible at build time by snapshotting the full
 * `{id -> movement_tags}` map of `data/exercises.json`. Any library edit — a new
 * movement (tagged *or* untagged), a removed movement, or a changed tag set —
 * breaks the snapshot and forces a conscious re-appraisal.
 *
 * WHAT THE GUARD DOES NOT DO
 *
 * It does not judge *whether* a movement qualifies as `loaded_flexion_rotation`
 * or `heavy_axial_loading`. That biomechanical appraisal is the Evidence &
 * Research Analyst's (DRE-39 appraisal: no current movement beyond the six
 * cataloged per tag qualifies). This guard is pure drift detection: it makes
 * silence impossible, it does not replace the human sign-off.
 *
 * APPRAISAL PROTOCOL (when this test fails)
 *
 * 1. You changed `data/exercises.json` — added a movement, dropped one, or
 *    edited a tag. Confirm the change is intentional.
 * 2. If the change adds/renames a movement that could be heavy axial loading or
 *    loaded flexion+rotation under substantial external load, route it to the
 *    Evidence & Research Analyst for appraisal BEFORE updating the snapshot —
 *    do not silently land it untagged.
 * 3. Update [EXPECTED_MOVEMENT_TAGS] below to match. That update is the recorded
 *    appraisal: the diff is reviewable, and a tagged movement appearing here
 *    without a matching appraisal note (DRE-39 document) is a review flag.
 */
class MovementTagsCoverageTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Minimal projection of a `data/exercises.json` entry — only what this guard needs. */
    @Serializable
    private data class MovementEntry(
        val id: String,
        @SerialName("movement_tags") val movementTags: List<String> = emptyList(),
    )

    /**
     * The frozen `{id -> movement_tags}` snapshot of `data/exercises.json`.
     *
     * Order mirrors the file: the 24 scoliosis-safe baseline movements carry no
     * contraindication tag; the two cataloged contraindication movement sets
     * follow, grouped by tag so the safety-critical coverage is visible at a
     * glance. Update this map only as the final step of the appraisal protocol
     * above (DRE-41).
     */
    private val expectedMovementTags: Map<String, Set<String>> = linkedMapOf(
        // --- scoliosis-safe baseline (no contraindication tag) ---
        "warm_breathing" to emptySet(),
        "wall_axial_elongation" to emptySet(),
        "quadruped_rockback" to emptySet(),
        "split_squat" to emptySet(),
        "goblet_squat" to emptySet(),
        "bulgarian_split_squat" to emptySet(),
        "reverse_lunge" to emptySet(),
        "b_stance_rdl" to emptySet(),
        "single_leg_rdl_supported" to emptySet(),
        "glute_bridge" to emptySet(),
        "pushup" to emptySet(),
        "db_floor_press" to emptySet(),
        "pike_pushup_optional" to emptySet(),
        "one_arm_row_supported" to emptySet(),
        "prone_ytw" to emptySet(),
        "reverse_fly" to emptySet(),
        "dead_bug" to emptySet(),
        "bird_dog" to emptySet(),
        "side_plank_equal" to emptySet(),
        "suitcase_hold_equal" to emptySet(),
        "wall_hip_abduction" to emptySet(),
        "wall_slide" to emptySet(),
        "brisk_walk" to emptySet(),
        "gentle_yoga_flow" to emptySet(),
        // --- heavy_axial_loading (DRE-18) — block for scoliosis_flagged (DRE-10) ---
        "barbell_back_squat" to setOf("heavy_axial_loading"),
        "barbell_front_squat" to setOf("heavy_axial_loading"),
        "overhead_barbell_press" to setOf("heavy_axial_loading"),
        "barbell_deadlift" to setOf("heavy_axial_loading"),
        "barbell_good_morning" to setOf("heavy_axial_loading"),
        "heavy_farmer_carry" to setOf("heavy_axial_loading"),
        // --- loaded_flexion_rotation (DRE-39) — block for scoliosis_flagged (DRE-25) ---
        "loaded_russian_twist" to setOf("loaded_flexion_rotation"),
        "cable_woodchop" to setOf("loaded_flexion_rotation"),
        "landmine_rotation" to setOf("loaded_flexion_rotation"),
        "bent_rotational_row" to setOf("loaded_flexion_rotation"),
        "heavy_rotational_carry" to setOf("loaded_flexion_rotation"),
        "loaded_good_morning_rotation" to setOf("loaded_flexion_rotation"),
    )

    @Test
    fun `library movement_tags match the frozen appraisal snapshot`() {
        val actual = loadMovementTags()

        // Full-map snapshot: any added/removed/retagged movement breaks here.
        // See the class KDoc appraisal protocol before updating the expected map.
        actual shouldBe expectedMovementTags
    }

    @Test
    fun `every contraindication-tagged movement resolves to a known contraindication tag`() {
        // Defense-in-depth: the only movement_tags values a contraindication rule
        // can match are the ones registered in ContraindicationStubs. A typo'd or
        // speculative tag (e.g. "heavy_axial" / "loaded_rotation") would never be
        // matched by any ACTIVE rule and is therefore silent dead weight — fail
        // loud so the tag aligns with a real rule.
        val registeredContraindicationTags = setOf("heavy_axial_loading", "loaded_flexion_rotation")
        val unknown = loadMovementTags().values.flatten().toSet() - registeredContraindicationTags
        unknown shouldBe emptySet()
    }

    private fun loadMovementTags(): Map<String, Set<String>> {
        val raw = javaClass.getResourceAsStream("/exercises.json")?.bufferedReader()?.use { it.readText() }
            ?: error("data/exercises.json not on the test classpath — check core/domain test resources (DRE-41).")
        return json.decodeFromString<List<MovementEntry>>(raw).associate { it.id to it.movementTags.toSet() }
    }
}
