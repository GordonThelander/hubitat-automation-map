# webCoRE census fixtures

Fixtures selected to the revised list from review 533/535. They prove the **extractor and lookup
model**, not per-member semantics: a fixture is deliberately NOT required for every member of a
large shared branch merely because it falls through. Broader per-member semantic fixtures belong to
later L3/L4 work.

Each file is a piston-document fragment. `tests/webcore-census-walker.groovy` runs the walker
extracted from the app source against every one of them.

| Fixture | Proves |
| --- | --- |
| `ordinary-members.json` | One ordinary member per saved-reachable dispatch site resolves to that site's construct |
| `empty-operand.json` | An empty `t` is a recognised member, and is distinguishable from a missing `t` |
| `shared-branch-numeric.json` | All five numeric members stay five distinct constructs despite shared conversion branches |
| `unknown-discriminator.json` | An unregistered discriminator yields `unknown-operand-type`, not silence |
| `nested-switch-trap.json` | An operand under an `on` statement's `c` is an event matcher; the same spelling under a `switch` statement's `lo` is not |
| `same-spelling-multisite.json` | `p` at two different sites resolves to two different constructs, proving lookup is context-qualified rather than a flat union |

## Increment 2 correction

The original fixtures encoded saved keys the pinned source does not use, and were rewritten:

- value types are read with `sMvt`, which is `vt`, not `ovt`;
- an expression result type is read with `sMt` on the node passed to `evaluateExpression`, not from
  an `exprType` key;
- a task carries `c` and `p`, and an expression is reached through an operand of type `e` at its
  `exp` key.

`expression.item.type` was also dropped from the coverage claim. `evaluateExpression` builds its
`items` list locally and pushes already-evaluated maps into it, so that dispatch reads runtime
results rather than saved nodes and no saved-document walker can reach it. The five numeric members
are still exercised, at `expression.evaluate.result-type`, which is where a saved item is actually
dispatched.

`manifest.json` records the same mapping machine-readably, including which construct IDs each
fixture is expected to produce and which frozen sites are saved-reachable.
