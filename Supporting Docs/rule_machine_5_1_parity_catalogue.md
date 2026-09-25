# Rule Machine 5.1 parity catalogue

What RM 5.1 can express, checked against what HAI-1 publishes. Written as a delta: HAI's
feed already lists 156 capabilities, so this records what is **not** in that list, what is
ambiguous, and what RM does badly enough that copying it would be a mistake.

**Sources**
- Hubitat's published Rule 5.1 page, read in a browser because the page is script-rendered
  and returns a title only to a plain fetch. **[external]**
- The hub's own live action schema (`set_rule_reference_actions`), which is generated from
  RM's wizard and is more detailed than the published page. **[strong]**
- The settings of a real rule on this hub (app 2112), read directly. **[strong]**
- HAI feed `hai.am/1`, `HAI-1/m5b-0.2.0-dev`, 156 capabilities: 151 Runs, 3 Partial,
  2 Missing.

**Method note.** Some RM action subtypes only render on a hub that owns a matching device,
so the hub alone undercounts. Garage door and valve open/close are the known examples. The
published page was used to cover that.

---

## 1. The modifier class

This is the finding that matters. RM has two kinds of thing: **action types**, which appear
as their own row in the actions list, and **modifiers**, which hang off an existing action
as extra settings. Every capability id in HAI's feed maps to an action type, a trigger, a
condition or a rule-level option. No id in the feed describes a modifier.

A rule can therefore use only action types HAI supports and still be unconvertible.

| Modifier | Where it applies | RM settings | Present in HAI's 156 |
| --- | --- | --- | --- |
| Meter? / Meter milliseconds | any multi-device action | `meter.<n>`, `meterMillis.<n>` | Yes, as `meterMs`, hub-proven |
| Delay? on the action itself | most actions | `delayAct.<n>`, `delayHour/Minute/Second.<n>` | Yes |
| Cancelable? | any delay | `cancelAct.<n>` | Yes |
| Random | delay | `randomAct.<n>` | Yes, as `randomSeconds` |
| Only switches that are on | switch on/off | `onlyOn` | Yes, as `onlyIfOn` |
| Random message | notify / speak | `ranMsg.<n>` | **Genuinely absent** |
| Speak at a set volume | speak | `speakVolume.<n>` | Yes, as the optional 2nd argument of `speak` |
| Use Last Event Device | Run Custom Action device picker | `useLastDev.<n>` | Yes, action side works (`coreIsEventDevice`) |
| Parameters on a custom action | Run Custom Action | parameter slots, literal or variable-sourced | **Partly**: literals only on Run Custom Action |
| Track event switch / dimmer | switch on/off, set dimmer | - | Yes |
| Variable-sourced value | dimmer level, delay, custom action parameters | `uVar.<n>` + `xVar` | Partly |
| And stays? | triggers, and per-event on Wait for Events | `stays<n>`; waits use dash-indexed `SHours-/SMins-/SSecs-<n>` | Yes, a wait event is written as a trigger with `duration` |
| Timeout? | both wait types | - | Yes (`timeoutSeconds` + `onTimeout`) |
| All of these? | Wait for Events | - | Yes (`match` any/all) |
| Use Duration? | Wait for Expression | - | Yes |
| Stoppable? / Repeat n times | Repeat | - | Yes |
| Disable an individual action | every action | `disable<n>` | Yes, listed twice |
| Conditional Trigger? | every trigger | `isCondTrig.<n>` | Yes |

### The real gap is the feed's vocabulary

Checked against HAI's implementation on 2026-09-20, four of the five modifiers this
catalogue first reported as absent were already built, shipped and in one case hub-proven.
They were invisible because **HAI's capability ids are generated from tracker rows, and a
modifier is a field on an action rather than a row of its own.** A modifier can therefore be
fully implemented and still appear nowhere in the 156.

So the parity gap is not mostly capability. It is that the feed has no vocabulary for
modifiers, which makes any consumer of it, including this catalogue and the Automation Map
coverage report, unable to see that half of RM's surface either way. Two real defects did
surface underneath it, listed below. **[strong]**

**Genuinely absent:** random message on notify/speak.

**Corrected overclaim:** Run Custom Action arguments must be literals. A variable reaches
them only as `%name%` inside a text slot, so a numeric slot on a custom action cannot be
variable-sourced. Catalogue commands do accept variable-sourced values in any slot.

**Guard gap, not a capability gap:** the validator's rule refusing `eventDevice` when no
device trigger can start the rule is written for `command` only, so a custom action aimed at
the last event device in a rule with no device trigger passes validation with nothing to
aim at.

**Metering is undocumented by Hubitat.** It is not mentioned anywhere on the published
Rule 5.1 page. It exists in the wizard as a "Meter?" toggle with a "Meter milliseconds"
field, and in settings as the two keys above. Storage detail is in
`rule_machine_5_1_storage_format.md` section 7.1. **[strong]**

---

## 2. Gaps HAI already declares

Taken from its own feed, not re-derived.

| Capability | Status |
| --- | --- |
| `action.devices-disable-enable-devices` | Missing |
| `action.devices-start-stop-z-wave-polling` | Missing |
| `action.thermostats-set-thermostats-thermostat-scheduler` | Partial |
| `action.rules-activate-room-lights-for-mode-period-turn` | Partial |
| `trigger.digital-switch-physical-switch-physical-dimmer-l` | Partial |

---

## 3. RM behaviour worth not inheriting

Parity does not mean copying the warts. Each of these is a documented or observed RM
limitation, not a design choice to reproduce.

1. **One Wait for Events per rule.** RM stores wait events in per-rule settings rather than
   per-action, so a second one silently overwrites the first. Nothing forces that on a new
   engine, and HAI confirms it does not inherit it. **[strong]**
2. **No stop-flash action.** `flash` starts a schedule that `on`/`off` does not cancel; the
   only escape is a custom action calling `flashOff`. **[strong]**
3. **No variable-sourced fan speed.** RM exposes a variable toggle on numeric and text
   fields but not on enum pickers, so fan speed cannot take a variable while dimmer level
   can. An engine can be uniform here. **[strong]**
4. **A per-action Delay? does not pause the script.** The delayed action is scheduled and
   execution continues immediately to the next action. This is a long-standing user trap
   and is worth making explicit rather than implicit. **[external]**
5. **Nested repeats are unsupported.** **[external]**
6. **Two triggers on the same attribute of the same device are unsupported**, relaxed only
   at platform 2.4.1 and only when the stays values differ. **[external]**
7. **Local variables cannot be used in required expressions**, because they generate no
   events and the expression never re-evaluates. A silent failure mode. **[external]**
8. **"Ignore trigger events while running" shipped in 2.3.9 only** and Hubitat's own docs
   say it has unexpected outcomes and will not be changed. **[external]**
9. **Interrupted edits leave orphaned action rows** in settings: keys written for an action that
   was never completed, which do not run and are not part of the rule. **The index is not held
   permanently.** An independent reproducer on 2026-09-25 (one log action, an incomplete wizard
   drive leaving an orphan at index 2, then a second add) had the next action **reuse index 2**,
   commit cleanly, and health then report no orphan rows, with the earlier device-list key still
   sitting underneath. An earlier claim here that such a row holds its index forever was wrong.
   Note also that the tool's own health text asserts "new actions are allocated above it", which
   that reproducer contradicts. **[strong]**
10. **Metering exists only on multi-device actions.** Pacing is a general need, not a
    per-action one. See section 4.

---

## 4. Pacing, and why it earned its own section

On 2026-09-20 this hub spent roughly thirty minutes unusable because one action issued
`initialize()` at thirteen Google Cast devices at once. Each unreachable device blocked a
device thread for its full timeout, measured at 130s, 261s and 393s, which is one timeout
retried up to three times. During that window a text-to-speech announcement took 4m23s to
reach a speaker, an unrelated Rule Machine rule blocked for 504s, and the hub throttled
three separate apps with `LimitExceededException`. **[strong]**

RM's answer is metering, which spaces dispatch but does nothing about how long each call
blocks. It only caps concurrency when the spacing exceeds a single device's response time,
which for a 393s timeout is not a practical setting.

An engine that models the cost of a device call can do better: cap concurrent in-flight
commands rather than space them by a fixed interval, and treat an unreachable device as a
result rather than as a thread to sit on.

---

## 5. Caveat on the coverage number

Automation Map's RM coverage report tokenises `actSubType`, `tCapab`, `rCapab_` and four
structure flags. No modifier in section 1 is in that set. "62 of 62 rules covered, zero gap
constructs" therefore means every action **type** in use has an HAI equivalent. It is not a
statement that those rules would convert. The report needs a modifier pass before the number
carries that weight.
