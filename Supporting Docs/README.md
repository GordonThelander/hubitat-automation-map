# Supporting Docs

Design and research material behind items in `BACKLOG.md`. **Not shipped with the HPM
package** and not read by the app at runtime. `packageManifest.json` lists only `apps[]`,
so nothing here reaches an installed hub.

Imported 2026-08-12 from files that previously existed only in a local Downloads folder,
where the backlog referenced them and they would eventually have been lost.

## Contents

| File | Backlog item |
| --- | --- |
| `registry-pack-v0.3/` | Integration registry |
| `Rule_Machine_5_1_Execution_Explained_Draft.md` | Rule Machine 5.1 execution documentation |
| `hubitat_automation_map_rule_to_rule_implementation.md` | Rule-to-rule: device-mediated links |

## registry-pack-v0.3

Generated 2026-08-11, originally distributed as `hubitat_automation_map_registry_pack_v0.3.zip`.

- `hubitat_automation_map_app_integration_registry_v0.3.json` is the useful part: 101
  entries, declarative `matchRules`, `dependencies[]` and `runtimeCriticality`.
- `hubitat_automation_map_registry_method_and_rationale.md` and `explanation.md` are the
  design write-ups.
- `hubitat_automation_map_device_driver_registry_v0.1.json` holds **3 device entries**. It
  is a schema stub, not data.
- `harvest_hubitat_compatible_devices.py` refreshes the device catalogue from
  docs2.hubitat.com. It needs Playwright and runs on a desktop, never on the hub.

Deliberately not imported from the original archive:

- `hubitat_automation_map_dependency_registry_v0.1.*` and `v0.2.json`, superseded by v0.3.
- Duplicate copies the archive shipped with `(1)` and `(2)` suffixes.
- `SHA256SUMS.txt`, which covers files that are not all present here and would be
  misleading. All 13 checksums verified against the original archive before importing.

## Status

These are inputs, not decisions. Read `BACKLOG.md` first: it records what was assessed,
what was rejected and why, and which parts are worth building. The
`rule_to_rule_implementation.md` document in particular is roughly five times larger than
what should be built, and its Phase 1 already shipped in 1.3.3.

## Rule Machine 5.1 execution documentation

A community documentation project rather than an app feature. Its tests T01 to T13 carry
result placeholders that have not been filled in. T01, T03 and T08 can be answered by
reading `scheduledJobs` and `eventSubscriptions` from `/installedapp/statusJson/<id>`; the
remaining ten need manual observation on a hub.
