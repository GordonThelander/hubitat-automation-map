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

### 1. Verify and fix Hub Variable pivot accuracy

**Why now:** incorrect relationship counts or directions make the pivot misleading and undermine
trust in the new first-class Hub Variable support.

**Next action:**

1. Reproduce the reported mismatch on the current Dev build with a fresh scan.
2. Count Hub Variable read and write edges independently.
3. Compare both Hub Variable pivot presets against the graph and AI-friendly export.
4. Determine whether the mismatch comes from filtering, edge direction, duplicate identity or
   stale scan data.
5. Fix the cause and verify the on-screen table and CSV export against the same fixture.

**Done when:** both presets show the expected variables, apps and directions, and the exported
rows agree with the visible table.

### 2. Reconcile and apply External Systems seed assessments

**Why now:** the panel structure is substantially better, but too many obvious dependencies remain
unclassified. That makes a core feature look unfinished.

The latest user-supplied seed matrix is the working ground truth. It includes explicit assessments
for AI Connector, Bureau of Meteorology, Chromecast, CoCoHue, Google Home, Kasa, LIFX, Maker API,
Meross, Sensibo, Tapo and mDNS, plus local-only classifications for built-in and community apps.
Some entries conflict with older backlog material and must be reconciled rather than silently
combined.

**Next action:**

1. Convert the latest seed matrix into canonical records keyed by app type and namespace.
2. Record relationship, external system, system kind, criticality and provenance separately.
3. Resolve conflicts with older registry and override records in favour of verified current data.
4. Merge seeds with `state.userRegistry` without replacing user overrides.
5. Ensure the panel, graph and AI-friendly export report the same classification.
6. Add regression fixtures for the named integrations and for a genuinely unknown app.

**Done when:** known seeded integrations are classified consistently, local-only apps are not
presented as unexplained external dependencies, and unknown remains a meaningful exception.

### 3. Correct stale discovery wording

**Why now:** the install page and README still describe device-led app discovery, while the app now
enumerates apps directly and then discovers relationships. The wording understates coverage.

**Next action:** update the install page, README and any matching help text to describe direct app
enumeration, unreadable-item gaps and relationship discovery accurately.

**Done when:** public documentation matches the current scan implementation and status model.

### 4. Desktop UI review and map workspace modernisation

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

## Next

### 5. Revalidate Local Variable handling

Earlier evidence suggested some Rule Machine Local Variables could be conflated with Hub Variables.
That finding predates the authoritative Hub Variable inventory and first-class variable model.

**Next action:** capture a fresh fixture containing identically named local and Hub Variables,
verify identity and edge separation, then fix only if the current build still conflates them.

### 6. Include Dashboard usage in cleanup findings

Device cleanup advice should account for devices referenced by Hubitat Dashboard, not only rules and
apps already represented by the map.

**Next action:** confirm a reliable read-only source for dashboard device references, model the
relationship, then suppress false unused-device findings.

### 7. Add runtime activity and performance context

Users want help finding automations that may contribute to hub load, but configuration structure is
not execution evidence.

**Next action:** define a conservative metric model using available app statistics and timestamps,
label observations as evidence rather than conclusions, and prototype a ranked diagnostic view.

### 8. Expand a focused map one hop at a time

Allow users to reveal immediate neighbours without returning to the full graph.

**Next action:** specify one-hop expansion, duplicate suppression, reset behaviour and visible
provenance. This replaces the overlapping multi-select and extend-map backlog requests.

### 9. Export and import configured app data for migration

Provide a safe, portable representation of user-maintained settings such as external-system
overrides and icon choices.

**Next action:** define a versioned schema, conflict rules, preview step and validation behaviour.
Never import scan results or secrets as configuration.

### 10. Open Automation Map in a normal browser tab

Hubitat's generated link opens a fixed-width popup, which is cramped for the graph and can be
blocked by browsers.

**Next action:** replace the framework popup link with a safe plain link that opens the map in a
normal new tab, then verify local and remote access paths.

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

- **Hub Variable search:** shipped.
- **Variable Connector association:** shipped.
- **First-class Hub Variable identity, focus and export:** shipped.
- **Insights summary and readability redesign:** shipped; further work belongs to the desktop UI
  review above.
- **External Systems hierarchy and identity handling:** shipped; classification coverage remains
  active in item 2.
- **Persistent manual node layout:** rejected because it conflicts with changing graph membership
  and creates fragile state.
- **Tablet-only legend redesign:** rejected as a separate item; desktop readability and responsive
  behaviour are covered by item 4.
- **Arbitrary node exclusion:** rejected because it can hide evidence and make the map misleading.
- **Single Hub Variable icon:** delivered in substance through first-class variable styling.

## Separate publication work

The Rule Machine storage-format write-up remains useful, but it is documentation work for the
developer utilities repository rather than an Automation Map product backlog item.
