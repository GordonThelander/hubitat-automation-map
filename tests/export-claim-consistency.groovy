#!/usr/bin/env groovy

// Guards the class of bug that shipped twice in v2.2.8: the DATA was correct
// while the prose describing it to a consumer was left at the previous
// increment. No local suite caught either, because both were true sentences
// about an older build. Every check here is a source-text assertion, so a
// future increment that changes behaviour without changing its own
// description fails here rather than in a stranger's AI export.

File sourceFile = new File('apps/automation_map.groovy')
assert sourceFile.exists()
String source = sourceFile.getText('UTF-8')

int passed = 0
void check(boolean condition, String label) {
    if (!condition) {
        println "FAIL  ${label}"
        System.exit(1)
    }
    println "PASS  ${label}"
}

// 1. The v2.2.7 device ringfence claim must not outlive the ringfence itself.
// It said piston device operands "are not decoded in this version" while the
// same export shipped decoded deviceRead and action edges.
check(!source.contains('piston device operands are not decoded in this version'),
    'export limitations do not still claim piston device operands are undecoded')
check(source.contains('piston-to-device relationships ARE decoded from saved configuration'),
    'export limitations state that piston device relationships are decoded')
passed += 2

// 2. deviceRead exists as an edge kind, so it must be documented as one.
check(source.contains("kind: 'deviceRead'"), 'the graph emits deviceRead edges')
check(source.contains('deviceRead (graph schema 14, export schema 12, v2.2.8)'),
    'schema.edges documents the deviceRead relationship')
passed += 2

// 3. schema.edges contradicted itself on direction one sentence apart: first
// "unknown only on deviceRead and usesVar", then "unknown only on usesVar".
check(!source.contains('direction is "unknown" only on usesVar edges and null otherwise'),
    'schema.edges carries no stale usesVar-only direction claim')
check(source.contains('direction is "unknown" only on deviceRead and usesVar edges'),
    'schema.edges states the one correct direction rule')
passed += 2

// 4. A webCoRE piston never gets a ruleFlows[] entry, so pointing a consumer
// at ruleFlows[].localVariables[] made every piston local unreachable by the
// documented route while top-level localVariables[] went undocumented.
// Scoped to the live schema string a consumer actually reads, not to the
// schema-6 changelog comment, which is accurate about its own era and now
// carries an explicit supersession note.
String edgesSchema = source.readLines().find { it.trim().startsWith('edges: ') } ?: ''
check(source.contains("localVariables: 'Rule-owned variables, flat and complete across every engine"),
    'schema documents the top-level localVariables array')
check(edgesSchema != '' && !edgesSchema.contains('present only nested, in ruleFlows[].localVariables[]'),
    'schema.edges no longer sends consumers to the flow-nested local copy alone')
check(source.contains('SUPERSEDED at schema 12 (v2.2.8)'),
    'the schema-6 nested-locals decision is marked superseded')
passed += 3

// 5. The piston panel hard-codes [HVR] and a "Hub" heading on that list, so it
// must only ever receive hub-scoped targets. Local edges from the same piston
// were rendering there, tagged as Hub Variables.
check(source.contains("return !!t && t.group === 'hubVariable';"),
    'the webCoRE hub-variable card list is filtered to hub-scoped targets')
passed += 1

// 6. A webCoRE local reference must carry a display name; without one the card
// rendered "[LOC]  - writes" with an empty variable name.
check(source.contains('localIdentity: identity, name: "${rawName}", usageRole: null'),
    'webCoRE local write references carry a name')
check(source.contains("localIdentity: identity, name: \"\${rawName}\", usageRole: 'unknown-read'"),
    'webCoRE local read references carry a name')
passed += 2

// 7. Apostrophe hazard: these schema entries are single-quoted JS literals, so
// an unescaped apostrophe silently truncates the string. Cost one live break.
String localsSchema = source.readLines().find { it.trim().startsWith('localVariables:') && it.contains('Rule-owned variables') } ?: ''
check(localsSchema != '' && localsSchema.count("'") == 2,
    'the localVariables schema string has no unescaped apostrophes')
passed += 1

println "${passed} passed, 0 failed"
