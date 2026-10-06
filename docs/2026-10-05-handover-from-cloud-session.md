# Handover from the cloud session of 2026-10-05

From the cloud session that ran `docs/cloud-task-test-harness.md`, at Gordon's request. Gordon asked for
the remaining steps to be done here, by a session that can reach the hub. Do them in order; each one
depends on the one before.

**Updated 2026-10-06: deploy `cloud/capture-restore-colour` instead of `cloud/fix-message-volume`.** It
contains the volume fix and the whole test harness, and adds decoding of Rule Machine's Capture, Restore and
Set Colour, which HAI can now migrate (HAI-D57, HAI branch `hai-capture-restore-colour`). Everything below
applies to it; the extra check for the new decode is in step 1.

## 1. Deploy the volume fix to the Dev hub and verify it

Branch `cloud/capture-restore-colour` (it contains `cloud/fix-message-volume` and `cloud/test-harness`).
One change to `apps/automation_map.groovy`, in `hamDetailActionOperands`, case `getMsg`: a message now
publishes `volume` only when it has speakers and a volume is actually stored. Before, `hamDetailInt`
returning 0 for an empty value meant every message published a volume: 15 notify-only messages got a volume
of 0 that nothing stored, and 8 got a leftover `speakVolume` from a speaker since removed. Details:
`docs/cloud-task-test-harness-findings.md` on that branch, finding 1.

- Deploy it to the Dev hub with `deploy-hub.ps1` as usual, and run a scan.
- Check a notify-only message in `ham-decode-detail-dev.json` carries no `volume`, and a speaking one with a
  stored volume still does.
- Check rule 3593 (`_HAI Migration test simple`) in `ham-decode-detail-dev.json`: action 1 `getCapture` is
  `supported: true` with devices `[Gordon Study Desk]`; action 2 `getSetColor` has `colorMode: Green`,
  `hue: 33`, `saturation: 100`, `level: 100`; action 4 `getRestore` is `supported: true` with no devices.

## 2. Capture the published documents again

Copy the freshly published `ham-decode-detail-dev.json` and `ham-decode-dev.json` from the hub's File Manager
over `tests/fixtures/ham-decode-detail.json` and `tests/fixtures/ham-decode.json`.

This is a new capture from the hub, on Gordon's instruction - not the regeneration the brief forbids. The
fixtures stay evidence: never edit them by hand.

Then run `pwsh tests/run-all.ps1`. Expected:

- `ham-published-documents.groovy`: *no message without a speaker publishes a volume* now passes.
- *every condition device reference is {id, name}* stays red. That is agreed, not a regression: BACKLOG.md
  entry 55 and HAI-D56 move it at the next contract version.
- The exact counts in that suite (71 rules, 510 actions, 375 supported, 104 triggers, 169 conditions, 22
  waits, 83 control-flow records, 38 Set Private Boolean, 69 rule links) describe the old capture. If the hub's
  rules changed in between, a count will differ. Update it deliberately, and say in the commit why the hub
  now holds that number. Do not loosen a count to a range.

## 3. Merge into dev

Once 1 and 2 hold, merge `cloud/capture-restore-colour` into `dev`. It brings `cloud/fix-message-volume` and
`cloud/test-harness` with it. Then tell HAI: its HAI-D57 can proceed.
Commit the new fixtures in the same change, so the suite and its evidence arrive together.

## Also worth knowing

- HAI keeps its own copy of the detail capture at `engine/test/fixtures/ham-decode-detail-dev.json`. After
  step 2 it is one capture behind. Recommendation 7 in `docs/2026-10-05-ham-hai-architecture-review.md` is
  about keeping the two in step.
- The cloud session could not delete branches or reach the hub. Everything it did is on the branches named
  above; nothing is waiting in a container.
