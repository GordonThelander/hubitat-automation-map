# Cloud task findings: assertions on the published documents

The written result of `docs/cloud-task-test-harness.md`, on branch `cloud/test-harness` (based on
`cloud/test-harness-setup` at `3f9b9b5`). Cloud session, 2026-10-05. No hub access: nothing here was
deployed, scanned or measured live. `apps/automation_map.groovy`, both `deploy-hub.ps1` files and the
fixtures are unchanged. Every change is under `tests/`.

Toolchain: Groovy 2.4.21 (the hub's version) on OpenJDK 8, Node 22, PowerShell 7.4.

## Result

`pwsh tests/run-all.ps1` runs **56 suites: 54 pass, 2 fail**. (After Gordon's decisions of 2026-10-05: the document suite has 28 passing and 2 failing - finding 1 until a fresh capture after the volume fix on `cloud/fix-message-volume`, and finding 2 by agreement until the next contract version.) Both failures are the app, not the tests.
They are the assertions below that failed against the captured documents, kept as they are: the brief
says a failing assertion is a finding to report, not a thing to weaken.

`validate.ps1` is clean.

## Assertions that failed against the fixtures, and what they imply

### 1. Messages with no speaker publish a volume (23 actions)

`ham-published-documents`: *no message without a speaker publishes a volume*.
`ham-decode-actions`: *no volume is invented when none is stored*.

There are 23 `getMsg` actions with no `speak` list that still publish `operands.volume`. They come from
two separate causes:

- **15 publish `volume: 0` that nothing stored.** In `hamDetailActionOperands` (case `getMsg`),
  `Integer vol = hamDetailInt(v["speakVolume.${num}"]); if (vol != null) o.volume = vol`. The app's
  `hamDetailInt` returns `0` for an empty value, never `null`, so the guard is always true and every
  message gets a volume. The suite did not see it because its stub of `hamDetailInt` returned `null`.
  That is the stubbed-signature failure the brief describes, in a third function.
- **8 publish a non-zero volume (50, 30, 100) with no speaker attached.** The message only notifies, but
  `speakVolume.<n>` still holds a value, most likely left over from a speaker removed in an earlier edit.
  This is the same stale-sibling pattern as rule 1775's leftover `endingA5 = '22:00'`: a setting Rule
  Machine keeps, published as though it applied.

There are also 6 messages that do speak and publish `volume: 0`. HAI reads 0 as "not set", so that
case is harmless today, but the contract does not say so.

**Implication:** a consumer that trusts `volume` would set a volume on notification devices that have
none, or treat 0 as "silent". HAI's converter currently ignores a 0 and only applies volume to speech,
so it is not affected today. The contract still publishes something false.

### 2. Condition devices are bare names, with ids in a parallel list (104 references)

*every condition device reference is {id, name}* fails. Condition operands carry
`devices: ['Cory', ...]` (names) beside `deviceIds: ['22', ...]` (ids). Action and trigger references
are `{id, name}` pairs: 258 and 110 of them, all well formed.

The companion check passes: every condition that names devices carries a `deviceIds` list of the same
length with no empty id. So a consumer can bind, and HAI does, by reading `deviceIds`. But the pairing is
by position, and nothing in the document enforces it. **Implication:** one shape for conditions and
another for everything else, joined by an index nobody checks.

**Decided by Gordon 2026-10-05:** change it at the next contract version, with HAI, not before.
Recorded as BACKLOG.md entry 55 here and HAI-D56 in the HAI repository. The assertion stays red until
then.

### 3. Step devices are names only, with no ids anywhere (667 references) - DECIDED

**Decided by Gordon 2026-10-05: `steps` is the display and rule-link layer.** Nothing binds a device
from it, so the {id, name} assertion was retargeted rather than loosened: the suite now asserts that
every `steps[].ruleTargets` entry, which HAI does consume, is a bare rule id naming a rule in the same
document (69 of 69 pass). The HAI contract, `ideation/ham_decode_detail_contract.md`, says so.

As first found: *every step device reference is {id, name}* failed. `steps[].devices` is names only. `steps` is the
display layer, and the HAI side already treats it as such: its migration code keys step devices by name
"until Automation Map publishes ids there too". **Implication:** nothing should bind from `steps`.
If that is intended, the contract should say `steps` is display-only, and this assertion should be
retargeted by Gordon's decision, not loosened by a test session.

## Stubs removed (brief item 3)

An inventory of every function a suite defined that also exists in the app found **33 stubs in 12
suites**, plus **3 suites carrying a hand copy of the function under test** ("keep the two in sync by
hand"). `tests/support/AppSource.groovy` now slices a named function or `@Field` out of the app at run
time, and refuses a name defined zero times or more than once.

Stubs that disagreed with the app, now replaced by the app's own code:

| Suite(s) | Stub | How it differed |
|---|---|---|
| ham-decode-actions, ham-decode-triggers | `hamDetailInt` | returned `null` for empty or bad input; the app returns `0`, which hid finding 1 |
| same | `hamDetailBool` | case-sensitive; the app ignores case |
| same | `hamDetailJsonList` | returned `[]` for unparseable text; the app returns `[text]` |
| ham-decode-triggers, flow-mode-label | modes as plain maps | the hub supplies objects. A map-shaped mode is what hid a defect on 2026-10-05; both now use objects |
| ham-decode-contract | `APP_VERSION` and six other constants | the copy said `2.4.3`, the app `2.4.4`; contract names are now the app's |
| ham-decode-contract | `engineOfNode` | knew only Rule Machine; the app also knows webCoRE, VRB and Notifier |
| ham-decode-contract, ham-decode-partial | `diagOn` | stubbed `false` / `true`; now the app's, switched by the setting and expiry it reads |
| rm-coverage-report | `haiCapabilityIdFor` | mapped every token to itself, which the app never does. Fixture moved onto real construct tokens |
| rm-trigger-flow | `stripTags`, `cleanCondition`, `expressionText`, `requiredDevices`, `actionLabel`, `buildVisualRuleBuilderFlow`, `buildNotifierFlow` | returned their input, `''` or `[]` |
| room-write-contract | `httpFetch(String, int)` | **invented signature**: the app's is `(String uri, int timeoutSec, Map extraOpts = [:])`, and five app callers pass options |
| webcore-coverage-endpoint | `scanEffectivelyActive` | a boolean field; now the app's, driven by `state.scanRunning` |
| device-tree-discovery, has-component-edges, node-entry-status | hand copies | identical in code today, and nothing kept them so; now sliced live |

Each converted suite asserts something only the real function returns (for example
`hamDetailInt('') == 0`), so a stub put back turns it red.

Five remain, all at a boundary the brief allows, each with an assertion that the app's signature has not
changed:

- `httpFetch` (2 suites): the network.
- `fetchHaiFeed`: the network.
- `clearAbandonedScan`: a call recorder. The real one walks scan locks, tombstones and the scheduler.
- `render(Map)`: Hubitat's platform method, not the app's.

## Suites that were red before any change

Three suites failed on a clean checkout under the hub's Groovy 2.4. None was an app defect:

- **ham-decode-detail**: `script.location = ...` writes the script's *binding* on Groovy 2.4, not its
  `@Field`, so every mode resolved to `null`. It presumably passes on a newer local Groovy, which is a
  reason to run these on 2.4. Now `script.@location`.
- **scan-diagnostics**: the diagnostics block now calls `scanProgress()`, which lives outside the slice.
  It crashed with "No signature of method". Now sliced, with `scanProgressIsLive`.
- **flow-render-guard.js**: the app's selection code gained `hideMigrationCard`, `renderEngineLink`,
  `renderMigrationCard`, an asynchronous `loadMermaid`, and three panels (`migrationReportPanel`,
  `rmCoveragePanel`, `roomPlanPanel`) the harness had never heard of. These are now extracted from the
  app, and the panel list is read from the app's own `secondaryPanels()`.

Also fixed: `ham-decode-contract` and `ham-decode-detail` printed "0 assertions passed" whatever passed.
On Groovy 2.4, a closure held in an `@Field` cannot update an `@Field` int.

## Running them

```
pwsh tests/run-all.ps1                 every suite, per-suite counts, non-zero exit on any failure
pwsh tests/run-all.ps1 -Only ham       a subset; says so, and never prints the full-run line
groovy tests/ham-published-documents.groovy
```

`tests/compare-device-metadata.ps1` and `tests/deploy-hub.ps1` are PowerShell, so the runner does not
pick them up. The second was left alone as instructed.

## For Gordon to decide

1. Whether finding 1 is fixed in the app: publish `volume` only when `speak` is non-empty and a volume
   was actually stored. The fix needs hub verification, so it is not made here.
2. Whether `steps` is declared display-only in the contract (finding 3), or given ids.
3. Whether condition devices move to `{id, name}` at the next contract version (finding 2).
4. Whether the runner should know about expected failures. It does not: it is red until the app changes,
   which is the honest state. Making it green any other way would be weakening the assertions.
