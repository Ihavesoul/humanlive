package dreamteam.domain.persistence

import dreamteam.domain.EvidenceId
import dreamteam.domain.ExerciseId
import dreamteam.domain.PlanId
import dreamteam.domain.ProgressId
import dreamteam.domain.RuleId
import dreamteam.domain.SymptomId
import dreamteam.domain.UserId
import dreamteam.domain.evidence.EvidenceSource
import dreamteam.domain.exercise.Exercise
import dreamteam.domain.nutrition.NutritionTarget
import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.safety.SafetyRule
import dreamteam.domain.symptom.Symptom
import dreamteam.domain.training.TrainingPlan
import dreamteam.domain.user.User

/**
 * Read/write ports the feature layer depends on. Feature code NEVER touches a
 * concrete store; it depends on these interfaces. Concrete implementations live
 * in `:server` (ADR 0001: the backend is the durable source of truth). A second
 * in-memory implementation backs tests.
 *
 * `contains` is the allowlist check that enforces evidence-by-allowlist and
 * exercise-by-allowlist (ADR 0001 non-negotiables #1–2): a recommendation
 * referencing an id not `contains`-ed is blocked by the safety gate.
 */
interface EvidenceSourceRepository {
    fun all(): List<EvidenceSource>
    fun byId(id: EvidenceId): EvidenceSource?
    fun contains(id: EvidenceId): Boolean
    fun save(source: EvidenceSource)
}

interface ExerciseRepository {
    fun all(): List<Exercise>
    fun byId(id: ExerciseId): Exercise?
    fun contains(id: ExerciseId): Boolean
    fun save(exercise: Exercise)
}

interface SafetyRuleRepository {
    fun all(): List<SafetyRule>
    fun byId(id: RuleId): SafetyRule?
    fun save(rule: SafetyRule)
}

interface UserRepository {
    fun byId(id: UserId): User?
    fun save(user: User)
}

interface TrainingPlanRepository {
    fun currentFor(userId: UserId): TrainingPlan?
    fun byId(id: PlanId): TrainingPlan?
    fun save(plan: TrainingPlan)
}

interface ProgressRepository {
    fun recentFor(userId: UserId, limit: Int): List<ProgressEntry>
    fun append(entry: ProgressEntry)
}

interface SymptomRepository {
    fun recentFor(userId: UserId, limit: Int): List<Symptom>
    fun byId(id: SymptomId): Symptom?
    fun append(entry: Symptom)
}

interface NutritionRepository {
    fun currentFor(userId: UserId): NutritionTarget?
    fun save(target: NutritionTarget)
}
