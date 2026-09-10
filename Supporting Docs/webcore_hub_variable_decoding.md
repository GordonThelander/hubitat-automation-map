# Hub Variables in webCoRE pistons

## Purpose

This paper explains one part of webCoRE on Hubitat: how a piston uses Hubitat Hub Variables, and how
Automation Map proves which pistons read or write them.

How pistons are stored, the source pins, the full saved grammar, local variables, device relationships,
decode coverage, privacy and implementation status now live in the canonical reference,
[`webcore_saved_piston_structure.md`](webcore_saved_piston_structure.md). This page keeps its original
address because it has already been shared, and points there for everything outside Hub Variables.

## The `@@` namespace on Hubitat

Generic webCoRE terminology calls `@name` a global and `@@name` a superglobal. The Hubitat port gives
the double-at namespace a concrete integration role. The parent app's `AddHeGlobals()` calls Hubitat's
`getAllGlobalVars()` and publishes each Hub Variable to webCoRE as `@@<name>`.

In the piston runtime, `getVariable()` and `setVariable()` strip the two leading characters before
calling Hubitat's global-variable API. A single-at name remains a webCoRE global and is not a Hub
Variable.

| Form | Meaning on Hubitat webCoRE | Automation Map treatment |
| --- | --- | --- |
| `@@name` | Hubitat Hub Variable exposed through webCoRE | Shared Hub Variable |
| `@name` | webCoRE global variable | Not currently mapped |
| `name` | Piston-local variable when declared by that piston | Owner-scoped Local Variable, see the canonical reference |

## When a reference counts as a Hub Variable

An `@@` name counts only when all three hold:

1. it appears as a structurally typed variable operand;
2. exactly two prefix characters are stripped;
3. the remaining name matches the authoritative Hub Variable inventory Hubitat returns.

Plain text containing `@@`, or a name absent from that inventory, never creates a Hub Variable
relationship. When authoritative inventory is unavailable for a scan, no Hub Variable node is
manufactured from a bare reference.

## How direction is proven

Read or write direction comes from where the variable sits in the saved piston, never from its name or
value.

- **Reads:** any evaluated position, such as a condition, calculation, expression or assigned value.
- **Writes:**
  - the first variable parameter of a `setVariable` task;
  - a loop counter;
  - a device capture variable;
  - a static `setVariable()` expression target.

The canonical reference has the full table with runtime anchors.

A variable found in both positions gets both a read and a write relationship. A destination
constructed dynamically at runtime names no definite Hub Variable and is omitted. It is never guessed.

## How Hub Variable relationships appear

| Evidence | Map relationship | Arrow direction |
| --- | --- | --- |
| Evaluated `@@` Hub Variable | Read | Variable to piston |
| `@@` Hub Variable destination | Write | Piston to variable |

The same relationships appear in the piston's "Variables used by this automation" list, tagged `[HVR]`.
They also appear in Hub Variable focus, pivot tables, Insights and the AI-friendly export.

A Hub Variable's connector device holds the same synchronised value. The map shows that link
separately as "Connector", and it is never treated as a piston reading or writing the variable.

## Evidence

Hub Variable direction was tested with four kinds of piston:

- read-only;
- write-only;
- read one variable while writing another;
- read and write the same variable.

The expected classifications stayed present while the pistons were paused. The interpretation is pinned
to the Hubitat webCoRE source commit given in the canonical reference.
