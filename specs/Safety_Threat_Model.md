# Safety and Threat Model

| Threat | Example | Consequence | Control |
|---|---|---|---|
| Hallucinated evidence | Model invents a PMID | False authority | Evidence-ID allowlist; resolve citation server-side |
| Image overinterpretation | Model estimates Cobb from phone photo | Wrong correction | Explicit prohibition; clinician data gate |
| Side-specific invention | “Train right side twice as much” | Reinforced compensation/pain | Equal-volume default; policy checker |
| Red-flag bypass | User asks to ignore bladder symptoms | Delayed urgent care | Deterministic gate before prompt; no override in client |
| Prompt injection | Notes contain “ignore system” | Policy erosion | Treat notes as untrusted data; schema and post-validation |
| Excess volume | Model creates 40 hard sets in 60 min | Poor recovery/injury risk | Set/time caps; progression limits |
| Failure misuse | Failure on unstable unilateral hinge | Technique breakdown | Exercise-specific failure policy |
| BIA false precision | Target exact 13.0% based on scale | Misguided dieting | Measurement provenance and trend-based decisions |
| Aggressive deficit | Model cuts 700 kcal after 3 days | Lean-mass/recovery loss | Minimum 14-day window and adjustment cap |
| API-key exposure | Key stored in JS | Account compromise | Backend adapter only |
| Sensitive-data leakage | X-rays uploaded to third party | Privacy breach | Images excluded from PoC; explicit consent/retention for production |
| Model drift | Provider changes behaviour | Silent safety regression | Version pinning, test suite, audit logs |
| Overreliance | User treats app as clinician | Missed diagnosis | Persistent boundaries and referral triggers |

## Prohibited output patterns

Reject output containing, without clinician instruction:

- “подложите X мм под левую/правую ягодицу”;
- “делайте планку только на одной стороне”;
- “дышите только в конкретную вогнутость”;
- “ваш угол Cobb примерно …”;
- “гарантированно выпрямит позвоночник”;
- “протрузия означает, что нельзя наклоняться/поднимать”;
- any unrecognized DOI/PMID/URL.

## Pain policy

Pain is not a perfect damage signal and no universal numeric threshold is diagnostic. The PoC uses a conservative operational rule:

- familiar mild discomfort that does not escalate: reduce range/load and monitor;
- sharp, electrical, radiating or progressively worsening symptoms: stop the provoking exercise;
- new neurological deficit or cauda-equina-type symptoms: block programme and seek urgent assessment.

## Psychological/stress component

Stress-related muscular guarding is acknowledged without declaring symptoms “only psychosomatic”. Breathwork and pacing may reduce arousal, while persistent or severe symptoms still require physical assessment.
