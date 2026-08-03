package dreamteam.app

import dreamteam.app.data.ExerciseNoteRow
import dreamteam.app.data.Profile
import dreamteam.app.data.ProgressRow
import dreamteam.app.data.SymptomEntry
import dreamteam.app.data.WorkoutCompletion
import dreamteam.domain.adaptation.AdaptationSignal
import dreamteam.domain.adaptation.DeLoadTrigger
import dreamteam.domain.safety.RuleStatus
import kotlinx.serialization.Serializable

/**
 * M10-D ([DRE-191](/DRE/issues/DRE-191)): the deterministic local-diagnostics
 * serializer — the pure, offline-first core of [Milestone 10](/DRE/issues/DRE-184).
 * Same edge-vs-core split as the M7-A export ([ClientExport.kt](./ClientExport.kt)):
 * a **pure** assembly/encode core ([buildDiagnosticsDocument] /
 * [encodeDiagnosticsDocument]) the Compose tree + a JVM test pin against, with
 * Android I/O only at the ([launchDiagnosticsExport]) edge.
 *
 * ## What this is (and is not)
 *
 * A user-reported issue now has a deterministic, **no-SDK** way to be diagnosed:
 * a lean support bundle a user can share via the SAME M7-B `FileProvider` +
 * `ACTION_SEND` handoff — recent activity, the last plan-generation decision, the
 * safety-gate decisions, app/build + DB version. **No analytics SDK, no
 * crash-reporting SDK, no new external dependency, no new network call, no new
 * safety surface, no medical claim.** Deterministic only: same on-device store →
 * same document (modulo [generatedAt]).
 *
 * It reuses the **existing** M7 path: the gate decision is re-derived through
 * the SAME [regenerateLocalPlans] core the UI/export use (one gate setup), and
 * the file handoff is the SAME [launchDataExport] plumbing.
 *
 * ## Privacy/safety invariants (strictly ≤ the M7 export)
 *
 * The diagnostics bundle is deliberately LEANER than the export — it never
 * exfiltrates MORE than [ExportDocument] already permits:
 *  - User free-text (symptom notes, exercise-note text) is **never** copied —
 *    only COUNTS + DATES appear. The export carries that text verbatim; the
 *    diagnostics does not.
 *  - The profile is summarized (scoliosis flag + red-flag COUNT), not copied.
 *  - No new network call, no new dependency.
 *
 * `nonFatalErrors` is the documented home for error markers; the app records
 * none today, so it is an empty list (honest, not fabricated instrumentation).
 *
 * ## Determinism + the gate decision
 *
 * [regenerateLocalPlans] is pure; re-running it here yields the SAME gate
 * decision (allow / red-flag / gateway-blocked) the on-screen plan made — that
 * decision is invariant to the symptom/progress window size (a red flag / allow
 * list block depends on the profile + candidate exercises, not the row count).
 * Only the de-load *magnitude* can vary with the window, and that is volume, not
 * safety. Mirrors the export, which also feeds the full store to the regenerator.
 */

/** Additive-only diagnostics schema version. A future field is appended, never renumbered. */
internal const val DIAGNOSTICS_SCHEMA: Int = 1

/**
 * Hand-synced with `:app` `versionCode` ([app/build.gradle.kts]); mirrors the
 * [APP_VERSION] hand-sync convention (ponytail: a const is the smallest thing
 * that makes `buildVersion` present + stable + JVM-testable now; wire AGP
 * `BuildConfig` if version drift across slices ever bites — same ceiling as
 * [APP_VERSION]).
 */
internal const val APP_BUILD: Int = 1

/** How many recent dated actions the timeline surfaces (newest-first). */
internal const val DIAGNOSTICS_ACTION_WINDOW: Int = 8

/**
 * The versioned, deterministic diagnostics document. Property declaration order
 * IS the stable JSON key order (kotlinx.serialization emits in declaration
 * order): envelope first, then the diagnostics content, then integrity/errors.
 * Additive schema: new fields are appended, never reordered/renumbered.
 */
@Serializable
internal data class DiagnosticsDocument(
    val diagnosticsSchema: Int,
    val appVersion: String,
    val buildVersion: Int,
    val generatedAt: String,
    val disclaimer: String,
    val store: DiagnosticsStore,
    val recentActions: List<DiagnosticsAction>,
    val planGeneration: DiagnosticsPlanGeneration,
    val gateDecisions: DiagnosticsGateDecisions,
    val dataIntegrity: DiagnosticsDataIntegrity,
    val nonFatalErrors: List<String>,
)

/** Data volume on device — COUNTS only, no row contents. */
@Serializable
internal data class DiagnosticsStore(
    val hasProfile: Boolean,
    val workoutCount: Int,
    val symptomCount: Int,
    val progressCount: Int,
    val exerciseNoteCount: Int,
)

/**
 * One recent dated user action — TYPE + DATE only. No free text, no exercise id,
 * no values: a support timeline of *what kind* of action happened *when*, never
 * the action's contents (strictly less than the export, which carries text).
 */
@Serializable
internal data class DiagnosticsAction(
    val type: String, // "workout" | "symptom" | "progress" | "exercise_note"
    val date: String, // YYYY-MM-DD
)

/**
 * The last plan-generation inputs/outputs SUMMARY. Inputs are summarized (flag +
 * COUNTS), never raw; outputs carry the outcome + surfaced-plan shape + the
 * blocking rule ids (closed-set tokens, not user notes) when the gate blocked.
 */
@Serializable
internal data class DiagnosticsPlanGeneration(
    val outcome: String, // "ok" | "red_flag" | "gateway_blocked" | "no_profile"
    val generatedForDate: String,
    val input: DiagnosticsPlanInput,
    val output: DiagnosticsPlanOutput,
    val adaptation: DiagnosticsSignal,
)

@Serializable
internal data class DiagnosticsPlanInput(
    val scoliosisReported: Boolean,
    val redFlagCount: Int,
    val symptomInputCount: Int,
    val progressInputCount: Int,
)

@Serializable
internal data class DiagnosticsPlanOutput(
    val trainingWeeks: Int?, // surfaced week count on Ok; null on either block path
    val nutritionPresent: Boolean?, // surfaced nutrition on Ok; null on block paths
    val blockingRuleIds: List<String>, // closed-set rule tokens; [] unless the assignment gateway blocked
)

/** The de-load signal the generator applied — volume modifier only, never safety. */
@Serializable
internal data class DiagnosticsSignal(
    val type: String, // "none" | "de_load"
    val trigger: String?, // "symptom_escalation" | "rapid_weight_loss" | null
)

/**
 * Per-gate allow/block decision (the decision, not per-candidate granularity —
 * [regenerateLocalPlans] does not expose candidate counts; surfacing them would
 * need a new domain diagnostic API, which is a deliberate ceiling here). Each
 * gate is `"allow"` | `"block"` | `"not_reached"` (an earlier gate blocked
 * before it ran). [gatewayProvisioned] + [activeRuleCount] pin the gate's
 * sign-off state (an unprovisioned gateway blocks everything by default).
 */
@Serializable
internal data class DiagnosticsGateDecisions(
    val medicalSafety: String,
    val assignmentGateway: String,
    val nutritionGate: String,
    val gatewayProvisioned: Boolean,
    val activeRuleCount: Int,
)

/** On-device store integrity — the LIVE SQLite user-version, not a hand-synced literal. */
@Serializable
internal data class DiagnosticsDataIntegrity(
    val dbSchemaVersion: Int,
)

/**
 * Pure assembly: build the diagnostics document from already-read data.
 * Deterministic — same inputs → identical document (modulo [generatedAt]). No
 * Android, no I/O, so a JVM test pins every invariant without a device.
 *
 * Re-derives the gate decision through the SAME [regenerateLocalPlans] core the
 * UI/export use. `profile == null` (pre-onboarding) yields a `no_profile`
 * outcome + unreachable gates — support can still debug a pre-onboarding state.
 */
internal fun buildDiagnosticsDocument(
    profile: Profile?,
    workouts: List<WorkoutCompletion>,
    symptoms: List<SymptomEntry>,
    progress: List<ProgressRow>,
    exerciseNotes: List<ExerciseNoteRow>,
    today: String,
    generatedAt: String,
    dbSchemaVersion: Int,
    appVersion: String = APP_VERSION,
    buildVersion: Int = APP_BUILD,
    schema: Int = DIAGNOSTICS_SCHEMA,
): DiagnosticsDocument {
    val activeRuleCount = CLIENT_SAFETY_RULES.count { it.status == RuleStatus.ACTIVE }
    val provisioned = activeRuleCount > 0
    // The gate decision the user would see right now — re-derived through the
    // SAME core the UI/export use. null only when there is no profile.
    val outcome: LocalPlanOutcome? = profile?.let { regenerateLocalPlans(it, today, symptoms, progress) }
    val signal = signalDescriptor(localAdaptationSignal(symptoms, progress))

    return DiagnosticsDocument(
        diagnosticsSchema = schema,
        appVersion = appVersion,
        buildVersion = buildVersion,
        generatedAt = generatedAt,
        disclaimer = DiagnosticsStrings.DISCLAIMER,
        store = DiagnosticsStore(
            hasProfile = profile != null,
            workoutCount = workouts.size,
            symptomCount = symptoms.size,
            progressCount = progress.size,
            exerciseNoteCount = exerciseNotes.size,
        ),
        recentActions = recentActionsFrom(workouts, symptoms, progress, exerciseNotes),
        planGeneration = diagnosticsPlanGeneration(profile, outcome, today, symptoms, progress, signal),
        gateDecisions = diagnosticsGateDecisions(outcome, provisioned, activeRuleCount),
        dataIntegrity = DiagnosticsDataIntegrity(dbSchemaVersion = dbSchemaVersion),
        // The app records no non-fatal error markers today (no android.util.Log
        // instrumentation exists). This is the documented home for any added
        // later; honest empty, not fabricated.
        nonFatalErrors = emptyList(),
    )
}

/**
 * Map a plan-regeneration [outcome] (null = no profile / pre-onboarding) to the
 * diagnostics plan-generation summary. Extracted from [buildDiagnosticsDocument]
 * as a pure function over the outcome so every branch — including
 * [LocalPlanOutcome.GatewayBlocked], which is hard to trigger via a real profile
 * — is deterministically unit-testable with a synthetic outcome.
 */
@Suppress("CyclomaticComplexMethod") // explicit per-outcome mapping is clearer than a table
internal fun diagnosticsPlanGeneration(
    profile: Profile?,
    outcome: LocalPlanOutcome?,
    today: String,
    symptoms: List<SymptomEntry>,
    progress: List<ProgressRow>,
    signal: DiagnosticsSignal,
): DiagnosticsPlanGeneration {
    val input = DiagnosticsPlanInput(
        scoliosisReported = profile?.scoliosisReported ?: false,
        redFlagCount = profile?.redFlags?.size ?: 0,
        symptomInputCount = symptoms.size,
        progressInputCount = progress.size,
    )
    val base = DiagnosticsPlanGeneration(
        outcome = "",
        generatedForDate = today,
        input = input,
        output = DiagnosticsPlanOutput(null, null, emptyList()),
        adaptation = signal,
    )
    return when (outcome) {
        null -> base.copy(outcome = "no_profile")
        is LocalPlanOutcome.Ok -> base.copy(
            outcome = "ok",
            output = DiagnosticsPlanOutput(
                trainingWeeks = outcome.plans.training.weeks.size,
                nutritionPresent = outcome.plans.nutrition != null,
                blockingRuleIds = emptyList(),
            ),
        )
        LocalPlanOutcome.RedFlag -> base.copy(outcome = "red_flag")
        is LocalPlanOutcome.GatewayBlocked -> base.copy(
            outcome = "gateway_blocked",
            output = DiagnosticsPlanOutput(null, null, outcome.ruleIds),
        )
    }
}

/**
 * Map a plan-regeneration [outcome] to the per-gate allow/block decision summary.
 * Each gate is "allow" | "block" | "not_reached" (an earlier gate blocked before it
 * ran). Pure over the outcome so every branch is deterministically unit-testable.
 */
internal fun diagnosticsGateDecisions(
    outcome: LocalPlanOutcome?,
    gatewayProvisioned: Boolean,
    activeRuleCount: Int,
): DiagnosticsGateDecisions = when (outcome) {
    null -> DiagnosticsGateDecisions("not_reached", "not_reached", "not_reached", gatewayProvisioned, activeRuleCount)
    LocalPlanOutcome.RedFlag -> DiagnosticsGateDecisions("block", "not_reached", "not_reached", gatewayProvisioned, activeRuleCount)
    is LocalPlanOutcome.GatewayBlocked -> DiagnosticsGateDecisions("allow", "block", "not_reached", gatewayProvisioned, activeRuleCount)
    is LocalPlanOutcome.Ok -> DiagnosticsGateDecisions(
        medicalSafety = "allow",
        assignmentGateway = "allow",
        nutritionGate = if (outcome.plans.nutrition != null) "allow" else "block",
        gatewayProvisioned = gatewayProvisioned,
        activeRuleCount = activeRuleCount,
    )
}

/**
 * Merge the dated rows into one newest-first timeline of typed actions (COUNTS +
 * DATES only — no free text). Deterministic: ties (same date) break by [type]
 * alphabetically, so the same store → identical list every time.
 */
internal fun recentActionsFrom(
    workouts: List<WorkoutCompletion>,
    symptoms: List<SymptomEntry>,
    progress: List<ProgressRow>,
    exerciseNotes: List<ExerciseNoteRow>,
): List<DiagnosticsAction> {
    val all: List<DiagnosticsAction> = buildList {
        workouts.forEach { add(DiagnosticsAction("workout", it.doneOn)) }
        symptoms.forEach { add(DiagnosticsAction("symptom", it.recordedOn)) }
        progress.forEach { add(DiagnosticsAction("progress", it.recordedOn)) }
        exerciseNotes.forEach { add(DiagnosticsAction("exercise_note", it.recordedOn)) }
    }
    return all.sortedWith(compareByDescending<DiagnosticsAction> { it.date }.thenBy { it.type }).take(DIAGNOSTICS_ACTION_WINDOW)
}

/** Map the sealed [AdaptationSignal] to a compact, free-text-free descriptor. */
internal fun signalDescriptor(signal: AdaptationSignal): DiagnosticsSignal = when (signal) {
    AdaptationSignal.None -> DiagnosticsSignal("none", null)
    is AdaptationSignal.DeLoad -> DiagnosticsSignal("de_load", triggerName(signal.trigger))
}

private fun triggerName(trigger: DeLoadTrigger): String = when (trigger) {
    DeLoadTrigger.SymptomEscalation -> "symptom_escalation"
    DeLoadTrigger.RapidWeightLoss -> "rapid_weight_loss"
}

/**
 * Deterministic JSON encoder for [DiagnosticsDocument]. Reuses the M7 export
 * [exportJson] config (encodeDefaults + prettyPrint + ignoreUnknownKeys) — one
 * encoder config, no duplicate. Key order is the property declaration order.
 */
internal fun encodeDiagnosticsDocument(doc: DiagnosticsDocument): String =
    exportJson.encodeToString(DiagnosticsDocument.serializer(), doc)

/**
 * The authored diagnostics framing strings. [DISCLAIMER] is the fixed non-medical
 * field the envelope carries — support/transparency framing only. Gathered here
 * so a JVM test can scan [all] against the banned medical-claim phrase list, the
 * same way every other authored surface is pinned. NOTE: the word «диагностика»
 * is deliberately AVOIDED — it contains the banned morpheme «диагности» (cf. the
 * [dreamteam.app.ExportStrings] / [dreamteam.app.HistoryStrings] scan-clean
 * stance); «самопроверка» (self-check) is the scan-clean framing used instead.
 */
internal object DiagnosticsStrings {
    const val DISCLAIMER =
        "Сводка для поддержки: версия, объём записей и решения шлюза безопасности. " +
            "Без самоотчёта и слов пользователя; приложение поддерживает, не заменяет врача."

    val all: List<String> = listOf(DISCLAIMER)
}
