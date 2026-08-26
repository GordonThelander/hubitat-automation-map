# Automation Map AI Export Specification

**Status:** implemented contract  
**Export schema:** 3  
**First conforming app version:** Automation Map 1.9.6  
**Default filename:** `automation-map-export-YYYY-MM-DD.json`

This document specifies the JSON file produced by **AI friendly export**. The file is intended for
an AI assistant, analysis program, documentation generator, or future MCP server. It is a
static configuration snapshot, not a command interface and not live device state.

The embedded `schema` and `limitations` fields in every export remain the portable,
self-describing version of this document. If this document and an export disagree, a consumer
must follow the export's own `exportSchemaVersion` and embedded description.

## 1. Goals

The format must:

- describe the hub's known devices, installed apps, external dependencies, Hub Variables and
  relationships without exposing raw Hubitat application state;
- use stable IDs for joins because display names are not guaranteed to be unique;
- distinguish a complete scan from a failed scan or one that completed with unreadable items;
- represent uncertainty honestly instead of guessing which same-named object a flow step
  references;
- include enough orientation for an AI to interpret the file without repository access;
- remain read-only: nothing in the export authorizes or describes a device command endpoint.

## 2. Non-goals

The export does not contain:

- current device attribute values or event history;
- credentials, access tokens, raw `appSettings`, or raw application state;
- executable automation code;
- complete flow decoding for every Hubitat automation engine;
- proof that an automation behaved a certain way at runtime;
- an API key, AI provider configuration, MCP transport, or permission to control the hub.

## 3. Privacy classification

Treat the file as **sensitive household configuration data**. Device, room, rule and app names
can disclose occupancy patterns, security equipment and the physical layout of a home.

Before sharing the file, the user should review where it is going and whether that AI service
retains prompts or uploaded files. A future MCP integration must preserve this explicit user
boundary and must not silently send the export to an external model.

## 4. Versioning

Two independent versions appear at the root:

- `exportSchemaVersion` describes this external JSON contract.
- `graphSchemaVersion` describes the app's internally cached graph shape from which the export
  was built.

Consumers must branch on `exportSchemaVersion`, not `generatedBy`. A consumer that does not
support the supplied schema version must stop with a clear compatibility error rather than
silently interpreting fields using an older contract.

Within a schema version:

- new optional fields may be added;
- enum expansion should be treated as possible;
- existing field meaning, ID prefixes and null semantics must not change.

A breaking change requires a new `exportSchemaVersion`.

## 5. Root object

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `about` | string | yes | Plain-language orientation for the consumer. |
| `generatedAt` | ISO-8601 string | yes | When the browser generated this file. |
| `generatedBy` | string | yes | Automation Map version that generated it. |
| `exportSchemaVersion` | integer | yes | External export contract version; `4` as of v2.0.14 (see section 18). Schema-3 files remain valid under section 4's compatibility rule; this app no longer generates them. |
| `graphSchemaVersion` | integer | yes | Internal graph version used for the snapshot. |
| `scan` | object | yes | Provenance and completeness of the underlying scan. |
| `summary` | object | yes | Convenience counts; arrays remain authoritative. |
| `limitations` | string[] | yes | Known structural or generation-time gaps. |
| `recommendedAiBehaviour` | string[] | yes | How an AI reading this file should behave - both what it may claim (section 15) and how a response should be structured (section 15, "Response shape"). |
| `privacyNote` | string | yes | Reminder that the file contains household data. |
| `schema` | object | yes | Self-contained field explanations for an AI. |
| `devices` | object[] | yes | Known device nodes. |
| `apps` | object[] | yes | Known app/rule nodes. |
| `externalSystems` | object[] | yes | External dependency nodes drawn on the map. |
| `hubVariables` | object[] | yes | As of schema 4 (v2.0.14): the hub's own authoritative Hub Variable inventory, reconciled with decoded rule references. Under schema 3 this held only variables found via decoded rule references (see section 18). |
| `edges` | object[] | yes | Relationships between nodes. |
| `ruleFlows` | object[] | yes | Decoded rule logic where supported. |
| `insights` | object | yes | Precomputed findings. |
| `externalSystemDeclarations` | object[] or null | yes | User declarations, or `null` if the auxiliary fetch failed. |
| `deviceIconOverrides` | object[] or null | yes | Non-default icon choices/notes, or `null` if the auxiliary fetch failed. |

Unknown root fields must be ignored unless the consumer explicitly supports them.

## 6. IDs and joins

IDs are strings and are the only authoritative join keys.

| Prefix | Node type | Example |
| --- | --- | --- |
| `d` | device | `d2999` |
| `a` | app or rule | `a2276` |
| `x` | external system | implementation-generated |
| `v` | Hub Variable | implementation-generated |

Names are supplied for readability and may be duplicated or changed. Consumers must never
join objects by name when an ID is available.

The union of `devices[].id`, `apps[].id`, `externalSystems[].id` and `hubVariables[].id`
forms the node-ID namespace. IDs must be unique across that union. Every `edges[].fromId` and
`edges[].toId` must resolve within it.

## 7. Scan provenance and completeness

`scan` has this shape:

```json
{
  "lastScanCompletedAt": "2026-08-16T23:36:28.269Z",
  "lastScanError": null,
  "status": "complete",
  "appsUnreadable": 0,
  "devicesUnreadable": 0
}
```

`status` is authoritative:

- `complete`: no top-level scan failure and both unreadable counts are zero.
- `complete-with-gaps`: the scan finished, but one or more apps/devices could not be read and
  may be absent or incomplete.
- `failed`: the scan aborted; `lastScanError` should explain why. Data may reflect an older or
  unusable snapshot and must not be presented as a complete current map.

An AI should state the scan status before making claims about completeness. It must not turn
`complete-with-gaps` into “complete” or infer that an absent item does not exist.

`generatedAt` is not the scan time. It is normal for it to be later than
`scan.lastScanCompletedAt`.

## 8. Node records

### 8.1 Devices

```json
{
  "id": "d2999",
  "name": "Back Door Light",
  "room": "Back Door",
  "iconCategory": "lighting",
  "capabilities": ["Actuator", "Light", "Switch"]
}
```

- `room` may be `null`.
- `iconCategory` is a best guess and may be `unknown`.
- `capabilities` is the raw capability-name list used for classification; it may be `null` if
  the auxiliary device metadata was unavailable or raced a rescan.
- `iconCategory: "connector"` (schema 4, v2.0.14) usually means a Hub Variable Connector device -
  see section 18.1's `connector` field for how to find the variable it belongs to. **One confirmed
  exception:** Hubitat also creates its own single parent device, typically named "Variable
  Connectors," that manages every per-variable Connector on the hub. The same detection rule
  classifies it `"connector"` too, but no `hubVariables[]` entry links to it and no
  `synchronizedWith` edge names it - it is not synchronized with one specific variable. Do not
  assume every `"connector"` device resolves to exactly one `hubVariables[]` entry.

### 8.2 Apps

```json
{
  "id": "a2276",
  "name": "Kitchen Downlight App (Rule-5.1)",
  "appType": "Rule-5.1",
  "status": "active",
  "parentId": "a487",
  "childIds": [],
  "hasDecodedFlow": true
}
```

`status` is one of:

- `active`
- `paused-or-disabled`
- `inert`
- `unscanned`
- `unreadable`
- `deleted-but-referenced`

`hasDecodedFlow: false` does not mean the app is faulty. It may not be a rule, or it may use
an engine whose internal rule structure Automation Map cannot decode.

`parentId` is nullable. Parent/child relationships must be symmetric: if app A lists B in
`childIds`, B should identify A as `parentId`.

### 8.3 External systems

```json
{ "id": "x...", "name": "Example Cloud", "kind": "internet" }
```

These are dependency nodes actually drawn on the map. They may come from the shared registry
or user declarations. They are not the same collection as `externalSystemDeclarations`.

### 8.4 Hub Variables

```json
{ "id": "v...", "name": "Overloadcount" }
```

Schema-3 shape, shown for reference against existing schema-3 files. As of schema 4 (v2.0.14,
this app's current output) the record carries several more fields and the array's meaning changes
from "referenced by a decoded rule" to "the hub's own authoritative inventory, reconciled with
decoded references" - see section 18.1 for the current shape and section 7/18.2 for how to read
completeness. The schema-3 caveat below still describes what schema-3 files (not this app's
current output) guarantee: only variables discovered through supported Rule Machine decoding are
represented; absence does not prove that no rule engine uses the variable.

## 9. Edges

```json
{
  "fromId": "a2276",
  "fromName": "Kitchen Downlight App (Rule-5.1)",
  "toId": "d2999",
  "toName": "Back Door Light",
  "relationship": "action",
  "stateful": true
}
```

`fromName` and `toName` are convenience copies. Join on IDs.

Relationship vocabulary:

| Relationship | Meaning |
| --- | --- |
| `trigger` | App listens to a device event. |
| `constraint` | Device condition or required expression gates the app. |
| `monitor` | App reads device state but does not command it. |
| `action` | App can command the device. |
| `exposed` | Device is published to an external system. |
| `owns` | App created/owns the device. |
| `depends` | App depends on an external system. |
| `write` | Rule writes a Hub Variable. |
| `read` | Rule reads a Hub Variable. |
| `runs` | Rule runs another rule's actions. |
| `cancelTimedActions` | Rule cancels another rule's timed actions. |
| `setspb` | Rule changes another rule's Private Boolean. |
| `pauseResume` | Rule pauses or resumes another rule. |

`stateful` is meaningful only when `relationship` is `action`:

- `true`: the app can leave the device in a lasting state, such as on/off/level.
- `false`: the action is momentary or not classified as lasting.
- `null`: the concept does not apply to this relationship type.

## 10. Rule flows

Each record is keyed by app ID, never app name:

```json
{
  "appId": "a2988",
  "appName": "Example Rule (Rule-5.1)",
  "engine": "Rule-5.1",
  "steps": []
}
```

Step fields such as `kind`, `label`, `cond`, `ctrl`, `selfTarget` and `ruleTargets` reflect
the decoder's reconstructed control flow. Some control steps legitimately have an empty
label or condition.

The normalized `references` array is the consumer-safe way to interpret names embedded in a
flow step:

```json
{ "type": "device", "id": "d2999", "name": "Back Door Light" }
```

`type` is one of:

- `device`: uniquely resolved device ID.
- `app`: uniquely resolved app ID.
- `self`: “This Rule”; ID is the owning `appId`.
- `ambiguous`: name matched multiple nodes; `id` is `null` and `candidateIds` lists every
  candidate. Consumers must not guess.
- `unresolved`: name matched nothing; `id` is `null`. This may be stale, renamed, deleted, or
  a decoder limitation.

`ruleTargets`, when present, contains explicit `{id, name}` app references reconstructed from
Rule Machine's stored target IDs.

## 11. Insights

`insights` contains:

- `contested`: devices for which multiple apps have stateful action edges;
- `unreferencedDevices`: devices with no mapped incoming relationship;
- `inertApps`: installed apps with no device or rule relationship, including a reason;
- `brokenRuleReferences`: deleted rule targets and the rules still referencing them.

These are precomputed conveniences, not additional source facts. A conforming consumer may
recalculate them from nodes and edges and should report a mismatch as a validation warning.

## 12. Summary invariants

Every summary count must equal the corresponding array length:

- `deviceCount` = `devices.length`
- `appCount` = `apps.length`
- `externalSystemCount` = `externalSystems.length`
- `hubVariableCount` = `hubVariables.length`
- `edgeCount` = `edges.length`
- `decodedRuleFlowCount` = `ruleFlows.length`
- insight counts = their corresponding `insights` array lengths

The arrays are authoritative if a mismatch is encountered.

## 13. Auxiliary-data null semantics

`externalSystemDeclarations` and `deviceIconOverrides` distinguish these cases:

- `[]`: the endpoint was reached and there were no saved records.
- non-empty array: the endpoint was reached and returned records.
- `null`: the browser could not obtain that auxiliary dataset while exporting.

When either field is `null`, `limitations` must contain a generation-time warning. An AI must
not describe `null` as “none configured.”

## 14. Minimum consumer validation

Before analysis, a consumer should:

1. Parse the file as JSON and verify the root is an object.
2. Check that `exportSchemaVersion` is supported.
3. Report `scan.status`, unreadable counts and `limitations`.
4. Verify node IDs are unique across all node arrays.
5. Verify every edge endpoint exists.
6. Verify every `ruleFlows[].appId` exists in `apps`.
7. Verify resolved flow references exist and do not resolve ambiguous/unresolved references
   by guesswork.
8. Compare `summary` with actual array lengths.
9. Respect `null` versus empty-array semantics for auxiliary data.

## 15. Recommended AI behaviour

An AI consuming the file should:

- identify the export and scan versions first;
- distinguish observed configuration facts from inferences;
- cite node IDs alongside names when ambiguity matters;
- qualify conclusions when the scan has gaps or a relevant engine is not decoded;
- use `edges` for topology and `ruleFlows` for supported step-by-step logic;
- avoid claiming that static configuration proves runtime causality;
- highlight broken references and contested devices as findings, not automatically as errors;
- never invent a missing link solely because two names look similar.

### Tone

Found live, on a real export: a receiving AI turned `contestedDeviceCount: 30` into a menu
item reading as "devices multiple automations can leave in conflicting states" - technically
compliant with the bullet above (a finding, not an outright claim of error) but still alarming
enough that the user's actual reaction was that the hub sounded like it was in a bad state. On
that same hub, the contested devices were mostly lights with four to six perfectly ordinary
rules each (motion, time-of-day, manual override) - ordinary automation design at that scale,
not evidence of anything wrong. "Not automatically an error" was not a strong enough
instruction on its own to prevent that framing:

- **A hub with dozens of rules and hundreds of devices will always show some contested
  devices and inert apps, as a normal by-product of scale.** Do not present a raw count as
  evidence the hub is in a bad state.
- **Avoid adversarial words - fighting, broken as an unqualified judgment, conflict - for
  anything the export itself does not use that word for.** State the plain mechanism instead
  (the last app to run decides the outcome) and let the user judge whether it is intentional.
- **State a count in proportion to the whole** - "30 of 194 devices", not a bare "30 devices"
  - so the user can judge scale themselves rather than be primed by an isolated number.

### Response shape

The above governs what the AI may claim. This governs how it should structure a response,
so a first reply reads as understood and actionable rather than a report dumped at the user:

- **Open with a short plain-language summary of what was understood** - device/app counts,
  scan completeness, and two or three *specific* named apps or devices as evidence the file
  was actually read, not a templated response. This is what lets the user catch a misreading
  before it propagates into everything that follows, rather than three paragraphs in.
- **Say what was found before saying what to do about it.** Keep findings and recommendations
  in visibly separate sections rather than blended into one paragraph.
- **Surface scan-quality caveats in the summary, not buried later.** If `scan.status` is
  `complete-with-gaps`, or a finding rests on an `unresolved`/`ambiguous` flow reference, say so
  before presenting anything built on top of it, not as a footnote after the fact.
- **When more than one thing is worth pursuing, offer it as a short menu and stop** - 2 to 5
  options, one line each on why it might matter, then ask which to go into. Do not silently
  pick one and go deep unprompted - unless the user's request or the evidence itself makes the
  next investigation unambiguous, in which case proceed with it directly rather than forcing an
  unnecessary choice.
- **When the user's own request is broad or vague** ("help me with my automations"), the first
  reply should itself be that options menu - what kind of help: audit for conflicts, find
  unused capability, explain a specific rule - rather than guessing scope.
- **Every option offered must read as investigate or explain, never as an action taken or
  promised.** "Check whether this conflict is intentional" is fine; "I'll fix this rule" is
  not - nothing about this export authorises any change to the hub, and no response should
  imply otherwise.

## 16. Future MCP bridge

The export is the interchange contract for a possible MCP integration. An MCP server could
load or obtain this same schema and expose read-only resources/tools such as:

- hub summary and scan quality;
- lookup by device/app ID or name;
- neighbours and dependency paths;
- decoded rule explanation;
- contested-device and broken-reference findings.

MCP does not require the Hubitat app itself to host an AI model. A local or hosted bridge may
serve this data to an AI client, but authentication, consent, transport, refresh policy and
data retention are separate design decisions. Device-control tools through Maker API are a
different security scope and must not be implied by this read-only export specification.

## 17. Conformance evidence

The schema-3 export generated on 2026-08-17 from Automation Map 1.9.6 was validated with:

- valid JSON and expected root fields;
- `scan.status: complete`, zero unreadable apps/devices;
- summary counts matching all arrays;
- globally unique node IDs;
- every edge endpoint resolving;
- every rule flow resolving to an app;
- symmetric app parent/child relationships;
- resolved flow references and explicit rule targets pointing to valid IDs;
- auxiliary empty arrays distinguished correctly from fetch failure (`null`).

This evidence validates that sample and the implemented contract; it is not a substitute for
running the minimum consumer validation on every future export.

## 18. Schema 4 (v2.0.14) delta

Everything in sections 1-17 above describes schema 3 except where a section explicitly points
here. This section is the complete, precise delta - implemented in v2.0.14, reviewed by Codex in
`Bucket/Queue/097`, deployed to the dev hub 2026-08-26. Full design rationale is in
`Supporting Docs/hub_variable_first_class_spec.md` (the parent design) and
`Supporting Docs/hub_variable_v2014_implementation_spec.md` (this release's scope).

Not a dual-export mode: this app generates schema 4 only. Schema-3 files already on disk remain
valid and interpretable under section 4's compatibility rule - a consumer must not assume a
schema-3 `hubVariables` array is a complete inventory, since schema 3 never claimed to be one.

### 18.1 `hubVariables[]` - authoritative inventory, not just referenced variables

```json
{
  "id": "v...",
  "name": "Overloadcount",
  "variableType": "Number",
  "identitySource": "hub-inventory",
  "connector": { "deviceId": "d...", "connectorType": "Switch" },
  "currentValue": null
}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `variableType` | string or null | `Number`, `Decimal`, `String`, `Boolean`, `DateTime`, or `null` if the platform's reported type spelling was not recognized. |
| `identitySource` | enum | `hub-inventory` (confirmed against the hub's own authoritative Hub Variable list this scan) or `reference-derived` (found only via a decoded rule reference; a weaker guarantee - see section 7 for how to read `scan.hubVariableInventory.status`). |
| `connector` | object or null | `{deviceId, connectorType}` whenever the hub itself reports a linked Connector device for this variable; `null` when it reports none. **Revised from the original design:** Connector devices do not appear in the same bulk device-enumeration the rest of `devices[]` is built from (a live platform finding, not a design choice - see `Supporting Docs/hub_variable_v2014_implementation_spec.md`), so a resolvable `deviceId` is trusted directly from the hub rather than requiring independent confirmation (confirmed acceptable against live hub data - Codex review 103). `connector.deviceId` always resolves in `devices[].id`; that device entry has `iconCategory: "connector"` and, when independent discovery genuinely did not find it, `room`/`capabilities` are both `null` (same null semantics as any other device missing from that fetch - section 8.1) - otherwise its real reported data is used. `connectorType` is the device's own reported type when independent discovery did find it, otherwise the projected Connector attribute label Hubitat itself reports (observed live: `"Variable"`, `"Humidity"`) - not necessarily the underlying driver's name. There is no `unresolvedConnectors` finding (see 18.4): trusting the reported `deviceId` unconditionally means no code path can fail to resolve one. The one gap this leaves - a Connector manually deleted out from under its variable, bypassing the normal remove-connector flow - is documented as export prose in `limitations`, not as a structured field, since no code path can currently populate one. |
| `currentValue` | JSON scalar or null | Always `null` in this release. No opt-in value export exists yet. |

`identitySource: "hub-inventory"` means every variable the hub itself reports appears here, even
one no decoded rule references at all (see `insights.hubVariables.noDecodedUsage`, 18.4) - the
inverse of schema 3, where absence from any decoded reference meant absence from this array
entirely.

### 18.2 `scan` additions - inventory completeness, kept separate from relationship completeness

```json
{
  "hubVariableInventory": { "status": "complete", "error": null, "count": 3, "source": "authoritative-hub-inventory" },
  "hubVariableRelationships": { "status": "partial", "supportedEngines": ["Rule Machine 5.1"], "limitations": [ "..." ] }
}
```

`hubVariableInventory.status` is `complete`, `complete-with-gaps`, `failed`, or `not-supported` -
independent of the top-level `scan.status` above, which describes app/device scanning only. A
consumer must not assume one implies the other. When `hubVariableInventory.status` is not
`complete`, expect `identitySource: "reference-derived"` entries in `hubVariables[]` instead of
`"hub-inventory"` ones (section 18.1) - the fallback described in section 12 of the parent spec:
existing reference-derived nodes still appear, but completeness is not claimed.

`hubVariableRelationships` is unchanged in shape from what section 7's schema-3 provenance
already implied informally; it is now explicit and versioned.

### 18.3 `edges[]` additions

```json
{ "usageRole": "unknown-read", "writeSource": { "kind": "deviceAttribute", "deviceId": "d...", "attribute": "temperature" } }
```

Both fields are present (nullable) on every edge, added alongside the existing fields from
section 9.

- `usageRole` - populated only on a proven Hub Variable `read` edge. This release always emits
  `"unknown-read"`: a read was proven, but this app does not yet classify the narrower
  trigger/condition/action-input/text-substitution role. Prefer `unknown-read` over guessing - do
  not treat its presence as meaning "this edge's role is unknown/broken"; it means exactly what it
  says, a proven read whose sub-classification is not yet built.
- `writeSource` - populated only on a Hub Variable `write` edge whose source device attribute
  resolved to a real, authoritative device ID (never joined by display label - see section 6). A
  write edge with no `writeSource` may still have a device-attribute source that simply could not
  be resolved to an ID; absence is not proof the write has no such source.
- `synchronizedWith` - a new `relationship` value (joins the enum in section 9): a Hub Variable
  node to its Connector device node, structural and bidirectional in meaning. It is not a
  read/write/trigger/action and must not be counted as device control or fed into any
  action-based/contested-device calculation.

### 18.4 `insights.hubVariables` - new findings object

```json
{
  "noDecodedUsage": [ { "id": "v...", "name": "..." } ],
  "readersWithoutDecodedWriter": [ { "id": "v...", "name": "..." } ],
  "writersWithoutDecodedReader": [ { "id": "v...", "name": "..." } ],
  "multipleWriters": [ { "variable": { "id": "v...", "name": "..." }, "writers": [ { "id": "a...", "name": "..." } ] } ],
  "unresolvedReferences": [ { "name": "...", "kind": "write", "referencedBy": { "id": "a...", "name": "..." } } ]
}
```

Every finding is a neutral observation, not a fault claim (see sections 15-16's tone guidance,
which applies identically here):

- `noDecodedUsage` - no decoded reader or writer at all. May simply be unused, or used by an app
  engine this export cannot decode (section 18.2's `hubVariableRelationships.limitations`).
- `readersWithoutDecodedWriter` / `writersWithoutDecodedReader` - may be set manually, externally,
  or by an undecoded app; or no longer needed. Not evidence of misconfiguration by itself.
- `multipleWriters` - shared state with more than one writer. Static configuration proves shared
  writers, not simultaneous execution - never call this a race condition.
- `unresolvedReferences` - a proven structured reference (never an unconfirmed free-text
  candidate) to a name absent from a `complete` authoritative inventory. The rule may reference a
  renamed or deleted variable, or inventory may have been incomplete for this scan
  (`scan.hubVariableInventory.status`) - check that before concluding the reference is stale.

There is no `unresolvedConnectors` finding here, unlike the original design this schema shipped
against. **Removed after Codex review 103:** trusting a reported Connector `deviceId`
unconditionally (see the `connector` field note in 18.1) means no code path can ever fail to
resolve one, so a permanently-unreachable field was removed from the schema rather than shipped.
The one gap this trust decision leaves - a Connector deleted out from under its variable, bypassing
the normal remove-connector flow, rendering indistinguishably from a real one - is documented only
as export prose (`limitations`), not as a structured field a consumer could rely on to detect it.

### 18.5 What did not change

No baseline-comparator behaviour changed in this release. No current-value export exists.
`about`, `privacyNote`, and the rest of the root object are unchanged from section 5 except where
noted above. Section 15's recommended-AI-behaviour guidance applies to Hub Variable findings with
no modification - the Hub-Variable-specific tone notes in section 18.4 above are elaborations of
that same guidance, not exceptions to it.
