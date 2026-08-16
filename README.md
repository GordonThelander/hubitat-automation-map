# Automation Map

Draws every installed app and device on your hub as an interactive graph, colouring each connection by the **role** the device plays in that app. Rules also render as a flowchart of their actual logic.

It answers questions the hub itself makes tedious: what does this rule really touch, which app keeps turning that light on, and what is this device even used for.

**Read-only.** It never commands a device or changes an app. It reads and draws.

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

A rule that is only ever a target, and touches no devices at all, still appears so the relationship is not lost. It is drawn as an outline rather than a filled node, because nothing else about it has been mapped.

**Drill-down.** Click an app to see just that app and what it uses. Click one of its devices to see everything else touching that device, and keep going. Both filters have search boxes.

**Rule flowcharts.** Focusing a rule draws its logic top to bottom: trigger, gating expression, then the ordered actions including waits, timeouts and `IF` / `ELSE-IF` / `ELSE` branches, with the devices named at each step.

**Insights.** Two lists the hub cannot give you:

- **Contested devices** - more than one app can leave the device in a lasting state, the usual cause of automations fighting each other. Notifications and chimes are excluded, since repeating those is not a conflict.
- **Devices nothing references** - no app owns, watches or drives them.

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
| `/device/fullJson/<id>` | discovering which apps exist, by asking each device which apps use it |
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

Flowcharts are different: they are reconstructed from each app's internal runtime state. Built-in apps are compiled and expose no source at all, so this is the only route. **Rule Machine 5.1**, **Notifier**, and **Visual Rule Builder 2.0** (Hubitat's newer visual/AI-prompt rule builder, still in beta - decoding covers a single trigger/decision/action graph shape, tested against one fixture so far) are decoded today. Rules on other engines still appear in the graph with their device relationships and are reported as undecoded, never silently blank.

## Requirements and limitations

- **OAuth must be enabled on the app**, since the map is served as a web page from your hub. See Install below.
- **Desktop browser.** The graph, filters and flowcharts need room and a pointer. Small screens are shown a notice instead of an unusable version.
- **The viewing browser needs internet.** The graph and flowchart libraries load from a CDN. The hub itself does not need internet.
- **Undocumented endpoints.** A future platform update could change them. If they stop answering, the app says so rather than showing an empty map.
- **Hub Login Security is untested.** If it prevents the hub reading its own endpoints, the app detects that and names it as the likely cause.
- **Only apps that reference at least one device appear.** Apps are discovered by asking every device which apps use it, so an app using no devices at all is invisible. There is no bulk app-list endpoint to cross-check against. The one exception is a rule named as the target of a rule-to-rule link, which is drawn even when the scan never reached it.
- **Rule-to-rule links are read from Rule Machine 5.1 only.** A rule on another engine is not analysed for them, so it will show no links rather than showing that it has none. Room Lighting, Basic Rules, Simple Automation and webCoRE are not read this way.
- **Rules that reference a deleted rule are shown as such.** If a rule still names a rule that no longer exists, the target is drawn and labelled `deleted`. The action stays in the calling rule and silently does nothing, so it is worth seeing.
- **Event subscriptions are a snapshot.** Rule Machine drops its trigger subscriptions while a Required Expression is false. Rule Machine rules are unaffected because their trigger and condition settings are read directly, but a non-Rule-Machine app that subscribes conditionally can map differently depending on when you scanned.
- **Roles reflect configuration, not runtime behaviour** - how a device is wired into an app, not what happened last night.
- A scan of roughly 200 devices and 60 apps takes about two minutes.

## Install

**OAuth must be enabled.** The map is a web page the app serves from your hub, which needs an OAuth access token. Without it there is no map link. Installing through Hubitat Package Manager enables OAuth for you; installing by hand does not, so step 2 below is not optional.

### Via Hubitat Package Manager

**Install** -> **From a URL**, then:

```
https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/main/packageManifest.json
```

Then continue from step 3.

### By hand

1. **Apps Code** -> **New App** -> paste in `apps/automation_map.groovy` -> **Save**.
2. Still in Apps Code, click **OAuth** -> **Enable OAuth in App** -> **Update**.
3. **Apps** -> **Add User App** -> **Automation Map**.
4. Press **Done**. The first scan starts by itself; there is nothing to configure.
5. The scan runs in two passes, devices then apps. The page updates itself, so there is no need to reload it.
6. **View Automation Map**.

The map link contains an access token unique to your installation. Open the map from the app rather than bookmarking the URL, since reinstalling the app issues a new token and the old link stops working.

Every device on the hub is scanned. There is no device picker: apps are found by asking each device which apps use it, and the hub supplies the device list.

## Re-scanning

The map is a snapshot taken when you scan, not a live view. Re-scan after adding or reconfiguring apps or devices.

If a stored map cannot be drawn correctly by the installed release, the app refuses to show it and asks you to scan again rather than rendering something subtly wrong.

## Troubleshooting

The app exposes two endpoints, using the same access token as the map link, useful if a scan appears stuck:

- `.../scan-status` - progress, counts, and any recorded error
- `.../scan` - starts a scan without opening the app

`check_template.sh` is a maintainer tool. The map page is built inside a Groovy string, so a stray backslash is consumed before the browser sees it and silently breaks the page script. Run it before committing changes to the page.

## Credits

**Jim Becker (JimB)** - primary tester and functional requirements contributor. Reported the scan-start failure that led to the Remote Admin routing fix, and tested through every diagnostic build until it was found.

**Jean P. May Jr. (TheBearMay)** - bulk application discovery. His *Rule References Rule Table* documented `/hub2/appsList`, the endpoint that closed Automation Map's device-less-app blind spot (Rule Functions and other apps that touch no devices at all).

## Branches

`main` is the released version. It is what Hubitat Package Manager installs, so anything pushed there is public immediately.

`dev` is a private test channel. It renames the app to **Automation Map (Dev)** with its own package id, so it installs alongside the release version on the same hub without touching it. Both builds exclude every variant of themselves from the map, so neither draws the other.

To use it, add this as a custom repository in your own HPM:

```
https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/dev/repository.json
```

Changes are made and tested on `dev`, then merged to `main` for release. Only the app name, package id and the raw URLs differ between the branches.
