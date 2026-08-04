package dreamteam.app

import dreamteam.domain.ExerciseId
import dreamteam.domain.training.ExerciseAssignment

/**
 * Redesign v2 ([DRE-210](/DRE/issues/DRE-210)) — the session/"Play" state core
 * (founder review DRE-205 p.6: «кнопка "плей" и отдельный экран/сцена»). The
 * Compose Play scene (DRE-211) renders the current exercise one at a time; this
 * is the Founding Engineer's pure state derivation it renders from.
 *
 * **Why derive, not store.** The scene's position is a pure function of the
 * persisted completion set, not an independent throwaway local index: the
 * current exercise is the FIRST incomplete assignment, and the session is
 * finished when every assignment is done. That makes the scene resilient to
 * process death / recomposition — returning to a half-done session lands on the
 * next exercise to do, not exercise 1 again — and it can never drift from the
 * source of truth (the completed set is the same one [LocalDatabase.logWorkout]
 * writes and [LocalDatabase.completedExercises] reads). Same inputs ⇒ same
 * position; a JVM test pins this without a device.
 *
 * **Division of labour (DRE-210 vs DRE-211).** This is pure (no Android, no
 * Compose), mirroring [todaySession] / [ClientToday]. The scene keeps its
 * manual advance UX; it calls [playSessionPosition] to compute the current
 * exercise from the live completed set, and logs completion via the unchanged
 * [LocalDatabase.logWorkout] edge (one source of truth). The deterministic
 * plan, the safety gate and persistence are untouched — the session is the
 * already-gated [ExerciseAssignment] list from [todaySession].
 *
 * **Skip interaction (for the UI track to reconcile).** A "skip without logging"
 * affordance is inherently non-resumable under a derive-from-completion model
 * (an uncompleted exercise stays current). If skip is kept, it needs its own
 * persisted "skipped" set added through [LocalDatabase]; this core deliberately
 * does not model skip, so the default (mark-done → advance) stays safe.
 */
internal data class PlaySessionPosition(
    val current: ExerciseAssignment?,
    val currentIndex: Int,
    val total: Int,
    val doneCount: Int,
    val finished: Boolean,
)

/**
 * Derive the Play session position deterministically from the persisted
 * completion [completed] set over [assignments]: the current exercise is the
 * first incomplete assignment; the session is finished when all are done. Empty
 * assignments ⇒ a finished empty session (the scene renders its rest-day line).
 * Pure over inputs — call it from the scene with the live completed set.
 */
internal fun playSessionPosition(
    assignments: List<ExerciseAssignment>,
    completed: Set<ExerciseId>,
): PlaySessionPosition {
    if (assignments.isEmpty()) return PlaySessionPosition(null, 0, 0, 0, finished = true)
    val done = assignments.count { it.exerciseId in completed }
    val firstIncomplete = assignments.indexOfFirst { it.exerciseId !in completed }
    return if (firstIncomplete < 0) {
        // All done: rest on the last assignment and mark finished so the scene
        // shows its completion state rather than a stale mid-session index.
        PlaySessionPosition(assignments.last(), assignments.lastIndex, assignments.size, done, finished = true)
    } else {
        PlaySessionPosition(assignments[firstIncomplete], firstIncomplete, assignments.size, done, finished = false)
    }
}
