package dreamteam.domain.safety

/**
 * Contraindication-rule SLOTS — engineering integration shape with the clinical
 * *spec* authored by the Safety Reviewer (threshold + movement set). A slot is
 * inert ([RuleStatus.DRAFT] with `evidenceRefs = emptyList()`, so
 * [SafetyGuardedGateway] never evaluates it) until the Evidence Analyst sources
 * a citation and the Safety Reviewer flips it to [RuleStatus.ACTIVE].
 * `heavyAxialLoadingForFlaggedScoliosis` is now ACTIVE + sourced
 * (`WEINSTEIN-AIS-2008`); `loadedFlexionRotationForFlaggedScoliosis` remains a
 * DRAFT slot pending its own sourcing.
 *
 * They exist so the Safety Reviewer can see exactly where absolute-
 * contraindication rules plug in and what each rule means. Activating one
 * (sourcing evidence via the Evidence Analyst + flipping [SafetyRule.status] to
 * [RuleStatus.ACTIVE]) is a Safety Reviewer + Evidence Analyst decision — never
 * this engineer's. See the DRE-7 boundary: "you implement rules others define;
 * you do not author medical guidance." The spec text here is the rule the
 * Founding Engineer's flag-derivation and exercise-tagging must match.
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
     * ACTIVE — sourced via `WEINSTEIN-AIS-2008` (`evidence_level: moderate_context`,
     * precautionary: no RCT proves heavy axial loading worsens adult scoliosis;
     * the block is the conservative, specialist-cleared default). The Evidence
     * Analyst's catalog (`data/evidence_catalog.json`) resolves the id, so the
     * rule is traceable end-to-end. The threshold below is the spec the Founding
     * Engineer's `scoliosis_flagged` derivation (and the `heavy_axial_loading`
     * exercise tagging in data/exercises.json) must match.
     *
     * CONDITION FLAG — `scoliosis_flagged` means a *diagnosed* scoliosis in a
     * presentation where heavy compressive axial loading is classically
     * cautioned: Cobb angle >= ~30 deg (moderate-or-greater), OR a
     * rigid/structural curve (reduced flexibility, previously braced or fused),
     * OR current brace wear. A self-reported mild curve (<~20 deg, flexible,
     * never braced) does NOT set the flag and stays on the generic baseline.
     *
     * MOVEMENT SET — `heavy_axial_loading` = substantial compressive/axial load
     * under heavy external load: barbell back/front squat, overhead/standing
     * barbell press, heavy deadlift and good-morning variants, heavy loaded
     * carries, heavy standing barbell work. Bodyweight/light unilateral variants
     * (split squat, goblet squat, push-up, single-arm row) are EXCLUDED — they
     * stay on the baseline.
     *
     * Claim boundary: support-not-treat. This blocks an unsafe load; it
     * prescribes nothing curve-specific.
     */
    val heavyAxialLoadingForFlaggedScoliosis = SafetyRule(
        id = "stub_heavy_axial_loading_scoliosis",
        description =
            "Heavy axial loading for a flagged scoliosis condition is blocked. Blocks heavy " +
                "compressive/axial loading (e.g. barbell back/front squat, standing barbell " +
                "press, heavy deadlift/good-morning, heavy loaded carries) for " +
                "moderate-or-greater (Cobb >= ~30 deg), rigid/structural, or currently-braced " +
                "scoliosis. Support-not-treat: blocks an unsafe load; prescribes nothing " +
                "curve-specific.",
        trigger =
            RuleTrigger.ContraindicationStub(
                exerciseTag = "heavy_axial_loading",
                conditionFlag = "scoliosis_flagged",
                clinicalQuestion =
                    "RESOLVED (Safety Reviewer): blocks heavy_axial_loading for scoliosis_flagged " +
                        "(Cobb >= ~30 deg / rigid-structural / braced). Movement set = barbell " +
                        "back+front squat, standing barbell press, heavy deadlift+good-morning, " +
                        "heavy loaded carries, heavy standing barbell work. Sourced: " +
                        "WEINSTEIN-AIS-2008 (moderate_context; precautionary — no RCT proves load " +
                        "worsens curves).",
            ),
        decision = SafetyRule.Decision.BLOCK,
        status = RuleStatus.ACTIVE,
        evidenceRefs = listOf("WEINSTEIN-AIS-2008"),
    )

    /**
     * Loaded flexion/rotation for a flagged scoliosis condition.
     *
     * Clinical spec authored by the Safety Reviewer; DRAFT until sourced and
     * flipped ACTIVE (see [EvidenceLinked]). Targets the specific concern of
     * combined loaded spinal flexion+rotation (torsional shear) on a
     * structural/asymmetric spine.
     *
     * CONDITION FLAG — reuses `scoliosis_flagged` (same threshold as the axial
     * rule: Cobb >= ~30 deg / rigid-structural / braced) for consistency.
     *
     * MOVEMENT SET — `loaded_flexion_rotation` = combined spinal flexion+rotation
     * under external load: loaded seated/decline trunk twists and Russian twists
     * with weight, heavy cable/landmine woodchopper and rotation chops, heavy
     * loaded side-bend, barbell good-morning with trunk rotation, heavy standing
     * rotational presses. Excludes unloaded/controlled mobility (cat-camel,
     * bird-dog, side-plank) and isolated anti-rotation bracing (Pallof press) —
     * those stay on the baseline.
     *
     * Claim boundary: support-not-treat. Blocks a torsional load; prescribes
     * nothing curve-specific.
     */
    val loadedFlexionRotationForFlaggedScoliosis = SafetyRule(
        id = "stub_loaded_flexion_rotation_scoliosis",
        description =
            "DRAFT (pending sourcing) — loaded flexion/rotation proposed for a flagged " +
                "scoliosis condition. Blocks combined spinal flexion+rotation under load (e.g. " +
                "loaded Russian/trunk twists, heavy cable/landmine chops, heavy loaded side-bend, " +
                "barbell good-morning with rotation) for moderate-or-greater (Cobb >= ~30 deg) / " +
                "rigid-structural / braced scoliosis. Support-not-treat: blocks a torsional load; " +
                "prescribes nothing curve-specific.",
        trigger =
            RuleTrigger.ContraindicationStub(
                exerciseTag = "loaded_flexion_rotation",
                conditionFlag = "scoliosis_flagged",
                clinicalQuestion =
                    "RESOLVED (Safety Reviewer): blocks loaded_flexion_rotation for " +
                        "scoliosis_flagged (Cobb >= ~30 deg / rigid-structural / braced). Movement " +
                        "set = loaded trunk/Russian twists, heavy cable/landmine chops, heavy loaded " +
                        "side-bend, good-morning with rotation, heavy standing rotational presses. " +
                        "PENDING: Evidence Analyst sourcing before status = ACTIVE.",
            ),
        decision = SafetyRule.Decision.BLOCK,
        status = RuleStatus.DRAFT,
        evidenceRefs = emptyList(),
    )

    /** All contraindication slots — the Safety Reviewer's worklist. ACTIVE ones
     *  are enforced by the gateway; DRAFT ones are inert until sourced + flipped. */
    val all: List<SafetyRule> =
        listOf(
            heavyAxialLoadingForFlaggedScoliosis,
            loadedFlexionRotationForFlaggedScoliosis,
        )
}
