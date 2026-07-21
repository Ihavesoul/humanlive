# Evidence → engine mapping (DRE-14)

Source-to-engine map for the PoC baseline plan and the first contraindication
rule. Every `EvidenceLinked` entity (`Exercise`, `ExerciseAssignment`,
`NutritionTarget`, `SafetyRule`) must resolve to ≥1 id in
`data/evidence_catalog.json` before it reaches the user (empty `evidenceRefs` =
**blocked-until-sourced**, never safe to ship — see
`core/domain/.../EvidenceLinked.kt`).

Controlled `evidenceLevel` vocabulary (a population/applicability tag, **not** a
private quality score): `high`, `moderate_high`, `moderate`,
`moderate_context`, `low_moderate`, `low`, `guideline`, `validated_equation`.

**Owners:** Evidence & Research Analyst curates the catalog + this map. The
Founding Engineer wires `evidenceRefs` into Kotlin entities; the Safety Reviewer
arbitrates contraindication activation. The model never emits DOI/PMID/URL —
citations resolve only from the allowlist (ADR 0001 non-negotiable #2).

---

## 1. PoC baseline plan — training side ✅ already wired

The baseline plan (`data/program_12_weeks.json`) references exercises by id;
each exercise in `data/exercises.json` carries `evidence_ids` that resolve to
the catalog. Verified: every referenced id exists in the catalog (0 dangling),
no exercise has an empty list. The Kotlin mirror is `Exercise.evidenceRefs`
(`core/domain/.../exercise/Exercise.kt`).

| Plan component | Backing catalog ids |
|---|---|
| Full-body strength (split squat, goblet squat, push-up, row, RDL, floor press, etc.) | `ACSM-RT-2026`, `LOPEZ-LOAD-2021`, `RIR-DOSE-2024`, `KIKUCHI-PUSHUP-2017`, `FULLBODY-SPLIT-2024` |
| Scoliosis / motor-control base (axial elongation, bird-dog, side-plank, etc.) | `MONTICONE-ADULT-SCOLIOSIS-2016`, `SOSORT-GUIDELINES-2016` |
| Warm-up / breathing | `SLOW-BREATHING-2022`, `DIAPHRAGMATIC-BREATHING-2019` |
| Mobility / gentle yoga | `YOGA-LBP-2022` |
| Brisk walk / aerobic + desk breaks | `WHO-ACTIVITY-2020`, `SIT-STAND-BREAKS-2021` |

> **Spec note for the Founding Engineer:** `data/exercises.json` is a PoC
> artifact and does **not** 1:1 mirror `Exercise.kt` — it uses snake_case keys
> (`name_ru`, `default_sets`, `evidence_ids`, …) and has RU-only fields with no
> Kotlin counterpart. The exercise seed loader must map these at load time
> (snake_case → camelCase `evidenceRefs`, pick the active-language name, etc.).
> Out of scope for the Evidence Analyst; flagged here so it is not missed.

---

## 2. PoC baseline plan — nutrition side ⚠️ citation supplied, wiring pending

`NutritionTarget` (`core/domain/.../nutrition/Nutrition.kt`) is
`EvidenceLinked` but its source `data/derived_metrics.json` carries **no**
evidence field today. The Founding Engineer must populate `evidenceRefs` when
building the nutrition seed/loader. The resolvable ids to wire:

```kotlin
NutritionTarget(
    …
    evidenceRefs = listOf(
        EvidenceId("MIFFLIN-1990"),      // primary BMR (sex/age/ht/wt)
        EvidenceId("CUNNINGHAM-1991"),   // secondary BMR (FFM-based) — caveat: FFM from imprecise consumer BIA
        EvidenceId("ISSN-DIETS-2017"),   // sustained deficit drives fat loss; slow loss preserves lean mass
        EvidenceId("MORTON-PROTEIN-2018")// protein plateau ~1.6 g/kg, uncertainty higher; 170 g ≈ 2.0 g/kg
    ),
)
```

| derived_metrics.json field | Backing id(s) | Why |
|---|---|---|
| `bmr_mifflin_st_jeor_kcal`, `bmr_blended_kcal` | `MIFFLIN-1990` (validated_equation) | practical RMR from wt/ht/age/sex |
| `bmr_cunningham_kcal` | `CUNNINGHAM-1991` (validated_equation) | 370 + 21.6 × FFM; secondary only — FFM from consumer BIA |
| `initial_target_kcal` (2300, deficit) | `ISSN-DIETS-2017` (moderate_high) | energy deficit drives fat loss; slower = lean-mass sparing |
| `protein_g` (170) | `MORTON-PROTEIN-2018` (high), `ISSN-DIETS-2017` | RT protein plateau ~1.6 g/kg, range extends higher |
| `body_fat_percent_bia` + "not BIA alone" note | `SMART-SCALE-2021` (moderate) | weight accurate, BIA composition is **not** — do not drive decisions off BIA% |

`CalibrationRule` (the ±100 kcal, ≥14-day, 7-day-mean-**weight** heuristic)
should be annotated as conservative-default-backed by `ISSN-DIETS-2017` (slow
loss preserves lean mass) and `SMART-SCALE-2021` (act on multi-day weight
trends, never a single weigh-in or a BIA%). `CalibrationRule` is not itself
`EvidenceLinked`; document the rationale in the loader/seed, not as a ref.

---

## 3. Contraindication — `stub_heavy_axial_loading_scoliosis` ⚠️ citation supplied, activation is Safety Reviewer's (DRE-10)

The catalog entry **`WEINSTEIN-AIS-2008`** is the citation that backs this rule.
Exact wiring for the Founding Engineer (only after the Safety Reviewer clears
activation in [DRE-10](/DRE/issues/DRE-10)):

```kotlin
// core/domain/.../safety/ContraindicationStubs.kt
evidenceRefs = listOf(EvidenceId("WEINSTEIN-AIS-2008")),
status = RuleStatus.ACTIVE,   // Safety Reviewer's call, not the Evidence Analyst's
```

- **Citation:** Weinstein SL et al. Adolescent idiopathic scoliosis. *Lancet*
  2008;371(9623):1527-37. DOI 10.1016/S0140-6736(08)60658-3 · PMID 18456103.
- **Design / level:** authoritative narrative review (Lancet seminar) of AIS
  natural history & management — `moderate_context`.
- **Why it backs a *conservative default*:** scoliosis management is magnitude-
  and growth-driven and specialist-led; curves ≳50° at skeletal maturity tend to
  progress ~1°/yr in adulthood. When scoliosis is flagged but current Cobb
  magnitude/classification are unknown, heavy compressive axial loading is
  restricted until a clinician with current curve data clears it.
- **Honest limitation (must travel with the citation):** this seminar does **not**
  study heavy resistance training or axial loading in adults and does **not**
  show that lifting causes curve progression. **Direct contraindication
  evidence is absent**; the rule is precautionary, extrapolated from
  AIS/adult-progression data. Tagged `moderate_context`, not higher, on purpose.

> The Safety Reviewer arbitrates whether this indirect/precautionary basis is
> sufficient to flip `ACTIVE`, or whether stronger direct evidence must be
> sourced first (open gap — see §4.1). That decision lives in
> [DRE-10](/DRE/issues/DRE-10); the Evidence Analyst supplies, does not decide.

---

## 4. Weak-evidence gaps → conservative defaults

The adult-scoliosis and contraindication literature is thin; the product
defaults conservative wherever evidence is weak or indirect. Each row's
rationale is already in the catalog `limitations`/`application`; this is the
consolidated register.

| # | Gap | Best source (level) | Conservative product default |
|---|---|---|---|
| 4.1 | **No direct evidence** that heavy axial loading harms adult scoliosis spines | `WEINSTEIN-AIS-2008` (moderate_context, AIS extrapolation) | RESTRICT heavy axial loading when scoliosis is flagged but Cobb/classification unknown, until specialist clearance. Precautionary. |
| 4.2 | Adult scoliosis **exercise** evidence is sparse | `ALANAZI-ADULT-SCOLIOSIS-2018` (low, 1 controlled study); `MONTICONE-ADULT-SCOLIOSIS-2016` (moderate, single RCT n=130, curve <35°) | Generic motor-control = capacity/tolerance training, **not** curve correction. Curve-specific PSSE locked until clinician-entered data + instruction. |
| 4.3 | Energy equations have **individual** error | `MIFFLIN-1990`, `CUNNINGHAM-1991` (validated_equation, population-level) | Calibrate from observed 7-day mean weight trend after ≥14 days; equation output is a starting estimate only. |
| 4.4 | Consumer BIA body-composition is **inaccurate** | `SMART-SCALE-2021` (moderate) | Drive adjustments from weight trend + adherence; treat BIA% / muscle / visceral-fat fields as noisy secondary signals. |
| 4.5 | RIR dose-response is noisy / causally uncertain | `RIR-DOSE-2024` (moderate_high) | Most sets 1–3 RIR; never grind failure on balance-demanding or spine-loaded patterns. |
| 4.6 | Low-load hypertrophy relies on effort + technique control | `LOPEZ-LOAD-2021` (high) | With 10 kg DBs: unilateral leverage, pauses, controlled tempo; technique gates progression. |
| 4.7 | Yoga may cause minor transient adverse events; not scoliosis-specific | `YOGA-LBP-2022` (moderate) | Gentle symptom-guided mobility/relaxation only; not a curve-correction method. |

---

## 5. Provenance trail

- **`data/evidence_catalog.json`** — the authoritative allowlist. Field shape
  mirrors `EvidenceSource.kt` **verbatim** (camelCase), so
  `decodeJson<EvidenceSource>` works under the repo's `Json { ignoreUnknownKeys = true }`
  config (no naming strategy). 22 entries, 0 dangling refs, all levels valid,
  every entry resolvable via DOI/PMID/URL.
- **`research/references.csv`** + **`research/references.bib`** — the
  human-readable audit index (CSV columns / bibtex keys are snake_case by
  convention; they are **not** deserialization targets). Both kept in sync with
  the catalog: same 22 ids, same DOIs/PMIDs/URLs.
- **`obsidian/13_Evidence_Map.md`** — PoC-era RU summary card (what each id does
  / does not prove). Retained for history; this file is the engine-facing map.
