package dreamteam.domain.profile

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Smoke test: deterministic domain math produces the reference value recorded
 * in the PoC's single-source-of-truth (data/derived_metrics.json for the seed
 * profile). If this breaks, a safety-relevant equation regressed.
 */
class BasalMetabolicRateTest {
    // Seed profile from data/profile.json (the inviolable reference).
    private val seedMale =
        Anthropometrics(
            sex = SexForEquations.MALE,
            ageYears = 28,
            heightCm = 188.0,
            weightKg = 83.2,
            bodyFatPercent = 21.2,
        )

    @Test
    fun `mifflin-st jeor matches seed profile reference of 1872 kcal`() {
        // Reference: data/derived_metrics.json -> derived.bmr_mifflin_st_jeor_kcal = 1872
        BasalMetabolicRate.mifflinStJeor(seedMale) shouldBe 1872
    }

    @Test
    fun `female variant subtracts 166 versus male`() {
        val female = seedMale.copy(sex = SexForEquations.FEMALE)
        // (male + 5) vs (male - 161) => difference 166
        val male = BasalMetabolicRate.mifflinStJeor(seedMale)
        BasalMetabolicRate.mifflinStJeor(female) shouldBe male - 166
    }
}
