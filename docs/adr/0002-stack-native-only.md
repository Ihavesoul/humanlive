# ADR 0002 — Stack: native Android (Kotlin/Compose) client + Kotlin/JVM Ktor backend

- **Status:** Accepted
- **Date:** 2026-07-21
- **Supersedes:** [ADR 0001](0001-stack-pwa-first.md) (PWA-first). Carries the
  founder directive [DRE-9](/DRE/issues/DRE-9) ("ONLY native"), which voids
  [DRE-8](/DRE/issues/DRE-8)'s PWA-first pivot.
- **Decides for:** [DRE-5](/DRE/issues/DRE-5), [DRE-6](/DRE/issues/DRE-6).

## Context

The build was briefly realigned to **PWA-first** ([ADR 0001](0001-stack-pwa-first.md) /
[DRE-8](/DRE/issues/DRE-8)) on a misread of an earlier founder comment. The
founder corrected it directly: *"Now it's a problem that someone do PWA someone
do Native. I would like do ONLY native. My PoC was just PoC, nothing more, it's
should be a superseed."* ([DRE-2](/DRE/issues/DRE-2) 2026-07-21T16:46Z).

So the PoC's vanilla-JS PWA is **reference only**, not the client. The product
client is **native Android** (Kotlin + Jetpack Compose). The PoC's *logic* —
`specs/`, `prompts/`, `data/` (incl. `evidence_catalog.json`), the safety
engine, the decision rules, and the 12 invariants — is mined and ported into the
native app and the shared domain tier. Its web/PWA stack is **not** adopted.

## Decision

The build is a **three-tier JVM + native Android** system:

| Layer        | Tech                                   | Location       | Build                |
| ------------ | -------------------------------------- | -------------- | -------------------- |
| Client       | Kotlin + Jetpack Compose (native)      | `app/`         | Gradle `:app` (AGP)  |
| Backend      | Kotlin/JVM + Ktor                      | `server/`      | Gradle `:server`     |
| Domain model | Pure Kotlin/JVM (no Android dep)       | `core/domain/` | Gradle `:core:domain`|

**Client is native-only.** No PWA, no TWA/Capacitor, no web client. The `app/`
directory now holds the Android Compose module, not the PWA.

**Persistence** ([DRE-6](/DRE/issues/DRE-6)) is **native Room/SQLite (encrypted
at rest) + the backend repository layer** — not PWA IndexedDB. The domain model
itself (`User`, `TrainingPlan`, `Exercise`, `Nutrition`, `Symptom`, `Progress`,
`SafetyRule`, `EvidenceSource`) is unchanged; only the persistence surface is
native.

**Shared deterministic core.** `:core:domain` stays Android-free so it compiles
and tests on the host JDK and is consumed by **both** `:app` (native) and
`:server` (backend). The native client gets the same deterministic safety/domain
logic the backend enforces — safety is decided in code, once.

### Why this stack

- **One JVM language** across backend, domain, and the native client (Kotlin).
  The safety-critical deterministic logic lives once in `:core:domain`.
- **Native client = real Android.** Compose is the supported, boring choice for
  a modern Android-first product; no wrapper tax, no webview.
- **`minSdk 26`**, `compileSdk 35`, AGP on the host toolchain.

## Non-negotiables enforced by this structure

These are the PoC's invariants; the stack keeps them enforceable in code:

1. **Safety gate = code, not model.** The red-flag gate and side-specific lock
   run pre-LLM and cannot be overridden by any client — including the native
   one. (Built in `:core:domain` / `SafetyGate`; [DRE-7](/DRE/issues/DRE-7).)
2. **Evidence by server-side allowlist.** The model never emits DOI/PMID/URL.
3. **Side-specific lock.** No Cobb/wedge/directional-breathing/unequal-load
   guidance from old x-ray photos.
4. **Deterministic fallback.** `/plans/generate` serves the fallback plan
   first, before any LLM path; no LLM failure strands the user.
5. **No LLM in the client.** The client sends structured requests and renders
   server-validated output. LLM wiring is the LLM & Safety Orchestrator's role.

## Consequences

- The Android client is the installed app; there is no web/PWA delivery path.
- `:core:domain` is the integration seam between native and backend; the native
  `:app` consumes it so deterministic safety logic is shared, not duplicated.
- Verification is local under JDK 17 ([DRE-27](/DRE/issues/DRE-27)): run the
  PoC reference validator, the JVM tests (`:core:domain`, `:server`), and
  `:app:assembleDebug` before pushing. GitHub Actions CI was retired (billing
  lock on `Ihavesoul/humanlive`); the workflow file was removed.
- Adding a feature: deterministic logic in `:core:domain`, the HTTP surface +
  repository layer in `:server`, and Compose UI in `:app`.
