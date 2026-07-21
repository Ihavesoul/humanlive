package dreamteam.server.plan

import dreamteam.domain.safety.ContraindicationStubs
import dreamteam.domain.safety.Recommendation
import dreamteam.domain.safety.RuleStatus
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
    fun `a contraindicated candidate blocks the whole plan and surfaces nothing`() {
        // The baseline is entirely safe; appending ONE contraindicated candidate
        // (heavy axial loading for flagged scoliosis) must block the ENTIRE plan.
        // Surfacing a partial plan that silently dropped the unsafe exercise would
        // be a footgun — surfacing is all-or-nothing.
        val contraindicated = Recommendation(
            exerciseId = "back_squat",
            evidenceRefs = listOf("ACSM-RT-2026"),
            exerciseTags = setOf("heavy_axial_loading"),
        )
        val activeContra = ContraindicationStubs.heavyAxialLoadingForFlaggedScoliosis
            .copy(status = RuleStatus.ACTIVE, evidenceRefs = listOf("WEINSTEIN-AIS-2008"))
        val gateway = SafetyGuardedGateway(
            ScreeningContext(
                // back_squat is allowlisted + its evidence resolves, so the ONLY
                // blocker is the contraindication — isolating the mechanism.
                allowedExerciseIds = BaselinePlan.allowedExerciseIds + "back_squat",
                allowedEvidenceIds = BaselinePlan.allowedEvidenceIds,
                conditionFlags = setOf("scoliosis_flagged"),
            ),
            BaselinePlan.activeAllowlistRules() + activeContra,
        )

        val plan = gateway.surface(BaselinePlan.candidates() + contraindicated)

        plan.shouldBeInstanceOf<SurfacedPlan.Blocked>()
        plan.surfaced.shouldBeEmpty() // even the safe baseline is NOT partially surfaced
        plan.items.map { it.recommendation.exerciseId } shouldContain "back_squat"
        plan.items[0].verdict.ruleIds shouldBe listOf("stub_heavy_axial_loading_scoliosis")
    }
}
