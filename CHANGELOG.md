# Changelog

Complete Automation Map development history previously carried in the HPM manifest.
The manifest now contains only the current Dev-channel summary so package metadata
stays easy to review.

## 2.4.26

Dev channel. Rule Machine valves (open and close), HSM arm and disarm, and button hold, double tap and release are now read for rule flowcharts and Hubitat Automation Intelligence. Room Manager places a room it has no saved position for in free space, so a room created elsewhere no longer lands on top of others. After upgrading, the settings page asks for a scan. Nothing here writes to your hub.

## 2.4.25

Dev channel. Rule flowcharts and Hubitat Automation Intelligence now read almost every Rule Machine action, including the ones no rule had used before: toggle and per-mode switches, push button, toggle, fade and raise/lower for dimmers, colour per mode and toggle colour, colour temperature fades, shades, fans, locks, thermostats, mute, delay per mode, exit, stop repeat, HTTP GET, ping and file actions. After upgrading, the settings page asks for a scan. Nothing here writes to your hub.

## 2.4.24

Dev channel. Every file Automation Map saves for you to keep or reload is now a .txt file: the AI friendly export already was, and the external systems and device icon backups now are too. Baseline Comparison and both Import buttons open .txt files, and still open older .json ones; before this, Baseline Comparison only offered .json files, so a fresh export could not be compared. The contents are unchanged. Nothing here writes to your hub.

## 2.4.23

Dev channel. After upgrading, the settings page asks for a scan before showing the map, as earlier upgrades did. Without this, an upgraded map kept showing the previous version's results with no prompt, and with daily scanning off it could stay that way. Nothing here writes to your hub.

## 2.4.22

Dev channel. A scan keeps its external systems when the shared registry cannot be read and an older copy of the app's data was written back at the same time; one scan had drawn 11 fewer of them. The list of apps a scan enumerated is now restored the same way as the app list itself, so the check that compares the two always has both. Nothing here writes to your hub.

## 2.4.21

Dev channel. Easy Mobile Dashboard instances are no longer added to the map. They turned out to be Hubitat's own room dashboards for its mobile app, which it creates itself and leaves off its Apps list, so adding them made the app count disagree with the hub's Apps page and drew one of them to every device. The scan no longer asks the hub which apps use each device, which also makes it about 15 seconds quicker. Nothing here writes to your hub.

## 2.4.20

Dev channel. A scan is no longer refused because another page or job wrote back an older copy of the app list while the scan was finishing: the scan keeps its own copy and restores it. Apps you installed are tagged [CUS] again; a lookup fault had tagged every app [INT] since 2.4.3. After a refused scan the settings page says when the automatic retry will run. In Room Manager, creating or deleting a room takes two calls to the hub instead of four, and the change shows as soon as the hub confirms it, with no reload of every device's room. An app that is not a rule no longer says it has no decoded rule flow. Nothing here writes to your hub beyond what Room Manager already did.

## 2.4.19

Dev channel. Easy Mobile Dashboard instances now appear on the map with the devices they show: the hub leaves them off its list of apps, so the scan now also asks the hub which apps use each device and adds any it missed, within a time limit. Visual Rule Builder's new repeated action (firmware 2.5.2.135) is drawn as the builder words it, the action, how often, and until when, and a device it only checks to decide when to stop is shown as a condition rather than a trigger. The webCoRE Migration Assessment says why a loop still has no Visual Rule Builder equivalent: its repeat runs one action until a condition, with no count and no device list. Nothing here writes to your hub.

## 2.4.18

Dev channel. When a scan has to refuse to save its result, it now tries once more by itself three minutes later, so the map comes back within minutes instead of staying blank until the next scheduled scan. Insights now says more about a hub variable no rule is seen to use: where your hub reports which apps use each variable, the finding says either that no app uses it, or which apps use it in a way this scan cannot read. Nothing here writes to your hub.

## 2.4.17

Dev channel. A scan whose list of apps is lost partway through, which can happen in the first minutes after a hub restart while pages are slow to load, is no longer published as a complete map with no apps or rules in it: the scan now refuses to save it and the last good map is kept. The sibling engine's rule migration reads that file, so it is no longer told a hub has no Rule Machine rules when it has. The same loss no longer makes external systems from the shared registry disappear from the External Systems page: the last matches are kept and the page says why. Nothing here writes to your hub.

## 2.4.16

Dev channel. The HAI Rule Container check now checks a container that names its rule even when the hub leaves out its event subscriptions, so stopped HAI rules are counted as checked instead of unknown. Nothing here writes to your hub.

## 2.4.15

Dev channel. Insights gains a check on HAI Rule Containers: one whose own saved state shows it never received a rule is listed under Possibly unused, one whose name disagrees with its state is listed under Needs attention as a defect in HAI, and one that could not be checked is said to be unknown rather than counted as fine. The decode file read by the sibling engine's rule migration now lists the devices the scan saw as disabled (contract schema 4), and leaves the list out when the hub did not say. Nothing here writes to your hub.

## 2.4.14

Dev channel. The decode file read by the sibling engine's rule migration now says when an On/Off action commands only switches that are currently on or off, or acts on the switch that triggered the rule, so such an action is left for a person instead of being copied as a plain on/off. One action on this hub uses one. Nothing here writes to your hub.

## 2.4.13

Dev channel. Two fixes to the decode file read by the sibling engine's rule migration, found by comparing it with the map's own reading of each rule. A Certain Time trigger saved under Rule Machine's older label is now read, where before the rule was published with no trigger at all. And the map no longer shows a trigger a rule does not have when Rule Machine has left an old trigger setting behind. 2.4.12 was an internal test build and was not released. Nothing here writes to your hub.

## 2.4.11

Dev channel. A Set Color action using a custom RGB colour is now read in full for the sibling engine's rule migration: its hue, saturation and level, plus the exact colour picked, which until now left those rules to be copied by hand. A setting Rule Machine leaves behind when a trigger is removed is no longer read as a trigger: one rule here was published with a presence trigger it does not have, which would have made a copy run far more often than the original. Nothing in the map changes, and nothing here writes to your hub.

## 2.4.10

Dev channel. The decode file now reads ten more Rule Machine actions for the sibling engine's rule migration: set a dimmer's level and fade, adjust a dimmer, flash, refresh, poll, chime, set mode, comment, HTTP POST, and open or close a garage door, its direction read as measured on the hub, which is the opposite way to its name. A conditional trigger now names the condition it is gated on, and the file lists each hub variable's type as the hub reports it. Mute is still left for a person, until how its direction is stored has been measured. Nothing in the map changes, and nothing here writes to your hub.

## 2.4.8

Dev channel. The decode file read by the sibling engine's rule migration moves to contract 2. A Wait for Expression or Wait for Events now says whether its time is how long the condition must hold or a timeout, which until now it named the wrong way round; Wait for Events is read for the first time, including its events. Devices in rule conditions are published with both their id and name. A rule that still names a rule since deleted no longer passes that link on: it is listed as a finding instead, so you can remove it in Rule Machine. The file now ends with a marker and a rule count, so a reader can tell a complete file from one cut short. Nothing in the map changes, and nothing here writes to your hub.

## 2.4.7

Dev channel. Rule Machine rules are now read in far more detail for the sibling engine's rule migration, which reads the decode file this app writes on your hub: each rule's triggers with their real devices and values, its conditions and Required Expression, IF/ELSE branches, and more than twenty kinds of action, including Set Variable, colour, colour temperature, volume, Capture and Restore, running, pausing and cancelling other rules, and Wait for Expression. If one part of a rule cannot be read, only that part is marked, with the reason, instead of the whole rule being reported as unreadable. A rule Hubitat marks as broken is now detected from Hubitat's own flag rather than from its label text. The webCoRE Migration Assessment no longer shows the date of ratings an upgrade is replacing, and says pistons were assessed under the previous version rather than that none have been. A message action now reports a volume only when it speaks and a volume is set. Nothing here writes to your hub. 2.4.5 and 2.4.6 were internal test builds and were not released.

## 2.4.4

Dev channel. The webCoRE Migration Assessment rated every piston on this hub each time the panel was opened. Ratings were already cached on the hub, but a rating goes stale the moment the graph is rebuilt and the panel treated stale as a reason to rate again, so a hub that scans daily re-rated everything on every open: 22 of 25 pistons, each costing a hub read and a decode, to redraw numbers it already held. A cached rating is now shown however old it is. Only two things cause a rating to be taken: a piston that has never been rated, and an upgrade of this app, which can change the equivalence table a rating was made against and is detected by storing the app version beside each rating. A graph rebuild still reports the rating as stale, which the export reads, but no longer drives re-rating. Refresh Scan replaces Reassess, above the search row, with the date of the previous assessment beneath it.

Mode names in rule flowcharts are read from the hub's live mode list rather than from the sentence Rule Machine stored when the condition was saved. That stored sentence is written once and never rewritten, while Rule Machine's own rule page rebuilds its text on view, so after a rename the page and the stored copy disagree and the stored copy is the wrong one. Mode 6 was renamed on this hub and two rules still carried the old name. Rule Machine's own wording is kept rather than invented, both forms read off a live hub: `Mode is X` for one mode, `Mode in [X, Y]` for several. A mode that no longer resolves keeps the stored text, because that text is then the only record of its name. Device names in the same sentence have the same problem and are not fixed: nothing records the previous name, so there is no way to find it in order to replace it.

A rule whose every trigger is the hub's own start event is separated from the rules that act on other rules during normal running. Such a rule acts once, at boot, and what it does to other rules is housekeeping rather than control flow: on this hub one contributes 43 of the 70 cross-rule links, every one resetting a Private Boolean, which buried the 27 links that change what another rule does. Classified on the trigger construct and never on the rule's name, and only when every trigger is the start event. Marked with a flag rather than given a fifth relationship kind, so the four rule-to-rule kinds the export documents stay four. Two new Show filters, Rule to rule except startup resets and Startup resets only, which partition the whole set, and those links draw faintly with their own legend row.

Fixed: a rule that tested the hub mode was discarded in full while being read, because the mode list entry was coerced to a Map and Hubitat's mode class has no matching accessor. Thirteen apps were lost on every scan since the condition detail was added, logged only as a processing failure per app. The decoded condition detail went from 64 rules and 103 conditions to 71 and 169 once it was corrected.

Resolved device conditions now publish device ids beside the device names. The saved setting holds both and only the names were being read. A name cannot be bound to: two devices can share a label, and one on this hub carries a trailing space.

## 2.4.3

Dev channel. The AI-friendly export now publishes the Rule Machine construct inventory it has always decoded. Each scanned Rule Machine entry in `apps` carries `rmConstructs`, a sorted list of the construct tokens observed in the recognized saved setting families: action subtypes, trigger capabilities, condition types, rule options and structural features. A matching `ruleFlows` entry repeats the array when decoded steps exist, while an inert or no-action rule remains represented correctly in `apps` with no synthetic flow. This is the same extraction the RM 5.1 coverage report is built on, so nothing new is decoded and no device, value or message content is added. An AI reading the export no longer has to infer what a rule does from the English step labels alone.

The same inventory is emitted for Button Rule 5.1 children. Their flows already exposed proven trigger and action construct associations, but their app type begins `Button Rule-` rather than `Rule-`, so the first Dev candidate omitted the matching app-level inventory. Live-export validation found the mismatch; the construct-specific engine gate and regression coverage now include both families without broadening Hub Variable or Local Variable decoding.

The inventory remains the complete rule-scoped view produced by the setting families this release recognises. Trigger and action steps now also carry the exact normalized token proven by their own saved row, so an AI can associate those constructs with the correct position and branch without matching English labels. Conditions, options and structure tokens remain rule-scoped because their per-step association has not been proven. The tokens describe observed saved settings, not guaranteed reachability in the current action path. Neither form contains configured values or runtime evidence, and neither overrides an unresolved device reference. The export states that an unrecognised Rule Machine setting family is invisible rather than reported as unknown, so token absence is not proof that a feature is absent.

Token spelling and meaning are versioned separately from the export structure by a new root `rmConstructVocabularyVersion`, because the values can change without the shape changing and consumers will join on them.

A root `rmConstructVocabulary` dictionary explains the 35 tokens whose saved names are not already plain language: 32 internal `action:getXxx` names, two structure tokens and the display-current-values option. Each definition gives a category, plain-language meaning and an HAI capability ID where an explicit mapping exists. Self-describing trigger and condition tokens are deliberately omitted rather than repeated.

The export specification's root table said schema 13 while the app emits 14. Corrected.

## 2.4.2

Dev channel. **Room Manager**, a new panel that files devices into rooms from one screen. Every room is a rectangle you can move and resize, with its devices shown as chips carrying the same icons the map draws. Anything with no room sits in a Not Allocated column down the left, beside every possible destination rather than behind a separate page. Drag a device to move it, ctrl-click to pick several and drag them together, shift-click to take a whole run. Rooms can be created, renamed and deleted without leaving the page, and the rectangle layout is remembered. Nothing reaches the hub until Apply is pressed: moves are staged and highlighted until then, Discard throws them away, and anything that fails to apply stays staged so a retry sends only what is outstanding.

This is the first time this app writes to the hub, and it was added for this feature and no other. It does not command devices, edit rules or alter other apps. The only thing it writes is a device's room. Deleting a room does not delete its devices: they become unallocated, and the confirmation says how many before it happens. A room move writes only room membership. It posts the room's device list and never sends any other field of the device record, so notes, tags, custom icons and dashboard assignments cannot be altered by it.

App tags were wrong for nine apps. INT means an app that ships with the hub and CUS means one you installed, and LIFX Light Manager, CoCoHue, Kasa, Tapo, Sensibo, Chromecast, Meross, Google Home and BOM Weather Alerts were all labelled as shipping with the hub. They do not. The tag is now read from the hub's own list of user-installed app types rather than from a list kept by hand, so it corrects itself after a scan.

The HAI RM5 Coverage table now reports Implemented, of those working on hub, Scoped, and Not built, replacing a set of columns that described the engine's older vocabulary rather than how much of the work is done. A capability status this app does not recognise is listed under its own name instead of being counted as partly working, which it previously was.

A rule flowchart opens wide enough to read. It renders at its natural size and was routinely wider than the panel, so more than half of a diagram sat behind a scrollbar. Panels also re-measure themselves when the browser is zoomed or the window resized, where before they kept the size they had when opened and could sit under the control rail. The Opening objects on the hub button is gone; the card it opened still appears by itself after an upgrade.

## 2.4.1

Dev channel. The engine this app reads its experimental rules from is now found on the hub by itself: there is no address to paste and the setting that asked for one is gone, along with the access token it held. When that engine is not installed the page says so and explains that its Rule Machine comparison still works, because the capability list it publishes openly is used instead. If a feed file is found with no app that writes it, its rules are left off the map and the reason is logged rather than guessed at. Location event triggers of every name, including lowMemory, now match the single capability that engine publishes for them, so a rule using one is no longer reported as having nothing to move to. View Automation Map moves above the Scan button so the map is reachable without scrolling on a phone. Scan progress no longer reports one phase's count against another phase's total. A paused rule of that engine is shown as paused rather than running. The flowchart library is fetched when a flowchart is first opened instead of on every view of the map.

## 2.4.0

In development on the dev channel.

Rules from Hubitat Automation Intelligence now appear on the map, marked experimental. That app
publishes its own rules in this app's graph shape, so the map reads them from it at each scan
rather than decoding them: what each rule triggers on, what it commands and with which commands,
and what it treats as a condition. Paste the feed address from that app into the new setting on
this app's settings page and its rules join the map alongside everything else. Previously those
rules could only be drawn from their device permissions, which read as "published to an external
system" and said nothing about what the rule does; those placeholder lines are now replaced.
Selecting one of these rules explains that its steps live in that app and points to its own page,
rather than drawing a flowchart. The main page reports how many of these rules and relationships
came from the feed, and says so plainly when the feed could not be read. Nothing is sent: the
address is read from your own hub.


The Community information card no longer claims a package's declared identity did not match its
source. That check is a static parse, and a package naming itself through a constant read as a
difference with nothing actually wrong, including this one. The card now says the identity could not
be confirmed and to treat the match as unconfirmed rather than wrong. The parser behind it was fixed
separately in the Community Utilities crawl.

A condition that nothing evaluates now draws as a dotted line on the map, in the same colour
as a normal condition, with a legend entry. Previously a device could only be marked when every
one of its relationships was dead, so a device that was both used and pointlessly named in an
old condition showed nothing.

Set Variable actions that calculate a value now show the variables they read and print the
arithmetic, for example "Set Variable Counter = TestNumber + 5". A plain copy from one
variable to another reads the same way.

Rule Machine wait-for-event devices are no longer drawn as rule triggers. Those devices are
stored under a separate key family that the old check also matched, which affected three rules
on a 66-rule hub, and they now appear as monitored devices.

A Set Variable action that writes a number from a device attribute now shows that source.
Numeric variables use a different field for it than text variables, and only the text one was
read.

webCoRE flow charts now draw a switch's default branch as `else` after its cases. Case values are
still shown as not decoded.

Opened through Hubitat remote access, the settings page no longer sits on "Remote scanning" after a
scan finishes. It reloads every 15 seconds while a scan runs, for at most three minutes.

The webCoRE construct registry now records the three fuel-stream commands as declared. They are
added by a conditional block the generator previously skipped.

## 2.3.2

Released 2026-09-16.

**Open anything on the hub.** Right-click any device or app on the map to reach its page in the
Hubitat admin UI in a second tab: the device page for a device, the status page or the app page for
an app or rule. The menu also copies the link, focuses the node, and re-reads that object from the
hub. Empty canvas and shift with right-click keep the browser's own menu, so saving the image is
still where it always was. A local variable node offers its owning app. A node with no hub page says
so rather than doing nothing. A short card explains the gesture the first time a view with clickable
objects is opened, again after an upgrade, and monthly only while the gesture has never been used;
"Opening objects on the hub" in the Focus panel shows it any time. What each browser has seen is tracked per installed app, so a Dev or Preprod install cannot mark the card seen for the production one.

**Rescan this object.** One device or app re-read from the hub without a full scan, applied to the
map in place, so the current view, zoom and focus are kept. A device has no record of its own, so
its edges come from the apps that use it and those are what get re-read, up to a bounded number.
Label, type and the Disabled flag are refreshed for the devices in scope, which a full scan was
previously the only way to pick up. It refuses while a full scan is running. Capabilities, rooms and
hub-wide insights still come from the last full scan.

**Export schema 14.** Two additions:

- `edges[].unusedConstraint` marks a constraint edge whose condition nothing evaluates, the same
  determination the map draws as the UNUSED tag. Scoped to that app, never a claim that the device
  is unused on the hub.
- `migrationRatings[]` carries each webCoRE piston's rating for Rule Machine and Visual Rule Builder
  2.0, with its label, up to three reasons, the number of parts needing rework, and whether
  automatic conversion is possible. The full per-part breakdown stays in the Migration Assessment
  panel. Ratings are cached on the hub as that panel computes them and read back in one call, so the
  export performs no hub reads of its own; each record carries `ratedAt`, a `stale` flag when it
  predates the last graph rebuild, and `not-rated` for a piston nobody has assessed yet.

The community thread link on the app's settings page now opens the newest post.

## 2.3.1

Dev channel. New webCoRE migration assessment. Selecting a webCoRE piston shows a magenta Migration assessment button on the map; pressing it rates how directly the piston maps onto Rule Machine and onto Visual Rule Builder, from 1 (direct equivalent, simple) to 5 (easier to rebuild from scratch). The rating names every part that needs rework and the change it needs. Where the only difference is that a repeated device event could run the rule again, and the piston only sends fixed-value commands such as turning lights on or setting a level, it is shown as a warning instead of rework. A piston with a part the assessment does not recognise is shown as Not assessed rather than given a guessed rating. Whether this app can convert the piston automatically is shown separately, and only for parts proven on a hub. The new webCoRE Migration Assessment button in the control panel opens a report for every piston on the hub, with a summary bar for each engine, level filters, a construct matrix, and CSV downloads of both. The panels can be dragged anywhere. The assessment only reads the piston's saved structure: no variable value, message or device label is read, and nothing is created or changed on the hub.\n\n2.3.0 - Dev channel. A webCoRE piston now draws as a flowchart on the map, the same way Rule Machine, Notifier and Visual Rule Builder rules already do: statement order, branching, condition text composed from the piston's own saved spelling, nested condition groups as bracketed sub-sentences, and each task's saved parameters beside its command name. A device the piston reads in an event or a condition now takes the same trigger or constraint relationship Rule Machine uses, decided by which of webCoRE's own two comparison blocks the operator belongs to, so the map and the chart agree with each other. Where that cannot be told with certainty the read stays an unattributed device read rather than being given a role that might be false. Graph schema moves to 15 and export schema to 13, so a cached graph is rebuilt rather than silently shown without roles. No piston variable value is ever read: a variable operand prints its name, not its contents, and the decoded piston document and raw chunks are never logged or exported. Constants a piston saved are transcribed where they appear in a task parameter or a condition, for example setLevel(40), so those labels do reach the chart and the export. Separately, the decode coverage check reports what a piston's saved statements mean, kept separate from structure: the order in which an if tests its then, else-if and else branches, condition negation, or groups, followed-by groups kept opaque as timed sequences rather than read as and, do as a block that runs its steps once in order, and the default statement settings. Every other statement type, restriction, policy and condition comparison is reported as a named gap on the statement it affects, so no piston is yet shown as fully explained, and the result never changes a structural level or the coverage percentage. The coverage card adds a Meaning line and a Meaning not yet proven list. Each proven meaning cites the webCoRE wiki where it documents the construct, and the pinned webCoRE source, and is withdrawn automatically if that source changes. Quick Search and the Focus dropdowns also match the tags shown on each row, such as [WCP].\n\n2.2.9 - Dev channel. Adds a read-only decode coverage assessment for an individual webCoRE piston, reached through a new authenticated endpoint. It walks the piston's whole saved configuration, accounts for every object, array, field, element and value, and reports which constructs it positively identified against a registry pinned to a specific webCoRE source commit, which it could not, and where the gaps sit as structural paths. Recognition is measured over construct candidates only, so complete traversal never implies complete understanding, and the question of whether a missing relationship is genuinely absent or was simply never looked for stops being a guess. Structure only: no piston literal, message, URL, variable value or device label is read into the result, an unknown object key is replaced by an ordinal placeholder, and every reason code is fixed in source. The assessment runs on request, never during a relationship scan, and refuses while a scan is active. There is no user interface for it yet, and no existing relationship, graph or export behaviour changes.\n\n2.2.8 - Dev channel. Removes the blanket exclusion of webCoRE piston device relationships and restores them at the individual piston level: a physical-device read (its saved compiled operand) resolves to a new deviceRead relationship, and a direct device action resolves to the existing action relationship, both reconciled against the owning webCoRE parent's own permitted-device hash index (verified live: a real piston resolved to the correct switch-attribute read and setColor action). webCoRE piston-local variables are also now first-class owner-scoped Local Variable nodes, matching Rule Machine's own locals, decoded from the piston's own saved chunk:N configuration the same way Hub Variable reads/writes already were. The webCoRE parent app itself still shows no device edges - its permission selections remain a permission list, not proof of piston use. Graph schema bumped 13->14, export schema 11->12.\n\n2.2.7 - Dev containment release. webCoRE parent device permissions and partial piston subscriptions are no longer presented as operational device relationships. Piston Hub Variable reads and writes remain visible, device-only pistons are not labelled inert, and the map and export disclose that piston-to-device relationships are not decoded yet.\n\n2.2.6 - Dev channel. webCoRE saved piston configuration now classifies source-proven Hub Variable reads and writes separately. Explicit assignment targets are writes, evaluated variable operands are reads, and one piston can carry both relationships to the same variable. Dynamically constructed target names remain invisible rather than being guessed.

## 2.2.5

Local-only working build, not pushed to GitHub - kept off the shared dev branch so existing testers stay on 2.2.4. Continues backlog item 1 Phase 3: Insights, External systems, Pivot tables, Device icons and Hubitat release activity now share one modern draggable panel shell at a large display area; the rule flowchart/inert-app/unreferenced-variable panel keeps its original small, content-sized shape and opens below the legend instead of hiding it, since it was never one of the unified panels. Compact legend is now contextual, showing only what the current view actually contains. Several live-found UI fixes on top of that: Focus combobox popups no longer lose to open panels (a CSS stacking-context issue affecting the whole #controls rail, not just the popup's own z-index), the release-activity chart panel no longer shows an unwanted scrollbar, the rule flowchart panel no longer overlaps the legend or renders far wider than its own content needs, its scrollbar for tall flowcharts works again, and the hub watermark image no longer sits partly behind the Focus control panel - halved in size again on top of that. Rollback anchor: git tag dev-v2.2.4-rollback.

## 2.2.3

Focusing an app, device or variable now frames the view reliably. Previously the inert-node shelf was rebuilt into the focused view and the zoom fitted around it, the reframing often did not run at all, and when it did it measured node centres only, so labels were cut off and the map could run underneath the legend, the controls menu or an open panel. The view is now sized from the nodes and their labels against the area actually free to draw in. The whole-hub map is unchanged.

## 2.2.2

Fixes narrowed views rendering too small to read. Focusing an app, device or variable left the map at a fraction of its usable scale, because the inert-node shelf was being rebuilt into the focused view and the zoom was then fitted around it. The shelf now appears only on the whole-hub map, and a narrowed view is allowed to magnify.

## 2.2.1

In development on the dev channel. Insights gains a set of findings for things that look fine but
silently do nothing, each pairing a state with the second fact that makes it worth acting on rather
than reporting the state alone:

- A paused or disabled rule that another rule still runs, so that step in the caller silently does
  nothing. Pause/resume links are deliberately excluded, since a rule whose job is to resume this
  one is the mechanism working rather than a failure.
- A disabled device automations still command or wait on as a trigger. Constraint and monitor reads
  are excluded as a weaker, much noisier claim than a command that cannot land.
- Rules Hubitat itself marks broken, read from its own label rather than judged by this scan.
- Every paused or disabled rule as plain context under expected patterns, not as a fault list, and
  never double-counted with the ones reported under Needs attention.
- Local Variables declared in a rule with no decoded read or write, carrying the same "may simply be
  unused" caveat the equivalent Hub Variable finding already has.

All findings reach the AI-friendly export as additive fields with their own limitations, which no
consumer is required to understand, so the export schema version is unchanged. Note that none of
this observes runtime behaviour: it is static configuration evidence that a step cannot do anything,
not evidence that it was ever reached.

## 2.2.0

Production-cleanup release. Local review and automated gates passed; deployed to Automation Map
(Dev) (Apps Code 1210 / instance 3083); diagnostic-toggle placement and off/on/off logging
behaviour independently verified live by Gordon. The telemetry-child migration test (clean
deletion, plus a deliberately-referenced device failing safely) is explicitly waived by Gordon, not
passed - low affected population, easy manual fallback. The
Automation Map Telemetry Driver and everything that fed it (`ensureTelemetryDevice()`,
`reportTelemetry()`, `fetchHubHardwareId()`, the manifest driver entry, the README disclosure) are
removed entirely rather than made optional - an always-present reporting driver read as intrusive
to some users regardless of what it actually collected. An upgrading instance removes its own
leftover telemetry child device automatically: exact-DNI, never forced, and if Hubitat refuses the
deletion because the device is still referenced elsewhere, the settings page shows a fixed warning
(never the raw exception text, which stays in the log only) and retries the next time settings are
saved.

In its place, a settings-page toggle enables on-demand diagnostic logging for troubleshooting - off
by default. A durable expiry timestamp, not just a scheduled job, enforces the one-hour auto-disable
even if the scheduled handler itself is missed (a one-shot job does not fire late or catch up if the
hub is down when it comes due); an unrelated later settings save while the toggle stays on does not
push the deadline out further, and the settings page reconciles a stale "on" display back to off on
its own next render. Every routine/lifecycle log line in the app (installs, scheduling
confirmations, endpoint-entry logs, successful saves, expected superseded-generation discards,
registry counts, scan start/completion detail) is now gated behind this toggle; failures and
degraded outcomes that can leave the map incomplete or stale stay unconditionally logged regardless
of it. The temporary `AM-TRACE` diagnostic path stays Dev-only regardless of the toggle's own state,
per the standing agreement not to make it part of the reusable production logging design.

The automatic-scan Hours/Minutes field now shows its real default (00:30 production, 01:00 Dev)
pre-filled, instead of appearing blank next to a `description:` that never rendered on `bool`/`time`
inputs. One helper function is the single source for that default across the input, the explanatory
paragraph, and the scheduler's own blank-time fallback. A separate effective-default helper treats a
genuinely unsaved setting the same as its own displayed-on default, since Hubitat does not
necessarily populate `settings` with a displayed default before the first save.

Both the removed remote-telemetry approach and the new local-logging approach are documented for
reuse at `https://github.com/GordonThelander/hubitat_dev_utililities` under "Application Telemetry
Methods", sanitized and parameterized rather than copied with real identifiers.

The four Focus dropdowns (Apps, Devices, Hub Variables, Local Variables) are now a single combined
combobox each, replacing the old search-input-stacked-above-a-select pair. Built and proven
standalone first, then iterated live against direct feedback: the closed control is a plain,
non-editable label-plus-arrow (an earlier version that let the closed control double as the search
field was tried live and rejected in favour of this conventional shape); opening it reveals a popup
whose first row is a dedicated, auto-focused search field, with the filtered options list directly
below it; the unfiltered list still offers the "All X" reset row, but a typed filter narrows to
matches only. The controls panel widened 150px -> 300px so option text is not truncated, and the hub
watermark image now tracks the panel's own right-anchored position instead of a fixed percentage, so
future panel-width changes cannot drift the two out of alignment the way they did here.

A colour and typography pass brings the desktop UI closer to
`gordonthelander.github.io/HPM_Manifest_Crawl/` (Hubitat Community Utilities) - Mulish typeface,
pill-shaped buttons and a shared blue accent across every control that was previously left to the
browser's own default styling, softer panel corners, small letter-spaced labels above each Focus
control. The dark background and the graph's own node/edge colour system are unchanged - that is a
separate, semantic legend, not general UI chrome. The typeface is self-hosted from this repo (a
single variable-weight WOFF2, `Fonts/`, with its upstream SIL OFL 1.1 licence carried alongside it)
rather than fetched live from Google Fonts on every page load, for the same reason this release
removed its own telemetry driver - a call to any third party on every visit reads as intrusive to
some users, and a live font request is the same category of thing even though it carries no app
data. Two real defects were found and fixed during this pass, not cosmetic: a CSS inheritance leak
that pulled the new small/bold label styling into the combobox popup wherever it happened to be
nested inside a `<label>` element, and a "Community information" card that only cleared its own text
when it had nothing to show rather than actually hiding, leaving a blank light rectangle on screen.

## 2.1.7

Device discovery now walks the complete tree `/hub2/devicesList` returns instead of only its
top-level entries. A device-owned component device (`isComponent: true`, created by a parent device
driver rather than an app - Shelly, Bond, and Matter bridges are the reported examples) can be
represented nested inside its parent's own `children` array, invisible to the previous flat read
regardless of whether the component was referenced by anything. Confirmed live: this hub's own
"Variable Connectors" parent carries all nine per-variable Connector devices this way, previously
invisible to bulk discovery and synthesized as bare placeholder nodes from Hub Variable metadata
instead. Aggregates by device ID before grouping so a device exposed both at the top level and
beneath a parent is enriched, not duplicated.

The discovered parent/child relationship is rendered as a new `hasComponent` edge kind on the graph
and in the AI-friendly export (graph schema 9, export schema 7), including correct focus-expansion
behaviour for an app that touches a component child without referencing its parent directly.

Also fixes a live regression introduced by this same discovery work: an apostrophe inside a
single-quoted JS string in the page's inline script terminated the string early, breaking the
entire embedded script and leaving the rendered map blank while the device/app counter still
rendered. Fixed and confirmed live via a rendered page load with zero console errors. Reported by
community tester Steve (oldcomputerwiz); independently confirmed on his own hub - Hubitat and
Automation Map both reported 351 devices, Aqara/Bond/Harmony/Shelly devices specifically checked
and all present, described as "spot on".

## 2.1.6

Local Variables (belong to one rule only) become first-class nodes on the network graph, not just
names inside the rule detail card - a real, decoded action like "Set Local Variable X" now has
somewhere to appear on the map itself. Seeded from each rule's own declarations rather than from
references, so a Local Variable that is declared but never written or read is still shown, isolated on
the same shelf an orphaned app already uses (a separate `unreferencedLocal` marker, deliberately not
the existing inert-app flag, so app-only counts and findings are unaffected). Owner-scoped identity
throughout: two rules can each declare their own same-named Local Variable, and they render as two
distinct nodes, never merged - the same identity guarantee Gate C (2.1.4) established for correctly
telling Local and Hub Variable references apart. Adds a Focus local variable picker and pivot table
support alongside the existing app/device/Hub Variable ones. Graph storage schema bumped 7 to 8 and the
AI-friendly export schema bumped 5 to 6, since edges[] can now target a Local Variable in addition to a
Hub Variable - the schema prose documents how to tell them apart without guessing. Dev-only testing
build, not yet verified on the Dev hub.

**2026-08-29 fix**: `graphVersion` moves from `state` to `atomicState`. A page load landing within about a
second of a scan's completion could read a stale pre-commit `state` snapshot - `state.graph` still null,
left over from the value scan-start assigns - and `shouldAutoScan()` would read that as "never scanned",
genuinely auto-starting a second scan. Confirmed live via hub trace logs (a completed generation's own
`noGraph` read `true` on the very next page load) and reproduced by hammering the settings-page render
immediately after a real scan completion. `atomicState` commits on every write instead of once at the end
of an execution, closing the window; a one-time migration backfills the field for installs that already
have a graph stored under the old key, so this deploy does not itself trigger a false "stale format,
please rescan" message. Verified on the Dev hub: 20 consecutive post-completion checks (real scan, rapid
repeated settings-page renders) all read `noGraph=false`, versus the pre-fix trace showing it flip `true`.

**2026-08-30 fix**: `state.graph` itself (not just `graphVersion`) could still be clobbered back to null by
an unrelated execution's own end-of-run `state` write-back landing after the real finalizer had already
committed it - confirmed live as the cause of the "View Automation Map" link (and the app/device/node
counts above it) silently disappearing after a scan, stuck across repeated app opens since nothing else
rewrites `state.graph` until the next scan does. Rather than move the whole (large) graph to `atomicState`
too - risking the memory failure the 2026-08-13 fix exists to avoid - `selfHealGraphIfNeeded()` now treats
`atomicState.graphVersion` (immune to this race) as proof a graph should exist, and if `state.graph` is
missing anyway, rebuilds it locally from the scan's other results (`appInfo`, device maps, a fresh Hub
Variable read) instead of requiring a full rescan; those inputs are written earlier in the pipeline than
`state.graph` itself, so they are not lost by the same race. Verified live on the Dev hub: the exact defect
reproduced naturally (real scan, real app reopen through Hubitat's own UI), the trace log shows
`selfHealGraphIfNeeded()` firing and rebuilding within about a second of the corruption, and the map link
and counts were correct on every check for the following two minutes.

## 2.1.5

Adds short `[LOC]`/`[HVR]`/`[CON]` class tags to the Hub Variable Focus dropdown and the rule detail
variables card, matching the existing app/device Focus tag convention (2.0.0) so a variable's class is
visible at a glance rather than requiring a click-through. `[HVR]` (Hub Variable) is a new code, chosen
deliberately over reusing `APP_TYPE_TAGS`'s existing `HUB` code - that one means "built-in Hubitat app",
a different axis from variable scope, and reusing it risked conflating the two. The Hub Variable Focus
dropdown shows `[CON]` for a Connector-backed Hub Variable and `[HVR]` for a plain one, reading the same
authoritative `connectorDeviceId` the graph already resolves; the rule detail card shows `[LOC]` for a
proven Local reference and `[HVR]` for a proven Hub reference, with no tag on a Needs-review entry since
its class is, by definition, not known. No new backend or export schema fields - every tag reads a value
already resolved by Gate C (2.1.4). Verified on the Dev hub against
fixture rule 3079: both tag branches confirmed against real classified data (a proven Hub reference and
a Connector-backed Hub Variable), with no regression on the earlier Gate A fixtures. Dev-only testing
build, not yet promoted to production.

## 2.1.4

Correctly distinguishes Rule Machine Local Variables (belong to one rule) from Hub Variables (shared
hub-wide) and Variable Connector devices, instead of treating every structured variable reference as a
Hub Variable. Empirically established that a same-named Local and Hub Variable in one rule cannot be
told apart from stored configuration alone - Rule Machine silently resolves the write to the Local
variable at runtime, but the persisted action storage gives no way to recover which one the author
intended - so this case is now reported as genuinely ambiguous rather than guessed at, and a reference
this app cannot confirm against the hub's own authoritative variable list is reported as unresolved
rather than falling back to a weaker-guarantee node. Adds a Local/Hub/Needs-review variables section to
the rule detail panel, and corresponding `localVariables`/`variableReferences`/
`nonResolvedVariableReferences` fields to the AI-friendly export (schema 5) - the export's Hub Variable
topology guarantee is strictly stronger as a result, which is why the schema version bumped rather than
just adding fields. Verified on the Dev hub: classification exactly matched Gate A's fixture predictions
across all three test rules, Hub Variable graph topology and corrected flow labels rendered correctly
with no invented relationships from ambiguous or unresolved references, the Local/Needs-review rule
detail card rendered correctly on a live rule, no variable value appeared anywhere, and scan
finalization/telemetry stayed single-shot. The card's Hub section was not exercised by a dedicated
fixture - it shares the same renderer and already-verified scope filter as the Local section, so this
is not treated as a coverage gap. Dev-only testing build, not yet promoted to production or verified on
a second hub.

## 2.1.3

Fixes the registry-finalization stale-snapshot race: a finalizer entering with a stale, pre-commit
registry snapshot now resolves correctly through a generation-keyed lookup instead of publishing an
incomplete result, and a completed generation can never be republished. Also fixes the settings page
showing stale "Scanning..."/"Building map" text after a scan has actually completed, and closes a gap
where auto-scan could start a second scan without checking the live scan lock. Verified on Gordon's
own hub - a genuine
state resurrection caught and cleared in under half a second, down from roughly 90 seconds before this
fix, and a settings-page fetch at the exact instant of true completion showed no stale text - and via a
controlled Dev-only test forcing the registry watchdog specifically to win the finalizer claim, which
completed cleanly with exactly one result and no duplicate telemetry row. Independently confirmed on
Steve's own C-5 hardware, which exercised the same stale-snapshot hazard on the ordinary chained path.

## 2.1.2

Adds the Automation Map Telemetry Driver, a new bundled driver that reports anonymous data to support ongoing development and future features, after every scan. No credential in the driver: the endpoint is open ingestion, protected by strict server-side payload validation rather than a secret shipped in public source. On by default, no toggle, disclosed in the README. Automation Map creates its own child device instance automatically; delivery is deferred so a telemetry failure can never affect scan publication. Also fixes a false `error: HTTP 302` status the driver reported for a successful send, caused by treating Apps Script's redirect response as a transport failure instead of following it. Verified end to end on the Dev hub: a real scan produced a genuine row in the telemetry sheet with correct data, and the status now reads `submitted`/`ok` instead of a false error.

## 2.1.1

Version bump only, no functional change - keeps the manifest's tracked version in sync with the production release after a Hubitat Package Manager version-tracking mismatch (HPM's own installed-version bookkeeping is separate from the app's live code and is only updated by an HPM-driven install/update).

## 2.1.0

Makes Insights concise and actionable with plain-language explanations, reasons a pattern may be normal, and practical next checks. The same guidance is included in the AI-friendly export. Adds reviewed defaults for common external systems, reconciles Hub Variable identities that include a trailing period, and corrects the installation-page description of how apps are discovered. Released to the Dev channel for hub testing.

## 2.0.14

Adds authoritative Hub Variable inventory through Hubitat's in-process `getAllGlobalVars()` API, Connector reconciliation, structured device-attribute `writeSource`, and export schema 4. Also adds Community Utilities context cards and release activity integration for Dev testing.

## 2.0.13

Fixes silent device metadata loss in bounded-async discovery. Devices whose bulk records omit rooms now receive targeted per-device lookups, malformed capability responses are reported as gaps instead of successful empty data, and representative rooms can no longer overwrite other devices in the same driver group. The export regression checker now detects changed non-empty rooms as well as disappearing rooms and capabilities. Verified on the Dev hub with 196 devices, zero room differences against both the authoritative device list and the historical clean export, zero empty capability lists, and zero unreadable devices.

## 2.0.12

Adds Community Utilities to the home page as a full-tab link, gives the three main navigation titles a consistent blue treatment, and improves the Baseline Comparison page with a prominent green Back control while removing Hubitat's misleading bottom Done/Cancel action. Verified on the Dev hub.

## 2.0.11

Makes graph construction and abandoned-scan finalisation entirely in-memory by resolving linked-rule deletion from the complete app inventory already collected, instead of making sequential 10-second loopback lookups. Removes the unused durable copy of device driver groupings. Remote Admin scans now show a truthful waiting message while local scans retain live progress, and the map endpoint refuses to render mixed old/new data while a scan is running. Verified on the Dev hub.

## 2.0.10

Includes every hub-discovered device in the graph and AI-friendly export, even when no app references it, so scan counts, Focus Device, Insights and exported inventory agree. The map link now includes the completed-scan timestamp, preventing browsers from reopening a cached pre-scan graph. Verified on the Dev hub with 195 devices and 322 nodes while all 1,011 existing relationships remained unchanged.

## 2.0.9

Rejects failed, malformed or incomplete /hub2/appsList responses instead of publishing them as a successful zero-app map, and requires durable proof that the complete async app results were committed before recovery may build a graph. Verified on the Dev hub with a successful 108-app/193-device scan after manually loading the corrected code.

## 2.0.8

Fixes a reproduced finalisation-recovery loop that could leave a completed manual scan showing 'Building map - please wait' indefinitely. Live status polling could repeatedly rebuild the completed graph, then discard it because the transient generation lock was missing or no longer matched. Finalisation now claims ownership before graph construction, concurrent recovery attempts do no duplicate work, and a stranded durable scan can atomically recover when its static lock has disappeared. Reproduced, fixed and verified on the Dev hub: the previously stranded scan recovered, a fresh 108-app/193-device manual scan completed in about 26.5 seconds, and logs were clean.

## 2.0.7

Fixes three remote-access issues surfaced by real-world testing of 2.0.5 (community feedback plus Gordon's own hub logs): (1) accessing the settings page over Remote Admin during a scan could reload the page every four seconds for the whole scan, rather than once - the live-progress poll added in 2.0.5 fell back to an unbounded reload chain instead of one delayed reload when it couldn't read cross-origin status; (2) a genuine race let a second /scan request start a duplicate, independent scan if it arrived before the first request's state had durably committed - state.scanRunning alone can't serve as a single-flight guard across two concurrent executions, so scan start now goes through an atomic lock shared by every entry point, released through one centralized helper covering all nine terminal paths including an unexpected exception mid-startup; (3) the routine '/scan endpoint reached' log line was warning-level and read as an error by testers - downgraded to info, real failures remain warnings. Also: a Dev install running alongside production on the same hub now defaults its overnight scan to 01:00 instead of 00:30, so the two no longer compete for the same loopback endpoints at the same second (an explicitly chosen time on either instance is unaffected). Full record in BACKLOG.md.

## 2.0.5

Reintroduces bounded-async device/app discovery (bulk /hub2/devicesList plus per-driver capability batching, concurrent dispatch for both scan phases) after an earlier attempt was reverted for a data-integrity issue: concurrent asynchttpGet callbacks could overwrite the durable scan result with a stale snapshot, a last-write-wins platform behavior. This rebuild is based on the fix used by hubitrep's HubDiagnostics for the same platform behavior, extended further since this app's scan results must survive a hub reboot: per-item claims with attempt tokens, atomic conditional-ownership retirement, a missing-callback reaper, exact completion invariants, and durable publication only from a separately scheduled execution. Reviewed and hardened through several rounds before any live test, catching two more real bugs along the way. Verified on the dev hub: four scans at 21-25s against a 134s serial baseline, identical device data, zero dispatch/reap/watchdog warnings. Full record in BACKLOG.md and Supporting Docs/async_scan_v205_technical_report.md. Dev-soak candidate - not yet promoted to main.

## 2.0.4

The app page now shows the date/time of the last scan next to the progress line, not just the device/app counts (community-requested).

## 2.0.3

CoCoHue Bridge and other bridge devices now auto-detect as Hub & infrastructure by name, Scene devices (CoCoHue Scene and similar) now auto-detect as their own Scene category instead of Buttons & remotes, both in the map and the Focus device dropdown. Hub photo watermark resized to half the Christmas tree's size and moved to sit below the controls panel; opacity raised to 50%. All 7 hub-facing HTTP calls now go through one shared request wrapper instead of each repeating its own fetch/error-handling - no behaviour change, just less duplication. Decided to keep the watermark and Community Utilities sound as GitHub hotlinks rather than move them to File Manager, since the map already requires internet for its CDN libraries regardless.

## 2.0.2

closes out the rest of the 2026-08-20 community code audit that 2.0.1 started on main: the OAuth-less hand-install now explains itself instead of throwing, all 8 quadratic dedup sites are O(1) instead of a linear scan, an IPv6 loopback host with a port is now correctly recognised as this hub, a scheduled job's cron string is escaped before display, the Show all sound is removed rather than fixed (it was falling back to the wrong sound on a lagging deploy), and saving an external-system or icon-override preference no longer waits on buildGraph()'s own HTTP calls before answering. minimumHEVersion raised to 2.5.1, the only firmware actually tested. Full findings in BACKLOG.md.

## 2.0.1 (main)

three issues found by the same audit: a custom-repository install could serve the private Dev build instead of the release, a rescan started while one was already running could let a stale internal job publish a half-finished map as complete, and the CDN-hosted graph/flowchart libraries now carry integrity hashes.

## 2.0.0

the biggest release since 1.2.1. Rule-to-rule links (Runs, Stops, Private Boolean) and Hub Variable read/write edges make the automation web between rules visible for the first time, not just app-to-device connections. External systems can now be declared and drawn as their own nodes, backed by a shared community registry, so you can see what breaks if a cloud dependency goes down. Every installed app is discovered now, including ones that touch no device at all - dimmed and labelled with why. New analysis tools: an Insights panel (contested devices, unreferenced devices, orphaned apps, broken rule references), Pivot tables with presets, a free-form builder and CSV export, and a Device icons panel with auto-detected icons, manual overrides and freeform notes. Rule flowcharts now decode Rule Machine 5.1, Notifier and Visual Rule Builder 2.0. Click-through drill-down with full browser Back/Forward support, an inert-node shelf, a collapsible legend, and an AI friendly structured JSON export round out the core release - see the README for the complete list. Since then: the Focus app/device dropdowns now prefix every entry with a short engine/category tag (RM5, VRB, INT, HUB and more for apps; LGT, SWT, MOT and more for devices) so a long list is easier to scan at a glance. The AI friendly export gained explicit guidance for the AI reading it - open with a plain-language summary, offer options rather than pick one unprompted, and never frame a routine count like contested devices as evidence something is wrong. Show all and every other way of choosing an app or device now reliably closes the other floating panels and brings the legend back, a real gap in earlier testing. The Start here hint now shows once ever rather than on every visit.
