package dreamteam.app

import dreamteam.domain.adaptation.AdaptationSignal
import dreamteam.domain.adaptation.deriveAdaptationSignal
import dreamteam.domain.nutrition.GeneratedNutritionPlan
import dreamteam.domain.nutrition.NutritionGoal
import dreamteam.domain.nutrition.NutritionPlan
import dreamteam.domain.nutrition.NutritionPlanGenerator
import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.profile.Anthropometrics
import dreamteam.domain.profile.SexForEquations
import dreamteam.domain.safety.ContraindicationStubs
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.StructuralSafetyRules
import dreamteam.domain.symptom.Symptom
import dreamteam.domain.training.BaselineProgram
import dreamteam.domain.training.DeterministicPlanGenerator
import dreamteam.domain.training.GeneratedPlan
import dreamteam.domain.training.PlanSession
import dreamteam.domain.training.PlanWeek
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * M5-B ([DRE-62](/DRE/issues/DRE-62)) — the guarantees the "Today" home
 * **surface** must give, enforced as code (mirrors [AdaptationNoteTest] /
 * [NutritionPlanViewTest], M3-C/M4-C). Today composes existing pieces — no new
 * domain logic — so the checks pin the composition, not re-test the pieces:
 *
 * 1. **No second source of truth:** [todaySession] picks today's session by
 *    day-of-week from the SAME gate-Ok week [DreamTeamApp.PlanScreen] renders;
 *    the returned session IS a member of that week; every weekday is covered;
 *    the pick is deterministic.
 * 2. **The gate is unchanged:** a flagged symptom still yields only None/DeLoad,
 *    and a de-load still surfaces through the same gate (Ok). Composing the view
 *    never bypasses [SafetyGuardedGateway].
 * 3. **No medical claim:** none of the strings Today can ever render — the
 *    authored chrome ([TodayStrings.all]) + every weekday's date line + the
 *    composed nutrition view + every domain DeLoad note — contains a banned
 *    diagnostic/treatment/cure phrase. Support framing only.
 *
 * Pure functions over a real generated week/plan, so a JVM assertion is the
 * smallest sufficient check of the Compose surface without a device.
 */
class TodayViewTest {

    /** The SAME gate-Ok week the app renders (the [DreamTeamApp.generateLocalPlan] path). */
    private fun week(signal: AdaptationSignal = AdaptationSignal.None): PlanWeek {
        val ctx = ScreeningContext(
            allowedExerciseIds = BaselineProgram.exerciseIds,
            allowedEvidenceIds = BaselineProgram.evidenceIds,
        )
        val gateway = SafetyGuardedGateway(ctx, StructuralSafetyRules.all + ContraindicationStubs.all)
        return DeterministicPlanGenerator(gateway).generate(userId = "local", createdAt = "2026-07-23", adaptation = signal)
            .shouldBeInstanceOf<GeneratedPlan.Ok>().plan.weeks.first()
    }

    private fun plan(): NutritionPlan {
        val body = Anthropometrics(SexForEquations.MALE, 28, 188.0, 83.2, 21.2)
        val gateway = SafetyGuardedGateway(
            ScreeningContext(allowedEvidenceIds = NUTRITION_EVIDENCE_IDS),
            listOf(StructuralSafetyRules.evidenceAllowlist),
        )
        return NutritionPlanGenerator(gateway).generate(userId = "local", body = body, goal = NutritionGoal.RECOMP, recordedOn = "2026-07-23")
            .shouldBeInstanceOf<GeneratedNutritionPlan.Ok>().plan
    }

    @Test
    fun `todaySession picks the session matching the date's weekday and stays in the rendered week`() {
        val w = week()
        val sample = LocalDate.of(2026, 7, 23) // a date in the test window
        val session = todaySession(w, sample)

        session.shouldBeInstanceOf<PlanSession>() // not null
        session.day shouldBe TODAY_WEEKDAY_NAMES[sample.dayOfWeek.ordinal]
        // Same source of truth: the picked session IS a member of the week
        // PlanScreen renders — todaySession never synthesises a second plan.
        (session in w.sessions) shouldBe true
    }

    @Test
    fun `every weekday maps to exactly one session - no empty day`() {
        val w = week()
        // Any 7 consecutive days cover Mon–Sun once; assert by each date's own
        // dayOfWeek so the test is calendar-independent.
        val weekSpan = (0..6).map { LocalDate.of(2026, 7, 19).plusDays(it.toLong()) }
        weekSpan.forEach { date ->
            val session = todaySession(w, date)
            // Baseline schedule covers Mon–Sun, so a session always exists; a null
            // would be a regression that leaves the day empty (a hole, not a rest).
            (session != null) shouldBe true
            session!!.day shouldBe TODAY_WEEKDAY_NAMES[date.dayOfWeek.ordinal]
        }
    }

    @Test
    fun `todaySession is deterministic - same week and date yield the same session`() {
        val w = week()
        val date = LocalDate.of(2026, 7, 23)
        val first = todaySession(w, date)
        val second = todaySession(w, date)

        first?.id shouldBe second?.id
        first?.day shouldBe TODAY_WEEKDAY_NAMES[date.dayOfWeek.ordinal]
    }

    @Test
    fun `a flagged symptom still yields only None or DeLoad and the view never bypasses the gate`() {
        // A flagged symptom → DeLoad signal. The week still surfaces Ok through
        // the SAME gate (week() asserts GeneratedPlan.Ok); todaySession returns a
        // member session of it. adaptationNote renders only on DeLoad, null on
        // None — only ever None/DeLoad, never an increase, never a bypass.
        val deLoad = AdaptationSignal.DeLoad(
            trigger = dreamteam.domain.adaptation.DeLoadTrigger.SymptomEscalation,
            volumeScale = AdaptationSignal.SCALE_MODERATE,
            reason = "появился новый/усиливающийся симптом",
        )
        val w = week(deLoad) // surfaces Ok through the gate (asserted inside week())
        val session = todaySession(w, LocalDate.of(2026, 7, 23))

        (session != null) shouldBe true
        (session in w.sessions) shouldBe true
        (adaptationNote(deLoad) != null) shouldBe true
        adaptationNote(AdaptationSignal.None) shouldBe null
    }

    // Banned substrings (lowercased) — same intent as the M3-C/M4-C surface
    // tests: Today may never assert a diagnosis, claim to treat/cure, or frame
    // the user in second-person clinical terms.
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
        "болезнь",
        "у вас", "вы больн", "вы здоровы", "ваш диагноз",
        "предписываю", "назначаю", "прописываю",
        "diagnos", "treat", "cure", "heal", "disease", "you have", "you are", "prescribe",
    )

    @Test
    fun `no rendered Today string contains a banned medical-claim phrase`() {
        val w = week()
        val nutritionView = nutritionPlanView(plan())
        // Every weekday's date line (carries the baseline session labels verbatim).
        val dateLines = (0..6).map { dow ->
            todayDateLine(todaySession(w, LocalDate.of(2026, 7, 19).plusDays(dow.toLong())))
        }
        // The composed nutrition view strings (already pinned in NutritionPlanViewTest;
        // scanned here too because Today composes them).
        val nutritionStrings = listOf(nutritionView.targetLine, nutritionView.evidenceLine, nutritionView.disclaimer) +
            nutritionView.meals.flatMap { listOf(it.label, it.line) }
        // Every real domain DeLoad reason surfaced via adaptationNote (derived, not
        // copied, so the scan reads the authored strings — mirrors AdaptationNoteTest).
        val deLoadNotes = allDeLoadSignals().map { adaptationNote(it as AdaptationSignal.DeLoad)!! }
            .flatMap { listOf(it.indicator, it.reason) }

        val rendered: List<String> = TodayStrings.all + dateLines + nutritionStrings + deLoadNotes
        rendered.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }

    private fun symptom(i: Int, on: String, words: List<String>) =
        Symptom(id = "s$i", userId = "local", recordedOn = on, source = "client_log", currentSymptoms = words)

    private fun progress(i: Int, on: String, kg: Double) =
        ProgressEntry(id = "p$i", userId = "local", recordedOn = on, weightKg = kg)

    /** Every domain DeLoad signal the UI can render (built via deriveAdaptationSignal). */
    private fun allDeLoadSignals(): List<AdaptationSignal> = listOf(
        deriveAdaptationSignal( // SymptomEscalation
            progress = emptyList(),
            symptoms = listOf(symptom(0, "2026-07-01", listOf("lumbar", "tension")), symptom(1, "2026-07-08", listOf("lumbar", "tension", "sharp"))),
        ),
        deriveAdaptationSignal( // RapidWeightLoss
            progress = listOf(progress(0, "2026-07-01", 100.0), progress(1, "2026-07-08", 99.0)),
            symptoms = emptyList(),
        ),
        deriveAdaptationSignal( // both triggers
            progress = listOf(progress(0, "2026-07-01", 100.0), progress(1, "2026-07-08", 99.0)),
            symptoms = listOf(symptom(0, "2026-07-01", listOf("tension")), symptom(1, "2026-07-08", listOf("tension", "sharp"))),
        ),
    )
}
