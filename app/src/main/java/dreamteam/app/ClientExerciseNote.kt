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
 * M9 polish ([DRE-179](/DRE/issues/DRE-179)) — human-review readability pass:
 * the note-field strings (LABEL / HINT / SAVE / OUTCOME_LABEL) and the four
 * outcome chips were reviewed for plain, phone-readable, scoliosis-safe
 * framing. Conclusion: they already read plainly on a phone (warm self-report
 * invite «Что вышло, что нет, дискомфорт или боль», short colloquial chips) and
 * carry no medical/diagnosis claim. No copy change — the field is the user's
 * INPUT, so a per-row "why" microcopy would only clutter every exercise row
 * (the dedicated "why this exercise" line lives on the references card,
 * [ReferencesCardStrings.WHY], the surface a user opens for context).
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
