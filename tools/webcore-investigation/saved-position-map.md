# webCoRE saved-position map

Increment 1 froze **where a value is dispatched in the pinned source**. That is not the same
question as **where the value sits in a saved piston document**, and the census walker only sees the
saved document. This file records the second mapping, derived from named sites in the pinned source
rather than assumed from the shape of a hand-built fixture.

Pinned source: `imnotbob/webCoRE` @ `hubitat-patches` @
`0a37eee2537accd706aaaeeed5a7b4bb0c82646e`, file
`smartapps/ady624/webcore-piston.src/webcore-piston.groovy`.

Key names come from the `@Field static final String s*` constants, so `sMt(m)` is `m['t']`,
`sMvt(m)` is `m['vt']`, `sMv(m)` is `m['v']`, `sMs(m, sS)` is `m['s']`, `sEXP` is `'exp'` and
`sEXPR` is `'expression'`.

## 1. The saved tree, as webCoRE itself walks it

`subscribeAll` is the authoritative saved-tree traversal: it walks a document that has just been
loaded, before any evaluation. Its closures name every structural key.

| Node | Keys | Anchor |
| --- | --- | --- |
| root | `r` restrictions, `s` statements, `v` variable declarations | `r9p[sR]`, `r9p[sS]`, `oMv(r9p)` |
| variable declaration | `n` name, `t` variable data type, `v` initializer operand | `getLocalVariables`, `getVariable`, `subscribeAll` |
| statement | `t`, `d`, `a`, `r`, `rn`, `c`, `s`, `e`, `ei`, `cs`, `lo`, `lo2`, `lo3`, `ctp`, `k`, `o`, `w`, `ct`, `di`, `tep`, `tsp`, `tcp` | `statementTraverser`, `traverseStatements`, `executeStatement`, `scheduleTimer`, `cleanCode` |
| else-if | `c` conditions, `s` statements | `for(Map ei in liMs(node,sEI))` |
| switch case | `ro`, `ro2` when the case type is `r`, `s` statements | `for(Map c in liMs(node,sCS))` |
| condition | `t` (`condition` or `group`), `co`, `lo`, `ro`, `ro2`, `to`, `to2`, `c` subconditions, `ts`, `fs`, `sm`, `ct`, `wt`, `wd` | `traverseConditions`, `conditionTraverser`, `evalRO1`, followed-by ladder |
| event | `lo` | `eventTraverser` |
| restriction | `t` (`restriction`), `co`, `lo`, `ro`, `ro2`, `r` | `traverseRestrictions`, `restrictionTraverser` |
| task | `c` command, `p` parameters, `m` mode restriction | `for(Map k in liMs(node,sK))`, `executeTask` |
| parameter | evaluated as an operand, plus `vt` | `executeTask`, `mevaluateOperand(r9,prm)` |
| operand | `t`, then `d`/`a`/`p` for `p`, `v` for `v`, `s`+`vt` for `s`, `c`+`vt` for `c`, `x`/`xi` for `x`, `exp` for `e` | `evaluateOperand` |
| expression | `t` result type, `i` items | `evaluateExpression`, `case sEXPR` |
| expression item | `t`; `n` when `t` is `function`; `i` for nested items | `case sFUNC`, `case sEXPR` |

**`subscribeAll` is not the whole grammar.** It is a subscription pass, and the executor reads
positions it never visits:

- `lo2` and `lo3` are operands for `for` (`evalDecimalOperand` in `executeStatement`) and for `every`
  (`scheduleTimer` passes them to `evalRO1`), and nowhere else;
- `ctp` is `switch`'s case traversal policy (`i` breaks after a case by default, `e` falls through);
- `rn` negates a statement's restrictions;
- `wt` is a followed-by step's wait type, a scalar, and `wd` is its wait delay, evaluated with
  `mevaluateOperand` on a condition node;
- `m` is a task's mode restriction list, checked in `executeTask`, and is not a device list.

`cleanCode` removes editor fields only when `inMem` is true, so the stored document keeps them.

**A root variable declaration's `v` is an operand.** Three anchors, and the general rule rests on
the first two rather than the third:

- `getLocalVariables` evaluates a constant initializer with
  `oMv(evaluateExpression(r9, mevaluateOperand(r9, mMv(var)), t))`;
- `getVariable` evaluates a Map-valued local variable with `mevaluateOperand(r9, mMv(res))` when it
  is read;
- `subscribeAll` calls `operandTraverser(variable, mMv(variable), ...)`, but only on its
  device-selector path, and not for every direct device identifier. It is corroborating evidence for
  the subscription pass, not the reason the rule holds.

Constructs used inside a saved initializer are therefore part of the document and are classified.
The declaration's own `t` is a variable data type rather than an operand discriminator and is
deliberately not classified: no frozen site dispatches on it, and several of its spellings collide
with `expression.evaluate.result-type` members.

**A statement's `c` is context-dependent.** `statementTraverser` routes `case sON` through
`traverseEvents` and `case sIF`/`sWHILE`/`sREPEAT` through `traverseConditions`. So the same key
holds events under an `on` statement and conditions everywhere else. That is the single fact the
`nested-switch-trap` and `same-spelling-multisite` fixtures exist to pin down.

## 2. Frozen dispatch site to saved position

| Frozen site | Saved position | Reachable |
| --- | --- | --- |
| `statement.subscribe.type` | statement `t` | yes |
| `statement.subscribe.timer-type` | statement `t`, subscription pass only | consumer only |
| `operand.evaluate.type` | operand `t` | yes |
| `operand.subscribe.type` | operand `t`, subscription pass only | consumer only |
| `operand.event-match.type` | operand `t` under an `on` statement's event `lo` | yes |
| `virtual-device.evaluate.name` | operand `v` where `t` is `v`, after `fixAttr` | yes |
| `virtual-device.subscribe.name` | same node, subscription pass only | consumer only |
| `preset.evaluate.name` | operand `s` where `t` is `s` | yes |
| `preset.evaluate.value-type` | operand `vt` where `t` is `s` | yes |
| `constant.evaluate.value-type` | operand `vt` where `t` is `c` | yes |
| `expression.evaluate.result-type` | `t` of the node passed to `evaluateExpression` | yes |
| `task.variable.value-type` | parameter `vt` | yes |
| `expression.item.type` | none | **not saved-reachable** |

### Why `expression.item.type` is not saved-reachable

`case sEXPR` declares `List<Map> items; items=[]` and fills it with `items.push(tmap)` where `tmap`
is the result of `evaluateExpression(r9, item)`. The dispatch frozen at that site therefore reads
already-evaluated maps, not saved nodes. A saved item is dispatched one level earlier, at
`expression.evaluate.result-type`, whose member list already contains the same numeric spellings.

The site stays in the registry as source evidence. It is simply outside what a structural census of
a saved document can observe.

## 3. Normalisations the walker must mirror

**`fixAttr`.** A virtual-device name is renamed before dispatch: the four `threeAxis` spellings
(`orientation`, `axisX`, `axisY`, `axisZ`) collapse, and the five `alarmSystem*` names become their
`hsm*` equivalents. Without this, every SmartThings-era HSM operand reads as unknown.

**The empty operand type.** `''` is an explicit member of `operand.evaluate.type`. The registry
generator names it `empty` (`String label = (m == '') ? 'empty' : m`), so the walker applies the same
transform. A *missing* `t` is a different thing and is reported as `malformed-node`.

**Function names.** The registry generator matched case-insensitively on both sides, so the lookup
does too.

**A preset name is gated by the value type.** `case sS:` dispatches on `ovt` first, and only the
`time` and `datetime` branches reach the preset-name switch. Every other value type takes that
switch's `default:` and uses `s` as a raw value, so a `setColor` parameter saved as
`{t:'s', vt:'color', s:<colour>}` is not a missing preset. Found on a real piston, not in a fixture.

**A condition's `to` and `to2` are operands.** `evalRO1` passes `mMs(cndtn,sTO)` straight to
`mevaluateOperand`, so a comparison offset carries constructs like any other operand.

**An optional task parameter is saved with no discriminator.** `executeTask` evaluates every saved
parameter through `mevaluateOperand`, and `evaluateOperand` switches on `sMt(operand)` with no
default, so a parameter Map without `t` matches no case and yields a dynamic null rather than
throwing. That null is the unselected state of an optional parameter, not an error: `cmd_setColor`
reads position 1 as the optional "only if switch is" enum and `ntMatSw()` skips the restriction when
it is null, and `vcmd_toggleRandom` falls back to 50 when its optional probability does not cast.

It is therefore registered as the structural construct `wc.task-parameter.unselected`, scoped to the
task-parameter position. A missing `t` anywhere else stays `malformed-node`, which is what the
`empty-operand` fixture's absent case continues to prove. Observed on two of the six Dev pistons and
confirmed against both consumers before being registered.

**Defaulted sites.** `preset.evaluate.value-type`, `constant.evaluate.value-type` and
`task.variable.value-type` each carry a default branch. Only an explicit member is a distinct
construct; every other invocation, including a missing, null or non-String value, still reaches the
source default and is counted as a default-branch occurrence rather than reported as a gap.

## 4. Positions deliberately not claimed

**Virtual commands.** A task's `c` reaches `executeVirtualCommand` only when the target device does
not itself have a command of that name (`wdeviceHascommand(device, command)` in `executeTask`).
Membership of `virtualCommands()` is therefore not sufficient to prove the virtual path runs, and a
name absent from it is an ordinary device command rather than an unknown virtual command. Task
commands are not counted as candidates, and `unknown-virtual-command` stays reserved.

**Policy values.** `tep`, `tsp` and `tcp` are registered as keys, not as value populations, so the
walker identifies the key and does not judge the value. `unknown-policy-value` stays reserved.

**Variable-backed device selectors.** `expandDeviceList` chooses between the device-list and
name-cast forms from live variable state. A `d` entry that is neither a direct identifier nor empty
is therefore recorded as `unknown-device-selector` rather than assigned to one of the two forms.

## 5. Path key allowlist

The walker's allowlist is exactly the keys named above:

```
$ a c ced co cs ct ctp cto d di e ei exp f fs g i id k l lo lo2 lo3 m n o ok p r rn ro ro2 rop s
sm str t tcp tep to to2 ts tsp v vt w wd wt x xi z
```

`ctp`, `lo2`, `lo3`, `m`, `rn`, `wd` and `wt` were added after tracing them to the executor, as set out
in section 1. `zc` (comments) and `data` are source-known but never interpreted. They are reported with
the fixed reason `known-opaque-field`, so they count as unidentified without being mistaken for
unknown structure. `u`, `pr` and `os` are not added until a saved position and a runtime consumer are
traced. Editor diagnostics (`err`, `errVar`, `loc`) stay unknown fields.

Nine of these were added after the first Dev-hub run, which produced 91 `unknown-key` records across
six real pistons and no user-derived key at all. Each was traced to a named site before being added:
`$` a saved node id (`node[sDLR]`), `cto` and `ced` piston options read through `gtPOpt`, `to` and
`to2` comparison offset operands (`evalRO1`), `rop` a restriction grouping, and `l`, `ok` and `str`
saved item fields stripped at load. The allowlist failing safe is what made that investigation
possible without a single value leaving the hub.

It fails safe. An omitted key costs path legibility and can never leak a value, because any key not
on the list is rendered as `<unknown-key#N>` where N is the sibling ordinal.
