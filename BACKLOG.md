# Backlog

Not shipped with the HPM package. Items here are agreed ideas awaiting a decision to build, not commitments.

---

## Urgent - v2.0.0 release blockers

Genuine defects affecting the v2.0.0 release, not feature requests. These jump the queue ahead
of the prioritised candidates below.

**All three below fixed and shipped in 2.0.1, 2026-08-20.** Kept here rather than deleted, as
the record of what shipped and why - see "Recently shipped" convention elsewhere in this file.

### Fixed in 2.0.1 - repository.json on main advertised only the Dev package

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact.

`repository.json` on `main` lists a single package, `"Automation Map (Dev)"`, description
`"Private test channel... Not for general installation"`, pointing at the `dev` branch's
manifest. Anyone adding this repo's `main`-branch `repository.json` as a custom HPM repository
therefore installs the Dev build with no release package available at all - almost certainly a
dev-to-main merge artifact, and exactly the kind of thing the README's own "Branches" note
("Only the app name, package id and the raw URLs differ between the branches") lets through
unnoticed on the next merge. The documented install path (Install -> From a URL ->
`packageManifest.json`) is unaffected.

Fix: point `main`'s `repository.json` at the release package and `main`'s manifest. Add this
file to the README's list of per-branch differences so a future merge doesn't reintroduce it.

**Fixed 2026-08-20.** `repository.json` on `main` now lists the real "Automation Map" package
pointing at `main/packageManifest.json`, with a fresh package id (`649b4714-efa5-48af-af93-
6359ccd9537d`) distinct from both the app's own definition id and the Dev channel's package id.
README's Branches section now calls out `repository.json` explicitly as a file that does not
diff-merge cleanly, so this doesn't silently regress on the next dev-to-main merge.

### Fixed in 2.0.1 - a stale finalization watchdog could publish a partial graph as finished

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact, two findings that share one root cause.

`startScan()` (`apps/automation_map.groovy:619`) only calls `unschedule('scanBatch')`. The last
batch of a scan additionally schedules `runIn(1, 'fetchRegistry')` and a `runIn(45,
'finishScan')` watchdog (:680, :686). Starting a second scan inside that 45-second window
orphans the watchdog, which then fires mid-way into the *new* scan, builds a graph from
half-populated `appInfo`, stamps it with the current `GRAPH_SCHEMA`, and clears
`scanRunning` - so the map reads as complete while the real scan is still running. It
self-corrects when the live chain reaches its own `finishScan()`, but the window in between
shows an incomplete map with no indication that it is one.

The trigger vector is the second finding: `scanMapping()` (:3345, the `/scan` GET endpoint)
calls `startScan()` unconditionally, with no `state.scanRunning` guard. Compare
`scheduledScanHandler()` (:191-198), which checks `state.scanRunning` first and skips instead
of restarting. A repeat GET to `/scan` while a scan is already running is exactly the trigger
for the watchdog bug above.

Fix both together: add the same `state.scanRunning` guard to `scanMapping()` that
`scheduledScanHandler()` already uses (answer with current status instead of restarting), and
add `unschedule('fetchRegistry')` / `unschedule('finishScan')` alongside the existing
`unschedule('scanBatch')` in `startScan()`. Either alone narrows the window; both close it.

**Fixed 2026-08-20.** `startScan()` now unschedules `fetchRegistry` and `finishScan` alongside
`scanBatch`. `scanMapping()` now checks `state.scanRunning` before calling `startScan()`, same
pattern as `scheduledScanHandler()`, and answers with current status instead of restarting.
Verified live against the hub with a deliberate race: two `/scan` hits fired back-to-back
produced identical `scanHeartbeat` timestamps, confirming the second call did not re-invoke
`startScan()`.

### Fixed in 2.0.1 - third-party CDN scripts ran same-origin with a live OAuth token, no SRI

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact.

The map page loads vis-network from unpkg and mermaid from jsdelivr (:3476-3477), plus an icon
font from cdnjs (:3491). Versions are pinned, but no script tag has an `integrity` attribute
(`grep -c 'integrity=' apps/automation_map.groovy` returns 0) and there is no CSP. The page is
served from the hub's own origin with a live OAuth access token in the URL query string, so
anything executing in that page can read the token and reach the hub admin UI same-origin. The
risk isn't that a CDN compromise is likely - it's the blast radius if one ever happens.

Fix: add `integrity` + `crossorigin="anonymous"` to both script tags (and the font), or vendor
the libraries into the hub's File Manager and serve them locally - see also the related P7 item
below, which would need this done anyway.

**Fixed 2026-08-20**, script tags only - the cdnjs icon font has no SRI mechanism available for
a CSS `@font-face` load, so it's unresolved by this fix and remains covered by the File Manager
migration item further down. Both script tags now carry `integrity="sha384-..."` and
`crossorigin="anonymous"`, hashes computed locally from the exact pinned files (not trusted from
a third party). Verified live: both the main graph (vis-network) and a rule flowchart (mermaid)
rendered correctly after the push, which a wrong hash would have blocked outright rather than
degraded.

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

**Rejected 2026-08-21.** Gordon: too complex for the value it returns, given "Show all" already
covers the common case above. Not pursuing the narrower same-layout-restore behaviour further.

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

**Rejected 2026-08-21.** Gordon: the app isn't designed for small form factor - use a large
screen instead, so the tablet-scaling requirement doesn't apply. This closes the item as
scoped. It doesn't independently confirm or deny whether the legend's own wording is still
accurate against current rendering rules; that's a separate, smaller question, worth its own
look only if a specific inaccuracy is ever actually spotted.

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

**Rejected 2026-08-21.** Gordon: doesn't hold together in this context - an app or device being
"noisy" on the map isn't a meaningful category the way it might be for a notification channel,
so there's nothing coherent for an exclusion toggle to act on.

### P3 - "View Automation Map" popup is a fixed 800px wide, not a full new tab

**Source:** hubitrep (community code audit author), forum feedback on the audit writeup,
2026-08-20/21 - "Open the SPA in a separate tab." Re-investigated 2026-08-21 after hubitrep
proposed a specific fix; the original framing of this item turned out to be wrong.

**Correction, 2026-08-21.** The original write-up of this item (below, struck through in intent
if not in markdown) claimed the link "navigates the current tab away... unlike the Community
Utilities button." That was based on checking only the `target` HTML attribute, which is a red
herring here: read the actual `onclick` and found `href()` never relies on `target` at all.
Every `href()` link on this platform renders `onclick="openWindow(this);return false;"`, calling
Hubitat's own `openWindow()`, which does `window.open(a.href, a.title,
"resizable,height=H,width=800,left=X")` and, once that window closes, resubmits the settings
form. So the map link was already opening in a separate window, not replacing the admin tab -
the original claim (and the fix built on it) was wrong.

hubitrep proposed changing `style: 'embedded'` to `style: 'external'` as "the whole fix." Tested
live on the Dev hub instance rather than trusted: both styles render the exact same `onclick`
handler, byte for byte. No difference in behaviour on this hub's platform build (confirmed C-8).
Reverted after testing; not carrying this change.

What the real, narrower issue actually is: `openWindow()` opens a **fixed 800px-wide** popup,
not a full-size new tab - cramped for a data-dense interactive graph, and popups are commonly
blocked by browsers by default with only a subtle address-bar indicator, which would read to a
user as "nothing happened" rather than "a new tab opened." Either of those is a real, worth-
fixing usability problem; "it replaces my tab" was not. A fix here means not going through
`href()`'s `openWindow()` at all - e.g. a plain anchor with `target="_blank"` rendered via
`paragraph` instead - not tweaking `href()`'s `style` parameter, which this session confirmed has
no effect on this behaviour.

### P1 - Hand-install without OAuth throws instead of explaining

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact.

`main()` calls `createAccessToken()` unguarded on every page render (:206). A hand-installer
who skips "OAuth -> Enable OAuth in App -> Update" (README step 2) gets an uncaught exception
page on first render rather than the README's promised "without it there is no map link" - the
failure is documented, but its on-screen shape isn't. This is the first screen a hand-installer
sees.

Fix: wrap in try/catch, render a short paragraph naming the two clicks that fix it.

**Fixed 2026-08-21.** `main()` now catches the exception from `createAccessToken()` and shows
a paragraph naming the two clicks (Apps Code -> Automation Map -> OAuth -> Enable OAuth in
App -> Update) instead of throwing. The rest of the page still renders below it.

### Closed - Runtime assets hotlinked from raw.githubusercontent.com

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact.

Every map load pulls a 352 KB watermark PNG (:3746), and on click, two MP3s (:6327, :6346),
straight from the GitHub raw host, branch chosen at render time by whether the app name
contains `(Dev)`. Same trust class as the CDN-script urgent item above, plus
`raw.githubusercontent.com` is rate-limited with no availability promise - it's not a delivery
CDN. This is also the concrete mechanism behind the "Community utilities plays the Show all
sound in prod" bug hit live 2026-08-20: `main`'s branch-selected asset URLs 404 whenever `main`
lags `dev` on GitHub, and both buttons fall back to the same synthesized tone.

Fix: File Manager, same as the CDN item. The watermark is the single largest asset on the page
and is purely decorative.

**Partially addressed 2026-08-20.** The Show all sound is removed entirely (function, constants,
call site, credit line, and the orphaned mp3 all deleted) rather than fixed - simplest immediate
option, and it also fully retires the "plays the wrong sound" bug since there is no longer a
sound to get wrong. The watermark and the Community utilities sound are still hotlinked from
`raw.githubusercontent.com` exactly as described above; this item stays open for those two.

**Decided 2026-08-21: keep hotlinking, File Manager rejected.** Discussed the three real options
(File Manager, base64-embedding like the existing 29KB constant, staying on GitHub). File Manager
rejected deliberately: it fixes nothing for anyone except Gordon's own hub, since storage is
per-hub and nothing auto-populates it for other HPM installers - `uploadHubFile()`
self-provisioning could close that gap but adds real complexity for two decorative assets. Stays
on GitHub hotlinking instead, with the requirement that a missing asset must never break the
app, not just degrade quietly.

Checked against the actual failure-handling code rather than assumed: this mostly already holds.
vis-network failing to load is caught (`typeof window.vis === 'undefined'`) and shows "Could not
load the drawing libraries" instead of a blank page. The sound already has a three-layer
fallback to a synthesized tone (`error` event, `play()` rejection, try/catch) and never throws.
The watermark `<img>` fails silently by nature of the tag, no JS involved. The one real gap:
mermaid's load state is never checked independently of vis-network's, so a flowchart-specific
CDN failure (vis-network fine, mermaid down) has no equivalent graceful message. Gordon's call:
leave as is - not worth a dedicated fix for a rare, narrow failure mode on a decorative/secondary
feature.

**Closed 2026-08-21.** Confirmed: stay on GitHub hotlinking for the watermark and Community
Utilities sound, no further work planned. Hotlinking is the cheaper, more practical choice here
precisely because the map already has a hard, unavoidable dependency on internet connectivity -
the vis-network and mermaid CDN libraries the whole page needs to function at all already require
it, and the README already documents this ("The viewing browser needs internet"). Removing two
decorative assets' dependency on GitHub would not remove that existing dependency, so it buys
no real reduction in what the app requires to work, only extra complexity for File Manager or
base64-embedding to carry two images/sounds that were never going to make the app internet-free
either way.

### P1 - Quadratic edge/device dedup inside the execution that already dies on large hubs

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact (all 8 call sites).

`buildGraph()` dedupes with `List<String> seen` and `seen.contains(key)` at six call sites
(:2684, :2701, :2727, :2776, :2837, :2878) - a linear scan per candidate edge, quadratic over
the build. The device walk has the same shape via `appIds.contains()` (:725, :751). A few
thousand edges means millions of string comparisons inside the exact execution that has already
died once on a large hub (see the P1 architecture item below).

Fix: swap `List` for a `Set` (or a `Map` keyed the same way) at all 8 sites - drop-in, identical
behaviour, no state-shape change. Worth doing regardless of the larger architecture item, since
it's cheap and this is the execution most at risk of timing out.

**Fixed 2026-08-21.** All 8 sites now use `LinkedHashSet`, preserving the existing insertion
order (`appIds`' device-found-ids-first ordering, relied on by the scan summary count) while
making membership checks O(1). `state.appIds` is still written back as a `List`, so its stored
shape is unchanged.

### P1 - Supporting Docs index references deleted files, omits three that exist

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact (both deletions, all three omissions).

`Supporting Docs/README.md`'s Contents table lists
`Rule_Machine_5_1_Execution_Explained_Draft.md` and
`hubitat_automation_map_rule_to_rule_implementation.md`, both deleted in `0d65e2b`; the Status
section still sends readers to the latter by name. The registry-pack section calls
`hubitat_automation_map_app_integration_registry_v0.3.json` "the useful part: 101 entries" -
deleted in `c117b71` when the app switched to fetching the shared registry instead. Three files
that do exist (`ai_export_spec.md`, `hpm_scrape_spec.md`,
`rule_machine_execution_and_cross_rule_causality.md`) appear nowhere in the index. The
deletions were deliberate and correct; only the index is stale.

Fix: regenerate the Contents table from the current directory listing.

**Fixed 2026-08-21.** Contents table now lists the 3 previously-omitted files and drops the 2
deleted ones. The `registry-pack-v0.3` and Status sections, which had the same staleness
independently (one referenced the deleted registry JSON by name, the other the deleted
rule-to-rule draft by name), are corrected too.

### P1 - minimumHEVersion is below the app's real floor

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact.

`packageManifest.json` claims `minimumHEVersion: 2.3.0`. The app decodes Visual Rule Builder
2.0 (`DECODED_ENGINES_TEXT`, :116) - a feature still in beta on the 2.4.x line - and depends on
`/hub2/appsList` (`fetchInstalledAppIds()`, :976), whose availability on 2.3.0 isn't
established anywhere in the repo. Degradation is graceful in both cases (undecoded engines are
counted and reported; a missing app list falls back to device-led discovery), so this is an
unverified claim rather than a crash risk - but it's still a claim the manifest makes to every
installer.

Fix: raise `minimumHEVersion` to the oldest firmware actually tested, or note in the README
that 2.3.0 is a floor for the core map only.

**Fixed 2026-08-21.** Gordon confirmed testing was only ever done on 2.5.1.152. Manifest raised
to `"2.5.1"` (HPM's 3-part semver, matching every other package in this portfolio, not the
4-segment build number). README's requirement list now states the tested platform directly
instead of caveating an unverified floor.

### P1 - README understates what the browser fetches, and two small manifest/doc mismatches

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact on both counts.

README says "The graph and flowchart libraries load from a CDN," but doesn't mention cdnjs for
the device icon font (:3491 - without it every icon node renders as a blank box) or GitHub for
the watermark and two sound effects (:3746, :6327, :6346). Separately:
`dateReleased: 2026-08-19` in the manifest predates same-day commits that landed after it on
`main` (`41284c4`, `b39b59f`); and the README says rule-to-rule links and Hub Variable edges are
read "from Rule Machine 5.1 only," while the code actually gates on `startsWith('Rule-')` (:1185
- any Rule Machine engine) and runs `extractRuleLinks()` (:1168) against every app
unconditionally. The gating comment at :1170-1184 explains that choice deliberately; the README
just describes the older, narrower rule.

Fix: list the actual hosts in Requirements and limitations (or make it moot by fixing the CDN
and asset-hotlinking items above); update the two doc/code mismatches - both are one-line edits.

**Fixed 2026-08-21**, except the asset-hotlinking item itself, which is unstarted. README now
lists cdnjs and GitHub alongside the CDN, and corrects the Rule Machine engine-gating claim to
match the code (any Rule Machine engine for Hub Variable edges, no engine gate at all for rule
links). `dateReleased` already matched its last commit day by the time this was checked, so
that half of the pairing needed no change.

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

**Decided 2026-08-21:** link to the bare homepage, as originally shown. Already how it works -
`communityUtilitiesBtn`'s click handler has opened
`https://gordonthelander.github.io/HPM_Manifest_Crawl/` in a new tab (`noopener`) since 2.0.0.
This decision confirms the existing behaviour rather than describing unbuilt work; the open
question above is now closed with no code change required.

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

**Clarified 2026-08-21.** Gordon's intended shape is narrower than the original title suggests:
click one app and walk outward N hops from it, not an arbitrary multi-select across the whole
map. That makes this the same interaction as "Extend the displayed map by one level" below,
just generalised past a single hop - see that item for the open question of what determines N.
Worth tracking as one item once picked up rather than two.

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

**Closed 2026-08-21.** Gordon considers this done via the icon work already shipped (every
device/app picklist entry now carries its icon/engine-tag). The parent/child ordering and
room/category grouping proposal above was never built and isn't being picked up separately.

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

**Open question, 2026-08-21:** what determines "one level"? A fixed single hop per click that
can be repeated (as described above), a user-chosen N up front, or unbounded extension until the
result stabilizes - each implies different UI. Also converges with "Multi-select an
interconnected subgraph" above, clarified the same day as essentially this same interaction
generalised to picking the starting point freely - resolve the two together.

### P2 - Local Variables are drawn as Hub Variables

**Source:** JimB testing, 2026-08-17. "Local Variables are identified as Hub Variables. Not
sure that we need to see Local Variables."

Investigation needed before deciding the fix: confirm whether the scanner is genuinely
conflating Hubitat's Local Variables with Hub Variables (a labelling bug), or whether Local
Variables are correctly detected but there is no case for showing them on the map at all (a
scope decision - Local Variables are private to one app, so a graph about cross-app
relationships may have nothing useful to say about them). Resolve which before touching code.

**Gordon, 2026-08-21: confirmed as the next investigation to pick up.** Not yet started - the
next step is reading how the scanner distinguishes (or fails to distinguish) the two variable
types before deciding which of the two explanations above is correct.

### P2 - A single icon per Hub Variable, so all of its connections are visible at once

**Source:** JimB testing, 2026-08-17. "Hub Variables should be identified by a single icon so
all connections for each Hub Variable can be seen."

Underspecified - needs clarification with Jim before design: is this about visually
distinguishing Hub Variable nodes from other node types at a glance (an icon convention, like
device icons), or about being able to select one Hub Variable and see every rule that reads or
writes it highlighted together (a selection/highlight behaviour)? The acceptance criteria differ
substantially between the two readings.

**Parked, 2026-08-21.** Gordon: hold this rather than pursue now. Revisit if the underspecified
question above gets answered on its own, or if this resurfaces as a concrete need.

### P2 - IPv6 loopback with a port is drawn as an external system

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact.

`hostFromUrl()` (:3002-3020) strips a trailing `:port` only when the host contains no `]` - a
guard meant to protect bracketed IPv6 literals, which also disables port-stripping for them
entirely (:3016). `http://[::1]:8080/hub/reboot` yields `[::1]:8080`, which misses
`LOOPBACK_HOSTS` (`'[::1]'` with no port, :2998), so a rule posting to the hub over IPv6 is
drawn as an outside dependency instead of "This hub." Unbracketed and no-port cases are both
correct.

Fix: when `]` is present, look for the last `:` after it rather than skipping the strip
entirely.

**Fixed 2026-08-21.** `hostFromUrl()` now looks for a colon after the closing bracket when one
is present, instead of skipping port-stripping for every bracketed host.

### P2 - One unescaped value in an otherwise consistently-escaped page

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact.

Escaping in this file is otherwise consistent (`jsonForScriptEmbed()`, `extEsc()`,
`mermaidEscape()`). The one exception: a scheduled job's cron string is concatenated into
`innerHTML` raw (:4768 - `'<code>' + j.cron + '</code>'`) while the timestamp beside it goes
through the safe path. The source is the hub's own scheduler, so this isn't a live hole, but
it's the kind of gap that survives a refactor into somewhere it does matter.

Fix: wrap in `extEsc()` like its neighbours.

**Fixed 2026-08-21.** The cron string now goes through `extEsc()` before `innerHTML`, same as
the timestamp beside it.

### P2 - Saving a preference can fire unbounded HTTP inside a web request

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact.

`buildGraph()` resolves unknown rule targets through `linkedRuleName()` -> `fetchAppName()`
(:2401 -> :2364), a synchronous loopback `httpGet` per unresolved id, cached per build but
unbounded in count. Both POST handlers - `/externals` and `/icon-overrides` - call
`buildGraph()` directly (:3214, :3286). A user saving an external-system declaration on a hub
with many dangling rule references pays for HTTP calls plus a full graph rebuild inside the
request - the same inline-work pattern already documented (:661) as having killed
`finishScan()` once.

Fix: answer the POST and rebuild on a `runIn(1, ...)`, the pattern the scan chain already uses.
Fully resolved if the graph-derivation architecture item below is taken on: saving a
declaration becomes a write plus a client-side re-derive, no hub work beyond persisting the
row.

**Fixed 2026-08-20 - the immediate fix, not the architecture change.** Both handlers now call
`runIn(1, 'rebuildStoredGraph')` instead of running `buildGraph()` inline; the new
`rebuildStoredGraph()` helper sits right after `buildGraph()`'s own definition. Confirmed safe
to defer: neither `externalsJson()` nor `iconOverridesJson()` (the JSON each handler returns)
reads `state.graph` - both derive their response from `state.appInfo`/`state.deviceLabels`/the
just-saved preference directly, so the response is unaffected by the rebuild happening a second
later. The graph-derivation architecture item below is still open and would remove this class of
problem entirely rather than defer it.

### Fixed - No shared request wrapper; seven call sites repeat the same contract

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact (all 7 sites, distinct timeout literals as described).

Every hub fetch is a bare `httpGet` with its own inline try/catch, its own `resp.data
instanceof Map` guard, its own timeout literal (10 at :536/:1025/:2367, 20 at :1071, 30 at
:818/:938/:979), and its own error-recording convention. The failure contract is defined seven
times and isn't quite the same twice - `fetchAllDeviceIds()` needed an explicit "sets scanError
itself and returns on failure" comment at its call site to be usable safely.

Fix: one private wrapper taking path, timeout, and a name, returning a normalized `[ok, data,
error]`, with the loopback base URL and the `instanceof Map` coercion in one place. Shortens all
seven sites, makes timeout choice visible as data, and gives a single point to add retry or an
async variant later.

**Fixed 2026-08-21.** Added `httpFetch(uri, timeoutSec, extraOpts = [:])`, returning `[ok, data,
error]`. Deviated from the suggestion in one place: `data` is returned exactly as the response
sent it rather than coerced to Map inside the wrapper, since `fetchAllDeviceIds()` needs a List
- coercion stays at each call site, which already knows its own endpoint's real shape. A
`LOOPBACK_BASE` constant replaces the six repeated `http://127.0.0.1:8080` literals;
`fetchRegistry()`'s external `REGISTRY_URL` call goes through the same wrapper via `extraOpts`
for its `contentType`. All 7 sites converted (`probeCompatibility`, `fetchRegistry`,
`fetchAllDeviceIds`, `fetchInstalledAppIds`, `fetchDeviceApps`, `fetchAppRelationships`,
`fetchAppName`) with no behaviour change at any of them - each site's own error-handling,
logging, and fallback logic is untouched, only the fetch mechanics moved. Verified: `groovyc`
clean, `check_template.sh` clean, zero raw `httpGet([uri:` calls remain outside the wrapper
itself.

### Fixed in 2.0.4 - Show the last scan's date/time on the app page

**Source:** oldcomputerwiz, forum feedback, 2026-08-21 - "list the date that the last scan was
done on the app page (maybe next to the scan button?)."

Already trivial to build: `state.scanHeartbeat` is stamped throughout the scan chain and
already surfaced to the AI export (`lastScanCompletedAt`) and used internally for abandoned-scan
recovery - it has just never been formatted and shown next to the Scan button itself, alongside
the existing "Last scan: X of Y" progress line.

**Fixed 2026-08-21, shipped in v2.0.4 on both branches.** The "Last scan" line now appends
` - yyyy-MM-dd HH:mm` (hub timezone) whenever `state.scanHeartbeat` is set. Verified live on
both the Dev and production hub instances.

### P3 - Search Hub Variables

**Source:** JimB testing, 2026-08-17, offered as "a future idea", not a request against
v2.0.0. Add a search box to whatever Hub Variable listing/panel exists, matching the
search-box pattern already used for the app/device filter dropdowns.

**Confirmed as wanted, 2026-08-21.** Gordon agrees it's a good, low-friction addition - stays
P3 (small, no dependencies) but is now an accepted candidate rather than just an idea offered
in passing.

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

**Rejected 2026-08-21.** Gordon: disqualified by the same property that made this need a
definition in the first place - the map is a graph with shared nodes and cycles, not a tree, so
"downstream" has no single honest meaning to hide by. The design questions above were never
answered because there isn't a version of this that stays simple once a node has more than one
path back in.

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

**Reframed, 2026-08-21.** Gordon sees a different, arguably stronger case than the original
scan-performance framing: how does a user's configured data (icon overrides, notes, external
system declarations, everything that isn't derivable from the hub itself) move to a new or
replacement hub on an upgrade? That's a real gap - none of it currently has an export/import
path at all. It doesn't remove the structural problem already identified above (device/app IDs
are hub-assigned and don't survive a hub swap, so a raw scan-result import still can't
reconstruct itself on different hardware without Hubitat's own backup/restore recreating the
apps and devices first), but it changes *why* this is worth building. Still deferred - needs
its own design pass, now specifically around "what survives a hub swap and how it reattaches to
the new hub's IDs" rather than the original warm-start framing.

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

**Rejected 2026-08-21.** Gordon: this is the wrong place for it - apps and devices already have
their own notes fields natively in Hubitat. Duplicating that inside Automation Map would give
users two places to look for the same kind of information rather than one.

### P2 - Cross-reference the map with hub runtime data to surface the heaviest/most active rules

**Source:** hubitrep (community code audit author), forum feedback on the audit writeup,
2026-08-20/21 - "Consider cross-referencing the analysis with the runtime data available on the
hub, to surface the heaviest rules or those that run most often."

Distinct from anything currently on the map: everything drawn today comes from *configuration*
(what an app is set up to touch), not *behaviour* (what actually ran, how often, or how long it
took). Genuinely useful on a large hub - `oldcomputerwiz`'s forum report of a scan on 322
devices/444 apps took long enough that they didn't want it running daily, and knowing which
rules are actually hot would help prioritise where to look first.

**Gating question closed, 2026-08-21.** hubitrep identified `/logs/json` and it checks out -
verified live against Gordon's hub. One unauthenticated local GET, no per-app fan-out at all:
top-level `appStats[]` (150 entries on this hub) and `deviceStats[]`, each with `id`, `total`
(cumulative ms), `count` (execution count), `average` (ms/execution), `pct`/`pctTotal` (share of
app/hub runtime), `stateSize` (bytes, confirmed - `formattedStateSize` is the same number with
thousands separators only, no unit conversion, so `"25,955"` is ~25KB not 25MB), `largeState`
(the hub's own oversized-state flag), plus `hubActionCount`/`cloudCallCount`/
`pendingEventsCount`. `appStats[].id` joins directly onto the `installedapp/statusJson/{id}`
endpoint this app already keys everything on - confirmed live (id `2869` in both). Note per
hubitrep, not independently reproduced: `appStats[].name` is the installed app's *label*, joins
to this app's `out.label` not `out.type` - easy to cross the two by mistake. Also per hubitrep:
these are cumulative since last reboot/stats reset, so "heaviest" means total-over-uptime, not a
current rate - a rate needs two samples or a divide-by-uptime.

This closes the original gating question (does Hubitat expose this without a fetch storm) with
a clean yes - one cheap call, not a P1/P2-style scaling problem like the scan itself. What's left
is design, not investigation: which of `total`/`count`/`pctTotal`/`largeState` to surface and
how (a sortable column in Insights, a size/colour cue on the graph itself, a dedicated panel).

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

## Architecture - hub/browser placement (from the 2026-08-20 code audit)

The shared root cause behind several items above: the hub is a 4-core ARM Cortex-A53 sharing
memory with every other app on the device; the browser rendering the map is, for this workload,
an order of magnitude faster with effectively unbounded memory. The app already honours that
split for most of its analysis (pivot tables, Insights, mermaid layout, focus/filter set
operations, CSV export, the whole AI export payload are all computed client-side, from data the
page already holds). These items are the places where derivable work stayed on the constrained
side. Dividing line to apply to anything new: ship raw, let the page derive. Sorts, set
differences, top-N, threshold classification, display-string shaping and percentage rollups
belong in the browser; irregular-payload parsing and fetch coalescing belong on the hub. The
scrape itself, response normalization, and rule-flow decoding (reverse-engineering Rule
Machine's private storage layout) are correctly hub-side and should stay there.

**Deferred to v3.0, 2026-08-21.** Gordon's call on all three items below: high effort and risk
for what would be, functionally, very little the user actually notices - the map would look and
behave the same either way, the benefit is scan speed and headroom for larger hubs, not new
capability. Worth doing eventually, and worth doing together rather than piecemeal once it's
picked up, but not a routine addition to any release before v3.0.

### P3 - Stop computing and persisting the derived graph on the hub

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact. This is a design change, not a bug fix - flagged by the auditor as
the item that decides how far this app can scale, not something to rush.

Three separate mitigations in this file address the same root cause: dropping the previous
graph before a rescan rather than holding it (`state.graph = null`, :612-618), stripping flows
from `appInfo` once they're in the graph because they were "61KB of a 244KB state... held
twice" (:889-897), and fetching a slim registry because the canonical 165KB one "was enough to
kill the execution that fetched it, silently." Nothing caps, sheds, or pages the graph itself,
so a hub several times the current dev hub's size hits the same wall from a different
direction - this is the app's real scaling limit, not CPU.

The structural reason: state holds two representations of the same information at once.
`state.appInfo` is the raw scrape; `state.graph` (written at :886, :3214, :3286) is
`buildGraph()`'s derivation from it. Stripping flows afterward recovers part of the
duplication, not the design that creates it. The quadratic-dedup and unbounded-inline-HTTP
items above are both consequences of this one decision, not independent defects.

Suggested direction: persist the raw scan result only, ship it to the page, and assemble the
graph there - the page already builds far more complex derived views from this same data. Halves
stored state, removes the dedup and inline-HTTP items above as a side effect (both POST
handlers stop needing to rebuild anything server-side). Snapshot semantics are unaffected: the
scan stays expensive and infrequent, the page still renders a fixed picture of the last one -
only the derivation step moves. If too large to take on now, state a supported hub size in the
README so the failure mode is expected rather than mysterious, and move the graph blob to File
Manager (written once per scan, read only by the map page).

**Sizing estimate reset, 2026-08-21.** The original public estimate (forum, 2026-08-12, ~80
bytes of app state per node/relationship) put the comfortable ceiling at roughly 2,500-3,000
combined nodes+relationships, ~600 devices and ~250 apps. `oldcomputerwiz`'s C5 hub (forum
feedback, 2026-08-21) - 322 devices, 444 apps, 91 Hub Variables, 3662 edges - is 857 nodes plus
3662 edges, ~4,500 combined, well past that old estimate, and it completed successfully. So the
hard ceiling is higher than originally estimated - real evidence now says at least ~4,500
combined items works, actual failure point still untested beyond that. Splitting this into two
separate numbers going forward rather than one: a **completes-at-all** ceiling (now known to be
higher than first thought) and a **comfortable/fast** ceiling (this hub was slow enough that the
reporter didn't want it running daily - that threshold is somewhere below 4,500 and still
unmeasured). This item (moving derivation to the browser) raises the first number by removing
the hub-side build/storage risk; it does not by itself fix the second, which is dominated by the
batch/scheduling loop - see the delta-scan idea below for a lever that actually attacks that one.

### P2 - Delta scan: only re-fetch what's changed since the last scan

**Source:** Gordon, 2026-08-21, prompted by `oldcomputerwiz`'s large-hub speed report above.

Every scan today does a full walk regardless of what changed: every device gets
`fetchDeviceApps()`, every app gets `fetchAppRelationships()`, same cost whether the hub is
untouched since yesterday or heavily edited. For the scheduled/automatic scan case especially,
most nights very little actually changes, so most of that work is repeated for nothing. Unlike
the graph-derivation item above (which mainly buys reliability/state-size headroom, not scan
*time* - see the sizing note above), a real delta mechanism would cut the dominant cost directly:
fewer items means fewer of the ~48 batches and their `runIn(1, ...)` scheduling hops, which is
where most of a scan's wall-clock time actually goes.

The gating question, not yet investigated: does Hubitat expose any cheap, lightweight signal
that a device or app's configuration changed since a given time, without fetching its full
relationship data to find out? If no such signal exists on the endpoints this app already uses
(`/device/fullJson/<id>`, `/hub2/appsList`, `/installedapp/statusJson/<id>`), a delta scan saves
nothing - fetching a device just to check whether it changed costs the same as fetching it to
read its relationships, so there's no shortcut without that signal. This needs checking against
the actual endpoint responses before anything else here is worth planning.

If a usable signal does exist, the harder design question is correctness under a false negative:
reusing stale data for something that changed but wasn't detected means the map is silently
wrong until the next full scan, with no indication on screen that it happened. Whatever design
comes out of this needs an explicit staleness model (e.g. always run a full scan on some cadence
regardless of deltas, or surface which parts of the map are how old) rather than trusting
incremental detection alone indefinitely.

Not scoped further than this; the endpoint investigation above decides whether it's worth
scoping at all.

**Clarifying evidence, 2026-08-21**, from reviewing hubitrep's own `HubDiagnostics` app
(`github.com/hubitrep/hubitat`, `HubDiagnostics/`) - a mature, independent Hubitat diagnostics
tool by the same person who ran the code audit. Its own config-drift feature ("Snapshots") does
*not* use a cheap per-item change signal either - it captures a full snapshot (complete device
list, app counts, network config, etc.) and diffs two full snapshots against each other after
the fact, per its README. That's a different technique from what this item originally wanted
(skip fetching the unchanged majority) - it still pays the full capture cost every time, just
gets a structured diff at the end instead of eyeballing it. Weak evidence, from one data point,
that no cheap "has this changed" signal exists on the platform for this to lean on: if it did,
a tool this deep into Hubitat's internals would be a likely place to see it used. Doesn't close
the gating question, but lowers confidence that this item is buildable as originally conceived.

### P1 - Rebuild the scan: bulk list + per-driver capabilities + async concurrency

**Source:** reviewing hubitrep's `HubDiagnostics` app (`github.com/hubitrep/hubitat`,
`HubDiagnostics/`) 2026-08-21, then measuring the ideas against Gordon's own hub directly.
Supersedes the earlier, vaguer version of this item. This is the single biggest scan-speed
finding in this file, and unlike the two reverted speed experiments elsewhere here, every claim
below was measured live rather than reasoned about.

**Measured baseline** (Gordon's C-8, 194 devices / 103 apps, from the live 2.0.4 scan):
device phase ~60s, app phase ~58s, ~124s total. Pure HTTP cost measured separately: ~34s
serial for the devices, ~42s serial for the apps, ~76s combined. The ~48s gap between 76s of
HTTP and a 124s scan is almost exactly the ~48 batches x `runIn(1, ...)` - confirming, with
numbers, the diagnosis already recorded in the reverted-experiments entries below.

Three independent findings, each verified on the hub:

**1. The per-device `appIds` walk is redundant - `/hub2/appsList` is a strict superset.**
Fetched every app id from `/hub2/appsList` (105), then fetched all 194 devices' `appsUsing` +
`parentApp` and unioned them (80). Apps discoverable *only* by walking devices: **zero**. Apps
only the listing finds: 25. So device-led app discovery contributes nothing the listing does not
already provide, and `fetchDeviceApps()`'s `appIds` extraction can go entirely. One hub, one
sample - worth re-checking on a hub with unusual child-app nesting before relying on it.
Knock-on: `state.appsFromListing` and the settings-page sentence built from it ("N of them
reference no device and would not have been found by walking devices alone") become
meaningless if device-led discovery is removed, and would need rewording.

**2. Capabilities are a property of the driver, not the device - fetch once per driver type.**
`/hub2/devicesList` returns all 194 devices in ONE call carrying `id`, `name` (the label),
`type` (the driver name), `roomName`/`roomId`, plus `deviceTypeId`, `isOrphan`, `disabled`,
`dashboardTypes`, and parent/child structure. That covers three of the five fields
`fetchDeviceApps()` currently pays a per-device call for (label, room, type); combined with
finding 1, **capabilities is the only remaining reason to fetch a device individually at all**.
And there are only **34 distinct `deviceTypeId` values across 194 devices**. Tested whether
devices sharing a driver share capabilities: sampled up to 4 devices in each of the 22 driver
types that have more than one device - **0 mismatches**. So capabilities can be fetched once per
driver type and applied to every device on that driver. (Sampling caveat: up to 4 per type on
one hub, not exhaustive. A composite/child driver that varies its capability list per instance
would break this; none seen here. Worth a guard that falls back to a per-device fetch if a
driver ever looks inconsistent.) Also probed for a bulk capabilities endpoint and found none -
`/device/list/data` (200, 194 entries) carries `deviceTypeName`/`label`/`roomId` but no
capabilities either; `/hub2/devicesCapabilities`, `/device/capabilities/json` and
`/hub2/deviceCapabilities` are all 404.

**Measured result of 1 + 2 together: the device phase drops from ~60s to 3.7s** - one
`/hub2/devicesList` call (1.0s) plus 34 driver-representative `fullJson` calls 8-way concurrent
(2.6s). That is a ~16x reduction, and it comes from making far fewer calls, not from tightening
any scheduling loop.

**3. Async concurrency, for what genuinely must stay per-item (the app phase).**
Every app still needs its own `/installedapp/statusJson/{id}` - that IS the relationship data,
there is no bulk equivalent. Measured: ~42s serial vs **12.2s at 8-way concurrent (3.5x)**. The
device side benefits less (34s serial vs 20s concurrent, only 1.7x) because the hub serialises
a lot internally - useful to know, since it means concurrency alone was never going to deliver
the 8x the connection count suggests. Concurrency is the smaller of the two levers; finding 2 is
the bigger one.

**The implementation pattern, read from HubDiagnostics' source directly** (cloned and read, not
summarised): `dispatchOne(scanId)` CAS-reserves a slot against `AUDIT_MAX_INFLIGHT = 8` via an
`AtomicInteger`, polls the next id off a `ConcurrentLinkedQueue`, and issues
`asynchttpGet('fullJsonCb', params, [scanId: ..., deviceId: ...])`. The callback
`fullJsonCb(resp, data)` records the result, increments `processed`, decrements `inFlight`, then
either dispatches the next pending id (keeping the pipeline full) or finalises. Startup is
`AUDIT_MAX_INFLIGHT.times { dispatchOne(scanId) }` - and note there is **no `runIn` between
items at all**; the only `runIn` is a single `runIn(120, 'auditWatchdog')` safety net for a
genuinely lost callback. That is the structural difference: the whole walk is one self-refilling
async pipeline, not N scheduled batches.

**Two traps that pattern implies for this app, both documented in their code:**
- Results must accumulate in a `@Field static final ConcurrentHashMap` keyed by scan id, **not
  in `state`**. Their `apiAuditStatus` comment spells out why: `state` writes from up to 8
  concurrent callbacks have last-write-wins semantics, so a `state`-based counter lags or loses
  data. This app's `scanDeviceBatch()`/`scanAppBatch()` write straight into `state.*` maps today,
  so this is not a small change to the accumulation model. (Mutable `@Field static` is used
  throughout their app and works; this repo's own notes carry a caution about statics in the
  Hubitat sandbox, so confirm with a small test before building on it.)
- Their finalisation check re-reads `processed` fresh rather than trusting the callback's own
  local value, with a long comment explaining that `processed` and `inFlight` are independent
  atomics and the callback that zeroes `inFlight` is not necessarily the one whose increment hit
  the total. Worth copying that reasoning rather than rediscovering it via a scan that never
  finalises.

**Projected combined effect: ~124s -> roughly 16-20s of fetching** (3.7s devices + 12.2s apps),
plus graph-build time, on this hub. Even taking only finding 2 and leaving the app phase
batched, the device phase alone gives back ~56s. Caveat on all projections: these were measured
with Python threads against the hub, which bounds what the HTTP work can cost but does not
prove Groovy's `asynchttpGet` scheduler achieves the same throughput in the app sandbox.

**Findings 1+2 built and live-tested, 2026-08-21.** `fetchDeviceListBulk()` and
`fetchDeviceCapabilities()` implemented, pushed to the Dev hub instance, groovyc/
`check_template.sh` clean, then run through a full fresh scan and verified against the actual
result rather than just measured HTTP timing:

- **Device phase: ~60s -> ~17s** (`scan-status` polling showed the devices phase already
  finished, 0/104 apps, by t=17s). Total scan **124s -> 101s**. Smaller than the pure-HTTP 3.7s
  projection above because this deliberately kept the existing `DEVICE_BATCH_SIZE`/`runIn(1,
  ...)` batching in place (3 batches of ~15/15/4 representatives instead of touching the
  scheduling model) - the 3.7s number was HTTP time alone, not scan wall-clock through the
  batch loop. App phase unchanged, as expected - findings 1+2 don't touch it.
- **Correctness, checked against the live result, not assumed:** 0 devices/apps unreadable, 61
  rules decoded (identical to every prior baseline scan this session), all 194 devices got a
  label/room/capabilities with zero empty-capability or missing-label rows. 31 distinct
  capability signatures across 194 devices (34 driver types) - the per-driver-sharing
  assumption held on a full live scan, not just the earlier 4-device sample. Both icon features
  built earlier this session still auto-detect correctly post-rewrite: `CoCoHue Bridge 988DA0`
  -> `hub`, the Alert Group Scene devices -> `scene`.

**Finding 3 built and live-tested, 2026-08-21.** Async concurrency for the app phase, following
the sequencing plan above - self-refilling `asynchttpGet` pipeline capped at 8 concurrent calls,
mirroring the pattern read directly from hubitrep's `HubDiagnostics` source, replacing the
`APP_BATCH_SIZE`/`runIn(1, ...)` batch loop for apps entirely (the device phase's batch loop is
untouched).

De-risked before building anything real: the whole feasibility of this rests on mutable
`@Field static` state surviving correctly across separate concurrent `asynchttpGet` callback
executions, exactly the pattern this repo's own history flags as a Hubitat sandbox risk that
local `groovyc` cannot catch. Built a throwaway isolated test first (8 concurrent callbacks each
incrementing a static `AtomicInteger` and writing to a static `ConcurrentHashMap`), ran it live
against the Dev hub instance 4 times back-to-back - 8/8 landed correctly every time, zero lost or
duplicate writes - then removed the test scaffold and built the real feature on a confirmed
assumption rather than a borrowed one.

**A genuine hub-vs-local compiler gap surfaced during this build**, worth remembering: local
`groovyc` accepted `state.appsDecoded = (state.appsDecoded ?: 0) as Integer + (scan.decoded as
AtomicInteger).get()` without complaint; the hub's own parser rejected it outright
(`expecting '}', found '+'`) - `as` and `+` are ambiguous at that precedence and the two parsers
resolve it differently. The push failed cleanly with an error response, no live breakage,
lock unconsumed - confirms the lock-based push flow fails safe. Fixed with explicit parens
(`((x ?: 0) as Integer) + y`). Filed as a fact about this toolchain, not solved once and
forgotten: local `groovyc` is a necessary check, not a sufficient one, for anything using `as`
next to an operator.

**Live-tested result, full scan on Gordon's C-8 (194 devices, 104 apps):**

| | Original | Findings 1+2 | Findings 1+2+3 |
|---|---|---|---|
| Total scan time | ~124s | 101s | **50s** |
| Device phase | ~60s | ~17s | ~17s (unchanged) |
| App phase | ~58-84s | unchanged | **~23s** |

Correctness checked against the live result across three independent test runs this session
(original baseline, findings 1+2, findings 1+2+3), not assumed to carry over: **61 rules
decoded, 0 apps/devices unreadable, identical every time.** The live map page was loaded and
inspected directly (not just the scan-status API) - `vis-network` rendered 315 nodes, matching
the scan summary's own count exactly, "Devices: 194 Apps: 103" (104 found, 1 self-exclusion, as
expected).

One accepted, narrow gap: the watchdog (`appAsyncWatchdog`, 90s) finalizes with whatever was
collected if callbacks stop arriving entirely - an app that never gets a callback at all (not
even a per-item failure, genuinely no response) is simply absent from `appInfo` with no visible
"this one went missing" indicator, unlike a normal per-app fetch failure which is tracked and
shown. Rare-case safety net, not the normal path; not solved further here.

Committed to `dev` only (`362dd6a` + the parser-precedence fix `e3f0eca`), not merged to `main`.

Sequencing suggestion if picked up: finding 2 first (biggest win, smallest blast radius - it
changes what `fetchDeviceApps` fetches, not how the scan is scheduled), then finding 1 (removes
code, needs the settings-page wording change), then finding 3 last (the real architectural
change, since it replaces the batch/`state` accumulation model wholesale). hubitrep offered help
on the P7 item elsewhere in this file and would be the obvious person to sanity-check finding 3
against their own implementation.

**Device phase also converted to the same async pipeline, 2026-08-21, all four levers now
combined.** After finding 3 shipped, Gordon noticed the device phase - untouched by findings
1+2's batch-loop-still-in-place design - now visibly dominated the scan: ~14s wall-clock for
work that only takes ~3.7s of real HTTP time, the same batch/`runIn(1, ...)` overhead already
known to be expensive, just now a much bigger fraction of a much smaller total. Extended the
proven app-phase pattern to the 34 driver-representative capability fetches:
`dispatchDeviceOne`/`deviceFetchCb`/`deviceAsyncWatchdog`/`finalizeDevicePhase`, mirroring their
app-phase counterparts exactly, `DEVICE_SCANS` alongside `APP_SCANS`. `scanBatch()`,
`scanDeviceBatch()`, `DEVICE_BATCH_SIZE`, and the now-redundant synchronous
`fetchDeviceCapabilities()` all removed - both phases are self-driving async pipelines now,
nothing schedules a batch loop for either one.

**Result: total scan time 50s -> 17-19s.** Verified stable and correct on a clean, isolated run
(not rapid-fire tested): 61 rules decoded, 104 apps read, 0 unreadable, `graphVersion` set,
identical to every prior baseline.

**Two more bugs found and fixed during this round, both worth remembering:**

- Another hub-vs-local parser gap, same shape as finding 3's: `state.appsDecoded = (x ?: 0) as
  Integer + y` compiled clean locally, hub rejected it (`expecting '}', found '+'`). Fixed the
  same way, explicit parens. Reinforces the earlier note - this is a recurring toolchain gap for
  `as` next to an operator, not a one-off.
- **A real double-counting bug**, caught live: `finalizeAppPhase` wrote `state.appsDecoded =
  ((state.appsDecoded ?: 0) as Integer) + scan.decoded.get()` - accumulating onto whatever state
  already held, rather than replacing it. `scan.decoded.get()` is already the complete count for
  that scan; there was never a reason to add it to anything. Surfaced during rapid back-to-back
  testing as `appsDecoded: 169` with only 104 real apps on the hub. Root cause not fully proven
  at the exact mechanism level, but strong evidence points to stale `runIn()` watchdog schedules
  from earlier test-scan generations: Hubitat's job scheduler persists `runIn()` entries across a
  code push even though recompilation resets the static accumulator maps
  (`APP_SCANS`/`DEVICE_SCANS`), so a stale watchdog from an old generation can fire during a
  later one. Confirmed by waiting 150s before a fresh test - the corruption did not recur.
  Very likely specific to this session's testing pattern (rapid re-triggering combined with
  redeploying source mid-scan) rather than something a normal user would hit - the existing
  `state.scanRunning` guard on `/scan` already blocks a genuine double-start through normal use,
  and normal use never involves pushing new source while a scan is in flight. Fixed regardless
  of exact mechanism: changed `+=` to `=`, which is idempotent - even if finalize is ever invoked
  more than once for the same scanId by some future edge case, it now writes the same correct
  value twice rather than compounding an error. `finalizeDevicePhase` was already correct (uses
  `putAll`/membership-check merges, naturally idempotent), only the four app-phase counters had
  the bug.

Committed to `dev` only (`efecbc5` device-phase extension, `bc2c8ec` the `+=`->`=` fix,
`530628a` diagnostic cleanup), not merged to `main`.

**Reverted, 2026-08-21 - a data-integrity issue neither found bug explains.** After the counter
fix above, a fresh isolated scan still showed `state.appInfo` (the durable per-app result) with
more entries than the scan's own `decoded`+`unreadable` counters could account for - confirmed
via a targeted diagnostic that the scan's own static accumulator was correct (61 entries,
matching `decoded=61` exactly) at the moment of merge, meaning something overwrote
`state.appInfo` with a stale snapshot *after* the correct write landed.

Read hubitrep's actual installed `HubDiagnostics` source directly (not the GitHub copy) looking
for how their own async device-audit pipeline handles this, and found their own documented fix
for the identical platform behavior: a code comment citing their own past issue ("R-6 G1")
where concurrent `asynchttpGet` callbacks writing to `state` exhibit last-write-wins lag under
load, verified by their own testing. Their answer: never let the real result touch `state` at
all - `finalizeAudit()` stores the actual device data in a `@Field static volatile Map`,
explicitly documented as "lost on hub restart (acceptable)" for an on-demand audit tool.
`state.audit` is only ever a tiny, disposable progress scalar, never the real data, so even
when a stale callback clobbers it, nothing important is lost.

That pattern isn't portable to Automation Map as-is - `state.appInfo`/`state.graph` surviving a
hub reboot is core to what this app is for, not an optional convenience. A real fix likely needs
two changes informed by this comparison: stop writing `state.scanDone`/`state.scanHeartbeat`
from inside every callback at all (read progress from the static `AtomicInteger` directly, same
conclusion hubitrep reached), and don't call the finalize step inline from the terminal
callback's own execution - schedule it, so the `state.appInfo` write happens in an execution
cleanly separated from the concurrent callback burst rather than racing inside it. Neither change
is proven to fully close the window - hubitrep's own system still has some residual exposure for
`state.audit`, it's just harmless there because the real data was never at risk. Given the
stakes (a wrong map, not just a slow one) and that this needs real platform expertise to resolve
with confidence, reverted `apps/automation_map.groovy` exactly to the pre-finding-3 baseline
(`f9d9228`, v2.0.4) rather than ship something not fully understood. Findings 1+2 (bulk device
list, per-driver capabilities) involved no async concurrency and no state-write timing issues
were ever observed with them - only finding 3 and its device-phase extension are being given up
here. Worth asking hubitrep directly before re-attempting, given they've already fought this
exact battle once.

**Reapplied as v2.0.5, 2026-08-22 - hardened, dev-soak candidate, not yet production.** Read
hubitrep's actual `HubDiagnostics` source directly (as speculated above) and found their own
documented fix for the identical platform behavior: never let concurrent callbacks write real
results into `state` at all. Rebuilt findings 1+2+3 on top of that insight, taken further than
HubDiagnostics needed to go since this app's results must survive a reboot and theirs don't:
per-item claims with attempt tokens, atomic conditional-ownership retirement
(`claims.remove(id, exactClaim)`), a missing-callback reaper, exact completion invariants
(`==`, not `>=`), and durable publication only from a separately scheduled execution, never
inline inside a callback. Reviewed and hardened through several rounds before any live test - two more real bugs found and fixed purely
through that review, before ever touching a hub: callbacks were still writing progress fields to
`state` (recreating the exact race this exists to prevent), and the watchdog's failure path
could race a legitimate finalize and overwrite a successful publication with a false failure.

Controlled dev-hub test (instance 3050, code id 1189, production id 1188 never touched): four
scans at 21.1-25.5s against a 134s same-day serial baseline, identical device data across all
194 devices, one explained app-count difference (an unrelated half-installed app that
independently vanished from the hub's own `/hub2/appsList` between measurements, not dropped by
this code), zero dispatch-throw/reap/watchdog/invariant-violation events on real data, no
post-completion mutation observed 140s past the watchdog horizon. The historical 74-app crash
this section describes was this same hub, not a smaller one - today's test ran successfully at
105 apps, past that scale, with the 08-13 state-footprint fix (drop-not-hold the previous graph)
still in place unchanged.

Two Hubitat-loader restrictions found only by pushing to the hub, not by local `groovyc`:
importing `AtomicLong` is rejected outright (`AtomicInteger` is fine), and one `@Field static
final` cannot reference another's value even in correct declaration order. Full writeup:
`Supporting Docs/async_scan_v205_technical_report.md`; generalized pattern published separately
at `github.com/GordonThelander/hubitat_dev_utililities` ("Transactional Bounded-Async
Discovery"), credited to hubitrep's `HubDiagnostics` as the origin.

Currently on `dev` only, live on the Dev hub instance for a bounded soak (the
recommendation: 24h+, several more scans, checked each time against the same acceptance
criteria as the four controlled runs) before production promotion is considered. Not yet
measured: hub CPU/memory pressure during a scan, and live exercise of the recovery paths
(dispatch-throw rollback, claim reaper) against real data - both still rest on the isolated
harness and code inspection, not a live failure.

### P2 - "Devices nothing references" doesn't check Dashboard tiles, only apps

### P2 - "Devices nothing references" doesn't check Dashboard tiles, only apps

**Source:** hubitrep's `HubDiagnostics` app (`github.com/hubitrep/hubitat`, `HubDiagnostics/`),
reviewed 2026-08-21 for extension ideas.

Its Device Usage Audit builds two reverse indices per its README: "Apps → devices" and
"Dashboards → devices," and flags a device as an unreferenced cleanup candidate only when
neither points to it. This app's existing Insights panel already has the app-side half of that
("Devices nothing references" - no app owns, watches or drives them) but has no visibility into
Hubitat Dashboards at all. A device that looks orphaned by this app's current check could
legitimately be sitting on someone's dashboard as a manual on/off tile with no automation
behind it - a real false positive in an existing, shipped Insight, not a new feature gap.

Not confirmed this session: which endpoint HubDiagnostics reads dashboard-to-device
assignments from - not visible in what was fetched from its README/source. Needs its own
investigation into the dashboard data Hubitat exposes (likely something under `/dashboard/...`
internal endpoints, unconfirmed) before this is scoped further. Two other small ideas from the
same audit feature, noted but not written up as their own items: rendering a disabled app's
device reference with strikethrough so a "ghost reference" is visually distinguishable from a
live one, and flagging devices via Hubitat's own `orphan: true` mesh field as a second,
independent signal alongside the reference-count check this app already does.

### P3 - Move remaining display-shaping and classification logic to the browser

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact (all cited functions and locations). Natural companion to the item
above; smaller in scope, cheapest to relocate first.

Several derivations run in Groovy over data the page already receives, producing nothing the
page couldn't produce itself: `autoDetectIconKeyForDevice()` (:2186) maps capabilities/name
words to an icon key, running once per device node in `buildGraph()` and again per row in
`iconOverridesJson()` (:3292) - twice per scan, over capability lists already shipped to the
browser, which already has `iconsEffectiveKey()` layering the user's override on top. `nodeEntry()`
(:2461) truncates labels for canvas rendering, but label width is a property of the viewport,
which only the page knows. `inertReason()` (:2486) and `compatibilitySummary()` (:473) assemble
presentation strings server-side; the latter has to (it renders the Hubitat settings page), the
former doesn't (consumed only by the map page). Registry matching (`registryMatches()` :3048,
`registryEntryState()` :3072, `externalsForType()` :3121, `classifiedTypes()` :3133) duplicates
set-intersection logic the page already does in `extRowsFor()`/`extRegistryFor()`.

Individually small; together they're the per-node cost multiplier on the graph build the items
above are about, and each bakes a display decision into the stored graph, making it larger than
it needs to be. Icon detection is the cheapest to relocate first - the rules are already a pair
of static tables, and moving them means an icon-rule change no longer invalidates a stored
graph.

### P3 - Serve the map page from File Manager instead of a Groovy GString

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact. Independent of the items above, but arrives free if the CDN/asset
security items are taken on, since those need somewhere local to serve from anyway.

Roughly 310KB of the 368KB app file is the HTML/CSS/JS map page, emitted as one GString from
`buildMapHtml()` (:3431-6433), plus a 29KB base64 PNG constant (:97). This creates a bug class
specific to the arrangement: Groovy consumes backslash escapes before the browser ever sees the
string, so a regex literal, a `join('\n')`, or an apostrophe can silently kill the page script.
`check_template.sh` exists solely to guard against this, and its header records the bug landing
three times already.

The current arrangement buys real things - single-file install with no File Manager step, and
no possibility of the page and the app drifting to different versions - so this is a trade-off,
not an unqualified defect.

Suggested direction: serving the page from File Manager removes the escaping hazard entirely,
brings the page under `node --check`, and cuts the Groovy file to roughly a sixth of its size.
It's also a prerequisite for the cleanest fix to the CDN-integrity and asset-hotlinking items
above, which need somewhere local to serve libraries/assets from - so if those are taken on,
this comes as part of that work rather than extra. Cost: a two-file deploy and a version check
between them.

**Version-skew mitigation proposed, 2026-08-21.** hubitrep (forum feedback on the audit
writeup) proposed the specific mechanism for that "version check between them" cost: embed a
`CODE_VERSION` constant in the served HTML, have the app's sync step verify a downloaded file's
embedded version matches its own before accepting it, and re-sync automatically if the file is
missing (`downloadHubFile`/`uploadHubFile`, both available in the app sandbox per hubitrep - not
independently confirmed this session). A mismatch would fail loudly instead of silently
rendering a stale page against a new API. Doesn't remove the two-file-deploy cost itself, but
does directly answer the drift concern that was the other half of it. hubitrep offered to point
at a working implementation if useful when this is picked up - worth taking them up on given
this session confirmed their earlier audit findings were all accurate, independent of the one
claim (the href tab-fix) that didn't hold up under direct testing above.

### P3 - Registry fetch is a synchronous blocking HTTP call (optional)

**Source:** external static code audit (community, 2026-08-20). Verified against the file
directly - confirmed exact. Auditor's own framing: "a note, not a defect."

`fetchRegistry()` (:818) does a blocking internet `httpGet` with `timeout: 30`. Well contained -
its own scheduled execution, a watchdog behind it, failure is explicitly non-fatal - so this is
low priority. `asynchttpGet` would remove the whole class of risk rather than fence it.

Worth doing only if the registry grows or moves off GitHub - not urgent on its own.

**Confirmed no action needed, 2026-08-21.** Gordon: it isn't blocking anything, it already
fails gracefully. Matches the auditor's own framing above - stays noted, not queued.

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

### Corrupted character in an exported app name (closed 2026-08-19 - not reproducible)

**Source:** Gordon, 2026-08-19, spotted via the app-type tagging work above - a real downloaded
AI friendly export had "Hubitat Dashboard" with its registered-trademark symbol replaced by the
Unicode replacement character.

Investigated rather than guessed: the in-memory client-side data for that exact app, from the
exact same scan the corrupted export came from, had the genuine character (codepoint `ae`, real
(R)), not the replacement character - so the scan fetch was clean. The export/download code
(`exportJSON()`'s `Blob`+`JSON.stringify`) also reads as standards-correct - `Blob` always UTF-8-
encodes a JS string regardless of declared MIME type, and `JSON.stringify` does not touch Unicode
characters. A second export, generated fresh from the identical underlying scan through the
identical code path, came back clean (verified by codepoint, not just by eye). Same data, same
code, different result - rules out a deterministic bug in Automation Map's own logic. Whatever
caused the original corruption was a one-off, most likely transient/environmental on that
specific earlier download, not something in this app to keep chasing without new evidence.

A defensive fetch-layer patch shipped anyway (`stripReplacementChar()`, applied where label/type
first come off the wire in `fetchAppRelationships`) - harmless hygiene, kept in case something
like this recurs, not because it was confirmed to be the actual fix. Re-open only if this is
seen again with something to actually investigate (a reproducible trigger, not just the symptom).

### Speeding up the scan via runInMillis (attempted and reverted, 2026-08-21)

**Source:** Gordon asked what the scan's limiting factor was. Answer at the time: ~48 total
batches (13 device + 35 app, at `DEVICE_BATCH_SIZE`/`APP_BATCH_SIZE`), each paying a full
second of `runIn(1, 'scanBatch')` scheduling gap between them - roughly 48+ seconds of pure
wait, ahead of the ~300 individual HTTP fetches themselves, against a ~123 second total scan.

Tried replacing the three batch-loop `runIn(1, 'scanBatch')` calls (`startScan()`,
`scanBatch()`'s same-phase reschedule, `startAppPhase()`) with `runInMillis()` at 200ms, then
400ms. Confirmed live both times: total scan time dropped dramatically, but not smoothly -
runs measured anywhere from under a second to ~10 seconds to, on one fresh-install test,
**~153 seconds with the scan stalling at 102 of 103 apps** and the hub logging "clearing an
abandoned scan" a full 87 seconds after it had already logged "scan complete". Polling
`/scan-status` directly during a fast run showed the batches were not actually respecting the
requested interval - progress went from 0/194 to fully complete (194 devices + 103 apps) in
under half a second once execution started, nothing like 47 evenly-spaced 400ms hops.

Concluded this is Hubitat's own scheduler behaving unpredictably under a tight, repeated
`runInMillis()` self-rescheduling loop fired back-to-back roughly 48 times - not something
either 200ms or 400ms is a "safer" choice of, and not something this app can control from its
own side. `runIn()`'s whole-second granularity is almost certainly the platform's actual
tested/supported case for this pattern; `runInMillis()` is a real API but this repeated-loop
use is apparently outside where it behaves reliably.

Fully reverted - confirmed a clean, zero-diff revert against the pre-experiment commit
(`47534f4`) for `apps/automation_map.groovy`. Also reverted alongside it: `scanButtonHtml()`'s
reload delay (was shortened 2000ms->800ms to try to keep the progress UI legible against the
faster scan) and `refreshInterval` (was 4->2, same reason) - both no longer needed once the
scan itself is back to its original, reliable pace.

Not re-open without a fundamentally different approach (larger batches to cut the *number* of
hops rather than the delay between them, which risks Hubitat's per-execution time ceiling
instead - see the graph-derivation architecture item elsewhere in this file for the same
underlying tension between hub-side batching and platform limits) - simply picking a different
millisecond value is not expected to behave more predictably than 200ms or 400ms did here.

### Larger app batches to cut the number of scheduling hops (attempted and reverted, 2026-08-21)

**Source:** Gordon, following the `runInMillis` result above - asked whether a ~25% speedup was
possible some other, safer way. The candidate identified: raise `APP_BATCH_SIZE` (3) instead of
shortening the gap between batches, since that leaves `runIn()`'s whole-second scheduling alone
entirely and just does more work per hop.

Tried `APP_BATCH_SIZE` 3 -> 4 (cuts the app phase from ~35 batches to ~26). Pushed to the Dev
hub instance and measured against a same-day, same-instance baseline (a fresh reinstall scan
just prior, unrelated to this change, timed at 124s end to end). The 3->4 run measured 128s -
no improvement, slightly slower if anything, and well inside the run-to-run variance already
seen elsewhere on this platform. Reverted back to 3; not worth the added risk (see the P1
quadratic-dedup item and the architecture section below for why a bigger per-execution app
batch is a real, not theoretical, risk on this platform) for a change that produced no measured
benefit.

Not re-open by guessing a different batch size without a reason to expect the earlier
measurement was noise. If revisited, measure against a same-session baseline (not a remembered
number from a different day) before drawing a conclusion.

### Remote-access reload loop and duplicate-scan race (2.0.7, implemented on dev 2026-08-23, not yet hub-tested)

**Source:** Community feedback (Steve/oldcomputerwiz - dev branch ran well at scale, 460 apps/342
devices in 58s, but flagged discrepancies; stable/main "would not rescan" over Remote Admin until
reinstalled, then took 14m30s with elevated hub load once forced). Separately, Gordon's own Dev
hub logs showed two `/scan endpoint reached` hits 13 seconds apart followed by contradictory
registry-fetch outcomes within a ~2.4s window, and Gordon reported the settings page reloading
several times over Remote Admin on the current dev build.

Two real, distinct bugs found. The reload cascade is scoped to code added earlier this session and
does not exist in production's v2.0.4. The read-then-act scan-start race and the warning-level
`/scan endpoint reached` log line, however, are not new - production carries the same caller-level
`scanRunning` check and the same log statement, inherited unchanged. Promoting this fix to main is
therefore expected to resolve both there too, not just extend a dev-only fix.

1. **Reload cascade.** `amProgressPoll()`'s cloud/Remote-Admin branch could not read cross-origin
   `/scan-status` (CORS), so it scheduled its own `location.reload()` after 4 seconds. Every reload
   re-rendered the page while `state.scanRunning` was still true, which re-entered
   `amProgressPoll()` on load and rescheduled another reload - an unbounded 4-second reload chain
   for the whole scan over remote access, not the single fallback the old comment described. Fixed
   by removing the self-scheduled reload entirely; the page's own `refreshInterval: 60` (already
   present) is the sole remote fallback now. Separately, `amStartScan()`'s own initial 4-second
   reload after launching the cloud iframe can still land before `startScan()`'s two synchronous
   HTTP calls finish and `scanRunning` commits, rendering a stale page (button looks enabled, no
   polling starts) with nothing left to recover it. A `sessionStorage` marker set before the iframe
   navigation allows exactly one bounded extra reload (not a chain - a counter caps it at one) if
   the next page load still doesn't show the scan as running; it's cleared the moment a page
   actually observes `scanRunning == true`.

2. **TOCTOU double-scan race.** `scanMapping()`'s (and `scheduledScanHandler()`'s) guard read
   `state.scanRunning` before acting on it - a plain read-then-act check. `startScan()` does not
   set `state.scanRunning = true` until after two synchronous HTTP calls (`probeCompatibility()`,
   `fetchDeviceListBulk()`), and Hubitat only commits `state` durably at the end of a whole
   execution. A second `/scan` request landing anywhere in that window read the still-uncommitted
   `false` and also called `startScan()`, producing two independent `scanId`s racing each other -
   consistent with Gordon's log evidence (one generation's `fetchRegistry` succeeding while
   another's own 45s watchdog found it still pending, 2.4s apart), though logs alone cannot rule out
   a user re-click or a relay-level retry as the trigger for the second request. Fixed with an atomic
   `SCAN_LOCKS` single-flight lock acquired at the top of `startScan()` itself rather than at each
   caller, so every entry point is covered without needing its own copy of the check.

   The lock's value is a unique per-attempt token, not a plain boolean, and every release goes
   through one centralized `markScanFinished(token, error)` helper using `remove(key, exactToken)` -
   caught in review before this went anywhere near a hub: an unconditional `remove(key)` has no
   ownership identity, so a late execution belonging to an OLDER scan generation (a delayed
   watchdog or finalizer) could otherwise remove a NEWER generation's still-legitimate lock if it
   fired after that newer generation had already acquired the same app id. The token is carried
   forward explicitly the whole way through the pipeline - as a parameter into `startAppPhase()`,
   on the device/app phase's own `DEVICE_SCANS`/`APP_SCANS` accumulator for the watchdog/finalizer
   paths, and through `runIn()`'s own `data` payload all the way to the registry/graph-build tail
   (`fetchRegistry`/`finishScan`) - never read back from a shared `state` field by any of them. An
   earlier draft of this fix took the shortcut of stashing the token in `state.scanLockToken` for
   the registry/finish tail specifically, reasoning that those two already trust state directly as
   single-owner scheduled executions; caught in a second review pass as the same identity-adoption
   bug one layer down - any of these are themselves separately scheduled executions that could fire
   late after their own generation was abandoned and a newer one has since overwritten that shared
   field with its own value, letting the late execution silently adopt the newer generation's
   identity. `clearAbandonedScan()`'s own recovery path is the one deliberate exception to "always
   carry your own token" - it snapshots whatever `SCAN_LOCKS` currently holds and conditionally
   removes exactly that, since its whole job is recovering whatever generation is actually stuck
   right now, not trusting a value written earlier that the malfunction itself might have made
   stale.

   Also caught in the same review pass: `markScanFinished()` itself was removing the lock
   conditionally but still mutating `state.scanRunning`/`state.scanError` unconditionally underneath
   that check - safe for the lock, but a late execution from an abandoned generation could still
   stomp a legitimately-running newer generation's status even though it could no longer touch that
   generation's lock entry. Reordered so the conditional removal's own success/failure is the single
   gate for every mutation in the function, not just the lock: only the execution that still
   verifiably owns the lock may write anything at all.

   An explicit ownership-transfer flag (`released`) means an unhandled exception during the
   pre-dispatch bootstrap still releases the lock rather than wedging it - a plain `finally` would
   wrongly release it on the success path too, which needs to keep holding it for the whole scan.
   Also caught in review: the empty-device-queue path originally set `released = true` before
   calling `startAppPhase()`, not after - an exception thrown before `startAppPhase()` installs its
   own recovery machinery would have left the lock held with the outer catch already believing it
   was someone else's responsibility. Reordered so this path is caught the same as every other.

   A third review pass caught two more gaps in the same area, both about a LATE execution belonging
   to an already-abandoned generation A still touching state that legitimately belongs to a newer
   generation B, even with every fix above in place. First: `markScanFinished()`'s successful case
   released the lock, then the caller kept writing more state afterward (`finishScan()`'s own
   graph/counter publication) - the release and the publish were not atomic, so B could acquire and
   start resetting state while A's `finishScan()` was still mid-write. Fixed with an atomic
   ownership-to-finishing transition (`SCAN_LOCKS.replace(key, token, "${token}-finishing")`): only
   the verified current owner can begin terminal publication, the finishing sentinel keeps occupying
   the slot (blocking any new acquisition) for the whole publish, and the real release
   (`state.scanRunning = false` plus `SCAN_LOCKS.remove(key, finishingToken)`) is the LAST thing that
   happens, after every write. `clearAbandonedScan()` now recognizes a `-finishing` value and leaves
   it alone entirely - it's actively wrapping up, not stuck, and recovering it would race the publish
   already in flight. Second: several separately-scheduled handlers (`finalizeDevicePhase()`,
   `finalizeAppPhase()`, `startAppPhase()`, `fetchRegistry()`) hand off to the NEXT phase on success
   without ever calling `markScanFinished()` at all, so token propagation alone didn't stop a late
   execution from writing intermediate state before it ever reached a terminal call it might lose.
   Added an explicit `ownsLock(token)` check before the first state write in each - re-checked again
   after any HTTP call in the middle (`fetchInstalledAppIds()` in `startAppPhase()`, the registry
   fetch itself in `fetchRegistry()`), since abandonment and a fresh acquisition can interleave in
   that gap exactly as easily as around a terminal release.

   A fourth review pass found the terminal protocol itself still had three gaps and asked for one
   unified implementation covering success and failure alike, rather than `markScanFinished()` and
   `finishScan()`'s success path drifting into two different mechanisms. Replaced both with a single
   `finishGeneration(token, error, publishWork)`: atomically claims `original -> "finishing:<token>:
   <since>"` via `SCAN_LOCKS.replace()` FIRST, runs the optional `publishWork` closure only if that
   claim succeeds, then releases - `state.scanRunning = false` and removing the sentinel - as the
   literal last statement, inside a `finally` so an exception inside `publishWork` still releases
   rather than stranding the slot. `markScanFinished()` is now a thin wrapper (`finishGeneration(token,
   error, null)`) so the nine existing failure/watchdog call sites needed no further changes.
   `finishScan()` was restructured so nothing touches `state` before the claim at all, not even the
   heartbeat stamp or the PENDING-registry-becomes-FAILED bookkeeping - both are computed into local
   variables first (harmless if this execution turns out to be stale, since the claim will simply
   fail and the locals get discarded) and only written inside the `publishWork` closure. The finishing
   value now carries a timestamp specifically because a `finally` cannot cover the execution being
   killed outright (a platform timeout, a hub reboot mid-publish) rather than merely throwing -
   `clearAbandonedScan()` parses that timestamp and, past `FINISHING_RECOVERY_SEC` (60s, generous
   against publish work that is pure in-memory writes with no HTTP calls), treats the sentinel as
   stranded and recovers it.

   A fifth review pass caught two more instances of exactly the mistake this whole area of the code
   has been converging away from. First: `finishScan()`'s "local" `regMeta` was a local variable NAME,
   not a local copy - `state.registryMeta` returns the same live Map object state already holds, so
   mutating it in place (`regMeta.state = 'FAILED'`) touched shared data before `finishGeneration()`
   ever checked ownership, regardless of whether the later, properly-gated reassignment happened.
   Fixed with a real defensive copy (`new LinkedHashMap(...)`) before any mutation. Second: the
   stranded-finishing recovery path added in the fourth pass was itself a plain
   `SCAN_LOCKS.remove()` followed by unconditional `state` writes - the identical remove-then-mutate
   race the unified protocol exists to close, reintroduced in the one path that didn't go through it.
   A brand new scan could acquire in the gap between the remove succeeding and the writes running,
   and recovery would then stomp its just-started state. Fixed by having recovery itself claim a
   distinct `"recovering:<value>:<since>"` sentinel first (still blocking new acquisition), writing
   the recovery state while that holds, then releasing last in a `finally` - not routed back through
   `finishGeneration()`, since recovering an already-stranded value is not a normal token handoff and
   must not itself claim a fresh `finishing:` transition, which would nest and break the parsing on a
   future pass.

   The losing caller gets a truthful `running:true, alreadyStarting:true` response instead of one
   built from its own stale `state.scanRunning` snapshot, which could otherwise read false even
   while the winner's scan was genuinely starting - but only the genuine CAS-loss case gets that
   override. An ordinary request arriving while a scan is already fully running (the common case)
   reports its real, current progress with `alreadyStarting:false`; the first draft of this
   conflated the two situations and forced the override onto both.

Also fixed as part of the same pass: `/scan endpoint reached` downgraded from `warn` to `info` (it
was alarming testers who read it as an error - it is unconditional instrumentation proving the
request reached the app, not a failure signal); and a Dev install now defaults its overnight scan
to 01:00 instead of 00:30 when the time is left blank, since Gordon runs both instances on the same
hub and they were competing for the same loopback endpoints at the same second every night
(explicitly chosen times on either instance are untouched).

Reading `main` directly (not just reasoning from dev's code) confirmed production's own 4-second
`refreshInterval` reload-during-scan and the same caller-level `scanRunning` race already exist
there too, by design/inheritance - not introduced by this session's work, and not something this
fix touches on its own. Promoting 2.0.7 to main is expected to resolve both as a side effect of
replacing the old refresh-driven flow, not via a separate v2.0.4 hotfix. Steve's "would not rescan
until reinstall" report remains un-reproduced on Gordon's own hub and is not being chased without
logs.

Implemented on dev only, local working tree, not yet pushed to GitHub or tested on the Dev hub.
Reviewed for correctness before any hub test; two real defects were caught and fixed in that pass
(the unconditional lock-release ownership gap and the premature `released` flag on the empty-device
path, both described above) plus the `alreadyStarting` conflation. Validation still required before
either push: local/remote/simultaneous-request behavior, the full graph-comparison regression
against the last known-good baseline, and the schedule-stagger default on a fresh install.
