# Backlog

Not shipped with the HPM package. Items here are agreed ideas awaiting a decision to build, not commitments.

---

## Integration registry (proposed)

Adopt the registry pack (generated 2026-08-11) as the source of external dependency
knowledge. Imported to `Supporting Docs/registry-pack-v0.3/`; the file to build from is
`hubitat_automation_map_app_integration_registry_v0.3.json`.

The map currently claims to show what drives an app, and is silently wrong for anything not
device driven. BOM Weather Alerts is driven by a ten minute poll of a BOM feed, but the map
shows OpenweatherMap as its trigger, because that is the only device it touches.

### Why a registry rather than declaring systems per app

Assessed 2026-08-12 by running its `matchRules` against the live hub. Three concrete
improvements over simply letting users name one external system per app type, which was the
first design considered and rejected:

**`dependencies[]` rather than one system per app.** LIFX is the proof case, and the exact
thing the simpler design got wrong:

    LIFX Light Manager
      +-- LIFX Cloud        HTTPS     MANAGEMENT
      +-- LIFX LAN Devices  LAN_UDP   RUNTIME

One class per app would have labelled LIFX "LAN" and lost the cloud dependency entirely.

**`runtimeCriticality` beats an internet/LAN binary.** RUNTIME / MANAGEMENT / SETUP_ONLY /
DISCOVERY_ONLY actually predicts failure. Meross cloud is SETUP_ONLY for authentication
while control is LAN, so losing the WAN means the garage door still opens but cannot be
re-paired. The binary would have said "garage door dies", which is wrong.

**Maker API is handled correctly.** `Home Assistant via Maker API` and
`Homebridge via Maker API` both key on `appName equals "Maker API"` but carry
`matchMode: ALL` plus a `userMapping` rule, so neither fires without explicit user
annotation. That is the right answer to a genuinely undecidable case, and it preserves the
user-override layer the earlier design needed anyway.

### Measured coverage on the dev hub

11 of 19 app types matched. Misses were Rule-5.1, Notifier, Critical Device Monitor,
Presence Manager, Notification Proxy, Averaging Master, Automation Map and Zigbee Map.
Most are Gordon's own apps, correctly absent from a public registry and covered by user
overrides. Critical Device Monitor does have real dependencies (8.8.8.8 and Gmail) so it
needs a user entry.

### Agreed design (2026-08-13)

Settled in discussion. Build in this order, because the user layer is what makes an
incomplete registry honest, and retrofitting storage after users have data in it is worse
than building it first.

**1. User layer and the classification page, on the map page rather than in settings.** One
table listing **every** app type on the hub, not only the unclassified ones:

    App type                          External system        Source
    CoCoHue - Hue Bridge Integration  Philips Hue Bridge     built in
    Critical Device Monitor           Google DNS, Gmail      yours
    Notification Proxy                --                     unclassified
    Rule-5.1                          none needed            built in

Showing only the gaps would hide the thing users most need to check: whether the shipped
classification is right for them. Kasa and Tapo can each run local or cloud depending on
setup, so the shipped answer is a guess for half of users and they cannot correct what they
cannot see. Any row is editable; a user entry overrides a shipped one rather than modifying
it.

Classifying is four fields: app type (chosen from the discovered list, never typed), system
name, kind, and what it is needed for (Runtime / Management / Setup only / Discovery only).
Those last two map onto the registry's own `class` and `runtimeCriticality`, so user and
shipped entries are the same shape and render identically.

Stored in **state as a list**, not in settings. Settings are keyed by name and accumulate
orphans as entries are added and removed; a list is clean and is already the shape the graph
builder wants.

**2. Backup as an ordinary file download and upload, user-triggered.**

- **Export**: the page builds a Blob and triggers a browser save to
  `automation-map-backup.json`. Entirely client side, no hub involvement.
- **Import**: `<input type="file">`, read in the browser, posted to the app.

Deliberately **not** an automatic mirror. An automatic write would overwrite a good backup
with a bad state; an explicit save is a snapshot the user chose to take. Stamp the file with
a date so restore can say what it is about to replace.

*Rejected: Hubitat File Manager via `uploadHubFile()` / `downloadHubFile()`.* It would have
worked and does survive uninstalling the package, since HPM only ever deletes files listed in
its own manifest. But it needs firmware 2.3.4.134, raising `minimumHEVersion` from 2.3.0, it
was unverified in this app's sandbox, and it makes the user visit a different part of
Hubitat's UI to get the file onto their PC. A browser download is one click and has none of
those costs.

*Rejected: copy-and-paste JSON.* Most users are not technical, and asking them to shepherd a
block of JSON is a poor answer when the browser already has file download and upload built in.

**3. Registry fetched from `main`, with cache and embedded fallback.** Precedence:

    user entries  >  fetched registry  >  embedded baseline

Fetch on scan with a short timeout, cache the result in state, fall back to the cache and
then to the embedded copy. Show which source is in use and its date, so a stale registry is
visible rather than silent. Carry a schema version in the file so a newer registry and an
older app cannot misread each other.

The registry lives on `main` only and **both branches read the same one**, so there is one
registry rather than two that drift. Fetching is what makes the registry maintainable: a new
integration is one JSON edit rather than an app release, and a community pull request that
touches only the registry cannot break the app.

### Corrected 2026-08-13: the scanner is not a blocker

An earlier version of this item claimed the scan had to be extended first, because the
matcher needs six fields and `appInfo` stores only `label` and `type`. That was wrong twice.

**The data is already in hand.** `namespace` sits in `installedApp.appType` in the response
already fetched per app, alongside `author` and `category`. The device response already
fetched in phase 1 carries `device.deviceTypeName` (the driver name) and a whole `parentApp`
object. All of it is currently discarded, so capturing it is a few lines.

**It barely matters for reach.** 98 of 101 entries carry an `appName` rule, which is the app
type string the scan already stores. Only three entries are reachable exclusively by another
field (Shelly MQTT Variant, Hub Mesh, Matter Bridge / Controller). `driverName` appears on 50
entries but always alongside `appName`, so it raises confidence rather than unlocking
anything. Capture the extra fields for confidence and those three entries, not as a gate.

### Consequence: the map page stops being read-only

Import needs the app to accept a write, so the OAuth-served page gains an endpoint that
replaces the user's classifications. The token already guards the map; it now guards this
too. The data is low harm, being labels the user typed themselves.

The README currently says "The app is read-only. It does not command devices or modify
apps." That is still true of the important part but the wording must change, to something
like "does not command devices or modify other apps", rather than being quietly dropped.

### Registry maintenance tooling

Discussed 2026-08-13. A separate Hubitat "admin app" was considered and rejected: it cannot
write to GitHub, cannot validate a pull request, and cannot run unattended. The registry
lives in the repo, so its tooling belongs there.

Two pieces worth building instead:

- **On the hub, inside Automation Map**, a "copy details for reporting" action on each
  unclassified row, emitting the exact identity strings an entry needs (`appName`,
  `namespace`, `author`, driver names, `parentAppName`). About twenty lines, since all of it
  is already fetched. This addresses the real constraint, which is that entries can only be
  authored for integrations the author personally runs. Any user can then supply correct raw
  material for an integration nobody here has installed.
- **In the repo, a validation script.** Not hypothetical: run against v0.3 it would have
  caught every defect found by hand, being the four entry classes and three dependency
  classes used but never declared in `nodeClasses`, the nineteen entries with no
  dependencies, and the duplicate shipped files. Add a check that a `matchRule` value is not
  a UI display name where the hub reports something different, and the `Rule-5.1` class of
  bug stops recurring. It also becomes the gate for community pull requests, which is the
  point of putting the registry on GitHub at all.

### Genuine blockers

- **`Rule-5.1` does not match.** The registry's Rule Machine entry keys on
  `contains "Rule Machine"` but the hub emits the type string `Rule-5.1`. That is 36 of 61
  apps. Harmless in itself (that entry declares no dependencies) but it proves the registry
  was authored against display names rather than the strings the hub actually reports.
  Audit every entry for this before trusting the match rates.
- **Schema inconsistency.** Four entry classes are used but never declared in `nodeClasses`
  (DASHBOARD, PLATFORM_UTILITY, SECURITY_ORCHESTRATOR, VIRTUALISATION_ORCHESTRATOR), plus
  three dependency classes (EXTERNAL_OR_LOCAL_SERVICE, LOCAL_DEVICE_OR_BRIDGE,
  LOCAL_OR_EXTERNAL_SERVICE). Those last three are a design smell too: a class meaning "one
  of two things" defeats the taxonomy, and `transport: LAN_OR_CLOUD` already expresses that
  ambiguity properly.
- **19 of 101 entries declare no dependencies** (webCoRE, Basic Rules, Room Lighting, Rule
  Machine). Redundant here, since the hub already reports those apps directly. Drop them
  from the embedded copy.
- **Pack hygiene.** Ships duplicate files with `(1)`/`(2)` suffixes and stale v0.1/v0.2
  registries next to v0.3. All 13 checksums in SHA256SUMS.txt verify, so nothing is
  corrupt, just noisy. Import one version.

### Size

    as shipped             85,802 bytes / 101 entries
    trimmed + minified     23,876 bytes /  82 entries
    automation_map.groovy  78,063 bytes / 1642 lines

Trimming the zero-dependency entries and prose fields lands at 24KB, roughly a 30% increase
in app size. Keep it as a source constant, never in app state, so it costs nothing against
the state ceiling.

### Out of scope

`device_driver_registry_v0.1.json` contains **3 device entries**. It is a schema stub, not
data. The companion `harvest_hubitat_compatible_devices.py` is sound (Playwright because
docs2.hubitat.com/en/devices/list-of-compatible-devices is JS-rendered, conservative parsing
that preserves raw rows) but it only runs on a desktop, not the hub, and manufacturer and
protocol detail barely changes the dependency graph. Skip both.

### Not provided by the registry itself

Still required:

- Unclassified must be an explicit state, never silent absence.
- User assertions render differently from scanned relationships (dashed edges).
- Blast radius is the actual payoff: external system, to apps, to devices.

---

## Rule-to-rule: device-mediated links (proposed, deferred)

Direct rule-to-rule links shipped in 1.3.3. Four action families are decoded from the
`rulesActs` settings: `getRuleActions`, `getStopActions`, `getPauseResumeRules` and
`getSetPrivateBoolean`. What follows is the part deliberately not built.

Device-mediated relationships: Rule A writes a virtual switch, Rule B triggers on it,
therefore A leads to B. That is where the remaining payoff sits and also where every false
positive lives. Full design in
`Supporting Docs/hubitat_automation_map_rule_to_rule_implementation.md`,
which is roughly five times bigger than what should be built:
15 edge types, 5 phases, cycle detection, feedback-loop oscillation analysis, three separate
semantic JSON files, and confidence values quoted to the percent (95 versus 90 is false
precision). Phases 3 onward are not worth building at all.

### The discipline that must survive any trimming

Without this, device-mediated inference is a false-positive generator that draws an edge
between any two rules touching the same device. Same principle that killed endpoint
auto-detection.

- A condition is not a trigger: TRIGGERS_VIA versus INFLUENCES_VIA (doc sections 10, 11).
- Value matching: Rule A sets switch X on, Rule B triggers on X off, therefore no edge (19).
- Key on `deviceId + attribute`, never `deviceId` alone (20).
- Required Expressions never produce trigger edges (23).
- POSSIBLE_RELATIONSHIP when subscription versus read cannot be established (44).

### Open questions from the delivered work

Both found while building 1.3.3, both handled by showing less rather than guessing. Neither
blocks anything. Both are tracked in full under the Rule Machine 5.1 documentation item
above, which is the canonical record for anything learned about the storage format.

- **`pvTF.<n>` reads inverted.** **Closed 2026-08-14, shipped in dev 1.7.2.** Rule 1999
  "Barking" supplied the missing case, a pair storing `true` and an empty string, and its
  page shows False then True. The rendered value is `!pvTF` with empty counting as false.
  Flow steps now read "Set Private Boolean True" or "... False" instead of a bare
  "Set Private Boolean".
- **Pause and Resume cannot be told apart.** Still open. Both use `getPauseResumeRules` and
  render as "Pause / Resume Rules". `pR.<n>` is the likely discriminator and was empty on
  the only available example, which its page showed as a Pause. One rule containing a
  **Resume** action would settle it, and `_Testy` is already set up to be that rule.

### Focused-view label collision (open, low priority)

Found 2026-08-14 while checking the rule-link work. Focusing an app draws every node's FULL
title at a fixed position, so two nodes close together overwrite each other's text and
neither is readable. Seen on `_Testy` with "Barking (Required Expression false) (Rule-5.1)"
and "Christmas Cheer (Required Expression false) (Rule-5.1)", which are long titles on
adjacent nodes.

vis.js does no label collision avoidance, so this is a design choice rather than a bug to
patch. The options are to keep the truncated label in the focused view and put the full
title in the hover tooltip only, to strip the hub's "(Required Expression false)" suffix
from the drawn label since it is runtime state rather than identity, or to accept it. Not
worth a quick coordinate nudge, which would only move the overlap somewhere else.

### Honest limitation, already documented in the README

Only Rule-5.1 is analysed. Room Lighting, Basic Rules, Simple Automation and webCoRE show no
links rather than showing that they have none.

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

663 lines, 12 sections, complete and internally consistent. Contains:

- A method section: rule page as ground truth, differential reading, corpus-wide checks
  across all 38 rules, building a working decoder so misreadings render as visibly wrong
  flowcharts, constructing test rules where the corpus had gaps, and checking official docs
  before claiming novelty. Also states what was *not* done.
- Evidence markers on every claim: `[invariant]` `[strong]` `[limited]` `[single]`
  `[heuristic]` `[unknown]`, so a finding holding across 38 rules is distinguishable from
  one resting on a single sample.
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
- **Evaluation order.** One live test on 2.5.1.140 resolved `A AND B OR C` as
  `A AND (B OR C)`, the opposite of conventional precedence. Cannot distinguish "OR binds
  tighter" from right-associative evaluation; a four-term case such as `A OR B AND C OR D`
  would separate them.
- **Repeat and while.** Every rule carries state for them (`hasWhileRule`, `inRepIf`,
  `nestedRepIf`, `blockIf`) but no rule on the hub uses them, so their action-level markers
  are unobserved. The IF family is therefore known to be an incomplete grammar.
- **Rule Function discriminator.** One sample only. Reports identically to an ordinary rule
  in every field examined, which is absence of evidence rather than evidence of absence.
- **Pause versus Resume.** `pR.<n>` looks like the discriminator but was empty on the only
  example. One rule containing a Resume action settles it.
- **`pvTF` meaning.** Three observed cases, all inverted against the rule page. Rendering
  `!pvTF` would be right on all three, but the reason is unknown so the value is not shown.
- **Bulk app enumeration.** No usable endpoint found on 2.5.1.142; an undocumented one may
  exist.

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
  found across 38 Rule-5.1 rules, which is suggestive but not proof since an unused option
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
revisited.
