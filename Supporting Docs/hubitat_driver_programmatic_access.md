# Programmatic access to Hubitat devices and drivers

**Status:** architecture and implementation method  
**Scope:** inventory, driver identity/source, capabilities, commands, attributes, events, state variables, preferences and scheduled jobs  
**Boundary:** read-only by default; command and configuration writes are separate privileged operations

## 1. The Hubitat data model

"Read the driver" can mean several different things. They are stored and exposed differently.

| Layer | Example | Meaning |
| --- | --- | --- |
| Device instance | Device `1279`, Alarm OFF Switch | One installed device record |
| Driver type | Virtual Switch | Code/type assigned to that device |
| Capabilities | Switch, Refresh | Standard behavioural contracts declared by the driver |
| Commands | `on`, `off`, `refresh` | Callable methods exposed by capabilities or driver metadata |
| Current attributes | `switch=off` | Current event-derived public state |
| Driver preferences | `autoOff=1 second` | Configuration saved from the Preferences tab and exposed inside that driver as `settings.autoOff` |
| Driver state variables | Driver-specific map | Internal persistent runtime state, distinct from attributes |
| Device data values | model, manufacturer, firmware | Relatively static metadata maintained by the driver/hub |
| Events | switch changed to on/off | Historical attribute events and command records |
| Scheduled jobs | auto-off callback | Work scheduled by the driver instance |
| Driver source | Groovy source | Available for user drivers; built-in driver implementation is compiled and unavailable |

These layers must not be collapsed into one generic `state` object.

## 2. Where each value lives

### 2.1 Attributes

Attributes are public device state created by events, for example:

```json
{
  "name": "switch",
  "value": "off",
  "date": "2026-08-23T10:24:41+0000"
}
```

They are readable through Maker API, Hubitat apps, device pages and suitable MCP tools.

### 2.2 Preferences

A driver declares preferences in its `preferences` metadata block:

```groovy
preferences {
    input name: "autoOff", type: "enum",
          title: "Enable auto off",
          options: ["disabled": "Disabled", "1": "1s"]
}
```

The saved value is scoped to the device instance. Inside the assigned driver it is available through:

```groovy
settings.autoOff
```

Hubitat persists it in the hub's internal database. It is not an attribute and does not normally generate a device event.

The preference definition and its saved value are different facts:

- definition: name, type, title, options and default;
- saved setting: selected underlying value;
- displayed setting: user-facing option label, such as `1s`;
- effective value: saved value, or possibly a driver-defined/default UI value when no value has yet been saved.

An extractor should preserve all four when possible.

### 2.3 Driver state variables

Driver `state` is persistent internal implementation data. It is not the same as Current States and is not a supported automation interface. It may change between driver versions.

### 2.4 Device data

Device data values are metadata attached to the device record. They commonly include model, manufacturer and firmware identifiers. They are distinct from driver preferences and runtime state.

## 3. Supported access surfaces

### 3.1 Maker API

Use Maker API for externally authorised devices and routine control.

Typical operations:

```text
GET /apps/api/<maker-app-id>/devices
GET /apps/api/<maker-app-id>/devices/all
GET /apps/api/<maker-app-id>/devices/<device-id>
GET /apps/api/<maker-app-id>/devices/<device-id>/events
GET /apps/api/<maker-app-id>/devices/<device-id>/commands
GET /apps/api/<maker-app-id>/devices/<device-id>/<command>/<arguments>
```

Maker API provides:

- authorised device inventory;
- type/name/label;
- capabilities;
- commands;
- current attributes;
- recent events;
- command execution.

Maker API does not provide:

- arbitrary driver preference values;
- driver state variables;
- scheduled jobs;
- complete device dependency information;
- source for the assigned driver;
- devices not authorised to that Maker API instance.

Do not give raw Maker API URLs or tokens to an AI. Keep them in a deterministic local gateway and expose structured operations.

### 3.2 A custom Hubitat app

A Hubitat app can receive selected device objects and use supported device methods:

```groovy
def value = selectedDevice.currentValue("switch")
selectedDevice.on()
selectedDevice.off()
selectedDevice.updateSetting("somePreference", [value: "1", type: "enum"])
```

Important limitation: an arbitrary app cannot directly read another driver instance's `settings` map. Hubitat exposes `settings` to the driver that owns them, not as a general device-object read API.

Therefore, Automation Map cannot obtain every driver preference through ordinary app code simply because it has a device reference.

### 3.3 Custom driver cooperation

For a user driver under your control, provide an explicit read-only inspection mechanism.

Possible patterns:

```groovy
command "exportDiagnosticConfiguration"

Map getDiagnosticConfiguration() {
    return [
        schemaVersion: 1,
        preferences: [
            autoOff: settings.autoOff
        ],
        stateSummary: [
            lastConfigured: state.lastConfigured
        ]
    ]
}
```

Limitations:

- arbitrary apps can normally call declared commands, but commands are primarily action-oriented and external transports may not return a structured result;
- parent apps can access non-private methods on their child devices;
- built-in drivers cannot be modified to add this contract;
- never export secrets, network credentials, lock codes or tokens.

A safer convention is for a driver to publish a sanitised JSON attribute only when diagnostics are explicitly enabled. Avoid continuous large attributes because every change creates an event and consumes database space.

## 4. Authenticated admin surfaces

The Hubitat admin UI can display information that Maker API and ordinary apps cannot. This includes Preferences, State Variables, Device Data, Scheduled Jobs and driver selection.

The current UI presents device instance `1279` at:

```text
/device/edit/1279
```

The Preferences UI for Alarm OFF Switch exposes a control whose identifier is:

```html
id="autoOff"
```

with displayed value:

```text
1s
```

Hubitat also has internal JSON/admin endpoints used by its own UI and community tooling, including the established device-detail route:

```text
/device/fullJson/<device-id>
```

These endpoints are:

- local admin interfaces, not Maker API;
- authenticated according to Hub Login Security/session rules;
- undocumented and version-sensitive;
- capable of exposing more household and implementation detail;
- unsuitable for direct exposure to an AI.

Use them only behind a local adapter that validates responses and fails closed when the platform shape changes.

## 5. Driver source access

### 5.1 User drivers

User driver source is stored under Drivers Code and can be read through authenticated admin tooling. A local gateway should expose:

```text
list_driver_types(include=user)
get_driver_source(driverTypeId, offset, length)
```

Return source in chunks and identify:

- driver type ID;
- name and namespace;
- source revision or hash;
- metadata capabilities;
- preference definitions;
- commands and parameters;
- imported libraries where visible.

### 5.2 Built-in drivers

Built-in Hubitat drivers are compiled system code. Their Groovy implementation source is not available through Drivers Code.

For built-in drivers, programmatic inspection is limited to observable metadata:

- assigned driver name and system type ID;
- declared capabilities;
- commands and argument metadata;
- current attributes;
- Preferences UI definitions and values through authenticated admin inspection;
- state variables/device data shown by the admin UI;
- events, logs and scheduled jobs;
- official documentation and observed behaviour.

Do not claim to have read built-in driver source.

## 6. Recommended local gateway

```text
AI client
   |
   | structured requests only
   v
Local deterministic gateway
   +-- Maker API adapter
   |     attributes, events, commands
   |
   +-- Hub admin read adapter
   |     preferences, state variables, data, jobs,
   |     assigned type, user-driver source
   |
   +-- Automation Map adapter
   |     dependencies, app roles, rule flows
   |
   +-- policy, redaction, cache and audit log
```

The AI never receives:

- Maker API token;
- OAuth URL;
- hub administrator password/session cookie;
- raw authenticated admin endpoint;
- unredacted secrets from preferences or source.

## 7. Proposed MCP read tools

### 7.1 `hub_get_device_runtime`

Supported, stable view:

```json
{
  "deviceId": 1279,
  "displayName": "Alarm OFF Switch",
  "room": "Virtual",
  "driver": {
    "typeId": 56,
    "name": "Virtual Switch",
    "kind": "system"
  },
  "capabilities": ["Switch", "Refresh"],
  "commands": ["on", "off", "refresh"],
  "attributes": {
    "switch": {
      "value": "off",
      "date": "..."
    }
  }
}
```

### 7.2 `hub_get_device_configuration`

Authenticated admin read:

```json
{
  "deviceId": 1279,
  "driverTypeId": 56,
  "preferences": [
    {
      "name": "autoOff",
      "title": "Enable auto off",
      "type": "enum",
      "savedValue": "1",
      "displayValue": "1s",
      "defaultValue": null,
      "source": "admin-device-page"
    }
  ],
  "stateVariables": [],
  "dataValues": {},
  "scheduledJobs": [],
  "redactions": [],
  "retrievedAt": "..."
}
```

Requirements:

- read-only;
- one exact device ID;
- preference allowlist or automatic secret-name redaction;
- password inputs always omitted;
- values matching token/key/password/secret/PIN/code patterns redacted;
- response size capped;
- report unavailable fields explicitly;
- include source and platform version.

### 7.3 `hub_get_driver_definition`

```json
{
  "driverTypeId": 56,
  "name": "Virtual Switch",
  "kind": "system",
  "sourceAvailable": false,
  "capabilities": [],
  "commands": [],
  "preferenceDefinitions": [],
  "limitations": [
    "Built-in driver source is compiled and unavailable"
  ]
}
```

For user drivers, permit `includeSource=true` only with a source-size cap and secret scanning.

### 7.4 `hub_list_device_jobs`

Returns driver-scheduled jobs for one device. This is valuable for confirming whether an auto-off continuation is actually pending after `on()`.

### 7.5 Separate writes

Keep writes out of read tools:

```text
hub_call_device_command
hub_update_device_preferences
hub_change_device_driver
```

Preference updates should require:

1. exact device ID;
2. current configuration read;
3. allowlisted preference key/type;
4. validation against available options;
5. explicit confirmation;
6. audit record;
7. post-write reread.

Changing a driver is higher risk and should also require a recent backup plus an explicit `configure()` decision.

## 8. Preference extraction method

### 8.1 Preferred method

Use the authenticated structured data request made by the current Hubitat device page, if it can be identified and validated for the installed platform build.

Algorithm:

```text
authenticate locally to hub admin
request exact device configuration by numeric ID
verify device ID and driver type
extract preference definitions
extract saved/effective values
redact sensitive fields
normalise type and display value
return structured configuration
```

### 8.2 UI DOM fallback

If no stable structured response is available:

```text
open /device/edit/<id>
select Preferences
enumerate preference containers
read stable control id as preference key
read title, description, control type and selected/checked value
do not click Save
```

This works for inspection but is slower and more fragile than a JSON adapter.

For the current Alarm OFF Switch example, DOM inspection yielded:

```text
preference key: autoOff
title: Enable auto off
control: enum/dropdown
display value: 1s
```

### 8.3 `/device/fullJson` fallback

Community apps commonly request the hub-local route:

```groovy
httpGet([
    uri: "http://127.0.0.1:8080",
    path: "/device/fullJson/${deviceId}",
    headers: [Accept: "application/json"]
]) { response ->
    Map body = response.data as Map
}
```

Treat returned `settings` as best effort. Validate every expected field, because contents can vary by platform version and driver type. Never rely on it as a public compatibility contract.

## 9. Cache design

Use separate caches because these values change at different rates.

| Cache | Suggested lifetime | Invalidate when |
| --- | --- | --- |
| Device inventory/room/type | 5 to 15 minutes | rescan, device update, unknown ID |
| Capabilities/commands | 1 hour | driver type changes |
| Current attributes | seconds or event-driven | device event |
| Preferences | 5 to 15 minutes | configuration save or explicit refresh |
| Driver definitions/source hash | until code/type revision changes | driver code save/import/update |
| Dependencies | Automation Map scan lifetime | Automation Map rescan |
| Events/jobs | do not cache, or very briefly | requested live |

Every cached preference response should state `retrievedAt` and `staleAfter`.

## 10. Security model

### Read roles

- `device-state-read`: inventory, capabilities, attributes and events;
- `device-config-read`: preferences, state variables, device data and jobs;
- `driver-source-read`: user driver source;
- `automation-map-read`: dependency graph and decoded flows.

### Write roles

- `device-command-write`;
- `device-config-write`;
- `driver-code-write`;
- `device-driver-change`.

Do not let possession of one role imply another.

### Redaction rules

Suppress preference/source values whose keys or definitions indicate:

```text
password, passwd, secret, token, apiKey, oauth, credential,
pin, lockCode, code, privateKey, certificate, psk, ssid
```

Apply entropy/shape checks for long tokens even when names are misleading. Return the preference name and `[REDACTED]`, not the secret.

## 11. Failure handling

The adapter must distinguish:

- device does not exist;
- device exists but is disabled;
- session/authentication unavailable;
- endpoint changed after a platform update;
- preferences tab has no settings;
- a default is displayed but not saved;
- field was redacted;
- built-in driver source unavailable;
- response parsing incomplete.

Never convert any of these to an empty object without a warning.

## 12. Minimum proof-of-concept

### Phase 1: read adapter

Implement:

- `hub_get_device_runtime`;
- `hub_get_device_configuration`;
- `hub_get_driver_definition`;
- redaction;
- platform/version tagging;
- cache and explicit refresh.

Validate with:

1. system Virtual Switch with auto-off;
2. system Hue/CoCoHue child light;
3. Zigbee device with preferences and data values;
4. Z-Wave device with configuration parameters;
5. user driver whose source is available;
6. device with no preferences;
7. device containing a deliberately fake secret preference to confirm redaction.

### Phase 2: Automation Map integration

Add optional device configuration summaries to the AI assessment export:

```json
{
  "deviceConfigurationEvidence": [
    {
      "deviceId": "d1279",
      "facts": [
        {
          "key": "autoOff",
          "displayValue": "1s",
          "classification": "timing-safeguard"
        }
      ]
    }
  ]
}
```

Do not export all preferences by default. Include only fields used as evidence for an assessment, or let the user explicitly request a configuration-inclusive export.

### Phase 3: guarded writes

Only after the read layer and audit log are proven, add separately permissioned preference updates and driver changes.

## 13. Application to Alarm OFF Switch

For device `1279`:

```text
runtime attribute:
  switch = off

driver preference:
  key = autoOff
  display value = 1s

rule relationship:
  Panic ON commands on
  Panic OFF triggers on the on event

effective sequence:
  on event -> Panic OFF trigger -> driver auto-off after 1 second
```

The existing MCP runtime device read correctly exposed `switch=off`, but omitted the `autoOff` preference. The proposed `hub_get_device_configuration` tool fills that exact gap.

## 14. References

- Hubitat Device Detail documentation: https://docs2.hubitat.com/en/user-interface/devices/device-detail
- Hubitat Driver Overview: https://docs2.hubitat.com/en/developer/driver/overview
- Hubitat Maker API documentation: https://docs2.hubitat.com/en/apps/maker-api
- Hubitat developer discussion confirming that arbitrary apps cannot directly read driver preferences: https://community.hubitat.com/t/get-device-setting-from-an-app/38553
- Automation Map repository and established hub-local inspection approach: https://github.com/GordonThelander/hubitat-automation-map

