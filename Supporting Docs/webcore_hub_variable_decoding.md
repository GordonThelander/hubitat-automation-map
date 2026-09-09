# Understanding webCoRE pistons in Automation Map

## Purpose

This paper explains the parts of webCoRE that matter to Automation Map: how a piston is represented
on Hubitat, what reliable evidence can be recovered from its saved configuration, and how Automation
Map converts that evidence into variables and device relationships.

It is not a general guide to writing webCoRE pistons and it does not attempt to document the complete
webCoRE execution engine. Its purpose is to establish a defensible boundary between what Automation
Map can prove statically and what would require a much larger flow interpreter or observation of a
running piston.

## Executive position

webCoRE is materially different from Rule Machine from a discovery perspective. Hubitat exposes a
webCoRE parent app and a child app for each piston, but the useful piston logic is stored as an encoded
compiled document inside the child app's settings. The parent app's device selections represent the
devices webCoRE is permitted to access. They do not establish which individual piston uses a device,
or whether that use is a read or an action.

Automation Map therefore treats the saved piston document as the authoritative source for
piston-level relationships. It decodes the document in memory, recognizes a deliberately limited set
of source-backed structures, and emits only relationships that those structures prove. It does not
execute the piston, inspect variable values, infer relationships from labels, or treat broad parent
permissions as actual device use.

This bounded approach can establish:

- Hub Variable reads and writes;
- piston-local variable declarations, reads, and writes;
- direct physical-device attribute reads; and
- direct device actions.

It does not reconstruct the complete piston flow, branch order, timing, or runtime-selected targets.
When the saved structure does not identify one definite relationship, Automation Map does not guess.

## The webCoRE model on Hubitat

There are three distinct layers to keep in mind.

| Layer | Hubitat representation | What it proves |
| --- | --- | --- |
| webCoRE parent | Main installed app | webCoRE installation, shared configuration, and permitted devices |
| Piston | Child installed app | Ownership, piston identity, status, and saved settings |
| Compiled piston | Encoded JSON in the child app's `chunk:N` settings | The piston's declarations, operands, conditions, actions, and other compiled logic |

This distinction is important. A device listed in the parent app is available to webCoRE, but that
fact alone does not prove that any particular piston uses it. Piston-level mapping must come from the
compiled piston, not from the parent's permission list.

The same principle applies to status. A paused piston retains its saved compiled configuration.
Pausing prevents execution, but it does not erase the declarations and relationships encoded in that
configuration. Static discovery should therefore continue to work for paused pistons.

## How a piston is stored

Each piston is a Hubitat child-app instance. Its compiled configuration is saved as text settings
named `chunk:0`, `chunk:1`, and so on. Small pistons may use only one chunk, while larger pistons are
divided across several numbered settings.

Hubitat exposes these settings through the read-only installed-app endpoint:

```text
GET /installedapp/statusJson/<installedAppId>
```

The chunks appear in the response's `appSettings` collection. Together they contain one Base64
representation of the compiled piston. Base64 is an encoding, not encryption. Decoding it as UTF-8,
applying webCoRE's emoji conversion, and parsing the result produces the JSON document used by the
piston runtime.

Automation Map applies strict bounds before accepting that document:

1. accept only setting names that exactly match `chunk:<non-negative integer>`;
2. require `chunk:0` and one contiguous numerical sequence;
3. reject duplicate, missing, empty, excessive, or oversized chunks;
4. concatenate the chunks in numerical order;
5. Base64-decode the result as UTF-8 and apply webCoRE's emoji decoding;
6. parse the JSON and require a map at the document root; and
7. retain only supported declarations and relationships, then discard the decoded document.

The compiled JSON is an internal representation rather than a stable public interchange format.
Automation Map consequently recognizes only structures that have been verified against the current
Hubitat webCoRE source and test data.

## Variable namespaces

webCoRE supports several kinds of variables. Their prefixes and ownership determine how Automation
Map represents them.

| Form | Meaning on Hubitat webCoRE | Automation Map treatment |
| --- | --- | --- |
| `name` | Piston-local variable when declared by that piston | Owner-scoped Local Variable |
| `@name` | webCoRE global variable | Not currently mapped |
| `@@name` | Hubitat Hub Variable exposed through webCoRE | Shared Hub Variable |

### Hub Variables and `@@`

Generic webCoRE terminology describes `@name` as a global and `@@name` as a superglobal. The Hubitat
port gives the double-at namespace a concrete integration role. The parent app's `AddHeGlobals()`
method calls Hubitat's `getAllGlobalVars()` and publishes each Hub Variable to webCoRE as
`@@<name>`.

In the piston runtime, `getVariable()` and `setVariable()` remove the two leading characters before
using Hubitat's global-variable API. A single-at name remains a webCoRE global and is not a Hub
Variable.

Automation Map accepts an `@@` name only when it appears as a structurally typed variable operand.
It removes exactly two prefix characters and reconciles the remaining name with the authoritative Hub
Variable inventory returned by Hubitat. Plain text containing `@@`, or a name absent from that
inventory, cannot create a Hub Variable relationship.

### Piston-local variables

Local-variable declarations are stored in the compiled document's top-level `v` array. A valid
declaration supplies its name in `n` and its webCoRE type in `t`. The declaration belongs to one
piston, so the same name declared in two pistons represents two separate Local Variables.

Variable operands use `t: "x"`. Automation Map can normalize the referenced name and compare it with
the declarations belonging to that piston. This distinguishes a local reference from a webCoRE
global, a Hub Variable, a system variable, or an undeclared name.

Declarations are useful independently of references. A declared local that is never read or written
can still be represented as an unreferenced Local Variable. Automation Map does not need to retrieve
the declaration's saved value or its runtime value.

## How variable direction is proven

Read or write direction is derived from structural position, not from the variable's name or current
value.

| Saved context | Classification | Runtime basis |
| --- | --- | --- |
| Variable evaluated in a condition, calculation, expression, assigned value, or variable-backed device selection | Read | webCoRE resolves it through `getVariable()` |
| First variable parameter of a `setVariable` task | Write | webCoRE passes it as the destination to `setVariable()` |
| Variable used as the value assigned to another variable | Read | The value is evaluated before assignment |
| `for` or `each` loop counter | Write | The loop updates that variable |
| Matching or non-matching device capture variable on a physical condition | Write | webCoRE stores the condition result in that variable |
| Static destination of an expression-form `setVariable()` call | Write | The first argument resolves to one literal target name |

Classifications accumulate. If the same variable is found in both evaluated and destination
positions, Automation Map records both read and write relationships. This applies independently to
Hub Variables and piston-local variables.

A variable destination constructed dynamically at runtime does not name one definite saved target.
Automation Map omits that relationship rather than selecting a variable speculatively.

## Direct piston-to-device evidence

The compiled piston contains direct evidence for two useful classes of device relationship.

### Physical-device reads

A map with `t: "p"` is a physical-device operand. Its `d` list contains device references and its
`a` field identifies the attribute being read. In webCoRE's `evaluateOperand()` path, the runtime
expands the device list and calls `getDeviceAttribute(...)` for each device.

This proves that the piston reads the specified device attribute. It does not, by itself, prove
whether that read is serving as a trigger, condition, constraint, or monitor. Automation Map therefore
uses a neutral device-read relationship rather than assigning a finer role that has not been decoded.

### Device actions

A map with `t: "action"` is a device-action statement. Its `d` list contains the target devices and
its `k` list contains tasks. Each task's `c` field identifies its command. webCoRE's
`executeAction()` path expands the target list and passes each task to `executeTask()`.

This proves that the piston can act on the target device. Automation Map may retain the command name
as bounded evidence, but it does not retain or export task parameter values.

## Resolving webCoRE device references

Direct device nodes do not contain ordinary Hubitat device IDs. webCoRE stores a colon-wrapped hash
calculated as:

```text
":" + lowercaseHex(MD5(UTF8("core." + deviceId))) + ":"
```

The hash cannot be reversed, but it does not need to be. The parent webCoRE app already identifies the
finite set of devices that the installation is permitted to access. Automation Map applies webCoRE's
own hashing method to those candidate device IDs and compares the results with the token stored in the
piston.

A device reference is accepted only when:

1. the token has the exact colon-wrapped 32-character hexadecimal form;
2. exactly one permitted device ID produces that token;
3. the candidate belongs to the piston's own parent webCoRE instance; and
4. the device is present in Automation Map's scanned physical-device inventory.

No relationship is created for a missing or ambiguous match. Automation Map never resolves these
references by device label and never creates a device node from an unmatched hash.

This method was checked against the current webCoRE source and independently reproduced with live
saved piston data. A physical switch operand resolved to the expected switch and a `setColor` action
resolved to the expected colour-capable device.

## How Automation Map renders webCoRE

Automation Map treats each piston as the automation owner. The parent webCoRE app remains visible as
the owning application, but its broad device permissions are not rendered as device-use
relationships.

The supported relationships are represented as follows:

| Evidence | Map relationship | Arrow direction |
| --- | --- | --- |
| Evaluated Hub or Local Variable | Read | Variable to piston |
| Hub or Local Variable destination | Write | Piston to variable |
| `t: "p"` physical-device operand | Device read | Device to piston |
| `t: "action"` direct target | Action | Piston to device |

When the same piston both reads and acts on one device, both relationships are retained. When it both
reads and writes one variable, both variable relationships are retained.

The same evidence must be reflected consistently in map focus, search, pivot tables, Insights, and
the AI-friendly export. Device pivot presets must include webCoRE pistons as automations, rather than
filtering them out because they are not Rule Machine rules. Complete webCoRE flow is not implied by
the presence of these relationships.

## Deliberate boundaries

This is a bounded structural decoder, not a webCoRE interpreter. Automation Map does not create a
fixed device relationship for:

- a variable-backed or otherwise runtime-selected device list;
- the device that generated the current event;
- a webCoRE virtual device or location operand;
- an unrecognized or malformed node; or
- a hash that cannot be reconciled uniquely with the correct parent inventory.

It also does not infer complete IF/ELSE flow, evaluation order, schedules, delays, cancellation
behaviour, or the exact trigger, condition, constraint, or monitor role of a physical read.

These omissions are intentional. They distinguish an incomplete picture from an incorrect one.
Unsupported static forms produce bounded coverage information, while malformed encoding or JSON
produces a fixed decoder error and a `complete-with-gaps` scan result. One malformed piston does not
prevent other applications from being discovered.

## Privacy and data handling

The decoder needs structure, not values. It does not log, cache, render, or export:

- the decoded piston document;
- raw Base64 chunks;
- unmatched device hashes;
- Hub, global, or local variable values;
- action parameter values; or
- local network details.

The decoded document exists only in memory during classification and is then discarded. Discovery
uses read-only Hubitat data and introduces no state-changing endpoint.

## Evidence base

The interpretation is grounded in the Hubitat webCoRE source at commit
[`0a37eee2537accd706aaaeeed5a7b4bb0c82646e`](https://github.com/imnotbob/webCoRE/commit/0a37eee2537accd706aaaeeed5a7b4bb0c82646e):

- [`webcore.groovy`](https://github.com/imnotbob/webCoRE/blob/0a37eee2537accd706aaaeeed5a7b4bb0c82646e/smartapps/ady624/webcore.src/webcore.groovy)
- [`webcore-piston.groovy`](https://github.com/imnotbob/webCoRE/blob/0a37eee2537accd706aaaeeed5a7b4bb0c82646e/smartapps/ady624/webcore-piston.src/webcore-piston.groovy)

Hub Variable direction was tested with read-only, write-only, read-one/write-another, and
read-and-write-the-same-variable pistons. The expected classifications remained present while the
pistons were paused.

The physical-read, direct-action, and device-hash interpretations were independently verified from
source and reproduced against a live test piston. The resolved devices matched both the stored
attribute and command semantics.

## Automation Map implementation status

Hub Variable discovery and direction classification are implemented in Automation Map Dev. webCoRE
parent and piston device relationships are currently suppressed to prevent parent-level permissions
from being mistaken for proven piston use.

The next Dev implementation is specified to add piston-local variables and replace that temporary
device ringfence with the direct piston-level reads and actions described above. It must preserve the
existing Hub Variable behaviour, keep the parent permission relationships suppressed, and expose the
new evidence consistently across the map and export surfaces.

The detailed implementation and acceptance criteria are in
[`webcore_piston_devices_and_local_variables_spec.md`](webcore_piston_devices_and_local_variables_spec.md).
