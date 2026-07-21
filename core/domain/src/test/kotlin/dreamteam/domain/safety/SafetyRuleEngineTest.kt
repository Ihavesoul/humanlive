package dreamteam.domain.safety

import dreamteam.domain.EvidenceId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * DRE-6 done-when: "a SafetyRule that can block a recommendation." Pins the
 * rule engine: matching rules block with no override path; no match allows.
 *
 * The rules here are *mechanism* samples, not clinical rules — which flags/ids
 * actually block is the Safety Reviewer's call, wired in DRE-7.
 */
class SafetyRuleEngineTest {

    private fun redFlagRule(flag: RedFlag) = SafetyRule(
        id = "block_on_$flag",
        description = "Block on $flag; escalate.",
        trigger = RuleTrigger.RedFlagPresent(flag),
        decision = SafetyRule.Decision.BLOCK,
        evidenceRefs = listOf("SAFETY-RED-FLAGS"),
    )

    @Test
    fun `a red-flag rule blocks the recommendation`() {
        val rule = redFlagRule(RedFlag.NIGHT_PAIN)
        val rec = Recommendation("split_squat", listOf("ACSM-RT-2026"))
        val ctx = ScreeningContext(redFlags = setOf(RedFlag.NIGHT_PAIN))

        val verdict = SafetyRuleEngine.evaluate(rec, ctx, listOf(rule))

        verdict shouldBe SafetyVerdict.Block(reason = rule.description, ruleIds = listOf(rule.id))
    }

    @Test
    fun `no matching rule allows`() {
        val rec = Recommendation("split_squat", listOf("ACSM-RT-2026"))
        val ctx = ScreeningContext()

        val verdict = SafetyRuleEngine.evaluate(rec, ctx, listOf(redFlagRule(RedFlag.FEVER)))

        verdict shouldBe SafetyVerdict.Allow
    }

    @Test
    fun `an exercise outside the allowlist is blocked`() {
        val rule = SafetyRule(
            id = "exercise_allowlist",
            description = "Only allowlisted exercises reach the user.",
            trigger = RuleTrigger.ExerciseNotInAllowlist("goblet_squat"),
            decision = SafetyRule.Decision.BLOCK,
            evidenceRefs = listOf("SAFETY-RED-FLAGS"),
        )
        val rec = Recommendation("goblet_squat", listOf("ACSM-RT-2026"))
        val ctx = ScreeningContext(allowedExerciseIds = setOf("split_squat")) // goblet not listed

        val verdict = SafetyRuleEngine.evaluate(rec, ctx, listOf(rule))

        verdict shouldBe SafetyVerdict.Block(rule.description, listOf(rule.id))
    }

    @Test
    fun `a recommendation with unsourced evidence is blocked`() {
        val unsourced: EvidenceId = "GHOST-STUDY"
        val rule = SafetyRule(
            id = "evidence_allowlist",
            description = "Evidence must come from the server allowlist.",
            trigger = RuleTrigger.EvidenceNotInAllowlist(unsourced),
            decision = SafetyRule.Decision.BLOCK,
            evidenceRefs = listOf("SAFETY-RED-FLAGS"),
        )
        val rec = Recommendation("split_squat", listOf(unsourced))
        val ctx = ScreeningContext(allowedEvidenceIds = setOf("ACSM-RT-2026"))

        val verdict = SafetyRuleEngine.evaluate(rec, ctx, listOf(rule))

        verdict shouldBe SafetyVerdict.Block(rule.description, listOf(rule.id))
    }

    @Test
    fun `side-specific lock blocks when engaged`() {
        val rule = SafetyRule(
            id = "side_specific_lock",
            description = "No directional/curve-specific guidance without current imaging.",
            trigger = RuleTrigger.SideSpecificLockEngaged("no current Cobb angles"),
            decision = SafetyRule.Decision.BLOCK,
            evidenceRefs = listOf("SPINE-IMAGING"),
        )
        val rec = Recommendation("schroth_rotational_breathing", listOf("SOSORT-GUIDELINES-2016"))
        val ctx = ScreeningContext(sideSpecificLockEngaged = true)

        val verdict = SafetyRuleEngine.evaluate(rec, ctx, listOf(rule))

        verdict shouldBe SafetyVerdict.Block(rule.description, listOf(rule.id))
    }
}
