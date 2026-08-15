# Automation Map - Hub Variable and Custom Attribute Lineage Specification

**Status:** Ready for implementation  
**Branch:** `dev`  
**Purpose:** Define the required Automation Map behaviour for Hub Variables and Custom Attributes, based on a confirmed Rule Machine 5.1 test case.

---

## 1. Requirement

Automation Map must represent **Hub Variables as first-class dependency objects** and show how Rule Machine automations read from and write to them.

Custom device attributes must remain visible as the specific data source or trigger behind a device dependency, but should not become first-class graph nodes by default.

The feature must answer questions such as:

- Which rule writes this Hub Variable?
- Which rules read this Hub Variable?
- Which device attribute ultimately supplies the value?
- Is the relationship a trigger, condition, read or write?
- What is the complete lineage across multiple rules?

Example target lineage:

```text
Hub Information.formattedUptime
        |
        | TRIGGER / READ
        v
_Test Variables (Rule-5.1)
        |
        | WRITE
        v
Hub Variable: TestHubUptime
        |
        | READ
        v
Another Rule
```

---

## 2. Confirmed test case

A real Hubitat test case has been created and verified.

| Item | Value |
|---|---|
| Device | `Hub Information` |
| Driver | `Hub Information Driver v3` |
| Device attribute | `formattedUptime` |
| Rule | `_Test Variables` |
| Rule type | Rule Machine 5.1 |
| Trigger | `Hub Information reports formattedUptime *changed*` |
| Hub Variable | `TestHubUptime` |
| Hub Variable type | `String` |
| Action | `Set TestHubUptime to Hub Information formattedUptime` |
| Confirmed variable value | `0d, 4h, 46m, 27s` |

The Hub Variable is successfully populated from the custom device attribute, proving the complete Hubitat data path.

Actual logical dependency:

```text
Hub Information
  .formattedUptime
        |
        | changed
        v
_Test Variables
        |
        | set
        v
TestHubUptime
```

This test rule is the canonical acceptance fixture for the first implementation.

---

## 3. Current Automation Map behaviour

The current Dev implementation already detects a significant part of this relationship.

### 3.1 What currently works

The execution reconstruction correctly identifies the trigger as:

```text
Hub Information reports formattedUptime *changed*
```

The whole map also correctly creates the device-to-rule relationship:

```text
Hub Information -> _Test Variables
```

Therefore:

- the device dependency is already detected;
- the specific custom attribute `formattedUptime` is already decoded in the Rule Machine execution view;
- the trigger semantics `changed` are already decoded.

### 3.2 What is currently missing

The action is currently rendered approximately as:

```text
Set Variable
Hub Information
```

This is incomplete and misleading because it identifies the source device but loses the **target Hub Variable**.

`TestHubUptime` does not currently appear as a dependency object anywhere on the map.

Current effective model:

```text
Hub Information.formattedUptime
        |
        v
_Test Variables
```

Required model:

```text
Hub Information.formattedUptime
        |
        | TRIGGER / READ
        v
_Test Variables
        |
        | WRITE
        v
TestHubUptime
```

---

## 4. Scope

### 4.1 In scope

The initial implementation must support:

1. discovery of Hub Variables referenced by Rule Machine;
2. creation of Hub Variable nodes in the dependency graph;
3. directed READ and WRITE relationships;
4. trigger and condition semantics where a Hub Variable participates in those constructs;
5. improved Rule Machine execution decoding for Set Variable operations;
6. preservation of custom attribute names on device relationships;
7. cross-rule lineage through shared Hub Variables;
8. scan persistence/export of the new dependency type using the existing Automation Map graph architecture;
9. safe behaviour where a variable reference cannot be resolved statically.

### 4.2 Out of scope for the first implementation

Do not expand this change into a general Rule Machine variable engine.

The following are explicitly not required for the first implementation:

- first-class graph nodes for Rule Machine local variables;
- first-class graph nodes for every device attribute;
- evaluation of variable expressions;
- evaluation of Rule Machine runtime logic;
- recreation of Hubitat's variable engine;
- display of Hub Variable values on the whole map;
- automatic support for arbitrary dynamic variable names that cannot be resolved statically;
- redesign of the entire graph visual language;
- special-case support for Hub Information Driver itself.

Hub Information Driver is only the test source. The feature must work for any device exposing attributes that Rule Machine can consume.

---

## 5. Domain model

Automation Map must treat the relevant objects as distinct concepts.

| Concept | Graph treatment | Notes |
|---|---|---|
| Physical device | First-class node | Existing behaviour |
| Virtual device | First-class node | Existing behaviour |
| Custom device attribute | Edge metadata / execution detail | Do not create nodes by default |
| Rule Machine rule | First-class node | Existing behaviour |
| Hub Variable | **New first-class node type** | Core requirement |
| Rule local variable | Internal rule detail | Not a graph node in v1 |
| Variable Connector | Compatibility/alias mechanism | Avoid duplicate logical lineage where possible |

### 5.1 Hub Variable identity

Hub Variable names are unique at hub scope and may be used as the logical key if Hubitat does not expose a better stable identifier.

Recommended conceptual identifier:

```text
hubvar:<variable-name>
```

Example:

```text
hubvar:TestHubUptime
```

If the platform exposes a stable Hub Variable ID, prefer that ID internally while retaining the variable name as the display label.

Do not invent IDs from undocumented internal state without verifying them first.

### 5.2 Hub Variable metadata

Capture at least:

```text
name
type
relationship(s)
```

Optionally capture a current value internally if it is already readily available, but **do not display variable values by default**.

Hub Variables may contain URLs, personal information, credentials, tokens or other sensitive data. Dependency mapping needs names and relationships, not values.

---

## 6. Relationship semantics

The graph must distinguish the direction and semantic type of Hub Variable dependencies.

### 6.1 WRITE

When a rule sets or modifies a Hub Variable:

```text
Rule -> Hub Variable
```

Relationship type:

```text
WRITE
```

Examples include conceptually:

```text
Set HubVariable = ...
Set HubVariable from device attribute
Set HubVariable from another variable
Increment / decrement HubVariable
```

Where Rule Machine exposes distinct mutation operations, they may be retained as detail metadata, but the graph-level semantic is WRITE.

### 6.2 READ

When a rule consumes a Hub Variable without changing it:

```text
Hub Variable -> Rule
```

Relationship type:

```text
READ
```

Examples include:

- using the variable in an action expression;
- inserting the variable into text;
- comparing the variable in a condition;
- using the variable as an input when setting another value.

### 6.3 TRIGGER

When a Hub Variable change causes a rule to execute:

```text
Hub Variable -> Rule
```

Relationship semantics:

```text
READ + TRIGGER
```

or an equivalent structured representation that preserves both dependency direction and trigger role.

Do not treat a trigger as a WRITE simply because the rule subsequently modifies the same variable.

### 6.4 CONDITION

When a Hub Variable participates in a condition or required expression:

```text
Hub Variable -> Rule
```

Relationship semantics:

```text
READ + CONDITION
```

### 6.5 READ and WRITE of the same variable

A rule may read and write the same Hub Variable.

Do not collapse this into an ambiguous undirected dependency.

Preserve both semantics, either as two directed edges or as a structured edge containing both roles if the graph renderer can express direction unambiguously.

---

## 7. Custom Attribute handling

Custom Attribute support is **not starting from zero**. The current Rule Machine decoder already identifies `formattedUptime` in the canonical test trigger.

The enhancement must preserve and extend this rather than introduce a parallel attribute system.

### 7.1 Default graph representation

Do not create a graph node for every custom attribute.

A driver such as Hub Information Driver can expose dozens of attributes. Creating a node for every attribute would produce severe graph noise.

Preferred representation:

```text
Hub Information --[formattedUptime / TRIGGER]--> _Test Variables
```

The device remains the node. The specific attribute becomes relationship metadata.

### 7.2 Execution detail

The execution view should continue to expose the actual attribute name:

```text
Trigger
Hub Information.formattedUptime changed
```

For the Set Variable action it should expose both source and destination:

```text
Set Hub Variable TestHubUptime
from Hub Information.formattedUptime
```

### 7.3 Attribute values

Do not show current attribute values on the whole dependency map merely because they are available.

Values are volatile and can be sensitive. This feature is about dependency lineage, not telemetry visualisation.

---

## 8. Rule Machine decoding requirements

### 8.1 Structured state first

Automation Map should continue using Rule Machine's internal state/configuration as the primary reconstruction source where possible.

Do not implement Hub Variable support by scraping visible English labels if the underlying app state contains structured variable references.

Preferred extraction order:

1. structured Rule Machine state/configuration;
2. existing decoded action structures;
3. stable internal identifiers already proven by Automation Map;
4. visible action text only as a bounded fallback.

### 8.2 Do not guess undocumented structures

Before implementation, capture the internal Rule Machine state for the canonical `_Test Variables` rule and identify exactly where the following are stored:

```text
TestHubUptime
formattedUptime
Hub Information device reference
Set Variable action type
trigger attribute reference
```

Document the observed structure in code comments or supporting documentation before generalising the parser.

### 8.3 Target variable is mandatory

A decoded Set Variable action is incomplete unless the target variable is known.

The current rendering:

```text
Set Variable
Hub Information
```

must no longer be considered a successful decode when the actual action is:

```text
Set TestHubUptime to Hub Information formattedUptime
```

If Automation Map can identify the Set Variable operation but cannot identify the target variable, represent this explicitly as unresolved rather than silently omitting it.

Example:

```text
Set Hub Variable [unresolved]
from Hub Information.formattedUptime
```

and emit a debug diagnostic when debug logging is enabled.

---

## 9. Dynamic and unresolved references

Hubitat permits sufficiently dynamic automation constructs that not every variable dependency will necessarily be statically resolvable.

Automation Map must not fabricate lineage.

When a reference cannot be resolved:

- retain the rule;
- retain any known source/device relationship;
- mark the variable relationship as unresolved if enough evidence exists to know one is present;
- do not create a fake variable node with a guessed name;
- log the relevant safe diagnostic only when debug logging is enabled.

Conceptual representation:

```text
Rule -> [Dynamic Hub Variable]
```

should only be used if the underlying Rule Machine state proves that the target is dynamic.

Otherwise simply report the decode gap in diagnostics.

---

## 10. Graph behaviour

### 10.1 New node type

Add a distinct graph entity type for Hub Variables.

Recommended conceptual type:

```text
hubVariable
```

A Hub Variable should be visually distinguishable from:

- devices;
- Rule Machine rules;
- other apps;
- external systems.

Do not reuse the device node type. A Hub Variable is shared state, not a virtual device.

### 10.2 Direction

Direction is essential.

Examples:

```text
Device.attribute -> Rule        source / trigger / read
Rule -> Hub Variable            write
Hub Variable -> Rule            read / condition / trigger
Rule -> Device                  command / write
```

This enables actual data lineage instead of a generic "uses" relationship.

### 10.3 Cross-rule lineage

The primary architectural value is the ability to connect otherwise unrelated rules through shared state.

Example:

```text
Temperature Sensor.temperature
        |
        v
Rule A
        |
        | WRITE
        v
CalculatedTemperature
        |
        | READ
        v
Rule B
        |
        v
Notification Device
```

Automation Map should allow this complete chain to be followed visually.

### 10.4 Filtering

If the map already supports entity filtering, Hub Variables should be independently filterable.

Preferred filter label:

```text
Hub Variables
```

If a new filter would materially complicate the first implementation, Hub Variables may initially follow the automation/dependency visibility state, but they must not be permanently inseparable from devices.

---

## 11. Execution view behaviour

The Rule Machine detail/execution view must be enhanced independently of the whole-map graph.

For the canonical test case, the target rendering should convey:

```text
Trigger
Hub Information.formattedUptime changed

Action
Set Hub Variable TestHubUptime
from Hub Information.formattedUptime
```

The exact visual formatting may follow the existing execution-view conventions, but the following information must be present:

| Field | Required |
|---|---|
| Action is a variable mutation | Yes |
| Target variable name | Yes |
| Target is a Hub Variable | Yes where determinable |
| Source device | Yes |
| Source attribute | Yes |
| Source variable, if applicable | Yes |
| Current variable value | No |

The execution view must not render only the source device for a Set Variable action.

---

## 12. Variable Connectors

Hub Variable Connectors are a compatibility mechanism that exposes a Hub Variable through a device-like abstraction.

They create a risk of duplicate lineage:

```text
Hub Variable
    +
Connector device representing same value
```

For the first implementation:

- do not require full connector reconciliation;
- do not intentionally create a second Hub Variable for a connector;
- if a connector is already discovered as a device, leave current device behaviour intact;
- avoid claiming that connector-device lineage and native Hub Variable lineage are independent data sources if the relationship can be identified reliably.

A later enhancement may explicitly model:

```text
Hub Variable <-> Variable Connector
```

but this must not block native Hub Variable support.

---

## 13. Data model and scan persistence

The scanner must preserve enough structured data that the renderer does not need to reverse-engineer Hub Variables from labels.

Conceptual graph records:

```json
{
  "id": "hubvar:TestHubUptime",
  "type": "hubVariable",
  "name": "TestHubUptime",
  "variableType": "String"
}
```

Conceptual relationship:

```json
{
  "from": "rule:<rule-id>",
  "to": "hubvar:TestHubUptime",
  "relationship": "WRITE"
}
```

Device attribute relationship:

```json
{
  "from": "device:<device-id>",
  "to": "rule:<rule-id>",
  "relationship": "TRIGGER",
  "attribute": "formattedUptime"
}
```

These examples are conceptual only. Fit the implementation into the existing graph schema rather than creating a competing parallel schema.

The important requirement is that variable identity, relationship direction and relationship semantics survive the scan boundary as structured data.

---

## 14. Privacy and security

Hub Variables can contain sensitive data.

The implementation must therefore follow these rules:

- do not expose Hub Variable values on the whole map;
- do not include values in graph node labels;
- do not log variable values by default;
- debug output should prefer variable name, type and relationship only;
- do not persist values merely to support dependency mapping;
- do not treat a variable containing a URL or token as safe diagnostic text.

The same principle applies to custom attribute values.

The lineage engine needs **identity and relationship**, not runtime payloads.

---

## 15. Failure handling

A parser failure must degrade gracefully.

### 15.1 Known variable, unknown role

If a Hub Variable can be identified but READ/WRITE semantics cannot:

- create the variable node only if the dependency is certain;
- classify the edge as `REFERENCE` or existing neutral dependency type;
- emit a debug warning;
- do not guess direction.

### 15.2 Known Set Variable action, unknown target

Render:

```text
Set Hub Variable [unresolved]
```

rather than dropping the target silently.

### 15.3 Unknown custom attribute

Retain the device-to-rule relationship and omit the attribute annotation if necessary.

Do not drop a confirmed device dependency simply because attribute-level decoding fails.

---

## 16. Implementation sequence

### Phase 1 - inspect canonical Rule Machine state

Use `_Test Variables` and capture the internal state/configuration required to identify:

- Hub Variable `TestHubUptime`;
- its role as the Set Variable target;
- source device `Hub Information`;
- source attribute `formattedUptime`.

Do not write a broad parser until this structure is understood.

### Phase 2 - fix execution decoder

Correct the existing Set Variable action decoding so the detail view shows:

```text
Set Hub Variable TestHubUptime
from Hub Information.formattedUptime
```

This provides a small, testable decoder change before graph expansion.

### Phase 3 - add Hub Variable graph entity

Add Hub Variable discovery and node creation.

For the canonical rule:

```text
_Test Variables -> TestHubUptime
```

must exist as a WRITE relationship.

### Phase 4 - add READ relationships

Create a second test rule that consumes `TestHubUptime` without modifying it.

Expected graph:

```text
_Test Variables
      |
      | WRITE
      v
TestHubUptime
      |
      | READ
      v
_Test Variable Consumer
```

### Phase 5 - cover trigger and condition usage

Add focused fixtures for:

- Hub Variable changed trigger;
- Hub Variable comparison condition;
- Required Expression using Hub Variable;
- variable used in action text/expression.

### Phase 6 - regression and UI hardening

Verify that:

- ordinary device/rule edges are unchanged;
- execution reconstruction remains correct for rules without variables;
- graphs do not explode into per-attribute nodes;
- variable values are not leaked;
- large numbers of Hub Variables remain readable.

---

## 17. Acceptance criteria

| ID | Test | Expected result |
|---|---|---|
| HV-01 | Scan canonical `_Test Variables` rule | `TestHubUptime` is discovered |
| HV-02 | Whole map | Hub Variable appears as a distinct node type |
| HV-03 | Canonical rule | `_Test Variables -> TestHubUptime` is a directed WRITE relationship |
| HV-04 | Canonical trigger | `Hub Information -> _Test Variables` remains present |
| HV-05 | Canonical trigger detail | Edge/execution detail retains `formattedUptime` |
| HV-06 | Execution view | Shows target `TestHubUptime` |
| HV-07 | Execution view | Shows source `Hub Information.formattedUptime` |
| HV-08 | Consumer rule reads variable | `TestHubUptime -> Consumer Rule` is a directed READ relationship |
| HV-09 | Variable-triggered rule | Relationship is identified as trigger/read, not write |
| HV-10 | Variable used in condition | Relationship is identified as condition/read |
| HV-11 | Rule reads and writes same variable | Both semantics are preserved without ambiguous direction |
| HV-12 | Rule without Hub Variables | Existing graph output is unchanged |
| HV-13 | Device with many custom attributes | Attributes do not become dozens of default graph nodes |
| HV-14 | Variable value contains sensitive text | Value is not displayed or logged by default |
| HV-15 | Unresolvable variable reference | No fabricated variable name or incorrect edge is created |
| HV-16 | Two rules share one Hub Variable | One shared variable node connects both rules |
| HV-17 | Re-scan | Variable nodes and edges do not duplicate |
| HV-18 | Delete/remove variable reference from rule | Stale dependency disappears on next full scan |

---

## 18. Required regression fixtures

At minimum create or document these Rule Machine fixtures during development:

### Fixture A - device attribute to Hub Variable

```text
Trigger:
Hub Information.formattedUptime changed

Action:
Set TestHubUptime from Hub Information.formattedUptime
```

Expected:

```text
Hub Information --formattedUptime/TRIGGER--> Rule A --WRITE--> TestHubUptime
```

### Fixture B - Hub Variable consumer

```text
Trigger:
any harmless test trigger

Action/Condition:
read TestHubUptime
```

Expected:

```text
TestHubUptime --READ--> Rule B
```

### Fixture C - variable trigger

```text
Trigger:
TestHubUptime changed
```

Expected:

```text
TestHubUptime --TRIGGER--> Rule C
```

### Fixture D - variable condition

```text
Condition:
TestHubUptime equals <test value>
```

Expected:

```text
TestHubUptime --CONDITION/READ--> Rule D
```

### Fixture E - read and write same variable

Expected:

```text
TestHubUptime --READ--> Rule E --WRITE--> TestHubUptime
```

The renderer must not reduce this to an undirected single relationship.

---

## 19. Design constraints

1. **Do not special-case `Hub Information Driver v3`.** It is only the verified source device.
2. **Do not create attribute nodes by default.** Attribute names belong on the relationship/detail layer.
3. **Do not parse only rendered English strings if structured Rule Machine state is available.**
4. **Do not infer WRITE merely from the presence of a variable name.** Determine the operation semantics.
5. **Do not expose current Hub Variable values by default.**
6. **Do not merge Hub Variables with Rule local variables.** They have different scope and lineage implications.
7. **Do not create duplicate variable nodes per referencing rule.** Hub Variables are hub-scoped shared objects.
8. **Do not fabricate dependencies when dynamic references cannot be resolved.**
9. **Preserve existing device/rule behaviour.** This is an additive graph capability.
10. **Keep parsing separate from rendering.** The scanner/decoder should produce structured relationships; the renderer should consume them.

---

## 20. Preferred end-state

Automation Map should move from showing only topology:

```text
Device -> Rule -> Device
```

towards showing actual automation data lineage:

```text
Device.attribute
      |
      v
Rule
      |
      v
Hub Variable
      |
      v
Rule
      |
      v
Device
```

For the confirmed test case the minimum correct result is:

```text
Hub Information
  .formattedUptime
        |
        | TRIGGER
        v
_Test Variables
        |
        | WRITE
        v
TestHubUptime
```

and the execution detail must say, in substance:

```text
Trigger:
Hub Information.formattedUptime changed

Action:
Set Hub Variable TestHubUptime
from Hub Information.formattedUptime
```

---

## 21. Definition of done

This requirement is complete when Automation Map can correctly reconstruct a Hub Variable as shared state between automations, preserve the direction and role of each relationship, retain the existing custom-attribute detail, and do so without exposing variable values or creating misleading graph noise.

---

## 22. Phase 1 findings - raw Rule Machine state for `_Test Variables`, 2026-08-15

Captured from `http://10.0.0.125/installedapp/statusJson/2981` (app instance `2981`,
`_Test Variables`, Rule-5.1, `appTypeId 190`). Local access, read-only, no code
changes yet - this satisfies Section 8.2's requirement to document the observed
structure before generalising the parser.

The rule has one trigger (index `1`) and one action (index `2`); indices are not
contiguous because Rule Machine numbers triggers and actions from shared, reused
counters as items are added and removed in the UI, not from a fresh count per rule.

### Trigger (index 1) - already correctly decoded, included for contrast

| Setting name | Value | Meaning |
|---|---|---|
| `tCapab1` | `"Custom Attribute"` | Capability picker choice |
| `tDev1` | *(value null; device carried in `deviceIdsForDeviceList`/`deviceList`)* | Device id `3574` = "Hub Information" |
| `tCustomAttr1` | `"formattedUptime"` | Which attribute |
| `tstate1` | `"*changed*"` | Match condition - RM's shorthand for "any change" |

Confirmed by `appState.trigCustoms = ["formattedUptime"]`,
`appState.trigDevs = {"3574:Custom Attribute": ["1"]}`, and the live
`eventSubscriptions` entry subscribing to device `3574`'s `formattedUptime`.

### Action (index 2) - the gap the spec identifies

| Setting name | Value | Meaning |
|---|---|---|
| `actSubType.2` | `"getSetVariable"` | Action subtype - matches `appState.actions["2"].method` |
| `xVarV.2` | `"TestHubUptime."` | **The target Hub Variable name.** Not read anywhere in the current parser - this is the missing piece. Note the trailing period; unconfirmed whether that's a fixed artifact of how RM's variable-picker enum stores its value or specific to this fixture. Strip it, but verify against a second variable before assuming it's always present. |
| `valStringOp.2` | `"Device attribute"` | Discriminates the value SOURCE type (device attribute, vs. presumably a fixed value or another variable on other fixtures - not yet observed) |
| `customDev.2` | *(value null; device carried in `deviceIdsForDeviceList`/`deviceList`)* | Source device id `3574` = "Hub Information" |
| `tCustomAttr.2` | `"formattedUptime"` | Source attribute |
| `actType.2` | `"modeActs"` | Unclear - did not vary with anything else observed here, not assumed meaningful for this action |
| `delayAct.2` | `"none"` | No delay on this action |

Naming pattern: trigger settings are `<name><index>` (`tDev1`), action settings are
`<name>.<index>` (`customDev.2`) - the dot before the index is only present on the
action side.

### Confirms the spec's core distinction

`appState.allLocalVars = {}` - empty. `TestHubUptime` does not appear anywhere in
this rule's own local-variable bookkeeping, which is exactly the spec's claim:
it's hub-scoped shared state, not a rule-local variable, and the two must not be
merged (Section 19, constraint 6).

### What Phase 2 needs to change

`actionLabel()`'s `getSetVariable` case (currently absent - it falls through to
the `default: prettyMethod(method)` branch, producing the generic "Set Variable"
seen in the screenshot) needs to read `xVarV.<num>` for the target and,  when
`valStringOp.<num> == 'Device attribute'`, `customDev.<num>` + `tCustomAttr.<num>`
for the source, to produce the spec's required:

    Set Hub Variable TestHubUptime
    from Hub Information.formattedUptime

Not yet implemented - Gordon asked for Phase 1 (this investigation) only, local
development, starting the version line at 1.9.0. `APP_VERSION` bumped locally;
not pushed to hub or git.

The canonical `_Test Variables` fixture must pass first, followed by at least one separate consumer rule proving cross-rule lineage through `TestHubUptime`.

---

## 23. Phase 2 - execution decoder fix, 2026-08-15

Added a `getSetVariable` case to `actionLabel()`, reading `xVarV.<n>` for the
target (trailing period stripped) and, when `valStringOp.<n> == 'Device
attribute'`, `customDev.<n>` + `tCustomAttr.<n>` for the source. Needed
`settingDevices` threaded into `actionLabel()`, which it didn't previously
receive (device-picker settings store `null` in `value`, with the real device
name only in `deviceList`, so `settingValues` alone can't resolve one).

Confirmed live: the flow-decode popup for `_Test Variables` now reads
`Set Hub Variable TestHubUptime from Hub Information.formattedUptime`,
matching the spec's Section 11 requirement exactly.

This only fixes the popup. The whole-map graph is built by a separate
function (`buildGraph`) that had no concept of a Hub Variable at all, so
`TestHubUptime` did not appear as a node anywhere - confirmed by Gordon
re-checking the map after this push. Expected: `actionLabel()` only feeds
`buildRuleFlow()`, not the graph.

## 24. Phase 3 - Hub Variable graph entity, 2026-08-15

Added the WRITE side only, per the spec's own Phase 3 scope (READ is Phase 4,
needs a second consumer-rule fixture that doesn't exist yet).

- `extractHubVariableWrites(Map data)` - a new function, independent of
  `actionLabel()`/`buildRuleFlow()` by design (matching how this file already
  computes `roles` and `flow` as separate passes over the same scan data
  rather than threading one through the other). Re-derives `xVarV`/
  `valStringOp`/`customDev`/`tCustomAttr` from `data.appSettings`/
  `data.appState` directly. Returns `[{variable, sourceDevice?, sourceAttr?}]`.
- `fetchAppRelationships()` calls it and stores the result as
  `out.hubVarWrites`, alongside the existing `roles`/`flow`/`ruleLinks`.
- `buildGraph()` creates a `hubVariable`-group node per unique variable name
  (id `v<name>` - identity is the name itself, since Hub Variables are
  hub-scoped shared state, not owned by the app that happens to write them,
  unlike a device id) and a `write` edge from the writing app to it, with an
  optional `detail` field ("from Hub Information.formattedUptime") carried on
  the edge for anything that wants it later.
- `GRAPH_SCHEMA` bumped `3` -> `4` so an existing stored graph is marked stale
  and forces a rescan, via the mechanism already built for exactly this
  (`graphIsStale()`) - the comment already sitting on that constant describes
  precisely this situation (an edge kind added with no colour/dash lookup).
- JS: `groupColors`/`roleColors`/`KIND_LABEL`/`GROUP_LABEL` all get a
  `hubVariable`/`write` entry (`#4fb3a9`, teal - distinct from app orange,
  device blue-grey, external light-grey). Node shape is `triangle` (new,
  alongside the existing dot/square/diamond). Legend gets one node-colour row
  and one edge-kind row, matching the existing rows' format exactly.

### Deliberately not done in this pass

- **Pivot table.** `pivotKindOptions`/`pivotColOptions`/`PIVOT_PRESETS` still
  only know about `app|app`, `app|device`, `app|external`. An `app|
  hubVariable` combination is not wired in - it degrades gracefully (empty
  option list, not a crash) rather than being unavailable due to a bug, but a
  "Rule -> Hub Variables" preset is a real gap, not just polish. Matches the
  spec's own Phase 6 ("regression and UI hardening"), not Phase 3.
- No filter checkbox to hide Hub Variables independently - matches spec
  Section 10.4's explicit fallback ("may initially follow the automation/
  dependency visibility state"), since one wasn't found to already exist for
  device/app either.
- Consumer-rule fixture (Phase 4) not created - needs a second Rule Machine
  rule on Gordon's hub, not just code.

### Next step

Rescan is required to see this - `GRAPH_SCHEMA` bumping means the currently
stored graph is stale, and `graphIsStale()` shows a message rather than
auto-rescanning. Pressing Scan again should draw `TestHubUptime` as a new
triangular node connected to `_Test Variables` by a teal `write` edge.

Pushed to hub (dev app instance 2976, revision 45). Not pushed to git.

---

## 25. Phase 4 - Hub Variable READ relationships, 2026-08-15

Gordon built the consumer fixture himself: app `2984`, `_Test Variables
Extended`, a clone of the canonical rule with the original write action
wrapped in a new `IF (Variable TestHubUptime is not equal to '0') THEN Set
Private Boolean True`. This rule both reads and writes `TestHubUptime` -
Section 6.5's hardest case ("do not collapse this into an ambiguous
undirected dependency") arrived as the actual fixture rather than a separate
one, since Gordon cloned rather than built from scratch.

### Raw structure - condition side

Pulled `/installedapp/statusJson/2984` before writing any code, same
discipline as Phase 1. A Variable-typed condition encodes as:

| Setting name | Value | Meaning |
|---|---|---|
| `rCapab_3` | `"Variable"` | Condition 3's left side is a Hub Variable - the condition-side counterpart to `tCapab1` on triggers |
| `xVar_3` | `"TestHubUptime."` | Which variable (same trailing-period artifact as `xVarV` on the write side) |
| `RelrDev_3` | `"≠"` | Comparison operator |
| `state_3` | `"0"` | Compare value |

`3` here is a condition-expression id from a separate counter than action ids
(`ruleNdx` vs `ndx` in `appState`) - coincidentally the same number as the IF
action's own id in this fixture, not the same id space. `eval` maps a group id
to a list of these atomic condition ids (`{"2":[3]}` here); `evalMap['0']`
would be the top-level Required Expression if the rule used one - this rule
doesn't, but the extraction covers that case for free by scanning every group
in `eval` rather than only ones reached from an IF action.

No `rDev_3` exists for this condition at all - `requiredDevices()` correctly
returns nothing for a Variable-typed condition, which is exactly why it
needed a counterpart rather than an extension: it looks for a device that
was never going to be there.

### What was built

- `extractHubVariableReads(Map data)` - scans every `eval` group, keeps atomic
  condition ids where `rCapab_<n> == 'Variable'`, reads the name from
  `xVar_<n>`. Returns `[{variable}]`, wired into `fetchAppRelationships()` as
  `out.hubVarReads`, same pattern as the write side.
- `buildGraph()` gets a second loop alongside the write one. Important
  decision: the READ edge is stored `from: appNodeId, to: varNodeId` - **not**
  reversed to `from: variable, to: app` despite reading conceptually flowing
  variable-to-rule. The page template has an explicit existing invariant
  (see the comment on `pivotKindOptions`: "every edge on this map has an app
  in `from`... that is a fact about the data") that other code depends on.
  Breaking it for read edges only would have made hub-variable edges a
  special case silently incompatible with code that assumes `edge.from`
  resolves to an app. The visual direction is corrected the same way a
  device trigger already is instead: `read` joins `trigger`/`constraint`/
  `monitor` in the JS `inbound` list, so the arrowhead still lands on the app
  even though `from` is the app.
- Same `from`/`to` pair as a write edge between the same rule and variable
  produces two parallel curved arcs rather than one edge overwriting the
  other - confirmed by reading the existing `pairSeen`/`dupIndex` curving
  logic rather than assuming it, since this was exactly the ambiguous-
  collapse failure Section 6.5 warns about.
- Colours: `read: '#8fd6cc'`, a lighter shade of the write teal (`#4fb3a9`) -
  deliberately related rather than a wholly new colour family, so both read
  and grouped visually as "Hub Variable relationship" while still being
  distinguishable from each other and from every existing role colour.
- `GRAPH_SCHEMA` bumped `4` -> `5` - `read` is a new edge kind the version-4
  graph (scanned for Phase 3) has never seen, same reasoning as the Phase 3
  bump.

### Coverage note

This fixture exercises READ-via-condition (part of the spec's Phase 5,
"Hub Variable comparison condition") and READ+WRITE-of-the-same-variable
(Section 6.5) simultaneously, ahead of the plain Phase 4 "consumes without
modifying" case and ahead of schedule for Phase 5. Not yet covered by any
fixture: a rule whose TRIGGER is itself a Hub Variable change (Fixture C),
and a Required Expression specifically rather than an IF condition (both
should already work given the implementation scans every `eval` group
uniformly, but neither has been observed against real hub data).

### Next step

Rescan required again (schema bump). Expect: `_Test Variables Extended` shows
on the map with both a teal `write` edge and a lighter-teal `read` edge to
`TestHubUptime`, curved apart rather than overlapping.

Confirmed live, both edges render correctly with the curved-parallel-edge
handling already in the page template, arrows pointing opposite directions
as intended - no code changes needed beyond what Phase 4 already built.

---

## 26. Phase 6 (partial) - pivot table, regression check, 2026-08-15

Gordon said to keep going. Phase 5's remaining items (variable-triggered
rule, dedicated Required Expression fixture, variable-in-action-text) all
need new fixtures only Gordon can build - per the established division of
labour this session (he builds Rule Machine fixtures, code follows from
real hub data, never from guessing an undocumented structure). Moved to
Phase 6 instead, which has concrete work not gated on new fixtures.

### Pivot table wired up

`pivotKindOptions`/`pivotColOptions` gain an `app|hubVariable` entry
(`['write', 'read']`). Two new `PIVOT_PRESETS`: "Rule -> Hub Variables" and
"Hub Variable -> Rules", mirroring the existing Device presets exactly
(`ruleRows`/`ruleCols` respectively).

No changes needed to `pivotRows()` itself - it already branches generically
on whichever of `fromNode.group`/`toNode.group` matches the requested
row/column group, in either order. This is a second, independent
confirmation that keeping every hub-variable edge stored `from: app` (see
Section 25) was the right call: the exact same invariant that made the
legend/colour code simple also made this fall out for free.

### Regression check against live hub data, not just the two test rules

Read `state.graph` directly from `/installedapp/statusJson/2976` (Automation
Map's own stored state) rather than eyeballing the rendered map:

    307 nodes, 1045 edges (was 303/1038 before this work began)
    2 hubVariable nodes: TestHubUptime, Overloadcount
    3 write edges, 2 read edges

**`Overloadcount` was not created for this work.** It's a Hub Variable
already in use on Gordon's live hub, written and read by app `2100` - a
real pre-existing cross-rule dependency the map had never been able to show
before, surfaced without any special-casing. Stronger evidence the feature
works than either deliberately-built fixture: it holds up against data it
was never designed around.

Node/edge growth (+4 nodes, +7 edges) is consistent with what was added
(app 2984, two hubVariable nodes, five hub-variable edges, plus 2984's own
ordinary trigger edge) - no runaway growth, no evidence of the "graphs do
not explode into per-attribute nodes" failure mode Phase 6's checklist warns
about.

### Still open from Phase 6's checklist

Not independently re-verified this pass (no reason to suspect a problem,
but not specifically checked either): variable values not leaked anywhere
in the new code paths (true by construction - neither extraction function
reads a Hub Variable's actual value, only its name), and readability at a
much larger Hub Variable count than the two seen so far.

Pushed to hub (revision 47, pivot table only, no schema bump - existing
graph data is fully compatible with the new UI). Not pushed to git.

---

## 27. Phase 5 - trigger and free-text reads, 2026-08-15

Gordon built all three remaining fixtures and exported them directly via
Rule Machine's own Export/Import/Clone feature rather than me pulling
`statusJson` - faster, and the export matched the reverse-engineered
structure exactly, an independent confirmation of everything built so far.

### `_Test Variables Required` (app 2990) did not test what it was meant to

`eval` is empty, no `rCapab_`/`xVar_`/`hasPredicate` anywhere - structurally
identical to the Trigger fixture (2988), just renamed. The Required
Expression toggle did not end up configured with a variable condition.
Not rebuilt this pass - `extractHubVariableReads()` already scans every
`eval` group including `'0'` (Required Expression's own group, alongside
whatever numbered groups individual IF actions use), so this case is very
likely already covered by the same code path proven for IF conditions -
but that is an inference from the code's own structure, not something
observed against real data. Flagged, not fixed. Rebuild properly if this
specific case needs to move from "very likely" to "confirmed."

### Trigger-by-variable - confirmed, implemented

Rule 2988, "_Test Variables Trigger": a rule that fires when `TestHubUptime`
itself changes, not on a device attribute.

| Setting | Value | Meaning |
|---|---|---|
| `tCapab1` | `"Variable"` | Same slot a device trigger puts "Custom Attribute" in |
| `xVar1` | `"TestHubUptime."` | The variable - no underscore, unlike the condition-side `xVar_<n>` |

Event subscription differs from every device trigger seen so far:
`type: "LOCATION"`, `name: "variable:TestHubUptime."`, `typeId: 1` (the
location itself) - not `type: "DEVICE"`. Not currently read by this app
(subscriptions aren't part of how any of this is detected), but worth
knowing if that ever needs to change.

Added to `extractHubVariableReads()`: scans every `tCapab<n>` setting,
treats `== 'Variable'` the same as a condition's `rCapab_<n>`, reads the
name from `xVar<n>`. Lands in the same `names` list as condition-based
reads - a variable trigger and a variable condition both currently produce
the same generic `read` edge. Per the spec (Section 6.3) a trigger is
technically `READ + TRIGGER`, a distinct semantic from plain `READ` - not
yet split into a separate edge kind. Same gap already logged for
condition-vs-plain-read.

### Free text - confirmed, implemented

Rule 2992, "_ Test Variables Text": `Set Variable` action, `valStringOp.1
= "Set string"`, `valString.1 = "%TestHubUptime%"` - Rule Machine's own
reserved substitution syntax, typed directly into a text field rather than
picked from a structured dropdown.

Added a third scan to `extractHubVariableReads()`: every `text`/`textarea`
setting value is checked for `%Name%` against a regex, any match is a read.
Deliberately the lowest-priority extraction in the function, matching the
spec's own stated preference (Section 8.1: structured state first, visible
text only as a bounded fallback) - it exists because nothing else can catch
a variable reference embedded in typed text the way `xVarV`/`xVar_` catch
a picker selection. Scoped to `text`/`textarea` setting types specifically,
not every setting, since RM's own bookkeeping fields are almost entirely
enums and buttons that could never legitimately contain this syntax.

### Trailing period, revised understanding

Comparing this batch's fixtures resolved something left open in Section 22.
`TestConcat` (created fresh this session, never renamed) carries no trailing
period anywhere it appears - `xVarV.1 = "TestConcat"`, clean. `TestHubUptime`
carries one everywhere, including a `p.TestHubUptime.` state key and a
`formerState` field. The period is not a general artifact of Rule Machine's
variable picker - it is specific to `TestHubUptime`'s own internal record,
most likely a leftover from being renamed at some point during earlier
testing. The strip-trailing-period code in every extraction function stays
correct regardless (a no-op when absent), but the reasoning in Section 22
calling it a "picker artifact" was an overclaim - corrected here rather than
left standing.

### Status

Pushed to hub (revision 48). No `GRAPH_SCHEMA` bump - `read` already existed
as an edge kind, this only expands which rules get detected as producing
one, not the shape of the data itself. Not pushed to git.

Remaining from the original spec: condition/trigger vs. plain-read not
distinguished as separate edge kinds, and Phase 6's scale/removal checks
still not empirically tested.

---

## 28. Required Expression - confirmed, 2026-08-15

Gordon rebuilt `_ Test Variables Required` (app 2990) correctly this time -
the Required Expression section now actually holds the variable condition,
confirmed both by the settings page screenshot (a distinct "Define Required
Expression" block, separate from Trigger and Actions) and the export.

`hasPredicate: true`, `eval: {"0": [2]}`, `rCapab_2 = "Variable"`,
`xVar_2 = "TestHubUptime."` - identical shape to the IF condition already
verified in Section 25, just in group `'0'` instead of an action-numbered
group. This is exactly what Section 27 predicted from the code's own
structure ("very likely already covered... not something observed against
real data") - now it is observed. No code change was needed for the
detection itself.

One real gap this data surfaced, fixed while confirming it: the existing
`buildRuleFlow()` only trusts group `'0'` when `hasPredicate == true` (so a
rule where the toggle was switched back off can't have a stale leftover
Required Expression rendered as if still active). `extractHubVariableReads()`
had no equivalent guard - it would have trusted `eval['0']` unconditionally.
Added the same check for consistency. Every other eval group is tied
directly to an action's presence in `actionList`, which has no equivalent
toggle to go stale against, so only group `'0'` needed it.

This rule has no `getSetVariable` action at all (only `getSetPrivateBoolean`),
so it also serves as the first clean READ-only fixture - no write edge
should appear for it, only read, contributed by both its trigger and its
Required Expression referencing the same variable, deduplicated to one edge
per the existing per-rule dedup.

### Status

Pushed to hub (revision 49). Not pushed to git. Every Phase 5 fixture the
spec asked for (condition, trigger, free text, Required Expression) is now
confirmed against real data, not inferred from code structure.

---

## 29. Fabricated lineage from free-text false positives - found and fixed, 2026-08-15

Gordon rescanned and pivoted the whole hub, not just the test fixtures. That
surfaced a real bug: `time`, `date`, and `device` showed up as "Hub
Variables" read by actual production rules - `Barking`, `Perimeter Closed`,
`Front Door Lights (Night)`, `Mode Alarm Reminder`, `Suitcase Access
Detected`, none of which have ever created a Hub Variable by any of those
names.

### Cause

Rule Machine reserves `%device%`, `%time%`, `%date%` (and presumably others)
as built-in notification-message tokens, unrelated to user Hub Variables.
The free-text scan added in Phase 5 matched `%Name%` syntax alone, with no
way to tell RM's own reserved tokens apart from a genuine variable
reference - exactly the "must not fabricate lineage" failure the spec warns
about (Section 9), now demonstrated on live production data rather than a
hypothetical.

### Fix

`extractHubVariableReads()` now tags every result `confirmed: true` (a
structured field - `rCapab_`/`xVar_` or `tCapab`/`xVar` - named it
explicitly, no ambiguity) or `confirmed: false` (only a `%Name%` text
pattern matched). `buildGraph()` gained a pre-pass, `confirmedVarNames`,
collecting every name confirmed anywhere on the hub - not just within the
same app, since a free-text candidate in one rule can only be validated
against structured evidence that might live in a completely different
rule - before any edge is drawn. An unconfirmed candidate is only kept if
its name independently appears in that set; otherwise it is dropped
silently, not drawn as a guess.

This means a genuine Hub Variable actually named `time` or `device` would
still be correctly detected (via its own structured write/read somewhere),
while RM's reserved tokens - which by definition never get a structured
reference, since nothing ever creates a Hub Variable called `device` - are
excluded. The free-text case (`TestHubUptime` read inside `_ Test Variables
Text`) still passes, since `TestHubUptime` is independently confirmed via
multiple structured references elsewhere on the hub.

### Second bug, found while fixing the first

`_Test Variables Trigger`'s focus panel showed the "no device or rule
relationship" inert-app text, despite having a real, drawn edge to
`TestHubUptime`. The `inert` boolean never checked `hubVarWrites`/
`hubVarReads` at all - a rule whose only relationship is to a Hub Variable
was being misclassified as having none. Fixed by adding a
`hasVarRelationship` check, itself checked against the same confirmed/
`confirmedVarNames` logic as the edges themselves - not just "is
hubVarReads non-empty", since an app whose only entry is an unconfirmed
candidate that gets filtered out must not count as having a relationship
either, or the two fixes would have quietly contradicted each other.

### Status

Pushed to hub (revision 51). Not pushed to git. Rescan needed to clear the
false-positive edges already sitting in the stored graph from the previous
scan - no `GRAPH_SCHEMA` bump this time (edge kinds/node shapes are
unchanged, this is a data-correctness fix, not a schema change), so the app
won't prompt for a rescan on its own; it has to be requested.
