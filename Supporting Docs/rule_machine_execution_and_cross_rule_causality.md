# Reasoning about Rule Machine execution and cross-rule causality

## Design principle

Do not confuse a plausible execution model with a locally demonstrated fact,
and do not turn shared objects into causal rule-to-rule edges unless the
available evidence supports that direction and meaning. Preserve uncertainty,
show the mediation object, and refuse to label influence as execution.

This document is a developer reference for answering one question correctly:

> What will a Rule Machine rule actually do, and what can it cause another rule
> to do?

Storage reconstruction is documented separately in
`rule_machine_5_1_storage_format.md`. This document covers the two layers above
storage: runtime execution semantics and cross-rule causality.

### Validation snapshot

This revision was externally re-checked on **2026-08-15** against the current
Hubitat Rule 5.1 documentation and the cited Bruce Ravenel explanations, then
cross-checked against the available local storage reference and Automation Map
implementation evidence.

Keep four evidence scopes separate:

1. **Officially documented runtime semantics** - behavior stated in current
   Hubitat documentation.
2. **Author-explained runtime mechanics** - implementation/runtime explanations
   from Bruce Ravenel that go beyond the user documentation.
3. **Locally observed storage/runtime evidence** - behavior actually seen on
   this hub or in exported fixtures.
4. **Automation Map implementation status** - what a specific source revision
   extracts and renders.

The storage reference was derived from a C-8 on platform **2.5.1.142** and
cross-checked against 38 Rule-5.1 rules. Runtime and private-storage behavior
remain version-sensitive. An implementation-status statement is reproducible
only when paired with `APP_VERSION`, `GRAPH_SCHEMA`, platform build, and a source
commit/blob SHA or hub-code revision.

**Implementation provenance warning:** the available `automation_map.groovy`
snapshot inspected during this external review predates the locally reported
Rule-Machine-only Hub Variable extraction gate. `handoff.md` records that gate
as fixed in hub revision 52. Until that exact post-fix source is committed or
attached, treat the gate as **handoff-confirmed, not independently source-
verified in this review**.

## 1. Evidence and implementation status

The evidence markers match the storage-format reference:

| Marker | Meaning |
| --- | --- |
| **[invariant]** | Inherent in the exposed data shape or directly enforced by the implementation. |
| **[strong]** | Repeatedly observed, controlled, or supported by authoritative material. |
| **[limited]** | Supported by a small number of observations or a narrow implementation path. |
| **[single]** | Observed in one fixture only. |
| **[heuristic]** | A practical inference or mitigation, not a fact guaranteed by Rule Machine. |
| **[unknown]** | Not established; no implementation claim should be made. |

Status words are separate from evidence strength:

- **Implemented** means the identified implementation snapshot extracts and
  renders the concept. If the change is local-only, record the hub-code revision
  or source hash rather than implying it is checked into Git.
- **Implemented more simply** means the app preserves a useful relationship but
  does not implement the proposal's finer semantics.
- **Proposal only** means the concept is future direction, not current behavior.
- **Community-sourced, locally unvalidated** means Bruce Ravenel or Hubitat
  documentation supports the runtime model, but the controlled validation suite has not been completed
  on this hub.

### What is settled now

- **[invariant] Implemented in the reviewed source snapshot:** direct rule
  link kinds are `runs`, `cancelTimedActions`, `setspb`, and `pauseResume`.
- **[strong] Implemented:** Hub Variables are first-class nodes with `write` and
  `read` edges. Structured writes, condition reads, trigger reads, Required
  Expression reads, and bounded free-text reads were checked against live data.
- **[invariant] Implemented more simply:** condition, trigger, Required
  Expression, and plain/free-text variable reads currently collapse to the same
  `read` kind. The graph does not yet claim whether that read triggers execution
  or only affects eligibility.
- **[invariant] Not implemented:** the current app does not derive collapsed
  rule-to-rule causality through devices, Mode, HSM, Hub Variables, or connector
  devices. It shows the intermediate objects and their edges instead.
- **[invariant] Not implemented:** confidence scores, semantic value matching,
  cycle analysis, transitive impact analysis, and multiple derived causality
  views remain proposal only.

### Not done, and therefore not claimed

Runtime behavior statements in sections 2-5 are externally sourced unless
explicitly marked as locally observed. The controlled validation suite remains
largely outstanding.

One important exception already exists: the storage-format work captured a live
production rule whose Required Expression was false and whose normal device
trigger subscription had disappeared, leaving only location subscriptions needed
for recovery. That is **[single] local observational evidence** for Required
Expression subscription gating. It does not replace controlled T01.

There is still no controlled local proof here for Delay/Wait retrigger behavior,
Rule Function concurrency, or the sequencing boundary created by `Run Rule
Actions`. Those remain explicit tests. The current Rule 5.1 documentation now
also states that **Ignore trigger events while running** was available only in
platform 2.3.9 and remains only for pre-existing rules, so T13 is a target-hub
inventory/compatibility check rather than an attempt to resolve an unresolved
documentation contradiction.

## 2. The execution model: events, state, and yield points

**[strong, officially documented; locally unvalidated as a controlled trace]**
Rule Machine is best understood as an event-driven automation engine, not as a
continuously running script:

```text
event or schedule
      -> rule invocation
      -> immediate actions and condition evaluation
      -> completion, or a Wait/Delay that leaves future work behind
      -> later event/timer may invoke continuation
```

An event answers "what happened?" Current state answers "what is true now?"
A trigger normally reacts to an event. An `IF` evaluates state when execution
reaches it; it does not keep monitoring a false condition. Use a Wait when later
change, rather than immediate state, is required. **[strong,
community-sourced; locally unvalidated: T02]**

### 2.1 Required Expressions are admission control

**[strong, officially documented; single local observation; controlled T01
pending]** A false Required Expression can remove normal trigger subscriptions
while retaining only what is needed to notice that the expression may become
true again. This is materially
different from allowing every trigger to start the rule and putting an `IF` at
the top.

A Required Expression can therefore influence whether a rule is eligible to be
invoked without itself being a trigger. Any future causality graph must preserve
that distinction.

### 2.2 Immediate action order is not device completion order

**[strong, officially documented ordering; completion semantics deliberately
not inferred]** Actions to Run form an ordered script. That ordering proves the
order in which Rule Machine reaches and dispatches non-yielding actions; it does
**not** prove that a physical Zigbee, Z-Wave, Matter, LAN, or cloud device has
completed the previous command. When later logic depends on a real device
transition, use the resulting state/event as evidence rather than assuming
command completion.

## 3. Delay and Wait

### 3.1 Plain Delay

**[strong, officially documented and author-explained; locally unvalidated:
T03]** A plain Delay schedules later continuation. Bruce Ravenel has further
explained that the current rule instance exits/yields and later continuation is
a separate instance:

```text
On: Light
Delay 5 minutes       -> schedule continuation, yield
Off: Light            -> resume later
```

An individually delayed action is different:

```text
Off: Light -> delayed 5 minutes
Notify: Done          -> may run immediately
```

**[strong, officially documented and author-explained; locally unvalidated:
T04]** The individual action is scheduled, but subsequent actions continue
immediately; the delayed action runs later.

### 3.2 Wait

**[strong, officially documented and author-explained; locally unvalidated]**
A Wait leaves a future wake-up condition - an event subscription or scheduled
job - and exits/yields the current invocation.

- **Wait for Events** does not evaluate current state when the action is
  reached; it waits for the configured future event(s). **[strong, officially
  documented; locally unvalidated: T05]**
- **Wait for Expression** continues immediately when the expression is already
  true; otherwise it waits for the expression to become true. **[strong,
  officially documented; locally unvalidated: T06]**

### 3.3 Retriggering

**[strong, officially documented and author-explained; locally unvalidated:
T07-T08]** A rule can have multiple invocations. Any Wait is cancelled when the
rule is retriggered. Ordinary delays are not cancelled merely because the rule
is retriggered; cancellation requires the relevant explicit cancellation
mechanism.

That gives the practical contrast:

```text
Delay: trigger at 10:00 and 10:03 may leave continuations at 10:05 and 10:08.
Wait:  trigger at 10:03 replaces the earlier wait, acting like a resettable timer.
```

## 4. Re-entry and shared app state

**[strong, community-sourced; locally unvalidated: T09]** Multiple invocations
of one Rule Machine app should not be assumed to have isolated stacks and
isolated mutable state. Every app has one app state. Risk rises when a rule
combines frequent triggers, nested conditions, repeats, Waits or Delays, and
shared/local values.

This is an architectural warning, not a claim that every overlapping invocation
fails. T09 deliberately records observed behavior without pre-asserting failure.

## 5. Direct cross-rule execution

### 5.1 `Run Rule Actions`: sequential until the target yields or exits

This section needs a more precise model than either "synchronous subroutine" or
"fire-and-forget launch".

**[strong, author-explained; controlled T10A-T10B pending]** For a Rule Machine
rule running the actions of another Rule Machine rule, Bruce Ravenel described
the call as a same-thread method-call sequence. The caller transfers execution
to the target and resumes when the target exits. The target exits at the first
of:

- finishing its action list;
- reaching a Delay;
- reaching a Wait;
- reaching a Repeat/yield boundary.

If the target reaches a Delay, Wait, or Repeat, its future continuation occurs
later in another instance/thread, while the caller can resume immediately after
the `Run Rule Actions` action.

Therefore:

```text
A -> Run B
     B immediate action 1
     B immediate action 2
     B finishes
A -> next action
```

is a valid model when B has no yield point.

But:

```text
A -> Run B
     B immediate action
     B Delay / Wait / Repeat -> B exits/yields
A -> next action
     ...
     B later continuation
```

is the better model once B yields.

This also explains why apparently contradictory older descriptions can both
look correct in specific tests. A 2020 author response described running another
rule as starting its actions and continuing; a more detailed 2021 clarification
described the Rule-Machine-to-Rule-Machine path as same-thread until the target
exits/yields. The later, more specific explanation is the safer basis for
reasoning.

The official Rule 5.1 documentation separately establishes that `Run Rule
Actions` is **not the same as triggering** the target. The target's Required
Expression does not normally gate this direct run, except that enabling
**Cancel pending actions when required expression becomes false** can prevent
the target actions from running. **[strong, officially documented; T11 pending]**

Do not generalise the same-thread detail to Rule Machine Legacy, Button Rule, or
other engines without separate evidence; Bruce explicitly described different
dispatch behavior for some cross-engine cases.

### 5.2 Rule Functions

**[strong, community-sourced; locally unvalidated: T12]** Rule Functions can
accept a parameter and return a value, but they remain Rule Machine apps with
shared app state. Near-simultaneous calls should not be assumed to behave like
stack-isolated functions in a conventional programming language.

**[limited]** The inspected storage exposes no reliable discriminator between a
Rule Function and an ordinary `Rule-5.1` target. This does not prevent resolving
the link by installed-app id; it prevents confident type-specific labelling.

## 6. What Automation Map implements today

This section was cross-checked against `apps/automation_map.groovy`, especially
`RULE_LINK_ACTIONS`, `RULE_LINK_KINDS`, `extractHubVariableWrites`, and
`extractHubVariableReads`.

### 6.1 Direct app-to-app edges

**[invariant]** The current direct taxonomy is:

| Current kind | Extracted Rule Machine actions | What the graph can safely say |
| --- | --- | --- |
| `runs` | `getRuleActions`, targets in `ruleAct`/`ruleActMain` | Source runs target Actions to Run; RM-to-RM sequencing is same-thread until the target exits/yields. |
| `cancelTimedActions` | `getStopActions`, targets in `stopAct` | Source invokes the target rule-timer cancellation operation; current docs describe Cancel Rule Timers as cancelling delays, waits, and repeats. |
| `setspb` | `getSetPrivateBoolean`, targets in `privateT`/`privateF` | Source sets a target rule's Private Boolean. |
| `pauseResume` | `getPauseResumeRules`, targets in `pauseRule` | Source manages target pause/resume state; the rendered edge currently combines both. |

Target ids are extracted from known settings aliases and deduplicated by source,
target, and kind. A self-target marker is stripped without discarding other
targets stored alongside it. **[strong]** These details were derived from and
tested against Rule Machine storage fixtures; see the storage-format reference.

The proposal names `INVOKES`, `RUNS_ACTIONS_OF`, `PAUSES`, `RESUMES`, `ENABLES`,
`DISABLES`, and `CANCELS`. The real implementation is narrower:

- `RUNS_ACTIONS_OF` maps conceptually to current `runs`.
- `CANCELS` maps conceptually to current `cancelTimedActions`.
- `PAUSES` and `RESUMES` are extracted but rendered as one `pauseResume` kind.
- Private Boolean control exists as `setspb`, although the proposal did not give
  it equal prominence.
- Separate `INVOKES`, `ENABLES`, and `DISABLES` kinds are **not implemented**.

### 6.2 Hub Variable nodes and edges

**[strong, implemented]** A `getSetVariable` action creates a Hub Variable
`write` edge. The extractor reads `xVarV.<n>` and may attach device-attribute
source detail when `valStringOp.<n> == 'Device attribute'`.

**[strong, implemented]** Reads are found through:

- `rCapab_<n> == Variable` plus `xVar_<n>` in conditions and Required
  Expressions;
- `tCapab<n> == Variable` plus `xVar<n>` in triggers;
- `%Name%` in text/textarea settings as a bounded fallback.

**[heuristic, implemented]** A free-text match is drawn only when the same name
is confirmed somewhere on the hub by a structured write/read. This avoids
manufacturing variables from reserved tokens such as `%device%`, `%time%`, and
`%date%`.

Current Rule 5.1 documentation also defines `%value%`, `%text%`, and `%now%` as
built-in substitutions. The confirmation heuristic therefore has two known
limits:

- if a genuine Hub Variable is named `device`, `value`, `text`, `date`, `time`,
  or `now`, a built-in token with the same spelling can become ambiguous once
  that name is independently confirmed elsewhere;
- if a genuine Hub Variable is referenced **only** through `%Name%` text and
  nowhere through a structured picker/write, the conservative heuristic will
  omit it rather than invent a dependency.

That is the correct failure direction for a dependency mapper: prefer a visible
"not proven" gap over a fabricated edge. If Hubitat exposes an authoritative Hub
Variable name registry to this app, use that to resolve the ambiguity instead of
loosening the text heuristic.

The current write extractor is also evidence-shaped, not feature-complete by
definition. Rule 5.1 supports several Set Variable operations. Only storage forms
actually observed and decoded should be claimed as supported; do not assume all
variable mutations share the verified `getSetVariable` shape without fixtures.

**[invariant, implemented more simply]** All consumers become one `read` edge.
The app does not yet distinguish `READ + CONDITION`, `READ + TRIGGER`, Required
Expression eligibility, or a plain/free-text read. Therefore the graph shows a
real dependency without claiming the exact causal semantics.

**[invariant]** Hub Variable edges remain app-to-variable in stored graph shape.
The client reverses the arrowhead for `read` so the visual direction is
variable-to-app. Writes and reads between the same pair remain separate.

### 6.3 Device relationships are not collapsed into rule causality

The app already shows app-to-device roles such as trigger, constraint, monitor,
and action. **[invariant]** It does not join producers and consumers to create a
direct `TRIGGERS_VIA_DEVICE` rule edge. Sharing a device is therefore visible as
an inspectable path, not asserted as causation.

This is deliberately safer than the proposal's derived graph because correct
collapse requires attribute, command, value, event-vs-state, conditional-path,
and timing semantics that are not implemented.

## 7. Proposal-to-implementation cross-check

| Proposal concept | Current status | Precise distinction |
| --- | --- | --- |
| Explicit run-rule relationship | **Implemented more simply** | `runs`; no separate `INVOKES` vs `RUNS_ACTIONS_OF`. |
| Pause and resume | **Implemented more simply** | Storage discriminator is known, but graph kind is combined as `pauseResume`. |
| Enable/disable another automation | **Proposal only** | No current direct edge kind. |
| Cancel rule timers/timed actions | **Implemented** | `cancelTimedActions`. |
| Set another rule's Private Boolean | **Implemented** | `setspb`. |
| Device-mediated `TRIGGERS_VIA_DEVICE` | **Proposal only** | Device path exists; no producer/consumer collapse. |
| Hub Variable mediation | **Implemented more simply** | Variable nodes with `write`/`read`; no derived rule-to-rule edge and no trigger-vs-condition kind yet. |
| Mode-mediated causality | **Proposal only** | No producer/consumer join or derived edge. |
| HSM-mediated causality | **Proposal only** | No producer/consumer join or derived edge. |
| Location-state causality | **Proposal only** | No derived causal taxonomy. |
| Rule Machine connector objects | **Proposal only** | Section 12 work remains unaddressed. |
| Shared control/trigger-source edges | **Proposal only** | Shared device paths may be visible, but no such rule edge is emitted. |
| Required Expression eligibility edge | **Proposal only** | Variable reads may include group `0`, but render as generic `read`. |
| Trigger vs condition classification | **Partially extracted** | Device roles distinguish trigger/constraint; Hub Variable reads collapse. No derived cross-rule semantics. |
| Attribute-level matching | **Proposal only** | Not used to infer rule-to-rule causality. |
| Producer/consumer value matching | **Proposal only** | No command-to-attribute/value join. |
| Conditional, delayed, repeated edge metadata | **Proposal only** | Flow decoding may display rule actions, but derived edges do not carry this semantic model. |
| Confidence scores/thresholds | **Proposal only** | Current graph uses categorical evidence, not numeric confidence. |
| Edge aggregation with multiple evidence paths | **Proposal only** | Current dedup prevents duplicate same-kind edges; it does not aggregate semantic evidence. |
| Full/rule/causality derived views | **Proposal only** | Current filters and pivots operate on actual graph edges, not a collapsed semantic graph. |
| Cycle/feedback-loop analysis | **Proposal only** | No strongly connected component or oscillation analysis. |
| Transitive reachability/impact analysis | **Proposal only** | No upstream/downstream semantic traversal feature. |

## 8. Future direction: a defensible causality graph

The proposal remains useful as a roadmap if it is treated as a hypothesis to
validate, not as documentation of current behavior.

### 8.1 Preserve the layered graph

Keep the observed graph:

```text
Rule A -> state object -> Rule B
```

Any collapsed edge should retain that evidence path. A rule-to-rule view must
never replace the underlying device/variable/Mode/HSM object.

### 8.2 Separate execution from influence

A future vocabulary should distinguish at least:

- **direct run/control:** runs, cancels, pauses, resumes, sets Private Boolean;
- **triggers through state:** a compatible produced event can invoke the target;
- **influences eligibility:** state participates only in a condition or Required
  Expression;
- **shared object only:** both rules mention an object, but causality is not
  established;
- **unknown relationship:** evidence is incomplete and no stronger label is
  justified.

For Hub Variables this first requires separating trigger reads, condition reads,
Required Expression reads, and plain interpolation reads.

### 8.3 Require semantic matching before collapse

A defensible device-mediated edge needs more than a shared device id:

```text
producer object + attribute + possible value/event
consumer object + attribute + trigger predicate
conditional/timing context
```

For example, writing `switch=on` does not satisfy a trigger for `switch=off`.
Watching `contact` does not match a producer that changes only `switch`, even if
both capabilities belong to one virtual device. Command-to-attribute mappings are useful but must be treated as **possible
effects**, not guaranteed events. Issuing `on()` does not prove that a device
successfully reached `switch=on`, nor that a new `switch` event was emitted. The
device may already be in that state, a driver may suppress or transform
duplicate events, a transport may fail, or a custom driver may implement a
command atypically.

Capability semantics can supply a default mapping, but driver metadata and
observed behavior must be allowed to refine or override it. A causal collapse
should therefore distinguish:

```text
command issued
    !=
state changed
    !=
event emitted
    !=
target rule invoked
```

### 8.4 Candidate future taxonomy

The proposal's names are candidates, not commitments:

`TRIGGERS_VIA_DEVICE`, `TRIGGERS_VIA_VARIABLE`, `TRIGGERS_VIA_MODE`,
`TRIGGERS_VIA_HSM`, `TRIGGERS_VIA_LOCATION`, `INFLUENCES_VIA_VARIABLE`,
`INFLUENCES_VIA_MODE`, `INFLUENCES_ELIGIBILITY`, `SHARES_CONTROL_DEVICE`,
`SHARES_TRIGGER_SOURCE`, and `POSSIBLY_TRIGGERS`.

Before adding any name, define the minimum evidence that permits it, the cases
that disprove it, and how unknown values or undocumented storage are surfaced.
Numeric confidence should not substitute for that contract. **[heuristic]**

### 8.5 Three grades of causality

Use explicit grades so the UI and diagnostics never overstate what the evidence
proves:

| Grade | Meaning | Example |
| --- | --- | --- |
| **Structural dependency** | Configuration proves a directional dependency path exists. | `Rule A -> Hub Variable X -> Rule B` |
| **Potential causal path** | Producer and consumer semantics are compatible, so A *can* cause the event/state B depends on. | A sets `switch=on`; B triggers on that same switch becoming on. |
| **Observed incident causality** | Runtime logs/events correlate a specific A execution with the mediator transition that invoked or affected B. | A log -> device event -> B trigger log in one incident. |

A structural dependency is not automatically a potential trigger, and a
potential trigger is not proof that it happened in a particular incident.

This distinction matters most under **fan-in**. If three rules can write the same
Hub Variable or command the same switch, the static graph cannot identify which
producer caused a particular downstream execution. Incident attribution needs
runtime evidence.

### 8.6 Evidence carried by every derived edge

A future collapsed rule-to-rule edge should retain its underlying evidence path
rather than only a label. A useful logical record is:

```text
sourceRule
mediatorType
mediatorId
producerOperation
producedAttribute
producedValue
consumerRole          # trigger | condition | requiredExpression | read
consumerPredicate
conditionalContext
timingContext
evidenceClass
sourcePath[]
```

The UI may render a compact edge, but inspection must be able to expand it back
to the mediation object and evidence that justified it. Multiple independent
paths between the same two rules should be aggregated as evidence, not silently
deduplicated away.

### 8.7 Negative evidence and retention limits

Absence of a log line is not necessarily proof that an event or execution did
not occur. Logging may have been disabled, history may have expired, or a device
may not have emitted the expected event. Treat missing runtime evidence as
**unknown** unless the observation mechanism itself is known to be complete for
the interval being analysed.

### 8.8 Advanced analysis remains downstream

Cycle detection, possible oscillation, transitive reachability, impact analysis,
collapsed views, evidence aggregation, and filters become meaningful only after
edge semantics are trustworthy. A structural cycle can be detected without
proving an operational feedback loop; the UI must keep those labels distinct.

## 9. Debugging runtime and causality

Use four runtime views together:

| Question | Best evidence |
| --- | --- |
| What was configured? | Rule definition/export and decoded storage |
| What executed? | Rule Machine logs |
| What future work remains? | App Status subscriptions and scheduled jobs |
| What happened in the world? | Device/location events and current state |

Then use Automation Map to inspect structural dependencies. The map can show
that a rule writes a Hub Variable or controls a device; logs and events establish
whether that path actually caused a later invocation in a particular incident.

For incident analysis, correlate in this order:

```text
source rule action log
    -> mediator state/event timestamp
    -> target trigger/event log
    -> target action log
```

If multiple producers can affect the same mediator, timestamp ordering alone is
not enough to attribute causality unless the intervening event/value also
matches. Keep the result at "possible" when attribution remains ambiguous.

## 10. Outstanding hub validation plan

**[unknown until run unless noted otherwise]** The controlled validation suite
below remains outstanding except where an existing local observation is called
out. Run it only with non-critical virtual devices. Do not use locks, garage doors, alarms, heaters,
irrigation, or other equipment where unintended activation matters.

Before testing, record hub model, platform build, Rule Machine version, date,
and tester. Create a virtual switch `RM Test Switch`, a harmless repeatable test
event source, Hub Variables `RM_Test_Result` and `RM_Test_Param`, and logging-only
actions where practical. Enable trigger and action logging. For each test retain
the rule/export, timestamped logs, subscriptions before/during/after, scheduled
jobs before/during/after, relevant device events, and platform build.

### T01 - Required Expression subscription gating

Create a rule whose Required Expression is `RM Test Switch is ON`, with a
separate test trigger and `Log: T01 fired`. With the switch OFF, record App
Status subscriptions, generate the trigger, then turn the switch ON, record
subscriptions again, and retrigger. Expected from sources: normal trigger
subscriptions are absent while false and restored when true; only what is needed
to detect expression recovery remains. Record screenshots and exact build.

### T02 - IF evaluates state and does not wait

Trigger on `RM Test Switch turns ON`; use an IF/ELSE over a false harmless
condition and log both the branch and completion. Leave the condition unchanged
for 30 seconds. Expected: false branch and completion occur immediately with no
subscription waiting for truth.

### T03 - Plain Delay schedules continuation

Log before a 30-second Delay and after it. Trigger, immediately capture scheduled
jobs, then capture logs and jobs after continuation. Expected: a scheduled
continuation exists during the Delay and the second log occurs when it fires.

### T04 - An individually delayed action does not hold later actions

Schedule one harmless action for 30 seconds later, followed by an immediate log.
If logs cannot be delayed, use a virtual-switch action and a logging observer.
Expected: the later list item runs near T+0; the delayed action runs near T+30.

### T05 - Wait for Event when state is already true

Set `RM Test Switch` ON, invoke a rule from a separate trigger, Wait for Event
`RM Test Switch turns ON`, and log after the Wait. Observe whether it resumes;
if not, toggle OFF then ON. Record subscriptions. Expected from sources: a new
matching event is required rather than current state satisfying the Wait.

### T06 - Wait for Condition/Expression when already true

Wait for `RM Test Switch is ON`. Test once with it already ON and once initially
OFF, turning it ON after ten seconds. Expected: immediate continuation when
already true; otherwise a subscription and later resume.

### T07 - Retrigger cancels Wait

On each trigger, log, Wait for elapsed time 30 seconds, then log completion.
Trigger at T+0 and T+20; inspect scheduled jobs around both. Expected: the first
Wait is replaced and completion occurs about 30 seconds after the second trigger.

### T08 - Retrigger does not automatically cancel Delay

On each trigger, log, Delay 30 seconds, then log continuation. Trigger at T+0
and T+20, inspect jobs, and count completions. Expected: two continuations can
remain, completing around T+30 and T+50.

### T09 - Controlled re-entry through nested logic

With harmless true conditions, nest two IFs, log, Delay 20 seconds, and log
again. Trigger, then retrigger five seconds later. Capture all logs and errors.
Do not assert a failure in advance; record current behavior under overlapping
execution and nested state.

### T10A - `Run Rule Actions` without a yield point

Rule B logs `B start`, logs `B end`, and contains no Delay, Wait, or Repeat.
Rule A logs `A before`, runs B's actions, then logs `A after`.

Expected from the 2021 author clarification for Rule-Machine-to-Rule-Machine
calls:

```text
A before
B start
B end
A after
```

Preserve the ordered log trace and exact engine versions.

### T10B - `Run Rule Actions` when the target yields

Rule B logs start, Delays 15 seconds, then logs end. Rule A logs before the run,
runs B's actions, then logs after.

Expected:

```text
A before
B start
A after
... about 15 seconds later ...
B end
```

The important observation is not merely that A continues. It is **where the
handoff occurs**: B executes inline until it reaches the Delay and exits/yields;
B's continuation runs later.

### T11 - Required Expression interaction with direct `Run Rule Actions`

Give Rule B a false Required Expression. Invoke it from A twice: once with
"Cancel pending actions when Required Expression becomes false" disabled and
once enabled. Record whether B runs in each case. Expected from 2024 author
guidance: the expression alone is not normal trigger admission for this direct run,
but cancellation configuration may prevent the target actions.

### T12 - Rule Function concurrency

Create a function that accepts `RM_Test_Param`, captures `%param%`, yields for
five seconds, and returns the captured value. Call it nearly simultaneously
from two rules with different values. Do not pre-assert correctness; record any
parameter or return interference.

### T13 - Legacy `Ignore trigger events while running` inventory

Create a new Rule Machine 5.1 rule, record the platform build, and inspect every
rule-level option. Also inspect at least one older rule if the hub has a legacy
rule that used this option.

Current official Rule 5.1 documentation states that the option was available
only in platform 2.3.9; existing rules using it continue to function, but new
rules do not receive it. Bruce Ravenel later confirmed its removal because it
could leave rules stuck.

Expected for a current new rule: the option is absent. Record any legacy rule
where it remains visible and do not silently rewrite its semantics.

### T14 - Rule Function storage discriminator

Create one ordinary action-only Rule-5.1 rule and one Rule Function with the
smallest possible body. Export both and compare `installedApp`, `appState`, and
`appSettings`.

A normal rule fixture already exposes an `isFunction` boolean preference whose
value was empty. That is a **candidate field**, not evidence of a discriminator.
Record whether a real Rule Function sets it, and whether the field remains stable
after save/export/import.

Do not change the current "indistinguishable" implementation claim unless this
test produces a repeatable discriminator.

### T15 - Built-in token / Hub Variable name collision

Create a harmless Hub Variable using one built-in token name, preferably
`RM_Test_Token` first for control and then a reserved-name fixture such as
`time` only if Hubitat permits it. Compare:

- structured picker reference;
- `%VariableName%` interpolation;
- built-in `%time%` or equivalent token use.

Expected: document whether Automation Map can distinguish the built-in token from
a real variable with the same spelling. If it cannot, preserve the current
conservative heuristic and explicitly mark the ambiguous case rather than
asserting a read edge.

### Validation matrix

| Claim | Test | Status |
| --- | --- | --- |
| Required Expression changes trigger subscriptions | T01 | Pending validation |
| IF evaluates current state and does not wait | T02 | Pending demonstration |
| Plain Delay schedules continuation | T03 | Pending demonstration |
| Individually delayed action permits later actions | T04 | Pending demonstration |
| Wait for Event needs a future event | T05 | Pending validation |
| Wait for Condition may pass immediately | T06 | Pending validation |
| Retrigger cancels outstanding Wait | T07 | Pending validation |
| Retrigger does not automatically cancel Delay | T08 | Pending validation |
| Nested logic under re-entry is platform-sensitive | T09 | Pending observation |
| Run Rule Actions is sequential until target exit/yield | T10A-T10B | Pending demonstration |
| Required Expression/direct-run interaction | T11 | Pending validation |
| Rule Function concurrency shares practical state | T12 | Pending practical test |
| Legacy ignore-trigger option inventory | T13 | Pending target-hub check |
| Rule Function storage discriminator | T14 | Pending fixture |
| Built-in token / Hub Variable collision | T15 | Pending ambiguity test |

## 11. Sources and provenance

Runtime material above is paraphrased from these public sources and remains
locally unvalidated unless a T-test result is later recorded:

1. Hubitat Documentation - Rule 5.1:
   https://docs2.hubitat.com/en/apps/rule-machine/rule-5-1
2. Hubitat Developer Documentation - App Overview:
   https://docs2.hubitat.com/en/developer/app/overview
3. Bruce Ravenel - Required Expression vs Conditional Action:
   https://community.hubitat.com/t/required-expression-vs-conditional-action/110670/7
4. Bruce Ravenel - Delay, Wait, retriggering, and rule instances:
   https://community.hubitat.com/t/rm-feature-request-prevent-rule-from-triggering-if-already-running/137609?page=4
5. Bruce Ravenel - Rule Functions and shared app state:
   https://community.hubitat.com/t/rule-machine-rule-functions/146774
6. Bruce Ravenel - Run Rule Actions and loops:
   https://community.hubitat.com/t/run-rule-actions-and-loops/45179
7. Bruce Ravenel - Rule Actions / Required Expression clarification:
   https://community.hubitat.com/t/question-about-rule-machine-rule-actions-required-expression-c8-v2-3-9-162/141009
8. Bruce Ravenel - Required Expression subscription behavior:
   https://community.hubitat.com/t/unexpected-behaviour-of-required-expression-versus-trigger/149623/44
9. Hubitat Community - Wait for Event vs Wait for Expression:
   https://community.hubitat.com/t/rule-machine-require-expression-and-wait-condition-not-firing/94911
10. Bruce Ravenel - removal of "Ignore trigger events while running":
    https://community.hubitat.com/t/bring-back-the-dont-run-while-running-switch/154432
11. Hubitat Community - release notes index:
    https://community.hubitat.com/c/news/release-notes/55
12. Bruce Ravenel - nested `Run Rule Actions`, same-thread execution and yield boundary:
    https://community.hubitat.com/t/nesting-run-rule-actions-commands/78479

Implementation-status claims must be tied to an identifiable source snapshot.
For this review, the available `automation_map.groovy` source snapshot and the
later `handoff.md` checkpoint are not identical: the handoff records a subsequent
Rule-Machine-only Hub Variable extraction fix (hub revision 52) that is not
present in the older attached source snapshot. Do not collapse those into one
unqualified "current code" claim.

Before publishing or using this file as an implementation contract, record:

```text
APP_VERSION:
GRAPH_SCHEMA:
Git branch:
Git commit/blob SHA (or "local only"):
Hub code revision if local-only:
Hub platform build:
Review date:
```

Storage-field evidence and fixture scope remain documented in
`rule_machine_5_1_storage_format.md`.
