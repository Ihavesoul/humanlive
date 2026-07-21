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
