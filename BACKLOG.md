# Backlog

Not shipped with the HPM package. Items here are agreed ideas awaiting a decision to build, not commitments.

---

## External system dependencies (proposed, 1.3.0)

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
