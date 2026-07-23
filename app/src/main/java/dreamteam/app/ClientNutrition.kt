package dreamteam.app

import dreamteam.app.data.Profile
import dreamteam.domain.nutrition.GeneratedNutritionPlan
import dreamteam.domain.nutrition.NutritionGoal
import dreamteam.domain.nutrition.NutritionPlan
import dreamteam.domain.nutrition.NutritionPlanGenerator
import dreamteam.domain.profile.Anthropometrics
import dreamteam.domain.profile.SexForEquations
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.StructuralSafetyRules

/**
 * M4-C ([DRE-57](/DRE/issues/DRE-57)): the client-side nutrition surface, the
 * mirror of the M3-C adaptation surface ([ClientAdaptation] /
 * [adaptationNote]). Two halves:
 *
 *  - [localNutritionPlan] — the offline-first wiring: builds the deterministic
 *    [NutritionPlan] locally from the stored profile via the SAME
 *    [NutritionPlanGenerator] the backend uses, behind a nutrition-appropriate
 *    gate. No server round-trip, no network to view the plan (ADR 0002).
 *  - [nutritionPlanView] — a **pure** render of the surfaced (gate-Ok) plan,
 *    extracted from the Compose tree so a JVM test
 *    ([NutritionPlanViewTest](../../test/kotlin/dreamteam/app/NutritionPlanViewTest.kt))
 *    can pin the two M4-C guarantees: (1) it renders from the plan — target +
 *    meal structure + evidence refs carried verbatim, and (2) no rendered
 *    string contains a banned medical-claim phrase; an explicit
 *    support-not-treatment disclaimer is always present.
 *
 * Same framing as M3-C: support, never diagnosis / prescription / claim.
 */

/**
 * The nutrition evidence allowlist for the client gate — the cataloged ids the
 * deterministic generator cites (resting-energy equation + protein/fat guidance
 * + activity). Mirrors data/evidence_catalog.json. The model never invents ids
 * (ADR 0001 §1), so this closed set is the complete provisioning; a cited id not
 * in it is blocked by the evidence allowlist.
 */
internal val NUTRITION_EVIDENCE_IDS: Set<String> = setOf(
    "CUNNINGHAM-1991", // FFM / Katch-McArdle resting energy (when body-fat % logged)
    "MIFFLIN-1990", // Mifflin-St Jeor fallback resting energy
    "MORTON-PROTEIN-2018", // protein dose (g/kg)
    "ISSN-DIETS-2017", // diet / macro distribution
    "WHO-ACTIVITY-2020", // sedentary PAL baseline
)

/**
 * Builds the deterministic [NutritionPlan] locally from the stored [Profile],
 * behind a nutrition-appropriate [SafetyGuardedGateway]. Pure domain call (no
 * Android, no I/O); identical profile → identical plan, same as the backend.
 *
 * [goal] defaults to RECOMP (== maintenance in the generator: no deficit) — a
 * conservative *support* baseline. An explicit onboarding goal selector is a
 * later design slice (founder consent), not built here; noted, not built.
 *
 * **Gate provisioning (M4 invariant).** Provisioned with the **evidence
 * allowlist only** (+ future allergen contraindications once the Safety Reviewer
 * authors them) — NOT the exercise allowlist, because a nutrition plan projects
 * to [dreamteam.domain.nutrition.NUTRITION_ITEM_ID], which is not a library
 * exercise id and would be wrongly blocked by the exercise allowlist. A red-flag
 * profile is still blocked upstream by [generateLocalPlan] (MedicalSafety) before
 * this runs; only a gate-Ok plan reaches the UI.
 */
internal fun localNutritionPlan(
    profile: Profile,
    today: String,
    goal: NutritionGoal = NutritionGoal.RECOMP,
): GeneratedNutritionPlan {
    val body = Anthropometrics(
        sex = if (profile.sex.equals("female", ignoreCase = true)) SexForEquations.FEMALE else SexForEquations.MALE,
        ageYears = profile.age,
        heightCm = profile.height,
        weightKg = profile.weight,
        bodyFatPercent = profile.bodyFat,
    )
    val gateway = SafetyGuardedGateway(
        ScreeningContext(allowedEvidenceIds = NUTRITION_EVIDENCE_IDS),
        listOf(StructuralSafetyRules.evidenceAllowlist),
    )
    return NutritionPlanGenerator(gateway).generate(
        userId = "local",
        body = body,
        goal = goal,
        recordedOn = today,
    )
}

/**
 * What [PlanScreen]/[TodayScreen] show for a surfaced [NutritionPlan]: a plain,
 * support-framed render — the daily target line, the deterministic meal rows, a
 * READABLE citation per evidence ref (M6-B: resolved via [resolveCitations] —
 * author/year + keyFinding + evidenceLevel, no invented citation; a ghost id
 * renders the blocked-until-sourced placeholder), and an explicit no-medical-
 * claim disclaimer. Pure (no Android, no I/O) so a JVM test pins render-from-
 * signal + no-claim, mirroring [adaptationNote].
 */
internal data class NutritionPlanView(
    val targetLine: String,
    val meals: List<MealRow>,
    /** M6-B: one READABLE citation per evidence ref (resolved), not raw ids. */
    val evidenceRows: List<ResolvedCitation>,
    /** Always-present support-not-treatment disclaimer (M4-C invariant). */
    val disclaimer: String,
)

/** One deterministic meal slot's rendered line (label is the authored RU slot label). */
internal data class MealRow(val label: String, val line: String)

internal fun nutritionPlanView(plan: NutritionPlan, resolver: EvidenceResolver): NutritionPlanView {
    val t = plan.target
    val targetLine = "Цель на день: ${t.targetKcal} ккал · Б${t.proteinG} Ж${t.fatG} У${t.carbohydrateG}"
    val meals = plan.structure.map { m ->
        MealRow(
            label = m.label,
            line = "${m.targetKcal} ккал · Б${m.proteinG} Ж${m.fatG} У${m.carbohydrateG}",
        )
    }
    // M6-B: render READABLE citations (author/year + keyFinding + evidenceLevel)
    // per ref, not raw ids. A ghost id → blocked-until-sourced placeholder.
    val evidenceRows = resolveCitations(plan.evidenceRefs, resolver)
    val disclaimer =
        "План питания — это поддержка, а не предписание и не замена врача или диетолога. " +
            "Учитывайте индивидуальные особенности и аллергии."
    return NutritionPlanView(targetLine, meals, evidenceRows, disclaimer)
}
