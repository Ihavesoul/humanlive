package dreamteam.domain.training

import dreamteam.domain.safety.ContraindicationStubs
import dreamteam.domain.safety.Recommendation
import dreamteam.domain.safety.RuleTrigger
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.SafetyRule
import dreamteam.domain.safety.SafetyVerdict
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.StructuralSafetyRules
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * M2-A done-when #1: the deterministic baseline plan is produced **exclusively
 * through [SafetyGuardedGateway.surface]**, and the two binding behaviours are
 * pinned — (a) the baseline survives the gate end-to-end, (b) a blocked
 * candidate surfaces nothing. If either breaks, a safety-relevant path silently
 * changed: that is a bug.
 *
 * No clinical rule is authored here: provisioning uses the structural allowlist
 * rules the Founding Engineer owns ([StructuralSafetyRules]); the allowlists
 * come from the PoC baseline itself ([BaselineProgram]).
 */
class DeterministicPlanGeneratorTest {

    private fun provisionedGateway(
        context: ScreeningContext,
        rules: List<SafetyRule> = StructuralSafetyRules.all,
    ) = SafetyGuardedGateway(context, rules)

    private fun baselineContext() = ScreeningContext(
        allowedExerciseIds = BaselineProgram.exerciseIds,
        allowedEvidenceIds = BaselineProgram.evidenceIds,
    )

    @Test
    fun `baseline plan survives the gateway end to end`() {
        val gateway = provisionedGateway(baselineContext())
        gateway.isProvisioned shouldBe true

        val result = DeterministicPlanGenerator(gateway).generate(userId = "seed-user", createdAt = "2026-07-21")

        result.shouldBeInstanceOf<GeneratedPlan.Ok>()
        // The surfaced plan is the full 12-week baseline; nothing was dropped.
        result.plan.weeks shouldContainExactly BaselineProgram.baselineTrainingPlan("seed-user", createdAt = "2026-07-21").weeks
        result.nutrition.targetKcal shouldBe 2300
        result.nutrition.evidenceRefs shouldContainExactly listOf("MIFFLIN-1990", "ISSN-DIETS-2017", "MORTON-PROTEIN-2018")
    }

    @Test
    fun `an exercise outside the allowlist is blocked and nothing is surfaced`() {
        // A baseline exercise removed from the allowlist => that assignment is
        // blocked => the whole plan is blocked (all-or-nothing). The safe
        // exercises are NOT partially surfaced.
        val ctxWithHole = baselineContext().copy(
            allowedExerciseIds = BaselineProgram.exerciseIds - "split_squat",
        )
        val gateway = provisionedGateway(ctxWithHole)

        val result = DeterministicPlanGenerator(gateway).generate(userId = "seed-user", createdAt = "2026-07-21")

        result.shouldBeInstanceOf<GeneratedPlan.Blocked>()
        // split_squat recurs across all 12 weeks; what matters is that *only* it
        // was blocked and the safe exercises were never partially surfaced.
        result.blockedExerciseIds.toSet() shouldContainExactly setOf("split_squat")
        result.ruleIds shouldContainExactly listOf("structural_exercise_allowlist")
    }

    @Test
    fun `an unsourced recommendation is blocked even inside an otherwise-provisioned gateway`() {
        // Evidence-by-allowlist: a candidate citing an id not in the catalog is
        // rejected — the model never invents citations (ADR 0001 §1).
        val gateway = provisionedGateway(baselineContext())
        val surfaced = gateway.surface(listOf(Recommendation("split_squat", listOf("GHOST-STUDY"))))

        surfaced.shouldBeInstanceOf<dreamteam.domain.safety.SurfacedPlan.Blocked>()
        surfaced.surfaced shouldBe emptyList() // nothing reaches the user
    }

    // --- DRE-18: movement-set tags flow library -> Recommendation -> rule ------

    /**
     * Provisions the heavy_axial_loading contraindication rule. Now that the rule
     * is natively ACTIVE + sourced (DRE-10/DRE-20, WEINSTEIN-AIS-2008), this is
     * just the production ruleset — kept as a helper so the end-to-end plumbing
     * test (library tag -> Recommendation.exerciseTags via [toRecommendation]
     * -> rule fires -> blocked) reads clearly.
     */
    private fun heavyAxialRuleActive(): List<SafetyRule> =
        StructuralSafetyRules.all + ContraindicationStubs.heavyAxialLoadingForFlaggedScoliosis

    private fun recFor(exerciseId: String): Recommendation =
        toRecommendation(
            ExerciseAssignment(
                exerciseId = exerciseId,
                sets = BaselineProgram.exercises.getValue(exerciseId).defaultSets,
                repScheme = BaselineProgram.exercises.getValue(exerciseId).repScheme,
                rir = BaselineProgram.exercises.getValue(exerciseId).defaultRir,
                evidenceRefs = BaselineProgram.exercises.getValue(exerciseId).evidenceRefs,
            ),
        )

    @Test
    fun `a heavy_axial_loading exercise is blocked end-to-end for a flagged scoliosis context`() {
        val flaggedCtx = baselineContext().copy(conditionFlags = setOf("scoliosis_flagged"))
        val gateway = SafetyGuardedGateway(flaggedCtx, heavyAxialRuleActive())

        val rec = recFor("barbell_back_squat")
        // Tag flowed from the library record into the candidate recommendation.
        rec.exerciseTags shouldContain "heavy_axial_loading"

        val verdict = gateway.vet(rec)
        verdict.shouldBeInstanceOf<SafetyVerdict.Block>()
        verdict.ruleIds shouldContain "stub_heavy_axial_loading_scoliosis"
    }

    @Test
    fun `a heavy_axial_loading exercise is allowed for a generic context`() {
        // Same tagged exercise, no condition flag => the contraindication rule
        // does not match, the allowlists pass => Allow. The tag alone never
        // blocks; it blocks only in combination with the condition flag.
        val gateway = SafetyGuardedGateway(baselineContext(), heavyAxialRuleActive())

        val rec = recFor("overhead_barbell_press")
        rec.exerciseTags shouldContain "heavy_axial_loading"

        gateway.vet(rec) shouldBe SafetyVerdict.Allow
    }

    @Test
    fun `baseline light unilateral movements are NOT tagged heavy_axial_loading`() {
        // Safety Reviewer's exclusion (DRE-10): split squat, goblet squat,
        // push-up, single-arm row are light/unilateral and must stay untagged so
        // they remain available on the generic baseline.
        listOf("split_squat", "goblet_squat", "pushup", "one_arm_row_supported").forEach { id ->
            recFor(id).exerciseTags shouldNotContain "heavy_axial_loading"
        }
    }

    // --- DRE-39: loaded_flexion_rotation tag flows library -> Recommendation -> rule --

    /**
     * Provisions the loaded_flexion_rotation contraindication rule. ACTIVE +
     * sourced (DRE-25, MARSHALL-MCGILL-AXIAL-TORQUE-2010 + MARRAS-TRUNK-MOTION-
     * 1993). Kept as a helper so the end-to-end plumbing test (library tag ->
     * Recommendation.exerciseTags via [toRecommendation] -> rule fires ->
     * blocked) reads clearly. Before DRE-39 no library exercise carried this tag,
     * so the rule was registered-but-inert; these tests pin that it now fires.
     */
    private fun loadedFlexionRotationRuleActive(): List<SafetyRule> =
        StructuralSafetyRules.all + ContraindicationStubs.loadedFlexionRotationForFlaggedScoliosis

    @Test
    fun `a loaded_flexion_rotation exercise is blocked end-to-end for a flagged scoliosis context`() {
        val flaggedCtx = baselineContext().copy(conditionFlags = setOf("scoliosis_flagged"))
        val gateway = SafetyGuardedGateway(flaggedCtx, loadedFlexionRotationRuleActive())

        val rec = recFor("cable_woodchop")
        // Tag flowed from the library record into the candidate recommendation.
        rec.exerciseTags shouldContain "loaded_flexion_rotation"

        val verdict = gateway.vet(rec)
        verdict.shouldBeInstanceOf<SafetyVerdict.Block>()
        verdict.ruleIds shouldContain "stub_loaded_flexion_rotation_scoliosis"
    }

    @Test
    fun `a loaded_flexion_rotation exercise is allowed for a generic context`() {
        // Same tagged exercise, no condition flag => the contraindication rule
        // does not match, the allowlists pass => Allow. The tag alone never
        // blocks; it blocks only in combination with the condition flag.
        val gateway = SafetyGuardedGateway(baselineContext(), loadedFlexionRotationRuleActive())

        val rec = recFor("loaded_russian_twist")
        rec.exerciseTags shouldContain "loaded_flexion_rotation"

        gateway.vet(rec) shouldBe SafetyVerdict.Allow
    }

    @Test
    fun `baseline anti-rotation and symmetric movements are NOT tagged loaded_flexion_rotation`() {
        // Safety Reviewer's exclusion (DRE-25): bird_dog, dead_bug,
        // side_plank_equal, suitcase_hold_equal are symmetric / light /
        // unloaded anti-rotation or trunk-endurance work that resists rotation
        // rather than producing it under load — they must stay untagged so they
        // remain available on the generic baseline.
        listOf("bird_dog", "dead_bug", "side_plank_equal", "suitcase_hold_equal").forEach { id ->
            recFor(id).exerciseTags shouldNotContain "loaded_flexion_rotation"
        }
    }
}
