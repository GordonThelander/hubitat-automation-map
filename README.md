# Automation Map

A Hubitat app that visualizes how installed apps and devices relate to each other, and **in what role** - which app owns a device, which devices trigger an app, which constrain it, and which it acts on - as an interactive force-directed graph, in the same visual style as Dan Danache's [Zigbee Map app](https://codeberg.org/dan-danache/hubitat/src/branch/main/zigbee-map-app).

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

- **App discovery can miss apps.** Apps are found via each scanned device's `appsUsingForDialog` list, which the hub truncates when a device is used by many apps (it carries an "and N more" count). Selecting all devices makes a miss unlikely but not impossible. There is no bulk app-list endpoint - `/installedapp/list.json`, `/hub2/appList` and similar all return 404.
- **Apps with no device references never appear**, since there is nothing to discover them through.
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
4. The scan runs in two phases - devices first (to discover apps), then apps (for the relationship data). Close and reopen the page to refresh progress.
5. Click **View Automation Map**.

Devices referenced by an app are added to the map automatically even if not selected in step 3 - the selection only decides which devices are used to discover apps.

## Using the map

- **Focus app** - show one app and every device it touches, coloured by role in that app.
- **Focus device** - show one device and every app that touches it.
- **Show** - filter to triggers, constraints, actions, or ownership.
