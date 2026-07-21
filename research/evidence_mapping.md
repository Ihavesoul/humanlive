# Evidence Mapping

Maps catalog evidence ids (`data/evidence_catalog.json`) to the safety rules and
program items that cite them. Maintained alongside the Evidence & Research
Analyst's catalog; this is an audit aid, not a clinical appraisal.

## `stub_heavy_axial_loading_scoliosis` (DRE-10 / sourced DRE-14, re-landed DRE-20)

- **Evidence id:** `WEINSTEIN-AIS-2008` — Weinstein SL et al. *Adolescent
  idiopathic scoliosis.* Lancet. 2008;371(9623):1527-1537. DOI
  10.1016/S0140-6736(08)60658-3 · PMID 18456103.
- **Evidence level:** `moderate_context` (controlled vocab, NOT a quality
  rating). It is a context source, not direct proof.
- **Honest caveat (do not inflate):** No RCT establishes that heavy axial
  loading worsens scoliosis. The block is **consensus / conservative
  guidance**: diagnosed moderate-or-greater (Cobb ≥ ~30°), rigid/structural, or
  braced curves are kept off heavy compressive spinal loading as a precaution.
  The source contextualises AIS management (observation, bracing, surgery); it
  does not trial load vs. progression. Adult scoliosis is extrapolated
  cautiously from an adolescent-focused review.
- **Why this id over alternates:** `SOSORT-GUIDELINES-2016`,
  `MONTICONE-ADULT-SCOLIOSIS-2016`, `PSSE-METHODS-2016` are rehabilitation /
  PSSE approach sources — appropriate for exercise-approach guidance, not for a
  *contraindication* attribution. A contraindication context source was the
  Evidence Analyst's call (DRE-14); this mapping records it for audit.

## `stub_loaded_flexion_rotation_scoliosis` (DRE-24 / follow-up to DRE-10)

- **Evidence ids:** `MARSHALL-MCGILL-AXIAL-TORQUE-2010` (primary, mechanism) +
  `MARRAS-TRUNK-MOTION-1993` (corroborating, human epidemiology).
- **Primary:** `MARSHALL-MCGILL-AXIAL-TORQUE-2010` — Marshall LW, McGill SM.
  *The role of axial torque in disc herniation.* Clin Biomech (Bristol, Avon).
  2010;25(1):6-9. DOI 10.1016/j.clinbiomech.2009.09.003 · PMID 19815318.
  Ex-vivo porcine repeated-loading study: **combined flexion-extension + axial
  torque/twist** produced radial annulus delamination in 67.5% of specimens,
  while axial torque **alone could not** initiate a herniation. The damaging
  pattern is the *combination* — exactly the rule's stated mechanism ("combined
  loaded flexion + rotation multiplies the rotational/axial torque").
- **Corroborating:** `MARRAS-TRUNK-MOTION-1993` — Marras WS et al. Spine.
  1993;18(5):617-628. DOI 10.1097/00007632-199304000-00015 · PMID 8484154.
  In-vivo workplace epidemiology (400+ lifting jobs, 48 industries): combined
  trunk flexion (sagittal angle) + twisting/lateral velocity + load moment
  distinguished high- from low-risk jobs for low back disorder (OR ~10.7). The
  living-human leg the porcine mechanics study lacks.
- **Evidence level:** `moderate_context` (controlled vocab) for both — these are
  context/mechanism sources, NOT scoliosis-specific proof.
- **Honest caveat (do not inflate):** No RCT proves loaded flexion+rotation
  worsens scoliosis (a PubMed search for scoliosis × exercise × rotation ×
  contraindication returns nothing direct — a genuine consensus/conservative
  gap). Marshall/McGill is ex-vivo *porcine cervical* spine (not human
  scoliotic lumbar, disc-injury outcome); Marras is occupational LBD risk in
  typical-spine workers (association, not causation — the authors' own caveat).
  Both are extrapolated to the rotational component of a flagged curve as a
  **conservative/precautionary** block, mirroring the `WEINSTEIN-AIS-2008`
  precedent for the axial rule. Support-not-treat: it withholds an unsafe load;
  it does not prescribe curve-specific correction.
- **Why these ids over alternates:**
  - `WEINSTEIN-AIS-2008` is scoped to compressive axial loading (its catalog
    `application` does not cover rotation → reuse here would be evidence
    inflation, per the rule's DRE-10 note).
  - `SOSORT-GUIDELINES-2016` / `PSSE-METHODS-2016` describe 3D self-correction
    (what TO do), not a loaded-rotation contraindication; `MONTICONE-ADULT-
    SCOLIOSIS-2016` is a rehabilitation RCT. None covers the torsional-load
    *precaution*.
  - Adams & Hutton 1981 (torsion, PMID 7268544) was considered and **rejected
    as a lone citation**: its headline concludes pure torsion is *not* the main
    disc-prolapse driver (the facet joints protect the disc), which would
    undercut a single-source contraindication. Marshall/McGill isolates the
    damaging *combination* and Marras confirms it in living humans — a more
    defensible thin-evidence base than any single source.
- **Handoff:** the Evidence-Analyst-owned sourcing step is complete (catalog +
  bib + csv + this mapping, committed). The `status = RuleStatus.ACTIVE` flip
  and `evidenceRefs = listOf("MARSHALL-MCGILL-AXIAL-TORQUE-2010",
  "MARRAS-TRUNK-MOTION-1993")` in `ContraindicationStubs.kt`, plus the
  end-to-end `SafetyGuardedGatewayTest`, are the Safety Reviewer's downstream
  step on this issue.

## Catalog integrity (DRE-24 / re-landed DRE-20)

- `data/evidence_catalog.json` carries 24 entries: the DRE-20 baseline plus
  `MARSHALL-MCGILL-AXIAL-TORQUE-2010` and `MARRAS-TRUNK-MOTION-1993` (added
  DRE-24 for the loaded-flexion-rotation rule).
- `research/references.bib` and `research/references.csv` mirror the same 24
  ids.
- The `:core:domain` allowlist (`BaselineProgram.evidenceIds`) is the runtime
  set; this catalog is the validated reference ids resolve against.
