package dreamteam.domain.coach

import dreamteam.domain.adaptation.AdaptationSignal
import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.safety.MedicalSafety
import dreamteam.domain.symptom.Symptom
import dreamteam.domain.training.BaselineProgram
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * M8-C ([DRE-89](/DRE/issues/DRE-89)) — pins the safety contract of the coach.
 * The smallest checks that fail if any invariant breaks:
 *
 *  1. **#1 red-flag gate is pre-LLM + binding.** A reported red flag blocks
 *     [Coach.report] / [Coach.explain] and the provider is never called.
 *  2. **#4 deterministic fallback always stands.** With no provider, and with a
 *     provider that fails/times out/returns junk, the fallback result is served;
 *     the adapted plan is the deterministic, gated plan; the original plan id is
 *     preserved.
 *  3. **The adapted plan is gate-produced, never LLM-authored.** Note pain ⇒ a
 *     de-load; the surfaced assignments are the allowlisted baseline subset.
 *  4. **#2 evidence/URL allowlist.** A provider response carrying a fabricated
 *     DOI/PMID/URL, a diagnosis, an out-of-allowlist exercise, or a side-specific
 *     directive is rejected wholesale → fallback. No fabricated citation leaks.
 *  5. **The plan is untouched by enrichment** (only summary/corrections swap).
 */
class CoachTest {

    private val cleanMedical = MedicalSafety(scoliosisReported = true, redFlags = emptyList())
    private val redFlagMedical = MedicalSafety(scoliosisReported = true, redFlags = listOf("new_bowel_or_bladder_dysfunction"))

    private val painNotes = listOf(
        CoachNote("split_squat", "сильно стреляло в поясницу, боль"),
        CoachNote("pushup", "нормально"),
    )

    /** A provider that always returns [ canned ] (or null to simulate failure). */
    private class FakeProvider(val canned: String?) : CoachProvider {
        var calls = 0
        override fun complete(systemPrompt: String, userPayloadJson: String): String? {
            calls++
            return canned
        }
    }

    private fun coach(provider: CoachProvider? = null) = Coach(provider = provider)

    @Test
    fun `report blocks on a red flag BEFORE calling the provider`() {
        val provider = FakeProvider(canned = """{"summary_ru":"x","corrections":[]}""")
        val report = coach(provider).report(
            userId = "local", createdAt = "2026-07-25",
            medical = redFlagMedical, originalPlanId = "baseline-12w",
            notes = painNotes,
        )
        report.shouldBeInstanceOf<CoachReport.Blocked>()
        provider.calls shouldBe 0 // #1: the gate is pre-LLM; the provider was never contacted.
    }

    @Test
    fun `explain blocks on a red flag BEFORE calling the provider`() {
        val provider = FakeProvider(canned = """{"summary_ru":"x"}""")
        val explain = coach(provider).explain("split_squat", redFlagMedical)
        explain.shouldBeInstanceOf<CoachExplain.Blocked>()
        provider.calls shouldBe 0
    }

    @Test
    fun `with no provider the deterministic fallback is served and the plan is gated`() {
        val report = coach(provider = null).report(
            userId = "local", createdAt = "2026-07-25",
            medical = cleanMedical, originalPlanId = "baseline-12w",
            notes = painNotes,
        ) as CoachReport.Ok

        report.source shouldBe CoachSource.FALLBACK
        report.originalPlanId shouldBe "baseline-12w" // preserved for original-vs-adaptation UI
        // #1: the adapted plan is the gate-produced baseline subset — every
        // assignment is an allowlisted exercise with sourced evidence.
        val assignments = report.adaptedPlan.weeks.flatMap { it.sessions }.flatMap { it.assignments }
        assignments.shouldNotBeEmpty()
        assignments.forEach { a ->
            (a.exerciseId in BaselineProgram.exerciseIds) shouldBe true
            a.evidenceRefs.shouldNotBeEmpty()
        }
        // Note pain ⇒ a de-load: week 1's main sets are reduced (baseline is 2 → holds;
        // a 3-set week would drop). The signal is de-load-only (no increase variant).
        report.summaryRu shouldNotBe ""
    }

    @Test
    fun `note pain triggers a de-load of working-set volume`() {
        val withPain = coach().report(
            userId = "local", createdAt = "2026-07-25",
            medical = cleanMedical, originalPlanId = "baseline-12w",
            notes = listOf(CoachNote("split_squat", "острая боль в колене")),
        ) as CoachReport.Ok
        // A 3-set build week (e.g. week 3) drops to 2 (the de-load floor) under pain.
        val buildWeek = withPain.adaptedPlan.weeks.first { it.weekNumber == 3 }
        buildWeek.setsMain shouldBe 2
        buildWeek.notes.contains("Адаптация") shouldBe true

        val noPain = coach().report(
            userId = "local", createdAt = "2026-07-25",
            medical = cleanMedical, originalPlanId = "baseline-12w",
            notes = listOf(CoachNote("split_squat", "всё ок, техника держится")),
        ) as CoachReport.Ok
        (noPain.adaptedPlan.weeks.first { it.weekNumber == 3 }.setsMain) shouldBe 3 // untouched
    }

    @Test
    fun `a valid provider annotation enriches the text but never the plan`() {
        val canned = """
            {"summary_ru":"Сессия в целом ровная. На следующей — спокойнее в тяге.",
             "corrections":[{"exercise_id":"split_squat","note_ru":"снизьте темп опускания"}]}
        """.trimIndent()
        val provider = FakeProvider(canned)
        val fallbackPlan = (coach().report(
            userId = "local", createdAt = "2026-07-25",
            medical = cleanMedical, originalPlanId = "baseline-12w", notes = painNotes,
        ) as CoachReport.Ok).adaptedPlan

        val enriched = coach(provider).report(
            userId = "local", createdAt = "2026-07-25",
            medical = cleanMedical, originalPlanId = "baseline-12w", notes = painNotes,
        ) as CoachReport.Ok

        enriched.source shouldBe CoachSource.LLM
        enriched.summaryRu shouldBe "Сессия в целом ровная. На следующей — спокойнее в тяге."
        enriched.corrections.map { it.exerciseId } shouldBe listOf("split_squat")
        // #1: the adapted plan is byte-identical whether the LLM enriched or not.
        enriched.adaptedPlan shouldBe fallbackPlan
        provider.calls shouldBe 1
    }

    @Test
    fun `provider failure, null, malformed json, or a banned claim all fall back`() {
        val baseline = coach().report(
            userId = "local", createdAt = "2026-07-25",
            medical = cleanMedical, originalPlanId = "baseline-12w", notes = painNotes,
        ) as CoachReport.Ok

        val badCases = listOf(
            null, // provider unavailable / timed out
            "not json at all",
            """{"summary_ru":"приложение лечит сколиоз"}""", // medical claim
            """{"summary_ru":"см. https://example.test/x"}""", // fabricated URL
            """{"summary_ru":"doi:10.1000/xyz"}""", // fabricated citation
            """{"summary_ru":"тренируйте правую сторону больше"}""", // side-specific directive (lock engaged)
        )
        badCases.forEach { canned ->
            val provider = FakeProvider(canned)
            val report = coach(provider).report(
                userId = "local", createdAt = "2026-07-25",
                medical = cleanMedical, originalPlanId = "baseline-12w", notes = painNotes,
            ) as CoachReport.Ok
            // Every bad/failed case ⇒ fallback standing, plan untouched, no leak.
            report.source shouldBe CoachSource.FALLBACK
            report.summaryRu shouldBe baseline.summaryRu
            report.adaptedPlan shouldBe baseline.adaptedPlan
            if (canned != null) provider.calls shouldBe 1
        }
    }

    @Test
    fun `an out-of-allowlist correction is dropped but a valid summary still enriches`() {
        // A benign summary + one ghost-exercise correction: the bad correction is
        // filtered (never leaked), the valid summary is adopted → source = LLM.
        val provider = FakeProvider(
            """{"summary_ru":"ровная сессия","corrections":[{"exercise_id":"INVENTED","note_ru":"x"}]}""",
        )
        val report = coach(provider).report(
            userId = "local", createdAt = "2026-07-25",
            medical = cleanMedical, originalPlanId = "baseline-12w", notes = painNotes,
        ) as CoachReport.Ok
        report.source shouldBe CoachSource.LLM
        report.summaryRu shouldBe "ровная сессия"
        report.corrections.shouldBeEmpty() // the ghost-exercise correction was dropped
    }

    @Test
    fun `no coach output field carries a fabricated citation or medical claim`() {
        val providers = listOf(
            null,
            FakeProvider("""{"summary_ru":"ок","corrections":[]}"""),
            FakeProvider("""{"summary_ru":"вы больны","corrections":[]}"""), // rejected → fallback
        )
        val banned = listOf("doi", "pmid", "http://", "https://", "www.", "лечит", "вылеч", "диагноз", "у вас")
        providers.forEach { p ->
            val report = coach(p).report(
                userId = "local", createdAt = "2026-07-25",
                medical = cleanMedical, originalPlanId = "baseline-12w", notes = painNotes,
            ) as CoachReport.Ok
            val texts = buildList {
                add(report.summaryRu)
                report.corrections.forEach { add(it.noteRu) }
            }
            texts.forEach { t ->
                val lower = t.lowercase()
                banned.forEach { b -> (b !in lower) shouldBe true }
            }
        }
    }

    @Test
    fun `explain with a valid provider returns the LLM cue and falls back on failure`() {
        val ok = coach(FakeProvider("""{"summary_ru":"Держите нейтраль, подконтрольное опускание."}"""))
            .explain("split_squat", cleanMedical) as CoachExplain.Ok
        ok.source shouldBe CoachSource.LLM
        ok.summaryRu shouldBe "Держите нейтраль, подконтрольное опускание."

        val fb = coach(FakeProvider(null)).explain("split_squat", cleanMedical) as CoachExplain.Ok
        fb.source shouldBe CoachSource.FALLBACK
        ("split_squat" !in fb.summaryRu) shouldBe true // uses the resolved name, not the raw id
    }
}
