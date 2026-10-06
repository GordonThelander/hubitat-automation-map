# Automation Map project instructions

## Read before planning work

Open work for both projects is in the GitHub Issues queue (see "Working together" below). The handover
note `docs/2026-10-05-handover-from-cloud-session.md` has the exact checks for Issue #12.

- `docs/2026-10-05-ham-hai-architecture-review.md` - an architecture, usability and enhancement review of
  Automation Map and HAI, with 20 ranked recommendations weighed against the hub's measured limits and a
  suggested order. Written 2026-10-05 at Gordon's request. Recommendations, not decisions: check with
  Gordon before starting one. The same file is in the HAI repository as
  `ideation/2026-10-05-ham-hai-architecture-review.md`.
- `docs/cloud-task-test-harness-findings.md` - what the published-document assertions found against the
  captured decode files, and Gordon's decisions on them.

## Tests

`pwsh tests/run-all.ps1` runs every suite in `tests/` and exits non-zero on any failure. Run them on
Groovy 2.4 (the hub's version): several behaviours differ on newer Groovy. Suites slice real functions from
`apps/automation_map.groovy` through `tests/support/AppSource.groovy`; do not add a stub for a function the
app defines. `tests/fixtures/ham-decode*.json` are captured hub output - never regenerate or edit them to
make a test pass.

## Working together: cloud, Claude HAM and Claude HAI (Gordon, 2026-10-06)

**The queue is GitHub Issues in the HAI repository**, for both projects:
https://github.com/GordonThelander/hubitat-automation-intelligence/issues. Start every session by
reading the open Issues labelled for you (`to:hai`, `to:ham` or `to:cloud`). One Issue per work item. Hand over
by commenting what you did and saw, then moving the `to:` label. Close the Issue with the evidence. Use
`to:gordon` for a decision only Gordon can make, `needs-hub` for work that only a session reaching the hub
can do, `blocked` while waiting on another Issue, and `feed` for anything touching the parity feed.

| | Cloud session | Claude HAM (local) | Claude HAI (local) |
|---|---|---|---|
| Reaches | GitHub only | Hub, local files, GitHub | Hub, local files, GitHub |
| Owns | All off-hub work: code, converter and decode logic against captured files, tests, CI, docs, reviews, merges of off-hub work | Deploying Automation Map, scans, recapturing decode files, checking a decode on the hub | Deploying HAI, hub tests (the Tested column), on-hub checks |
| Hands to the others | `needs-hub` Issues with exact checks | Results and captures, back to `to:cloud` | Results, back to `to:cloud` |

**Codex supervises the local Claude sessions** (Gordon, 2026-10-06). It keeps them to the Issue in hand,
to hub work, and to one commit per Issue, without extra test runs. When Codex says stop or narrow the scope,
the Claude session does that.

**Concord is local only.** Codex and the local Claude sessions talk live through it, but the cloud session
cannot see it. So anything that hands work over, records a hub result or needs Gordon's decision goes into
an Issue, never only into Concord.

Local sessions: **do hub work only**, and hand code changes to the cloud session as a `to:cloud` Issue rather
than making them. Do not commit or run the suites after every small change. Commit once, when the hub work
of an Issue is done. Cloud runs the suites (CI does as well) and merges. A change that has to reach the hub
is merged after the hub check on its Issue passes.

**The RM parity feed from HAI Dev to production Automation Map must not be disturbed without Gordon's
go-ahead.** `FEED_LOCK.json` pins every piece that decides what production's table 1 shows: HAI's capability
files and feed endpoint, the public `hai-capabilities.json` that production fetches from Automation Map's
`dev` branch on GitHub, and the Automation Map code that fetches and renders it. `tools/feed_guard.py` checks
it in CI on every push. A Claude hook in this repository's `.claude/settings.json` runs it after every edit
and asks Gordon before any publish. Re-pin only with his words: `python tools/feed_guard.py --approve
"<Gordon's go-ahead, date>"`.

Gordon lifted the cloud brief's "never commit to `dev`" (`docs/cloud-task-test-harness.md`) on 2026-10-06,
provided the feed stays protected: the cloud session may now merge off-hub work into `dev`. Code the hub
runs is merged after its hub check passes. Production installs update from `main`, not `dev`, with one exception:
production reads `public/hai-capabilities.json` from `dev`, and `FEED_LOCK.json` pins it.
