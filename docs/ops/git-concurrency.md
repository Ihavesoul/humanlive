# humanlive git-concurrency & dead-run-lock runbook

Two recurring ops problems on the shared `humanlive` checkout and how we now
handle them. Root-cause writeup: [DRE-33](/DRE/issues/DRE-33).

---

## 1. Serialize git-mutating runs on `main` (the durable fix)

**Problem.** Several agents (Founding Engineer, Evidence & Research Analyst,
Chief of staff, …) share one working tree at `cwd=/Users/sadge/myprojects/humanlive`
and all publish to `main`. With `maxConcurrentRuns > 1`, two runs committing
and pushing `main` in the same window diverged the tree and forced the
DRE-21 reconciliation saga (rework on DRE-10/DRE-14).

**Rule (mandatory).** *Every* publish to `main` goes through the serialized
publisher. Do **not** run `git commit` + `git push origin main` by hand for
main-branch work.

```sh
# stage your changes as usual, then:
scripts/git_publish.py -m "feat(safety): activate rule X (DRE-N)"
```

The helper holds an exclusive lock (`fcntl.flock` on
`.git/paperclip-main.lock`) across the **whole** sequence:

```
(optional commit) -> fetch origin/main -> rebase onto origin/main -> push origin main
```

Because the lock is held end-to-end, publishers serialize: the second one
blocks, then fetches the just-pushed `origin/main` and rebases onto it. The
push is always a fast-forward. **Divergence is impossible while everyone routes
through here.** Conflicts abort cleanly (no force, no auto-resolve).

**Enforcement.** A committed `pre-push` hook (`scripts/git-hooks/pre-push`),
wired via `git config core.hooksPath scripts/git-hooks`, blocks any bare
`git push origin main` unless the helper set `PAPERCLIP_MAIN_PUSH_OK=1`. So
even an agent that ignores this rule cannot race — it just fails fast with a
pointer to the helper. Pushes to other branches are untouched.

**Operator escape hatch** (hotfixes, emergency rebase):

```sh
PAPERCLIP_MAIN_PUSH_BYPASS=1 git push origin main
```

**Verify the lock works:**

```sh
python3 scripts/git_publish.py --test
# -> "exclusive lock blocks a concurrent writer."
```

**Install / re-install after a fresh clone** (the hook config is local, not
committed):

```sh
git config core.hooksPath scripts/git-hooks
chmod +x scripts/git-hooks/pre-push scripts/git_publish.py
```

**Why not per-agent worktrees?** That is the cleanest root fix (no shared
tree at all), but the local adapter has no agent-facing API to assign per-run
workspaces (adapter/workspace endpoints return 404). Until that exists, this
serialized-publisher + hook is the durable guard. Tracked as a platform ask in
[DRE-33](/DRE/issues/DRE-33).

---

## 2. Dead-run execution locks — what actually happens and what to do

**What you'll see.** A run dies mid-checkout and the issue shows a stale
`checkedOutByRunId` / `executionRunId` / `executionLockedAt`. Past tasks filed
for this: [DRE-28](/DRE/issues/DRE-28), [DRE-30](/DRE/issues/DRE-30),
[DRE-32](/DRE/issues/DRE-32), [DRE-34](/DRE/issues/DRE-34).

**Two different fields — don't conflate them:**

| field | meaning | clears how |
|---|---|---|
| `checkedOutByRunId` / `checkedOutAt` | the **held checkout lock** (blocks other actors from the issue) | control-plane **run-timeout / liveness backstop** reclaims it automatically. Proven on DRE-23/DRE-31/DRE-34 and on DRE-21. |
| `executionRunId` / `executionLockedAt` (on a `done` issue) | **execution attribution** — which run did the work — *not* a held lock | informational; nothing is blocked. |

**Runbook, in order:**

1. **Don't panic and don't file a force-release task immediately.** The
   liveness backstop reclaims held checkout locks on its own timeout (observed
   within minutes). Verify first:
   ```sh
   curl -s -H "Authorization: Bearer $PAPERCLIP_API_KEY" \
     "$PAPERCLIP_API_URL/api/issues/<id>" | jq '{status, checkedOutByRunId, executionRunId}'
   ```
2. **If the issue is already `done`** with `checkedOutByRunId=null`, nothing is
   held — the lingering `executionRunId` is attribution. No action needed; do
   **not** open an ops task for it.
3. **If a downstream issue is genuinely stuck** (`blocked` on a held-lock issue
   that has not self-cleared after the backstop window), post the diagnosis in
   the thread and escalate to the board/operator: there is currently **no
   agent-accessible force-release** (release is boundary-scoped to the owning
   run; cross-actor release returns 403). This is a platform gap, filed in
   [DRE-33](/DRE/issues/DRE-33): a dead-run execution-lock reaper /
   admin force-release path is the upstream ask.
4. **Concurrency is the upstream prevention.** Most "dead run" incidents start
   as a run that got SIGTERM'd while fighting another run on the shared tree
   (DRE-22/DRE-21). Rule #1 above removes that trigger.

**Platform gaps still open (need operator/Paperclip, not agents):**

- No agent/admin API to force-release a checkout lock held by a dead run
  (only the liveness backstop clears it; no manual override exposed).
- No per-agent / per-run execution worktree config exposed via the API
  (adapter/workspace endpoints 404), so the shared-tree contention cannot be
  removed at the config layer by an agent.
- No visible dead-run reaper process; reclamation is implicit via run-timeout.

These are recorded here so we stop reopening ops tasks for symptoms the
backstop already handles, and so the durable fixes live in one place.

---

## 3. Intra-agent concurrent-heartbeat divergence (DRE-42)

**Problem.** An agent with `runtimeConfig.heartbeat.maxConcurrentRuns > 1`
can run several heartbeats at once. The serialized `main` publisher (§1)
protects the **git layer** — only one commit lands. The per-issue checkout
lock protects the **mutation layer** — only one run owns the issue. Neither
serializes **read-only appraisal + status-comment posting**. So concurrent
heartbeats of the *same* agent can each read the (pre-commit) tree, appraise
independently, and post contradictory conclusions — all non-mutating, so no
lock stops them.

This bit the Evidence & Research Analyst on [DRE-39](/DRE/issues/DRE-39):
`maxConcurrentRuns: 20`, three heartbeats in one window produced two
contradictory appraisals ("rule live" vs "empty set / rule inert"). Only the
"live" appraisal's commit (`37ed65c`) shipped — the git layer held — but both
comments landed on the thread. Same root class as the
[DRE-21](/DRE/issues/DRE-21) inter-agent divergence, now intra-agent. Filed
and reconciled in [DRE-42](/DRE/issues/DRE-42).

**Why checkout + publisher aren't enough here.** The publisher serializes
*pushes*; the checkout serializes *issue ownership*. Read-only appraisal that
culminates in a comment (no checkout, no push) touches neither, so N
concurrent heartbeats can emit N appraisals. Contradiction is invisible until
a reviewer reads the thread — and by then both are on the record.

**Mitigation (durable, config layer).** For agents whose output is
appraisal/reasoning where contradiction is a correctness/integrity hazard
(Evidence & Research Analyst, Safety Reviewer, Reflection Coach), set
`maxConcurrentRuns: 1`. Serial heartbeats → no two appraisals can race.
Research/review work is not throughput-bound; correctness >> parallelism here.
Softer alternative: `2`–`3` if genuine parallelism across *different* issues is
later wanted, accepting same-issue races remain possible.

```jsonc
// PATCH /api/agents/{agentId}   (runtimeConfig.heartbeat.maxConcurrentRuns)
{ "runtimeConfig": { "heartbeat": { "maxConcurrentRuns": 1 } } }
```

This is a governance decision (changes a teammate's capacity) — propose via
board confirmation, don't patch unilaterally.

**Secondary defense (behavioral, fragile).** Before posting an appraisal or
status flip, re-fetch the thread; if a newer same-agent appraisal already
landed, reconcile or defer instead of re-appraising. Helps, but depends on
every run behaving — the config change is the root fix.

**Platform ask (operator/Paperclip).** Serialize per-issue comment/status
writes for same-agent concurrent runs the way checkout serializes ownership,
so an appraisal can't land while a sibling run is mid-flight on the same
issue. (Same theme as the §2 platform gaps.) Until then,
`maxConcurrentRuns: 1` for appraisal agents is the guard.
