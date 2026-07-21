# humanlive

**humanlive** — доказательное приложение для recomposition тела с учётом сколиоза:
тренировки, питание, восстановление и отслеживание симптомов. RU-only, PWA-first.

Evidence-linked, safety-gated training & nutrition planner for body recomposition
with scoliosis awareness. Russian-only UI/content. PWA-first delivery.

## Стек / Stack

- **Delivery:** PWA-first (installable, offline-capable). Android via installable PWA → TWA/Capacitor later if needed.
- **LLM:** GLM 5.2 (Zhipu), RU region, behind a deterministic post-validation orchestrator.
- **Language:** Russian only (UI, prompts, user-facing content). Evidence/data IDs stay English.
- **Safety:** deterministic pre-LLM safety gate, side-specific lock, evidence-by-allowlist resolved server-side. The model never enforces safety alone.

## Этот репозиторий / This repo

Seeded from the verified PoC baseline **v0.2.0** (`skinny_dry_scoliosis_poc`).
The PoC is the reference specification the whole product is built around — its data
model, safety gate, evidence allowlist, and side-specific lock are inviolable.

Current layout (from the PoC; to be refactored into `app/` + `server/` + shared):

- `app/` — mobile-first PWA (HTML/CSS/vanilla JS, no build step)
- `data/` — single source of truth (profile, metrics, exercises, 12-week program, evidence catalog, schemas)
- `prompts/` — the 5 production prompts (system / workout-generator / red-flag-gate / evidence-verifier / weekly-adjustment)
- `specs/` — architecture, API contracts, decision rules, LLM harness, SDD, safety threat model
- `tests/` — T01–T12 harness test cases (the safety contract)
- `obsidian/`, `excel/`, `research/` — human-readable protocol, analytical tracker, source research

## Безопасность / Safety invariants

See the project's PoC baseline doc and `specs/Safety_Threat_Model.md`. Non-negotiable:

1. Safety gate is code, not model — pre-LLM, no client override.
2. Side-specific content locked by default.
3. Evidence by allowlist, resolved server-side; model never emits URLs.
4. Deterministic fallback always present.
5. Trend-based nutrition, never single-point.

---

_Co-maintained by the DreamTeam agent org. Founder: Ihavesoul._
