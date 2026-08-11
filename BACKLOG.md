# Backlog

Not shipped with the HPM package. Items here are agreed ideas awaiting a decision to build, not commitments.

---

## External system dependencies (SUPERSEDED, see "Integration registry" below)

Retained for the rejection reasoning only. The hand-rolled design in this section was
replaced on 2026-08-12 by the registry pack, which does the same job better. Do not build
this version. Skip to "Integration registry (proposed, 1.3.0)".

Let users declare which external systems each app type depends on, render them as a
new node class, and use that to answer "what breaks if this fails".

### Why

The map currently claims to show what drives an app, and is silently wrong for anything
not device driven. BOM Weather Alerts is driven by a ten minute poll of a BOM feed, but
the map shows OpenweatherMap as its trigger, because that is the only device it touches.

Auto detection was investigated and rejected. Scanning app settings for URL and host
values across eight apps found only two genuine external feeds, one false positive
(Maker API `corsHosts`), and four LAN phone IPs. Critically it missed every polished
integration (CoCoHue, LIFX, Kasa, Tapo, Sensibo, Google Home, Chromecast), because a
well written integration hardcodes or discovers its endpoint rather than exposing it as
a text setting. Partial coverage is worse than none in an audit tool: if BOM shows a
dependency and CoCoHue does not, the natural reading is that CoCoHue is self contained.

Manual declaration fixes that, because unknown becomes an explicit state rather than an
inferred absence.

### Data model

One list in app state, keyed on the app **type** string, never on installed app id:

    { name: "Hue Bridge",   class: "lan",      types: ["CoCoHue - Hue Bridge Integration"] }
    { name: "Meross Cloud", class: "internet", types: ["Meross MSG100 Garage Door Setup"] }
    { name: "BOM",          class: "internet", types: ["BOM Weather Alerts"] }

Keying on type is what makes this cheap. The dev hub has 61 installed apps but only 19
distinct types, because 36 Rule-5.1 rules and 5 Averaging Master instances collapse to
one row each. It also means the table does not go stale when rules are added or removed.

Stored separately from the graph blob, since a rescan rebuilds that, and re-applied at
render time by matching type strings. Nineteen rows is negligible against the roughly
2500 to 3000 node and relationship state ceiling.

### Seeding

App type strings are identical across hubs, so a static table of around thirty common
integrations ships inside the app. First scan matches what it recognises and leaves the
rest unclassified. The settings table shows whether a row is built in or user set, and
user values always win. A new user's table should be mostly correct before they touch it.

### Unclassified is explicit

Any app type neither seeded nor user classified is shown as unclassified in the table and
counted in Insights ("3 app types not classified"). Never silently treated as having no
dependency. This is the property that makes the manual version worth building when auto
detection was not.

### Rendering

- New node class, visually distinct from devices.
- Dashed edges, because these are asserted by a human, not read from the hub. A user
  assertion must not render with the same authority as a scanned relationship.
- External systems added to the existing filter dropdown.

### The actual payoff

Blast radius. Selecting an external system focuses to that system, every app that depends
on it, and every device those apps own or command. Nothing in Hubitat answers this today,
and the map already holds the other two legs of the chain.

This is why the useful axis is `class` (internet vs lan) rather than vendor name. LIFX
Light Manager is LAN UDP despite LIFX being a cloud brand; Meross is internet dependent
despite the garage door being three metres away.

### UI

Per system rather than per app type, which is about 10 blocks instead of 19:

    [ Hue Bridge ]   class: (LAN v)        used by: [multi-select of app types]
    [ Meross Cloud ] class: (Internet v)   used by: [multi-select of app types]
    [ + add system ]

Native Hubitat inputs on a dynamic page. No new write endpoint, so the map page stays
read only and the OAuth token keeps guarding a pure view.

Settings names must be sanitised from the system name. App type strings contain spaces,
hyphens and dots ("CoCoHue - Hue Bridge Integration", "Zigbee Map 3.0.4").

### Known limitations to document

- User entered data is only as good as the person entering it.
- Kasa and Tapo are genuinely both local and cloud depending on configuration. One class
  per system is lossy for those.
- The built in seed will go stale as integrations rename. Keep it additive and always
  user overridable.

### Cost and risk

Roughly 250 to 350 lines: settings page, seed table, state merge, node and edge injection
into the graph builder, filter dropdown entry, focus mode, one Insights line.

Low risk, purely additive, does not touch scanning. With an empty table the map behaves
exactly as it does now. Main hazard is the GString backslash trap on any new JavaScript,
which `check_template.sh` catches mechanically. Run it before pushing.

---

## Integration registry (proposed, 1.3.0)

Adopt the registry pack (`hubitat_automation_map_registry_pack_v0.3.zip`, generated
2026-08-11) as the source of external dependency knowledge, replacing the hand-rolled
design above. Source files live in Gordon's Downloads folder; move the app/integration
registry into this repo before building, or the reference will rot.

### Why this supersedes the earlier design

Assessed 2026-08-12 by running its `matchRules` against the live hub. Three concrete
improvements over the superseded section:

**`dependencies[]` rather than one system per app.** LIFX is the proof case and the exact
thing the earlier design got wrong:

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

### Blockers to fix before building

- **The scanner does not collect the fields the matcher needs.** `appInfo` currently stores
  only `label` and `type` per app. The registry matches on `appName`, `parentAppName`,
  `driverName`, `namespace`, `deviceMetadata` and `userMapping`, so five of six fields have
  no data. Extending the scan is a prerequisite, not an optional refinement.
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

### Carry over from the superseded design

Still required, and not provided by the registry itself:

- Unclassified must be an explicit state, never silent absence.
- User assertions render differently from scanned relationships (dashed edges).
- Blast radius is the actual payoff: external system, to apps, to devices.

---

## Rule-to-rule mapping, Phase 1 only (proposed)

Requested by JimB on the community thread. Full design in
`hubitat_automation_map_rule_to_rule_implementation.md` (Gordon's Downloads folder).

Show when one automation causes, enables, disables or invokes another, instead of leaving
the reader to infer it from two rules touching the same device.

### Build Phase 1 only

Direct rule actions: Run Rule Actions, pause, resume, enable, disable, cancel. These are
explicit in Rule Machine's settings, need no inference, carry 100% confidence, and answer
"which rules control other rules" outright. The RM action decode already exists for the
flowcharts, so this is largely reusing work that is in the app today.

### Defer Phase 2, drop Phase 3 onward

Phase 2 is device-mediated relationships (Rule A writes a virtual switch, Rule B triggers on
it). That is where the real payoff sits and also where every false positive lives. Worth
doing after Phase 1, but only with the correctness discipline below enforced.

The source document is roughly five times bigger than what should be built: 15 edge types,
5 phases, cycle detection, feedback-loop oscillation analysis, three separate semantic JSON
files, and confidence values quoted to the percent (95 versus 90 is false precision).

### The discipline that must survive any trimming

This is the good part of the document and it is the same principle that killed endpoint
auto-detection. Without it, rule-to-rule is a false-positive generator that draws an edge
between any two rules touching the same device.

- A condition is not a trigger: TRIGGERS_VIA versus INFLUENCES_VIA (doc sections 10, 11).
- Value matching: Rule A sets switch X on, Rule B triggers on X off, therefore no edge (19).
- Key on `deviceId + attribute`, never `deviceId` alone (20).
- Required Expressions never produce trigger edges (23).
- POSSIBLE_RELATIONSHIP when subscription versus read cannot be established (44).

### Honest limitation to document

Only works for Rule-5.1. Room Lighting, Basic Rules, Simple Automation and webCoRE would be
invisible in the rule-to-rule view. Same partial-coverage trap as the rejected items below,
so it needs the same explicit "not analysed" state rather than silent absence.

---

## Rule Machine 5.1 execution documentation (separate publication)

`Rule_Machine_5_1_Execution_Explained_Draft.md` (Gordon's Downloads folder). A community
documentation project, not an Automation Map feature. Keep the two separate.

Assessed 2026-08-12 as high quality: sourced to specific Bruce Ravenel posts rather than
folklore, evidence markers separating documented from author-confirmed from unvalidated, and
tests T01 to T13 written as procedures with result placeholders instead of asserted
outcomes. T13 is the right instinct exactly, since the indexed docs still describe "Ignore
trigger events while running" while Bruce stated in June 2025 that it was removed.

### The one item that feeds back into this app

**Section 3 explains a limitation Automation Map does not currently disclose.** A false
Required Expression causes Rule Machine to drop its trigger subscriptions. This app derives
triggers from `eventSubscriptions`, which is a snapshot, so scanning while a rule's Required
Expression is false under-reports that rule's triggers, and a later rescan shows different
edges with nothing having changed.

Add this to the Limitations list in the README and the community post. Cheap, independent of
everything else here, and worth doing regardless of whether the document is ever published.

### Tests that can be run directly

T01 (Required Expression subscription gating), T03 (plain Delay schedules a continuation) and
T08 (delays accumulate) are all readable from `scheduledJobs` and `eventSubscriptions` on
`/installedapp/statusJson/<id>`, against virtual devices. The remaining ten need manual
observation.

---

## Rejected

### Auto detection of external endpoints from app settings

Investigated 2026-08-09, rejected. See the reasoning above. Roughly 40 percent precision
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
