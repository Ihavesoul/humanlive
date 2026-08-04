package dreamteam.app

import dreamteam.domain.ExerciseId
import dreamteam.domain.training.ExerciseAssignment
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Redesign v2 ([DRE-210](/DRE/issues/DRE-210)) — pins the guarantees of the
 * Play session-position derivation ([playSessionPosition] → [PlaySessionPosition])
 * the Compose Play scene (DRE-211) renders from. Pure over inputs (no Android,
 * no device), mirroring [TodayViewTest].
 *
 * Guarantees (the smallest thing that fails if the derivation breaks):
 * 1. Current = the FIRST incomplete assignment (done exercises are skipped over).
 * 2. Resuming a half-done session lands on the next exercise to do, not #1 —
 *    resilient to process death / recomposition (the whole point of deriving
 *    from the completion set rather than a throwaway local index).
 * 3. All-done ⇒ finished, resting on the last assignment (no stale mid-session
 *    index, no crash on an empty tail).
 * 4. Empty assignments ⇒ a finished empty session (the scene's rest-day line).
 * 5. The derivation is a pure function: same inputs ⇒ same position.
 */
class PlaySessionPositionTest {

    private fun assignment(id: ExerciseId): ExerciseAssignment =
        ExerciseAssignment(exerciseId = id, sets = 3, repScheme = "8–10", rir = 2, evidenceRefs = emptyList())

    private val assignments = listOf(
        assignment("a"), assignment("b"), assignment("c"),
    )

    @Test
    fun `current is the first incomplete assignment`() {
        val pos = playSessionPosition(assignments, completed = emptySet())
        pos.currentIndex shouldBe 0
        pos.current?.exerciseId shouldBe "a"
        pos.total shouldBe 3
        pos.doneCount shouldBe 0
        pos.finished shouldBe false
    }

    @Test
    fun `resuming a half-done session lands on the next exercise to do`() {
        // a + b done ⇒ current is c, NOT a again (the local-index bug this fixes).
        val pos = playSessionPosition(assignments, completed = setOf("a", "b"))
        pos.currentIndex shouldBe 2
        pos.current?.exerciseId shouldBe "c"
        pos.doneCount shouldBe 2
        pos.finished shouldBe false
    }

    @Test
    fun `all done is finished, resting on the last assignment`() {
        val pos = playSessionPosition(assignments, completed = setOf("a", "b", "c"))
        pos.finished shouldBe true
        pos.doneCount shouldBe 3
        pos.currentIndex shouldBe assignments.lastIndex
        pos.current?.exerciseId shouldBe "c"
    }

    @Test
    fun `completion can be non-contiguous - current is still the first gap`() {
        // b done but a skipped/still-todo ⇒ current stays a (derive-from-completion
        // never advances past an incomplete exercise; skip needs its own state).
        val pos = playSessionPosition(assignments, completed = setOf("b"))
        pos.currentIndex shouldBe 0
        pos.current?.exerciseId shouldBe "a"
        pos.doneCount shouldBe 1
        pos.finished shouldBe false
    }

    @Test
    fun `empty assignments is a finished empty session`() {
        val pos = playSessionPosition(assignments = emptyList(), completed = emptySet())
        pos.finished shouldBe true
        pos.current shouldBe null
        pos.total shouldBe 0
    }
}
