package dreamteam.app

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import org.junit.jupiter.api.Test

/**
 * DRE-123 — structural pin for the exercise-note draft lifecycle.
 *
 * The defect (P1, raised on the M9-C denser cards): the unsaved per-exercise note
 * draft was lost when the collapsible detail card was closed. `ExerciseNoteField`
 * owned its draft via `remember(sessionId, exerciseId) { mutableStateOf(...) }`
 * INSIDE the `if (detailOpen) { ... }` block. In Compose, leaving that conditional
 * disposes the subtree's state, so re-expanding recomposed the field from the
 * DB-saved value only — the in-progress note/outcome was gone. (Regression from
 * M8-B, where the field was composed unconditionally.)
 *
 * This repo has **no Compose UI test harness** (no `androidTest` source set, no
 * `createComposeRule`, no `ui-test-junit4`), and adding one is out of scope for
 * this slice ("no new dependency"). As the smallest verification that the fix's
 * structure holds, this test pins:
 *   1. the draft state is declared ABOVE the `if (detailOpen)` body block —
 *      per-assignment lifetime, not conditional lifetime; and
 *   2. [ExerciseNoteField] is a stateless render of caller-owned state.
 *
 * It is red against the pre-fix code and green now. A future regression that
 * moves the draft `remember` back inside the conditional turns (1) red.
 */
class ExerciseNoteDraftLifecycleTest {

    private val source = File("src/main/java/dreamteam/app/DreamTeamApp.kt").readText()

    @Test
    fun `the note draft state is hoisted above the collapsible-detail conditional`() {
        // The caller-owned draft state holders (SessionCard, per-assignment scope)...
        val noteSaved = source.indexOf("val noteSaved = remember")
        val noteDraft = source.indexOf("var noteDraft by remember")
        val outcomeDraft = source.indexOf("var outcomeDraft by remember")
        // ...must all precede the `if (detailOpen) {` body block, so collapsing the
        // detail never disposes them.
        val detailBlock = source.indexOf("if (detailOpen) {")

        (noteSaved >= 0) shouldBe true
        (noteDraft >= 0) shouldBe true
        (outcomeDraft >= 0) shouldBe true
        (detailBlock >= 0) shouldBe true

        (noteSaved < detailBlock) shouldBe true
        (noteDraft < detailBlock) shouldBe true
        (outcomeDraft < detailBlock) shouldBe true
    }

    @Test
    fun `ExerciseNoteField is a stateless render of caller-owned draft state`() {
        // The field must no longer own DB-backed remember state: its note/outcome
        // come in as hoisted params, and the save is the caller's job. Owning the
        // state here is exactly what made the draft disposable on collapse.
        val fieldStart = source.indexOf("private fun ExerciseNoteField(")
        (fieldStart >= 0) shouldBe true
        // Top-level closing brace at column 0 ends the function.
        val fieldEnd = source.indexOf("\n}\n", fieldStart)
        (fieldEnd >= 0) shouldBe true
        val body = source.substring(fieldStart, fieldEnd)

        body shouldNotContain "db.exerciseNote("
        body shouldNotContain "db.appendExerciseNote("
        body shouldNotContain "remember(sessionId"
        body shouldContain "note: String,"
        body shouldContain "outcome: ExerciseNoteOutcome?"
        body shouldContain "onNoteChange"
        body shouldContain "onOutcomeChange"
        body shouldContain "onSave"
    }
}
