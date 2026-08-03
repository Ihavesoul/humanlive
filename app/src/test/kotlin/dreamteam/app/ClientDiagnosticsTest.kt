package dreamteam.app

import dreamteam.app.data.Profile
import dreamteam.app.data.ProgressRow
import dreamteam.app.data.ExerciseNoteOutcome
import dreamteam.app.data.ExerciseNoteRow
import dreamteam.app.data.SymptomEntry
import dreamteam.app.data.WorkoutCompletion
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * M10-D ([DRE-191](/DRE/issues/DRE-191)) — the deterministic JVM checks that pin
 * the local-diagnostics path. Mirrors the [ClientExportTest] pure-core pattern:
 * the assembly/encode core is pure over fixed inputs, so a JVM assertion is the
 * smallest sufficient check without a device.
 *
 * Pins:
 *  1. **Round-trip / completeness** — encode → decode preserves every field.
 *  2. **Determinism** — same inputs → byte-identical document (modulo generatedAt).
 *  3. **Privacy (≤ export)** — user free-text (symptom + exercise-note words)
 *     NEVER appears in the encoded bundle; the diagnostics is strictly leaner
 *     than the M7 export.
 *  4. **Gate decisions** — each outcome branch maps to the correct per-gate
 *      allow/block/not_reached summary (no_profile / ok / red_flag /
 *      gateway_blocked).
 *  5. **recentActions** — newest-first, capped at the window, type+date only.
 *  6. **No medical claim** — authored strings scan-clean.
 *  7. **Schema stability** — diagnosticsSchema pinned to its current literal.
 */
class ClientDiagnosticsTest {

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
        SymptomEntry("2026-07-15", "lumbar tension"), // free text — must NOT leak
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
    private val exerciseNotes = listOf(
        ExerciseNoteRow("week1-dayA-squat", "back_squat_goblet", "keep chest tall", ExerciseNoteOutcome.OK, "2026-07-21"), // free text — must NOT leak
        ExerciseNoteRow("week1-dayB-hinge", "romanian_deadlift", "hip hinge soft at the bottom", ExerciseNoteOutcome.HARD, "2026-07-22"),
    )

    private fun doc(
        p: Profile? = profile,
        gen: String = "2026-07-23T10:00:00Z",
        dbVer: Int = 4,
    ): DiagnosticsDocument = buildDiagnosticsDocument(
        profile = p,
        workouts = workouts,
        symptoms = symptoms,
        progress = progress,
        exerciseNotes = exerciseNotes,
        today = today,
        generatedAt = gen,
        dbSchemaVersion = dbVer,
    )

    // --- 1. round-trip / completeness --------------------------------------

    @Test
    fun `every diagnostics field survives the encode-decode round-trip`() {
        val d = doc()
        val encoded = encodeDiagnosticsDocument(d)
        val decoded = exportJson.decodeFromString(DiagnosticsDocument.serializer(), encoded)

        decoded.diagnosticsSchema shouldBe DIAGNOSTICS_SCHEMA
        decoded.appVersion shouldBe APP_VERSION
        decoded.buildVersion shouldBe APP_BUILD
        decoded.generatedAt shouldBe "2026-07-23T10:00:00Z"
        decoded.disclaimer shouldBe DiagnosticsStrings.DISCLAIMER
        decoded.store.hasProfile shouldBe true
        decoded.store.workoutCount shouldBe 2
        decoded.store.symptomCount shouldBe 2
        decoded.store.progressCount shouldBe 2
        decoded.store.exerciseNoteCount shouldBe 2
        decoded.dataIntegrity.dbSchemaVersion shouldBe 4
        decoded.nonFatalErrors shouldHaveSize 0
    }

    // --- 2. determinism -----------------------------------------------------

    @Test
    fun `identical inputs encode to byte-identical JSON`() {
        val a = encodeDiagnosticsDocument(doc(gen = "fixed"))
        val b = encodeDiagnosticsDocument(doc(gen = "fixed"))
        a shouldBe b
    }

    @Test
    fun `the JSON key order is the stable envelope-first declaration order`() {
        val json = encodeDiagnosticsDocument(doc())
        val topKeys = json.lineSequence()
            .filter { it.startsWith("  \"") && !it.startsWith("   \"") }
            .map { it.substringAfter("  \"").substringBefore("\":") }
            .toList()
        topKeys shouldBe listOf(
            "diagnosticsSchema", "appVersion", "buildVersion", "generatedAt", "disclaimer",
            "store", "recentActions", "planGeneration", "gateDecisions", "dataIntegrity", "nonFatalErrors",
        )
    }

    // --- 3. privacy (strictly less than the export) ------------------------

    @Test
    fun `user free-text never appears in the diagnostics bundle`() {
        // The diagnostics carries COUNTS + DATES only — never the symptom or
        // exercise-note TEXT the export copies verbatim. This is the hard privacy
        // invariant: the bundle is strictly ≤ the M7 export's disclosure.
        val encoded = encodeDiagnosticsDocument(doc())
        val lower = encoded.lowercase()
        listOf("lumbar tension", "tired", "keep chest tall", "hip hinge soft at the bottom").forEach { phrase ->
            (phrase.lowercase() !in lower) shouldBe true
        }
    }

    @Test
    fun `profile objective fields are not copied into the diagnostics`() {
        // Only the scoliosis flag + red-flag COUNT are summarized; height/weight/
        // age/sex are not duplicated into the diagnostics (leaner than the export).
        val encoded = encodeDiagnosticsDocument(doc())
        val lower = encoded.lowercase()
        listOf("83.2", "188.0", "21.2", "\"sex\"", "\"male\"").forEach { token ->
            (token.lowercase() !in lower) shouldBe true
        }
    }

    // --- 4. gate decisions (every outcome branch) --------------------------

    @Test
    fun `a scan-clean profile yields an ok outcome with all gates allowing`() {
        val d = doc()
        d.planGeneration.outcome shouldBe "ok"
        d.planGeneration.output.trainingWeeks shouldNotBe null
        d.planGeneration.output.blockingRuleIds shouldHaveSize 0
        d.gateDecisions.medicalSafety shouldBe "allow"
        d.gateDecisions.assignmentGateway shouldBe "allow"
        // nutrition gate either allows (present) or blocks (null) — both valid.
        d.gateDecisions.nutritionGate shouldBeIn listOf("allow", "block")
        d.gateDecisions.gatewayProvisioned shouldBe true
        d.gateDecisions.activeRuleCount shouldNotBe 0
    }

    @Test
    fun `a red-flag profile yields a red_flag outcome with the medical gate blocking`() {
        val redFlag = profile.copy(redFlags = listOf("other"))
        val d = doc(p = redFlag)
        d.planGeneration.outcome shouldBe "red_flag"
        d.planGeneration.input.redFlagCount shouldBe 1
        d.planGeneration.output.trainingWeeks shouldBe null
        d.gateDecisions.medicalSafety shouldBe "block"
        d.gateDecisions.assignmentGateway shouldBe "not_reached"
        d.gateDecisions.nutritionGate shouldBe "not_reached"
    }

    @Test
    fun `a null profile yields a no_profile outcome with every gate not_reached`() {
        val d = doc(p = null)
        d.store.hasProfile shouldBe false
        d.planGeneration.outcome shouldBe "no_profile"
        d.planGeneration.input.redFlagCount shouldBe 0
        d.planGeneration.output.trainingWeeks shouldBe null
        d.gateDecisions.medicalSafety shouldBe "not_reached"
        d.gateDecisions.assignmentGateway shouldBe "not_reached"
        d.gateDecisions.nutritionGate shouldBe "not_reached"
        // A pre-onboarding user still has their data volume + gate provisioning.
        d.store.workoutCount shouldBe 2
        d.gateDecisions.gatewayProvisioned shouldBe true
    }

    @Test
    fun `a gateway-blocked outcome maps to assignment-gateway block with the triggering rule ids`() {
        // GatewayBlocked is hard to trigger via a real profile, so the pure mapper
        // is exercised with a synthetic outcome — this is why it was extracted.
        val mapped = diagnosticsGateDecisions(
            outcome = LocalPlanOutcome.GatewayBlocked(listOf("contraindication_xyz")),
            gatewayProvisioned = true,
            activeRuleCount = 5,
        )
        mapped.medicalSafety shouldBe "allow"
        mapped.assignmentGateway shouldBe "block"
        mapped.nutritionGate shouldBe "not_reached"
        mapped.activeRuleCount shouldBe 5

        val planGen = diagnosticsPlanGeneration(
            profile = profile,
            outcome = LocalPlanOutcome.GatewayBlocked(listOf("contraindication_xyz")),
            today = today,
            symptoms = symptoms,
            progress = progress,
            signal = DiagnosticsSignal("none", null),
        )
        planGen.outcome shouldBe "gateway_blocked"
        planGen.output.blockingRuleIds shouldBe listOf("contraindication_xyz")
        planGen.output.trainingWeeks shouldBe null
    }

    // --- 5. recentActions ---------------------------------------------------

    @Test
    fun `recentActions is newest-first, capped at the window, type and date only`() {
        val actions = recentActionsFrom(workouts, symptoms, progress, exerciseNotes)
        actions.size shouldBe 8 // under the window of 8 → all present, none dropped
        // newest date first
        actions.first().date shouldBe "2026-07-22"
        actions.last().date shouldBe "2026-07-01"
        // type + date only — each type is one of the closed action kinds, and the
        // date is a YYYY-MM-DD token (no free text rides on an action row).
        val validTypes = setOf("workout", "symptom", "progress", "exercise_note")
        val dateShape = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        actions.forEach { a ->
            a.type shouldBeIn validTypes
            dateShape.matches(a.date) shouldBe true
        }
    }

    @Test
    fun `recentActions caps at the configured window when the store exceeds it`() {
        val many = (1..30).map { WorkoutCompletion("s$it", "e$it", "2026-08-%02d".format(it)) }
        val actions = recentActionsFrom(many, emptyList(), emptyList(), emptyList())
        actions shouldHaveSize DIAGNOSTICS_ACTION_WINDOW
        actions.first().date shouldBe "2026-08-30" // newest first
    }

    // --- 6. no medical claim ------------------------------------------------

    // The banned morphemes every authored surface is scanned against. NOTE:
    // «диагности» is in this list — so the diagnostics strings must use
    // «самопроверка» (self-check), never «диагностика».
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
        "у вас", "вы больн", "вы здоровы", "ваш диагноз",
    )

    @Test
    fun `all authored diagnostics strings are scan-clean of medical-claim phrases`() {
        val all = DiagnosticsStrings.all + DiagnosticsUiStrings.all
        all.forEach { s ->
            val lower = s.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }

    @Test
    fun `the disclaimer is support framing and carries no medical claim`() {
        val d = DiagnosticsStrings.DISCLAIMER
        d.isNotBlank() shouldBe true
        ("поддержив" in d.lowercase()) shouldBe true // support framing present
    }

    // --- 7. schema stability ------------------------------------------------

    @Test
    fun `the diagnostics schema version is pinned to its current additive literal`() {
        // Regression pin (mirrors the export's M10-B literal pin): assert the
        // LITERAL, not the constant — bumping the constant must fail this test
        // intentionally. ponytail: hand-synced literal; ceiling is a future
        // additive bump — update this literal as part of that change.
        doc().diagnosticsSchema shouldBe 1
        DIAGNOSTICS_SCHEMA shouldBe 1
    }

}
