package dreamteam.server.persistence

import dreamteam.domain.evidence.EvidenceSource
import dreamteam.domain.exercise.Exercise
import dreamteam.domain.nutrition.Meal
import dreamteam.domain.nutrition.NutritionGoal
import dreamteam.domain.nutrition.NutritionPlan
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
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * DRE-16 done-when: the durable encrypted-SQLite repos satisfy the same
 * repository-layer contract as the in-memory repos, AND data survives a full
 * store close + reopen (i.e. it is genuinely persistent, not in-memory).
 *
 * Encryption seam: a fixed AES-256 key stands in for the injected deployment
 * secret. The DB file on disk is ciphertext payloads — proved by the
 * tamper/restart cases below.
 */
class SqliteRepositoryContractTest {

    @TempDir
    lateinit var tempDir: Path

    private fun dbPath(): Path = tempDir.resolve("dreamteam-test.db")

    private fun open() = SqliteRepositories.open("jdbc:sqlite:${dbPath()}", TEST_KEY)

    @Test
    fun `sqlite repos satisfy the repository-layer contract`() {
        // The exact same assertions the in-memory repos run, behind the same ports.
        open().use { r ->
            evidenceExerciseContract(r.evidence, r.exercises)
            userPlanProgressSymptomNutritionContract(r.users, r.plans, r.progress, r.symptoms, r.nutrition)
            nutritionPlanContract(r.nutritionPlans)
        }
    }

    @Test
    fun `data survives a restart - not in-memory`() {
        val user = User(
            id = "user-1",
            anthropometrics = Anthropometrics(SexForEquations.MALE, 28, 188.0, 83.2, 21.2),
            equipment = listOf("dumbbells"),
            preferences = UserPreferences(3, 2, 60, "calisthenics"),
            medicalContext = MedicalContext(scoliosisReported = true),
        )
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
        val source = EvidenceSource(
            id = "ACSM-RT-2026", domain = "resistance_training",
            citation = "ACSM PS", design = "overview", keyFinding = "RT works",
            application = "progressive overload", limitations = "healthy adults",
            evidenceLevel = "high",
        )
        val exercise = Exercise(
            id = "split_squat", name = "Split squat", category = "knee_dominant",
            equipment = "dumbbells", defaultSets = 3, repScheme = "8-15/side", rir = 2,
            instructions = "Equal volume both sides.", progression = "reps -> weight",
            regression = "shorter range", scoliosisRule = "no forced symmetry",
            evidenceRefs = listOf("ACSM-RT-2026"),
        )
        val target = NutritionTarget(
            userId = "user-1", targetKcal = 2300, proteinG = 170, fatG = 75, carbohydrateG = 236,
            evidenceRefs = listOf("ENERGY-ESTIMATION"), recordedOn = "2026-07-21",
        )

        // Process A: write one of every aggregate, then fully close the store.
        open().use { r ->
            r.evidence.save(source)
            r.exercises.save(exercise)
            r.users.save(user)
            r.plans.save(plan)
            r.progress.append(ProgressEntry("p1", "user-1", "2026-07-21", 83.2, 21.2))
            r.symptoms.append(Symptom("s1", "user-1", "2026-07-21", "self-report", listOf("lumbar tension")))
            r.nutrition.save(target)
        }

        // Process B: a brand-new store + repos at the SAME file path reads it all back.
        // If the store were in-memory, every read here would be empty.
        open().use { r ->
            r.evidence.byId("ACSM-RT-2026") shouldBe source
            r.evidence.contains("ACSM-RT-2026") shouldBe true
            r.evidence.contains("GHOST-STUDY") shouldBe false
            r.exercises.byId("split_squat") shouldBe exercise
            r.users.byId("user-1") shouldBe user
            r.plans.currentFor("user-1") shouldBe plan
            r.plans.byId("plan-1") shouldBe plan
            r.plans.historyFor("user-1").map { it.id } shouldBe listOf("plan-1") // rowid order survives reopen
            r.progress.recentFor("user-1", 10).size shouldBe 1
            r.symptoms.recentFor("user-1", 10).size shouldBe 1
            r.symptoms.byId("s1")?.currentSymptoms shouldBe listOf("lumbar tension")
            r.nutrition.currentFor("user-1") shouldBe target
        }
    }

    @Test
    fun `nutrition plan versioning survives a restart - not in-memory`() {
        // M4-B ([DRE-56](/DRE/issues/DRE-56)): append-mostly versioning must be
        // durable. Save two versions under new ids, close the store, reopen at
        // the same file: both versions are retained, ordered oldest-first, and
        // the current pointer points at the last save. If the store were
        // in-memory, every read here would be empty.
        val plan1 = NutritionPlan(
            id = "user-1@2026-07-21", userId = "user-1", goal = NutritionGoal.RECOMP,
            target = NutritionTarget(
                userId = "user-1", targetKcal = 2300, proteinG = 170, fatG = 75, carbohydrateG = 236,
                evidenceRefs = listOf("ENERGY-ESTIMATION"), recordedOn = "2026-07-21",
            ),
            structure = listOf(Meal("breakfast", "Завтрак", 0.30, 690, 51, 22, 71)),
            evidenceRefs = listOf("ENERGY-ESTIMATION"), createdAt = "2026-07-21",
        )
        val plan2 = plan1.copy(id = "user-1@2026-07-28", createdAt = "2026-07-28")

        open().use { r ->
            r.nutritionPlans.save(plan1)
            r.nutritionPlans.save(plan2)
        }

        open().use { r ->
            r.nutritionPlans.currentFor("user-1") shouldBe plan2
            r.nutritionPlans.byId("user-1@2026-07-21") shouldBe plan1
            r.nutritionPlans.byId("user-1@2026-07-28") shouldBe plan2
            r.nutritionPlans.historyFor("user-1").sortedBy { it.createdAt } shouldBe listOf(plan1, plan2)
        }
    }

    @Test
    fun `the db file on disk holds ciphertext, not plaintext`() {
        // Encrypt the sensitive payload, then prove the raw bytes on disk do not
        // contain the plaintext health data (e.g. the user's symptom text).
        val secret = listOf("lumbar tension")
        open().use { r ->
            r.symptoms.append(Symptom("s1", "user-1", "2026-07-21", "self-report", secret))
        }
        val onDisk = String(Files.readAllBytes(dbPath()), java.nio.charset.StandardCharsets.ISO_8859_1)
        // Byte-preserving decode: plaintext would appear verbatim iff its raw bytes did.
        (onDisk.contains(secret.first())) shouldBe false
        // ...but the store with the key round-trips it back exactly.
        open().use { r -> r.symptoms.byId("s1")?.currentSymptoms shouldBe secret }
    }

    @Test
    fun `a wrong key cannot read data`() {
        open().use { r -> r.users.save(User(
            id = "user-1",
            anthropometrics = Anthropometrics(SexForEquations.MALE, 28, 188.0, 83.2, 21.2),
            equipment = listOf("dumbbells"),
            preferences = UserPreferences(3, 2, 60, "calisthenics"),
            medicalContext = MedicalContext(scoliosisReported = true),
        )) }
        val wrongKey = EncryptionKeys.of(ByteArray(32) { (it + 99).toByte() })
        SqliteRepositories.open("jdbc:sqlite:${dbPath()}", wrongKey).use { r ->
            org.junit.jupiter.api.assertThrows<Exception> { r.users.byId("user-1") }
        }
    }

    private companion object {
        // Deterministic AES-256 key — stands in for the injected deployment secret.
        val TEST_KEY = EncryptionKeys.of(ByteArray(32) { (it + 1).toByte() })
    }
}