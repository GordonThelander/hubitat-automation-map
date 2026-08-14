# Amnesia

A running record of avoidable mistakes, wasted effort, and lessons that should be carried forward when working on this repository.

The purpose is simple: when a mistake costs time or creates unnecessary friction, record it here so the same pattern is not repeated.

## 2026-08-14

### Overcomplicated a simple `handoff.md` edit

**What happened**

The review of dev build 1.8.5 had already been completed and the full review content was available in the conversation. When asked to add it to `handoff.md`, I unnecessarily re-read large parts of the file, fetched additional repository content, explored GitHub tool capabilities, and started reconstructing the document instead of performing the requested edit directly.

**Why this was wasteful**

- The content to add already existed.
- The target file and branch were already known.
- No further investigation was required to decide what to write.
- Re-fetching and re-analysing repository state added latency without improving the result.
- The user had asked for a simple file edit, not another review.

**Rule going forward**

When the user asks to add already-known content to a known repository file:

1. Treat it as an editing task, not a research task.
2. Reuse the content already available in the conversation.
3. Fetch only what is strictly required by the write API, and only once.
4. Do not re-run analysis, repository searches, comparisons, or source review unless the requested edit itself depends on new information.
5. Complete the write before doing any optional validation.

### Do not substitute snippets when the user expects the complete artefact

**What happened**

When asked to add a URL to a message, I responded with replacement snippets instead of returning the complete revised message.

**Rule going forward**

For this project, when revising a message, post, handoff section, release note, or other prose artefact, return the complete revised artefact unless the user explicitly asks for a snippet, diff, or isolated paragraph.

### Use the repository as source of truth for current implementation

**Lesson**

Automation Map changes quickly. Historical notes, prior messages, and README text can lag the current `dev` source.

**Rule going forward**

- For implementation questions, inspect the current branch/source when freshness matters.
- Do not re-check GitHub when the exact current content has already been fetched during the same task and has not changed.
- Distinguish current code from stale documentation rather than averaging the two.

---

## Standing convention

Gordon can say "amnesia: `<thing>`" in chat as a fast way to flag
something worth adding here. Treat it like any other memory-worthy
statement - add a short, accurate note under the relevant section (or a
new one) rather than a verbatim dump.

# Read this before touching any Hubitat hub

A MEMORY.md index line is a pointer, not the instruction. Seeing a one-line
summary like "Direct hub workflow for all Hubitat work" is NOT the same as
having read the file. Open the actual memory file before improvising
anything - this has already cost real time and Gordon's patience three
separate times by not doing that.

**A worse variant of the same failure (2026-08-08): reading this file in
full mid-session, then reverting to the old paste-and-copy habit for
THREE MORE code changes anyway**, before Gordon had to call it out a
second time in the same conversation. Having read the instruction once is
not the same as having applied it - it has to be re-checked before *every*
subsequent relevant action in a session, not just the one right after a
reminder lands. If a Hubitat code change was just made, the next question
is always "did I just push this myself, or did I ask Gordon to paste it
in" - not an assumption that reading the file once covers the rest of the
session.

## The specific thing that gets forgotten every time

Hubitat app/driver code can be read and pushed directly over HTTP to the
hub at 10.0.0.125 (no auth, Hub Login Security is off). Full detail lives
in `memory/feedback_hubitat_direct_hub_workflow.md` - read it in full, not
just its MEMORY.md summary. Short version so there's no excuse:

- `GET /app/ajax/code?id=<appTypeId>` - read a user app's current source
  and its `version` (an optimistic lock).
- `POST /app/ajax/update` with `id`, `version`, `source` - write new app
  code directly. No browser, no copy-paste, no DevTools. **Must be
  `application/x-www-form-urlencoded`, not a JSON body** - a JSON body
  fails with a useless generic `"Unknown exception occured while saving
  app code"` and tells you nothing about the real cause. In PowerShell,
  `Invoke-RestMethod -Body $hashtable` (no explicit `-ContentType`) form-
  encodes automatically and works; confirmed live 2026-08-08 (version
  lock incremented 35→36 on success).
- `GET /installedapp/statusJson/<installedAppId>` - an app instance's
  settings/state/scheduled jobs.
- `GET /device/fullJson/<deviceId>` - a device's full state.

Apps Code's *list/navigation* pages became a Vue SPA on newer firmware
(2.5.1.14x) - that's real, but it never affected these `/app/ajax/*`
endpoints. Don't let "the nav page is a SPA now" become "so nothing is
scriptable" - that exact wrong leap already happened once.

## Before doing ANY of the following, read the full memory file first

- Pushing or updating Hubitat app/driver code
- Reading an installed app's live settings or state
- Debugging "is this actually saved on the hub" or "why isn't this firing"
- Anything where the instinct is to ask Gordon to paste code, screenshot a
  page, or use browser DevTools

Scope stays as documented there too: read-only unless a write is actually
needed, confined to the app/device in question, HSM/garage/locks/sirens
still need explicit confirmation per
`memory/feedback_hubitat_maker_api_guardrails.md`.

## The PowerShell tool and the Bash tool are different shells - don't mix syntax

Both exist in this session and each takes only its own syntax. The
failure is a parse error before a single line runs, so nothing in a long
command executes and the whole call is wasted.

- **Heredocs (`python - <<'PY' ... PY`) are Bash only.** In the
  PowerShell tool they produce `Missing file specification after
  redirection operator`. PowerShell's equivalent is a here-string,
  `@'...'@` with the closing `'@` at column 0, but for feeding a script
  to an interpreter it is simpler to write a real `.py` file with the
  Write tool and run it.
- Also PowerShell-only: `$env:VAR` not `export VAR=`, `2>$null` not
  `2>/dev/null`, `Test-Path` not `[ -f x ]`, backtick not backslash for
  escaping, and no `VAR=x cmd` inline prefix.
- Before writing a long shell command, check which tool is being called
  and commit to that dialect. A 40-line command that dies on line 5 costs
  the same as one that dies on line 40.

## Never edit Claude Code's own settings.json (user or project) myself

Both `C:\Users\gordo\.claude\settings.json` and this project's
`.claude\settings.json` are off-limits to write directly - the auto-mode
classifier blocks it, and even when it doesn't, this is "modifying
system/security settings," a category to hand to Gordon, not do myself.
When a permission rule or hook is needed: on the FIRST attempt, write out
the exact JSON and tell Gordon which file it goes in - don't try the edit
myself first and burn a blocked attempt before falling back to this.

## Don't walk Gordon through multi-step DevTools/browser archaeology

Asking him to open DevTools, find the right tab (Network vs its own
internal Search sub-panel), clear filter boxes, distinguish a page's own
search box from DevTools', right-click vs left-click the right request,
Copy as cURL, etc. - this went badly wrong for a long stretch of a real
session and burned a lot of patience for very little payoff. If an HTTP
endpoint needs discovering: try documented memory first (see top of this
file), then try it myself directly (curl, download+grep the actual JS
bundle), and only ask Gordon for a live browser capture as a genuine last
resort - expect it to go sideways even then.

## The Hubitat hub's local admin UI legitimately loads third-party scripts

`cdn.customgpt.ai/js/embed.js` (official Hubitat Support Chatbot,
announced by Hubitat support staff on their community forum, Sep 2025)
and a New Relic browser-monitoring agent both appear in the raw HTML of
`/app/list` and `/installedapp/list`. Confirmed legitimate, not a
compromise. Don't re-raise this as a security concern in future sessions.

## Known Hubitat appTypeId / installedAppId / deviceId values

Extend this list as more get discovered instead of rediscovering them:

- Meross MSG100 Garage Door: appTypeId 1091, installed app id 2892,
  device id 3560 ("Volos Garage Door Opener"), driver deviceTypeId 2628.
- mDNS Device Discovery: installed app id 2518.
- Chromecast Integration child devices (driver "Chromecast Audio"/
  "Chromecast Video", exposes `ipAddress` attribute directly - no DNI
  decoding needed - plus `initialize()` via capability `Initialize`):
  Garage speaker 3369, Security Speaker 3370, Twin Speakers [Google Cast
  Group] 3374, Front door speaker 2381, Tanya Study Speaker 2397, Lounge
  Speaker 2449, Dining room speaker 2474, Master Bedroom speaker 2513,
  Patio Speaker 2514, Gordon Study speaker 2519, Master Bedroom display
  [Google Nest Hub] 3548, Garage speaker [Google Home Mini] 3550.
- BOM Weather Alerts: appTypeId 1143.
- Automation Map: release appTypeId **1140**. The **(Dev)** appTypeId is NOT
  stable - every HPM uninstall/reinstall mints a new one (seen 1148 then
  1149 within one session, with 1148 left returning zero bytes). **Never
  reuse a remembered dev appTypeId.** Resolve it each time from
  `GET /hub2/appsList` -> `userAppTypes`, and check the source length is
  non-zero before pushing. Pushing to a stale id fails with
  `java.sql.SQLException: Failed to update source code`, which does not
  hint at the real cause. If no `(Dev)` type exists, STOP - do not fall
  back to 1140, that is the production install.
  **Standing instruction (2026-08-14): push every dev iteration to the hub
  automatically, don't wait to be asked.** Before every push, run
  `./check_template.sh apps/automation_map.groovy` from the repo root - it
  catches a GString-backslash bug that blanks the whole rendered page with
  no error, and has already bitten this project three times. Run it before
  the push, not as an afterthought once something looks wrong.
- Hub Information Driver (reboot/shutdown/configure/updateCheck): device
  id 2843.
- Presence Manager: Gordon S25 sensor 2466, Tanya S25 sensor 2512,
  "Presence Manager Main Status" output child 3543 (driver "Presence
  Manager Output" - custom attributes lastEvidence/lastReason/
  confidence/lastChanged), Guest Mode child switch 3544.
- Gmail Notification Gateway: driver deviceTypeId 2576 (namespace
  "Hubitat Integrations"), device id 3363 "Gmail Broker". 7 apps send
  through it (Critical Device Monitor, Panic ON/OFF, Automation Map x2,
  Maker API Export, _System Start), so it is not a low-blast-radius
  device despite being a single virtual device.

Driver code is read/written with the *driver* equivalents of the app
endpoints: `GET /driver/ajax/code?id=<deviceTypeId>` and
`POST /driver/ajax/update` with `id`/`version`/`source`. Confirmed
working on 2.5.1.14x. `GET /driver/list/data` lists every driver type
(946 entries) with `type: usr` marking user drivers - that's how to find
a deviceTypeId without the UI.

## Full Hubitat hub/device control reference (consolidated 2026-08-08)

Hub: **10.0.0.125**, model **C-8**, firmware **2.5.1.14x**, location
"Volos Cove," Australia/Perth TZ, no Hub Login Security. This section
merges everything scattered across separate memory files into one place
- read this instead of hunting through individual files, though the
originals still have more narrative detail if needed:
`reference_hubitat_maker_api.md`, `feedback_hubitat_maker_api_guardrails.md`,
`feedback_hubitat_command_speed.md`,
`feedback_hubitat_maker_api_comma_splitting.md`,
`feedback_hubitat_sandbox_restrictions.md`,
`feedback_hubitat_lan_driver_dni_routing.md`,
`feedback_hubitat_verification_honesty.md`,
`feedback_hubitat_platform_behavior_confidence.md`,
`feedback_live_hub_testing_caution.md`,
`feedback_trust_gordon_device_ids.md`,
`feedback_hubitat_status_table_ui.md`,
`feedback_hubitat_test_buttons_real_logic.md`,
`reference_hubitat_hpm_publishing.md`.

### Two separate control surfaces - use the right one

1. **Maker API** (app id `2826`) - device attribute/command read-write.
   Endpoint details (incl. access token) are in
   `Downloads\Hubitat Apps\Maker Endpoints\Maker Export Device endpoints.txt`.
   `/devices/all` returns every device's current attributes AND available
   commands in one call. `/devices/[id]/commands` gives argument types.
   199 devices across 30 rooms as of the last full pull. An `_LLM`/`__LLM`
   -prefixed sandbox (ids 2999-3017, 3375, all LIFX Local + a "LIFX
   MASTER SWITCH" id 3010) exists specifically for me to control freely.
2. **Hub admin `/app/ajax/*`, `/installedapp/*`, `/device/*` endpoints**
   (see the direct-hub-workflow section above) - app/driver source code,
   installed-app settings/state, full device JSON. Different mechanism
   from Maker API - don't assume one covers what the other doesn't.

### Command guardrails (Maker API)

- **Free rein, no need to ask**: lights, switches, dimmers, sensors,
  scenes, groups, anything routine - includes the `_LLM` sandbox.
- **Always ask first**: HSM arm/disarm, garage door (device 3560),
  hub reboot/shutdown/rebootPurgeLogs (device 2843), sirens (Kitchen/
  Garage Dome). Gordon chose this tier deliberately because the token can
  touch home security and physical access.

### Speed practices (Maker API)

- Fire multi-device commands backgrounded in one Bash call
  (`curl ... & curl ... & wait`), not sequential loops or multiple
  round-trips.
- Don't re-fetch/verify after routine on/off/color/level commands unless
  something's reported wrong or the action is non-routine - report done
  on HTTP 200 and move on.
- **Python IS installed, but nothing finds it by name.** The real
  interpreter is
  `C:\Users\gordo\AppData\Local\Programs\Python\Python312\python.exe`
  (3.12.10) and it is **not on PATH**. `python`, `python3` AND `py` all
  fail: in Bash they hit the 0-byte Windows Store stub ("Python was not
  found; run without arguments to install from the Microsoft Store"), and
  in PowerShell `py` is simply absent. **Always invoke the full path.**
  Burned three tool calls on 2026-08-13 rediscovering this, twice, after
  an older version of this very note said only "python is broken, don't
  use it" - which sent me looking for workarounds instead of the binary.
  If a script genuinely needs running, use the full path; use PowerShell
  `ConvertFrom-Json` only for one-off parsing where a script is overkill.
- For routine command turns, keep the reply to one short confirmation
  line - no tables, no restating the device list, no explaining what was
  measured. Long conversation history (not command construction) is what
  actually drives perceived slowness, and that's not fixable by trimming
  a single reply further - if a session has done its exploration/setup
  and is just issuing routine commands, a fresh chat is faster than a
  long-running one.

### Known gotcha: Maker API splits commas into separate parameters

`/devices/{id}/deviceNotification/Subject: X,Body` arrives as two args,
matches no single-String method, driver silently never runs - but Maker
API still returns HTTP 200, so it looks like success. Fix: any driver
command taking free-text String needs overloads for 2..N parts that
rejoin with commas (`void cmd(Object p1, Object p2) { cmd([p1,p2].join(",")) }`).
The overloads are invisible to `/devices/{id}/commands` introspection.

### Email alerting is available on this hub - use it, don't reinvent it

Gordon has a working email notification path. Any Hubitat app or rule can
send email simply by sending notification text to the device **"Gmail
Broker" (device 3363, driver deviceTypeId 2576, Gmail Notification
Gateway v1.2.0)**. It implements `capability.notification`, so it appears
in any `capability.notification` device selector. Full detail in
`memory/project_hubitat_gmail_notification_gateway.md`.

Audience and subject are chosen per message with two optional prefixes,
each terminated by a comma, `Group:` first:

    Group: family,Subject: URGENT water leak,Leak in the laundry
    Group: family,Water leak in the laundry     (default subject)
    Subject: Water leak,In the laundry          (default group)
    Water leak in the laundry                   (both defaults)

Groups on Gordon's Apps Script: `user` = Gordon only, `family` and
`critical` = Gordon + Tanya. A group is an **audience, not a severity** -
urgency goes in the Subject. Real addresses live only in Apps Script; the
hub only ever sends a group name. Adding/renaming a group means editing
Apps Script, redeploying, AND updating the device's Known Recipient
Groups preference (an unknown group is rejected on the hub).

Three things to watch when wiring an app to it:

- **A `capability.notification` input is usually multi-select.** If a
  non-Gmail notifier (Pushover, SMS) is also selected, it receives the
  literal `Group: family,...` text as visible message body. Needs a
  Gmail-specific selection or an opt-in setting, not blind prefixing.
- **`lastStatus = sent` does not prove delivery.** Apps Script answers
  POST with an HTTP 302 the driver can't follow, so token errors, unknown
  groups and quota exhaustion all still report `sent`. Open fix for 1.2.1
  (drop `contentType: "application/json"` so Hubitat stops auto-parsing
  the response). Never tell Gordon an email definitely arrived on the
  strength of `sent` alone - the email itself is the proof.
- **Quota counts recipients, not messages** (~100/day consumer Gmail), so
  one `family` alert costs 2.

Apps Script deployment gotcha, cost an hour once: updating via **New
deployment** mints a NEW `/exec` URL and orphans the one Hubitat holds,
which then 404s and surfaces as the useless driver error "Lexing failed
... while reading '<'". Always **Manage deployments → pencil → New
version** to keep the URL stable.

### Groovy/Hubitat sandbox coding gotchas (local groovyc catches NONE of these)

- `System.currentTimeMillis()` / any `java.lang.System.*` call → blocked.
  Use `now()`.
- Top-level `static` fields/methods outside `@Field` → fails install with
  a generic, useless "notify the package developer" error. Just avoid
  `static` entirely (no existing Hubitat code in this project uses it) or
  use the proper `@Field static final` idiom deliberately.
- `.getClass()` / reflection generally → blocked, same error class as
  the `System.*` case. This is a *class* of restriction (reflective/JVM
  introspection calls), not a one-off - check for the whole class after
  fixing one instance of it, not just the specific line that broke.
- Direct references to `java.io.*` classes (e.g. `instanceof Reader`) →
  blocked. Prefer duck-typing/dynamic dispatch instead of `instanceof`
  against those.
- LAN drivers using `HubAction`/`sendHubCommand`/`parse()`: the response
  only reaches `parse()` if the Device Network ID is the hex-encoded IP
  of the device. Any other DNI (e.g. keyed off a cloud UUID) makes
  `parse()` silently never fire - no error, commands still work outbound,
  just no status update, ever, with clean debug logs. Prefer
  `asynchttpPost(callback, params, dataMap)` instead - it correlates
  request/response through its own callback, no DNI gotcha possible.
- **`timeOfDayIsBetween()` does not reliably handle an overnight window**
  (start time later in the day than end time, e.g. 21:30-06:00) → confirmed
  live TWICE, independently, in two different apps (Critical Device
  Monitor v1.9.1, BOM Weather Alerts v1.5.1) - both let an alert through
  in the middle of the night instead of holding it, no error thrown, no
  local-compile warning possible either way. Never use it for a
  quiet-hours/overnight check. Proven fix, copy this pattern verbatim
  instead of re-deriving it a third time:
  ```groovy
  def tz = location.timeZone
  def startCal = Calendar.getInstance(tz); startCal.setTime(toDateTime(settings.quietHoursStart))
  def endCal   = Calendar.getInstance(tz); endCal.setTime(toDateTime(settings.quietHoursEnd))
  def nowCal   = Calendar.getInstance(tz); nowCal.setTime(new Date())
  def startMin = startCal.get(Calendar.HOUR_OF_DAY) * 60 + startCal.get(Calendar.MINUTE)
  def endMin   = endCal.get(Calendar.HOUR_OF_DAY) * 60 + endCal.get(Calendar.MINUTE)
  def nowMin   = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
  if (startMin == endMin) return false
  if (startMin < endMin) return nowMin >= startMin && nowMin < endMin
  return nowMin >= startMin || nowMin < endMin  // overnight wrap
  ```
  `Calendar` itself is confirmed fine in the sandbox (this exact code runs
  live on Gordon's hub in both apps). Wrap in try/catch and fail open
  (return `false`/not-quiet) on any exception, so a bad time setting can
  never silently eat a real alert.

- **`state.*` flags written from an event handler (not a scheduled job)
  are a race condition waiting to happen** → confirmed live TWICE
  independently (Critical Device Monitor v1.6.2's water/smoke/CO
  `safetyAlerted` map, BOM Weather Alerts v1.5.2's `tempHighAlerted`/
  `tempLowAlerted`). `state` is a per-execution cached copy, written back
  in full at the end of each app method run - two events arriving close
  together can each read the flag as unset before either writes back
  `true`, so both fire, producing a duplicate alert with the same
  reading. No error, no local-compile warning, only shows up as an
  oddly-duplicated message in a live notification. Fix: use `atomicState`
  instead for any flag written inside a `subscribe()` callback - it
  reads/writes storage directly on every access, no per-execution
  caching. Don't blanket-convert every `state` usage in an app though -
  this only matters for flags actually written from event handlers;
  scheduled-job-only state has no realistic concurrent-execution exposure.

**After any local groovyc "compiles clean" check**: say explicitly that
this only proves standard Groovy syntax, not hub-sandbox compatibility -
never let a clean local compile imply the code will load on the hub.

### Live-hub testing caution (separate from routine Maker API commands)

This is about deploying/testing *code changes*, not issuing device
commands - much higher stakes:

1. Confirm a backup exists before touching anything live, always.
2. Prefer HPM's update/repair flow over manual Apps-Code-editor imports
   when a package is HPM-managed.
3. Keep iteration inside an isolated dev install (separate namespace/
   DNI/app instance) for the whole session - don't "graduate" a fix to
   production mid-session even if it looks minimal and well-diffed.
4. If a production fix is identified, hand over the diff/description and
   let deploying it be Gordon's own separate, deliberate decision.
5. Don't scope-creep a live test by syncing unrelated files "for
   cleanliness."
6. Something inexplicable on a live hub → immediate rollback and stop,
   not continued live diagnosis.
7. If both a known-good and a modified copy of a file are already in
   hand, diff them directly before any live UI troubleshooting - static
   comparison is always cheaper and more reliable.
8. Before suggesting deletion/uninstall of any parent app, check that
   app's own `uninstalled()`/cleanup logic for cascading side effects
   first (e.g. it may cascade-delete child devices actual rules/
   dashboards depend on) - true even for app code written earlier the
   same session.

**Don't assert platform/HPM safeguards will hold without hedging** (e.g.
"`singleInstance: true` will prevent a duplicate install" - it didn't,
HPM created a second Apps Code entry instead of matching up to an
existing manually-pasted one). Before predicting how an install/update/
migration will behave on the live hub, either say plainly it's expected-
but-unverified, or point at a cheap pre-check to do first - not just a
post-hoc diagnostic checklist after something's already gone wrong.

### Trust Gordon's own device/ID identifications

When he states a specific ID fact (a MAC belongs to a named device, etc.)
he's typically already verified it himself - use it as ground truth
rather than re-verifying or hedging against it. Explaining *why* code
currently disagrees is still fine and useful; arguing his stated fact is
wrong is not.

### Browser tool doesn't work against 10.0.0.125

Hit a persistent "this site requires per-action approval" block that
Gordon's verbal approval couldn't clear - looks like a hardcoded
restriction on browser-tool access to local/private network addresses,
not fixable from either side. **Use curl/Maker API for hub device state,
not the Claude_Browser tool.** Custom app-rendered dynamicPage UI (e.g. a
diagnostic-mode table) genuinely isn't reachable any other way, so
screenshots are still needed for that specific case - but plain device
attributes and app state should always go through direct HTTP first.

### UI conventions for Hubitat app pages

- Status/summary tables: one shared `<table>` across all groups, not a
  separate table per group (per-group tables auto-size columns from
  their own content and drift out of alignment). Wrap in
  `<div style="overflow-x:auto">` with `table-layout:fixed` and
  `<colgroup>` widths so mobile scrolls instead of squishing.
- Test buttons: wire to the actual check function against real
  configured devices/thresholds, not a canned message - a canned message
  only proves the notification *delivery* mechanism works, not the check
  logic itself (this caught two real logic bugs in Critical Device
  Monitor's daily digest that a canned test never would have).

### HPM publishing mechanics

Gordon's package index is `repository.json` on **main** of
`hubitat-LIFX-Light-Manager`, already registered upstream - adding a new
package needs **no PR**, just append an entry (name/category/location→
packageManifest.json/description/tags/id) and push to main. Watch out:
LIFX repo's **dev branch has a different, private-test-channel
repository.json** - never merge dev to land an index change, commit to
main directly via a separate clone if possible so the dev checkout stays
untouched. Validate categories/tags against
`HubitatCommunity/hubitat-packagerepositories`'s `settings.json` or the
package won't show under Browse by Tags. HPM installs Groovy only - it
can't do external setup (e.g. a companion Google Apps Script), so
packages needing that must say so in `releaseNotes`.

### Rule Machine confirmed facts (Volos Cove C8, build 2.5.1.14x)

- **CORRECTED 2026-08-13. The previous note here said "standard operator
  precedence (AND binds tighter than OR)". That is WRONG and it is the
  opposite of what was measured.** Mixed AND/OR with no explicit grouping
  is read **right-associatively**, rightmost operator applied first. Two
  live tests, `A AND B OR C` with F,F,T giving FALSE (2.5.1.140) and
  `A AND B OR C AND D` with T,T,F,F giving TRUE (2.5.1.147). Right
  grouping is the only model fitting both; conventional precedence and
  left-to-right are ruled out by the first, OR-binds-first by the second.
- **The consequence that bites real rules: a term to the right of an OR
  is unreachable whenever the OR's left operand is true.** A Private
  Boolean placed last in `Mode AND Evening OR Morning AND PB` is ignored
  all evening. Found live as a lamp that never turned off. **Fix by
  putting the guard FIRST**, not by adding an `IF ... THEN Exit Rule`
  action - Private Boolean is absent from RM 5.1's Action Condition
  capability list, which is a different list from the Required Expression
  one, so that action cannot be built.
- Before proposing a live precedence test, work out which condition
  values actually discriminate between the candidate models FIRST. Three
  rounds were wasted reading screenshots of states that evaluate
  identically under every model.
- Full detail and evidence markers live in the LIVING document
  `Downloads\Hubitat Apps\hubitat-automation-map\Supporting Docs\rule_machine_5_1_storage_format.md`.
- "Manage Conditions" **cannot** hold a compound multi-device OR/AND as
  a single reusable named Condition - confirmed by Gordon directly, don't
  suggest that fix again.
