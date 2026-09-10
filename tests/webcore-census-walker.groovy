#!/usr/bin/env groovy
//
// Increment 2: the pure census walker, executed from the app source so the
// tests cannot pass against a drifted copy. Covers the implementation map's
// T2 accounting oracle, T3 classification, T4 privacy canaries and T5 bounds.
// Run with: groovy tests/webcore-census-walker.groovy

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('..').canonicalFile

int passed = 0
int failed = 0

def check = { boolean cond, String label ->
    if (cond) { println "PASS  ${label}" } else { println "FAIL  ${label}" }
    cond
}
List<Boolean> results = []
def assertThat = { boolean cond, String label -> results << check(cond, label) }

// ---- load the walker straight out of the app -------------------------------

String source = new File(repoRoot, 'apps/automation_map.groovy').getText('UTF-8')
String beginMarker = '// --- webcore census walker: begin ---'
String endMarker = '// --- webcore census walker: end ---'
int begin = source.indexOf(beginMarker)
int end = source.indexOf(endMarker)
assert begin >= 0 && end > begin: 'census walker block not found in app source'
String block = source.substring(begin + beginMarker.length(), end)

Class walkerClass = new GroovyClassLoader(this.class.classLoader).parseClass(
        "class WebcoreCensusUnderTest {\n" + block + "\n}")
def walker = walkerClass.newInstance()

// ---- load the pinned registry ---------------------------------------------

File registryFile = new File(repoRoot, 'tools/webcore-investigation/generated/webcore_construct_registry.groovy')
String registryText = registryFile.getText('UTF-8')
String anchor = 'WEBCORE_CONSTRUCT_REGISTRY = '
int registryStart = registryText.indexOf(anchor)
assert registryStart >= 0: 'registry constant not found'
Map registry = new GroovyShell().evaluate(registryText.substring(registryStart + anchor.length())) as Map
assertThat((registry.constructs as Map).size() == 279, 'registry loaded with the reviewed population')

File fixtureDir = new File(repoRoot, 'tests/fixtures/webcore-census')
def slurper = new JsonSlurper()
Map manifest = slurper.parse(new File(fixtureDir, 'manifest.json')) as Map

// ---- T2: an accounting oracle the walker did not produce -------------------

// Literal expected counts, worked out by hand from the shape below:
//   objects  root, statement, task, param                       = 4
//   arrays   s, k, p                                            = 3
//   fields   s | t k | c p | t x                                = 7
//   elements one per array                                      = 3
//   scalars  'action', 'setVariable', 'x', 1                    = 4
Map oracleDoc = [s: [[t: 'action', k: [[c: 'setVariable', p: [[t: 'x', x: 'counter']]]]]]]
Map oracle = walker.collectWebcoreDecodeCoverage(oracleDoc, registry) as Map
Map oracleAcc = oracle.accounting as Map
assertThat(oracleAcc.objectsVisited == 4, "oracle objectsVisited is 4 (${oracleAcc.objectsVisited})")
assertThat(oracleAcc.arraysVisited == 3, "oracle arraysVisited is 3 (${oracleAcc.arraysVisited})")
assertThat(oracleAcc.fieldsVisited == 7, "oracle fieldsVisited is 7 (${oracleAcc.fieldsVisited})")
assertThat(oracleAcc.arrayElementsVisited == 3, "oracle arrayElementsVisited is 3 (${oracleAcc.arrayElementsVisited})")
assertThat(oracleAcc.scalarsVisited == 4, "oracle scalarsVisited is 4 (${oracleAcc.scalarsVisited})")
assertThat(oracleAcc.constructCandidates == 2, "oracle constructCandidates is 2 (${oracleAcc.constructCandidates})")
assertThat(oracleAcc.constructsIdentified == 2, "oracle constructsIdentified is 2 (${oracleAcc.constructsIdentified})")
assertThat(oracle.unrecognised == [], 'oracle records nothing unrecognised')

// A deliberately naive second counter, so the balance check does not depend on
// the walker's own bookkeeping.
Map tally(Object value, Map t) {
    if (value instanceof Map) {
        t.objects = (t.objects as Integer) + 1
        (value as Map).each { Object k, Object v -> t.fields = (t.fields as Integer) + 1; tally(v, t) }
    } else if (value instanceof List) {
        t.arrays = (t.arrays as Integer) + 1
        (value as List).each { Object v -> t.elements = (t.elements as Integer) + 1; tally(v, t) }
    } else {
        t.scalars = (t.scalars as Integer) + 1
    }
    return t
}

// ---- every fixture: complete accounting and the recognition invariant ------

Map<String, Map> walked = [:]
(manifest.fixtures as List).each { Map f ->
    String name = f.file as String
    Object doc = slurper.parse(new File(fixtureDir, name))
    Map out = walker.collectWebcoreDecodeCoverage(doc, registry) as Map
    walked[name] = out
    Map t = tally(doc, [objects: 0, arrays: 0, fields: 0, elements: 0, scalars: 0])
    Map a = out.accounting as Map
    assertThat(out.status == 'complete', "${name}: status complete")
    assertThat(a.objectsVisited == t.objects && a.arraysVisited == t.arrays &&
               a.fieldsVisited == t.fields && a.arrayElementsVisited == t.elements &&
               a.scalarsVisited == t.scalars,
        "${name}: all five traversal counters balance against the independent tally")
    assertThat((a.constructsIdentified as Integer) <= (a.constructCandidates as Integer),
        "${name}: constructsIdentified <= constructCandidates")
}

// ---- T3: classification ----------------------------------------------------

(manifest.fixtures as List).each { Map f ->
    String name = f.file as String
    if (!(f.expectConstructs instanceof List)) return
    Set found = ((walked[name].constructCounts as Map).keySet()) as Set
    List missing = (f.expectConstructs as List).findAll { !found.contains(it) }
    assertThat(missing.isEmpty(), "${name}: every expected construct identified${missing ? ' (missing ' + missing + ')' : ''}")
}

Map numeric = walked['shared-branch-numeric.json']
List numericMembers = ['integer', 'float', 'double', 'decimal', 'number']
Collection numericIds = numericMembers.findAll { (numeric.constructCounts as Map).containsKey('wc.expression.result-type.' + it) }
assertThat(numericIds.size() == 5, "shared branch keeps five distinct result types (${numericIds.size()})")

Map multi = walked['same-spelling-multisite.json']
assertThat((multi.constructCounts as Map).containsKey('wc.operand.p') &&
           (multi.constructCounts as Map).containsKey('wc.operand.event-match.p'),
    'the same spelling at two sites yields two constructs, not one')

Map trap = walked['nested-switch-trap.json']
assertThat((trap.constructCounts as Map).containsKey('wc.operand.p') &&
           (trap.constructCounts as Map)['wc.operand.event-match.p'] == 1,
    'an on statement event matcher is not confused with a switch operand')

Map empty = walked['empty-operand.json']
assertThat((empty.constructCounts as Map)['wc.operand.empty'] == 1,
    'an empty t is identified rather than skipped')
assertThat((empty.unrecognised as List).any { it.reason == 'malformed-node' },
    'a missing t stays distinct from an empty t')

Map unknown = walked['unknown-discriminator.json']
assertThat((unknown.unrecognised as List).any { it.reason == 'unknown-operand-type' },
    'an unregistered discriminator is reported with the fixed reason')
assertThat(!JsonOutput.toJson(unknown).contains('zzz-not-a-real-operand-type'),
    'the raw discriminator never reaches the result')

// Classification must not steer traversal: an unknown statement type still has
// its whole subtree walked.
Map unknownStatement = [s: [[t: 'not-a-statement', c: [[t: 'condition', co: 'is', lo: [t: 'v', v: 'mode']]]]]]
Map steer = walker.collectWebcoreDecodeCoverage(unknownStatement, registry) as Map
assertThat((steer.constructCounts as Map).containsKey('wc.virtual-device.mode'),
    'an unrecognised parent does not stop its children being classified')
assertThat((steer.unrecognised as List).any { it.reason == 'unknown-statement-type' },
    'the unrecognised statement type is still reported')

// Legacy attribute spellings are renamed exactly as the pinned source renames
// them, or every SmartThings-era HSM operand would read as unknown.
Map legacy = walker.collectWebcoreDecodeCoverage(
    [s: [[t: 'if', c: [[t: 'condition', co: 'is', lo: [t: 'v', v: 'alarmSystemStatus']]]]]], registry) as Map
assertThat((legacy.constructCounts as Map).containsKey('wc.virtual-device.hsmStatus'),
    'a legacy attribute name is normalised before lookup')

// Functions are saved as {t:'function', n:<name>} and looked up case-insensitively.
Map functions = walker.collectWebcoreDecodeCoverage(
    [s: [[t: 'action', k: [[c: 'log', p: [[t: 'e', exp: [t: 'expression', i: [
        [t: 'function', n: 'celsius'], [t: 'function', n: 'notARealFunction']]]]]]]]]], registry) as Map
assertThat((functions.constructCounts as Map).keySet().any { "${it}".startsWith('wc.function.') },
    'a registered function is identified')
assertThat((functions.unrecognised as List).any { it.reason == 'unknown-function' },
    'an unregistered function is reported with the fixed reason')
assertThat(!JsonOutput.toJson(functions).contains('notARealFunction'),
    'an unregistered function name never reaches the result')

// Device selectors: only the statically resolvable forms are claimed.
Map selectors = walker.collectWebcoreDecodeCoverage(
    [s: [[t: 'action', d: [':0123456789abcdef0123456789abcdef:', '', '@someDeviceVariable']]]], registry) as Map
assertThat((selectors.constructCounts as Map)['wc.device-selector.direct-identifier'] == 1,
    'a direct device identifier is identified')
assertThat((selectors.constructCounts as Map)['wc.device-selector.empty'] == 1,
    'an empty device entry is identified')
assertThat((selectors.unrecognised as List).any { it.reason == 'unknown-device-selector' },
    'a variable-backed device entry is not guessed at')
assertThat(!JsonOutput.toJson(selectors).contains('someDeviceVariable'),
    'a device variable name never reaches the result')

// ---- safe paths and the unknown-key reason ---------------------------------

Map oddKeys = walker.collectWebcoreDecodeCoverage(
    [s: [[t: 'action', 'Kitchen Lamp': 1, 'another user key': 2]]], registry) as Map
String oddJson = JsonOutput.toJson(oddKeys)
assertThat(!oddJson.contains('Kitchen Lamp') && !oddJson.contains('another user key'),
    'an arbitrary object key never reaches a path')
assertThat((oddKeys.unrecognised as List).count { it.reason == 'unknown-key' } == 2,
    'each unknown key is reported separately')
List placeholders = (oddKeys.unrecognised as List).findAll { it.reason == 'unknown-key' }.collect { it.path }
assertThat(placeholders.unique().size() == 2,
    'two unknown keys under one object get distinct ordinal placeholders')
assertThat(placeholders.every { "${it}".contains('<unknown-key#') },
    'unknown keys are rendered as the fixed placeholder plus a sibling ordinal')

// ---- T4: privacy canaries --------------------------------------------------

String canary = 'CANARYbf41d2'
Map canaryDoc = [
    n: "piston ${canary}".toString(),
    (canary + 'Key'): 'value',
    v: [[n: canary + 'Var', t: 'string', v: canary + 'Value']],
    s: [[
        t: 'action',
        d: [canary + 'Device'],
        c: [[t: 'condition', co: 'is', lo: [t: canary + 'Type', a: canary + 'Attr'],
             ro: [t: 'c', c: canary + 'Literal']]],
        k: [[c: canary + 'Command', p: [
            [t: 'e', exp: [t: 'expression', i: [
                [t: 'function', n: canary + 'Func'],
                [t: 'string', v: "http://example.invalid/${canary}".toString()]]]],
            [t: 'x', x: canary + 'VarName', vt: canary + 'ValueType']]]]
    ]]
]
Map canaryOut = walker.collectWebcoreDecodeCoverage(canaryDoc, registry) as Map
String canaryJson = JsonOutput.toJson(canaryOut)
assertThat(!canaryJson.contains(canary), 'no canary survives into the walker result')
assertThat((canaryOut.unrecognised as List).size() > 0, 'the canary document does produce unrecognised records')
assertThat((canaryOut.unrecognised as List).every {
        it.reason in ['unknown-statement-type', 'unknown-operand-type', 'unknown-function',
                      'unknown-virtual-command', 'unknown-policy-value', 'unknown-device-selector',
                      'unknown-key', 'malformed-node'] },
    'every reason comes from the closed walker enumeration')
assertThat((canaryOut.unrecognised as List).every { it.nodeKind in ['object', 'array', 'scalar'] },
    'every nodeKind comes from the closed enumeration')
assertThat(!canaryJson.contains('decode-error'),
    'decode-error is not a walker reason')

// ---- T5: bounds ------------------------------------------------------------

List manyUnknowns = (1..60).collect { int i -> [t: "unknown-statement-${i}".toString()] }
Map bounded = walker.collectWebcoreDecodeCoverage([s: manyUnknowns], registry) as Map
assertThat((bounded.unrecognised as List).size() == 50, "unrecognised is capped at 50 (${(bounded.unrecognised as List).size()})")
assertThat(bounded.unrecognisedOverflow == 10, "overflow counts the remainder (${bounded.unrecognisedOverflow})")
assertThat((bounded.accounting as Map).constructCandidates == 60,
    'bounding the retained list does not bound the accounting')

// Deduplication runs before the cap. The safe path builder already gives every
// node a distinct path, so this is a guard rather than a working mechanism:
// what it must guarantee is that the retained list never holds a duplicate.
List repeated = (1..60).collect { [t: 'same-unknown-statement'] } + [[t: 'a-different-unknown']]
Map deduped = walker.collectWebcoreDecodeCoverage([s: repeated], registry) as Map
List dedupedRecords = deduped.unrecognised as List
assertThat(dedupedRecords.collect { "${it.path}|${it.reason}|${it.nodeKind}" }.unique().size() == dedupedRecords.size(),
    'the retained list holds no duplicate record')
assertThat(dedupedRecords.size() == 50 && deduped.unrecognisedOverflow == 11,
    "repeated spellings at distinct paths stay distinct records (${dedupedRecords.size()}, overflow ${deduped.unrecognisedOverflow})")

// Depth is bounded deterministically and says so rather than silently stopping.
Map deep = [:]
Map cursor = deep
(1..140).each { cursor.s = [[t: 'do']]; cursor = (cursor.s as List)[0] as Map }
Map deepOut = walker.collectWebcoreDecodeCoverage(deep, registry) as Map
assertThat(deepOut.status == 'truncated', 'a document deeper than the bound is reported as truncated')
assertThat((deepOut.truncation as Map).reason == 'depth-limit', 'the truncation reason is fixed')

// ---- provenance ------------------------------------------------------------

Map prov = oracle.provenance as Map
assertThat(prov.referenceSourceCommit == '0a37eee2537accd706aaaeeed5a7b4bb0c82646e',
    'the result carries the pinned reference commit')
assertThat(prov.observedWebcoreVersion == null && prov.compatibilityStatus == 'unknown',
    'no observed version is invented')
assertThat(oracle.registryVersion == '1', 'the result carries the registry version')

// A non-map document is refused with an existing fixed code rather than walked.
Map wrongRoot = walker.collectWebcoreDecodeCoverage('not a document', registry) as Map
assertThat(wrongRoot.status == 'error' && wrongRoot.error == 'unexpected-root',
    'a non-map document returns the fixed unexpected-root code')

// ---- summary ---------------------------------------------------------------

int total = results.size()
int bad = results.count { !it }
println "${total - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
