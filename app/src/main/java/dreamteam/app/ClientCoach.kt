package dreamteam.app

import dreamteam.app.data.ExerciseNoteRow
import dreamteam.app.data.Profile
import dreamteam.app.data.ProgressRow
import dreamteam.app.data.SymptomEntry
import dreamteam.domain.ExerciseId
import dreamteam.domain.adaptation.AdaptationSignal
import dreamteam.domain.coach.Coach
import dreamteam.domain.coach.CoachExplain
import dreamteam.domain.coach.CoachExerciseCorrection
import dreamteam.domain.coach.CoachNote
import dreamteam.domain.coach.CoachReport
import dreamteam.domain.coach.CoachSource
import dreamteam.domain.safety.MedicalSafety
import dreamteam.domain.training.BaselineProgram
import dreamteam.domain.training.PlanSession

/**
 * M8-C ([DRE-89](/DRE/issues/DRE-89)): the client half of the AI coach. The app
 * calls the **shared [Coach]** from [:core:domain] with **no provider** — so the
 * client always serves the **deterministic fallback** (#4: never stranded) and
 * there is **no LLM in the client** (#5: Z.AI is called only on the server via
 * [dreamteam.server.coach.ZaiCoachProvider], which the app reaches through the
 * M9-D app→server transport in [CoachServerTransport] — default off, DRE-125).
 * The same coach logic + safety gate run here offline-first as on the server.
 *
 * Two pure view builders ([coachReportView] / [coachExplainView]) turn the
 * domain result into phone-readable rows the Compose tree renders — extracted so
 * a JVM test pins the shape + the no-medical-claim stance without a device.
 */
internal val appCoach: Coach = Coach(provider = null)

/** Build the coach's per-exercise note inputs from the M8-B logged rows. */
internal fun coachNotesFromRows(rows: List<ExerciseNoteRow>): List<CoachNote> =
    rows.map { CoachNote(exerciseId = it.exerciseId, note = it.note) }

/**
 * "Сообщить коучу": run the coach over a finished session's notes (+ the user's
 * medical/symptom/progress context). Returns the gate-produced adapted plan +
 * phone-readable text. Pure given its inputs; same inputs → same report.
 */
internal fun coachReportForSession(
    profile: Profile,
    notes: List<CoachNote>,
    symptoms: List<SymptomEntry>,
    progress: List<ProgressRow>,
    today: String,
    originalPlanId: String = "baseline-12w",
    server: CoachServerClient? = coachServerClientOrNull(),
): CoachReport {
    val medical = MedicalSafety(
        scoliosisReported = profile.scoliosisReported,
        redFlags = profile.redFlags,
    )
    // M9-D (DRE-125): try the app→server coach transport first; on any failure
    // (flag off / unreachable / unparseable) `server?.report` is null ⇒ the
    // local deterministic fallback stands (#4: never stranded).
    server?.report(medical, originalPlanId, notes)?.let { return it }
    return appCoach.report(
        userId = "local",
        createdAt = today,
        medical = medical,
        originalPlanId = originalPlanId,
        notes = notes,
        symptoms = clientSymptoms(symptoms),
        progress = clientProgress(progress),
    )
}

/** "Спросить у AI": the short contextual cue for one exercise. */
internal fun coachExplainForExercise(
    exerciseId: ExerciseId,
    profile: Profile,
    server: CoachServerClient? = coachServerClientOrNull(),
): CoachExplain {
    val medical = MedicalSafety(
        scoliosisReported = profile.scoliosisReported,
        redFlags = profile.redFlags,
    )
    // M9-D (DRE-125): try the app→server coach transport first; any failure ⇒ local fallback.
    server?.explain(exerciseId, medical)?.let { return it }
    return appCoach.explain(exerciseId = exerciseId, medical = medical)
}

// --- phone-readable view models ---------------------------------------------

/** A name-resolved correction row for the UI (the raw id never reaches the screen). */
internal data class CoachCorrectionRow(val exerciseName: String, val noteRu: String)

/** The phone-readable report view the popup renders. */
internal data class CoachReportView(
    val summaryRu: String,
    val corrections: List<CoachCorrectionRow>,
    val sourceLabel: String,
    val isDeLoad: Boolean,
)

/** The phone-readable explain view the cue popup renders. */
internal data class CoachExplainView(val summaryRu: String, val sourceLabel: String)

/** Pure render of a [CoachReport.Ok] into phone-readable rows. */
internal fun coachReportView(report: CoachReport.Ok): CoachReportView {
    val rows = report.corrections.map { it.toRow() }
    return CoachReportView(
        summaryRu = report.summaryRu,
        corrections = rows,
        sourceLabel = sourceLabel(report.source),
        // The de-load shows in the adapted plan's week notes (BaselineProgram writes
        // the "Адаптация" suffix when a DeLoad was applied).
        isDeLoad = report.adaptedPlan.weeks.any { "Адаптация" in it.notes },
    )
}

/** Pure render of a [CoachExplain.Ok] cue. */
internal fun coachExplainView(explain: CoachExplain.Ok): CoachExplainView =
    CoachExplainView(summaryRu = explain.summaryRu, sourceLabel = sourceLabel(explain.source))

private fun CoachExerciseCorrection.toRow(): CoachCorrectionRow =
    CoachCorrectionRow(
        exerciseName = BaselineProgram.exercises[exerciseId]?.name ?: exerciseId,
        noteRu = noteRu,
    )

private fun sourceLabel(source: CoachSource): String = when (source) {
    CoachSource.LLM -> CoachStrings.SOURCE_LLM
    CoachSource.FALLBACK -> CoachStrings.SOURCE_FALLBACK
}

/**
 * The adapted counterpart of [original] inside an adapted plan: same session id,
 * de-loaded dose. Null if the id is absent (should not happen — the baseline
 * structure is mirrored). Used by the "оригинал vs адаптация" popup.
 */
internal fun adaptedSessionOf(adapted: CoachReport.Ok, original: PlanSession): PlanSession? =
    adapted.adaptedPlan.weeks.flatMap { it.sessions }.firstOrNull { it.id == original.id }

/**
 * The authored coach strings the popups render. Gathered as one list
 * ([CoachStrings.all]) so a JVM test can snapshot them against the banned
 * medical-claim phrase list (mirrors [TodayStrings] / [SafetyBlockStrings]).
 * Support framing only — no diagnosis, no treatment claim.
 */
internal object CoachStrings {
    /** "Спросить у AI" button on an exercise row. */
    const val ASK_AI = "Спросить у AI"
    /** "Сообщить коучу" CTA at the end of a session. */
    const val REPORT_CTA = "Сообщить о тренировке коучу"
    const val EXPLAIN_TITLE = "Коуч — коротко"
    const val REPORT_TITLE = "Отчёт коучу"
    const val ORIGINAL_LABEL = "Оригинал"
    const val ADAPTATION_LABEL = "Адаптация"
    /** The popup's default-selected option (reviewer p.3.4). */
    const val ADAPTATION_DEFAULT_HINT = "Адаптация выбрана по умолчанию"
    const val APPLY_ADAPTATION = "Применить адаптацию"
    const val KEEP_ORIGINAL = "Оставить оригинал"
    /** Inline confirmation after the user applies the adaptation (choice feedback). */
    const val APPLIED_ADAPTATION = "Адаптация применена к следующей сессии. Оригинал сохранён."
    const val KEPT_ORIGINAL = "Оригинал сохранён."
    const val SOURCE_LLM = "коуч (LLM)"
    const val SOURCE_FALLBACK = "коуч (офлайн-план)"
    const val REDFLAG_BLOCK = "Красный флаг: обратитесь за медицинской оценкой. Приложение поддерживает, а не заменяет врача."
    /** DRE-99 graceful degrade: the gateway blocked the baseline plan; keep the original. */
    const val PLAN_UNAVAILABLE = "Не удалось подготовить адаптацию плана. Оригинал сохранён — продолжайте по нему. Приложение поддерживает, а не заменяет врача."

    val all: List<String> = listOf(
        ASK_AI, REPORT_CTA, EXPLAIN_TITLE, REPORT_TITLE, ORIGINAL_LABEL, ADAPTATION_LABEL,
        ADAPTATION_DEFAULT_HINT, APPLY_ADAPTATION, KEEP_ORIGINAL, APPLIED_ADAPTATION, KEPT_ORIGINAL,
        SOURCE_LLM, SOURCE_FALLBACK, REDFLAG_BLOCK, PLAN_UNAVAILABLE,
    )
}
