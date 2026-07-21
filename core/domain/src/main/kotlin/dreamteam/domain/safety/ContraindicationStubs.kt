package dreamteam.domain.safety

/**
 * Contraindication-rule SLOTS where absolute-contraindication rules plug in.
 * Clinical content is authored by the Safety Reviewer + sourced by the Evidence
 * Analyst, never by the implementing engineer.
 *
 * Each entry starts as [RuleStatus.DRAFT], so [SafetyGuardedGateway] never
 * evaluates it: a draft rule cannot block (or unblock) anything. Filling its
 * thresholds + sourcing evidence + flipping [SafetyRule.status] to
 * [RuleStatus.ACTIVE] is a Safety Reviewer + Evidence Analyst decision — never
 * this engineer's (DRE-7 boundary: "you implement rules others define; you do
 * not author medical guidance"). Current state:
 *   - [heavyAxialLoadingForFlaggedScoliosis] — ACTIVE + sourced (DRE-10/DRE-20).
 *   - [loadedFlexionRotationForFlaggedScoliosis] — ACTIVE + sourced (DRE-25; a
 *     precautionary two-source bundle). Both contraindication slots now provision
 *     the gate.
 *
 * The mechanism ([RuleTrigger.ContraindicationStub]) is pure string matching on
 * a movement tag + a condition flag; the *medical* judgement — which movements
 * carry which tag, at what severity a condition flags, and whether a
 * combination is truly contraindicated — is precisely the TODO left here.
 *
 * The "blocked-until-sourced" gate (per [dreamteam.domain.EvidenceLinked], an
 * empty `evidenceRefs` means "not safe to activate") is honored per-rule: an
 * ACTIVE rule always carries a resolvable [EvidenceId]; a DRAFT slot keeps
 * `evidenceRefs = emptyList()` until the Evidence Analyst supplies one.
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
     * EVIDENCE STATUS: *consensus / conservative* guidance — no RCT proves
     * heavy axial loading worsens scoliosis. Sourced + ACTIVATED (DRE-10/DRE-20):
     * `evidenceRefs = WEINSTEIN-AIS-2008` (Lancet Seminar, rated
     * `moderate_context` honestly — context-grade, never inflated to "proof").
     * Per the [dreamteam.domain.EvidenceLinked] contract the resolvable id lifts
     * the blocked-until-sourced hold, so `status = ACTIVE` and this rule is what
     * provisions [SafetyGuardedGateway] today. This is a support-not-treat rule:
     * it blocks an unsafe load, it does not prescribe curve-specific treatment.
     */
    val heavyAxialLoadingForFlaggedScoliosis = SafetyRule(
        id = "stub_heavy_axial_loading_scoliosis",
        description =
            "Heavy axial loading (e.g. barbell back/front squat, overhead press, " +
                "heavy deadlift/carry) is blocked for a flagged scoliosis presentation — " +
                "Cobb >= ~30°, a rigid/structural curve, or current brace wear. Generic " +
                "baseline movements remain available; curve-specific loaded work needs " +
                "clinician-taught direction. ACTIVE — sourced WEINSTEIN-AIS-2008 " +
                "(moderate_context, consensus/conservative; no RCT proves load worsens " +
                "curves).",
        trigger =
            RuleTrigger.ContraindicationStub(
                exerciseTag = "heavy_axial_loading",
                conditionFlag = "scoliosis_flagged",
                clinicalQuestion =
                    "ACTIVATED (DRE-10/DRE-20): block heavy_axial_loading for " +
                        "scoliosis_flagged = diagnosed moderate-or-greater (Cobb >= ~30°) / " +
                        "rigid / braced. Sourced via WEINSTEIN-AIS-2008 (moderate_context).",
            ),
        decision = SafetyRule.Decision.BLOCK,
        status = RuleStatus.ACTIVE,
        evidenceRefs = listOf("WEINSTEIN-AIS-2008"),
    )

    /**
     * Loaded flexion / rotation for a flagged scoliosis condition.
     *
     * THRESHOLD (resolved by Safety Reviewer, DRE-10): same gate as the axial
     * loading rule — fires on `scoliosis_flagged` (diagnosed moderate-or-greater,
     * Cobb >= ~30°, or rigid/structural, or currently braced). The rotational
     * concern is sharpest with measurable apical vertebral rotation, but the
     * screening flag is the gating condition here; a self-reported mild / flexible
     * curve does not set it and is not blocked.
     *
     * MOVEMENT SET (`loaded_flexion_rotation` tag): combined spinal flexion +
     * axial rotation under substantial external load — e.g. loaded Russian twist
     * / loaded trunk rotation, heavy cable or landmine woodchop/rotation, bent
     * rotational row, heavy unilateral rotational carry, loaded good-morning with
     * rotation. The mechanism: combined loaded flexion + rotation multiplies the
     * rotational / axial torque on an already-rotated (apical) vertebra in a
     * scoliotic curve. Symmetric, light, or unloaded anti-rotation (Pallof press,
     * birddog) is NOT in this set and stays on the generic baseline.
     *
     * EVIDENCE STATUS: *precautionary / conservative* caution — no RCT proves
     * loaded rotation worsens scoliosis. Sourced + ACTIVATED (DRE-25) with a
     * two-source bundle that isolates the damaging *combination* (loaded flexion
     * + axial rotation), not pure rotation: `MARSHALL-MCGILL-AXIAL-TORQUE-2010`
     * (ex-vivo porcine mechanism, rated `low_moderate` honestly — combined
     * flexion + axial torque delaminates the annulus; torque alone could not
     * initiate injury) + `MARRAS-TRUNK-MOTION-1993` (in-vivo human epidemiology,
     * `moderate_context` — trunk flexion + twist/load moment distinguishes
     * high-risk jobs). Neither is scoliosis-specific; together they are a
     * defensible *precautionary* basis, never inflated to "proof" (reusing the
     * axial rule's WEINSTEIN-AIS-2008 here would be inflation — its catalog scope
     * is axial loading only). Per the [dreamteam.domain.EvidenceLinked] contract
     * the resolvable ids lift the blocked-until-sourced hold, so `status =
     * ACTIVE`. Support-not-treat: withholds a torsional load; prescribes nothing
     * curve-specific.
     */
    val loadedFlexionRotationForFlaggedScoliosis = SafetyRule(
        id = "stub_loaded_flexion_rotation_scoliosis",
        description =
            "Loaded combined flexion + rotation under heavy load (e.g. loaded Russian " +
                "twist, cable/landmine woodchop, bent rotational row) is blocked for a " +
                "flagged scoliosis presentation (Cobb >= ~30° / rigid / braced) — the " +
                "multiplied rotational torque can aggravate the rotational curve " +
                "component. Symmetric/light anti-rotation (Pallof, birddog) stays on the " +
                "generic baseline. ACTIVE — sourced MARSHALL-MCGILL-AXIAL-TORQUE-2010 " +
                "(low_moderate, mechanism) + MARRAS-TRUNK-MOTION-1993 (moderate_context, " +
                "human epi); precautionary, no RCT proves rotation worsens curves.)",
        trigger =
            RuleTrigger.ContraindicationStub(
                exerciseTag = "loaded_flexion_rotation",
                conditionFlag = "scoliosis_flagged",
                clinicalQuestion =
                    "ACTIVATED (DRE-25): block loaded_flexion_rotation for scoliosis_flagged " +
                        "(Cobb >= ~30° / rigid / braced). Sourced via MARSHALL-MCGILL-AXIAL-" +
                        "TORQUE-2010 (low_moderate) + MARRAS-TRUNK-MOTION-1993 " +
                        "(moderate_context) — precautionary; neither is scoliosis-specific.",
            ),
        decision = SafetyRule.Decision.BLOCK,
        status = RuleStatus.ACTIVE,
        evidenceRefs =
            listOf(
                "MARSHALL-MCGILL-AXIAL-TORQUE-2010",
                "MARRAS-TRUNK-MOTION-1993",
            ),
    )

    /** All contraindication rules: both ACTIVE + sourced (both provision the gate). */
    val all: List<SafetyRule> =
        listOf(
            heavyAxialLoadingForFlaggedScoliosis,
            loadedFlexionRotationForFlaggedScoliosis,
        )
}
