package dreamteam.server.persistence

import dreamteam.domain.evidence.EvidenceSource
import dreamteam.domain.exercise.Exercise
import dreamteam.domain.nutrition.NutritionTarget
import dreamteam.domain.persistence.EvidenceSourceRepository
import dreamteam.domain.persistence.ExerciseRepository
import dreamteam.domain.persistence.NutritionRepository
import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.symptom.Symptom
import dreamteam.domain.training.ExerciseAssignment
import dreamteam.domain.training.PlanSession
import dreamteam.domain.training.PlanWeek
import dreamteam.domain.training.TrainingPlan
import dreamteam.domain.user.User
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * DRE-6 "repository layer so feature code never touches storage directly":
 * writes and reads every core aggregate through the *port interface*, against
 * the in-memory reference implementation. Feature code holds only the
 * interface types — the concrete store is never visible.
 */
class RepositoryLayerTest {

    @Test
    fun `evidence and exercise round-trip through their ports with allowlist check`() {
        val evidence: EvidenceSourceRepository = InMemoryEvidenceSourceRepository()
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

        val exercises: ExerciseRepository = InMemoryExerciseRepository()
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

    @Test
    fun `user, plan, progress, symptom, nutrition round-trip through their ports`() {
        val users = InMemoryUserRepository()
        val plans = InMemoryTrainingPlanRepository()
        val progress = InMemoryProgressRepository()
        val symptoms = InMemorySymptomRepository()
        val nutrition: NutritionRepository = InMemoryNutritionRepository()

        val user = User(
            id = "user-1",
            anthropometrics = dreamteam.domain.profile.Anthropometrics(
                dreamteam.domain.profile.SexForEquations.MALE, 28, 188.0, 83.2, 21.2,
            ),
            equipment = listOf("dumbbells"),
            preferences = dreamteam.domain.user.UserPreferences(3, 2, 60, "calisthenics"),
            medicalContext = dreamteam.domain.user.MedicalContext(scoliosisReported = true),
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
}
