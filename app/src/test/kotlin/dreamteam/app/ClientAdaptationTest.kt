package dreamteam.app

import dreamteam.app.data.SymptomEntry
import dreamteam.domain.adaptation.AdaptationSignal
import dreamteam.domain.adaptation.DeLoadTrigger
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * M3-C ([DRE-52](/DRE/issues/DRE-52)) — the one runnable check that the
 * client→domain wiring closes the loop on symptoms **offline-first**: free-text
 * symptom rows ([SymptomEntry]) flow through [clientSymptoms] →
 * [deriveAdaptationSignal] into a de-load-only [AdaptationSignal]. The signal
 * derivation itself is pinned in `:core:domain`
 * ([AdaptationSignalTest](../../../../../core/domain/src/test/kotlin/dreamteam/domain/adaptation/AdaptationSignalTest.kt));
 * this test pins the **client side**: the tokenization + the `progress = empty`
 * call shape that the app actually makes.
 *
 * Acceptance (DRE-52 §1): ≥2 entries where the latest introduces a token absent
 * from prior entries ⇒ DeLoad in `[SCALE_FLOOR, 1.0)`; 0/1 entries ⇒ None. No
 * diagnosis is produced anywhere — the signal is a volume modifier only.
 */
class ClientAdaptationTest {

    private fun entry(on: String, text: String) = SymptomEntry(recordedOn = on, text = text)

    @Test
    fun `two entries with a new token in the latest escalate to a moderate de-load`() {
        // Prior: "lumbar tension"; latest adds NEW token "sharp" (and "pain").
        val signal = localAdaptationSignal(
            listOf(
                entry("2026-07-01", "lumbar tension"),
                entry("2026-07-08", "lumbar tension, sharp pain"),
            ),
        )
        signal.shouldBeInstanceOf<AdaptationSignal.DeLoad>()
        signal.trigger shouldBe DeLoadTrigger.SymptomEscalation
        signal.volumeScale shouldBe AdaptationSignal.SCALE_MODERATE
        // De-load invariant: scale is always inside [SCALE_FLOOR, 1.0).
        (signal.volumeScale >= AdaptationSignal.SCALE_FLOOR) shouldBe true
        (signal.volumeScale < 1.0) shouldBe true
    }

    @Test
    fun `stable symptom text across entries does not escalate`() {
        // Same words in both entries ⇒ no new token ⇒ None.
        localAdaptationSignal(
            listOf(
                entry("2026-07-01", "lumbar tension"),
                entry("2026-07-08", "tension lumbar"), // same tokens, reordered
            ),
        ) shouldBe AdaptationSignal.None
    }

    @Test
    fun `a single symptom entry cannot establish an escalation`() {
        localAdaptationSignal(listOf(entry("2026-07-01", "lumbar tension"))) shouldBe AdaptationSignal.None
    }

    @Test
    fun `no symptom entries produce no signal`() {
        localAdaptationSignal(emptyList()) shouldBe AdaptationSignal.None
    }

    @Test
    fun `tokenization splits on whitespace and commas and lowercases`() {
        // Guards the client-side tokenization contract the escalation test above
        // relies on: "Sharp, PAIN" and "sharp pain" must normalize to the same set.
        val a = clientSymptoms(listOf(entry("2026-07-08", "Sharp, PAIN")))[0].currentSymptoms
        val b = clientSymptoms(listOf(entry("2026-07-08", "sharp pain")))[0].currentSymptoms
        a.toSet() shouldBe b.toSet()
        a.toSet() shouldBe setOf("sharp", "pain")
    }
}
