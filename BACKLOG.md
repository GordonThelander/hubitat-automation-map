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

### 3. Revalidate Local Variable handling

**Why now:** first-class Hub Variable support is now live, but older evidence suggested that an
identically named Rule Machine Local Variable could be conflated with a Hub Variable. This is the
remaining variable-model correctness question.

**Next action:** capture a fresh fixture containing identically named Local and Hub Variables,
verify identity and edge separation in the graph, pivots and AI-friendly export, then fix only if
the current build still conflates them.

**Done when:** the fixture proves that Local and Hub Variable identities and relationships remain
separate across every output, or a verified fix makes them separate.

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

## Later / v3

### 10. Move graph derivation into the browser

Reduce Groovy-side rendering work and make UI iteration easier by sending normalized records and
deriving view-specific graph structures client-side.

### 11. Move remaining display shaping into the browser

After graph derivation is stable, migrate filtering, grouping, styling and panel preparation while
keeping scan collection and authoritative normalization on the hub.

### 12. Separate the frontend from the Groovy GString

Investigate a maintainable source and build arrangement for HTML, CSS and JavaScript without
breaking single-app Hubitat distribution.

### 13. Delta scanning

Only pursue partial scans if a cheap, reliable app or device change signal can be proven. A faster
but incomplete map is not acceptable.

### 14. Same-hub warm-start cache

Investigate a bounded cache that can restore a recent map quickly while clearly showing its age and
never presenting stale data as a completed current scan.

## Hold / closed

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
