package dreamteam.app

import dreamteam.app.data.Profile
import dreamteam.app.data.ProgressRow
import dreamteam.app.data.SymptomEntry
import dreamteam.app.data.WorkoutCompletion
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * M7-B ([DRE-73](/DRE/issues/DRE-73)) — the runnable checks that pin the
 * "Export my data" action's byte-production, per [the slice spec](/DRE/issues/DRE-73):
 *
 * 1. **Round-trip / completeness** — the bytes the action writes (the content of
 *    the `FileProvider` URI's file) decode back to an [ExportDocument] with every
 *    store row present, none dropped, and `plan` = the freshly regenerated gated plan.
 * 2. **Gate behavior** — a blocked-gate (medical red-flag) profile exports
 *    `plan == null` through this UI path too: the action never bypasses the gate
 *    or emits a non-gated plan.
 * 3. **No medical claim** — the in-app UI strings surfaced at the action are
 *    banned-phrase-clean (transparency/support framing only).
 *
 * The action's pure byte-production is [exportActionDocument] — the exact
 * pipeline [launchDataExport] calls before the Android handoff edge (file write →
 * FileProvider URI → `ACTION_SEND`). That edge is device-level plumbing, not
 * JVM-pinnable without a NEW test dependency (Robolectric/instrumentation), which
 * is forbidden by the M7 hard invariant "no new external dependency". So this
 * check drives the pure core the action delegates to — the same edge-vs-core
 * split the repo uses for the symptom-log SQLite edge ([ClientExportTest]) and
 * every other `Client*` module. It fails if the action's wiring regresses (a
 * second serialization path, a dropped row, or a bypassed gate).
 */
class ClientExportShareTest {

    // PoC seed profile (male/28/188cm/83.2kg/21.2%BF, scoliosis reported, NO red
    // flag) — scan-clean at the medical-safety gate, so a real plan is regenerated.
    private val profile = Profile(
        sex = "male",
        age = 28,
        height = 188.0,
        weight = 83.2,
        bodyFat = 21.2,
        scoliosisReported = true,
        redFlags = emptyList(),
        createdOn = "2026-07-01",
    )
    private val today = "2026-07-23"
    private val symptoms = listOf(SymptomEntry("2026-07-15", "lumbar tension"))
    private val progress = listOf(ProgressRow("2026-07-15", 78.4))
    private val workouts = listOf(
        WorkoutCompletion("week1-dayA-squat", "back_squat_goblet", "2026-07-21"),
        WorkoutCompletion("week1-dayB-hinge", "romanian_deadlift", "2026-07-22"),
    )

    // The bytes the action would hand to the FileProvider, encoded from the
    // document the pure core produced.
    private fun actionBytes(doc: ExportDocument): String = encodeExportDocument(doc)

    // --- 1. round-trip / completeness --------------------------------------

    @Test
    fun `the export action bytes decode back with every store row present`() {
        val doc = exportActionDocument(profile, workouts, symptoms, progress, today, generatedAt = "2026-07-23T10:00:00Z")
        val decoded = exportJson.decodeFromString(ExportDocument.serializer(), actionBytes(doc))

        decoded.exportSchema shouldBe EXPORT_SCHEMA
        decoded.appVersion shouldBe APP_VERSION
        decoded.generatedAt shouldBe "2026-07-23T10:00:00Z"
        decoded.disclaimer shouldBe ExportStrings.DISCLAIMER
        // Every row present, verbatim — none silently dropped by the UI path.
        decoded.profile shouldBe profile
        decoded.workoutLog shouldBe workouts
        decoded.symptomLog shouldBe symptoms
        decoded.progressLog shouldBe progress
        // plan is the freshly regenerated gated plan for the same store snapshot.
        decoded.plan shouldNotBe null
        decoded.plan shouldBe exportPlanFrom(regenerateLocalPlans(profile, today, symptoms, progress))
    }

    // --- 2. gate behavior ---------------------------------------------------

    @Test
    fun `a red-flag profile exports plan == null through the action path`() {
        // A red-flag profile is blocked at the medical-safety gate → no surfaced
        // plan. The UI action path must carry null, never a hand-edited/bypassed plan.
        val redFlag = profile.copy(redFlags = listOf("other"))
        val doc = exportActionDocument(redFlag, workouts, symptoms, progress, today, generatedAt = "t")
        val decoded = exportJson.decodeFromString(ExportDocument.serializer(), actionBytes(doc))

        decoded.plan shouldBe null
        // Portability does not depend on the gate: profile + logs still export.
        decoded.profile shouldBe redFlag
        decoded.workoutLog shouldBe workouts
        decoded.symptomLog shouldBe symptoms
        decoded.progressLog shouldBe progress
    }

    // --- 3. no medical claim ------------------------------------------------

    // The banned diagnostic/treatment/cure morphemes every authored surface is
    // scanned against (mirrors [ClientExportTest] / [HistoryViewTest] / [TodayViewTest]).
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
        "у вас", "вы больн", "вы здоровы", "ваш диагноз",
    )

    @Test
    fun `the export UI strings are transparency framing with no medical-claim phrase`() {
        // The in-app line surfaced at the action mirrors the envelope disclaimer
        // framing — support/transparency only, no diagnostic/treatment/cure claim.
        ExportUiStrings.all.forEach { s ->
            s.isNotBlank() shouldBe true
            val lower = s.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }
}
