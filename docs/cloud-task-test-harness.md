# Cloud task: assert on the published documents, not just the resolvers

This brief lives in the repository rather than in a message, because a cross-session message does not
survive into a cloud session intact. Everything needed is here or in the checkout.

**Do not start until Gordon gives explicit permission.**

## What this repository is

One Groovy app, `apps/automation_map.groovy`, about 1.4 MB. It decodes Hubitat Rule Machine 5.1 rules
and publishes two JSON documents that a sibling app (Hubitat Automation Intelligence) consumes to
rebuild those rules on a different engine.

**You have no access to the hub.** It is on a LAN at 10.0.0.125. You cannot deploy, scan, read live
published files, or measure anything. That constraint is the reason this task was chosen for you: it
needs none of those things.

## The problem

On 2026-10-05 five defects reached the hub that a fully green test suite did not catch. Every one was
plainly visible in the published output and invisible to the tests. All real:

- A `delay` was published on 13 wait steps that does not exist. The resolver was correct in isolation
  and wrong about which actions it should run on. 41 assertions passed, including several written
  specifically for delays. The consumer turns a per-action delay into a wait, so a rebuilt rule would
  have waited twice.
- All 83 control-flow records published `supported: false`, across 25 rules, because the structure was
  resolved into the display array and the tests asserted on the resolver rather than on the array a
  rebuild actually reads. The largest construct on the hub was refusing on structure the app had
  already decoded.
- A trigger resolver called a function with its arguments in the wrong order. It threw, and a throw
  there discarded the entire rule record, so six working rules published as `unreadable` with nothing
  in them. The suite passed because the harness stubbed that function with an invented signature.

**The cause is structural, not carelessness.** The suites slice a function out of
`apps/automation_map.groovy` with `source.indexOf(...)`, run it in a `GroovyShell` against hand-written
stubs, and assert on its return value. Nothing asserts on the shape of the published document. A
resolver can be perfect and the document still wrong.

## The task

### 1. Use the committed fixtures

`tests/fixtures/ham-decode-detail.json` and `tests/fixtures/ham-decode.json` are real captured output
from a live hub: 71 Rule Machine rules, contracts `ham.decode.detail/1` and `ham.decode/1`.

**Never regenerate or hand-edit them.** If an assertion disagrees with a fixture, the fixture is the
evidence and the assertion or the app is what is in question.

### 2. Assert on the documents

Write a suite that reads the fixtures and asserts on their shape. At minimum:

- every rule carries `decodeFailures`; an empty map means no failure, and an absent key is a failure of
  this assertion
- no action whose `method` is `getWaitRule` or `getWaitEvents` carries a `delay` (a wait's duration is
  not a delay before it; publishing one made a rebuilt rule wait twice)
- every control-flow method (`getIfThen`, `getElseIf`, `getElse`, `getEndIf`) has `supported: true` and
  carries `operands.branch`
- every `supported: true` action carries `type` and `operands`; every `supported: false` carries
  `method` so a consumer can refuse it by name
- every device reference anywhere is `{id, name}` with a non-empty `id` (a name cannot bind: two
  devices can share a label, and one on this hub carries a trailing space)
- every trigger carries `index` and `capability`
- no rule has `status: unreadable` while also publishing conditions or actions
- `getSetPrivateBoolean` actions carry a boolean `operands.value` (the underlying `pvTF` field is
  inverted, so this is the assertion that would catch a regression)
- counts are asserted, not just shapes, so a silent collapse to zero fails

### 3. Remove every stub that invents a signature

Where a suite stubs a function that exists in the app, slice the real one from source instead. See
`tests/rm-trigger-flow.groovy`, which does this for `rulePredicateIsLive`, for the pattern. Add an
assertion forbidding the stub's return.

**This is the highest-value item.** Two of the five defects were hidden by stubs that did not match
reality: one stubbed hub `Mode` objects as plain maps, the other invented a three-argument signature
for a function that takes different arguments in a different order.

### 4. One runner

A single script running every `tests/*.groovy` and `tests/*.js`, exiting non-zero on any failure and
reporting per-suite counts. There are about thirty suites and no runner; they are run by hand.

## Rules

- Work on branch `cloud/test-harness`. **Never commit to `dev` or `main`.** You cannot verify against
  the hub, so nothing you write may land where the sibling app reads it.
- **Do not modify `apps/automation_map.groovy`.** If an assertion fails against a fixture, that is a
  finding to report, not a thing to fix. A decoder fix without hub verification is a guess, and
  guessing is what caused the defects above.
- **Do not touch `deploy-hub.ps1` or `tests/deploy-hub.ps1`.** Uncommitted work by another agent.
- **Do not weaken an assertion to make it pass.** If it fails, report it with what it implies.
- Groovy and Node are both required. Suites run from the repository root as `groovy tests/<name>.groovy`
  and `node tests/<name>.js`.
- `validate.ps1` enforces repository rules, including no backslashes in template strings. Run it.

## Done

A branch carrying the runner, the document assertions, the stubs removed, every suite passing, and a
written list of any assertion that failed against a fixture with what it implies about the app.
