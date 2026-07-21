package dreamteam.domain.training

import dreamteam.domain.EvidenceId
import dreamteam.domain.ExerciseId
import dreamteam.domain.UserId

/**
 * The deterministic PoC v0.2.0 baseline training + nutrition plan, encoded as
 * pure Kotlin constants so the same self-sourced baseline can be produced with
 * **no I/O and no LLM** by both the backend (`:server`) and the offline-first
 * native client (`:app`). Implements the "deterministic fallback" invariant:
 * `/plans/generate` serves this first, before any provider path; no LLM failure
 * can strand the user (ADR 0001 non-negotiable #4).
 *
 * **Source of truth:** this mirrors `data/program_12_weeks.json`,
 * `data/exercises.json`, and `data/derived_metrics.json` (the frozen PoC
 * reference, integrity-checked by `scripts/validate_build.py`). Field values are
 * copied verbatim; if the PoC files change, update this mirror. The PoC files
 * remain canonical — this is a faithful, reviewable Kotlin projection of them,
 * not a second authoring. No clinical content is invented here: every exercise,
 * set scheme, and evidence id comes straight from the PoC.
 *
 * This baseline is **generic / non-curve-specific**: side-specific corrections
 * stay locked (ADR 0001 §3). It is the "READY_GENERIC" state from SDD §5, never
 * a diagnosis or treatment claim.
 */
object BaselineProgram {

    /**
     * Minimal exercise record the generator + renderer need. The full library
     * (instructions, progression, regression, scoliosis_rule) lives in
     * `data/exercises.json`; this carries the deterministic, plan-relevant
     * fields. [name] is the user-facing Russian label (content language is RU;
     * ids stay English — ADR 0001).
     */
    data class BaselineExercise(
        val id: ExerciseId,
        val name: String,
        val category: String,
        val defaultSets: Int,
        val repScheme: String,
        val defaultRir: Int?,
        val evidenceRefs: List<EvidenceId>,
    )

    data class SessionTemplate(
        val id: String,
        val day: String,
        val label: String,
        val warmup: List<ExerciseId> = emptyList(),
        val main: List<ExerciseId> = emptyList(),
    )

    data class WeekParameter(
        val week: Int,
        val phase: String,
        val setsMain: Int,
        val rir: Int,
        val volumeFactor: Double,
        val notes: String,
    )

    // --- Exercise library (mirrors data/exercises.json) ---------------------
    // Evidence ids all resolve to data/evidence_catalog.json (the allowlist).
    private val warmBreathing = BaselineExercise("warm_breathing", "Спокойное дыхание 5–6/мин", "warmup", 1, "3–5 мин", null, listOf("SLOW-BREATHING-2022", "DIAPHRAGMATIC-BREATHING-2019"))
    private val wallAxial = BaselineExercise("wall_axial_elongation", "Осевое вытяжение у стены", "motor_control", 5, "3–5 дыханий", null, listOf("MONTICONE-ADULT-SCOLIOSIS-2016", "SOSORT-GUIDELINES-2016"))
    private val rockback = BaselineExercise("quadruped_rockback", "Рок-бэк на четвереньках", "mobility_control", 2, "8–12", 3, listOf("MONTICONE-ADULT-SCOLIOSIS-2016"))
    private val wallSlide = BaselineExercise("wall_slide", "Скольжение руками по стене", "mobility_control", 2, "8–12", 3, listOf("MONTICONE-ADULT-SCOLIOSIS-2016"))
    private val splitSquat = BaselineExercise("split_squat", "Сплит-присед / передняя нога на возвышении", "knee_dominant", 3, "8–15/сторона", 2, listOf("ACSM-RT-2026", "LOPEZ-LOAD-2021"))
    private val gobletSquat = BaselineExercise("goblet_squat", "Гоблет-присед с темпом", "knee_dominant", 3, "10–20", 2, listOf("ACSM-RT-2026", "LOPEZ-LOAD-2021"))
    private val bulgarian = BaselineExercise("bulgarian_split_squat", "Болгарский сплит-присед", "knee_dominant", 3, "8–15/сторона", 2, listOf("ACSM-RT-2026", "LOPEZ-LOAD-2021"))
    private val reverseLunge = BaselineExercise("reverse_lunge", "Обратный выпад", "knee_dominant", 3, "8–15/сторона", 2, listOf("ACSM-RT-2026"))
    private val bStanceRdl = BaselineExercise("b_stance_rdl", "B-stance румынская тяга", "hip_hinge", 3, "10–20/сторона", 2, listOf("ACSM-RT-2026", "LOPEZ-LOAD-2021"))
    private val singleLegRdl = BaselineExercise("single_leg_rdl_supported", "Одноногая тяга с опорой", "hip_hinge", 3, "8–15/сторона", 2, listOf("ACSM-RT-2026"))
    private val gluteBridge = BaselineExercise("glute_bridge", "Ягодичный мост / хамстринг-выходы", "hip_extension", 3, "10–20", 2, listOf("ACSM-RT-2026"))
    private val pushup = BaselineExercise("pushup", "Отжимания: выбранная прогрессия", "horizontal_push", 3, "8–20", 2, listOf("KIKUCHI-PUSHUP-2017", "ACSM-RT-2026"))
    private val floorPress = BaselineExercise("db_floor_press", "Жим гантелей лёжа на полу", "horizontal_push", 3, "10–20", 2, listOf("ACSM-RT-2026", "LOPEZ-LOAD-2021"))
    private val pikePushup = BaselineExercise("pike_pushup_optional", "Пайк-отжимание (опционально)", "vertical_push", 2, "6–15", 2, listOf("ACSM-RT-2026"))
    private val oneArmRow = BaselineExercise("one_arm_row_supported", "Тяга гантели одной рукой с опорой", "horizontal_pull", 3, "12–30/сторона", 1, listOf("ACSM-RT-2026", "LOPEZ-LOAD-2021"))
    private val proneYtw = BaselineExercise("prone_ytw", "Y–T–W лёжа", "scapular", 2, "6–10 каждой позиции", 3, listOf("ACSM-RT-2026"))
    private val reverseFly = BaselineExercise("reverse_fly", "Разведение гантелей в наклоне с опорой", "scapular", 2, "15–30", 2, listOf("ACSM-RT-2026", "LOPEZ-LOAD-2021"))
    private val deadBug = BaselineExercise("dead_bug", "Dead bug", "trunk_control", 3, "6–10/сторона", 3, listOf("MONTICONE-ADULT-SCOLIOSIS-2016", "ALANAZI-ADULT-SCOLIOSIS-2018"))
    private val birdDog = BaselineExercise("bird_dog", "Bird dog", "trunk_control", 3, "5–8/сторона, пауза 3–5 с", 3, listOf("MONTICONE-ADULT-SCOLIOSIS-2016", "ALANAZI-ADULT-SCOLIOSIS-2018"))
    private val sidePlank = BaselineExercise("side_plank_equal", "Боковая планка — обе стороны поровну", "trunk_endurance", 2, "20–45 с/сторона", 2, listOf("ALANAZI-ADULT-SCOLIOSIS-2018"))
    private val suitcase = BaselineExercise("suitcase_hold_equal", "Suitcase hold/march — обе стороны поровну", "trunk_endurance", 2, "30–45 с/сторона", 2, listOf("ACSM-RT-2026"))
    private val wallHipAbd = BaselineExercise("wall_hip_abduction", "Изометрия отведения бедра у стены", "hip_stability", 2, "20–30 с/сторона", 3, listOf("MONTICONE-ADULT-SCOLIOSIS-2016"))
    private val briskWalk = BaselineExercise("brisk_walk", "Быстрая ходьба", "aerobic", 1, "20–40 мин", null, listOf("WHO-ACTIVITY-2020"))
    private val yogaFlow = BaselineExercise("gentle_yoga_flow", "Мягкий йога-флоу", "mobility_relaxation", 1, "15–25 мин", null, listOf("YOGA-LBP-2022"))

    /** All baseline exercises, keyed by id. */
    val exercises: Map<ExerciseId, BaselineExercise> = listOf(
        warmBreathing, wallAxial, rockback, wallSlide, splitSquat, gobletSquat, bulgarian,
        reverseLunge, bStanceRdl, singleLegRdl, gluteBridge, pushup, floorPress, pikePushup,
        oneArmRow, proneYtw, reverseFly, deadBug, birdDog, sidePlank, suitcase, wallHipAbd,
        briskWalk, yogaFlow,
    ).associateBy { it.id }

    /** Every exercise id the baseline plan may surface — the allowlist seed. */
    val exerciseIds: Set<ExerciseId> = exercises.keys

    /** Every evidence id cited by the baseline — the catalog allowlist seed. */
    val evidenceIds: Set<EvidenceId> = exercises.values.flatMap { it.evidenceRefs }.toSet()

    // --- Session templates (mirrors data/program_12_weeks.json -> sessions) --
    private val strengthA = SessionTemplate("strength_A", "Monday", "Full Body A — базовая сила и контроль",
        warmup = listOf("warm_breathing", "wall_axial_elongation", "quadruped_rockback"),
        main = listOf("split_squat", "pushup", "one_arm_row_supported", "single_leg_rdl_supported", "dead_bug", "prone_ytw"))
    private val strengthB = SessionTemplate("strength_B", "Thursday", "Full Body B — присед, жим, задняя цепь",
        warmup = listOf("warm_breathing", "wall_slide", "quadruped_rockback"),
        main = listOf("goblet_squat", "db_floor_press", "one_arm_row_supported", "reverse_lunge", "glute_bridge", "suitcase_hold_equal"))
    private val strengthC = SessionTemplate("strength_C", "Saturday", "Full Body C — односторонняя работа и объём",
        warmup = listOf("warm_breathing", "wall_axial_elongation", "wall_slide"),
        main = listOf("bulgarian_split_squat", "pushup", "one_arm_row_supported", "b_stance_rdl", "pike_pushup_optional", "side_plank_equal", "reverse_fly"))
    private val scoliosisA = SessionTemplate("scoliosis_A", "Tuesday", "Сколиоз/моторный контроль A — нейтральная база",
        main = listOf("warm_breathing", "wall_axial_elongation", "quadruped_rockback", "dead_bug", "bird_dog", "side_plank_equal", "wall_hip_abduction", "brisk_walk"))
    private val scoliosisB = SessionTemplate("scoliosis_B", "Friday", "Сколиоз/моторный контроль B — интеграция и расслабление",
        main = listOf("warm_breathing", "wall_axial_elongation", "wall_slide", "suitcase_hold_equal", "bird_dog", "gentle_yoga_flow"))
    private val recoveryWalk = SessionTemplate("recovery_walk_breathing", "Wednesday", "Восстановление — дыхание и прогулка",
        main = listOf("warm_breathing", "brisk_walk"))
    private val rest = SessionTemplate("rest_or_easy_walk", "Sunday", "Отдых или лёгкая прогулка")

    /** The 7-day schedule (mirrors data/program_12_weeks.json -> default_week_schedule). */
    private val schedule: List<SessionTemplate> =
        listOf(strengthA, scoliosisA, recoveryWalk, strengthB, scoliosisB, strengthC, rest)

    // --- Week parameters (mirrors data/program_12_weeks.json -> week_parameters)
    private val weekParameters: List<WeekParameter> = listOf(
        WeekParameter(1, "re-entry", 2, 3, 0.70, "Калибровка техники, симптомов, повторов и рабочего темпа."),
        WeekParameter(2, "re-entry", 2, 3, 0.80, "Добавить повторы, не вес любой ценой."),
        WeekParameter(3, "build-1", 3, 2, 0.90, "Большинство основных упражнений по 3 подхода."),
        WeekParameter(4, "build-1", 3, 2, 1.00, "Двойная прогрессия в рамках диапазона повторов."),
        WeekParameter(5, "build-1", 3, 1, 1.05, "Последний подход изолированных/стабильных движений может быть 1 RIR."),
        WeekParameter(6, "deload", 2, 4, 0.55, "Сократить подходы на 40–50%, сохранить движения и прогулки."),
        WeekParameter(7, "build-2", 3, 2, 0.95, "Вернуться чуть ниже недели 5."),
        WeekParameter(8, "build-2", 3, 2, 1.00, "Усложнить рычаг или темп там, где 10 кг уже лёгкие."),
        WeekParameter(9, "build-2", 3, 1, 1.05, "Добавить 1 подход только отстающим паттернам при нормальном восстановлении."),
        WeekParameter(10, "build-2", 3, 1, 1.10, "Пиковый рабочий объём; без отказа в балансных и осевых движениях."),
        WeekParameter(11, "benchmark", 3, 1, 1.00, "AMRAP с 1 RIR только в безопасных упражнениях: отжимания, тяга с опорой, мост."),
        WeekParameter(12, "deload-retest", 2, 4, 0.55, "Снизить нагрузку, снять мерки, сравнить 7-дневные средние и технику."),
    )

    /**
     * Builds the full 12-week baseline [TrainingPlan] for [userId]. Pure: same
     * inputs → same plan, every time. Warm-ups keep their exercise default sets;
     * main lifts take the week's [WeekParameter.setsMain] / [WeekParameter.rir].
     * Every assignment carries the exercise's PoC evidence refs (no assignment
     * ships unsourced — DRE-6).
     */
    fun baselineTrainingPlan(userId: UserId, planId: String = "baseline-12w", createdAt: String): TrainingPlan {
        val weeks = weekParameters.map { wp ->
            val sessions = schedule.map { template ->
                val warmupAssignments = template.warmup.map { assignment(it, sets = exercises.getValue(it).defaultSets, rir = exercises.getValue(it).defaultRir) }
                val mainAssignments = template.main.map { assignment(it, sets = wp.setsMain, rir = wp.rir) }
                PlanSession(
                    id = template.id,
                    day = template.day,
                    label = template.label,
                    assignments = warmupAssignments + mainAssignments,
                )
            }
            PlanWeek(
                weekNumber = wp.week,
                phase = wp.phase,
                setsMain = wp.setsMain,
                rir = wp.rir,
                volumeFactor = wp.volumeFactor,
                notes = wp.notes,
                sessions = sessions,
            )
        }
        return TrainingPlan(
            id = planId,
            userId = userId,
            name = "12-week lean recomposition + scoliosis-aware motor control (PoC baseline)",
            weeks = weeks,
            createdAt = createdAt,
        )
    }

    private fun assignment(id: ExerciseId, sets: Int, rir: Int?): ExerciseAssignment {
        val ex = exercises.getValue(id)
        return ExerciseAssignment(
            exerciseId = ex.id,
            sets = sets,
            repScheme = ex.repScheme,
            rir = rir ?: ex.defaultRir,
            evidenceRefs = ex.evidenceRefs,
        )
    }
}
