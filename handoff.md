# Automation Map - Development Handoff

## TheBearMay reverse-engineering

### Purpose

This section records findings from reviewing Jean P. May Jr. (`thebearmay`) **Rule References Rule Table**, its reverse-engineering of Rule Machine's stored rule references, and the related Hubitat community discussions. The objective is not to copy that application into Automation Map, but to identify what its empirical work reveals about Rule Machine storage and where Automation Map's current rule-to-rule decoder can be strengthened.

### Executive conclusion

Automation Map's current `dev` implementation is architecturally stronger than Rule References Rule Table. Automation Map correlates Rule Machine action type, action subtype, action number and target setting before creating a typed rule-to-rule edge. TheBearMay's application is intentionally broader: it scans `appSettings` for setting-name patterns known to contain rule IDs and builds forward and reverse reference tables from them.

The correct direction is therefore **not to port TheBearMay's parser wholesale**. Instead:

1. retain Automation Map's semantically correlated decoder as the primary source of rule-to-rule edges;
2. incorporate the additional storage variants found through TheBearMay's community testing;
3. use `/hub2/appsList` as a supplementary installed-app discovery source rather than relying only on device-led discovery;
4. retain provenance/confidence when a relationship is discovered by a weaker fallback heuristic;
5. surface stale or deleted targets as integrity findings rather than silently dropping them.

The most material missing reference forms identified by this investigation are:

- `privateF.*`;
- `ruleActMain.*`;
- `valFunction.*`, used for Rule Functions.

These are concrete false-negative risks for Automation Map's current decoder.

---

### What TheBearMay reverse-engineered

The key discovery is that Hubitat does not expose a native reverse `In Use By` index for rules. Instead, **the calling automation stores the target rule ID in its own installed-app settings**. The reverse relationship can therefore be reconstructed by:

```text
Enumerate installed Rule/automation app instances
        |
        v
GET /installedapp/statusJson/<appId>
        |
        v
Inspect appSettings for settings containing target rule IDs
        |
        v
Build source -> target references
        |
        v
Invert them to produce target <- used-by relationships
```

TheBearMay's application obtains Rule-related installed child apps from:

```text
/hub2/appsList
```

and then reads:

```text
/installedapp/statusJson/<appId>
```

for each rule. It scans `appSettings` for recognised setting names, extracts IDs, and builds two tables:

- **Rule Affects** - rules referenced by the source rule;
- **In Use By** - rules that reference the target rule.

This is useful because it confirms that Rule Machine relationships can be recovered from internal runtime/configuration state without parsing user-visible Rule Machine text and without access to the built-in Rule Machine source.

Primary implementation reviewed:

- https://github.com/thebearmay/hubitat/blob/main/apps/ruleRruleList.groovy

Relevant community discussions:

- https://community.hubitat.com/t/feature-request-add-rule-machine-in-use-by-list/147838
- https://community.hubitat.com/t/release-rule-machine-rules-in-use-by-app/148245
- https://community.hubitat.com/t/rule-machine-rule-functions/146774
- https://community.hubitat.com/t/finding-rules-that-are-in-use-by-other-rules/95874
- https://community.hubitat.com/t/would-love-a-rule-flow-chart/148651

---

### TheBearMay setting patterns versus Automation Map

TheBearMay's current implementation looks for these setting-name families:

```text
ruleAct
pauseRule.
valFunction.
privateT
privateF
stopAct
```

Community testing also exposed `ruleActMain.*` as another rule-target storage form.

The current Automation Map `dev` branch already has a much more selective mapping:

```groovy
@Field static final Map RULE_LINK_ACTIONS = [
    getRuleActions      : [target: 'ruleAct',   engine: 'runRuleType',   kind: 'runs'],
    getStopActions      : [target: 'stopAct',   engine: 'stopRuleType',  kind: 'stops'],
    getSetPrivateBoolean: [target: 'privateT',  engine: 'pvRuleType',    kind: 'setspb'],
    getPauseResumeRules : [target: 'pauseRule', engine: 'pauseRuleType', kind: 'pauses'],
]
```

and only accepts those targets when the corresponding action is confirmed as part of the `rulesActs` family through:

```text
actType.<n> == rulesActs
actSubType.<n> == recognised Rule action method
```

This is preferable to scanning every matching setting name because Rule Machine can retain stale or historical settings. A broad setting-name scan can therefore report a syntactically valid target ID that is no longer part of the executable rule.

The recommended model is a **hybrid**:

| Reference form | Meaning | Current Automation Map | Recommendation |
|---|---|---:|---|
| `ruleAct.<n>` | Run Actions of another rule | Yes | Keep current correlated parsing |
| `stopAct.<n>` | Cancel Timed Actions for another rule | Yes | Keep extraction, improve semantic name |
| `pauseRule.<n>` | Pause/Resume another rule | Yes | Keep extraction, avoid claiming direction until proven |
| `privateT.<n>` | Private Boolean target | Yes | Keep |
| `privateF.<n>` | Alternate Private Boolean target storage | No | Add |
| `ruleActMain.<n>` | Alternate rule-action target storage | No | Add |
| `valFunction.<n>` | Rule Function invocation | No | Add as a separate relationship type |
| `*` / own app ID | This Rule/self | Yes | Keep out of inter-rule graph; retain in focused flow |

---

### Important gap 1 - `privateF.*`

TheBearMay added `privateF` after real-world community testing exposed references missed by the original implementation.

Automation Map currently recognises only:

```text
privateT.<n>
```

for `getSetPrivateBoolean` actions.

`privateF.*` should be treated as an additional candidate target setting for this action family. However, the suffix must **not** be assumed to prove that the Private Boolean is being set false. Automation Map has already found another PB-related field (`pvTF.<n>`) whose apparent boolean meaning does not agree with what Rule Machine displays in the UI. The safe approach is therefore:

```text
privateT/privateF -> target storage variant
```

not:

```text
privateT -> set true
privateF -> set false
```

until controlled fixtures prove that interpretation.

Recommended family shape:

```groovy
getSetPrivateBoolean: [
    targets: ['privateT', 'privateF'],
    engine: 'pvRuleType',
    kind: 'setPrivateBoolean'
]
```

---

### Important gap 2 - `ruleActMain.*`

Community testing around Rule References Rule exposed `ruleActMain.*` as another setting capable of containing referenced rule IDs, particularly around Button Rule / alternate action-storage cases.

Automation Map currently only checks:

```text
ruleAct.<n>
```

for `getRuleActions`.

The decoder should therefore allow more than one target-setting prefix per semantic action family:

```groovy
getRuleActions: [
    targets: ['ruleAct', 'ruleActMain'],
    engine: 'runRuleType',
    kind: 'runActions'
]
```

This must be fixture-tested before treating every `ruleActMain.*` occurrence as executable. If it cannot be correlated to an active action number, it should be retained as a lower-confidence candidate rather than emitted as a normal high-confidence edge.

---

### Important gap 3 - Rule Functions via `valFunction.*`

This is the most important functional gap identified.

Hubitat Rule Functions are callable automation components. Another Rule or Button Rule can invoke a Rule Function, optionally pass a parameter and receive a returned value. This is not merely a shared-device relationship. It is a genuine automation-to-automation call and belongs directly in Automation Map's topology.

TheBearMay added recognition of:

```text
valFunction.<n>
```

after community testing demonstrated Rule Function references.

Automation Map's current extraction begins by restricting candidates to:

```text
actType.<n> == rulesActs
```

so a Rule Function target stored outside that family can be missed completely.

Rule Functions should therefore be extracted through a second, explicit decoder rather than pretending they are `getRuleActions`:

```text
extractDirectRuleActions()
    -> run actions
    -> cancel timed actions
    -> set private boolean
    -> pause/resume

extractRuleFunctionReferences()
    -> valFunction.*
```

Recommended graph relationship:

```text
callsFunction
```

Example:

```text
Rule A
   |
   | calls function
   v
Calculate Target Temperature
```

This should remain distinct from:

```text
Rule A
   |
   | runs actions
   v
Rule B
```

because the coupling and execution semantics are different.

---

### Semantic corrections to current edge names

Two current relationship labels are stronger than the evidence supports.

#### `stops` should become `cancelTimedActions`

`getStopActions` corresponds to Rule Machine's **Cancel Timed Actions** behaviour. It does not mean that the target rule is stopped as a running process.

Current:

```text
stops
```

Recommended:

```text
cancelTimedActions
```

This avoids creating a false operational interpretation in the graph.

#### `pauses` should become `pauseResume`

Automation Map already notes that the same Rule Machine action family covers both Pause and Resume and that the observed discriminator has not yet been reliable enough to distinguish them.

Current:

```text
pauses
```

Recommended until proven:

```text
pauseResume
```

If controlled fixtures later identify a reliable discriminator, split it into:

```text
pauses
resumes
```

The compatibility message should likewise avoid enumerating incomplete semantics. Prefer:

```text
No direct automation-to-automation references found.
```

rather than listing only runs/stops/private-boolean operations.

---

### Why TheBearMay's broad parser should not replace Automation Map's decoder

TheBearMay's parser is intentionally pragmatic. It asks whether a setting name resembles one of the known reference-bearing patterns, extracts IDs and reports them.

That maximises discovery but weakens certainty.

Automation Map currently has stronger evidence because it correlates:

```text
action family
+ action subtype
+ action number
+ target setting
+ target app ID
```

before creating an edge.

This distinction matters because the Hubitat community investigation found examples of apparent stale/ghost references remaining in Rule Machine settings. In some cases old references disappeared only after an action was deleted and recreated. A setting-name-only parser can therefore turn historical configuration debris into a confident-looking dependency.

Recommended evidence levels:

| Evidence | Confidence | Graph treatment |
|---|---:|---|
| Active action subtype + matching action-number target | Verified/high | Normal typed edge |
| Rule Function setting correlated to active function-call action | Verified/high | Normal `callsFunction` edge |
| Known target-setting pattern without active action correlation | Medium | Candidate/unverified edge or integrity finding |
| Unrecognised numeric setting that resolves to an app ID | Low | Do not draw by default; retain for diagnostics |

This confidence/provenance should be available internally even if the first UI version does not display it.

---

### Extend the rule-link data model

Current rule links are approximately:

```groovy
[
    to: targetId,
    kind: fam.kind,
    engine: engine
]
```

A better handoff target is:

```groovy
[
    to: targetId,
    kind: 'runActions',
    action: '29',
    sourceSetting: 'ruleAct.29',
    engine: 'Rule Machine',
    confidence: 'verified'
]
```

Useful provenance fields:

- source action number;
- setting name that supplied the target;
- Rule Machine action subtype;
- target engine/type where available;
- decoder used;
- confidence level;
- whether target resolution succeeded;
- whether the target is paused/disabled/deleted.

This will make future debugging much easier when Hubitat changes an undocumented internal representation.

---

### `/hub2/appsList` is potentially more important than TheBearMay's parser

TheBearMay's rule enumeration uses:

```text
/hub2/appsList
```

This is significant for Automation Map because its main discovery path is currently device-led:

```text
all devices
    -> /device/fullJson/<id>
        -> appsUsing
            -> installed app IDs
```

That approach is generic and has worked well, but it has an intrinsic blind spot: **an automation with no device relationship may never be discovered at all**.

Examples include:

- a Rule Function that only performs calculation/variable work;
- a reusable macro-style rule;
- a rule that only calls another rule;
- some Button Rule variants;
- future built-in automation children that do not bind directly to devices.

Automation Map already contains compensating logic for a rule discovered only because another rule references it. `/hub2/appsList` provides a cleaner second discovery channel.

Recommended discovery model:

```text
                       +------------------+
Devices -------------->| appsUsing        |
                       +--------+---------+
                                |
                                +-------+
                                        |
/hub2/appsList -------------------------+----> UNION installed app IDs
                                        |
Referenced target IDs ------------------+
                                        |
                                        v
                               statusJson scanning
```

The recommendation is **not** to replace device-led discovery. Union the sources.

Also do not copy TheBearMay's `type.contains("Rule")` filter blindly. His application is specifically a Rule reference utility. Automation Map is broader. Investigate whether `/hub2/appsList` can safely enumerate all installed child applications and use the full hierarchy as an additional source of app instances.

Potential benefit: this may remove the architectural limitation that an app must reference at least one device before it is naturally discovered.

---

### Deleted and stale references should become Automation Map findings

The Rule References Rule community discussion quickly raised another useful case: a source rule can retain a reference to an automation that has since been deleted.

Automation Map is already ahead here. `fetchAppName()` handles the unusual Hubitat behaviour where `statusJson` may return an empty shell rather than a 404 for a deleted app, and can label a target:

```text
Rule <id> - deleted
```

This should be promoted from an implementation detail into **Insights**.

Recommended integrity findings:

| Finding | Suggested severity |
|---|---|
| Rule references deleted automation | High |
| Rule Function target missing | High |
| Referenced automation paused/disabled | Medium |
| Reference recognised only by fallback heuristic | Warning |
| Multiple source actions reference same target | Informational |
| Self-reference only | Suppress from topology; retain in focused rule flow |

This turns rule-reference mapping into automation integrity analysis rather than merely another visual relationship.

---

### Incoming and outgoing dependency view

TheBearMay's two-table presentation remains useful even though Automation Map has a graph.

For a focused automation, consider a compact dependency panel showing:

```text
AFFECTS
- Rule B - Run Actions
- Rule C - Set Private Boolean
- Calculate Target Temperature - Calls Rule Function

IN USE BY
- Rule D - Run Actions
- Button Rule E - Calls Rule Function
```

This would reproduce the practical `Rule Affects` / `In Use By` value of TheBearMay's utility without requiring the user to visually trace every graph edge.

The graph remains the primary model; the lists are simply indexed views of the same typed edges.

---

### Do not overstate indirect causality

Direct references are only one form of automation dependency.

Example:

```text
Rule A
   |
   v
Virtual Switch X
   |
   v
Rule B
```

Rule A may never contain Rule B's ID, yet Rule A can still be the practical cause of Rule B running. The same is possible through:

- Hub Variables;
- Location Mode;
- HSM state;
- virtual devices;
- external integrations;
- HTTP/Maker API paths;
- MQTT or other brokers.

These should not be conflated with explicit rule references.

Recommended dependency classes:

| Dependency class | Example | Confidence |
|---|---|---:|
| Direct automation control | Rule A -> Run Actions Rule B | Very high |
| Direct function call | Rule A -> Rule Function B | Very high |
| Direct automation state control | Rule A -> Set PB Rule B | Very high |
| Direct cancellation/pause-resume control | Rule A -> Rule B | Very high when decoded |
| Indirect device coupling | Rule A -> Virtual Switch -> Rule B | High when both producer/consumer sides are known |
| Indirect variable coupling | Rule A -> Hub Variable -> Rule B | Future capability |
| Runtime causal chain through arbitrary apps/external systems | Multi-hop | Cannot generally be proven statically |

Automation Map should therefore distinguish **explicit topology** from **inferred causality** rather than flattening both into the same edge class.

---

### Recommended implementation sequence

| Priority | Change | Value | Effort |
|---|---|---:|---:|
| P0 | Add `privateF.*` candidate support | Closes demonstrated false negatives | Low |
| P0 | Add `ruleActMain.*` candidate support | Closes demonstrated alternate-action false negatives | Low |
| P0 | Add independent `valFunction.*` Rule Function extraction | Adds an entire missing dependency type | Low-medium |
| P0 | Rename `stops` to `cancelTimedActions` | Corrects misleading semantics | Low |
| P0 | Rename `pauses` to `pauseResume` until discriminator is proven | Avoids unsupported precision | Low |
| P1 | Add source action number + setting provenance to rule-link records | Debuggability and future compatibility | Low |
| P1 | Add `/hub2/appsList` as supplementary discovery | Finds device-less automations | Medium |
| P1 | Add missing/deleted target findings to Insights | High operational value | Low |
| P1 | Collapse duplicate visible edges but retain action IDs/count internally | Readability without evidence loss | Low |
| P2 | Add focused `Affects` / `In Use By` panel | Practical dependency navigation | Medium |
| P2 | Add fixture-based decoder regression tests | Protects undocumented internal parsing | Medium |

---

### Regression fixtures to capture before implementation

Because all of this uses undocumented Hubitat/Rule Machine state, changes should be driven by captured real `statusJson` fixtures rather than assumptions about field names.

At minimum capture examples for:

1. Run Actions of another rule using `ruleAct.*`;
2. alternate `ruleActMain.*` storage;
3. Cancel Timed Actions of another rule;
4. Pause another rule;
5. Resume another rule;
6. Private Boolean target using `privateT.*`;
7. Private Boolean target using `privateF.*`;
8. PB targeting both `This Rule` and another rule in the same action;
9. Rule Function call from Rule Machine;
10. Rule Function call from Button Rule;
11. multiple target rules in one action;
12. deleted target rule;
13. paused/disabled target rule;
14. cloned rule carrying stale/ghost target settings;
15. source rule with no device bindings;
16. target rule with no device bindings;
17. Button Rule / RM child whose storage differs from the normal Rule-5.1 path.

For each fixture, record:

```text
- visible rule/action in the Hubitat UI
- installed app ID
- action number
- relevant appState.actions entry
- relevant appSettings entries
- expected Automation Map typed edge
- expected flowchart rendering
- whether an incoming reference should be generated
```

This should become the compatibility contract for the decoder.

---

### Strongest synthesis

TheBearMay's work is valuable primarily as **empirical discovery evidence**, not as a parser architecture to transplant.

Automation Map already has the better core design because it reconstructs semantic actions and creates typed app-to-app relationships rather than simply reporting IDs found in settings. The community work nevertheless exposes three real gaps - `privateF`, `ruleActMain`, and especially `valFunction` - and demonstrates that `/hub2/appsList` can provide a second app-enumeration path that may solve device-less automation discovery.

The recommended direction is therefore:

```text
Keep the current semantic decoder
        +
add newly discovered storage variants
        +
add Rule Function calls as their own edge type
        +
union /hub2/appsList into app discovery
        +
retain confidence/provenance
        +
promote broken references into Insights
```

If implemented this way, Automation Map will not merely reproduce Rule References Rule Table. It will subsume its useful capability inside a richer topology model while remaining more resistant to stale settings, undocumented storage variation and false-positive dependencies.

---

### Reviewed and accepted position (2026-08-14)

Cross-checked against the current `dev` source rather than taken on the document's word. `RULE_LINK_ACTIONS` does have exactly one `target` string per family, not a list, so `privateF`/`ruleActMain` are genuinely unrecognised. There is no `/hub2/appsList` discovery path in the app at all. The `pvTF` warning matches the existing comment in the code, which means this analysis actually engaged with Automation Map's own documented findings rather than guessing.

**Accepted, low effort, closes a real gap:**

- `privateF` / `ruleActMain` as additional target-setting aliases within the existing families. A structural change from `target: 'x'` to `targets: ['x', 'y']`, nothing more.
- Rename `stops` -> `cancelTimedActions` and `pauses` -> `pauseResume`. Not cosmetic - the app does not currently know which of pause/resume it is looking at, and the label should not claim more than that.
- Promote deleted/paused rule targets from an inline label into an Insights finding. `fetchAppName()` already computes `missing: true`; this is surfacing existing data, not new detection.

**Accepted in principle, but gated on a live fixture before implementation, not on this document's say-so:**

- `valFunction` as a `callsFunction` edge type. Real gap if Rule Functions are genuinely separate installed apps on this hub - confirm that shape against a live fixture first, the same discipline used to test and reject the HTTP-proximity heuristic for Sensibo rather than ship it on a hunch.
- `/hub2/appsList` as a second discovery channel. Sound reasoning, but verify a Rule Function actually has its own installed-app id on this hub before treating this as the fix for device-less automation discovery. If it does not, this solves the wrong layer.

**Right instinct, correctly deferred:**

- Per-edge confidence/provenance (verified vs candidate) mirrors the three-state MATCH/NO_MATCH/NOT_EVALUABLE pattern already used for the integration registry. P1/P2 is the right call - it is a data-model and UI change, not a quick add.
- A fixture suite is the right discipline, but fold it into the existing `Supporting Docs/rule_machine_5_1_storage_format.md` living document rather than standing up a second system. That document already carries evidence markers per finding; a separate fixture file would duplicate the job.

**The generalizable method, worth reusing deliberately:** someone else's reverse-engineering of the same undocumented platform is a source of candidate hypotheses, never a source of facts about this hub. Each claim here was checked against the live `statusJson` shape before being accepted. `privateF` is credible because a community thread showed it being hit in practice, not because the code looks plausible. That standard is what makes it worth adopting, and it applies equally to any future outside source, including the HPM manifest crawl.

No code changed as part of this review. See the priority table above for sequencing when implementation starts.

---

### Gate results, measured 2026-08-14

Both items gated in the section above were tested against the live hub on firmware
2.5.1.147. No code was changed.

**`/hub2/appsList` gate: PASSED.** The gate was "verify a Rule Function actually has its own
installed-app id on this hub before treating this as the fix for device-less automation
discovery". It does. `_Testy Function` is installed app 2973, `type` of `Rule-5.1`,
indistinguishable in the listing from any other rule, present in `/hub2/appsList` and absent
from the device-led scan. The endpoint returns 89 apps against 74 found by walking devices,
and carries `appTypeId` and `disabled` per app without a second request.

Two qualifications that should shape how it is adopted:

- It is a supplement, not a replacement. It reports that an app exists, never which devices
  it touches, so device-led discovery still does the work it does today.
- On this hub the union found **no rule links** the device-led scan had missed, because
  every rule that acts on another rule happened to be reachable through devices. What it
  buys here is the guarantee and the device-less apps. The guarantee is not nothing, given
  `appsUsingForDialog` once hid 12 of 74 apps.

The free `appTypeId` is worth noting against the integration registry item in BACKLOG.md,
whose recorded blocker was that the scanner stores only `label` and `type` per app.

**`valFunction` gate: NOT PASSED, no fixture.** Every rule-typed app on the hub was
enumerated and searched: zero instances of `valFunction`, and likewise zero of `ruleActMain`
and `privateF`, which are already handled defensively as aliases. Nothing here confirms or
refutes the claim, so it stays gated exactly as written. This is the same standard applied
to the rest of that analysis: another project's source is a source of hypotheses, not facts
about this hub.
