# First-Class Hub Variables Specification

**Status:** Draft for review  
**Date:** 2026-08-26  
**Scope:** Automation Map graph, discovery, UI, diagnostics, baseline comparison and AI-friendly export  
**Implementation authorization:** None. This document is a design proposal.

## 1. Decision

A Hub Variable is a first-class Automation Map entity, at the same graph and navigation level as an
app, device and external system. It is not the same semantic kind as any of them.

The entity model is:

```text
Map entity
|- App                 actor or automation logic
|- Device              physical or virtual endpoint
|- Hub Variable        hub-scoped shared state resource
|- External system     dependency outside the hub graph
`- Hub                 execution container, if represented
```

Equal graph status means a Hub Variable can have its own stable identity, node, search result,
filter, detail view, relationships, comparison result, diagnostics and export record. It does not
mean Hub Variables inherit device capabilities, commands, drivers or app behavior.

## 2. Platform basis

Hub Variables are implemented by the Hubitat platform rather than owned by Rule Machine. They are
hub-scoped, can be consumed by multiple compatible apps and support Number, Decimal, String, Boolean
and DateTime types. Hubitat can associate a Connector with a Hub Variable. The Connector is a
virtual device synchronized bidirectionally with the variable.

References:

- <https://docs2.hubitat.com/en/user-interface/settings/hub-variables>
- <https://community.hubitat.com/t/new-app-features-in-2-2-8/74236>

Consequences for Automation Map:

- A variable is not owned by the rule that happens to reveal it.
- A complete variable inventory is a hub-level concern, not a Rule-Machine-only concern.
- A Connector is an adapter exposing the variable through the device model, not an independent
  source of truth.
- Variable names and values are household information.

## 3. Goals

1. Represent every Hub Variable that the hub can authoritatively enumerate, including unused ones.
2. Preserve evidence-backed read and write relationships from supported app decoders.
3. Treat unsupported consumers as an explicit discovery limitation rather than proof of no use.
4. Connect a variable to its Connector device without duplicating or collapsing either record.
5. Make variable dependencies searchable and understandable in the UI.
6. Add structural Hub Variable data and diagnostics to the AI-friendly export.
7. Make baseline comparison report meaningful variable and connector changes.
8. Preserve existing app/device graph semantics and conservative false-positive behavior.

## 4. Non-goals

- Changing, creating or deleting Hub Variables.
- Setting a variable or Connector value from Automation Map.
- Treating a Hub Variable as a device when no Connector exists.
- Claiming every app that uses a variable can be decoded.
- Inferring causation between two rules merely because one writes and another reads the same value.
- Exporting event history or a time series of variable values.
- Weakening the `%device%`, `%value%`, `%text%`, `%date%`, `%time%` and `%now%` token safeguards.

## 5. Terminology

| Term | Meaning |
| --- | --- |
| Defined variable | Present in an authoritative hub-level Hub Variable inventory. |
| Referenced variable | Found in a decoded app or rule configuration. |
| Connector | Virtual device linked bidirectionally to a Hub Variable by Hubitat. |
| Reader | App whose supported configuration proves that it consumes the variable. |
| Writer | App whose supported configuration proves that it can change the variable. |
| Unresolved reference | Variable name found in configuration but not matched to authoritative inventory. |
| Inventory completeness | Whether the hub-level variable inventory was obtained successfully. |
| Relationship completeness | Scope of app engines for which reads and writes can be decoded. |

## 6. Discovery contract

### 6.1 Authoritative inventory

Before implementation, identify and fixture-test a read-only hub surface that supplies, where
available:

- stable variable identifier;
- name;
- declared type;
- current value;
- Connector device identifier and Connector type;
- any platform-provided in-use metadata.

The discovery surface must be tested on the currently supported hub firmware and classified as a
documented API, supported in-process API, or undocumented internal endpoint. If no stable identifier
is exposed, the implementation may use a deterministic name-derived ID with the limitation that a
rename appears as remove plus add.

A read-only query through the separately installed community MCP Rule Server returned an inventory
shape containing `name`, `value`, `type`, `deviceId`, `attribute` and `source`, with no separate
stable variable ID. This is useful evidence that the hub data exists and that name-derived identity
may be necessary. It is not yet proof that Automation Map can reach the same data from its own app
sandbox. The sample variables had no Connectors, so the populated `deviceId` and `attribute` shapes
also remain unverified. Do not make the MCP app a runtime dependency.

The scan must record inventory status separately from relationship-decoder status:

```json
{
  "hubVariableInventory": {
    "status": "complete",
    "error": null,
    "count": 3,
    "source": "authoritative-hub-inventory"
  },
  "hubVariableRelationships": {
    "status": "partial",
    "supportedEngines": ["Rule Machine 5.1"],
    "limitations": ["Other app engines may use Hub Variables without exposing a decoded edge."]
  }
}
```

Allowed inventory statuses are `complete`, `complete-with-gaps`, `failed` and `not-supported`.

### 6.2 Relationship discovery

Keep the existing conservative Rule Machine evidence rules:

- structured variable writes produce `write` relationships;
- structured trigger, condition and Required Expression references produce proven reads;
- bounded `%Name%` parsing remains a fallback only;
- a free-text candidate is accepted only when authoritative inventory or a separate structured
  reference confirms the same name;
- reserved built-in substitutions are not manufactured as variables.

When evidence permits, a read carries a usage role:

| Usage role | Meaning |
| --- | --- |
| `trigger` | A change to the Connector or supported variable mechanism can initiate evaluation. |
| `condition` | The value participates in a condition or Required Expression. |
| `action-input` | The value is consumed while executing an action. |
| `text-substitution` | The value is inserted in supported text. |
| `unknown-read` | A read is proven, but its narrower role is not proven. |

Do not delay first-class inventory on complete role classification. `unknown-read` is preferable to
an invented role.

### 6.3 Reconciliation

- A referenced name that matches one defined variable attaches to that variable.
- A referenced name with no inventory match becomes an unresolved-reference finding, not a defined
  Hub Variable.
- A defined variable with no decoded edges remains visible as an unused-or-usage-unknown variable.
- If inventory fails, existing confirmed reference-derived nodes may still be shown, marked
  `reference-derived`, and scan completeness must expose the gap.
- Names are case-sensitive or case-insensitive only as proven by the platform. Do not choose a
  normalization rule without a fixture.

## 7. Graph model

### 7.1 Hub Variable node

Required internal fields:

```json
{
  "id": "v123",
  "group": "hubVariable",
  "name": "HouseOccupied",
  "variableType": "Boolean",
  "identitySource": "hub-inventory",
  "connectorDeviceId": "d456",
  "connectorType": "Switch"
}
```

The current runtime invariant that stored edges use the app as `from` remains unchanged unless a
separate graph-wide migration is approved. Visual direction can continue to reverse the arrowhead
for reads.

### 7.2 Relationship vocabulary

| Relationship | Stored endpoints | Visual meaning |
| --- | --- | --- |
| `write` | app to Hub Variable | App can change shared state. |
| `read` | app to Hub Variable | Shared state is consumed by app; arrow points toward app. |
| `synchronizedWith` | Hub Variable to Connector device | Connector and variable expose synchronized state. |

The `synchronizedWith` edge is structural and bidirectional in meaning, not a read, write, trigger
or action. It must not contribute to contested-device calculations or imply that the variable
commands an unrelated device.

A `write` relationship may also carry a proven source describing how the new value is obtained:

```json
{
  "kind": "deviceAttribute",
  "deviceId": "d456",
  "attribute": "temperature"
}
```

This does not create a direct device-to-variable causality edge. The rule remains the actor that
reads the device attribute and writes the variable. If the device ID cannot be joined
authoritatively, preserve the write edge but leave its structured source unresolved rather than
joining by a possibly duplicated display name.

### 7.3 Connector identity

The Connector remains in the device collection because it is a real Hubitat virtual device that
other apps may use through capabilities. The Hub Variable remains in the Hub Variable collection.
Their `synchronizedWith` relationship tells consumers that they are two interfaces to synchronized
state.

The UI should visually group or cross-link them so users do not mistake them for independent state.
Do not merge the Connector into the variable because doing so would lose real device relationships
from apps that only know the Connector.

## 8. User interface requirements

This is a bounded expansion of current entity behavior, not authorization for the paused broad UI
modernization track.

### 8.1 Map and navigation

- Keep a distinct Hub Variable shape and accessible text label.
- Include Hub Variables in search and focus controls as a first-class entity type.
- Preserve Rule to Hub Variables and Hub Variable to Rules pivot views.
- Selecting a Hub Variable focuses its readers, writers and Connector.
- Selecting a Connector identifies the linked Hub Variable.
- The legend describes a Hub Variable as hub-scoped shared state, not a Rule Machine property.

### 8.2 Detail view

Show:

- name and declared type;
- inventory/discovery status;
- Connector device and Connector type, when present;
- reading apps, grouped by proven usage role where available;
- writing apps;
- diagnostic findings and limitations;
- current value only under the policy in section 10.

### 8.3 Diagnostics

Provide neutral findings, not automatic fault claims:

| Finding | Condition | Wording intent |
| --- | --- | --- |
| No decoded readers or writers | Defined variable has no decoded relationships | May be unused, or used by an unsupported app. |
| Readers but no writer | Reads exist and no write is decoded | May be manually set, externally set or written by an unsupported app. |
| Writers but no reader | Writes exist and no read is decoded | May be externally consumed or no longer needed. |
| Multiple writers | More than one writer exists | Shared state has multiple writers; review only if behavior is unexpected. |
| Unresolved reference | Decoded reference does not match inventory | Rule may refer to a renamed/deleted variable or inventory may be incomplete. |
| Connector missing | Configuration indicates a Connector that cannot resolve to a device | Connector inventory is inconsistent or incomplete. |

Multiple writers are not automatically a race condition. Static configuration proves shared
writers, not simultaneous execution.

## 9. Baseline comparison requirements

Compare Hub Variables by stable platform ID when available. Otherwise compare by the documented
fallback identity and declare rename limitations.

Report:

- variable added or removed;
- name changed when stable ID proves continuity;
- declared type changed;
- Connector created, removed, changed or unresolved;
- reader/writer relationship added or removed;
- proven write source or source attribute changed;
- inventory completeness changed between exports.

Current value changes are excluded from the default structural comparison. A variable value is
runtime state and would make ordinary comparisons noisy.

## 10. Current-value policy

Declared type is structural and should be captured when available. Current value is transient and
may reveal occupancy, alarm, holiday, health or other sensitive household state.

Therefore:

- do not add current values to the default AI-friendly export;
- do not use current values in default baseline comparison;
- the live detail view may display the current value because the user is already inside the hub UI;
- a later explicit `Include current Hub Variable values` export option may add values, default off;
- when included, the export must state that values are a point-in-time snapshot and may change
  immediately after export.

## 11. AI-friendly export contract

### 11.1 Versioning

This expansion changes the meaning of `hubVariables` from "variables inferred from supported rule
references" to "authoritative inventory when available, reconciled with decoded references". It
also adds Connector topology and new completeness metadata. Publish it as export schema version 4,
not as an undocumented semantic change to schema 3.

Schema 3 remains readable and retains its existing meaning. Consumers must not assume schema-3
Hub Variables are a complete hub inventory.

### 11.2 Root additions

Schema 4 adds:

```json
{
  "scan": {
    "hubVariableInventory": {
      "status": "complete",
      "error": null,
      "count": 3,
      "source": "authoritative-hub-inventory"
    },
    "hubVariableRelationships": {
      "status": "partial",
      "supportedEngines": ["Rule Machine 5.1"]
    }
  },
  "summary": {
    "hubVariableCount": 3,
    "hubVariablesWithConnectorCount": 1,
    "unresolvedHubVariableReferenceCount": 0
  }
}
```

The `hubVariables` array is authoritative for the variable count. Derived counts must equal their
corresponding filtered arrays or findings.

### 11.3 Hub Variable record

```json
{
  "id": "v123",
  "name": "HouseOccupied",
  "variableType": "Boolean",
  "identitySource": "hub-inventory",
  "connector": {
    "deviceId": "d456",
    "connectorType": "Switch"
  },
  "currentValue": null
}
```

Fields:

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `id` | string | yes | Authoritative join key within this export. |
| `name` | string | yes | Household variable name. |
| `variableType` | string or null | yes | Number, Decimal, String, Boolean, DateTime, or null if unknown. |
| `identitySource` | enum | yes | `hub-inventory` or `reference-derived`. |
| `connector` | object or null | yes | Linked Connector device and type, if resolved. |
| `currentValue` | JSON scalar or null | yes | Null in the default export; populated only by explicit opt-in. |

`connector.deviceId` must resolve in `devices[].id`. The graph also carries a `synchronizedWith` edge
so general graph consumers do not need special object traversal.

### 11.4 Edge additions

Schema 4 adds:

```json
{
  "fromId": "a789",
  "toId": "v123",
  "relationship": "read",
  "usageRole": "condition",
  "writeSource": null,
  "stateful": null
}
```

- `usageRole` is required and nullable on every edge.
- It is populated only for Hub Variable reads when proven.
- `writeSource` is required and nullable on every edge.
- For a Hub Variable write sourced from a proven device attribute, `writeSource` is
  `{ "kind": "deviceAttribute", "deviceId": "d...", "attribute": "..." }`.
- A source device must resolve in `devices`; never join this field by display name alone.
- `synchronizedWith` is added to the relationship vocabulary.
- `stateful` remains meaningful only for device `action` relationships.

### 11.5 Findings

Add `insights.hubVariables`:

```json
{
  "noDecodedUsage": [],
  "readersWithoutDecodedWriter": [],
  "writersWithoutDecodedReader": [],
  "multipleWriters": [],
  "unresolvedReferences": [],
  "unresolvedConnectors": []
}
```

Each finding contains IDs and evidence, not only display names. The schema explanation and AI
guidance must state that decoder coverage is partial and these findings are investigation prompts,
not proof of defective automation.

### 11.6 Privacy note and AI guidance

Schema 4 must explicitly tell an AI:

- Hub Variable names are household data.
- Values are absent unless explicitly included.
- A variable with no decoded use may still be used by an unsupported app or external integration.
- Multiple writers do not prove a race.
- Connector and Hub Variable represent synchronized state, not two independent values.
- A device-attribute write source means the rule copies or derives its write from that attribute;
  it does not mean the device writes the Hub Variable directly.
- Conclusions must cite IDs and discovery completeness.

## 12. Failure behavior

- Inventory failure must not abort device/app scanning.
- A failed inventory does not erase reference-derived Hub Variable nodes from a prior supported
  relationship scan, but the new scan result must not claim completeness.
- A stale prior authoritative inventory must not silently masquerade as current. Either discard it
  or label it with its source scan timestamp and stale status.
- Connector resolution failure produces a finding and preserves both records where independently
  discovered.
- Export generation must distinguish `failed`, `not-supported` and genuinely empty inventory.

## 13. Acceptance criteria

1. Every authoritatively enumerated Hub Variable appears once, even with no decoded relationships.
2. Every current proven read and write edge remains present after migration.
3. Reserved Rule Machine substitutions do not become variables without authoritative confirmation.
4. Connector devices remain devices and link to exactly one matching variable when Hubitat reports
   that association.
5. Hub Variable selection exposes readers, writers, Connector and limitations.
6. Baseline comparison excludes current-value noise by default.
7. Schema-4 export validates all node joins and summary invariants.
8. Default export contains no current variable values.
9. Inventory failure is visible but does not break map generation.
10. Tests cover complete inventory, empty inventory, failed inventory, unused variable, multiple
    writers, reader-only, writer-only, unresolved reference, Connector present, Connector missing,
    device-attribute write source, duplicated source-device label, reserved-token collision and
    schema-3 compatibility.

## 14. Open evidence questions

1. Which read-only hub surface supplies the authoritative inventory on supported firmware?
2. Does it expose a stable variable ID across rename, backup/restore and reboot?
3. How does it represent each declared type and null/empty values?
4. Does it expose Connector device ID directly, and can that ID be joined reliably to the device
   inventory?
5. Are variable names case-sensitive for Rule Machine matching?
6. Which non-Rule-Machine built-in apps expose variable use in inspectable configuration?
7. Can app-level in-use metadata provide useful edges, or only an unclassified usage warning?
8. Should explicit value export be part of the first implementation or deferred entirely?

## 15. Proposed delivery order

1. Resolve and fixture the open discovery questions using read-only evidence.
2. Amend this specification with confirmed endpoint shapes and identity rules.
3. Add authoritative inventory and Connector reconciliation to the scan state.
4. Add graph nodes, `synchronizedWith` edges, focus/detail behavior and diagnostics.
5. Implement schema 4 and update `Supporting Docs/ai_export_spec.md`.
6. Update the comparator for Hub Variable structural changes.
7. Validate locally, then on a development hub under separate authorization.
8. Commit and push only after Gordon approves the tested implementation.
