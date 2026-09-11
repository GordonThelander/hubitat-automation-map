# Automation Map backlog

This file tracks agreed work that has not shipped. It is not included in the HPM package and is
not a release commitment.

## How this backlog is organised

- **Now**: correctness defects, misleading output and high-value usability work.
- **Next**: valuable work with a known direction but more design or investigation required.
- **Later / v3**: architectural work or lower-priority improvements.
- **Hold / closed**: deliberately deferred, rejected or completed items kept as a short record.

Every active item states its next action. Detailed research belongs in Supporting Docs or commit
history, not in this delivery list.

## Release gate

**Closed, and superseded by an actual release.** Steve retested the original missing
component-device scenario on his own hub: Hubitat reported 351 devices and Automation Map matched
it at 351; he specifically checked Aqara, Bond, Harmony, and Shelly devices and confirmed all were
present, and described the release as "spot on" with nothing else discovered on his end. That
defect, along with everything else accumulated on `dev` through 2026-09-02, shipped to production
as v2.2.0 (2026-09-03) - see item 16 in Hold/closed for the release path. This section is now a
historical record, not an open gate.

## Now

### 29. Screen audit on the dev hub, 2026-09-10

Found by stepping through every Focus entry type, Insights, the large panels, the full legend and all
twelve Show filters on dev hub revision 136, measured in the browser.

**Closed.** Accepted in review and verified on dev hub revision 143 (commits `c7b6aaa` to `ce97021`):

- **A.** A size chosen with the resize grip was kept for every later item, so the panel's right edge
  overhung the legend. A new item now starts at the default size and position.
- **B.** Insights showed the previous item's rule variables card beneath its own content.
- **C.** The Hubitat releases, External systems, Pivot tables and Device icons panels had no bottom
  bar, unlike the normal flow view.
- **D.** "webCoRE variable use only" drew an empty map. It matched only direction-unknown references,
  of which there were none, while real variable reads and writes had no filter. Now "Variable use
  only" keeps reads, writes and direction-unknown use from every engine; "Variable connectors only"
  keeps Hub Variable connector synchronisation, which is not use; the device filter is "webCoRE
  device state reads only". webCoRE commands stay under Actions only.
- **E.** Every Local Variable label named its owner twice, and the unused Local Variable panel title
  named it twice more and added "(Local Variable)". One builder now serves the dropdowns, Quick
  Search, canvas labels and the panel, each naming the owner once.
- **F.** App labels repeated the app type when the label is exactly the type name, for example
  "Tapo Integration (Tapo Integration)". Stripped only on an exact repeat.

- **G.** An external system picked from Quick Search was written into the Focus Device dropdown. It
  now has its own transient focus, used by currentFocus, the map filter, history, Back and Forward and
  Show all, with every Focus dropdown left at All. Its framing was wrong because inert nodes kept their
  whole-map shelf pins in narrowed views; those pins are now released.
- **H.** A Hub Variable and its connector device drew their labels on top of each other. A narrowed
  view's 1.5 second fallback switched physics off before the layout spread (measured 41px apart). The
  narrowed view now runs the layout to rest first (measured 362px). Every drawn view, including an app
  view laid out without physics, takes ownership of the canvas, so an older settle's listener or timer
  cannot shelve inert nodes into, reframe, or reveal a newer view.
- **Shelf line in narrowed views.** The "Inert Nodes" divider was drawn in every view, so it could
  cross a focused map. It is now drawn for the whole map only. The start-up Show all also no longer
  resets a focus picked before the first settle.
- **Zoomed flowchart.** When the flow panel was zoomed in, the chart's horizontal scrollbar sat at
  the bottom of the chart rather than the bottom of the panel, so wide content could only be scrolled
  sideways after scrolling to the end of the chart. While zoomed, the panel body now scrolls both ways.
- **Connector legend.** A Hub Variable focus drew the connector line with no legend row. It now has
  one in both the compact and the Full legend, in the colour the line is drawn.
- **Side panels over a pending flowchart.** Opening External systems, Device icons, Hubitat release
  activity, Pivot tables or the Full legend while a flowchart was still drawing let the old drawing land
  afterwards and reopen the flow panel. Opening any other panel now makes a pending drawing stale.
- **I.** The webCoRE parent panel drew an informational sentence in the red attention style, and its
  "holds 6 apps" heading repeated the title. Now ordinary text, and a heading already in the title is
  not repeated. Red stays for a partial or failed piston coverage.

**Still open, waiting on Gordon:**

- **Narrow windows:** not yet checked. It needs the browser window resized or the viewport overridden.

**Decided by Gordon, 2026-09-11:**

- **Indented font size:** kept as it is. The webCoRE text already measures the same as Rule Machine's.
- **webCoRE piston locals:** tagged [WCV] in the dropdowns, Quick Search, their panel and the rule
  variables card. Rule Machine locals keep [LOC].
- **Tags are searchable (fixed on dev).** Quick Search and the Focus dropdowns matched only an item's
  name, so a visible tag such as [WCP] found nothing while "WC" matched names. They now match the
  text each row shows, tag and type prefix included.
- **Repeated variable card rows.** A rule that writes the same Local Variable from two fields showed
  "[LOC] Overloadcount - writes" twice. Identical visible rows now merge by scope, name, operation and
  read role in the Local, Hub and Needs review lists; the saved references stay one per field.

## Next

### 23. Remaining findings from the independent UI assessment (2026-09-07)

Full report: `Supporting Docs/desktop_ui_independent_assessment_2026-09-07.md`. The three concrete
bugs it found are item 22 in Hold/closed below; everything here needs an actual design/implementation
decision, not just a wording fix. Spot-verified against source (line citations, opacity/stabilization
timing, `#status`/`#legend` 375px and `#controls` 300px fixed widths, the 820px small-screen
breakpoint, close buttons using generic `title="Close"` with no `aria-label`) - all checked claims
matched current code.

- Narrowing the relationship filter (e.g. "External systems only") blanks the graph for roughly
  1.5s with no busy indicator before the narrowed layout appears - the network's opacity is
  deliberately zeroed during physics stabilization (`apps/automation_map.groovy` `settle()`) with a
  1500ms fallback reveal, and nothing tells the user layout is still running during that window.
- Duplicate visible labels in Quick Search results are indistinguishable - no room, parent, or ID
  discriminator shown when two nodes share a label, only a hidden internal id.
- Panels (Insights, External systems, Pivot tables, Device icons, flow/details, release activity)
  have no `role="dialog"`/`aria-labelledby`, close buttons expose only "x" with no `aria-label`, and
  focus does not move into an opened panel or return to the launching control on close. The graph
  canvas has no keyboard-accessible node structure.
- At 1024x768 the fixed 375px legend plus ~300px control rail leaves a narrow central strip for a
  large map, but the small-screen fallback message only triggers below 820px - so 1024px is treated
  as a fully supported desktop graph layout while being difficult to read in practice.
- Panel-internal action styling is inconsistent - the main tool rail uses large rounded buttons,
  but External systems/Device icons render Save, backup/restore, and similar actions as small
  browser-default buttons, and Pivot tables/Export CSV use yet another compact treatment.
- The initial whole-map view packs ~386 nodes into a small central cluster with unreadable labels
  until the user searches or focuses; overlaps with item 1's existing "search-first" direction below.

**Next action:** each bullet needs its own scoped design decision before implementation (matches
item 1's review scope for the layout/styling ones) - not a batch to fix blind. Raise with Gordon
which to schedule and in what order.

### 1. Desktop UI review and map workspace modernisation

**Why now:** the desktop map is powerful but visually dense. Important actions compete with raw
data, panels use space inconsistently, and several views are difficult to scan. This is a product
usability issue, not cosmetic polish.

**Review scope:**

- Test at 1440 x 900 and 1920 x 1080, including browser zoom at 100% and 125%.
- Review the map canvas, top-level actions, legend, search, focus views, Insights, External Systems,
  Community Utilities, baseline comparison and export entry points.
- Identify duplicated controls, competing visual emphasis, undersized text, overly long labels,
  weak grouping and panels that expose detail before the user asks for it.
- Check keyboard focus, close behaviour, scrolling, resize behaviour and restoration of the map
  after a panel closes.

**Preferred direction:**

- Keep the map as the dominant desktop surface.
- Replace scattered controls with a compact, clearly labelled tool rail or toolbar.
- Use one consistent panel shell with a stable header, close control and content region.
- Use progressive disclosure: summary first, supporting detail on demand.
- Maintain a practical 14 to 16 px text floor for ordinary content.
- Give primary actions, navigation and status distinct visual roles.
- Keep the legend compact and contextual rather than permanently consuming map space.
- Use concise tables, counts, filters and ranked findings instead of long prose lists.
- Preserve graph context when switching tools or opening detail.

**Deliverables:**

1. Annotated desktop UI audit with specific problems and affected views.
2. A low-risk layout proposal that can be delivered incrementally.
3. A desktop wireframe for the map, tool rail and shared panel shell.
4. An implementation sequence separating structural changes from visual refinement.
5. Acceptance checks for desktop readability, navigation and panel behaviour.

**Done when:** a user can quickly identify Search, Insights, External Systems, Community Utilities
and Export; only one primary panel is open at a time; ordinary text is comfortably readable; and
the graph remains useful while tools are opened and closed.

**Status:** audit, low-risk proposal, desktop wireframe and acceptance matrix completed on
2026-08-31 in `WIP/desktop_ui_review_and_modernisation.md`. The next implementation gate is Phase 1,
the structural workspace shell. Source implementation, Dev deployment, commit, push and production
promotion remain separately authorised actions.

**Live feedback from Gordon's own testing (2026-09-02) - implemented ahead of the fuller audit/
wireframe, all three confirmed live on Automation Map (Dev) / Apps Code 1210:**

- The External Systems "Community information" card (e.g. the LIFX Light Manager tile) is narrower
  (`#communityCard` max-width). Done.
- Spacing added between the top Focus dropdowns and the "Show" relationship filter below them
  (`#showFilterLabel` margin-top). Done.
- The four Focus dropdowns (Apps, Devices, Hub Variables, Local Variables) are now a single combined
  combobox each, replacing the old search-input-stacked-above-a-select pair. Proven standalone first
  in `Bucket/combobox-harness/` (31 automated checks) before porting, then iterated live against
  Gordon's own feedback: a non-editable closed control (label + arrow) opens a popup whose first row
  is a dedicated, auto-focused search field, with the filtered options list directly below it and no
  pinned "All X" row once a filter term is typed. `#controls` widened 150px -> 300px and the hub
  watermark image repositioning tracks the panel's own right-anchored geometry (`right:` instead of
  a fixed `left:` percentage) so the two cannot drift out of alignment again the way they did when
  the panel first widened. Done.

### 4. Include Dashboard usage in cleanup findings

Device cleanup advice should account for devices referenced by Hubitat Dashboard, not only rules and
apps already represented by the map.

**Next action:** confirm a reliable read-only source for dashboard device references, model the
relationship, then suppress false unused-device findings.

### 24. Variable usage from apps and platforms Automation Map cannot decode (webCoRE, Dashboard)

Community feedback from thebearmay (Hubitat forum, replying to Gordon re: the Hub Variable identity
work) on the current Rule Machine variable handling: "the variables look correct" - but flags two
gaps in coverage:

- webCoRE has its own variable ecosystem entirely invisible to Automation Map today: webCoRE local
  variables (his read: easy to locate), webCoRE global variables (a dynamic table, names hashed -
  "still working out the specifics" even from his side), and webCoRE's own use of Hub Variables.
  Discovery currently only decodes Rule Machine/Notifier/VRB2 flows, so none of this is captured.
- Dashboard's use of Hub Variables specifically - item 4 above already covers Dashboard's *device*
  references, but not Hub Variable usage.

The more promising lead in his message: he believes Hubitat itself may maintain some registry of
"what uses this Hub Variable," visible on the platform's own Hub Variables page, though he does not
know where it is sourced from ("can see it on the Hub Variables page so I know it exists"). If real
and read-accessible, this would be a single, authoritative source covering webCoRE, Dashboard and any
other third-party consumer at once, rather than needing bespoke per-platform decoding for each one -
consistent with how Hub Variable identity itself is already sourced authoritatively via
`getAllGlobalVars()` rather than purely inferred from decoded flows.

**Registry interface finding (2026-09-07):** the registry is real but
not a viable production data source. Confirmed live with a test webCoRE piston referencing a Hub
Variable directly (no Connector device) - the Hub Variables page did list it as a consumer, and kept
listing it even after the piston was paused and its runtime subscriptions/controls removed, meaning
the registry reflects saved app *configuration*, not current runtime activity. No safe read-only
interface to it was found on firmware 2.5.1.181: the Hub Variables app's own status JSON does not
carry it, and the one endpoint that returns per-variable consumers
(`/installedapp/configure/json/<id>/hubVar`) only answers for whichever variable is currently
selected in that built-in app's own UI state - switching variables means POSTing to the undocumented
generic `/installedapp/btn` handler, which is not an appropriate thing for Automation Map to depend
on in production.

**Independent re-verification (2026-09-07), same conclusion via a different route:**
decompiled the Hub Variables page's own client-side `buttonClick()` function directly rather than
inferring from network traffic - confirms the original finding: showing a variable's consumers
calls `$.post('/installedapp/btn', {id, name, stateAttribute:'inUse', ...})`, a stateful call against
the built-in app's own session, with no stateless GET or URL-parameterized equivalent. The caution
against depending on it stands, now confirmed two independent ways.

**But found a working alternative for webCoRE specifically, with real decoded data, not just a
plan:** a webCoRE piston's own settings are readable through the exact same generic per-app
config fetch Automation Map already uses for every other app (`hub_get_app_config`/
`installedapp/statusJson` - no special endpoint, no POST, fully read-only). Built a real test piston
(`__AM Hub Variable Registry Test`) referencing `AMGateA_HubOnly` directly with no Connector device,
fetched its own settings, and found the piston's compiled logic in a base64-encoded JSON field
(`chunk:0`) with the variable reference sitting in cleartext:
`{"t":"condition","lo":{"t":"x","x":"@@AMGateA_HubOnly","f":"l","vt":"string"},"co":"changes",...}`.
`chunk:` prefixed settings confirmed as the real, general mechanism against webCoRE's own public
source (`ady624/webCoRE`, `webcore-piston.groovy`, `setup()`) - large pistons split across multiple
`chunk:N` fields when a single one would exceed the platform's per-setting size limit.

**Hubitat-port namespace correction, verified against the exact installed source version:** the
generic webCoRE documentation describes `@@` as a Superglobal and `@` as a Global, but the current
Hubitat port deliberately maps Hub Variables into that `@@` namespace. Its `AddHeGlobals()` reads
`getAllGlobalVars()` and publishes every entry as `@@<name>`; its read and write paths strip the two
prefix characters and call Hubitat's `getGlobalVar()` / `setGlobalVar()`. Plain `@<name>` remains a
webCoRE global. The checked source constants exactly match the installed built-in webCoRE and piston
versions, so for this Hubitat implementation a typed variable operand (`t:"x"`) whose `x` begins
`@@` is a Hub Variable reference. The adjacent `f:"l"` field is not needed to distinguish a legacy
webCoRE Superglobal. Any extracted name must still be reconciled against Automation Map's
authoritative Hub Variable inventory before creating a relationship.

**webCoRE's own usage report, now checked:** "Dump global variables in use" is safely readable with
the existing read-only app-config fetch (`pageDumpGlob`); it does not require a state-changing button
POST. Matching source shows that it renders a static in-memory `globalVarsUseFLD` cache populated by
piston analysis/execution paths, rather than decoding each piston's saved settings at request time.
The live report currently still lists the paused test piston, disproving the absolute claim that a
paused piston will not appear, but the cache can still be incomplete or stale across lifecycle/code
reload boundaries. It is useful corroboration, not an authoritative replacement for `chunk:N`
configuration decoding.

**Prototype result:**
`tmp/webcore-variable-decoder-prototype.groovy` mirrors the matching Hubitat-port implementation:
contiguous chunk assembly, Base64/UTF-8 and emoji decoding, JSON parsing, and typed variable-operand
classification for Hub Variables (`@@`), webCoRE globals (`@`), and declared piston locals. Nine
targeted checks pass, including arbitrary multi-chunk boundaries and fail-closed malformed-input
cases; a sanitized read-only decode of the installed test piston also matches. The hub currently has
no representative real pistons beyond that synthetic fixture, so real-world diversity remains
untested. The prototype proves consumer-reference discovery only, not read/write role or full flow
reconstruction.

**v2.2.5 implementation reviewed and verified on Dev:** Automation Map now decodes each
webCoRE piston's saved `chunk:N` configuration during its existing app scan, reconciles `@@` names
against the authoritative Hub Variable inventory, and emits a distinct `usesVar` relationship with
direction explicitly unknown. The graph, focused-app card, fixed and custom pivot tables, Insights,
scan-quality status, and AI export all preserve that distinction rather than manufacturing a read or
write. Malformed configuration produces only a fixed error code, keeps every other app relationship,
and marks the scan complete-with-gaps; decoded documents and values are never retained or exported.
The export contract moves to schema 9 and the cached graph to schema 11. Targeted source-bound tests
cover chunking, malformed input, privacy, reconciliation, inert-app handling, rendering/pivots,
Insights and export semantics. Dashboard and other community apps remain open. The Hub Variables
page and webCoRE usage report stay manual corroboration, not production data sources. Absence from
decoded configuration still cannot prove non-use where a reference is constructed dynamically.

**v2.2.8 implementation reviewed and verified on Dev: the webCoRE portion of this item is now
closed.** Two further additions, both decoded from the same saved piston configuration the v2.2.5
work already reads: owner-scoped webCoRE piston local variables (declaration plus proven read/write,
feeding the same generic Local Variable machinery Rule Machine's own locals already use), and direct
physical-device reads and actions, resolved only against the specific webCoRE parent's own
permitted-device list using webCoRE's own device hash construction - never guessed, never inferred
from permissions alone. Each piston reports real per-piston device relationship coverage
(complete/partial/none/error) rather than a fixed placeholder. A form the decoder cannot resolve (a
variable-backed device list, webCoRE's own current-triggering-device placeholder, a location/virtual
operand) produces a counted coverage gap, never a guessed relationship. The export contract moves to
schema 12 and the cached graph to schema 14; `Supporting Docs/webcore_piston_devices_and_local_variables_spec.md`
holds the full specification and acceptance detail. Dashboard's Hub and Local Variable usage remains
the only open part of this item.

### 25. webCoRE decode coverage: account for the whole piston, not just the parts we read

**The gap.** The webCoRE decoder is a targeted extractor. It walks each piston's saved configuration
hunting for three specific shapes (typed variable operands, physical-device reads, direct device
actions) and silently discards everything else. That was the right scope for what v2.2.5 to v2.2.8
set out to do, and it produced real relationships, but it means the app cannot answer a question
users reasonably ask: *what else is in this piston, and how much of it can the map actually read?*

Absence of a relationship currently has two indistinguishable causes: the piston genuinely does not
have one, or the decoder never looked. The app should be able to tell those apart and say so.

**Proposed Phase A, decode coverage.** A complete traversal that visits every node and field and
accounts for all of it, reported per piston:

- A hard accounting invariant. Traversal counters (objects, arrays, fields, array elements, scalars)
  must balance against an independent oracle. Nothing may be dropped silently.
- `visited` kept strictly separate from `identified`, so complete traversal never implies complete
  understanding. Recognition is measured over construct candidates, never over every JSON value.
- An in-source construct registry pinned to a webCoRE source commit, covering its statement types,
  expression functions, virtual commands and execution policy flags. Version drift against the
  installed webCoRE is reported and downgrades confidence rather than being assumed away.
- Per-construct evidence levels rather than a recognised/not-recognised flag, so partial
  understanding is visible and cannot silently regress.
- Run per piston on demand from its panel, not on every scan. Most hubs do not run webCoRE and should
  bear no scan cost for this.
- Structure and paths only. No literal values, command parameters, messages, URLs or variable values
  leave the decoder, preserving the existing export privacy commitment. Enforced by a path allowlist
  and canary tests rather than asserted.

**Explicitly not in scope.** No translation to Rule Machine or Visual Rule Builder, no destination
recommendation, no judgement that a piston is simple or safe to convert, no write path of any kind.
Automation Map continues to describe and never to change the hub.

**Why it is worth doing.** It closes the honesty gap above, it tells a user which
parts of a piston the map is reading, and its output would establish whether fuller webCoRE flow
decoding is practical at all or whether permanent partial coverage with explicit gaps is the truthful
end state. That answer is currently unknown and is worth having either way.

**Status.** Building on the dev channel, in reviewed increments.

- **Construct registry (done).** A registry of 279 constructs generated from a pinned webCoRE source
  commit, with per-region evidence hashes, gates that refuse to emit on a provenance or membership
  failure, and a determinism test proving the checked-in file is byte-identical to a fresh
  generation. The app ships a projection of it carrying construct identity and evidence level only.
- **Census walker (done).** A pure traversal with five independent counters balanced against an
  independent oracle, context-sensitive classification that never steers traversal, safe paths from
  a reviewed key allowlist, fixed reason codes, and deterministic depth, value, path-length and
  retained-list bounds.
- **Read-only endpoint (v2.2.9).** One authenticated route that runs the census for a single piston
  on request. It refuses while a scan is active, accepts only an ID this app has already scanned
  whose type is a webCoRE piston, permits one operation per piston at a time, bounds the request,
  the loopback and the analysis with fixed time limits, and builds its response field by field from
  an explicit allowlist at every level.
- **Decode coverage card (v2.2.9, dev).** A card in the focused panel of a webCoRE piston. Nothing is
  fetched until its button is pressed. It leads with whether the whole saved piston was accounted
  for, then the share of construct positions recognised at L2 or above, the constructs found with
  their evidence level, and any gaps as structural paths. Verified in a browser on the dev hub in
  every state it can show, and accepted in review.
- **No cache.** The endpoint applies fixed traversal, output and time bounds. Measured on the dev hub,
  results were under 1KB and returned within a second, so a cache would add hub state for no
  meaningful saving there; that is observed evidence, not a guarantee for every hub.
- **Custom command tasks (fixed on dev).** The editor saves `cm: true` on a task with a custom
  command. `cm` was missing from the walker's key allowlist, so such a task reported an unidentified
  key; it is now allowlisted after tracing it through the editor serializer.
- **Piston option keys are unidentified.** A test piston's root options map saved `mps`, `pep`,
  `dco`, `des`, `aps` and `ish` alongside the allowlisted `cto` and `ced`. They sit outside every
  statement, so they do not affect structural validity, but any piston that saves them reads
  "Coverage incomplete". Each needs tracing to the executor's piston-option reads before it is
  allowlisted.
- **Statement evidence gaps to close.** The twelve statement families are structural (L3), but an
  occurrence that takes a saved branch without a matching editor save and reload in the fixtures is
  held at identified (L2). Open: `sm` on a statement; an absent `tcp`, which the editor can produce
  but the capture matrix does not yet contain; a task carrying `cm` or `a`; a `for` without `x`; and a
  group's retained or unconsumed
  `wd`, and `wt` of `l` or `n`. Nodes
  saved before the piston was first reopened (`$`, `ct` or `s` absent) cannot be shown after a reload
  and stay held by design.
- **Semantic evidence (L4), on hub dev v2.3.0.** The coverage card shows what saved statements mean,
  separately from structure: the order in which an if tests its branches, condition negation, `or`
  groups, followed-by groups kept opaque, `do` as a sequential block, the default statement settings,
  switch case order and its case-traversal policy, a break scoped to its nearest switch, and exit as
  a whole-piston terminate. Everything else is an explicit gap per occurrence, so no current piston is
  yet reported as fully explained.
- **Still to come.** The remaining L4 steps (loops, events and policies, actions, operands), then the
  flow rendering that eventually lets a piston draw its own flow with anything not yet understood
  shown as an explicit opaque block.

The binding constraint remains fixture diversity: the dev hub has six pistons, which cannot establish
real-world coverage, so any broad claim needs a sanitized opt-in corpus first. Related to item 24,
which covers webCoRE variable usage specifically.

### 26. Contested devices: compute the trigger overlap instead of asking the user to

**The gap.** The contested-device finding lists every automation that can leave a device in a lasting
state, then says: *"Check whether their triggers can overlap and which automation should win when
they do."* The first half of that is work the app already holds the data to do. `trigger` edges
(app to device) are on the graph for every app with decoded triggers, so shared trigger sources
between the controlling apps are a straight derivation, not new information.

**Evidence, from a real scan on the dev hub.** One device had 10 controlling automations. Nine
distinct trigger sources across them, except that **four shared a single trigger device**, and those
four were near-duplicates (an import, a second import, and a clone of the same rule) all firing from
the same source onto the same light. That is the actionable signal, and it was invisible under a flat
list of ten names that the user was asked to cross-reference by hand.

**Proposed change.** Group the controlling apps by shared trigger source and surface the clusters,
leaving "which should win" as the question it genuinely is.

**The honesty constraint, which cuts both ways and shapes the wording:**

- A shared trigger device is **positive evidence** that two automations can fire from the same event.
  Safe to state.
- Not sharing one **proves nothing**. Time, mode, variable and rule-invoked triggers produce no
  device edge at all, so "these cannot overlap" would present a decoding gap as proven emptiness. The
  finding must surface the positive signal and stay explicitly silent on the negative.
- An app with no decoded trigger at all is a **third state**, undetermined, not absent. In the sample
  above one Basic Rule fell in this category and must be reported as such rather than folded into
  either group.

**Scope.** A derivation over existing `trigger` edges plus a rewrite of the one guidance string. No
new scan work, no new decoding, no schema change. Small and self-contained enough to be a **Now**
candidate rather than Next, if prioritised.

**Status.** Not started, not authorized. Behaviour confirmed against a real export before writing this
entry; the underlying edges are already present and sufficient.

### 5. Add runtime activity and performance context

Users want help finding automations that may contribute to hub load, but configuration structure is
not execution evidence.

**Next action:** define a conservative metric model using available app statistics and timestamps,
label observations as evidence rather than conclusions, and prototype a ranked diagnostic view.

### 6. Expand a focused map one hop at a time

Allow users to reveal immediate neighbours without returning to the full graph.

**Next action:** specify one-hop expansion, duplicate suppression, reset behaviour and visible
provenance. This replaces the overlapping multi-select and extend-map backlog requests.

### 7. Export and import configured app data for migration

Provide a safe, portable representation of user-maintained settings such as external-system
overrides and icon choices.

**Next action:** define a versioned schema, conflict rules, preview step and validation behaviour.
Never import scan results or secrets as configuration.

### 10. Live Hubitat platform update check

The existing "Hubitat release activity" panel only shows historical Community Utilities/Hubitat
release data; it never tells the user whether their own hub currently has an update available.

Investigated two approaches on 2026-08-27. Hubitat's own live update-check
(`/hub/cloud/checkForUpdate`) is real and confirmed working - a status read via the hub-rules MCP
server reported `UPDATE_AVAILABLE` (2.5.1.172 -> 2.5.1.174) with version, release-notes URL and beta
flag, and a second call actually triggered the install (download, apply, reboot). But that path is
only reachable from outside the app sandbox (via the MCP server's admin access), not from a Hubitat
app's own code, and it bundles the check together with the install - there is no way to ask "is one
available" without also committing to install if the answer is yes.

Better direction found the same day: `HPM_Manifest_Crawl`'s own feature-tracker dataset
(`site/feature-tracker/data/hubitat_release_features.json`, publicly fetchable, confirmed live)
already tracks every Hubitat release with a `version` and `releasedAt`. A sandboxed app can safely
read `location.hub.firmwareVersionString` (standard, documented) and compare it against that
dataset's latest entry - no undocumented endpoint, no admin access, no risk. The limit: this is a
scheduled crawl of the community forum, not a live Hubitat query, so it lags real releases by up to
one crawl cycle - confirmed directly, since at the moment 2.5.1.174 was installing on Gordon's hub,
the dataset's last harvest (2026-08-26) still only knew about 2.5.1.172.

**Next action:** publish a small derived `latest.json` (`{version, releasedAt}`) from the
`HPM_Manifest_Crawl` pipeline instead of shipping the full ~4 MB dataset to a Hubitat app, fetch it
from Automation Map, compare against `location.hub.firmwareVersionString`, and label the result
honestly as "latest known as of `releasedAt`" rather than "latest available" so the crawl lag stays
visible. Report only - never trigger an install from within Automation Map itself.

### 24. A dead constraint on a device that also has a live relationship

Rule Machine keeps a condition's `rDev_<n>` setting forever, including conditions no expression
names any more, so those devices are drawn as constraints even though nothing evaluates them. The
map now tags a device `UNUSED` when every relationship visible in the current view is one of these,
which covers the case that prompted the work (Perimeter Open's orphaned illuminance condition on the
two Back Garden lights).

What it does not cover: a device holding both a dead constraint and a live relationship. Perimeter
Closed is the example - its five door contacts are live triggers and also sit in an abandoned contact
condition, so they keep an unexplained constraint line with no tag. A node tag cannot say this
without falsely calling the device unused, since the device genuinely is in use.

**Next action:** decide whether to mark the edge rather than the node (dimming or dashing a dead
constraint line), and whether to add a matching neutral Insights finding alongside
`disabledDevicesStillUsed`. Detection already exists and is exposed as `unused` on constraint edges;
this is a presentation decision, not new analysis. Hub-wide there were 20 such edges across 6 rules
when this was measured (2026-09-09).

## Later / v3

### 11. Move graph derivation into the browser

Reduce Groovy-side rendering work and make UI iteration easier by sending normalized records and
deriving view-specific graph structures client-side.

### 12. Move remaining display shaping into the browser

After graph derivation is stable, migrate filtering, grouping, styling and panel preparation while
keeping scan collection and authoritative normalization on the hub.

### 13. Separate the frontend from the Groovy GString

Investigate a maintainable source and build arrangement for HTML, CSS and JavaScript without
breaking single-app Hubitat distribution.

### 14. Delta scanning

Only pursue partial scans if a cheap, reliable app or device change signal can be proven. A faster
but incomplete map is not acceptable.

### 15. Same-hub warm-start cache

Investigate a bounded cache that can restore a recent map quickly while clearly showing its age and
never presenting stale data as a completed current scan.

## Hold / closed

- **Fix the three real bugs found by independent UI assessment (item 22):** completed and verified
  live on the Dev hub, 2026-09-07 (v2.2.5, local-only, not yet pushed). Full report:
  `Supporting Docs/desktop_ui_independent_assessment_2026-09-07.md`.
  - Focusing the Automation Map app itself used to falsely claim "Nothing at all: no children, no
    schedule, no subscriptions... it is not configured yet, or has been removed" despite genuinely
    running a scheduled scan - `processAppRelationships()` returned early for every instance of this
    app's own family (deliberately, to avoid drawing a second/orphaned instance as an app with
    hundreds of meaningless whole-hub edges), and that early return also skipped the separate,
    harmless schedule/subscription/child-count capture every other empty app gets from the same scan
    response. Now captured before the early return, same shape the existing shared block already
    builds for every other inert app. Verified after a real scan (117 apps): the self-app node now
    carries `sched: 4` and its panel lists all four real scheduled jobs (including the daily 01:00
    scan cron) instead of the false abandoned-app message.
  - The Full legend's Private Boolean row was a truncated sentence ("...rule sets another rule's",
    no object) - completed to match the correct wording the compact legend already had elsewhere in
    the same file.
  - Quick Search claimed to "search everything..." but excluded external-system nodes by
    construction. Added `external` to the search group allowlist, dispatched through `focusNode()` -
    the same generic path a direct click on an external-system node on the graph already used, so no
    new focus mechanism was actually needed. Verified live: 22 external-system items now appear in
    Quick Search; selecting one through the real combobox interaction correctly focused it.

  Item 23 (Next) carries everything else the same assessment found, since those all need an actual
  design decision rather than a wording/logic fix.

- **Tell the user when a newer version has been published (item 21):** completed and shipped in
  production v2.2.3 (2026-09-05). The settings page title shows a blue, bracketed notice when the
  channel's own manifest reports a newer version than `APP_VERSION` - notify only, HPM remains the
  thing that installs. Compares against the build's own channel (`dev` checks `dev`, production
  checks `main`), so a Dev tester ahead of production is never told they are behind. Scheduled
  independently of the auto-scan toggle, in the hub's own local timezone with the minute derived
  from the app id so installs do not all wake together and no UTC/DST handling is needed. Fetches
  asynchronously into `state`; page render is a string read, never a network wait. Fails silent on
  any error. Disclosed next to the scan-schedule paragraph, since it is a new outbound request and
  the lesson from the removed telemetry driver was that the objection is to phoning out
  unannounced, not to what is sent.

- **Surface broken or disabled rules in Insights (item 9):** completed and verified live on the Dev
  hub (v2.2.1, 2026-09-05). Adds findings for things that look fine but silently do nothing: a
  paused or disabled rule another rule still calls, a disabled device automations still command or
  use as a trigger, and rules Hubitat itself marks broken via its own `*BROKEN*` label - the only
  place that state is exposed; genuine runtime execution errors remain unreadable and are documented
  as a limitation, not claimed as covered. Every paused/disabled rule is also listed as plain context
  under expected patterns rather than as a fault, and Local Variables with no decoded usage get the
  finding their Hub Variable equivalent already had. Each finding needs a second fact before it is
  reported - pause/resume callers and constraint/monitor reads are deliberately excluded, since those
  are the mechanism working, not a failure. All of it derives from the same `deriveInsightData()` the
  export already used, so the panel and export cannot drift; confirmed on a real 220-device/116-app
  scan, both from the panel and from a downloaded AI-friendly export (`rulesFlaggedBroken`,
  `disabledDevicesStillUsed`, `inactiveRulesStillCalled`, `inactiveRules`,
  `unreferencedLocalVariables` all present and correctly populated). Additive export fields, so the
  schema version is unchanged. Also fixed in passing: the "best viewed on a desktop" small-screen
  message was sharing the page with the live status pill and hub watermark image, both left visible
  by the small-screen media query - now hidden with everything else the desktop UI doesn't need.
- **Production-builder line-ending hardening (item 20):** completed 2026-09-05. Generation is now
  independent of how a checkout materialized. Three paths carried the defect, not the one originally
  identified: the app source read, the header literal (which inherited the builder file's own
  checkout line endings), and the curated release notes, which embed verbatim into the manifest JSON
  as a string value and escaped as `\r\n`. All three canonicalize to LF, and each generator now
  fails closed if a carriage return survives into its finished candidate rather than trusting that
  canonicalization held. `.gitattributes` pins the build inputs and the builders to `eol=lf`
  (`validate.ps1` deliberately excluded, CRLF being native for PowerShell); no renormalization was
  needed since every affected blob was already LF, the exposure being that `core.autocrlf` rewrote
  them on checkout. Verified three ways: LF/CRLF/mixed fixtures through the real generator functions
  (confirmed failing without the fix), a fresh checkout under `core.autocrlf=true` keeping the inputs
  LF while the excluded `validate.ps1` converted as expected, and byte-identical candidates from two
  independent checkouts of the same commit. A CRLF-corrupted checkout is now additionally rejected by
  provenance verification itself rather than building silently. Generated output is unchanged: the
  candidate is byte-identical to the shipped v2.2.0 artifact apart from the embedded commit SHA.
  Fixed in passing: `validate.ps1` aborted on a detached HEAD (`git branch --show-current` returns
  nothing), which broke exactly the isolated-worktree builds used to recover from the original
  incident.
- **Structured Dev diagnostics and a comment-stripping production build (item 16):** the
  comment-stripping production build half shipped as v2.2.0 (2026-09-03) - a small, versioned
  allowlist of exact substitutions (never a general transform), each proven via full
  structural/positional comparison against the annotated Dev source, git-bound to one verified
  commit. Built as `tools/production-builder/` (`production-profile.groovy`,
  `production-manifest.groovy`, `production-package.groovy`), hardened across multiple review
  rounds with independent verification at each step, methodology generalized and written up at
  `hubitat_dev_utililities/Provenance-Verified Substitution Build/README.md` for reuse elsewhere.
  Release path: verified build -> isolated manual HPM install/test on a non-colliding app id ->
  Gordon's explicit hub confirmation and production authorization -> promotion to `main` (commit
  `72ff48a`) -> live HPM update -> community notice. A real gap found during the promotion build
  (candidate generation is not actually commit-pure with respect to local checkout line endings) is
  tracked separately as item 20, not blocking this closure. The structured Dev-only trace schema
  replacing `AM-TRACE` (the other half of this item) remains unstarted - if still wanted, re-open as
  its own item rather than reviving this one.
- **Open Automation Map in a normal browser tab (was item 2):** dropped as infeasible within the
  Hubitat-generated app UI, which controls the map link's small pop-out window. Do not pursue a link
  rewrite unless Hubitat later exposes a supported way for the app to choose normal-tab behaviour.
- **Show disabled devices distinctly on the map (was item 18):** completed and verified on Dev,
  pending production release (2026-08-31) - disabled devices and paused/disabled rules get a
  canonical label suffix, structured export fields (`devices[].disabled`, `apps[].status`
  distinguishing `disabled`/`paused`), and coloured Focus dropdown entries. Also fixed the
  duplicate-suffix bug noted under item 9. The canvas-level red-suffix piece was deliberately left
  out - see item 19.
- **Scan-schedule setting shows its real default (was item 8):** local review and automated gates
  passed, deployed to Automation Map (Dev) (Apps Code 1210 / instance 3083), and confirmed live in
  Gordon's own testing (2026-09-02) - the Hours/Minutes field now shows its actual default
  (00:30 production, 01:00 Dev) pre-filled via the input's own `defaultValue`, rather than appearing
  blank with an explanatory `description:` that never rendered on `bool`/`time` inputs. A
  `paragraph` states the display-vs-saved nuance. A separate effective-default helper
  (`autoScanEffectivelyEnabled()`) treats a genuinely unsaved `null` the same as the toggle's own
  displayed-on default, since Hubitat does not necessarily populate `settings` with a displayed
  default before the first save - without it, a truly fresh install could read the toggle as off and
  hide the time input entirely. One helper function is the single source for the default time across
  the input, the paragraph, and the scheduler's own blank-time fallback, so the three can't drift
  apart.
- **v2.1.8 production cleanup - telemetry removed, on-demand diagnostic logging added:** local
  review and automated gates passed, deployed to Automation Map (Dev) (Apps Code 1210 / instance
  3083). Diagnostic-toggle placement and off/on/off logging behaviour independently verified live
  by Gordon (2026-09-02) - `AM-TRACE` present only while enabled, gated routine lines correct, the
  two anomaly lines at `warn`. The telemetry-child migration test (clean deletion, plus a
  deliberately-referenced device failing safely) is explicitly **waived by Gordon**, not passed -
  low affected population, easy manual fallback. The Automation Map
  Telemetry Driver and everything that fed it (`ensureTelemetryDevice()`/`reportTelemetry()`/
  `fetchHubHardwareId()`, the manifest driver entry, the README disclosure) are removed outright
  rather than made optional - community reaction to an always-present reporting driver was that it
  read as intrusive regardless of what it actually collected. An upgrading instance removes its own
  leftover telemetry child device automatically (best-effort, exact-DNI `deleteChildDevice()`; if
  Hubitat refuses because it's still referenced elsewhere, the settings page shows a fixed warning -
  never the raw exception text, which is internal diagnostic detail and stays in the log only - and
  retries the next time settings are saved, not on a schedule of its own). In its place, a
  settings-page toggle enables on-demand diagnostic logging for troubleshooting - off by default,
  with a durable expiry timestamp (not just a scheduled job, which a missed hub-down window could
  leave stuck) enforcing the one-hour auto-disable even if the scheduled handler itself is missed;
  the settings page reconciles a stale "on" display back to off on its own next render. Only
  routine/lifecycle log lines are gated behind it (installs, scheduling confirmations, endpoint-entry
  logs, successful saves, expected superseded-generation discards, registry counts, scan
  start/completion detail); the temporary `AM-TRACE` path is Dev-only regardless of the toggle,
  per the existing agreement not to make it part of the reusable production logging design. Failures
  and degraded outcomes that can leave the map incomplete or stale stay unconditionally logged
  regardless of the toggle. Both the removed remote-telemetry approach and the new local-logging
  approach are documented for reuse at `https://github.com/GordonThelander/hubitat_dev_utililities`
  under "Application Telemetry Methods", sanitized and parameterized rather than copied with real
  identifiers.
- **Add anonymous Variable coverage counts to telemetry (was item 17):** cancelled, superseded by
  the decision to remove remote telemetry entirely rather than extend it (2026-09-02).

  This is a narrower, immediately-authorised slice of item 16 below, not a substitute for it -
  item 16's structured Dev-only race trace and its comment-stripping production build remain
  separate, still gated on Gordon starting that phase explicitly.
- **Component-device (parent/child) discovery and rendering:** completed and verified on Dev,
  pending production release (2026-08-31) - `/hub2/devicesList`'s hierarchical response (a
  device-owned component, e.g. a Shelly/Bond/Matter-bridge child, nested inside its parent's own
  `children` rather than as a top-level sibling) is now fully walked during discovery and rendered
  as a `hasComponent` relationship on the graph and in the AI export, including correct
  focus-expansion behaviour for an app that touches a child but not its parent directly.
- **Revalidate Local Variable handling (was item 3):** completed and verified on Dev, pending
  production release - identical names are not guessed or merged. A proven Local identity remains
  owner-scoped to its rule, a proven Hub identity remains hub-scoped, and an indistinguishable
  same-rule reference (persisted Rule Machine storage cannot always prove which was intended) is
  reported as ambiguous rather than assigned to either scope. Gate C shipped in v2.1.4 with live Dev
  verification; v2.1.6 added owner-scoped Local Variable graph nodes, focus and pivot support, and
  resolvable export endpoints, independently accepted per queue 315-317. The proposal document's
  `Draft`/`Implementation authorization: None` header is stale and should be corrected separately if
  the document is retained.
- **Hub Variable search:** shipped.
- **Variable Connector association:** shipped.
- **First-class Hub Variable identity, focus and export:** shipped.
- **Hub Variable pivot reconciliation:** fixed and independently verified on the Dev hub in
  v2.0.15, including a trailing-period variable with six read/write relationships.
- **Insights summary and readability redesign:** shipped; further work belongs to the desktop UI
  review above.
- **Actionable Insights guidance and AI-export alignment:** shipped and independently verified on
  the Dev hub in v2.0.15.
- **External Systems hierarchy, identity and reviewed seed classifications:** shipped and
  independently verified in v2.0.15. The only unassessed apps in the verification scan were two
  intentionally unknown scratch/test apps.
- **Current discovery wording:** corrected on the install page and in the README in v2.0.15.
- **Persistent manual node layout:** rejected because it conflicts with changing graph membership
  and creates fragile state.
- **Tablet-only legend redesign:** rejected as a separate item; desktop readability and responsive
  behaviour are covered by item 1.
- **Arbitrary node exclusion:** rejected because it can hide evidence and make the map misleading.
- **Single Hub Variable icon:** delivered in substance through first-class variable styling.

## Separate publication work

The Rule Machine storage-format write-up remains useful, but it is documentation work for the
developer utilities repository rather than an Automation Map product backlog item.
