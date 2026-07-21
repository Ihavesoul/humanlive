package dreamteam.domain.training

import dreamteam.domain.EvidenceId
import dreamteam.domain.UserId
import dreamteam.domain.nutrition.NutritionTarget

/**
 * The deterministic PoC baseline nutrition target. Mirrors
 * `data/derived_metrics.json` -> `derived` (the seed-profile reference: Mifflin-St
 * Jeor BMR = 1872 kcal, pinned by [dreamteam.domain.profile.BasalMetabolicRateTest]).
 *
 * This is a planning *support* estimate, not a prescription or diagnosis. The
 * calibration rule in [dreamteam.domain.nutrition.CalibrationRule] governs
 * trend-based adjustment — never a reaction to a single weigh-in. Evidence ids
 * resolve to `data/evidence_catalog.json` (the validated equation + diet/protein
 * guidance), so the target is evidence-linked, not unsourced.
 *
 * For M2-A the baseline is the deterministic fallback served *first*; per-user
 * energy math wired from the live anthropometrics lands with the deterministic
 * `/v1/calculate` endpoint (a follow-up, out of this milestone's scope).
 */
object BaselineNutrition {
    // data/derived_metrics.json -> derived.initial_target_kcal / protein_g / fat_g / carbohydrate_g
    private const val TARGET_KCAL = 2300
    private const val PROTEIN_G = 170
    private const val FAT_G = 75
    private const val CARBOHYDRATE_G = 236

    // Mifflin-St Jeor (energy equation) + ISSN diets + Morton protein dose.
    private val EVIDENCE: List<EvidenceId> =
        listOf("MIFFLIN-1990", "ISSN-DIETS-2017", "MORTON-PROTEIN-2018")

    fun baselineTarget(userId: UserId, recordedOn: String): NutritionTarget =
        NutritionTarget(
            userId = userId,
            targetKcal = TARGET_KCAL,
            proteinG = PROTEIN_G,
            fatG = FAT_G,
            carbohydrateG = CARBOHYDRATE_G,
            evidenceRefs = EVIDENCE,
            recordedOn = recordedOn,
        )
}
