# webCoRE Hub Variable decoding

## Scope

Automation Map 2.2.6 discovers Hub Variable references from a webCoRE piston's saved
configuration and classifies each proven reference as a read, a write, or both. It does not
reconstruct webCoRE's complete execution flow.

The implementation was derived from the current Hubitat webCoRE source rather than inferred from
labels or runtime behaviour. The source version inspected was commit
[`0a37eee2537accd706aaaeeed5a7b4bb0c82646e`](https://github.com/imnotbob/webCoRE/commit/0a37eee2537accd706aaaeeed5a7b4bb0c82646e)
on the `hubitat-patches` branch.

Relevant source paths:

- [`webcore.groovy`](https://github.com/imnotbob/webCoRE/blob/0a37eee2537accd706aaaeeed5a7b4bb0c82646e/smartapps/ady624/webcore.src/webcore.groovy)
  provides the parent app's Hub Variable integration.
- [`webcore-piston.groovy`](https://github.com/imnotbob/webCoRE/blob/0a37eee2537accd706aaaeeed5a7b4bb0c82646e/smartapps/ady624/webcore-piston.src/webcore-piston.groovy)
  loads the saved piston and implements variable evaluation and assignment.

## Where the saved piston is found

Each installed webCoRE Piston is a Hubitat child-app instance. webCoRE saves that piston's compiled
configuration as text settings on the child app, named `chunk:0`, `chunk:1`, and so on. These are
Hubitat application settings, not files in a user-visible directory.

Hubitat exposes the settings through the read-only installed-app response:

```text
GET /installedapp/statusJson/<installedAppId>
```

They appear in the response's `appSettings` collection. A small piston may have only `chunk:0`;
larger pistons are split over several numbered chunks.

The chunk value is Base64 encoding, not encryption. Automation Map:

1. selects only settings whose names exactly match `chunk:<number>`;
2. rejects duplicates, a missing `chunk:0`, gaps in the sequence, empty chunks, excessive indexes,
   or an excessive combined size;
3. sorts the chunks numerically and concatenates their values;
4. Base64-decodes the result as UTF-8, applies webCoRE's emoji decoding, and parses the JSON;
5. walks the parsed structure in memory and retains only classified Hub Variable names; and
6. discards the decoded document without logging, caching, or exporting it.

A live v2.2.6 test used a one-chunk piston whose 1,260 encoded characters became 944 bytes of JSON.
Those measurements are illustrative only and are not part of the format contract.

## What `@@` means on Hubitat

webCoRE has several variable namespaces. Generic webCoRE terminology describes `@name` as a global
and `@@name` as a superglobal. The Hubitat port gives `@@` an additional, concrete meaning: it maps
Hubitat Hub Variables into webCoRE's double-at namespace.

The parent webCoRE app's `AddHeGlobals()` method calls Hubitat's `getAllGlobalVars()` and publishes
each returned name to webCoRE as `@@<name>`. In the piston runtime:

- `getVariable()` sees `@@`, removes the two leading characters, and reads the matching Hub Variable
  through Hubitat's global-variable API;
- `setVariable()` performs the same name conversion and writes through Hubitat's global-variable
  API; and
- a single-at name, `@<name>`, remains a webCoRE global and is not a Hub Variable.

Automation Map therefore treats a structurally typed variable name beginning with `@@` as a Hub
Variable candidate. It removes exactly the two namespace characters, then reconciles the remaining
name against the authoritative Hub Variable inventory returned by the hub. A candidate absent from
that inventory cannot create a Hub Variable node or relationship. Plain text that happens to contain
`@@` is not treated as a variable reference.

## How read and write direction is classified

Direction comes from the variable's structural position and the matching webCoRE runtime path:

- A variable evaluated in a condition, calculation, expression, assigned value, or variable-backed
  device selection is a **read** because webCoRE resolves it through `getVariable()`.
- In a `setVariable` task, the first parameter is the destination name passed to `setVariable()` and
  is a **write**. Later parameters are evaluated normally, so a variable used as the assigned value
  is a **read**.
- A `for` or `each` loop counter is a **write**.
- The optional matching-device and non-matching-device capture variables on a physical condition are
  **writes**.
- An expression-form `setVariable()` call is a **write** only when its first argument reduces to one
  literal variable name in the saved structure.

Classification is accumulated rather than reduced to one role. If the same Hub Variable occurs in
both evaluated and assignment positions, Automation Map creates both a read edge and a write edge.

For example, a saved piston shaped like this:

```text
if Hub Variable A changes
then
    Set variable Hub Variable A = "test"
end if
```

contains an evaluated condition operand, which proves a read, and the first parameter of a
`setVariable` task, which proves a write. The resulting map therefore shows Hub Variable A as both
read and written by that piston.

## Verification and limits

The v2.2.6 Dev verification used four saved pistons:

1. read only;
2. write only;
3. read one Hub Variable and write another; and
4. read and write the same Hub Variable.

All four classifications matched the saved piston scripts in the Automation Map pivot table. Paused
pistons were also discovered correctly because the evidence comes from saved configuration, not
current subscriptions or execution state.

A variable target assembled dynamically at runtime does not identify one definite destination in
the saved structure. Automation Map does not guess in that case. Malformed encoding, malformed JSON,
or an incomplete chunk sequence produces a fixed decoder error and a `complete-with-gaps` scan status;
it never exposes the saved document or invents a relationship.
