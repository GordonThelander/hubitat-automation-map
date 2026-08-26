# Supporting Docs

Design and research material behind items in `BACKLOG.md`. **Not shipped with the HPM
package** and not read by the app at runtime. `packageManifest.json` lists only `apps[]`,
so nothing here reaches an installed hub.

Imported 2026-08-12 from files that previously existed only in a local Downloads folder,
where the backlog referenced them and they would eventually have been lost.

## Contents

| File | Backlog item |
| --- | --- |
| `rule_machine_5_1_storage_format.md` | Rule Machine 5.1 documentation (**written**, ready to publish) |
| `registry-pack-v0.3/` | Integration registry |
| `rule_machine_execution_and_cross_rule_causality.md` | Rule Machine 5.1 documentation and rule-to-rule links (superseded source material, see below) |
| `ai_export_spec.md` | AI-friendly Export JSON (implemented contract, export schema 3) |
| `hpm_scrape_spec.md` | Origin task spec for the package identity index now built and maintained in `GordonThelander/HPM_Manifest_Crawl` |
| `async_scan_v205_technical_report.md` | v2.0.5 bounded-async scan architecture, failure model, harness evidence, dev-hub validation, and remaining release gates |
| `hubitat_local_mcp_data_access_assessment.md` | Assessment of reusable Hubitat data-access methods evidenced by the Community Hubitat Local MCP server |
| `hub_variable_first_class_spec.md` | First-class Hub Variable entity and export specification (draft, consensus reached, implementation not yet authorized) |
| `hubitat_driver_programmatic_access.md` | Architecture for programmatic access to Hubitat devices and drivers (inventory, source, capabilities, commands, attributes, events, state) |
| `ai_assessment_export_extension.md` | AI assessment export feature contract (proposed, not implemented) - augments the AI-friendly export with evidence for automated review. Delivered 2026-08-23 (see its own Source line) |
| `ai_bad_rule_assessment_framework.md` | Detailed reasoning catalogue behind the AI assessment export feature contract (superseded as the design itself by `ai_assessment_export_extension.md`, retained as background) |
| `hub_variable_v2014_implementation_spec.md` | Bounded v2.0.14 implementation plan for `hub_variable_first_class_spec.md` (draft, sent for review, not authorized for implementation) |
| `community_context_card_spec.md` | Proposed contextual Community Utilities information card for selected Automation Map apps, including the slim online data contract, local matching, privacy boundary and release gates |

## rule_machine_5_1_storage_format.md

The one piece here that is finished work rather than an input. Documents how Rule Machine
stores a rule, derived from the Automation Map decoding and verified against a live hub.

Written because a check of the official documentation showed the *execution* draft was
largely restating what Hubitat already publishes: Required Expression removing trigger
subscriptions, Run Rule Actions not being a trigger, delay versus delayed action, retrigger
not cancelling delays, and simultaneous execution are all covered on the
[Rule 5.1 page](https://docs2.hubitat.com/en/apps/rule-machine/rule-5-1). Storage format is
not documented anywhere.

## registry-pack-v0.3

Generated 2026-08-11, originally distributed as `hubitat_automation_map_registry_pack_v0.3.zip`.

- The 101-entry `matchRules`/`dependencies[]`/`runtimeCriticality` registry this pack
  originally shipped as `hubitat_automation_map_app_integration_registry_v0.3.json` was
  deleted here in `c117b71`: the app now fetches it at scan time from the crawl repository
  that builds and maintains it, so this folder no longer keeps a second copy to go stale.
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
what was rejected and why, and which parts are worth building. The rule-to-rule
implementation proposal folded into `rule_machine_execution_and_cross_rule_causality.md` was
roughly five times larger than what should be built, and its Phase 1 already shipped in 1.3.3.

## Rule Machine 5.1 execution documentation

A community documentation project rather than an app feature. Its tests T01 to T13 carry
result placeholders that have not been filled in. T01, T03 and T08 can be answered by
reading `scheduledJobs` and `eventSubscriptions` from `/installedapp/statusJson/<id>`; the
remaining ten need manual observation on a hub.
