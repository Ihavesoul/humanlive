package dreamteam.domain.training

import dreamteam.domain.safety.ContraindicationStubs
import dreamteam.domain.safety.Recommendation
import dreamteam.domain.safety.RuleTrigger
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.SafetyRule
import dreamteam.domain.safety.SafetyVerdict
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.StructuralSafetyRules
import dreamteam.domain.adaptation.AdaptationSignal
import dreamteam.domain.adaptation.DeLoadTrigger
import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.symptom.Symptom
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

    // --- DRE-49 (M3-A): AdaptationSignal threads into the generator ----------

    /**
     * A DeLoad signal must reduce real training volume (working-set count) but
     * never below the program's own deload floor (2), and never raise it. The
     * lever is [BaselineProgram]'s per-week `setsMain` (Decision_Rules YELLOW:
     * "reduce sets 25–50%"); warm-ups keep their default sets and are untouched.
     */
    @Test
    fun `a de-load signal reduces main working sets on build weeks but never below the deload floor`() {
        val baseline = DeterministicPlanGenerator(provisionedGateway(baselineContext()))
            .generate(userId = "seed-user", createdAt = "2026-07-21")
        baseline.shouldBeInstanceOf<GeneratedPlan.Ok>()

        val deLoaded = DeterministicPlanGenerator(provisionedGateway(baselineContext()))
            .generate(
                userId = "seed-user",
                createdAt = "2026-07-21",
                adaptation = AdaptationSignal.DeLoad(
                    trigger = DeLoadTrigger.SymptomEscalation,
                    volumeScale = AdaptationSignal.SCALE_MODERATE,
                    reason = "test",
                ),
            )
        deLoaded.shouldBeInstanceOf<GeneratedPlan.Ok>()

        // Week 4 is a 3-set build week (baseline setsMain = 3). After a 0.75
        // de-load: 3 * 0.75 = 2.25 -> floor 2 -> coerceIn(2, 3) = 2. Real cut.
        val baselineBuildSets = baseline.plan.weeks.first { it.weekNumber == 4 }.setsMain
        val deLoadedBuildSets = deLoaded.plan.weeks.first { it.weekNumber == 4 }.setsMain
        baselineBuildSets shouldBe 3
        deLoadedBuildSets shouldBe 2

        // De-load-only invariant: every adapted week's sets <= its baseline.
        baseline.plan.weeks.forEach { base ->
            val adapted = deLoaded.plan.weeks.first { it.weekNumber == base.weekNumber }
            adapted.setsMain shouldBe (if (base.setsMain > 2) base.setsMain - 1 else base.setsMain)
            (adapted.setsMain <= base.setsMain) shouldBe true
            (adapted.setsMain >= 2) shouldBe true
        }
    }

    @Test
    fun `no adaptation signal yields the unchanged baseline`() {
        val none = DeterministicPlanGenerator(provisionedGateway(baselineContext()))
            .generate(userId = "seed-user", createdAt = "2026-07-21")
        none.shouldBeInstanceOf<GeneratedPlan.Ok>()
        none.plan.weeks shouldContainExactly BaselineProgram.baselineTrainingPlan("seed-user", createdAt = "2026-07-21").weeks
    }

    /**
     * The hard invariant: adaptation cannot bypass the gate. An exercise
     * outside the allowlist is still BLOCKED (nothing surfaced) even when a
     * DeLoad signal is present — the signal only changes volume, never
     * selection/evidence/tags, so the gateway vets identical candidates.
     */
    @Test
    fun `a de-load signal does not unblock an allowlist violation`() {
        val ctxWithHole = baselineContext().copy(
            allowedExerciseIds = BaselineProgram.exerciseIds - "split_squat",
        )
        val result = DeterministicPlanGenerator(provisionedGateway(ctxWithHole)).generate(
            userId = "seed-user",
            createdAt = "2026-07-21",
            adaptation = AdaptationSignal.DeLoad(
                trigger = DeLoadTrigger.RapidWeightLoss,
                volumeScale = AdaptationSignal.SCALE_STRONG,
                reason = "test",
            ),
        )
        result.shouldBeInstanceOf<GeneratedPlan.Blocked>()
        result.blockedExerciseIds.toSet() shouldContainExactly setOf("split_squat")
    }

    /**
     * A contraindicated movement (heavy_axial_loading, flagged scoliosis) is
     * still blocked end-to-end with a DeLoad active: adaptation is a pre-gate
     * volume modifier and never reaches [Recommendation], so the contraindication
     * rule fires exactly as without adaptation.
     */
    @Test
    fun `a contraindicated movement is still blocked end-to-end under a de-load`() {
        val flaggedCtx = baselineContext().copy(conditionFlags = setOf("scoliosis_flagged"))
        val gateway = SafetyGuardedGateway(flaggedCtx, StructuralSafetyRules.all + ContraindicationStubs.heavyAxialLoadingForFlaggedScoliosis)

        // The surfaced baseline (none of its movements carry heavy_axial) still
        // passes end-to-end under a de-load — no tagged movement is introduced.
        val ok = DeterministicPlanGenerator(gateway).generate(
            userId = "seed-user",
            createdAt = "2026-07-21",
            adaptation = AdaptationSignal.DeLoad(
                trigger = DeLoadTrigger.SymptomEscalation,
                volumeScale = AdaptationSignal.SCALE_MODERATE,
                reason = "test",
            ),
        )
        ok.shouldBeInstanceOf<GeneratedPlan.Ok>()

        // And a contraindicated candidate is still blocked in the same provisioned gateway.
        val verdict = gateway.vet(recFor("barbell_back_squat"))
        verdict.shouldBeInstanceOf<SafetyVerdict.Block>()
        verdict.ruleIds shouldContain "stub_heavy_axial_loading_scoliosis"
    }

    // --- DRE-51 (M3-B): weekly recalculation (recalculate) --------------------

    /**
     * The recalc reads a user's logged progress + symptoms and regenerates the
     * plan through [generate] + the gate, under a NEW versioned plan id. Pinned
     * behaviours: (a) a de-load-triggering log set reduces real volume vs the
     * baseline; (b) a no-signal log set reproduces baseline volume; (d) a
     * contraindicated movement is still blocked under a recalc; (e) same logs
     * => identical recalc (determinism). (c) history retention is covered at
     * the repository layer.
     */
    private fun progress(weight: Double, on: String) =
        ProgressEntry(id = "p-$on", userId = "seed-user", recordedOn = on, weightKg = weight)

    private fun symptom(on: String, current: List<String>) =
        Symptom(id = "s-$on", userId = "seed-user", recordedOn = on, source = "self-report", currentSymptoms = current)

    @Test
    fun `(a) a de-load-triggering log set reduces working-set volume vs the baseline`() {
        val generator = DeterministicPlanGenerator(provisionedGateway(baselineContext()))

        val baseline = generator.generate(userId = "seed-user", createdAt = "2026-07-21")
        baseline.shouldBeInstanceOf<GeneratedPlan.Ok>()

        // 80kg -> 78.4kg over 2 weeks = -1.0%/week => rapid weight loss => DeLoad.
        val recalc = generator.recalculate(
            userId = "seed-user",
            createdAt = "2026-07-28",
            progress = listOf(progress(80.0, "2026-07-14"), progress(78.4, "2026-07-28")),
            symptoms = emptyList(),
        )
        recalc.shouldBeInstanceOf<GeneratedPlan.Ok>()

        // Week 4 is a 3-set build week; after the de-load it drops to 2.
        val baselineWeek4 = baseline.plan.weeks.first { it.weekNumber == 4 }.setsMain
        val recalcWeek4 = recalc.plan.weeks.first { it.weekNumber == 4 }.setsMain
        baselineWeek4 shouldBe 3
        recalcWeek4 shouldBe 2
        (recalcWeek4 < baselineWeek4) shouldBe true

        // De-load-only invariant: no recalc week exceeds its baseline.
        baseline.plan.weeks.forEach { base ->
            val adapted = recalc.plan.weeks.first { it.weekNumber == base.weekNumber }
            (adapted.setsMain <= base.setsMain) shouldBe true
        }

        // Versioning: recalc mints a distinct id, never the baseline id.
        (recalc.plan.id != baseline.plan.id) shouldBe true
        recalc.plan.id shouldBe "seed-user@2026-07-28"
    }

    @Test
    fun `(b) a no-signal log set reproduces the baseline volume identically`() {
        val generator = DeterministicPlanGenerator(provisionedGateway(baselineContext()))

        val baseline = generator.generate(userId = "seed-user", createdAt = "2026-07-21")
        baseline.shouldBeInstanceOf<GeneratedPlan.Ok>()

        // Stable logs (slow loss, no symptom change) => AdaptationSignal.None =>
        // the recalc's working-set volume is byte-identical to the baseline.
        val recalc = generator.recalculate(
            userId = "seed-user",
            createdAt = "2026-07-28",
            progress = listOf(progress(80.0, "2026-07-14"), progress(79.8, "2026-07-28")),
            symptoms = listOf(
                symptom("2026-07-14", listOf("lumbar tension")),
                symptom("2026-07-28", listOf("lumbar tension")),
            ),
        )
        recalc.shouldBeInstanceOf<GeneratedPlan.Ok>()

        recalc.plan.weeks.map { it.setsMain } shouldContainExactly
            baseline.plan.weeks.map { it.setsMain }
    }

    @Test
    fun `(d) a contraindicated movement is still blocked end-to-end under a recalc`() {
        val flaggedCtx = baselineContext().copy(conditionFlags = setOf("scoliosis_flagged"))
        val gateway = SafetyGuardedGateway(flaggedCtx, StructuralSafetyRules.all + ContraindicationStubs.heavyAxialLoadingForFlaggedScoliosis)
        val generator = DeterministicPlanGenerator(gateway)

        // The recalc of the (tag-free) baseline still surfaces Ok under the
        // flagged gate: adaptation changes volume, never selection/tags.
        val recalc = generator.recalculate(
            userId = "seed-user",
            createdAt = "2026-07-28",
            progress = listOf(progress(80.0, "2026-07-14"), progress(78.4, "2026-07-28")),
            symptoms = emptyList(),
        )
        recalc.shouldBeInstanceOf<GeneratedPlan.Ok>()

        // ...and a contraindicated candidate is still blocked in the SAME
        // provisioned gateway — the gate is not bypassed by the recalc path.
        val verdict = gateway.vet(recFor("barbell_back_squat"))
        verdict.shouldBeInstanceOf<SafetyVerdict.Block>()
        verdict.ruleIds shouldContain "stub_heavy_axial_loading_scoliosis"
    }

    @Test
    fun `(e) same logs produce the identical recalc - determinism`() {
        val generator = DeterministicPlanGenerator(provisionedGateway(baselineContext()))
        val progress = listOf(progress(80.0, "2026-07-14"), progress(78.4, "2026-07-28"))
        val symptoms = listOf(symptom("2026-07-14", listOf("a")), symptom("2026-07-21", listOf("a", "b")))

        val first = generator.recalculate(userId = "seed-user", createdAt = "2026-07-28", progress = progress, symptoms = symptoms)
            .shouldBeInstanceOf<GeneratedPlan.Ok>()
        val second = generator.recalculate(userId = "seed-user", createdAt = "2026-07-28", progress = progress, symptoms = symptoms)
            .shouldBeInstanceOf<GeneratedPlan.Ok>()

        // Identical inputs (incl. createdAt => identical versioned id) => identical plan.
        first.plan shouldBe second.plan
        first.plan.id shouldBe "seed-user@2026-07-28"
    }
}
