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

    /**
     * An absolute-contraindication slot: block when a recommendation carrying
     * [exerciseTag] is proposed for a user with [conditionFlag]. Pure mechanism
     * (string matching) — *which* tags/flags and combinations actually block is
     * a Safety Reviewer decision. [clinicalQuestion] makes the open clinical
     * content machine-visible so it can be tracked to sign-off.
     */
    @Serializable @SerialName("contraindication_stub")
    data class ContraindicationStub(
        val exerciseTag: String,
        val conditionFlag: String,
        val clinicalQuestion: String,
    ) : RuleTrigger
}

/**
 * Lifecycle of a [SafetyRule]. The gateway evaluates [ACTIVE] rules only; a
 * [DRAFT] rule is a documented slot the Safety Reviewer must fill and sign off
 * before it can block anything. This keeps the engineering integration (where a
 * contraindication plugs in) decoupled from the clinical authoring (the actual
 * blocking decision), which is never this engineer's call.
 */
@Serializable
enum class RuleStatus {
    @SerialName("draft") DRAFT,
    @SerialName("active") ACTIVE,
}

/**
 * A safety rule the engine evaluates. When its [trigger] matches the screening
 * context, the recommendation is BLOCKED — never warned-and-dismissed.
 *
 * DRE-6 models the mechanism and the non-overridable decision. The *content*
 * (which flags, which thresholds, which exercise/evidence ids) is authored by
 * the Safety Reviewer and wired in DRE-7. This type carries rules; it does not
 * invent clinical guidance. Every rule carries [evidenceRefs] so a block is
 * itself traceable. [status] separates an authored, active rule
 * ([RuleStatus.ACTIVE]) from a documented-but-pending stub ([RuleStatus.DRAFT]).
 */
@Serializable
data class SafetyRule(
    val id: RuleId,
    val description: String,
    val trigger: RuleTrigger,
    val decision: Decision,
    override val evidenceRefs: List<EvidenceId>,
    val status: RuleStatus = RuleStatus.ACTIVE,
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
