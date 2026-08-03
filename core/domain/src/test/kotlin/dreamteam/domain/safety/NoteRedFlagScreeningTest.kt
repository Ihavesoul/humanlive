package dreamteam.domain.safety

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * DRE-100 ([plan](/DRE/issues/DRE-100#document-plan)) — pins the free-text
 * red-flag scanner. Phrase table is the Safety Reviewer's spec, implemented
 * verbatim; these are the smallest checks that fail if precision or recall
 * drifts. A failure here is a safety-rule change, not a refactor.
 *
 * Evidence: `ACR-LBP-RED-FLAGS` (escalation semantics: red flag ⇒ block/route
 * to assessment). The phrase→flag mapping is a conservative heuristic.
 */
class NoteRedFlagScreeningTest {

    private fun derive(vararg notes: String): Set<RedFlag> =
        NoteRedFlagScreening.derive(notes.toList())

    // --- Test 1: precision / no false positives (benign notes stay on de-load) ---

    @Test
    fun `benign notes derive no red flag`() {
        // «спина болит», «устал после подхода», bare «немеет нога» — all stay on
        // the existing de-load path; none escalate.
        derive("спина болит").shouldBeEmpty()
        derive("устал после подхода").shouldBeEmpty()
        derive("немеет нога").shouldBeEmpty()
        // Fatigue phrasing must not look like progressive weakness.
        derive("слабость в руках после тренировки").shouldBeEmpty()
        // Bare night mention is too common — needs the strict "wakes from sleep".
        derive("ночью побаливала спина").shouldBeEmpty()
    }

    // --- Test 1b (DRE-107): phrase-anchor precision tightenings (gate-#2 condition) ---

    @Test
    fun `benign fatigue and equipment phrasing derive no red flag (DRE-107 anchors)`() {
        // Bare «пах» collided with «впахал» → groin morphology anchors (пахов/в пах/паховая/паху).
        derive("после тренировки немеют руки, впахал сегодня").shouldBeEmpty()
        // Bare «седл» matched «седло велосипеда» → dropped; «седловидн» stays as the clinical anchor.
        derive("седло велосипеда давит, немеет").shouldBeEmpty()
        // «нога подкашива» is a common fatigue idiom → dropped from the foot-drop set.
        derive("ноги подкашиваются от усталости").shouldBeEmpty()
    }

    // --- Test 2: recall / cauda-equina constellation (highest acuity) ---

    @Test
    fun `saddle numbness plus bladder mention escalates both flags`() {
        val flags = derive("онемение в промежности и недержание мочи")
        flags.shouldContainAll(RedFlag.BOWEL_OR_BLADDER_DYSFUNCTION, RedFlag.NUMBNESS_OR_SADDLE_ANAESTHESIA)
    }

    @Test
    fun `constellation split across two notes still fires`() {
        val flags = derive("онемение в паху", "потерял контроль над мочеиспусканием")
        flags.shouldContainAll(RedFlag.NUMBNESS_OR_SADDLE_ANAESTHESIA, RedFlag.BOWEL_OR_BLADDER_DYSFUNCTION)
    }

    // --- Test 3: single-flag recall ---

    @Test
    fun `strict night pain escalates NIGHT_PAIN`() {
        derive("боль будит ночью").shouldContain(RedFlag.NIGHT_PAIN)
        derive("просыпаюсь от боли в спине").shouldContain(RedFlag.NIGHT_PAIN)
        derive("боль не дает спать").shouldContain(RedFlag.NIGHT_PAIN)
    }

    @Test
    fun `progressive weakness escalates PROGRESSIVE_LEG_WEAKNESS`() {
        derive("слабость нарастает в ноге").shouldContain(RedFlag.PROGRESSIVE_LEG_WEAKNESS)
    }

    @Test
    fun `foot-drop phrasing escalates PROGRESSIVE_LEG_WEAKNESS`() {
        derive("стопа падает при ходьбе").shouldContain(RedFlag.PROGRESSIVE_LEG_WEAKNESS)
        derive("волочит ногу").shouldContain(RedFlag.PROGRESSIVE_LEG_WEAKNESS)
    }

    @Test
    fun `saddle anaesthesia clinical term escalates NUMBNESS`() {
        derive("седловидная анестезия").shouldContain(RedFlag.NUMBNESS_OR_SADDLE_ANAESTHESIA)
    }

    @Test
    fun `retention phrasing escalates BOWEL_OR_BLADDER_DYSFUNCTION`() {
        derive("задержка мочи").shouldContain(RedFlag.BOWEL_OR_BLADDER_DYSFUNCTION)
        derive("не могу помочиться").shouldContain(RedFlag.BOWEL_OR_BLADDER_DYSFUNCTION)
    }

    // --- morphology tolerance + case-insensitivity ---

    @Test
    fun `matching is case-insensitive and morphology-tolerant`() {
        derive("Онемение ОБЕИХ ног").shouldContain(RedFlag.NUMBNESS_OR_SADDLE_ANAESTHESIA)
    }

    @Test
    fun `blank and empty notes derive nothing`() {
        derive("").shouldBeEmpty()
        derive("   ").shouldBeEmpty()
        derive("всё ок").shouldBeEmpty()
    }

    @Test
    fun `gate-merge wire string matches the domain serial name`() {
        // The merge in SafetyGate.evaluate(medical, notes) appends each derived
        // flag's serial name to medical.redFlags; spot-check the round trip.
        (RedFlag.BOWEL_OR_BLADDER_DYSFUNCTION.name.lowercase()) shouldBe "bowel_or_bladder_dysfunction"
    }

    // --- M10-B ([DRE-186](/DRE/issues/DRE-186)): uncovered escalation branch pin ---

    @Test
    fun `progression plus a neuro term escalates RAPID_NEUROLOGICAL_PROGRESSION`() {
        // Regression pin (M10-B): the `progress && neuro` branch in derive() — a
        // spreading/worsening neurological term escalates RAPID_NEUROLOGICAL_
        // PROGRESSION (⇒ block → route to assessment). This red-flag branch had no
        // prior test; silently dropping or widening it weakens/twists a safety
        // escalation. «нараста» (progress) + «онемен» (neuro) fires it and nothing
        // else (no saddle distribution, no weakness).
        // ponytail: coarse stem-substring match — ceiling is RU-morphology false
        // edges; the Evidence/Safety Reviewer owns phrase-anchor tuning, upgrade
        // path is a dedicated phrase-test table (see DRE-107 anchors).
        derive("нарастает онемение в ноге") shouldBe setOf(RedFlag.RAPID_NEUROLOGICAL_PROGRESSION)
        derive("распространяется онемение") shouldBe setOf(RedFlag.RAPID_NEUROLOGICAL_PROGRESSION)
    }
}
