# Hubitat Local MCP Server as a source of hub data-access methods

**Status:** technical assessment and design input  
**Assessed project:** [kingpanther13/Hubitat-local-MCP-server](https://github.com/kingpanther13/Hubitat-local-MCP-server)  
**Assessed revision:** `a385ffd1` (`4.0.2`)  
**Assessment date:** 2026-08-26  
**Primary purpose:** identify proven Hubitat data-access methods that may improve Automation Map discovery, verification and AI-friendly export  
**Boundary:** this paper does not approve an implementation, make Automation Map depend on the MCP server, or recommend copying its write-capable administration surface

## Executive summary

The Community Hubitat Local MCP project is much more than an MCP transport adapter. It is a substantial body of Hubitat reverse-engineering and live-tested implementation knowledge. Its source includes direct Hubitat app APIs, authenticated loopback HTTP access, internal endpoint contracts, defensive parsers, firmware compatibility fallbacks and an inventory of endpoints recovered from the hub's own administrative user interface.

The most important finding for Automation Map is conclusive support for first-class Hub Variable discovery. A Hubitat app can call `getAllGlobalVars()` to obtain all Hub Variables, not merely those with connector devices. The returned records provide the variable name, value, type and, where applicable, connector device ID and attribute. `getGlobalVar(name)` provides the corresponding single-variable lookup. This resolves the main discovery uncertainty in the existing Hub Variable specification.

Other useful methods include:

- `/hub2/appsList` for the complete installed-app hierarchy;
- `/device/listWithCapabilities/json` for an all-hub device and capability inventory;
- `/device/fullJson/<id>` for device identity, state, commands, room and app-use information;
- `/device/eventsJson/<id>` and `/logs/eventsJson` for device and location event history;
- `/installedapp/configure/json/<id>` for app configuration structure and settings;
- `/installedapp/statusJson/<id>` for app status, subscriptions, scheduled work and Rule Machine local variables;
- room, mode, hub-health, performance, log, code-metadata and source-code interfaces.

The recommended approach is selective reuse of proven read techniques. Automation Map should not require the MCP server to be installed and should not inherit its broad administrative powers. Normal in-process Hubitat APIs should be preferred, with read-only internal endpoints used where they provide otherwise unavailable information. Every internal endpoint must fail gracefully and remain subject to fixture testing and live firmware verification.

## 1. Assessment method

The assessment examined the repository source rather than relying only on its README or advertised MCP tools. The relevant material includes:

- `hubitat-mcp-server.groovy`, including the common internal HTTP transport;
- `libraries/mcp-variables-lib.groovy`;
- `libraries/mcp-devices-lib.groovy`;
- `libraries/mcp-rooms-lib.groovy`;
- `libraries/mcp-system-lib.groovy`;
- `libraries/mcp-diagnostics-lib.groovy`;
- `libraries/mcp-code-management-lib.groovy`;
- the Rule Machine and Visual Rule Builder libraries;
- `resources/hub2-source/README.md`, which inventories endpoint evidence recovered from the hub's own administrative UI assets;
- associated automated tests and endpoint-contract notes.

The review distinguishes four classes of access:

1. documented or platform-provided in-process Groovy APIs;
2. read-only internal HTTP endpoints;
3. privileged write or administrative endpoints;
4. MCP-specific orchestration that is not itself a new source of hub data.

Only the first two classes are candidates for Automation Map discovery. Write interfaces are recorded where necessary to understand the source, but are outside the proposed Automation Map scope.

## 2. Architectural lesson: MCP is not the data source

MCP is the protocol through which the project exposes tools to an AI client. The underlying data comes from Hubitat itself through ordinary app APIs and local administrative HTTP interfaces.

This distinction matters for Automation Map. Installing or calling the Community MCP server is not required in order to use the discovered techniques. Automation Map can implement appropriate read methods directly, subject to its own security and compatibility boundaries. Conversely, merely adding an MCP connection would not automatically make Automation Map's runtime data model more complete.

The project is therefore best treated as:

- an implementation reference;
- an endpoint and response-shape catalogue;
- a collection of failure-handling patterns;
- evidence about what has been live-tested on recent Hubitat firmware;
- a source of test cases and compatibility warnings.

It should not become a runtime dependency of Automation Map.

## 3. Hub Variables

### 3.1 Complete variable enumeration

The server uses the Hubitat app API:

```groovy
def allVars = getAllGlobalVars()
```

It transforms each returned entry into:

```groovy
[
    name: name,
    value: var?.value,
    type: var?.type,
    deviceId: var?.deviceId,
    attribute: var?.attribute,
    source: "hub"
]
```

The implementation explicitly distinguishes this modern API from the older connector-limited `getAllGlobalConnectorVariables()` behaviour. A variable without a connector remains discoverable, with `deviceId` and `attribute` null.

Single-variable lookup uses:

```groovy
def hubVar = getGlobalVar(name)
```

This is sufficient for Automation Map to model Hub Variables as first-class shared-state entities rather than virtual-device substitutes.

### 3.2 Change observation

The MCP project subscribes to variable events using:

```groovy
subscribe(location, "variable:${varName}", "handleHubVariableEvent")
```

This proves a useful event form for observing Hub Variable changes. The project retains a bounded local history, but correctly describes it as an opportunistic buffer rather than an authoritative audit log. It notes that the hub's location event history is the more complete source.

For Automation Map, subscriptions are not needed for a basic topology scan. They may be useful later for change tracking or a live diagnostics mode, provided they are bounded and optional.

### 3.3 Connector relationships

The `deviceId` and `attribute` values provide the exact relationship between a Hub Variable and its connector device. Automation Map can therefore create one variable node and connect it to its connector rather than rendering the variable multiple times.

The appropriate semantic relationship remains:

```text
Hub Variable synchronizedWith Variable Connector
```

The connector is a projection of the shared value for device-oriented apps. It is not the variable's owner and should not replace the Hub Variable node.

### 3.4 Export implications

The AI-friendly export should include, at minimum:

- stable variable identity based on the variable name;
- declared Hubitat type;
- current value, according to the export's privacy policy;
- connector device ID and attribute when present;
- application and rule reads;
- application and rule writes;
- a connector synchronization edge;
- discovery status and any enumeration error.

This aligns with `hub_variable_first_class_spec.md`. The MCP evidence changes the method from speculative to implementable.

## 4. Installed applications

### 4.1 Application tree

The principal endpoint is:

```http
GET /hub2/appsList
```

The project parses its nested `apps` tree and extracts:

- app instance ID;
- displayed name;
- type;
- disabled flag;
- user versus built-in status;
- hidden status;
- parent-child relationships;
- child counts.

The implementation contains several useful defensive behaviours:

- embedded HTML is removed from displayed names;
- hidden parents can be excluded while their children are promoted to the nearest visible ancestor;
- parsing failure is distinguished from an empty application list;
- firmware response-shape drift produces an explicit error;
- result filtering happens after authoritative enumeration;
- tree-level unreadability does not become a false assertion that individual apps are enabled.

Automation Map already uses `/hub2/appsList`, but should compare its implementation against these behaviours. In particular, unknown status must remain unknown when the endpoint is unavailable.

### 4.2 Application configuration

The project uses:

```http
GET /installedapp/configure/json/<appId>
```

This can expose the live configuration page, including sections, inputs and saved settings. It is potentially valuable for relationship extraction because a configured device, rule, mode or variable can sometimes be identified directly from structured settings.

The method should be treated as richer but more expensive than `/hub2/appsList`. A sensible scan strategy is:

1. enumerate applications once;
2. fetch detailed configuration only for supported decoders or when ordinary relationship walking is incomplete;
3. preserve an unreadable-app result rather than silently omitting it;
4. avoid repeatedly rebuilding the same app page during one scan.

### 4.3 Application status and local variables

The following endpoint is particularly useful:

```http
GET /installedapp/statusJson/<appId>
```

For Rule Machine rules, the project reports that `appState.allLocalVars` carries the rule-local variable map. The response represents `appState` as a list of name/value entries, so the correct lookup is conceptually:

```groovy
appState.find { it.name == "allLocalVars" }?.value
```

The same status surface can provide subscriptions and scheduled jobs. These are valuable for understanding execution behaviour, but they should be exported as runtime evidence rather than confused with configured topology.

### 4.4 Application event history

```http
GET /installedapp/eventsJson/<appId>
```

This provides application event history. It is potentially useful for diagnostics but is not required for the normal relationship map. Event history can be large and privacy-sensitive, so it should be opt-in and time-bounded.

## 5. Devices

### 5.1 All-hub device discovery

The repository documents:

```http
GET /device/listWithCapabilities/json
```

This returns an all-hub device list containing device IDs, labels and capabilities. Its principal advantage is that it can include devices not granted to the calling app through a normal Hubitat device input.

This creates an opportunity to simplify or supplement Automation Map's device enumeration. It also creates a security and compatibility obligation. Access outside the app's selected-device set should be explicit, read-only and clearly described to the user.

The classic capability-filtered picker endpoint is also identified:

```http
GET /device/listJson?capability=<capability>
```

This is useful for targeted discovery, but the all-device endpoint is a better foundation for a complete inventory.

### 5.2 Full device record

The project relies extensively on:

```http
GET /device/fullJson/<deviceId>
```

Depending on firmware and device type, the response can include:

- device name and label;
- device ID and network ID;
- assigned driver information;
- room assignment;
- disabled state;
- current attribute states;
- available attributes;
- available commands;
- preferences and device metadata;
- apps using the device.

This endpoint is especially valuable as an enrichment and verification source. The normal Groovy device object should remain the preferred route for devices explicitly selected by the user. `fullJson` can then fill gaps, validate discovery and reach unselected devices when an all-device scan mode is intentionally enabled.

An important warning from the MCP implementation is that an unknown device ID can still return a parseable but under-populated response. Existence must be established from an authoritative device inventory before interpreting an empty `appsUsing` value as "no applications use this device."

### 5.3 Device event history

```http
GET /device/eventsJson/<deviceId>
```

The response supplies newest-first device events. The MCP project normalizes records to fields such as:

- attribute name;
- value;
- unit;
- description text;
- timestamp;
- state-change flag.

This is suitable for diagnostics, baseline comparison and explaining recent behaviour. It is not necessary for building the static relationship graph.

### 5.4 Device consumers

The `appsUsing` structure returned by `/device/fullJson/<id>` provides an independent view of application-to-device dependencies. Automation Map can use it as a cross-check against relationships derived by walking app configuration.

Differences should be surfaced rather than silently reconciled. For example:

- an app walk finds a relationship absent from `appsUsing`;
- `appsUsing` reports a consumer whose configuration could not be decoded;
- a deleted or cached app identifier remains in one source;
- a device is known but inaccessible through the selected-device API.

This could support an export field such as `evidenceSources` and improve confidence scoring.

## 6. Rooms, modes and other grouping context

The MCP project provides room listing and detail methods. Room summaries contain room ID, name, device count and assigned device IDs. A room detail call can add current device states.

Rooms should be modelled as grouping or location context, not as peers of applications, devices and Hub Variables. They can support:

- filtering the map;
- grouping devices visually;
- summarising automation by room;
- identifying unassigned devices;
- adding human-readable context to AI export.

The system library also reads modes from the Hubitat location model and enriches them through:

```http
GET /modes/json
GET /modes/easyModeManager/json
```

Modes are closer to shared-state entities than rooms because automations both observe and change them. A later model expansion may treat them similarly to Hub Variables, but that is separate from this assessment.

## 7. Hub health, diagnostics and logs

The repository identifies useful read-only operational endpoints:

```http
GET /hub2/hubData
GET /hub/advanced/freeOSMemory
GET /hub/advanced/freeOSMemoryHistory
GET /hub/advanced/internalTempCelsius
GET /hub/advanced/databaseSize
GET /logs/json
GET /logs/past/json
GET /logs/eventsJson
```

`/hub2/hubData` represents data calculated for the modern Hubitat administration UI. The project uses it for firmware status, safe-mode state and hub-generated health alerts.

The logs and performance surfaces can expose:

- recent and past application or device logs;
- scheduled jobs;
- running jobs;
- hub actions;
- app and device execution statistics;
- state size;
- event and state counts;
- memory history;
- database size;
- temperature;
- radio status.

These methods support a future operational-intelligence view, where topology and resource behaviour are considered together. They should not be folded into the ordinary map scan because they have different cost, retention and privacy characteristics.

A better separation is:

- **Topology scan:** what exists and how it is connected;
- **Configuration evidence:** why the connection was inferred;
- **Operational snapshot:** what the hub is doing now;
- **Event history:** what recently happened;
- **Health assessment:** where resource or reliability risks are present.

## 8. User code and configuration metadata

The code-management library can enumerate and read:

- user app types;
- user driver types;
- user libraries;
- Groovy source;
- installed-app configuration;
- app pages;
- device dependants.

This enables more advanced analysis, including:

- identifying the exact implementation behind an installed app or driver;
- checking declared capabilities against observed device data;
- recognising application-specific relationship patterns;
- finding Maker API or HTTP integrations in user code;
- improving identity matching against HPM and community catalogues;
- explaining why a generic decoder could not understand an app.

Source retrieval is powerful and sensitive. It can expose tokens, URLs, personal identifiers or hard-coded secrets. It must not be added to the standard AI-friendly export. If ever implemented, it should be an explicit developer operation with secret filtering and clear user consent.

## 9. Internal HTTP transport pattern

The MCP server centralises internal requests in a hardened wrapper. Its behaviour is valuable independent of MCP:

1. determine the hub's local base address;
2. authenticate through `/login` when Hub Security is enabled;
3. retain the returned session cookie in `atomicState` for a bounded period;
4. attach the cookie to subsequent requests;
5. retry once with a fresh cookie after an HTTP 401 or 403;
6. pass query parameters through the request's `query` map;
7. never embed a query string in the request path;
8. distinguish text, JSON, form and raw redirect-response shapes;
9. propagate body-read failures instead of converting stream objects to meaningless strings;
10. distinguish empty responses from non-JSON responses;
11. redact sensitive values from diagnostic logging;
12. report response-shape drift explicitly.

One subtle but important finding is that Hubitat's HTTP client can escape a `?` embedded in the `path` parameter. Exact routes may then return 404, while wildcard routes may absorb the malformed suffix and appear to work incorrectly. Query values must be supplied through the query map and must not be pre-encoded.

Automation Map should reuse these transport principles for any internal endpoint it adopts. A smaller read-only helper is preferable to importing the MCP project's general administration layer.

## 10. What should not be imported into Automation Map

The MCP project includes broad write and destructive capabilities, including device changes, app and driver source deployment, variable creation and deletion, radio operations, backups, firmware actions and hub administration.

These are appropriate only within a deliberately privileged control server with explicit safety gates. They are not required for Automation Map's purpose.

Automation Map should not acquire:

- device command execution merely because commands are discoverable;
- source-code update or deployment operations;
- variable creation, mutation or deletion;
- device, app, room, mode or radio mutation;
- firmware, backup, reboot or shutdown controls;
- an unrestricted arbitrary-endpoint tool;
- automatic export of source code or secrets;
- a runtime dependency on the Community MCP project.

Read access can still be sensitive. Labels, room names, locations, coordinates, logs and event descriptions may contain personal information. The existing export privacy model must apply to all newly discovered data.

## 11. Proposed Automation Map adoption plan

### Phase 1: Hub Variables

Implement direct, in-process variable discovery with `getAllGlobalVars()`.

Required behaviours:

- enumerate all variables once per scan;
- preserve name, type and connector metadata;
- represent one node per variable;
- connect connector devices explicitly;
- retain current values according to export privacy settings;
- distinguish no variables from enumeration failure;
- add Hub Variables to map, picklists, insights and AI export;
- add fixtures for variables with and without connectors.

### Phase 2: device enrichment and verification

Evaluate:

```http
/device/listWithCapabilities/json
/device/fullJson/<id>
```

Use these to:

- identify devices outside the normal selected-device scope;
- enrich device identity, driver and room metadata;
- cross-check application dependencies through `appsUsing`;
- record discovery gaps explicitly.

This phase must measure scan overhead on small and large hubs before it becomes a default behaviour.

### Phase 3: application evidence

Harden `/hub2/appsList` parsing and selectively use:

```http
/installedapp/configure/json/<id>
/installedapp/statusJson/<id>
```

Priorities are:

- Rule Machine local variables;
- scheduled jobs and subscriptions as optional runtime evidence;
- clearer disabled, paused and unknown status handling;
- configuration evidence for otherwise unexplained relationships.

### Phase 4: diagnostics integration

Build a separate optional diagnostics view rather than expanding the core scan indiscriminately. It may combine:

- app and device performance;
- hub memory and database health;
- scheduled and running jobs;
- recent errors;
- map complexity and high-connectivity nodes;
- device and app event evidence.

### Phase 5: developer analysis

Consider opt-in source and metadata analysis for advanced users. Keep this separate from the standard export and protect secrets by default.

## 12. Testing and compatibility requirements

Every adopted method needs:

- recorded response fixtures from at least two firmware versions where practical;
- empty, malformed, unauthorized and timeout fixtures;
- explicit handling of HTTP 401, 403, 404 and 5xx responses;
- a test proving that one failed enrichment source does not discard the successful scan;
- response-shape validation before data enters the graph;
- bounded request counts and measured hub impact;
- clear differentiation between absent data, inaccessible data and endpoint failure;
- sanitisation of labels and names before HTML rendering;
- secret and personal-data review before export;
- documentation that internal endpoints are unsupported and may change.

For high-cardinality endpoints, the scan should fetch once and index locally rather than performing repeated per-relationship calls. Per-device and per-app detail requests should be queued, bounded and deduplicated using the transactional bounded-async pattern already documented for Automation Map.

## 13. Relationship to the existing read-only harness

The Hubitat Read-Only Internal API Harness is the appropriate place to validate these findings independently from Automation Map.

Candidate additions or confirmations include:

- an in-process `getAllGlobalVars()` test;
- variable result-shape reporting, including connector metadata;
- `/device/listWithCapabilities/json`;
- `/device/fullJson/<knownId>`;
- `/device/eventsJson/<knownId>`;
- `/installedapp/configure/json/<knownId>`;
- `/installedapp/statusJson/<knownId>`;
- `/installedapp/eventsJson/<knownId>`;
- `/hub2/hubData` shape reporting;
- `/logs/eventsJson` and scoped `/logs/past/json` checks;
- read-only room and mode enumeration;
- explicit warnings on unsupported, sensitive or firmware-dependent endpoints.

The harness should preserve endpoints judged unsuitable for production use, but label them clearly rather than deleting the evidence. The goal is a reproducible research instrument, not an endorsement of every endpoint.

## 14. Recommended decision

Adopt the following position:

1. Treat the Community Hubitat Local MCP repository as high-value implementation evidence.
2. Proceed with `getAllGlobalVars()` for first-class Hub Variable support.
3. Prototype device and application enrichment in the read-only harness before changing production scans.
4. Incorporate the MCP project's defensive parsing and authenticated-loopback patterns where relevant.
5. Keep Automation Map independent of the MCP server installation.
6. Keep write, destructive and source-deployment capabilities outside Automation Map.
7. Separate topology, configuration evidence, operational diagnostics and event history in both UI and export.

The project materially reduces the amount of Hubitat behaviour that Automation Map must rediscover. Its greatest value is not that it exposes many AI tools, but that it records concrete, defensive and frequently live-tested ways to obtain hub data that the documented application model does not expose completely.

## References

- [Community Hubitat Local MCP server](https://github.com/kingpanther13/Hubitat-local-MCP-server)
- [Hub Variable implementation](https://github.com/kingpanther13/Hubitat-local-MCP-server/blob/main/libraries/mcp-variables-lib.groovy)
- [Device implementation](https://github.com/kingpanther13/Hubitat-local-MCP-server/blob/main/libraries/mcp-devices-lib.groovy)
- [Room implementation](https://github.com/kingpanther13/Hubitat-local-MCP-server/blob/main/libraries/mcp-rooms-lib.groovy)
- [System implementation](https://github.com/kingpanther13/Hubitat-local-MCP-server/blob/main/libraries/mcp-system-lib.groovy)
- [Diagnostics implementation](https://github.com/kingpanther13/Hubitat-local-MCP-server/blob/main/libraries/mcp-diagnostics-lib.groovy)
- [Code-management implementation](https://github.com/kingpanther13/Hubitat-local-MCP-server/blob/main/libraries/mcp-code-management-lib.groovy)
- [Internal hub UI evidence inventory](https://github.com/kingpanther13/Hubitat-local-MCP-server/blob/main/resources/hub2-source/README.md)
- `Supporting Docs/hub_variable_first_class_spec.md`
- `Supporting Docs/ai_export_spec.md`
- `Supporting Docs/hubitat_driver_programmatic_access.md`
- `Supporting Docs/async_scan_v205_technical_report.md`
