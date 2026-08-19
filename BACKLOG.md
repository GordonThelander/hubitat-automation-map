# Backlog

Not shipped with the HPM package. Items here are agreed ideas awaiting a decision to build, not commitments.

---

## Urgent - v2.0.0 release blockers

Genuine defects affecting the v2.0.0 release, not feature requests. These jump the queue ahead
of the prioritised candidates below.

None open currently.

---

## Prioritised post-v2 candidates

Priority means ordering for investigation and delivery, not a commitment. P1 items have the
clearest user value and smallest unresolved design questions. P2 items are useful but larger
or dependent on P1 foundations. P3 items need product or data-model decisions before code.

### P1 - Preserve the map layout when returning to the full map

**Source:** JimB functional review, 2026-08-16.

The unconnected-app shelf at the bottom has shipped and should remain. The remaining request
is that drilling into an app/device and returning to the full map restores the same node
layout, without physics moving everything into a new arrangement.

Acceptance criteria:

- Capture the full-map node positions after layout settles.
- Restore those positions after drill-down/filter navigation and Exit to whole map.
- A rescan may legitimately create a new layout; ordinary navigation may not.
- Verify on a large live map, not only a small fixture.

**Attempted and reverted, 2026-08-18.** A working version shipped to the hub (within-session
position restore, cross-session persistence via a new `/map-layout` endpoint, and a follow-on
"Reset Physics" button Gordon asked for once a layout could get stuck). Reverted at Gordon's
request after the Reset Physics addition turned into repeated live debugging with no working
result - not because the underlying idea is bad, but because it burned real time without
landing. Whoever picks this up again should read `vis-network.js` (unminified) directly before
touching physics/stabilization code, not experiment against the live page first:
`network.stabilize()` calls `_blockRedraw` at the start and only `_allowRedraw` once every
iteration is done, so it is architecturally incapable of ever showing a visible settle no
matter how `stabilization.updateInterval` is tuned - it renders the final result once, nothing
in between. A visible animated settle needs the normal continuous simulation path
(`network.startSimulation()`, which drives the real per-frame render loop) instead, listening
for the `'stabilized'` event it fires on natural convergence - confirmed by reading
`PhysicsEngine.startSimulation`/`simulationStep`/`_emitStabilized` in the source, not assumed.
Revisit only with that groundwork already done.

**Closing note, 2026-08-18: no separate button needed.** The existing "Show all" button already
does this. `exitToWholeMap()` -> `applyFilters()`'s unfocused branch calls `settle()`, which
only ever does `network.setOptions({physics:{enabled:true}})` and waits - it never calls
`network.stabilize()`. That means it was always running the visible continuous-simulation path
described above, not the hidden batch one, the whole time. Gordon confirmed live that "Show
all" already gives the jiggle-then-settle behaviour this item and the reverted Reset Physics
button were both trying to build. The remaining, narrower ask above (return to the *same*
layout rather than a freshly re-settled one) is still open, but a dedicated reset-to-fresh-
layout button is not - "Show all" already is one.

### P1 - Make the legend accurate and usable on smaller tablets

**Source:** JimB functional review, 2026-08-16.

The legend must not claim that a device's colour is determined by how it connects to an app;
one device can have different roles in different apps. Device identity is now represented by
icons and relationship meaning by edge colour/style. Rewrite the legend around that model.

Acceptance criteria:

- Audit every legend sentence against the actual node/icon/edge rendering rules.
- On smaller tablets, either use a shorter legend or give it a bounded scroll area.
- Ensure the legend does not obscure the controls, map, or open panels.
- Confirm the result at desktop and tablet widths.

### P1 - Picklist exclusion controls

**Source:** JimB functional review, 2026-08-16.

Provide a practical way to remove noisy apps/devices (Maker API was the example) from the
display without changing the underlying scan. Start with all relevant items included and let
the user uncheck individual entries.

This is deliberately narrower than the multi-selection subgraph request below: exclusion is
useful on its own and can establish the selection-state model without committing to the more
complex graph semantics in one pass.

Acceptance criteria:

- Each listed app/device has a clear included/excluded state; all begin included.
- Excluding an item updates the visible graph but never deletes scan data.
- Reset restores all entries.
- Search/filtering the picklist does not lose selections outside the current search result.
- Decide explicitly whether selections persist only for the page session or across reloads.

### P2 - "Community Utilities" button on the map page

**Source:** Gordon, 2026-08-18, following review of the HPM Manifest Crawl Community Utilities
context in `handoff.md`. Screenshots supplied: the site's green "Open the project repository"
button as the colour reference, and the button bar (Insights / External systems / Pivot tables
/ Device icons / Export JSON / Exit map) with the new button's position marked between Export
JSON and Exit map.

Would link to <https://gordonthelander.github.io/HPM_Manifest_Crawl/>, styled in the site's
green, `target="_blank"` (confirmed with Gordon - opens in a new tab, the map page is
mid-session state that should not be lost).

Open design question before building: link to the bare homepage as shown, or to something more
specific and contextual - the Identity Resolver for an unmatched identity, or the Package Feed
- per the "Optional future integration posture" already on record in `handoff.md`, which
proposed targeted links over a homepage link. Does not conflict with that note's registry
guardrails (a navigation link creates no registry/matcher dependency), but is a genuine
product-framing decision worth making deliberately: every other button in that row is
self-contained (opens a panel, or acts on data already loaded in the page); this would be the
first to take the user off the Hubitat-served map entirely, to a separate site Automation Map
does not control. Not a functional risk, the app works identically without it - just a
different kind of button than anything else currently in that row.

### P2 - Multi-select an interconnected subgraph

**Source:** JimB functional review, 2026-08-16.

Allow several apps and/or devices to be selected together and show the relationships between
the selected items. Do not assume that "between" includes every neighbour: define whether
the result is the induced subgraph (selected nodes and edges whose two endpoints are selected)
or also includes connector nodes required to make a path intelligible.

Acceptance criteria:

- App and device selections can coexist.
- The displayed graph has a documented, predictable inclusion rule.
- Empty, disconnected, and very large selections have understandable feedback.
- Existing single-item drill-down and browser Back behaviour continue to work.

### P2 - Group and identify items in the picklists

**Source:** JimB functional review, 2026-08-16.

Make long lists easier to scan. Show a device/app icon where the browser control permits it;
if native option elements cannot render icons consistently, use an accessible custom list or
retain text rather than a browser-specific trick.

Grouping proposal to validate with Jim before implementation:

- Apps: parent app or integration first, then its children alphabetically.
- Devices: group by the app/device category Jim intended; clarify whether this means room,
  icon category, owning app, or a separate Apps/Devices split before coding.
- Preserve search across groups and expose group names to assistive technology.

### P2 - Extend the displayed map by one level

**Source:** JimB functional review, 2026-08-16.

From a reduced map, let the user select a node at the visible edge and add its directly
connected neighbours. An **Extend one level** action is preferable to silently expanding on
selection because it makes the scope change deliberate and reversible.

Acceptance criteria:

- One action adds only immediate neighbours and their connecting edges.
- Repeating the action extends one additional hop.
- Newly added nodes are visually distinguishable long enough for the user to understand what
  changed.
- Back/undo restores the preceding scope without rebuilding the full layout.

### P2 - Local Variables are drawn as Hub Variables

**Source:** JimB testing, 2026-08-17. "Local Variables are identified as Hub Variables. Not
sure that we need to see Local Variables."

Investigation needed before deciding the fix: confirm whether the scanner is genuinely
conflating Hubitat's Local Variables with Hub Variables (a labelling bug), or whether Local
Variables are correctly detected but there is no case for showing them on the map at all (a
scope decision - Local Variables are private to one app, so a graph about cross-app
relationships may have nothing useful to say about them). Resolve which before touching code.

### P2 - A single icon per Hub Variable, so all of its connections are visible at once

**Source:** JimB testing, 2026-08-17. "Hub Variables should be identified by a single icon so
all connections for each Hub Variable can be seen."

Underspecified - needs clarification with Jim before design: is this about visually
distinguishing Hub Variable nodes from other node types at a glance (an icon convention, like
device icons), or about being able to select one Hub Variable and see every rule that reads or
writes it highlighted together (a selection/highlight behaviour)? The acceptance criteria differ
substantially between the two readings.

### P3 - Search Hub Variables

**Source:** JimB testing, 2026-08-17, offered as "a future idea", not a request against
v2.0.0. Add a search box to whatever Hub Variable listing/panel exists, matching the
search-box pattern already used for the app/device filter dropdowns.

### P3 - Hide a branch or everything downstream

**Source:** JimB functional review, 2026-08-16.

This needs a definition before implementation. The graph is not a simple tree: cycles,
shared devices, rule-to-rule links, Hub Variables, ownership edges and direction-dependent
roles make "downstream" ambiguous. Hiding a branch must never imply that shared nodes have no
other relationships.

Design questions:

- Which relationship kinds and directions count as downstream?
- Does a shared node remain when another visible path reaches it?
- Is this a reversible view operation, and how is hidden state disclosed?
- How does it interact with exclusion, multi-select and one-level extension?

### P3 - Export as a warm-start cache, not a portable full rebuild

**Source:** Gordon, 2026-08-19, discussing whether the AI export could be extended into a full
system backup that another instance of the app could read to rebuild the map with no
discovery/scan at all.

Discussed and postponed rather than scoped - real product decisions needed first:

- The `d`/`a` node IDs in the export are almost certainly Hubitat's own numeric device/app IDs,
  which are specific to the hub that issued them. That means an export can only ever warm-start
  *the same hub's* Automation Map instance (skip a rescan after a reinstall/corruption), not
  rebuild the map on a genuinely different hub - a fresh hub's recreated apps/devices get new
  Hubitat-assigned IDs, breaking the mapping. True portability would need Hubitat's own hub
  backup/restore to recreate the apps and devices first; that is out of this app's scope.
- The current export deliberately excludes raw `appSettings` and executable config (see the
  Rejected section below and `Supporting Docs/ai_export_spec.md` section 2) - that is what
  makes it safe to hand to an AI. A restore capable of a genuine rebuild would want enough raw
  state to re-decode with a *future*, improved decoder, not be frozen at whatever the decoder
  understood on export day. That is a different, more sensitive tier of data than the
  AI-facing export, and probably a separate artifact/mode rather than one expanded document.
- Recommended framing if this is ever picked up: import an export as a *starting cache*, then
  run the normal scan as a diff/verify pass rather than promising zero discovery ever - self-
  heals drift between the backup and the live hub, and avoids silently trusting a stale decode.

Not scoped further than this; revisit only with those three questions actually decided.

### P3 - User-authored notes/tags on apps and devices

**Source:** Gordon, 2026-08-19, discussing what else the AI export could carry to let an AI
advise rather than just describe topology. Every other candidate discussed alongside this one
(schedule/mode-HSM/notification summaries, a capability-vs-usage-gap insight, external-
dependency blast radius) is inference from structure the app already has. This is the one
gap none of that closes: why a node exists or what the user actually wants, which is not
derivable from the hub at all.

Two precedents already exist to build on rather than starting fresh:

- The Device Icons panel already has a `note` field, wired end-to-end through UI, saved state,
  and the export schema (`deviceIconOverrides[].note`) - but scoped narrowly to "why did I
  override this icon", and devices only, not apps. Generalising it (usable without also
  setting an icon override, and available for apps too) is a smaller lift than a new field.
- External systems and Pivot tables already prove the pattern of an editable table panel whose
  edits persist server-side and appear in the export.

Two UX shapes discussed, not mutually exclusive - recommended to share one backing state
rather than build as two separate features:

- Quick-add at the per-node click-through panel (flow/Insights/inert panel) - low friction,
  capture a thought while already looking at that node.
- A table view, a 5th panel button alongside the existing four - better for reviewing/bulk-
  editing everything written so far.

Storage should follow the External systems/Device Icons convention: server-side app `state`
with GET/SAVE ajax endpoints, not `localStorage` - notes need to survive a browser change and
appear in the export, the same reason those two already work that way.

Not scoped further than this; needs its own design pass (quick-add panel layout, table panel
layout, and the state shape both would read/write) before implementation.

### P3 - Associate Hub Variables with Variable Connector devices

**Source:** JimB functional review, 2026-08-16; Hub Variable lineage specification section 12;
reconfirmed in JimB's 2026-08-17 testing pass (connector devices still shown unconnected).

Named, unique Hub Variable nodes and rule read/write edges have shipped. The remaining request
is to connect each Hub Variable to its associated Variable Connector device without drawing
two independent logical lineages for the same value.

Investigation required before implementation:

- Determine the authoritative Hubitat data linking a connector device to a Hub Variable.
- Define a distinct relationship kind and direction.
- Test renamed, deleted and duplicated/stale connectors.
- Decide whether the connector remains a normal device node, becomes an alias, or is visually
  grouped with the variable. Native Hub Variable support must not be blocked by this work.

---

## Recently shipped (removed from Proposed)

- **Scheduled full scan:** shipped in 1.9.5. Runs daily by default at 00:30, is configurable,
  can be disabled, and uses the existing scan guards.
- **AI-friendly Export JSON:** shipped in 1.9.5 and hardened in 1.9.6. The user-driven file
  export is structured, self-describing and privacy-labelled. Direct in-app AI/API or future
  MCP integration remains a separate product decision, not an extension silently implied by
  the export.
- **Scan-status self-heal:** shipped in 1.9.7. A scan that finished reading every app/device
  but never finalized (a scheduled Hubitat call silently failing to fire - hit live by JimB and
  Gordon, 2026-08-17) could look stuck for minutes until the settings page was manually
  reopened. `/scan-status` now runs the same recovery on every poll, so any status check can
  un-stick it, not only a settings-page reload.

---

## Rule Machine 5.1 documentation (written, awaiting publication)

**"Reverse-engineering the Rule Machine 5.1 storage format"**,
`Supporting Docs/rule_machine_5_1_storage_format.md`. A community publication, not an app
feature. Gordon will publish when he judges it ready; no deadline.

**This is a living document. Add to it whenever Automation Map work turns up something new
about how Rule Machine stores a rule, without being asked.** Anything learned while
extending the decoder belongs there, and the open questions below are the specific gaps a
future finding might close.

### Current state

1055+ lines, complete and internally consistent, and kept current as new findings land
(most recently section 13, added 2026-08-15: writing, reading via condition/trigger/Required
Expression, the trailing-period artifact, free-text `%Name%` interpolation, and the
`%device%`/`%time%`/`%date%` reserved-token trap that produced real false positives on live
rules). Contains:

- A method section: rule page as ground truth, differential reading, corpus-wide checks
  across all rules, building a working decoder so misreadings render as visibly wrong
  flowcharts, constructing test rules where the corpus had gaps, and checking official docs
  before claiming novelty. Also states what was *not* done.
- Evidence markers on every claim: `[invariant]` `[strong]` `[limited]` `[single]`
  `[heuristic]` `[unknown]`, so a finding holding across the corpus is distinguishable from
  one resting on a single sample. Section 13's Hub Variable findings are marked lighter than
  sections 1-11's - a handful of deliberately-built fixtures, not the 38-rule corpus.
- A framing boundary: the stored representation holds enough to **reconstruct** a rule, but
  not necessarily enough to **execute or reason about** one. Reconstruction is checkable
  against the rule page; evaluation is not.
- A security warning that `appSettings` returns every setting an app holds, including other
  apps' tokens and credentials, so nothing should be logged or exported wholesale.
- A worked example decoding one rule end to end, which also demonstrates the
  `eventSubscriptions` snapshot trap live.

Reviewed externally 2026-08-12; review incorporated. Two review points led to better
findings than the review proposed, both now in the document: the 13 action-object shapes,
and the correction that expressions must not be assumed to evaluate left to right.

### Open questions, any of which a future finding could close

Tracked here so a discovery is recognised as closing something rather than passed over.

- **Expression grammar.** Explicit grouping or parentheses, NOT, whether `eval[n]` can
  reference another expression rather than a bare condition, and operators beyond AND/OR.
  All `[unknown]`.
- **Evaluation order beyond four terms.** Two live tests (three-term and four-term) both
  point to right-associative grouping and rule out conventional precedence, OR-binds-first,
  and left-to-right. Not yet separated from conventional precedence with full certainty -
  a case like `F AND F OR T AND T` (conventional TRUE, right-associative FALSE) would settle
  it outright. Untested with NOT or more than one OR.
- **Repeat and while.** Every rule carries state for them (`hasWhileRule`, `inRepIf`,
  `nestedRepIf`, `blockIf`) but no rule on the hub uses them, so their action-level markers
  are unobserved. The IF family is therefore known to be an incomplete grammar.
- **Rule Function discriminator.** One sample only. Reports identically to an ordinary rule
  in every field examined, which is absence of evidence rather than evidence of absence.
- **Bulk app enumeration.** Resolved in practice - `/hub2/appsList` returns the complete
  installed-app tree and is now unioned into discovery (dev 1.8.0) - but worth a note in the
  document itself if not already there, since the earlier text claimed no such endpoint
  existed.

### Why storage rather than execution

Checked the official docs before writing anything, and most of the originally planned
execution document would have restated what Hubitat already publishes. The
[Rule 5.1 page](https://docs2.hubitat.com/en/apps/rule-machine/rule-5-1) already covers, in
its own words: required expressions "typically remove the trigger event subscriptions when
false"; Run Rule Actions is "not identical to a triggering of the other rule" including the
Cancel-pending interaction; a delayed action "affects only that action, and not subsequent
actions"; re-triggering "does not cancel previously scheduled delays" by default; and the
"Multiple simultaneous rule execution error", including that platform 2.3.8.186 fixed
top-level conditionals.

Storage format is documented nowhere, and the Automation Map work is the primary source for
it. That is the piece worth publishing.

`Rule_Machine_5_1_Execution_Explained_Draft.md` is retained as the superseded input.

### Two loose ends worth a separate short post

- **"Ignore trigger events while running" is still fully documented** on the Rule 5.1 page,
  while Bruce stated in June 2025 that it had been removed. No corresponding setting was
  found across the rule corpus, which is suggestive but not proof since an unused option
  may store nothing. Opening any rule and looking for the toggle settles it. Either a stale
  doc or a misremembering, and worth reporting either way.
- **Rule Functions are entirely undocumented officially.** Zero mentions on the Rule 5.1
  page and zero on the Rule Machine index page; the only source is Bruce's forum post. A
  practical guide would fill a real void, and there is now hands-on material: a Rule
  Function reports itself identically to an ordinary rule, and normally references no
  devices at all.

---

## Rejected

### Auto detection of external endpoints from app settings

Investigated 2026-08-09, rejected. Roughly 40 percent precision
on a naive URL and host heuristic, and it misses exactly the integrations where a
dependency map would be most useful. Source grepping does not rescue it: `importUrl`
points at GitHub in every app, documentation links and commented out endpoints produce
false positives, and built in apps expose no source at all.

### Showing an app's non-device settings and scheduled jobs in the focused panel

Investigated 2026-08-09, rejected. Cheap and generic, but noise on Rule Machine rules,
which carry dozens of internal settings such as `state_16`, `actSubType.1` and `ctL.1`.

Note that `scheduledJobs` is complete and authoritative, unlike external endpoints, so a
time or schedule node class would be reliably correct wherever it appeared. Not pursued,
but a better foundation than external detection if the "what drives this app" gap is
revisited. (Partially superseded 2026-08-14: scheduled-job detail is now shown for *inert*
apps specifically - next run time and raw cron, no English interpretation - which is a
narrower and safer version of this than showing every app's schedule in the focused panel.)

### Device-mediated rule-to-rule links (closed 2026-08-14, was "proposed, deferred")

Direct rule-to-rule links shipped in 1.3.3 and were substantially extended afterward -
`ruleActMain`/`privateF` alias recognition, honest kind naming (`cancelTimedActions`,
`pauseResume`), broken-reference detection surfaced in Insights. What was never built, and
is now closed rather than left open: inferring a link through a shared device - Rule A
writes a virtual switch, Rule B triggers on it, therefore A leads to B.

That inference is also where every false positive would live. The original design
(`Supporting Docs/hubitat_automation_map_rule_to_rule_implementation.md`) was roughly five
times bigger than what should be built - 15 edge types, 5 phases, cycle detection,
feedback-loop oscillation analysis, three separate semantic JSON files, confidence values
quoted to the percent. If this is ever revisited, the discipline that must survive any
trimming is on record in that document and must not be skipped:

- A condition is not a trigger: TRIGGERS_VIA versus INFLUENCES_VIA.
- Value matching: Rule A sets switch X on, Rule B triggers on X off, therefore no edge.
- Key on `deviceId + attribute`, never `deviceId` alone.
- Required Expressions never produce trigger edges.
- POSSIBLE_RELATIONSHIP when subscription versus read cannot be established.

Honest limitation, already documented in the README: only Rule-5.1 is analysed. Room
Lighting, Basic Rules, Simple Automation and webCoRE show no links rather than showing that
they have none.

### Integration registry as a backlog item (closed 2026-08-14 - shipped, not rejected)

The design discussed under this heading - a user classification table on the map page
listing every app type, four-field classification (system name, kind, criticality), backup
via browser file download/upload, registry fetched from `main` with cache and embedded
fallback - has shipped. It lives in the app as the External systems panel
(`externalsGetMapping`/`externalsSaveMapping`, `fetchRegistry()`), backed by the registry
built and maintained in `GordonThelander/HPM_Manifest_Crawl`. Removed from backlog because
it is no longer proposed work; the code and that repo's own history are the record of it
now, not this file.
