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

**Do not re-verify what is already recorded.** CI runs every suite on Groovy 2.4 on each push, and the
cloud session's work of 2026-10-06 is described in its Issues and PRs. A local session does only the hub
check its Issue names, and reports that result. It does not re-run suites, re-audit merged or pushed work,
or retest anything without new contrary evidence (the binding rules above).

**Local sessions keep doing all hub work** (Gordon, 2026-10-07): deploys, scans, reading the hub, measuring
devices, recapturing decode files, and the on-hub checks Issues ask for. None of that is restricted.

**Code is written once, by the cloud session.** Four times on 6-7 October a local session wrote code the cloud
session was already writing on a branch, and the two collided or duplicated. So `tools/local_code_guard.py`, a
hook in `.claude/settings.json`, refuses a local session's edit to a code path (engine/lib, engine/test,
engine/app, apps/, tests/, tools/; fixtures excepted) without prompting Gordon. Describe the change on a
`to:cloud` Issue, or as a comment on the cloud session's open PR for that area: a PR comment wakes the cloud
session within seconds, an Issue comment does not. Before touching any area, check `gh pr list` for an open
cloud PR on it.

**Measurement on the hub, decoder in the cloud** (agreed on Automation Map PR #3, 2026-10-07). A local session
builds probe rules and measures, then posts the measured storage shape and the observed behaviour on the
cloud PR for that area. The cloud session writes the decoder and HAI's converter from it, usually within
minutes, because a PR comment wakes it. Claude HAM did exactly this for Wait for Events.

**Standing permissions** (Gordon, 2026-10-07 - granted once; do not ask him again for any of these):
- Local sessions push a capture to the frozen PR branch they were asked to check. They do not push to `dev` or
  `HAI_Engine_Dev` (see "One change, one hub cycle" below).
- The cloud session pushes to its branches, opens PRs, and merges its own PRs into `dev` / `HAI_Engine_Dev`
  once CI is green and the hub check on the Issue has passed. On Automation Map that merge is the HPM Dev release.
- Deploying to the Dev hub, reading anything, running tests, and everything technical agreed between agents.
Gordon is asked only for: production releases (`main` / `preprod`), changes to the parity feed production shows,
and destructive hub actions (reboot, firmware, radios, HSM, sirens, garage). Nothing else.

**Settle it between agents; Gordon is not the relay** (Gordon, 2026-10-07). Technical questions - who writes
what, resolving a collision, what a measurement means, which build goes to Dev - are agreed between agents on
the PR or Issue. Gordon is asked only for what the standing permissions below leave him, and for genuine product
decisions, once, in your own chat, with the decision stated.

**Nothing wakes a local session.** A PR comment wakes the cloud session; a local session is woken by nothing,
so after asking anything it polls for the answer or binds the PR. Asking is the start of a wait, not the end of
the task. Channels in one page: `docs/AGENT_CHANNELS.md` in the HAI repository.
**An idle local session polls** (Gordon, 2026-10-07: both sat asleep while work waited). When a local session has
nothing in hand, it runs `/loop 10m` over the inbox (HAI PR #34) and any open PR naming it, and picks up what is
addressed to it. It never ends its turn idle without that loop running.

**One change, one hub cycle** (Gordon, 2026-10-07: batch, and stop pushing the smallest changes).
`dev` and `HAI_Engine_Dev` change only through merged pull requests. On Automation Map a GitHub ruleset refuses
any other push to `dev`; on HAI, `.github/workflows/dev-push-guard.yml` undoes one and a local hook refuses it.
1. The cloud session batches a change on one PR, bumps the version itself (`APP_VERSION` with the manifest,
   or `ENGINE_APP_VERSION`), and comments **frozen** with the full SHA. It pushes nothing to that PR after.
2. The local session deploys exactly that SHA, runs the hub check the PR names, and pushes the capture, once,
   to that PR branch. Nothing else: no code, no suites, no version commits.
3. The cloud session merges. If it must change code after a freeze, it says so on the PR first, and that
   means a new deploy and capture.

**The cloud session cannot see local commits.** What is on the hub must be on GitHub: the deploy's PR
comment names the full SHA that is on the hub, and that SHA is the frozen one on the PR.

**`dev` is the HPM Dev channel; a merge into it is a release** (Gordon, 2026-10-07). Testers get the app on `dev`,
offered when `packageManifest.json`'s version rises. So any change to `apps/` reaching `dev` raises `APP_VERSION`,
the manifest's `version` and `apps[0].version` together, and starts the release notes with that version.
`tools/release_guard.py` fails CI on a push or PR to `dev` that does not (`.github/workflows/release-guard.yml`).
It exists because the cloud session merged PR #2 as 2.4.4 without a bump; 2.4.7 corrected it.

**Hub data stays private** (Gordon, 2026-10-08). This repository is public; the HAI repository is private. Anything describing Gordon's home goes only to the HAI repository: rule names and numbers, device names, modes, captures, decode files, screenshots, hub addresses and measurements. Post it on the HAI Issue for that work, or on the inbox, HAI #34. A PR here carries code, the version, the frozen SHA and a link to that HAI comment, nothing more. Its hub check is reported on HAI, and a capture is committed to the HAI repository, never to a branch here. When a public example is needed, invent one ("Hall Light", "Front Door").

**Concord is local only.** Codex and the local Claude sessions talk live through it, but the cloud session
cannot see it. So anything that hands work over, records a hub result or needs Gordon's decision goes into
an Issue, never only into Concord.

Local sessions: **do hub work only**, and hand code changes to the cloud session as a comment on its PR rather
than making them. Do not commit or run the suites. The one commit a local session makes is the capture, step 2
above. Cloud runs the suites (CI does as well) and merges. A change that has to reach the hub is merged after
the hub check on its Issue passes.

**Documentation exception** (agreed on HAI PR #34, 2026-10-07, at Gordon's request): a local session may also commit and push to `Supporting Docs/` and `docs/` only, on its own branch (`ham/docs-<topic>` or `hai/docs-<topic>`), and open a PR to `dev`. The cloud session reviews and merges it. Measurements are written down by the session that made them. Nothing else changes: no code paths, no pushes to `dev` / `HAI_Engine_Dev`, no version bumps.

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
