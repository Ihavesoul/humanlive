package dreamteam.app

import dreamteam.app.data.ProgressRow
import dreamteam.app.data.SymptomEntry
import dreamteam.domain.adaptation.weeklyWeightRate
import java.util.Locale
import kotlin.math.abs

/**
 * M5-C ([DRE-63](/DRE/issues/DRE-63)): the read-only history/trend surface — the
 * visibility half of the retention loop. Mirrors the M3-C/M4-C/M5-B pattern
 * ([ClientAdaptation] / [ClientNutrition] / [ClientToday]): a thin, **pure**
 * (no Android, no I/O) render layer the Compose tree calls, so a JVM test
 * ([HistoryViewTest](../../test/kotlin/dreamteam/app/HistoryViewTest.kt)) can
 * pin the guarantees without a device.
 *
 * **No new domain logic, no new persistence, no second source of truth.** The
 * trend number is the SAME [weeklyWeightRate] the RapidWeightLoss adaptation
 * trigger consumes (extracted in M3-A, surfaced here): the rate on screen can
 * never drift from the signal the plan uses. The view only READS logged data —
 * it never writes, never calls the generator, never bypasses the safety gate.
 * Framing is support/transparency only: input shown back, never a diagnosis.
 */

/**
 * One logged body-weight point as the history view renders it. [date] and
 * [weightKg] are the verbatim row fields — never mutated ([ProgressRow] read
 * straight through into here).
 */
internal data class HistoryPoint(val date: String, val weightKg: Double)

/**
 * The pure render of the logged body-weight points + the deterministic trend
 * line. [points] are in **oldest→newest** read order (the store returns
 * newest-first via [dreamteam.app.data.LocalDatabase.recentProgress]; the view
 * reverses for display). [trendLine] is built from [weeklyWeightRate] — a plain
 * factual number, NO interpretation of what it means, NO diagnosis.
 */
internal data class ProgressHistoryView(
    val points: List<HistoryPoint>,
    val trendLine: String,
)

/**
 * Render the logged body-weight rows as plain points in oldest→newest read order
 * (reversing the store's newest-first order) + the deterministic trend line from
 * [weeklyWeightRate]. Pure: no Android, no I/O; same rows → same view. **No
 * mutation** — [ProgressRow] fields pass through verbatim into [HistoryPoint].
 *
 * The trend line is the SAME number the RapidWeightLoss adaptation trigger
 * reads: `null` (can't establish a rate) → a plain "недостаточно данных" line; a
 * number → a factual `"Тренд веса: ~±X.X%/нед"` (rate ×100 to 1 dp, sign shown).
 * No interpretation of what it means, no diagnosis.
 */
internal fun progressHistoryView(rows: List<ProgressRow>): ProgressHistoryView {
    val points = rows
        .sortedBy { it.recordedOn } // oldest→newest read order (store is newest-first)
        .map { HistoryPoint(date = it.recordedOn, weightKg = it.weightKg) }
    return ProgressHistoryView(points = points, trendLine = trendLine(weeklyWeightRate(clientProgress(rows))))
}

/**
 * Render each logged symptom entry verbatim as `"• {date}: {text}"`, in the
 * store's newest-first order. Pure passthrough — no rewording, no interpretation.
 */
internal fun symptomHistoryView(entries: List<SymptomEntry>): List<String> =
    entries.map { "• ${it.recordedOn}: ${it.text}" }

/** The deterministic trend line: null rate → "недостаточно данных"; else the factual rate. */
private fun trendLine(rate: Double?): String =
    if (rate == null) {
        HistoryStrings.INSUFFICIENT_DATA
    } else {
        // Locale.US forces a '.' decimal separator (host default may be ',').
        val pct = rate * 100
        val sign = if (pct >= 0) "+" else "-"
        "${HistoryStrings.TREND_PREFIX}$sign${String.format(Locale.US, "%.1f", abs(pct))}${HistoryStrings.TREND_SUFFIX}"
    }

/**
 * The authored chrome strings [HistoryScreen] renders. Gathered as one list so a
 * JVM test can snapshot them against the banned medical-claim phrase list
 * (mirrors [TodayStrings] / [adaptationNote] tests). Support framing only: no
 * diagnosis, no "у вас …", no treatment/cure — input shown back, not a verdict.
 */
internal object HistoryStrings {
    const val TITLE = "История и тренд"
    // Spec suggested "…не диагностирует." but that contains the banned morpheme
    // "диагности" (the no-medical-claim scan is a hard gate, DRE-63 done-when).
    // Aligned to the established M3-C/M4-C/M5-B scan-clean support framing
    // ("поддерживает, а не заменяет врача") — same support-not-authority stance.
    const val SUPPORT = "Это ваши записи. Приложение поддерживает, а не заменяет врача."
    const val BACK = "Назад к сегодняшнему дню"
    const val WEIGHT_SECTION = "Вес (записи)"
    const val SYMPTOMS_SECTION = "Симптомы (записи)"
    const val INSUFFICIENT_DATA = "недостаточно данных для тренда"
    const val TREND_PREFIX = "Тренд веса: ~"
    const val TREND_SUFFIX = "%/нед"

    val all: List<String> = listOf(
        TITLE, SUPPORT, BACK, WEIGHT_SECTION, SYMPTOMS_SECTION, INSUFFICIENT_DATA, TREND_PREFIX, TREND_SUFFIX,
    )
}
