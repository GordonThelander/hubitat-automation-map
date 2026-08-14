# Backlog

Not shipped with the HPM package. Items here are agreed ideas awaiting a decision to build, not commitments.

---

## Rule Machine 5.1 documentation (written, awaiting publication)

**"Reverse-engineering the Rule Machine 5.1 storage format"**,
`Supporting Docs/rule_machine_5_1_storage_format.md`. A community publication, not an app
feature. Gordon will publish when he judges it ready; no deadline.

**This is a living document. Add to it whenever Automation Map work turns up something new
about how Rule Machine stores a rule, without being asked.** Anything learned while
extending the decoder belongs there, and the open questions below are the specific gaps a
future finding might close.

### Current state

663+ lines, complete and internally consistent, and kept current as new findings land
(most recently the December 2026 four-term precedence test and the right-associative
evaluation-order conclusion). Contains:

- A method section: rule page as ground truth, differential reading, corpus-wide checks
  across all rules, building a working decoder so misreadings render as visibly wrong
  flowcharts, constructing test rules where the corpus had gaps, and checking official docs
  before claiming novelty. Also states what was *not* done.
- Evidence markers on every claim: `[invariant]` `[strong]` `[limited]` `[single]`
  `[heuristic]` `[unknown]`, so a finding holding across the corpus is distinguishable from
  one resting on a single sample.
- A framing boundary: the stored representation holds enough to **reconstruct** a rule, but
  not necessarily enough to **execute or reason about** one. Reconstruction is checkable
  against the rule page; evaluation is not.
- A security warning that `appSettings` returns every setting an app holds, including other
  apps' tokens and credentials, so nothing should be logged or exported wholesale.
- A worked example decoding one rule end to end, which also demonstrates the
  `eventSubscriptions` snapshot trap live.

Reviewed externally 2026-08-12; review incorporated. Two review points led to better
findings than the review proposed, both now in the document: the 13 action-object shapes,
and the correction that expressions must not be assumed to evaluate left to right.

### Open questions, any of which a future finding could close

Tracked here so a discovery is recognised as closing something rather than passed over.

- **Expression grammar.** Explicit grouping or parentheses, NOT, whether `eval[n]` can
  reference another expression rather than a bare condition, and operators beyond AND/OR.
  All `[unknown]`.
- **Evaluation order beyond four terms.** Two live tests (three-term and four-term) both
  point to right-associative grouping and rule out conventional precedence, OR-binds-first,
  and left-to-right. Not yet separated from conventional precedence with full certainty -
  a case like `F AND F OR T AND T` (conventional TRUE, right-associative FALSE) would settle
  it outright. Untested with NOT or more than one OR.
- **Repeat and while.** Every rule carries state for them (`hasWhileRule`, `inRepIf`,
  `nestedRepIf`, `blockIf`) but no rule on the hub uses them, so their action-level markers
  are unobserved. The IF family is therefore known to be an incomplete grammar.
- **Rule Function discriminator.** One sample only. Reports identically to an ordinary rule
  in every field examined, which is absence of evidence rather than evidence of absence.
- **Bulk app enumeration.** Resolved in practice - `/hub2/appsList` returns the complete
  installed-app tree and is now unioned into discovery (dev 1.8.0) - but worth a note in the
  document itself if not already there, since the earlier text claimed no such endpoint
  existed.

### Why storage rather than execution

Checked the official docs before writing anything, and most of the originally planned
execution document would have restated what Hubitat already publishes. The
[Rule 5.1 page](https://docs2.hubitat.com/en/apps/rule-machine/rule-5-1) already covers, in
its own words: required expressions "typically remove the trigger event subscriptions when
false"; Run Rule Actions is "not identical to a triggering of the other rule" including the
Cancel-pending interaction; a delayed action "affects only that action, and not subsequent
actions"; re-triggering "does not cancel previously scheduled delays" by default; and the
"Multiple simultaneous rule execution error", including that platform 2.3.8.186 fixed
top-level conditionals.

Storage format is documented nowhere, and the Automation Map work is the primary source for
it. That is the piece worth publishing.

`Rule_Machine_5_1_Execution_Explained_Draft.md` is retained as the superseded input.

### Two loose ends worth a separate short post

- **"Ignore trigger events while running" is still fully documented** on the Rule 5.1 page,
  while Bruce stated in June 2025 that it had been removed. No corresponding setting was
  found across the rule corpus, which is suggestive but not proof since an unused option
  may store nothing. Opening any rule and looking for the toggle settles it. Either a stale
  doc or a misremembering, and worth reporting either way.
- **Rule Functions are entirely undocumented officially.** Zero mentions on the Rule 5.1
  page and zero on the Rule Machine index page; the only source is Bruce's forum post. A
  practical guide would fill a real void, and there is now hands-on material: a Rule
  Function reports itself identically to an ordinary rule, and normally references no
  devices at all.

---

## Rejected

### Auto detection of external endpoints from app settings

Investigated 2026-08-09, rejected. Roughly 40 percent precision
on a naive URL and host heuristic, and it misses exactly the integrations where a
dependency map would be most useful. Source grepping does not rescue it: `importUrl`
points at GitHub in every app, documentation links and commented out endpoints produce
false positives, and built in apps expose no source at all.

### Showing an app's non-device settings and scheduled jobs in the focused panel

Investigated 2026-08-09, rejected. Cheap and generic, but noise on Rule Machine rules,
which carry dozens of internal settings such as `state_16`, `actSubType.1` and `ctL.1`.

Note that `scheduledJobs` is complete and authoritative, unlike external endpoints, so a
time or schedule node class would be reliably correct wherever it appeared. Not pursued,
but a better foundation than external detection if the "what drives this app" gap is
revisited. (Partially superseded 2026-08-14: scheduled-job detail is now shown for *inert*
apps specifically - next run time and raw cron, no English interpretation - which is a
narrower and safer version of this than showing every app's schedule in the focused panel.)

### Device-mediated rule-to-rule links (closed 2026-08-14, was "proposed, deferred")

Direct rule-to-rule links shipped in 1.3.3 and were substantially extended afterward -
`ruleActMain`/`privateF` alias recognition, honest kind naming (`cancelTimedActions`,
`pauseResume`), broken-reference detection surfaced in Insights. What was never built, and
is now closed rather than left open: inferring a link through a shared device - Rule A
writes a virtual switch, Rule B triggers on it, therefore A leads to B.

That inference is also where every false positive would live. The original design
(`Supporting Docs/hubitat_automation_map_rule_to_rule_implementation.md`) was roughly five
times bigger than what should be built - 15 edge types, 5 phases, cycle detection,
feedback-loop oscillation analysis, three separate semantic JSON files, confidence values
quoted to the percent. If this is ever revisited, the discipline that must survive any
trimming is on record in that document and must not be skipped:

- A condition is not a trigger: TRIGGERS_VIA versus INFLUENCES_VIA.
- Value matching: Rule A sets switch X on, Rule B triggers on X off, therefore no edge.
- Key on `deviceId + attribute`, never `deviceId` alone.
- Required Expressions never produce trigger edges.
- POSSIBLE_RELATIONSHIP when subscription versus read cannot be established.

Honest limitation, already documented in the README: only Rule-5.1 is analysed. Room
Lighting, Basic Rules, Simple Automation and webCoRE show no links rather than showing that
they have none.

### Integration registry as a backlog item (closed 2026-08-14 - shipped, not rejected)

The design discussed under this heading - a user classification table on the map page
listing every app type, four-field classification (system name, kind, criticality), backup
via browser file download/upload, registry fetched from `main` with cache and embedded
fallback - has shipped. It lives in the app as the External systems panel
(`externalsGetMapping`/`externalsSaveMapping`, `fetchRegistry()`), backed by the registry
built and maintained in `GordonThelander/HPM_Manifest_Crawl`. Removed from backlog because
it is no longer proposed work; the code and that repo's own history are the record of it
now, not this file.
