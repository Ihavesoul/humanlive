package dreamteam.domain.adaptation

import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.symptom.Symptom
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * M3-A ([DRE-49](/DRE/issues/DRE-49)) done-when: the [deriveAdaptationSignal]
 * pure function turns logged progress + symptoms into a de-load-only signal.
 *
 * Pinned behaviours:
 *  - a 2-point rapid weight-loss trend ⇒ DeLoad (Decision_Rules `r < -0.0075`);
 *  - a new symptom in the latest entry vs prior union ⇒ DeLoad;
 *  - the result is **never** a load increase — the type can't express it, and a
 *    stable/improving history yields [AdaptationSignal.None];
 *  - a single point cannot establish a trend/change ⇒ None (conservative).
 *
 * No clinical threshold is authored here: the rapid-loss threshold is quoted
 * verbatim from specs/Decision_Rules.md; symptom "escalation" is a pure
 * set-membership test (a string appearing that was absent before).
 */
class AdaptationSignalTest {

    private fun progress(
        weight: Double,
        on: String,
        id: String = "p-$on",
    ) = ProgressEntry(
        id = id,
        userId = "u",
        recordedOn = on,
        weightKg = weight,
    )

    private fun symptom(on: String, current: List<String>, id: String = "s-$on") =
        Symptom(id = id, userId = "u", recordedOn = on, source = "self-report", currentSymptoms = current)

    // --- progress trend -----------------------------------------------------

    @Test
    fun `two points spanning a rapid loss trend produce a DeLoad`() {
        // 80kg → 78.4kg over 2 weeks = -1.0%/week (< -0.0075) ⇒ rapid loss.
        val signal = deriveAdaptationSignal(
            progress = listOf(progress(80.0, "2026-07-01"), progress(78.4, "2026-07-15")),
            symptoms = emptyList(),
        )
        signal.shouldBeInstanceOf<AdaptationSignal.DeLoad>()
        signal.trigger shouldBe DeLoadTrigger.RapidWeightLoss
        signal.volumeScale shouldBe AdaptationSignal.SCALE_MODERATE
    }

    @Test
    fun `a slow loss trend produces no signal`() {
        // 80kg → 79.8kg over 2 weeks ≈ -0.125%/week (>= -0.0075) ⇒ stable.
        deriveAdaptationSignal(
            progress = listOf(progress(80.0, "2026-07-01"), progress(79.8, "2026-07-15")),
            symptoms = emptyList(),
        ) shouldBe AdaptationSignal.None
    }

    @Test
    fun `weight gain never triggers a de-load`() {
        // De-load is for recovery stress; gain is not a de-load signal (M3-A scope).
        deriveAdaptationSignal(
            progress = listOf(progress(80.0, "2026-07-01"), progress(82.0, "2026-07-15")),
            symptoms = emptyList(),
        ) shouldBe AdaptationSignal.None
    }

    @Test
    fun `two points less than a week apart produce no trend signal`() {
        // Need >=1 week span to call two points a weekly rate.
        deriveAdaptationSignal(
            progress = listOf(progress(80.0, "2026-07-01"), progress(78.0, "2026-07-03")),
            symptoms = emptyList(),
        ) shouldBe AdaptationSignal.None
    }

    @Test
    fun `fewer than two progress points produce no trend signal`() {
        deriveAdaptationSignal(progress = listOf(progress(80.0, "2026-07-01")), symptoms = emptyList()) shouldBe AdaptationSignal.None
    }

    // --- symptom escalation -------------------------------------------------

    @Test
    fun `a new symptom in the latest entry escalates to DeLoad`() {
        // prior: lumbar tension; latest: lumbar tension + NEW "sharp pain".
        val signal = deriveAdaptationSignal(
            progress = emptyList(),
            symptoms = listOf(
                symptom("2026-07-01", listOf("lumbar tension")),
                symptom("2026-07-08", listOf("lumbar tension", "sharp pain")),
            ),
        )
        signal.shouldBeInstanceOf<AdaptationSignal.DeLoad>()
        signal.trigger shouldBe DeLoadTrigger.SymptomEscalation
        signal.volumeScale shouldBe AdaptationSignal.SCALE_MODERATE
    }

    @Test
    fun `stable symptoms do not escalate`() {
        // Same symptom set across both entries ⇒ nothing new ⇒ GREEN.
        deriveAdaptationSignal(
            progress = emptyList(),
            symptoms = listOf(
                symptom("2026-07-01", listOf("lumbar tension")),
                symptom("2026-07-08", listOf("lumbar tension")),
            ),
        ) shouldBe AdaptationSignal.None
    }

    @Test
    fun `improving symptoms do not escalate`() {
        // Symptom dropped from the latest entry ⇒ not escalation.
        deriveAdaptationSignal(
            progress = emptyList(),
            symptoms = listOf(
                symptom("2026-07-01", listOf("lumbar tension", "sharp pain")),
                symptom("2026-07-08", listOf("lumbar tension")),
            ),
        ) shouldBe AdaptationSignal.None
    }

    @Test
    fun `a single symptom entry cannot establish escalation`() {
        // One entry ⇒ no prior to compare ⇒ cannot call it "new" ⇒ None.
        deriveAdaptationSignal(
            progress = emptyList(),
            symptoms = listOf(symptom("2026-07-01", listOf("lumbar tension"))),
        ) shouldBe AdaptationSignal.None
    }

    // --- combination & the never-increase invariant -------------------------

    @Test
    fun `both triggers produce a stronger de-load`() {
        val signal = deriveAdaptationSignal(
            progress = listOf(progress(80.0, "2026-07-01"), progress(78.0, "2026-07-15")),
            symptoms = listOf(
                symptom("2026-07-01", listOf("lumbar tension")),
                symptom("2026-07-08", listOf("lumbar tension", "sharp pain")),
            ),
        )
        signal.shouldBeInstanceOf<AdaptationSignal.DeLoad>()
        signal.volumeScale shouldBe AdaptationSignal.SCALE_STRONG
    }

    @Test
    fun `a de-load volume scale is always below one and at or above the floor`() {
        // The signal can only ever de-load or hold — it never asks for more load.
        // Drive every branch and assert the scale stays inside [SCALE_FLOOR, 1.0).
        val scales = listOf(
            deriveAdaptationSignal(
                listOf(progress(80.0, "2026-07-01"), progress(78.4, "2026-07-15")), emptyList(),
            ),
            deriveAdaptationSignal(
                emptyList(),
                listOf(symptom("2026-07-01", listOf("a")), symptom("2026-07-08", listOf("a", "b"))),
            ),
            deriveAdaptationSignal(
                listOf(progress(80.0, "2026-07-01"), progress(78.0, "2026-07-15")),
                listOf(symptom("2026-07-01", listOf("a")), symptom("2026-07-08", listOf("a", "b"))),
            ),
        ).mapNotNull { (it as? AdaptationSignal.DeLoad)?.volumeScale }

        scales shouldContainExactlyInAnyOrder listOf(
            AdaptationSignal.SCALE_MODERATE,
            AdaptationSignal.SCALE_MODERATE,
            AdaptationSignal.SCALE_STRONG,
        )
        scales.forEach {
            (it >= AdaptationSignal.SCALE_FLOOR) shouldBe true
            (it < 1.0) shouldBe true
        }
    }

    // --- M10-B ([DRE-186](/DRE/issues/DRE-186)): shared rate-formula regression pins ---

    @Test
    fun `weeklyWeightRate returns the per-week rate the trend view and the signal share`() {
        // Regression pin (M10-B): weeklyWeightRate is the ONE number both the
        // de-load signal (detectRapidWeightLoss) and the M5-C trend view read —
        // "one function, two callers". Pinning it in a tight band locks the
        // per-week formula so the rate on screen can never drift from the rate the
        // plan keys off. 80→78.4 kg over exactly 2 weeks = −1.0%/week.
        // ponytail: band, not exact equality — (78.4−80)/80/2 is not bit-identical
        // to the −0.01 literal in IEEE-754; the band still rejects the likely
        // formula regressions (no /weeks ⇒ −0.02, /days ⇒ −0.005, swapped ends ⇒ +).
        val rate = weeklyWeightRate(listOf(progress(80.0, "2026-07-01"), progress(78.4, "2026-07-15")))
        (rate != null && rate in -0.0105..-0.0095) shouldBe true
    }

    @Test
    fun `weeklyWeightRate returns null when a rate cannot be established`() {
        // Regression pin (M10-B): the null branches of the shared rate function —
        // fewer than 2 points, a non-positive start weight, a sub-week span, and
        // an unparseable date all yield null (⇒ no DeLoad), never a bogus rate
        // that could mis-fire the signal or render a garbage trend. The derive→
        // None path is partially covered above; this pins the rate function's own
        // null contract, which the trend view also relies on.
        weeklyWeightRate(listOf(progress(80.0, "2026-07-01"))) shouldBe null // single point
        weeklyWeightRate(listOf(progress(0.0, "2026-07-01"), progress(78.4, "2026-07-15"))) shouldBe null // non-positive start
        weeklyWeightRate(listOf(progress(80.0, "2026-07-01"), progress(78.4, "2026-07-05"))) shouldBe null // < 1 week span
        weeklyWeightRate(listOf(progress(80.0, "not-a-date"), progress(78.4, "2026-07-15"))) shouldBe null // unparseable date
    }
}
