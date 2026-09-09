# webCoRE piston devices and local variables, Dev implementation specification

**Status:** Implemented, Automation Map Dev v2.2.8. Both additions below are live and verified on the
Dev hub instance. Production, `main`, and HPM publication remain a separate, not-yet-authorized step.

**Target:** Shipped as Automation Map Dev v2.2.8, graph schema 14, export schema 12.

**Scope:** Two additions derived from the same saved webCoRE piston document:

1. owner-scoped webCoRE piston local-variable declarations and proven read/write relationships;
2. direct physical-device reads and direct device actions attributed to the individual piston.

This is not a webCoRE flow decoder. It does not reconstruct IF/ELSE structure, timing, execution
order, or runtime values.

## 1. Evidence basis

The implementation must be based on the current Hubitat webCoRE source and the installed piston's
saved configuration, not labels, subscriptions, cache entries, logs, or inferred behaviour.

The existing Automation Map decoder already proves that a piston is stored as Base64-encoded UTF-8
JSON split across contiguous `chunk:N` app settings. The same decoded document contains local-variable
declarations and device operands.

The following device findings were verified against current webCoRE source and independently
reproduced against a live test piston on Hubitat 2.5.1.181:

- `t: "p"` is the physical-device operand. Its `d` list supplies device references and its `a`
  field supplies the attribute read. webCoRE expands the device list and calls
  `getDeviceAttribute(...)` for each device.
- `t: "action"` is a device-action statement. Its `d` list supplies target devices and its `k`
  list supplies tasks. webCoRE expands the targets and passes every task to `executeTask(...)`.
- A physical device is stored as `":" + MD5("core." + deviceId) + ":"`. The hash is not reversed.
  It is resolved by applying the same function to the parent webCoRE app's permitted device IDs and
  matching the result.
- Both hashes in the live read-and-action fixture resolved to the correct devices, and the resolved
  devices supported the stored attribute and command respectively.
- A piston's local-variable declarations are stored in the decoded document's top-level `v` array.
  Each declaration includes at least its name in `n` and webCoRE type in `t`. An unused `dynamic`
  declaration was confirmed live, including while the piston was paused.

Source references are pinned in `webcore_hub_variable_decoding.md`. The relevant runtime paths are
`evaluateOperand()`'s physical-device branch, `executeAction()`, `executeTask()`, `expandDeviceList()`,
`hashD()`, and `hashId2()`.

## 2. Required outcome

After a scan:

- selecting a webCoRE piston shows every statically resolvable physical-device attribute read;
- selecting the piston shows every statically resolvable direct device action;
- read arrows point from the device toward the piston;
- action arrows point from the piston toward the device;
- the webCoRE parent app does not gain device edges from its permission list;
- every declared piston-local variable appears as an owner-scoped Local Variable node, even when
  unused;
- proven local-variable reads and writes use the existing variable `read` and `write` relationships;
- paused pistons retain the same saved relationships;
- map search, focus, pivot tables, Insights, and the AI-friendly export all describe the same data;
- unsupported or unresolved forms are reported as coverage gaps and never guessed.

## 3. Single bounded piston decoder

Replace the narrowly named Hub Variable decoder with one piston-configuration decoder that performs
chunk reconstruction and JSON parsing once per piston. It must retain the existing safeguards:

- accept only `chunk:<non-negative integer>` settings;
- require `chunk:0` and a contiguous sequence;
- reject duplicate, missing, empty, or excessive chunks;
- retain the encoded-size limit;
- Base64-decode, interpret as UTF-8, apply webCoRE emoji decoding, then parse JSON;
- require a map at the document root;
- never retain, log, render, or export the decoded document or any variable value.

The result should separate configuration parsing from relationship classification. A representative
internal contract is:

```groovy
[
    status: 'complete' | 'not-present' | 'error',
    error: null | '<fixed-code>',
    hubVariableReads: [],
    hubVariableWrites: [],
    hubVariableUses: [],
    localVariableDefinitions: [],
    localVariableReads: [],
    localVariableWrites: [],
    deviceReads: [],
    deviceActions: [],
    unsupportedDeviceReferences: [],
]
```

The names may follow current source conventions, but the separation and evidence semantics are
required. Existing Hub Variable behaviour must remain unchanged.

## 4. webCoRE piston-local variables

### 4.1 Definitions and identity

Read declarations only from the decoded root `v` array. A valid declaration must be a map with a
non-empty `n` name. Create one definition per unique name:

```groovy
[
    identity: "a${pistonAppId}:${name}",
    name: name,
    variableType: '<display type>',
    engineVariableType: '<raw webCoRE t value>',
    engine: 'webCoRE',
]
```

Identity remains owner-scoped. Two pistons declaring the same name create two different nodes.
`engineVariableType` preserves the saved webCoRE type, including `dynamic`. `variableType` may use a
friendly normalized label for display, but must be `null` rather than guessed when no mapping is
defined. No declaration value or runtime value is read.

Malformed and duplicate declarations are ignored deterministically and covered by fixtures. They
must not abort the whole scan.

### 4.2 Read and write classification

Reuse the source-backed structural positions already used for Hub Variable direction, but accept a
name as local only when its normalized base name matches a declaration in this same piston:

- evaluated variable operands are reads;
- the first variable operand of a `setVariable` task is a write target;
- `for` and `each` counter targets are writes;
- physical-condition matching and non-matching capture targets are writes;
- a `setVariable()` expression call is a write only when its target is one static literal;
- variable-backed device lists are variable reads, even though the device value itself is not
  evaluated by Automation Map.

Apply the existing one-trailing-index base-name normalization so an indexed reference attaches to
its declared base variable. Do not treat system variables, `@` webCoRE globals, or `@@` Hub Variables
as local. An unprefixed name that does not match a local declaration creates no Local Variable node
or edge.

Every proven local read uses `usageRole: "unknown-read"`. Read versus write is proven, but this
increment does not reconstruct enough flow context to claim trigger, constraint, or action-input.
A variable seen in both positions receives both edges. A declared variable with neither edge remains
visible and is marked `unreferencedLocal` using the existing post-edge calculation.

### 4.3 Existing Local Variable surfaces

Generalize `graph.ruleVariables` so it can contain Rule Machine rules and webCoRE pistons. The graph
node identity and existing Local Variable focus control remain unchanged.

The AI-friendly export must expose webCoRE local definitions without pretending that a webCoRE flow
was decoded. Export schema 12 therefore adds a top-level `localVariables[]` collection containing all
owner-scoped Local Variable definitions across supported engines. Each entry contains:

```json
{
  "identity": "a123:Example",
  "name": "Example",
  "ownerAppId": "a123",
  "ownerAppName": "Example piston",
  "engine": "webCoRE",
  "variableType": null,
  "engineVariableType": "dynamic",
  "unreferenced": true
}
```

Existing `ruleFlows[].localVariables` remains as the Rule Machine compatibility projection. It is not
extended to webCoRE because a piston still has no decoded `steps` flow. Variable edges target the
top-level definition's `identity`. `summary.localVariableCount` and
`insights.unreferencedLocalVariables` are recalculated from the new cross-engine collection.

## 5. webCoRE piston-to-device relationships

### 5.1 Build the permitted-device hash index

Before suppressing the webCoRE parent's generic device roles, retain only the selected device IDs
from its non-empty capability `deviceList` maps. Do not retain the permission relationships as graph
edges.

Build one lookup per webCoRE parent:

```text
":" + lowercaseHex(MD5(UTF8("core." + deviceId))) + ":" -> deviceId
```

Use the piston's recorded `parentAppId` to select the correct lookup. Do not merge candidate sets
across separate webCoRE parent instances. Build the lookup once per parent per scan. Do not add any
new per-device or per-piston HTTP request.

A token resolves only when:

1. it has the exact colon-wrapped 32-hex shape;
2. exactly one permitted device ID produces that hash;
3. that device ID exists in Automation Map's scanned physical-device inventory.

No match, multiple matches, an unreadable parent, or a missing parent produces a fixed coverage gap
and no edge. Never join by device label and never synthesize a physical device from a hash.

### 5.2 Direct physical-device reads

For every `t: "p"` map, inspect `d` only for direct hash tokens. Each uniquely resolved token creates
one `deviceRead` relationship from the piston app to the device, carrying the saved attribute from
`a` as evidence metadata.

`deviceRead` is a new relationship kind. It must not be labelled `trigger`, `constraint`, or
`monitor`, because the operand proves an attribute read but this bounded decoder does not prove why
the piston reads it. In the rendered graph its arrow points from the device toward the piston. The
legend text is:

> Device read - webCoRE piston reads this device; exact trigger/condition role is not decoded

Deduplicate by piston ID, device ID, relationship kind, and attribute. Multiple attributes may be
retained as evidence while the default map draws at most one visually parallel `deviceRead` edge per
piston-device pair.

### 5.3 Direct device actions

For every `t: "action"` map, inspect `d` only for direct hash tokens. Each uniquely resolved token
creates the existing `action` relationship from the piston to the device. Preserve the command names
from `k[*].c` as bounded evidence metadata, but never task parameter values.

Do not infer `stateful: false` from an unrecognized command. For this first increment, webCoRE action
edges use `stateful: null` and are excluded from the contested-device calculation. A later change may
add a separately source-verified stateful-command catalogue. The action edge still participates in
device usage, disabled-device-use, focus, pivot, and unreferenced-device calculations.

If the same piston both reads and acts on one device, retain both `deviceRead` and `action`.

### 5.4 Unsupported device forms

The following do not create a fixed device edge in this increment:

- variable-backed device lists;
- the current-event device or any other runtime-selected device;
- location and webCoRE virtual-device operands;
- malformed or unrecognized node shapes;
- hashes that cannot be uniquely reconciled against the correct parent inventory.

Record only fixed categories and aggregate counts, not raw tokens or variable values. At minimum:

- `variable-backed-device-list`;
- `runtime-selected-device`;
- `non-physical-device`;
- `unresolved-device-hash`;
- `ambiguous-device-hash`;
- `missing-parent-device-index`.

A piston with one or more unsupported forms has device coverage `partial`, not `complete`. A decoded
piston with no device operand has coverage `none`. A chunk decode failure has coverage `error`.

## 6. Graph, UI, Insights, and pivot behaviour

- Bump graph schema 13 to 14 because Local Variable nodes and device edges are added to cached graph
  state.
- Add `deviceRead` to edge styles, labels, legend visibility, focus filtering, pivot relationship
  choices, role ordering, and arrow-direction logic.
- The webCoRE parent remains relationship-suppressed and explains that its device selections are
  permissions, not piston usage.
- The piston panel no longer says all device relationships are excluded. It reports direct decoded
  reads/actions and states when coverage is partial.
- A piston with a successful decode and no relationships may be called inert only when it also has
  no unsupported references. Decode failure or partial coverage must never be presented as proven
  emptiness.
- Resolved `deviceRead` and `action` edges prevent their devices from appearing in
  `unreferencedDevices`.
- `disabledDevicesStillUsed` includes resolved webCoRE actions under its existing action rule.
- `deviceRead` is treated as a read-only relationship by the monitored-only/read-only analysis, but
  its display wording retains the webCoRE role limitation.
- The main settings-page summary states that direct webCoRE device reads/actions and Hub/Local
  Variable use are decoded, while complete webCoRE flow and runtime-selected devices remain excluded.
- All new user-facing text must use the same UTF-8-safe arrow characters or HTML entities already
  used by the corrected v2.2.7 controls. No mojibake sequences are permitted.

## 7. AI-friendly export contract

Bump export schema 11 to 12. Update `ai_export_spec.md` in the same implementation change.

Required additions:

- top-level `localVariables[]` as defined in section 4.3;
- `deviceRead` as a documented edge relationship;
- optional `attribute` evidence on `deviceRead` edges;
- optional `commands[]` evidence on webCoRE action edges, containing command names only;
- `apps[].deviceRelationshipCoverage` values `complete`, `partial`, `none`, `error`, and
  `parent-permissions-omitted` for the parent;
- bounded device decode issue records and summary counts;
- updated limitations and recommended interpretation text;
- updated local-variable, edge, unreferenced-device, and decoded-coverage counts.

The export must not include:

- decoded piston JSON;
- raw Base64 chunks;
- raw unmatched hashes;
- local, Hub, or global variable values;
- action parameter values;
- a claim that webCoRE flow steps were decoded.

`apps[].hasDecodedFlow` remains `false` for webCoRE pistons. Device and variable relationship
decoding is independent of step-by-step flow decoding.

## 8. Failure and privacy rules

- Existing chunk errors remain fixed codes and make the scan `complete-with-gaps`, not silently
  complete.
- Device reconciliation gaps also make the scan `complete-with-gaps` when a saved direct hash cannot
  be resolved.
- Unsupported but valid dynamic forms produce `partial` coverage and a limitation, not a decoder
  exception.
- One malformed piston must not abort other app discovery.
- Values, decoded documents, raw hashes, device names, app labels, and local network details must not
  enter logs or telemetry.
- The decoded document is transient and discarded after classification.
- No state-changing Hubitat endpoint is introduced.

## 9. Named implementation areas

The implementation is expected to touch only the following bounded areas:

1. `apps/automation_map.groovy`
   - version and graph/export schema constants;
   - the existing webCoRE chunk decoder and tree walker;
   - parent permitted-device ID capture before generic role suppression;
   - app-to-parent reconciliation and hash lookup creation;
   - `graph.ruleVariables`, Local Variable node/edge construction, and unreferenced calculation;
   - direct device relationship construction;
   - edge styles, arrows, legend, filters, pivots, focus panels, summary, Insights, and export;
   - replacement of stale text saying all webCoRE device relationships are excluded.
2. `tests/webcore-variable-integration.groovy`
   - expand or split into a piston-decoder suite covering both variable namespaces and devices.
3. `Supporting Docs/ai_export_spec.md`
   - schema 12 contract.
4. `Supporting Docs/webcore_hub_variable_decoding.md`
   - broaden the saved-piston documentation to include local variables and direct devices.
5. Dev package metadata and release notes, only after implementation and live verification.

Do not add a second independent Base64/chunk decoder. The standalone investigation utility remains a
diagnostic example, not a runtime dependency.

## 10. Required fixtures

### Local variables

- no declarations;
- one unused `dynamic` declaration;
- read only;
- write only;
- both read and write;
- reading one local while writing another;
- same local name in two pistons remains owner-scoped;
- indexed local reference resolves to its declared base name;
- `@` global and `@@` Hub Variable do not become local;
- unprefixed undeclared names do not create nodes;
- malformed and duplicate declarations;
- paused piston produces the same result.

### Devices

- one direct `t: "p"` read resolves with its attribute;
- one direct `t: "action"` target resolves with its command;
- the same device is both read and acted on;
- two different devices resolve from one piston;
- unresolved hash;
- deliberately duplicated hash-index entry fails closed as ambiguous;
- missing or unreadable parent;
- parent candidate absent from the scanned device inventory;
- variable-backed device list;
- current-event device;
- location and virtual-device operand;
- malformed `d`, `a`, and `k` shapes;
- multi-chunk piston;
- paused piston produces the same result;
- webCoRE parent permissions create no graph edge.

### Integrated surfaces

- map arrows point inward for `deviceRead` and outward for `action`;
- local variables appear in search and focus;
- device relationships appear in both app and device focus;
- pivot presets and custom rows/columns include the new data without duplicate edges;
- AI export edge and Local Variable counts match the graph arrays;
- unsupported coverage is visible but never represented as a guessed edge;
- no old `webCoRE device relationships are excluded` statement survives where it is no longer true;
- no malformed arrow characters appear in the rendered page or export text.

## 11. Live Dev acceptance test

Under separate deployment authorization:

1. Deploy only to the resolved `Automation Map (Dev)` Apps Code target using `deploy-hub.ps1`.
2. Verify exact saved-source SHA-256 and revision increase.
3. Run one controlled scan after the hub settles.
4. Select a piston that reads one switch attribute and commands a different colour-capable device.
5. Confirm the map shows a `deviceRead` arrow into the piston and an `action` arrow out to the target.
6. Pause the piston, rescan once, and confirm both saved relationships remain while the piston is
   visibly marked paused.
7. Confirm the webCoRE parent has no device edges.
8. Confirm one declared but unused local variable appears as an unreferenced Local Variable.
9. Confirm read-only, write-only, and read/write local fixtures produce the expected edges.
10. Open pivot tables, app/device/local-variable focus, Insights, and the AI-friendly export and
    reconcile their counts and coverage statements.
11. Inspect complete scan logs for fixed decoder errors and confirm no decoded content or hashes were
    logged.

Successful deployment alone is not acceptance. The rendered map and export must both pass.

## 12. Release gates

Implementation may begin only after Gordon approves this specification. Dev deployment, committing,
pushing `dev`, production generation, and promotion remain separate permissions.

No production candidate may be generated until:

- all targeted fixtures pass;
- the live Dev acceptance test passes;
- the supporting documentation matches the implemented schema;
- development-only wording and diagnostics are excluded by the production builder;
- Gordon separately authorizes the next release stage.
