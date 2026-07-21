# Software Design Description

## 1. Контекст системы

PoC состоит из четырёх представлений одной модели данных:

1. Excel — аналитика и ручной трекер.
2. PWA — мобильный ежедневный интерфейс.
3. Obsidian — человекочитаемый протокол и знания.
4. LLM harness — контролируемая генерация/адаптация программы.

Источником истины для сущностей служат JSON-файлы в `data/`. В PoC данные дублируются в `app/app.js` для работы через `file://`. Production-версия должна генерировать bundle из единого каталога на этапе сборки.

## 2. Компоненты

### 2.1 Profile Engine

Входы:

- возраст, пол для уравнения, рост, вес;
- BIA-жир как необязательная оценка;
- activity multipliers;
- дефицит и macro factors.

Выходы:

- FFM estimate;
- BMR Mifflin;
- BMR Cunningham;
- blended BMR;
- TDEE range;
- target calories and macros;
- target-weight scenarios with explicit caveat.

### 2.2 Programme Engine

Детерминированно выбирает шаблоны `strength_A/B/C` и `scoliosis_A/B`. LLM не требуется для базового плана.

### 2.3 Log Store

- browser `localStorage`;
- keys versioned with prefix `lsr_poc_v1_`;
- JSON export is canonical backup;
- CSV is convenience export.

### 2.4 Safety Gate

Pure function:

```text
input: red_flags[], progressive_neurologic_symptoms, clinician_data
output: allow_training_generation, allow_side_specific_content, warnings[]
```

Rules are deterministic and execute before any LLM call.

### 2.5 Prompt Builder

Builds a request object conforming to `workout_request.schema.json` and a human-readable prompt. Free-text notes are wrapped as untrusted user context; they cannot alter system policy.

### 2.6 Response Validator

Production pipeline:

1. parse JSON;
2. validate schema;
3. reject exercise IDs outside allowlist;
4. reject evidence IDs outside catalog;
5. reject side-specific language if lock is true;
6. reject total set/time limits;
7. run red-flag consistency checks;
8. log model/provider/prompt/catalog versions.

## 3. Data model

### Profile

- stable identity-free physical inputs;
- user-reported medical context;
- provenance per field: `measured`, `inferred`, `bia_estimate`, `user_reported`, `clinician_report`.

### Exercise

- ID, Russian name, category, equipment;
- sets/rep scheme/RIR;
- instructions, progression, regression;
- scoliosis rule;
- evidence IDs.

### Session

- ordered exercise IDs;
- duration;
- programme notes;
- week-level set/RIR overlay.

### Evidence record

- evidence ID;
- bibliographic citation;
- design;
- finding, application, limitation;
- URL/DOI/PMID;
- evidence level.

### Check-in

- date;
- anthropometry;
- nutrition;
- recovery and symptoms;
- session outcome.

## 4. Calculation details

### Mifflin–St Jeor

Male:

```text
BMR = 10 × kg + 6.25 × cm − 5 × age + 5
```

Female:

```text
BMR = 10 × kg + 6.25 × cm − 5 × age − 161
```

### Cunningham

```text
BMR = 370 + 21.6 × estimated FFM kg
```

Cunningham is not used when body-fat input is missing. Because consumer BIA is imprecise, the UI labels it secondary.

### Target calories

```text
maintenance_low  = blended_BMR × activity_low
maintenance_high = blended_BMR × activity_high
target = round_to_50(midpoint × (1 − deficit_fraction))
```

Default profile uses 1.40–1.50 and 13% deficit.

### Macros

```text
protein = round_to_5(weight × protein_factor)
fat     = round_to_5(weight × fat_factor)
carbs   = (target − 4×protein − 9×fat) / 4
```

Defaults: 2.0 g/kg protein and 0.9 g/kg fat.

## 5. State transitions

### Programme readiness

```text
DRAFT -> READY_GENERIC -> ACTIVE -> PAUSED -> COMPLETED
          |                  |
          +-> NEEDS_CLINICIAN_INPUT
          +-> BLOCKED_RED_FLAG
```

### Side-specific content

```text
LOCKED by default
UNLOCKED only when:
- current_curve_data_available == true
- clinician_curve_specific_plan_available == true
- clinician_instructions non-empty
- red flag gate passed
```

Even then the LLM may only restate or schedule clinician-entered instructions; it must not invent them.

## 6. Error handling

- invalid numeric input: inline validation and no calculation;
- BIA missing: skip Cunningham and target-BF calculations;
- no logs: show baseline only;
- localStorage quota/error: offer JSON export and show warning;
- invalid import: reject without overwriting current state;
- LLM invalid JSON: no partial rendering; return validation report;
- unknown evidence/exercise ID: reject response.

## 7. Observability for production

Log without raw medical notes by default:

- request schema version;
- policy version;
- evidence catalog hash;
- model/provider/version;
- safety gate result;
- validation errors;
- latency/token usage;
- user acceptance/rejection.

## 8. Security and privacy

- no API keys in PWA;
- no image uploads in PoC;
- no analytics SDK;
- exported files may contain health data and must be handled accordingly;
- production backend should encrypt at rest/in transit, use explicit consent, retention limits and regional compliance review.
