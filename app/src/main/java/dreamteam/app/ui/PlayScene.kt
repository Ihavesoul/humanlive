package dreamteam.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dreamteam.app.data.LocalDatabase
import dreamteam.app.playSessionPosition
import dreamteam.domain.training.BaselineProgram
import dreamteam.domain.training.PlanSession
import java.time.LocalDate

/**
 * Redesign v2 ([DRE-211](/DRE/issues/DRE-211)) — the dedicated "Play" session
 * scene (founder review DRE-205 p.6: «у любого приложения надо сделать кнопку
 * "плей" и отдельный экран/сцену под этот "плей"»). Today's workout runs here,
 * one exercise at a time: the current assignment big and legible, a clear
 * "done → next" advance, a progress readout, and a breathing entry for the
 * warm-up / between sets. Completion is logged through the SAME
 * [LocalDatabase.logWorkout] edge the SessionCard checkbox uses, so a set marked
 * done in Play shows done on Today/Plan — one source of truth.
 *
 * Division of labour (DRE-211 vs [DRE-210](/DRE/issues/DRE-210)): the *session
 * position* is the Founding Engineer's pure derivation ([playSessionPosition]):
 * the current exercise is the first incomplete assignment, derived from the
 * persisted completion set, so a half-done session resumes on the next exercise
 * to do (never exercise 1 again) and can't drift from the [LocalDatabase.logWorkout]
 * source of truth. This scene renders from it — current-exercise focus, manual
 * mark-done → advance, progress, breathing entry. The gate/plan/data are
 * untouched: the session is the already-gated [PlanSession] from the deterministic plan.
 */
@Composable
internal fun PlayScene(
    modifier: Modifier = Modifier,
    db: LocalDatabase,
    session: PlanSession,
    onBreathing: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val today = LocalDate.now().toString()
    val assignments = session.assignments
    // DRE-210: the session position is DERIVED from the persisted completion
    // set, not a throwaway local index ([playSessionPosition]). The current
    // exercise is the first incomplete one; finished when all are done — so a
    // half-done session resumes on the next exercise to do, never exercise 1
    // again, and can't drift from the [LocalDatabase.logWorkout] source of truth.
    var completed by remember(session.id) {
        mutableStateOf(db.completedExercises(session.id))
    }
    val position = playSessionPosition(assignments, completed)

    fun markCurrentDone() {
        val current = position.current ?: return
        if (current.exerciseId !in completed) {
            db.logWorkout(session.id, current.exerciseId, today)
            completed = completed + current.exerciseId
        }
    }

    if (assignments.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(Spacing.screen),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { Text(PlayStrings.EMPTY, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val current = position.current ?: return
    val name = BaselineProgram.exercises[current.exerciseId]?.name ?: current.exerciseId
    val allDone = position.finished

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screen, vertical = Spacing.xl),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Header: progress readout + back. A calm breathing entry (warm-up /
        // between sets) belongs in the flow (founder: a breathing option per session).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                PlayStrings.progressLine(position.currentIndex + 1, assignments.size, position.doneCount),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onBack) { Text(PlayStrings.BACK) }
        }
        FilledTonalButton(onClick = onBreathing, modifier = Modifier.fillMaxWidth()) {
            Text(PlayStrings.BREATHING)
        }

        // Workout body: show completion confirmation when all exercises are done,
        // otherwise the current exercise large and legible.
        if (allDone) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    PlayStrings.COMPLETE,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    PlayStrings.progressLine(assignments.size, assignments.size, assignments.size),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        PlayStrings.currentOf(position.currentIndex + 1, assignments.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        name,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        PlayStrings.prescription(current.sets, current.repScheme, current.rir),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // DRE-210: mark-done → advance is the single path. "Skip without logging"
        // is non-resumable under derive-from-completion (an uncompleted exercise
        // stays current) and is deliberately not modelled (ClientPlay.kt) — a
        // skipped set would need its own persisted "skipped" set.
        if (allDone) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(PlayStrings.FINISH) }
        } else {
            Button(
                onClick = ::markCurrentDone,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(PlayStrings.DONE_NEXT) }
        }
    }
}

/** Authored copy for the Play scene. Support framing only — no medical claim. */
internal object PlayStrings {
    const val BACK = "Назад"
    const val BREATHING = "Дыхание"
    const val DONE_NEXT = "Готово · далее"
    const val FINISH = "Завершить тренировку"
    const val COMPLETE = "Тренировка завершена!"
    const val EMPTY = "На сегодня упражнений нет."
    fun sessionTitle(session: PlanSession) = "Тренировка · ${session.label}"
    fun progressLine(current: Int, total: Int, done: Int) = "Упражнение $current из $total · выполнено $done"
    fun currentOf(current: Int, total: Int) = "$current / $total"
    fun prescription(sets: Int, repScheme: String, rir: Int?) =
        if (rir != null) "$sets × $repScheme · RIR $rir" else "$sets × $repScheme"
    val all: List<String> = listOf(BACK, BREATHING, DONE_NEXT, FINISH, COMPLETE, EMPTY)
}
