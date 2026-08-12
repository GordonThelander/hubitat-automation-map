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

Both found while building 1.3.3, both currently handled by showing less rather than
guessing. Neither blocks anything.

- **`pvTF.<n>` reads inverted.** It looks like it holds the value a Set Private Boolean
  action writes, but is backwards against the rule page in all three observed cases: rule
  1806 action 31 (`true`, page shows "Rule Boolean False"), 1806 action 33 (`false`, page
  shows "True"), and 2972 action 7 (`true`, page shows "False"). Ordering is confirmed
  correct, since every other step of 1806 matches its page exactly. Rendering `!pvTF` would
  be right on every known case, but the reason is unknown, so the value is not shown at all.
- **Pause and Resume cannot be told apart.** Both use `getPauseResumeRules`. `pR.<n>` looks
  like the discriminator but is empty on the only available example, which the rule page
  shows as a Pause. Both currently render as "Pause / Resume Rules". One rule with a Resume
  action would settle it.

### Honest limitation, already documented in the README

Only Rule-5.1 is analysed. Room Lighting, Basic Rules, Simple Automation and webCoRE show no
links rather than showing that they have none.

---

## Rule Machine 5.1 documentation (written, awaiting publication)

`Supporting Docs/rule_machine_5_1_storage_format.md`. Documents how Rule Machine **stores**
a rule, verified against a live hub. A community publication, not an app feature.

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
