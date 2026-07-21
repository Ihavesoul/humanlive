package dreamteam.domain.safety

import dreamteam.domain.EvidenceId
import kotlinx.serialization.Serializable

/**
 * What the engine is proposing to surface to the user. Minimal by design: the
 * rule engine only needs the ids/tags it must vet (the exercise + its evidence +
 * any contraindication tags).
 */
@Serializable
data class Recommendation(
    val exerciseId: String,
    override val evidenceRefs: List<EvidenceId>,
    /** Movement tags a contraindication rule may match on (e.g. "heavy_axial_loading"). */
    val exerciseTags: Set<String> = emptySet(),
) : dreamteam.domain.EvidenceLinked

/**
 * The user's screening context at evaluation time — the facts rules test
 * against. Populated from the user's medical context + the active allowlists
 * ([dreamteam.domain.user.User]; ADR 0001 §2).
 *
 * `redFlags` here is the domain enum view; the wire DTO [MedicalSafety] carries
 * the raw schema strings. [conditionFlags] is the generic condition view a
 * contraindication stub matches on (e.g. "scoliosis_flagged"). DRE-7 bridges
 * the wire DTO to this context.
 */
@Serializable
data class ScreeningContext(
    val redFlags: Set<RedFlag> = emptySet(),
    val sideSpecificLockEngaged: Boolean = false,
    val allowedExerciseIds: Set<String> = emptySet(),
    val allowedEvidenceIds: Set<EvidenceId> = emptySet(),
    val clinicianCurveSpecificPlanAvailable: Boolean = false,
    val conditionFlags: Set<String> = emptySet(),
)

/**
 * Applies a list of [SafetyRule]s to a [Recommendation] and returns a binding
 * [SafetyVerdict]. Distinct from the deterministic pre-LLM [SafetyGate]
 * (DRE-8), which vets the overall medical-safety DTO before generation starts;
 * this engine vets each *specific* recommendation against the registered rules.
 *
 * The first matching rule wins; an explicit [SafetyVerdict.Block] is returned
 * with **no override path** — there is no "dismiss" / "skip" parameter anywhere
 * in this API, by construction. Rule *content* (which flags/ids actually block)
 * is supplied by the caller — the Safety Reviewer's ruleset.
 *
 * **Block-by-default (DRE-7).** The conservative empty-state choice: when the
 * ruleset is empty (unprovisioned), every recommendation is blocked — unsafe
 * output cannot reach the user even before any real rules exist. Once a
 * non-empty ruleset is registered, a recommendation is allowed iff no rule
 * matches it: the Safety Reviewer's provisioned rules are the sign-off, and
 * "nothing blocked it" is then an allow. There is no per-call override of this.
 *
 * DRE-6 done-when ("a SafetyRule that can block a recommendation") and the
 * DRE-7 done-when ("unsafe output cannot reach the user even before any real
 * rules exist") are both pinned by [SafetyRuleEngineTest] and
 * [SafetyGuardedGatewayTest].
 */
object SafetyRuleEngine {
    private val UNPROVISIONED = SafetyVerdict.Block(
        reason = "Safety ruleset not provisioned; block-by-default (DRE-7).",
        ruleIds = emptyList(),
    )

    fun evaluate(
        recommendation: Recommendation,
        context: ScreeningContext,
        rules: List<SafetyRule>,
    ): SafetyVerdict {
        if (rules.isEmpty()) return UNPROVISIONED
        for (rule in rules) {
            if (matches(rule.trigger, recommendation, context)) {
                return SafetyVerdict.Block(reason = rule.description, ruleIds = listOf(rule.id))
            }
        }
        return SafetyVerdict.Allow
    }

    private fun matches(
        trigger: RuleTrigger,
        rec: Recommendation,
        ctx: ScreeningContext,
    ): Boolean = when (trigger) {
        is RuleTrigger.RedFlagPresent -> trigger.flag in ctx.redFlags
        is RuleTrigger.SideSpecificLockEngaged -> ctx.sideSpecificLockEngaged
        is RuleTrigger.ExerciseNotInAllowlist -> rec.exerciseId !in ctx.allowedExerciseIds
        is RuleTrigger.EvidenceNotInAllowlist -> rec.evidenceRefs.any { it !in ctx.allowedEvidenceIds }
        is RuleTrigger.ClinicianCurveSpecificPlanRequired -> !ctx.clinicianCurveSpecificPlanAvailable
        is RuleTrigger.ContraindicationStub ->
            trigger.exerciseTag in rec.exerciseTags && trigger.conditionFlag in ctx.conditionFlags
    }
}
