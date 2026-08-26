# Community Release Activity Embed for Automation Map

**Status:** Proposed specification for joint review  
**Target:** Iteration after the Community Context Card  
**Repositories:** `hubitat-automation-map` and `HPM_Manifest_Crawl`  
**Primary outcome:** Give Automation Map users a useful preview of Hubitat release activity that
drives intentional traffic to the full Community Utilities Update Tracker.

## 1. User outcome

Automation Map users can open a compact **Hubitat release activity** panel from the map. The panel
shows a purpose-built, read-only version of the Community Utilities releases-over-time chart.
Selecting the preview opens the full Update Tracker in a new browser tab.

The preview is an invitation to explore the Community Utilities site. It is not part of map
discovery, matching, scanning or export.

## 2. Scope

### Included

- A visible Automation Map action labelled `Hubitat release activity`.
- A dismissible Automation Map panel containing a responsive iframe.
- A dedicated Community Utilities embed route containing the releases-over-time chart.
- A prominent `Explore the full Update Tracker` link.
- Click-through from the chart area to the full Update Tracker in a new tab.
- Loading, unavailable and unsupported-browser states.
- Basic anonymous view and click-through measurement using the site's existing aggregate analytics.

### Excluded from the first iteration

- Embedding the complete Update Tracker page or its site navigation.
- Passing hub identity, installed apps, devices, access tokens or scan content to the website.
- Filtering the chart from private Automation Map data.
- Making scan, map rendering, export or Community Context Cards depend on the iframe.
- Interactive filtering inside the embed.
- Automatic opening on page load.

## 3. Community Utilities deliverable

Create a stable HTTPS route such as:

```text
https://gordonthelander.github.io/HPM_Manifest_Crawl/embed/release-activity/
```

The route should contain only:

- the chart title;
- the current release-date range;
- the releases-over-time chart and legend;
- a short freshness label;
- an `Explore the full Update Tracker` call to action; and
- a short independent-community-project attribution where practical.

It should not contain the normal site header, full navigation, search form, catalogue cards or
footer navigation.

### 3.1 Click behavior

Clicking the chart or call to action opens the canonical Update Tracker in a new tab. The embed
must not navigate Automation Map's iframe to the full site.

Use a normal HTTPS link with `target="_blank"` and `rel="noopener noreferrer"`. The entire chart
may be wrapped in that link or covered by an accessible click-through layer, provided keyboard
users can activate it and the chart remains readable.

### 3.2 Embedding policy

The published response must permit framing by the Hubitat page. Verify the effective response does
not send `X-Frame-Options` or a Content Security Policy `frame-ancestors` rule that blocks local
hub origins. This must be checked against the live GitHub Pages response before app integration is
declared ready.

### 3.3 Responsive design

- Designed for an iframe width from 320 to 1,100 CSS pixels.
- No horizontal scrolling at 320 CSS pixels.
- Chart labels remain legible at phone width.
- No fixed page height that clips the chart.
- Light visual treatment suitable inside Automation Map's dark map surface, preferably through a
  self-contained card with its own background.

The 320-pixel requirement makes the embed reusable outside Automation Map. Automation Map itself
currently replaces the map interface below an 820-pixel viewport, so this feature does not change
or bypass that existing small-screen policy.

## 4. Automation Map deliverable

Add a clearly labelled action directly above the existing Community Utilities button in
`#controls`'s vertical stack. It opens a panel consistent with the existing flow, external-system,
pivot and icon panels rather than introducing the application's first modal or permanently
occupying map space.

Suggested presentation:

```text
[Hubitat release activity]

Panel title: Hubitat releases over time
Supporting text: Community Utilities release history and documented changes.
[embedded preview]
[Open full Update Tracker]
```

### 4.1 Loading behavior

- Do not create or load the iframe until the user opens the panel.
- Create it at most once per map-page session, then retain or recreate it without repeated hidden
  background loading.
- Loading must not block map interaction.
- Closing the panel must return focus to the launch control.
- Reuse the app's established panel coordination so closing this panel does not incorrectly restore
  or hide another open panel or the legend.

### 4.2 Readiness handshake

Success is proven by an explicit `postMessage` from the embed, never by the iframe's own `load`
event. A blocked or HTTP-error cross-origin response still fires `load`, so `load` cannot
distinguish a rendered chart from a 404 or a framing refusal, and must not mark the load settled.

The embed sends, only after its dataset has validated and its chart, range and freshness text have
rendered:

```javascript
window.parent.postMessage({ type: 'automation-map-release-activity-ready', version: 1 }, '*');
```

The wildcard target is required: the reusable public embed cannot know the private hub origin of the
page framing it. The message carries no hub, user or map data and grants no capability.

Automation Map accepts the message only when **all** of the following hold:

| Check | Required value |
| --- | --- |
| `event.origin` | `"null"` under the section 4.3 sandbox. The literal `https://gordonthelander.github.io` is also accepted, for a possible future unsandboxed execution context only. |
| `event.source` | Strictly equal to this iframe's own `contentWindow`. |
| `event.data.type` | Exactly `automation-map-release-activity-ready`. |
| `event.data.version` | Exactly `1`. |

**On the origin value.** The sandbox in 4.3 deliberately omits `allow-same-origin`, which gives the
embed document an *opaque* origin; everything it posts therefore arrives as origin `"null"`, never
the Pages host. An earlier revision of this contract required the literal host, which could never
match - the handshake never completed, the timeout always fired, and a correctly rendered chart was
torn down and replaced by the 4.2 failure state on every open. Measured live and corrected
(design review).

Provenance rests on `event.source`, not on the origin string: this page creates the iframe and sets
its `src` to the single fixed, source-controlled URL from 4.3, and no other frame can forge
`event.source`. Requiring the host string instead would mean granting `allow-same-origin`, which
4.3 forbids and which would weaken the sandbox for no gain.

Only a message passing all four checks clears the timeout. The listener is removed on verified
readiness, timeout, or explicit iframe error.

### 4.2.1 Failure behavior

If the site is unavailable, blocked from framing, times out without a verified readiness message, or
raises an explicit iframe error, show:

```text
The release preview could not be loaded.
[Open the Community Utilities Update Tracker]
```

The direct link remains available. No repeated automatic retries occur during that page session.
The failure must not affect any Automation Map function.

### 4.3 Security and privacy

- Use one fixed, source-controlled HTTPS embed URL. Do not accept a URL from query parameters,
  exported data or hub state.
- Use an iframe `title` describing its content.
- Use `loading="lazy"`.
- Apply the narrow sandbox baseline
  `allow-scripts allow-popups allow-popups-to-escape-sandbox`. Do not add `allow-same-origin`.
- Do not grant same-origin, forms, downloads, clipboard, camera, microphone, geolocation or storage
  permissions unless separately justified and reviewed.
- Set a restrictive `referrerpolicy`, preferably `no-referrer`.
- Do not append app IDs, device IDs, hub address, access token, map contents or user search terms to
  the iframe or outbound URL.

## 5. Traffic and measurement

The integration should drive useful, voluntary traffic rather than manufacture page loads.

- Opening the panel may count as an embed-page view.
- Clicking the chart or call to action should open the full Update Tracker.
- If campaign attribution is desired, use a static non-identifying parameter such as
  `?ref=automation-map-release-preview`.
- Do not fingerprint hubs or create per-install identifiers.
- Do not preload the iframe merely to increase site traffic.

Suggested full-page destination:

```text
https://gordonthelander.github.io/HPM_Manifest_Crawl/update-tracker/?ref=automation-map-release-preview
```

## 6. Accessibility

- Launcher is a real button or link and is keyboard accessible.
- Panel has a programmatic heading and usable close control.
- Iframe has a meaningful `title`.
- The embed includes a text link independent of chart pointer interaction.
- Focus is contained appropriately while a modal is open and restored when closed.
- Information is not communicated by colour alone.
- Reduced-motion preferences are respected.

## 7. Acceptance criteria

### Community Utilities

1. The live embed route contains only the compact release preview and call to action.
2. It renders without horizontal scrolling at 320, 768 and 1,100 CSS pixels.
3. The live response can be framed from a Hubitat-hosted Automation Map page.
4. Chart and call-to-action clicks open the full Update Tracker in a new tab.
5. The displayed data and freshness date agree with the full Update Tracker dataset.
6. Existing site build, dataset and route tests pass.

### Automation Map

1. No request for the embed occurs before the user opens it.
2. Opening and closing the preview does not move, rebuild or reset the map.
3. The panel behaves correctly alongside every existing panel and the legend.
4. A blocked or unavailable iframe leaves the direct Update Tracker link usable.
5. No hub or scan information is transmitted in the URL, referrer or client messages.
6. The panel remains usable across Automation Map's supported viewport range, beginning above its
   existing 820-pixel small-screen cutoff.
7. Existing validation and template checks pass.

## 8. Ownership and sequence

1. **Community Utilities site:** implement and publish the dedicated embed route, tests and live
   framing verification.
2. **Automation Map app:** implement the launcher, panel, lazy iframe and failure treatment only
   after the live embed contract is available.
3. **Review:** the combined source and live behavior.
4. **Gordon:** approve Dev hub testing and the eventual release.

The Community Context Card iteration should be completed and accepted before this feature begins,
so the two integrations do not compete for panel behavior or review attention.

## 9. Future extensions

The same embed contract could later support separate, purpose-built previews for Identity Resolver,
package health, network evidence and recent ecosystem changes. Each requires its own specification
and stable embed route. Automation Map should not expose a general-purpose arbitrary website iframe.
