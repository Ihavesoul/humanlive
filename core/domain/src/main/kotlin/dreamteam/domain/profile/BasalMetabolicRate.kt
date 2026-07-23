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
 * Sources: Mifflin-St Jeor (1990) — the PoC's primary equation; matches
 * data/derived_metrics.json reference value for the seed profile (1872 kcal).
 * The fat-free-mass equation (M4-A, Katch-McArdle: 370 + 21.6 × FFM) is sourced
 * in our catalog as CUNNINGHAM-1991, whose keyFinding states this exact formula;
 * it is preferred whenever a body-fat % is available because it tracks logged
 * weight + composition (the M4-A spec), falling back to Mifflin-St Jeor when not.
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

    /**
     * Fat-free mass (kg) from logged weight + body-fat %, or null when no
     * composition estimate is available (consumer BIA — a *noisy* signal, never
     * a measurement; see SMART-SCALE-2021).
     */
    fun fatFreeMassKg(a: Anthropometrics): Double? =
        a.bodyFatPercent?.let { a.weightKg * (1.0 - it / 100.0) }

    /**
     * Katch-McArdle BMR in kcal/day: 370 + 21.6 × FFM. Requires a body-fat %
     * (FFM from logged weight × composition). The cataloged source for this
     * exact formula is CUNNINGHAM-1991. Returns null when no body-fat % is
     * available — callers fall back to [mifflinStJeor].
     */
    fun katchMcArdle(a: Anthropometrics): Int? =
        fatFreeMassKg(a)?.let { (370.0 + 21.6 * it).roundToInt() }

    /**
     * The resting-energy estimate a nutrition plan starts from: the FFM-based
     * Katch-McArdle equation when body composition is available, else a graceful
     * fallback to Mifflin-St Jeor (which needs only sex/age/height/weight).
     * Returns the kcal value and the evidence id that sources it.
     */
    fun estimateResting(a: Anthropometrics): RestingEnergy {
        val katch = katchMcArdle(a)
        return if (katch != null) {
            RestingEnergy(katch, "CUNNINGHAM-1991")
        } else {
            RestingEnergy(mifflinStJeor(a), "MIFFLIN-1990")
        }
    }
}

/** A resting-energy estimate paired with the cataloged evidence id that sources it. */
data class RestingEnergy(val kcal: Int, val evidenceId: String)
