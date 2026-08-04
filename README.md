# Hub Map

**Status:** early exploration, not a working app yet.

The goal is a Hubitat app that visualizes how installed apps and devices relate to each other on the hub - which app owns/created which device, and which devices each app references in its configuration - rendered as an interactive force-directed graph, in the same visual style as Dan Danache's [Zigbee Map app](https://codeberg.org/dan-danache/hubitat/src/branch/main/zigbee-map-app) (vis-network graph, colour-coded nodes/edges, drag-to-explore).

## Why this is hard

Hubitat's app sandbox only gives an app authorized access to devices the user explicitly selects, and there's no official API for "list every installed app and what devices it's configured with". The only way community apps get hub-wide app/device data is by having the app fetch it from the hub's own internal admin endpoints (the ones the hub's web UI itself calls) via a self-request to `127.0.0.1` - an established but undocumented technique, not a public API.

Two other candidate data sources were ruled out first:
- A fresh hub backup (`.lzf`) is an H2 database, but it's encrypted at the storage layer with no known password.
- The Maker API (already set up, app id 2826) was tested live and confirmed to expose device attributes/commands only - no parent-app, parent-device, or per-app device-reference data at all.

## Components

### Apps

- `apps/hub_map_probe.groovy` - **throwaway diagnostic**, not the real app. Installs on the hub and fetches a batch of candidate internal endpoint paths (`/hub2/appList`, `/device/edit/<id>`, `/installedapp/configure/<id>`, etc.), showing status code and a body sample for each. Its job is to find out which endpoints actually exist and what shape they return, so the real app can be written against confirmed facts instead of guesses. Delete it once its results are captured.

## Next steps

1. Install `hub_map_probe.groovy` on the hub, run it once, capture the results.
2. Use those results to write the real app: enumerate installed apps and devices, extract ownership (parent app/device) and usage (device referenced in app config) relationships, and render them as a vis-network graph hosted the same way Zigbee Map hosts its pages (OAuth-token local file server via `mappings`).
3. Delete the probe app.
