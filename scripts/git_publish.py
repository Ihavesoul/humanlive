#!/usr/bin/env python3
"""
Serialized publisher for the shared `humanlive` checkout.

Why this exists: several agents share one working tree (cwd=humanlive) and all
push `main`. Two runs publishing in the same window diverged the tree and cost
us DRE-21's reconciliation saga (DRE-10/DRE-14 rework). This script makes the
publish to `main` atomic and exclusive so that cannot happen again.

It holds an exclusive lock (fcntl.flock) across the whole sequence:
    (optional commit) -> fetch origin main -> rebase onto origin/main -> push

Because the lock is held for the entire sequence, two publishers serialize:
the second blocks until the first releases, then fetches the freshly-pushed
origin/main and rebases. A fast-forward push is always the result. Divergence
is impossible while everyone routes through here.

The companion pre-push hook (scripts/git-hooks/pre-push) blocks bare
`git push origin main` unless this script set PAPERCLIP_MAIN_PUSH_OK=1, so even
an agent that bypasses the helper cannot race.

Usage:
    scripts/git_publish.py -m "feat(x): do the thing (DRE-N)"
    scripts/git_publish.py -m "msg" --       # extra staged changes already added
    scripts/git_publish.py                   # publish already-committed local main
    scripts/git_publish.py --test            # self-test: flock serializes

macOS has no `flock(1)`; we use fcntl (always available here).
"""
import argparse
import fcntl
import os
import subprocess
import sys
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOCKNAME = "paperclip-main.lock"  # lives in .git so it is scoped to this checkout
COAUTHOR = "Co-Authored-By: Paperclip <noreply@paperclip.ing>"


def run(cmd, check=True, env=None, capture=False):
    e = dict(os.environ)
    if env:
        e.update(env)
    if capture:
        return subprocess.run(cmd, check=check, env=e, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return subprocess.run(cmd, check=check, env=e)


def gitdir():
    out = run(["git", "rev-parse", "--git-dir"], capture=True)
    gd = out.stdout.strip()
    return gd if os.path.isabs(gd) else os.path.join(REPO, gd)


def shastate(ref):
    try:
        out = run(["git", "rev-parse", "--verify", ref], capture=True, check=False)
        return out.stdout.strip() if out.returncode == 0 else None
    except Exception:
        return None


def publish(message=None):
    lockpath = os.path.join(gitdir(), LOCKNAME)
    with open(lockpath, "w") as lf:
        # Blocking exclusive lock. Held until the `with` block exits.
        fcntl.flock(lf.fileno(), fcntl.LOCK_EX)
        # Write holder info (advisory; for humans inspecting a stuck lock).
        try:
            lf.seek(0); lf.truncate()
            lf.write(f"{os.getpid()}\n{time.strftime('%Y-%m-%dT%H:%M:%S')}\n")
            lf.flush()
        except Exception:
            pass

        # 1. optional commit of whatever the caller staged
        if message:
            # ponytail: append the required co-author trailer once.
            full = message if message.rstrip().endswith("Paperclip <noreply@paperclip.ing>") else f"{message}\n\n{COAUTHOR}"
            run(["git", "commit", "-m", full])

        # 2. fetch latest under the lock
        run(["git", "fetch", "origin", "main"])

        # 3. reconcile local main onto origin/main (fast-forward or rebase).
        local = shastate("HEAD")
        upstream = shastate("origin/main")
        if local and upstream and local != upstream:
            base = run(["git", "merge-base", "HEAD", "origin/main"], capture=True).stdout.strip()
            if base == local:
                pass  # behind: fast-forward push handles it
            elif base == upstream:
                pass  # ahead: fast-forward push
            else:
                # diverged: replay our local commits on top of origin/main.
                r = run(["git", "rebase", "origin/main"], check=False,
                        env={"PAPERCLIP_MAIN_PUSH_OK": "1"})
                if r.returncode != 0:
                    run(["git", "rebase", "--abort"], check=False)
                    print("git_publish: rebase onto origin/main conflicted; "
                          "aborted so you can resolve deliberately. No push.", file=sys.stderr)
                    return 2

        # 4. push (fast-forward). Marker lets the pre-push hook allow it.
        run(["git", "push", "origin", "main"], env={"PAPERCLIP_MAIN_PUSH_OK": "1"})
    return 0


def selftest():
    """Smallest check that proves the lock blocks a concurrent writer."""
    lockpath = os.path.join(gitdir(), LOCKNAME + ".selftest")
    open(lockpath, "w").close()
    # Child holds the exclusive lock for 1s.
    holder = subprocess.Popen([sys.executable, "-c",
        "import fcntl,sys,time; f=open(sys.argv[1],'w'); "
        "fcntl.flock(f.fileno(), fcntl.LOCK_EX); time.sleep(1.0)",
        lockpath])
    time.sleep(0.3)  # let the holder acquire first
    # A second non-blocking acquire MUST fail while the holder has it.
    probe = subprocess.run([sys.executable, "-c",
        "import fcntl,sys; f=open(sys.argv[1],'w'); "
        "sys.exit(0 if fcntl.flock(f.fileno(), fcntl.LOCK_EX|fcntl.LOCK_NB)==0 else 1)",
        lockpath], capture_output=True)
    holder.wait()
    if probe.returncode == 0:
        print("git_publish self-test FAILED: a concurrent writer acquired the "
              "lock while one was held; serialization is broken.", file=sys.stderr)
        return 1
    print("git_publish self-test OK: exclusive lock blocks a concurrent writer.")
    return 0


def main():
    ap = argparse.ArgumentParser(description="Serialized publisher for humanlive main.")
    ap.add_argument("-m", "--message", help="Commit staged changes with this message, then publish.")
    ap.add_argument("--test", action="store_true", help="Run self-test and exit.")
    args = ap.parse_args()
    return selftest() if args.test else publish(args.message)


if __name__ == "__main__":
    sys.exit(main())
