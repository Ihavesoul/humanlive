# ADR 0003: Durable SQLite behind the repository ports

- Status: **Accepted**
- Date: 2026-07-21
- Supersedes: the "in-memory only" placeholder noted in ADR 0001 (server repo layer)
- Related: [ADR 0002](0002-stack-native-only.md) (native-only), DRE-6 (ports), DRE-12 (durable impl)

## Context

DRE-6 introduced the repository *ports* (`Repositories.kt`) so feature code never
touches a concrete store, backed by an in-memory reference implementation. That
implementation is explicitly **not durable** — it is lost on process restart.
Milestone 2 needs the deterministic plan pipeline to persist generated plans,
user profiles, progress, and symptoms across restarts, and the smoke test
("restart, data persists") requires a durable backing store.

The store must sit **behind the existing ports** (no feature-code change), stay
boring / few-dependency (ADR 0001), and be consistent with the offline-first,
native-aligned posture of ADR 0002.

## Decision

The backend durable store behind the repository ports is **SQLite**, accessed
via the `sqlite-jdbc` (Xerial) driver on the JVM. Each aggregate is stored as a
JSON document keyed by id, with a small number of indexed columns for the
by-user lookups the ports require. The schema baseline is fixed at
`SchemaVersion.CURRENT` (v1); future schema changes are document-migration steps
on the existing `DocumentMigrator`, not ad-hoc `ALTER TABLE`s.

The in-memory implementations remain as the test double; the SQLite
implementations are the durable default in `:server`.

## Why SQLite (and not e.g. Postgres)

- **Offline-first, native-aligned.** The native client persists on-device with
  SQLite; the backend using the same engine keeps one mental model and a trivial
  export/import path between device and server. Single-file, copyable, inspectable.
- **Boring, one dependency.** A single, broadly-deployed library; no separate DB
  server to provision, operate, or page on at 3am. The founding risk is scope,
  not framework choice.
- **Sufficient for the load profile.** A single-writer, single-process backend
  for an early product is well within SQLite's envelope. The `synchronized`
  single-connection guard is documented with its ceiling and upgrade path.

If/when contention or concurrency demands it, the swap is a new implementation
of the same ports (a pool + Postgres), not a feature-code change — the ports are
the isolation boundary.

## Encryption-at-rest

The brief asked for "encrypted SQLite". For the **backend service**, at-rest
encryption is enforced at the **infrastructure layer** (managed volume / database
disk encryption), which is the appropriate level for a server process: app-level
SQLCipher keys on a server would live next to the data they protect, buying
little real threat reduction while adding a fragile native-crypto dependency on
the JVM (poor SQLCipher-on-JVM library support). SDD §8 specifies
"production backend should encrypt at rest/in transit" — disk/volume encryption
satisfies at-rest; TLS satisfies in transit.

The **on-device client** is the different and stronger case: a phone is a
shared, easily-stolen surface, so the native store uses **SQLCipher** (encrypted
SQLite). That lands with the Room/SQLCipher integration in M3. The ADR therefore
splits encryption by surface rather than mandating SQLCipher on the JVM, which
would be the wrong tool there.

If the threat model later demands application-keyed encryption on the server
too, it slots in behind the same ports (encrypt the `payload` column, or migrate
to SQLCipher-backed driver) without touching feature code.

## Consequences

- **+** Plans, users, progress, and symptoms survive restart (smoke test green).
- **+** One store engine across device and server; portable single-file database.
- **+** Schema evolution is a documented migration step, not a silent drift.
- **−** Single-writer ceiling — acceptable now, documented as the upgrade trigger.
- **−** Server at-rest encryption is infra-level, not app-level — deliberate, by
  surface (see above); revisit if the threat model changes.
