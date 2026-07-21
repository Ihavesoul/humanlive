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
     * THRESHOLD (resolved by Safety Reviewer, DRE-10): the rule fires on the
     * condition flag `scoliosis_flagged`, which is set when a user reports a
     * *diagnosed* scoliosis in a presentation where heavy compressive axial
     * loading is classically cautioned — i.e. any of:
     *   - Cobb angle >= ~30° (moderate or greater; the boundary above which a
     *     curve is typically structurally rigid), OR
     *   - a known rigid / structural curve (reduced flexibility on screening,
     *     previously braced or fused), OR
     *   - current brace wear (brace protocol generally contraindicates heavy
     *     loaded spinal compression).
     * A self-reported *mild* curve (<~20°, flexible, never braced) does NOT set
     * the flag and is not blocked here — it is served the generic baseline.
     *
     * MOVEMENT SET (`heavy_axial_loading` tag): exercises that impose
     * substantial compressive/axial load on the spine under heavy external
     * load — e.g. barbell back/front squat, overhead/standing barbell press,
     * heavy deadlift and good-morning variations, heavy loaded carries, heavy
     * standing work under a bar. Bodyweight and light unilateral variants
     * (split squat, goblet squat, push-up, single-arm row) are NOT in this set
     * and remain on the generic baseline. (Wiring these tags onto the exercise
     * library is a data task for the library owner, not this rule.)
     *
     * EVIDENCE STATUS: this is *consensus / conservative* guidance — no RCT
     * proves heavy axial loading worsens adult scoliosis. Activation therefore
     * requires a conservative source the Evidence Analyst rates honestly as
     * low-to-moderate / consensus-grade (never inflated). `evidenceRefs` stays
     * empty until DRE-14 supplies a resolvable EvidenceId; per the
     * [dreamteam.domain.EvidenceLinked] contract an empty list means
     * "blocked-until-sourced", so `status` stays [RuleStatus.DRAFT] until then.
     * This is a support-not-treat rule: it blocks an unsafe load, it does not
     * prescribe curve-specific treatment.
     */
    val heavyAxialLoadingForFlaggedScoliosis = SafetyRule(
        id = "stub_heavy_axial_loading_scoliosis",
        description =
            "Heavy axial loading (e.g. barbell back/front squat, overhead press, " +
                "heavy deadlift/carry) is blocked for a flagged scoliosis presentation — " +
                "Cobb >= ~30°, a rigid/structural curve, or current brace wear. Generic " +
                "baseline movements remain available; curve-specific loaded work needs " +
                "clinician-taught direction. (Content defined DRE-10; DRAFT pending sourcing.)",
        trigger =
            RuleTrigger.ContraindicationStub(
                exerciseTag = "heavy_axial_loading",
                conditionFlag = "scoliosis_flagged",
                clinicalQuestion =
                    "Resolved (DRE-10): block heavy_axial_loading for scoliosis_flagged = " +
                        "diagnosed moderate-or-greater (Cobb >= ~30°) / rigid / braced. " +
                        "PENDING: Evidence Analyst sourcing (DRE-14) before activation.",
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
