package dreamteam.app

import dreamteam.app.data.Profile
import dreamteam.domain.coach.CoachExplain
import dreamteam.domain.coach.CoachReport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * M8-C ([DRE-89](/DRE/issues/DRE-89)) — pins the client coach surface. The app
 * runs the shared deterministic [dreamteam.domain.coach.Coach] with no provider
 * (#4/#5), so the smallest checks that prove the UI wiring without a device:
 *
 * 1. **Offline fallback always serves a phone-readable result** — explain +
 *    report produce non-empty summary text + a source label (never blank, never
 *    a crash) from the user's own notes.
 * 2. **The adaptation loop is real** — a pain note ⇒ a de-load flagged on the
 *    report view (the popup's adaptation block renders), and the adapted
 *    session's working sets are reduced vs the original.
 * 3. **No authored coach string carries a banned medical-claim phrase** (the
 *    same pin every app-authored surface carries).
 * 4. **A red-flag profile surfaces a block**, not a plan (the gate is pre-LLM).
 */
class ClientCoachTest {

    private val cleanProfile = Profile(
        sex = "male", age = 28, height = 188.0, weight = 83.2, bodyFat = null,
        scoliosisReported = true, redFlags = emptyList(), createdOn = "2026-07-25",
    )
    private val redFlagProfile = cleanProfile.copy(redFlags = listOf("new_bowel_or_bladder_dysfunction"))

    @Test
    fun `explain returns a non-empty phone-readable cue for an allowlisted exercise`() {
        val result = coachExplainForExercise("split_squat", cleanProfile)
        val ok = result.shouldBeInstanceOf<CoachExplain.Ok>()
        val view = coachExplainView(ok)
        view.summaryRu.isNotBlank() shouldBe true
        view.sourceLabel.isNotBlank() shouldBe true
        // The resolved name is shown, never the raw id leak as the whole summary.
        ("split_squat" !in view.summaryRu) shouldBe true
    }

    @Test
    fun `a pain note yields a de-loaded adapted plan flagged on the report view`() {
        val report = coachReportForSession(
            profile = cleanProfile,
            notes = coachNotesFromRows(
                listOf(dreamteam.app.data.ExerciseNoteRow("strength_A", "split_squat", "острая боль в колене", dreamteam.app.data.ExerciseNoteOutcome.PAINFUL, "2026-07-25")),
            ),
            symptoms = emptyList(),
            progress = emptyList(),
            today = "2026-07-25",
        ).shouldBeInstanceOf<CoachReport.Ok>()

        val view = coachReportView(report)
        view.summaryRu.isNotBlank() shouldBe true
        view.isDeLoad shouldBe true // pain note ⇒ de-load ⇒ adaptation popup renders
        // The adapted session's working sets are <= the original baseline's (de-load-only).
        val original = dreamteam.domain.training.BaselineProgram
            .baselineTrainingPlan("local", "baseline-12w", "2026-07-25").weeks.first().sessions.first()
        val adapted = adaptedSessionOf(report, original)!!
        adapted.assignments.size shouldBe original.assignments.size // same selection
        // A 3-set build week drops to 2 under pain (the de-load floor) — never more than baseline.
        val buildOriginal = dreamteam.domain.training.BaselineProgram
            .baselineTrainingPlan("local", "baseline-12w", "2026-07-25").weeks.first { it.weekNumber == 3 }
        val buildAdapted = report.adaptedPlan.weeks.first { it.weekNumber == 3 }
        buildAdapted.setsMain shouldBe 2
        (buildAdapted.setsMain <= buildOriginal.setsMain) shouldBe true
    }

    @Test
    fun `a no-pain session reports no de-load and keeps baseline volume`() {
        val report = coachReportForSession(
            profile = cleanProfile,
            notes = coachNotesFromRows(
                listOf(dreamteam.app.data.ExerciseNoteRow("strength_A", "split_squat", "всё ок, техника держится", dreamteam.app.data.ExerciseNoteOutcome.OK, "2026-07-25")),
            ),
            symptoms = emptyList(),
            progress = emptyList(),
            today = "2026-07-25",
        ).shouldBeInstanceOf<CoachReport.Ok>()
        coachReportView(report).isDeLoad shouldBe false
    }

    @Test
    fun `a red-flag profile blocks the coach report - no plan surfaced`() {
        val report = coachReportForSession(
            profile = redFlagProfile,
            notes = emptyList(),
            symptoms = emptyList(),
            progress = emptyList(),
            today = "2026-07-25",
        )
        (report is CoachReport.Blocked) shouldBe true
    }

    // Banned substrings (lowercased) — same list as the other app surfaces.
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
        "болезнь",
        "у вас", "вы больн", "вы здоровы", "ваш диагноз",
        "предписываю", "назначаю", "прописываю",
        "diagnos", "treat", "cure", "heal", "disease", "you have", "you are", "prescribe",
    )

    @Test
    fun `no authored coach string contains a banned medical-claim phrase`() {
        CoachStrings.all.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }
}
