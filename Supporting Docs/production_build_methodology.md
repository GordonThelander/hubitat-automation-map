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
