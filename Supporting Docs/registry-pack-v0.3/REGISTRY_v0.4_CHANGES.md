# Registry v0.4 changes

Generated 2026-08-13 from v0.3, applying fixes evidenced by the HPM crawl.

| Category | Entry | Change |
| --- | --- | --- |
| schema | `(nodeClasses)` | declared missing entry class DASHBOARD |
| schema | `(nodeClasses)` | declared missing entry class PLATFORM_UTILITY |
| schema | `(nodeClasses)` | declared missing entry class SECURITY_ORCHESTRATOR |
| schema | `(nodeClasses)` | declared missing entry class VIRTUALISATION_ORCHESTRATOR |
| schema | `govee-v2` | dependency class EXTERNAL_OR_LOCAL_SERVICE -> EXTERNAL_SERVICE, ambiguity moved to transport |
| schema | `reolink` | dependency class LOCAL_DEVICE_OR_BRIDGE -> LOCAL_DEVICE, ambiguity moved to transport |
| schema | `weatherflow` | dependency class LOCAL_OR_EXTERNAL_SERVICE -> EXTERNAL_SERVICE, ambiguity moved to transport |
| schema | `owntracks` | dependency class EXTERNAL_OR_LOCAL_SERVICE -> EXTERNAL_SERVICE, ambiguity moved to transport |
| schema | `reolink-camera` | dependency class LOCAL_DEVICE_OR_BRIDGE -> LOCAL_DEVICE, ambiguity moved to transport |
| schema | `rtsp-camera-integration` | dependency class LOCAL_DEVICE_OR_BRIDGE -> LOCAL_DEVICE, ambiguity moved to transport |
| false-positive | `rule-machine` | was contains "Rule Machine", which matches the unrelated package "Rule Machine Manager". Now equals the strings hubs actually report: Rule-5.1, Button Rule-5.1, Rule-4.1 |
| unmatchable | `node-red` | dropped 1 rule(s) naming a Hubitat app that does not exist; reachable only by user mapping now |
| unmatchable | `ifttt` | dropped 0 rule(s) naming a Hubitat app that does not exist; reachable only by user mapping now |
| unmatchable | `sharp-tools` | dropped 1 rule(s) naming a Hubitat app that does not exist; reachable only by user mapping now |
| over-broad | `groups-scenes` | appName contains 'Group' matched unrelated packages; narrowed to 'Groups and Scenes' |
| over-broad | `shelly-mqtt` | driverName contains 'MQTT' matched unrelated packages; narrowed to 'Shelly MQTT' |
| verification | `homekit-import` | NEEDS_REVIEW |
| verification | `insteon` | NOT_IN_INDEX |
| verification | `mdns-discovery` | NOT_IN_INDEX_BY_DESIGN |
| verification | `shelly-device-manager` | NEEDS_REVIEW |
| verification | `unifi-network` | NEEDS_REVIEW |
| verification | `tuya-cloud` | NEEDS_REVIEW |
| verification | `volvo-cars` | NOT_IN_INDEX |
| verification | `bom-weather-alerts` | NOT_IN_INDEX_BY_DESIGN |
| verification | `honeywell-tcc` | NEEDS_REVIEW |
| verification | `hubconnect` | NOT_IN_INDEX_CRAWLER_LIMITATION |

Total changes: **26**
