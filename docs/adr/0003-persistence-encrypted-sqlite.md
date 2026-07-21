# ADR 0003 — Persistence: encrypted SQLite at rest behind the repository ports

- **Status:** Accepted
- **Date:** 2026-07-21
- **Decides for:** [DRE-16](/DRE/issues/DRE-16). Formalizes the persistence
  direction already named by [ADR 0002](0002-stack-native-only.md) ("native
  Room/SQLite encrypted at rest + the backend repository layer") and by
  [DRE-6](/DRE/issues/DRE-6) (the ports).
- **Supersedes (stance):** [ADR 0001](0001-stack-pwa-first.md)'s "no on-device
  native database; offline-first state in PWA IndexedDB" — already voided by
  [DRE-9](/DRE/issues/DRE-9)/ADR 0002. The stale comment in
  `core/domain/.../persistence/SchemaVersion.kt` repeating that stance is
  superseded by this ADR; refresh it when the domain module is next touched.
- **Carries:** the Chief-of-Staff decision flagged in
  [DRE-6](/DRE/issues/DRE-6) — pick the durable store behind the ports.

## Context

Feature code depends on the repository *ports* in
`core/domain/.../persistence/Repositories.kt`, never on a concrete store
([DRE-6](/DRE/issues/DRE-6)). So far the only implementation has been the
**in-memory** reference repos (`server/.../InMemoryRepositories.kt`) —
thread-safe but **not durable**: every process restart loses all users, plans,
progress, symptoms, and the evidence/safety allowlists. For health-signal data,
"the data is the product" — losing a user's progress history or silently failing
to persist a recorded symptom is a data-integrity bug, not an inconvenience.

The Chief of Staff picked the durable backend: **encrypted SQLite at rest,
offline-first, reversible.** This ADR records *why SQLite*, *why encrypted*, and
*how the key is handled*, and points at the implementation.

## Decision

The durable store is a **single SQLite file** behind the existing ports, with
**per-row AES-GCM encryption at rest**:

| Concern        | Choice                                                                                    |
| -------------- | ----------------------------------------------------------------------------------------- |
| Engine         | SQLite (JDBC `org.xerial:sqlite-jdbc` on the backend; Room/SQLCipher on the native client later) |
| Shape          | Versioned document store — each aggregate is one encrypted JSON blob in its own table     |
| Encryption     | AES-GCM per payload (`javax.crypto`, stdlib); layout `[12-byte nonce][ciphertext+tag]`    |
| Encrypted      | The payload column (the actual health-signal data)                                        |
| Plaintext      | Query keys only: `id`, `user_id` (non-sensitive identifiers needed to find rows)          |
| Key            | Injected via `EncryptionKey`; runtime reads a base64 AES-256 key from a deployment secret/env var. **Never in code, git, config, or logs.** |
| Concurrency    | Single connection + monitor (SQLite is single-writer)                                     |
| Reversibility  | Swappable behind the ports; in-memory and SQLite both pass the same `RepositoryLayerTest` contract |

Implementation: `server/src/main/kotlin/dreamteam/server/persistence/`
(`Encryption.kt` for the key/cipher seam, `SqliteRepositories.kt` for the store
+ eight repos). Feature code is unchanged — it still holds only the interface
types.

### Why SQLite (not Postgres) for the backend today

- **Offline-first parity.** The native client will use on-device SQLite
  (Room/SQLCipher). Sharing the engine family and the document-store shape
  between client and backend keeps the persistence mental model one thing, and
  makes a future shared encrypted-store module plausible without a rewrite.
- **Zero-ops at current scale.** A single file, no server process, no
  connection-pool tuning, trivially backed up by copying the file. The backend
  is single-tenant-ish today; SQLite's single-writer ceiling is not a live
  constraint.
- **Reversible.** Because the ports are the only seam, a future switch to
  Postgres (or anything else) is a behind-the-ports swap validated by the same
  contract test — not an architecture change.
- **Postgres is the known upgrade path, deliberately deferred.** The day the
  backend needs concurrent multi-writer throughput, HA, or genuine server-side
  query workloads, Postgres behind these same ports is the boring next step.
  This ADR makes that an explicit, contained migration, not a rewrite.

### Why application-layer AES-GCM (not SQLCipher) on the backend

The backend runs on the JVM; SQLCipher's value is strongest on-device. Doing
per-row AES-GCM over the payload column with `javax.crypto` (no new native
dependency, works in CI as-is) gives the same security property — **the DB file
on disk is useless without the injected key** — while keeping the build boring
and cross-platform. The `SqliteRepositoryContractTest` proves the file holds
ciphertext, not plaintext, and that a wrong key cannot decrypt.

## Encryption boundary (threat model)

- **Encrypted:** every payload column — anthropometrics, symptoms, medical
  context, plans, progress, nutrition. This is the health data.
- **Plaintext:** `id` / `user_id` query keys. These are non-sensitive
  identifiers (e.g. `user-1`, `plan-1`, `ACSM-RT-2026`). Keeping them plaintext
  is what makes "find by user / by id" a cheap indexed lookup instead of
  decrypting the whole table. An attacker with the file learns *that* N users
  with M symptoms exist, but nothing about any user's health.
- **Authenticated:** AES-GCM tags mean any tampering with a blob or its nonce
  fails decryption — no silent corruption of health data.

## Non-negotiables enforced here

1. **No key in code or git.** `EncryptionKey` is injected; the runtime provider
   (`EncryptionKeys.fromBase64Env`) reads from a deployment secret and fails
   loudly (never silently) if the key is missing or not 32 bytes.
2. **Evidence-by-allowlist still holds.** `EvidenceSourceRepository.contains` /
   `ExerciseRepository.contains` are the same allowlist gates; the SQLite store
   answers them the same way the in-memory store did.
3. **Data integrity.** WAL journaling + authenticated encryption: restart-safe
   writes and tamper-evident reads for health-signal data.
4. **Reversibility.** The store is behind the ports; swapping it is contained.

## Consequences

- New dependency: `org.xerial:sqlite-jdbc` (one well-known lib; native lib
  auto-extracted per platform — works on macOS arm64 dev and Ubuntu x86_64 CI).
- `ponytail` ceilings to revisit: single connection + synchronized access (fine
  until backend write throughput matters); single file (fine until multi-tenant
  scale or HA demand — then Postgres).
- **Key management is now an operational responsibility.** Losing the injected
  key makes the file unrecoverable (that is the point). Key rotation = a
  re-encrypt migration over the encrypted payloads — tracked as a follow-up, not
  built speculatively.
- On-device persistence (Room/SQLCipher in `:app`) is a separate, later task;
  this ADR fixes the **backend** durable store and the encryption boundary the
  client will mirror.
