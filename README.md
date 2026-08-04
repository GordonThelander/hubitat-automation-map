# Automation Map

A Hubitat app that visualizes how installed apps and devices relate to each other, and **in what role** - which app owns a device, which devices trigger an app, which constrain it, and which it acts on - as an interactive force-directed graph, in the same visual style as Dan Danache's [Zigbee Map app](https://codeberg.org/dan-danache/hubitat/src/branch/main/zigbee-map-app).

## What "app" means here

Hubitat uses the word *app* for two different things, and the counts make no sense until you separate them:

- an **app type** is a piece of code in **Apps Code** - Rule Machine, Maker API, CoCoHue
- an **installed app** is one configured thing built on that code - your "Back Door Night" rule

Automation Map counts **installed apps**, the entries on your **Apps** page. On the hub it was developed against that is **64 installed apps built from only 17 app types**, because 41 of them are individual Rule Machine rules all sharing the one piece of Rule Machine code, alongside 5 Averaging Master instances, 3 Maker APIs and 2 Notifiers.

So each orange square is a single automation or integration you set up, not a piece of code. That is deliberate: "Back Door Night" and "Patio Night" control different lights, so collapsing them into one Rule Machine node would throw away the entire point of the map.

## Where the data comes from

There is no official Hubitat API for "list every app and what devices it uses, and how". The data comes from the hub's own internal endpoints (the ones the hub's own web UI calls), fetched via a self-request to `127.0.0.1` - an established community technique, not a public API.

| Endpoint | Used for |
| --- | --- |
| `/device/fullJson/<id>` | `parentApp` + `appsUsingForDialog`, used **only to discover which app ids exist** |
| `/installedapp/statusJson/<id>` | the real relationship data per app: `childDevices`, `eventSubscriptions`, and every setting that resolves to devices |

Two other candidate sources were ruled out first:

- A hub backup (`.lzf`) is an H2 database, but it is encrypted at the storage layer with no known password.
- The Maker API was tested live and exposes device attributes/commands only - no parent-app, subscription, or per-app device-reference data.

## How roles are decided

Derived by probing two apps whose source is known (Presence Manager, LIFX Light Manager) plus one Rule Machine rule, and cross-checking against both the source and the rule's own UI:

Checked in this order:

| Signal | Role | Evidence |
| --- | --- | --- |
| device in the app's `childDevices` | **owns** | LIFX Light Manager: 12 child lights, zero subscriptions - matches its source, which only calls `addChildDevice` |
| setting named `tDev*` | **trigger** | Rule Machine trigger devices; `tDev9` matched the rule's own "reports presence *changed*" trigger |
| setting named `rDev*` | **constraint** | Rule Machine conditions and required expression; `rDev_20` matched the rule's illuminance `>= 500` check |
| setting type is the wildcard `capability.*` | **exposed** | integrations publishing devices to an external system. Maker API and Google Home both use it; without this, one Maker API instance alone contributed 192 false "commands this device" edges |
| device appears in `eventSubscriptions` | **trigger** | general case - an app subscribes to what it listens to. Presence Manager's 5 subscriptions matched its `subscribeEvidenceDevices()` exactly |
| setting type is a capability with no commands | **monitor** | watched, not driven. Critical Device Monitor subscribes only to its water/smoke/CO pickers but also inspects contact, motion, lock and garage-door pickers it never commands |
| any other setting resolving to devices | **action** | `onOffSwitch.*`, `volume.*`, `note.*`, `siren.*`, `chime.*`, `speakDevice.*` |

Only the `tDev`/`rDev` rules are Rule Machine specific. `childDevices`, `eventSubscriptions` and capability types are platform-level, so the graph covers apps it was never written against - it handled all 17 app types on the development hub, 12 of them integrations with no specific support.

## Rule flows

Focusing a single app draws its logic as a flowchart: trigger, gating expression, then the ordered actions including waits and their timeouts, with `IF` / `ELSE-IF` / `ELSE` rendered as real branches.

This is reconstructed from each app's internal state, **not** from its source. Built-in apps are compiled classes and expose no source at all (`/app/ajax/code` returns an empty body for them), which is equally true of Rule Machine - it was decoded purely from runtime state, cross-checked against its own UI.

Supported today:

- **Rule Machine 5.1** (`SUPPORTED_RULE_ENGINE`). Rule Machine's `indent` field is not used: one real rule opens three `IF`s and closes only two, and its indents disagree with the visible nesting, so structure comes from the control-flow markers with a stack that auto-closes anything left open.
- **Notifier**, a built-in app, which stores an already-rendered description in `state.text`.

Rules on any other engine still appear in the graph with their device relationships; they are counted and reported rather than silently producing an empty flow.

## Insights

- **Contested devices** - more than one app can leave the device in a lasting state. Restricted to stateful capabilities, because two apps notifying the same phone is not a conflict while two apps driving the same light is. On the development hub this distinction cut the list from 76 entries to 28 real ones.
- **Devices nothing references** - no app owns, watches or drives them.

`tDev`/`rDev` are Rule Machine's private naming and could change if Rule Machine changes. The `eventSubscriptions` and `childDevices` signals are structural and apply to every app.

A device can hold different roles in different apps - a motion sensor may trigger one rule and be acted on by another. Roles are therefore per-relationship, not per-device: edges are always coloured by role, and device nodes are coloured by role only when a single app is focused.

## Known limitations

- **The app count is of installed apps that reference at least one device.** Apps are discovered by asking each scanned device which apps use it, so an installed app referencing no devices is invisible to this method and will not appear in the map or the count. There is no bulk app-list endpoint to cross-check against - `/installedapp/list.json`, `/hub2/appList` and similar all return 404 - so the total cannot be reconciled with your Apps page automatically.
- **App discovery can also miss apps that do use devices.** Each device's `appsUsingForDialog` list is truncated by the hub when a device is used by many apps (it carries an "and N more" count). An app that only ever appears in a truncated list can be missed. Selecting all devices makes this unlikely but not impossible.
- **Flow decoding is engine-specific.** Rule Machine 5.1 and Notifier are supported. Other engines (Rule 4.x, Simple Automation, Basic Rules, Room Lighting, Motion Lighting) have their own private layouts and would each need decoding the same empirical way. They are detected and reported, not silently skipped.
- **Event subscriptions are a snapshot, not static configuration.** Observed live: rule 2279 "Back Door Night" was subscribed *only* to its Required Expression device, with no subscription to its actual trigger, because that expression was false at scan time - Rule Machine drops trigger subscriptions while the gate is closed and restores them when it opens. Rule Machine apps are unaffected, because `tDev*`/`rDev*` settings are static and take precedence over the subscription signal. For **non-Rule-Machine apps**, where subscriptions are the only trigger signal, an app that subscribes conditionally can map differently depending on when the scan ran.
- Roles reflect how a device is *wired into* an app's configuration, not runtime behaviour.

## Components

### Apps

- `apps/automation_map.groovy` - the app.
- `apps/automation_map_probe.groovy` - **throwaway diagnostic**, not needed to run Automation Map. It dumps candidate internal endpoints so role detection could be built against confirmed facts rather than guesses. Kept in the repo as a tool for re-probing if a hub firmware update changes these undocumented endpoints. Safe to delete from the hub once Automation Map works.

## Installation

1. **Apps Code** -> **New App** -> paste in `apps/automation_map.groovy` -> Save.
2. **Apps** -> **Add User App** -> Automation Map.
3. Select devices to scan (use "Select All"), click **Scan relationships now**.
4. The scan runs in two phases - devices first (to discover apps), then apps (for the relationship data). It takes a couple of minutes on a large hub; the page refreshes itself and shows a percentage, so there is no need to reload it.
5. Click **View Automation Map**.

Devices referenced by an app are added to the map automatically even if not selected in step 3 - the selection only decides which devices are used to discover apps.

## Using the map

**Designed for a desktop browser.** The graph, filter panel and flowcharts need room and a pointer, so a small screen is shown a notice rather than an unusable shrunken version.

- **Focus app** - show one app and every device it touches, coloured by role in that app. If it is a supported rule engine you also get a flowchart of its logic.
- **Focus device** - show one device and every app that touches it.
- **Show** - filter to a single relationship type: triggers, constraints, monitored, actions, exposed or ownership.
- **Insights** - contested and unreferenced devices (see above).
- Each filter has a **search box**, since a large hub puts a couple of hundred entries in the device list.

The whole-hub view is dense by nature. Focusing one app or device is the normal way to use it; the opening screen says so.

## Re-scanning

The map is a snapshot taken when you scan, not a live view. Re-scan after adding or reconfiguring apps or devices. If the app is upgraded and the stored graph no longer matches what the new version draws, it refuses to display a stale map and tells you to scan again, rather than rendering something subtly wrong.
