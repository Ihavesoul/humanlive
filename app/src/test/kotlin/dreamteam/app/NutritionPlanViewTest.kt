package dreamteam.app

import dreamteam.domain.nutrition.GeneratedNutritionPlan
import dreamteam.domain.nutrition.NutritionGoal
import dreamteam.domain.nutrition.NutritionPlan
import dreamteam.domain.nutrition.NutritionPlanGenerator
import dreamteam.domain.profile.Anthropometrics
import dreamteam.domain.profile.SexForEquations
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.StructuralSafetyRules
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * M4-C ([DRE-57](/DRE/issues/DRE-57)) — the two guarantees the nutrition
 * **surface** (not the wiring) must give, enforced as code rather than relied on.
 * Mirrors [AdaptationNoteTest] (M3-C, [DRE-53](/DRE/issues/DRE-53)):
 *
 * 1. **Renders from the surfaced plan:** [nutritionPlanView] emits the daily
 *    target line, one row per meal slot, a READABLE citation per evidence ref
 *    (M6-B: resolved via the catalog — author/year + keyFinding + evidenceLevel,
 *    not raw ids), and an always-present disclaimer — each carrying the gate-Ok
 *    plan's data verbatim. A null (gate-blocked) plan renders nothing (no
 *    surface) — that branch is owned by PlanScreen; here the rendered view is a
 *    pure function of the plan.
 * 2. **No medical claim:** none of the strings the APP AUTHORS (not the verbatim
 *    catalog text) — across representative real plans (with/without body-fat % →
 *    Cunningham vs Mifflin resting energy; RECOMP vs FAT_LOSS) plus the disclaimer
 *    and every authored meal label — contain a banned diagnostic / treatment /
 *    cure phrase. The catalog citation text is vetted evidence vocabulary and is
 *    NOT crude-substring-scanned: it legitimately contains study words
 *    ("health", "prescription", "treatment") inside study titles/findings. The
 *    catalog-side claim guard is the always-present support disclaimer +
 *    rendering catalog text verbatim (no invented claim).
 *
 * The rendered text is a pure function of the plan, so a JVM assertion over it
 * is the smallest sufficient check of the Compose surface without a device or
 * Compose-instrumentation dependency (DRE-53 "or screenshot-gated check",
 * engineer-default). The plans are produced by the *real*
 * [NutritionPlanGenerator] so the test scans the authored meal labels + evidence
 * ids, not a duplicated copy that would drift. The generator math itself is
 * pinned in `:core:domain` ([NutritionPlanGeneratorTest]).
 */
class NutritionPlanViewTest {

    // The seed profile = data/profile.json (male, 28y, 188 cm, 83.2 kg, 21.2% BF).
    private val withBodyFat = Anthropometrics(
        sex = SexForEquations.MALE,
        ageYears = 28,
        heightCm = 188.0,
        weightKg = 83.2,
        bodyFatPercent = 21.2,
    )
    private val noBodyFat = withBodyFat.copy(bodyFatPercent = null)

    private fun gateway() = SafetyGuardedGateway(
        ScreeningContext(allowedEvidenceIds = NUTRITION_EVIDENCE_IDS),
        listOf(StructuralSafetyRules.evidenceAllowlist),
    )

    private fun plan(body: Anthropometrics, goal: NutritionGoal): NutritionPlan =
        NutritionPlanGenerator(gateway()).generate(userId = "seed-user", body = body, goal = goal, recordedOn = "2026-07-23")
            .shouldBeInstanceOf<GeneratedNutritionPlan.Ok>().plan

    // M6-B: the offline-first resolver over the bundled catalog, as a JVM test
    // reads it (classpath — app/build.gradle.kts adds repo-root `data/` as a test
    // resource). Byte-identical to the bundled asset [loadEvidenceResolver] decodes.
    private val resolver: EvidenceResolver =
        EvidenceResolver.fromJson(NutritionPlanViewTest::class.java.getResourceAsStream("/evidence_catalog.json")!!.use { it.readBytes().decodeToString() })

    // Representative plans the UI can render: every energy-equation branch and
    // goal variant, so the banned-phrase scan covers the real authored strings.
    private val representativePlans: List<NutritionPlan> = listOf(
        plan(withBodyFat, NutritionGoal.RECOMP), // Cunningham resting energy
        plan(noBodyFat, NutritionGoal.RECOMP), // Mifflin-St Jeor fallback
        plan(withBodyFat, NutritionGoal.FAT_LOSS), // deficit branch
    )

    // Banned substrings (lowercased) — same intent as [AdaptationNoteTest]: the
    // surface may never assert a diagnosis, claim to treat/cure, or frame the
    // user in second-person clinical terms. The disclaimer phrases support
    // ("поддержка, а не предписание и не замена врача или диетолога") and so
    // carries none of these assertion roots.
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
        "болезнь",
        "у вас", "вы больн", "вы здоровы", "ваш диагноз",
        "предписываю", "назначаю", "прописываю",
        "diagnos", "treat", "cure", "heal", "disease", "you have", "you are", "prescribe",
    )

    @Test
    fun `the view renders the daily target line from the plan target verbatim`() {
        val p = plan(withBodyFat, NutritionGoal.RECOMP)
        val view = nutritionPlanView(p, resolver)

        view.targetLine shouldBe
            "Цель на день: ${p.target.targetKcal} ккал · Б${p.target.proteinG} Ж${p.target.fatG} У${p.target.carbohydrateG}"
    }

    @Test
    fun `the view renders one row per meal slot carrying the structure verbatim`() {
        val p = plan(withBodyFat, NutritionGoal.RECOMP)
        val view = nutritionPlanView(p, resolver)

        view.meals.map { it.label } shouldContainExactly p.structure.map { it.label }
        view.meals.forEachIndexed { i, row ->
            val m = p.structure[i]
            row.line shouldBe "${m.targetKcal} ккал · Б${m.proteinG} Ж${m.fatG} У${m.carbohydrateG}"
        }
        // The deterministic 4-slot structure (M4-A) is reflected in full.
        view.meals.size shouldBe 4
    }

    @Test
    fun `the view renders a READABLE citation per evidence ref - not raw ids (M6-B)`() {
        val p = plan(withBodyFat, NutritionGoal.RECOMP)
        val view = nutritionPlanView(p, resolver)

        // One resolved row per ref, in order.
        view.evidenceRows.map { it.id } shouldContainExactly p.evidenceRefs
        view.evidenceRows.forEach { row -> row.resolved shouldBe true }
        // M6-B core guarantee: each rendered line shows a REAL citation (it is
        // NOT the bare id the old `"Основа: ..."` join showed) and carries the
        // evidenceLevel label + keyFinding. Asserted against a known nutrition
        // id present in every RECOMP plan (protein guidance).
        val proteinRow = view.evidenceRows.first { it.id == "MORTON-PROTEIN-2018" }
        ("Morton" in proteinRow.line) shouldBe true // author
        ("уровень: high" in proteinRow.line) shouldBe true // evidenceLevel label
        // The line is the full readable citation, never the raw id alone.
        (proteinRow.line.length > "MORTON-PROTEIN-2018".length) shouldBe true
        // Every cited id is still in the provisioned allowlist (no invented citations).
        p.evidenceRefs.forEach { (it in NUTRITION_EVIDENCE_IDS) shouldBe true }
    }

    @Test
    fun `the view always carries a non-empty support-not-treatment disclaimer`() {
        representativePlans.forEach { p ->
            val disclaimer = nutritionPlanView(p, resolver).disclaimer
            disclaimer.isNotBlank() shouldBe true
            // Support framing is explicit; medical authority is explicitly denied.
            ("поддержка" in disclaimer.lowercase()) shouldBe true
        }
    }

    @Test
    fun `no APP-authored nutrition string contains a banned medical-claim phrase`() {
        // M6-B note: the crude substring scan covers ONLY strings the app authors
        // (target line, meal labels, disclaimer) — NOT the verbatim catalog
        // citation rows, which legitimately carry study vocabulary
        // ("health", "prescription", "treatment") inside study titles/findings.
        // Crude-scanning catalog text would false-positive on vetted evidence.
        val rendered: List<String> = representativePlans.flatMap { p ->
            val view = nutritionPlanView(p, resolver)
            listOf(view.targetLine, view.disclaimer) + view.meals.flatMap { listOf(it.label, it.line) }
        }
        rendered.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }
}
