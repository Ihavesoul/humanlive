package dreamteam.app

import dreamteam.domain.training.PlanSession
import dreamteam.domain.training.PlanWeek
import java.time.LocalDate

/**
 * M5-B ([DRE-62](/DRE/issues/DRE-62)): the "Today" home surface — composes the
 * daily action (today's training session + today's nutrition + the week's
 * adaptation note + one-tap logging) on ONE screen, for retention. Mirrors the
 * M3-C/M4-C pattern ([ClientAdaptation] / [ClientNutrition]): a thin, **pure**
 * (no Android, no I/O) layer the Compose tree renders from, so a JVM test pins
 * the guarantees without a device.
 *
 * **No new domain logic, no new persistence, no second source of truth.**
 * Today's session is picked by day-of-week from the SAME deterministic week
 * [generateLocalPlan] → [PlanResult.Ok].week that [PlanScreen] renders. The
 * nutrition line and adaptation note reuse the existing pure views
 * ([nutritionPlanView], [adaptationNote]); the gate is unchanged — composing
 * the view never bypasses [dreamteam.domain.safety.SafetyGuardedGateway].
 */

/**
 * English weekday names matching the [dreamteam.domain.training.BaselineProgram]
 * schedule's `day` field. Indexed by [java.time.DayOfWeek] ordinal
 * (MONDAY=0 … SUNDAY=6), so [todaySession] is deterministic and free of any
 * locale-dependent name formatting.
 */
internal val TODAY_WEEKDAY_NAMES: List<String> =
    listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/**
 * Pick today's [PlanSession] deterministically by day-of-week from the SAME
 * week [PlanScreen] renders. No new selection algorithm, no second source of
 * truth: the returned session **is** a member of [week.sessions]. Pure; null
 * only if the schedule lacked today's weekday (the baseline covers Mon–Sun, so
 * never in practice — a null is rendered as a plain rest line, not a hole).
 */
internal fun todaySession(week: PlanWeek, today: LocalDate): PlanSession? =
    week.sessions.firstOrNull { it.day.equals(TODAY_WEEKDAY_NAMES[today.dayOfWeek.ordinal], ignoreCase = true) }

/**
 * The one dynamic authored line [TodayScreen] shows at the top: today's weekday
 * + session label, or a plain rest-day line when there is no session for today.
 * Pure so the banned-phrase test can scan it (carries the baseline session
 * labels verbatim — no second authoring).
 */
internal fun todayDateLine(session: PlanSession?): String =
    session?.let { "Сегодня — ${it.day} · ${it.label}" } ?: TodayStrings.REST_DAY

/**
 * The authored chrome strings [TodayScreen] renders. Gathered as one list so a
 * JVM test can snapshot them against the banned medical-claim phrase list
 * (mirrors [adaptationNote] / [nutritionPlanView] tests). Support framing only:
 * no diagnosis, no "у вас …", no treatment/cure — the same stance as the M3-C /
 * M4-C disclaimers ("поддержка, а не замена врача").
 */
internal object TodayStrings {
    const val TRAINING = "Тренировка сегодня"
    const val NUTRITION = "Питание сегодня"
    const val ADAPTATION = "Адаптация недели"
    const val FULL_PLAN = "Весь план на неделю"
    const val BACK_TO_TODAY = "Назад к сегодняшнему дню"
    const val LOG_SYMPTOM = "Записать симптом"
    const val LOG_PROGRESS = "Записать прогресс"
    const val REST_DAY = "Сегодня — день отдыха или лёгкой активности"
    const val LOG_HINT =
        "Тренд ваших записей влияет на объём следующей недели. Это поддержка, а не замена врача."

    val all: List<String> =
        listOf(TRAINING, NUTRITION, ADAPTATION, FULL_PLAN, BACK_TO_TODAY, LOG_SYMPTOM, LOG_PROGRESS, REST_DAY, LOG_HINT)
}
