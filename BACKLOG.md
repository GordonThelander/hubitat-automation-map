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

## Release gate

**Closed, and superseded by an actual release.** Steve retested the original missing
component-device scenario on his own hub: Hubitat reported 351 devices and Automation Map matched
it at 351; he specifically checked Aqara, Bond, Harmony, and Shelly devices and confirmed all were
present, and described the release as "spot on" with nothing else discovered on his end. That
defect, along with everything else accumulated on `dev` through 2026-09-02, shipped to production
as v2.2.0 (2026-09-03) - see item 16 in Hold/closed for the release path. This section is now a
historical record, not an open gate.

## Now

### 20. Production-builder line-ending hardening

Found during the v2.2.0 `main` promotion (2026-09-03): the production candidate generator is not
actually commit-pure. It consumes the physical line endings of the working-tree checkout rather than
a canonicalized form, while the provenance check (`git hash-object --path`) applies Git's own EOL
normalization for comparison - so provenance can pass while checkout-specific line-ending bytes
(e.g. after switching branches on a machine with `core.autocrlf=true`) still change the generated
candidate's actual bytes. Independently confirmed on review as required hardening before the next
production build, not an optional polish item. The v2.2.0 release
itself is unaffected - the contaminated build was caught before promotion and rebuilt in an isolated
`git worktree`, verified byte-identical to the previously-reviewed candidate - but the underlying gap
in the tooling remains.

**Both parts required together:**

1. Make candidate generation commit-pure: canonicalize output line endings (e.g. always emit LF), or
   fail closed on mixed/noncanonical line endings in the input before generating.
2. Add an explicit `.gitattributes` policy for the build-input paths. Every build input is stored
   LF in the repository, but the repo declares no policy, so `core.autocrlf` converts them to CRLF
   in the working tree on checkout - the exact mechanism behind the v2.2.0 incident. Needs a
   deterministic LF/CRLF fixture test proving identical candidate bytes either way.

**Next action:** wait for Gordon to explicitly start this phase, same gating discipline as item 16.

## Next

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

**Status:** audit, low-risk proposal, desktop wireframe and acceptance matrix completed on
2026-08-31 in `WIP/desktop_ui_review_and_modernisation.md`. The next implementation gate is Phase 1,
the structural workspace shell. Source implementation, Dev deployment, commit, push and production
promotion remain separately authorised actions.

**Live feedback from Gordon's own testing (2026-09-02) - implemented ahead of the fuller audit/
wireframe, all three confirmed live on Automation Map (Dev) / Apps Code 1210:**

- The External Systems "Community information" card (e.g. the LIFX Light Manager tile) is narrower
  (`#communityCard` max-width). Done.
- Spacing added between the top Focus dropdowns and the "Show" relationship filter below them
  (`#showFilterLabel` margin-top). Done.
- The four Focus dropdowns (Apps, Devices, Hub Variables, Local Variables) are now a single combined
  combobox each, replacing the old search-input-stacked-above-a-select pair. Proven standalone first
  in `Bucket/combobox-harness/` (31 automated checks) before porting, then iterated live against
  Gordon's own feedback: a non-editable closed control (label + arrow) opens a popup whose first row
  is a dedicated, auto-focused search field, with the filtered options list directly below it and no
  pinned "All X" row once a filter term is typed. `#controls` widened 150px -> 300px and the hub
  watermark image repositioning tracks the panel's own right-anchored geometry (`right:` instead of
  a fixed `left:` percentage) so the two cannot drift out of alignment again the way they did when
  the panel first widened. Done.

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

- **Structured Dev diagnostics and a comment-stripping production build (item 16):** the
  comment-stripping production build half shipped as v2.2.0 (2026-09-03) - a small, versioned
  allowlist of exact substitutions (never a general transform), each proven via full
  structural/positional comparison against the annotated Dev source, git-bound to one verified
  commit. Built as `tools/production-builder/` (`production-profile.groovy`,
  `production-manifest.groovy`, `production-package.groovy`), hardened across multiple review
  rounds with independent verification at each step, methodology generalized and written up at
  `hubitat_dev_utililities/Provenance-Verified Substitution Build/README.md` for reuse elsewhere.
  Release path: verified build -> isolated manual HPM install/test on a non-colliding app id ->
  Gordon's explicit hub confirmation and production authorization -> promotion to `main` (commit
  `72ff48a`) -> live HPM update -> community notice. A real gap found during the promotion build
  (candidate generation is not actually commit-pure with respect to local checkout line endings) is
  tracked separately as item 20, not blocking this closure. The structured Dev-only trace schema
  replacing `AM-TRACE` (the other half of this item) remains unstarted - if still wanted, re-open as
  its own item rather than reviving this one.
- **Open Automation Map in a normal browser tab (was item 2):** dropped as infeasible within the
  Hubitat-generated app UI, which controls the map link's small pop-out window. Do not pursue a link
  rewrite unless Hubitat later exposes a supported way for the app to choose normal-tab behaviour.
- **Show disabled devices distinctly on the map (was item 18):** completed and verified on Dev,
  pending production release (2026-08-31) - disabled devices and paused/disabled rules get a
  canonical label suffix, structured export fields (`devices[].disabled`, `apps[].status`
  distinguishing `disabled`/`paused`), and coloured Focus dropdown entries. Also fixed the
  duplicate-suffix bug noted under item 9. The canvas-level red-suffix piece was deliberately left
  out - see item 19.
- **Scan-schedule setting shows its real default (was item 8):** local review and automated gates
  passed, deployed to Automation Map (Dev) (Apps Code 1210 / instance 3083), and confirmed live in
  Gordon's own testing (2026-09-02) - the Hours/Minutes field now shows its actual default
  (00:30 production, 01:00 Dev) pre-filled via the input's own `defaultValue`, rather than appearing
  blank with an explanatory `description:` that never rendered on `bool`/`time` inputs. A
  `paragraph` states the display-vs-saved nuance. A separate effective-default helper
  (`autoScanEffectivelyEnabled()`) treats a genuinely unsaved `null` the same as the toggle's own
  displayed-on default, since Hubitat does not necessarily populate `settings` with a displayed
  default before the first save - without it, a truly fresh install could read the toggle as off and
  hide the time input entirely. One helper function is the single source for the default time across
  the input, the paragraph, and the scheduler's own blank-time fallback, so the three can't drift
  apart.
- **v2.1.8 production cleanup - telemetry removed, on-demand diagnostic logging added:** local
  review and automated gates passed, deployed to Automation Map (Dev) (Apps Code 1210 / instance
  3083). Diagnostic-toggle placement and off/on/off logging behaviour independently verified live
  by Gordon (2026-09-02) - `AM-TRACE` present only while enabled, gated routine lines correct, the
  two anomaly lines at `warn`. The telemetry-child migration test (clean deletion, plus a
  deliberately-referenced device failing safely) is explicitly **waived by Gordon**, not passed -
  low affected population, easy manual fallback. The Automation Map
  Telemetry Driver and everything that fed it (`ensureTelemetryDevice()`/`reportTelemetry()`/
  `fetchHubHardwareId()`, the manifest driver entry, the README disclosure) are removed outright
  rather than made optional - community reaction to an always-present reporting driver was that it
  read as intrusive regardless of what it actually collected. An upgrading instance removes its own
  leftover telemetry child device automatically (best-effort, exact-DNI `deleteChildDevice()`; if
  Hubitat refuses because it's still referenced elsewhere, the settings page shows a fixed warning -
  never the raw exception text, which is internal diagnostic detail and stays in the log only - and
  retries the next time settings are saved, not on a schedule of its own). In its place, a
  settings-page toggle enables on-demand diagnostic logging for troubleshooting - off by default,
  with a durable expiry timestamp (not just a scheduled job, which a missed hub-down window could
  leave stuck) enforcing the one-hour auto-disable even if the scheduled handler itself is missed;
  the settings page reconciles a stale "on" display back to off on its own next render. Only
  routine/lifecycle log lines are gated behind it (installs, scheduling confirmations, endpoint-entry
  logs, successful saves, expected superseded-generation discards, registry counts, scan
  start/completion detail); the temporary `AM-TRACE` path is Dev-only regardless of the toggle,
  per the existing agreement not to make it part of the reusable production logging design. Failures
  and degraded outcomes that can leave the map incomplete or stale stay unconditionally logged
  regardless of the toggle. Both the removed remote-telemetry approach and the new local-logging
  approach are documented for reuse at `https://github.com/GordonThelander/hubitat_dev_utililities`
  under "Application Telemetry Methods", sanitized and parameterized rather than copied with real
  identifiers.
- **Add anonymous Variable coverage counts to telemetry (was item 17):** cancelled, superseded by
  the decision to remove remote telemetry entirely rather than extend it (2026-09-02).

  This is a narrower, immediately-authorised slice of item 16 below, not a substitute for it -
  item 16's structured Dev-only race trace and its comment-stripping production build remain
  separate, still gated on Gordon starting that phase explicitly.
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
