package dreamteam.server.plan

import dreamteam.domain.safety.ContraindicationStubs
import dreamteam.domain.safety.Recommendation
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.SurfacedPlan
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * DRE-17: the deterministic fallback plan is produced **only** via
 * [SafetyGuardedGateway.surface]. These pin the gateway integration that
 * `/v1/plans/generate` depends on:
 *  - an allowlist-provisioned gateway surfaces the **full** baseline (nothing
 *    silently dropped), and
 *  - a contraindicated candidate blocks the **whole** plan (all-or-nothing) so
 *    no unsafe guidance and no partial plan reaches the user.
 *
 * No clinical rule is authored: the contraindication here is a mechanism sample
 * (an activated [ContraindicationStubs] slot); activation in production is the
 * Safety Reviewer's (DRE-10).
 */
class BaselinePlanGatewayTest {

    @Test
    fun `an allowlist-provisioned gateway surfaces the full baseline plan`() {
        val candidates = BaselinePlan.candidates()
        candidates.shouldNotBeEmpty() // sanity: the embedded baseline loaded

        val gateway = SafetyGuardedGateway(
            ScreeningContext(
                allowedExerciseIds = BaselinePlan.allowedExerciseIds,
                allowedEvidenceIds = BaselinePlan.allowedEvidenceIds,
            ),
            BaselinePlan.activeAllowlistRules(),
        )
        gateway.isProvisioned shouldBe true

        val plan = gateway.surface(candidates)

        plan.shouldBeInstanceOf<SurfacedPlan.Ok>()
        // The whole vetted baseline surfaces — nothing dropped, all-or-nothing allow.
        plan.surfaced.map { it.exerciseId }.toSet() shouldBe
            candidates.map { it.exerciseId }.toSet()
        // Invariant #2: no surfaced recommendation without resolvable evidence.
        plan.surfaced.all { it.evidenceRefs.isNotEmpty() } shouldBe true
    }

    @Test
    fun `an unprovisioned gateway blocks the whole baseline plan`() {
        // Block-by-default (DRE-7): no ACTIVE rule => nothing surfaces, even for
        // the safe baseline. This is why the endpoint must provision the gateway.
        val gateway = SafetyGuardedGateway(ScreeningContext(), rules = emptyList())
        gateway.isProvisioned shouldBe false

        val plan = gateway.surface(BaselinePlan.candidates())

        plan.shouldBeInstanceOf<SurfacedPlan.Blocked>()
        plan.surfaced.shouldBeEmpty()
    }

    @Test
    fun `the production-registered ruleset blocks a contraindicated candidate end to end`() {
        // DRE-35: BaselinePlan.generate() now registers the ACTIVE contraindication
        // rules (activeAllowlistRules() + ContraindicationStubs.all), so a
        // flagged-scoliosis request proposing a heavy_axial_loading movement is
        // BLOCKED — not surfaced. The baseline itself carries no tagged movement;
        // appending one contraindicated candidate must block the ENTIRE plan
        // (all-or-nothing, no partial surfacing). The rule is used as-authored —
        // no .copy — because it is natively ACTIVE + sourced (DRE-10/DRE-20).
        val contraindicated = Recommendation(
            exerciseId = "back_squat",
            evidenceRefs = listOf("ACSM-RT-2026"),
            exerciseTags = setOf("heavy_axial_loading"),
        )
        // The exact production ruleset BaselinePlan.generate() registers today.
        val productionRules = BaselinePlan.activeAllowlistRules() + ContraindicationStubs.all
        val gateway = SafetyGuardedGateway(
            ScreeningContext(
                // back_squat is allowlisted + its evidence resolves, so the ONLY
                // blocker is the contraindication — isolating the mechanism.
                allowedExerciseIds = BaselinePlan.allowedExerciseIds + "back_squat",
                allowedEvidenceIds = BaselinePlan.allowedEvidenceIds,
                conditionFlags = setOf("scoliosis_flagged"),
            ),
            productionRules,
        )

        val plan = gateway.surface(BaselinePlan.candidates() + contraindicated)

        plan.shouldBeInstanceOf<SurfacedPlan.Blocked>()
        plan.surfaced.shouldBeEmpty() // even the safe baseline is NOT partially surfaced
        plan.items.map { it.recommendation.exerciseId } shouldContain "back_squat"
        plan.items[0].verdict.ruleIds shouldBe listOf("stub_heavy_axial_loading_scoliosis")
    }

    @Test
    fun `the production-registered ruleset also blocks loaded_flexion_rotation for flagged scoliosis`() {
        // DRE-24 coverage at the boundary: the second ACTIVE contraindication rule
        // is registered too, so a loaded_flexion_rotation movement is blocked for
        // a flagged user.
        val contraindicated = Recommendation(
            exerciseId = "cable_woodchop",
            evidenceRefs = listOf("ACSM-RT-2026"),
            exerciseTags = setOf("loaded_flexion_rotation"),
        )
        val gateway = SafetyGuardedGateway(
            ScreeningContext(
                allowedExerciseIds = BaselinePlan.allowedExerciseIds + "cable_woodchop",
                allowedEvidenceIds = BaselinePlan.allowedEvidenceIds,
                conditionFlags = setOf("scoliosis_flagged"),
            ),
            BaselinePlan.activeAllowlistRules() + ContraindicationStubs.all,
        )

        val plan = gateway.surface(listOf(contraindicated))

        plan.shouldBeInstanceOf<SurfacedPlan.Blocked>()
        plan.surfaced.shouldBeEmpty()
        plan.items[0].verdict.ruleIds shouldBe listOf("stub_loaded_flexion_rotation_scoliosis")
    }

    @Test
    fun `the contraindication rules do not over-reach an unflagged generic user`() {
        // DRE-10/DRE-24 threshold specificity at the boundary: with
        // scoliosis_flagged absent, the same contraindicated movement surfaces (the
        // rule is not a blanket ban). Proves the production registration blocks
        // ONLY flagged users and leaves the generic baseline intact.
        val movement = Recommendation(
            exerciseId = "back_squat",
            evidenceRefs = listOf("ACSM-RT-2026"),
            exerciseTags = setOf("heavy_axial_loading"),
        )
        val gateway = SafetyGuardedGateway(
            ScreeningContext(
                allowedExerciseIds = BaselinePlan.allowedExerciseIds + "back_squat",
                allowedEvidenceIds = BaselinePlan.allowedEvidenceIds,
                conditionFlags = emptySet(), // not flagged
            ),
            BaselinePlan.activeAllowlistRules() + ContraindicationStubs.all,
        )

        val plan = gateway.surface(listOf(movement))

        plan.shouldBeInstanceOf<SurfacedPlan.Ok>()
        plan.surfaced shouldBe listOf(movement)
    }
}
