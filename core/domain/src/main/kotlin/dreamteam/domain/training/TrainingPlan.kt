package dreamteam.domain.training

import dreamteam.domain.EvidenceId
import dreamteam.domain.EvidenceLinked
import dreamteam.domain.ExerciseId
import dreamteam.domain.PlanId
import dreamteam.domain.UserId
import kotlinx.serialization.Serializable

/**
 * A single exercise assignment inside a plan session. Carries evidence per item
 * (DRE-6 done-when: every plan item can reference an EvidenceSource) so a block
 * or citation can be traced to the exact assignment, not just the plan.
 */
@Serializable
data class ExerciseAssignment(
    val exerciseId: ExerciseId,
    val sets: Int,
    val repScheme: String,
    val rir: Int? = null,
    override val evidenceRefs: List<EvidenceId>,
) : EvidenceLinked

@Serializable
data class PlanSession(
    val id: String,
    val day: String,
    val label: String,
    val assignments: List<ExerciseAssignment>,
)

@Serializable
data class PlanWeek(
    val weekNumber: Int,
    val phase: String,
    val setsMain: Int,
    val rir: Int,
    val volumeFactor: Double,
    val notes: String = "",
    val sessions: List<PlanSession>,
)

/**
 * A user's training plan. Mirrors data/program_12_weeks.json. A plan is never
 * surfaced to the user without first passing the [dreamteam.domain.safety.SafetyGate].
 */
@Serializable
data class TrainingPlan(
    val id: PlanId,
    val userId: UserId,
    val name: String,
    val weeks: List<PlanWeek>,
    val createdAt: String,
)
