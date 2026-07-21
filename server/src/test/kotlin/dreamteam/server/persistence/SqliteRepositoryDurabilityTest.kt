package dreamteam.server.persistence

import dreamteam.domain.UserId
import dreamteam.domain.evidence.EvidenceSource
import dreamteam.domain.exercise.Exercise
import dreamteam.domain.nutrition.NutritionTarget
import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.symptom.Symptom
import dreamteam.domain.training.ExerciseAssignment
import dreamteam.domain.training.PlanSession
import dreamteam.domain.training.PlanWeek
import dreamteam.domain.training.TrainingPlan
import dreamteam.domain.user.MedicalContext
import dreamteam.domain.user.User
import dreamteam.domain.user.UserPreferences
import dreamteam.domain.profile.Anthropometrics
import dreamteam.domain.profile.SexForEquations
import dreamteam.domain.safety.SafetyRule
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * M2-A done-when #2: the SQLite repository implementations are DURABLE — data
 * written in one [SqliteStore] survives a full reopen (process-restart
 * equivalent) and reads back byte-identical through the same ports. This is the
 * property the in-memory repos lacked and the smoke test ("restart, data
 * persists") requires. ADR 0003.
 *
 * All assertions go through the *port interfaces* — feature code never sees the
 * concrete store (DRE-6).
 */
class SqliteRepositoryDurabilityTest {

    @TempDir
    lateinit var dir: Path

    private fun dbPath(): String = dir.resolve("durability.db").toString()

    @Test
    fun `evidence and exercise round-trip and the allowlist gate survives restart`() {
        val source = EvidenceSource(
            id = "ACSM-RT-2026", domain = "resistance_training", citation = "ACSM PS",
            design = "overview", keyFinding = "RT works", application = "progressive overload",
            limitations = "healthy adults", evidenceLevel = "high",
        )
        val exercise = Exercise(
            id = "split_squat", name = "Split squat", category = "knee_dominant",
            equipment = "dumbbells", defaultSets = 3, repScheme = "8-15/side", rir = 2,
            instructions = "Equal volume both sides.", progression = "reps -> weight",
            regression = "shorter range", scoliosisRule = "no forced symmetry",
            evidenceRefs = listOf("ACSM-RT-2026"),
        )
        // Write in one process.
        SqliteStore(dbPath()).use { store ->
            val evidence = SqliteEvidenceSourceRepository(store)
            val exercises = SqliteExerciseRepository(store)
            evidence.save(source)
            exercises.save(exercise)
            evidence.contains("ACSM-RT-2026") shouldBe true
            exercises.contains("GHOST") shouldBe false // allowlist gate
        }
        // Reopen in a new process: data survived.
        SqliteStore(dbPath()).use { store ->
            val evidence = SqliteEvidenceSourceRepository(store)
            val exercises = SqliteExerciseRepository(store)
            evidence.byId("ACSM-RT-2026") shouldBe source
            exercises.byId("split_squat") shouldBe exercise
            evidence.contains("GHOST-STUDY") shouldBe false
        }
    }

    @Test
    fun `user plan progress symptom nutrition survive a full reopen`() {
        val userId: UserId = "user-1"
        val user = User(
            id = userId,
            anthropometrics = Anthropometrics(SexForEquations.MALE, 28, 188.0, 83.2, 21.2),
            equipment = listOf("dumbbells"),
            preferences = UserPreferences(3, 2, 60, "calisthenics"),
            medicalContext = MedicalContext(scoliosisReported = true),
        )
        val plan = TrainingPlan(
            id = "plan-1", userId = userId, name = "PoC", createdAt = "2026-07-21",
            weeks = listOf(
                PlanWeek(1, "re-entry", 2, 3, 0.7, "", listOf(
                    PlanSession("strength_A", "Monday", "Strength A", listOf(
                        ExerciseAssignment("split_squat", 2, "8-15/side", 2, listOf("ACSM-RT-2026")),
                    )),
                )),
            ),
        )
        val nutrition = NutritionTarget(
            userId = userId, targetKcal = 2300, proteinG = 170, fatG = 75, carbohydrateG = 236,
            evidenceRefs = listOf("MIFFLIN-1990", "ISSN-DIETS-2017", "MORTON-PROTEIN-2018"),
            recordedOn = "2026-07-21",
        )
        val rule = SafetyRule(
            id = "structural_exercise_allowlist",
            description = "allowlist",
            trigger = dreamteam.domain.safety.RuleTrigger.ExerciseNotInAllowlist("__x__"),
            decision = SafetyRule.Decision.BLOCK,
            evidenceRefs = listOf("SAFETY-STRUCTURAL-ALLOWLIST"),
        )

        // Write everything in process A.
        SqliteStore(dbPath()).use { store ->
            SqliteUserRepository(store).save(user)
            val plans = SqliteTrainingPlanRepository(store)
            plans.save(plan)
            plans.currentFor(userId) shouldBe plan // current pointer set
            SqliteProgressRepository(store).append(ProgressEntry("p1", userId, "2026-07-21", 83.2, 21.2))
            SqliteSymptomRepository(store).append(Symptom("s1", userId, "2026-07-21", "self-report", listOf("lumbar tension")))
            SqliteNutritionRepository(store).save(nutrition)
            SqliteSafetyRuleRepository(store).save(rule)
        }
        // Reopen in process B: every aggregate survived restart.
        SqliteStore(dbPath()).use { store ->
            SqliteUserRepository(store).byId(userId) shouldBe user
            SqliteTrainingPlanRepository(store).currentFor(userId) shouldBe plan
            SqliteProgressRepository(store).recentFor(userId, 10) shouldHaveSize 1
            SqliteSymptomRepository(store).recentFor(userId, 10) shouldHaveSize 1
            SqliteNutritionRepository(store).currentFor(userId) shouldBe nutrition
            SqliteSafetyRuleRepository(store).byId(rule.id)?.id shouldBe rule.id
        }
    }

    @Test
    fun `progress and symptoms are returned chronologically and capped by limit`() {
        val userId: UserId = "user-2"
        SqliteStore(dbPath()).use { store ->
            val progress = SqliteProgressRepository(store)
            repeat(5) { i -> progress.append(ProgressEntry("p$i", userId, "2026-07-$i", 83.0 + i)) }
            progress.recentFor(userId, 3) shouldHaveSize 3
            progress.recentFor(userId, 3).map { it.id } shouldBe listOf("p2", "p3", "p4") // chronological
        }
    }
}
