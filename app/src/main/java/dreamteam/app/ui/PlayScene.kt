package dreamteam.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dreamteam.app.data.LocalDatabase
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
 * state machine* (set/rep progression, rest timer, auto-advance) is the Founding
 * Engineer's track. This scene ships the full **UI** now — current-exercise
 * focus, manual advance, progress, breathing entry — driven by local UI state
 * (`currentIndex`). When the FE engine lands it replaces this local index with a
 * richer state object; the render surface (the params below) is the integration
 * contract. The gate/plan/data are untouched: the session is the already-gated
 * [PlanSession] from the deterministic plan.
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
    // Local UI state — the FE session engine (DRE-210) will drive this later.
    var currentIndex by remember(session.id) { mutableIntStateOf(0) }
    var completed by remember(session.id) {
        mutableStateOf(db.completedExercises(session.id))
    }

    fun markCurrentDoneAndAdvance() {
        val a = assignments.getOrNull(currentIndex) ?: return
        if (a.exerciseId !in completed) {
            db.logWorkout(session.id, a.exerciseId, today)
            completed = completed + a.exerciseId
        }
        if (currentIndex < assignments.lastIndex) currentIndex += 1
    }

    if (assignments.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(Spacing.screen),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { Text(PlayStrings.EMPTY, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val a = assignments[currentIndex]
    val name = BaselineProgram.exercises[a.exerciseId]?.name ?: a.exerciseId
    val allDone = completed.size >= assignments.size

    Column(
        modifier = modifier
            .fillMaxSize()
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
                PlayStrings.progressLine(currentIndex + 1, assignments.size, completed.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onBack) { Text(PlayStrings.BACK) }
        }
        FilledTonalButton(onClick = onBreathing, modifier = Modifier.fillMaxWidth()) {
            Text(PlayStrings.BREATHING)
        }

        // The current exercise, large and legible — the whole point of the scene.
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        PlayStrings.currentOf(currentIndex + 1, assignments.size),
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
                        PlayStrings.prescription(a.sets, a.repScheme, a.rir),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Advance controls. "Готово → далее" logs + moves on; the last one ends.
        if (allDone) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(PlayStrings.FINISH) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = ::markCurrentDoneAndAdvance,
                    modifier = Modifier.weight(1f),
                ) { Text(PlayStrings.DONE_NEXT) }
                // Skip without logging (an exercise the user passes on).
                FilledTonalButton(onClick = {
                    if (currentIndex < assignments.lastIndex) currentIndex += 1
                }) { Text(PlayStrings.SKIP) }
            }
        }
    }
}

/** Authored copy for the Play scene. Support framing only — no medical claim. */
internal object PlayStrings {
    const val BACK = "Назад"
    const val BREATHING = "Дыхание"
    const val DONE_NEXT = "Готово · далее"
    const val SKIP = "Пропустить"
    const val FINISH = "Завершить тренировку"
    const val EMPTY = "На сегодня упражнений нет."
    fun sessionTitle(session: PlanSession) = "Тренировка · ${session.label}"
    fun progressLine(current: Int, total: Int, done: Int) = "Упражнение $current из $total · выполнено $done"
    fun currentOf(current: Int, total: Int) = "$current / $total"
    fun prescription(sets: Int, repScheme: String, rir: Int?) =
        if (rir != null) "$sets × $repScheme · RIR $rir" else "$sets × $repScheme"
    val all: List<String> = listOf(BACK, BREATHING, DONE_NEXT, SKIP, FINISH, EMPTY)
}
