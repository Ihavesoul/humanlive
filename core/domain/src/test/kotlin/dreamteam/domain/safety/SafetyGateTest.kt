package dreamteam.domain.safety

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the deterministic safety gate (SDD §2.4 / ADR 0001 invariants #1, #3).
 * If any of these break, a safety rule silently changed — that is a bug.
 */
class SafetyGateTest {
    private fun cleared() =
        MedicalSafety(
            scoliosisReported = true,
            redFlags = emptyList(),
            currentCurveDataAvailable = false,
            clinicianCurveSpecificPlanAvailable = false,
        )

    @Test
    fun `no red flags and no clinician data allows training but locks side-specific`() {
        val verdict = SafetyGate.evaluate(cleared())
        verdict.redFlagGatePassed shouldBe true
        verdict.allowTrainingGeneration shouldBe true
        // Side-specific stays locked without current clinician curve data.
        verdict.allowSideSpecificContent shouldBe false
        verdict.warnings.shouldContainCurveLocked()
    }

    @Test
    fun `any reported red flag blocks training generation and side-specific content`() {
        val flagged =
            cleared().copy(
                redFlags = listOf("new_bowel_or_bladder_dysfunction"),
                // Even with clinician data present, a red flag overrides everything.
                currentCurveDataAvailable = true,
                curveClassification = "Lenke 1AN",
                clinicianCurveSpecificPlanAvailable = true,
            )
        val verdict = SafetyGate.evaluate(flagged)
        verdict.redFlagGatePassed shouldBe false
        verdict.allowTrainingGeneration shouldBe false
        verdict.allowSideSpecificContent shouldBe false
        verdict.warnings.any { it.startsWith("Red flag reported") } shouldBe true
    }

    @Test
    fun `current clinician curve data unlocks side-specific content`() {
        val withCurve =
            cleared().copy(
                currentCurveDataAvailable = true,
                currentCobbAnglesDeg = listOf(28.0),
                curveClassification = "Lenke 1AN; right thoracic",
                clinicianCurveSpecificPlanAvailable = true,
            )
        val verdict = SafetyGate.evaluate(withCurve)
        verdict.redFlagGatePassed shouldBe true
        verdict.allowTrainingGeneration shouldBe true
        verdict.allowSideSpecificContent shouldBe true
        verdict.warnings.isEmpty() shouldBe true
    }

    @Test
    fun `historic curve data without a current clinician plan stays locked`() {
        // Old x-ray angles alone never unlock side-specific content (invariant #3).
        val historicOnly =
            cleared().copy(
                currentCurveDataAvailable = true,
                currentCobbAnglesDeg = listOf(28.0),
                curveClassification = "Lenke 1AN",
                clinicianCurveSpecificPlanAvailable = false,
            )
        val verdict = SafetyGate.evaluate(historicOnly)
        verdict.allowSideSpecificContent shouldBe false
        verdict.warnings.shouldContainCurveLocked()
    }

    private fun List<String>.shouldContainCurveLocked() {
        any { it.contains("Curve-specific correction remains locked") } shouldBe true
    }
}
