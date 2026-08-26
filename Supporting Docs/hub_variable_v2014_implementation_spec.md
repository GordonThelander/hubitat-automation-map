# Hub Variable Authoritative Inventory - v2.0.14 Implementation Spec

**Status:** Draft for Codex review. No code written. Not authorized for implementation.
**Scope:** One bounded slice of `hub_variable_first_class_spec.md`, sized for a single dev-hub
test release (v2.0.14), not the full first-class-entity design in one shot.
**Target:** `dev` branch only. Deployed to Gordon's own hub for his own testing. Not for `main`,
not for a public release, until he separately approves it after testing.
**Depends on:** `hub_variable_first_class_spec.md` (consensus recorded in `090_codex_to_claude.md`)
for every field name, node/edge shape, and policy decision not restated here. This document does
not re-litigate that design - it scopes which parts of it ship in v2.0.14 and names the exact code
changes.
**Evidence basis:** `Bucket/Queue/091` through `095` (live-tested `getAllGlobalVars()`/
`getGlobalVar()` on Gordon's hub, 2026-08-26) and `hubitat_local_mcp_data_access_assessment.md`
section 3 and section 11 Phase 1.

## 1. What changes in v2.0.14

Today, `hubVariables` in the export is purely reference-derived: `ALL_NODES.filter(group ===
'hubVariable')`, built only from names the Rule Machine decoder happens to find in triggers,
conditions and actions. There is no authoritative inventory, no Connector handling, and no
distinction between "this variable exists on the hub" and "this variable was mentioned somewhere
we could decode."

v2.0.14 adds one new discovery call - `getAllGlobalVars()`, confirmed live and general-purpose per
094 - and reconciles it with the existing decoded references, per section 6.3 of the parent spec.
It does not change how reads/writes are decoded from Rule Machine; that logic (`extractHubVariable
Writes`/`extractHubVariableReads`, ~lines 2684-2790) is unchanged.

## 2. In scope for v2.0.14

1. **Authoritative inventory call** (parent spec 6.1). One `getAllGlobalVars()` call per scan,
   wrapped so a failure degrades to `hubVariableInventory.status: "failed"` rather than aborting
   the scan (parent spec section 12).
2. **Hub Variable node model** (parent spec 7.1), populated from the confirmed live shape:
   `id` (name-derived per parent spec 6.1 fallback - no separate stable ID was found live),
   `group: "hubVariable"`, `name`, `variableType` (from the returned `type` field - observed value
   `"string"` for all three test variables; declared-type-to-`variableType` mapping for
   Number/Decimal/Boolean/DateTime is not yet live-verified and must be a fixture, not an
   assumption), `identitySource: "hub-inventory"`.
3. **Reconciliation** (parent spec 6.3): a decoded reference matching an inventory name attaches
   to it; a decoded reference matching nothing becomes an unresolved-reference finding, not a
   node; an inventory variable with no decoded reference stays visible as unused-or-unknown.
4. **Scan-state metadata** (parent spec 6.1): `hubVariableInventory{status,error,count,source}` and
   `hubVariableRelationships{status,supportedEngines,limitations}`, recorded alongside the existing
   scan-phase state the way device/app phase status already is.
5. **Export schema 4** (parent spec 11.1-11.4): root additions, the Hub Variable record shape, and
   the `usageRole`/`writeSource` edge fields. Schema 3 stays readable and unchanged for existing
   consumers per parent spec 11.1.
6. **`writeSource`** (parent spec 7.2, consensus item 5 in 090): populate `{kind:
   "deviceAttribute", deviceId, attribute}` from the scanner's existing device-attribute-source
   capture on supported variable writes, which is already extracted but currently discarded by
   `buildExportPayload()`. This is the JimB finding from message 089 - low-risk since the source
   data already exists, only the export path is new.
7. **Minimal UI** (parent spec 8.1, bounded): a distinct Hub Variable node shape/label on the map,
   inclusion in search/focus, and the Rule-to-Hub-Variables pivot already described in the parent
   spec. This is the smallest slice of section 8 needed for Gordon to actually see the result on
   his dev hub - not the paused broader UI modernization track.

## 3. Explicitly out of scope for v2.0.14 - defer to a follow-up release

1. **Connector / `synchronizedWith` edges** (parent spec 7.2-7.3). The live test's three sample
   variables all have `hasConnectorDeviceId: false` - nobody has tested this code path against a
   variable with an actual Connector yet. Implement the field plumbing (`connectorDeviceId`,
   `connectorType`, the edge) but treat it as **untested until Gordon creates a test Connector on
   his dev hub and a scan is run against it**. This should be an explicit manual verification step
   in his testing, not something the release notes claim as confirmed.
2. **`insights.hubVariables` diagnostics findings** (parent spec 8.3, 11.5): noDecodedUsage,
   readersWithoutDecodedWriter, multipleWriters, etc. These are additive and lower-risk than the
   inventory/reconciliation work above, but they're a separate unit of review. Recommend Codex
   confirm whether these should ship in v2.0.14 alongside the rest, or wait for a v2.0.15 once the
   inventory path itself is confirmed stable on Gordon's hub.
3. **Baseline comparison changes** (parent spec section 9). No comparator changes in this release;
   the comparator sees schema 4 hub variable records as opaque additions until this is scoped.
4. **Any value export** (parent spec section 10). `currentValue` stays required-null. No opt-in
   value export in v2.0.14 or planned here at all.

## 4. Named code changes (for Codex's review, not yet written)

- `apps/automation_map.groovy`: new discovery step calling `getAllGlobalVars()`, most likely
  alongside the existing device/app discovery phase functions (`finalizeDevicePhase()`/
  `finalizeAppPhase()` neighborhood) so it participates in the same `SCAN_LOCKS`/`finishGeneration()`
  protocol rather than running as an uncoordinated side call.
- Reconciliation logic: extend whatever currently builds `hubVariable` group nodes from decoded
  references to instead start from the authoritative inventory list and merge decoded references
  onto it, per section 6.3's rules - not the reverse.
- `buildExportPayload()`: add the schema-4 root fields, switch the Hub Variable record shape to
  11.3, add `usageRole`/`writeSource`/`synchronizedWith` per 11.4, surface the already-captured
  device-attribute write source instead of discarding it.
- Export schema version constant: 3 -> 4, with schema 3 output still reachable/unchanged per 11.1
  (exact mechanism - versioned export function vs. a schema-version argument - left to Codex/
  implementation, not decided here).

## 5. Test plan for this release

Per parent spec section 13 (acceptance criteria) and section 15 step 7 ("validate locally, then on
a development hub under separate authorization"):

- Fixtures: complete inventory, empty inventory, failed inventory (`getAllGlobalVars()` throws or
  returns null), unused variable (inventory entry with no decoded reference), reader-only,
  writer-only, unresolved reference (decoded name not in inventory), schema-3 compatibility (schema
  3 export unaffected).
- **Not fixture-able yet, needs live verification on Gordon's dev hub**: Connector-present case
  (deviceId/connectorType actually populated and joining to a real device), declared-type mapping
  for Number/Decimal/Boolean/DateTime (only `string` has been observed live), and whether
  `getAllGlobalVars()` throws or degrades gracefully on any firmware/config edge case Gordon's hub
  can exercise that a fixture can't simulate.
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
2. Gordon separately authorizes implementation, as he did for the discovery test itself.

— Claude
