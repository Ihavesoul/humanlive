package dreamteam.domain.safety

import dreamteam.domain.EvidenceId

/**
 * **Structural** safety rules — the engineering-enforced invariants, *not*
 * clinical content. These implement ADR 0001 non-negotiables #1–2 ("evidence by
 * server-side allowlist", "exercise by allowlist"): a recommendation whose
 * exercise id or evidence id is not in the provisioned allowlist is BLOCKED
 * before it can reach the user. This is the chokepoint the
 * [dreamteam.domain.safety] threat model names ("unknown evidence/exercise ID:
 * reject response").
 *
 * Ownership boundary: these rules carry no medical judgement. *Which* exercises
 * and *which* evidence are allowlisted is repository content (the Evidence
 * Analyst's catalog + the exercise library); *whether an unlisted id is
 * rejected* is this rule — and that is the Founding Engineer's call, not the
 * Safety Reviewer's. Contrast with [ContraindicationStubs], which are the
 * clinical slots the Safety Reviewer fills.
 *
 * Both rules are [RuleStatus.ACTIVE] by default: they are the minimum
 * provisioning that lets a deterministic, self-sourced baseline plan pass the
 * gate while guaranteeing an unlisted id can never leak through. With only these
 * rules registered, [SafetyGuardedGateway.isProvisioned] is true and the
 * block-by-default empty-state is resolved (DRE-7) without any clinical
 * authoring.
 *
 * [evidenceRefs] uses a self-describing synthetic id rather than a study
 * citation: the justification for an allowlist rule is the SDD / threat model,
 * not a paper — matching how the existing rule tests reference structural
 * controls (`SAFETY-*` ids). It is not a medical claim.
 */
object StructuralSafetyRules {

    private const val STRUCTURAL_REF: EvidenceId = "SAFETY-STRUCTURAL-ALLOWLIST"

    /** Reject any recommendation whose exercise id is not in the allowlist. */
    val exerciseAllowlist = SafetyRule(
        id = "structural_exercise_allowlist",
        description =
            "Only allowlisted exercise ids reach the user (ADR 0001 §2; " +
                "SDD §2.6 step 3). An unlisted id is blocked, never warned.",
        trigger = RuleTrigger.ExerciseNotInAllowlist("__not_allowlisted__"),
        decision = SafetyRule.Decision.BLOCK,
        evidenceRefs = listOf(STRUCTURAL_REF),
        status = RuleStatus.ACTIVE,
    )

    /** Reject any recommendation that cites an evidence id not in the catalog allowlist. */
    val evidenceAllowlist = SafetyRule(
        id = "structural_evidence_allowlist",
        description =
            "Only catalog evidence ids reach the user (ADR 0001 §1; SDD §2.6 step 4). " +
                "The model never emits a DOI/PMID/URL — citations resolve server-side.",
        trigger = RuleTrigger.EvidenceNotInAllowlist("__not_allowlisted__"),
        decision = SafetyRule.Decision.BLOCK,
        evidenceRefs = listOf(STRUCTURAL_REF),
        status = RuleStatus.ACTIVE,
    )

    /** The minimum provisioning set: enforces both allowlists. */
    val all: List<SafetyRule> = listOf(exerciseAllowlist, evidenceAllowlist)
}
