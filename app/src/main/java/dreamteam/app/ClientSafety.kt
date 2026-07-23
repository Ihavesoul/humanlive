package dreamteam.app

import dreamteam.domain.RuleId
import dreamteam.domain.safety.ContraindicationStubs
import dreamteam.domain.safety.SafetyRule
import dreamteam.domain.safety.StructuralSafetyRules

/**
 * M6-C ([DRE-69](/DRE/issues/DRE-69)): the explainable-safety-block surface —
 * the pure render that turns a gate **block** into a transparent explanation by
 * resolving the *blocking* [SafetyRule]'s `evidenceRefs` to readable citations.
 *
 * The mission promises an app that "**blocks unsafe guidance**". The block
 * itself already *works* (the [dreamteam.domain.safety.SafetyGuardedGateway] is
 * block-by-default; `SurfacedPlan.Blocked.surfaced == []`), but it was *opaque*:
 * the client surfaced authored warning strings and never showed *why* a rule
 * blocks in traceable terms. This slice resolves those refs via the SAME M6-A
 * ([DRE-67](/DRE/issues/DRE-67)) resolver + M6-B ([DRE-68](/DRE/issues/DRE-68))
 * `resolveCitations` path the nutrition/training surfaces already use — one
 * render path, no second catalog, no invented citation (a ghost id renders the
 * `EVIDENCE_NOT_SOURCED` placeholder, honoring EvidenceLinked).
 *
 * Deterministic, read-only — **no rule added, no rule changed, no gate
 * bypassed**: citations *explain* existing rules only. They are explanation of
 * the block, not rendered guidance (nothing is surfaced when the gate blocks).
 *
 * Pure + JVM-testable (the [dreamteam.app.ClientNutrition.nutritionPlanView]
 * pattern): [safetyBlockExplanation] takes a resolver + the rule list + the
 * verdict's rule ids and returns citation rows — no Android, no I/O. Android I/O
 * stays at the edge ([loadEvidenceResolver], decoded once at the Compose root).
 */

/**
 * The client-side rule set the gate is provisioned with — the SAME list
 * [DreamTeamApp.generateLocalPlan] builds the gateway from (`StructuralSafetyRules.all
 * + ContraindicationStubs.all`). Exposed once so the block explanation's
 * `ruleIds → SafetyRule → evidenceRefs` lookup is the single source of truth:
 * there is no second hand-rolled list that could drift from what actually blocks.
 */
internal val CLIENT_SAFETY_RULES: List<SafetyRule> = StructuralSafetyRules.all + ContraindicationStubs.all

/**
 * What the block card renders for a gate block: a support-framed headline ([reason])
 * plus the **readable citations** for the blocking rules' `evidenceRefs` (M6-C).
 * A ghost id (e.g. a synthetic structural-guardrail id) renders the
 * blocked-until-sourced placeholder; a real study id renders author/year +
 * keyFinding + evidenceLevel. Citations explain the block — they are NOT guidance.
 */
internal data class SafetyBlockExplanation(
    val reason: String,
    val citations: List<ResolvedCitation>,
)

/**
 * Resolve a gate block's triggering [ruleIds] to a transparent explanation:
 * look each id up in [rules] → its `evidenceRefs` → readable citations via the
 * M6-A/M6-B path. Pure (no Android, no I/O) so a JVM test pins the two M6-C
 * guarantees: a contraindication block surfaces ≥1 resolved citation, and a
 * synthetic allowlist block renders the `EVIDENCE_NOT_SOURCED` placeholder.
 *
 * A rule id with no registered rule (should not happen — the client provisions
 * the set) contributes no refs. Refs are de-duplicated across blocking rules.
 */
internal fun safetyBlockExplanation(
    reason: String,
    ruleIds: List<RuleId>,
    rules: List<SafetyRule>,
    resolver: EvidenceResolver,
): SafetyBlockExplanation {
    val byId: Map<RuleId, SafetyRule> = rules.associateBy { it.id }
    val refs = ruleIds.flatMap { id -> byId[id]?.evidenceRefs ?: emptyList() }.distinct()
    return SafetyBlockExplanation(reason = reason, citations = resolveCitations(refs, resolver))
}

/**
 * The authored block-framing strings the block card renders. Gathered as one
 * list so a JVM test can snapshot them against the banned medical-claim phrase
 * list (mirrors [dreamteam.app.TodayStrings] / [dreamteam.app.HistoryStrings]).
 * Support framing only: no diagnosis, no "у вас …", no treatment/cure. The
 * verbatim catalog citation ROWS are deliberately NOT in [all] — they
 * legitimately carry study vocabulary ("health", "treatment") inside titles, so
 * the crude-substring scan would false-positive on vetted evidence (the M6-B
 * design call); the catalog-side claim guard is rendering them verbatim.
 */
internal object SafetyBlockStrings {
    /** A gate block (rule-engine verdict): nothing reached the user. */
    const val GATEWAY_HEADLINE = "План заблокирован шлюзом безопасности."
    /**
     * The medical-safety gate ([dreamteam.domain.safety.SafetyGate]) routed to
     * assessment before generation. Support framing — the prior copy used
     * "не ставит диагноз", which contains the banned morpheme "диагноз"; aligned
     * to the established scan-clean stance ("поддерживает, а не заменяет врача",
     * cf. [dreamteam.app.HistoryStrings.SUPPORT]). Not a gate change: the red
     * flag still blocks; only the user-facing copy is reframed.
     */
    const val REDFLAG_HEADLINE =
        "Красный флаг: обратитесь за медицинской оценкой. Приложение поддерживает, а не заменяет врача."
    /** The transparency label that prefixes the blocking rule's citations. */
    const val CITATION_LABEL = "Основание:"

    val all: List<String> = listOf(GATEWAY_HEADLINE, REDFLAG_HEADLINE, CITATION_LABEL)
}
