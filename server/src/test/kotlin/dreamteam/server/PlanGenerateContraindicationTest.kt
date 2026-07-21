package dreamteam.server

import dreamteam.domain.safety.ContraindicationStubs
import dreamteam.domain.safety.Recommendation
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.StructuralSafetyRules
import dreamteam.domain.safety.SurfacedPlan
import dreamteam.domain.training.BaselineProgram
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * DRE-35 boundary proof: the `POST /v1/plans/generate` endpoint registers the
 * ACTIVE contraindication rules on its gateway, so a flagged-scoliosis request
 * proposing a `heavy_axial_loading` / `loaded_flexion_rotation` movement is
 * BLOCKED — never surfaced. The deterministic baseline itself carries no tagged
 * movement (so it surfaces unchanged), which is why this gap was invisible: the
 * rule could not fire because production never registered it.
 *
 * This test mirrors the exact gateway provisioning in
 * [Application.module] `/plans/generate`: the same
 * [ScreeningContext] (with `scoliosis_flagged` derived from
 * `scoliosisReported = true`) and the same production ruleset
 * (`StructuralSafetyRules.all + ContraindicationStubs.all`). If you change one,
 * update the other. The end-to-end HTTP contract (`scoliosisReported = true`
 * still returns the safe baseline) is pinned in [PlanGenerateRouteTest].
 */
class PlanGenerateContraindicationTest {

    /** The production ruleset the endpoint registers on its gateway today. */
    private val productionRules = StructuralSafetyRules.all + ContraindicationStubs.all

    private fun flaggedContext() = ScreeningContext(
        redFlags = emptySet(),
        sideSpecificLockEngaged = true,
        allowedExerciseIds = BaselineProgram.exerciseIds,
        allowedEvidenceIds = BaselineProgram.evidenceIds,
        clinicianCurveSpecificPlanAvailable = false,
        conditionFlags = setOf("scoliosis_flagged"),
    )

    @Test
    fun `a heavy_axial_loading movement is blocked for a flagged-scoliosis request`() {
        val gateway = SafetyGuardedGateway(flaggedContext(), productionRules)
        // barbell_back_squat is a cataloged library member (allowlisted) carrying
        // the heavy_axial_loading tag, so the ONLY blocker is the contraindication.
        val unsafe = Recommendation(
            exerciseId = "barbell_back_squat",
            evidenceRefs = listOf("ACSM-RT-2026", "LOPEZ-LOAD-2021"),
            exerciseTags = setOf("heavy_axial_loading"),
        )

        val plan = gateway.surface(listOf(unsafe))

        plan.shouldBeInstanceOf<SurfacedPlan.Blocked>()
        plan.surfaced.shouldBeEmpty()
        plan.items[0].verdict.ruleIds shouldBe listOf("stub_heavy_axial_loading_scoliosis")
    }

    @Test
    fun `a loaded_flexion_rotation movement is blocked for a flagged-scoliosis request`() {
        // cable_woodchop is a cataloged library member (allowlisted, DRE-39)
        // carrying the loaded_flexion_rotation tag, so the ONLY blocker is the
        // contraindication.
        val gateway = SafetyGuardedGateway(flaggedContext(), productionRules)
        val unsafe = Recommendation(
            exerciseId = "cable_woodchop",
            evidenceRefs = listOf("ACSM-RT-2026"),
            exerciseTags = setOf("loaded_flexion_rotation"),
        )

        val plan = gateway.surface(listOf(unsafe))

        plan.shouldBeInstanceOf<SurfacedPlan.Blocked>()
        plan.surfaced.shouldBeEmpty()
        plan.items[0].verdict.ruleIds shouldBe listOf("stub_loaded_flexion_rotation_scoliosis")
    }

    @Test
    fun `the contraindication rules do not over-reach an unflagged generic user`() {
        // Threshold specificity at the endpoint boundary: scoliosis_flagged absent
        // => the same tagged movement surfaces. The rule blocks ONLY flagged users.
        val ctx = flaggedContext().copy(conditionFlags = emptySet())
        val gateway = SafetyGuardedGateway(ctx, productionRules)
        val movement = Recommendation(
            exerciseId = "barbell_back_squat",
            evidenceRefs = listOf("ACSM-RT-2026", "LOPEZ-LOAD-2021"),
            exerciseTags = setOf("heavy_axial_loading"),
        )

        val plan = gateway.surface(listOf(movement))

        plan.shouldBeInstanceOf<SurfacedPlan.Ok>()
        plan.surfaced shouldBe listOf(movement)
    }
}
