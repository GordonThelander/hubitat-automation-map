# Rule Machine 5.1 Execution Explained

## Events, waits, delays, retriggering and rule-to-rule execution

**Status:** Draft for validation  
**Source review date:** 11 August 2026  
**Scope:** Hubitat Rule Machine 5.1 execution semantics, not a feature-by-feature user manual  
**Validation status:** Source-grounded draft with explicit hub-test placeholders

---

## Purpose

Rule Machine is easy to misread as a small procedural scripting language: a trigger starts at the top, actions run down the page, a delay appears to pause execution, and execution eventually reaches the bottom.

That mental model is useful for simple rules, but it becomes misleading as soon as a rule contains waits, delays, repeated triggers, nested conditional actions, or calls to another rule.

A more accurate model is:

> **Rule Machine is an event-driven automation engine. A rule is activated by events or schedules, executes actions until it completes or yields work back to the platform, and may leave behind subscriptions or scheduled jobs that cause later execution.**

This document explains that execution model. It deliberately does **not** catalogue every Rule Machine trigger, condition, action or device capability. The official Rule 5.1 documentation already serves that purpose. [1]

### Evidence notation

| Marker | Meaning |
|---|---|
| **Documented** | Behaviour is stated in current Hubitat documentation. |
| **Author-confirmed** | Behaviour has been explicitly explained by Bruce Ravenel, author of Rule Machine. |
| **VALIDATE Txx** | Behaviour should be confirmed on the target hub/platform before publication. |

---

# 1. The mental model

At its simplest, a Rule Machine rule can be viewed as this lifecycle:

```text
Hubitat event or schedule
          |
          v
Trigger subscription receives event
          |
          v
Rule is admitted for execution
          |
          v
Actions execute
          |
          +---- immediate actions
          |
          +---- IF / ELSE evaluation
          |
          +---- WAIT ----------> subscription / timer left behind
          |
          +---- DELAY ---------> scheduled continuation left behind
          |
          v
Current execution ends
          |
          v
A later event or timer may invoke the rule again
```

The important word is **invoke**.

A rule that appears to be "sitting in a ten-minute delay" is not necessarily an execution thread sitting idle for ten minutes. Bruce Ravenel has explained that a delay causes the rule to exit after scheduling a future event that wakes it later. Likewise, a Wait exits the current rule instance while leaving behind the event subscriptions or scheduled jobs required to resume it. [4]

This distinction explains much of Rule Machine's behaviour under retriggering.

**Author-confirmed:** Delay and Wait are both yield points. They do not simply block a continuously running script. [4]

**[VALIDATE T03, T05]** Confirm the observable scheduled-job/subscription state on the target platform.

---

# 2. Events are not states

Hubitat applications are event-driven. Apps subscribe to device or location events and may also create scheduled jobs. The platform invokes an app handler when a subscribed event occurs or a scheduled job becomes due. [2]

Rule Machine builds on the same model.

Consider a contact sensor:

```text
State:
Door contact = open

Events:
10:02:14 contact changed to open
10:07:51 contact changed to closed
```

The state answers:

> What is true now?

The event answers:

> What happened?

This difference is fundamental.

A trigger normally reacts to an event. A conditional action such as an `IF` evaluates state at the point execution reaches it.

Therefore:

```text
Trigger: Door opens
```

means "start when an open event occurs".

Whereas:

```text
IF Door is open THEN
```

means "when execution reaches this line, inspect whether the current contact state is open".

An `IF` statement does not continue monitoring that condition after the evaluation. If the rule needs to stop and wait for something to happen later, it needs a Wait or another event-driven mechanism.

### Practical consequence

This rule:

```text
Trigger: Motion active

IF Door is closed THEN
    Turn light on
END-IF
```

does not mean:

```text
When motion occurs, wait until the door closes, then turn the light on.
```

It means:

```text
When motion occurs:
    check the door immediately;
    if it is closed at that instant, turn the light on;
    otherwise continue past the IF without waiting.
```

**Documented/established:** Rule Machine distinguishes event-triggered behaviour from condition/state evaluation. [1]

**[VALIDATE T02]** Capture logs proving that a false `IF` does not create a subscription or scheduled continuation.

---

# 3. Triggers and Required Expressions

A Required Expression is more than an `IF` statement placed at the beginning of a rule.

Bruce Ravenel has explained that when a Required Expression becomes false, Rule Machine can remove the rule's trigger subscriptions or schedules. The rule retains only what it needs to detect that the Required Expression may have become true again. When it becomes true, the trigger subscriptions are restored. [3][8]

Conceptually:

```text
Required Expression = TRUE
        |
        +---- trigger subscriptions installed
        |
        v
Trigger event can invoke the rule


Required Expression = FALSE
        |
        +---- trigger subscriptions removed
        |
        +---- subscriptions/schedules needed to detect RE change remain
```

That is materially different from:

```text
Trigger event
    |
    v
Rule starts
    |
    v
IF some condition is false
    |
    v
Exit / do nothing
```

In the second case the trigger still wakes the rule. In the Required Expression case, Rule Machine may not subscribe to the trigger at all while the expression is false. [3]

There is another subtle point. If the Required Expression and Trigger concern the same changing attribute, the Required Expression can be evaluated against the state that existed before the trigger transition. Bruce has described this as allowing state transitions to be handled in ways an ordinary conditional action cannot reproduce directly. [3]

**Author-confirmed:** A false Required Expression can make a rule effectively quiescent by removing trigger subscriptions. [3][8]

**[VALIDATE T01]** Verify this using App Status event subscriptions on the target platform.

---

# 4. Normal action execution

For actions that do not yield execution, the useful user-level model is still sequential:

```text
Action 1
Action 2
Action 3
```

Rule Machine evaluates and dispatches these actions in order.

However, this does **not** imply that the physical devices have completed each requested change before Rule Machine dispatches the next command. Device networks and drivers operate independently of the visual action list.

For example:

```text
On: Lamp
Set Dimmer: Lamp to 40
Notify: "Lamp changed"
```

should be understood as an ordered sequence of requested actions, not as a transaction in which each physical device operation is confirmed complete before the next line runs.

For most rules this distinction is unimportant. It matters when a later action depends on a real-world device response rather than merely on Rule Machine having issued a command. In that case, waiting for an event or condition can be more deterministic than inserting an arbitrary fixed delay.

This document does not attempt to generalise physical device command acknowledgement because Zigbee, Z-Wave, Matter, LAN and cloud integrations have different behaviour.

---

# 5. Delay: schedule continuation, then leave

A plain Delay is one of the most important Rule Machine concepts to understand.

Example:

```text
On: Light
Delay 0:05:00
Off: Light
```

The intuitive model is:

```text
On
sleep for five minutes
Off
```

The more accurate Rule Machine model is:

```text
T+0       Rule starts
          On: Light
          Delay encountered
          Schedule continuation for T+5 min
          Current execution exits

T+5 min   Scheduled continuation invokes Rule Machine
          Resume with Off: Light
```

Bruce Ravenel has explicitly described a delay as causing the rule to exit after setting a scheduled event to wake it when the delay is over. [4]

## 5.1 A Delay action and a delayed action are not identical

These two action lists look similar but have different sequencing:

```text
A.
Delay 10 seconds
Off: Light
Notify: Done
```

```text
B.
Off: Light -> delayed 10 seconds
Notify: Done
```

In A, execution after the Delay is deferred.

In B, the individual `Off` action is scheduled for later, but Rule Machine can continue immediately to `Notify: Done`.

Bruce has explained that both forms create future instances, but the difference is where the continuation occurs. With a plain Delay, subsequent actions are held until the scheduled continuation. With an individually delayed action, later actions can execute before the delayed action. [4]

This is a major source of rules that appear to run "out of order".

**Author-confirmed:** Both forms use scheduled future execution, but only a plain Delay postpones the subsequent action sequence. [4]

**[VALIDATE T03]** Plain Delay execution trace.  
**[VALIDATE T04]** Individual delayed-action execution trace.

---

# 6. Wait: create a future wake-up condition

A Wait is conceptually different from a fixed Delay because it is waiting for an event, a condition transition, or an elapsed-time condition.

The current Rule 5.1 documentation describes Wait for Events as effectively pausing action execution at that point until one of the selected events occurs. [1]

At runtime, Bruce has clarified that a Wait exits the current rule instance while leaving behind event subscriptions or scheduled jobs. [4]

Conceptually:

```text
Rule starts
    |
    v
Wait for door closed
    |
    +---- subscribe to relevant future event
    |
    +---- current execution exits
               |
               v
         Door closes later
               |
               v
      Rule Machine invoked again
               |
               v
       Continue after Wait
```

## 6.1 Wait for Event versus Wait for Condition/Expression

A useful distinction is:

- **Wait for Event** is interested in a future matching event.
- **Wait for Condition/Expression** can proceed immediately if the expression is already true; otherwise it waits for events that may make the expression true. This behaviour has been explained in Hubitat community guidance and should be verified against the current target release. [9]

That difference matters.

If a door is already closed when this line is reached:

```text
Wait for Event: Door closed
```

the rule may wait for a **new** closed event.

Whereas:

```text
Wait for Condition: Door closed
```

is intended to recognise that the required state is already true and continue.

**[VALIDATE T05]** Wait for Event when the target state is already true.  
**[VALIDATE T06]** Wait for Condition/Expression when the target state is already true.

---

# 7. Retriggering changes everything

The most common incorrect mental model is:

> A rule is already running, therefore a new trigger cannot start it again.

Rule Machine can have multiple simultaneous instances of the same rule. Bruce Ravenel has explicitly confirmed this, including for Rule Functions. [5]

This becomes important when execution has yielded at a Delay or Wait.

## 7.1 Retrigger during a Delay

Suppose:

```text
Trigger: Motion active

On: Light
Delay 5 minutes
Off: Light
```

Motion occurs at 10:00.

The rule schedules a continuation for 10:05.

Now motion occurs again at 10:03.

A second execution can start and schedule another continuation for 10:08. Bruce has shown examples of multiple instances simultaneously holding delayed continuations. [4]

The result can therefore resemble:

```text
10:00  Trigger A
10:00  Light ON
10:00  continuation A scheduled for 10:05

10:03  Trigger B
10:03  Light ON
10:03  continuation B scheduled for 10:08

10:05  continuation A -> Light OFF
10:08  continuation B -> Light OFF
```

That is usually **not** what someone designing a motion timeout wants.

## 7.2 Retrigger during a Wait

Wait behaves differently.

Bruce has stated that Waits are cancelled by triggers. When the rule is retriggered, the event subscriptions or scheduled jobs left by the previous Wait are removed. [4]

For a motion rule this can be extremely useful:

```text
Trigger: Motion active

On: Light
Wait for elapsed time 5 minutes
Off: Light
```

Conceptually:

```text
10:00 trigger
      create five-minute wait

10:03 retrigger
      cancel previous wait
      start rule again
      create a new five-minute wait

10:08 wait completes
      light off
```

This is why a Wait for elapsed time can behave more like a retriggerable timer, while a normal Delay may accumulate multiple future continuations.

**Author-confirmed:** Retriggering cancels Wait leave-behinds; ordinary delays are not automatically cancelled simply because a new trigger occurs. [4]

**[VALIDATE T07]** Retrigger during Wait.  
**[VALIDATE T08]** Retrigger during Delay.

---

# 8. Re-entry and shared rule state

Multiple invocations are not necessarily independent in the way separate function calls in a conventional programming language would be.

Bruce Ravenel has stated that every app has a single state and that simultaneous instances share that state. He specifically warns that multiple simultaneous Rule Function instances can therefore be problematic in some circumstances. [5]

This explains why complex rules containing nested `IF-THEN` structures, delays and frequent retriggers have historically produced confusing behaviour.

The risk can be represented as:

```text
Execution A                         Execution B
-----------                         -----------
Enter nested IF
Delay / yield
                                    Trigger occurs
                                    Enter same rule
                                    Modify shared execution state
Resume later
Need previous nesting state
```

The exact implementation should not be inferred beyond what Hubitat exposes, but the architectural implication is clear:

> Do not assume that two simultaneous executions of one complex Rule Machine app have isolated stacks and isolated mutable state.

This is not normally a concern for short, stateless automations. It becomes material when a rule combines:

- frequent triggers;
- delays or waits;
- nested conditional structures;
- repeats;
- shared/local rule state;
- Rule Functions called concurrently.

**[VALIDATE T09]** Construct a safe, controlled re-entry test with nested conditional actions and a Delay. The objective is not to force corruption, but to observe the current platform's behaviour and logs.

---

# 9. Calling another rule

`Run Rule Actions` is often misunderstood as a conventional subroutine call.

It is not.

Bruce Ravenel describes it as **launching** the other rule's actions. The calling rule continues immediately. You cannot assume that the called rule completes before the caller continues, and relative timing is non-deterministic. [6]

Conceptually:

```text
Rule A
  |
  +---- Run Rule Actions: Rule B -----> Rule B starts
  |
  +---- Rule A next action              Rule B continues independently
  |
  v
Rule A continues
```

Not:

```text
Rule A
  |
  v
Call Rule B
  |
  v
Wait until Rule B returns
  |
  v
Continue Rule A
```

This matters when Rule A expects Rule B to calculate or change something before Rule A uses the result.

If sequencing is required, the two rules need an explicit coordination mechanism, such as a state change plus a Wait in the calling rule.

## 9.1 Does the called rule's trigger matter?

No trigger event is required when another rule explicitly runs its actions. The action list is being invoked directly rather than through the target rule's normal trigger path.

Required Expression interaction is more nuanced. A 2024 clarification from Bruce states that `Run Rule Actions` is not simply governed by the target rule's Required Expression, but if that Required Expression is configured to cancel pending actions when false, it can prevent the called actions from running. [7]

**[VALIDATE T10]** Confirm current `Run Rule Actions` launch/continuation ordering.  
**[VALIDATE T11]** Confirm the current Required Expression / Cancel Pending Actions interaction.

---

# 10. Rule Functions

Rule Functions were introduced as a special type of Rule Machine rule that can be called by another Rule or Button Rule, accept an optional parameter, and return a String, Integer, Decimal or Boolean value. A Rule Function has actions but no normal Triggers or Required Expression. [5]

They make reusable logic considerably clearer than coordinating ordinary action-only rules through Hub Variables.

However, they should not be mentally modelled as ordinary stack-isolated functions in a programming language.

Bruce has explicitly said:

- Rule Machine is not a programming language;
- multiple simultaneous instances of a Rule Function are possible;
- every app has a single state;
- the parameter is effectively passed by value, but its working value is held in app state;
- concurrent invocation can therefore still be problematic. [5]

For simple, quick transformations this may never matter. For functions that wait, delay, manipulate shared variables, or are likely to be called simultaneously, it is an architectural consideration.

**[VALIDATE T12]** Test two near-simultaneous calls to a deliberately slow Rule Function and confirm how parameters/returns behave on the current platform.

---

# 11. A note on "Ignore trigger events while running"

This area currently has conflicting external evidence.

The present Rule 5.1 documentation indexed by Hubitat still contains text describing **Ignore trigger events while running**. [1]

However, in June 2025, Bruce Ravenel confirmed in the Hubitat Community that the feature had been removed after its brief availability. He explained that it could become stuck in a state that users could not manually reset, and recommended Private Boolean in a Conditional Trigger for users who genuinely need protection from multiple simultaneous instances. [10]

As of this draft, Hubitat's public release listing shows platform 2.5.1 as the current release line, published immediately before this source review. [11]

Therefore this document does **not** assume the option exists on a current hub.

**[VALIDATE T13]** On the target platform, record whether `Ignore trigger events while running` is present in a newly created Rule Machine 5.1 rule. If absent, treat the indexed documentation text as stale. If present, record exact platform build and behaviour.

This is precisely the type of issue that justifies local validation before publication.

---

# 12. Debugging the runtime

When a complex rule behaves unexpectedly, reading the rule definition alone is often insufficient.

Use four views together:

```text
1. Rule definition
       |
       v
2. Rule Machine logs
       |
       v
3. App Status
   - event subscriptions
   - scheduled jobs
       |
       v
4. Device events / current state
```

The objective is to answer four different questions:

| Question | Best evidence |
|---|---|
| What was the rule intended to do? | Rule definition |
| What did Rule Machine actually execute? | Rule logs |
| What future work is currently outstanding? | App Status scheduled jobs/subscriptions |
| What actually happened to the device? | Device events and current state |

For a Wait-related problem, inspect subscriptions.

For a Delay-related problem, inspect scheduled jobs.

For a retrigger problem, correlate timestamps and look for overlapping executions or multiple future continuations.

For a Required Expression problem, compare event subscriptions while the expression is true and false.

This is also where an automation dependency map becomes useful: it can reveal that what looks like an isolated rule is actually being invoked by another rule or indirectly driven through shared devices and variables.

---

# 13. The model to remember

The entire document can be reduced to these rules:

1. **Hubitat is event-driven.** A trigger reacts to an event or schedule, not to a permanently executing rule.
2. **State is not an event.** Conditions inspect what is true; triggers and waits react to things happening.
3. **A Required Expression is an admission mechanism, not merely an IF statement.** When false, trigger subscriptions may be removed.
4. **An IF does not wait.** It evaluates the condition when execution reaches it.
5. **A Delay is scheduled future continuation.** The current execution exits.
6. **A delayed action schedules that action while later actions can continue immediately.**
7. **A Wait also exits.** It leaves subscriptions or timers that allow execution to resume.
8. **Retriggering cancels outstanding Waits, but not ordinary Delays.**
9. **A rule can have multiple simultaneous instances.**
10. **Those instances are not guaranteed to have isolated mutable app state.**
11. **Run Rule Actions launches another rule's actions.** It is not a synchronous subroutine call.
12. **Rule Functions improve reuse but do not turn Rule Machine into a conventional programming runtime.**

If those twelve points are understood, most of Rule Machine's apparently strange behaviour becomes predictable.

---

# Addendum A - Hub validation test plan

## A.1 Test discipline

Run these tests on a non-critical set of virtual devices. Do not use locks, garage doors, alarms, heaters, irrigation valves or other equipment where unintended activation matters.

Before testing, record:

| Field | Value |
|---|---|
| Hub model | __________________ |
| Platform version/build | __________________ |
| Rule Machine version shown | __________________ |
| Test date | __________________ |
| Tester | __________________ |

Create:

- Virtual Switch `RM Test Switch`
- Virtual Contact Sensor if convenient, or another controllable test event source
- Hub Variable `RM_Test_Result` as String
- Hub Variable `RM_Test_Param` as String
- Notification/logging actions only where practical

Enable Rule Machine trigger and action logging for all tests.

For every test capture:

1. screenshot/export of the rule;
2. Rule Machine logs with timestamps;
3. App Status event subscriptions before/during/after;
4. App Status scheduled jobs before/during/after;
5. relevant device events;
6. platform build number.

---

## T01 - Required Expression subscription gating

**Purpose:** Confirm that trigger subscriptions are removed when the Required Expression is false and restored when true.

**Rule**

```text
Required Expression:
    RM Test Switch is ON

Trigger:
    [test event source]

Actions:
    Log: "T01 fired"
```

**Procedure**

1. Set `RM Test Switch` OFF.
2. Open the rule's App Status page.
3. Record Event Subscriptions and Scheduled Jobs.
4. Generate the trigger event.
5. Confirm whether the rule logs a trigger.
6. Set `RM Test Switch` ON.
7. Re-open App Status.
8. Record subscriptions again.
9. Generate the trigger event.

**Expected from sources**

- With Required Expression false, the normal trigger subscription should be absent.
- A subscription/schedule required to detect the Required Expression changing should remain.
- When the Required Expression becomes true, the trigger subscription should appear.
- The trigger should then fire.

**Publication placeholder**

> **TEST RESULT T01:** [INSERT RESULT, SCREENSHOT REFERENCE AND PLATFORM BUILD]

---

## T02 - IF evaluates state and does not wait

**Purpose:** Demonstrate that a conditional action is an immediate state evaluation.

**Rule**

```text
Trigger:
    RM Test Switch turns ON

Actions:
    IF [test condition is TRUE] THEN
        Log: "T02 condition true"
    ELSE
        Log: "T02 condition false"
    END-IF
    Log: "T02 finished"
```

**Procedure**

1. Make the test condition false.
2. Trigger the rule.
3. Do not change the condition for 30 seconds.
4. Review logs and App Status.

**Expected**

The rule should immediately log the false path and finish. It should not create a subscription waiting for the condition to become true.

**Publication placeholder**

> **TEST RESULT T02:** [INSERT RESULT]

---

## T03 - Plain Delay schedules continuation

**Purpose:** Observe what a plain Delay leaves behind.

**Rule**

```text
Trigger:
    RM Test Switch turns ON

Actions:
    Log: "T03 before delay"
    Delay 30 seconds
    Log: "T03 after delay"
```

**Procedure**

1. Trigger the rule.
2. Immediately inspect App Status.
3. Record scheduled jobs.
4. Wait for the 30-second continuation.
5. Record logs and App Status again.

**Expected**

A scheduled job/continuation should be visible during the delay. The later log entry should occur when the scheduled continuation fires.

**Publication placeholder**

> **TEST RESULT T03:** [INSERT SCHEDULED JOB DETAILS AND LOG TIMES]

---

## T04 - Individual delayed action does not hold subsequent actions

**Purpose:** Contrast a delayed action with a plain Delay.

**Rule**

```text
Trigger:
    RM Test Switch turns ON

Actions:
    Log: "T04 delayed message" -> delayed 30 seconds
    Log: "T04 immediate next action"
```

If logging cannot itself be individually delayed in the current UI, use a harmless virtual switch action and a second rule that logs the switch event.

**Expected**

The immediate action should occur near T+0. The delayed action should occur near T+30 seconds.

**Publication placeholder**

> **TEST RESULT T04:** [INSERT RESULT]

---

## T05 - Wait for Event when state is already true

**Purpose:** Determine whether Wait for Event requires a new future event.

**Rule**

```text
Trigger:
    [separate test trigger]

Actions:
    Log: "T05 entering wait"
    Wait for Event: RM Test Switch turns ON
    Log: "T05 resumed"
```

**Procedure**

1. Set `RM Test Switch` ON before triggering the rule.
2. Trigger the rule.
3. Observe whether it resumes immediately.
4. If it remains waiting, toggle the switch OFF then ON.
5. Record subscriptions and logs.

**Expected**

A Wait for Event should wait for a future matching ON event rather than treating the already-ON state as an event.

**Publication placeholder**

> **TEST RESULT T05:** [INSERT RESULT]

---

## T06 - Wait for Condition/Expression when already true

**Purpose:** Contrast condition-based waiting with event-based waiting.

**Rule**

```text
Trigger:
    [separate test trigger]

Actions:
    Log: "T06 entering wait"
    Wait for Condition/Expression: RM Test Switch is ON
    Log: "T06 resumed"
```

**Procedure**

1. Set `RM Test Switch` ON before triggering the rule.
2. Trigger the rule.
3. Record whether execution continues immediately.
4. Repeat with switch initially OFF, then turn it ON after 10 seconds.

**Expected**

When already true, the wait should complete immediately. When false, the rule should leave a subscription and resume after an event makes the condition true.

**Publication placeholder**

> **TEST RESULT T06:** [INSERT RESULT]

---

## T07 - Retrigger cancels Wait

**Purpose:** Confirm Wait reset semantics.

**Rule**

```text
Trigger:
    RM Test Switch turns ON

Actions:
    Log: "T07 trigger"
    Wait for Event: Elapsed Time 30 seconds
    Log: "T07 wait completed"
```

**Procedure**

1. Trigger at T+0.
2. At approximately T+20 seconds, generate another valid trigger.
3. Watch App Status scheduled jobs before and after the second trigger.
4. Observe whether completion occurs near T+30 or T+50.

**Expected**

The first Wait should be cancelled on retrigger and replaced by the new Wait. Completion should occur roughly 30 seconds after the second trigger.

**Publication placeholder**

> **TEST RESULT T07:** [INSERT RESULT]

---

## T08 - Retrigger does not automatically cancel Delay

**Purpose:** Confirm that ordinary delays can accumulate outstanding continuations.

**Rule**

```text
Trigger:
    RM Test Switch turns ON

Actions:
    Log: "T08 trigger"
    Delay 30 seconds
    Log: "T08 continuation"
```

**Procedure**

1. Trigger at T+0.
2. Create another valid trigger at approximately T+20.
3. Inspect scheduled jobs.
4. Count continuation log entries and timestamps.

**Expected**

Two delayed continuations should be capable of existing, with completions around T+30 and T+50.

**Publication placeholder**

> **TEST RESULT T08:** [INSERT RESULT]

---

## T09 - Controlled re-entry through nested logic

**Purpose:** Observe current behaviour when a second execution enters a rule that has yielded inside nested conditional logic.

**Rule**

Use only logs and virtual devices.

```text
Trigger:
    [repeatable test event]

Actions:
    IF RM Test Switch is ON THEN
        IF [second harmless condition] THEN
            Log: "T09 before delay"
            Delay 20 seconds
            Log: "T09 after delay"
        END-IF
    END-IF
```

**Procedure**

1. Make both conditions true.
2. Trigger the rule.
3. Retrigger approximately 5 seconds later.
4. Capture all logs.
5. Do not assume failure is expected. Record actual behaviour.

**Expected**

No fixed outcome should be asserted before testing. Historical author commentary warns that simultaneous instances plus nested IF state and embedded delays have been problematic.

**Publication placeholder**

> **TEST RESULT T09:** [INSERT RESULT AND ANY ERRORS]

---

## T10 - Run Rule Actions is launch, not call-and-return

**Purpose:** Demonstrate caller/callee ordering.

**Rule B - no trigger required**

```text
Actions:
    Log: "T10 B start"
    Delay 15 seconds
    Log: "T10 B end"
```

**Rule A**

```text
Trigger:
    RM Test Switch turns ON

Actions:
    Log: "T10 A before launch"
    Run Rule Actions: Rule B
    Log: "T10 A after launch"
```

**Expected**

`A after launch` should occur without waiting 15 seconds for Rule B to finish.

**Publication placeholder**

> **TEST RESULT T10:** [INSERT ORDERED LOG TRACE]

---

## T11 - Required Expression interaction with Run Rule Actions

**Purpose:** Confirm the target rule behaviour with Required Expression and Cancel Pending Actions.

Create Rule B with a false Required Expression and test two configurations:

1. Cancel pending actions when Required Expression becomes false - disabled.
2. Cancel pending actions when Required Expression becomes false - enabled.

Invoke Rule B using `Run Rule Actions` from Rule A.

**Expected from 2024 author clarification**

The Required Expression alone does not behave like normal trigger admission for a direct `Run Rule Actions` invocation, but the Cancel Pending Actions setting may prevent the target actions from running.

**Publication placeholder**

> **TEST RESULT T11:** [INSERT RESULTS FOR BOTH CONFIGURATIONS]

---

## T12 - Rule Function concurrency

**Purpose:** Determine the practical behaviour of near-simultaneous Rule Function calls.

**Function**

Create a Rule Function that:

1. receives `RM_Test_Param`;
2. copies `%param%` to an appropriate local variable;
3. introduces a short 5-second Wait or Delay;
4. returns the captured value.

**Callers**

Create two rules that call the same Rule Function with different parameter values as close together as practical.

**Expected**

Do not pre-assert correctness. The purpose is to determine whether overlapping invocation can cause parameter/return interference on the current build.

**Publication placeholder**

> **TEST RESULT T12:** [INSERT RESULT]

---

## T13 - Current availability of "Ignore trigger events while running"

**Purpose:** Resolve the public-source contradiction.

**Procedure**

1. Create a new Rule Machine 5.1 rule on the target hub.
2. Record the platform build.
3. Inspect all rule-level options for `Ignore trigger events while running`.
4. If present, enable it and run a basic retrigger test.
5. If absent, record that fact.

**Expected**

No expectation is asserted. Bruce Ravenel stated in June 2025 that the feature had been removed, while the currently indexed Rule 5.1 documentation still contains text describing it.

**Publication placeholder**

> **TEST RESULT T13:** [PRESENT / ABSENT, BUILD, BEHAVIOUR IF PRESENT]

---

# Addendum B - Publication validation matrix

| Statement | Source confidence | Hub test | Publication status |
|---|---:|---:|---|
| Hubitat apps are event/subscription/schedule driven | High | Optional | Ready |
| Required Expression false removes trigger subscriptions | High | T01 | Pending validation |
| IF evaluates current state and does not wait | High | T02 | Pending demonstration |
| Plain Delay schedules a later continuation | High | T03 | Pending demonstration |
| Individual delayed action lets later actions continue | High | T04 | Pending demonstration |
| Wait for Event requires a future event | Medium-high | T05 | Pending validation |
| Wait for Condition can pass immediately if already true | Medium-high | T06 | Pending validation |
| Retrigger cancels outstanding Wait | High | T07 | Pending validation |
| Retrigger does not automatically cancel ordinary Delay | High | T08 | Pending validation |
| Nested IF + re-entry can be problematic | High historically | T09 | Platform-sensitive |
| Run Rule Actions launches and caller continues | High | T10 | Pending demonstration |
| Required Expression interaction with direct rule invocation | High | T11 | Pending validation |
| Rule Function concurrent instances share app state | High | T12 | Pending practical test |
| Ignore-trigger option current availability | Conflicting sources | T13 | Must validate |

---

# Sources

1. **Hubitat Documentation - Rule 5.1**  
   https://docs2.hubitat.com/en/apps/rule-machine/rule-5-1

2. **Hubitat Developer Documentation - App Overview**  
   https://docs2.hubitat.com/en/developer/app/overview

3. **Bruce Ravenel - Required Expression vs Conditional Action?**  
   https://community.hubitat.com/t/required-expression-vs-conditional-action/110670/7

4. **Bruce Ravenel - RM Feature Request: Prevent rule from triggering if already running, page 4**  
   Includes explanations of Delay, Wait, retriggering and rule instances.  
   https://community.hubitat.com/t/rm-feature-request-prevent-rule-from-triggering-if-already-running/137609?page=4

5. **Bruce Ravenel - Rule Machine: Rule Functions**  
   Includes concurrency and shared app-state discussion.  
   https://community.hubitat.com/t/rule-machine-rule-functions/146774

6. **Bruce Ravenel - Run Rule Actions and Loops**  
   Explains that running another rule launches its actions and the caller continues.  
   https://community.hubitat.com/t/run-rule-actions-and-loops/45179

7. **Bruce Ravenel - Rule Actions / Required Expression clarification**  
   https://community.hubitat.com/t/question-about-rule-machine-rule-actions-required-expression-c8-v2-3-9-162/141009

8. **Bruce Ravenel - Required Expression event subscription behaviour, 2025**  
   https://community.hubitat.com/t/unexpected-behaviour-of-required-expression-versus-trigger/149623/44

9. **Hubitat Community - Wait for Event vs Wait for Expression discussion**  
   https://community.hubitat.com/t/rule-machine-require-expression-and-wait-condition-not-firing/94911

10. **Bruce Ravenel - removal of "Ignore trigger events while running" and current recommendation**  
    https://community.hubitat.com/t/bring-back-the-dont-run-while-running-switch/154432

11. **Hubitat Community - Release Notes index**  
    Used to establish the current public platform release line at the time of source review.  
    https://community.hubitat.com/c/news/release-notes/55

---

## Drafting note

This document intentionally stops short of asserting platform-sensitive behaviour where direct hub evidence would materially strengthen the explanation. Once tests T01-T13 are completed, the placeholders should be replaced with short empirical traces and the publication validation matrix updated to `Validated`.
