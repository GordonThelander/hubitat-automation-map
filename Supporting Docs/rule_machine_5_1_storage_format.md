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
section 5.2 shows the stored expression carries no grouping, and the live tests available
contradict conventional precedence.

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
source; no writes to rule configuration; no testing of evaluation semantics; one hub, one
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

Unmarked prose is description or advice rather than a claim about the format.

### The short version

If you read nothing else, read these:

- An action's parameters are **not** in the action. They are in the app's settings, keyed by
  the action number. Neither half is usable alone (section 3). **[invariant]**
- Some actions carry a field called `rule` which is **not a rule reference**. It is a
  condition index, and it is non-null for only three action types (section 9.1). **[invariant]**
- `indent` does not reliably describe nesting. Do not build a tree from it (section 9.2). **[strong]**
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

Two settings accompanied every action examined: **[strong]**

| Setting | Meaning |
| --- | --- |
| `actType.<n>` | the action *family*, e.g. `switchActs`, `dimmerActs`, `condActs`, `delayActs`, `rulesActs` |
| `actSubType.<n>` | the specific action, matching the `method` in the action object |

`actSubType` duplicates `method`. Prefer whichever you like, but `actSubType` was present in
every case observed, so it is the safer primary key. **[strong]**

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

Operators appear inline as strings between condition numbers. **The stored form is flat and
carries no grouping information at all** for the expressions examined. **[strong]**

**Do not infer evaluation order from that flatness.** How Rule Machine evaluates a mixed
AND/OR chain is an execution question this document does not answer, and it is not safely
guessable. Two live tests on build 2.5.1.140, both reading the Required Expression result
the rule page prints:

| terms | expression | condition values | result | build |
|---|---|---|---|---|
| 3 | `A AND B OR C` | F, F, T | FALSE | 2.5.1.140 |
| 4 | `A AND B OR C AND D` | T, T, F, F | TRUE | 2.5.1.147 |

**The two tests are from different builds.** An expression evaluator changing across a patch
release is unlikely but not excluded, so the combined conclusion below is weaker than two
measurements on one build would be.

The three-term case resolved as `A AND (B OR C)`, the *opposite* of conventional Boolean
precedence, since `(A AND B) OR C` would have printed TRUE. The four-term case then ruled
out two more candidates:

    (A∧B) ∨ (C∧D)      conventional      TRUE    survives test 2, fails test 1
    A ∧ (B ∨ (C∧D))    right grouping    TRUE    survives both
    A ∧ (B∨C) ∧ D      OR binds first    FALSE   ruled out
    ((A∧B) ∨ C) ∧ D    left to right     FALSE   ruled out

**Right-associative grouping, rightmost operator applied first, is the only model consistent
with both results.** It also subsumes the three-term result, which was previously described
as "OR binds tighter" and is better read as a special case of grouping from the right.
Conventional precedence fits test 2 alone and is not excluded by it, only by test 1. **[limited]**

Two measurements are still not a specification. Neither test used NOT, neither used more than
one OR, and no test has yet forced conventional and right grouping apart directly, which needs
a case such as `F AND F OR T AND T` where conventional gives TRUE and right grouping FALSE.

The practical consequence is worth stating because it bites real rules: **under either
surviving model, a term to the right of an OR is unreachable whenever the OR's left operand
is true.** A Private Boolean placed last in `Mode AND Evening OR Morning AND PB` is silently
ignored throughout the evening window. Observed on a live rule, diagnosed as a lamp that never
turned off.

The obvious workaround, moving the gate into the actions as an early `Exit Rule`, is **not
available for a Private Boolean**. Rule Machine 5.1 uses a different capability list for
"Select capability for Action Condition" than for Required Expression conditions, and Private
Boolean is absent from the action list. Verified on 2.5.1.140: the action list runs
`... Power source, Presence, Switch, Temperature, ...` with no `Private Boolean` entry where
alphabetical order would place it. **[single]** The remaining fix is to reorder the expression
so the gate sits leftmost, which under right grouping makes it the outermost test.

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
is Rule Machine's way of storing "set the Private Boolean of this rule *and* of rule 1809".
Treating the presence of `"*"` as meaning self-only will silently drop genuine cross-rule
references. **[strong]**

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

### 9.4 Labels carry hub-injected HTML

An app's label is not clean text. Hubitat appends status markup:

    Theatre Room Light and Fireplace <span style='color:red'>(Required Expression false)</span>

Strip tags. Note the parenthetical text survives stripping, which is usually what you want,
since it is real information.

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
