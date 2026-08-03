package dreamteam.app

import dreamteam.domain.EvidenceId
import dreamteam.domain.nutrition.GeneratedNutritionPlan
import dreamteam.domain.nutrition.NutritionGoal
import dreamteam.domain.nutrition.NutritionPlan
import dreamteam.domain.nutrition.NutritionPlanGenerator
import dreamteam.domain.profile.Anthropometrics
import dreamteam.domain.profile.SexForEquations
import dreamteam.domain.safety.ContraindicationStubs
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.StructuralSafetyRules
import dreamteam.domain.training.BaselineProgram
import dreamteam.domain.training.DeterministicPlanGenerator
import dreamteam.domain.training.ExerciseAssignment
import dreamteam.domain.training.GeneratedPlan
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * M6-B ([DRE-68](/DRE/issues/DRE-68)) — pins the guarantees of the SHARED render
 * path ([resolveCitations]) that both the nutrition + training views now render
 * against, enforced as code rather than relied on. Mirrors the M6-A
 * [ClientEvidenceResolverTest] shape: the cited sets come from the SAME
 * deterministic generators the client surfaces, and the catalog is read off the
 * test classpath (byte-identical to the bundled asset).
 *
 * Guarantees (the smallest thing that fails if M6-B breaks):
 * 1. A RESOLVED ref renders a real citation (author/year) + the evidenceLevel
 *    label + keyFinding — NEVER the raw id alone the old `joinToString()` showed.
 * 2. A GHOST id renders the blocked-until-sourced placeholder and `resolved=false`
 *    — never an invented citation (honors EvidenceLinked).
 * 3. Training assignment rows surface at least one resolved citation where
 *    [ExerciseAssignment.evidenceRefs] is non-empty (was surfaced as nothing
 *    before M6-B), using the SAME render path as nutrition.
 *
 * Claim guard: the no-claim disclaimer on the nutrition surface is pinned in
 * [NutritionPlanViewTest]; the catalog citation text is vetted evidence
 * vocabulary and is deliberately NOT crude-substring-scanned here (study titles
 * legitimately contain "health"/"prescription"/"treatment").
 */
class EvidenceCitationRenderTest {

    /** The resolver over the bundled catalog, as a JVM test reads it (classpath). */
    private fun bundledResolver(): EvidenceResolver {
        val raw = EvidenceCitationRenderTest::class.java.getResourceAsStream("/evidence_catalog.json")!!
            .use { it.readBytes().decodeToString() }
        return EvidenceResolver.fromJson(raw)
    }

    private val resolver: EvidenceResolver by lazy { bundledResolver() }

    @Test
    fun `a resolved ref renders a real citation with author + evidenceLevel + keyFinding - not a raw id`() {
        val rows = resolveCitations(listOf("MORTON-PROTEIN-2018"), resolver)

        rows.size shouldBe 1
        val row = rows.single()
        row.resolved shouldBe true
        // M6-B core: a real citation (author/year), not the bare id.
        ("Morton" in row.line) shouldBe true
        // The controlled-vocabulary evidenceLevel label is present verbatim.
        ("уровень: high" in row.line) shouldBe true
        // The line is the readable citation, never the raw id alone.
        (row.line.length > "MORTON-PROTEIN-2018".length) shouldBe true
        ("MORTON-PROTEIN-2018" == row.line) shouldBe false
    }

    @Test
    fun `a ghost id renders the blocked-until-sourced placeholder - never an invented citation`() {
        val rows = resolveCitations(listOf("GHOST-STUDY"), resolver)

        rows.size shouldBe 1
        val row = rows.single()
        row.resolved shouldBe false
        // The exact M6-A-contract placeholder is rendered, not a fabricated citation.
        row.line shouldBe EVIDENCE_NOT_SOURCED
        // No invented author/year leaks in for an unresolved id.
        ("GHOST-STUDY" in row.line) shouldBe false
    }

    @Test
    fun `the render is one row per ref in order, mixing resolved and ghost`() {
        val refs: List<EvidenceId> = listOf("ACSM-RT-2026", "GHOST-STUDY", "ISSN-DIETS-2017")
        val rows = resolveCitations(refs, resolver)

        rows.map { it.id } shouldBe refs
        rows[0].resolved shouldBe true
        rows[1].resolved shouldBe false
        rows[2].resolved shouldBe true
        // Empty refs → empty render (no placeholder noise when nothing is cited).
        resolveCitations(emptyList(), resolver).shouldBeEmpty()
    }

    /**
     * The surfaced training assignments the client renders — produced by the SAME
     * provisioned gateway [generateLocalPlan] uses for a generic (unflagged) user.
     */
    private fun surfacedTrainingAssignments(): List<ExerciseAssignment> {
        val gateway = SafetyGuardedGateway(
            ScreeningContext(
                allowedExerciseIds = BaselineProgram.exerciseIds,
                allowedEvidenceIds = BaselineProgram.evidenceIds,
            ),
            StructuralSafetyRules.all + ContraindicationStubs.all,
        )
        val plan = DeterministicPlanGenerator(gateway)
            .generate(userId = "local", createdAt = "2026-07-23")
            .shouldBeInstanceOf<GeneratedPlan.Ok>()
            .plan
        return plan.weeks.flatMap { it.sessions }.flatMap { it.assignments }
    }

    @Test
    fun `surfaced training assignments render at least one resolved citation where evidenceRefs is non-empty`() {
        val assignments = surfacedTrainingAssignments()
        assignments.shouldNotBeEmpty()

        // Sanity: at least one surfaced assignment carries evidence (else vacuous).
        val withRefs = assignments.filter { it.evidenceRefs.isNotEmpty() }
        withRefs.shouldNotBeEmpty()

        // M6-B: every assignment with refs renders ≥1 RESOLVED citation via the
        // same render path as nutrition — was surfaced as nothing before.
        withRefs.forEach { a ->
            val rows = resolveCitations(a.evidenceRefs, resolver)
            rows.size shouldBe a.evidenceRefs.size
            rows.filter { it.resolved }.shouldNotBeEmpty()
            // Each resolved line is a real citation, not the raw id.
            rows.filter { it.resolved }.forEach { (it.line.length > it.id.length) shouldBe true }
        }

        // A known training id is among the surfaced, resolved set (real citation).
        val allResolvedIds = assignments.flatMap { it.evidenceRefs }.distinct()
        allResolvedIds.shouldContain("ACSM-RT-2026")
        val acsm = resolveCitations(listOf("ACSM-RT-2026"), resolver).single()
        ("American College of Sports Medicine" in acsm.line) shouldBe true
    }

    /** A surfaced nutrition plan (Cunningham branch) — same generator as the client. */
    private fun surfacedNutritionPlan(): NutritionPlan {
        val gateway = SafetyGuardedGateway(
            ScreeningContext(allowedEvidenceIds = NUTRITION_EVIDENCE_IDS),
            listOf(StructuralSafetyRules.evidenceAllowlist),
        )
        val body = Anthropometrics(
            sex = SexForEquations.MALE,
            ageYears = 28,
            heightCm = 188.0,
            weightKg = 83.2,
            bodyFatPercent = 21.2,
        )
        return NutritionPlanGenerator(gateway)
            .generate(userId = "local", body = body, goal = NutritionGoal.RECOMP, recordedOn = "2026-07-23")
            .shouldBeInstanceOf<GeneratedNutritionPlan.Ok>()
            .plan
    }

    @Test
    fun `surfaced nutrition plan renders every ref as a resolved citation - 0 raw ids`() {
        val plan = surfacedNutritionPlan()
        val view = nutritionPlanView(plan, resolver)

        // Every ref renders resolved (M6-A 0-dangling) with a real citation line.
        view.evidenceRows.size shouldBe plan.evidenceRefs.size
        view.evidenceRows.forEach { it.resolved shouldBe true }
        // No rendered nutrition citation is a bare raw id like `energy_estimation`.
        view.evidenceRows.forEach { row -> (row.line == row.id) shouldBe false }
    }

    /**
     * M9 polish ([DRE-180](/DRE/issues/DRE-180)): the readable + tappable citation
     * card renders from STRUCTURED fields on [ResolvedCitation] (title +
     * keyFinding always visible; design/application/limitations + sourceUrl unfold
     * on tap). Pin they populate verbatim from the catalog for a resolved id —
     * the smallest thing that fails if the data path breaks — and stay null for a
     * ghost id (no tap target behind a placeholder).
     */
    @Test
    fun `a resolved ref carries structured title + keyFinding + detail + sourceUrl for the readable card`() {
        val src = resolver.resolveEvidence("MORTON-PROTEIN-2018")!!
        val row = resolveCitations(listOf("MORTON-PROTEIN-2018"), resolver).single()

        row.resolved shouldBe true
        row.title shouldBe src.citation
        row.keyFinding shouldBe src.keyFinding
        row.design shouldBe src.design
        row.application shouldBe src.application
        row.limitations shouldBe src.limitations
        // The catalog's vetted source URL lands verbatim (0 naked links → button).
        row.sourceUrl shouldBe src.url
        row.sourceUrl shouldNotBe null
    }

    @Test
    fun `a ghost id carries no structured title or sourceUrl - nothing to tap`() {
        val row = resolveCitations(listOf("GHOST-STUDY"), resolver).single()

        row.resolved shouldBe false
        row.title shouldBe null
        row.keyFinding shouldBe null
        row.design shouldBe null
        row.sourceUrl shouldBe null
    }

    // Banned substrings (lowercased) — same list as the surface tests: the shared
    // citation card's authored strings may never assert a diagnosis or treat/cure.
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
        "болезнь",
        "у вас", "вы больн", "вы здоровы", "ваш диагноз",
        "предписываю", "назначаю", "прописываю",
        "diagnos", "treat", "cure", "heal", "disease", "you have", "you are", "prescribe",
    )

    @Test
    fun `no authored evidence-citation-card string contains a banned medical-claim phrase`() {
        EvidenceCitationStrings.all.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }
}
