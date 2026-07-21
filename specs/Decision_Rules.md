# Decision Rules

## Nutrition

### Initial target

- Use measured weight and height where available.
- If height is inferred, display that status.
- Calculate Mifflin.
- Calculate Cunningham only with a body-fat/FFM estimate and label it lower confidence.
- Use the midpoint of a plausible activity range; start with 10–15% deficit.

### Adjustment after ≥14 days

Let `r` be weekly change in 7-day mean weight divided by previous mean.

```text
if adherence < 0.80:
    no calorie change; improve measurement/adherence
elif r > -0.0015:
    target -= 100 kcal
elif -0.0075 <= r <= -0.0020:
    keep target
elif r < -0.0075 or recovery/performance deteriorates:
    target += 100 to 150 kcal
else:
    keep target and collect another week
```

Do not adjust from one weigh-in, one BIA reading or one high-sodium day.

## Training readiness

```text
GREEN:
- no red flags
- symptoms stable
- sleep/recovery acceptable
=> planned session

YELLOW:
- pain/tension clearly elevated but no neurological red flag
- poor sleep/high stress
=> reduce sets 25–50%, use regressions, keep easy walking/breathing

RED:
- new/progressive weakness, saddle anaesthesia, bladder/bowel change,
  severe systemic or trauma red flag
=> no generated training; medical evaluation route
```

## Exercise substitution

A replacement must match:

1. movement category;
2. available equipment;
3. equal or lower balance/spinal-control demand;
4. no prohibited side-specific strategy;
5. whitelisted exercise ID;
6. similar session duration.

Examples:

- pike push-up → dumbbell floor press or elevated push-up;
- Bulgarian split squat → supported split squat;
- single-leg RDL → B-stance RDL → two-leg hinge;
- full side plank → knee side plank;
- high-rep row limited by grip → supported pause row with slower eccentric.

## Progression

Progress only when:

- all prescribed sets stay within technique constraints;
- symptoms do not show a consistent adverse 24-hour trend;
- top of rep range is reached at target RIR twice;
- next progression does not require torso rotation or breath holding.

Priority:

1. repetitions;
2. range of motion within control;
3. pause/eccentric;
4. leverage;
5. dumbbell load;
6. one additional set, within weekly cap.

## Side-specific content

```text
allow_side_specific =
    current_curve_data_available
    AND clinician_curve_specific_plan_available
    AND clinician_instructions not empty
    AND red_flag_gate_passed
```

When false, all unilateral exercise dose is equal. Per-side repetitions may differ only to maintain the same RIR without loss of technique; the weaker side does not automatically receive extra sets.
