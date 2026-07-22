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
}
