package dreamteam.domain.nutrition

import dreamteam.domain.profile.Anthropometrics
import dreamteam.domain.profile.SexForEquations
import dreamteam.domain.safety.Recommendation
import dreamteam.domain.safety.RuleStatus
import dreamteam.domain.safety.RuleTrigger
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.SafetyRule
import dreamteam.domain.safety.SafetyVerdict
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.StructuralSafetyRules
import dreamteam.domain.safety.SurfacedPlan
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * M4-A done-when: the nutrition plan is produced by **deterministic,
 * evidence-linked math (no LLM, no I/O) and surfaced exclusively through
 * [SafetyGuardedGateway.surface]** — the same chokepoint training uses, so a
 * future nutrition/allergy contraindication can block a plan the way it blocks
 * an exercise. These tests pin the energy/macro/meal math against the PoC seed
 * profile, prove byte-identical reproducibility, and pin the gate's binding
 * behaviours (allow / block-unsourced / block-contraindicated).
 *
 * Seed profile = data/profile.json (the inviolable reference): male, 28y,
 * 188 cm, 83.2 kg, 21.2% BF => FFM 65.5616 => Katch-McArdle 1786 kcal
 * (matches data/derived_metrics.json -> derived.bmr_cunningham_kcal).
 */
class NutritionPlanGeneratorTest {

    private val seed = Anthropometrics(
        sex = SexForEquations.MALE,
        ageYears = 28,
        heightCm = 188.0,
        weightKg = 83.2,
        bodyFatPercent = 21.2,
    )

    /** Evidence allowlist = the cataloged ids the generator cites. */
    private val catalogEvidence = setOf(
        "CUNNINGHAM-1991", "MIFFLIN-1990", "MORTON-PROTEIN-2018", "ISSN-DIETS-2017", "WHO-ACTIVITY-2020",
    )

    /**
     * Nutrition-appropriate provisioning: the evidence allowlist (nutrition is
     * evidence-linked, not an exercise-library item) — NOT the exercise
     * allowlist, since [NUTRITION_ITEM_ID] is not an exercise id. Allergen
     * contraindications plug in here when the Safety Reviewer adds them.
     */
    private fun provisionedGateway(
        context: ScreeningContext = ScreeningContext(allowedEvidenceIds = catalogEvidence),
        rules: List<SafetyRule> = listOf(StructuralSafetyRules.evidenceAllowlist),
    ) = SafetyGuardedGateway(context, rules)

    // --- Deterministic energy/macro math (seed profile) ----------------------

    // Resting energy: Katch-McArdle 370 + 21.6 * 65.5616 = 1786.13 -> 1786 (CUNNINGHAM-1991).
    // PAL 1.4 (sedentary, WHO-ACTIVITY-2020) => TDEE = round(1786 * 1.4) = 2500.
    @Test
    fun `recomp target is resting energy times PAL with evidence-linked macros`() {
        val result = NutritionPlanGenerator(provisionedGateway()).generate(
            userId = "seed-user",
            body = seed,
            goal = NutritionGoal.RECOMP,
            recordedOn = "2026-07-23",
        )
        val plan = result.shouldBeInstanceOf<GeneratedNutritionPlan.Ok>().plan

        plan.target.targetKcal shouldBe 2500 // TDEE round(1786 * 1.4); RECOMP applies no deficit
        // protein 2.0 g/kg => round(2.0 * 83.2) = 166
        plan.target.proteinG shouldBe 166
        // fat 25% of kcal => round(0.25 * 2500 / 9) = round(69.44) = 69
        plan.target.fatG shouldBe 69
        // carb = remainder => round((2500 - 166*4 - 69*9) / 4) = round(303.75) = 304
        plan.target.carbohydrateG shouldBe 304

        // Energy accounting closes: protein + fat + carb kcal ~= target (rounding).
        val macroKcal = plan.target.proteinG * 4 + plan.target.fatG * 9 + plan.target.carbohydrateG * 4
        (kotlin.math.abs(macroKcal - plan.target.targetKcal) <= 12) shouldBe true
    }

    @Test
    fun `evidence refs resolve to the catalog and source the FFM equation`() {
        val plan = NutritionPlanGenerator(provisionedGateway())
            .generate("seed-user", seed, NutritionGoal.RECOMP, "2026-07-23")
            .shouldBeInstanceOf<GeneratedNutritionPlan.Ok>().plan

        plan.evidenceRefs shouldContainExactly
            listOf("CUNNINGHAM-1991", "MORTON-PROTEIN-2018", "ISSN-DIETS-2017", "WHO-ACTIVITY-2020")
        plan.target.evidenceRefs shouldBe plan.evidenceRefs
    }

    @Test
    fun `falls back to mifflin-st jeor evidence when no body-fat percent`() {
        val noBf = seed.copy(bodyFatPercent = null)
        val plan = NutritionPlanGenerator(provisionedGateway())
            .generate("seed-user", noBf, NutritionGoal.RECOMP, "2026-07-23")
            .shouldBeInstanceOf<GeneratedNutritionPlan.Ok>().plan

        // Mifflin-St Jeor 1872 * PAL 1.4 = 2620.8 -> 2621.
        plan.target.targetKcal shouldBe 2621
        plan.evidenceRefs shouldContainExactly
            listOf("MIFFLIN-1990", "MORTON-PROTEIN-2018", "ISSN-DIETS-2017", "WHO-ACTIVITY-2020")
    }

    @Test
    fun `fat-loss goal applies a conservative deficit off maintenance`() {
        val recomp = NutritionPlanGenerator(provisionedGateway())
            .generate("seed-user", seed, NutritionGoal.RECOMP, "2026-07-23")
            .shouldBeInstanceOf<GeneratedNutritionPlan.Ok>().plan
        val fatLoss = NutritionPlanGenerator(provisionedGateway())
            .generate("seed-user", seed, NutritionGoal.FAT_LOSS, "2026-07-23")
            .shouldBeInstanceOf<GeneratedNutritionPlan.Ok>().plan

        // RECOMP == MAINTAIN (maintenance); FAT_LOSS is 400 kcal below.
        fatLoss.target.targetKcal shouldBe recomp.target.targetKcal - 400
        fatLoss.target.targetKcal shouldBe 2100
        (fatLoss.target.targetKcal < recomp.target.targetKcal) shouldBe true
    }

    @Test
    fun `meal structure projects the daily target across four deterministic slots`() {
        val plan = NutritionPlanGenerator(provisionedGateway())
            .generate("seed-user", seed, NutritionGoal.RECOMP, "2026-07-23")
            .shouldBeInstanceOf<GeneratedNutritionPlan.Ok>().plan

        plan.structure.map { it.slot } shouldContainExactly listOf("breakfast", "lunch", "dinner", "snack")
        // Fractions sum to 1.0.
        plan.structure.sumOf { it.fraction } shouldBe 1.0
        // Each slot carries a non-negative macro projection (slot-level rounding
        // may differ from the daily total by a gram or two — that is expected).
        plan.structure.forEach { meal ->
            (meal.proteinG >= 0 && meal.fatG >= 0 && meal.carbohydrateG >= 0) shouldBe true
            (meal.targetKcal > 0) shouldBe true
        }
        // The largest slots (breakfast/lunch at 0.30) carry more kcal than snack (0.15).
        val breakfast = plan.structure.first { it.slot == "breakfast" }
        val snack = plan.structure.first { it.slot == "snack" }
        (breakfast.targetKcal > snack.targetKcal) shouldBe true
    }

    @Test
    fun `identical inputs produce a byte-identical plan - determinism`() {
        val first = NutritionPlanGenerator(provisionedGateway())
            .generate("seed-user", seed, NutritionGoal.RECOMP, "2026-07-23")
            .shouldBeInstanceOf<GeneratedNutritionPlan.Ok>()
        val second = NutritionPlanGenerator(provisionedGateway())
            .generate("seed-user", seed, NutritionGoal.RECOMP, "2026-07-23")
            .shouldBeInstanceOf<GeneratedNutritionPlan.Ok>()

        first.plan shouldBe second.plan
        first.plan.id shouldBe "seed-user@2026-07-23"
    }

    @Test
    fun `a different date mints a distinct versioned plan id`() {
        val a = NutritionPlanGenerator(provisionedGateway())
            .generate("seed-user", seed, NutritionGoal.RECOMP, "2026-07-23")
            .shouldBeInstanceOf<GeneratedNutritionPlan.Ok>().plan
        val b = NutritionPlanGenerator(provisionedGateway())
            .generate("seed-user", seed, NutritionGoal.RECOMP, "2026-07-30")
            .shouldBeInstanceOf<GeneratedNutritionPlan.Ok>().plan

        a.id shouldNotBe b.id
        // Same body/goal => identical macro math; only the version id + recordedOn differ.
        a.target.targetKcal shouldBe b.target.targetKcal
        a.target.proteinG shouldBe b.target.proteinG
        a.target.fatG shouldBe b.target.fatG
        a.target.carbohydrateG shouldBe b.target.carbohydrateG
        a.target.recordedOn shouldNotBe b.target.recordedOn
    }

    // --- Safety-gate wiring (the M4 hard invariant) --------------------------

    @Test
    fun `an evidence-linked plan surfaces Ok through the provisioned gateway`() {
        val gateway = provisionedGateway()
        gateway.isProvisioned shouldBe true

        val result = NutritionPlanGenerator(gateway).generate("seed-user", seed, NutritionGoal.RECOMP, "2026-07-23")

        result.shouldBeInstanceOf<GeneratedNutritionPlan.Ok>()
        result.plan.target.targetKcal shouldBe 2500
    }

    @Test
    fun `an unsourced nutrition plan is blocked - nothing is surfaced`() {
        // Evidence-by-allowlist: a candidate citing an id not in the catalog is
        // rejected — the model never invents citations (ADR 0001 §1).
        val gateway = provisionedGateway()
        val surfaced = gateway.surface(listOf(Recommendation(NUTRITION_ITEM_ID, listOf("GHOST-STUDY"))))

        surfaced.shouldBeInstanceOf<SurfacedPlan.Blocked>()
        surfaced.surfaced shouldBe emptyList() // nothing reaches the user
    }

    @Test
    fun `a nutrition plan carrying an allergen tag is blocked for an allergic context`() {
        // Future-proofing pin: the gate vets nutrition exactly like exercise. A
        // contraindication rule on a food tag + an allergy condition flag blocks
        // a plan carrying that tag — nutrition never overrides a contraindication.
        // (The rule here is a test stand-in; real allergen rules are the Safety
        // Reviewer's to author + the Evidence Analyst's to source.)
        val allergenRule = SafetyRule(
            id = "stub_allergen_peanut",
            description = "Peanut allergen: a plan referencing peanut is blocked for a peanut allergy.",
            trigger = RuleTrigger.ContraindicationStub(
                exerciseTag = "allergen:peanut",
                conditionFlag = "allergy:peanut",
                clinicalQuestion = "Stand-in allergen rule; real content is Safety Reviewer + Evidence Analyst authored.",
            ),
            decision = SafetyRule.Decision.BLOCK,
            evidenceRefs = listOf("ISSN-DIETS-2017"),
            status = RuleStatus.ACTIVE,
        )
        val ctx = ScreeningContext(
            allowedEvidenceIds = catalogEvidence,
            conditionFlags = setOf("allergy:peanut"),
        )
        val gateway = SafetyGuardedGateway(ctx, listOf(StructuralSafetyRules.evidenceAllowlist, allergenRule))

        val candidate = Recommendation(
            exerciseId = NUTRITION_ITEM_ID,
            evidenceRefs = listOf("CUNNINGHAM-1991", "ISSN-DIETS-2017"),
            exerciseTags = setOf("allergen:peanut"),
        )
        val verdict = gateway.vet(candidate)
        verdict.shouldBeInstanceOf<SafetyVerdict.Block>()
        verdict.ruleIds shouldContain "stub_allergen_peanut"

        // And end-to-end through surface(): nothing reaches the user.
        gateway.surface(listOf(candidate)).shouldBeInstanceOf<SurfacedPlan.Blocked>()
    }

    @Test
    fun `an unprovisioned gateway blocks every nutrition plan by default`() {
        // Block-by-default (DRE-7): an empty active ruleset blocks the plan even
        // though it is fully evidence-linked — unsafe output cannot reach the
        // user before the gate is provisioned.
        val unprovisioned = SafetyGuardedGateway(ScreeningContext(allowedEvidenceIds = catalogEvidence), emptyList())
        unprovisioned.isProvisioned shouldBe false

        val result = NutritionPlanGenerator(unprovisioned).generate("seed-user", seed, NutritionGoal.RECOMP, "2026-07-23")

        val blocked = result.shouldBeInstanceOf<GeneratedNutritionPlan.Blocked>()
        blocked.ruleIds.isEmpty() shouldBe true
    }
}
