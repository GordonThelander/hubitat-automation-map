# Automation Map backlog

The active sections (Now, Next) track agreed work that has not shipped. Hold/closed is
deliberately the opposite: a historical record of what was delivered, rejected or deferred, kept
so a decision can be traced later. It is not included in the HPM package and is not a release
commitment.

## How this backlog is organised

- **Now**: correctness defects, misleading output and high-value usability work.
- **Next**: valuable work with a known direction but more design or investigation required.
- **Hold / closed**: deliberately deferred, rejected or completed items kept as a short record.

Every active item states its next action. Detailed research belongs in Supporting Docs or commit
history, not in this delivery list.

## Now

### 33. Remote access: the page stays on "Remote scanning" after the scan finishes

Seen on the 2.3.0 preprod install (2026-09-13) through remoteaccess.aws.hubitat.com: the scan completed
cleanly, but the page stayed on "Remote scanning, this page will refresh once done." until Done was
pressed. Not a 2.3.0 regression: this path has shipped unchanged since 2.0.11 and is in production.

**Established.** Off the hub's own origin the page cannot poll `/scan-status` (Hubitat's cloud API sends
no CORS headers), so `amProgressPoll()` returns immediately on the cloud path. The only thing that
moves the page on is the dynamicPage `refreshInterval` of 60s while a scan is active.

**Hypothesis, not confirmed.** Either the remote UI does not honour `refreshInterval`, or its refresh
rendered from a snapshot that still showed the scan running. Needs a remote reproduction with the
page left open for more than 60s after completion.

**Done on dev (2.3.3):** off the hub origin, a page rendered while a scan is active now reloads every
15s, capped at 12 reloads per tab session; the cap resets once a page renders with no scan running.
This works whichever hypothesis is true.

**Next action:** confirm through remote access that the page moves on by itself after a scan.

## Next

### 35. HAI as a third engine: map decoding and piston migration target

Hubitat Automation Intelligence (HAI) is Gordon's Rule Machine replacement. It will publish its rules
already in the map's graph shape from a read-only `/automation-map` endpoint on the HAI Engine app
(contract `hai.am/1`: nodes, edges and flows using this app's node ids and edge kinds), together with a
`capabilities` list of what HAI supports and at what status. The HAI project builds and maintains both
with its parity work.

Two pieces here, both small adapters, not decoders:
- **Map:** find the HAI Engine during a scan, read the endpoint, and merge its nodes, edges and flows as
  another engine. HAI rule apps appear with `engine: HAI`.
- **Piston migration assessment:** add HAI as a third target beside RM 5.1 and VRB 2.0, rating each
  webCoRE construct against the published `capabilities` list rather than a hand-kept table. The rating
  panel, the equivalence tab and the export's cached ratings all gain the HAI column.

**Next action:** wait for the HAI session's first feed on the hub, then add both adapters against its
golden fixtures and verify live.

### 36. The HAI exchange: what we owe, what we are owed, and what we verify

This project and HAI are now each other's suppliers. We consume its `hai.am/1` feed and its
capability list; it consumes our RM storage and execution documents, the `am.edges/1` contract,
and measurements we take on the hub. Neither side should take the other's claims on trust, and
both sides' documents are wrong often enough to matter: on 2026-09-24 a claim of ours marked
[strong] was wrong and HAI's challenge was right, and on the same day HAI offered to withdraw a
correct challenge in our favour, which was refused.

**Known properties of the feed contract, recorded so neither side files them as defects.**

- **The feed carries the hub's current device names, not the names captured when a rule was
  authored.** The engine re-resolves every device reference against live hub names before rendering
  a step, so a rule written long ago whose device has since been renamed still draws correctly here.
  The consequence is that a device reference's authored name is structurally unreachable from this
  side: it is not an input to anything we receive. If a name on our map ever differs from what a
  user sees in the engine's own editor, this is why, and it is correct behaviour on both sides.
  Established 2026-09-26, when it silently defeated a test we had jointly designed around renaming
  device references.
- **The feed is a complete document.** Every rule that has not been removed appears, whatever
  changed. Our merge depends on this in three places that all fail silently on a partial document:
  the stripping of placeholder `exposed` edges is keyed on the ids in the response, the engine tag,
  editor link and status flags are applied per node present, and flows are keyed on ids present.
  The engine has pinned this as a named test on its side that fails on exactly the mutation an
  incremental-write optimisation would make. If a `partial` or `delta` marker is ever proposed, we
  need a retained-copy merge path built first.

The standing rules for the exchange:

- **Measure rather than defer.** Where the two sides disagree, run it on the hub. A concession
  from either side is not evidence and does not close a question.
- **Correct our own documents in public.** When a measurement goes against us, rewrite the
  section and mark that it changed, so a reader who saw the old version can see why.
- **Say which kind of claim it is.** Measured, read from live settings, or documentation. Never
  let a documentation claim travel as a measurement.
- **Record a finding where it belongs.** Storage keys to the storage document, runtime behaviour
  to the execution document, a delivery consequence here.

**Owed to HAI:**
- The list of RM capabilities this hub's rules actually reach, so the engine can prioritise what is
  in real use. Offered 2026-09-20, asked for 2026-09-23.
- Row 6 of its `rm-semantics.md`: what RM does with a condition it cannot read. Authoring-time
  validation blocks the obvious test, so the device has to be removed after authoring.
- The `lowMemory` location event payload, when one next fires.

**Delivered 2026-09-24:** the list of capabilities this hub's rules actually reach, taken from
`/rm-coverage` rather than a hand scan. Whenever that figure travels it needs two sentences, never
one: what this hub's own rules exercise is covered, and a rule set from one house is no evidence
at all about the capabilities it never touches.

**Owed to us:**
- Its 22 decode defects with evidence, to check against storage sections 5, 9 and 10.
- Its three RM-editor verdicts on mixed AND/OR grouping, which should also close T07/T08 in the
  execution document.

**Received 2026-09-24:** a generated per-rule disposition for this hub's rules, which is the
natural source for a migration-readiness column here (item 35) rather than anything hand-kept. It
carries its own caveat and that caveat must travel with it: a reading plus a validator pass is not
a behavioural pass.

Two accuracy items raised with HAI and accepted by it: the feed names its location-event
capability for sunrise and sunset while rule 2100 uses the same capability for `lowMemory` and
`severeLoad`, so the name is narrower than the thing; and `Run Custom Action`, at 22 rules the
third most used capability here, is an arbitrary device command with parameters, so its fidelity
is not one behaviour but as many as there are commands behind it. Both belong to the engine
rather than to this app.

The standing rule both sides have adopted, after a day in which nearly every error caught in
either direction was a true observation stated more widely than its evidence: **state the scope
of the evidence with the claim**. A finding that held on one code path, or on one hub, says so.
That failure mode does not feel like guessing at the time, which is why it needs naming rather
than care.

**Settled 2026-09-24, and it bounds HAI's method rather than a single rule.** What RM *renders*
is the author's input, not the command it issues: the colour modes derive values, store them and
send them without ever displaying them. Any method that compares two engines by reading what each
renders inherits that seam: wherever a construct carries derived values the rendering under-reads
both sides equally, and the pair agrees without either having examined the part that could
differ.

**Next action:** take the 22 defects against the storage document section by section.

### 37. The HAI capability list can go stale without anyone noticing

Found 2026-09-24 while generating the capability list: `/rm-coverage` returned `ok:false` because
the engine's runtime on the hub had been built against an older capabilities hash than the file it
was given. The runtime was the stale side, not the file, so re-uploading would have changed
nothing. It was rebuilt and the report now answers.

**What the app did right, checked rather than assumed.** The panel renders `reason` verbatim on
`ok:false`, so anyone who opened it saw the hub's own explanation in words. An earlier note here
said it "rendered nothing useful"; that was wrong and is corrected. The report was never
silently empty and never read as false parity.

**What the app does not do.** The condition is only discovered by opening the panel. Nothing on
the app's main page says the feed's capability list is unreadable, even though that page already
reports how many rules the last scan read from the feed.

An independent hash check here was considered and rejected: the engine's runtime already compares the
hash it was built with against the file, and already reports the mismatch through
`capabilitiesError`, which this app surfaces. Re-implementing that comparison would duplicate a
working check rather than cover a gap. The published-list fallback (no engine on the hub) cannot
compare hashes at all, having no runtime to compare against, and already shows its `generatedAt`
date as its freshness signal.

**Next action:** add the feed's capability-list state to the main page beside the existing
"Last scan read N rules from the feed" line, so the condition is visible without opening the
graph. Small, and the only part of this incident the app can actually fix.

### 38. Location Event triggers are all mapped to the sunrise/sunset capability

`RM_CONSTRUCT_CAPABILITY` maps `trigger:Location Event` to
`trigger.location-event-sunrise-sunset-sunrisetime-sunset` and nothing else. The mapping is keyed
on the trigger's capability name alone, so it never reads `tstate<n>`, which is where the actual
event name lives (6.4 of the storage document). A trigger on `lowMemory` or `severeLoad` is
therefore reported as covered by the sun-events capability, which does not include either.

Two rules on this hub use Location Event triggers; rule 2100 `_Overload` triggers on `lowMemory`
and `severeLoad`. The engine's parity list carries a second location-event row for the system
events (`systemStart`, `severeLoad`, `zigbeeOff/On`, `zwaveCrashed`) and has no row for
`lowMemory` at all, so the correct outcomes are: sun events to the first row, system events to the
second, and `lowMemory` reported **unmapped** until the engine adds it.

This makes `/rm-coverage` overstate. The 2026-09-24 run reported 62 of 62 rules covered with 0
unmapped; at least one of those rules was covered only by a mis-attribution. The figures had
already been passed to the engine's project and were corrected there.

The same weakness applies anywhere a capability's identity depends on a value rather than the
construct name. Location Event is the case found; the mapping should be audited for others.

**Done 2026-09-24 (Dev revision 298).** `extractRuleConstructs` now carries the event name in the
token (`trigger:Location Event:<event>`), `RM_LOCATION_EVENT_TO_HAI` maps the four sun events and
the five system events to their two capabilities, and `haiCapabilityIdFor` answers that prefix
**before** the generic `trigger:` fallback, so an unrecognised hub event cannot quietly become an
ordinary device trigger. Focused cases pass for sunrise, severeLoad, lowMemory, an unknown name,
an empty name, a mapped device trigger and an unmapped one, plus extraction cases for rule 2100's
two bare-index triggers and a sun event beside an untouched Motion trigger.

Re-run after a fresh scan: `lowMemory` now reports **unmapped** and rule 2100 `_Overload` is no
longer counted as covered; `severeLoad` and `systemStart` map to the system-events capability.

### 39. Condition text truncation: not a defect here (closed)

**Closed 2026-09-24, unfixed because there is nothing to fix.** Raised on the belief that the flow
view inherits a truncation the HAI session measured in Rule Machine's own rendering, where a
condition using `<` displays as "... is" with no operator or threshold.

What the hub actually stores, read from `/installedapp/statusJson` for four affected conditions:

    Illuminance of _ Average External Illuminance(<span style='color:black'>9755</span>) is < 200

The stored string is **complete**. The loss HAI measured happens in the browser rendering RM's
config page, where `< 200` is parsed as a tag opener. It is a display fault in RM's page, not a
fault in the data, and this app never reads that page.

Our own cleaning is safe by construction: `stripTags` matches `<[^>]*>`, which requires a closing
`>`, so a trailing `< 200` is left alone. Verified by running `cleanCondition` against the four
real stored strings plus a `<=` and a `>=` case; all six render their comparator and threshold.

**The repair that was authorised would have corrupted working text**, appending "< 200" to a
string already ending in "is < 200". The instruction to inspect one affected raw condition before
editing is what caught it.

Worth keeping: **a value read from RM's rendered page is not the same as the value RM stored**, and
the page is the lossy one. Anything comparing engines by reading RM's UI should read rule state
instead, which is intact.

### 40. Twelve dimmer action subtypes are missing from the capability mapping

Surfaced by the re-run for item 38, on rules that did not exist at the previous scan. `getSetDimmer`
("Set dimmer", the most ordinary dimmer action there is) reports **unmapped** on three rules.

`RM_CONSTRUCT_TO_HAI` carries `action:getSetDimmers`, plural, which matches nothing Rule Machine
emits. Checked against RM's own `dimmerActs` subtype list, read from the action wizard's schema on
the hub, twelve of its sixteen subtypes have no entry at all:

    getSetDimmer  getToggleDimmer  getDimmersPerMode  getFadeDimmer  getStopFade
    getRLDimmer   getStopDimmer    getToggleColor     getColorPerMode
    getToggleColorTemp  getColorTempPerMode  getFadeCT  getStopCTFade

An unmapped `action:` token has no fallback, so each reports as a gap and drags its rule out of
"covered". Invisible until now because Gordon's own rules use none of the twelve; his dimmer usage
is `getSetColorTemp`, `getSetColor` and `getAdjustDimmer`, all mapped. Any other hub with a plain
"Set dimmer" action sees a false gap.

The engine publishes capabilities that answer most of them (`action.dimmers-set-dimmer-level-fade-time-variable-leve`,
`action.dimmers-set-dimmer-per-mode`, `action.dimmers-fade-dimmer-over-time-stop-fade-start-ra`,
`action.dimmers-toggle-dimmer-adjust-dimmer-relative-cha`, and the colour equivalents), so this is
a mapping gap rather than a coverage gap.

**Done 2026-09-24 (Dev revision 299).** All sixteen `dimmerActs` subtypes now map to the eight
capabilities that answer them, paired from the engine's own `rm51` descriptions of which Rule
Machine feature each covers. Verified two ways: no RM subtype left unmapped, and every capability
id referenced exists in the published list. `getSetDimmers` is kept as a deliberate alias rather
than deleted, so a rule carrying the plural still resolves.

**Quantified 2026-09-25**, at Gordon's direction, by sweeping the `actSubType` picker for all
twelve `actType` families on the hub. **73 subtypes offered. 54 mapped, 19 with no entry:**

    getCondAct  getWhile  getModeSwitch  getChooseSwitch  getPushButtonPerMode  getChooseButton
    getRLShade  getStopShade  getAdjustFan  getSetHSM  getLULock  getOCValve*  getSetThermostat
    getSetMusicPlayer  getTone  getHTTPGet  getDisable  getStartStopZPoll  getDelayPerMode
    getCancelDelay

Several are everyday actions: Lock/Unlock, Set Thermostat, HSM, Open/Close shades, Music Player,
Simple Conditional Action, Repeat While, HTTP Get. Each reports unmapped and drags its rule out of
"covered" on any hub that uses it. None is used by Gordon's rules, which is why the report has
looked clean.

Seventeen further table keys are absent from every picker. **Do not delete them on that basis.**
At least `getElse`, `getElseIf` and `getEndIf` are genuinely stored by real rules; RM creates those
from dedicated buttons rather than the subtype menu, so the picker sweep under-reports the format
(7.7.1). The rest need checking individually before any removal.

**Next action:** map the 19, then verify the 17 one at a time against stored rules rather than
against the picker. **Deferred 2026-09-25** while resources go to the other project's milestone;
no effect on this hub, whose rules use none of the nineteen.

### 41. Consume the engine's identity contract: capabilityIdsHash, formerIds, retired

This app pins about seventy capability ids by string in `RM_CONSTRUCT_TO_HAI` and
`RM_LOCATION_EVENT_TO_HAI`. That is a dependency on another project's identifiers, taken without a
promise behind it, and it was raised as such on 2026-09-24. The engine has since published one.

What it now guarantees, and what this app should use when the engine's build is deployed:

- **`capabilityIdsHash`**, a new field in the `hai.am/1` feed beside `capabilitiesHash`. The
  existing hash moves when any content moves, including a description or an evidence date, so it
  cannot tell identity from wording. The new one moves **only** when the set of ids changes. That
  is the signal worth watching; `capabilitiesHash` is not.
- **`formerIds`** on a capability that replaced another, so a rename is followed with one lookup
  and no coordination.
- **`retired`**, mapping a withdrawn id to its replacement or to null. A published id that simply
  vanishes is a test failure on their side rather than a silent change here.
- **`docs/developer/api.md`**, "What a consumer may rely on": what is contract, what is content,
  what is explicitly not promised. Node and edge ids are hub-local and must not be pinned. The
  feed may gain fields, so unknown fields must be ignored rather than treated as errors.

**Not present on this hub yet.** Treat the field's absence as "older build", never as an error,
which is also what the published contract says to do.

**Next action:** when `capabilityIdsHash` appears on the hub, record it alongside the feed version
already cached, resolve a mapped id through `formerIds` / `retired` before reporting a construct
unmapped, and say plainly in the coverage panel when a pinned id has been retired rather than
silently reporting the construct as uncovered. Until then this is a watch item, not work in
progress.

### 42. Flow text says nothing about custom actions, colours or metering

`actionStep` renders `getDefinedAction` as the literal string `Run defined actions` and
`getSetColor` as `Set colour`. Neither carries the command, the device list, the parameters or the
metering, so the flow view is materially thinner than Rule Machine's own rendering of the same
action.

Rule 2112 is the case that makes it concrete. RM renders:

    initialize() on <eight speakers> meter 30000 ms

and the map renders `Run defined actions`. The staggering is the whole point of that rule, and a
reader of the map cannot see it.

Everything needed to render these properly was measured on 2026-09-24/25 and is in the storage
document: `cCmd.<n>` and `devices.<n>` for the command and targets (7.6), the `cpType<i>`/`cpVal<i>`
pairs read in ascending `i` with coercion by type (7.6), `meter.<n>` / `meterMillis.<n>` for the
spacing (7.1), and `color.<n>` with its mode-dependent companions for colour (7.3, 7.5).

Not a correctness defect: the app does not claim anything false, it just says less than it knows.
Grouped here with the `ctFade.<n>` gap, which is the same shape - a stored value with a rendered
consequence that the flow text does not mention.

**Next action:** render command, devices, parameters and metering for `getDefinedAction`, and the
colour mode with its values for `getSetColor`. Deferred 2026-09-25 while resources go to the other
project's milestone.

### 43. DONE 2026-09-26. Read the engine's rules from a published state file rather than its endpoint

Agreed with the engine's project 2026-09-25, not yet built on either side. The scan currently
fetches nodes, edges and flows from a live endpoint on the other app. That endpoint now assembles
each rule's document from a separate child app, so a feed build costs one cross-app call per rule.

The replacement: the engine writes `hai-am-state.json` to the hub's File Manager whenever its
content would change, and this app reads `/local/hai-am-state.json` during a scan. No app execution
in our read path, and no dependency on that app being responsive while we scan.

**The agreed shape, which this app must hold up its end of:**

| | |
| --- | --- |
| freshness | a single **content hash** covering documents, states and the paused set. Compare it with the one last read; unchanged means skip the parse |
| capabilities | **not in the file.** The scan strips them anyway, and two call sites read them live on purpose because a rating shown today must answer for the engine as it is today |
| completeness | whole set every write, roughly 80 KB. No incremental diff path |
| device names | ours. `mergeHaiFeed` keeps our own node where the scan already found it and only tags the engine, so a device rename cannot stale the file |
| **app node ids** | **must be `a<installedAppId>` of the app as the hub installs it.** `mergeHaiFeed` keys on node id: an id our scan already found is kept and tagged `engine: HAI`, an id it did not find is created from the feed. If the engine ever emits a logical rule id instead, every rule appears **twice** - once as a plain app from our scan, once as an HAI rule from the feed, with no edge between them and no error anywhere. Silent duplication, not a failure. The engine is pinning this with a test that asserts the installed id and rejects the logical one |
| pause lag | immediate on the submission path, otherwise one watchdog tick, about 60 seconds worst case |

**One signal deliberately, not three.** A `revision` and a `statusEpoch` were offered alongside the
hash and declined: two companion values describing the same content as the hash can drift from it,
which is the failure documented in 5.6.1 and 7.7 kind 4, and this app has no path that could use
"only the status changed" because the graph is rebuilt whole every scan. If that changes, ask again;
adding a field later costs nothing, because the published contract already requires consumers to
ignore unknown fields.

**Why it is worth doing at all**, stated accurately rather than as a latency win: the scan reads the
feed once per scan and automatic daily scanning is off on this hub, so this is a few reads a day,
not a few an hour. The real argument is hub memory. This hub emitted `lowMemory` five times in seven
days, down to 37,808 KB free at worst, and the change removes about 62 KB of permanent state from an
app that holds it whether or not anyone is looking.

**Verified from this side 2026-09-25**, rather than taking the producer's word: 42,564 bytes,
`contract: hai.am/1` which is the string `fetchHaiFeed` already gates on, `contentHash` and
`generatedAt` present, 67 nodes, 106 edges, 41 flows, and **no capability keys of any kind**. App
nodes carry `id`, `ruleId`, `label`, `group`, `engine`, `status`, `disabled`, `url`, which covers
every field `mergeHaiFeed` reads. One rule shows as paused and 26 as stopped-and-disabled, matching
the hub.

**Two integration details found by that check:**

`state: 'OK'` is set by our own wrapper rather than carried in the payload, so a file read passed
through the same normaliser works unchanged. Nothing to do.

**The cache key needs moving.** The file carries no `capabilitiesHash`, correctly, since it carries
no capabilities. But `haiFeedVersionForCache()` keys on `capabilitiesHash` and falls back to
`haiVersion`, which by our own note "does not move between builds". Sourcing the scan feed from the
file would empty that field and quietly demote the migration-ratings cache to a key that barely
changes, so a rating could survive a capability change that should have invalidated it. The fix is
to take the cache key from the live capabilities fetch the rating path already performs, not from
the scan-stored feed. **Do this in the same change, not after.**

**Do not start this until the engine's own situation resolves.** As of 2026-09-25 its ownership
change is deployed but inert: zero registry entries carry the derived index, so the parent still
holds every document *and* makes a child call wherever one is wanted. Its app is being throttled by
the hub, and a roll-back of all three of its apps is on the table. The sequencing that follows:

- **If it rolls back**, the slow endpoint path goes with it and this item stops being urgent.
- **If it fixes forward with a real backfill**, the endpoint's cost profile changes when the
  backfill lands, so the file read is worth doing *after* that rather than before.

Either way this is not work to start now, and it is not blocked on anyone: it is waiting for the
answer that decides which of the two it is.

**A condition of building this at all: "the file exists" must never mean "a producer is alive."**
The engine's writer only exists in the newer build. If that build is rolled back, uninstalled,
disabled, or restored from an older backup, `hai-am-state.json` stays on the hub at its last
content - present, readable, well-formed, and progressively wrong, with nothing inside it able to
say so. The content hash cannot show staleness once the producer is gone, because it describes the
file rather than the hub, and no field the producer writes can help for the same reason.

This is the stale capability list of 2026-09-24 in a form that cannot be fixed from the producer
side. The fix is here and it is nearly free: **the scan already enumerates every installed app on
the hub, so use the file only when that same scan also found the app that writes it.** Producer
absent, file ignored, fall through to the endpoint or to nothing, which is the behaviour a missing
file already gets. That covers rollback, uninstall, disable, and an old restore in one check, none
of which a field inside the file could ever have reported.

**A blind spot the content hash does not cover, found 2026-09-25 by reading the file.** A file that
was not rewritten because nothing changed, and a file that was not rewritten because **the write
failed**, are byte-identical and both look current. The producer's log carries
`hai-am-state.json not written: not canonicalisable: null` twice on the morning of 2026-09-25, as
warnings only - the file simply stayed as it was and nothing outside the log could tell. Had that
happened after a content change rather than before one, this app would have read stale data with
every check agreeing it was fresh.

The producer already had the field and we did not know its name: **`runtimeAmStateError` in the
engine runtime app's state**, set when a write fails and cleared when one succeeds. Absent means the
last write succeeded. It is deliberately not in the file, because a broken writer cannot update the
file to say it is broken.

**It is not authoritative yet.** The line that records it was written as
`"${e.class?.simpleName}: ${e.message}"`, and the Hubitat sandbox refuses `getClass()`, which
`.class` compiles to - so the statement whose only purpose was to make a failed write observable
would itself throw inside the catch block, taking the record and the log line with it. Fixed in the
producer's source as `e.toString()`, but the unsafe line is still live on Dev until their next
deploy lands. **Until then, absence of `runtimeAmStateError` proves nothing.**

**Do not start reading the file until that deploy has landed**, or this app is building on a
freshness signal whose failure indicator can itself fail.

**Gate on the writer, not on "an engine app exists."** Those are different questions, and only one
of them is the one worth asking: an estate can contain many apps of that family while the single
app that writes this file has been removed, and a family check would then hold the gate open in
exactly the case it exists to close. Identify the writer specifically.

This was first written on the premise that the engine's rules were about to become top-level apps
with the parent holding none of them. **That premise turned out to be wrong** - rules remain
children, with the parent relation kept as a factory mechanism only. The rule above does not depend
on it and is recorded here without it, because it holds under either arrangement.

A `producerRevision` field was offered and declined: it only means anything when compared against a
live endpoint, which is the very call the file exists to avoid, and it is a second value describing
the same thing as the hash.

**Keep the claims separate.** This file is a consumer optimisation for the scan's read path. It is
not evidence that the engine's parent-state ownership change worked, and the two must be measured
and reported separately. The temptation runs one way: the file is the visible, verifiable artefact
of the two, which makes it easy to let it stand in for the one that is neither.

**Built and verified on the Dev hub 2026-09-26, app code version 305.** What shipped, including
where it departed from the plan above:

- The scan reads `/local/hai-am-state.json` over loopback, gated on the engine's runtime app being
  found in the same scan, matched on the `HAI Runtime` type prefix so a channel rename cannot
  silently turn detection off.
- **The configured feed address is gone entirely, and so is the endpoint fallback.** That is a
  deliberate departure from the plan, on Gordon's instruction: the engine is a sibling app, so the
  integration should need no configuration, and the unusual case of running it elsewhere is not
  worth catering for. The removed setting held the other app's OAuth token in cleartext; cleared by
  `migrateRemoveHaiFeedSetting()`, and verified gone from the hub.
- The decoder line on the main page now reads "Experimental HAI Rule Engine (not installed)" when
  the engine is absent.
- **The capability list is joined on from the second file the engine publishes**,
  `hai-am-capabilities.json`, a bare array on build cadence. Its bytes are hashed here and compared
  against the `capabilitiesHash` the state file declares, rather than trusted: a deploy that moves
  the hash while the file upload fails leaves a copy that is present, readable and stale, which no
  missing-file check catches. A declared hash that is absent means an older engine build and the
  list is used unverified; a hash that disagrees means the pair is not current and neither is served.

**Three consumers broke on the way, and only one was noticed without re-reading this item.** The
coverage report, the webCoRE migration ratings, and the ratings cache key all read the capability
list through the scan feed. The cache-key demotion is the one this item had already predicted in
writing, under "the cache key needs moving", with the instruction to do it in the same change. That
prediction existed and was not re-read at the moment it applied, which is worse than not having
written it down. All three are fixed by one change at the source: `fetchHaiFeed()` joins the
capability list on, and the scan keeps stripping it while retaining the hash beside it.

Verified after the fix: the coverage report returns a full report with hub statistics rather than
the published fallback, and the stored feed carries `capabilitiesHash` matching the hash computed
independently from the file's bytes, with `capabilityCount` 156.

**Superseded from the plan above:** the content-hash skip-the-parse optimisation was not built, the
graph is still rebuilt whole every scan, and `runtimeAmStateError` is not read. None were needed to
land the transport change and each can be taken on its own merits.

**Next action when that settles:** read the file in the scan path **gated on the engine app being
found in the same scan**, with the endpoint as the fallback, move the ratings cache key off the
scan-stored feed in the same change, and treat a missing file as "older build" rather than an
error - the same rule as item 41.

### 44. On a phone the only visible action is Scan, and the map link is below the fold

Seen 2026-09-25 on a phone through Remote Admin. Opening the app shows the summary paragraph, the
flow-decoding sentence and any warning text, and the viewport ends around there. **"View Automation
Map" sits below the fold and "Scan relationships now" is the only action a user can see**, so the
natural thing to press is Scan.

That is the wrong default in both directions. A scan is the expensive operation - roughly 52
seconds, a full pass over every app and device, and load on a hub that has hit 37,808 KB free -
and the map is what the person actually came for. The existing data is almost always good enough
to open, and if it is not, the page already says when the last scan ran.

Worse on the remote path specifically, which is where this was seen: off the hub's own origin the
page cannot poll `/scan-status` (item 33), so a scan started from a phone gives the least feedback
of any route.

**Next action:** put the map link above the scan button in the rendered order, or make the map the
primary action and the scan a secondary one. Worth checking the whole page at phone width while in
there rather than fixing the one control - the same warning text that pushed the button down will
push other things down on a narrower screen.

### 45. The engine feed's status word is discarded for every rule the scan already found

**Confirmed live on 2026-09-26, and the code fix is written but not yet on the hub.**

`mergeHaiFeed` treats a hub-scanned node as authoritative and returned as soon as it had tagged the
node with its engine, before the branch that reads the feed's `status`. That early return is right
for the label and the relationships, which the hub reads directly. It was wrong for `status`,
because the scan derives paused from an `appState` entry named `paused`, which is Rule Machine's own
convention, while that engine publishes the same fact only in its feed.

Every rule of that engine is an ordinary child app, so the scan always found it and the feed's
status was therefore never consulted for any of them.

**Verified, not inferred.** The engine's rule apps expose exactly three `appState` rows, `editorLink`,
`rt` and `rtFence`. There is no `paused` row on any of them, so the scan's derivation returned
nothing every time. One of the three rules on the hub was genuinely paused while the map drew it as
running. The only reason a reader saw the truth at all is that the engine writes a literal
`(Paused)` into its own app label, with no surrounding span, so the label cleaner leaves the word in
the title. That is an accident of labelling, not the flag working.

**The fix, applied in source.** Set `disabled`, `paused` and `inactive` from the feed inside the
existing-node branch, before the early return. Flags only. The label, draw text and title are left
exactly as the scan built them, because the engine already puts the status word in its own label and
appending a second suffix reproduces the visible duplicate that review 374 removed for Rule Machine.
`stopped` sets `inactive` alone, matching what `inactive` already means everywhere else, "not
running".

**Trap to avoid if anyone is tempted to read the engine's state directly instead.** Its `state.rt`
carries a top-level `pausedRules` key. That is not the rule's own status. It is the set of Rule
Machine rule ids that rule is watching, for a rule-paused trigger. Reading it because the name
matches gives you someone else's pause flag, and on a rule watching nothing it reads empty and looks
like a confident "not paused". The rule's own flag lives at `rt.world.rules[rt.ruleId].paused`, and
even that is a cross-check only. The feed's `status` field is the value the engine intends us to
consume, and reaching into its internal blob would break the first time it reshapes it, silently.

**Verified on the Dev hub, 2026-09-26.** App code version 300, scan at 07:25 in 51 seconds, "Last
scan read 3 rules from the feed". Read out of the rendered graph: `a3453` carries `paused: true` and
`inactive: true`, and the two active rules carry neither key. Before the deploy `a3453` carried
neither. The drawn text reads `[HAI] GT HAI Rule 1 (Paused) (HAI Rule (Dev))`, with the word once,
confirming that taking the flag without the status word was the right split.

Two unrelated confirmations fell out of the same scan: apps went 189 to 151 and nodes 495 to 457,
both exactly 38 fewer, matching the engine's removal of its 38 test fixtures.

### 46. WITHDRAWN. The engine's flow text does not go stale

**Raised and disproved the same day, 2026-09-26. Not a defect. Recorded because the reasoning that
raised it was wrong in a way worth remembering.**

The claim was that `buildGraph`'s fallback to the previously built graph's flow runs before
`mergeHaiFeed`, and the merge skips any id already present, so the engine's fresh flow text would be
skipped on every scan after the first.

**Disproved on the hub.** The engine built a throwaway probe rule, seeded it, then changed its
trigger value and action command so the step text differed while the device refs stayed identical.
Feed served `becomes off` / `Turn off`; the stored graph held `becomes on` / `Turn on`. After a scan
the map drew `becomes off` / `Turn off`, matching the live feed. A positive reading of a present
string, not an inference from a missing one.

**Why the reasoning was wrong.** Both halves of the path were read correctly. What was never checked
was whether the input to that branch can be non-empty when it matters. `startScan` sets
`state.graph = null` before a scan begins, so `priorFlows` is always empty during one. The fallback
only fires on a rebuild, and a rebuild does not re-fetch the feed either, so the retained flow and
the retained feed agree by construction.

That null is the drop-not-hold memory hardening from 2026-08-13, which stopped the app holding two
graph copies after that pattern crashed a 74-app hub. A decision taken for peak memory is what makes
this path correct, which is why reading the two obvious lines did not reveal it.

**Carry forward:** when a branch looks dangerous, check whether its guard can actually be true in
the case being worried about, before reporting it. Tracing the branch is not the same as establishing
its precondition.

### 47. PARTLY DONE 2026-09-26. The flowchart library is no longer fetched on every map view

**What was built and kept.** The flowchart library is 3.34 MB against the graph library's 652 KB and
most map views never open a flowchart, so it was being fetched on every view of a graph it is not
used to draw. It now loads on the first flowchart instead, and initialises once when it lands.
Measured on the Dev hub at app code version 311: absent on a map view, 80 ms to load and render on
demand. That saving is measured and stands on its own.

**What was built, deployed and then reverted, and why.** The libraries were also vendored into this
repository, delivered by a `files` array in the manifest and loaded from `/local/` with the CDN
behind them. That was reverted at Gordon's instruction on the same day, and the two files were
removed from the hub.

The reason is worth keeping, because the mistake is the point. It was justified by a
"Could not load the drawing libraries" failure seen twice, once by Gordon and once by this session.
**That failure was never reproduced, never instrumented, and never characterised.** The obvious
control, loading the production app and comparing, was run by Gordon rather than by this session,
and it showed production and dev emit byte-identical script tags, URLs and SRI hashes. So nothing
about dev caused it, and there was no established defect for the change to fix.

Local hosting may still be a reasonable product choice: a local-first tool arguably should not need
the internet to draw its main view. But that is a decision to take deliberately on its merits, not
a repair to justify with an undiagnosed fault.

**Ruled out along the way, and still true:** the URLs return 200, and the SHA-384 of each file as
served matches the `integrity` attribute exactly. Whatever the two failures were, they were not a
wrong URL, a wrong version or a stale hash.

**A separate finding, unrelated to the libraries and not caused by any change here. The map page
cannot be fetched over Hubitat's cloud relay, on production or dev, and the cause is the page's own
size.**

**Two wrong explanations were published here before this one, and both are recorded because the
second was disproved by a test that was set up to confirm it.** The first said the map "is LAN-only
and has been", a historical claim with no evidence behind it; Gordon reports it worked remotely
before. The second said it was hub memory pressure. The hub was rebooted on 2026-09-26 specifically
to test that, with the stated prediction that cloud access would return. **It did not.**

**Measured on a settled hub with 233,924 KB free:**

| | | |
| --- | --- | --- |
| production map, LAN | 780,169 bytes | 4.0 s, 200 |
| dev map, LAN | 787,459 bytes | 4.7 s, 200 |
| production map, cloud | | 10.9 s, **504** |
| dev map, cloud | | 10.8 s, **504** |

LAN generation came back *faster* than before the reboot, 4.0 s against 4.9 s, while memory more than
doubled, and cloud still failed. Memory was not the cause.

**The relay measured against payload size:**

| | | |
| --- | --- | --- |
| 353 bytes | 4.3 s | 200 |
| 29,824 bytes | 5.8 s | 200 |
| 101,291 bytes | 5.4 s | 200 |
| 780,169 bytes | 10.8 s | **504** |

About 4.3 s of fixed relay overhead, 100 KB carried comfortably, and the map page over the budget.
Generation is only 4 s of it; the rest is transfer.

**Which explains the history without needing a hub event.** The page embeds the entire graph, so it
grew with the hub. It crossed the relay's limit at some point and stayed over. Nothing broke on a
particular day and nothing will restore it, including a restart.

**Next action:** serve the map as a small page shell that fetches the graph as a separate request.
That puts it back under the limit with margin, and the margin survives the hub growing further.
The same change also reduces the amount of work a map view costs the hub. Note that the graph
fetch itself would then be ~700 KB over the relay and would need the same treatment or pagination,
so this is a design task rather than a one-line move.

### 24. Variable usage Automation Map cannot decode

Two scopes remain from thebearmay's original community feedback. The webCoRE half is closed: piston
locals are owner-scoped nodes, `@@` Hub Variable use is decoded with proven read/write direction, and
pistons carry device relationships and a drawn flow. The investigation behind that, including why the
platform's own Hub Variables registry is not a usable data source, is in Hold/closed.

- **webCoRE globals (`@`).** A dynamic table with hashed names, still undecoded. Distinct from `@@`,
  which the Hubitat port maps to Hub Variables and which is already handled.
- **Dashboard Hub Variable usage.** Dashboard's device references were a separate item and are no
  longer tracked; its Hub Variable usage has never been decoded.

**Next action:** decide whether either is worth pursuing. Neither has a proven read-only source yet,
and absence from decoded configuration still cannot prove non-use where a reference is built
dynamically.

### 25. webCoRE decode coverage: account for the whole piston, not just the parts we read

**The original gap, now closed.** The webCoRE decoder was a targeted extractor: it hunted three
specific shapes and silently discarded everything else, so absence of a relationship had two
indistinguishable causes, the piston genuinely not having one or the decoder never having looked.
Phase A set out to make a piston account for itself, and it did. The accounting invariant, the
separation of `visited` from `identified`, the source-pinned construct registry with version-drift
reporting, per-construct evidence levels, on-demand per-piston running, and the structure-and-paths
privacy boundary are all built and gated; the detail is in Hold/closed.

**Still in force.** No translation to Rule Machine or Visual Rule Builder, no destination
recommendation, no judgement that a piston is simple or safe to convert, no write path of any kind.
Automation Map continues to describe and never to change the hub.

**What the answer turned out to be.** The open question was whether fuller webCoRE decoding was
practical at all, or whether permanent partial coverage with explicit gaps was the truthful end
state. It proved practical: pistons now draw a flow with real condition text, task parameters and
device roles. Partial coverage remains, but it is now a named and shrinking list rather than an
unbounded unknown.

**Status: the coverage work itself is delivered.** The construct registry, census walker,
read-only endpoint, coverage card, statement and operand evidence ladders, the drawn piston flow
and its condition text all shipped on dev through v2.3.0; that record is in Hold/closed. What is
left below is the part that is genuinely still open.

- **Piston option keys are unidentified.** A test piston's root options map saved `mps`, `pep`,
  `dco`, `des`, `aps` and `ish` alongside the allowlisted `cto` and `ced`. They sit outside every
  statement, so they do not affect structural validity, but any piston that saves them reads
  "Coverage incomplete". Each needs tracing to the executor's piston-option reads before it is
  allowlisted.

- **Statement evidence gaps to close.** The twelve statement families are structural (L3), but an
  occurrence that takes a saved branch without a matching editor save and reload in the fixtures is
  held at identified (L2). Open: `sm` on a statement; an absent `tcp`, which the editor can produce
  but the capture matrix does not yet contain; a task carrying `cm` or `a`; a `for` without `x`; and a
  group's retained or unconsumed
  `wd`, and `wt` of `l` or `n`. Nodes
  saved before the piston was first reopened (`$`, `ct` or `s` absent) cannot be shown after a reload
  and stay held by design.

- **Found during that work, not yet actioned.** Two items. First, nothing actually verifies
  `operand-l3.groovy`'s `sourceAssertions`: repo-wide, only `tests/webcore-l3-manifest.groovy` reads
  them and it loads the *statement* manifest, so every operand citation is currently unchecked despite a
  comment in that file claiming otherwise. Second, `wc.task-parameter.unselected` now has a clean,
  inert, round-tripped capture, but promoting it needs generator work rather than a data addition:
  structural forms are emitted with a hardcoded `level: 'L2'` and consult no evidence manifest, and the
  operand gate's own id pattern does not match `wc.task-parameter.*`.

- **Still to come.** Operand semantic (L4) meaning: what a comparison actually *means*, as opposed to
  the saved spelling the chart now transcribes. Transcription already gave the user the readable
  sentence, so this rung is no longer the visible win it was described as here; it is what would let
  the app reason about a condition rather than only print it. Then the runtime walker using the
  raised operand levels for anything at all, the rest of L4 (remaining action semantics), and
  webCoRE's own display templates (`"Wait {0}"`, `Send notification "{0}"`) to replace raw command
  names with worded labels, which is roughly a hundred entries to transcribe.

The binding constraint remains fixture diversity: the dev hub has six pistons, which cannot establish
real-world coverage, so any broad claim needs a sanitized opt-in corpus first. Related to item 24,
which covers webCoRE variable usage specifically.

### 26. Contested devices: surface shared trigger sources instead of asking the user to cross-reference

**The gap.** The contested-device finding lists every automation that can leave a device in a lasting
state, then says: *"Check whether their triggers can overlap and which automation should win when
they do."* The first half of that is work the app already holds the data to do. `trigger` edges
(app to device) are on the graph for every app with decoded triggers, so shared trigger sources
between the controlling apps are a straight derivation, not new information.

**Evidence, from a real scan on the dev hub.** One device had 10 controlling automations. Nine
distinct trigger sources across them, except that **four shared a single trigger device**, and those
four were near-duplicates (an import, a second import, and a clone of the same rule) all firing from
the same source onto the same light. That is the actionable signal, and it was invisible under a flat
list of ten names that the user was asked to cross-reference by hand.

**Proposed change.** Group the controlling apps by shared trigger source and surface the clusters,
leaving "which should win" as the question it genuinely is.

**The honesty constraint, which cuts both ways and shapes the wording:**

- A shared trigger device is positive evidence that two automations **read the same source device**.
  It is NOT evidence that they can fire from the same event: they may subscribe to different
  attributes or event predicates, and a constraint may stop either acting. The safe result is
  "shared trigger-source device detected", explicitly not "trigger overlap computed". Raising that
  to overlap needs per-attribute or predicate evidence the graph does not currently carry.
- Not sharing one **proves nothing**. Time, mode, variable and rule-invoked triggers produce no
  device edge at all, so "these cannot overlap" would present a decoding gap as proven emptiness. The
  finding must surface the positive signal and stay explicitly silent on the negative.
- An app with no decoded trigger at all is a **third state**, undetermined, not absent. In the sample
  above one Basic Rule fell in this category and must be reported as such rather than folded into
  either group.

**Scope.** A derivation over existing `trigger` edges plus a rewrite of the one guidance string. No
new scan work, no new decoding, no schema change.

**Status.** Not started, not authorized. Held in Next rather than Now: the headline claim is
stronger than the evidence until the graph distinguishes attributes and predicates. Behaviour confirmed against a real export before writing this
entry; the underlying edges are already present and sufficient.

### 5. Add runtime activity and performance context

Users want help finding automations that may contribute to hub load, but configuration structure is
not execution evidence.

**Next action:** define a conservative metric model using available app statistics and timestamps,
label observations as evidence rather than conclusions, and prototype a ranked diagnostic view.

### 6. Expand a focused map one hop at a time

Allow users to reveal immediate neighbours without returning to the full graph.

**Next action:** specify one-hop expansion, duplicate suppression, reset behaviour and visible
provenance. This replaces the overlapping multi-select and extend-map backlog requests.

### 7. Export and import configured app data for migration

Provide a safe, portable representation of user-maintained settings such as external-system
overrides and icon choices.

**Next action:** define a versioned schema, conflict rules, preview step and validation behaviour.
Never import scan results or secrets as configuration.

### 10. Live Hubitat platform update check

The existing "Hubitat release activity" panel only shows historical Community Utilities/Hubitat
release data; it never tells the user whether their own hub currently has an update available.

Investigated two approaches on 2026-08-27. Hubitat's own live update-check
(`/hub/cloud/checkForUpdate`) is real and confirmed working - a status read via the hub-rules MCP
server reported `UPDATE_AVAILABLE` (2.5.1.172 -> 2.5.1.174) with version, release-notes URL and beta
flag, and a second call actually triggered the install (download, apply, reboot). But that path is
only reachable from outside the app sandbox (via the MCP server's admin access), not from a Hubitat
app's own code, and it bundles the check together with the install - there is no way to ask "is one
available" without also committing to install if the answer is yes.

Better direction found the same day: `HPM_Manifest_Crawl`'s own feature-tracker dataset
(`site/feature-tracker/data/hubitat_release_features.json`, publicly fetchable, confirmed live)
already tracks every Hubitat release with a `version` and `releasedAt`. A sandboxed app can safely
read `location.hub.firmwareVersionString` (standard, documented) and compare it against that
dataset's latest entry - no undocumented endpoint, no admin access, no risk. The limit: this is a
scheduled crawl of the community forum, not a live Hubitat query, so it lags real releases by up to
one crawl cycle - confirmed directly, since at the moment 2.5.1.174 was installing on Gordon's hub,
the dataset's last harvest (2026-08-26) still only knew about 2.5.1.172.

**Next action:** publish a small derived `latest.json` (`{version, releasedAt}`) from the
`HPM_Manifest_Crawl` pipeline instead of shipping the full ~4 MB dataset to a Hubitat app, fetch it
from Automation Map, compare against `location.hub.firmwareVersionString`, and label the result
honestly as "latest known as of `releasedAt`" rather than "latest available" so the crawl lag stays
visible. Report only - never trigger an install from within Automation Map itself.

### 30. The graph is rebuilt a second time after most scans

**The symptom.** Every completed scan on the dev hub is followed within a second by this pair in the
log, at 07:20, 07:53, 13:37, 15:24 and 16:25 on 2026-09-12 alone:

```
clearing resurrected scan flags for an already-completed generation
state.graph was missing after a completed scan - rebuilding from existing scan data
```

**What is established.** Both lines come from the same execution, a render of the main page:
`clearAbandonedScan()` is called at line 513 and `selfHealGraphIfNeeded()` at line 536 of the same
method. `startScan()` deliberately sets `state.graph = null` to free memory, `finishScan()` commits
the rebuilt graph, and the self-heal fires when a page render sees a null graph alongside a non-null
`atomicState.graphVersion`. At 16:25 the scan committed at `.264`, the flag clear logged at `.293`,
and the self-heal at `1.007`, so the render was working from a state snapshot taken before the
commit landed.

**The cost.** The map itself is correct, because the self-heal rebuilds from the same `appInfo`. The
waste is a second full `buildGraph()` across 143 apps and 221 devices immediately after the scan
already built one, plus two warnings that read as faults when they are a mitigation working.

**Answered on the hub, 2026-09-12 16:57.** The `lockVsState()` trace added for this settled it in one
scan. The scan completed at `22.594`, and at `25.003` a page render logged
`graph=false appInfo=143 appResultsReady=true graphVersion=15`. That combination is only reachable
from a snapshot taken between the app-phase commit and the graph commit, and it was logged 2.4
seconds AFTER completion. So the racing execution is a page render that STARTED before `finishScan`
committed and ENDED after it. Its own end-of-run write-back nulls the graph, and the self-heal in the
same execution rebuilds it.

**Why it is not simply fixed.** Hubitat writes the whole state snapshot back when an execution
returns (see the comment at the app-phase commit), so a stale render cannot be stopped from
clobbering `state.graph` from inside that render. Rebuilding is the correct response, not a
workaround. Avoiding it entirely would mean moving the graph out of `state`, which the scan-start
comment rejects on measured peak-memory grounds, so that is a real design change and not a tidy-up.

**What was changed instead.** The mitigation no longer reports itself as a fault. Every graph commit
now writes a paired marker, `state.graphCommittedAtLocal` alongside `atomicState.graphCommittedAt`,
with the same value. `atomicState` commits on every write and cannot go stale, which is the same
property `shouldAutoScan()` already relies on, so the two disagreeing is proof of a stale snapshot
rather than an inference from timing. The self-heal logs at info when staleness is proven and keeps
its warning otherwise, because a graph missing for any other reason still deserves one. The
`clearAbandonedScan` flag-clear line before it is also info (2026-09-13): it only runs once the
generation's terminal tombstone proves the scan finished. The pair is
deliberately not overloaded onto `state.scanHeartbeat`, which feeds `clearAbandonedScan`'s
90-second freshness check.

**Still open.** The second full rebuild per scan remains, and is inherent to the platform's snapshot
semantics rather than to this app's logic. Closing it needs the graph held somewhere a stale snapshot
cannot overwrite.

**Not a regression.** Pre-existing, and unrelated to the v2.3.0 webCoRE work: it fired at 07:20 and
07:53, before any of that day's changes were deployed. The source comments date the underlying race
to 2026-08-30, and `selfHealGraphIfNeeded()` was written for it as a recovery, not a cure.

**Worth weighing before fixing.** The self-heal is doing its job and the user sees a correct map, so
the case for touching a known-delicate scan lifecycle is efficiency and log honesty, not correctness.
Both lines now log at info in this case, so a normal scan shows no WARN.

## Hold / closed

- **A dead constraint on a device that also has a live relationship (item 31).** Closed on dev
  for 2.3.3. A constraint edge whose condition nothing evaluates now draws dotted and thin in
  the same constraint colour, with its own legend row, so the case a node tag cannot state (a
  device holding both a dead constraint and a live relationship) is visible on the edge itself.
  The export already carried it as `unusedConstraint` from 2.3.2.

  The hub's own `inUseConds` / `unusedConds` lists were assessed as a replacement for the
  detection and rejected: they agree with a reconstruction from `eval` plus action references
  on only 27 of 66 rules, they list a condition used solely by the Required Expression as
  unused (rules 1809, 2100), and on rule 3080 the same condition ids appear in BOTH lists.
  Recorded in the storage-format document as meaning unknown.

- **Variable-sourced Set Variable actions (item 34).** Closed on dev for 2.3.3. A numeric
  target names its source in `numOp.<n>`, so the device-attribute source is now read from
  either field, `variable math` emits read references for its operand variables (`xVar3` /
  `xVar4`, skipping the `(constant)` placeholder) under the new usage role `value-source`, and
  `add number` records the read of the target's own value. Flow labels print the arithmetic.
  A plain `variable` copy was then created as fixture rule 3356 (`numOp.1 = variable`, source in
  `xVar3.1`) and is decoded the same way. The String-target copy's stored form is still unknown.

- **webCoRE source and description inconsistencies (item 32).** Closed on dev for 2.3.3. The
  registry generator now reads the `graphsOn()` block of `virtualCommands()`, so `clearFuelStream`,
  `readFuelStream` and `writeFuelStream` are declared and current (67 declared commands). The flow
  chart now draws a switch default branch, read from the switch's `e` list as the executor does, and
  the export description and spec say the same. Case values remain undecoded.

- **webCoRE decode coverage, the delivered part (item 25).** Shipped on dev in reviewed
  increments through v2.3.0. Kept in full because each entry records what was proven and how.

  <details>
  <summary>Item 25 delivered detail</summary>

- **Construct registry (done).** A registry of 279 constructs generated from a pinned webCoRE source
  commit, with per-region evidence hashes, gates that refuse to emit on a provenance or membership
  failure, and a determinism test proving the checked-in file is byte-identical to a fresh
  generation. The app ships a projection of it carrying construct identity and evidence level only.

- **Census walker (done).** A pure traversal with five independent counters balanced against an
  independent oracle, context-sensitive classification that never steers traversal, safe paths from
  a reviewed key allowlist, fixed reason codes, and deterministic depth, value, path-length and
  retained-list bounds.

- **Read-only endpoint (v2.2.9).** One authenticated route that runs the census for a single piston
  on request. It refuses while a scan is active, accepts only an ID this app has already scanned
  whose type is a webCoRE piston, permits one operation per piston at a time, bounds the request,
  the loopback and the analysis with fixed time limits, and builds its response field by field from
  an explicit allowlist at every level.

- **Decode coverage card (v2.2.9, collapsed on dev).** A card in the focused panel of a webCoRE
  piston. It began as a construct table behind a Check coverage button. Once the flow chart started
  drawing anything it could not decode as a visible block, the table no longer told the user
  anything the map was not already showing, so the card was cut to a single line: the check runs on
  selection and the card stays hidden unless the decoder meets a field it has never seen, which is a
  webCoRE version saving something new rather than anything a person can act on. Outcomes that are
  not a completed walk say nothing.

- **No cache.** The endpoint applies fixed traversal, output and time bounds. Measured on the dev hub,
  results were under 1KB and returned within a second, so a cache would add hub state for no
  meaningful saving there; that is observed evidence, not a guarantee for every hub.

- **Custom command tasks (fixed on dev).** The editor saves `cm: true` on a task with a custom
  command. `cm` was missing from the walker's key allowlist, so such a task reported an unidentified
  key; it is now allowlisted after tracing it through the editor serializer.

- **Semantic evidence (L4), on hub dev v2.3.0.** The evidence ladder records what saved statements
  mean, separately from structure: the order in which an if tests its branches, condition negation, `or`
  groups, followed-by groups kept opaque, `do` as a sequential block, the default statement settings,
  switch case order and its case-traversal policy, `while` as a pre-condition loop, `repeat` as a
  post-condition loop that stops once its condition becomes true, `for`/`each` as step/device
  iteration, a break scoped to its nearest switch or loop, `on` as any-event matching, `every` as
  own-timer-only and always ending the piston's execution pass, and the tep/tsp/tcp task policy
  vocabularies. Exit is a whole-piston terminate. An action's saved device list is now proven too:
  expanded once and shared by every task, distinguishing a static device target from the dynamic
  `$currentEventDevice` sentinel. Everything else is an explicit gap per occurrence, so no current
  piston is yet reported as fully explained.

- **Task order proven.** The `zz-L3-09 tasks` piston was recaptured with a valid first-save/round-trip
  lineage after the original round trip's only save was found unusable. A saved action runs its task
  list `k` sequentially in saved list order, one task at a time, stopping the remaining tasks early
  only when a task fails during a normal (non-fast-forward) run (`statement.action.task-order.v1`).
  This claim, together with the earlier device-list target claim, means all twelve registered
  statement types now have at least one proven L4 claim. Fast-forward resumption's effect on the
  break-on-failure behaviour is a new, separate, explicit gap
  (`statement.action.fast-forward-unresolved`); an action with one task or fewer still carries
  `statement.action.task-order-unresolved`, since no capture exercises order for it.

- **Operand structural (L3) evidence, eleven of twelve, wired into the registry.** Constant, virtual
  (mode/HSM/etc. reads), variable (Hub/global/local references), expression, physical-device (a device
  attribute read), preset (a named time-of-day value), a bare device-list operand, an argument operand,
  and all three event-match forms (virtual, physical and variable - the operand inside an `on`
  statement's own trigger list, saved like the ordinary operand of the same kind but read by a
  separate, simpler consumer) all have a reviewed, source-cited shape and a gate proving it against
  every occurrence in the fixture corpus. Four new test pistons were built and captured directly (not
  delegated) to close the remaining kinds: an `if` condition using the Argument operand type
  (`zz-L3-13`), a Device-typed piston-local variable (`zz-L3-14`), a physical-device `on` trigger
  (`zz-L3-15`) and a variable-change `on` trigger (`zz-L3-16`) - each built paused, saved twice
  (first-save and an unchanged round-trip) and verified inert before capture. A real registry gap
  surfaced along the way and was fixed: the census walker's flat allowlist of recognised field names
  (`webcoreCensusSchemaKeys()`) was missing `u`, so every argument operand's own value key read as an
  unrecognised field even though the construct itself was registered; `saved-position-map.md`'s
  allowlist section is updated to match. As of 2026-09-12 all eleven proven kinds are promoted to L3 in
  the construct registry itself, through the same committed-metadata promotion gate the statement
  manifest already used. The twelfth kind stays L2, and as of 2026-09-12 that is a settled finding
  rather than an open gap: a direct capture (`zz-L3-18`, a `Make a web request` task with its three
  optional parameters left untouched, paused throughout) showed the editor holds an empty-string `t` in
  memory and renders it as "(no value set)", but the empty string never reaches storage - both the first
  save and the round trip stored those parameters with no `t` key at all. That is a different registered
  construct (`wc.task-parameter.unselected`), not `wc.operand.empty`. The empty operand is therefore
  source-proven, since the executor carries a real dispatch case for it, but not editor-producible.

- **webCoRE piston flow now draws (dev, 2026-09-12).** A piston decodes into the same step list
  `mermaidFor()` already renders for Rule Machine, Notifier and Visual Rule Builder 2.0, so it draws
  through the existing rendering path rather than a new one, and `showFlow` needed no change because it
  gates only on a step list existing. Statement order and branch structure only: a condition is emitted
  as an explicitly undecoded step rather than invented comparison text, an unrecognised statement
  becomes a visible not-decoded block rather than being dropped, and a switch default is not drawn at
  all because where its body is stored is unproven. Device tokens stay unresolved until graph assembly,
  the first point the owning parent's hash index exists, reusing the existing never-guess resolver.
  Verified by lifting the builder out of the hub's own deployed source and running it against committed
  captures: the conditional fixture yields a full if/elseif/elseif/else/endif chain, the switch fixture
  an ordered case chain, the events fixture its two triggers. **Confirmed on the dev hub**: a scan took
  `graph.flows` from 68 entries with no piston among them to 93 entries including all 25 pistons, in 38
  seconds, with no errors logged. Device-token resolution - the one path unit tests cannot reach, since
  it runs only inside buildGraph - resolved correctly to real names (`setColor` on Gordon Study Desk, a
  three-device toggle, a switch trigger on _Test Switch) with no unresolved markers. Pistons whose `if`
  has an empty saved body correctly draw as a decision with no branch content rather than inventing one.

- **Conditions now read as text (dev, 2026-09-12).** A decision that said `2 conditions not decoded` now
  reads `Entrance Hall Motion Sensor's motion changes and Patio Door's contact is closed` - the same
  wording the piston editor shows. Nothing new had to be proven: the physical operand already carried its
  attribute and device tokens, the constant operand its value, and the comparison its own stored
  spelling, so this transcribes rather than interprets (underscores spaced, no operator meaning claimed -
  the same basis on which a task transcribes its own saved command and parameters). Composed during graph
  assembly, because a device name only exists once the owning parent index does and `mermaidFor` does not
  append a device list to a diamond. Anything that cannot be named in full - an unresolved device, an
  operand kind with no transcription, a group too deeply nested to follow - collapses back to the
  undecoded fallback rather than printing half a sentence. Sanitised fixtures cannot cover this (the sanitiser placeholders
  operator and joiner strings), so it is covered by synthetic tests plus live hub verification.

  </details>

- **Release gate (closed 2026-09-03).** Steve retested the original missing component-device scenario
  on his own hub: Hubitat reported 351 devices and Automation Map matched it at 351. He specifically
  checked Aqara, Bond, Harmony and Shelly devices, confirmed all were present, and described the
  release as "spot on" with nothing else discovered on his end. That defect, along with everything
  else accumulated on `dev` through 2026-09-02, shipped to production as v2.2.0 (2026-09-03); see
  item 16 below for the release path. This was still sitting at the top of the file as an open gate
  long after it closed, which is what moved it here.
- **Screen audit on the dev hub (item 29, closed).** Accepted in review and verified across dev hub
  revisions 143 to 177. Every finding from the 2026-09-10 audit is fixed except the narrow-window
  check, which is carried forward as its own item in Now. The full fixed list, including the
  2026-09-12 flow and map work, is preserved below.

  <details>
  <summary>Item 29 detail</summary>

Found by stepping through every Focus entry type, Insights, the large panels, the full legend and all
twelve Show filters on dev hub revision 136, measured in the browser.

**Closed.** Accepted in review and verified on dev hub revision 143 (commits `c7b6aaa` to `ce97021`):

- **A.** A size chosen with the resize grip was kept for every later item, so the panel's right edge
  overhung the legend. A new item now starts at the default size and position.
- **B.** Insights showed the previous item's rule variables card beneath its own content.
- **C.** The Hubitat releases, External systems, Pivot tables and Device icons panels had no bottom
  bar, unlike the normal flow view.
- **D.** "webCoRE variable use only" drew an empty map. It matched only direction-unknown references,
  of which there were none, while real variable reads and writes had no filter. Now "Variable use
  only" keeps reads, writes and direction-unknown use from every engine; "Variable connectors only"
  keeps Hub Variable connector synchronisation, which is not use; the device filter is "webCoRE
  device state reads only". webCoRE commands stay under Actions only.
- **E.** Every Local Variable label named its owner twice, and the unused Local Variable panel title
  named it twice more and added "(Local Variable)". One builder now serves the dropdowns, Quick
  Search, canvas labels and the panel, each naming the owner once.
- **F.** App labels repeated the app type when the label is exactly the type name, for example
  "Tapo Integration (Tapo Integration)". Stripped only on an exact repeat.

- **G.** An external system picked from Quick Search was written into the Focus Device dropdown. It
  now has its own transient focus, used by currentFocus, the map filter, history, Back and Forward and
  Show all, with every Focus dropdown left at All. Its framing was wrong because inert nodes kept their
  whole-map shelf pins in narrowed views; those pins are now released.
- **H.** A Hub Variable and its connector device drew their labels on top of each other. A narrowed
  view's 1.5 second fallback switched physics off before the layout spread (measured 41px apart). The
  narrowed view now runs the layout to rest first (measured 362px). Every drawn view, including an app
  view laid out without physics, takes ownership of the canvas, so an older settle's listener or timer
  cannot shelve inert nodes into, reframe, or reveal a newer view.
- **Shelf line in narrowed views.** The "Inert Nodes" divider was drawn in every view, so it could
  cross a focused map. It is now drawn for the whole map only. The start-up Show all also no longer
  resets a focus picked before the first settle.
- **Zoomed flowchart.** When the flow panel was zoomed in, the chart's horizontal scrollbar sat at
  the bottom of the chart rather than the bottom of the panel, so wide content could only be scrolled
  sideways after scrolling to the end of the chart. While zoomed, the panel body now scrolls both ways.
- **Connector legend.** A Hub Variable focus drew the connector line with no legend row. It now has
  one in both the compact and the Full legend, in the colour the line is drawn.
- **Side panels over a pending flowchart.** Opening External systems, Device icons, Hubitat release
  activity, Pivot tables or the Full legend while a flowchart was still drawing let the old drawing land
  afterwards and reopen the flow panel. Opening any other panel now makes a pending drawing stale.
- **I.** The webCoRE parent panel drew an informational sentence in the red attention style, and its
  "holds 6 apps" heading repeated the title. Now ordinary text, and a heading already in the title is
  not repeated. Red stays for a partial or failed piston coverage.
- **The map contradicted the piston's own flowchart.** Every device a piston touched drew as one
  light-blue "Device read" line, so a motion sensor the flowchart drew as a purple trigger was blue on
  the map beside it. Rule Machine drew the same relationship purple in both, which made the piston look
  like the odd one out rather than the map looking undecided. A read reached through an `on` event or
  through a condition now takes the `trigger` or `constraint` kind that read was decoded in, reusing the
  kinds and colours Rule Machine already uses rather than inventing new ones. The classification is
  webCoRE's own: which of its two comparison blocks the operator belongs to. `deviceRead` survives for a
  read that genuinely could not be attributed, such as one inside an expression or a task parameter, and
  its legend row now says so instead of claiming no role is ever decoded. Caveat worth remembering: a
  saved `ct` can be stale (see the structure doc), and both the flowchart and this now prefer it over the
  operator name, so they are at least consistent with each other.
- **The flow panel kept the previous app's zoom.** Picking a new app cleared a chosen size and position
  but not the zoom, so a chart still scaled from the last app read as a panel that had not gone back to
  its default size. Zoom now follows size and position. This reverses a previously tested decision that
  the zoom was held for the whole page session; reopening the same app still keeps it.
- **Nested condition groups composed instead of collapsing.** A group was marked opaque, and one opaque
  part collapsed the entire condition, so a single grouped clause turned an otherwise readable decision
  into `condition not decoded` along with every other clause beside it. A group is now composed as a
  bracketed sub-sentence joined by its own saved operator, reading as
  `(mode is Home or mode is Away) and phase is Night`. Groups nest, bounded at six levels; a group the
  decoder can read nothing out of, or one past that bound, is still opaque, and the all-or-nothing rule
  still holds inside a group, so an unnamed device there falls the whole label back. Device name
  resolution had to become recursive to match, or every grouped device would have gone unnamed and
  defeated the change.
- **The decision diamond was an off-palette blue.** Gordon spotted the last of the chart-versus-map
  colour mismatches in his own screenshot: Patio Door drew teal on the map as a Constraint while its
  diamond in the chart was blue. The diamond used `#4aa3c7`, which appears nowhere in the map's
  colour table. It now uses the map's own constraint teal, so a condition is one colour in both
  views. A required expression shares that colour, which is correct: the map gives both a single
  colour and the shape is what tells them apart. Rule Machine charts get the same fix, since they
  draw the same diamond.
- **Tasks showed a bare command name.** A task drew as `setVariable` with nothing about what it set.
  It now transcribes its saved parameters beside the command, reading as
  `setVariable(localCounter, @@AMGateA_NumShared)`, reusing the operand transcription conditions
  already use. The same all-or-nothing rule applies: a task holding a parameter kind with no
  transcription, such as a device selection, shows its bare command rather than a list with silent
  holes in it. webCoRE's own display templates (`"Wait {0}"`, `Send notification "{0}"`) would give
  properly worded labels instead of raw command names, but that is roughly a hundred entries to
  transcribe and is not done.
- **Schema bumped to graph 15, export 13** (on Gordon's explicit approval). The device-read role is
  decided during the decode pass and stored with the read, so a cached schema-14 graph holds no roles
  at all and every read in it falls back to `deviceRead`. Without the bump the app treats that cache
  as current and never prompts for the rescan that fixes it, which is the same reasoning recorded for
  the 13 to 14 bump. The export contract moved too: `attribute` now rides `trigger` and `constraint`
  edges, and `deviceRead` means something narrower, so a consumer counting piston device
  relationships must read all three kinds.
- **Diagnostic logging covered failures but nothing else.** Gordon recalled more being logged in an
  earlier version and was right: `dc7f0dc` removed 365 lines of AM-TRACE instrumentation once the
  investigation it served closed, including named trace points at `display.lock-vs-state` and the
  C0/C1/C2 recovery decisions. The failure logging left behind is genuinely good, 34 unconditional
  warnings naming the failing app or device, the exception, and the invariant state. What was gone
  was everything about a scan that did NOT fail: no timings anywhere, nothing about what the decoder
  achieved, and no way to tell which execution won a race. Added, all gated behind `diagOn()` so a
  production install stays quiet: total scan duration on the completion line (the value already
  existed in `state.lastScanDurationSeconds`, it was simply computed after the log rather than
  before), per-phase durations for the device and app phases from two new durable timestamps, a
  one-line webCoRE decode summary, and a compact lock-versus-state snapshot at all four recovery
  points. Decoded flows are deliberately not counted in the summary: `finishScan` moves them out of
  `appInfo` into `graph.flows`, so counting them at the log site would depend on that ordering.
- **The device-role classifier was overclaiming, and is now tri-state.** Caught in independent review
  before the push. The first version asked "is this a trigger?" and treated every "no" as a
  constraint, so an unrecognised comparison, or a condition carrying none at all, was positively
  labelled `constraint` despite the comment beside it promising the opposite. The stored `ct` also
  simply won, which cannot be right in either direction: `subscribeAll` can legitimately downgrade a
  trigger comparison to a condition before writing `ct`, so a genuine `co: changes` with `ct: c`
  exists, while the structure doc already records that a saved `ct` can be stale after an edit.
  Neither source can arbitrate the other. `webcoreFlowRole()` now returns `trigger`, `constraint` or
  null, deciding by closed-set membership against BOTH comparison blocks, transcribed from the same
  pinned source (the trigger block was already there; the 35-entry condition block was not, and had
  to be added for membership to be decidable in both directions). Agreement or an absent `ct` yields
  a role; a conflict, an unrecognised operator or an unrecognised `ct` yields null and the read stays
  an unattributed `deviceRead`. The same uncertainty now governs the flow split, so nothing is lifted
  out of a decision on a saved `ct` alone. This deliberately gives up classifying context-downgraded
  triggers rather than ever publishing a role that might be false.
- **A mandatory gate was skipped, not failed.** `validate.ps1` was never run for any of this work;
  only `groovyc`, `check_template.sh` and the suites were. It failed at HEAD because a comment
  reintroduced the banned literal `AM-TRACE` while describing the facility that was removed. The
  marker list at `validate.ps1:162` is deliberately exact, so the comment was reworded rather than
  the gate bypassed. Both `validate.ps1 -BuildProfile Dev` and `-SelfTest` now pass.

**Carried forward:** the narrow-window check was tracked separately for a time, then dropped from the
active list.

**Decided by Gordon, 2026-09-11:**

- **Indented font size:** kept as it is. The webCoRE text already measures the same as Rule Machine's.
- **webCoRE piston locals:** tagged [WCV] in the dropdowns, Quick Search, their panel and the rule
  variables card. Rule Machine locals keep [LOC].
- **Tags are searchable (fixed on dev).** Quick Search and the Focus dropdowns matched only an item's
  name, so a visible tag such as [WCP] found nothing while "WC" matched names. They now match the
  text each row shows, tag and type prefix included.
- **Repeated variable card rows.** A rule that writes the same Local Variable from two fields showed
  "[LOC] Overloadcount - writes" twice. Identical visible rows now merge by scope, name, operation and
  read role in the Local, Hub and Needs review lists; the saved references stay one per field.
  </details>

- **Fix the three real bugs found by independent UI assessment (item 22):** completed and verified
  live on the Dev hub, 2026-09-07 (v2.2.5; "local-only, not yet pushed" was the status at the time,
  since shipped). Full report:
  `Supporting Docs/desktop_ui_independent_assessment_2026-09-07.md`.
  - Focusing the Automation Map app itself used to falsely claim "Nothing at all: no children, no
    schedule, no subscriptions... it is not configured yet, or has been removed" despite genuinely
    running a scheduled scan - `processAppRelationships()` returned early for every instance of this
    app's own family (deliberately, to avoid drawing a second/orphaned instance as an app with
    hundreds of meaningless whole-hub edges), and that early return also skipped the separate,
    harmless schedule/subscription/child-count capture every other empty app gets from the same scan
    response. Now captured before the early return, same shape the existing shared block already
    builds for every other inert app. Verified after a real scan (117 apps): the self-app node now
    carries `sched: 4` and its panel lists all four real scheduled jobs (including the daily 01:00
    scan cron) instead of the false abandoned-app message.
  - The Full legend's Private Boolean row was a truncated sentence ("...rule sets another rule's",
    no object) - completed to match the correct wording the compact legend already had elsewhere in
    the same file.
  - Quick Search claimed to "search everything..." but excluded external-system nodes by
    construction. Added `external` to the search group allowlist, dispatched through `focusNode()` -
    the same generic path a direct click on an external-system node on the graph already used, so no
    new focus mechanism was actually needed. Verified live: 22 external-system items now appear in
    Quick Search; selecting one through the real combobox interaction correctly focused it.

  Item 23 (Next) carries everything else the same assessment found, since those all need an actual
  design decision rather than a wording/logic fix.

- **Tell the user when a newer version has been published (item 21):** completed and shipped in
  production v2.2.3 (2026-09-05). The settings page title shows a blue, bracketed notice when the
  channel's own manifest reports a newer version than `APP_VERSION` - notify only, HPM remains the
  thing that installs. Compares against the build's own channel (`dev` checks `dev`, production
  checks `main`), so a Dev tester ahead of production is never told they are behind. Scheduled
  independently of the auto-scan toggle, in the hub's own local timezone with the minute derived
  from the app id so installs do not all wake together and no UTC/DST handling is needed. Fetches
  asynchronously into `state`; page render is a string read, never a network wait. Fails silent on
  any error. Disclosed next to the scan-schedule paragraph, since it is a new outbound request and
  the lesson from the removed telemetry driver was that the objection is to phoning out
  unannounced, not to what is sent.

- **Surface broken or disabled rules in Insights (item 9):** completed and verified live on the Dev
  hub (v2.2.1, 2026-09-05). Adds findings for things that look fine but silently do nothing: a
  paused or disabled rule another rule still calls, a disabled device automations still command or
  use as a trigger, and rules Hubitat itself marks broken via its own `*BROKEN*` label - the only
  place that state is exposed; genuine runtime execution errors remain unreadable and are documented
  as a limitation, not claimed as covered. Every paused/disabled rule is also listed as plain context
  under expected patterns rather than as a fault, and Local Variables with no decoded usage get the
  finding their Hub Variable equivalent already had. Each finding needs a second fact before it is
  reported - pause/resume callers and constraint/monitor reads are deliberately excluded, since those
  are the mechanism working, not a failure. All of it derives from the same `deriveInsightData()` the
  export already used, so the panel and export cannot drift; confirmed on a real 220-device/116-app
  scan, both from the panel and from a downloaded AI-friendly export (`rulesFlaggedBroken`,
  `disabledDevicesStillUsed`, `inactiveRulesStillCalled`, `inactiveRules`,
  `unreferencedLocalVariables` all present and correctly populated). Additive export fields, so the
  schema version is unchanged. Also fixed in passing: the "best viewed on a desktop" small-screen
  message was sharing the page with the live status pill and hub watermark image, both left visible
  by the small-screen media query - now hidden with everything else the desktop UI doesn't need.
- **Production-builder line-ending hardening (item 20):** completed 2026-09-05. Generation is now
  independent of how a checkout materialized. Three paths carried the defect, not the one originally
  identified: the app source read, the header literal (which inherited the builder file's own
  checkout line endings), and the curated release notes, which embed verbatim into the manifest JSON
  as a string value and escaped as `\r\n`. All three canonicalize to LF, and each generator now
  fails closed if a carriage return survives into its finished candidate rather than trusting that
  canonicalization held. `.gitattributes` pins the build inputs and the builders to `eol=lf`
  (`validate.ps1` deliberately excluded, CRLF being native for PowerShell); no renormalization was
  needed since every affected blob was already LF, the exposure being that `core.autocrlf` rewrote
  them on checkout. Verified three ways: LF/CRLF/mixed fixtures through the real generator functions
  (confirmed failing without the fix), a fresh checkout under `core.autocrlf=true` keeping the inputs
  LF while the excluded `validate.ps1` converted as expected, and byte-identical candidates from two
  independent checkouts of the same commit. A CRLF-corrupted checkout is now additionally rejected by
  provenance verification itself rather than building silently. Generated output is unchanged: the
  candidate is byte-identical to the shipped v2.2.0 artifact apart from the embedded commit SHA.
  Fixed in passing: `validate.ps1` aborted on a detached HEAD (`git branch --show-current` returns
  nothing), which broke exactly the isolated-worktree builds used to recover from the original
  incident.
- **Structured Dev diagnostics and a comment-stripping production build (item 16):** the
  comment-stripping production build half shipped as v2.2.0 (2026-09-03) - a small, versioned
  allowlist of exact substitutions (never a general transform), each proven via full
  structural/positional comparison against the annotated Dev source, git-bound to one verified
  commit. Built as `tools/production-builder/` (`production-profile.groovy`,
  `production-manifest.groovy`, `production-package.groovy`), hardened across multiple review
  rounds with independent verification at each step, methodology generalized and written up at
  `hubitat_dev_utililities/Provenance-Verified Substitution Build/README.md` for reuse elsewhere.
  Release path: verified build -> isolated manual HPM install/test on a non-colliding app id ->
  Gordon's explicit hub confirmation and production authorization -> promotion to `main` (commit
  `72ff48a`) -> live HPM update -> community notice. A real gap found during the promotion build
  (candidate generation is not actually commit-pure with respect to local checkout line endings) is
  tracked separately as item 20, not blocking this closure. The structured Dev-only trace schema
  replacing `AM-TRACE` (the other half of this item) remains unstarted - if still wanted, re-open as
  its own item rather than reviving this one.
- **Open Automation Map in a normal browser tab (was item 2):** dropped as infeasible within the
  Hubitat-generated app UI, which controls the map link's small pop-out window. Do not pursue a link
  rewrite unless Hubitat later exposes a supported way for the app to choose normal-tab behaviour.
- **Show disabled devices distinctly on the map (was item 18):** completed and verified on Dev (2026-08-31; "pending production release" was the status at the
  time, since shipped) - disabled devices and paused/disabled rules get a
  canonical label suffix, structured export fields (`devices[].disabled`, `apps[].status`
  distinguishing `disabled`/`paused`), and coloured Focus dropdown entries. Also fixed the
  duplicate-suffix bug noted under item 9. The canvas-level red-suffix piece was deliberately left
  out - see item 19.
- **Scan-schedule setting shows its real default (was item 8):** local review and automated gates
  passed, deployed to Automation Map (Dev) (Apps Code 1210 / instance 3083), and confirmed live in
  Gordon's own testing (2026-09-02) - the Hours/Minutes field now shows its actual default
  (00:30 production, 01:00 Dev) pre-filled via the input's own `defaultValue`, rather than appearing
  blank with an explanatory `description:` that never rendered on `bool`/`time` inputs. A
  `paragraph` states the display-vs-saved nuance. A separate effective-default helper
  (`autoScanEffectivelyEnabled()`) treats a genuinely unsaved `null` the same as the toggle's own
  displayed-on default, since Hubitat does not necessarily populate `settings` with a displayed
  default before the first save - without it, a truly fresh install could read the toggle as off and
  hide the time input entirely. One helper function is the single source for the default time across
  the input, the paragraph, and the scheduler's own blank-time fallback, so the three can't drift
  apart.
- **v2.1.8 production cleanup - telemetry removed, on-demand diagnostic logging added:** local
  review and automated gates passed, deployed to Automation Map (Dev) (Apps Code 1210 / instance
  3083). Diagnostic-toggle placement and off/on/off logging behaviour independently verified live
  by Gordon (2026-09-02) - `AM-TRACE` present only while enabled, gated routine lines correct, the
  two anomaly lines at `warn`. The telemetry-child migration test (clean deletion, plus a
  deliberately-referenced device failing safely) is explicitly **waived by Gordon**, not passed -
  low affected population, easy manual fallback. The Automation Map
  Telemetry Driver and everything that fed it (`ensureTelemetryDevice()`/`reportTelemetry()`/
  `fetchHubHardwareId()`, the manifest driver entry, the README disclosure) are removed outright
  rather than made optional - community reaction to an always-present reporting driver was that it
  read as intrusive regardless of what it actually collected. An upgrading instance removes its own
  leftover telemetry child device automatically (best-effort, exact-DNI `deleteChildDevice()`; if
  Hubitat refuses because it's still referenced elsewhere, the settings page shows a fixed warning -
  never the raw exception text, which is internal diagnostic detail and stays in the log only - and
  retries the next time settings are saved, not on a schedule of its own). In its place, a
  settings-page toggle enables on-demand diagnostic logging for troubleshooting - off by default,
  with a durable expiry timestamp (not just a scheduled job, which a missed hub-down window could
  leave stuck) enforcing the one-hour auto-disable even if the scheduled handler itself is missed;
  the settings page reconciles a stale "on" display back to off on its own next render. Only
  routine/lifecycle log lines are gated behind it (installs, scheduling confirmations, endpoint-entry
  logs, successful saves, expected superseded-generation discards, registry counts, scan
  start/completion detail); the temporary `AM-TRACE` path is Dev-only regardless of the toggle,
  per the existing agreement not to make it part of the reusable production logging design. Failures
  and degraded outcomes that can leave the map incomplete or stale stay unconditionally logged
  regardless of the toggle. Both the removed remote-telemetry approach and the new local-logging
  approach are documented for reuse at `https://github.com/GordonThelander/hubitat_dev_utililities`
  under "Application Telemetry Methods", sanitized and parameterized rather than copied with real
  identifiers.
- **Add anonymous Variable coverage counts to telemetry (was item 17):** cancelled, superseded by
  the decision to remove remote telemetry entirely rather than extend it (2026-09-02).

  This is a narrower, immediately-authorised slice of item 16 below, not a substitute for it -
  item 16's structured Dev-only race trace and its comment-stripping production build remain
  separate, still gated on Gordon starting that phase explicitly.
- **Component-device (parent/child) discovery and rendering:** completed and verified on Dev (2026-08-31; "pending production release" was the status at the
  time, since shipped) - `/hub2/devicesList`'s hierarchical response (a
  device-owned component, e.g. a Shelly/Bond/Matter-bridge child, nested inside its parent's own
  `children` rather than as a top-level sibling) is now fully walked during discovery and rendered
  as a `hasComponent` relationship on the graph and in the AI export, including correct
  focus-expansion behaviour for an app that touches a child but not its parent directly.
- **Revalidate Local Variable handling (was item 3):** completed and verified on Dev (status at the time; since shipped) - identical names are not guessed or merged. A proven Local identity remains
  owner-scoped to its rule, a proven Hub identity remains hub-scoped, and an indistinguishable
  same-rule reference (persisted Rule Machine storage cannot always prove which was intended) is
  reported as ambiguous rather than assigned to either scope. Gate C shipped in v2.1.4 with live Dev
  verification; v2.1.6 added owner-scoped Local Variable graph nodes, focus and pivot support, and
  resolvable export endpoints, independently accepted per queue 315-317. The proposal document's
  `Draft`/`Implementation authorization: None` header is stale and should be corrected separately if
  the document is retained.
- **Hub Variable search:** shipped.
- **Variable Connector association:** shipped.
- **First-class Hub Variable identity, focus and export:** shipped.
- **Hub Variable pivot reconciliation:** fixed and independently verified on the Dev hub in
  v2.0.15, including a trailing-period variable with six read/write relationships.
- **Insights summary and readability redesign:** shipped; further work belongs to the desktop UI
  review above.
- **Actionable Insights guidance and AI-export alignment:** shipped and independently verified on
  the Dev hub in v2.0.15.
- **External Systems hierarchy, identity and reviewed seed classifications:** shipped and
  independently verified in v2.0.15. The only unassessed apps in the verification scan were two
  intentionally unknown scratch/test apps.
- **Current discovery wording:** corrected on the install page and in the README in v2.0.15.
- **Persistent manual node layout:** rejected because it conflicts with changing graph membership
  and creates fragile state.
- **Tablet-only legend redesign:** rejected as a separate item; desktop readability and responsive
  behaviour were covered by the desktop UI review item, since dropped from the active list.
- **Arbitrary node exclusion:** rejected because it can hide evidence and make the map misleading.
- **Single Hub Variable icon:** delivered in substance through first-class variable styling.

## Separate publication work

The Rule Machine storage-format write-up remains useful, but it is documentation work for the
developer utilities repository rather than an Automation Map product backlog item.
