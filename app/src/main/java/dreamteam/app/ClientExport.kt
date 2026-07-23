package dreamteam.app

import dreamteam.app.data.LocalDatabase
import dreamteam.app.data.Profile
import dreamteam.app.data.ProgressRow
import dreamteam.app.data.SymptomEntry
import dreamteam.app.data.WorkoutCompletion
import dreamteam.domain.nutrition.NutritionPlan
import dreamteam.domain.training.TrainingPlan
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * M7-A ([DRE-72](/DRE/issues/DRE-72)): the deterministic data-export serializer
 * — the pure, offline-first, no-medical-claim core of [Milestone 7](/DRE/issues/DRE-71)
 * (user-data portability). Mirrors the established M3-C/M4-C/M5-C/M6 pattern: a
 * **pure** assembly/encode core ([buildExportDocument] / [encodeExportDocument])
 * the Compose tree + a JVM test pin against, with Android I/O only at the
 * ([exportUserData]) edge.
 *
 * What it copies out (verbatim — adds no interpretation, no diagnosis, no new
 * guidance): the stored [Profile], every `workout_log` completion, every
 * `symptom_log` row, every `progress_log` row (newest-first preserved), and the
 * **regenerated** deterministic [TrainingPlan] + [NutritionPlan] (computed, not
 * cached — see [regenerateLocalPlans]). The export is the safety-gated plan the
 * app already produces; it never adds, weakens, or bypasses a [SafetyRule], and
 * a gate-blocked profile exports `plan = null` (the export never contains a
 * non-gated plan).
 *
 * Envelope: `exportSchema` (additive integer), `appVersion`, `generatedAt`, and
 * a fixed non-medical [ExportStrings.DISCLAIMER] (support/transparency framing —
 * symptom/progress rows are raw self-report input, never a diagnosis).
 *
 * Hard invariants (M7 tracker): deterministic only; no LLM; no network; no new
 * external dependency (reuses the `kotlinx.serialization` plugin already in the
 * build). Cloud sync / account / server-side export / off-device re-identification
 * need founder consent → deferred to an `in_review` question on
 * [DRE-71](/DRE/issues/DRE-71); noted, not built.
 */

/** Additive-only export schema version. A future field is added, never renumbered. */
internal const val EXPORT_SCHEMA: Int = 1

/**
 * Hand-synced with `:app` `versionName` ([app/build.gradle.kts]). ponytail: wire
 * AGP `BuildConfig` if version drift across slices ever bites; a const is the
 * smallest thing that makes `appVersion` present + stable now.
 */
internal const val APP_VERSION: String = "0.1.0"

/**
 * The regenerated deterministic plans embedded in the export. [nutrition] is
 * null when the nutrition gate blocks (training still surfaced); the whole
 * [ExportDocument.plan] is null when the red-flag / assignment gateway blocks —
 * the export then carries the user's profile + logs without a plan section,
 * honoring "never a non-gated plan".
 */
@Serializable
internal data class ExportedPlan(
    val training: TrainingPlan,
    val nutrition: NutritionPlan?,
)

/**
 * The versioned, deterministic export document. Property declaration order IS the
 * stable JSON key order (kotlinx.serialization emits in declaration order), so
 * the envelope comes first, then the user data, then the plan. Additive schema:
 * new fields are appended, never reordered/renumbered.
 */
@Serializable
internal data class ExportDocument(
    val exportSchema: Int,
    val appVersion: String,
    val generatedAt: String,
    val disclaimer: String,
    val profile: Profile,
    val workoutLog: List<WorkoutCompletion>,
    val symptomLog: List<SymptomEntry>,
    val progressLog: List<ProgressRow>,
    val plan: ExportedPlan?,
)

/**
 * Pure assembly: build the export document from already-read data. Deterministic
 * — same inputs → identical document (modulo [generatedAt]). No Android, no I/O,
 * so a JVM test pins all four M7-A invariants without a device.
 *
 * [plan] is the freshly regenerated [ExportedPlan] (or null for a gate-blocked
 * profile) — the caller regenerates it via [regenerateLocalPlans] so it is
 * computed, never a stale cache.
 */
internal fun buildExportDocument(
    profile: Profile,
    workouts: List<WorkoutCompletion>,
    symptoms: List<SymptomEntry>,
    progress: List<ProgressRow>,
    plan: ExportedPlan?,
    generatedAt: String,
    appVersion: String = APP_VERSION,
    schema: Int = EXPORT_SCHEMA,
): ExportDocument = ExportDocument(
    exportSchema = schema,
    appVersion = appVersion,
    generatedAt = generatedAt,
    disclaimer = ExportStrings.DISCLAIMER,
    profile = profile,
    workoutLog = workouts,
    symptomLog = symptoms,
    progressLog = progress,
    plan = plan,
)

/**
 * The Android-I/O edge: read the FULL on-device store (profile + every workout /
 * symptom / progress row) and assemble the export document with the caller's
 * regenerated [plan]. Offline-first — no network. Throws if no profile is saved
 * (there is nothing meaningful to export; M7-B only offers export post-onboarding).
 *
 * [plan] is a parameter, not a cache read: the caller passes the freshly
 * regenerated deterministic plan (see [regenerateLocalPlans] / [exportPlanFrom]),
 * honoring the plan-is-computed invariant.
 */
internal fun exportUserData(
    db: LocalDatabase,
    plan: ExportedPlan?,
    generatedAt: String,
): ExportDocument = buildExportDocument(
    profile = db.loadProfile() ?: error("export requires a saved profile"),
    workouts = db.allWorkouts(),
    symptoms = db.allSymptoms(),
    progress = db.allProgress(),
    plan = plan,
    generatedAt = generatedAt,
)

/**
 * Map a regeneration outcome to the export's plan section: the surfaced training
 * + nutrition on [LocalPlanOutcome.Ok]; `null` on either block path (the export
 * then carries profile + logs with no plan — it never contains a non-gated plan).
 */
internal fun exportPlanFrom(outcome: LocalPlanOutcome): ExportedPlan? =
    (outcome as? LocalPlanOutcome.Ok)?.let { ExportedPlan(it.plans.training, it.plans.nutrition) }

/**
 * M7-B ([DRE-73](/DRE/issues/DRE-73)): the **pure byte-production** the "Export
 * my data" Compose action calls immediately before the Android handoff edge
 * ([launchDataExport]). One serialization path, no second core: regenerate the
 * safety-gated plan ([regenerateLocalPlans] → [exportPlanFrom]) over the same
 * symptom/progress snapshots, then assemble the document ([buildExportDocument])
 * from an already-read store snapshot. The export's bytes are exactly
 * [encodeExportDocument] of this document — same determinism + completeness
 * guarantees as M7-A; a gate-blocked profile yields `plan == null` here too, so
 * the UI path never emits a non-gated plan.
 *
 * Pure over fixed inputs (no Android, no I/O) so a JVM test can drive the ACTION
 * and assert the produced bytes decode back with every store row present — per
 * the repo's established edge-vs-core split (the FileProvider/ACTION_SEND handoff
 * is device-level plumbing, the same way the symptom-log SQLite edge is not
 * JVM-pinned; the pure core it delegates to is what's pinned).
 */
internal fun exportActionDocument(
    profile: Profile,
    workouts: List<WorkoutCompletion>,
    symptoms: List<SymptomEntry>,
    progress: List<ProgressRow>,
    today: String,
    generatedAt: String,
): ExportDocument {
    val plan = exportPlanFrom(regenerateLocalPlans(profile, today, symptoms, progress))
    return buildExportDocument(profile, workouts, symptoms, progress, plan, generatedAt)
}

/**
 * Deterministic JSON encoder for [ExportDocument]. `encodeDefaults` so every
 * schema field is always present (additive schema, no silent omission of
 * defaulted/null fields); `prettyPrint` for human-inspectability. Key order is
 * the property declaration order in both modes — byte-stable for the same input.
 * Same instance config as the catalog decode ([evidenceJson]: `ignoreUnknownKeys`
 * is irrelevant for encoding but kept consistent for forward-compatible reads).
 */
internal val exportJson: Json = Json {
    encodeDefaults = true
    prettyPrint = true
    prettyPrintIndent = "  "
    ignoreUnknownKeys = true
}

/** Encode the document to deterministic JSON (same document → identical bytes). */
internal fun encodeExportDocument(doc: ExportDocument): String =
    exportJson.encodeToString(ExportDocument.serializer(), doc)

/**
 * The authored export framing strings. [DISCLAIMER] is the fixed non-medical
 * field the envelope carries — transparency/support framing only (mirrors
 * M3-C/M4-C/M5-C/M6-B). Gathered here so a JVM test can scan it against the
 * banned medical-claim phrase list, the same way every other authored surface is
 * pinned. User free-text in symptom/progress rows is verbatim self-report and is
 * deliberately NOT scanned — it is the user's words, not an app claim.
 */
internal object ExportStrings {
    const val DISCLAIMER =
        "Это полная копия ваших данных для прозрачности: профиль, записи и текущий план. " +
            "Приложение поддерживает, а не заменяет врача. Записи — ваш самоотчёт, без оценки состояния."

    val all: List<String> = listOf(DISCLAIMER)
}
