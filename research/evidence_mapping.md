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

## Catalog integrity (DRE-20)

- `data/evidence_catalog.json` carries 22 entries incl. `WEINSTEIN-AIS-2008`.
- `research/references.bib` and `research/references.csv` mirror the same id.
- The `:core:domain` allowlist (`BaselineProgram.evidenceIds`) is the runtime
  set; this catalog is the validated reference ids resolve against.
