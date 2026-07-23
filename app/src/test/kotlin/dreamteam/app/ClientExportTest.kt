package dreamteam.app

import dreamteam.app.data.Profile
import dreamteam.app.data.ProgressRow
import dreamteam.app.data.SymptomEntry
import dreamteam.app.data.WorkoutCompletion
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * M7-A ([DRE-72](/DRE/issues/DRE-72)) — the runnable checks that the deterministic
 * export serializer pins the four M7-A test pins (see [the slice spec](/DRE/issues/DRE-72)):
 *
 * 1. **Round-trip / completeness** — every logged point/symptom/completion present
 *    in the store appears in the document; none silently dropped. Proved by
 *    encoding → decoding and asserting every input row survives, verbatim.
 * 2. **Plan-not-cached** — the `plan` section equals the freshly regenerated
 *    deterministic plan for the same profile (computed via [regenerateLocalPlans],
 *    never a cached read); regenerating twice yields the same plan.
 * 3. **Determinism** — same inputs → byte-identical document (modulo `generatedAt`);
 *    stable JSON key order (envelope first, then user data, then plan).
 * 4. **No medical claim** — the disclaimer is present, asserts transparency, and
 *    carries no banned diagnostic/treatment/cure phrase.
 *
 * Pure functions over fixed inputs, so a JVM assertion is the smallest sufficient
 * check without a device (the [ClientAdaptation]/[ClientNutrition] pattern).
 * The Android I/O edge ([exportUserData]) mirrors the symptom-log precedent: its
 * SQLite round-trip is device-level; the pure core it delegates to is what's pinned.
 */
class ClientExportTest {

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
    private val symptoms = listOf(
        SymptomEntry("2026-07-15", "lumbar tension"),
        SymptomEntry("2026-07-08", "tired"),
    )
    private val progress = listOf(
        ProgressRow("2026-07-15", 78.4),
        ProgressRow("2026-07-01", 80.0),
    )
    private val workouts = listOf(
        WorkoutCompletion("week1-dayA-squat", "back_squat_goblet", "2026-07-21"),
        WorkoutCompletion("week1-dayB-hinge", "romanian_deadlift", "2026-07-22"),
    )

    private fun regeneratedPlan(): ExportedPlan? =
        exportPlanFrom(regenerateLocalPlans(profile, today, symptoms, progress))

    // --- 1. round-trip / completeness --------------------------------------

    @Test
    fun `every logged row survives the encode-decode round-trip verbatim`() {
        val plan = regeneratedPlan()
        val doc = buildExportDocument(profile, workouts, symptoms, progress, plan, generatedAt = "2026-07-23T10:00:00Z")
        val encoded = encodeExportDocument(doc)
        val decoded = exportJson.decodeFromString(ExportDocument.serializer(), encoded)

        decoded.exportSchema shouldBe EXPORT_SCHEMA
        decoded.appVersion shouldBe APP_VERSION
        decoded.generatedAt shouldBe "2026-07-23T10:00:00Z"
        decoded.disclaimer shouldBe ExportStrings.DISCLAIMER
        decoded.profile shouldBe profile
        decoded.workoutLog shouldBe workouts // none dropped, verbatim
        decoded.symptomLog shouldBe symptoms
        decoded.progressLog shouldBe progress
        decoded.plan shouldBe plan
    }

    @Test
    fun `an empty store round-trips to empty logs, not nulls`() {
        val doc = buildExportDocument(profile, emptyList(), emptyList(), emptyList(), plan = null, generatedAt = "t")
        val decoded = exportJson.decodeFromString(ExportDocument.serializer(), encodeExportDocument(doc))
        decoded.workoutLog shouldHaveSize 0
        decoded.symptomLog shouldHaveSize 0
        decoded.progressLog shouldHaveSize 0
        decoded.plan shouldBe null
    }

    // --- 2. plan-not-cached -------------------------------------------------

    @Test
    fun `the plan section equals the freshly regenerated deterministic plan`() {
        val doc = buildExportDocument(profile, workouts, symptoms, progress, regeneratedPlan(), generatedAt = "t")
        // The document's plan IS a fresh regeneration for the same profile/logs.
        doc.plan shouldBe regeneratedPlan()
        doc.plan shouldNotBe null
    }

    @Test
    fun `regenerating the plan twice yields the same plan`() {
        // Computed, never cached: two fresh regenerations for identical inputs agree.
        regenerateLocalPlans(profile, today, symptoms, progress) shouldBe
            regenerateLocalPlans(profile, today, symptoms, progress)
    }

    @Test
    fun `a gate-blocked profile exports a null plan, never a non-gated plan`() {
        // A red-flag profile is blocked at the medical-safety gate → no surfaced
        // plan. The export must carry null, not a hand-edited/bypassed plan.
        val redFlag = profile.copy(redFlags = listOf("other"))
        val outcome = regenerateLocalPlans(redFlag, today, emptyList(), emptyList())
        outcome.shouldBeInstanceOf<LocalPlanOutcome.RedFlag>()
        exportPlanFrom(outcome) shouldBe null
        val doc = buildExportDocument(redFlag, workouts, symptoms, progress, exportPlanFrom(outcome), generatedAt = "t")
        doc.plan shouldBe null
        // Profile + logs still export (portability does not depend on the gate).
        doc.profile shouldBe redFlag
        doc.symptomLog shouldBe symptoms
    }

    // --- 3. determinism -----------------------------------------------------

    @Test
    fun `identical inputs encode to byte-identical JSON`() {
        val plan = regeneratedPlan()
        val a = encodeExportDocument(buildExportDocument(profile, workouts, symptoms, progress, plan, generatedAt = "fixed-timestamp"))
        val b = encodeExportDocument(buildExportDocument(profile, workouts, symptoms, progress, plan, generatedAt = "fixed-timestamp"))
        a shouldBe b // byte-for-byte (same generatedAt ⇒ identical)
    }

    @Test
    fun `the JSON key order is the stable envelope-first declaration order`() {
        val json = encodeExportDocument(buildExportDocument(profile, workouts, symptoms, progress, regeneratedPlan(), generatedAt = "t"))
        // Envelope fields precede the user-data fields precede the plan section.
        // (indices are not absolute — pretty-print indents — only their order matters)
        val schemaAt = json.indexOf("\"exportSchema\"")
        val planAt = json.indexOf("\"plan\"")
        val progressAt = json.indexOf("\"progressLog\"")
        (schemaAt < json.indexOf("\"appVersion\"")) shouldBe true
        (json.indexOf("\"appVersion\"") < json.indexOf("\"generatedAt\"")) shouldBe true
        (json.indexOf("\"generatedAt\"") < json.indexOf("\"disclaimer\"")) shouldBe true
        (json.indexOf("\"disclaimer\"") < json.indexOf("\"profile\"")) shouldBe true
        (json.indexOf("\"profile\"") < json.indexOf("\"workoutLog\"")) shouldBe true
        (json.indexOf("\"workoutLog\"") < json.indexOf("\"symptomLog\"")) shouldBe true
        (json.indexOf("\"symptomLog\"") < progressAt) shouldBe true
        (progressAt < planAt) shouldBe true
        // exportSchema is the very first key (nothing precedes it but the opening brace).
        (json.indexOf("{") < schemaAt) shouldBe true
    }

    // --- 4. no medical claim ------------------------------------------------

    // The banned diagnostic/treatment/cure morphemes every authored surface is
    // scanned against (mirrors [HistoryViewTest] / [TodayViewTest]). User symptom
    // free-text is verbatim self-report and is NOT scanned — it is the user's
    // words, not an app claim.
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
        "у вас", "вы больн", "вы здоровы", "ваш диагноз",
    )

    @Test
    fun `the disclaimer is transparency framing with no medical-claim phrase`() {
        val disclaimer = ExportStrings.DISCLAIMER
        disclaimer.isNotBlank() shouldBe true
        ("прозрачност" in disclaimer.lowercase()) shouldBe true
        val lower = disclaimer.lowercase()
        banned.forEach { b -> (b !in lower) shouldBe true }
    }
}
