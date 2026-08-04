# Hub Map

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

| Signal | Role | Evidence |
| --- | --- | --- |
| device in the app's `childDevices` | **owns** | LIFX Light Manager: 12 child lights, zero subscriptions - matches its source, which only calls `addChildDevice` |
| setting named `tDev*` | **trigger** | Rule Machine trigger devices; `tDev9` matched the rule's own "reports presence *changed*" trigger |
| setting named `rDev*` | **constraint** | Rule Machine conditions and required expression; `rDev_20` matched the rule's illuminance `>= 500` check |
| device appears in `eventSubscriptions` | **trigger** | general case - an app subscribes to what it listens to. Presence Manager's 5 subscriptions matched its `subscribeEvidenceDevices()` exactly |
| any other setting resolving to devices | **action** | `onOffSwitch.*`, `volume.*`, `note.*`, `siren.*`, `chime.*`, `speakDevice.*` |

`tDev`/`rDev` are Rule Machine's private naming and could change if Rule Machine changes. The `eventSubscriptions` and `childDevices` signals are structural and apply to every app.

A device can hold different roles in different apps - a motion sensor may trigger one rule and be acted on by another. Roles are therefore per-relationship, not per-device: edges are always coloured by role, and device nodes are coloured by role only when a single app is focused.

## Known limitations

- **App discovery can miss apps.** Apps are found via each scanned device's `appsUsingForDialog` list, which the hub truncates when a device is used by many apps (it carries an "and N more" count). Selecting all devices makes a miss unlikely but not impossible. There is no bulk app-list endpoint - `/installedapp/list.json`, `/hub2/appList` and similar all return 404.
- **Apps with no device references never appear**, since there is nothing to discover them through.
- Roles reflect how a device is *wired into* an app's configuration, not runtime behaviour.

## Components

### Apps

- `apps/hub_map.groovy` - the app.
- `apps/hub_map_probe.groovy` - **throwaway diagnostic**, not needed to run Hub Map. It dumps candidate internal endpoints so role detection could be built against confirmed facts rather than guesses. Kept in the repo as a tool for re-probing if a hub firmware update changes these undocumented endpoints. Safe to delete from the hub once Hub Map works.

## Installation

1. **Apps Code** -> **New App** -> paste in `apps/hub_map.groovy` -> Save.
2. **Apps** -> **Add User App** -> Hub Map.
3. Select devices to scan (use "Select All"), click **Scan relationships now**.
4. The scan runs in two phases - devices first (to discover apps), then apps (for the relationship data). Close and reopen the page to refresh progress.
5. Click **View Hub Map**.

Devices referenced by an app are added to the map automatically even if not selected in step 3 - the selection only decides which devices are used to discover apps.

## Using the map

- **Focus app** - show one app and every device it touches, coloured by role in that app.
- **Focus device** - show one device and every app that touches it.
- **Show** - filter to triggers, constraints, actions, or ownership.
