package dreamteam.server.persistence

import dreamteam.domain.evidence.EvidenceSource
import dreamteam.domain.exercise.Exercise
import dreamteam.domain.nutrition.Meal
import dreamteam.domain.nutrition.NutritionGoal
import dreamteam.domain.nutrition.NutritionPlan
import dreamteam.domain.nutrition.NutritionTarget
import dreamteam.domain.persistence.EvidenceSourceRepository
import dreamteam.domain.persistence.ExerciseRepository
import dreamteam.domain.persistence.NutritionPlanRepository
import dreamteam.domain.persistence.NutritionRepository
import dreamteam.domain.persistence.ProgressRepository
import dreamteam.domain.persistence.SymptomRepository
import dreamteam.domain.persistence.TrainingPlanRepository
import dreamteam.domain.persistence.UserRepository
import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.profile.Anthropometrics
import dreamteam.domain.profile.SexForEquations
import dreamteam.domain.symptom.Symptom
import dreamteam.domain.training.ExerciseAssignment
import dreamteam.domain.training.PlanSession
import dreamteam.domain.training.PlanWeek
import dreamteam.domain.training.TrainingPlan
import dreamteam.domain.user.MedicalContext
import dreamteam.domain.user.User
import dreamteam.domain.user.UserPreferences
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * The repository-layer **contract** (DRE-6 / DRE-16): the exact round-trip +
 * allowlist behaviour every `Repositories.kt` port implementation must satisfy.
 * Both the in-memory repos (fast unit tests) and the durable encrypted-SQLite
 * repos (ADR 0003) run these same assertions, so the SQLite store is proven to
 * honour the same contract the feature layer already depends on — one source of
 * truth, no duplicated assertions.
 */
internal fun evidenceExerciseContract(
    evidence: EvidenceSourceRepository,
    exercises: ExerciseRepository,
) {
    val source = EvidenceSource(
        id = "ACSM-RT-2026", domain = "resistance_training",
        citation = "ACSM PS", design = "overview", keyFinding = "RT works",
        application = "progressive overload", limitations = "healthy adults",
        evidenceLevel = "high",
    )
    evidence.save(source)
    evidence.byId("ACSM-RT-2026") shouldBe source
    evidence.contains("ACSM-RT-2026") shouldBe true
    evidence.contains("GHOST-STUDY") shouldBe false // allowlist gate

    val exercise = Exercise(
        id = "split_squat", name = "Split squat", category = "knee_dominant",
        equipment = "dumbbells", defaultSets = 3, repScheme = "8-15/side", rir = 2,
        instructions = "Equal volume both sides.", progression = "reps -> weight",
        regression = "shorter range", scoliosisRule = "no forced symmetry",
        evidenceRefs = listOf("ACSM-RT-2026"),
    )
    exercises.save(exercise)
    exercises.byId("split_squat") shouldBe exercise
    exercises.contains("split_squat") shouldBe true
}

internal fun userPlanProgressSymptomNutritionContract(
    users: UserRepository,
    plans: TrainingPlanRepository,
    progress: ProgressRepository,
    symptoms: SymptomRepository,
    nutrition: NutritionRepository,
) {
    val user = User(
        id = "user-1",
        anthropometrics = Anthropometrics(SexForEquations.MALE, 28, 188.0, 83.2, 21.2),
        equipment = listOf("dumbbells"),
        preferences = UserPreferences(3, 2, 60, "calisthenics"),
        medicalContext = MedicalContext(scoliosisReported = true),
    )
    users.save(user)
    users.byId("user-1") shouldBe user

    val plan = TrainingPlan(
        id = "plan-1", userId = "user-1", name = "PoC", createdAt = "2026-07-21",
        weeks = listOf(
            PlanWeek(1, "re-entry", 2, 3, 0.7, "", listOf(
                PlanSession("strength_A", "Monday", "Strength A", listOf(
                    ExerciseAssignment("split_squat", 2, "8-15/side", 2, listOf("ACSM-RT-2026")),
                )),
            )),
        ),
    )
    plans.save(plan)
    plans.currentFor("user-1") shouldBe plan

    // DRE-51 (M3-B): append-mostly versioning — a second save under a NEW id
    // retains the prior version and bumps the current pointer; historyFor lists
    // every retained version (audit/rollback), ordered oldest-first.
    val plan2 = plan.copy(id = "plan-2", createdAt = "2026-07-28")
    plans.save(plan2)
    plans.currentFor("user-1") shouldBe plan2
    plans.historyFor("user-1") shouldContainExactlyInAnyOrder listOf(plan, plan2)
    plans.historyFor("user-1").sortedBy { it.createdAt } shouldBe listOf(plan, plan2)

    progress.append(ProgressEntry("p1", "user-1", "2026-07-21", 83.2, 21.2))
    progress.recentFor("user-1", 10) shouldHaveSize 1

    symptoms.append(Symptom("s1", "user-1", "2026-07-21", "self-report", listOf("lumbar tension")))
    symptoms.recentFor("user-1", 10) shouldHaveSize 1

    val target = NutritionTarget(
        userId = "user-1", targetKcal = 2300, proteinG = 170, fatG = 75, carbohydrateG = 236,
        evidenceRefs = listOf("ENERGY-ESTIMATION"), recordedOn = "2026-07-21",
    )
    nutrition.save(target)
    nutrition.currentFor("user-1") shouldBe target
}

/**
 * M4-B ([DRE-56](/DRE/issues/DRE-56)): the versioned `NutritionPlanRepository`
 * contract — mirrors [userPlanProgressSymptomNutritionContract]'s training-plan
 * versioning. Append-mostly: a second save under a NEW id retains the prior
 * version and bumps the current pointer; [NutritionPlanRepository.historyFor]
 * lists every retained version oldest-first for audit/rollback.
 */
internal fun nutritionPlanContract(plans: NutritionPlanRepository) {
    val plan = sampleNutritionPlan(id = "user-1@2026-07-21", createdAt = "2026-07-21")
    plans.save(plan)
    plans.currentFor("user-1") shouldBe plan
    plans.byId("user-1@2026-07-21") shouldBe plan

    // Append-mostly versioning: a second save under a NEW id retains the prior
    // version and bumps the current pointer; historyFor lists every retained
    // version, ordered oldest-first by createdAt (audit/rollback).
    val plan2 = sampleNutritionPlan(id = "user-1@2026-07-28", createdAt = "2026-07-28", targetKcal = 2500)
    plans.save(plan2)
    plans.currentFor("user-1") shouldBe plan2
    plans.byId("user-1@2026-07-28") shouldBe plan2
    plans.historyFor("user-1") shouldContainExactlyInAnyOrder listOf(plan, plan2)
    plans.historyFor("user-1").sortedBy { it.createdAt } shouldBe listOf(plan, plan2)

    // A different user's history is isolated.
    val other = sampleNutritionPlan(id = "user-2@2026-07-21", createdAt = "2026-07-21", userId = "user-2")
    plans.save(other)
    plans.historyFor("user-1") shouldContainExactlyInAnyOrder listOf(plan, plan2)
    plans.historyFor("user-2") shouldBe listOf(other)
}

/** Builds a fully evidence-linked [NutritionPlan] for contract tests. */
private fun sampleNutritionPlan(
    id: String,
    createdAt: String,
    userId: String = "user-1",
    targetKcal: Int = 2300,
): NutritionPlan {
    val target = NutritionTarget(
        userId = userId, targetKcal = targetKcal, proteinG = 170, fatG = 75, carbohydrateG = 236,
        evidenceRefs = listOf("ENERGY-ESTIMATION"), recordedOn = createdAt,
    )
    return NutritionPlan(
        id = id,
        userId = userId,
        goal = NutritionGoal.RECOMP,
        target = target,
        structure = listOf(
            Meal("breakfast", "Завтрак", 0.30, 690, 51, 22, 71),
            Meal("lunch", "Обед", 0.30, 690, 51, 22, 71),
        ),
        evidenceRefs = listOf("ENERGY-ESTIMATION", "MORTON-PROTEIN-2018"),
        createdAt = createdAt,
    )
}
