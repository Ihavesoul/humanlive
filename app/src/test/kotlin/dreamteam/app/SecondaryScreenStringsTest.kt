package dreamteam.app

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Iter 3 ([DRE-258](/DRE/issues/DRE-258)): banned-phrase gate for the secondary
 * screen authored-string objects (Onboarding / Plan / Symptom / Progress).
 * Mirrors [ExerciseDensityChipTest]'s scan; none of the new literals contain a
 * banned morpheme — the test is green by construction but required by G3.
 */
class SecondaryScreenStringsTest {

    // Banned substrings (lowercased) — same list as the M3-C/M4-C/M9-B surface
    // tests: authored strings may never assert a diagnosis or claim to treat/cure.
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
        "болезнь",
        "у вас", "вы больн", "вы здоровы", "ваш диагноз",
        "предписываю", "назначаю", "прописываю",
        "diagnos", "treat", "cure", "heal", "disease", "you have", "you are", "prescribe",
    )

    @Test
    fun `no authored onboarding string contains a banned medical-claim phrase`() {
        OnboardingStrings.all.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }

    @Test
    fun `no authored plan string contains a banned medical-claim phrase`() {
        PlanStrings.all.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }

    @Test
    fun `no authored symptom string contains a banned medical-claim phrase`() {
        SymptomStrings.all.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }

    @Test
    fun `no authored progress string contains a banned medical-claim phrase`() {
        ProgressStrings.all.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }
}
