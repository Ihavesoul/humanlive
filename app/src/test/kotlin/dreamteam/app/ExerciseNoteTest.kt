package dreamteam.app

import dreamteam.app.data.ExerciseNoteOutcome
import dreamteam.app.data.ExerciseNoteRow
import dreamteam.app.data.Profile
import dreamteam.app.data.ProgressRow
import dreamteam.app.data.SymptomEntry
import dreamteam.app.data.WorkoutCompletion
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * M9-A ([DRE-112](/DRE/issues/DRE-112)) — pins for the per-exercise structured
 * outcome flag (`ok / hard / painful / skipped`):
 *
 * 1. **Enum contract** — exactly the four self-report outcomes with stable,
 *    unique storage tokens (the on-disk + export representation never drifts).
 * 2. **No medical claim** — the outcome labels are app-authored UI; none carries
 *    a banned diagnostic/treatment phrase (the user is asked for self-report,
 *    never diagnosed). "Боль" is allowed: it is the user's own experience being
 *    asked for, the same way the symptom logger accepts free text.
 * 3. **Recorded, not acted on** — the hard safety invariant: a note (incl.
 *    PAINFUL) is exported verbatim but NEVER changes the plan section. The plan
 *    path ([regenerateLocalPlans]) takes no note input, so a flag can neither
 *    suppress nor alter nor bypass the plan here. Adaptation from notes is the
 *    gated M9-D, explicitly out of scope.
 */
class ExerciseNoteTest {

    private val profile = Profile(
        sex = "male",
        age = 28,
        height = 188.0,
        weight = 83.2,
        bodyFat = 21.2,
        scoliosisReported = true,
        redFlags = emptyList(),
        createdOn = "2026-07-01",
    )
    private val today = "2026-07-23"
    private val symptoms = listOf(SymptomEntry("2026-07-15", "lumbar tension"))
    private val progress = listOf(ProgressRow("2026-07-15", 78.4))
    private val workouts = listOf(WorkoutCompletion("s", "e", "2026-07-21"))

    // --- 1. enum contract ---------------------------------------------------

    @Test
    fun `the outcome enum exposes exactly the four self-report tokens`() {
        ExerciseNoteOutcome.entries shouldHaveSize 4
        ExerciseNoteOutcome.entries.map { it.storage }.toSet() shouldBe
            setOf("ok", "hard", "painful", "skipped")
    }

    @Test
    fun `fromStorage round-trips known tokens and yields null for unknown or absent`() {
        ExerciseNoteOutcome.fromStorage("painful") shouldBe ExerciseNoteOutcome.PAINFUL
        ExerciseNoteOutcome.fromStorage("ok") shouldBe ExerciseNoteOutcome.OK
        ExerciseNoteOutcome.fromStorage(null) shouldBe null
        ExerciseNoteOutcome.fromStorage("future-outcome") shouldBe null // forwards-compatible
    }

    // --- 2. no medical claim ------------------------------------------------

    // Mirrors the banned list every authored surface is scanned against.
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
        "болезнь", "у вас", "вы больн", "вы здоровы", "ваш диагноз",
        "diagnos", "treat", "cure", "heal", "disease", "you have", "you are",
    )

    @Test
    fun `no outcome label contains a banned medical-claim phrase`() {
        ExerciseNoteOutcome.entries.forEach { o ->
            val lower = o.labelRu.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }

    // --- 3. recorded, not acted on ------------------------------------------

    @Test
    fun `a painful note is exported verbatim but never changes the plan section`() {
        // The note carries a PAINFUL self-report flag.
        val painfulNote = ExerciseNoteRow(
            sessionId = "s", exerciseId = "e", note = "sharp discomfort",
            outcome = ExerciseNoteOutcome.PAINFUL, recordedOn = "2026-07-21",
        )
        // exportActionDocument regenerates the plan INTERNALLY (via the real
        // regenerateLocalPlans path) and only copies exerciseNotes into the
        // noteLog section — so this proves the flag never reaches plan generation.
        val docWithPain = exportActionDocument(
            profile = profile, workouts = workouts, symptoms = symptoms, progress = progress,
            exerciseNotes = listOf(painfulNote), today = today, generatedAt = "t",
        )
        val docWithoutNotes = exportActionDocument(
            profile = profile, workouts = workouts, symptoms = symptoms, progress = progress,
            exerciseNotes = emptyList(), today = today, generatedAt = "t",
        )

        // The painful self-report IS in the export (recorded)...
        docWithPain.exerciseNoteLog shouldBe listOf(painfulNote)
        // ...but the plan section is identical whether or not notes are present —
        // notes are not an input to plan generation, so a painful flag can never
        // suppress, alter, or bypass the plan in this slice.
        docWithPain.plan shouldBe docWithoutNotes.plan
    }

    @Test
    fun `an outcome row round-trips through the export encoder with its flag intact`() {
        val withFlag = ExerciseNoteRow("s", "e", "felt good", ExerciseNoteOutcome.OK, "2026-07-21")
        val doc = buildExportDocument(profile, workouts, symptoms, progress, listOf(withFlag), plan = null, generatedAt = "t")
        val decoded = exportJson.decodeFromString(ExportDocument.serializer(), encodeExportDocument(doc))
        decoded.exerciseNoteLog shouldBe listOf(withFlag)
        decoded.exerciseNoteLog.first().outcome shouldBe ExerciseNoteOutcome.OK
    }
}
