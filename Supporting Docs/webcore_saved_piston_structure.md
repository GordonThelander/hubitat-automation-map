# webCoRE saved piston structure: findings from decode coverage

## Purpose

Automation Map's Decode coverage card walks every part of a saved webCoRE piston and reports what it
recognises. Building that walker exposed facts about how webCoRE stores a piston that are not written
down anywhere else. This document records them, so that any tool reading saved pistons can rely on
evidence rather than on guesses.

All source references are to the webCoRE executor, `webcore-piston.groovy`, at
`imnotbob/webCoRE`, branch `hubitat-patches`, commit `0a37eee2537accd706aaaeeed5a7b4bb0c82646e`. The
key-by-key map with source anchors lives in
[`tools/webcore-investigation/saved-position-map.md`](../tools/webcore-investigation/saved-position-map.md).
Findings from live pistons were measured on a Hubitat C-8 development hub in September 2026.

## 1. The stored piston is not the in-memory piston

webCoRE's `cleanCode(item, inMem)` removes editor and default fields, but **only when `inMem` is
true**, meaning from the copy it holds in memory to run the piston. The stored document keeps them.

A tool that reads the saved configuration therefore sees keys the executor never looks at:

- `str` and `ok` on expressions;
- a string `l` on operands;
- `z` and `zc` comments;
- `w` warnings;
- default policies such as `ctp: 'i'`;
- empty lists.

Treat these as expected editor content, not as corruption.

## 2. Statement grammar

Keys each statement type uses, from `executeStatement`, `subscribeAll` and `cleanCode`.

**Keys any statement may carry:**

| Key | Meaning |
| --- | --- |
| `t` | statement type |
| `$` | statement number |
| `a` | `'1'` for asynchronous execution |
| `di` | disabled |
| `tcp`, `tep`, `tsp` | task cancellation, execution and scheduling policies |
| `r` | restrictions |
| `rop` | restriction operator |
| `rn` | restriction negation |
| `z`, `zc` | description and comments |

**Keys specific to one statement type:**

| Statement | Keys |
| --- | --- |
| `action` | `d` devices; `k` tasks, each with `c` command, `p` parameters and optional `m` mode restriction |
| `if` | `c` conditions, `o` operator, `n` negation, `s` then-statements, `ei` else-ifs (each with `c` and `s`), `e` else-statements |
| `while` | `c` conditions, `s` statements |
| `repeat` | `s` statements, `c` until-conditions evaluated after the body |
| `every` | `lo` timer operand, `lo2` and `lo3` for units of a day or longer, `s` statements |
| `on` | `c` **events**, each with `lo`; `s` statements |
| `each` | `lo` device list operand, `x` variable, `s` statements |
| `for` | `lo` start, `lo2` end, `lo3` step (default 1), `x` counter variable, `s` statements |
| `switch` | `lo` operand; `ctp` case traversal (`i` breaks after a case by default, `e` falls through); `cs` cases, each with `t` (`s` single value or `r` range), `ro`, `ro2` for a range, and `s`; `e` default |
| `do` | `s` statements |
| `break` | no keys of its own |
| `exit` | `lo` operand giving the piston state to set |

Conditions can carry `wt` (followed-by wait type: loose, strict or negated) and `wd` (followed-by
wait delay).

## 3. Keys whose meaning depends on position

- **A statement's `c` holds events under `on`, and conditions everywhere else.** `statementTraverser`
  routes `on` through `traverseEvents` and `if`, `while` and `repeat` through `traverseConditions`.
- **A root variable declaration's `v` is an operand.** `getLocalVariables` and `getVariable` evaluate
  it, so constructs inside an initializer are part of the piston.
- **A task parameter with no `t` is the unselected state of an optional parameter**, not an error.
  `cmd_setColor` and `vcmd_toggleRandom` both tolerate it.
- **A preset name `s` is meaningful only when the operand's `vt` is `time` or `datetime`.**
- **Condition `to` and `to2` are operands.**
- **Legacy SmartThings attribute names** are renamed by `fixAttr()` before dispatch.

## 4. What the subscription pass misses

`subscribeAll` is the fullest saved-tree traversal in the source, but it is a subscription pass, not
an execution pass. The executor also reads positions it never visits:

- `lo2` and `lo3` of `for` loops and of `every` timers;
- `ctp` on `switch`;
- `rn` on restrictions;
- `wt` and `wd` on followed-by conditions;
- `m` on tasks.

A structural reader built only from `subscribeAll` will mis-report pistons that use those
statements. Automation Map's walker currently has this gap, and pistons using these constructs show
unrecognised fields until the fix lands.

## 5. Expressions, and what the editor saves for an unknown function

An expression is stored as `exp` with `t` (result type) and `i` (items). A function call is an item
with `t: 'function'` and `n` (the function name).

**The editor never saves an unknown function call as a function node.** Typing a call to a function
webCoRE does not have, then saving, stores:

- the expression with `t`, `i`, `str`, `ok`, `err`, `errVar` and `loc`;
- the call as an item with `t: 'variable'`, `x` holding the attempted name, `ok: false` and an `err`
  saying the identifier was not found as a variable.

No item has `t: 'function'`. The editor shows a warning but saves the piston. A reader must not
expect an "unknown function" record from editor-saved pistons: it will see an invalid variable
wrapped in parser diagnostics.

## 6. How Automation Map uses this

The Decode coverage card, fetched only when its button is pressed, reports:

1. **Accounting.** Whether every object, array, field and value of the saved piston was visited.
2. **Recognition.** Construct positions recognised at evidence level L2 or above.
   - L1 means present in the webCoRE source vocabulary.
   - L2 means identified with a registry entry.
   - Higher levels need further evidence.
   - L0, constructs never seen in any test fixture, is never shown for a piston.
3. **Gaps.** Unrecognised positions, each as a structural path with a fixed reason. For example
   `$.s[0].k[0].p[1].exp.<unknown-key#0>` reports an unrecognised field without revealing its name
   or value.

**The percentage rule.** Unrecognised fields are not construct positions, so a piston can have every
construct recognised and still contain fields the walker does not understand.

- The percentage is shown only for a complete walk with nothing unidentified.
- Otherwise the card reads, for example, "Coverage incomplete. 13 construct positions recognised at
  L2 or above; 4 positions not identified."

**Measured cost.** On the development hub a coverage result is under 1KB and returns well within a
second, so results are not cached. The card never shows piston values, and nothing decoded is
written to the logs or the AI-friendly export.

## 7. Evidence base

- **Pinned executor source,** at the commit named above: `executeStatement`, `subscribeAll`,
  `cleanCode`, `scheduleTimer`, `executeAction`, `evaluateOperand` and `evaluateExpression`.
- **Six development pistons.** Their coverage matched expectations: every walk complete and every
  construct recognised.
- **One deliberately unrecognised fixture piston**, saved through the supported editor, which
  produced the stored shape described in section 5.
- **A relationship scan before and after the fixture work.** The export was unchanged apart from the
  added fixture app.
