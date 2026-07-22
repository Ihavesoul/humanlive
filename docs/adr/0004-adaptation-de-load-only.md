# ADR 0004 — Plan adaptation is de-load only (volume down or hold, never up)

- **Status:** Accepted
- **Date:** 2026-07-22
- **Decides for:** [DRE-49](/DRE/issues/DRE-49) (M3-A). Formalizes the hard
  invariant named by the milestone [DRE-48](/DRE/issues/DRE-48): "adaptation only
  lowers risk (de-load/hold), never bypasses the safety gate".
- **Carries:** the instruction in [DRE-49](/DRE/issues/DRE-49) to raise an ADR if
  the de-load-only invariant needs one — it does, because it is a safety-shaping
  rule a future engineer could silently weaken by adding an "intensify" path.

## Context

Milestone 3 closes the adaptation loop: the plan reacts to the user's own logged
progress + symptoms ([DRE-48](/DRE/issues/DRE-48)). The first slice (M3-A,
[DRE-49](/DRE/issues/DRE-49)) introduces an `AdaptationSignal` derived from logs
and threads it into `DeterministicPlanGenerator`. The open safety question is
*which direction* adaptation may move load. Allowing it to *increase* load in
response to data would let a noisy signal (consumer-scale water shift, a
mis-tapped symptom) push a user toward more work — the opposite of the product's
"conservative, reversible, explainable" adaptation posture, and a recovery-risk
footgun for a recomposition / scoliosis-aware audience.

The pre-existing safety layer ([SafetyGate](../../core/domain/src/main/kotlin/dreamteam/domain/safety/SafetyGate.kt),
[SafetyGuardedGateway](../../core/domain/src/main/kotlin/dreamteam/domain/safety/SafetyGuardedGateway.kt))
already vets *what* is recommended (exercise selection, evidence, movement
tags). Adaptation is about *how much* — the working-set dose. It must not become
a second, unaudited channel that can raise load.

## Decision

**Adaptation may only reduce training volume or hold it — never increase it.**

This is enforced structurally in `core/domain/.../adaptation/AdaptationSignal.kt`
and in the generator, not by convention:

1. **Type-system gate.** `AdaptationSignal` is a sealed hierarchy with exactly
   two members: `None` (serve the baseline) and `DeLoad` (reduce volume). There
   is no `Intensify` / `Load` variant. Adding one is a compile-breaking change —
   the explicit review point this invariant wants.
2. **Scale bound.** A `DeLoad` carries a `volumeScale` in `[SCALE_FLOOR, 1.0)`
   — always `< 1.0`; a no-op reduction is `None`.
3. **Per-week cap.** `BaselineProgram.adaptedWeekDose` clamps the adapted
   working-set count to `coerceIn(DELOAD_SETS_FLOOR = 2, baselineSetsMain)`. So
   even a malformed scale cannot push a week above its baseline sets or below the
   program's own intentional deload-week floor.
4. **Gate-independence.** Adaptation touches volume (`setsMain`) only — never
   exercise selection, evidence refs, or movement tags. The surfaced
   `Recommendation` candidates are byte-identical with or without adaptation, so
   `SafetyGuardedGateway.surface` vets them identically. Adaptation works
   *strictly inside* the already-approved gate bounds; it cannot unblock a
   contraindication or an allowlist violation (pinned by
   `DeterministicPlanGeneratorTest`).

### Lever and granularity (M3-A)

The de-load lever is the per-week **working-set count** (`setsMain`), matching
`specs/Decision_Rules.md` training-readiness YELLOW ("reduce sets 25–50%, use
regressions"). `volumeFactor` is left as the baseline phase marker (it is not
applied to assignment dose today); the de-load acts on the real dose. Because
sets are integers (2 or 3), a moderate and a strong scale both collapse to the
floor (2) on a 3-set week — safe (less reduction), coarse. A finer lever (rir,
per-assignment fractional volume) is an **M3-B** decision, not a safety one, and
stays de-load-only whenever it lands.

### Triggers (decision-rule-sourced, not invented)

- **Symptom escalation** — a symptom string in the latest entry absent from
  prior entries (needs ≥2 entries; one point cannot establish a change). YELLOW.
- **Rapid weight loss** — endpoint weekly rate `r < -0.0075` over ≥1 week; the
  threshold is quoted verbatim from `specs/Decision_Rules.md` (the
  "recovery/performance deteriorates" branch), not authored here. Weight *gain*
  never triggers a de-load in M3-A.

RED-flag symptoms are a separate, harder gate handled upstream by `SafetyGate`
(they block generation entirely); they never reach adaptation as "just" input.

## Non-negotiables enforced here

1. **De-load only.** No code path may increase prescribed load in response to a
   logged signal. The sealed type + per-week cap make this a compile/structural
   property, not a comment to honor.
2. **Gate still binding.** Adaptation is pre-gate volume only; it never reaches
   `Recommendation`, so a contraindicated / unallowlisted movement is blocked
   exactly as without adaptation.
3. **No medical claim.** The signal's `reason` frames the change as a volume
   reduction (support), never a diagnosis or treatment. Thresholds cite the
   decision-rules spec, not a study, and carry no evidence id (the signal is an
   internal modifier, not surfaced guidance).

## Consequences

- An "intensify / progress load" adaptation (the legitimate future need —
  progressive overload) is **not** expressible today by design. When M3-D / a
  later slice adds progression, it must extend this type *and* pass safety
  review — this ADR is the flag that forces that conversation rather than
  letting progression slip in as a symmetric counterpart to de-load.
- Granularity ceiling: integer sets mean de-load steps are coarse (3→2, else
  hold). Acceptable for M3-A; revisited in M3-B with a finer volume lever.
- The signal is not persisted or surfaced in M3-A (M3-B persistence, M3-C UI);
  this ADR governs the *direction* of adaptation, which holds across all three.
