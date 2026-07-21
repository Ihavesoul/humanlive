package dreamteam.server.persistence

import dreamteam.domain.persistence.EvidenceSourceRepository
import dreamteam.domain.persistence.ExerciseRepository
import dreamteam.domain.persistence.NutritionRepository
import org.junit.jupiter.api.Test

/**
 * DRE-6 "repository layer so feature code never touches storage directly":
 * writes and reads every core aggregate through the *port interface*. Feature
 * code holds only the interface types — the concrete store is never visible.
 *
 * The assertions live once in [evidenceExerciseContract] /
 * [userPlanProgressSymptomNutritionContract]; the in-memory repos run them here,
 * the durable encrypted-SQLite repos run the same contract in
 * [SqliteRepositoryContractTest] (ADR 0003 / DRE-16).
 */
class RepositoryLayerTest {

    @Test
    fun `evidence and exercise round-trip through their ports with allowlist check`() {
        val evidence: EvidenceSourceRepository = InMemoryEvidenceSourceRepository()
        val exercises: ExerciseRepository = InMemoryExerciseRepository()
        evidenceExerciseContract(evidence, exercises)
    }

    @Test
    fun `user, plan, progress, symptom, nutrition round-trip through their ports`() {
        val users = InMemoryUserRepository()
        val plans = InMemoryTrainingPlanRepository()
        val progress = InMemoryProgressRepository()
        val symptoms = InMemorySymptomRepository()
        val nutrition: NutritionRepository = InMemoryNutritionRepository()
        userPlanProgressSymptomNutritionContract(users, plans, progress, symptoms, nutrition)
    }
}
