# Automation Map

Draws every installed app and device on your hub as an interactive graph, colouring each connection by the **role** the device plays in that app. Rules also render as a flowchart of their actual logic.

It answers questions the hub itself makes tedious: what does this rule really touch, which app keeps turning that light on, and what is this device even used for.

> **Development channel:** This `dev` branch documents the current parallel test build. It installs as **Automation Map (Dev)** with separate settings and scan data. The production release remains on `main`. See the [changelog](CHANGELOG.md) for the complete development history.

**Read-only where it matters.** It never commands a device or changes another app. The only things you can edit are its own notes about your setup - device icon corrections and external system declarations - never anything on the hub itself.

## What you get

**Roles.** Every connection is one of:

| Role | Meaning |
| --- | --- |
| **Trigger** | the app listens to this device and reacts to it |
| **Constraint** | a condition or required expression that gates the app |
| **Monitor** | the app reads this device's state but cannot command it |
| **Action** | the app can command this device |
| **Exposed** | published to an external system, such as Maker API or Google Home |
| **Owns** | the app created this device |

A device can hold different roles in different apps: a motion sensor may trigger one rule and be switched by another. Roles therefore belong to the connection, not the device.

**Rule to rule.** Rules do not only touch devices, they act on each other, and that is invisible everywhere else on the hub. Three relationships are drawn directly between two rules:

| Link | Meaning |
| --- | --- |
| **Runs** | the rule runs another rule's actions |
| **Stops** | the rule stops another rule's actions |
| **Private Boolean** | the rule sets another rule's Private Boolean |

Pick **Rule to rule only** in the Show filter to see the automation chains on their own, with every device edge hidden.

A rule that is only ever a target, and touches no devices at all, still appears so the relationship is not lost. It is drawn as an outline rather than a filled node, because nothing else about it has been mapped. If a rule still names another rule that has since been deleted, that is shown too - labelled `deleted`, so the action silently doing nothing is something you can actually see rather than only discover the hard way.

**Hub Variables.** If one rule sets a Hub Variable and another reads it, that dependency is invisible everywhere else on the hub. Hub Variables are drawn as their own triangular nodes, with arrows showing which rules write to them and which read from them.

**Drill-down.** Click an app to see just that app and what it uses. Click one of its devices to see everything else touching that device, and keep going. Both filters have search boxes.

**Rule flowcharts.** Focusing a rule draws its logic top to bottom: trigger, gating expression, then the ordered actions including waits, timeouts and `IF` / `ELSE-IF` / `ELSE` branches, with the devices named at each step.

**Device icons.** Every device is drawn with an icon representing what it actually is - a light looks like a light, a door like a door, a water sensor like a water sensor - guessed automatically from the device's own capabilities and, where that alone is not specific enough, its name. Wrong for a particular device? The Device icons panel lists every device with its guessed icon, lets you pick the right one by hand, and lets you leave yourself a short note on anything left unrecognised. Overrides and notes survive future rescans, and can be backed up to a file and restored later.

**Insights.** Findings the hub cannot give you directly:

- **Contested devices** - more than one app can leave the device in a lasting state, the usual cause of automations fighting each other. Notifications and chimes are excluded, since repeating those is not a conflict.
- **Devices nothing references** - no app owns, watches or drives them.
- **Apps with no device or rule relationship** - installed and readable, but touch nothing, grouped by why (holds other apps, runs on a schedule, references nothing at all).
- **Broken rule references** - a rule still names another rule, action, pause target or Private Boolean that no longer exists.

**External systems.** The hub cannot see outside itself, so it cannot tell you that an integration needs a cloud bridge or an outside API to work. Declare it yourself in the External systems panel and it is drawn as its own diamond-shaped node, dashed edge back to the app that depends on it - so you can see what breaks if that outside service goes down. A [shared community registry](https://github.com/GordonThelander/HPM_Manifest_Crawl/blob/main/AUTOMATION_MAP_CONTRACT.md) pre-fills the common ones; your own declarations always win over it.

**Pivot tables.** Cross-reference anything already on the map - which devices a given app touches, which apps touch a given device, and more - with ready-made presets or a free-form builder for something specific. Results export to CSV.

**AI friendly export.** Download the whole map as one structured file - every device, app, connection, external system, Hub Variable and decoded rule's logic, with an explanation of the file's own structure built into the file itself. Meant for handing to an AI assistant or another external tool, not for reading raw. Device names, rooms and rule names in it reflect your real home, so treat the file with the same care you would the device list itself before sharing it anywhere.

## What "app" means here

Hubitat uses *app* for two different things, and the counts make no sense until you separate them:

- an **app type** is a piece of code in **Apps Code** - Rule Machine, Maker API, CoCoHue
- an **installed app** is one configured thing built on that code - your "Back Door Night" rule

Automation Map counts **installed apps**, the entries on your **Apps** page. A hub with 64 installed apps may have only 17 app types, because each Rule Machine rule is its own installed app sharing the one piece of Rule Machine code.

So each orange square is a single automation or integration you set up, not a piece of code. Two rules built on Rule Machine control different devices, so collapsing them into one node would throw away the point of the map.

## How it works

Hubitat has no API for "list every app and what devices it uses, and how". Automation Map asks the hub's own internal endpoints - the same ones the admin UI calls - through a local request the hub makes to itself. This is an established community technique, not a public API.

| Endpoint | Used for |
| --- | --- |
| `/device/fullJson/<id>` | device metadata and relationship evidence |
| `/hub2/appsList` | the complete installed-app tree in one call, so an app that touches no device at all - a Rule Function, a schedule-only app, a container - is not invisible |
| `/installedapp/statusJson/<id>` | the relationship data per app: child devices, event subscriptions, and every setting that resolves to devices |

Roles are decided in this order:

| Signal | Role |
| --- | --- |
| device is in the app's child devices | owns |
| Rule Machine trigger setting | trigger |
| Rule Machine condition setting | constraint |
| setting takes any device type (the wildcard picker) | exposed |
| device appears in the app's event subscriptions | trigger |
| setting's capability exposes no commands | monitor |
| anything else resolving to devices | action |

Child devices, event subscriptions and capability types are platform-level facts, so the graph covers apps it was never specifically written for, including integrations.

Flowcharts are different: they are reconstructed from each app's internal runtime state. Built-in apps are compiled and expose no source at all, so this is the only route. **Rule Machine 5.1**, **Notifier**, and **Visual Rule Builder 2.0** (Hubitat's newer visual/AI-prompt rule builder, still in beta on Hubitat's side - VRB itself currently allows only one decision node per rule, no nesting, which this decoder matches rather than works around) are decoded today. Rules on other engines still appear in the graph with their device relationships and are reported as undecoded, never silently blank.

## Requirements and limitations

- **OAuth must be enabled on the app**, since the map is served as a web page from your hub. See Install below.
- **Desktop browser.** The graph, filters and flowcharts need room and a pointer. Small screens are shown a notice instead of an unusable version.
- **The viewing browser needs internet.** The graph and flowchart libraries load from a CDN, the device icon font loads from cdnjs, and the watermark and click sound effects load from GitHub. The hub itself does not need internet.
- **Undocumented endpoints.** A future platform update could change them. If they stop answering, the app says so rather than showing an empty map.
- **Tested only on platform 2.5.1.152.** `minimumHEVersion` in the manifest matches; HPM will not offer this app on an earlier build.
- **Hub Login Security is untested.** If it prevents the hub reading its own endpoints, the app detects that and names it as the likely cause.
- **Every installed app is discovered, whether or not it touches a device.** Device-led discovery is unioned with the complete app list from `/hub2/appsList`, so a Rule Function, a schedule-only app, or a container with no devices of its own still appears - dimmed, and labelled with why it has nothing else mapped.
- **Hub Variable read/write edges are read from any Rule Machine engine, not only 5.1.** Rule-to-rule link detection runs against every app's settings regardless of engine, so it depends on Rule Machine's own settings shape being present rather than an explicit type check. Room Lighting, Basic Rules, Simple Automation and webCoRE do not store rules that way, so they show no links rather than showing that they have none.
- **Device icons are a best guess, not a certainty.** Capability and name-based detection cover most devices well, but a handful of categories (appliances, robot vacuums, and a few others) have no reliable Hubitat signal to detect from at all - see the Device icons panel to correct any of these by hand.
- **Event subscriptions are a snapshot.** Rule Machine drops its trigger subscriptions while a Required Expression is false. Rule Machine rules are unaffected because their trigger and condition settings are read directly, but a non-Rule-Machine app that subscribes conditionally can map differently depending on when you scanned.
- **Roles reflect configuration, not runtime behaviour** - how a device is wired into an app, not what happened last night.
- On the current Dev build, a scan of roughly 200 devices normally completes well under a minute. The production v2.0.4 serial scanner can take about two minutes on a similarly sized hub. Hub load and app count still affect both figures.

## Telemetry

Automation Map may collect anonymous data after a scan to support ongoing development and future features. This has no toggle; it is part of how the app works. Delivery happens after the map is published, so a telemetry failure can never affect a scan.

## Install

**OAuth must be enabled.** The map is a web page the app serves from your hub, which needs an OAuth access token. Without it there is no map link. Installing through Hubitat Package Manager enables OAuth for you; installing by hand does not, so step 2 below is not optional.

### Production release via Hubitat Package Manager

**Install** -> **From a URL**, then:

```
https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/main/packageManifest.json
```

Then continue from step 3.

### Development channel via Hubitat Package Manager

Add the following URL as a custom repository in HPM, then install **Automation Map (Dev)**:

```
https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/dev/repository.json
```

The Dev package installs alongside production and keeps its own settings, scan data and schedule.

### By hand

1. **Apps Code** -> **New App** -> paste in `apps/automation_map.groovy` -> **Save**.
2. Still in Apps Code, click **OAuth** -> **Enable OAuth in App** -> **Update**.
3. **Apps** -> **Add User App** -> **Automation Map**, or **Automation Map (Dev)** when using the Dev source.
4. Press **Done**. The first scan starts by itself; there is nothing to configure.
5. The scan runs in two passes, devices then apps. The page updates itself, so there is no need to reload it.
6. **View Automation Map**.

The map link contains an access token unique to your installation. Open the map from the app rather than bookmarking the URL, since reinstalling the app issues a new token and the old link stops working.

Every device on the hub is scanned. There is no device picker: Automation Map reads the hub's complete installed-app list, then scans every app and device to build their relationships.

A daily scan runs automatically by default. Production uses 00:30 and Dev uses 01:00 so parallel installations do not scan at the same moment. The time is changeable in the app's settings page, and automatic scanning can be disabled entirely.

## Re-scanning

The map is a snapshot taken when you scan, not a live view. Re-scan after adding or reconfiguring apps or devices.

If a stored map cannot be drawn correctly by the installed release, the app refuses to show it and asks you to scan again rather than rendering something subtly wrong.

## Troubleshooting

The app exposes two endpoints, using the same access token as the map link, useful if a scan appears stuck:

- `.../scan-status` - progress, counts, and any recorded error
- `.../scan` - starts a scan without opening the app

The map page is built inside a Groovy string, so a stray backslash can be consumed before the browser sees it and silently break the page script. Maintainers should run `powershell -File validate.ps1` before committing. The validator checks manifest and source version alignment, branch-specific URLs, JSON validity, tracked compiler artefacts and the embedded-template backslash guard. `check_template.sh` remains available on systems with Bash and `grep`, and now fails clearly if that dependency is unavailable.

## Credits

**Jim Becker (JimB)** - primary tester and functional requirements contributor. Reported the scan-start failure that led to the Remote Admin routing fix, and tested through every diagnostic build until it was found.

**Jean P. May Jr. (TheBearMay)** - bulk application discovery. His *Rule References Rule Table* documented `/hub2/appsList`, the endpoint that closed Automation Map's device-less-app blind spot (Rule Functions and other apps that touch no devices at all).

**Hubitrep** - the bounded-async scan rewrite is built on the fix in their `HubDiagnostics` app (`github.com/hubitrep/hubitat`): concurrent `asynchttpGet` callbacks writing to `state` are subject to last-write-wins persistence, which can silently overwrite a correct result with a stale one. Their diagnosis and fix were the origin; this app extends it further since its scan results must survive a hub reboot, unlike theirs. 


## Branches

`main` is the released version. It is what Hubitat Package Manager installs, so anything pushed there is public immediately.

`dev` is a development test channel. It renames the app to **Automation Map (Dev)** with its own package id, so it installs alongside the release version on the same hub without touching it. Both builds exclude every variant of themselves from the map, so neither draws the other.

To use it, add this as a custom repository in your own HPM:

```
https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/dev/repository.json
```

Changes are made and tested on `dev`, then promoted to `main` for release. While development is in progress, `dev` also contains unreleased functionality and may differ substantially from `main`. During promotion, preserve Main's production app name, package ids and raw URLs, and check `repository.json` explicitly because it does not merge cleanly and has previously ended up on `main` still pointing at the Dev package.
