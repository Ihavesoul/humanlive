# humanlive — DreamTeam

Evidence-linked, safety-gated training & nutrition planner for body
recomposition with scoliosis awareness. **PWA-first**, offline-capable,
Russian-only user-facing content; evidence/data identifiers stay English.

> This app **supports** training, nutrition, and recovery. It does **not**
> diagnose, treat, or cure any condition. Safety guidance is enforced in code
> and can never be dismissed by the user. See [`specs/Safety_Threat_Model.md`](specs/Safety_Threat_Model.md).

The repo is built from a verified PoC v0.2.0 baseline. The PoC's vanilla-JS
PWA **is** the client; its data model, safety gate, evidence allowlist, and
side-specific lock are the **inviolable reference** the product is built from.
Stack rationale: [`docs/adr/0001-stack-pwa-first.md`](docs/adr/0001-stack-pwa-first.md).

> **Scope note (DRE-8):** the earlier "Android-first / Kotlin-Compose" framing
> is superseded. The client is the PWA in `app/`; there is no native Android
> client to build. A native Compose shell, the `:core:data` Room/SQLite layer,
> and the `:feature:plan` UI library were dropped from the build.

## Stack (one JVM language + a static client)

| Layer        | Where          | Type                                   |
| ------------ | -------------- | -------------------------------------- |
| Client       | `app/`         | Vanilla-JS PWA — **no build step**     |
| Backend      | `server/`      | Kotlin/JVM · Ktor (`:server`)          |
| Domain model | `core/domain/` | Pure Kotlin/JVM (`:core:domain`) — the safety math, no Android dependency |

Gradle (Kotlin DSL) + version catalog at `gradle/libs.versions.toml`.
**JDK 17**. **No Android SDK required.**

## Prerequisites

- **JDK 17** (Temurin recommended).
- Python 3 (only for the PoC build validator and the local PWA dev server).

macOS quick start (Homebrew):

```bash
brew install openjdk@17
```

## Build · run · test

```bash
# Backend + domain tests (JVM only — the CI commit gate)
./gradlew :core:domain:test :server:test

# Run the backend locally (http://127.0.0.1:8080/health)
./gradlew :server:run

# PoC build validator (repo integrity: required files + JSON + XLSX)
python3 scripts/validate_build.py

# Serve the PWA locally (prints desktop + LAN URLs)
python3 scripts/serve_pwa.py
```

### Smoke tests

- `core/domain` verifies the deterministic energy math against the PoC's single
  source of truth: Mifflin-St Jeor BMR for the seed profile equals **1872 kcal**
  (`data/derived_metrics.json`). If it breaks, a safety-relevant equation
  regressed.
- The server smoke test asserts `GET /health` returns `{"status":"ok"}`.
- `scripts/validate_build.py` confirms the PoC manifest is intact.

## Repo layout

```
app/            # PWA client — vanilla JS, no build (the PoC client)
server/         # Ktor backend (:server) — safety engine, repository layer, orchestrator
core/domain/    # pure Kotlin/JVM domain (:core:domain) — shared by server
gradle/         # version catalog + wrapper
docs/adr/       # architecture decision records
specs/          # product specs (Architecture, SDD, Safety_Threat_Model, …)
data/           # single source of truth (profile, exercises, evidence, program)
prompts/        # LLM prompts used by the server orchestrator
obsidian/       # PoC authoring vault (progression rules, evidence map, …)
scripts/        # PoC build validator + local PWA dev server
```

## Non-negotiables (the PoC's invariants)

1. **Safety gate = code, not model** — red-flag gate + side-specific lock run
   server-side, pre-LLM, unoverridable from any client.
2. **Evidence by server-side allowlist** — the model never emits DOI/PMID/URL.
3. **Side-specific lock** — no Cobb/wedge/directional-breathing/unequal-load
   guidance from old x-ray photos.
4. **Deterministic fallback** — `/plans/generate` serves the fallback plan
   first, before any LLM path; no LLM failure strands the user.
5. **No LLM in the client** — the client only sends structured requests and
   renders server-validated output.

## Conventions

**Commits:** [Conventional Commits](https://www.conventionalcommits.org) —
`feat:`, `fix:`, `build:`, `docs:`, `test:`, `chore:`, `refactor:`. Keep the
subject ≤72 chars. One logical change per commit.

**Safety:** any change touching plan generation or safety rules must name the
evidence source and the safety rule it implements in the commit/PR. A
recommendation with no evidence link, a safety rule the user can skip, or a
medical claim is a **bug**, not a feature.

## CI

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push/PR:
the PoC build validator (Python) **and** the JVM tests (`:core:domain:test`
`:server:test`) as independent commit gates.

---

_Co-maintained by the DreamTeam agent org. Founder: Ihavesoul._
