# Reverse-engineering the Rule Machine 5.1 storage format

Notes for anyone building a tool that reads Rule Machine rules from a Hubitat hub.

**Status:** derived empirically from a C-8 running platform 2.5.1.142, cross-checked against
each rule's own page in the Rule Machine UI. The sample was one hub with 38 Rule-5.1 rules,
so some findings rest on a single example. Each claim carries an evidence marker; see
"How confident is each finding" below.

**Section 13 added 2026-08-15**, covering Hub Variables (reading, writing, triggers,
Required Expression, free-text interpolation). That section rests on a handful of
deliberately-constructed fixtures on one hub, not a corpus survey - its evidence markers are
correspondingly weaker than sections 1-11's, and should be read as such.

**Updated 2026-09-18.** Cross-checked against the community Hubitat Local MCP Server's
source (kingpanther13/Hubitat-local-MCP-server), which drives the same rules through the
wizard rather than reading them, and re-verified on a C-8 running 2.5.1.183 across 66
Rule-5.1 rules. That pass corrected two claims (sections 3 and 5.2), added the
`parens` / `inUseConds` / `unusedConds` keys, the Wait-for-Events key family, the numeric
Set Variable discriminator, and section 14.

**Updated 2026-09-15.** Section 5.2 now follows Rule Machine's author on mixed AND/OR: a
left-to-right walk that stops early, replacing an earlier right-grouping reading. New
observations on firmware 2.5.1.183 were added to sections 8 (an own-id "this rule" target),
10.3 (subscriptions while a Required Expression is false) and 13.8 (deleting a variable a rule
still uses).

**Design principle, if you take only one thing from this:** do not manufacture meaning from
undocumented fields. Retain what you do not recognise, flag it, and refuse to guess. Section
9.1 exists because guessing what one field meant produced 28 confident and entirely
fictional relationships on a 38-rule hub.

---

## 1. Scope and warnings

This describes how Rule Machine **stores** a rule, not how it **executes** one. For
execution semantics (delays, waits, retriggering, simultaneous instances) the official
[Rule 5.1 documentation](https://docs2.hubitat.com/en/apps/rule-machine/rule-5-1) is the
authority and covers the ground properly.

That split is not just a scoping convenience. It is the real boundary:

> The stored representation contains enough information to **reconstruct** a rule, but not
> necessarily enough to independently **execute or reason about** one correctly.

Reconstruction is well supported. You can recover a rule's triggers, conditions, ordered
actions and targets, and check the result against the rule's own page. Evaluation is not:
section 5.2 shows the stored expression carries no grouping, and Rule Machine evaluates it
as a left-to-right walk that stops early, not with conventional precedence.

A tool that displays what a rule *is* stands on solid ground. A tool that decides what a
rule *would do* is reimplementing Rule Machine from an undocumented format, and will be
wrong in ways its author cannot see.

Three warnings before you build anything on this:

- **None of it is a public API.** These are the hub's own internal endpoints, the ones its
  administration UI calls. Field names, shapes and semantics can change in any platform
  release, without notice, because nothing here is a documented contract.
- **Read-only.** Everything below is about reading. Writing rule configuration through
  these structures is not covered and is not advisable.
- **Treat the rule page as the authority.** When your reconstruction disagrees with what
  Rule Machine shows on the rule's own page, your reconstruction is wrong. That rule of
  thumb caught every bug described in section 9.

### How this was worked out

Rule Machine is a built-in app, so there is no source to read: `/app/ajax/code` returns an
empty body for it. Everything here was obtained by observing stored state and comparing it
against what Rule Machine displays.

**1. Ground truth is the rule page.** Every finding was checked against what Rule Machine
itself shows for that rule. This is the whole method in one sentence: the hub renders the
rule correctly by definition, so any reading of the stored data that produces a different
rule is wrong. Nothing here was accepted because it looked plausible.

**2. Differential reading.** Take a rule whose displayed behaviour is known, read its
`appState` and `appSettings`, and work out which stored fields account for which displayed
elements. Fields that changed when a rule changed were the informative ones.

**3. Corpus checking rather than single examples.** A pattern noticed in one rule was then
queried across all 38 by script. This is what the evidence markers record, and it repeatedly
mattered. "Every action carries a `rule` field" survived several rules and was **false**
across the corpus. The thirteen object shapes in section 3.1 and the null-value table in
section 9.1 both came out of corpus queries, not from reading rules individually.

**4. Building a decoder as the test.** The real validation was implementing the format in a
working app that renders each rule as a flowchart, then comparing every flowchart against
its rule page. A misreading is not subtle in that setup: it produces a visibly wrong rule.
Three of the five traps in section 9 were found this way rather than by inspection,
including the `rule` field, which produced 28 confident and entirely fictional relationships
before anyone noticed.

**5. Constructing cases the corpus lacked.** Where no rule exercised something, one was
built to order. That is how the rule-to-rule actions in section 8 were established, and
building a test rule that used all of them immediately exposed a whole action family
(`getPauseResumeRules`) that had been missed.

**6. Checking the official documentation before claiming novelty.** Execution semantics
turned out to be well covered by Hubitat already, which is why this document is scoped to
storage. Do not assume something is undocumented because it is undocumented in the place you
first looked.

**Not done, and therefore not claimed:** no decompilation and no access to Rule Machine's
source; no writes to rule configuration; evaluation semantics only through the two Required
Expression results in section 5.2 and the author's own description; one hub, one
platform build, one person's rules.

### How confident is each finding

Reverse-engineered notes are worth little without saying how well evidenced each claim is.
Findings below carry one of these markers:

| Marker | Meaning |
| --- | --- |
| **[invariant]** | held across all 38 rules examined, with no counter-example |
| **[strong]** | held in every case examined, but the sample did not cover every variation |
| **[limited]** | a handful of samples only, stated as a caution rather than a rule |
| **[single]** | one observation, which is not evidence of a pattern |
| **[heuristic]** | a technique that works in practice, not a property of the format |
| **[unknown]** | explicitly not established |
| **[external]** | asserted by another tool's source, not observed here; the source is named |

Unmarked prose is description or advice rather than a claim about the format.

### The short version

If you read nothing else, read these:

- An action's parameters are **not** in the action. They are in the app's settings, keyed by
  the action number. Neither half is usable alone (section 3). **[invariant]**
- Some actions carry a field called `rule` which is **not a rule reference**. It is a
  condition index, and it is non-null for only three action types (section 9.1). **[invariant]**
- `indent` does not reliably describe nesting. Do not build a tree from it (section 9.2). **[strong]**
- Key your action scan on `actSubType.<n>`. `actType.<n>` is missing on block closers such as
  END-IF and ELSE, so an actType-keyed scan loses them (section 3). **[strong]**
- `eventSubscriptions` is a snapshot that changes with Required Expression state, so a
  rule's triggers can appear to vanish (section 10.3). **[strong]**
- `%device%`, `%time%`, `%date%` are Rule Machine's own reserved notification tokens, not
  Hub Variables, and match the exact same `%Name%` syntax a real variable reference uses
  (section 13.7). **[strong]**

### A note on reading settings

`/installedapp/statusJson/<id>` returns **every** setting an app holds, and that is not
limited to Rule Machine. Other apps store API tokens, passwords, cloud endpoints and account
identifiers in ordinary settings.

If you build something that reads this endpoint, persist only the fields your model needs
and redact everything else by default. Do not log whole settings blocks, and be careful
about what ends up in an exported map, a diagnostic bundle or a GitHub issue attachment.

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

    "4": { "quick": false, "method": "getIfThen", "indent": "", "rule": 2 }

`appSettings` holds that action's *parameters*, keyed `<prefix>.<actionNumber>`:

    actType.4      = condActs
    actSubType.4   = getIfThen

Neither half is usable on its own. The action object tells you an On/Off switch action
exists; only the settings tell you which device and whether it is on or off.

Two settings accompany an action: **[strong]**

| Setting | Meaning |
| --- | --- |
| `actType.<n>` | the action *family*, e.g. `switchActs`, `dimmerActs`, `condActs`, `delayActs`, `rulesActs` |
| `actSubType.<n>` | the specific action, matching the `method` in the action object |

`actSubType` duplicates `method`. Prefer whichever you like, but `actSubType` is the safer
primary key, because **`actType` is not always written**. **[strong]**

**Correction, 2026-09-18.** An earlier version of this document said both settings accompanied
every action examined. A scan of 66 rules on 2.5.1.183 found 209 actions carrying both and 8
carrying `actSubType` alone: `getEndIf` (three rows), `getElse` (one), and four rows whose
`actSubType` value is an empty string. Every one of the named cases is a block closer, which
is what the community MCP Rule Server's source also reports for rows written by Rule Machine's
own UI. A scan keyed on `actType.<n>` silently drops those rows and, with them, the end of
every IF block they close. Key the scan on `actSubType.<n>` and treat `actType.<n>` as
optional enrichment. **[strong]**

The family list is longer than the five named above. The MCP server's source names twelve:
`condActs`, `delayActs`, `deviceActs`, `dimmerActs`, `lockActs`, `messageActs`, `modeActs`,
`repeatActs`, `rulesActs`, `sceneActs`, `soundActs`, `switchActs`, with several non-obvious
placements (Hubitat Safety Monitor sits under `lockActs`). **[external]**

### 3.1 Action objects come in more than one shape

Do not assume a fixed set of fields. Across 38 rules the action objects took **13 distinct
shapes**. The three most common:

    {indent, method, quick}                                    175x
    {delay, indent, method, quick}                              30x
    {cond, delay, indent, method, modes, quick, rule, wait}     19x

The abbreviated shape carries three fields. The fullest shape seen carries ten, including
`label` and `nested`. Whether an action is stored abbreviated or full is **not** determined
by its method: `getOnOffSwitch` appears in both. **[invariant]**

The practical consequence is that **presence of a key means nothing**. Test values, not
keys. Section 9.1 is the case where this matters most.

Thirteen shapes across one action type suggests the objects are not serialised from a clean
per-action schema. Whatever the cause, the safe conclusion for a reader is the same: this is
not a typed structure and should not be deserialised as one.

### 3.2 Treat the action object as an untyped property bag

Do not model actions as method-specific types with required fields. Read them defensively
and take meaning from the settings instead:

    actionList  ->  action number
                ->  actSubType.<n>          what kind of action this is
                ->  settings ending .<n>    what it is configured to do
                ->  action object fields    only where non-null and understood

Put another way: **`actSubType` tells you what an action probably is, the settings tell you
what it is configured to do, and the presence of a property in the action object tells you
almost nothing.**

Prefer the setting over the action object even where both carry the same information.
`actSubType.<n>` and `method` agree in every case observed, but `actSubType` was always
present while the action object's field set varies. A reader that falls back from `method`
to `actSubType` costs one line and removes a whole class of failure.

---

## 4. Execution order

**Settings key order means nothing.** `appSettings` is neither display order nor numeric
order: rule 1809 returns `actType.38`, `actType.23`, `actType.61`, `actType.63` before any
`actSubType.<n>` key at all. The compiled state's `actionList` is the only display-ordered
sequence the hub exposes, and it lists action numbers as strings. **[strong]**

`appState.actionList` is the ordered list of action numbers.

**It is not the numeric order of the keys, and not the insertion order.** A real example:

    actionList: 7, 6, 4, 1, 5, 2, 8, 3

Action 7 runs first and action 3 runs last. Action numbers are stable identifiers assigned
when an action is created; reordering actions in the UI rewrites `actionList` and leaves the
numbers alone. Iterating the `actions` map directly will give you a rule in an order that
resembles the user's rule only by accident.

---

## 5. Conditions and expressions

Conditions are numbered independently of actions, and are stored across several structures.

### 5.1 Human-readable text

`capabstrue` and `capabsfalse` together describe **every** condition in plain text. The
split between them is only whether the condition currently evaluates true:

    capabsfalse["7"]  = "Time between Sunset-15 minutes(18:08) and 21:30"
    capabsfalse["12"] = "Temperature of _ Average External Temperature(20.2) is <= 15.0"
    capabstrue["1"]   = "Theatre Room Motion Sensor motion reports active"

Merge both maps to get the full set. Do not read anything into which map a condition landed
in beyond its truth at the moment you fetched.

The text carries HTML, so strip tags before displaying it.

### 5.2 Expressions

`eval` maps a branch number to the condition expression for that branch:

    eval["0"] = [5, "AND", 7, "OR", "10"]
    eval["1"] = 2
    eval["2"] = 12

**The value type is inconsistent, including within a single value.** Across one rule it is a
list, a bare integer and, elsewhere, a quoted string. Worse, that first list mixes integer
condition numbers `5` and `7` with the *string* `"10"`. Coerce everything to string and
handle all shapes, or you will crash on the very common single-condition branch and silently
mis-handle mixed lists.

Operators appear inline as strings between condition numbers. **Each `eval` entry is flat and
carries no grouping of its own** for the expressions examined. **[strong]**

**Correction, 2026-09-18.** An earlier version concluded from that flatness that grouping is
not stored at all, and listed parentheses as not examined. Grouping has its own key.
`parens` sits beside `eval`, in both `appState` and the compiled state, keyed by the same
branch numbers: rule 1809 returns `parens = {"0": 0, "11": 0, "12": 0, ...}`. Every rule on
this hub returns zero for every branch, because none of them groups its conditions, so the
encoding of a non-zero value is **[unknown]** here. That the hub authors grouping at all is
confirmed by the MCP server's source, whose wizard writer opens a sub-expression with the
condition-picker value `b` and closes it with the operator value `end-sub-expression )`,
nested to any depth. **[external]** Treat a non-zero `parens` entry as a signal to stop
trusting a flat left-to-right read of that branch.

**Do not infer evaluation order from that flatness.** Rule Machine's author describes the
evaluation directly: **[strong]**

> "it is a strictly left to right evaluation, and once something to the left of OR is true,
> or the left of AND is false, it returns true or false and stops evaluating."
> (bravenel, Hubitat Community, [13 Oct 2021](https://community.hubitat.com/t/rule-5-1-predicate-and-repeat-while-until-rule/81158/2))

The operator vocabulary is `AND`, `OR` and `XOR`; the MCP server validates exactly that set,
and `NOT` is not an inline operator but a per-condition `not<n>` flag. **[external]** Nothing
is claimed here about how `XOR` interacts with the early stop.

The same rule appears in his original Rule Machine introduction
([6 Feb 2018](https://community.hubitat.com/t/rule-machine-introduction/307/1)), which adds
that `NOT` applies only to the term immediately after it and that brackets form a sub-rule
evaluated on its own.

Read that carefully: "left to right" here does **not** mean folding the terms together left to
right, `((A AND B) OR C) AND D`. It means walking the chain and **stopping the whole
expression** at the first false before an AND or the first true before an OR. Two live tests
reading the Required Expression result the rule page prints agree with that description:

| terms | expression | condition values | result | build |
|---|---|---|---|---|
| 3 | `A AND B OR C` | F, F, T | FALSE | 2.5.1.140 |
| 4 | `A AND B OR C AND D` | T, T, F, F | TRUE | 2.5.1.147 |

In the three-term test, A is false before an AND, so evaluation stops at FALSE and C is never
read. In the four-term test, B is true before an OR, so evaluation stops at TRUE and C and D
are never read. Conventional precedence, `(A AND B) OR C`, would have printed TRUE for the
first test, so it is ruled out.

**Correction 2026-09-15.** An earlier revision of this section concluded from these two tests
that Rule Machine groups from the right. That was wrong framing: it compared the results
against a fully evaluated left fold, labelled "left to right", and missed the early stop the
author describes. Right grouping happens to give the same answers for these cases, but it is
not how Rule Machine describes its own evaluation, so do not describe it that way. Neither
test used NOT or XOR, and XOR is not covered by the author's description.

The practical consequence is worth stating because it bites real rules: **a term to the right
of an OR is never read whenever the terms before that OR evaluate true.** A Private Boolean placed last in `Mode AND Evening OR Morning AND PB` is silently
ignored throughout the evening window. Observed on a live rule, diagnosed as a lamp that never
turned off.

The obvious workaround, moving the gate into the actions as an early `Exit Rule`, is **not
available for a Private Boolean**. Rule Machine 5.1 uses a different capability list for
"Select capability for Action Condition" than for Required Expression conditions, and Private
Boolean is absent from the action list. Verified on 2.5.1.140: the action list runs
`... Power source, Presence, Switch, Temperature, ...` with no `Private Boolean` entry where
alphabetical order would place it. **[single]** The remaining fixes are to group the
alternatives in brackets, `Mode AND (Evening OR Morning) AND PB`, or to move the gate leftmost so a
false gate stops the evaluation before any OR is reached.

Not examined at all, and required before anyone writes an evaluator: explicit grouping or
parentheses, NOT, whether `eval[n]` can reference another expression rather than a bare
condition, and any operators beyond AND and OR. **[unknown]**

For reconstructing and displaying a rule, reproduce the stored sequence as it stands and let
the reader apply their own understanding, which is what Rule Machine's own page effectively
does.

`eval["0"]` is the Required Expression when `hasPredicate` is true. `predCapabs` lists the
condition numbers it involves, with duplicates, so deduplicate if you use it.

**`eval` keys above 0 do not follow `actionList` order.** Do not pair the nth `eval` entry with
the nth conditional action; it is wrong and the failure is silent, because both sequences are
plausible and a mismatched pair still renders. Live counter-example, rule 2329, whose action
order is IF, ELSE-IF, END-IF, WAIT: **[single]**

    eval["1"] = [8, "OR", 16]              the WAIT   (fourth conditional in action order)
    eval["2"] = [18, "OR", 21, "AND", 23]  the IF     (first)
    eval["3"] = ["23"]                     the ELSE-IF (second)

The correct association has not been established. Until it is, a tool that needs to name which
action an expression belongs to should say it cannot, rather than assume order. Matching on the
condition numbers rendered in the rule's own page is currently the only reliable check.

**`capabstrue` and `capabsfalse` are NOT a per-condition truth cache.** Reading them as one
produces confident nonsense: on a live rule they reported every condition false, including a
Private Boolean whose `private` value was demonstrably `true`, which led to a false diagnosis
of a stuck rule. Whatever they hold, it is not "which conditions currently evaluate true", and
no tool should present them as condition state. The rule's own page computes truth at render
time; the stored state does not carry it in these keys. **[strong]**

A Private Boolean test inside a Required Expression is an **ordinary numbered condition** in
`eval["0"]` and `predCapabs`, not a special case. Live sample, rule 2325: **[single]**

    eval["0"]   = [5, "AND", 7, "OR", "10", "AND", 15]
                   Mode      Evening   Morning     PrivateBoolean
    predCapabs  = [5, 7, "7", 10, 15, "7", "10", "7", "7", "7", "7"]

Note the duplicates and the mixed integer/string types in both, consistent with 5.2. Separate
state keys carry the boolean's own value and are easy to confuse with the condition:
`private` is the rule's actual Private Boolean value, `p.PB` mirrors it, and `predPB` is a
flag rather than the condition. Read the value from `private`, and read the *test* from the
condition referenced in `eval["0"]`. **[single]**

### 5.3 Condition definitions

Each condition also has settings describing how it is built:

    rCapab_12  = Temperature          the condition type
    state_12   = 15                   the comparison value
    RelrDev_12 = <=                   the operator

    rCapab_7           = Between two times
    starting7          = Sunset
    startSunsetOffset7 = -15
    ending7            = A specific time
    endingA7           = 21:30
    atOrBetween7       = false

A time condition is built from a starting kind and an ending kind, each either a clock time
or a sun event, with an optional offset in minutes. Condition 7 above reads as "between 15
minutes before sunset and 21:30".

You rarely need these if you are using `capabstrue`/`capabsfalse`, which already render the
condition in words. They matter if you want the raw values rather than the prose.

---

### 5.4 `inUseConds` and `unusedConds`

`appState` carries two lists beside `eval`, and the compiled state repeats them:
`inUseConds` and `unusedConds`, both arrays of condition numbers as strings. They look like
the hub's own answer to "which conditions does anything evaluate", which is the question
section 9.1 and Automation Map's UNUSED tagging otherwise answer by inspection.

**Do not use them for that without further work.** On rule 1809 `unusedConds` is
`["44", "55", "48", "38", "51"]`, yet `38` and `44` are both named in that rule's Required
Expression (`eval["0"] = [38, "AND", "44"]`) and in `predCapabs`. Either the lists mean
something narrower than their names suggest, or they are stale. Present on 45 of 66 rules.
**[unknown]**

### 5.5 Comparator and shape keys

The condition and trigger sides use the same asymmetry as `tDev`/`rDev_`: **[strong]**

| Key | Side | Note |
| --- | --- | --- |
| `ReltDev<n>` | trigger | comparator for a trigger row |
| `RelrDev_<n>` | condition | comparator for a condition row |
| `isDev_<n>` | condition | true reveals a device-relative comparison |
| `isVar_<n>` | condition | true reveals a variable-to-variable comparison |
| `AlltDev<n>` | trigger | "all of these" rather than any |
| `AllrDev_<n>` | condition | the same qualifier, underscore form: `"true"` is all, empty is any |
| `AlltDev-<n>` | Wait for Events row | the same qualifier again, dash-indexed with the rest of that family |
| `stays<n>`, `SHours<n>`, `SMins<n>`, `SSecs<n>` | trigger | "and stays" duration |
| `disableT<n>` | trigger | one trigger disabled in place |
| `isCondTrig<n>`, `condTrig<n>` | trigger | a condition attached to one trigger row |

Comparator values are stored as the glyph, not ASCII: `RelrDev_2` on rule 2990 reads `≠`,
never `!=`. String comparisons are stored as asterisk-wrapped literals, `*changed*` and
`*contains*`; there is no "does not contain", which is `not<n>` plus `*contains*`.
**[strong]** For the device-relative shape the MCP server's source reports that `state_<n>`
carries the offset rather than a comparison value, so `state_<n>` is not always "the value".
**[external]**

Time values are polymorphic. `atTime<n>` holds `21:05` for a daily trigger, and a full ISO
datetime for a one-shot dated trigger. **[external, for the ISO form]** A time range writes
`starting<n>` / `ending<n>` as a mode word such as `A specific time`, with the value in
`startingA<n>` / `endingA<n>` (`06:00` on rule 2195). **[strong]** Mode conditions store the
mode **id** in `modes<n>` (`["2"]`), not the mode name; the MCP server reports the trigger
side writes `modesX<n>`. **[strong]** / **[external]**

A conditional trigger (`isCondTrig<n>` with `condTrig<n>`) is evaluated **after** the trigger
event fires, which is the structural opposite of a Required Expression: the expression decides
whether the subscription exists at all, the conditional trigger filters an event that already
arrived. **[external]**

### 5.6 `eval` holds every expression ever built, not the live ones

`state.eval` maps an expression id to the condition indices and operators that expression uses. It
is the authority for **what an expression references**, and it is not an authority for **which
expressions the rule runs**. RM keeps an entry for every expression built in the editor, whether it
ended up attached to anything or not.

Rule 2076 carries nine entries and two live ones. `eval["7"] = ["71"]` is orphaned, and reading
`eval` as the live set makes condition 71's 06:00-21:00 window look referenced when the rule
renders no IF at all.

**The live set is `eval["0"]`, the Required Expression, plus each action's own expression id** taken
from `state.actions` (an IF or Wait for Expression carries its id as the action's `rule` field). Any
other entry is editor residue, in the same family as 7.7 kind 4: it exists, it is well-formed, and
nothing runs it.

Found by the other engine's session while mechanising a check; the orphaned entry announced itself
as an arithmetic impossibility rather than as a wrong answer. **[strong]**

#### 5.6.1 Two cautions on counting conditions

**A time condition does carry `rCapab_<n>`.** Both `Between two times` and `Time of day` appear
there like any other condition type: 2076 holds `rCapab_57`, `rCapab_60` and `rCapab_71` all as
`Between two times`, and 1809 holds `rCapab_55` the same way. A claim that time conditions have no
`rCapab_` key was tested against both rules and does not hold.

**`rCapab_<n>` can be an empty string.** 1809 carries `rCapab_45 = ""` and `rCapab_3 = ""`. A
"defined conditions" set built by filtering `rCapab_` entries on truthiness silently drops those,
which is enough to make a rule look as though it references more conditions than it defines. Test
for the key's presence, not its truthiness - the same distinction as `AllrDev_<n>`, where empty
means **any** rather than unset. **[strong]**
## 6. Separating triggers from conditions

This is the most useful distinction in the whole format and the basis for classifying what
role a device plays in a rule.

| Prefix | Meaning |
| --- | --- |
| `tDev<n>` | devices that **trigger** condition n |
| `rDev_<n>` | devices used in condition n as a **condition** |

    tDev1   -> Theatre Room Motion Sensor      (the trigger, motion becomes active)
    rDev_2  -> Theatre Room Motion Sensor      (the same device, as a condition)
    rDev_12 -> _ Average External Temperature  (a temperature gate)

The same physical device appears as both, and means different things each time. A tool that
keys on device id alone cannot tell a rule's trigger from its gating conditions; a tool that
keys on the setting prefix can.

**Note the inconsistent underscore.** It is `tDev1` but `rDev_2`. Likewise `tCapab1` and
`tstate1` against `rCapab_2` and `state_2`. This is not a typo in this document.

---

### 6.1 Wait for Events uses dash-indexed, rule-scoped keys

A Wait for Events action does not store its event rows per action. They live in rule-scope
settings whose index is separated by a **dash**: `tCapab-<n>`, `tDev-<n>`, `tstate-<n>`,
`stays-<n>`, `SHours-<n>`. Rule 1230 carries `tCapab-5 = Certain Time (and optional date)`,
`tDev-4`, `tstate-4 = closed`. A device in `tDev-4` is a wait target; a device in `tDev4`
is a trigger. Read the dash. **[strong]**

A Wait for Events carries its own all-or-any qualifier per row in `AlltDev-<n>`, and **its rows are
OR'd together**. Rule 1230 holds `tCapab-4 = Contact` with five doors, `tstate-4 = closed` and
`AlltDev-4 = "true"` alongside `tCapab-5 = Certain Time (and optional date)` with `atTime-5 =
02:00`, and renders as "... all contact is closed / OR When time is 02:00". So "all" is within a
row and "or" is between rows. A multi-device threshold puts the qualifier where the operator would
go, rendering "Back Garden Left, Back Garden Right(1, 1) all >= 500" rather than "is >= 500".
Found by the other engine's session and confirmed here against 1230's live settings. **[strong]**

Because the keys are rule-scoped rather than per-action, **only one Wait for Events action can
exist per rule**: a second overwrites the first. The MCP server reports the same limit in
Hubitat's own UI. **[external]**

### 6.4 A Location Event trigger subscribes by name alone

A trigger on a hub-level event stores only two keys, with a bare index and no dash:

| Setting | Value |
| --- | --- |
| `tCapab<n>` | `Location Event` |
| `tstate<n>` | the event name, e.g. `lowMemory`, `severeLoad` |

There is no device, comparator or value. The rule fires on any occurrence of that named event.
Rule 2100 carries `tCapab12 / tstate12 = lowMemory` and `tCapab47 / tstate47 = severeLoad`.

The event's payload reaches the actions through the usual tokens. `severeLoad` was observed
with `%value% = 2.52` and `%text% = "Severe hub CPU load detected"`, so it carries a numeric
load figure.

`lowMemory` was measured on 2026-09-24 from seven days of location-event history, five occurrences:

| Field | Value |
| --- | --- |
| `name` | `lowMemory` |
| `value` | free memory in **KB**, as a string: `63352`, `59888`, `37808`, `52300`, `62720` |
| `unit` | `null` - the KB are not carried, a consumer has to know them |
| `descriptionText` | `Hub memory is low` |
| `type` | `SYSTEM` |
| `isStateChange` | `true` |

The scale is corroborated by the hub reporting `freeMemoryKB` of 169252 while healthy. The event
repeats while memory stays low rather than firing once per episode: three occurrences on
2026-09-20 at 17:10, 17:20 and 17:25. With `isStateChange` true each one is delivered, so a rule
triggered on it runs every time, which is what rule 2100 relies on when it counts occurrences.
**[strong]**

## 7. Action parameters by family

Once you know an action's `actSubType`, its parameters follow a `<prefix>.<n>` convention.
A non-exhaustive list of ones confirmed on a live hub:

| Action | Parameter settings |
| --- | --- |
| `getOnOffSwitch` | `onOffSwitch.<n>` device, `onOff.<n>` true/false |
| `getSetColorTemp` | `ct.<n>` device, `ctL.<n>` kelvin, `ctLevel.<n>` level |
| `getSetVolume` | `volume.<n>` device, `volumeVal.<n>` level |
| `getMsg` | `msg.<n>` text, plus **two** independent device pickers - see 7.2 |
| `getDelay` | `delaySecond.<n>`, `delayMin.<n>`, `delayAct.<n>` |
| `getWaitRule` | condition via the action's `rule` field, `delay` on the action for timeout |
| `getIfThen`, `getElseIf` | condition via the action's `rule` field |
| `getDefinedAction` | `devices.<n>` device list, `myCapab.<n>` capability, `cCmd.<n>` command, `meter.<n>`, `meterMillis.<n>` |

Most actions also carry `delayAct.<n>`, which is `none` unless the individual action has its
own delay.

The reliable general approach is: for action `n`, collect every setting whose name ends in
`.<n>`. That finds the parameters without needing a table for every action type, which
matters because the list above is certainly incomplete.

### 7.4 A delay can take its length from a variable, and the unit is not stored

A Delay action normally carries `delayHour.<n>` / `delayMinute.<n>` / `delaySecond.<n>`. It can
instead take its length from a variable, in which case `xVar.<n>` names the variable and **no
time field is present at all**.

The settings do not record the unit. Nothing stored says whether the variable holds seconds or
minutes, so a reader cannot recover the intended duration from the rule alone. Anything
converting this has to carry the operand and stop, rather than assume a unit: guessing wrong
produces a rule that waits sixty times too long or too short while reading as correct
everywhere. **[strong]**

### 7.2 A message action carries two device pickers, not one

`getMsg` holds two unrelated target lists on the same action, and a rule commonly populates
both:

| Setting | Target |
| --- | --- |
| `note.<n>` | notification devices |
| `speakDevice.<n>` | speech devices |
| `speakVolume.<n>` | volume for the speech devices, may be blank |

Reading only `note.<n>` loses every spoken announcement while the action still decodes as a
message, so the failure is silent. Found across a 62-rule corpus where nine messages decoded
to no target at all; a four-rule sample showed none of them. **[strong]**

`ranMsg.<n>` sits beside these and selects a random message variant rather than a target.

### 7.3 The colour keys are named the opposite way round from what you would assume

**Corrected 2026-09-24.** This section previously said `colorH.<n>` carries a hue percentage and
holds a hex string only for a custom colour. That was wrong in both halves. It was contradicted
by the other engine's reading of 62 live rules and then settled by walking RM's own action
wizard and reading the input definitions it emits.

`color.<n>` holds the picker's mode, and the mode decides which other keys exist:

| `color.<n>` | Keys that appear | Input type |
| --- | --- | --- |
| a colour name (`Red`, `Soft White`, ...) | `colorLevel.<n>` | number, "Bulb level?" |
| `Pick a Color` | `colorH.<n>` | **color** swatch, "Pick a color" |
| `Custom HSB color` | `colorHex.<n>`, `colorSat.<n>`, `colorLevel.<n>` | **number**, "Hue value?" / "Saturation value?" / "Bulb level?" |
| `Custom RGB color` | `colorH.<n>` | **text**, "RGB value?" |
| `Random color` | none | |

So `colorHex.<n>` is the numeric hue percentage, despite the name, and `colorH.<n>` is the
hex or RGB string, despite the name. `colorH.<n>` never holds a hue number. Anything that
reads `colorH` as a number, or `colorHex` as a hex string, has it backwards.

The companion booleans mark which fields are variable-sourced: `uVar.<n>` level,
`uVar2.<n>` hue, `uVar3.<n>` saturation, `uVar4.<n>` RGB.

What each mode sends at run time, measured on a virtual RGBW light in one rule:

| Mode, as authored | Command RM sends |
| --- | --- |
| `Red` (a name) | `setColor([hue:100, saturation:100, level:null])` |
| `Custom HSB color`, hue 50 sat 60 level 40 | `setColor([hue:50, saturation:60, level:40])` |
| `Pick a Color`, `#FF00F7` | `setColor([hue:83, saturation:100, level:100])` |
| `Random color` | `setColor([hue:1, saturation:100, level:null])` |

Custom HSB passes its three values through unchanged. `Pick a Color` converts the hex to HSB and
**sends a level of 100 even though the action carries no level field**, because the hex encodes a
brightness the named and random paths have no equivalent for; those two send `level: null`. An
engine that treats all four modes as "colour without a level" will dim or brighten a bulb that RM
would not touch. `Random color` picks a fresh hue per run at full saturation; the hue above is one
sample and is not a constant.

The table below lists the keys RM offers as *inputs*. On the `Custom RGB color` path RM also
derives and stores the full HSV conversion at commit time, so a saved rule carries the input and
three computed values. Authoring `#03000D` stores:

    colorH     "#03000D"   the input
    colorHex   "70"        hue, derived
    colorSat   "100"       saturation, derived
    colorLevel "5"         level, derived

RGB(3,0,13) converts to hue 70 on the 0-100 scale, saturation 100, and value 13/255 = 5.1 per
cent. Every stored number matches the arithmetic. The action then sends
`setColor([hue:70, saturation:100, level:5])`, so the derived level **is** part of the command.

A stale `colorLevel` cannot survive on this path: an action authored with a level of 77 under a
named colour and then switched to `Custom RGB color` came out holding 5, RM having overwritten it
at commit. A `colorLevel` found beside a `colorH` is a derived value, never a leftover.

**What RM renders is the input, not the command.** The rendered text for this mode shows the RGB
value and no level, while the action computes, stores and sends one. The same holds for
`Pick a Color`, which sends `level: 100` with no level field displayed. Any method that reads
RM's own rendering to decide what an action does will under-read every derived value on the
colour modes. **[strong]**, from one RGB value on one hub and one firmware; the exact arithmetic
match is the reason for treating it as a formula rather than a coincidence.

### 7.5 A named colour is stored as a name, and resolves on the 0-100 hue scale

A colour chosen from the wizard's list is stored in `color.<n>` as the name itself:

| Setting | Value |
| --- | --- |
| `actType.<n>` | `dimmerActs` |
| `actSubType.<n>` | `getSetColor` |
| `bulbs.<n>` | `{deviceId: label}` |
| `color.<n>` | the colour name, e.g. `"Red"` |

`color.<n>` always holds the mode; see 7.3 for what a non-name mode stores alongside it. RM
resolves the name at run time. Measured on a virtual RGBW light by running one action per name
and reading the device's own command events:

| Name | Command sent |
| --- | --- |
| Soft White | `setColor([hue:11, saturation:30, level:null])` |
| White | `setColor([hue:11, saturation:0, level:null])` |
| Daylight | `setColor([hue:11, saturation:10, level:null])` |
| Warm White | `setColor([hue:11, saturation:20, level:null])` |
| Red | `setColor([hue:100, saturation:100, level:null])` |
| Green | `setColor([hue:33, saturation:100, level:null])` |
| Blue | `setColor([hue:66, saturation:100, level:null])` |
| Yellow | `setColor([hue:16, saturation:100, level:null])` |
| Orange | `setColor([hue:11, saturation:100, level:null])` |
| Purple | `setColor([hue:83, saturation:100, level:null])` |
| Pink | `setColor([hue:97, saturation:25, level:null])` |

Hue is Hubitat's 0-100 scale, not degrees, so Red is 100 rather than 0. The four whites are all
hue 11 and differ only by saturation, so they are desaturated orange, not colour temperatures.
`level` is passed as `null` when the action carries no level, rather than omitted or defaulted
to 100. The device emits a `colorName` event after the command, and that name is the driver's
own, not RM's: `Pink` comes back as `Red`, and hue 83 comes back as `Magenta`.

Method note: all twelve commands landed in the same second, so they were mapped to actions by
ascending event id. That ordering was checked, not assumed: Red and Green reproduce the values
from a separate two-action run. **[strong]**

### 7.1 Metering a multi-device action

An action that targets several devices can space its commands rather than sending them
together. Two settings carry it:

| Setting | Value |
| --- | --- |
| `meter.<n>` | `"true"` or `"false"`, stored as a string |
| `meterMillis.<n>` | milliseconds between each device, stored as a string |

The UI labels these "Meter?" and "Meter milliseconds", and the rendered action text gains a
`meter <N> ms` suffix. With metering on, RM dispatches the command to device *k* at
`(k - 1) x meterMillis`. It changes only when each command is sent, not how long a device
takes to return, so it caps how many devices are in flight at once only when the spacing
exceeds a single device's response time.

An unmetered action still carries `meter.<n> = "false"` with an empty `meterMillis.<n>`, so
the presence of either key says nothing on its own. Read the value. **[strong]**

---

### 7.6 Run Custom Action: parameters, and a value Rule Machine drops without saying so

`actType.<n> = modeActs` with `actSubType.<n> = getDefinedAction`. The command is in
`cCmd.<n>`, the target in `devices.<n>`, and each parameter is a pair:

| Setting | Holds |
| --- | --- |
| `cpType<i>.<n>` | `string`, `number` or `decimal` - those three only |
| `cpVal<i>.<n>` | the value, **always stored as a string** |

**Read the pairs in ascending `i` and map them in order onto the command's parameters.** Do not
treat `i` as the position. Measured here, three parameters occupied slots 2, 3 and 4, and a second
action in the same rule used 2 and 3 again, so `i` is per-action rather than a running counter;
but real rules on this hub carry a `playTrack` URL in `cpVal6` and in `cpVal1`, so no fixed offset
holds. Order is the only reliable property.

`cpVal` is a string even when the type is numeric, and RM coerces at dispatch using `cpType`:
`number` arrives as **Integer**, `decimal` as **BigDecimal**, `string` as String. An engine that
passes the stored value through unconverted sends a String where RM sends an Integer.

Only three parameter types exist. A driver command declaring a BOOL or ENUM parameter cannot have
that parameter supplied from a Run Custom Action at all; it arrives null.

**A `number` above 2147483647 is silently dropped, and every later parameter shifts left.**
Measured on a driver that reports what it receives:

| Authored | RM renders | Device receives |
| --- | --- | --- |
| `probe('maxint', 2147483647, 9)` | all three | `txt=maxint` `num=2147483647` (Integer) `dec=9` |
| `probe('overmax', 2147483648, 9)` | all three | `txt=overmax` `num=9` (BigDecimal) `dec=null` |
| `probe('big', 9999999999, 2)` | all three | `txt=big` `num=2` (BigDecimal) `dec=null` |

The boundary is exactly `Integer.MAX_VALUE`. One over and the parameter is not passed as null, it
is removed from the argument list, so the next value lands in the wrong position. The rule's own
rendered text still shows the value that was dropped.

This is the sharpest case of the rule stated in 7.3 and section 9: **RM's rendering describes what
the author typed, not what the device receives.** Here the rendering is correct and the behaviour
is wrong, so no comparison of rendered text between two engines can detect it. Only running both
and reading what each device actually received will. **[strong]**

### 7.7 The wizard discloses progressively, so a key's absence proves nothing

Rule Machine's action page builds itself as you fill it in. A field does not exist in the page
schema until whatever it depends on has a value, which means **reading a schema snapshot and
concluding "this action has no such setting" is unsound**. Four distinct kinds of conditional
visibility were measured on 2026-09-25, and they fail in different ways.

**1. A field revealed by an earlier field in the same action.**

| Appears | Once this is set |
| --- | --- |
| `actSubType.<n>` | `actType.<n>` |
| `devices.<n>` | `myCapab.<n>` (Run Custom Action) |
| `cCmd.<n>`, `meter.<n>` | `devices.<n>` |
| `meterMillis.<n>` | `meter.<n>` is true |
| `cpType<i>.<n>` / `cpVal<i>.<n>`, `moreParams` | `cCmd.<n>` |
| `color.<n>`, `colorLevel.<n>`, `uVar.<n>` | `bulbs.<n>` |
| `colorH.<n>` or `colorHex`+`colorSat`+`colorLevel` | the mode chosen in `color.<n>` (see 7.3) |
| `onOff.<n>`, `optSwitch.<n>`, `trackSwitch.<n>`, `delayAct.<n>` | `onOffSwitch.<n>` |
| `delayHour`/`delayMinute`/`delaySecond` | `delayAct.<n>` = `hrs:min:sec` |
| `xVar.<n>` | `delayAct.<n>` = `variable` |
| `tstate<n>` | `tCapab<n>` **and** `tDev<n>` |
| `actionDone` | every required field for the chosen subtype |

The MCP rule server documents the same thing from its own side: "doActPage's schema is incremental
-- `actionDone` only appears after all required type-specific fields are set". **[strong]**

**2. A field revealed by content elsewhere in the rule.** The time and date format pickers appear on
the rule's *main* page only when some action uses `%time%`, `%now%` or `%date%`. The stored key for
the first is `timeFormat` (rule 2100 holds `"HH:mm"`). The date picker's key has not been observed,
because no rule read so far has had one saved. This kind is the nastiest to reason about, because
the revealing content is in a different action from the revealed setting. **[strong]** for the
reveal, **[unknown]** for the date key name.

**3. A subtype or capability revealed by what is installed on the hub.** The MCP server's reference
records that the garage-door and valve subtypes of `lockActs` are "only visible with the
corresponding device", and that `getSetHSM` "appears only when HSM is installed on the hub". All
five `lockActs` subtypes are visible on this hub because it has all five device kinds, so this
could not be disproved here, only cited. **[external]**

The consequence is worth stating plainly: **Rule Machine's authoring vocabulary on a given hub is a
function of what that hub owns.** No enumeration taken from one hub can be complete, and a tool
that builds its expectations by enumerating one hub inherits that hub's device list as a silent
assumption.

**4. A field that stops applying keeps its last value, and the rendering ignores it.** The three
kinds above are about a key that does not exist yet. This one is the opposite and more dangerous: a
key that exists, holds a plausible value, and is dead.

A time bound stores its *type* in `starting<n>` / `ending<n>` and its value in one of several
companion keys, only one of which applies:

| Bound type | The key that applies |
| --- | --- |
| `A specific time` | `startingA<n>` / `endingA<n>` |
| `Sunrise` | `startSunriseOffset<n>` / `endSunriseOffset<n>` |
| `Sunset` | `startSunsetOffset<n>` / `endSunsetOffset<n>` |

Change the bound type and the previously-applicable key **retains its old value**. Measured on two
rules:

    1775  starting5 = 'A specific time'   startingA5 = '06:00'        applies
          ending5   = 'Sunrise'           endingA5   = '22:00'        STALE
                                          endSunriseOffset5 = '10'    applies
          renders: "Time between 06:00 and Sunrise+10 minutes"

    2290  starting9 = 'Sunset'            startSunsetOffset9  = ''    applies, empty
          ending9   = 'Sunrise'           endSunriseOffset9   = ''    applies, empty
                                          startSunriseOffset9 = '15'  STALE
                                          endSunsetOffset9    = '-15' STALE
          renders: "Time between Sunset(18:15) and Sunrise(06:04)", no offset

2290 is the clearer case: **both applicable offset keys are empty and both inapplicable ones hold
values**, which is why the rendering shows no offset at all. A reader that takes `endingA5` without
first reading `ending5` gets 22:00 where Rule Machine means sunrise, and one that takes
`endSunsetOffset9` gets a 15-minute shift Rule Machine does not apply.

A stale offset also outlives a switch to a **clock** bound, so this is not confined to sun bounds
swapping bodies:

    2279  starting3 = 'A specific time'   startingA3 = '21:30'        applies
          ending3   = 'A specific time'   endingA3   = '06:00'        applies
                                          endSunriseOffset3 = '6'     STALE
          renders: "Time between 21:30 and 06:00", no offset

So the rule for this format is: **`starting<n>` / `ending<n>` select which companion key is live, and
every other companion is noise.** Never read a value key without reading its type key first.

The structure and the first two instances came from the other engine's session; 2279 was found by
mechanising the check and is confirmed here from its stored settings and its own rendering.
**[strong]**

Not every pair of populated companions is a stale one. On the `Custom RGB color` path both
`colorH` and `colorHex` carry values and **both are live**: the first is the input, the second the
hue RM derived from it (7.3). The test is whether the second value is derivable from the first, not
whether two keys are populated at once.

#### 7.7.1 The action subtypes the picker offers

Read from the `actSubType` enum for each of the twelve `actType` families on a C-8 running 2.5.1.183
with a full device complement. **73 subtypes.**

| Family | Subtypes |
| --- | --- |
| `condActs` | getIfThen, getCondAct |
| `switchActs` | getOnOffSwitch, getToggleSwitch, getFlashSwitch, getModeSwitch, getChooseSwitch, getPushButton, getPushButtonPerMode, getChooseButton |
| `dimmerActs` | getSetDimmer, getToggleDimmer, getAdjustDimmer, getDimmersPerMode, getFadeDimmer, getStopFade, getRLDimmer, getStopDimmer, getSetColor, getToggleColor, getColorPerMode, getSetColorTemp, getToggleColorTemp, getColorTempPerMode, getFadeCT, getStopCTFade |
| `sceneActs` | getRLShade, getShadePosition, getStopShade, getFanSpeed, getAdjustFan |
| `lockActs` | getSetHSM, getOCGarage, getLULock, getOCValve, getSetThermostat |
| `messageActs` | getMsg, getLogMsg, getHTTPGet, getHTTPPost, getPingIP |
| `soundActs` | getSetMusicPlayer, getSetVolume, getMuteUnmute, getTone, getChime, getSiren |
| `modeActs` | getSetVariable, getSetMode, getDefinedAction, getWriteLocalFile, getAppendLocalFile, getDeleteLocalFile |
| `rulesActs` | getSetPrivateBoolean, getRuleActions, getStopActions, getPauseResumeRules |
| `deviceActs` | getCapture, getRestore, getRefreshSwitch, getPollSwitch, getDisable, getStartStopZPoll |
| `repeatActs` | getRepeat, getWhile, getStopRepeat |
| `delayActs` | getDelay, getDelayPerMode, getCancelDelay, getWaitEvents, getWaitRule, getExitRule, getComment |

**This list is not the set of `actSubType` values a rule can store.** `getElse`, `getElseIf` and
`getEndIf` are all written into real rules and appear in none of these pickers, because RM creates
them from dedicated buttons (`butElse`, `butEndIf`) rather than from the subtype menu. So even a
complete sweep of the authoring surface under-reports the storage format, which is the same shape
of error as reading a rendering and assuming it describes the command. **[strong]**

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
is Rule Machine's way of storing "set the Private Boolean of this rule *and* of rule 1809".
Treating the presence of `"*"` as meaning self-only will silently drop genuine cross-rule
references. **[strong]**

The rule's own numeric id is **not** the same stored form. On 2026-09-15 (firmware 2.5.1.183)
a throwaway rule was given a Rule Boolean action written with its own id, `privateT.1 =
["3269"]`. It worked when run, setting the rule's own Private Boolean, but the rule page
rendered the action as `Rule Boolean False: ''`, with no target name. **[single]** Rule
Machine's picker evidently offers the rule itself only as `"*"`. A reader should treat an own-id
target as a self-reference, and a writer should not assume it round-trips through the editor.

Parse each element explicitly rather than stripping non-digits out of the whole value:

    for each element:
        if element == "*"            -> this rule
        else if element is all digits -> installed app id
        else                          -> unknown, record and skip

Stripping non-digits is tempting and shorter, but it silently turns any element you have not
anticipated into a plausible-looking id. A future sentinel of the form `RM1809` would become
`1809`, which is a real installed app, and the resulting wrong edge would look entirely
credible. Rejecting what you do not recognise is the safer default throughout this format.

`actType.<n> = rulesActs` also covers actions with no target at all, so check `actSubType`
before assuming a target setting exists.

---

## 9. Traps

Each of these cost real debugging time.

### 9.1 The `rule` field is not a rule reference

Some action objects carry a field named `rule`:

    { "method": "getIfThen",   "rule": 2 }
    { "method": "getWaitRule", "rule": 1, "delay": "0:10:00" }

It is a **condition index**, used to look up `eval[<rule>]`. Above, `rule: 2` resolves
through `eval[2] = 12` to condition 12, and `rule: 1` through `eval[1] = 2` to condition 2.
Neither has anything to do with a rule numbered 1 or 2.

**Test the value, not the key.** Whether the key is present depends on which storage shape
the action happens to use (section 3.1), not on what the action does. Across 38 rules the
key appeared on twelve different methods, including `getOnOffSwitch`, `getMsg` and
`getDelay`. But it is **non-null for exactly three**: **[invariant]**

| Method | `rule` non-null | `rule` present but null |
| --- | --- | --- |
| `getIfThen` | 13 | 0 |
| `getWaitRule` | 12 | 0 |
| `getElseIf` | 3 | 0 |
| `getEndIf` | 0 | 10 |
| `getSetPrivateBoolean` | 0 | 11 |
| `getOnOffSwitch` | 0 | 6 |
| `getMsg`, `getDelay`, `getChime`, `getElse`, `getStopActions`, `getHTTPPost` | 0 | 16 |

So `if (action.rule != null)` is correct and `if ('rule' in action)` is not. Note this makes
the method list above descriptive rather than prescriptive: gate on the non-null value and
you do not need to know which methods can carry one.

Reading it as a target rule id produces confident, entirely fictional rule-to-rule links,
one for every conditional and wait on the hub. On a 38-rule hub that was 28 fabricated
relationships. Real rule targets live in the settings described in section 8.

### 9.2 `indent` does not describe nesting

Actions carry an `indent` string of tab characters. It disagrees with actual nesting: on one
observed rule the IF is at `""` while its own `getEndIf` is at `"\t"`, and another rule opens
three IFs and closes two.

Build structure from the control-flow markers instead: `getIfThen`, `getElseIf`, `getElse`,
`getEndIf`, maintaining your own stack. Use `indent` for nothing. **[strong]**

**Those four are not the whole grammar.** They are the IF family, and they are the only
block construct the rules examined actually used. Rule Machine also has repeat and while
constructs, which every rule on the test hub carries state for even without using them:
`hasWhileRule` in 22 rules, `inRepIf` in 22, `nestedRepIf` in 38, `blockIf` in 20. Their
action-level markers were never observed and are **[unknown]**.

Treat a marker you do not recognise as an unclosed block and say so, rather than assuming
the four above are exhaustive and silently producing a flat action list from a rule that has
real nesting.

### 9.3 `pvTF` reads inverted

`pvTF.<n>` accompanies `getSetPrivateBoolean` and looks like the value being written. It is
backwards against the rule page in every observed case:

| Rule | Action | Position | `pvTF` | Rule page shows |
| --- | --- | --- | --- | --- |
| 1806 | 31 | second | `true` | Rule Boolean **False** |
| 1806 | 33 | last | `false` | Rule Boolean **True** |
| 2972 | 7 | sixth | `true` | Rule Boolean **False** |
| 1999 | 8 | third | `true` | Rule Boolean **False** |
| 1999 | 7 | last | *(empty)* | Rule Boolean **True** |

Ordering is not the explanation: every other action of rule 1806 matches its page exactly in
order. Whatever `pvTF` means, it is not straightforwardly "the value set".

**Settled 2026-08-14.** The last two rows are rule 1999 "Barking", read off its page
directly, and they are the case the first three did not cover: a pair storing `true` and an
empty string. The page shows **False** then **True**, in that order. So: **[strong]**

> The value the rule page shows is the negation of `pvTF`, with an empty value counting as
> false. `true` renders as False; `false` and `''` both render as True.

That is now verified against four rule pages spanning both stored forms of the pair, which
is enough to render it. Earlier revisions of this document recommended showing nothing, on
three samples that all happened to be the `true` half. The remaining doubt was never about
the inversion, it was about whether the empty string was a third state; it is not.

Worth keeping in mind when reading a rule page to check any of this: the `(true)` printed
immediately after the words "Private Boolean" is the CURRENT value of the rule's own boolean,
not the value the action writes. The written value is the bare word at the end of the line.
Two different things sit on one line, which is most of why this field looked incoherent for
so long.

**There is a third value.** Every `getSetPrivateBoolean` action on the hub was enumerated on
2026-08-14, 23 in total across 12 rules. `pvTF` is not a two-valued field: **[strong]**

| `pvTF.<n>` | Count |
| --- | --- |
| `true` | 12 |
| *(empty string)* | 9 |
| `false` | 2 |

Empty is not a missing setting. The key is present with an empty value, which is how a
Hubitat `bool` input persists when it has never been switched on. So `''` and `false` are
most likely the same state, and the field is closer to two-valued-with-a-default than to a
tri-state.

The population also shows the shape these actions come in. Eleven of the twelve rules hold
**exactly two**, and in all eleven pairs the values are `true` at the earlier position and
`''` or `false` at the later one, by `actionList` order. Nine of the eleven sit at position
0, the very first action. That is the standard re-entry guard: block the rule at the start,
re-arm it at the end. It is consistent with the inversion above, and it means an apparently
odd `pvTF` is usually explained by which half of the pair you are looking at. The twelfth
rule is the hand-built test rule, which holds a single unpaired action.

Not enough to start rendering the value. It does narrow what a confirming test needs to be:
a single rule page read for a pair whose stored values are `true` and `''`, since the `true`
/ `false` pair is already covered by rule 1806 in the table above.

**`privateF.<n>` was not observed at all.** All 23 store their target under `privateT.<n>`,
including the two actions whose `pvTF` is `false`. **[strong]** The alias remains worth
checking, since checking it costs nothing and missing it drops a link silently, but nothing
on this hub demonstrates that Rule Machine ever writes it.

`pvTF` is not alone. The MCP server's action schema names three more stored booleans that
read backwards against their own field name: `lockRL.<n>` true means UNLOCK, `shadeRL.<n>`
true means CLOSE, `disEn.<n>` true means ENABLE. None of the three appears anywhere in this
hub's 66 rules, so they are recorded here unverified. **[external]**

### 9.4 Labels carry hub-injected HTML

An app's label is not clean text. Hubitat appends status markup:

    Theatre Room Light and Fireplace <span style='color:red'>(Required Expression false)</span>

Strip tags. Note the parenthetical text survives stripping, which is usually what you want,
since it is real information.

**The decoration is a family.** Beyond `(Paused)`, Rule Machine appends `(Stopped)` and
`(Required Expression false)` the same way, as a styled span. The span form is what separates
a runtime decoration from a rule a user genuinely named "Porch (Paused)": the decoration
arrives as `>(Paused)</span>`, a literal name does not. Comparing the `/hub2/appsList` name
with the RMUtils label is the robust test, because a literal name carries the suffix in both
strings. **[strong]**

### 9.5 Groovy: a GString key never matches a String key

Not a Rule Machine issue, but it will bite anyone parsing this inside a Hubitat app, and the
boundary is narrower and stranger than folklore suggests. Measured on Groovy directly rather
than assumed. **[invariant]**

**Safe.** Groovy coerces, or uses `==` which compares by value:

    map["${x}"] = v        // putAt coerces: the stored key is a String
    map["${x}"]            // getAt coerces too
    "${x}" == 'literal'    // true
    switch ("${x}") { case 'literal': }   // matches

**Broken.** These go through `equals()`, and `String.equals(GString)` is false in both
directions even though the two print identically:

    map.get("${x}")            // null, even when map['x'] exists
    map.containsKey("${x}")    // false
    list.contains("${x}")      // false
    "${x}" in list             // false

So a map built with subscript syntax is fine, which is why decoding code written this way
works. What fails is membership testing. The failure is silent: no exception, just a `false`
or a `null` that sends you looking in the wrong place entirely.

Assign through a `String`-typed local before any comparison or membership test:

    String n = "${s.name}"
    if (!seen.contains(n)) seen << n

**Corrected 2026-08-13.** An earlier version of this section claimed map keys were the
problem. They are not; `putAt` coerces them. The real hazard is `contains`, `in`, `get` and
`containsKey`. The claim was written from folklore rather than from a test, which is exactly
the failure this document warns about elsewhere.

---

### RM's rendered page is lossy where its stored string is not

A condition comparing with `<` is stored complete:

    Illuminance of _ Average External Illuminance(<span style='color:black'>9755</span>) is < 200

but Rule Machine's own config page displays it as "... is", with the operator and the threshold
gone. RM writes the `<` unescaped, so a browser parses `< 200` as a tag opener and swallows it.
Measured over 62 rules by the other engine's session: `<` never renders (5 conditions), `<=` never
renders (4), `>=` always renders, `>` renders 5 of 6 with one case unexplained. A bare `>` is not a
tag opener, which is the mechanism confirming itself.

Two consequences. Read rule **state**, not the rendered page: `capabstrue` / `capabsfalse` hold the
intact string. And a tag-stripper of the form `<[^>]*>` is safe here precisely because it requires a
closing `>`, so it leaves a trailing `< 200` alone; a greedy or open-ended one would not.

More generally, this is the same lesson as 7.3 from the other direction. What RM renders is neither
the whole command nor, here, the whole condition. The stored data is the better source in both
cases. **[strong]**

### The white colour names are not colour temperatures

`White`, `Daylight`, `Warm White` and `Soft White` read like colour temperatures, and any
reasonable engine would route them to `setColorTemperature`. RM does not. All four resolve to
**hue 11** and differ only by saturation (0, 10, 20, 30), so RM issues `setColor` with a
desaturated orange. Measured; see 7.5.

This is the shape of trap worth watching for generally: a stored value whose *name* implies one
command family while RM uses another. Nothing in the settings marks the difference, so it
produces a wrong output silently, on a device that still responds normally. **[strong]**

## 10. What the data cannot tell you

Being clear about the limits matters as much as the format.

### 10.1 No Rule Function discriminator found

A Rule Function reports `installedApp.name` of `Rule-5.1`, exactly like any other rule, and
a `Run Actions` call targeting one stores `runRuleType = "Rule Machine"`, exactly like a
call targeting an ordinary rule. No examined field distinguishes them. **[limited]**

Only one Rule Function was available to test against, so this is an absence of evidence
rather than evidence of absence: a discriminator may well exist in a field not examined.

In practice this does not matter for reading the link, since the target id resolves either
way. It matters if you want to label the two differently.

**Update, 2026-09-18.** `isFunction` is present as a rule-level setting on 50 of 66 rules
here, with an empty value on every one of them, so this hub still contains no positive
example. The MCP server's guide states its meaning on the write side: setting
`isFunction: true` "marks the rule as a function that returns a value, so other rules can call
it as a function". **[external]** That makes it the named candidate rather than an open
question; what remains unproven here is how a rule that IS a function reads back.

### 10.2 Pause/Resume discriminator: `pR.<n>`

**Settled 2026-08-14.** Both use `getPauseResumeRules`, discriminated by `pR.<n>`:
**[strong]**

| `pR.<n>` | Rule page shows |
| --- | --- |
| `true` | **Resume** Rules |
| *(empty)* | **Pause** Rules |

Measured on one rule holding both, so engine version, firmware and rule are all held
constant across the pair. Rule 2972 action 8 stores `pR=true` against a page reading
"Resume Rules: Back Door Night", and action 6 stores an empty string against "Pause Rules:
Kettle button".

Note that this reads the right way round, unlike `pvTF` in 9.3, which is inverted. Two
booleans on the same family of actions, stored with opposite polarity. Do not assume one
from the other.

Empty behaves as the default here too, as it does for `pvTF`: it is a present key with an
empty value rather than a missing key, which is how a Hubitat `bool` input persists when it
has never been switched on. So an action left untouched is a Pause.

**A note on a disagreement.** The MCP server's own action schema describes `pR.<n>` as
inverted, in the same words it uses for `pvTF`. Its mapping is identical to the one above
(`true` is Resume), so the disagreement is in the characterisation, not the data. The mapping
is what matters: do not "fix" a correct readback to satisfy either description. **[strong]**

### 10.3 `eventSubscriptions` is a snapshot, not a definition

The worked example below demonstrates this live. Rule Machine removes a rule's trigger
subscriptions while its Required Expression is false, so a rule whose trigger is a motion
sensor can show no subscription to that sensor at all.

Rule Machine's author states the same: "If Predicate is false, subscriptions to trigger events
are removed, so the rule is not triggered at all" ([bravenel, 13 Oct 2021](https://community.hubitat.com/t/rule-5-1-predicate-and-repeat-while-until-rule/81158/2)).

**Confirmed again 2026-09-15** on firmware 2.5.1.183 with a throwaway rule: one Switch trigger,
a Log action, and the Required Expression `Private Boolean is true`. With the Private Boolean
false and the rule updated, `eventSubscriptions` was empty; with it true and the rule updated,
the trigger subscription was back. **[strong]** When the Required Expression instead tested the
same switch, the count stayed at one, because Rule Machine keeps the subscription it needs to
notice the expression becoming true. So a nonzero count does not prove the triggers are
subscribed, and a zero count does not prove a trigger is misconfigured.

For Rule Machine specifically this does not matter, because triggers are recorded in
`tDev<n>` settings and can be read directly. It matters greatly for **other** apps, where
subscriptions may be the only evidence available, and it means two scans minutes apart can
legitimately disagree.

---

**Three causes, not one.** A zero subscription count does not imply a false Required
Expression. `appState` carries independent `paused` and `stopped` booleans (both present on
this hub: `stopped` on all 66 rules, `paused` on 31), and Rule Machine removes trigger
subscriptions for a paused or stopped rule as well. The MCP server's settle check treats all
three the same way for exactly this reason. **[strong]** / **[external]** A fourth, unrelated
cause is the app being disabled, which is a separate flag again.

## 11. Finding rules in the first place

**Superseded 2026-08-14: `/hub2/appsList` is the bulk endpoint.** It returns the complete
installed-app tree as JSON. **[strong]**

Credit where it is due: this was found by reading Jean P. May Jr.'s (TheBearMay) *Rule
References Rule Table*, which calls it directly, not by further probing here. It was then
verified against this hub on firmware 2.5.1.147 rather than taken on faith.

    GET /hub2/appsList

Top-level keys are `systemAppTypes`, `userAppTypes` and `apps`. Each entry in `apps` has a
`data` object and a `children` list, and parents nest arbitrarily, so it needs walking
recursively rather than reading one level. Per app, `data` carries `id`, `appTypeId`,
`name`, `type`, `disabled`, `user`, `hidden`.

Two things that matter beyond enumeration:

- `appTypeId` arrives **without** a second request per app, which is otherwise only
  obtainable from `/installedapp/statusJson/<id>`.
- `disabled` is reported here directly.

Measured on this hub: 89 apps enumerated against 74 found by walking devices. The 15 not
found by devices were parent containers (Rule Machine, Button Controllers, Groups and
Scenes, Notifications), device-less utilities (Rebooter, Averaging Master), and, notably,
**a Rule Function**, which is the case device-led discovery can never reach by design.

The paragraph this replaces said no bulk endpoint had been found. That was accurate about
`/app/list` and `/installedapp/list`, which really are JavaScript shells of about 6KB under
plain HTTP, and it is still worth knowing they are dead ends. It was wrong as a general
conclusion. Worth remembering as a caution about **[unknown]**: absence of evidence had been
recorded honestly, and the answer still turned up in someone else's source rather than in
more probing.

Two older routes, both **[heuristic]** rather than properties of the format, and both still
useful because the bulk endpoint does not report which devices an app touches:

- **From a known id.** `/installedapp/statusJson/<id>` gives you `appTypeId` and everything
  else. Getting that first id usually means reading it out of the URL bar while the app's
  page is open.
- **Through devices.** `/device/fullJson/<deviceId>` returns the apps referencing that
  device. Walking every device discovers nearly all apps.

### 11.1 Use `appsUsing`, never `appsUsingForDialog`

That response carries three related fields, and only one of them is complete: **[invariant]**

| Field | Contents |
| --- | --- |
| `appsUsingForDialog` | **capped at five entries**, on every device |
| `appsUsingForDialogMore` | a **count** of the remainder, not the ids |
| `appsUsing` | the complete list |

Measured on one device with 29 apps: `appsUsingForDialog` held 5, `appsUsingForDialogMore`
held the integer 24, and `appsUsing` held all 29. The name is the clue. The dialog field
exists to render a UI element, not to enumerate anything.

This is worth stating flatly because reading the wrong field does not look like a bug. It
returns a plausible list and silently omits everything past the fifth entry on every shared
device. On a 193-device hub it hid **12 of 74 apps**, including two whole app types that
never appeared at all, and the loss was noticed only because an unrelated rule named a
missing rule as a target.

### 11.2 A deleted app still answers 200

`/installedapp/statusJson/<id>` for an app that no longer exists returns HTTP 200 with an
empty `installedApp` shell rather than a 404. **[strong]**

So a rule naming a rule that has since been deleted cannot be detected by status code. Check
whether `installedApp.label` and `installedApp.name` are both absent. This is worth
detecting rather than ignoring: the action remains in the calling rule and silently does
nothing, which is the kind of thing a dependency map exists to surface.

Measured again on 2026-08-14, firmware 2.5.1.147, against two ids named as `privateT`
targets by a live rule: the response body is literally `{}`, two bytes, with no
`installedApp` key at all. **[strong]** The "empty shell" wording above describes a body
that still carries the key. Test for the absence of `label` and `name` rather than for the
shape of the response, since that holds for both forms.

```
GET /installedapp/statusJson/2328  ->  200  {}
GET /installedapp/statusJson/1838  ->  200  {}
GET /installedapp/statusJson/2973  ->  200  {"installedApp":{...,"trueLabel":"_Testy Function",...}}
```

The third id is the useful control. It answers with a full body despite never being reached
by device-driven discovery, which is what separates a deleted target from an unscanned one.
Both are missing from the scan; only one is missing from the hub. A map that renders them
identically is asserting something false about the second, which is what prompted the 1.7.1
styling split.

### 11.3 The device-less blind spot, and what closes it

An app referencing no devices is invisible to device-driven discovery entirely, which is the
normal case for a Rule Function. `/hub2/appsList` closes it, and the confirming case is
concrete: **[strong]**

A Rule Function does get its own installed-app id, `type` of `Rule-5.1`, indistinguishable
in the listing from any other rule. On this hub, `_Testy Function` is installed app 2973. It
appears in `/hub2/appsList` and does not appear in a device-led scan, which is exactly the
shape the blind spot predicts.

That also means device-led discovery is not redundant. The bulk endpoint says an app exists;
it does not say which devices the app touches. Both are needed, and the union is the
complete set.

One measurement worth recording, because it argues against overstating the gain: on this hub
every rule that *acts on another rule* was already reached through devices, so the union
found no rule links that the device-led scan had missed. The endpoint buys a guarantee and
the device-less apps, not a pile of new edges. Given `appsUsingForDialog` once hid 12 of 74
apps (11.1), the guarantee is the point.

### 11.4 `valFunction` remains unverified

TheBearMay's parser also matches `valFunction.<n>`, understood to be a rule calling a Rule
Function that returns a value. **Every rule-typed app on this hub was enumerated via
`/hub2/appsList` and searched: zero instances.** **[unknown]**

So there is no local fixture, and nothing here either confirms or refutes it. Recorded so
the next person does not re-run the same search, and so it is not implemented on the
strength of another project's source alone. `ruleActMain` and `privateF` are in the same
position: both are handled defensively as aliases, and neither occurs on this hub.

---

## 12. Worked example

Rule **Theatre Room Light and Fireplace**, installed app 2325. Its page shows a Required
Expression, a motion trigger, a lamp switched on, an IF that also lights the fireplace when
it is cold, then a ten-minute wait for motion to stop before turning everything off.

### Raw

    actionList:   7, 6, 4, 1, 5, 2, 8, 3
    hasPredicate: true

    actions:
      7: { method: getSetPrivateBoolean, indent: "\t" }
      6: { method: getOnOffSwitch,       indent: "" }
      4: { method: getIfThen,            indent: "",   rule: 2 }
      1: { method: getOnOffSwitch,       indent: "" }
      5: { method: getEndIf,             indent: "\t", rule: null, label: "END-IF" }
      2: { method: getWaitRule,          indent: "",   rule: 1, delay: "0:10:00", wait: 1 }
      8: { method: getSetPrivateBoolean, indent: "" }
      3: { method: getOnOffSwitch,       indent: "" }

    eval:
      0: [5, "AND", 7, "OR", "10"]
      1: 2
      2: 12

    capabstrue:   1  -> "Theatre Room Motion Sensor motion reports active"
    capabsfalse:  2  -> "Theatre Room Motion Sensor motion is inactive"
                  5  -> "Mode in [Home, Visitor]"
                  7  -> "Time between Sunset-15 minutes(18:08) and 21:30"
                  10 -> "Time between 06:00 and Sunrise+15 minutes(07:19)"
                  12 -> "Temperature of _ Average External Temperature(20.2) is <= 15.0"
                  15 -> "Private Boolean(true) is true"

    tDev1   -> Theatre Room Motion Sensor
    rDev_2  -> Theatre Room Motion Sensor
    rDev_12 -> _ Average External Temperature

    onOffSwitch.6 -> Theatre Room Lamp,             onOff.6 = true
    onOffSwitch.1 -> Fireplace,                onOff.1 = true
    onOffSwitch.3 -> Theatre Room Lamp, Fireplace,  onOff.3 = false
    delayAct.2 = hrs:min:sec, delayMin.2 = 10
    pvTF.7 = true

### Decoded

**Trigger.** `tDev1` names the trigger device, and condition 1 renders it: Theatre Room Motion
Sensor becomes active.

**Required Expression.** `hasPredicate` is true, so `eval[0]` applies:
`[5, "AND", 7, "OR", "10"]`, read left to right as condition 5 AND condition 7 OR condition
10. That is: the mode is Home or Visitor, and it is either evening or early morning.

Note this single expression contains integer condition numbers `5` and `7` next to the
string `"10"`. Any parser that assumes a consistent element type fails here.

**Actions**, walked in `actionList` order:

| # | Action | Resolution |
| --- | --- | --- |
| 7 | `getSetPrivateBoolean` | `pvTF.7 = true`, value not shown, see 9.3 |
| 6 | `getOnOffSwitch` | `Theatre Room Lamp`, `onOff.6 = true`, so on |
| 4 | `getIfThen` | `rule: 2` to `eval[2] = 12` to condition 12, external temperature <= 15 |
| 1 | `getOnOffSwitch` | `Fireplace`, `onOff.1 = true`, so on |
| 5 | `getEndIf` | |
| 2 | `getWaitRule` | `rule: 1` to `eval[1] = 2` to condition 2, motion inactive, timeout 0:10:00 |
| 8 | `getSetPrivateBoolean` | |
| 3 | `getOnOffSwitch` | `Theatre Room Lamp, Fireplace`, `onOff.3 = false`, so off |

Reading out: on motion, set the Private Boolean, turn the lamp on, and if it is 15 degrees
or colder outside also light the fireplace; then wait up to ten minutes for motion to stop,
reset the Private Boolean, and turn both off.

Which is what the rule's own page says.

### Three traps visible in this one rule

**`actionList` order.** The list starts at action **7**, and action 3 runs last. Iterating
the `actions` map by key would produce a rule nobody wrote.

**`indent` is wrong here in two directions.** Action 7 is the *first* action, at top level,
yet carries `"\t"`. Action 5 is the `getEndIf` closing the IF opened by action 4, and carries
`"\t"` while action 4 itself carries `""`. Reconstructing nesting from these values gives a
structure that matches neither the rule nor itself.

**The `rule` field is a condition index.** Actions 4 and 2 both carry one: `rule: 2` and
`rule: 1`. Neither is a reference to rule 2 or rule 1. They index `eval`, resolving to
conditions 12 and 2 respectively.

### The subscription trap, live

This rule's label at the time of reading was
`Theatre Room Light and Fireplace (Required Expression false)`, and its complete
`eventSubscriptions` were:

    LOCATION / [Hub Name]
    LOCATION / [Hub Name]

**No device subscriptions at all.** Theatre Room Motion Sensor, the rule's entire trigger, has
none. Because the Required Expression is false, Rule Machine has removed the trigger
subscription and kept only what it needs to notice the expression becoming true again, which
for a mode-and-time expression is location events alone.

Anything inferring this rule's triggers from `eventSubscriptions` would conclude it has no
device triggers whatsoever. Reading `tDev1` gives the right answer regardless of when you
look.

---

## 13. Hub Variables

Hub Variables are hub-scoped shared state - visible to every rule, not owned by any one of
them - distinct from a rule's own Private Boolean or local variables (`allLocalVars`, always
empty for a rule that only touches Hub Variables). A rule can write one, read one via a
condition, read one via a trigger, or reference one inside free text, and all four use
different storage conventions.

Everything below rests on a handful of deliberately-constructed test fixtures on one hub
(2026-08-15), not a corpus survey like sections 1-11. Evidence markers here are
correspondingly weaker - read `[single]` and `[limited]` as literal, not as this document's
usual conservative hedge.

### 13.1 Writing a variable

A `getSetVariable` action's target is not in the action object. Same `.<n>`-suffixed
settings convention as every other action:

    actSubType.2 = getSetVariable
    xVarV.2      = TestHubUptime.

The value SOURCE is discriminated by `valStringOp.<n>`. Two source types observed:

| `valStringOp.<n>` | Source | Companion settings |
| --- | --- | --- |
| `Device attribute` | a device's attribute | `customDev.<n>` (device), `tCustomAttr.<n>` (attribute name) |
| `Set string` | literal typed text, itself possibly containing `%OtherVariable%` (13.6) | `valString.<n>` |

**[single/limited]** Rule Machine's "Select string operation" menu offers many more options
(Remove string, Replace string, Token, URL Encode/Decode, Set from HTTP GET/POST response,
Set from local file, LowerCase string, Format DateTime, Copy variable, Rule Function) - none
of these tested, storage shape **[unknown]**.

**The discriminator depends on the target type.** The `valStringOp.<n>` field above is the
String-target discriminator. A Number or Decimal target instead carries **`numOp.<n>`**, whose
observed values are `number`, `variable`, `device attribute` and `variable math`, with the
value in `valNumber.<n>` and the math operands in `xVar3.<n>` / `xVar4.<n>` / `valConst.<n>` /
`valMathOp.<n>`. Both key families are present on this hub (`numOp.1 = number`,
`valNumber.1 = 405` on rule 3078; `valStringOp.3 = Set string` on rule 2992). A decoder that
looks only for `valStringOp` reads a numeric Set Variable action as having no source.
**[strong]**

How each `numOp` value names its source, from fixtures on this hub: **[single]** each

| `numOp.<n>` | Source fields | Fixture |
| --- | --- | --- |
| `number` | `valNumber.<n>` | rule 3078 |
| `variable` | `xVar3.<n>` holds the variable copied; `valOffset.<n>` (number, UI default `0`) is added to it. A row without `valOffset` throws `Ambiguous method overloading for method java.lang.Long#plus` when it runs and leaves the target unchanged | rule 3397 (UI-built, fw 2.5.1.183) |
| `variable math` | `xVar3.<n>` and `xVar4.<n>` are the operands, `valMathOp.<n>` the operator; the literal `(constant)` in either slot means the number in `valConst.<n>` / `valConst2.<n>` | rule 3079 |
| `add number` | `valNumber.<n>` is added to the target's current value, so the target is also read | rule 2100 |

`add number` is not in the value list the community MCP Rule Server documents, so treat the
enum as open.

A **String** target has no `numOp` at all. Its source picker is `valStringOp.<n>`, whose options
on 2.5.1.183 are `Set string`, `Remove string`, `Replace string`, `Token`, `Device attribute`,
`URL Encode`, `URL Decode`, `Set from HTTP GET response`, `Set from HTTP GET response data.text`,
`Set from HTTP POST response`, `Set from local file`, `LowerCase string`, `Format DateTime`,
`Copy variable` and `Rule Function`. A copy stores `valStringOp.<n> = Copy variable` with the
source in the same `xVar3.<n>` slot the numeric copy uses; built by hand in the Rule Machine UI
(rule 3375: `valStringOp.1 = Copy variable`, `xVar3.1 = AMGateA_Shared`, rendered
"Set GT1 to AMGateA_Shared"). **[single]** The Boolean and DateTime copy pickers are
**[unknown]**.

**`xVarV.<n>` does not tell you the namespace.** Rule-local variables and Hub Variables share
one Set Variable action and one picker, so a name in `xVarV.<n>` may be either. Treating every
`xVarV` as a Hub Variable write manufactures false hub-variable edges; cross-check the name
against the rule's own `allLocalVars` first. The same picker also offers two selectable header
rows, `" --LOCAL VARIABLES--"` and `" --HUB VARIABLES--"`, each with a leading space, which are
not variable names. **[external]**

### 13.2 Reading a variable in a condition

Same slot a device condition uses, typed `Variable` instead of a capability name:

    rCapab_3  = Variable         the condition-side counterpart to tCapab1 on triggers
    xVar_3    = TestHubUptime.
    RelrDev_3 = ≠                comparison operator
    state_3   = 0                compare value

The underscore convention documented in section 6 for device conditions (`rDev_<n>` vs.
`tDev<n>`) applies identically: condition-side variable settings carry the underscore
(`rCapab_`, `xVar_`), trigger-side do not (13.3). **[single]**

### 13.3 Reading a variable via a trigger

A rule can fire when a Hub Variable itself changes, not just reference one after the fact:

    tCapab1 = Variable
    xVar1   = TestHubUptime.     no underscore - trigger-side, not condition-side

The event subscription this produces is a genuinely different shape from every device
trigger elsewhere in this document:

    { "type": "LOCATION", "name": "variable:TestHubUptime.", "typeId": 1, "typeName": "<hub name>" }

against a device trigger's `{ "type": "DEVICE", "typeId": <deviceId>, "name": <attribute> }`.
**[single]**

### 13.4 Required Expression referencing a variable

Structurally identical to 13.2 - same `rCapab_`/`xVar_` pair - just filed under `eval['0']`
instead of a numbered group tied to an action, per section 5.2's existing note that
`eval['0']` is the Required Expression, valid only while `hasPredicate` is true. No new field
shape; worth recording only because it confirms the same convention holds in that slot too,
rather than assuming it. **[single]**

### 13.5 The trailing period is not a picker artifact

`TestHubUptime.` carries a trailing period everywhere a setting refers to it - `xVarV`,
`xVar_`, `xVar`, and a `p.TestHubUptime.` state-cache key. A second variable created fresh in
the same session, `TestConcat`, carries no such artifact anywhere it appears.

Best explanation available: the period belongs to that one variable's own internal record - a
`formerState` field alongside it suggests a rename at some point - not a general property of
how the variable picker stores a selection. **[single, and corrects an earlier assumption in
this project's own working notes that had it backwards]**

Practical consequence: strip a trailing period defensively wherever a variable name comes
from one of these enum settings, but do not assume every variable will have one, and do not
build logic that depends on the period meaning anything in particular.

### 13.6 Free text: `%Name%` interpolation

A "Set string" value can embed another variable's live value inline:

    valStringOp.1 = Set string
    valString.1   = %TestHubUptime%

No trailing period here, unlike 13.1-13.4, even though it names the same variable - the
period is a property of the enum-picker settings specifically (13.5), not of the name.

### 13.7 Trap: `%device%`/`%time%`/`%date%` are not Hub Variables

Rule Machine reserves `%device%`, `%time%`, `%date%` (at least - not enumerated further) as
built-in notification-message tokens, unrelated to user-created Hub Variables and matching
the identical `%Name%` syntax a real variable reference uses (13.6). Nothing in the stored
text distinguishes a reserved token from a genuine variable reference.

**Confirmed live, at real cost.** Scanning every text/textarea setting hub-wide for `%Name%`
produced `time`, `date` and `device` reported as Hub Variables read by several real
production rules, none of which had ever created a variable by any of those names. **[strong
that the collision happens - reproduced across multiple real rules, not a single instance]**

No authoritative list of Rule Machine's reserved tokens was found or searched for. The
mitigation applied by the one consuming app built against this format: treat a free-text
`%Name%` match as unconfirmed unless the same name is independently confirmed by a
structured reference (13.1-13.4) somewhere else on the hub. That correctly excludes the
reserved tokens - nothing ever creates a real Hub Variable literally called `device` - while
still allowing a genuine variable that happens to share a common word. This is a mitigation
applied by the consuming app, not a fact about the storage format itself, recorded here
because the trap belongs with the format notes even though the fix is necessarily app-side.
**[heuristic]**

**The reserved list is longer.** The MCP server's test matrix names `%value%`, `%text%` and
`%now%` alongside `%device%`, `%time%` and `%date%`, so a variable with any of those names
collides the same way. **[external]**

### 13.8 Deleting a variable a rule still uses

Deleting a Hub Variable does not check the rules that use it, and the rule breaks at once.
On 2026-09-15 (firmware 2.5.1.183) a throwaway rule held a `getSetVariable` action for a
Number variable and a Log action containing `%Name%`. The variable was deleted through the
Hub Variables delete-and-confirm steps, driven by a tool rather than by hand. Immediately
afterwards the rule label read `*BROKEN*`, `ruleBuilderJson` reported `broken: true`, and the
page showed `**Broken Action**`. **[single]**

Whether the hub's own delete screen shows an in-use warning first was not observed, because
the confirmation was submitted programmatically. For a reader of this format the consequence is
the same: a structured variable reference whose name is missing from the Hub Variable
inventory marks a rule that is already broken, not one that will recover.

## 14. Facts held by the community MCP Rule Server, not yet observed here

The Hubitat Local MCP Server (kingpanther13/Hubitat-local-MCP-server) drives Rule Machine
through its wizard rather than reading it, so its source records write-side behaviour this
read-only work cannot reach. The items below are **[external]**: taken from that source and
its live-verified comments, not observed on this hub. They are recorded so a reader knows
where to look, not as findings of this document.

**Storage and editing state**

- Each `appSettings` record carries a `multiple` marshal flag. A write that omits the
  `<name>.multiple=true` sidecar flips it false, after which every page render throws
  `Command 'size' is not supported by device '<label>'`, `eventSubscriptions` stays at zero,
  and the rule is inert until the whole three-field group is re-posted.
- Rule Machine never renumbers after a delete. Indices keep their gaps, the next add takes
  `max + 1`, and an emptied row persists as a present key with a blank value. This hub shows
  the same pattern (four `actSubType.<n>` rows with an empty value).
- A rule can hold stuck wizard state in `state.editAct` and `state.editCond`. While `editAct`
  is set, Rule Machine silently ignores delete clicks, and it does not clear on its own.
- A disabled app renders only "App is disabled" with no wizard at all, so `disabled` is not
  cosmetic for anything that follows config pages.
- Rule Machine leaks `predCapabs` from the Required Expression builder into the next action
  written, which then renders under `IF(**Broken Condition**)`.

**Rule-level settings on the main page**

`origLabel` (the rule name), `comments` (rule notes), `useST` (enables the Required Expression
page, the settings-side counterpart to `appState.hasPredicate`), `logging` (a JSON array, not
a comma-separated string), `dValues` (display current values) and `isFunction`. All except
`isFunction` were also seen on this hub.

**Breakage reporting**

The rendered page carries three distinct markers, `**Broken Trigger**`, `**Broken Action**`
and `**Broken Condition**`, alongside the `*BROKEN*` label suffix. The compiled `broken`
boolean lags the rendered label: deleting a trigger device sets the label immediately while
`broken` stays false until the rule re-validates. Cross-check the two rather than trusting
either alone.

**Value sources that no device scan will find**

`trackSwitch.<n>` and `useLastDev.<n>` make an action read the triggering event instead of a
stored device, and `optSwitch.<n>` ("command only switches that are on?") changes what an
on/off action does once a device is chosen. All three are present on this hub in quantity
(249 `optSwitch`, 190 `trackSwitch`, 44 `useLastDev` rows), but their effect on decoding is
recorded from the MCP source.

**Scenes**

Rule Machine 5.1 has no activate-scene action. A scene or Room Lighting group is activated by
turning on its activator device through an ordinary Switch action, so a scene dependency is
stored as a plain device reference and reads as one.

**Endpoints**

- `GET /app/ruleBuilderJson/<id>` returns the compiled state for any installed app: `broken`,
  `paused`, `hasPredicate`, `predCapabs`, `eval`, `parens`, `actionList` and the rendered
  condition text. Like `statusJson`, it answers 200 with `{}` for an id that does not exist.
  Confirmed on this hub.
- Deletion has two endpoints with different semantics: `/installedapp/delete/<id>` returns
  `{success, message}` and refuses when the app has children, while
  `/installedapp/forcedelete/<id>/quiet` redirects and always succeeds.
- Rule-local variables are deleted through a two-step button flow on `/installedapp/btn`:
  `name=<varName>` with `stateAttribute=deleteGV`, then `name=delConfirm` with
  `stateAttribute=deleteConfirm`.
