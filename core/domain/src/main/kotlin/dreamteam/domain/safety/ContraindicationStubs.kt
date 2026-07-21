package dreamteam.domain.safety

/**
 * DRAFT contraindication-rule SLOTS — engineering integration only, **no
 * clinical content**.
 *
 * Each entry is [RuleStatus.DRAFT], so [SafetyGuardedGateway] never evaluates
 * it: a draft rule cannot block (or unblock) anything. They exist so the Safety
 * Reviewer can see exactly where absolute-contraindication rules plug in, what
 * shape they take, and which clinical question remains open. Activating one
 * (filling its thresholds + sourcing evidence + flipping [SafetyRule.status] to
 * [RuleStatus.ACTIVE]) is a Safety Reviewer + Evidence Analyst decision — never
 * this engineer's. See the DRE-7 boundary: "you implement rules others define;
 * you do not author medical guidance."
 *
 * The mechanism ([RuleTrigger.ContraindicationStub]) is pure string matching on
 * a movement tag + a condition flag; the *medical* judgement — which movements
 * carry which tag, at what severity a condition flags, and whether a
 * combination is truly contraindicated — is precisely the TODO left here.
 *
 * Evidence is intentionally unsourced (`evidenceRefs = emptyList()`): per the
 * [dreamteam.domain.EvidenceLinked] contract, an empty list means
 * "blocked-until-sourced", so these cannot be activated without the Evidence
 * Analyst supplying a citation.
 */
object ContraindicationStubs {

    /**
     * Heavy axial loading for a flagged scoliosis condition.
     *
     * TODO(Safety Reviewer): define the blocking threshold (e.g. Cobb magnitude
     * / rigidity / brace status) and confirm the movement set tagged
     * `heavy_axial_loading`. TODO(Evidence Analyst): supply the citation before
     * activation.
     */
    val heavyAxialLoadingForFlaggedScoliosis = SafetyRule(
        id = "stub_heavy_axial_loading_scoliosis",
        description =
            "STUB (DRAFT) — heavy axial loading proposed for a flagged scoliosis " +
                "condition. TODO(Safety Reviewer): define the blocking threshold and exercise " +
                "set, source evidence, then set status = ACTIVE.",
        trigger =
            RuleTrigger.ContraindicationStub(
                exerciseTag = "heavy_axial_loading",
                conditionFlag = "scoliosis_flagged",
                clinicalQuestion =
                    "At what curve severity / presentation is heavy axial loading " +
                        "contraindicated, and which exercises carry the 'heavy_axial_loading' tag?",
            ),
        decision = SafetyRule.Decision.BLOCK,
        status = RuleStatus.DRAFT,
        evidenceRefs = emptyList(),
    )

    /**
     * Loaded flexion / rotation for a flagged scoliosis condition.
     *
     * TODO(Safety Reviewer): define which loaded flexion/rotation movements and
     * which presentations are blocked. TODO(Evidence Analyst): source it.
     */
    val loadedFlexionRotationForFlaggedScoliosis = SafetyRule(
        id = "stub_loaded_flexion_rotation_scoliosis",
        description =
            "STUB (DRAFT) — loaded flexion/rotation proposed for a flagged scoliosis " +
                "condition. TODO(Safety Reviewer): define the blocking threshold and exercise " +
                "set, source evidence, then set status = ACTIVE.",
        trigger =
            RuleTrigger.ContraindicationStub(
                exerciseTag = "loaded_flexion_rotation",
                conditionFlag = "scoliosis_flagged",
                clinicalQuestion =
                    "Which loaded flexion/rotation movements are contraindicated, and for " +
                        "which scoliosis presentations?",
            ),
        decision = SafetyRule.Decision.BLOCK,
        status = RuleStatus.DRAFT,
        evidenceRefs = emptyList(),
    )

    /** All DRAFT stubs — the Safety Reviewer's worklist. Inert until activated. */
    val all: List<SafetyRule> =
        listOf(
            heavyAxialLoadingForFlaggedScoliosis,
            loadedFlexionRotationForFlaggedScoliosis,
        )
}
