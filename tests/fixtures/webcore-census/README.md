# webCoRE census fixtures

Increment 1 fixtures, selected to Codex's revised list in queue 533/535. They prove the
**extractor and lookup model**, not per-member semantics: a fixture is deliberately NOT required for
every member of a large shared branch merely because it falls through. Broader per-member semantic
fixtures belong to later L3/L4 work.

Each file is a piston-document fragment. The walker does not exist yet (Increment 2), so these are
data for it plus a validity check now.

| Fixture | Proves |
| --- | --- |
| `ordinary-members.json` | One ordinary member per dispatch site resolves to that site's construct |
| `empty-operand.json` | `t:''` is a recognised member, and is distinguishable from a missing `t` |
| `shared-branch-numeric.json` | All five members of `expression.item.type` stay five distinct constructs despite one shared branch |
| `unknown-discriminator.json` | An unregistered discriminator yields `unknown-operand-type`, not silence |
| `nested-switch-trap.json` | The event matcher nested inside `case sON` is not confused with the statement switch |
| `same-spelling-multisite.json` | `p` at two different sites resolves to two different constructs, proving lookup is context-qualified rather than a flat union |

`manifest.json` records the same mapping machine-readably, including which construct IDs each
fixture is expected to produce once the walker exists.
