# Hubitat Automation Map - Rule-to-Rule Mapping Implementation

## 1. Purpose

Automation Map should be able to show when one Hubitat automation causes, enables, disables, invokes, changes, or otherwise influences another automation.

This is different from ordinary device dependency mapping.

A conventional device map might show:

```text
Rule A
  |
  v
Virtual Switch
  |
  v
Rule B
```

That is useful, but it hides the more meaningful automation relationship:

```text
Rule A
  |
  v
Rule B
```

The objective of rule-to-rule mapping is therefore to derive a second-order automation graph from the underlying Hubitat runtime relationships.

The rule-to-rule graph should answer:

> Which automations influence other automations, directly or indirectly?

It should not replace the lower-level device graph. It should be a derived view built on top of it.

---

## 2. Core Principle

Rule-to-rule mapping should use a layered inference model.

```text
Direct app/action relationship
        |
        v
Explicit rule invocation
        |
        v
Shared virtual/control device
        |
        v
Location state or mode change
        |
        v
App enable/disable/pause relationship
        |
        v
Indirect inferred relationship
```

The stronger the evidence, the stronger the graph edge.

Automation Map should distinguish:

- direct invocation;
- direct control;
- shared state mediation;
- inferred causality;
- possible relationship.

---

## 3. Rule-to-Rule Relationship Types

Recommended relationship types include:

| Edge type | Meaning |
|---|---|
| `INVOKES` | Rule A explicitly runs Rule B |
| `RUNS_ACTIONS_OF` | Rule A executes another rule's actions |
| `PAUSES` | Rule A pauses Rule B |
| `RESUMES` | Rule A resumes Rule B |
| `ENABLES` | Rule A enables Rule B |
| `DISABLES` | Rule A disables Rule B |
| `CANCELS` | Rule A cancels pending actions in Rule B |
| `TRIGGERS_VIA_DEVICE` | Rule A changes a device that triggers Rule B |
| `TRIGGERS_VIA_VARIABLE` | Rule A changes a variable observed by Rule B |
| `TRIGGERS_VIA_MODE` | Rule A changes Location Mode and Rule B subscribes to Mode |
| `TRIGGERS_VIA_HSM` | Rule A changes HSM state and Rule B reacts |
| `TRIGGERS_VIA_LOCATION` | Rule A changes a location-level state consumed by Rule B |
| `SHARES_CONTROL_DEVICE` | Both rules write to the same coordination device |
| `SHARES_TRIGGER_SOURCE` | Multiple rules react to the same source but do not trigger one another |
| `POSSIBLY_TRIGGERS` | Weak inferred relationship requiring review |

The UI should visually distinguish direct and indirect edges.

---

## 4. Discovery Sources

Rule-to-rule discovery should use several evidence sources.

### 4.1 Native App Relationships

Where Hubitat exposes one app invoking another, this should be treated as authoritative.

Examples may include actions such as:

- run another rule;
- run another rule's actions;
- pause another rule;
- resume another rule;
- disable another automation;
- enable another automation.

These should create direct edges.

Example:

```text
Rule A
  |
RUNS_ACTIONS_OF
  |
  v
Rule B
```

Confidence:

```text
100%
```

---

## 5. Source and Action Inspection

When the action model is available, Automation Map should inspect the rule's configured actions.

The objective is not to parse arbitrary Groovy.

The objective is to identify known Rule Machine action types and their targets.

Conceptually:

```text
Rule
  |
  +-- Trigger definitions
  |
  +-- Conditions
  |
  +-- Actions
         |
         +-- Device command
         +-- Variable write
         +-- Mode change
         +-- HSM command
         +-- Run Rule
         +-- Pause Rule
         +-- Resume Rule
```

For each action, Automation Map should emit an intermediate semantic record.

Example:

```json
{
  "sourceRule": "Rule A",
  "actionType": "RUN_RULE_ACTIONS",
  "targetRule": "Rule B",
  "confidence": 100
}
```

---

## 6. Device-Mediated Rule Relationships

Most Hubitat rule-to-rule interactions will not be explicit.

They will occur through devices.

Example:

```text
Rule A
  |
  v
Virtual Switch X
  |
  v
Rule B
```

If:

- Rule A writes to Virtual Switch X, and
- Rule B triggers on Virtual Switch X,

Automation Map can derive:

```text
Rule A
  |
TRIGGERS_VIA_DEVICE
  |
  v
Rule B
```

This is a strong inferred relationship.

Suggested confidence:

```text
90-95%
```

The original device path should remain inspectable.

---

## 7. Producer and Consumer Model

The cleanest implementation is to classify each rule relationship to a stateful object as either:

- producer;
- consumer;
- both.

Example:

```text
Rule A -> Virtual Switch X
```

If Rule A executes:

```text
Switch X ON
```

then Rule A is a:

```text
PRODUCER
```

If Rule B subscribes to:

```text
Switch X changed
```

then Rule B is a:

```text
CONSUMER
```

Automation Map can derive:

```text
PRODUCER -> STATE OBJECT -> CONSUMER
```

and collapse it to:

```text
Rule A -> Rule B
```

for the rule-to-rule view.

---

## 8. State Objects

The producer/consumer model should apply to more than devices.

Recommended state object types include:

| State object | Example |
|---|---|
| Device attribute | switch, contact, motion |
| Virtual device | virtual switch, button, sensor |
| Hub variable | Boolean, number, string |
| Connector device | Rule Machine connector |
| Location Mode | Home, Away, Night |
| HSM status | Armed Away, Disarmed |
| Presence state | Present/Away |
| Private Boolean | Rule-specific state |
| Global variable | Legacy/global RM variable |
| Virtual lock/contact | Coordination object |

The rule graph engine should normalise all of these into a generic state-object model.

---

## 9. Hub Variables

Hub Variables are particularly useful coordination mechanisms.

Example:

```text
Rule A
  |
SETS VARIABLE
  |
  v
GuestMode = true
  |
OBSERVED BY
  |
  v
Rule B
```

Derived relationship:

```text
Rule A
  |
TRIGGERS_VIA_VARIABLE
  |
  v
Rule B
```

However, changing a variable does not necessarily trigger Rule B.

Rule B may only read it as a condition.

The graph should therefore distinguish:

```text
TRIGGERS_VIA_VARIABLE
```

from:

```text
INFLUENCES_VIA_VARIABLE
```

This distinction matters.

---

## 10. Trigger Versus Condition

A major source of false positives is treating a condition as a trigger.

Example:

```text
Rule B Trigger:
Motion Active

Rule B Condition:
GuestMode = false
```

If Rule A sets:

```text
GuestMode = false
```

Rule A does not necessarily trigger Rule B.

It only changes whether Rule B is eligible to run.

Therefore:

```text
Rule A -> GuestMode -> Rule B
```

should produce:

```text
INFLUENCES
```

not:

```text
TRIGGERS
```

Recommended derived edge:

```text
CONDITIONS
```

or:

```text
INFLUENCES_VIA_STATE
```

This distinction is essential for accurate rule-to-rule mapping.

---

## 11. Trigger Semantics

Automation Map should classify subscriptions separately from conditions.

For each rule:

```text
Triggers:
- Motion active
- Switch changed
- Variable changed
- Mode changed
```

versus:

```text
Conditions:
- Time between 23:00 and 05:00
- Mode = Night
- GuestMode = false
```

Only trigger subscriptions should normally create:

```text
TRIGGERS_VIA_*
```

edges.

Conditions create:

```text
INFLUENCES_VIA_*
```

edges.

---

## 12. Mode-Based Relationships

Example:

```text
Rule A
  |
SET MODE = Night
  |
  v
Location Mode
  |
  v
Rule B
```

If Rule B triggers on:

```text
Mode becomes Night
```

derive:

```text
Rule A
  |
TRIGGERS_VIA_MODE
  |
  v
Rule B
```

If Rule B only contains:

```text
Required Expression:
Mode = Night
```

derive:

```text
Rule A
  |
INFLUENCES_VIA_MODE
  |
  v
Rule B
```

---

## 13. HSM Relationships

The same approach applies to Hubitat Safety Monitor.

Example:

```text
Rule A
  |
Arm HSM Away
  |
  v
HSM State
  |
  v
Rule B
```

If Rule B triggers on HSM state:

```text
TRIGGERS_VIA_HSM
```

If Rule B only checks HSM state:

```text
INFLUENCES_VIA_HSM
```

---

## 14. Pause, Resume and Disable

These are direct automation-management relationships.

Example:

```text
Rule A
  |
PAUSES
  |
  v
Rule B
```

This is not a trigger relationship.

It is an operational-control dependency.

Suggested graph edge:

```text
PAUSES
```

with:

```text
confidence = 100
```

Similarly:

```text
Rule A -> RESUMES -> Rule B
Rule A -> ENABLES -> Rule B
Rule A -> DISABLES -> Rule B
```

These edges should be visually distinct from event-driven edges.

---

## 15. Cancel Pending Actions

If Rule A cancels delayed actions belonging to Rule B, this should also be represented.

Example:

```text
Rule A
  |
CANCELS
  |
  v
Rule B
```

This is particularly valuable when diagnosing delayed or repeated automations.

---

## 16. Virtual Coordination Devices

Virtual switches and buttons are commonly used as orchestration buses.

Example:

```text
Rule A
  |
turns on
  |
  v
Virtual Switch: Bedtime
  |
triggers
  |
  v
Rule B
```

Automation Map should preserve both views:

### Physical/logical view

```text
Rule A -> Bedtime Virtual Switch -> Rule B
```

### Collapsed automation view

```text
Rule A -> Rule B
```

The user should be able to expand the edge to see the mediation object.

---

## 17. Multi-Consumer Relationships

One producer may affect several rules.

Example:

```text
Rule A
  |
  v
Away Mode
  |
  +--> Rule B
  +--> Rule C
  +--> Rule D
```

Derived view:

```text
Rule A
  +--> Rule B
  +--> Rule C
  +--> Rule D
```

The edge metadata should preserve:

```text
mediatedBy = Location Mode
```

---

## 18. Multi-Producer Relationships

Several rules may write to the same coordination object.

Example:

```text
Rule A ----            Rule B ------> Virtual Switch X ---> Rule D
            /
Rule C ----/
```

Automation Map should not assume all three producers always trigger Rule D.

The edge should be derived from:

```text
producer writes state
+
consumer trigger predicate
```

where possible.

---

## 19. Action Value Matching

A stronger implementation should compare values, not just objects.

Example:

Rule A:

```text
Set Virtual Switch X = ON
```

Rule B trigger:

```text
Virtual Switch X turns OFF
```

There is no direct causal relationship.

A naive device-level matcher would incorrectly create one.

The graph engine should therefore support semantic matching.

Example:

```json
{
  "producer": {
    "object": "Virtual Switch X",
    "attribute": "switch",
    "writes": "on"
  },
  "consumer": {
    "object": "Virtual Switch X",
    "attribute": "switch",
    "trigger": "off"
  }
}
```

Result:

```text
No trigger edge
```

If Rule B triggers on:

```text
switch changed
```

then:

```text
TRIGGERS_VIA_DEVICE
```

is valid.

---

## 20. Attribute-Level Matching

Device matching should occur at the attribute level.

Examples:

```text
Motion Sensor
  - motion
  - battery
```

If Rule A manipulates a virtual device's:

```text
switch
```

and Rule B watches:

```text
contact
```

the relationship should not be inferred merely because they refer to the same device.

The semantic key should be:

```text
deviceId + attribute
```

not just:

```text
deviceId
```

---

## 21. Command-to-Attribute Mapping

Commands must be mapped to the state changes they normally produce.

Examples:

| Command | Expected attribute impact |
|---|---|
| `on()` | `switch = on` |
| `off()` | `switch = off` |
| `open()` | `door/contact/open` depending capability |
| `close()` | `door/contact/closed` |
| `setLevel()` | `level`, usually `switch` |
| `setColorTemperature()` | `colorTemperature` |
| `setMode()` | thermostat mode |
| `lock()` | `lock = locked` |
| `unlock()` | `lock = unlocked` |

This mapping allows Automation Map to infer causal relationships more accurately.

It should be capability-aware rather than driver-name-specific.

---

## 22. Rule Machine Connectors

Rule Machine connector devices should be treated as first-class mediation objects.

Example:

```text
Rule A
  |
updates connector
  |
  v
Rule A Connector
  |
  v
Rule B
```

These can be collapsed in the rule-to-rule view while remaining visible in the expanded dependency view.

---

## 23. Required Expressions

Required Expressions should never be treated as triggers.

If Rule A changes a state used only in Rule B's Required Expression:

```text
Rule A
  |
  v
State X
  |
  v
Rule B Required Expression
```

derive:

```text
Rule A
  |
INFLUENCES_ELIGIBILITY
  |
  v
Rule B
```

This is an important architectural relationship even though it does not cause Rule B to execute immediately.

---

## 24. Conditional Actions

Rule actions may themselves be conditional.

Example:

```text
IF Mode = Night THEN
    Set Switch X ON
END-IF
```

Automation Map should preserve this as conditional edge metadata:

```json
{
  "edgeType": "TRIGGERS_VIA_DEVICE",
  "conditional": true,
  "conditionSummary": "Mode = Night"
}
```

The graph should avoid pretending that the relationship always occurs.

---

## 25. Delayed Actions

Rule Machine supports delayed actions.

Example:

```text
Rule A
  |
after 5 minutes
  |
Virtual Switch X ON
  |
  v
Rule B
```

Derived edge:

```text
Rule A
  |
TRIGGERS_VIA_DEVICE
delay = 300s
  |
  v
Rule B
```

Delay metadata is important for troubleshooting.

---

## 26. Repeated Actions

Repeated actions should also be represented.

Example:

```text
Rule A
  |
every 60 seconds
  |
Virtual Switch X toggle
  |
  v
Rule B
```

Edge metadata:

```text
repeating = true
interval = 60s
```

---

## 27. Cycles

Rule-to-rule graphs may contain cycles.

Example:

```text
Rule A -> Rule B -> Rule C -> Rule A
```

Cycles are not necessarily errors.

They may represent:

- state machines;
- escalation logic;
- mutual enable/disable patterns;
- reset loops;
- accidental feedback loops.

Automation Map should detect strongly connected components and mark them.

Suggested presentation:

```text
Cycle detected
A -> B -> C -> A
```

The UI should not automatically label the cycle as faulty.

---

## 28. Potential Feedback Loops

Some cycles are operationally dangerous.

Example:

```text
Rule A sets Switch X ON
Rule B triggers on Switch X ON
Rule B sets Switch Y ON
Rule C triggers on Switch Y ON
Rule C sets Switch X OFF
Rule A triggers on Switch X OFF
```

Automation Map could flag:

```text
Potential automation feedback loop
```

but should distinguish:

```text
Confirmed cycle
```

from:

```text
Potential oscillation
```

The latter requires semantic event/value analysis.

---

## 29. Self-References

A rule may affect a device that it also subscribes to.

Example:

```text
Rule A
  |
  v
Switch X
  |
  v
Rule A
```

This should be represented as a self-loop.

Self-loops are particularly useful when analysing repeated-trigger behaviour.

---

## 30. Confidence Levels

Suggested confidence model:

| Evidence | Confidence |
|---|---:|
| Explicit Rule A invokes Rule B | 100% |
| Explicit pause/resume/enable/disable | 100% |
| Producer value exactly matches consumer trigger | 95% |
| Producer changes attribute and consumer watches `changed` | 95% |
| Producer writes object consumed as trigger but value unclear | 85% |
| Producer changes state used as condition | 90% influence, not trigger |
| Shared device only | 50-60% |
| Name-based inference | 40-60% |

Edges below a configurable threshold should be hidden by default.

---

## 31. Intermediate Graph Model

Implementation should use an intermediate semantic graph before rendering.

Recommended entities:

```text
AutomationNode
StateObjectNode
IntegrationNode
DeviceNode
ExternalNode
```

Recommended edge categories:

```text
WRITES
READS
SUBSCRIBES_TO
INVOKES
MANAGES
CONDITIONS
```

Example raw graph:

```text
Rule A
  |
WRITES switch=on
  |
Virtual Switch X
  |
SUBSCRIBES_TO switch=on
  |
Rule B
```

Then derive:

```text
Rule A
  |
TRIGGERS_VIA_DEVICE
  |
Rule B
```

This separation keeps discovery logic independent from rendering logic.

---

## 32. Recommended Internal Data Structure

Example:

```json
{
  "sourceRuleId": "123",
  "targetRuleId": "456",
  "relationship": "TRIGGERS_VIA_DEVICE",
  "confidence": 95,
  "mediatedBy": {
    "type": "DEVICE",
    "id": "789",
    "name": "Bedtime Virtual Switch",
    "attribute": "switch",
    "producerValue": "on",
    "consumerPredicate": "on"
  },
  "conditional": false,
  "delaySeconds": 0,
  "repeating": false
}
```

---

## 33. Rule Scanner

The scanner should produce two structures per automation.

### Inputs

```json
{
  "ruleId": "456",
  "triggers": [],
  "conditions": [],
  "subscriptions": []
}
```

### Outputs

```json
{
  "ruleId": "123",
  "actions": [],
  "writes": [],
  "ruleManagementActions": []
}
```

The relationship engine then joins outputs to inputs.

---

## 34. Producer/Consumer Join

Pseudo-process:

```text
FOR each producer action:
    normalise target
    normalise attribute
    normalise value

    FIND consumers of same target + attribute

    FOR each consumer:
        IF consumer is trigger:
            compare producer value with trigger predicate
            create trigger edge if compatible

        IF consumer is condition:
            create influence edge

        IF consumer is required expression:
            create eligibility edge
```

This is the core of indirect rule-to-rule discovery.

---

## 35. Direct Rule Actions

Direct automation-management actions bypass the producer/consumer join.

Pseudo-process:

```text
IF action targets another automation:
    create direct rule edge
```

Examples:

```text
Run Rule
Pause Rule
Resume Rule
Cancel Rule Actions
Enable Rule
Disable Rule
```

These should be processed first.

---

## 36. Deduplication

The same relationship may be discovered through several paths.

Example:

```text
Rule A explicitly runs Rule B
```

and also:

```text
Rule A writes a virtual switch that Rule B triggers on
```

Automation Map should retain multiple evidence paths but normally show one edge.

Example:

```json
{
  "source": "Rule A",
  "target": "Rule B",
  "primaryRelationship": "INVOKES",
  "evidence": [
    "Explicit rule invocation",
    "Also mediated by Virtual Switch X"
  ]
}
```

---

## 37. Edge Aggregation

If Rule A can trigger Rule B through multiple devices:

```text
Rule A -> Switch X -> Rule B
Rule A -> Variable Y -> Rule B
```

the collapsed view may show:

```text
Rule A -> Rule B
```

with:

```text
2 dependency paths
```

Expanding the edge should reveal both.

---

## 38. Graph Views

Automation Map should offer at least three views.

### Full Dependency View

Shows:

```text
Rule -> Device -> Rule
```

### Rule-to-Rule View

Collapses mediation objects:

```text
Rule -> Rule
```

### Causality View

Shows only edges that can cause execution:

```text
TRIGGERS
INVOKES
RUNS_ACTIONS_OF
```

and hides:

```text
CONDITIONS
SHARED_DEVICE
INFLUENCES
```

This is particularly useful when troubleshooting unexpected automation chains.

---

## 39. Filtering

Recommended filters:

- direct only;
- inferred only;
- triggers only;
- influence only;
- pause/enable management;
- minimum confidence;
- show/hide mediation devices;
- show/hide virtual devices;
- show cycles only;
- show downstream chain;
- show upstream chain.

---

## 40. Impact Analysis

Rule-to-rule mapping enables useful queries.

Examples:

```text
What rules can eventually trigger Rule X?
```

```text
What rules are downstream of Rule Y?
```

```text
If I delete Virtual Switch Z, which automation chains break?
```

```text
Which rules control other rules?
```

```text
Which rules participate in cycles?
```

```text
Which rules are indirectly dependent on Mode?
```

---

## 41. Transitive Reachability

The rule graph should support transitive traversal.

Example:

```text
Rule A -> Rule B -> Rule C -> Rule D
```

Automation Map should distinguish:

```text
Direct dependency
```

from:

```text
Downstream dependency
```

A configurable traversal depth prevents large hubs from becoming unreadable.

---

## 42. Performance

Do not recompute the full derived graph for every render.

Recommended approach:

```text
Scan apps
  |
Normalise semantic relationships
  |
Persist/cache graph
  |
Rebuild when app configuration changes
```

On Hubitat, care should be taken not to create expensive repeated scans.

A manual:

```text
Refresh Map
```

operation is acceptable for the first implementation.

Later versions could perform incremental updates.

---

## 43. Runtime Constraints

Hubitat does not necessarily expose every internal Rule Machine configuration in a convenient public API.

Implementation therefore needs to be pragmatic.

Priority order:

```text
1. Public/native metadata
2. App settings available to Automation Map
3. Known Rule Machine structures
4. Exported/configuration representations if accessible
5. User annotation
```

The implementation should avoid dependence on undocumented internals where possible.

If a rule cannot be fully analysed, it should still appear in the graph with partial relationships.

---

## 44. Unknown Relationships

When Automation Map sees:

```text
Rule A writes Device X
Rule B references Device X
```

but cannot establish whether Rule B subscribes to or merely reads it, the edge should be:

```text
POSSIBLE_RELATIONSHIP
```

not:

```text
TRIGGERS
```

This prevents false causal claims.

---

## 45. Visual Semantics

Suggested edge rendering:

| Relationship | Visual treatment |
|---|---|
| Direct invocation | Strong solid line |
| Device-mediated trigger | Solid line |
| Variable/mode-mediated trigger | Solid line with mediation badge |
| Condition/influence | Dashed line |
| Pause/disable | Control-style line |
| Possible relationship | Faint/dotted line |
| Cycle | Highlighted cycle badge |

Exact colours should be a UI decision rather than embedded in the data model.

---

## 46. Example

Consider:

### Rule A

```text
Trigger:
23:00

Actions:
Set Mode = Night
Turn Bedtime Virtual Switch ON
```

### Rule B

```text
Trigger:
Bedtime Virtual Switch turns ON
```

### Rule C

```text
Trigger:
Mode becomes Night
```

### Rule D

```text
Trigger:
Motion active

Required Expression:
Mode = Night
```

Raw graph:

```text
Rule A
  +--> Mode = Night
  |      +--> Rule C
  |      +--> Rule D Required Expression
  |
  +--> Bedtime Virtual Switch ON
         |
         +--> Rule B
```

Derived rule map:

```text
Rule A
  |
  +-- TRIGGERS_VIA_DEVICE --> Rule B
  |
  +-- TRIGGERS_VIA_MODE ----> Rule C
  |
  +-- INFLUENCES_ELIGIBILITY -> Rule D
```

This is substantially more accurate than simply drawing four rules connected because they share devices or Mode.

---

## 47. Recommended Implementation Phases

### Phase 1 - Direct Rule Relationships

Implement:

- run another rule;
- run another rule's actions;
- pause;
- resume;
- enable;
- disable;
- cancel actions.

### Phase 2 - Device-Mediated Relationships

Implement:

- producer/consumer device mapping;
- attribute matching;
- virtual switches/buttons;
- command-to-attribute mapping.

### Phase 3 - State-Mediated Relationships

Implement:

- Hub Variables;
- Mode;
- HSM;
- connector devices;
- Required Expressions;
- conditions versus triggers.

### Phase 4 - Semantic Precision

Implement:

- value matching;
- delayed actions;
- repeated actions;
- conditional paths;
- edge confidence.

### Phase 5 - Advanced Analysis

Implement:

- cycle detection;
- downstream/upstream impact;
- transitive chains;
- potential feedback-loop detection;
- graph filtering.

---

## 48. Maintenance

The rule-to-rule mapping engine should be maintained separately from the external Integration Registry.

Recommended components:

```text
rule_semantics.json
command_attribute_map.json
rule_action_types.json
```

These files can define:

- recognised Rule Machine action types;
- recognised trigger types;
- command-to-attribute effects;
- state-object classifications;
- confidence defaults.

This prevents Rule Machine-specific knowledge from being hard-coded throughout the graph engine.

When Hubitat introduces new Rule Machine actions or automation types:

1. identify the new semantic action;
2. classify it as producer, consumer, direct rule action or condition;
3. add/update the semantic mapping;
4. validate against a real rule;
5. increment the semantic-map version.

---

## 49. Final Design

The rule-to-rule map should be a derived semantic graph.

```text
Native Rule Configuration
        +
Action/Trigger Semantics
        +
Producer/Consumer Analysis
        +
State-Object Mediation
        +
Confidence Scoring
        =
Rule-to-Rule Dependency Graph
```

The central design principle is:

> Do not infer rule causality merely because two rules reference the same device.

Instead determine whether one automation produces a state transition that another automation actually subscribes to, invokes, or depends upon.

That distinction is what makes the rule-to-rule map useful rather than merely visually impressive.
