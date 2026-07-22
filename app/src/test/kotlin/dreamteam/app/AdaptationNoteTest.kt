package dreamteam.app

import dreamteam.domain.adaptation.AdaptationSignal
import dreamteam.domain.adaptation.deriveAdaptationSignal
import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.symptom.Symptom
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * M3-C ([DRE-53](/DRE/issues/DRE-53)) — the two guarantees the adaptation
 * **surface** (not the wiring) must give, enforced as code rather than relied on:
 *
 * 1. **Renders from a known signal:** `adaptationNote(DeLoad)` emits an
 *    indicator + the support-framed reason; `adaptationNote(None)` emits nothing
 *    (baseline shows as today — no adaptation UI).
 * 2. **No medical claim:** none of the strings the surface can ever render —
 *    across **every** domain `DeLoad` reason (SymptomEscalation, RapidWeightLoss,
 *    both) plus the indicator — contain a banned diagnostic/treatment phrase.
 *    The note is behavioral ("объём снижен"), never "у вас … / treats / cures".
 *
 * The rendered text is a pure function of the signal, so a JVM assertion over it
 * is the smallest sufficient check of the Compose surface without a device or
 * Compose-instrumentation dependency (DRE-53 "or screenshot-gated check",
 * engineer-default). The loop-closing wiring itself is pinned by
 * [ClientAdaptationTest].
 */
class AdaptationNoteTest {

    private fun symptom(i: Int, on: String, words: List<String>) =
        Symptom(id = "s$i", userId = "local", recordedOn = on, source = "client_log", currentSymptoms = words)

    private fun progress(i: Int, on: String, kg: Double) =
        ProgressEntry(id = "p$i", userId = "local", recordedOn = on, weightKg = kg)

    // Every domain reason the UI can render — now (SymptomEscalation) and once
    // weight-progress logging lands (RapidWeightLoss + both). Built via
    // deriveAdaptationSignal so the test scans the *real* authored strings, not
    // a duplicated copy that would drift.
    private val allDeLoadSignals: List<AdaptationSignal> = listOf(
        deriveAdaptationSignal( // SymptomEscalation: a new token in the latest entry
            progress = emptyList(),
            symptoms = listOf(
                symptom(0, "2026-07-01", listOf("lumbar", "tension")),
                symptom(1, "2026-07-08", listOf("lumbar", "tension", "sharp")),
            ),
        ),
        deriveAdaptationSignal( // RapidWeightLoss: −1% over one week (< −0.0075)
            progress = listOf(progress(0, "2026-07-01", 100.0), progress(1, "2026-07-08", 99.0)),
            symptoms = emptyList(),
        ),
        deriveAdaptationSignal( // both triggers fire (stronger cut)
            progress = listOf(progress(0, "2026-07-01", 100.0), progress(1, "2026-07-08", 99.0)),
            symptoms = listOf(
                symptom(0, "2026-07-01", listOf("tension")),
                symptom(1, "2026-07-08", listOf("tension", "sharp")),
            ),
        ),
    )

    // Banned substrings (lowercased): the surface may never diagnose, claim to
    // treat/cure, or frame the user in second-person clinical terms. Mirrors the
    // DRE-48 hard invariants + the app's no-medical-claim stance (DISCLAIMER).
    // "симптом" is intentionally NOT banned — it is the user's own logged word
    // surfaced back, descriptive not diagnostic.
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
        "болезнь",
        "у вас", "вы больн", "вы здоровы",
        "diagnos", "treat", "cure", "heal", "disease", "you have", "you are",
    )

    @Test
    fun `None renders nothing - baseline shows as today`() {
        adaptationNote(AdaptationSignal.None) shouldBe null
    }

    @Test
    fun `every DeLoad signal renders the indicator plus the carried reason`() {
        allDeLoadSignals.forEach { signal ->
            val deLoad = signal.shouldBeInstanceOf<AdaptationSignal.DeLoad>()
            val note = adaptationNote(deLoad).shouldBeInstanceOf<AdaptationNote>()
            note.indicator shouldBe "Объём снижен"
            note.reason shouldBe deLoad.reason // carried verbatim, no rewording
            note.reason.isNotBlank() shouldBe true
        }
    }

    @Test
    fun `no rendered adaptation string contains a banned medical-claim phrase`() {
        val rendered = allDeLoadSignals.flatMap { signal ->
            val note = adaptationNote(signal as AdaptationSignal.DeLoad)!!
            listOf(note.indicator, note.reason)
        }
        rendered.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }
}
