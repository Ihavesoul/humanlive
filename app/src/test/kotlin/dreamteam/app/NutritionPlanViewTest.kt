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
 *    target line, one row per meal slot, the cataloged evidence ids, and an
 *    always-present disclaimer — each carrying the gate-Ok plan's data verbatim.
 *    A null (gate-blocked) plan renders nothing (no surface) — that branch is
 *    owned by PlanScreen; here the rendered view is a pure function of the plan.
 * 2. **No medical claim:** none of the strings the surface can ever render —
 *    across representative real plans (with/without body-fat % → Cunningham vs
 *    Mifflin resting energy; RECOMP vs FAT_LOSS) plus the disclaimer and every
 *    authored meal label/evidence id — contain a banned diagnostic / treatment /
 *    cure phrase. The surface is support, never diagnosis or prescription.
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
        val view = nutritionPlanView(p)

        view.targetLine shouldBe
            "Цель на день: ${p.target.targetKcal} ккал · Б${p.target.proteinG} Ж${p.target.fatG} У${p.target.carbohydrateG}"
    }

    @Test
    fun `the view renders one row per meal slot carrying the structure verbatim`() {
        val p = plan(withBodyFat, NutritionGoal.RECOMP)
        val view = nutritionPlanView(p)

        view.meals.map { it.label } shouldContainExactly p.structure.map { it.label }
        view.meals.forEachIndexed { i, row ->
            val m = p.structure[i]
            row.line shouldBe "${m.targetKcal} ккал · Б${m.proteinG} Ж${m.fatG} У${m.carbohydrateG}"
        }
        // The deterministic 4-slot structure (M4-A) is reflected in full.
        view.meals.size shouldBe 4
    }

    @Test
    fun `the view renders the cataloged evidence ids as the traceable link`() {
        val p = plan(withBodyFat, NutritionGoal.RECOMP)
        val view = nutritionPlanView(p)

        view.evidenceLine shouldBe "Основа: ${p.evidenceRefs.joinToString()}"
        // Every cited id is in the provisioned allowlist (no invented citations).
        p.evidenceRefs.forEach { (it in NUTRITION_EVIDENCE_IDS) shouldBe true }
    }

    @Test
    fun `the view always carries a non-empty support-not-treatment disclaimer`() {
        representativePlans.forEach { p ->
            val disclaimer = nutritionPlanView(p).disclaimer
            disclaimer.isNotBlank() shouldBe true
            // Support framing is explicit; medical authority is explicitly denied.
            ("поддержка" in disclaimer.lowercase()) shouldBe true
        }
    }

    @Test
    fun `no rendered nutrition string contains a banned medical-claim phrase`() {
        val rendered: List<String> = representativePlans.flatMap { p ->
            val view = nutritionPlanView(p)
            listOf(view.targetLine, view.evidenceLine, view.disclaimer) + view.meals.flatMap { listOf(it.label, it.line) }
        }
        rendered.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }
}
