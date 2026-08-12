# Hubitat Automation Map Registry Pack

## 1. Purpose

This package provides the data model and seed registries for extending Hubitat Automation Map beyond native Hubitat object references.

The goal is to let Automation Map answer:

> What has to be working for this automation to work?

rather than only:

> Which Hubitat apps, rules and devices reference one another?

Hubitat can expose many internal relationships directly, but significant dependencies often sit outside the native graph. Examples include Hue bridges, LIFX LAN devices, Tuya cloud services, Meross devices, Home Assistant, SmartThings, MQTT brokers, Google Home, Alexa, external APIs, RTSP cameras, UniFi controllers and other local or cloud services.

The registry pack therefore enriches the native Hubitat graph with architectural knowledge that cannot always be discovered automatically.

---

## 2. Package Structure

The package intentionally separates several concerns.

### Canonical/current working files

- `hubitat_automation_map_app_integration_registry_v0.3.json`
  - Current application/integration registry.
  - Contains native Hubitat automations, apps, integrations, community integrations, gateways and architectural dependencies.
  - This is the preferred starting point for application/integration classification.

- `hubitat_automation_map_device_driver_registry_v0.1.json`
  - Device and driver registry schema plus initial seed data.
  - Intended to hold official Hubitat-compatible physical devices, recommended drivers, protocol information and runtime fingerprints.

- `hubitat_automation_map_device_driver_registry_v0.1.csv`
  - Human-readable/editable representation of the device registry seed.

- `hubitat_automation_map_registry_pack_manifest_v0.3.json`
  - Describes how the registries combine to form the effective Automation Map topology.

- `harvest_hubitat_compatible_devices.py`
  - Harvester for the dynamically rendered official Hubitat compatible-device catalogue.
  - Intended to refresh the physical-device registry from the current Hubitat documentation rather than hard-coding a stale list.

### Earlier/reference artefacts

- `hubitat_automation_map_dependency_registry_v0.1.json`
- `hubitat_automation_map_dependency_registry_v0.1.csv`
- `hubitat_automation_map_dependency_registry_v0.2.json`
- `hubitat_automation_map_registry_method_and_rationale.md`

These retain earlier design iterations and are useful for change tracking.

### User-attached copies

The package also contains the supplied files exactly as attached:

- `hubitat_automation_map_dependency_registry_v0.1(1).csv`
- `hubitat_automation_map_dependency_registry_v0.2(2).json`
- `hubitat_automation_map_registry_method_and_rationale(2).md`

These are preserved deliberately for provenance and comparison.

---

## 3. Core Architectural Logic

Automation Map should use a layered discovery and enrichment pipeline.

```text
Hubitat runtime objects
        |
        v
Native Hubitat relationships
        |
        v
Parent/child ownership
        |
        v
HPM package identification
        |
        v
Exact registry matching
        |
        v
Heuristic registry matching
        |
        v
Device/driver enrichment
        |
        v
Protocol dependency enrichment
        |
        v
User overrides
        |
        v
Optional network/source enrichment
        |
        v
Effective architectural dependency graph
```

The principle is simple:

1. Trust what Hubitat exposes directly.
2. Use registries to classify what Hubitat cannot describe architecturally.
3. Use heuristics only when stronger signals are unavailable.
4. Allow users to override ambiguous cases.
5. Preserve the distinction between confirmed, inferred and user-defined relationships.

---

## 4. Why the Registry Is Editable

The Hubitat ecosystem is too broad and changes too quickly to hard-code integration knowledge throughout Automation Map.

An editable registry provides several advantages:

- new integrations can be added without changing graph-engine code;
- existing fingerprints can be corrected without waiting for a new Automation Map release;
- Hubitat-native, community and user-defined definitions can coexist;
- transport variants can be modelled independently;
- cloud, LAN, bridge and broker dependencies can be represented accurately;
- registry updates can be version-controlled and reviewed separately from application code.

The registry should therefore be treated as data, not executable logic.

---

## 5. Matching Strategy

Each registry entry contains one or more declarative match rules.

Typical fields include:

- `appName`
- `parentAppName`
- `driverName`
- `namespace`
- `deviceName`
- `deviceNetworkId`
- `deviceMetadata`
- `HpmPackage`
- `userMapping`

Typical operators include:

- `equals`
- `contains`
- `startsWith`
- `endsWith`
- `matches`
- `exists`

Example:

```json
{
  "id": "cocohue",
  "name": "CoCoHue",
  "matchMode": "ANY",
  "matchRules": [
    {
      "field": "appName",
      "operator": "contains",
      "value": "CoCoHue",
      "confidence": 100
    },
    {
      "field": "driverName",
      "operator": "contains",
      "value": "CoCoHue",
      "confidence": 95
    }
  ]
}
```

Declarative matching is preferred to embedded Groovy because it is safer, portable, editable and easier to maintain.

---

## 6. Matching Precedence

Recommended precedence:

```text
1. Explicit user override
2. Exact runtime relationship
3. Exact registry match
4. HPM-supported match
5. Device/driver fingerprint match
6. Heuristic registry match
7. Generic driver fallback
8. Unknown/unclassified
```

Native parent/child ownership is particularly strong evidence and should normally outrank name-based heuristics.

---

## 7. Confidence Model

Not every inferred relationship is equally reliable.

Recommended interpretation:

| Confidence | Meaning |
|---|---|
| 100% | Explicit Hubitat app or parent-child relationship |
| 95-99% | Strong namespace, package or driver fingerprint |
| 85-94% | Strong name-based inference |
| 70-84% | Multiple supporting heuristic signals |
| 50-69% | Weak or ambiguous inference |
| User override | Explicitly confirmed by the user |

The UI should distinguish at least:

- Confirmed
- Inferred
- User-defined
- Unclassified

This prevents an inferred dependency from being presented as fact.

---

## 8. Application and Integration Registry

The app/integration registry represents Hubitat software components and external integration paths.

It should contain:

- Hubitat-native automations
- Hubitat-native apps
- Hubitat-native integrations
- legacy built-in apps where still relevant
- HPM/community integrations
- external-service gateways
- automation engines
- discovery components
- integration variants

Examples include:

- Rule Machine
- Basic Rules
- Visual Rules Builder
- Room Lighting
- Hubitat Safety Monitor
- Mode Manager
- Maker API
- Hub Mesh
- Hue Bridge Integration
- LIFX Integration
- CoCoHue
- Tuya Cloud
- Tuya Local
- Meross
- Home Assistant Device Bridge
- MQTT
- UniFi
- Google Home
- Alexa

Native Hubitat apps remain first-class graph nodes even when they introduce no external dependency.

---

## 9. Device and Driver Registry

The physical device registry is a separate layer.

It answers:

> What actual device is this and how is it connected?

Typical fields include:

- manufacturer
- product name
- model
- protocol
- recommended Hubitat driver
- supported hub models
- runtime fingerprint
- source/provenance

The official Hubitat compatible-device catalogue should be the authority for `HUBITAT_NATIVE` device support.

Community drivers should be maintained in a separate overlay and must not be represented as official Hubitat-native support.

---

## 10. Protocol Enrichment

Directly paired devices introduce protocol-level dependencies.

Examples:

```text
Native Zigbee Device
    |
    v
Hubitat Zigbee Radio
```

```text
Native Z-Wave Device
    |
    v
Hubitat Z-Wave Radio
```

```text
Matter over Thread Device
    |
    +-- Hubitat Matter Controller
    |
    +-- Thread Border Router
```

```text
LAN Device
    |
    v
Local Network
```

This distinguishes a directly paired Zigbee device from a Hue Zigbee device that is actually dependent on:

```text
Hubitat
  |
CoCoHue
  |
Hue Bridge
  |
Hue Zigbee Mesh
  |
Hue Device
```

---

## 11. Multiple Dependencies

One integration may depend on several systems with different purposes.

Example:

```text
                 LIFX Cloud
                     |
              metadata/discovery
                     |
                     v
            LIFX Light Manager
                     |
              LAN UDP control
                     |
                     v
                LIFX Bulbs
```

The cloud dependency may be needed for configuration or enrichment while LAN connectivity is required for runtime control.

The schema therefore uses `dependencies[]` rather than a single `externalSystem` property.

---

## 12. Typed Nodes

Recommended node classes include:

- `INTEGRATION`
- `INTEGRATION_VARIANT`
- `INTEGRATION_MAPPING`
- `GATEWAY`
- `AUTOMATION_ENGINE`
- `DISCOVERY`
- `LOCAL_BRIDGE`
- `LOCAL_DEVICE`
- `LOCAL_SERVICE`
- `EXTERNAL_SERVICE`
- `EXTERNAL_PLATFORM`
- `INFRASTRUCTURE`
- `UNKNOWN_EXTERNAL`

Different node classes should render differently so the graph makes failure domains visible.

---

## 13. Typed Edges

Recommended edge types include:

- `OWNS`
- `CREATES`
- `CONTROLS`
- `BRIDGES_TO`
- `DEPENDS_ON`
- `READS_FROM`
- `SENDS_TO`
- `EXPOSES_TO`
- `AUTHENTICATES_WITH`
- `OBSERVES`
- `CONSUMES`
- `MONITORS`

Example:

```text
Meross Setup App
    |
AUTHENTICATES_WITH
    |
Meross Cloud
```

versus:

```text
Meross Driver
    |
CONTROLS
    |
MSG100
```

The distinction matters because one is a setup-time dependency and the other is a runtime dependency.

---

## 14. Runtime Criticality

Dependencies should record when they are required.

Recommended values:

- `RUNTIME`
- `MANAGEMENT`
- `SETUP_ONLY`
- `DISCOVERY_ONLY`

This allows Automation Map to distinguish outage impact.

For example:

```text
Meross Cloud          [SETUP_ONLY]
Meross LAN Device     [RUNTIME]
```

If the cloud service is unavailable after configuration, local garage-door control may continue.

---

## 15. Integration Variants

A vendor name must not imply one fixed architecture.

Examples:

```text
Tuya
 ├─ Cloud
 ├─ Local LAN
 └─ Zigbee
```

```text
Shelly
 ├─ Direct LAN
 ├─ HTTP/RPC
 └─ MQTT
```

```text
Home Assistant
 ├─ Device Bridge
 ├─ Maker API
 └─ MQTT
```

```text
MQTT
 ├─ Hub-local broker
 └─ External broker
```

Variants should be modelled separately whenever their transport or failure domain differs.

---

## 16. HPM Role

Hubitat Package Manager is valuable for identifying installed community software.

It can help establish:

- package name
- package author
- app names
- driver names
- namespaces
- source locations

However:

> HPM identifies the software. The dependency registry describes its architecture.

HPM alone cannot reliably tell whether an integration is LAN-based, cloud-based, broker-dependent, setup-only cloud-dependent, or multi-hop.

---

## 17. User Overrides

Some external relationships are intrinsically undiscoverable from Hubitat alone.

Maker API is a common example.

Hubitat may know:

```text
Devices
   |
Maker API
   |
External Consumer
```

but not whether the consumer is:

- Home Assistant
- Homebridge
- Node-RED
- SmartThings
- an MCP server
- a custom application

Automation Map should therefore allow explicit instance-level mappings such as:

```text
Maker API instance -> Home Assistant
```

User mappings must override inferred mappings.

---

## 18. Registry Layers and Override Model

Recommended composition:

```text
Factory Registry
      +
Imported/Community Registry
      +
User Registry
      +
Per-app/Per-device Overrides
      =
Effective Registry
```

Factory definitions should not be directly edited.

Users should instead be able to:

- disable a factory entry;
- clone it;
- modify the clone;
- add a new definition;
- override a specific app, driver or device;
- mark a false positive;
- resolve an unknown integration manually.

This makes upgrades safe.

---

# Maintenance Method

## 19. Maintenance Principles

Registry maintenance should be treated like maintaining a small compatibility database.

The priorities are:

1. authoritative sources first;
2. preserve provenance;
3. separate official from community data;
4. never overwrite user overrides;
5. version every registry release;
6. validate before promotion;
7. keep uncertain entries explicitly marked as uncertain.

---

## 20. Source Hierarchy

Recommended authority order:

### Tier 1 - Hubitat official sources

Use for:

- built-in apps
- built-in automations
- built-in integrations
- compatible devices
- built-in driver recommendations
- protocol/platform changes

Primary source:

- Hubitat documentation

### Tier 2 - Hubitat Package Manager

Use for:

- community package identity
- package authors
- app/driver names
- namespaces
- source repositories

HPM should not by itself define architecture.

### Tier 3 - Hubitat Community forum

Use for:

- integration release threads
- transport behaviour
- cloud/LAN distinctions
- known limitations
- deprecations
- replacement integrations
- runtime behaviour not captured in HPM metadata

### Tier 4 - Public source repositories

Use for:

- architecture confirmation
- API endpoints
- transport details
- parent/child structures
- runtime versus setup dependencies
- namespaces and driver names

### Tier 5 - User overrides

Use where runtime architecture cannot be established automatically.

---

## 21. Updating Built-in Hubitat Apps and Integrations

Recommended process:

1. Review the current Hubitat Apps/Integrations documentation.
2. Compare the documented catalogue with the existing registry.
3. Add new built-in items with:
   - `origin = HUBITAT_NATIVE`
   - an official verification status
   - exact-name match rules where possible.
4. Mark deprecated or legacy apps rather than deleting them immediately.
5. Record the Hubitat platform version when an app first appears or disappears if known.
6. Test the match against a real hub before marking the definition canonical.

Built-in app identity should normally receive confidence 100.

---

## 22. Updating the Official Device Registry

The compatible-device page is dynamically rendered and does not currently expose a documented machine-readable feed.

The included:

`harvest_hubitat_compatible_devices.py`

is intended to produce a reviewable snapshot.

Recommended refresh process:

```text
Run harvester
    |
    v
Produce raw snapshot
    |
    v
Diff against current registry
    |
    v
Review additions/removals/changes
    |
    v
Normalise manufacturer/model/driver/protocol
    |
    v
Validate representative entries
    |
    v
Promote to next registry version
```

Do not automatically publish harvested data without review.

The harvester deliberately preserves raw source-row content so a Hubitat page-layout change does not silently corrupt the registry.

---

## 23. Updating Community Integrations

For each community integration:

1. Identify the HPM package where available.
2. Record:
   - package name
   - app names
   - driver names
   - namespace
   - source repository.
3. Review the Hubitat Community release thread.
4. Review the repository/README if necessary.
5. Determine:
   - local versus cloud
   - inbound versus outbound
   - bridge/broker/API dependency
   - runtime versus setup dependency
   - child-device ownership
   - supported variants.
6. Add declarative fingerprints.
7. Assign confidence according to evidence quality.
8. Mark the provenance and verification status.

---

## 24. Versioning

Use semantic-style registry versions independently from Automation Map releases.

Example:

```text
App/Integration Registry
v0.3 -> v0.4 -> v0.5

Device Registry
v0.1 -> v0.2
```

Suggested change interpretation:

- PATCH: corrected fingerprints, metadata or descriptions
- MINOR: new integrations/devices or additive schema fields
- MAJOR: incompatible schema change

Every registry should contain:

- schema version
- registry version
- generated/updated date
- source/provenance metadata

---

## 25. Validation

Before a registry version is promoted:

### Structural validation

Check:

- unique IDs
- valid node classes
- valid edge types
- valid criticality values
- required fields
- valid match operators
- no malformed JSON

### Semantic validation

Check:

- native items are actually native
- community items are not labelled native
- cloud dependencies are not mislabelled LAN
- setup-only dependencies are not marked runtime
- variants are separate where architectures differ
- parent-child ownership is correctly represented

### Runtime validation

Where possible, test against real Hubitat runtime metadata.

Collect:

- app name
- parent app
- driver name
- namespace
- device network ID
- device data
- HPM package identity

Then confirm the highest-confidence rule selects the expected registry entry.

---

## 26. Handling Conflicts

If multiple registry definitions match:

1. prefer explicit user override;
2. prefer exact parent/app relationship;
3. prefer exact HPM/package identity;
4. prefer exact namespace;
5. prefer exact driver;
6. prefer exact name;
7. score remaining heuristic evidence;
8. if ambiguity remains, classify as unresolved rather than guessing.

Automation Map should be capable of displaying:

```text
Possible matches:
- Tuya Cloud Integration 82%
- Tuya Local Integration 76%

Status: Requires confirmation
```

---

## 27. Deprecation

Do not immediately delete old registry entries.

Instead use lifecycle states such as:

- `ACTIVE`
- `LEGACY`
- `DEPRECATED`
- `SUPERSEDED`
- `REMOVED_FROM_CURRENT_HUBITAT`
- `NEEDS_RUNTIME_VALIDATION`

Retaining old definitions matters because many Hubitat users run older integrations for years.

---

## 28. Registry Distribution

Preferred canonical format:

- JSON

Optional review/maintenance format:

- CSV

The JSON registry can be:

- bundled with Automation Map;
- downloaded from a maintained repository;
- imported manually;
- updated independently from application code.

A future community registry could be maintained in GitHub and versioned independently.

---

## 29. Security and Safety

Registry data must remain declarative.

Do not allow registry entries to execute arbitrary Groovy or JavaScript.

Allowed operations should be limited to controlled match operators and data fields.

Imported registries should be schema-validated before activation.

Remote registry updates should be:

- explicitly versioned;
- checksum-verifiable;
- reviewable before activation;
- incapable of overriding local user mappings unless the user chooses to do so.

---

## 30. Recommended Automation Map Runtime Sequence

```text
1. Discover Hubitat apps and devices
2. Build native graph
3. Resolve parent/child ownership
4. Identify HPM packages
5. Match app/integration registry
6. Match device/driver registry
7. Add protocol dependencies
8. Add external dependency nodes
9. Apply confidence and provenance
10. Apply user overrides
11. Optionally enrich network data
12. Render the effective topology
```

---

## 31. Final Maintenance Philosophy

The registries should not attempt to make Automation Map omniscient.

They should provide a controlled way to combine:

- what Hubitat explicitly knows;
- what official Hubitat documentation states;
- what HPM identifies;
- what community integrations publish;
- what runtime metadata strongly implies;
- what the user explicitly confirms.

The result is an extensible architectural model that can improve over time without making the Automation Map codebase a collection of vendor-specific special cases.

The long-term objective is:

```text
Native Hubitat Graph
        +
Curated Registry Knowledge
        +
Runtime Evidence
        +
User Truth
        =
Useful Home Automation Architecture Map
```
