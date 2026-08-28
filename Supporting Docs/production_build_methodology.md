# Production build methodology

Agreed 2026-08-28 between Claude and Codex, at Gordon's request, covering two separate goals:

1. structured, permanent Dev diagnostics for troubleshooting;
2. a build step that strips developer commentary from what actually ships to `main`/HPM, since end
   users installing via HPM do not need the annotated Dev source's commentary.

**Status: design agreed, nothing implemented.** No work on this begins until the registry-finalization-
race correctness investigation closes (Steve's large C-5 hub `AM-TRACE` trace - see `BACKLOG.md`), and
only after Gordon starts the next phase himself. This file is the durable record of what was agreed, so
neither agent has to re-derive or re-litigate it in a future session.

## Dev diagnostics

A small, permanent, structured trace schema, replacing ad-hoc `AM-TRACE` log prose:

- scan/generation identifier;
- phase name and elapsed milliseconds;
- registry outcome;
- finalizer claim result;
- recovery decision;
- fixed error category only.

Same privacy rules already established for the telemetry work: no device names, app labels, room
names, hub identifier, local IP, or external token in any event. Diagnostic construction must
short-circuit before reading or formatting fields when disabled - no cost when off.

Development comments, trace explanations, internal notes, and diagnostic-only wording must never be
rendered or otherwise exposed to production users. Keeping that material in the annotated Dev source
does not authorize including it in the generated production artifact, its UI, exports, logs, or
telemetry. The production build and production-profile checks must fail closed if any allowlisted Dev
marker or diagnostic-only user-facing text survives generation.

Start with only this core list. Add a field only when a real troubleshooting case actually needs it.
A bounded, downloadable Dev-only diagnostic bundle (so a tester can send one coherent report instead of
screenshots from multiple log pages) is a later, separately reviewed increment - not part of the first
pass.

## Production builder

**First iteration is a feasibility spike on `apps/automation_map.groovy` only** - not the telemetry
driver, not CI, not branch protection. Only if the spike succeeds does a second phase add those.

Spike scope:

1. Pin one Groovy lexer/tool version locally.
2. Build a small fixture suite covering this file's dangerous lexical forms: slashy and dollar-slashy
   regexes, single/double/triple-quoted strings, GStrings containing embedded JavaScript comments,
   division operators, escaped delimiters, comment-like text inside string content.
3. Strip only actual Groovy comment tokens from a temporary candidate file - never regex-based, since
   `//` and `/* */` appear inside runtime string/GString content (URLs, embedded JS/HTML/CSS) that
   must survive untouched. Regex cannot safely tell a real comment from string content in this file.
4. Preserve every string/GString exactly, including embedded browser code.
5. Run the existing gates (`validate.ps1`, self-tests, `check_template.sh`, `groovyc`) against both the
   annotated Dev source and the stripped candidate.
6. Compare the non-comment executable token streams between annotated and stripped, accounting only
   for explicit production-profile substitutions (below) - proves comment removal did not alter
   executable Groovy tokens, which a key-matching sidecar check could not.
7. Demonstrate the builder produces byte-identical output when run twice from the same commit, and
   report measured byte/line reduction.

## Production header

Per Gordon's stated preference, the generated production artifact keeps only:

- the Apache licence/copyright notice (legally required);
- a short generated-file notice;
- the Dev source commit SHA;
- a link to the canonical, fully annotated Dev source.

The long architectural doc block at the top of the Dev source is NOT retained in the generated
artifact. The production README and the linked annotated source provide transparency without adding
the Dev commentary's byte cost to every HPM installation - the executable source remains fully
auditable even with no inline comments, since anyone can follow the link to the real thing.

## Production profile

One small, versioned, allowlisted substitution list (app identity, branch URLs, package identifiers,
driver import URL, build channel, diagnostic level, version/release metadata). Every expected
substitution must match exactly once in the source or the build fails. An unrecognized Dev identity,
URL, package ID, diagnostic setting, or release-metadata pattern must fail the build closed, not pass
through unmodified - matches this app's own existing design philosophy (visible failure over silent
guessing) rather than a best-effort transform.

Explicit build-profile constants, replacing today's implicit app-name-derived checks:

```groovy
@Field static final String BUILD_CHANNEL = 'dev'   // 'production' in the generated artifact
@Field static final int DIAGNOSTIC_LEVEL = 2        // 0 in the generated artifact
```

Normal functional code must not branch on these except for diagnostics, Dev identity, and explicitly
approved test-only facilities.

## Hub deployment runbook

Deployment, committing, pushing, and promotion are separate gates. Gordon's authorization must name
the intended target or profile. Permission to deploy Dev does not authorize a Git push, a production
deployment, or promotion to `main`.

### Preflight

1. Re-read the current branch, HEAD, working-tree status, relevant diff, and active queue claim.
2. Preserve unrelated changes. Confirm the source identity, version, and build channel before doing
   anything to the hub.
3. Run `validate.ps1`, `validate.ps1 -SelfTest`, `check_template.sh`, and `groovyc` with temporary
   compiler output. A failed or unavailable gate stops deployment.
4. Confirm no production and Dev scans will overlap during a performance or correctness test,
   especially on a large or older hub.

### Required Dev deployment method

- Use `deploy-hub.ps1`, not an MCP inline edit, browser paste, or improvised HTTP request.
- Use `-WhatIf` first for an unfamiliar source/target combination or after changing the deployment
  tool.
- The tool must resolve exactly one Apps Code target named `Automation Map (Dev)`, make a recoverable
  backup before writing, verify the saved SHA-256 against the local source, and confirm that the hub
  revision increased.
- The current tool is Dev-only. Never weaken or repurpose its exact-name protection for production.
- A production deployment requires a separately reviewed production-profile equivalent that targets
  the exact production Apps Code entry and deploys only the generated production candidate.

As of 2026-08-28, `deploy-hub.ps1` is still an untracked workspace file. It must be reviewed and
committed separately before this runbook can be considered portable beyond the current workspace.
Do not silently add it as part of a documentation-only change.

### Deployment evidence and smoke test

Record the branch and commit, or clearly identify a dirty diff, plus the local SHA-256, target Apps
Code ID, old and new hub revisions, backup path, and validation results. Redact hub addresses,
credentials, tokens, and private names before placing evidence in documentation or the queue.

After deployment:

1. Prove that the source read back from the hub has the exact local SHA-256.
2. Open the app settings page and confirm the expected name and version.
3. Run one controlled scan, then inspect the map, affected UI panels, AI export, telemetry, and the
   complete logs for that generation.
4. Treat a successful hub deployment as test evidence only. It does not authorize a push or release.

### Failure and rollback

Stop immediately on a wrong or ambiguous target, truncated source, validation failure, hash mismatch,
or unexpected revision result. Restore the exact pre-deployment backup when restoration is required,
then verify its hash. If a public `main` artifact is broken, first restore or revert the public artifact
to close the exposure, then investigate. Do not build optional test infrastructure during an active
incident unless it is necessary to restore safety.

## Telemetry assessment runbook

Telemetry is supporting evidence, not a conclusion. It can show version, topology counts, duration,
and fixed error categories, but it cannot by itself prove phase ordering, rule out a race, or explain a
duplicate finalization. Those questions require the complete correlated trace and hub logs.

### Correlation and interpretation

- Correlate the hardware model/code, firmware, app version and channel, apps/devices/nodes/edges,
  exact scan timestamp, and the same generation's structured trace.
- Distinguish the collector's `Received At` time from the scan's UTC timestamp and state the timezone
  when discussing either.
- Scan duration is measured from the generation/lock start through accepted completion. Do not derive
  it only from the visible `scan started` log line.
- A blank telemetry error field means no fixed error category was reported. It does not prove there
  was no timing race, stale callback, or duplicate completion.
- A telemetry delivery failure is separate from scan success. Assess both paths independently.

### Performance methodology

1. Record the first scan after a code reload or deployment as a cold run, not the baseline.
2. Allow the hub to settle, then collect at least three comparable warm runs with no overlapping
   production/Dev scan and no known competing workload.
3. Compare the median warm duration and its range, not one isolated row. Keep firmware, app version,
   topology, and test conditions comparable.
4. Stable counts support a functional comparison, but identical counts do not prove semantic graph
   equivalence. Count changes can also be legitimate when the hub configuration changed.
5. Label each conclusion as observation, hypothesis, or confirmed cause. One anomalous duration calls
   for correlation and repetition, not an immediate timeout patch.

For example, the 2026-08-28 C-8 sequence had identical topology counts and durations of 35 seconds,
then 59/47/43 seconds immediately around code reload activity, followed by 24/24/27 seconds. The
defensible reading is cold-run and environmental variance followed by a stable warm cluster. It is not
evidence of a fixed 2x regression or proof that timing is irrelevant.

### Evidence required for timing or completion faults

- Capture complete logs from endpoint entry or scan acquisition through terminal cleanup and any
  recovery callback, with exact timestamps.
- Include the entire structured trace for the same generation. A screenshot or telemetry row alone is
  secondary evidence.
- For a hang, duplicate completion, stale recovery, or registry-finalization report, retain the final
  claim result and recovery decision. Do not infer them from message ordering across different scans.
- Combine telemetry, trace evidence, the hub smoke test, and verified source identity before making a
  release decision.

Telemetry must remain bounded and privacy-preserving. Never send device, app, room, variable, or hub
names; a unique hub identifier; IP address or location; credentials or tokens; free-form logs; or
free-form error text. Use only the documented fixed categories and aggregate counts.

## Promotion invariant

Annotated Dev source is never pushed to `main` and cleaned up afterward - there is no such workflow.
Sequence:

1. Freeze and tag the exact tested Dev SHA.
2. Generate a production candidate from that SHA into a temporary directory or release worktree, never
   directly into `main`.
3. Run every gate above; record annotated size, production size, reduction percentage, token
   fingerprint, and source SHA.
4. Deploy the generated candidate to Gordon's production test instance via `deploy-hub.ps1` (or its
   production equivalent) and verify exact SHA-256.
5. Complete a production-profile smoke scan and UI/export checks.
6. Open/review a promotion PR containing only generated production artifacts and release metadata.
7. Once CI/branch protection exist (second phase, not the spike), `main` rejects hand-edited or
   non-reproducible generated artifacts.
8. Merge only after Gordon's explicit production approval.

## Open questions for the second phase (not the spike)

- Whether Groovy lexer token equivalence, once proven on this file, generalizes cleanly to the
  telemetry driver.
- Whether CI/branch-protection infrastructure is worth setting up for a project this size, once the
  spike's real effort is known.
- Whether the diagnostic bundle increment is worth building, once the core event schema has actually
  been used for real troubleshooting.
