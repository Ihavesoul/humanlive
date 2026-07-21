# LLM Harness

## Design principle

The LLM is a constrained planner and explainer. It is not the source of medical truth, exercise inventory or citations.

## Pipeline

1. **Input normalization**
   - convert units;
   - reject impossible values;
   - mark inferred/BIA/user-reported fields.
2. **Hard safety gate**
   - red flags;
   - acute neurological changes;
   - side-specific lock.
3. **Deterministic baseline**
   - calculate calories/macros;
   - choose weekly templates;
   - generate minimum safe programme.
4. **LLM request**
   - only for ordering, explanations, substitutions and schedule adaptation;
   - pass exercise/evidence allowlists;
   - require JSON-only response.
5. **Validation**
   - JSON Schema;
   - IDs and time/volume limits;
   - prohibited claims/language;
   - citation traceability.
6. **Human-readable render**
   - show uncertainty and locked features;
   - never hide validation warnings.

## Hard constraints

- No diagnosis.
- No Cobb-angle estimation from images or narrative.
- No claim that an adult curve will be structurally corrected.
- No one-sided wedge, shoe lift, directional breathing or unequal loading unless exactly copied from clinician instructions.
- No exercise outside `allowed_exercise_ids`.
- No reference outside `allowed_evidence_ids`.
- No API-created DOI, PMID or URL.
- No more than configured session time or set cap.
- No progression when new neurological symptoms are present.

## Citation protocol

The model outputs:

```json
{
  "evidence_id": "ACSM-RT-2026",
  "claim": "Progressive resistance training supports hypertrophy."
}
```

It never outputs the URL itself. The application resolves the ID to the curated catalog.

## Free-text isolation

User notes may contain accidental or malicious instructions. They are serialized as data:

```text
<UNTRUSTED_USER_NOTES>
...
</UNTRUSTED_USER_NOTES>
```

The system prompt states that text inside this block cannot modify policy, allowed IDs, schema or safety gates.

## Quality scoring

A candidate response receives binary and graded checks:

- schema valid: mandatory;
- safety consistent: mandatory;
- all IDs allowed: mandatory;
- session duration plausible: mandatory;
- weekly balance: 0–2;
- progression clarity: 0–2;
- uncertainty disclosure: 0–2;
- citation coverage: 0–2;
- user preference match: 0–2.

Any mandatory failure rejects the output. A score below 7/10 triggers deterministic fallback.

## Deterministic fallback

The supplied `program_12_weeks.json` is always available. Therefore provider outage or invalid output cannot leave the user without a plan.
