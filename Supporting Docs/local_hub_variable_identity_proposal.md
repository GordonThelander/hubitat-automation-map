# Local, Hub, and Connector Variable Identity Proposal

**Status:** Implemented (Gate C, v2.1.4) and extended (v2.1.6) - completed and verified on Dev,
pending production release. See BACKLOG.md's Hold/closed entry for the current summary.
**Date:** 2026-08-28 (original proposal)  
**Scope:** Rule Machine variable decoding, graph identity, pivots, rule detail, diagnostics, baseline
comparison, and AI-friendly export

## 1. Problem

Hubitat exposes three related but different concepts:

1. A **Rule Machine Local Variable** belongs to one rule.
2. A **Hub Variable** is shared across the hub.
3. A **Variable Connector** is a virtual device synchronized with one Hub Variable.

Hubitat's public documentation explains their user-facing semantics, but Rule Machine stores and
references them through internal structures that Automation Map has had to establish empirically.
Variable references can also contain historical spelling artifacts, while free-text `%Name%`
substitution overlaps with Rule Machine built-in tokens.

The current decoder treats structured Rule Machine fields such as `xVarV.<n>`, `xVar_<n>`, and
`xVar<n>` as Hub Variable references. That is correct for the Hub Variable fixtures already tested,
but a current fixture has not proven how those fields distinguish a same-named Local Variable.

Name equality must not cause Automation Map to:

- turn a Local Variable into a Hub Variable;
- attach a rule-local read or write to hub-wide state;
- merge same-named Local Variables owned by different rules;
- associate a Connector device with the wrong Hub Variable;
- count a Hub Variable twice because it also has a Connector;
- invent a relationship when the internal storage is ambiguous.

## 2. Existing evidence

### Confirmed in the current implementation

- `getAllGlobalVars()` provides the authoritative hub-level Hub Variable inventory available to the
  Automation Map app sandbox.
- That inventory currently supplies the variable name, runtime type and, when configured, Connector
  `deviceId` and attribute metadata. It has not supplied a separate stable Hub Variable identifier.
- A Connector remains a real device node. Its `synchronizedWith` edge is created only from the
  Hubitat-reported `deviceId`, not from a label match.
- Hub Variable reads and writes are decoded from structured Rule Machine settings, with free-text
  `%Name%` treated as weaker evidence.
- Exact inventory spelling wins. The trailing-period fallback is allowed only for one unambiguous
  inventory match.

### Not yet proven

- The complete storage shape of a rule that defines and uses a Local Variable.
- Whether a structured variable picker records an explicit local-versus-hub discriminator.
- Whether `allLocalVars` is authoritative and complete for Local Variables on the current firmware.
- Whether Local and Hub Variables with the same visible name can both be selected and distinguished in
  one rule, and how that difference appears in `appState` and `appSettings`.
- Whether Local Variable renames preserve an internal key or appear as a new owner-scoped name.

The design must not turn any of these unknowns into assumed platform facts.

## 3. Decisions

### 3.1 Scope is part of identity

Variable identity is a tuple, not a display name:

```text
Hub Variable       = (scope: hub, canonical hub identity)
Local Variable     = (scope: local, owner rule app ID, local identity)
Variable Connector = (scope: device, Hubitat device ID)
```

The internal key format may change after the fixture proves what Hubitat exposes, but scope and local
ownership must always participate in identity.

### 3.2 Names are labels, not join keys

An exact name match is supporting evidence only within a scope already established by structured
storage or authoritative inventory. It must never decide between Local and Hub scope.

If a reference could resolve to both a Local and Hub Variable and no scope discriminator is proven,
the result is `ambiguous`. Automation Map must not choose the Hub Variable merely because it appears
in `getAllGlobalVars()`.

### 3.3 Local Variables are rule-owned, not map peers

The first implementation should represent Local Variables inside their owning rule's decoded detail
and AI-export record. It should not add them to the global Hub Variable collection, Hub Variable
pivots, global insights, or whole-map graph.

This is a correctness boundary, not a permanent rejection of Local Variable visualization. A future
UI proposal may add rule-scoped child entities if users gain enough value to justify the added graph
density.

### 3.4 Connector identity remains device-ID based

A Connector is associated only through the `deviceId` reported by authoritative Hub Variable
inventory. Device label, driver name, `iconCategory`, variable name, or similar text is never enough
to create `synchronizedWith`.

The parent `Variable Connectors` device is not a per-variable Connector and must not be associated
with any one Hub Variable.

## 4. Required normalized reference model

Decode every Rule Machine variable reference into one normalized record before graph construction:

```json
{
  "name": "Example",
  "scope": "hub",
  "ownerAppId": "a1234",
  "localIdentity": null,
  "operation": "read",
  "usageRole": "condition",
  "evidence": {
    "kind": "structured-setting",
    "field": "xVar_3",
    "scopeSource": "hub-picker-discriminator"
  }
}
```

Allowed `scope` values:

| Scope | Meaning |
| --- | --- |
| `hub` | Structured evidence proves a Hub Variable reference. |
| `local` | Structured evidence proves a Local Variable owned by this rule. |
| `ambiguous` | Both scopes remain possible after all available structured evidence is applied. |
| `unresolved` | The reference is proven, but no corresponding variable definition can be resolved. |

Allowed `operation` values begin with `read` and `write`. Existing role classification can continue
to use `unknown-read` until trigger, condition, Required Expression, action input, and text
substitution roles are individually proven.

Values are not exported. Local Variable definitions and references may contain private household
information and follow the same privacy handling as Hub Variable names.

## 5. Classification algorithm

For each rule:

1. Build a rule-local inventory from the fixture-proven Local Variable storage structure. Retain the
   raw local key if one exists, visible name, declared type if available, and owner app ID.
2. Read the authoritative Hub Variable inventory independently.
3. Decode each structured variable reference without assigning scope prematurely.
4. Apply any fixture-proven scope discriminator from the stored reference.
5. If storage proves `local`, resolve only against that rule's local inventory.
6. If storage proves `hub`, resolve only against authoritative Hub Variable inventory.
7. If storage does not prove scope:
   - local match only: classify `local`;
   - Hub match only: classify `hub`;
   - both match: classify `ambiguous`;
   - neither matches: classify `unresolved`.
8. Apply punctuation or rename normalization only inside the selected scope and only when exactly one
   candidate remains.
9. Treat `%Name%` free-text interpolation as weak evidence. It may resolve only when the name and
   scope are independently established. Built-in tokens remain excluded unless authoritative
   evidence proves a real variable with that name and the reference's scope can be distinguished.

No ambiguous or unresolved reference creates a Hub Variable graph edge.

## 6. Graph, UI, and insight behaviour

### Hub Variable

- Remains a first-class `hubVariable` node.
- Receives `read` and `write` edges only from references classified `hub`.
- Appears in existing Hub Variable search, focus, pivots, comparison, and insights.

### Local Variable

- Does not create a whole-map node or Hub Variable edge in the first implementation.
- Appears in the owning rule's detail as a clearly labelled Local Variable read or write.
- Same-named Local Variables in different rules remain separate because owner app ID is part of their
  identity.
- Does not contribute to Hub Variable reader/writer or unresolved-Hub-Variable findings.

### Variable Connector

- Remains a device node with its normal app/device relationships.
- Receives one structural `synchronizedWith` edge from the Hub Variable whose authoritative inventory
  record reports its device ID.
- Does not cause an extra Hub Variable count or imply a rule read/write relationship by itself.

### Ambiguous or unresolved reference

- Appears in rule detail and diagnostics with its evidence and reason.
- Is described neutrally, not as a broken rule unless the evidence proves a deleted or invalid target.
- Never creates an inferred Hub Variable, Local Variable, or Connector relationship.

## 7. AI-friendly export

A schema bump is required if the export shape changes.

Keep existing `hubVariables[]` and `synchronizedWith` relationships unchanged. Add owner-scoped
variable evidence to the relevant `ruleFlows[]` entry:

```json
{
  "appId": "a1234",
  "localVariables": [
    {
      "identity": "local-key-or-owner-scoped-name",
      "name": "Example",
      "variableType": "String"
    }
  ],
  "variableReferences": [
    {
      "name": "Example",
      "scope": "local",
      "localIdentity": "local-key-or-owner-scoped-name",
      "operation": "write",
      "usageRole": null,
      "evidenceKind": "structured-setting"
    }
  ]
}
```

Requirements:

- never place a Local Variable in top-level `hubVariables[]`;
- never use display name alone to join export records;
- include `ownerAppId` directly or make ownership unambiguous through the containing `ruleFlows[]`
  record;
- export no current or historical variable values;
- add `ambiguousVariableReferences[]` or equivalent structured diagnostics with app ID, name,
  operation, candidate scopes, and evidence kind;
- update `recommendedAiBehaviour` to prohibit cross-scope name joins.

Baseline comparison should ignore rule-local definitions in the first implementation unless a
separate reviewed requirement establishes a useful owner-scoped comparison contract.

## 8. Required fixture matrix

Use harmless test rules and non-sensitive values. Record hub model, firmware, Rule Machine version,
app IDs, timestamp, and raw storage fields needed for the test.

| Fixture | Purpose | Expected classification |
| --- | --- | --- |
| Hub-only variable read and write | Confirm existing decoder remains valid | `hub` |
| Local-only variable read and write | Establish Local Variable storage | `local` |
| Same name exists as Local and Hub Variable in one rule | Find a scope discriminator | Separate, or `ambiguous` if none exists |
| Same Local Variable name in two rules | Prove owner-scoped identity | Two local identities |
| Hub Variable with Connector | Preserve explicit device-ID association | Hub node plus device node and one `synchronizedWith` edge |
| Local Variable matching a Connector or Hub label | Prevent label-based association | No association |
| `%Name%` referring to Local Variable | Test free-text evidence limits | Local only if independently proven |
| `%Name%` colliding with built-in token | Preserve token safeguard | Excluded or ambiguous, never guessed |
| Trailing-period or renamed variable case | Bound normalization by scope | One match inside proven scope only |
| Reference to deleted Local or Hub Variable | Test unresolved handling | `unresolved`, no invented node |

For every fixture retain:

- redacted `appState` and `appSettings` fields;
- decoded normalized reference records;
- graph nodes and edges;
- pivot output;
- rule detail output;
- AI-friendly export fragments;
- before/after counts proving no double-counting.

## 9. Implementation sequence

### Gate A: observation only

Create and capture the fixture matrix on the Dev hub. Do not change the decoder during this gate.
Document the exact Local Variable inventory and reference discriminator, or explicitly record that no
reliable discriminator exists.

### Gate B: pure classifier

Implement a small classification layer over saved fixtures. Keep extraction, classification, and
graph emission separate so identity decisions can be tested without rescanning a hub.

Required tests:

- identical names across scopes;
- identical local names across owners;
- ambiguous reference does not emit a Hub edge;
- Connector association uses device ID only;
- punctuation normalization cannot cross scope;
- existing Hub-only fixtures remain byte-for-byte equivalent where schema does not change.

### Gate C: graph and export integration

Feed only classified `hub` records into existing Hub Variable graph logic. Add owner-scoped Local
Variable evidence to rule detail/export, then verify pivots and insights remain Hub-only.

### Gate D: Dev hub verification

Deploy only after Gordon authorises the Dev test. Follow the canonical Hubitat runbook, verify exact
source identity, run a controlled scan, and compare the full fixture matrix.

### Gate E: release review

No production promotion until the fixture evidence, classifier tests, graph/export invariants,
privacy constraints, and regression results are independently confirmed.

## 10. Failure policy

If Hubitat exposes no reliable discriminator when Local and Hub Variables share a name, the correct
result is conservative ambiguity. Do not use picker label order, capitalization, punctuation,
Connector presence, rule text, or global-name existence as a tie-breaker unless a fixture proves that
specific field is authoritative.

It is better to omit one uncertain relationship and report why than to publish a convincing but false
hub-wide dependency.

## 11. Review questions

1. Does the current code contain another scope discriminator not captured here?
2. Is `allLocalVars` sufficiently stable to serve as the rule-local inventory, or does the fixture
   need to capture additional state/settings fields?
3. Should Local Variable evidence live only in `ruleFlows[]`, or is a separate top-level owner-scoped
   collection justified for AI consumers?
4. Is schema bumping required for rule-flow additions under the current export contract?
5. Are there existing tests or historical fixtures that can reduce the live fixture work without
   weakening the same-name collision proof?

