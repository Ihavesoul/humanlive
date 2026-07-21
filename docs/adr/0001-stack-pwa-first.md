# ADR 0001 — Stack: PWA-first client + Kotlin/JVM Ktor backend

- **Status:** Superseded by [ADR 0002](0002-stack-native-only.md)
- **Date:** 2026-07-21
- **Superseded because:** the founder reversed the PWA-first decision in
  [DRE-9](/DRE/issues/DRE-9) ("ONLY native"). This PWA-first record is kept
  for history; do not build against it. The authoritative stack is
  [ADR 0002](0002-stack-native-only.md) (native Android + Compose client).
- **Supersedes:** the "Android-first / Kotlin-Compose" framing originally
  scoped in [DRE-5](/DRE/issues/DRE-5) and [DRE-6](/DRE/issues/DRE-6). That
  reframe is carried by the directive [DRE-8](/DRE/issues/DRE-8) (now void),
  which locked the founder decision from [DRE-2](/DRE/issues/DRE-2).

## Context

The PoC v0.2.0 baseline shipped a working **vanilla-JS PWA** (`app/`) with no
build step: profile/macronutrient math, a 120-day log, the 12-week programme,
the safety gate, export, and a structured-LLM-request generator. It already
runs offline-first on Android and desktop by installation.

The founder locked the product direction as **PWA-first**: the PoC's existing
PWA **is** the client. There is no separate native Android client to build.
An earlier scoping pass ([DRE-5](/DRE/issues/DRE-5)/[DRE-6](/DRE/issues/DRE-6))
framed the work as Android-first with a Kotlin/Compose `:app` shell, native
Room/SQLite persistence, and `:feature:*` UI libraries. That framing is
superseded; any native Compose UI already explored is sunk cost and is not
extended.

## Decision

The build is a **two-tier JVM + static-asset** system:

| Layer        | Tech                                   | Location      | Build         |
| ------------ | -------------------------------------- | ------------- | ------------- |
| Client       | Vanilla-JS PWA (no transpile/bundle)   | `app/`        | None — served |
| Backend      | Kotlin/JVM + Ktor                      | `server/`     | Gradle `:server` |
| Domain model | Pure Kotlin/JVM (no Android dep)       | `core/domain/`| Gradle `:core:domain` |

**Dropped:** the native `:app` Compose shell, the `:feature:plan` Compose
library, and the `:core:data` Android library (Room/SQLite surface). The Gradle
settings now include only `:server` and `:core:domain`. The build has **no
Android SDK dependency**.

**Persistence** is split between the two tiers (per [DRE-6](/DRE/issues/DRE-6)):
offline-first state lives in the PWA (IndexedDB/localStorage, encrypted at
rest), and the durable source of truth for plans/progress/symptoms/evidence
lives in the backend repository layer. There is no native on-device database.

### Why this stack (boring, small surface)

- **One JVM language** for backend + domain. The safety-critical deterministic
  logic (energy equations, the rule engine, the deterministic fallback plan)
  lives in pure Kotlin and is unit-testable on the JDK alone.
- **No client build step.** The PWA is static files; no bundler, no transpiler,
  no framework to age. The PoC already works this way.
- **No native toolchain in CI.** Tests and the build validator run on a plain
  JDK + Python; no Android SDK, emulator, or signing setup.
- **Offline-first by default.** A PWA is installable on Android and stays
  useful with no connectivity — the product's stated posture.

## Non-negotiables enforced by this structure

These are the PoC's invariants; the stack is chosen so they are enforceable in
code, not by trust:

1. **Safety gate = code, not model.** The red-flag gate and side-specific lock
   run server-side, pre-LLM, and cannot be overridden by any client. (Built in
   [DRE-7](/DRE/issues/DRE-7).)
2. **Evidence by server-side allowlist.** The model never emits DOI/PMID/URL;
   citations come from a backend allowlist only.
3. **Side-specific lock.** No Cobb angle, wedge, directional-breathing, or
   unequal-load guidance derived from old x-ray photos.
4. **Deterministic fallback.** `/plans/generate` serves the fallback plan
   **first**, before any LLM path; no LLM failure can strand the user.
5. **No LLM in the client.** LLM wiring is the LLM & Safety Orchestrator's
   role (being hired); the client only sends structured requests and renders
   server-validated output.

## Consequences

- The Android "app" is the installed PWA; a native wrapper is deferred
  indefinitely and would only be revisited if a capability the PWA cannot
  provide becomes load-bearing.
- `:core:domain` stays Android-free so it compiles and tests on the host JDK
  and can be consumed by `:server` without an Android dependency.
- CI runs (a) the PoC build validator over the repo and (b) the backend/domain
  Kotlin tests, as commit gates (`.github/workflows/ci.yml`).
- Adding a feature means: deterministic logic in `:core:domain`, the HTTP
  surface + repository layer in `:server`, and UI in `app/`. No new tiers.
