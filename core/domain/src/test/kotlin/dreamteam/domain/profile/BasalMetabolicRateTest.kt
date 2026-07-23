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

    // --- M4-A: Katch-McArdle (FFM-based) + graceful fallback ------------------

    @Test
    fun `katch-mcardle matches the seed profile reference of 1786 kcal`() {
        // FFM = 83.2 * (1 - 0.212) = 65.5616; BMR = 370 + 21.6 * 65.5616 = 1786.13 -> 1786.
        // Reference: data/derived_metrics.json -> derived.bmr_cunningham_kcal = 1786
        // (the catalog records this exact equation under CUNNINGHAM-1991).
        BasalMetabolicRate.katchMcArdle(seedMale) shouldBe 1786
    }

    @Test
    fun `katch-mcardle is unavailable without a body-fat percent`() {
        BasalMetabolicRate.katchMcArdle(seedMale.copy(bodyFatPercent = null)) shouldBe null
    }

    @Test
    fun `estimateResting uses Katch-McArdle when composition is present, else Mifflin-St Jeor`() {
        // FFM form sourced via CUNNINGHAM-1991.
        val katch = BasalMetabolicRate.estimateResting(seedMale)
        katch.kcal shouldBe 1786
        katch.evidenceId shouldBe "CUNNINGHAM-1991"

        // Graceful fallback to Mifflin-St Jeor (MIFFLIN-1990) without body fat.
        val fallback = BasalMetabolicRate.estimateResting(seedMale.copy(bodyFatPercent = null))
        fallback.kcal shouldBe 1872
        fallback.evidenceId shouldBe "MIFFLIN-1990"
    }

    // --- M4-A: FFM-based (Katch-McArdle) resting energy -------------------

    @Test
    fun `fat-free mass from logged weight + body-fat percent`() {
        // 83.2 kg at 21.2% BF => FFM = 83.2 * (1 - 0.212) = 65.5616
        BasalMetabolicRate.fatFreeMassKg(seedMale)!! shouldBe 65.5616
    }

    @Test
    fun `katch-mcardle matches 370 plus 21_6 times ffm for the seed profile`() {
        // 370 + 21.6 * 65.5616 = 1786.93 -> 1786 under Double arithmetic; matches
        // the PoC reference data/derived_metrics.json -> bmr_cunningham_kcal = 1786.
        // Sourced as CUNNINGHAM-1991 (catalog keyFinding states this exact formula).
        BasalMetabolicRate.katchMcArdle(seedMale) shouldBe 1786
    }

    @Test
    fun `estimateResting prefers the FFM equation when body-fat is present`() {
        val resting = BasalMetabolicRate.estimateResting(seedMale)
        resting.kcal shouldBe 1786
        resting.evidenceId shouldBe "CUNNINGHAM-1991"
    }

    @Test
    fun `estimateResting falls back to mifflin-st jeor when no body-fat percent`() {
        val noBf = seedMale.copy(bodyFatPercent = null)
        val resting = BasalMetabolicRate.estimateResting(noBf)
        resting.kcal shouldBe BasalMetabolicRate.mifflinStJeor(noBf) // 1872
        resting.evidenceId shouldBe "MIFFLIN-1990"
    }
}
