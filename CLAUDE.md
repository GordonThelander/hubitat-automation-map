# Automation Map project instructions

## Read before planning work

- `docs/2026-10-05-ham-hai-architecture-review.md` - an architecture, usability and enhancement review of
  Automation Map and HAI, with 20 ranked recommendations weighed against the hub's measured limits and a
  suggested order. Written 2026-10-05 at Gordon's request. Recommendations, not decisions: check with
  Gordon before starting one. The same file is in the HAI repository as
  `ideation/2026-10-05-ham-hai-architecture-review.md`.
- `docs/cloud-task-test-harness-findings.md` - what the published-document assertions found against the
  captured decode files, and Gordon's decisions on them.

## Tests

`pwsh tests/run-all.ps1` runs every suite in `tests/` and exits non-zero on any failure. Run them on
Groovy 2.4 (the hub's version): several behaviours differ on newer Groovy. Suites slice real functions from
`apps/automation_map.groovy` through `tests/support/AppSource.groovy`; do not add a stub for a function the
app defines. `tests/fixtures/ham-decode*.json` are captured hub output - never regenerate or edit them to
make a test pass.
