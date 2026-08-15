# Automation Map - Development Handoff

Cleared 2026-08-15. Previous contents (Remote Admin routing fix through the
Hub Variable lineage spec, phases 1-6) are in git history:

    git show e8d79cb:handoff.md

---

## 1.9.0 checkpoint, 2026-08-15 - Hub Variable lineage

**Status: local only.** Pushed to Gordon's dev hub (app instance 2976) and
verified live. Not pushed to `dev` on git - explicitly held back pending
JimB confirming 1.8.9 (the Remote Admin fix) is solid on his end first.

Local code location (not in this repo's git history yet):

    C:\Users\gordo\Downloads\Hubitat Apps\hubitat-automation-map\apps\automation_map.groovy

`APP_VERSION` and `packageManifest.json` both already read `1.9.0` locally.

### What 1.9.0 is

Hub Variables now appear on the map as their own thing. Before this, if
Rule A set a Hub Variable and Rule B read it, that connection was invisible
- the exact kind of dependency this app exists to catch, missed. Now a Hub
Variable draws as a triangular node, with directional edges showing which
rules write to it and which read from it.

### How it was built - six phases, each checked against real Rule Machine data before writing code

1. **Investigation** - pulled the raw internal state for `_Test Variables`
   and found the actual field names Rule Machine uses (`xVarV.<n>` for a Set
   Variable target, `customDev.<n>`/`tCustomAttr.<n>` for its
   device-attribute source).
2. **Execution decoder fix** - the flow popup went from a vague "Set
   Variable / Hub Information" to "Set Hub Variable TestHubUptime from Hub
   Information.formattedUptime."
3. **WRITE relationships** - `TestHubUptime` became a real graph node, with
   a write edge from the rule that sets it.
4. **READ relationships** - extended to conditions (`rCapab_`/`xVar_`),
   confirmed by `_Test Variables Extended`, which reads and writes the same
   variable - the spec's hardest case, both directions kept as separate
   edges rather than collapsing into one.
5. **Trigger, free text, Required Expression** - three more ways a rule can
   reference a variable, each confirmed against a real fixture Gordon built
   and exported via Rule Machine's own Export/Import/Clone feature.
6. **Hardening** - pivot table wired up (`Rule -> Hub Variables`,
   `Hub Variable -> Rules`), plus two real bugs found once tested against
   the whole hub instead of just the test fixtures:
   - Rule Machine's own reserved notification tokens (`%device%`, `%time%`,
     `%date%`) were being fabricated into fake Hub Variable relationships on
     real rules like `Barking` and `Perimeter Closed`. Fixed by requiring
     any free-text match to be independently confirmed elsewhere on the hub
     via a structured reference before it is drawn.
   - A rule whose only relationship was to a Hub Variable was being
     mislabelled "inert" (no device or rule relationship). Fixed to match
     what actually gets drawn.

Bonus finding along the way: `Overloadcount`, a real pre-existing Hub
Variable dependency on Gordon's hub (`_Overload` rule), surfaced without any
special-casing - the strongest evidence the feature works on data it was
never built around.

### Rule Machine extraction gating - fixed 2026-08-15, after external review

`extractHubVariableWrites`/`extractHubVariableReads` were running against
every installed app, not just Rule Machine. The structured fields (`xVarV`,
`rCapab_`/`xVar_`, `tCapab`/`xVar`) were never a real risk - reverse-engineered
from Rule Machine's own internal naming, no other app type would coincidentally
use them. The free-text scan was the actual exposure: its hub-wide
confirmation check only looks at the *name* matched, not which app it came
from, so an unrelated app whose own text happened to contain a confirmed
Hub Variable's name would have picked up a false read edge.

Fixed by gating both extractors to `"${out.type}".startsWith('Rule-')` in
`fetchAppRelationships()` - matching the existing engine check already used
elsewhere in the file (~line 663) for the same "any Rule Machine version"
purpose, rather than `SUPPORTED_RULE_ENGINE`'s exact-version pin. Pushed to
hub (revision 52).

### What's still open

- Condition-read vs. trigger-read vs. plain-read all currently collapse into
  one generic `read` edge kind - the spec wants them distinguished
  (`READ + CONDITION`, `READ + TRIGGER`).
- Phase 6's scale/removal checks not empirically tested: readability with
  many Hub Variables (only `TestHubUptime`, `TestConcat`, `Overloadcount`
  exist so far), and whether a removed variable reference correctly
  disappears on the next full scan.
- Variable Connectors (spec Section 12) not addressed at all.
- `README.md` credits JimB as primary tester and functional requirements
  contributor - not yet pushed to git alongside everything else.

### Next step

Waiting on JimB to confirm 1.8.9 works for him before any of this - code,
manifest, or README credit - goes to `dev` on git. Gordon is drafting the
message to Jim now; teaser screenshots (pivot table showing `_Overload`/
`Overloadcount`, the map view with a Hub Variable node, a flow-decode popup)
still need to be captured from the live hub.

### Codex / Claude coordination, 2026-08-15

Codex inspected the worktree after Claude's handoff and confirmed that this
Codex task has made no edits in this repository. The current uncommitted 1.9.0
changes are a single existing body of work and should be preserved as-is.

Collision-free ownership for the next pass:

- **Claude:** own the empirical Phase 6 verification only: test map readability
  with several Hub Variables and verify that removing a variable reference makes
  its relationship disappear after the next full scan. Record fixtures, observed
  results, hub/app revision, and any defects here. Do not edit
  `apps/automation_map.groovy` while this verification is in progress.
- **Codex:** no active implementation work is underway. Code changes remain
  paused pending Claude's verification results and JimB's 1.8.9 confirmation.
- **Unassigned:** edge-kind separation for condition/trigger/plain reads and
  Variable Connectors (Section 12). Assign explicitly before editing because
  both are likely to touch `apps/automation_map.groovy`.

If verification exposes a defect, document the smallest reproducible case here
before either session changes code, then explicitly hand ownership of the fix to
one session.

### Phase 6 verification (Claude), 2026-08-15

**Status: verified against existing live data only.** No new fixtures created -
Gordon steered away from expanding live-hub test data this pass ("that seems
too much"). Scale and removal-on-rescan remain untested; see below.

**What was checked.** Read the live scan already cached in app instance 2976's
`appState`: `graphVersion` 5, `scanHeartbeat` 2026-08-15 16:17 local (Perth),
`scanDone` 95/95, `scanError` null, `compatOk` true, running app version 52 -
a clean, complete, current run against the gating fix already on the hub.
No new scan was triggered; this is the same run `handoff.md` already
described as "Pushed to hub (revision 52)."

- 3 Hub Variables exist on the hub (`TestHubUptime`, `TestConcat`,
  `Overloadcount`), producing 9 edges across the 5 dedicated test rules plus
  the pre-existing `_Overload` rule.
- Cross-checked each test rule's `hubVarWrites`/`hubVarReads` against the
  graph's rendered edges - all 5 mechanisms (structured write, structured
  read via condition/trigger/Required Expression, free-text read) produced
  exactly the edge each rule was built to exercise. No mismatches.
- `_Test Variables Extended` (2984), the both-directions case: write and read
  to the same variable render as two separate edges, not collapsed into one -
  matches phase 4's stated goal.
- `_Overload` (2100) issues two `getSetVariable` writes to `Overloadcount` in
  its raw settings; the graph correctly collapses them to a single `write`
  edge rather than drawing a duplicate.
- `_Overload` also exercises the reserved-token guard on a real production
  rule, not just a fixture: its `hubVarReads` includes `{variable: 'time',
  confirmed: false}`, and that entry does not appear anywhere in the
  rendered graph - the exact behavior the confirmation mechanism is supposed
  to produce.
- Client-side rendering code (`groupColors`, `GROUP_LABEL`, the pivot-table
  row/col options, the triangle shape) all reference `hubVariable`
  consistently - checked by reading the source, not by rendering, since the
  Browser tool is documented as blocked against 10.0.0.125. The map's actual
  on-screen legibility was not visually confirmed.

**What's still open, and why.** Only 3 Hub Variable nodes exist against 312
total graph nodes - too small a sample to say anything about readability
with "many" of them, and confirming an edge disappears on removal needs an
existing rule edited and rescanned. Both are live-hub writes, and neither was
attempted this pass. Both remain valid future verification work whenever new
fixtures get built for another reason, or if Gordon wants to spend the time
on it later - not something to re-request proactively.
