# Hubitat Automation Map - Integration and Dependency Registry Method

## Purpose

Automation Map can reliably discover relationships between Hubitat automations, apps and devices, but many important dependencies sit outside Hubitat's native object graph.

Examples include:

- Philips Hue bridges
- LIFX devices and cloud metadata services
- Tuya cloud services
- Meross LAN devices
- Home Assistant
- SmartThings
- MQTT brokers
- Google Home and Alexa
- external APIs
- cloud notification services
- local bridges, gateways and controllers

The objective of the Integration and Dependency Registry is to make those otherwise invisible dependencies visible without requiring Automation Map to understand or parse the source code of every Hubitat integration.

The registry therefore acts as an architectural classification and enrichment layer between raw Hubitat discovery and graph rendering.

---

## Design Principle

The core principle is:

> Discover what Hubitat can expose directly, identify known integrations using metadata and registry fingerprints, enrich them with known architectural dependencies, and allow explicit user overrides where automatic discovery cannot prove the relationship.

The design deliberately avoids hard-coding individual integrations throughout the Automation Map application.

Instead, integration knowledge is stored in an editable, declarative registry that can evolve independently of the graph engine.

---

## Why a Registry Is Required

Hubitat normally exposes relationships such as:

```text
Rule Machine
    |
    v
Hue Motion Sensor
```

That relationship is technically correct but architecturally incomplete.

The real dependency may be:

```text
Rule Machine
    |
    v
Hue Motion Sensor
    |
    v
CoCoHue
    |
    v
Philips Hue Bridge
```

Similarly:

```text
Rule Machine
    |
    v
Tuya Device
    |
    v
Tuya Integration
    |
    v
Tuya Cloud
```

The difference matters operationally.

A Hubitat device may appear healthy in the local object model while the automation still depends on:

- Internet access
- an external cloud platform
- a LAN bridge
- an MQTT broker
- Home Assistant
- SmartThings
- an API gateway
- authentication services
- discovery services

Automation Map should answer:

> What has to be working for this automation to work?

rather than only:

> Which Hubitat objects reference one another?

---

## Discovery Method

The recommended discovery pipeline is hierarchical.

```text
1. Hubitat native metadata
        |
        v
2. Hubitat application/device relationships
        |
        v
3. HPM package metadata
        |
        v
4. Dependency Registry exact matches
        |
        v
5. Dependency Registry heuristic matches
        |
        v
6. Device/app metadata enrichment
        |
        v
7. User-defined mappings and overrides
        |
        v
8. Optional network/source-code enrichment
```

Each stage adds information while preserving the relationships discovered by earlier stages.

---

## 1. Hubitat Native Metadata

Automation Map should first use the information that Hubitat exposes directly.

Useful attributes include:

- application name
- parent application
- device name
- driver name
- driver namespace
- device network ID
- device data
- parent/child relationships
- selected devices used by applications
- Hub Mesh information
- native application identity

These relationships should always take precedence over heuristic inference.

---

## 2. Parent and Child Relationships

Parent-child relationships are one of the strongest indicators of integration ownership.

For example:

```text
CoCoHue
    |
    +-- Hue Motion Sensor
    +-- Hue Bulb
    +-- Hue Group
```

If a device is a child of CoCoHue, Automation Map can classify it with high confidence without examining the application's source code.

This should be preferred over weaker signals such as device labels.

---

## 3. HPM Metadata

Hubitat Package Manager is valuable because it already maintains package metadata for a large part of the Hubitat community ecosystem.

HPM metadata can identify:

- package name
- author
- application names
- driver names
- namespaces
- source locations
- package relationships

HPM therefore helps answer:

> What software is installed?

However, HPM does not necessarily describe:

> What external infrastructure does that software depend upon?

For example, HPM may identify a Broadlink package, but it does not necessarily describe the architecture:

```text
Hubitat
   |
Broadlink Integration
   |
LAN
   |
Broadlink RM4
   |
IR
   |
Television
```

Therefore:

> HPM identifies the software. The Dependency Registry describes the architecture.

---

## 4. Declarative Matching Rules

Registry entries should use declarative matching rather than executable Groovy code.

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
    },
    {
      "field": "namespace",
      "operator": "contains",
      "value": "cocohue",
      "confidence": 95
    }
  ]
}
```

Typical matcher fields should include:

- `appName`
- `parentAppName`
- `driverName`
- `namespace`
- `deviceName`
- `deviceNetworkId`
- `deviceMetadata`
- `HpmPackage`
- `userMapping`

Typical operators should include:

- `equals`
- `contains`
- `startsWith`
- `endsWith`
- `matches`
- `exists`

This keeps the registry editable, safe and portable.

---

## 5. Confidence Model

Not every match is equally reliable.

Automation Map should attach a confidence value to each inferred relationship.

Suggested interpretation:

| Confidence | Meaning |
|---|---|
| 100% | Explicit application or parent-child relationship |
| 95-99% | Strong namespace or driver fingerprint |
| 85-94% | Strong name-based inference |
| 70-84% | Multiple supporting heuristics |
| 50-69% | Weak or ambiguous inference |
| User override | Explicitly confirmed by the user |

Manual mappings should take precedence over inferred mappings.

The UI should distinguish between:

```text
Confirmed
Inferred
User-defined
Unclassified
```

---

## 6. Multiple Dependencies Per Integration

A single integration can depend on more than one external system.

This is why a simple `externalSystem` field is insufficient.

The registry should support:

```text
dependencies[]
```

Example - LIFX Light Manager:

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

The cloud dependency is required for discovery and metadata enrichment but not necessarily for normal runtime control.

That distinction is operationally important.

---

## 7. Dependency Taxonomy

Dependencies should be classified rather than treated as generic external systems.

Recommended node classes include:

| Class | Meaning |
|---|---|
| `INTEGRATION` | Hubitat software connecting another system |
| `INTEGRATION_VARIANT` | A specific transport or architecture variant |
| `GATEWAY` | Exposes Hubitat to another service |
| `LOCAL_BRIDGE` | Physical LAN controller or bridge |
| `LOCAL_DEVICE` | Directly controlled LAN endpoint |
| `LOCAL_SERVICE` | Service hosted on the local network |
| `EXTERNAL_SERVICE` | Internet-hosted API or platform |
| `EXTERNAL_PLATFORM` | Home Assistant, SmartThings, Google Home, etc. |
| `INFRASTRUCTURE` | MQTT broker, mDNS, network dependency |
| `DISCOVERY` | Discovery or inventory service |
| `AUTOMATION_ENGINE` | webCoRE or similar logic engine |
| `UNKNOWN_EXTERNAL` | External dependency not yet classified |

This prevents very different architectural objects from being represented as equivalent nodes.

---

## 8. Typed Graph Edges

Relationships should also be typed.

Recommended edge types include:

| Edge | Example |
|---|---|
| `OWNS` | Integration owns child device |
| `CREATES` | Application creates a virtual device |
| `CONTROLS` | Integration controls physical endpoint |
| `BRIDGES_TO` | CoCoHue bridges to Hue Bridge |
| `DEPENDS_ON` | Tuya integration depends on Tuya Cloud |
| `READS_FROM` | Weather app reads BOM feed |
| `SENDS_TO` | Notification gateway sends to Apps Script |
| `EXPOSES_TO` | Maker API exposes devices to Home Assistant |
| `AUTHENTICATES_WITH` | Meross app authenticates with Meross Cloud |
| `OBSERVES` | mDNS discovery observes LAN discovery cache |
| `CONSUMES` | Presence Manager consumes presence evidence |
| `MONITORS` | Device monitor watches a sensor or endpoint |

Typed edges allow the map to describe both topology and behaviour.

---

## 9. Runtime Criticality

Dependencies should record whether they are required during normal operation.

Recommended values:

| Criticality | Meaning |
|---|---|
| `RUNTIME` | Required for normal operation |
| `MANAGEMENT` | Required for configuration or enrichment |
| `SETUP_ONLY` | Required during initial setup/authentication |
| `DISCOVERY_ONLY` | Required only for discovery |

Example - Meross:

```text
Meross Cloud
    |
authentication
SETUP_ONLY
    |
    v
Meross Integration
    |
LAN control
RUNTIME
    |
    v
MSG100
```

If the Meross cloud is unavailable after setup, local control may continue.

That is a materially different failure mode from Tuya Cloud, where cloud access may be a runtime dependency.

---

## 10. Integration Variants

Vendor identity alone must not determine dependency architecture.

Many ecosystems have multiple integration patterns.

Examples:

```text
Tuya
 ├─ Tuya Cloud
 ├─ Tuya Local
 └─ Tuya Zigbee
```

```text
Shelly
 ├─ Direct LAN
 ├─ HTTP/RPC
 └─ MQTT
```

```text
Home Assistant
 ├─ Home Assistant Device Bridge
 ├─ Maker API
 └─ MQTT
```

```text
MQTT
 ├─ Hub-local broker
 └─ External broker
```

The registry therefore needs separate variants where the transport or failure domain differs.

---

## 11. User Overrides

Some relationships cannot be discovered reliably.

Maker API is the classic example.

Hubitat may expose:

```text
Devices
   |
Maker API
   |
External Consumer
```

but Hubitat may not know whether the consumer is:

- Home Assistant
- Homebridge
- Node-RED
- SmartThings
- an MCP server
- a custom application

Automation Map should therefore allow the user to map:

```text
Maker API instance -> Home Assistant
```

or:

```text
MQTT Broker -> Home Assistant
```

User overrides should be treated as authoritative.

---

## 12. Registry Precedence

The effective registry should be composed from several layers.

```text
Factory Registry
      +
Community/Imported Registry
      +
User Registry
      +
Per-device/App Overrides
      =
Effective Dependency Registry
```

Recommended precedence:

```text
1. Explicit user override
2. Exact runtime relationship
3. Exact registry match
4. HPM-supported match
5. Heuristic registry match
6. Unknown/unclassified
```

Factory definitions should not be directly modified.

Instead, users should be able to:

- disable a definition
- clone it
- modify the clone
- add new definitions
- override a specific app or device

This preserves upgradeability.

---

## 13. Import and Export

The registry should be serialisable as JSON.

Benefits include:

- independent updates from Automation Map releases
- community-contributed integration definitions
- simple backup and restore
- sharing known integration fingerprints
- testing outside Hubitat
- version-controlled registry evolution

CSV can also be provided as a human-review format, but JSON should remain the canonical runtime structure.

---

## 14. Optional Source-Code Analysis

Source-code inspection should not be the primary discovery mechanism.

Reasons include:

- source code may not be available
- built-in Hubitat applications are not inspectable
- integrations may be installed manually
- APIs and transports may be dynamically configured
- source parsing creates significant complexity
- code structure does not always equal runtime topology

Source inspection can still be useful as optional enrichment for:

- endpoint discovery
- known API hosts
- transport hints
- authentication requirements
- child-device creation logic

But the registry should work without it.

---

## 15. Optional Network Enrichment

Automation Map may optionally enrich registry results with local network information.

Possible sources include:

- mDNS
- SSDP
- known bridge IP addresses
- driver/device metadata
- router inventory
- MAC addresses
- Hubitat discovery cache

Example:

```text
CoCoHue
   |
   v
Philips Hue Bridge
192.168.1.42
```

Network enrichment should remain optional because several Hubitat discovery mechanisms are undocumented or implementation-specific.

---

## 16. Recommended Runtime Flow

A practical implementation sequence is:

```text
Scan Hubitat applications and devices
        |
        v
Build native dependency graph
        |
        v
Resolve parent-child ownership
        |
        v
Correlate HPM packages where available
        |
        v
Run registry exact-match rules
        |
        v
Run heuristic match rules
        |
        v
Create typed integration/dependency nodes
        |
        v
Apply user overrides
        |
        v
Optionally enrich with network/source metadata
        |
        v
Render effective architecture map
```

---

## 17. Design Rationale

This method was selected because it balances four competing requirements.

### Coverage

The Hubitat ecosystem is too broad to hard-code every integration inside Automation Map.

A registry allows the application to recognise dozens or hundreds of integrations without modifying core graph logic.

### Accuracy

Native relationships and parent-child ownership remain authoritative.

Heuristics are only used when stronger signals are unavailable.

### Extensibility

New integrations can be added by updating registry data rather than releasing new Automation Map code.

### Operational Value

Typed dependencies and runtime criticality allow Automation Map to expose real failure domains.

For example:

```text
Automation
   |
   v
LIFX Device
   |
   v
LIFX Light Manager
   |
   +---- LIFX Cloud       [MANAGEMENT]
   |
   +---- LAN / Wi-Fi      [RUNTIME]
```

This provides substantially more useful information than a conventional Hubitat device-reference map.

---

## Final Architecture

The recommended architecture is:

```text
Hubitat Native Discovery
        +
HPM Package Identification
        +
Editable Dependency Registry
        +
Confidence-Based Matching
        +
User Overrides
        +
Optional Network/Source Enrichment
        =
Automation Map Architectural Dependency Graph
```

The key design decision is that Automation Map does not need to understand every integration's source code.

It only needs enough reliable metadata to identify the integration, after which the editable registry supplies the architectural knowledge required to represent external dependencies correctly.
