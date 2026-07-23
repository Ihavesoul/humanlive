package dreamteam.server.persistence

import dreamteam.domain.UserId
import dreamteam.domain.evidence.EvidenceSource
import dreamteam.domain.exercise.Exercise
import dreamteam.domain.nutrition.NutritionPlan
import dreamteam.domain.nutrition.NutritionTarget
import dreamteam.domain.persistence.EvidenceSourceRepository
import dreamteam.domain.persistence.ExerciseRepository
import dreamteam.domain.persistence.NutritionPlanRepository
import dreamteam.domain.persistence.NutritionRepository
import dreamteam.domain.persistence.ProgressRepository
import dreamteam.domain.persistence.SafetyRuleRepository
import dreamteam.domain.persistence.SymptomRepository
import dreamteam.domain.persistence.TrainingPlanRepository
import dreamteam.domain.persistence.UserRepository
import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.safety.SafetyRule
import dreamteam.domain.symptom.Symptom
import dreamteam.domain.training.TrainingPlan
import dreamteam.domain.user.User
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory reference implementation of the persistence ports.
 *
 * The backend durable store behind these interfaces is chosen separately (ADR
 * 0001 leaves the engine open); this implementation makes the repository layer
 * real *now* so feature code (DRE-7 safety engine, the orchestrator) depends on
 * the interfaces and has a working store to run against. It is thread-safe via
 * [ConcurrentHashMap]; it is NOT durable across process restarts by design —
 * durability is the job of the concrete persistent store that will back these
 * same interfaces.
 */
class InMemoryEvidenceSourceRepository : EvidenceSourceRepository {
    private val store = ConcurrentHashMap<String, EvidenceSource>()
    override fun all(): List<EvidenceSource> = store.values.toList()
    override fun byId(id: String): EvidenceSource? = store[id]
    override fun contains(id: String): Boolean = store.containsKey(id)
    override fun save(source: EvidenceSource) { store[source.id] = source }
}

class InMemoryExerciseRepository : ExerciseRepository {
    private val store = ConcurrentHashMap<String, Exercise>()
    override fun all(): List<Exercise> = store.values.toList()
    override fun byId(id: String): Exercise? = store[id]
    override fun contains(id: String): Boolean = store.containsKey(id)
    override fun save(exercise: Exercise) { store[exercise.id] = exercise }
}

class InMemorySafetyRuleRepository : SafetyRuleRepository {
    private val store = ConcurrentHashMap<String, SafetyRule>()
    override fun all(): List<SafetyRule> = store.values.toList()
    override fun byId(id: String): SafetyRule? = store[id]
    override fun save(rule: SafetyRule) { store[rule.id] = rule }
}

class InMemoryUserRepository : UserRepository {
    private val store = ConcurrentHashMap<String, User>()
    override fun byId(id: String): User? = store[id]
    override fun save(user: User) { store[user.id] = user }
}

class InMemoryTrainingPlanRepository : TrainingPlanRepository {
    private val byId = ConcurrentHashMap<String, TrainingPlan>()
    private val currentByUser = ConcurrentHashMap<UserId, String>()
    override fun currentFor(userId: UserId): TrainingPlan? = currentByUser[userId]?.let { byId[it] }
    override fun byId(id: String): TrainingPlan? = byId[id]
    override fun historyFor(userId: UserId): List<TrainingPlan> =
        byId.values.filter { it.userId == userId }.sortedBy { it.createdAt }
    override fun save(plan: TrainingPlan) {
        byId[plan.id] = plan
        currentByUser[plan.userId] = plan.id
    }
}

class InMemoryProgressRepository : ProgressRepository {
    private val byUser = ConcurrentHashMap<UserId, MutableList<ProgressEntry>>()
    override fun recentFor(userId: UserId, limit: Int): List<ProgressEntry> =
        byUser[userId]?.takeLast(limit) ?: emptyList()
    override fun append(entry: ProgressEntry) {
        byUser.computeIfAbsent(entry.userId) { mutableListOf() }.add(entry)
    }
}

class InMemorySymptomRepository : SymptomRepository {
    private val byId = ConcurrentHashMap<String, Symptom>()
    private val byUser = ConcurrentHashMap<UserId, MutableList<Symptom>>()
    override fun recentFor(userId: UserId, limit: Int): List<Symptom> =
        byUser[userId]?.takeLast(limit) ?: emptyList()
    override fun byId(id: String): Symptom? = byId[id]
    override fun append(entry: Symptom) {
        byId[entry.id] = entry
        byUser.computeIfAbsent(entry.userId) { mutableListOf() }.add(entry)
    }
}

class InMemoryNutritionRepository : NutritionRepository {
    private val currentByUser = ConcurrentHashMap<UserId, NutritionTarget>()
    override fun currentFor(userId: UserId): NutritionTarget? = currentByUser[userId]
    override fun save(target: NutritionTarget) { currentByUser[target.userId] = target }
}

class InMemoryNutritionPlanRepository : NutritionPlanRepository {
    private val byId = ConcurrentHashMap<String, NutritionPlan>()
    private val currentByUser = ConcurrentHashMap<UserId, String>()
    override fun currentFor(userId: UserId): NutritionPlan? = currentByUser[userId]?.let { byId[it] }
    override fun byId(id: String): NutritionPlan? = byId[id]
    override fun historyFor(userId: UserId): List<NutritionPlan> =
        byId.values.filter { it.userId == userId }.sortedBy { it.createdAt }
    override fun save(plan: NutritionPlan) {
        byId[plan.id] = plan
        currentByUser[plan.userId] = plan.id
    }
}
