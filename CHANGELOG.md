# Changelog

Complete Automation Map development history previously carried in the HPM manifest.
The manifest now contains only the current Dev-channel summary so package metadata
stays easy to review.

## 2.2.1

In development on the dev channel. Insights gains a set of findings for things that look fine but
silently do nothing, each pairing a state with the second fact that makes it worth acting on rather
than reporting the state alone:

- A paused or disabled rule that another rule still runs, so that step in the caller silently does
  nothing. Pause/resume links are deliberately excluded, since a rule whose job is to resume this
  one is the mechanism working rather than a failure.
- A disabled device automations still command or wait on as a trigger. Constraint and monitor reads
  are excluded as a weaker, much noisier claim than a command that cannot land.
- Rules Hubitat itself marks broken, read from its own label rather than judged by this scan.
- Every paused or disabled rule as plain context under expected patterns, not as a fault list, and
  never double-counted with the ones reported under Needs attention.
- Local Variables declared in a rule with no decoded read or write, carrying the same "may simply be
  unused" caveat the equivalent Hub Variable finding already has.

All findings reach the AI-friendly export as additive fields with their own limitations, which no
consumer is required to understand, so the export schema version is unchanged. Note that none of
this observes runtime behaviour: it is static configuration evidence that a step cannot do anything,
not evidence that it was ever reached.

## 2.2.0

Production-cleanup release. Local review and automated gates passed; deployed to Automation Map
(Dev) (Apps Code 1210 / instance 3083); diagnostic-toggle placement and off/on/off logging
behaviour independently verified live by Gordon. The telemetry-child migration test (clean
deletion, plus a deliberately-referenced device failing safely) is explicitly waived by Gordon, not
passed - low affected population, easy manual fallback. The
Automation Map Telemetry Driver and everything that fed it (`ensureTelemetryDevice()`,
`reportTelemetry()`, `fetchHubHardwareId()`, the manifest driver entry, the README disclosure) are
removed entirely rather than made optional - an always-present reporting driver read as intrusive
to some users regardless of what it actually collected. An upgrading instance removes its own
leftover telemetry child device automatically: exact-DNI, never forced, and if Hubitat refuses the
deletion because the device is still referenced elsewhere, the settings page shows a fixed warning
(never the raw exception text, which stays in the log only) and retries the next time settings are
saved.

In its place, a settings-page toggle enables on-demand diagnostic logging for troubleshooting - off
by default. A durable expiry timestamp, not just a scheduled job, enforces the one-hour auto-disable
even if the scheduled handler itself is missed (a one-shot job does not fire late or catch up if the
hub is down when it comes due); an unrelated later settings save while the toggle stays on does not
push the deadline out further, and the settings page reconciles a stale "on" display back to off on
its own next render. Every routine/lifecycle log line in the app (installs, scheduling
confirmations, endpoint-entry logs, successful saves, expected superseded-generation discards,
registry counts, scan start/completion detail) is now gated behind this toggle; failures and
degraded outcomes that can leave the map incomplete or stale stay unconditionally logged regardless
of it. The temporary `AM-TRACE` diagnostic path stays Dev-only regardless of the toggle's own state,
per the standing agreement not to make it part of the reusable production logging design.

The automatic-scan Hours/Minutes field now shows its real default (00:30 production, 01:00 Dev)
pre-filled, instead of appearing blank next to a `description:` that never rendered on `bool`/`time`
inputs. One helper function is the single source for that default across the input, the explanatory
paragraph, and the scheduler's own blank-time fallback. A separate effective-default helper treats a
genuinely unsaved setting the same as its own displayed-on default, since Hubitat does not
necessarily populate `settings` with a displayed default before the first save.

Both the removed remote-telemetry approach and the new local-logging approach are documented for
reuse at `https://github.com/GordonThelander/hubitat_dev_utililities` under "Application Telemetry
Methods", sanitized and parameterized rather than copied with real identifiers.

The four Focus dropdowns (Apps, Devices, Hub Variables, Local Variables) are now a single combined
combobox each, replacing the old search-input-stacked-above-a-select pair. Built and proven
standalone first, then iterated live against direct feedback: the closed control is a plain,
non-editable label-plus-arrow (an earlier version that let the closed control double as the search
field was tried live and rejected in favour of this conventional shape); opening it reveals a popup
whose first row is a dedicated, auto-focused search field, with the filtered options list directly
below it; the unfiltered list still offers the "All X" reset row, but a typed filter narrows to
matches only. The controls panel widened 150px -> 300px so option text is not truncated, and the hub
watermark image now tracks the panel's own right-anchored position instead of a fixed percentage, so
future panel-width changes cannot drift the two out of alignment the way they did here.

A colour and typography pass brings the desktop UI closer to
`gordonthelander.github.io/HPM_Manifest_Crawl/` (Hubitat Community Utilities) - Mulish typeface,
pill-shaped buttons and a shared blue accent across every control that was previously left to the
browser's own default styling, softer panel corners, small letter-spaced labels above each Focus
control. The dark background and the graph's own node/edge colour system are unchanged - that is a
separate, semantic legend, not general UI chrome. The typeface is self-hosted from this repo (a
single variable-weight WOFF2, `Fonts/`, with its upstream SIL OFL 1.1 licence carried alongside it)
rather than fetched live from Google Fonts on every page load, for the same reason this release
removed its own telemetry driver - a call to any third party on every visit reads as intrusive to
some users, and a live font request is the same category of thing even though it carries no app
data. Two real defects were found and fixed during this pass, not cosmetic: a CSS inheritance leak
that pulled the new small/bold label styling into the combobox popup wherever it happened to be
nested inside a `<label>` element, and a "Community information" card that only cleared its own text
when it had nothing to show rather than actually hiding, leaving a blank light rectangle on screen.

## 2.1.7

Device discovery now walks the complete tree `/hub2/devicesList` returns instead of only its
top-level entries. A device-owned component device (`isComponent: true`, created by a parent device
driver rather than an app - Shelly, Bond, and Matter bridges are the reported examples) can be
represented nested inside its parent's own `children` array, invisible to the previous flat read
regardless of whether the component was referenced by anything. Confirmed live: this hub's own
"Variable Connectors" parent carries all nine per-variable Connector devices this way, previously
invisible to bulk discovery and synthesized as bare placeholder nodes from Hub Variable metadata
instead. Aggregates by device ID before grouping so a device exposed both at the top level and
beneath a parent is enriched, not duplicated.

The discovered parent/child relationship is rendered as a new `hasComponent` edge kind on the graph
and in the AI-friendly export (graph schema 9, export schema 7), including correct focus-expansion
behaviour for an app that touches a component child without referencing its parent directly.

Also fixes a live regression introduced by this same discovery work: an apostrophe inside a
single-quoted JS string in the page's inline script terminated the string early, breaking the
entire embedded script and leaving the rendered map blank while the device/app counter still
rendered. Fixed and confirmed live via a rendered page load with zero console errors. Reported by
community tester Steve (oldcomputerwiz); independently confirmed on his own hub - Hubitat and
Automation Map both reported 351 devices, Aqara/Bond/Harmony/Shelly devices specifically checked
and all present, described as "spot on".

## 2.1.6

Local Variables (belong to one rule only) become first-class nodes on the network graph, not just
names inside the rule detail card - a real, decoded action like "Set Local Variable X" now has
somewhere to appear on the map itself. Seeded from each rule's own declarations rather than from
references, so a Local Variable that is declared but never written or read is still shown, isolated on
the same shelf an orphaned app already uses (a separate `unreferencedLocal` marker, deliberately not
the existing inert-app flag, so app-only counts and findings are unaffected). Owner-scoped identity
throughout: two rules can each declare their own same-named Local Variable, and they render as two
distinct nodes, never merged - the same identity guarantee Gate C (2.1.4) established for correctly
telling Local and Hub Variable references apart. Adds a Focus local variable picker and pivot table
support alongside the existing app/device/Hub Variable ones. Graph storage schema bumped 7 to 8 and the
AI-friendly export schema bumped 5 to 6, since edges[] can now target a Local Variable in addition to a
Hub Variable - the schema prose documents how to tell them apart without guessing. Dev-only testing
build, not yet verified on the Dev hub.

**2026-08-29 fix**: `graphVersion` moves from `state` to `atomicState`. A page load landing within about a
second of a scan's completion could read a stale pre-commit `state` snapshot - `state.graph` still null,
left over from the value scan-start assigns - and `shouldAutoScan()` would read that as "never scanned",
genuinely auto-starting a second scan. Confirmed live via hub trace logs (a completed generation's own
`noGraph` read `true` on the very next page load) and reproduced by hammering the settings-page render
immediately after a real scan completion. `atomicState` commits on every write instead of once at the end
of an execution, closing the window; a one-time migration backfills the field for installs that already
have a graph stored under the old key, so this deploy does not itself trigger a false "stale format,
please rescan" message. Verified on the Dev hub: 20 consecutive post-completion checks (real scan, rapid
repeated settings-page renders) all read `noGraph=false`, versus the pre-fix trace showing it flip `true`.

**2026-08-30 fix**: `state.graph` itself (not just `graphVersion`) could still be clobbered back to null by
an unrelated execution's own end-of-run `state` write-back landing after the real finalizer had already
committed it - confirmed live as the cause of the "View Automation Map" link (and the app/device/node
counts above it) silently disappearing after a scan, stuck across repeated app opens since nothing else
rewrites `state.graph` until the next scan does. Rather than move the whole (large) graph to `atomicState`
too - risking the memory failure the 2026-08-13 fix exists to avoid - `selfHealGraphIfNeeded()` now treats
`atomicState.graphVersion` (immune to this race) as proof a graph should exist, and if `state.graph` is
missing anyway, rebuilds it locally from the scan's other results (`appInfo`, device maps, a fresh Hub
Variable read) instead of requiring a full rescan; those inputs are written earlier in the pipeline than
`state.graph` itself, so they are not lost by the same race. Verified live on the Dev hub: the exact defect
reproduced naturally (real scan, real app reopen through Hubitat's own UI), the trace log shows
`selfHealGraphIfNeeded()` firing and rebuilding within about a second of the corruption, and the map link
and counts were correct on every check for the following two minutes.

## 2.1.5

Adds short `[LOC]`/`[HVR]`/`[CON]` class tags to the Hub Variable Focus dropdown and the rule detail
variables card, matching the existing app/device Focus tag convention (2.0.0) so a variable's class is
visible at a glance rather than requiring a click-through. `[HVR]` (Hub Variable) is a new code, chosen
deliberately over reusing `APP_TYPE_TAGS`'s existing `HUB` code - that one means "built-in Hubitat app",
a different axis from variable scope, and reusing it risked conflating the two. The Hub Variable Focus
dropdown shows `[CON]` for a Connector-backed Hub Variable and `[HVR]` for a plain one, reading the same
authoritative `connectorDeviceId` the graph already resolves; the rule detail card shows `[LOC]` for a
proven Local reference and `[HVR]` for a proven Hub reference, with no tag on a Needs-review entry since
its class is, by definition, not known. No new backend or export schema fields - every tag reads a value
already resolved by Gate C (2.1.4). Verified on the Dev hub against
fixture rule 3079: both tag branches confirmed against real classified data (a proven Hub reference and
a Connector-backed Hub Variable), with no regression on the earlier Gate A fixtures. Dev-only testing
build, not yet promoted to production.

## 2.1.4

Correctly distinguishes Rule Machine Local Variables (belong to one rule) from Hub Variables (shared
hub-wide) and Variable Connector devices, instead of treating every structured variable reference as a
Hub Variable. Empirically established that a same-named Local and Hub Variable in one rule cannot be
told apart from stored configuration alone - Rule Machine silently resolves the write to the Local
variable at runtime, but the persisted action storage gives no way to recover which one the author
intended - so this case is now reported as genuinely ambiguous rather than guessed at, and a reference
this app cannot confirm against the hub's own authoritative variable list is reported as unresolved
rather than falling back to a weaker-guarantee node. Adds a Local/Hub/Needs-review variables section to
the rule detail panel, and corresponding `localVariables`/`variableReferences`/
`nonResolvedVariableReferences` fields to the AI-friendly export (schema 5) - the export's Hub Variable
topology guarantee is strictly stronger as a result, which is why the schema version bumped rather than
just adding fields. Verified on the Dev hub: classification exactly matched Gate A's fixture predictions
across all three test rules, Hub Variable graph topology and corrected flow labels rendered correctly
with no invented relationships from ambiguous or unresolved references, the Local/Needs-review rule
detail card rendered correctly on a live rule, no variable value appeared anywhere, and scan
finalization/telemetry stayed single-shot. The card's Hub section was not exercised by a dedicated
fixture - it shares the same renderer and already-verified scope filter as the Local section, so this
is not treated as a coverage gap. Dev-only testing build, not yet promoted to production or verified on
a second hub.

## 2.1.3

Fixes the registry-finalization stale-snapshot race: a finalizer entering with a stale, pre-commit
registry snapshot now resolves correctly through a generation-keyed lookup instead of publishing an
incomplete result, and a completed generation can never be republished. Also fixes the settings page
showing stale "Scanning..."/"Building map" text after a scan has actually completed, and closes a gap
where auto-scan could start a second scan without checking the live scan lock. Verified on Gordon's
own hub - a genuine
state resurrection caught and cleared in under half a second, down from roughly 90 seconds before this
fix, and a settings-page fetch at the exact instant of true completion showed no stale text - and via a
controlled Dev-only test forcing the registry watchdog specifically to win the finalizer claim, which
completed cleanly with exactly one result and no duplicate telemetry row. Independently confirmed on
Steve's own C-5 hardware, which exercised the same stale-snapshot hazard on the ordinary chained path.

## 2.1.2

Adds the Automation Map Telemetry Driver, a new bundled driver that reports anonymous data to support ongoing development and future features, after every scan. No credential in the driver: the endpoint is open ingestion, protected by strict server-side payload validation rather than a secret shipped in public source. On by default, no toggle, disclosed in the README. Automation Map creates its own child device instance automatically; delivery is deferred so a telemetry failure can never affect scan publication. Also fixes a false `error: HTTP 302` status the driver reported for a successful send, caused by treating Apps Script's redirect response as a transport failure instead of following it. Verified end to end on the Dev hub: a real scan produced a genuine row in the telemetry sheet with correct data, and the status now reads `submitted`/`ok` instead of a false error.

## 2.1.1

Version bump only, no functional change - keeps the manifest's tracked version in sync with the production release after a Hubitat Package Manager version-tracking mismatch (HPM's own installed-version bookkeeping is separate from the app's live code and is only updated by an HPM-driven install/update).

## 2.1.0

Makes Insights concise and actionable with plain-language explanations, reasons a pattern may be normal, and practical next checks. The same guidance is included in the AI-friendly export. Adds reviewed defaults for common external systems, reconciles Hub Variable identities that include a trailing period, and corrects the installation-page description of how apps are discovered. Released to the Dev channel for hub testing.

## 2.0.14

Adds authoritative Hub Variable inventory through Hubitat's in-process `getAllGlobalVars()` API, Connector reconciliation, structured device-attribute `writeSource`, and export schema 4. Also adds Community Utilities context cards and release activity integration for Dev testing.

## 2.0.13

Fixes silent device metadata loss in bounded-async discovery. Devices whose bulk records omit rooms now receive targeted per-device lookups, malformed capability responses are reported as gaps instead of successful empty data, and representative rooms can no longer overwrite other devices in the same driver group. The export regression checker now detects changed non-empty rooms as well as disappearing rooms and capabilities. Verified on the Dev hub with 196 devices, zero room differences against both the authoritative device list and the historical clean export, zero empty capability lists, and zero unreadable devices.

## 2.0.12

Adds Community Utilities to the home page as a full-tab link, gives the three main navigation titles a consistent blue treatment, and improves the Baseline Comparison page with a prominent green Back control while removing Hubitat's misleading bottom Done/Cancel action. Verified on the Dev hub.

## 2.0.11

Makes graph construction and abandoned-scan finalisation entirely in-memory by resolving linked-rule deletion from the complete app inventory already collected, instead of making sequential 10-second loopback lookups. Removes the unused durable copy of device driver groupings. Remote Admin scans now show a truthful waiting message while local scans retain live progress, and the map endpoint refuses to render mixed old/new data while a scan is running. Verified on the Dev hub.

## 2.0.10

Includes every hub-discovered device in the graph and AI-friendly export, even when no app references it, so scan counts, Focus Device, Insights and exported inventory agree. The map link now includes the completed-scan timestamp, preventing browsers from reopening a cached pre-scan graph. Verified on the Dev hub with 195 devices and 322 nodes while all 1,011 existing relationships remained unchanged.

## 2.0.9

Rejects failed, malformed or incomplete /hub2/appsList responses instead of publishing them as a successful zero-app map, and requires durable proof that the complete async app results were committed before recovery may build a graph. Verified on the Dev hub with a successful 108-app/193-device scan after manually loading the corrected code.

## 2.0.8

Fixes a reproduced finalisation-recovery loop that could leave a completed manual scan showing 'Building map - please wait' indefinitely. Live status polling could repeatedly rebuild the completed graph, then discard it because the transient generation lock was missing or no longer matched. Finalisation now claims ownership before graph construction, concurrent recovery attempts do no duplicate work, and a stranded durable scan can atomically recover when its static lock has disappeared. Reproduced, fixed and verified on the Dev hub: the previously stranded scan recovered, a fresh 108-app/193-device manual scan completed in about 26.5 seconds, and logs were clean.

## 2.0.7

Fixes three remote-access issues surfaced by real-world testing of 2.0.5 (community feedback plus Gordon's own hub logs): (1) accessing the settings page over Remote Admin during a scan could reload the page every four seconds for the whole scan, rather than once - the live-progress poll added in 2.0.5 fell back to an unbounded reload chain instead of one delayed reload when it couldn't read cross-origin status; (2) a genuine race let a second /scan request start a duplicate, independent scan if it arrived before the first request's state had durably committed - state.scanRunning alone can't serve as a single-flight guard across two concurrent executions, so scan start now goes through an atomic lock shared by every entry point, released through one centralized helper covering all nine terminal paths including an unexpected exception mid-startup; (3) the routine '/scan endpoint reached' log line was warning-level and read as an error by testers - downgraded to info, real failures remain warnings. Also: a Dev install running alongside production on the same hub now defaults its overnight scan to 01:00 instead of 00:30, so the two no longer compete for the same loopback endpoints at the same second (an explicitly chosen time on either instance is unaffected). Full record in BACKLOG.md.

## 2.0.5

Reintroduces bounded-async device/app discovery (bulk /hub2/devicesList plus per-driver capability batching, concurrent dispatch for both scan phases) after an earlier attempt was reverted for a data-integrity issue: concurrent asynchttpGet callbacks could overwrite the durable scan result with a stale snapshot, a last-write-wins platform behavior. This rebuild is based on the fix used by hubitrep's HubDiagnostics for the same platform behavior, extended further since this app's scan results must survive a hub reboot: per-item claims with attempt tokens, atomic conditional-ownership retirement, a missing-callback reaper, exact completion invariants, and durable publication only from a separately scheduled execution. Reviewed and hardened through several rounds before any live test, catching two more real bugs along the way. Verified on the dev hub: four scans at 21-25s against a 134s serial baseline, identical device data, zero dispatch/reap/watchdog warnings. Full record in BACKLOG.md and Supporting Docs/async_scan_v205_technical_report.md. Dev-soak candidate - not yet promoted to main.

## 2.0.4

The app page now shows the date/time of the last scan next to the progress line, not just the device/app counts (community-requested).

## 2.0.3

CoCoHue Bridge and other bridge devices now auto-detect as Hub & infrastructure by name, Scene devices (CoCoHue Scene and similar) now auto-detect as their own Scene category instead of Buttons & remotes, both in the map and the Focus device dropdown. Hub photo watermark resized to half the Christmas tree's size and moved to sit below the controls panel; opacity raised to 50%. All 7 hub-facing HTTP calls now go through one shared request wrapper instead of each repeating its own fetch/error-handling - no behaviour change, just less duplication. Decided to keep the watermark and Community Utilities sound as GitHub hotlinks rather than move them to File Manager, since the map already requires internet for its CDN libraries regardless.

## 2.0.2

closes out the rest of the 2026-08-20 community code audit that 2.0.1 started on main: the OAuth-less hand-install now explains itself instead of throwing, all 8 quadratic dedup sites are O(1) instead of a linear scan, an IPv6 loopback host with a port is now correctly recognised as this hub, a scheduled job's cron string is escaped before display, the Show all sound is removed rather than fixed (it was falling back to the wrong sound on a lagging deploy), and saving an external-system or icon-override preference no longer waits on buildGraph()'s own HTTP calls before answering. minimumHEVersion raised to 2.5.1, the only firmware actually tested. Full findings in BACKLOG.md.

## 2.0.1 (main)

three issues found by the same audit: a custom-repository install could serve the private Dev build instead of the release, a rescan started while one was already running could let a stale internal job publish a half-finished map as complete, and the CDN-hosted graph/flowchart libraries now carry integrity hashes.

## 2.0.0

the biggest release since 1.2.1. Rule-to-rule links (Runs, Stops, Private Boolean) and Hub Variable read/write edges make the automation web between rules visible for the first time, not just app-to-device connections. External systems can now be declared and drawn as their own nodes, backed by a shared community registry, so you can see what breaks if a cloud dependency goes down. Every installed app is discovered now, including ones that touch no device at all - dimmed and labelled with why. New analysis tools: an Insights panel (contested devices, unreferenced devices, orphaned apps, broken rule references), Pivot tables with presets, a free-form builder and CSV export, and a Device icons panel with auto-detected icons, manual overrides and freeform notes. Rule flowcharts now decode Rule Machine 5.1, Notifier and Visual Rule Builder 2.0. Click-through drill-down with full browser Back/Forward support, an inert-node shelf, a collapsible legend, and an AI friendly structured JSON export round out the core release - see the README for the complete list. Since then: the Focus app/device dropdowns now prefix every entry with a short engine/category tag (RM5, VRB, INT, HUB and more for apps; LGT, SWT, MOT and more for devices) so a long list is easier to scan at a glance. The AI friendly export gained explicit guidance for the AI reading it - open with a plain-language summary, offer options rather than pick one unprompted, and never frame a routine count like contested devices as evidence something is wrong. Show all and every other way of choosing an app or device now reliably closes the other floating panels and brings the legend back, a real gap in earlier testing. The Start here hint now shows once ever rather than on every visit.
