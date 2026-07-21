package dreamteam.domain.safety

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * DRE-7 end-to-end proof: the [SafetyGuardedGateway] is the permanent chokepoint
 * between a candidate recommendation and anything that reaches the user, and it
 * blocks unsafe output even before any real rules exist. If any of these break,
 * the safety invariant silently changed — that is a bug.
 *
 * No clinical rule is authored here: contraindication coverage uses mechanism
 * samples (generic tag+flag matches) and the DRAFT [ContraindicationStubs].
 */
class SafetyGuardedGatewayTest {

    private fun allowlistRule(allowedExercises: Set<String>) = SafetyRule(
        id = "exercise_allowlist",
        description = "Only allowlisted exercises reach the user.",
        trigger = RuleTrigger.ExerciseNotInAllowlist("__not_allowlisted__"),
        decision = SafetyRule.Decision.BLOCK,
        evidenceRefs = listOf("SAFETY-ALLOWLIST"),
    )

    private fun ctx(allowedExercises: Set<String>, conditionFlags: Set<String> = emptySet()) =
        ScreeningContext(
            allowedExerciseIds = allowedExercises,
            conditionFlags = conditionFlags,
        )

    @Test
    fun `an unprovisioned gateway blocks every recommendation`() {
        // DRE-7 done-when: unsafe output cannot reach the user even before any
        // real rules exist. Empty active ruleset => nothing is ever surfaced.
        val gateway = SafetyGuardedGateway(ScreeningContext(), rules = emptyList())
        gateway.isProvisioned shouldBe false

        val plan = gateway.surface(listOf(Recommendation("split_squat", listOf("ACSM-RT-2026"))))

        plan.shouldBeInstanceOf<SurfacedPlan.Blocked>()
        plan.surfaced.shouldBeEmpty()
    }

    @Test
    fun `a recommendation outside the active allowlist is blocked and not surfaced`() {
        val gateway = SafetyGuardedGateway(
            ctx(allowedExercises = setOf("split_squat")),
            rules = listOf(allowlistRule(setOf("split_squat"))),
        )
        gateway.isProvisioned shouldBe true

        val plan = gateway.surface(listOf(Recommendation("back_squat", listOf("ACSM-RT-2026"))))

        plan.shouldBeInstanceOf<SurfacedPlan.Blocked>()
        plan.surfaced.shouldBeEmpty()
        plan.items shouldHaveSize 1
        plan.items[0].recommendation.exerciseId shouldBe "back_squat"
        plan.items[0].verdict.ruleIds shouldBe listOf("exercise_allowlist")
    }

    @Test
    fun `a fully-vetted plan surfaces every candidate`() {
        val gateway = SafetyGuardedGateway(
            ctx(allowedExercises = setOf("split_squat", "hip_hinge")),
            rules = listOf(allowlistRule(setOf("split_squat", "hip_hinge"))),
        )
        val candidates = listOf(
            Recommendation("split_squat", listOf("ACSM-RT-2026")),
            Recommendation("hip_hinge", listOf("ACSM-RT-2026")),
        )

        val plan = gateway.surface(candidates)

        plan.shouldBeInstanceOf<SurfacedPlan.Ok>()
        plan.surfaced shouldBe candidates
    }

    @Test
    fun `one blocked candidate blocks the whole plan - all or nothing`() {
        // A partial plan with a silently-dropped contraindicated exercise is a
        // footgun; surfacing is all-or-nothing.
        val gateway = SafetyGuardedGateway(
            ctx(allowedExercises = setOf("split_squat", "back_squat")),
            rules = listOf(allowlistRule(setOf("split_squat", "back_squat"))),
        )
        val candidates = listOf(
            Recommendation("split_squat", listOf("ACSM-RT-2026")),
            Recommendation("back_squat", listOf("ACSM-RT-2026"), exerciseTags = setOf("heavy_axial_loading")),
        )
        val contraRule = SafetyRule(
            id = "stub_heavy_axial_loading_scoliosis",
            description = "Block heavy axial loading for flagged scoliosis.",
            trigger = RuleTrigger.ContraindicationStub(
                exerciseTag = "heavy_axial_loading",
                conditionFlag = "scoliosis_flagged",
                clinicalQuestion = "(mechanism test)",
            ),
            decision = SafetyRule.Decision.BLOCK,
            status = RuleStatus.ACTIVE,
            evidenceRefs = listOf("SAFETY-STUB"),
        )
        val gatedGateway = SafetyGuardedGateway(
            ctx(allowedExercises = setOf("split_squat", "back_squat"), conditionFlags = setOf("scoliosis_flagged")),
            rules = listOf(allowlistRule(setOf("split_squat", "back_squat")), contraRule),
        )

        val plan = gatedGateway.surface(candidates)

        plan.shouldBeInstanceOf<SurfacedPlan.Blocked>()
        plan.surfaced.shouldBeEmpty() // the safe split_squat is NOT partially surfaced
        plan.items shouldHaveSize 1
        plan.items[0].recommendation.exerciseId shouldBe "back_squat"
    }

    @Test
    fun `draft contraindication stubs are inert and do not provision the gateway`() {
        // DRE-7 boundary: the stubs are documentation slots, never live medical
        // rules. A gateway provisioned only with DRAFT stubs stays unprovisioned
        // and blocks everything — they cannot accidentally unblock a candidate.
        val gateway = SafetyGuardedGateway(
            ctx(allowedExercises = setOf("back_squat"), conditionFlags = setOf("scoliosis_flagged")),
            rules = ContraindicationStubs.all, // all DRAFT
        )
        gateway.isProvisioned shouldBe false

        val unsafe = Recommendation(
            "back_squat",
            listOf("ACSM-RT-2026"),
            exerciseTags = setOf("heavy_axial_loading"),
        )
        val plan = gateway.surface(listOf(unsafe))

        plan.shouldBeInstanceOf<SurfacedPlan.Blocked>()
        plan.surfaced.shouldBeEmpty()
    }

    @Test
    fun `an active contraindication rule blocks the unsafe candidate end to end`() {
        // Mechanism sample (not a clinical rule): proves a contraindication rule,
        // once activated by the Safety Reviewer, blocks end-to-end through the
        // gateway. The tag/flag values here are generic strings.
        val activeRule = ContraindicationStubs.heavyAxialLoadingForFlaggedScoliosis
            .copy(status = RuleStatus.ACTIVE, evidenceRefs = listOf("SAFETY-SIGNED-OFF"))
        val gateway = SafetyGuardedGateway(
            ctx(allowedExercises = setOf("back_squat"), conditionFlags = setOf("scoliosis_flagged")),
            rules = listOf(allowlistRule(setOf("back_squat")), activeRule),
        )

        val unsafe = Recommendation(
            "back_squat",
            listOf("ACSM-RT-2026"),
            exerciseTags = setOf("heavy_axial_loading"),
        )
        val plan = gateway.surface(listOf(unsafe))

        plan.shouldBeInstanceOf<SurfacedPlan.Blocked>()
        plan.surfaced.shouldBeEmpty()
        plan.items[0].verdict.ruleIds shouldBe listOf("stub_heavy_axial_loading_scoliosis")
    }

    @Test
    fun `the axial-loading contraindication only blocks the flagged condition, not a generic user`() {
        // DRE-10 threshold specificity: the rule encodes "heavy axial loading is
        // blocked for a *flagged* scoliosis presentation", not a blanket ban. The
        // same movement with an unflagged screening context must NOT be blocked
        // by this rule — otherwise it over-reaches generic users. (Clinical
        // content is defined in ContraindicationStubs; activation is gated on
        // DRE-14 sourcing, so the test activates a copy with the same mechanism.)
        val activeRule = ContraindicationStubs.heavyAxialLoadingForFlaggedScoliosis
            .copy(status = RuleStatus.ACTIVE, evidenceRefs = listOf("SAFETY-SIGNED-OFF"))
        val gateway = SafetyGuardedGateway(
            // scoliosis_flagged NOT present -> rule must not fire.
            ctx(allowedExercises = setOf("back_squat"), conditionFlags = emptySet()),
            rules = listOf(allowlistRule(setOf("back_squat")), activeRule),
        )
        val movement = Recommendation(
            "back_squat",
            listOf("ACSM-RT-2026"),
            exerciseTags = setOf("heavy_axial_loading"),
        )

        val plan = gateway.surface(listOf(movement))

        plan.shouldBeInstanceOf<SurfacedPlan.Ok>()
        plan.surfaced shouldBe listOf(movement)
    }
}
