# API Contracts — proposed production backend

Base path: `/v1`

## POST `/calculate`

Request:

```json
{
  "sex_for_equations": "male",
  "age_years": 28,
  "height_cm": 188,
  "weight_kg": 83.2,
  "body_fat_percent_bia": 21.2,
  "activity_multiplier_low": 1.40,
  "activity_multiplier_high": 1.50,
  "deficit_fraction": 0.13,
  "protein_g_per_kg": 2.0,
  "fat_g_per_kg": 0.9
}
```

Response:

```json
{
  "bmr_mifflin": 1872,
  "bmr_cunningham": 1786,
  "bmr_blended": 1829,
  "maintenance_range": [2560, 2744],
  "target_kcal": 2300,
  "macros": {"protein_g": 165, "fat_g": 75, "carbohydrate_g": 241},
  "warnings": ["Body-fat value is a consumer BIA estimate."]
}
```

Implementation may round protein to the configured preferred 170 g and recompute carbs.

## POST `/safety/evaluate`

Request: medical safety subset from `workout_request.schema.json`.

Response:

```json
{
  "red_flag_gate_passed": true,
  "allow_training_generation": true,
  "allow_side_specific_content": false,
  "warnings": ["Curve-specific correction remains locked."]
}
```

## POST `/plans/generate`

Request must validate against `workout_request.schema.json`.

Response must validate against `workout_response.schema.json`.

HTTP outcomes:

- `200` valid plan;
- `409` blocked by deterministic safety policy;
- `422` schema/validation error;
- `502` provider output invalid after retry; deterministic fallback returned in body;
- `503` provider unavailable; deterministic fallback returned.

## POST `/plans/validate`

Takes a candidate response and returns:

```json
{
  "valid": false,
  "errors": [
    {"code": "UNKNOWN_EVIDENCE_ID", "path": "citations[2].evidence_id"},
    {"code": "SIDE_SPECIFIC_CONTENT_LOCKED", "path": "programme.sessions[1]"}
  ],
  "score": 4
}
```

## POST `/coach/explain` and `/coach/report` (M8-C — AI coach)

The only LLM surface. The client sends a structured request (#5: no LLM in the
client); the server calls Z.AI (GLM, Max think) and returns a **validated**,
phone-readable JSON. A red flag ⇒ `409` pre-LLM (#1); a provider that is
absent/errors/times out ⇒ the deterministic fallback stands in the `200` body
(#4). The adapted plan is produced **exclusively** by the deterministic,
safety-gated generator — the LLM only annotates `summary_ru`/`corrections`.

`POST /v1/coach/explain` — one short contextual cue ("Спросить у AI"), not a chat.

```json
// request
{ "user_id": "", "exercise_id": "split_squat",
  "medical_safety": { "scoliosis_reported": true, "red_flags": [] } }
// 200 response (fallback shown when Z.AI is unavailable)
{ "status": "ok", "exercise_id": "split_squat",
  "summary_ru": "Сплит-присед … держите нейтральное положение …",
  "source": "fallback" }
// 409 response (red flag)
{ "status": "blocked_red_flag",
  "safety": { "red_flag_gate_passed": false, "allow_training_generation": false, … } }
```

`POST /v1/coach/report` — end-of-workout report ("Сообщить коучу"). Returns
`summary_ru` + per-exercise `corrections[]` + the gate-produced `adapted_plan`,
with `original_plan_id` preserved for the original-vs-adaptation UI.

```json
// request
{ "user_id": "", "medical_safety": { … }, "original_plan_id": "baseline-12w",
  "notes": [ { "exercise_id": "split_squat", "note": "боль в колене" } ] }
// 200 response (sealed [CoachReport.Ok]): { summary_ru, corrections[],
//   adapted_plan, original_plan_id, source: "fallback"|"llm" }
```

Provider config (deployment secrets, never committed): `DREAMTEAM_ZAI_API_KEY`,
`DREAMTEAM_ZAI_BASE_URL`, `DREAMTEAM_ZAI_MODEL` (target GLM 5.2 / Max think),
`DREAMTEAM_ZAI_TIMEOUT_MS`.

## POST `/weekly-adjustment`

Request:

```json
{
  "daily_checkins": [],
  "current_target_kcal": 2300,
  "policy_version": "1.0.0"
}
```

Response:

```json
{
  "status": "insufficient_data",
  "recommended_target_kcal": 2300,
  "reason_codes": ["LESS_THAN_14_DAYS"],
  "metrics": {}
}
```

## Data retention

PoC has no backend. Production must expose delete/export endpoints and define retention. Health notes should not enter general application logs.
