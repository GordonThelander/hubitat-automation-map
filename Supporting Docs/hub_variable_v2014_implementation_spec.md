# Hub Variable Authoritative Inventory - v2.0.14 Implementation Spec

**Status:** Draft, corrections from Codex review (097) incorporated. No code written. Not authorized
for implementation.
**Scope:** One bounded slice of `hub_variable_first_class_spec.md`, sized for a single dev-hub
test release (v2.0.14), not the full first-class-entity design in one shot.
**Target:** `dev` branch only. Deployed to Gordon's own hub for his own testing. Not for `main`,
not for a public release, until he separately approves it after testing.
**Depends on:** `hub_variable_first_class_spec.md` (consensus recorded in `090_codex_to_claude.md`)
for every field name, node/edge shape, and policy decision not restated here. This document does
not re-litigate that design - it scopes which parts of it ship in v2.0.14 and names the exact code
changes.
**Evidence basis:** `Bucket/Queue/091` through `097` (live-tested `getAllGlobalVars()`/
`getGlobalVar()` on Gordon's hub, 2026-08-26; Codex's code-level review of `apps/automation_map.groovy`
in 097; `extractHubVariableWrites()` finding independently confirmed against source the same day).

## Revision history

- **2026-08-26 (rev 2):** Codex reviewed the original draft in `097_codex_to_claude.md`, approved
  the bounded-v2.0.14 approach, and required seven corrections before the draft is
  implementation-ready. All seven are incorporated below. This revision changes the scope split
  from the original draft: Connector edges and `insights.hubVariables` move from "deferred" to
  "in scope," and the `writeSource` plan changes from an export-wiring task to an
  extraction-logic task.
- **2026-08-26 (rev 1):** Initial draft (Claude), sent to Codex for review as `096_claude_to_codex.md`.

## 1. What changes in v2.0.14

Today, `hubVariables` in the export is purely reference-derived: `ALL_NODES.filter(group ===
'hubVariable')`, built only from names the Rule Machine decoder happens to find in triggers,
conditions and actions. There is no authoritative inventory, no Connector handling, and no
distinction between "this variable exists on the hub" and "this variable was mentioned somewhere
we could decode."

v2.0.14 adds one new discovery call - `getAllGlobalVars()`, confirmed live and general-purpose per
094 - and reconciles it with the existing decoded references, per section 6.3 of the parent spec.
It does not change how reads are decoded from Rule Machine; that logic (`extractHubVariableReads`)
is unchanged. The write-side extraction (`extractHubVariableWrites`) does change - see item 6 below.

## 2. In scope for v2.0.14

1. **Authoritative inventory call** (parent spec 6.1). One `getAllGlobalVars()` call per scan,
   wrapped so a failure degrades to `hubVariableInventory.status: "failed"` rather than aborting
   the scan (parent spec section 12).
2. **Hub Variable node model** (parent spec 7.1), populated from the confirmed live shape:
   `id`, `group: "hubVariable"`, `name`, `variableType`, `identitySource: "hub-inventory"`.
   - **Type normalization, defined explicitly (Codex point 6):** map known platform type spellings
     case-insensitively to the canonical schema values `Number`, `Decimal`, `String`, `Boolean`,
     `DateTime`. Use `null` for an unrecognized type rather than inventing a value. Only `"string"`
     has been observed live (all three test variables); the other four spellings must be covered by
     fixture, not assumed.
   - **Identity rule, stated explicitly (Codex point 6):** no separate stable ID was found live
     (parent spec 6.1 fallback applies) - state the exact deterministic name-derived ID rule in the
     implementation itself, and retain the rename-appears-as-remove-plus-add limitation. Match names
     exactly (case-sensitive) until platform case behaviour is proven; this release does not resolve
     parent spec open question 5, it picks the conservative default.
3. **Reconciliation** (parent spec 6.3), restated precisely per Codex point 5:
   - When authoritative inventory **succeeds**, an unmatched proven structured reference becomes an
     unresolved-reference finding and is **not** promoted to a defined variable node.
   - When authoritative inventory **fails**, existing proven reference-derived nodes may still be
     built, tagged `identitySource: "reference-derived"`, and scan metadata must not claim
     completeness.
4. **Scan-state metadata** (parent spec 6.1): `hubVariableInventory{status,error,count,source}` and
   `hubVariableRelationships{status,supportedEngines,limitations}`.
   - **Transactional publication (Codex point 7):** the `getAllGlobalVars()` call is synchronous,
     but its result must be published through the existing scan generation/publication boundary
     (`SCAN_LOCKS`/`finishGeneration()`), not outside it. A failed or stale inventory must never mix
     into an otherwise-complete new graph, and no new mutable whole-`state` path may be introduced
     for any async callback this feature adds. This targets the exact failure class behind the
     v2.0.8 "Building map" stall and the v2.0.9-2.0.13 metadata-loss bugs earlier in this project's
     history - do not reintroduce it here.
5. **Export schema 4** (parent spec 11.1-11.4): root additions, the Hub Variable record shape, and
   the `usageRole`/`writeSource` edge fields.
   - **No dual-export mode (Codex point 4):** this is a clean advance to schema 4, not a
     version-selector or duplicate schema-3 export path, unless Gordon separately asks for one.
     "Schema 3 remains readable" (parent spec 11.1) means existing schema-3 **files** stay
     interpretable by consumers, not that v2.0.14 must keep **generating** schema-3 output.
     Update `Supporting Docs/ai_export_spec.md` with the schema-4 contract in the same change -
     that doc currently documents schema 3 only and would otherwise go stale the moment this ships.
6. **`writeSource` - corrected (Codex point 1, verified against source 2026-08-26).** The original
   draft assumed the scanner already captures an authoritative device-attribute source and only
   discards it on export. That was wrong. In `extractHubVariableWrites()`
   (`apps/automation_map.groovy` ~line 2684), `settingDevices[n]` is built from
   `s.deviceList.values()` after `stripTags()` - confirmed directly against source: this is the
   **display label**, not the device ID (the ID is the map's key, `s.deviceList.keySet()`, which is
   currently discarded). `write.sourceDevice` (line 2720) is therefore a label today, and schema 4
   forbids joining `writeSource.deviceId` by display name (parent spec 7.2). The extraction code
   must change to preserve the device-list **key** as the ID at extraction time (alongside or
   instead of the label), and `writeSource` must only be emitted when that ID resolves in
   `devices[]`. This is real extraction-logic work, not export wiring - it was under-scoped in the
   original draft. Keep a duplicated-device-label fixture (two devices sharing a display label,
   exactly the case an ID-based join exists to disambiguate) in the test gate.
7. **Connector / `synchronizedWith` edges - moved in scope (Codex point 2).** Resolves the original
   draft's internal contradiction between calling this "out of scope" and then describing field
   plumbing to implement. Connector reconciliation ships in v2.0.14: implement `connectorDeviceId`,
   `connectorType`, and the `synchronizedWith` edge, with fixtures for both the Connector-present
   and Connector-absent cases. **Live verification against a real Connector created on Gordon's dev
   hub is a release gate**, not optional - the feature may describe itself as unverified until that
   live test passes, but it does not ship deferred to a later release.
8. **`insights.hubVariables` findings - moved in scope (Codex point 3).** Ship the complete
   schema-4 findings object now rather than publishing a partial schema 4 and changing it again in
   v2.0.15 - these are deterministic projections of the same inventory/edge data already being
   built. Use neutral wording per parent spec 8.3/11.6. An unresolved-reference finding may be
   created **only** from a proven structured reference, never from an unmatched free-text `%Name%`
   candidate - this preserves the parent spec's conservative evidence rule (6.2) inside the new
   findings surface.
9. **Minimal UI** (parent spec 8.1, bounded): a distinct Hub Variable node shape/label on the map,
   inclusion in search/focus, and the Rule-to-Hub-Variables pivot already described in the parent
   spec. This is the smallest slice of section 8 needed for Gordon to actually see the result on
   his dev hub - not the paused broader UI modernization track.

## 3. Explicitly out of scope for v2.0.14

Narrowed from the original draft: Connector edges and `insights.hubVariables` are now in scope
(section 2, items 7-8) per Codex's review. What remains out of scope:

1. **Baseline comparison changes** (parent spec section 9). No comparator changes in this release;
   the comparator sees schema 4 hub variable records as opaque additions until this is scoped.
2. **Any value export** (parent spec section 10). `currentValue` stays required-null. No opt-in
   value export in v2.0.14 or planned here at all.
3. **Broad UI modernization** beyond the bounded slice in section 2 item 9.

## 4. Named code changes (for Codex's review, not yet written)

- `apps/automation_map.groovy`: new discovery step calling `getAllGlobalVars()`, most likely
  alongside the existing device/app discovery phase functions (`finalizeDevicePhase()`/
  `finalizeAppPhase()` neighborhood) so it participates in the same `SCAN_LOCKS`/`finishGeneration()`
  protocol rather than running as an uncoordinated side call - this is now a hard requirement
  (section 2 item 4), not a suggestion.
- Reconciliation logic: extend whatever currently builds `hubVariable` group nodes from decoded
  references to instead start from the authoritative inventory list and merge decoded references
  onto it, per section 6.3's rules - not the reverse.
- `extractHubVariableWrites()` (~line 2684): change `settingDevices[n]` construction to retain the
  device-list key (device ID), not just the `stripTags()`-ed label, so `write.sourceDevice` can
  become an authoritative ID rather than a display string. See section 2 item 6.
- `buildExportPayload()`: add the schema-4 root fields, switch the Hub Variable record shape to
  11.3, add `usageRole`/`writeSource`/`synchronizedWith` per 11.4 (using the corrected, ID-based
  `writeSource`), add `insights.hubVariables` per 11.5, add Connector fields/edges per 7.2-7.3.
- Export schema version constant: 3 -> 4, as one coherent contract (no dual-mode selector). Update
  `Supporting Docs/ai_export_spec.md` in the same change.

## 5. Test plan for this release

Per parent spec section 13 (acceptance criteria) and section 15 step 7 ("validate locally, then on
a development hub under separate authorization"):

- Fixtures: complete inventory, empty inventory, failed inventory (`getAllGlobalVars()` throws or
  returns null), unused variable (inventory entry with no decoded reference), reader-only,
  writer-only, unresolved reference (decoded name not in inventory, from a proven structured
  reference only), Connector present, Connector missing, duplicated device label disambiguated only
  by ID (the `writeSource` correction's reason for existing), reserved-token collision, and the type
  spellings not yet observed live (Number/Decimal/Boolean/DateTime).
- **Declared-type mapping - confirmed live 2026-08-26.** Gordon created `TestNumber`/`TestDecimal`/
  `TestBoolean`/`TestDateTime` via MCP and exported twice. The first export caught a real bug:
  `getAllGlobalVars()` returns Groovy runtime type names, not the UI's declared labels -
  `"integer"`/`"bigdecimal"`, not `"number"`/`"decimal"` - so Number/Decimal came back
  `variableType: null` (the safe fallback, not a crash) while Boolean/DateTime matched directly.
  Fixed and reconfirmed in the second export: all five canonical types now normalize correctly.
- **Still a live-verification release gate, not fixture-able:** the Connector-present case. Three
  `hub_create_connector` attempts (two variable types, two connectorType values) all failed with
  "wizard completed but still has no deviceId" - an issue with that MCP tool's automation on this
  hub, not yet resolved. No orphaned devices were left behind. Waiting on a working creation path
  or a manually-created Connector before this can be confirmed.
- Never log or export a variable's `value` outside the explicit-opt-in path that doesn't exist yet
  - same discipline as every test this effort has run so far.

## 6. Release framing

- Version: 2.0.14.
- Branch: `dev` only. No push to `main` for this release - `main` currently carries the last
  approved production state and this is explicitly a Gordon-testing build.
- CHANGELOG entry drafted only after implementation and Gordon's dev-hub verification, not now.

## 7. Gate

This is a specification for review, matching the process this whole effort has used since 088.
No implementation begins until:

1. Codex reviews this document and either agrees, proposes amendments, or flags disagreement.
   (Done - see `097_codex_to_claude.md`; corrections incorporated above.)
2. Gordon separately authorizes implementation, as he did for the discovery test itself.

Once authorized: claim the narrow implementation topic via `PROTOCOL.md` before editing, preserve
every unrelated in-flight change in the working tree, and stop before hub deployment, commit, or
push unless those specific actions are separately authorized.

— Claude
