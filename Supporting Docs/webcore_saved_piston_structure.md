# webCoRE pistons in Automation Map: saved structure and decoding reference

## Purpose and position

This is the reference for how Automation Map understands webCoRE pistons on Hubitat. It covers:

- how a piston is stored;
- which source the interpretation is pinned to;
- the grammar of the saved document;
- how Automation Map turns that document into relationships and coverage evidence;
- what it renders;
- where its boundaries are;
- how it handles privacy;
- what is implemented today.

It is not a guide to writing pistons, and it does not document the complete webCoRE execution engine.

webCoRE differs from Rule Machine from a discovery perspective. Hubitat exposes a webCoRE parent app
and one child app per piston, but the piston logic is stored as an encoded compiled document inside the
child app's settings. The parent's device selections are permissions. They show which devices webCoRE
may access, not which piston uses a device or how.

Automation Map therefore treats the saved piston document as the only authoritative source for
piston-level evidence. It decodes the document in memory, recognises a deliberately bounded set of
source-backed structures, emits only what those structures prove, and then discards the document. It
does not run pistons, read variable values, infer relationships from labels, or treat parent
permissions as use. It is a bounded structural decoder, not a webCoRE interpreter.

Two related documents cover narrower ground:

- [`webcore_hub_variable_decoding.md`](webcore_hub_variable_decoding.md), a focused explanation of Hub
  Variables in webCoRE pistons;
- [`webcore_piston_devices_and_local_variables_spec.md`](webcore_piston_devices_and_local_variables_spec.md),
  the implementation record for piston-local variables and direct device relationships.

## 1. Source lineage and pins

Every interpretation here is pinned to the Hubitat webCoRE port at
[`imnotbob/webCoRE@0a37eee2537accd706aaaeeed5a7b4bb0c82646e`](https://github.com/imnotbob/webCoRE/commit/0a37eee2537accd706aaaeeed5a7b4bb0c82646e),
branch `hubitat-patches`, committed 2026-08-08. Three parts of that commit are used:

- **Parent app:** [`webcore.groovy`](https://github.com/imnotbob/webCoRE/blob/0a37eee2537accd706aaaeeed5a7b4bb0c82646e/smartapps/ady624/webcore.src/webcore.groovy),
  which publishes Hub Variables and holds the permitted devices.
- **Piston executor:** [`webcore-piston.groovy`](https://github.com/imnotbob/webCoRE/blob/0a37eee2537accd706aaaeeed5a7b4bb0c82646e/smartapps/ady624/webcore-piston.src/webcore-piston.groovy),
  which reads the saved document.
- **Dashboard editor source:** [`dashboard/`](https://github.com/imnotbob/webCoRE/tree/0a37eee2537accd706aaaeeed5a7b4bb0c82646e/dashboard),
  which writes it.

The hosted editor at `dashboard.webcore.co` reported IDE version `v0.3.114.20220203` when the fixture
pistons below were saved.

Naive clones of the upstream repository land on SmartThings-era code from 2019. Always resolve the
`hubitat-patches` branch and record the commit.

The key-by-key map of saved positions, with source anchors, is in
[`tools/webcore-investigation/saved-position-map.md`](../tools/webcore-investigation/saved-position-map.md).

## 2. The webCoRE model on Hubitat

| Layer | Hubitat representation | What it proves |
| --- | --- | --- |
| webCoRE parent | Main installed app | webCoRE installation, shared configuration, and permitted devices |
| Piston | Child installed app | Ownership, piston identity, status, and saved settings |
| Compiled piston | Encoded JSON in the child app's `chunk:N` settings | The piston's declarations, operands, conditions, actions, and other compiled logic |

A device listed in the parent is available to webCoRE, but that does not prove any piston uses it.
Piston-level evidence must come from the compiled piston.

A paused piston keeps its saved configuration, so static discovery works the same for paused pistons.

## 3. How a piston is stored

The compiled configuration is saved as text settings named `chunk:0`, `chunk:1`, and so on. Hubitat
exposes them through the read-only endpoint:

```text
GET /installedapp/statusJson/<installedAppId>
```

Together, the chunks in `appSettings` hold one Base64 representation of the compiled piston. Base64 is
an encoding, not encryption. Decoding as UTF-8, applying webCoRE's emoji conversion, and parsing
produces the JSON document the runtime uses.

Automation Map accepts that document only within strict bounds:

1. setting names must exactly match `chunk:<non-negative integer>`;
2. `chunk:0` must be present, in one contiguous numerical sequence;
3. duplicate, missing, empty, excessive, or oversized chunks are rejected;
4. chunks are concatenated in numerical order;
5. the result is Base64-decoded as UTF-8 and emoji-decoded;
6. the JSON must parse to a map at the root;
7. only supported evidence is retained, and the decoded document is discarded.

The compiled JSON is an internal representation, not a stable interchange format. Automation Map only
recognises structures verified against the pinned source and test data.

### The stored piston is not the in-memory piston

webCoRE's `cleanCode(item, inMem)` removes editor fields and defaults **only when `inMem` is true**,
from the copy it keeps in memory to run the piston. The stored document keeps them, so a reader of the
saved configuration sees keys the executor never uses:

- `str` and `ok` on expressions;
- a string `l` on operands;
- `z` and `zc` comments;
- default policies such as `ctp: 'i'`;
- empty lists.

Treat these as expected editor content, not corruption.

### Opening a piston in the IDE changes what a later save persists

The IDE never edits the stored settings directly. Opening a piston asks the child app for it, and the
copy Hubitat returns has already been processed:

- `recreatePiston` rebuilds the piston from its settings and numbers every node with `msetIds`, so
  statements, else-ifs, cases, tasks, conditions, groups, events and restrictions all carry `$`;
- the subscription pass, `subscribeAll`, then writes `ct` (trigger or condition) on conditions, events
  and switch statements, a subscription flag `s` on subscribed nodes, and `w` warnings.

The editor keeps what it received. Its serializer, `compilePiston`, deletes `w` and every false, null or
empty value before saving, so a later save persists `$` on every node that existed when the piston was
opened, and `ct` and a true `s` on the nodes the subscription pass marked. A node added since then has
none of them, and `w` is never saved.

None of these saved values is trusted on the next load. `clearMsetIds` nulls every `$` before the tree
is renumbered, and `subscribeAll` recomputes `s` everywhere and `ct` on conditions and switch
statements. Only an event's saved `ct` is kept, because the pass sets it only when absent, and its only
value is `t`.

A saved `ct` can therefore be stale. Editing a condition after the piston was opened keeps the `ct` it
had when opened, so changing a condition's comparison to a trigger saves `ct: 'c'`, and only the next
load and save writes `t`. Read a saved `ct` as what the last load computed, not as the current
comparison type, and treat the settings saved after an unchanged reopen as the canonical form.

## 4. Statement grammar

These are the keys each statement type uses, from `executeStatement`, `subscribeAll` and `cleanCode`.

Any statement may carry:

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

| Statement | Keys |
| --- | --- |
| `action` | `d` devices; `k` tasks, each with `c` command, `p` parameters and optional `m` mode restriction |
| `if` | `c` conditions, `o` operator, `n` negation, `s` then-statements, `ei` else-ifs (each with `c` and `s`), `e` else-statements |
| `while` | `c` conditions, `s` statements |
| `repeat` | `s` statements, `c` until-conditions evaluated after the body |
| `every` | `lo` timer operand, `lo2` and `lo3` timer operands, `s` statements |
| `on` | `c` **events**, each with `lo`; `s` statements |
| `each` | `lo` device list operand, `x` variable, `s` statements |
| `for` | `lo` start, `lo2` end, `lo3` step (default 1), `x` counter variable, `s` statements |
| `switch` | `lo` operand; `ctp` case traversal (`i` breaks after a case by default, `e` falls through); `cs` cases, each with `t` (`s` single value or `r` range), `ro`, `ro2` for a range, and `s`; `e` default |
| `do` | `s` statements |
| `break` | no keys of its own |
| `exit` | `lo` operand giving the piston state to set |

For conditions and tasks:

- A **condition** has `t` (`condition` or `group`), `co`, `lo`, `ro`, `ro2`, `to`, `to2`, `c`
  subconditions, `ts`, `fs`, `sm` and `ct`.
- A **step of a followed-by list** also carries `wt`, the wait type (loose, strict or negated), and
  `wd`, the wait delay.
- A **task parameter** is evaluated as an operand, plus `vt`.

### Keys whose meaning depends on position

- **`c` on a statement** holds **events** under `on`, and conditions everywhere else.
  `statementTraverser` routes `on` through `traverseEvents` and `if`, `while` and `repeat` through
  `traverseConditions`.
- **Followed-by steps.** A node whose operator `o` is `followed by` turns its `c` list into ladder
  steps. Only those steps have a `wd` wait delay that the executor evaluates as an operand.
- **`lo2` and `lo3`** are operands only under `for`, via `evalDecimalOperand`, and under `every`,
  where `scheduleTimer` passes them to `evalRO1`.
- **A task's `m`** is a list of mode ids checked by `executeTask`. It is not a device list.
- **A root variable declaration's `v` is an operand.** `getLocalVariables` and `getVariable` evaluate
  it, so constructs inside an initializer are part of the piston.
- **A task parameter with no `t`** is the unselected state of an optional parameter, not an error.
  `cmd_setColor` and `vcmd_toggleRandom` both tolerate it.
- **A preset name `s`** is meaningful only when the operand's `vt` is `time` or `datetime`.
- **Condition `to` and `to2`** are operands.
- **Legacy SmartThings attribute names** are renamed by `fixAttr()` before dispatch.

### The subscription pass is not the whole grammar

`subscribeAll` is the fullest saved-tree traversal in the source, but it is a subscription pass. The
executor also reads positions it never visits:

- `lo2` and `lo3`;
- `ctp`;
- `rn`;
- `wt` and `wd`;
- a task's `m`.

A reader built only from `subscribeAll` would mis-report pistons that use those constructs. Automation
Map's coverage walker initially had that gap; it now reads each of these keys in the position the
executor does.

### Expressions, and what the editor saves for an unknown function

An expression is stored as `exp` with `t` (result type) and `i` (items). A function call is an item with
`t: 'function'` and `n` (the name).

**The editor never saves an unknown function call as a function node.** Typing a call to a function
webCoRE does not have, and saving, stores:

- the expression with `t`, `i`, `str`, `ok`, `err`, `errVar` and `loc`;
- the call as an item with `t: 'variable'`, `x` holding the attempted name, `ok: false` and an `err`
  saying the identifier was not found as a variable.

No item has `t: 'function'`. The editor warns but saves.

### Operand grammar

Every operand discriminates on `t`, against a closed vocabulary the executor itself validates on
`cleanCode` (`ListAL`): `p`, `d`, `v`, `s`, `x`, `c`, `e`, `u`, plus the empty (nothing-selected) form,
nine values at the general `evaluateOperand` dispatch. A separate `on`-statement event matcher
discriminates the same way on `p`, `v`, `x` again, but at a distinct saved parent position, so those
three are tracked as three further registered kinds rather than folded into the general nine. Twelve
operand kinds in total.

Structural (L3) evidence exists for six of the twelve so far: constant (`t: 'c'`), virtual (`t: 'v'`),
variable (`t: 'x'`), expression (`t: 'e'`), physical-device (`t: 'p'`) and preset (`t: 's'`). The other
four, a bare device-list operand (`t: 'd'`), argument (`t: 'u'`), the three event-match forms, and the
empty form, have zero occurrences anywhere in the captured fixture corpus and remain unverified; every
saved device list on an `action` statement observed so far is either empty or the single static target
added for the action-targeting increment (section 4, `action`). As of 2026-09-12 all six proven kinds
are raised to L3 in the construct registry itself, through the same committed-metadata promotion gate
the statement grammar uses; the other four stay L2. No operand-meaning (L4) claim exists yet, so the
raised level is not yet consumed by anything beyond the registry and the decode-coverage card's own
recognition count.

| Kind | Discriminator | Always-persisted keys | Notes |
| --- | --- | --- | --- |
| constant | `t: 'c'` | `vt`, `c`, `exp` | `c` is exclusive to constant; `exp` is shared with expression only |
| virtual | `t: 'v'` | `vt`, `v` | `v` is one of a closed ~24-value case list (mode, HSM, system events, and similar); exclusive to virtual |
| variable | `t: 'x'` | `vt`, `x` | `x` is the referenced variable name; a leading `@` names a webCoRE global, `@@` a Hubitat Hub Variable, confirmed again here at `evaluateOperand`; exclusive to variable |
| expression | `t: 'e'` | `vt`, `exp` | shares `exp` with constant; its own `e` key is saved but not read by `evaluateOperand` in the reviewed region, purpose unproven |
| physical-device | `t: 'p'` | `vt`, `a`, `d` | `a` (the attribute name) is exclusive to physical-device; `d` (the device list) is not exclusive to it, since `cleanCode` only strips `d` for the kinds in `ListC2`, which excludes both `p` and `d` |
| preset | `t: 's'` | `vt`, `s` | `s` (the preset name: sunset, sunrise, midnight or noon) is exclusive to preset, and meaningful only when `vt` is `time` or `datetime`; otherwise passed through unvalidated |

Each kind also carries `f` (format) and `g` (grouping function) when non-default, and `vt` itself is a
value-type discriminator whose closed vocabulary is not yet reconciled against the executor. A saved
physical-device operand can also carry a user-optional `p` (a physical/digital/any read preference for
attributes that offer the choice); no captured occurrence has exercised it yet, so its saved shape is
unproven. All six follow the same exclusivity rule already established for statement keys: a key
belongs to exactly one `t`, stripped unconditionally from every other kind by `cleanCode`, except `exp`,
which two kinds share (`ListEC = [e, c]`). As with statement keys, this exclusivity is proven for
`cleanCode`'s unconditional strip on load (`recreatePiston`), not for the editor's own save; a `d` list
has been observed left over on both a `v`-type and a `c`-type operand in an `edit-save` capture, absent
again after the next round-trip. Treat a stray key on an editor-save-only capture as expected cruft, not
as a broken exclusivity claim.

## 5. How Automation Map classifies evidence

### 5.1 Variables

| Form | Meaning on Hubitat webCoRE | Automation Map treatment |
| --- | --- | --- |
| `name` | Piston-local variable when declared by that piston | Owner-scoped Local Variable |
| `@name` | webCoRE global variable | Not currently mapped |
| `@@name` | Hubitat Hub Variable exposed through webCoRE | Shared Hub Variable, see the focused Hub Variable paper |

Local declarations live in the root `v` array, each with a name in `n` and a webCoRE type in `t`. They
belong to one piston, so the same name declared in two pistons is two Local Variables. A declared local
that is never read or written still appears, as unreferenced. Variable operands use `t: "x"`.

Direction comes from structural position, never from names or values:

| Saved context | Classification | Runtime basis |
| --- | --- | --- |
| Variable evaluated in a condition, calculation, expression, assigned value, or variable-backed device selection | Read | `getVariable()` |
| First variable parameter of a `setVariable` task | Write | the destination passed to `setVariable()` |
| Variable used as the value assigned to another variable | Read | evaluated before assignment |
| `for` or `each` loop counter | Write | the loop updates it |
| Matching or non-matching device capture variable on a physical condition | Write | webCoRE stores the condition result there |
| Static destination of an expression-form `setVariable()` call | Write | the first argument resolves to one literal target |

Classifications accumulate, so a variable in both positions gets both a read and a write. A
destination constructed dynamically at runtime names no definite target and is omitted.

### 5.2 Direct device evidence

- **Physical-device reads.** A map with `t: "p"` is a physical-device operand: `d` holds device
  references and `a` the attribute. `evaluateOperand()` expands the list and calls
  `getDeviceAttribute(...)`. This proves a read, but not whether it serves as a trigger, condition,
  constraint or monitor, so Automation Map uses a neutral device-read relationship.
- **Device actions.** A map with `t: "action"` is an action statement: `d` holds targets, `k` holds
  tasks, and each task's `c` is its command. `executeAction()` expands the targets and passes each task
  to `executeTask()`. Command names may be kept as bounded evidence; parameter values never are.

### 5.3 Resolving device references

Direct device nodes hold a colon-wrapped hash, not a Hubitat device ID:

```text
":" + lowercaseHex(MD5(UTF8("core." + deviceId))) + ":"
```

The hash is not reversed. Automation Map applies the same function to the parent webCoRE app's
permitted device IDs and matches. A reference is accepted only when all of these hold:

1. the token is the exact colon-wrapped 32-character hexadecimal form;
2. exactly one permitted device ID produces it;
3. that candidate belongs to the piston's own parent webCoRE instance;
4. the device is in Automation Map's scanned device inventory.

A missing or ambiguous match creates no relationship. Automation Map never resolves by label and never
creates a device node from an unmatched hash.

### 5.4 Decode coverage

The Decode coverage card walks the whole saved piston when its button is pressed, and reports three
things.

1. **Accounting.** Whether every object, array, field and value was visited.
2. **Recognition.** Construct positions recognised at evidence level L2 or above.
   - L1 means present in the source vocabulary.
   - L2 means identified by a registry entry.
   - L3 and above need further evidence.
   - L0, never seen in any test fixture, is never shown for a piston.
3. **Unidentified positions.** Each is a structural path with a fixed reason, and never reveals a
   field name or value from the piston:
   - an unrecognised field is shown as a placeholder such as
     `$.s[0].k[0].p[1].exp.<unknown-key#0>`;
   - a source-known editor or data field (`zc` comments, `data`) is reported once as "Opaque field,
     not interpreted". Its contents are still traversed for accounting and to enforce the depth,
     value and time bounds, but they are never interpreted, classified or reported.

**The percentage rule.** Unrecognised fields are not construct positions, so a piston can have every
construct recognised and still contain fields the walker does not understand.

- The percentage is shown only for a complete walk with nothing unidentified.
- Otherwise the card reads, for example, "Coverage incomplete. 13 construct positions recognised at
  L2 or above; 4 positions not identified".

Editor diagnostics (`err`, `errVar`, `loc`) are deliberately left as unrecognised fields, because they
mark saved structure outside the runtime grammar.

## 6. What Automation Map renders

Each piston is the automation owner. The webCoRE parent is visible as the owning application, but its
permissions are never rendered as device use.

| Evidence | Map relationship | Arrow direction |
| --- | --- | --- |
| Evaluated Hub or Local Variable | Read | Variable to piston |
| Hub or Local Variable destination | Write | Piston to variable |
| `t: "p"` physical-device operand | Device read | Device to piston |
| `t: "action"` direct target | Action | Piston to device |

When one piston both reads and acts on a device, or both reads and writes a variable, both
relationships are kept.

The same evidence is reflected in focus, search, pivot tables, Insights and the AI-friendly export.
Complete webCoRE flow is not implied by these relationships.

## 7. Boundaries

Automation Map does not create a fixed relationship for:

- a variable-backed or otherwise runtime-selected device list;
- the device that generated the current event;
- a webCoRE virtual device or location operand;
- an unrecognised or malformed node;
- a hash that cannot be reconciled uniquely with the correct parent inventory.

It does not infer complete IF/ELSE flow, evaluation order, schedules, delays, cancellation behaviour, or
the exact role of a physical read.

Unsupported static forms produce bounded coverage information. Malformed encoding or JSON produces a
fixed decoder error and a `complete-with-gaps` scan. One malformed piston never stops other apps being
discovered. These omissions keep the picture incomplete rather than wrong.

## 8. Privacy and data handling

The decoder needs structure, not values. Automation Map does not log, cache, render or export:

- the decoded piston document;
- raw Base64 chunks;
- unmatched device hashes;
- Hub, global or local variable values;
- action parameter values;
- local network details.

The decoded document exists only in memory during classification. Discovery uses read-only Hubitat
data and adds no state-changing endpoint. Coverage results carry no piston values, and nothing decoded
reaches the logs or the export.

## 9. Evidence base

- **Pinned source.** The functions named throughout: `evaluateOperand`, `evaluateExpression`,
  `executeStatement`, `executeAction`, `executeTask`, `scheduleTimer`, `evaluateConditions`,
  `subscribeAll`, `cleanCode`, `expandDeviceList`, `getVariable` and `setVariable`.
- **Hub Variable direction pistons.** Read-only, write-only, read-one/write-another and
  read-and-write-the-same-variable pistons, with classifications unchanged while paused.
- **Device evidence.** The physical-read, direct-action and device-hash interpretations were
  reproduced on a live piston. The resolved devices matched the stored attribute and command.
- **Coverage.** Six development pistons all walked completely with every construct recognised. A
  deliberately unrecognised fixture piston saved through the supported editor produced the stored
  shape in section 4.
- **Scans.** Relationship scans before and after the fixture work left the export unchanged apart from
  the added fixture app.

Measured on a Hubitat C-8 development hub, 2026-09.

## 10. Implementation status

- **Automation Map v2.2.8:**
  - Hub Variable discovery and direction;
  - piston-local variables as owner-scoped nodes;
  - direct device reads and actions resolved through webCoRE's own hashing;
  - per-piston device relationship coverage (complete, partial, none or error).

  The v2.2.7 device ringfence was removed, and the parent still receives no device edges. Graph schema
  14, export schema 12.
- **Automation Map Dev v2.2.9:**
  - the Decode coverage card, with its accounting, recognition at L2 and above, fixed-reason gaps and
    the percentage rule;
  - reading of `lo2`, `lo3`, `ctp`, `rn`, `wt`, `wd` and `m` in the positions the executor uses;
  - opaque-field reporting.

  Coverage results are not cached. The endpoint applies fixed traversal, output and time bounds.
  Measured on the development hub, results were under 1KB and returned within a second; that is
  observed evidence, not a guarantee.
- **Statement structural and semantic evidence:** all twelve registered statement types (`action`,
  `if`, `while`, `repeat`, `every`, `on`, `each`, `for`, `switch`, `do`, `break`, `exit`) have
  source-cited structural (L3) evidence and at least one proven semantic (L4) claim, each gated on a
  hand-authored trace, a hashed pinned-source region and a canonical (round-trip) fixture with proven
  save/reload lineage.
- **Operand structural evidence:** six of twelve kinds (see "Operand grammar" above), now raised to L3
  in the construct registry. **In progress:** the remaining four operand kinds, and operand semantic
  (L4) meaning once an operand kind is L3-proven. Not yet started: the runtime coverage walker doing
  anything with the raised operand levels beyond reporting them.
