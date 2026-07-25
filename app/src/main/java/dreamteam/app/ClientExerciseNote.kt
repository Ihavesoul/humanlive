package dreamteam.app

import dreamteam.app.data.ExerciseNoteOutcome

/**
 * M8-B ([DRE-78](/DRE/issues/DRE-78)): the authored UI strings for the per-exercise
 * note field in the execution log. Gathered into one internal object so a JVM test
 * ([AdaptationNoteTest]) can scan them against the banned medical-claim phrase list
 * — the same pin every other authored surface (adaptation note, history, export,
 * safety block) carries. The note itself is the user's verbatim self-report and is
 * NOT scanned (mirrors symptom/progress rows: the user's words, not an app claim);
 * only the app-authored labels/hints are pinned here.
 *
 * M9-A ([DRE-112](/DRE/issues/DRE-112)): the outcome chip labels
 * ([ExerciseNoteOutcome.labelRu]) are app-authored too, so they are folded into
 * [all] for the same banned-phrase scan.
 *
 * Framing is support/transparency only — the app invites a self-report
 * ("что вышло / что нет / боль"), never diagnoses or treats. "боль" is
 * intentionally allowed: it is the user's own experience being asked for, the
 * same way the symptom logger accepts free text (cf. [HistoryStrings.SUPPORT]);
 * the banned list blocks "болезнь" / "вы больн" (disease / second-person clinical
 * framing), not "pain".
 */
internal object ExerciseNoteStrings {
    /** Inline label shown beside the note field on each exercise row. */
    const val LABEL = "Заметка"

    /** Placeholder hint inside the field — mirrors the issue's «что вышло / что нет / боль». */
    const val HINT = "Что вышло, что нет, дискомфорт или боль"

    /** Save action label. */
    const val SAVE = "Сохранить заметку"

    /** M9-A (DRE-112): section label above the outcome chips ("how did it go"). */
    const val OUTCOME_LABEL = "Как прошло"

    /** All app-authored strings this surface can render, for the banned-phrase scan. */
    val all: List<String> = listOf(LABEL, HINT, SAVE, OUTCOME_LABEL) +
        ExerciseNoteOutcome.entries.map { it.labelRu }
}
