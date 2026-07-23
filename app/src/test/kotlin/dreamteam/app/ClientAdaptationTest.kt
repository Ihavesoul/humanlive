package dreamteam.app

import dreamteam.app.data.ProgressRow
import dreamteam.app.data.SymptomEntry
import dreamteam.domain.adaptation.AdaptationSignal
import dreamteam.domain.adaptation.DeLoadTrigger
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * M3-C ([DRE-52](/DRE/issues/DRE-52)) + M5-A ([DRE-61](/DRE/issues/DRE-61)) — the
 * runnable checks that the client→domain wiring closes the loop on symptoms **and**
 * progress **offline-first**: free-text symptom rows ([SymptomEntry]) and body-weight
 * rows ([ProgressRow]) flow through [clientSymptoms]/[clientProgress] →
 * [dreamteam.domain.adaptation.deriveAdaptationSignal] into a de-load-only
 * [AdaptationSignal]. The signal derivation itself is pinned in `:core:domain`
 * ([AdaptationSignalTest](../../../../../core/domain/src/test/kotlin/dreamteam/domain/adaptation/AdaptationSignalTest.kt));
 * this test pins the **client side**: the tokenization, the progress bridge, and the
 * call shape the app actually makes (real progress list, not the former
 * `emptyList()` deferral).
 *
 * Acceptance (DRE-52 §1 / DRE-61): ≥2 symptom entries where the latest introduces
 * a token absent from prior entries ⇒ DeLoad; ≥2 weight points spanning ≥1 week
 * with a rapid-loss trend ⇒ DeLoad; 0/1 of either ⇒ None. No diagnosis is
 * produced anywhere — the signal is a volume modifier only.
 *
 * ponytail: the SQLite append/recent round-trip for [dreamteam.app.data.LocalDatabase]
 * is NOT unit-tested here — it mirrors the symptom-log precedent (device/Robolectric
 * level; the app's unit tests stay pure-Kotlin, no Android runtime). What IS pinned
 * is the pure bridge [clientProgress] that the logged row must survive to reach the
 * signal: date + weight are preserved verbatim, and the bridge output feeds a
 * derivable trend.
 */
class ClientAdaptationTest {

    private fun entry(on: String, text: String) = SymptomEntry(recordedOn = on, text = text)
    private fun pRow(on: String, kg: Double) = ProgressRow(recordedOn = on, weightKg = kg)

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

    // --- M5-A (DRE-61): progress → RapidWeightLoss --------------------------

    @Test
    fun `clientProgress bridges body-weight rows to ProgressEntry preserving date and weight`() {
        // The pure bridge is the round-trip a logged row must survive to reach the
        // signal: recordedOn + weightKg are preserved verbatim; ids are synthesized
        // and unique within the window; userId is the client-side "local".
        val bridged = clientProgress(listOf(pRow("2026-07-01", 80.0), pRow("2026-07-15", 78.4)))
        bridged.size shouldBe 2
        bridged.map { it.recordedOn } shouldBe listOf("2026-07-01", "2026-07-15")
        bridged.map { it.weightKg } shouldBe listOf(80.0, 78.4)
        bridged.map { it.id }.toSet().size shouldBe 2 // unique synthesized ids
        bridged.all { it.userId == "local" } shouldBe true
        // No optional clinical fields are populated by the MVP weight-only logger.
        bridged.all { it.bodyFatPercent == null && it.waistCm == null } shouldBe true
    }

    @Test
    fun `localAdaptationSignal now passes a non-empty progress list and derives a rapid-loss trend`() {
        // DRE-61 done-when: the deferral is gone. 80→78.4kg over 2 weeks =
        // -1.0%/week (< -0.0075) ⇒ RapidWeightLoss fires client-side, with no
        // symptom input at all. This proves progress is no longer emptyList().
        val signal = localAdaptationSignal(
            symptoms = emptyList(),
            progress = listOf(pRow("2026-07-01", 80.0), pRow("2026-07-15", 78.4)),
        )
        signal.shouldBeInstanceOf<AdaptationSignal.DeLoad>()
        signal.trigger shouldBe DeLoadTrigger.RapidWeightLoss
        signal.volumeScale shouldBe AdaptationSignal.SCALE_MODERATE
    }

    @Test
    fun `fewer than two progress points do not establish a trend`() {
        // A single weight point is noise, not a trend — conservative: None.
        localAdaptationSignal(symptoms = emptyList(), progress = listOf(pRow("2026-07-01", 80.0))) shouldBe
            AdaptationSignal.None
    }

    @Test
    fun `progress plus a flagged symptom never escalates load beyond a de-load`() {
        // The gate/signal never turns inputs into MORE load. Drive the strongest
        // client-side stress (rapid loss + a new symptom) and assert the result is
        // still only ever None or DeLoad — the type cannot express an increase,
        // and the client wiring honours that. This is the DRE-61 "gate still
        // blocks escalation" check at the client layer.
        val strong = localAdaptationSignal(
            symptoms = listOf(entry("2026-07-01", "tension"), entry("2026-07-08", "tension, sharp")),
            progress = listOf(pRow("2026-07-01", 80.0), pRow("2026-07-15", 78.0)),
        )
        strong.shouldBeInstanceOf<AdaptationSignal.DeLoad>()
        strong.volumeScale shouldBe AdaptationSignal.SCALE_STRONG
        (strong.volumeScale >= AdaptationSignal.SCALE_FLOOR && strong.volumeScale < 1.0) shouldBe true
    }
}
