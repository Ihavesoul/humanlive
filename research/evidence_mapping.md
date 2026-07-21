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
- **Evidence level (split; DRE-31 settlement):** `MARSHALL-MCGILL-AXIAL-TORQUE-2010`
  = `low_moderate`; `MARRAS-TRUNK-MOTION-1993` = `moderate_context` (controlled
  vocab). Rated **separately** because the two sources differ in strength.
  - **MARSHALL `low_moderate`** (downgraded from the prior committed
    `moderate_context`): a single **ex-vivo porcine** mechanism study. By the
    catalog's own precedent ladder it is weaker *direct* evidence than
    `DIAPHRAGMATIC-BREATHING-2019` (human **systematic review** = `low_moderate`)
    and `KIKUCHI-PUSHUP-2017` (single **human** study = `moderate`); it cannot
    sit at `moderate_context` (the band of `WEINSTEIN-AIS-2008`'s human
    narrative review, *above* a human SR) — that would be inflation that
    undercuts the support-not-treat framing. Not `low`: the combined
    flexion+axial-torsion → annular-failure mechanism is among the most
    reproduced results in spine biomechanics, so `low_moderate` =
    "methodologically sound but indirect/animal."
  - **MARRAS `moderate_context`** (kept): **in-vivo human**, large-n
    epidemiology (400+ jobs, 48 industries, OR ~10.7) — genuinely
    moderate-quality *human* evidence, stronger than MARSHALL. Observational +
    occupational-LBD in presumably-typical spines, not scoliosis, so
    applicability is as **supporting context** (population mismatch).
  - **Net:** the two-source bundle reads as "solid human epi context (MARRAS) +
    corroborating animal mechanism (MARSHALL)" — a defensible *precautionary*
    basis, not a treatment claim. `evidenceLevel` reflects *each* source's own
    basis; it was not set to match a sibling rule for "coherence."
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

## `loaded_flexion_rotation` tag set (DRE-39)

Appraisal (Evidence & Research Analyst, library-content owner): the rule's
movement set is *combined spinal flexion + axial rotation under substantial
external load*. **No movement in the scoliosis-safe baseline library met this
definition** — the 24 baseline movements are light / unilateral / anti-rotation
(bird dog, dead bug, Pallof-like suitcase hold, split/goblet squat, push-up),
which the rule explicitly EXCLUDES; the 6 `heavy_axial_loading` movements are
pure compressive/hip-hinge (squats, presses, deadlift, good-morning, farmer
carry) with no rotational component. That is why the rule was
registered-but-inert: the movement class was genuinely absent from the
library, not merely untagged.

Closing the gap mirrors the DRE-18 `heavy_axial_loading` pattern: six canonical
loaded-flexion-rotation movements are cataloged in `data/exercises.json` and
mirrored in `BaselineProgram.kt` with `movementTags = setOf(
"loaded_flexion_rotation")`, placed in the library allowlist but **not** in any
session template (so the surfaced baseline stays scoliosis-safe):

- `loaded_russian_twist`, `cable_woodchop`, `landmine_rotation` — loaded trunk
  rotation / rotational chop patterns.
- `bent_rotational_row` — flexion + rotation pulling pattern.
- `heavy_rotational_carry` — heavy unilateral rotational carry.
- `loaded_good_morning_rotation` — flexion + rotation hinge (the plain
  `barbell_good_morning` stays tagged `heavy_axial_loading` only; this is the
  distinct combined rotational variant).

Evidence note: the movement records cite `ACSM-RT-2026` + `LOPEZ-LOAD-2021`
(general resistance-training parameters, consistent with the `heavy_axial_loading`
records). The *contraindication mechanism* evidence
(`MARSHALL-MCGILL-AXIAL-TORQUE-2010` + `MARRAS-TRUNK-MOTION-1993`) lives on the
RULE, not the movement records — same convention as `heavy_axial_loading`
(WEINSTEIN on the rule, not the movement).

Net: the rule now has real referents and fires end-to-end on a real library
movement (`DeterministicPlanGeneratorTest`: `cable_woodchop` →
`stub_loaded_flexion_rotation_scoliosis` Block for `scoliosis_flagged`; allowed
for a generic context; safe baseline stays untagged). Safety Reviewer verifies
the tagged set blocks correctly for `scoliosis_flagged` without over/under-reach
(mirror of the `heavy_axial_loading` check from DRE-37).

## Catalog integrity (DRE-24 / re-landed DRE-20)

- `data/evidence_catalog.json` carries 24 entries: the DRE-20 baseline plus
  `MARSHALL-MCGILL-AXIAL-TORQUE-2010` and `MARRAS-TRUNK-MOTION-1993` (added
  DRE-24 for the loaded-flexion-rotation rule).
- `research/references.bib` and `research/references.csv` mirror the same 24
  ids.
- The `:core:domain` allowlist (`BaselineProgram.evidenceIds`) is the runtime
  set; this catalog is the validated reference ids resolve against.
