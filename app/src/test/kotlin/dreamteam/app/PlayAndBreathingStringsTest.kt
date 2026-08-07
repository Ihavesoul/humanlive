package dreamteam.app

import dreamteam.app.ui.BreathSound
import dreamteam.app.ui.BreathingStrings
import dreamteam.app.ui.PHASES
import dreamteam.app.ui.PlayStrings
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Redesign v2 ([DRE-211](/DRE/issues/DRE-211)) — closes the banned-phrase-scan
 * gap the two new scenes (Play / Breathing) opened: every OTHER authored surface
 * in the app carries a scan pin (Today / Coach / Nutrition / Evidence / Settings /
 * references-card / density-chip / …), but [PlayStrings] and [BreathingStrings]
 * shipped in slice 1 without one. This test pins the same invariant for them so
 * no medical-claim phrase can slip into the scene copy unnoticed.
 *
 * Mirrors [ExerciseReferencesCardTest] / [ClientCoachTest]: the SAME banned
 * morpheme list, scanned lowercased over the app-authored `.all` constants only.
 * Catalog CONTENT rendered verbatim by a scene (e.g. an exercise's `ai_summary_ru`)
 * is Evidence-Analyst output, not app copy — it is NOT scanned here, the same way
 * citation rows are not scanned (study vocabulary is vetted upstream).
 */
class PlayAndBreathingStringsTest {

    // Banned substrings (lowercased) — the same list every authored surface is
    // scanned against: scene copy may never assert a diagnosis or claim to treat/cure.
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
        "болезнь",
        "у вас", "вы больн", "вы здоровы", "ваш диагноз",
        "предписываю", "назначаю", "прописываю",
        "diagnos", "treat", "cure", "heal", "disease", "you have", "you are", "prescribe",
    )

    @Test
    fun `no authored Play-scene string contains a banned medical-claim phrase`() {
        PlayStrings.all.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }

    @Test
    fun `no authored Breathing-scene string contains a banned medical-claim phrase`() {
        BreathingStrings.all.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }

    @Test
    fun `box-breathing phases map to the expected sound cues`() {
        PHASES.map { it.sound } shouldBe listOf(
            BreathSound.IN, BreathSound.HOLD, BreathSound.OUT, BreathSound.HOLD,
        )
    }
}
