package dreamteam.app

import dreamteam.app.data.ProgressRow
import dreamteam.app.data.SymptomEntry
import dreamteam.domain.adaptation.weeklyWeightRate
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.math.abs

/**
 * M5-C ([DRE-63](/DRE/issues/DRE-63)) — the guarantees the read-only
 * history/trend **surface** must give, enforced as code (mirrors
 * [TodayViewTest] / [NutritionPlanViewTest] / [AdaptationNoteTest], M3-C/M4-C/M5-B).
 * The view composes existing pieces — no new domain logic — so the checks pin
 * the surface guarantees:
 *
 * 1. **Renders from the logged data, verbatim, no mutation.** The logged
 *    body-weight rows appear as plain points in oldest→newest read order,
 *    weightKg unmodified; each symptom entry renders with its verbatim text +
 *    recordedOn.
 * 2. **One source of truth for the trend.** The trend line carries EXACTLY the
 *    rate [weeklyWeightRate] computes (the same basis
 *    [dreamteam.domain.adaptation.deriveAdaptationSignal]'s RapidWeightLoss
 *    trigger uses — derived, not copied). When no rate can be established
 *    (<2 points / <1 week), the view shows a plain "недостаточно данных" line,
 *    never a fabricated trend.
 * 3. **No medical claim.** None of the strings the view can ever render — the
 *    authored chrome ([HistoryStrings.all]) + every trend line + every rendered
 *    point/symptom line — contains a banned diagnostic/treatment/cure phrase.
 *    Support framing only.
 *
 * Pure functions over fixed inputs, so a JVM assertion is the smallest
 * sufficient check of the Compose surface without a device.
 */
class HistoryViewTest {

    // --- 1. renders from the logged data, verbatim, no mutation ------------

    @Test
    fun `progressHistoryView renders points in oldest-to-newest order, weight unmodified`() {
        // Stored newest-first (as LocalDatabase.recentProgress returns); the view
        // reverses to oldest→newest read order without touching the weight values.
        val newestFirst = listOf(
            ProgressRow("2026-07-15", 78.4),
            ProgressRow("2026-07-08", 79.2),
            ProgressRow("2026-07-01", 80.0),
        )
        val view = progressHistoryView(newestFirst)

        view.points.map { it.date } shouldBe listOf("2026-07-01", "2026-07-08", "2026-07-15")
        view.points.map { it.weightKg } shouldBe listOf(80.0, 79.2, 78.4)
    }

    @Test
    fun `progressHistoryView keeps weightKg byte-for-byte even for an out-of-order single-day set`() {
        val rows = listOf(ProgressRow("2026-07-08", 79.21), ProgressRow("2026-07-08", 80.0))
        val view = progressHistoryView(rows)
        // Same date: stable sort keeps store order; weights pass through verbatim.
        view.points.map { it.weightKg } shouldBe listOf(79.21, 80.0)
    }

    @Test
    fun `symptomHistoryView renders each entry verbatim with its recordedOn and text`() {
        val entries = listOf(
            SymptomEntry("2026-07-08", "lumbar tension after squats"),
            SymptomEntry("2026-07-01", "tired"),
        )
        val lines = symptomHistoryView(entries)

        lines shouldBe listOf("• 2026-07-08: lumbar tension after squats", "• 2026-07-01: tired")
        // Verbatim: the free text + date survive unchanged (no rewording).
        lines.forEachIndexed { i, line ->
            (entries[i].recordedOn in line) shouldBe true
            (entries[i].text in line) shouldBe true
        }
    }

    // --- 2. one source of truth for the trend ------------------------------

    @Test
    fun `the trend line carries exactly the rate weeklyWeightRate computes for a one-week two-point span`() {
        // 80kg → 78.4kg over exactly 14 days = 2 weeks ⇒ r = (78.4-80)/80/2 = -0.01 (-1%/нед).
        val rows = listOf(ProgressRow("2026-07-01", 80.0), ProgressRow("2026-07-15", 78.4))
        val rate = weeklyWeightRate(clientProgress(rows))

        rate shouldBe ((78.4 - 80.0) / 80.0 / 2.0) // the RapidWeightLoss basis, derived
        // The line embeds rate×100 to 1 dp with the sign shown — hand-computed.
        progressHistoryView(rows).trendLine shouldBe "Тренд веса: ~-1.0%/нед"
        // One source of truth: the rendered line is built FROM weeklyWeightRate's
        // output, not a separate copy of the math.
        progressHistoryView(rows).trendLine shouldBe expectedTrendLine(rate)
    }

    @Test
    fun `the trend line shows a sign for weight gain too (no interpretation, just the number)`() {
        // 80kg → 81.6kg over 14 days = +1%/нед (gain never triggers a de-load; the
        // view still shows the factual rate — no judgement).
        val rows = listOf(ProgressRow("2026-07-01", 80.0), ProgressRow("2026-07-15", 81.6))
        progressHistoryView(rows).trendLine shouldBe "Тренд веса: ~+1.0%/нед"
    }

    @Test
    fun `fewer than two points show the insufficient-data line, never a fabricated trend`() {
        progressHistoryView(listOf(ProgressRow("2026-07-01", 80.0))).trendLine shouldBe HistoryStrings.INSUFFICIENT_DATA
        progressHistoryView(emptyList()).trendLine shouldBe HistoryStrings.INSUFFICIENT_DATA
    }

    @Test
    fun `two points less than a week apart show the insufficient-data line`() {
        progressHistoryView(listOf(ProgressRow("2026-07-01", 80.0), ProgressRow("2026-07-03", 78.0)))
            .trendLine shouldBe HistoryStrings.INSUFFICIENT_DATA
    }

    // --- 3. no medical claim in any rendered string ------------------------

    // Banned substrings (lowercased) — same intent as the M3-C/M4-C/M5-B surface
    // tests: the history view may never assert a diagnosis, claim to treat/cure,
    // or frame the user in second-person clinical terms.
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
        "болезнь",
        "у вас", "вы больн", "вы здоровы", "ваш диагноз",
        "предписываю", "назначаю", "прописываю",
        "diagnos", "treat", "cure", "heal", "disease", "you have", "you are", "prescribe",
    )

    @Test
    fun `no rendered history string contains a banned medical-claim phrase`() {
        val nullRateView = progressHistoryView(listOf(ProgressRow("2026-07-01", 80.0)))
        val realRateView = progressHistoryView(listOf(ProgressRow("2026-07-01", 80.0), ProgressRow("2026-07-15", 78.4)))
        val symptomLines = symptomHistoryView(
            listOf(
                SymptomEntry("2026-07-08", "lumbar tension after squats"),
                SymptomEntry("2026-07-01", "tired, poor sleep"),
            ),
        )
        // Weight point lines as the Compose tree renders them (date + number; the
        // only authored token is "кг" — scanned for completeness).
        val weightLines = realRateView.points.map { "• ${it.date}: ${it.weightKg} кг" }

        val rendered: List<String> = HistoryStrings.all +
            listOf(nullRateView.trendLine, realRateView.trendLine) +
            weightLines +
            symptomLines

        rendered.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }

    /** Mirrors the impl's trend-line build, driven by [weeklyWeightRate]'s own output. */
    private fun expectedTrendLine(rate: Double?): String =
        if (rate == null) {
            HistoryStrings.INSUFFICIENT_DATA
        } else {
            val pct = rate * 100
            val sign = if (pct >= 0) "+" else "-"
            "${HistoryStrings.TREND_PREFIX}$sign${String.format(Locale.US, "%.1f", abs(pct))}${HistoryStrings.TREND_SUFFIX}"
        }
}
