package dreamteam.domain.profile

import kotlin.math.roundToInt

/**
 * Resting energy expenditure (Basal Metabolic Rate) estimates.
 *
 * Deterministic, evidence-linked equations. These are estimates for planning a
 * *support* programme — not diagnostic, not a guarantee of outcome. Per the
 * product's calibration rule, targets adjust on multi-day *trends*, never a
 * single weigh-in.
 *
 * Source: Mifflin-St Jeor (1990) — the PoC's primary equation; matches
 * data/derived_metrics.json reference value for the seed profile (1872 kcal).
 */
object BasalMetabolicRate {
    /**
     * Mifflin-St Jeor BMR in kcal/day.
     *
     * Male:   10·kg + 6.25·cm − 5·age + 5
     * Female: 10·kg + 6.25·cm − 5·age − 161
     */
    fun mifflinStJeor(a: Anthropometrics): Int {
        val base = 10.0 * a.weightKg + 6.25 * a.heightCm - 5.0 * a.ageYears
        val adjusted =
            when (a.sex) {
                SexForEquations.MALE -> base + 5.0
                SexForEquations.FEMALE -> base - 161.0
            }
        return adjusted.roundToInt()
    }
}
