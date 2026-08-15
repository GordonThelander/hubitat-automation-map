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

The canonical `_Test Variables` fixture must pass first, followed by at least one separate consumer rule proving cross-rule lineage through `TestHubUptime`.
