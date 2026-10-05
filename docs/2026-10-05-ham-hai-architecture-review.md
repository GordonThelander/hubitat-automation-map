# HAM and HAI: architecture, usability and enhancement review

2026-10-05, at Gordon's request, from a cloud session that worked both repositories today. Based on reading
both codebases, their docs, backlogs and issue ledger, and on what today's test work exposed. Nothing here
was measured on the hub; where a number is quoted, its source is named. Recommendations are ranked within
each section by value for effort, and each says how it sits with the hub's limits.

## What the two apps are, together

**Automation Map (HAM)** reads the hub - every app, device and rule - over loopback and draws it: a graph of
what touches what, and Rule Machine, Notifier, VRB and webCoRE flows. It is read-only apart from Room
Manager. One 1.41 MB Groovy file, 38% of it a single HTML/JS string.

**Automation Intelligence (HAI)** is a rule engine meant to replace Rule Machine: one strict rule document
(`hai.rule/1`), one validator, one simulator and one runtime that share `hai_core`, an editor, AI drafting
through the Claude API, and an MCP server. Two apps: a 535 KB parent and a 308 KB per-rule child.

**The partnership is the important part.** HAM is the decoder - it understands Rule Machine's storage and
publishes `ham.decode/1` and `ham.decode.detail/1`. HAI is the rebuilder - it converts those documents into
its own rules and publishes `hai.am/1` and a capability feed back. Neither parses the other's storage. That
division is right, and most of the recommendations below are about making the seam between them as
trustworthy as each side is internally.

## What is already strong

- **Refusal over guessing.** Both codes refuse what they have not measured (the short-circuit expression
  walk, inverted `pvTF`, unclosed IFs) and say why in words a person can act on. Today's work added tests
  so those refusals cannot quietly regress.
- **Hub-aware engineering.** Single-flight scan locks, callbacks that never touch `state`, a fail-closed
  publish guard, deploy gates for memory and uptime, a cross-agent deploy lock, SHA-pinned editor files.
  These came from real failures and are recorded where they live.
- **Evidence discipline.** Hub-tested versus simulated is defined and enforced; claims are dated.

## The hub limits that should shape every decision

| Limit | Evidence | Consequence |
|---|---|---|
| Every code save leaks class metadata until reboot; `OutOfMemoryError: Metaspace` at 491 MB "free" | HAI deploy-hub.ps1:323-338, HAI-D34 | Fewer, smaller code saves. Large source is a cost on every deploy, not just at upload |
| `state` is snapshotted per execution and written back whole, last write wins | HAM BACKLOG 30, 51; HAI-D44 | Big or shared data in `state` gets clobbered; HAM rebuilds its graph twice per scan because of it |
| `runIn` is unreliable: nothing before install, nothing with a session cookie, sometimes never | HAM :626, :1166, :1450; HAI-D40, D44, D47 | Every scheduled step needs a watchdog; many small jobs accumulate and orphan |
| 65,535-byte JVM constant limit; GString backslash consumption | HAM README:201, validate.ps1 | The HTML-in-Groovy approach is fragile |
| Groovy 2.4 differs from local Groovy | Today: `ham-decode-detail` and HAI `TestRuntime` fail only on 2.4 | Tests must run on 2.4/Java 8, or they certify code the hub cannot run |
| Loopback only; LAN IP self-calls time out; cloud OAuth 504s at ~10.8 s | HAI api.md:216; HAM BACKLOG 47 | Remote access paths need their own design |
| CDN dependency for the map (vis-network, Mermaid 3.34 MB) | HAM BACKLOG 47 | Offline or filtered hubs see a blank map |

## Recommendations: architecture and reliability

1. **Move HAM's map UI out of the Groovy file and into File Manager, as HAI already does for its editor.**
   ~544 KB of HTML/JS leaves the app source: a 38% smaller code save (the Metaspace point above), no more
   65 KB constant splits or backslash hazards, and the JS becomes testable as JS. HAI's solution to File
   Manager's missing cache headers - `v=<sha256>` on the link and `no-store` - carries over unchanged. Cost:
   an upload step at install, which HPM can do as a package file. *Highest-value structural change.*

2. **Take large derived data out of `state` in HAM.** The graph and app info are rebuilt, not edited; write
   them to a File Manager JSON (the decode files already prove the route) and keep only a pointer and hash in
   `state`. This removes the clobber that forces the second `buildGraph` (BACKLOG 30), shrinks every state
   write, and is the most likely cure for 51 (first scan after an upgrade collects 0 apps) - instrument 51
   first, as planned, but expect this to be where it leads.

3. **One decoder, two renderers in HAM.** HAM now decodes Rule Machine twice: once into display text for the
   flow panel and once into operands for HAI. BACKLOG 42, 52 and 54 are all the display path reading cached
   or rendered fields the operand path already avoids. Generate flow sentences from the detail operands, and
   both outputs become correct together - and every HAM fix to the decode reaches HAI's migration too.

4. **One scheduler per HAI rule, not many `runIn` jobs.** Store all of a rule's deadlines and keep exactly
   one scheduled job, set to the earliest; on firing, run what is due and reschedule. That makes orphaned
   timers (D47) impossible by construction, removes the 1 ms commit race (D44), and gives the watchdog one
   thing to check. Fits the measured timer behaviour (30 ms late at 1 s and above).

5. **Run every suite on Groovy 2.4 / Java 8 in CI.** Today three suites across the two repos failed only on
   2.4, the hub's version: HAI `TestRuntime` (does not compile: a duplicated variable), HAI `TestHam`
   (truncated JSON) and HAM `ham-decode-detail` (fixed today). A GitHub Actions job running both repos'
   runners on 2.4.21 costs nothing per push and would have caught all three.

6. **Bring HAI's top-level docs back in line with the code, and give the parity number one source.** README,
   `docs/developer/architecture.md` and the user guide still describe three apps (merged to two on
   2026-09-27), "no import button", and a 40-device cap removed on 2026-10-02; `rm_migration_implementation.md`
   says nothing is built. The tested count is 133 in `rm51_parity.md`, 106 in CLAUDE.md and 61 "seen on a
   hub" in the README. Generate the README figure from `tools/recount_parity.py` so it cannot drift.

## Recommendations: the HAM-HAI contract

7. **Share one fixture, checked on both sides.** HAM now asserts on its captured documents
   (`tests/ham-published-documents.groovy`) and HAI converts its own copy (`engine/test/fixtures/
   ham-decode-detail-dev.json`). Add a check that the two are the same capture, or record which HAM build
   each came from. Otherwise HAM fixes a shape and HAI keeps testing the old one.

8. **Version the contract in both repos.** The contract text lives only in HAI's `ideation/`. Put a copy, or
   a pinned link, in HAM's docs, and have each side's suite assert the contract version it was written
   against. Condition devices moving to `{id, name}` (HAM 55, HAI-D56) is the first change that needs it.

9. **Publish evidence with its date and build.** HAM republishes HAI's evidence levels to people deciding
   whether to migrate and cannot audit them (HAI-D29, HAM 36). If each capability carries the build and date
   its evidence was recorded on, HAM can show "seen on a hub, 2026-09-22, build 108" and people can judge
   staleness themselves.

## Recommendations: usability

10. **HAI: acknowledge every click immediately, and make the slow paths fast or visible.** Switching drafts
    takes 8.5 s with no acknowledgement (D51, and it produced the false report D48); a first Check takes
    12.5 s; Run on hub ~22 s. Immediate feedback (a spinner on the clicked item) is cheap; the larger win is
    returning the check result with the draft in one call and caching it by document hash.
11. **HAM: a phone view.** Below 820 px the map is replaced by "use a desktop". A list view - pick a device,
    see what triggers, constrains or commands it - answers the app's core question on a phone without a
    graph library.
12. **HAM: a local fallback for the drawing libraries.** Serve vis-network (652 KB) from File Manager, and
    keep Mermaid on the CDN with a clear message when it cannot load. The intermittent "Could not load the
    drawing libraries" (BACKLOG 47) becomes impossible for the map itself.
13. **Say the limit before the person types, not after.** HAI's limits mostly refuse after drafting and
    several do not name which limit was hit (`limits_audit.md` §B). Show remaining room as the person types.
14. **Make "scan twice after an upgrade" unnecessary or explicit.** Until 51 is fixed, the settings page should
    say so after an update, rather than people discovering it.

## Recommendations: enhancements

15. **HAI shadow mode before switch-over.** Run a converted rule beside its Rule Machine original with commands
    suppressed, recording what it *would* have done, and show the differences. That is the cheapest honest
    evidence that a migration is safe, and it fits the project's definition of hub-tested. Limit to a few
    rules at once: it doubles subscriptions for each.
16. **Close the migration loop: switch back, and verify one live switch.** "Make a copy, test it, switch over
    when you trust it" needs the HAI-to-RM direction (D45), and no live switch has been verified (D11). Both
    come before any user other than Gordon.
17. **HAM: "why did this turn on?"** For one device, on demand, join the hub's event history with the graph's
    triggering apps into a timeline. On demand, not at scan, because of memory. It is the question people
    install HAM to answer.
18. **HAI: an allow-list for HTTP and ping actions.** They can reach any host today (`rule-format.md:315`). A
    host allow-list in settings is cheap, and an AI-drafted rule can otherwise call anywhere.

## Process, from today

19. **One brief, one session.** Today four cloud sessions worked the HAI brief in parallel and three pushed
    competing branches. Briefs in the repository - as HAM's `docs/cloud-task-test-harness.md` now is - plus a
    claimed-by marker in the brief would have prevented it.
20. **A setup script for cloud sessions.** Each new session spent 5-10 minutes building Groovy 2.4, Java 8 and
    PowerShell. A SessionStart hook in each repository would make that automatic.

## Suggested order

First, because they reduce risk on every later change: 5 (2.4 in CI), 6 (docs and one parity number),
1 (map UI out of Groovy), 2 (graph out of `state`). Then the contract work, 7-9, before HAM 55's version
bump. Then 4 and 16, which gate migration for anyone but Gordon. The usability items can go alongside any
of these; 15 and 17 are the enhancements worth planning first.
