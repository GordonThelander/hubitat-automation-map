# Automation Map backlog

This file tracks agreed work that has not shipped. It is not included in the HPM package and is
not a release commitment.

## How this backlog is organised

- **Now**: correctness defects, misleading output and high-value usability work.
- **Next**: valuable work with a known direction but more design or investigation required.
- **Later / v3**: architectural work or lower-priority improvements.
- **Hold / closed**: deliberately deferred, rejected or completed items kept as a short record.

Every active item states its next action. Detailed research belongs in Supporting Docs or commit
history, not in this delivery list.

## Now

### 1. Desktop UI review and map workspace modernisation

**Why now:** the desktop map is powerful but visually dense. Important actions compete with raw
data, panels use space inconsistently, and several views are difficult to scan. This is a product
usability issue, not cosmetic polish.

**Review scope:**

- Test at 1440 x 900 and 1920 x 1080, including browser zoom at 100% and 125%.
- Review the map canvas, top-level actions, legend, search, focus views, Insights, External Systems,
  Community Utilities, baseline comparison and export entry points.
- Identify duplicated controls, competing visual emphasis, undersized text, overly long labels,
  weak grouping and panels that expose detail before the user asks for it.
- Check keyboard focus, close behaviour, scrolling, resize behaviour and restoration of the map
  after a panel closes.

**Preferred direction:**

- Keep the map as the dominant desktop surface.
- Replace scattered controls with a compact, clearly labelled tool rail or toolbar.
- Use one consistent panel shell with a stable header, close control and content region.
- Use progressive disclosure: summary first, supporting detail on demand.
- Maintain a practical 14 to 16 px text floor for ordinary content.
- Give primary actions, navigation and status distinct visual roles.
- Keep the legend compact and contextual rather than permanently consuming map space.
- Use concise tables, counts, filters and ranked findings instead of long prose lists.
- Preserve graph context when switching tools or opening detail.

**Deliverables:**

1. Annotated desktop UI audit with specific problems and affected views.
2. A low-risk layout proposal that can be delivered incrementally.
3. A desktop wireframe for the map, tool rail and shared panel shell.
4. An implementation sequence separating structural changes from visual refinement.
5. Acceptance checks for desktop readability, navigation and panel behaviour.

**Done when:** a user can quickly identify Search, Insights, External Systems, Community Utilities
and Export; only one primary panel is open at a time; ordinary text is comfortably readable; and
the graph remains useful while tools are opened and closed.

### 2. Open Automation Map in a normal browser tab

**Why now:** Hubitat's generated link opens a fixed-width popup that is cramped for the graph and
can be blocked by browsers. This is a contained usability fix that supports the wider desktop UI
work without depending on its redesign.

**Next action:** replace the framework popup link with a safe plain link that opens the map in a
normal new tab, then verify local, Remote Admin and cloud access paths.

**Done when:** the map reliably opens in a normal tab at the available browser size without
regressing authenticated local or remote access.

## Next

### 4. Include Dashboard usage in cleanup findings

Device cleanup advice should account for devices referenced by Hubitat Dashboard, not only rules and
apps already represented by the map.

**Next action:** confirm a reliable read-only source for dashboard device references, model the
relationship, then suppress false unused-device findings.

### 5. Add runtime activity and performance context

Users want help finding automations that may contribute to hub load, but configuration structure is
not execution evidence.

**Next action:** define a conservative metric model using available app statistics and timestamps,
label observations as evidence rather than conclusions, and prototype a ranked diagnostic view.

### 6. Expand a focused map one hop at a time

Allow users to reveal immediate neighbours without returning to the full graph.

**Next action:** specify one-hop expansion, duplicate suppression, reset behaviour and visible
provenance. This replaces the overlapping multi-select and extend-map backlog requests.

### 7. Export and import configured app data for migration

Provide a safe, portable representation of user-maintained settings such as external-system
overrides and icon choices.

**Next action:** define a versioned schema, conflict rules, preview step and validation behaviour.
Never import scan results or secrets as configuration.

### 8. Scan-schedule setting descriptions never render

Not urgent - cosmetic, and has been present in production (confirmed on the live v2.0.4 instance,
unrelated to any recent work) without being noticed until now.

The `autoScanEnabled` and `autoScanTime` inputs both set a `description:` ("On by default at
00:30/01:00...", "Leave blank for 00:30/01:00.") that never appears in Hubitat's rendered settings
page - confirmed on both the Dev and production instances, same gap on both, so this is a platform
rendering limitation for `bool`/`time` input descriptions, not something broken in this app's code.
The Hours/Minutes fields showing empty is correct - no time has ever been explicitly set, and the
default only applies internally at scheduling time, never as a pre-filled value.

**Next action:** replace the two `description:` attributes with a `paragraph` element instead,
since plain paragraphs render reliably everywhere else in this app.

### 9. Surface broken or disabled rules in Insights

Rule Machine rules that are paused, disabled, or already reporting an execution error are invisible
in the current map and Insights output; a user has to already know something is wrong and go check
Rule Machine directly.

**Next action:** confirm what rule health signals are reliably readable (paused/disabled state via
RMUtils, recent error status), then add a ranked Insights finding surfacing them, distinct from the
existing structural findings.

**Related bug found while designing item 18, fixed 2026-08-31:** the app label was showing a
duplicate inactive suffix on some live rules (observed as `[paused] [paused]`) - fixed as part of
item 18's work (`nodeEntry()`'s `statusInTitle` parameter) and confirmed live: the same rule's title
now reads `(Paused)` exactly once.

### 10. Live Hubitat platform update check

The existing "Hubitat release activity" panel only shows historical Community Utilities/Hubitat
release data; it never tells the user whether their own hub currently has an update available.

Investigated two approaches on 2026-08-27. Hubitat's own live update-check
(`/hub/cloud/checkForUpdate`) is real and confirmed working - a status read via the hub-rules MCP
server reported `UPDATE_AVAILABLE` (2.5.1.172 -> 2.5.1.174) with version, release-notes URL and beta
flag, and a second call actually triggered the install (download, apply, reboot). But that path is
only reachable from outside the app sandbox (via the MCP server's admin access), not from a Hubitat
app's own code, and it bundles the check together with the install - there is no way to ask "is one
available" without also committing to install if the answer is yes.

Better direction found the same day: `HPM_Manifest_Crawl`'s own feature-tracker dataset
(`site/feature-tracker/data/hubitat_release_features.json`, publicly fetchable, confirmed live)
already tracks every Hubitat release with a `version` and `releasedAt`. A sandboxed app can safely
read `location.hub.firmwareVersionString` (standard, documented) and compare it against that
dataset's latest entry - no undocumented endpoint, no admin access, no risk. The limit: this is a
scheduled crawl of the community forum, not a live Hubitat query, so it lags real releases by up to
one crawl cycle - confirmed directly, since at the moment 2.5.1.174 was installing on Gordon's hub,
the dataset's last harvest (2026-08-26) still only knew about 2.5.1.172.

**Next action:** publish a small derived `latest.json` (`{version, releasedAt}`) from the
`HPM_Manifest_Crawl` pipeline instead of shipping the full ~4 MB dataset to a Hubitat app, fetch it
from Automation Map, compare against `location.hub.firmwareVersionString`, and label the result
honestly as "latest known as of `releasedAt`" rather than "latest available" so the crawl lag stays
visible. Report only - never trigger an install from within Automation Map itself.

### 16. Structured Dev diagnostics and a comment-stripping production build

Gordon wants two things: a small structured trace schema for Dev troubleshooting (replacing the
current ad-hoc `AM-TRACE` log prose), and a build step that strips developer commentary out of what
`main`/HPM actually distributes, since end users installing via HPM do not need the annotated Dev
source's commentary. Development comments and diagnostic-only wording must never be exposed in the
production UI, exports, logs, telemetry, or generated source. The design also contains mandatory
runbooks for safe hub deployment and disciplined telemetry assessment, so future sessions do not
improvise either process. Full agreed design lives in the private, cross-project
`production-protocol` repo (`production_build_methodology.md`), not in this repo - moved there
2026-08-28 so it is reusable across projects instead of scoped to this one:
https://github.com/GordonThelander/production-protocol/blob/main/production_build_methodology.md

**Status: design agreed, nothing implemented.** The registry-finalization-race prerequisite this item
was previously waiting on is closed (v2.1.3, Dev-verified and independently confirmed on Steve's C-5
hardware; queue 270/271 record the controlled tests). That does not itself authorise starting this
item - it just removes a stale blocker from this text.

**Next action:** wait for Gordon to explicitly start this phase. No work begins before then. First
actual step, once started, is a small feasibility spike proving the Groovy-lexer-based comment
stripper works correctly on `apps/automation_map.groovy` alone - see the spec doc for the full spike
scope before any CI or branch-protection work is considered.

### 17. Add anonymous Variable coverage counts to telemetry

Variable discovery and Local-versus-Hub classification are now substantial product features, but the
current telemetry records only overall topology counts. Add bounded aggregate counts so testing can
show how widely these features are exercised and how often classification needs review across real
hubs.

**Proposed fields:**

- `hubVariables`
- `hubVariableConnectors`
- `localVariables`
- `ambiguousVariableReferences`
- `unresolvedVariableReferences`

These are counts only. Never transmit variable names or values, owning rule names, Connector device
IDs, Hub Variable types, a unique hub identifier, or any other identifying or free-form content.

**Next action:** define the exact count sources from the completed scan graph, then update the
Automation Map payload, telemetry driver validation/forwarding, Apps Script validation and headers,
and the live Google Sheet columns as one versioned schema change. Existing rows may remain blank in
the new columns. Do not implement only one layer because the current strict validators will reject or
discard a partial schema change.

**Done when:** one controlled Dev scan writes all five non-negative integer counts to the expected
columns, invalid payloads are rejected, existing telemetry fields remain unchanged, and inspection
confirms that no variable name, value, owner or Connector identity can enter the payload.

### 19. Canvas-level red suffix for disabled/paused labels

Item 18 (shipped, see Hold / closed below) implemented the `(Disabled)`/`(Paused)` label suffix and coloured the
native Focus dropdown options red for a disabled/paused entry, but left the canvas node label itself
as plain text rather than colouring just the suffix red as originally asked for. Colouring part of
one vis-network label needs its per-node rich-text mode (`font: {multi: 'html'}`) - a real, supported
feature, but a rendering path this codebase has never used anywhere and has no test coverage for.

**Next action:** Gordon's decision whether this is worth the risk of a rendering path with no
automated coverage. If yes, scope it to only the nodes that need it (per-node font override, not a
network-wide config change) and verify live on Dev before shipping, same as every other JS-only
change in this codebase.

## Later / v3

### 11. Move graph derivation into the browser

Reduce Groovy-side rendering work and make UI iteration easier by sending normalized records and
deriving view-specific graph structures client-side.

### 12. Move remaining display shaping into the browser

After graph derivation is stable, migrate filtering, grouping, styling and panel preparation while
keeping scan collection and authoritative normalization on the hub.

### 13. Separate the frontend from the Groovy GString

Investigate a maintainable source and build arrangement for HTML, CSS and JavaScript without
breaking single-app Hubitat distribution.

### 14. Delta scanning

Only pursue partial scans if a cheap, reliable app or device change signal can be proven. A faster
but incomplete map is not acceptable.

### 15. Same-hub warm-start cache

Investigate a bounded cache that can restore a recent map quickly while clearly showing its age and
never presenting stale data as a completed current scan.

## Hold / closed

- **Show disabled devices distinctly on the map (was item 18):** completed and verified on Dev,
  pending production release (2026-08-31) - disabled devices and paused/disabled rules get a
  canonical label suffix, structured export fields (`devices[].disabled`, `apps[].status`
  distinguishing `disabled`/`paused`), and coloured Focus dropdown entries. Also fixed the
  duplicate-suffix bug noted under item 9. The canvas-level red-suffix piece was deliberately left
  out - see item 19.
- **Component-device (parent/child) discovery and rendering:** completed and verified on Dev,
  pending production release (2026-08-31) - `/hub2/devicesList`'s hierarchical response (a
  device-owned component, e.g. a Shelly/Bond/Matter-bridge child, nested inside its parent's own
  `children` rather than as a top-level sibling) is now fully walked during discovery and rendered
  as a `hasComponent` relationship on the graph and in the AI export, including correct
  focus-expansion behaviour for an app that touches a child but not its parent directly.
- **Revalidate Local Variable handling (was item 3):** completed and verified on Dev, pending
  production release - identical names are not guessed or merged. A proven Local identity remains
  owner-scoped to its rule, a proven Hub identity remains hub-scoped, and an indistinguishable
  same-rule reference (persisted Rule Machine storage cannot always prove which was intended) is
  reported as ambiguous rather than assigned to either scope. Gate C shipped in v2.1.4 with live Dev
  verification; v2.1.6 added owner-scoped Local Variable graph nodes, focus and pivot support, and
  resolvable export endpoints, independently accepted per queue 315-317. The proposal document's
  `Draft`/`Implementation authorization: None` header is stale and should be corrected separately if
  the document is retained.
- **Hub Variable search:** shipped.
- **Variable Connector association:** shipped.
- **First-class Hub Variable identity, focus and export:** shipped.
- **Hub Variable pivot reconciliation:** fixed and independently verified on the Dev hub in
  v2.0.15, including a trailing-period variable with six read/write relationships.
- **Insights summary and readability redesign:** shipped; further work belongs to the desktop UI
  review above.
- **Actionable Insights guidance and AI-export alignment:** shipped and independently verified on
  the Dev hub in v2.0.15.
- **External Systems hierarchy, identity and reviewed seed classifications:** shipped and
  independently verified in v2.0.15. The only unassessed apps in the verification scan were two
  intentionally unknown scratch/test apps.
- **Current discovery wording:** corrected on the install page and in the README in v2.0.15.
- **Persistent manual node layout:** rejected because it conflicts with changing graph membership
  and creates fragile state.
- **Tablet-only legend redesign:** rejected as a separate item; desktop readability and responsive
  behaviour are covered by item 1.
- **Arbitrary node exclusion:** rejected because it can hide evidence and make the map misleading.
- **Single Hub Variable icon:** delivered in substance through first-class variable styling.

## Separate publication work

The Rule Machine storage-format write-up remains useful, but it is documentation work for the
developer utilities repository rather than an Automation Map product backlog item.
