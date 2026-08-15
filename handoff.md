# Automation Map - Development Handoff

Prior contents (the JimB scan-start investigation through the 1.8.8 diagnostic
build) were cleared 2026-08-15. They remain in git history:

    git show 2026af4:handoff.md

---

## v1.8.9: the actual fix for JimB's scan-start error

The 1.8.8 diagnostic build did its job. Jim's screenshots showed a 404 HTML
page from `remoteaccess.aws.hubitat.com` and a completely silent hub log for
the same button press - proving the request never reached the app at all. He
reaches his hub through Hubitat's Remote Admin cloud gateway, not the LAN.

`getLocalURL()` builds a URL and then deliberately strips the scheme and host,
returning only a relative path. That is correct when the page is served from
the hub's own address, and wrong when it's served from Remote Admin's - the
relative path resolves against whatever origin is currently in the address
bar, and Remote Admin doesn't proxy that path shape.

### What changed

- `getLocalOrigin()` and `getCloudURL()` added next to `getLocalURL()`. The
  origin one exposes just the hub's scheme+host so the browser can compare it
  against `window.location.hostname`. The cloud one mirrors `getLocalURL()`
  but builds off `fullApiServerUrl` (Hubitat's cloud-reachable address)
  instead of `fullLocalApiServerUrl`.
- `amPickURL(localPath, cloudUrl)` added to both client-side templates (the
  Scan button's script, and the map page's script). Checked at the moment of
  use, not baked in at render time - it compares the browser's actual current
  origin to the hub's own, and only falls back to the absolute cloud URL when
  they don't match. Local users see no behaviour change and no new internet
  dependency; a Remote Admin user gets a working request instead of a 404
  before this app's code ever runs.
- Applied to the Scan button's fetch and to `EXT_URL` (the External systems
  panel), which covers both places a JS fetch could make that runtime check.
- The "View Automation Map" link is different: it's a native Hubitat `href()`
  element on the settings page, rendered server-side with no request object
  exposed to app code, so there's no way to detect which origin THAT page
  request came in on. Added a second explicit link next to it - "Open
  Automation Map (via Hubitat cloud...)" - pointing at the absolute cloud URL,
  rather than guessing wrong for one group of users either way.

### Not yet verified against a live hub

Local `groovyc` compiles clean, but that only proves syntax, not that
`fullApiServerUrl` behaves the way the community examples and docs describe
inside Hubitat's actual sandbox. Specifically unverified:

- That `fullApiServerUrl` (used here as a bare property, matching how this
  file already uses `fullLocalApiServerUrl`) resolves without error and
  produces a `https://cloud.hubitat.com/api/<hubUID>/apps/<appId>` shape.
- That the resulting cloud URL actually reaches this app's OAuth mappings
  when requested from a browser - i.e. that this isn't just a plausible
  construction but one that Hubitat's cloud relay will actually route.
- That `amPickURL`'s origin comparison behaves correctly on Gordon's own
  local-LAN hub (should pick the local path, unchanged behaviour) once pushed.

### Live-tested against Remote Admin, 2026-08-15

Pushed to Gordon's dev hub and tested on his own install through Remote Admin
(`remoteaccess.aws.hubitat.com`), same access path as JimB's.

First attempt failed: `Failed to fetch | tried:
https://cloud.hubitat.com/api/f4e77355-.../apps/2976/scan`. The URL shape was
exactly right - `fullApiServerUrl` resolves correctly - but `fetch()` cannot
read a cross-origin response without CORS headers, and Hubitat's cloud API
does not send them. Confirmed as CORS, not a malformed URL, because the
attempted-URL diagnostic (added specifically because the first failure gave
no detail at all) showed a well-formed address.

Gordon asked why not just use the cached LAN address directly instead of the
cloud one. Doesn't work: `10.0.0.125` is a private address, unreachable from
outside the LAN regardless of caching - that's a routing fact, not something
either side of this app can configure around. That's specifically why Remote
Admin exists.

Fix: CORS restricts *reading* a cross-origin response, not sending the
request, and does not apply to navigation at all. So the cloud-fallback case
now fires the same URL via a hidden iframe instead of `fetch()` - the request
still reaches `scanMapping()` and still starts the scan, this code just can't
see what came back. Reloads after 4 seconds and lets the page's own
server-rendered state show the result, the same way the local success path
already relies on a reload rather than reading the response.

**Confirmed working live**: after the iframe fix, pressing Scan on Remote
Admin genuinely started a scan on Gordon's hub - the reload showed real
progress ("Reading devices: 105 of 193, 54%"), not a stuck or failed state.

Known limitation, accepted rather than solved: if a cloud-triggered scan ever
fails partway, there's no way to read why over this path - progress just
stops advancing, with none of the detailed HTTP-status/body diagnostic the
local path gets. CORS blocks reading the response regardless of whether it's
success or failure.

Not yet fixed: the External systems panel (`EXT_URL`) has the identical
cross-origin `fetch()` pattern and will hit the same CORS block through Remote
Admin. Harder than the Scan button - it needs to read structured JSON back,
including a save/POST - so a plain navigation swap won't cover it the way it
did here. Deliberately left alone rather than guessing at a fix blind; revisit
once the Scan button fix is confirmed solid.

### Next step

Confirm the scan Gordon just triggered completes and updates the node/edge
counts, same as a local scan would. If so, 1.8.9 is ready for JimB to test:
Scan button and the "via Hubitat cloud" map link. The External panel remains
untested/unfixed for Remote Admin and should be called out as a known gap if
Jim reports back on it.
