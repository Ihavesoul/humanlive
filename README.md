# humanlive — DreamTeam

Evidence-linked, safety-gated training & nutrition planner for body
recomposition with scoliosis awareness. **Native Android (Kotlin + Jetpack
Compose)**, offline-first, Russian-only user-facing content; evidence/data
identifiers stay English.

> This app **supports** training, nutrition, and recovery. It does **not**
> diagnose, treat, or cure any condition. Safety guidance is enforced in code
> and can never be dismissed by the user. See [`specs/Safety_Threat_Model.md`](specs/Safety_Threat_Model.md).

The build is mined from a verified PoC v0.2.0 baseline. The PoC was a proof of
concept — its **logic** (`data/`, `specs/`, `prompts/`, safety engine, decision
rules, the 12 invariants) is the inviolable reference the product is built from.
Its web/PWA UI is **not** the client; the client is native. Stack rationale:
[`docs/adr/0002-stack-native-only.md`](docs/adr/0002-stack-native-only.md)
([ADR 0001](docs/adr/0001-stack-pwa-first.md), the PWA-first pivot, is superseded/void).

> **Scope note (DRE-9):** the brief PWA-first realignment (DRE-8) is **void**.
> The client is the native `:app` Compose module; there is no PWA, no
> TWA/Capacitor, no web client. Persistence is native Room/SQLite (encrypted at
> rest) + the backend repository layer (DRE-6).

## Stack (one JVM language across native + backend)

| Layer        | Where          | Type                                                      |
| ------------ | -------------- | --------------------------------------------------------- |
| Client       | `app/`         | Kotlin + Jetpack Compose — **native Android** (`:app`)    |
| Backend      | `server/`      | Kotlin/JVM · Ktor (`:server`)                             |
| Domain model | `core/domain/` | Pure Kotlin/JVM (`:core:domain`) — the shared safety math & rules |

Gradle (Kotlin DSL) + version catalog at `gradle/libs.versions.toml`.
**JDK 17** (host JDK 17+). **Android SDK required** for `:app` (compileSdk 35).

## Prerequisites

- **JDK 17** (Temurin recommended; host JDK 17+ also fine).
- **Android SDK** with `platforms;android-35` and `build-tools;35.0.0`.
  Set `sdk.dir` in `local.properties` (gitignored, machine-specific).
- Python 3 (only for the PoC reference build validator).

macOS quick start (Homebrew):

```bash
brew install openjdk@17
# Android SDK via Android Studio's SDK Manager, or `sdkmanager`.
```

## Build · run · test

```bash
# Native client — assemble the debug APK
./gradlew :app:assembleDebug

# Backend + domain tests (JVM — the CI commit gate)
./gradlew :core:domain:test :server:test

# Run the backend locally (http://127.0.0.1:8080/health)
./gradlew :server:run

# PoC reference integrity validator (required files + JSON + XLSX)
python3 scripts/validate_build.py
```

### Smoke tests

- `:app:assembleDebug` produces `app/build/outputs/apk/debug/app-debug.apk`.
- `core/domain` verifies the deterministic energy math against the PoC's single
  source of truth: Mifflin-St Jeor BMR for the seed profile equals **1872 kcal**
  (`data/derived_metrics.json`). If it breaks, a safety-relevant equation
  regressed. The deterministic safety gate (`SafetyGate`) pins the red-flag gate
  and side-specific lock (invariants #1, #3).
- The server smoke test asserts `GET /health` returns `{"status":"ok"}` and
  `/v1/safety/evaluate` returns the binding deterministic verdict.
- `scripts/validate_build.py` confirms the PoC reference manifest is intact.

## Repo layout

```
app/            # Native Android client — Kotlin + Compose (:app) — the product client
server/         # Ktor backend (:server) — safety engine, repository layer, orchestrator
core/domain/    # pure Kotlin/JVM domain (:core:domain) — shared by :app + :server
gradle/         # version catalog + wrapper
docs/adr/       # architecture decision records (0002 native-only is authoritative)
specs/          # product specs (Architecture, SDD, Safety_Threat_Model, …)
data/           # PoC source of truth (profile, exercises, evidence, program)
prompts/        # LLM prompts used by the server orchestrator
obsidian/       # PoC authoring vault (progression rules, evidence map, …)
scripts/        # PoC reference build validator
```

## Non-negotiables (the PoC's invariants)

1. **Safety gate = code, not model** — red-flag gate + side-specific lock run
   pre-LLM, unoverridable from any client (including native).
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

## Verification

GitHub Actions CI is **retired** ([DRE-27](/DRE/issues/DRE-27)) — the
`Ihavesoul/humanlive` account has a billing lock, and the board directed
local verification. Before pushing, build and test under **JDK 17** on the
host:

- JVM tests: `./gradlew :core:domain:test :server:test`
- Native build: `./gradlew :app:assembleDebug`
- PoC reference build validator (Python).

---

_Co-maintained by the DreamTeam agent org. Founder: Ihavesoul._
