# How Rule Machine 5.1 stores a rule

Notes for anyone building a tool that reads Rule Machine rules from a Hubitat hub.

**Status:** derived empirically from a C-8 running platform 2.5.1.142, cross-checked against
each rule's own page in the Rule Machine UI. Everything below was verified against a live
hub with 38 Rule-5.1 rules.

---

## 1. Scope and warnings

This describes how Rule Machine **stores** a rule, not how it **executes** one. For
execution semantics (delays, waits, retriggering, simultaneous instances) the official
[Rule 5.1 documentation](https://docs2.hubitat.com/en/apps/rule-machine/rule-5-1) is the
authority and covers the ground properly.

Three warnings before you build anything on this:

- **None of it is a public API.** These are the hub's own internal endpoints, the ones its
  administration UI calls. Field names, shapes and semantics can change in any platform
  release, without notice, because nothing here is a documented contract.
- **Read-only.** Everything below is about reading. Writing rule configuration through
  these structures is not covered and is not advisable.
- **Treat the rule page as the authority.** When your reconstruction disagrees with what
  Rule Machine shows on the rule's own page, your reconstruction is wrong. That rule of
  thumb caught every bug described in section 9.

### The short version

If you read nothing else, read these:

- An action's parameters are **not** in the action. They are in the app's settings, keyed by
  the action number. Neither half is usable alone (section 3).
- Every action carries a field called `rule` that is **not a rule reference**. It is a
  condition index (section 9.1).
- `indent` does not reliably describe nesting. Do not build a tree from it (section 9.2).
- `eventSubscriptions` is a snapshot that changes with Required Expression state, so a
  rule's triggers can appear to vanish (section 10.3).

---

## 2. Where the data lives

    GET /installedapp/statusJson/<installedAppId>

Returns the whole runtime picture of one installed app:

| Key | Contents |
| --- | --- |
| `installedApp` | id, label, name (the app type), `appTypeId`, `parentAppId`, disabled |
| `appSettings` | every setting, with `deviceList` resolving device ids to names |
| `appState` | every state entry, which for a rule is where the structure lives |
| `eventSubscriptions` | what the app is subscribed to **right now** |
| `scheduledJobs` | pending scheduled work |
| `childDevices` | devices the app created |

Note `appTypeId` sits inside `installedApp`, not at the top level.

**Built-in apps are readable this way even though their source is not.** `GET
/app/ajax/code?id=<appTypeId>` returns an empty body for system apps, because they are
compiled classes rather than user code. Rule Machine is one of them. Its entire rule
structure is nevertheless sitting in `appState`, which is why decoding rules is possible at
all without access to a line of Rule Machine's source.

---

## 3. The shape of a rule

The single most important structural fact: **a rule is stored in two halves that must be
joined by action number.**

`appState.actions` is a map keyed by action number, and holds what *kind* of action it is:

    "6": { "quick": false, "method": "getIfThen", "indent": "", "rule": 3 }

`appSettings` holds that action's *parameters*, keyed `<prefix>.<actionNumber>`:

    actType.6      = condActs
    actSubType.6   = getIfThen

Neither half is usable on its own. The action object tells you an On/Off switch action
exists; only the settings tell you which device and whether it is on or off.

Two settings always accompany an action:

| Setting | Meaning |
| --- | --- |
| `actType.<n>` | the action *family*, e.g. `switchActs`, `dimmerActs`, `condActs`, `delayActs`, `rulesActs` |
| `actSubType.<n>` | the specific action, matching the `method` in the action object |

`actSubType` duplicates `method`. Prefer whichever you like, but note `method` is absent
from the action object for some actions while `actSubType` has been present in every case
observed, so `actSubType` is the safer primary key.

---

## 4. Execution order

`appState.actionList` is the ordered list of action numbers.

**It is not the numeric order of the keys, and not the insertion order.** A real example:

    actionList: 6, 1, 9, 10, 11, 3, 4

Action 6 runs first and action 3 runs sixth. Action numbers are stable identifiers assigned
when an action is created; reordering actions in the UI rewrites `actionList` and leaves the
numbers alone. Iterating the `actions` map directly will give you a rule in an order that
resembles the user's rule only by accident.

---

## 5. Conditions and expressions

Conditions are numbered independently of actions, and are stored across several structures.

### 5.1 Human-readable text

`capabstrue` and `capabsfalse` together describe **every** condition in plain text. The
split between them is only whether the condition currently evaluates true:

    capabsfalse["11"] = "Time between 22:00 and 06:00"
    capabsfalse["2"]  = "Illuminance of Back Garden Left, Back Garden Right any is < 500"
    capabstrue["1"]   = "Guest Toilet Motion Sensor motion reports active"

Merge both maps to get the full set. Do not read anything into which map a condition landed
in beyond its truth at the moment you fetched.

The text carries HTML, so strip tags before displaying it.

### 5.2 Expressions

`eval` maps a branch number to the condition expression for that branch:

    eval["0"] = [2, "OR", 9]
    eval["2"] = 6
    eval["3"] = "11"

**The value type is inconsistent.** In one rule it is a list of condition numbers and
operators, a bare integer, and a quoted string. Coerce to string and handle all three, or
you will crash on rules that happen to have a single-condition branch.

`eval["0"]` is the Required Expression when `hasPredicate` is true. `predCapabs` lists the
condition numbers it involves, with duplicates, so deduplicate if you use it.

### 5.3 Condition definitions

Each condition also has settings describing how it is built:

    rCapab_2    = Illuminance          the condition type
    state_2     = 500                  the comparison value
    RelrDev_2   = <                    the operator
    rCapab_11   = Between two times
    starting11  = A specific time
    startingA11 = 22:00
    ending11    = A specific time
    endingA11   = 06:00

You rarely need these if you are using `capabstrue`/`capabsfalse`, which already render the
condition in words. They matter if you want the raw values rather than the prose.

---

## 6. Separating triggers from conditions

This is the most useful distinction in the whole format and the basis for classifying what
role a device plays in a rule.

| Prefix | Meaning |
| --- | --- |
| `tDev<n>` | devices that **trigger** condition n |
| `rDev_<n>` | devices used in condition n as a **condition** |

    tDev1   -> Guest Toilet Motion Sensor        (the trigger)
    rDev_2  -> Back Garden Left, Back Garden Right  (an illuminance gate)
    rDev_6  -> Guest Toilet Motion Sensor        (the same device, as a condition)

The same physical device appears as both, and means different things each time. A tool that
keys on device id alone cannot tell a rule's trigger from its gating conditions; a tool that
keys on the setting prefix can.

**Note the inconsistent underscore.** It is `tDev1` but `rDev_2`. Likewise `tCapab1` and
`tstate1` against `rCapab_2` and `state_2`. This is not a typo in this document.

---

## 7. Action parameters by family

Once you know an action's `actSubType`, its parameters follow a `<prefix>.<n>` convention.
A non-exhaustive list of ones confirmed on a live hub:

| Action | Parameter settings |
| --- | --- |
| `getOnOffSwitch` | `onOffSwitch.<n>` device, `onOff.<n>` true/false |
| `getSetColorTemp` | `ct.<n>` device, `ctL.<n>` kelvin, `ctLevel.<n>` level |
| `getSetVolume` | `volume.<n>` device, `volumeVal.<n>` level |
| `getMsg` | `msg.<n>` text, plus a device picker for the target |
| `getDelay` | `delaySecond.<n>`, `delayMin.<n>`, `delayAct.<n>` |
| `getWaitRule` | condition via the action's `rule` field, `delay` on the action for timeout |
| `getIfThen`, `getElseIf` | condition via the action's `rule` field |

Most actions also carry `delayAct.<n>`, which is `none` unless the individual action has its
own delay.

The reliable general approach is: for action `n`, collect every setting whose name ends in
`.<n>`. That finds the parameters without needing a table for every action type, which
matters because the list above is certainly incomplete.

---

## 8. Acting on other rules

Actions that target another rule all share `actType.<n> = rulesActs` and follow one shape:
the target is a list of installed app ids in a setting, with a companion setting naming the
engine.

| `actSubType` | Rule Machine calls it | Target setting | Engine setting |
| --- | --- | --- | --- |
| `getRuleActions` | Run Actions | `ruleAct.<n>` | `runRuleType.<n>` |
| `getStopActions` | Cancel Timed Actions | `stopAct.<n>` | `stopRuleType.<n>` |
| `getPauseResumeRules` | Pause Rules | `pauseRule.<n>` | `pauseRuleType.<n>` |
| `getSetPrivateBoolean` | Rule Boolean True/False | `privateT.<n>` | `pvRuleType.<n>` |

Note the UI wording differs from the method name. `getStopActions` is presented as **Cancel
Timed Actions**, not "Stop Actions". Deriving a label from the method name produces text the
user has never seen.

### Target values

    ruleAct.4    = ["1806"]
    privateT.31  = ["*","1809"]

`"*"` means **this rule**. Critically, it can appear **alongside** real targets: `["*","1809"]`
is Rule Machine's way of storing "set the Private Boolean of this rule *and* Perimeter
Closed". Treating the presence of `"*"` as meaning self-only will silently drop genuine
cross-rule references. Stripping non-digits handles it cleanly.

`actType.<n> = rulesActs` also covers actions with no target at all, so check `actSubType`
before assuming a target setting exists.

---

## 9. Traps

Each of these cost real debugging time.

### 9.1 The `rule` field is not a rule reference

Every action object carries a field named `rule`:

    { "method": "getIfThen",   "rule": 3 }
    { "method": "getWaitRule", "rule": 2, "delay": "0:02:00" }

It is a **condition index**, used to look up `eval[<rule>]`. It is used by `getIfThen`,
`getElseIf` and `getWaitRule`.

Reading it as a target rule id produces confident, entirely fictional rule-to-rule links,
one for every conditional and wait on the hub. On a 38-rule hub that was 28 fabricated
relationships. Real rule targets live in the settings described in section 8.

### 9.2 `indent` does not describe nesting

Actions carry an `indent` string of tab characters. It disagrees with actual nesting: on one
observed rule the IF is at `""` while its own `getEndIf` is at `"\t"`, and another rule opens
three IFs and closes two.

Build structure from the control-flow markers instead: `getIfThen`, `getElseIf`, `getElse`,
`getEndIf`, maintaining your own stack. Use `indent` for nothing.

### 9.3 `pvTF` reads inverted

`pvTF.<n>` accompanies `getSetPrivateBoolean` and looks like the value being written. It is
backwards against the rule page in every observed case:

| Rule | Action | Position | `pvTF` | Rule page shows |
| --- | --- | --- | --- | --- |
| 1806 | 31 | second | `true` | Rule Boolean **False** |
| 1806 | 33 | last | `false` | Rule Boolean **True** |
| 2972 | 7 | sixth | `true` | Rule Boolean **False** |

Ordering is not the explanation: every other action of rule 1806 matches its page exactly in
order. Whatever `pvTF` means, it is not straightforwardly "the value set". Three consistent
samples are not enough to justify rendering `!pvTF`, so the safe move is to show nothing.

### 9.4 Labels carry hub-injected HTML

An app's label is not clean text. Hubitat appends status markup:

    Guest Toilet <span style='color:red'>(Required Expression false)</span>

Strip tags. Note the parenthetical text survives stripping, which is usually what you want,
since it is real information.

### 9.5 Groovy: a GString key never matches a String key

Not a Rule Machine issue, but it will bite anyone parsing this inside a Hubitat app.
Building a map with `vals["${s.name}"] = ...` stores a **GString** key, and looking it up
later with another GString of identical text misses, because their hash codes differ. Assign
through a `String`-typed local first:

    String n = "${s.name}"
    vals[n] = v

---

## 10. What the data cannot tell you

Being clear about the limits matters as much as the format.

### 10.1 A Rule Function is indistinguishable from an ordinary rule

A Rule Function reports `installedApp.name` of `Rule-5.1`, exactly like any other rule, and
a `Run Actions` call targeting one stores `runRuleType = "Rule Machine"`, exactly like a
call targeting an ordinary rule. No examined field distinguishes them.

In practice this does not matter for reading the link, since the target id resolves either
way. It matters if you want to label the two differently.

### 10.2 Pause cannot be told from Resume

Both use `getPauseResumeRules`. A setting `pR.<n>` looks like the discriminator but was
empty on the only available example, which the rule page displayed as a Pause.

### 10.3 `eventSubscriptions` is a snapshot, not a definition

The worked example below demonstrates this live. Rule Machine removes a rule's trigger
subscriptions while its Required Expression is false, so a rule whose trigger is a motion
sensor can show no subscription to that sensor at all.

For Rule Machine specifically this does not matter, because triggers are recorded in
`tDev<n>` settings and can be read directly. It matters greatly for **other** apps, where
subscriptions may be the only evidence available, and it means two scans minutes apart can
legitimately disagree.

---

## 11. Finding rules in the first place

There is no bulk app-list endpoint. `/app/list` and `/installedapp/list` return a JavaScript
application shell of about 6KB with no app data in it; a browser executing the JavaScript
renders the list, but plain HTTP does not.

Two workable routes:

- **From a known id.** `/installedapp/statusJson/<id>` gives you `appTypeId` and everything
  else. Getting that first id usually means reading it out of the URL bar while the app's
  page is open.
- **Through devices.** `/device/fullJson/<deviceId>` returns `appsUsingForDialog`, the apps
  that reference that device. Walking every device discovers most apps.

The device route has two known blind spots. An app referencing no devices is invisible
entirely, which is the normal case for a Rule Function. And `appsUsingForDialog` is
**truncated** when many apps use one device, so an app appearing only in truncated lists is
missed. On a 193-device hub this caused one real rule to be missed, discovered only because
another rule named it as a target.

---

## 12. Worked example

Rule **Guest Toilet**, installed app 2290. Its page shows a Required Expression, a motion
trigger, an IF/ELSE setting two different colour temperatures, then a two-minute wait and a
switch off.

### Raw

    actionList:   6, 1, 9, 10, 11, 3, 4
    hasPredicate: true

    actions:
      6:  { method: getIfThen,      indent: "",   rule: 3 }
      1:  { method: getSetColorTemp, indent: "" }
      9:  { method: getElse,        indent: "\t" }
      10: { method: getSetColorTemp, indent: "\t" }
      11: { method: getEndIf,       indent: "\t" }
      3:  { method: getWaitRule,    indent: "",   rule: 2, delay: "0:02:00", wait: 2 }
      4:  { method: getOnOffSwitch, indent: "" }

    eval:
      0: [2, "OR", 9]
      2: 6
      3: "11"

    capabstrue:   1  -> "Guest Toilet Motion Sensor motion reports active"
    capabsfalse:  2  -> "Illuminance of Back Garden Left, Back Garden Right any is < 500"
                  6  -> "Guest Toilet Motion Sensor motion is inactive"
                  9  -> "Time between Sunset(18:05) and Sunrise(06:33)"
                  11 -> "Time between 22:00 and 06:00"

    tDev1  -> Guest Toilet Motion Sensor
    rDev_2 -> Back Garden Left, Back Garden Right
    rDev_6 -> Guest Toilet Motion Sensor

    ct.1 -> _LLM Guest Toilet Light,  ctL.1  = 2500, ctLevel.1  = 1
    ct.10 -> _LLM Guest Toilet Light, ctL.10 = 2500, ctLevel.10 = 40
    onOffSwitch.4 -> _LLM Guest Toilet Light, onOff.4 = false

### Decoded

**Trigger.** `tDev1` names the trigger device, and condition 1 renders it: Guest Toilet
Motion Sensor becomes active.

**Required Expression.** `hasPredicate` is true, so `eval[0]` applies: `[2, "OR", 9]`, which
is condition 2 OR condition 9, that is illuminance below 500 OR between sunset and sunrise.
A darkness gate.

**Actions**, walked in `actionList` order:

| # | Action | Resolution |
| --- | --- | --- |
| 6 | `getIfThen` | `rule: 3` to `eval[3] = "11"` to condition 11, between 22:00 and 06:00 |
| 1 | `getSetColorTemp` | `_LLM Guest Toilet Light` to 2500K at level 1 |
| 9 | `getElse` | |
| 10 | `getSetColorTemp` | `_LLM Guest Toilet Light` to 2500K at level 40 |
| 11 | `getEndIf` | |
| 3 | `getWaitRule` | `rule: 2` to `eval[2] = 6` to condition 6, motion inactive, timeout 0:02:00 |
| 4 | `getOnOffSwitch` | `onOff.4 = false`, so off |

Reading out: on motion, if it is between 22:00 and 06:00 use a dim 2500K, otherwise a
brighter 2500K, then wait for motion to stop with a two-minute timeout, then turn off.

Which is what the rule's own page says.

### The subscription trap, live

This rule's label at the time of reading was `Guest Toilet (Required Expression false)`, and
its `eventSubscriptions` were:

    DEVICE   / Back Garden Left
    DEVICE   / Back Garden Right
    LOCATION / Volos Cove (C8)

**The trigger device is not in that list.** Guest Toilet Motion Sensor, the entire reason
this rule exists, has no subscription at all, because the Required Expression is false and
Rule Machine has removed it. What remains is exactly what the rule needs to notice the
Required Expression becoming true again: the two illuminance sensors and the location, for
the sunset/sunrise condition.

Anything inferring this rule's triggers from `eventSubscriptions` would conclude it is
triggered by garden illuminance. Reading `tDev1` gives the right answer regardless of when
you look.
