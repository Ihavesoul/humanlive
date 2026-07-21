package dreamteam.domain.safety

import dreamteam.domain.EvidenceId
import dreamteam.domain.EvidenceLinked
import dreamteam.domain.RuleId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Red flags that hard-block guidance and require clinical escalation. Closed set
 * derived from data/safety_screening.json -> `items`. Adding a flag is a Safety
 * Reviewer decision (a clinical rule), not an engineering one.
 */
@Serializable
enum class RedFlag {
    @SerialName("progressive_leg_weakness") PROGRESSIVE_LEG_WEAKNESS,
    @SerialName("numbness_or_saddle_anaesthesia") NUMBNESS_OR_SADDLE_ANAESTHESIA,
    @SerialName("bowel_or_bladder_dysfunction") BOWEL_OR_BLADDER_DYSFUNCTION,
    @SerialName("night_pain") NIGHT_PAIN,
    @SerialName("unintentional_weight_loss") UNINTENTIONAL_WEIGHT_LOSS,
    @SerialName("fever") FEVER,
    @SerialName("recent_major_trauma") RECENT_MAJOR_TRAUMA,
    @SerialName("rapid_neurological_progression") RAPID_NEUROLOGICAL_PROGRESSION,
}

/**
 * The condition under which a [SafetyRule] fires. Sealed so the engine is forced
 * to handle every case. New triggers (e.g. a future heart-rate ceiling) are a
 * deliberate addition, not a silent string match.
 */
@Serializable
sealed interface RuleTrigger {
    @Serializable @SerialName("red_flag_present")
    data class RedFlagPresent(val flag: RedFlag) : RuleTrigger

    /** Side-specific lock engaged: no Cobb/curve data => no directional guidance (ADR 0001 §3). */
    @Serializable @SerialName("side_specific_lock_engaged")
    data class SideSpecificLockEngaged(val reason: String) : RuleTrigger

    @Serializable @SerialName("exercise_not_in_allowlist")
    data class ExerciseNotInAllowlist(val exerciseId: String) : RuleTrigger

    @Serializable @SerialName("evidence_not_in_allowlist")
    data class EvidenceNotInAllowlist(val evidenceId: String) : RuleTrigger

    @Serializable @SerialName("clinician_curve_specific_plan_required")
    data class ClinicianCurveSpecificPlanRequired(val reason: String) : RuleTrigger
}

/**
 * A safety rule the engine evaluates. When its [trigger] matches the screening
 * context, the recommendation is BLOCKED — never warned-and-dismissed.
 *
 * DRE-6 models the mechanism and the non-overridable decision. The *content*
 * (which flags, which thresholds, which exercise/evidence ids) is authored by
 * the Safety Reviewer and wired in DRE-7. This type carries rules; it does not
 * invent clinical guidance. Every rule carries [evidenceRefs] so a block is
 * itself traceable.
 */
@Serializable
data class SafetyRule(
    val id: RuleId,
    val description: String,
    val trigger: RuleTrigger,
    val decision: Decision,
    override val evidenceRefs: List<EvidenceId>,
) : EvidenceLinked {
    /** BLOCK = the recommendation never reaches the user. REQUIRE_CLINICIAN = escalate. */
    @Serializable
    enum class Decision { BLOCK, REQUIRE_CLINICIAN }
}

/** Outcome of evaluating rules against a recommendation. */
@Serializable
sealed interface SafetyVerdict {
    @Serializable @SerialName("allow")
    data object Allow : SafetyVerdict

    @Serializable @SerialName("block")
    data class Block(val reason: String, val ruleIds: List<RuleId>) : SafetyVerdict
}
