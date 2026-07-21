package dreamteam.domain.training

import dreamteam.domain.safety.Recommendation
import dreamteam.domain.safety.RuleStatus
import dreamteam.domain.safety.RuleTrigger
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.SafetyRule
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.StructuralSafetyRules
import io.kotest.matchers.collections.shouldContainExactly
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
}
