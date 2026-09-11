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
assertThat((registry.constructs as Map).size() == 280, 'registry loaded with the reviewed population')

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
assertThat(oracleAcc.defaultBranchOccurrences == 1,
    "oracle counts the parameter's missing vt as one default-branch occurrence (${oracleAcc.defaultBranchOccurrences})")

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

// The reason set is asserted exactly, including empty for the positive
// fixtures, so a new accidental gap cannot hide inside an existing partial
// result.
(manifest.fixtures as List).each { Map f ->
    String name = f.file as String
    Set actual = ((walked[name].unrecognised as List).collect { it.reason } as Set)
    Set expected = ((f.expectReasons ?: []) as Set)
    assertThat(actual == expected,
        "${name}: reason set is exactly ${expected.isEmpty() ? 'empty' : expected}${actual == expected ? '' : ' (got ' + actual + ')'}")
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
                      'unknown-key', 'known-opaque-field', 'malformed-node'] },
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

// ---- root variable declarations --------------------------------------------

// subscribeAll hands a root variable's v to operandTraverser, so constructs used
// inside a saved initializer are part of the document and must be accounted for.
Map varDecl = walked['variable-declaration.json']
String varDeclJson = JsonOutput.toJson(varDecl)
assertThat((varDecl.constructCounts as Map).containsKey('wc.function.celsius'),
    'a function inside a variable declaration initializer is identified')
assertThat((varDecl.unrecognised as List).any { it.reason == 'unknown-operand-type' },
    'a gap inside a variable declaration initializer is still reported')
assertThat(!(varDecl.constructCounts as Map).containsKey('wc.operand.device') &&
           !(varDecl.constructCounts as Map).containsKey('wc.operand.dynamic'),
    "a declaration's own t is not treated as an operand discriminator")
assertThat(!varDeclJson.contains('deviceRef') && !varDeclJson.contains('computed') &&
           !varDeclJson.contains('not-a-real-item-type'),
    'nothing from a variable declaration reaches the result')

// ---- defaulted sites -------------------------------------------------------

// A missing, null or non-String value still reaches the source default branch,
// so every invocation that does not resolve to a registered member is counted.
Map defaultedDoc = [s: [[t: 'action', k: [[c: 'setVariable', p: [
    [t: 'x', x: 'a', vt: 'variable'],
    [t: 'x', x: 'b', vt: 'not-a-registered-value-type'],
    [t: 'x', x: 'c'],
    [t: 'x', x: 'd', vt: 4242]
]]]]]]
Map defaulted = walker.collectWebcoreDecodeCoverage(defaultedDoc, registry) as Map
String defaultedJson = JsonOutput.toJson(defaulted)
assertThat((defaulted.constructCounts as Map)['wc.task.value-type.variable'] == 1,
    'a registered member at a defaulted site is identified')
assertThat((defaulted.accounting as Map).defaultBranchOccurrences == 3,
    "an unregistered, a missing and a non-String value each count once (${(defaulted.accounting as Map).defaultBranchOccurrences})")
assertThat(!(defaulted.unrecognised as List).any { "${it.path}".endsWith('.vt') },
    'a defaulted site never produces an unrecognised record')
assertThat(!defaultedJson.contains('not-a-registered-value-type') && !defaultedJson.contains('4242'),
    'no value from a defaulted site reaches the result')

// ---- retained path length --------------------------------------------------

// Depth bounds traversal, not the retained string: a path grows two segments per
// nesting level and was measured at 248 characters by depth 45.
Map deepPath = [:]
Map pathCursor = deepPath
(1..40).each { pathCursor.s = [[t: 'do']]; pathCursor = (pathCursor.s as List)[0] as Map }
pathCursor.s = [[t: 'first-unknown-statement'], [t: 'second-unknown-statement']]
Map elided = walker.collectWebcoreDecodeCoverage(deepPath, registry) as Map
List elidedRecords = elided.unrecognised as List
assertThat(elidedRecords.size() == 2, "both deep gaps are retained (${elidedRecords.size()})")
assertThat(elidedRecords.every { "${it.path}".length() <= 200 },
    "every retained path is within the cap (max ${elidedRecords.collect { "${it.path}".length() }.max()})")
assertThat(elidedRecords.every { "${it.path}".contains('<path-elided>') },
    'a path over the cap carries the fixed elision marker')
assertThat(elidedRecords.every { "${it.path}".startsWith('$.s[0]') && "${it.path}".endsWith('.t') },
    'the elided path keeps both its root context and its leaf')
// Two sibling gaps share a head and a tail, so this also proves deduplication
// still runs on the full path rather than the shortened one.
assertThat(elidedRecords.collect { it.path }.unique().size() == 2,
    'two distinct deep gaps stay two distinguishable records')

// ---- preset names are gated by the value type -------------------------------

// The preset-name dispatch sits inside the time/datetime branch of the value-type
// switch, so a setColor parameter carrying t:'s' with a colour name is not a
// missing preset. Found on a real piston, not in a hand-built fixture.
Map presets = walker.collectWebcoreDecodeCoverage([s: [[t: 'action', k: [[c: 'setColor', p: [
    [t: 's', vt: 'color', s: 'Soft White'],
    [t: 's', vt: 'time', s: 'sunset'],
    [t: 's', vt: 'time', s: 'not-a-preset']
]]]]]], registry) as Map
assertThat((presets.constructCounts as Map)['wc.preset.sunset'] == 1,
    'a preset name under a time value type is identified')
assertThat(!JsonOutput.toJson(presets).contains('Soft White'),
    'a colour parameter value never reaches the result')
assertThat((presets.unrecognised as List).count { it.reason == 'unknown-operand-type' } == 1,
    "only the genuine unregistered preset is reported (${(presets.unrecognised as List).count { it.reason == 'unknown-operand-type' }})")
// Four: the colour value type at the preset site, plus all three parameters at
// the task value-type site, whose only registered member is 'variable'.
assertThat((presets.accounting as Map).defaultBranchOccurrences == 4,
    "every defaulted-site invocation is counted (${(presets.accounting as Map).defaultBranchOccurrences})")

// A condition's to and to2 are the comparison's offset operands, so constructs
// inside them are classified rather than walked past.
Map offsets = walker.collectWebcoreDecodeCoverage([s: [[t: 'if', c: [[t: 'condition', co: 'is',
    lo: [t: 'v', v: 'time'], to: [t: 'c', vt: 'time', c: 5], to2: [t: 'x', x: 'offset']]]]]], registry) as Map
assertThat((offsets.constructCounts as Map).containsKey('wc.operand.c') &&
           (offsets.constructCounts as Map).containsKey('wc.operand.x'),
    'a comparison offset operand is classified')

// Keys taken from named sites in the pinned source are legible rather than
// placeholders, which is what keeps the retained list about real gaps.
Map schemaKeys = walker.collectWebcoreDecodeCoverage([s: [[t: 'action', '$': 1, str: 'x', ok: true,
    l: 'x', rop: 'and', k: [[c: 'log', p: [[t: 'c', c: 1, g: 'all', f: 'l']]]]]],
    o: [cto: false, ced: 0]], registry) as Map
assertThat(!(schemaKeys.unrecognised as List).any { it.reason == 'unknown-key' },
    "reviewed schema keys are not reported as unknown (${(schemaKeys.unrecognised as List).findAll { it.reason == 'unknown-key' }*.path})")

// ---- A0: saved keys the executor reads that subscribeAll does not ----------

def unknownKeys = { Map out -> (out.unrecognised as List).findAll { it.reason == 'unknown-key' }*.path }
def count = { Map out, String id -> ((out.constructCounts as Map)[id] ?: 0) as Integer }

Map forLoop = walker.collectWebcoreDecodeCoverage([s: [[t: 'for', x: 'i', lo: [t: 'c', vt: 'integer', c: 1],
    lo2: [t: 'c', vt: 'integer', c: 5], lo3: [t: 'c', vt: 'integer', c: 1], s: []]]], registry) as Map
assertThat(unknownKeys(forLoop).isEmpty(), "a for loop's lo2 and lo3 are not unknown fields (${unknownKeys(forLoop)})")
assertThat(count(forLoop, 'wc.operand.c') == 3, "a for loop's start, end and step are all classified as operands (${count(forLoop, 'wc.operand.c')})")

Map everyTimer = walker.collectWebcoreDecodeCoverage([s: [[t: 'every', lo: [t: 'c', vt: 'd', c: 1],
    lo2: [t: 'c', vt: 'time', c: 3600000], lo3: [t: 'c', vt: 'integer', c: 0], s: []]]], registry) as Map
assertThat(unknownKeys(everyTimer).isEmpty(), "an every timer's lo2 and lo3 are not unknown fields (${unknownKeys(everyTimer)})")
assertThat(count(everyTimer, 'wc.operand.c') == 3, "an every timer's lo2 and lo3 are classified as operands (${count(everyTimer, 'wc.operand.c')})")

Map loWrongPlace = walker.collectWebcoreDecodeCoverage([s: [[t: 'if', c: [],
    lo2: [t: 'c', vt: 'integer', c: 5], lo3: [t: 'c', vt: 'integer', c: 1], s: []]]], registry) as Map
assertThat(unknownKeys(loWrongPlace).isEmpty() && count(loWrongPlace, 'wc.operand.c') == 0,
    'lo2 and lo3 under a statement that does not read them are not given the operand route')

Map fallThrough = walker.collectWebcoreDecodeCoverage([s: [[t: 'switch', ctp: 'e', lo: [t: 'c', vt: 'integer', c: 1],
    cs: [[t: 's', ro: [t: 'c', vt: 'integer', c: 1], s: []]]]]], registry) as Map
assertThat(unknownKeys(fallThrough).isEmpty(), "a fall-through switch's ctp is not an unknown field (${unknownKeys(fallThrough)})")

Map negatedRestriction = walker.collectWebcoreDecodeCoverage([s: [[t: 'action', rn: true, rop: 'and',
    r: [[t: 'restriction', co: 'is', lo: [t: 'v', v: 'mode'], ro: [t: 'c', vt: 'string', c: 'x']]], k: []]]], registry) as Map
assertThat(unknownKeys(negatedRestriction).isEmpty(), "a negated restriction's rn is not an unknown field (${unknownKeys(negatedRestriction)})")

Map followedBy = walker.collectWebcoreDecodeCoverage([s: [[t: 'if', s: [], c: [[t: 'group', o: 'followed by', c: [
    [t: 'condition', co: 'changes', lo: [t: 'v', v: 'mode']],
    [t: 'condition', co: 'changes', lo: [t: 'v', v: 'mode'], wt: 'l', wd: [t: 'c', vt: 'm', c: 5]]]]]]]], registry) as Map
assertThat(unknownKeys(followedBy).isEmpty(), "a followed-by step's wt and wd are not unknown fields (${unknownKeys(followedBy)})")
assertThat(count(followedBy, 'wc.operand.c') == 1, "a followed-by step's wait delay is classified as an operand (${count(followedBy, 'wc.operand.c')})")

Map wdWrongPlace = walker.collectWebcoreDecodeCoverage([s: [[t: 'if', s: [], c: [[t: 'group', o: 'followed by',
    wd: [t: 'c', vt: 'm', c: 5], c: [[t: 'condition', co: 'changes', lo: [t: 'v', v: 'mode']]]]]]]], registry) as Map
assertThat(unknownKeys(wdWrongPlace).isEmpty() && count(wdWrongPlace, 'wc.operand.c') == 0,
    'wd on a condition group rather than a followed-by step is not given the operand route')

Map wdOrdinary = walker.collectWebcoreDecodeCoverage([s: [[t: 'if', s: [], c: [
    [t: 'condition', co: 'changes', lo: [t: 'v', v: 'mode'], wd: [t: 'c', vt: 'm', c: 5]]]]]], registry) as Map
assertThat(unknownKeys(wdOrdinary).isEmpty() && count(wdOrdinary, 'wc.operand.c') == 0,
    'wd on an ordinary condition outside a followed-by list is not given the operand route')

Map wdAndGroup = walker.collectWebcoreDecodeCoverage([s: [[t: 'if', s: [], c: [[t: 'group', o: 'and', c: [
    [t: 'condition', co: 'changes', lo: [t: 'v', v: 'mode'], wd: [t: 'c', vt: 'm', c: 5]]]]]]]], registry) as Map
assertThat(count(wdAndGroup, 'wc.operand.c') == 0, 'wd on a condition inside an and group is not given the operand route')

Map statementFollowedBy = walker.collectWebcoreDecodeCoverage([s: [[t: 'if', o: 'followed by', s: [], c: [
    [t: 'condition', co: 'changes', lo: [t: 'v', v: 'mode']],
    [t: 'condition', co: 'changes', lo: [t: 'v', v: 'mode'], wd: [t: 'c', vt: 'm', c: 5]]]]]], registry) as Map
assertThat(count(statementFollowedBy, 'wc.operand.c') == 1,
    "wd on a step of a statement-level followed-by list is one operand (${count(statementFollowedBy, 'wc.operand.c')})")

Map taskModes = walker.collectWebcoreDecodeCoverage([s: [[t: 'action', k: [[c: 'on', m: ['mode-one', 'mode-two'], p: []]]]]], registry) as Map
assertThat(unknownKeys(taskModes).isEmpty(), "a task's mode restriction m is not an unknown field (${unknownKeys(taskModes)})")
assertThat(!(taskModes.unrecognised as List).any { it.reason == 'unknown-device-selector' } &&
           (taskModes.constructCounts as Map).keySet().every { !"${it}".startsWith('wc.device-selector.') },
    "a task's mode list is not read as a device list")

Map customTask = walker.collectWebcoreDecodeCoverage([s: [[t: 'action', k: [[c: 'refreshNow', cm: true, p: []]]]]], registry) as Map
assertThat(unknownKeys(customTask).isEmpty(), "a custom command task's cm flag is not an unknown field (${unknownKeys(customTask)})")

Map opaque = walker.collectWebcoreDecodeCoverage([s: [[t: 'action', zc: 'a comment', data: [t: 'c', vt: 'integer', c: 1], k: []]]], registry) as Map
List opaqueRecords = (opaque.unrecognised as List).findAll { it.reason == 'known-opaque-field' }
assertThat(opaqueRecords*.path.sort() == ['$.s[0].data', '$.s[0].zc'], "zc and data are reported as known opaque fields with legible paths (${opaqueRecords*.path})")
assertThat(unknownKeys(opaque).isEmpty() && count(opaque, 'wc.operand.c') == 0, 'nothing inside an opaque field is interpreted')
assertThat(!JsonOutput.toJson(opaque).contains('a comment'), 'an opaque field value never reaches the result')
assertThat((opaque.accounting as Map).objectsVisited >= 3, 'an opaque field is still counted by the traversal accounting')

String opaqueCanary = 'CANARYd4a1c9'
Map deepOpaque = walker.collectWebcoreDecodeCoverage([s: [[t: 'action', k: [], data: [
    (opaqueCanary + 'Key'): [t: 'c', vt: 'integer', c: 1, data: [inner: opaqueCanary]],
    'arbitrary nested key': [[t: 'p', d: ['a1b2c3d4'], x: opaqueCanary]]]]]], registry) as Map
String deepOpaqueJson = JsonOutput.toJson(deepOpaque)
assertThat((deepOpaque.unrecognised as List).size() == 1 &&
           (deepOpaque.unrecognised as List)[0].path == '$.s[0].data' &&
           (deepOpaque.unrecognised as List)[0].reason == 'known-opaque-field',
    "an opaque field with nested structure yields exactly one finding at its root (${deepOpaque.unrecognised})")
assertThat((deepOpaque.constructCounts as Map).keySet() == (['wc.statement.action'] as Set),
    "nothing nested inside an opaque field is classified (${(deepOpaque.constructCounts as Map).keySet()})")
assertThat(!deepOpaqueJson.contains(opaqueCanary) && !deepOpaqueJson.contains('arbitrary nested key') &&
           !deepOpaqueJson.contains('a1b2c3d4') && !deepOpaqueJson.contains('inner'),
    'no nested opaque key name or value reaches the result')
assertThat((deepOpaque.accounting as Map).objectsVisited >= 5 && (deepOpaque.accounting as Map).arraysVisited >= 3,
    'opaque descendants are still counted by the traversal accounting')

Map editorDiagnostics = walker.collectWebcoreDecodeCoverage([s: [[t: 'action', k: [[c: 'log', p: [[t: 'e',
    exp: [t: 'expression', err: 'x', errVar: 'y', loc: 1, i: [[t: 'variable', x: 'z', ok: false, err: 'w']]]]]]]]]], registry) as Map
assertThat(unknownKeys(editorDiagnostics).size() == 4 && !(editorDiagnostics.unrecognised as List).any { it.reason == 'known-opaque-field' },
    'editor diagnostics err, errVar and loc stay unknown fields, as the live fixture recorded')

// ---- the unselected task parameter -----------------------------------------

Map unselected = walked['unselected-task-parameter.json']
assertThat((unselected.constructCounts as Map)['wc.task-parameter.unselected'] == 2,
    "both observed parameter shapes identify as the fixed construct (${(unselected.constructCounts as Map)['wc.task-parameter.unselected']})")
assertThat(!(unselected.unrecognised as List).any { it.reason == 'malformed-node' },
    'the previous malformed-node records are gone')
String unselectedJson = JsonOutput.toJson(unselected)
assertThat(!unselectedJson.contains('placeholder') && !unselectedJson.contains('setColor') &&
           !unselectedJson.contains('toggleRandom') && !unselectedJson.contains('0123456789abcdef'),
    'no parameter field or value enters the result')

// The construct is scoped to the task-parameter position. A missing t anywhere
// else is still a node the decoder cannot classify.
Map missingElsewhere = walker.collectWebcoreDecodeCoverage([s: [
    [t: 'if', c: [[t: 'condition', co: 'is', lo: [a: 'switch']]]],
    [t: 'on', c: [[lo: [a: 'switch']]]]
]], registry) as Map
assertThat((missingElsewhere.unrecognised as List).count { it.reason == 'malformed-node' } == 2,
    "a missing discriminator outside a task parameter is still malformed-node (${(missingElsewhere.unrecognised as List).count { it.reason == 'malformed-node' }})")
assertThat(!(missingElsewhere.constructCounts as Map).containsKey('wc.task-parameter.unselected'),
    'the unselected construct never appears outside a task parameter')

// ---- the deadline is enforced at both boundaries ----------------------------

// The periodic check only keeps the clock cheap. On its own it would never fire
// for a document smaller than one check interval, so the boundaries carry the
// contract and these fixtures are deliberately far under 2000 values.
Map smallDoc = [s: [[t: 'action', k: [[c: 'setVariable', p: [[t: 'x', x: 'counter', vt: 'variable']]]]]]]

Map alreadyExpired = walker.collectWebcoreDecodeCoverage(smallDoc, registry, { -> true }) as Map
assertThat(alreadyExpired.status == 'truncated' &&
           (alreadyExpired.truncation as Map).reason == 'analysis-deadline',
    'a deadline already passed before traversal is reported, not ignored')
assertThat((alreadyExpired.accounting as Map).objectsVisited == 0 &&
           (alreadyExpired.accounting as Map).scalarsVisited == 0,
    'nothing is walked once the deadline has already passed')
assertThat(alreadyExpired.constructCounts == [:] && alreadyExpired.unrecognised == [],
    'a deadline before traversal exposes no partial counts')

// False on the entry check, true on the exit check: the walk runs to completion
// and is then refused, which is the case the periodic check alone cannot catch.
int deadlineCalls = 0
Map expiredAfter = walker.collectWebcoreDecodeCoverage(smallDoc, registry,
    { -> deadlineCalls++; deadlineCalls > 1 }) as Map
assertThat(deadlineCalls == 2,
    "a walk shorter than the check interval consults the clock exactly twice (${deadlineCalls})")
assertThat(expiredAfter.status == 'truncated' &&
           (expiredAfter.truncation as Map).reason == 'analysis-deadline',
    'a deadline that passes during a short walk is caught on the way out')
assertThat((expiredAfter.accounting as Map).objectsVisited > 0,
    'the walk did happen, so the exit check is what refused it')

Map insideBudget = walker.collectWebcoreDecodeCoverage(smallDoc, registry, { -> false }) as Map
assertThat(insideBudget.status == 'complete',
    'the same document completes while inside budget')
assertThat((insideBudget.accounting as Map).objectsVisited ==
           (expiredAfter.accounting as Map).objectsVisited,
    'the refused walk and the accepted walk covered the same document')

// A document long enough to reach the periodic check keeps that behaviour, so
// the boundary checks are an addition rather than a replacement.
Map longDoc = [s: (1..1200).collect { [t: 'do'] }]
int periodicCalls = 0
Map periodic = walker.collectWebcoreDecodeCoverage(longDoc, registry,
    { -> periodicCalls++; periodicCalls > 1 }) as Map
assertThat(periodic.status == 'truncated' &&
           (periodic.accounting as Map).objectsVisited > 0 &&
           (periodic.accounting as Map).objectsVisited < 1201,
    "the periodic check still stops a long walk part way through (${(periodic.accounting as Map).objectsVisited} of 1201 objects)")
assertThat(periodicCalls == 2,
    "the entry check and one periodic check are what ran, not an exit check (${periodicCalls})")

// ---- A1b: structural validation per statement occurrence --------------------

String shapesText = new File(repoRoot, 'tools/webcore-investigation/generated/webcore_statement_shapes.groovy').getText('UTF-8')
Map shapes = new GroovyClassLoader(this.class.classLoader)
        .parseClass("class ShapesForWalker {\n" + shapesText + "\n}").newInstance().webcoreStatementShapes() as Map

def census = { Object w, Map doc, Map reg = registry -> w.collectWebcoreDecodeCoverage(doc, reg, null, shapes) as Map }
def occurrence = { Map out, String id -> ((out.constructOccurrences as Map)[id] ?: [structurallyValid: 0, structurallyInvalid: 0]) as Map }
def isValid = { Map out, String id -> occurrence(out, id).structurallyValid == 1 && occurrence(out, id).structurallyInvalid == 0 }
def isInvalid = { Map out, String id -> occurrence(out, id).structurallyInvalid == 1 && occurrence(out, id).structurallyValid == 0 }
def categoriesOf = { Map out -> (out.structureFindings as List).collect { it.category }.unique().sort() }

def leafNode = { Map extra = [:] -> [t: 'condition', lo: [t: 'v', v: 'mode'], co: 'is', ro: [t: 'c', vt: 'string', c: 'x'],
    ro2: [t: 'c', vt: 'string', c: 'y'], to: [t: 'c', vt: 'integer', c: 0], to2: [t: 'c', vt: 'integer', c: 0], ts: [], fs: [], sm: 'auto'] + extra }
def groupNode = { List c, Map extra = [:] -> [t: 'group', c: c, o: 'and', ts: [], fs: [], sm: 'auto'] + extra }
def stmtNode = { String t, Map extra -> [t: t, a: '0', r: [], rop: 'and'] + extra }
def ifNode = { List c, Map extra = [:] -> stmtNode('if', [o: 'and', c: c, s: [], ei: [], e: []] + extra) }
def waitOp = { [t: 'c', vt: 'm', c: 5] }
def switchNode = { List cs, Map extra = [:] -> stmtNode('switch', [lo: [t: 'c', vt: 'integer', c: 1], cs: cs, e: [], ctp: 'i'] + extra) }
def everyNode = { String unit -> stmtNode('every', [a: '1', lo: [t: 'c', vt: unit, c: 1], lo2: [t: 'c', vt: 'integer', c: 5],
    lo3: [t: 'c', vt: 'integer', c: 0], s: []]) }

Map validIf = census(walker, [s: [ifNode([leafNode()])]])
assertThat(isValid(validIf, 'wc.statement.if') && (validIf.structureFindings as List).isEmpty() && (validIf.unrecognised as List).isEmpty(),
    "an if in its current-editor shape is one structurally valid occurrence (${validIf.constructOccurrences} ${validIf.structureFindings})")

// Invariants: validation never steers, counts or bounds the walk.
Map richDoc = [s: [ifNode([leafNode(), groupNode([leafNode([ok: true])])], [ok: true, ei: [[o: 'and', c: [leafNode()], s: [[t: 'do', s: []]]]]]),
                   switchNode([[t: 's', ro: [t: 'c', vt: 'integer', c: 1], s: []]], [s: [[t: 'action', k: []]]]),
                   stmtNode('exit', [lo: [[t: 'c', vt: 'integer', c: 1]]]), everyNode('m')]]
Map plain = walker.collectWebcoreDecodeCoverage(richDoc, registry) as Map
Map shaped = census(walker, richDoc)
List visitedFields = ['objectsVisited', 'arraysVisited', 'fieldsVisited', 'arrayElementsVisited', 'scalarsVisited']
assertThat(visitedFields.every { (plain.accounting as Map)[it] == (shaped.accounting as Map)[it] } &&
           plain.status == shaped.status && plain.truncation == shaped.truncation,
    "structural validation leaves the visited-value accounting, status and truncation unchanged (${plain.accounting} ${shaped.accounting})")
// Shape routing withholds classification from unconsumed or malformed positions, so construct
// candidates can only fall, never rise, and no new unrecognised finding can appear.
assertThat(((shaped.accounting as Map).constructCandidates as Integer) <= ((plain.accounting as Map).constructCandidates as Integer) &&
           (plain.unrecognised as List).containsAll(shaped.unrecognised as List),
    'validation only withholds classification: candidates do not rise and no unrecognised finding is added')
Map deepShaped = [s: [[t: 'do', s: []]]]
Map deepCursor = deepShaped.s[0] as Map
(1..120).each { Map next = [t: 'do', s: []]; (deepCursor.s as List) << next; deepCursor = next }
assertThat(census(walker, deepShaped).status == 'truncated' && (census(walker, deepShaped).truncation as Map).reason == 'depth-limit' &&
           census(walker, deepShaped).accounting == (walker.collectWebcoreDecodeCoverage(deepShaped, registry) as Map).accounting,
    'the depth bound truncates exactly as it does without validation')

// Unexpected own-node keys cap the occurrence.
Map unexpected = census(walker, [s: [ifNode([leafNode()], [ok: true])]])
assertThat(isInvalid(unexpected, 'wc.statement.if') && categoriesOf(unexpected) == ['unexpected-key'] &&
           (unexpected.structureFindings as List)[0].path == '$.s[0].ok',
    "an allowlisted key the statement shape does not model is an unexpected-key mismatch (${unexpected.structureFindings})")
Map unknownOwn = census(walker, [s: [ifNode([leafNode()], [CANARYkeyA1b: 'CANARYvalA1b'])]])
assertThat(isInvalid(unknownOwn, 'wc.statement.if') && (unknownOwn.structureFindings as List).isEmpty() &&
           !JsonOutput.toJson(unknownOwn).contains('CANARY'),
    'an unknown own key is an existing unknown-key finding that invalidates the occurrence without leaking its name')
Map opaqueOwn = census(walker, [s: [ifNode([leafNode()], [zc: 'a comment'])]])
assertThat(isInvalid(opaqueOwn, 'wc.statement.if') && !JsonOutput.toJson(opaqueOwn).contains('a comment'),
    'an opaque field on the statement invalidates the occurrence')

// Validated child structures fail their owning statement.
Map childElseIf = census(walker, [s: [ifNode([leafNode()], [ei: [[o: 'and', c: [leafNode()], s: [], ok: true]]])]])
assertThat(isInvalid(childElseIf, 'wc.statement.if') && (childElseIf.structureFindings as List)*.path == ['$.s[0].ei[0].ok'],
    'an unexpected key on an else-if fails the owning if')
Map childCase = census(walker, [s: [switchNode([[t: 's', ro: [t: 'c', vt: 'integer', c: 1], ro2: [t: 'c', vt: 'integer', c: 2], s: [], ok: true]])]])
assertThat(isInvalid(childCase, 'wc.statement.switch'), 'an unexpected key on a case fails the owning switch')
Map childEvent = census(walker, [s: [stmtNode('on', [a: '1', o: 'or', s: [], c: [[t: 'event', lo: [t: 'v', v: 'mode'], sm: 'auto', ok: true]]])]])
assertThat(isInvalid(childEvent, 'wc.statement.on'), 'an unexpected key on an event fails the owning on statement')
Map childTask = census(walker, [s: [stmtNode('action', [d: [], k: [[c: 'on', p: [], ok: true]]])]])
assertThat(isInvalid(childTask, 'wc.statement.action'), 'an unexpected key on a task fails the owning action')
Map foreignVariant = census(walker, [s: [ifNode([groupNode([leafNode()], [lo: [t: 'v', v: 'mode']])])]])
assertThat(isInvalid(foreignVariant, 'wc.statement.if') && categoriesOf(foreignVariant) == ['variant-foreign-key'],
    "a leaf key on a group is a variant-foreign-key mismatch (${foreignVariant.structureFindings})")
Map noDiscriminator = census(walker, [s: [ifNode([leafNode().findAll { k, v -> k != 't' }])]])
assertThat(isInvalid(noDiscriminator, 'wc.statement.if') && categoriesOf(noDiscriminator) == ['missing-discriminator'] &&
           !(noDiscriminator.constructCounts as Map).containsKey('wc.virtual-device.mode'),
    'a condition without t is a missing-discriminator mismatch and nothing beneath it is classified')
Map badDiscriminator = census(walker, [s: [ifNode([leafNode([t: 'bogus'])])]])
assertThat(categoriesOf(badDiscriminator) == ['unknown-variant'], 'a condition with an unknown t is an unknown-variant mismatch')

// Ownership: a nested statement owns its own findings; an operand or restriction finding belongs to its statement.
Map nested = census(walker, [s: [ifNode([leafNode()], [s: [stmtNode('do', [s: [], ok: true])]])]])
assertThat(isValid(nested, 'wc.statement.if') && isInvalid(nested, 'wc.statement.do'),
    'an invalid nested statement does not invalidate the statement that contains it')
Map operandFinding = census(walker, [s: [stmtNode('action', [d: [], k: [[c: 'on', p: [[t: 'no-such-operand-type']]]]])]])
assertThat(isInvalid(operandFinding, 'wc.statement.action'), 'an unknown operand beneath a task invalidates the owning action')
Map restrictionShape = census(walker, [s: [stmtNode('action', [d: [], k: [], r: [[t: 'restriction', co: 'is', lo: [t: 'v', v: 'mode'], ok: true]]])]])
assertThat(isValid(restrictionShape, 'wc.statement.action') && (restrictionShape.structureFindings as List).isEmpty(),
    'restriction members are outside this increment: their shape is not validated')
Map restrictionFinding = census(walker, [s: [stmtNode('action', [d: [], k: [], r: [[t: 'restriction', co: 'is', lo: [t: 'no-such-operand-type']]]])]])
assertThat(isInvalid(restrictionFinding, 'wc.statement.action'), 'an existing finding inside a restriction still invalidates the owning statement')

// Persistence semantics.
Map missingAlways = census(walker, [s: [ifNode([leafNode()]).findAll { k, v -> k != 'rop' }]])
assertThat(categoriesOf(missingAlways) == ['missing-key'], 'a missing always key is missing-key')
Map emptyKept = census(walker, [s: [ifNode([leafNode()], [n: false])]])
assertThat(categoriesOf(emptyKept) == ['empty-persisted'], 'an unless-empty key kept as false is empty-persisted')
Map neverKept = census(walker, [s: [stmtNode('on', [a: '1', o: 'or', n: true, s: [], c: []])]])
assertThat(categoriesOf(neverKept) == ['never-persisted'], 'a never-persisted key that is present is never-persisted')
Map optionalAbsent = census(walker, [s: [stmtNode('action', [d: [], k: [[c: 'on', p: []]]])]])
assertThat(isValid(optionalAbsent, 'wc.statement.action'), 'user-optional and round-trip keys may be absent')
Map roundTrip = census(walker, [s: [ifNode([leafNode(['$': 2, ct: 'c', s: true])], ['$': 1])]])
assertThat(isValid(roundTrip, 'wc.statement.if'), 'hub-written round-trip keys are valid when present')
Map laterMissingWd = census(walker, [s: [ifNode([leafNode(), leafNode([wt: 'l'])], [o: 'followed by'])]])
assertThat(categoriesOf(laterMissingWd) == ['missing-key'], 'a later followed-by step without wd is missing-key')
Map retainedOk = census(walker, [s: [ifNode([leafNode([wd: waitOp()])])]])
assertThat(isValid(retainedOk, 'wc.statement.if'), 'a retained wd outside a later step is valid shape and not a mismatch')
Map retainedMalformed = census(walker, [s: [ifNode([leafNode([wd: 'not-an-operand'])])]])
assertThat(categoriesOf(retainedMalformed) == ['wrong-kind'], 'a malformed retained wd is still a wrong-kind mismatch')
Map retainedBadValue = census(walker, [s: [ifNode([leafNode([wt: 'x'])])]])
assertThat(categoriesOf(retainedBadValue) == ['bad-value'], 'a retained wt outside its value set is a bad-value mismatch')
Map singleCase = census(walker, [s: [switchNode([[t: 's', ro: [t: 'c', vt: 'integer', c: 1], s: []]])]])
assertThat(categoriesOf(singleCase) == ['missing-unconsumed-key'], 'a single-value case without ro2 is missing-unconsumed-key')

// Validation and meaning stay separate.
assertThat(count(retainedOk, 'wc.operand.c') == count(validIf, 'wc.operand.c'), 'a retained wd is not classified as an operand')
Map firstStepWd = census(walker, [s: [ifNode([leafNode([wd: waitOp()]), leafNode([wd: waitOp(), wt: 'l'])], [o: 'followed by'])]])
assertThat(count(firstStepWd, 'wc.operand.c') == count(validIf, 'wc.operand.c') * 2 + 1, "only the later step's wd is an operand (${count(firstStepWd, 'wc.operand.c')})")
Map groupLaterWd = census(walker, [s: [ifNode([leafNode(), groupNode([leafNode()], [wd: waitOp(), wt: 'l'])], [o: 'followed by'])]])
assertThat(isValid(groupLaterWd, 'wc.statement.if') && count(groupLaterWd, 'wc.operand.c') == count(validIf, 'wc.operand.c') * 2 + 1,
    "a group's wd on a later step is an operand (${count(groupLaterWd, 'wc.operand.c')})")
assertThat(count(census(walker, [s: [everyNode('m')]]), 'wc.operand.c') == 1 && count(census(walker, [s: [everyNode('d')]]), 'wc.operand.c') == 3,
    'an every timer classifies lo2 and lo3 only for a calendar unit')
Map singleWithRo2 = census(walker, [s: [switchNode([[t: 's', ro: [t: 'c', vt: 'integer', c: 1], ro2: [t: 'c', vt: 'integer', c: 2], s: []]])]])
assertThat(count(singleWithRo2, 'wc.operand.c') == 2, "a single-value case's ro2 is not classified (${count(singleWithRo2, 'wc.operand.c')})")
Map malformedLo = census(walker, [s: [stmtNode('exit', [lo: [[t: 'c', vt: 'integer', c: 1]]])]])
assertThat(isInvalid(malformedLo, 'wc.statement.exit') && count(malformedLo, 'wc.operand.c') == 0 &&
           (malformedLo.accounting as Map).objectsVisited == 3,
    'a list where an operand belongs is walked and counted but classifies nothing')
Map switchScalar = census(walker, [s: [switchNode([], [s: true, ct: 'c'])]])
assertThat(isValid(switchScalar, 'wc.statement.switch'), "a switch's round-trip s scalar is valid")
Map switchInjected = [s: [switchNode([], [s: [stmtNode('action', [d: [], k: []])]])]]
assertThat(!(census(walker, switchInjected).constructCounts as Map).containsKey('wc.statement.action') &&
           !((walker.collectWebcoreDecodeCoverage(switchInjected, registry) as Map).constructCounts as Map).containsKey('wc.statement.action') &&
           isInvalid(census(walker, switchInjected), 'wc.statement.switch'),
    "a list injected as a switch's s is not walked as statements, and fails the switch")

Map breakLo = census(walker, [s: [stmtNode('break', [lo: [t: 'c', vt: 'integer', c: 1]])]])
assertThat(isInvalid(breakLo, 'wc.statement.break') && categoriesOf(breakLo) == ['unexpected-key'] && count(breakLo, 'wc.operand.c') == 0,
    "an operand injected under break is an unexpected key and is not counted (${breakLo.structureFindings} ${breakLo.constructCounts})")
Map selectorDoc = [s: [ifNode([leafNode([d: [':0123456789abcdef0123456789abcdef:']])])]]
Map conditionD = census(walker, selectorDoc)
assertThat(isInvalid(conditionD, 'wc.statement.if') && categoriesOf(conditionD) == ['unexpected-key'] &&
           !(conditionD.constructCounts as Map).keySet().any { "${it}".startsWith('wc.device-selector.') } &&
           ((walker.collectWebcoreDecodeCoverage(selectorDoc, registry) as Map).constructCounts as Map).containsKey('wc.device-selector.direct-identifier'),
    'an excluded condition d holding a selector produces no device-selector construct, though the unvalidated walk counts one')

// The copy the IDE re-saves numbers every node, writes ct and s, and drops an empty task mode list
// and an empty else-if. A first save and its round trip are both structurally valid.
Map firstSaveDoc = [s: [ifNode([leafNode()], [tcp: 'c', ei: [[o: 'and', c: [leafNode()], s: []], [o: 'and', c: [], s: []]],
                                           s: [stmtNode('action', [tcp: 'c', d: [], k: [[c: 'noop', p: [], m: []]]])]])]]
Map roundTripDoc = [s: [ifNode([leafNode(['$': 2, ct: 'c', s: true])], ['$': 1, tcp: 'c', ei: [['$': 3, o: 'and', c: [leafNode(['$': 4, ct: 'c'])], s: []]],
                                           s: [stmtNode('action', ['$': 5, tcp: 'c', d: [], k: [['$': 6, c: 'noop', p: []]]])]])]]
Map firstSaveOut = census(walker, firstSaveDoc)
Map roundTripOut = census(walker, roundTripDoc)
assertThat(isValid(firstSaveOut, 'wc.statement.if') && isValid(firstSaveOut, 'wc.statement.action') &&
           isValid(roundTripOut, 'wc.statement.if') && isValid(roundTripOut, 'wc.statement.action'),
    "a first-save shape and its round-trip shape are both structurally valid (${firstSaveOut.structureFindings} ${roundTripOut.structureFindings})")

// Levels: statement ceilings are L3, and one holds only when every occurrence is valid and takes no
// evidence gap. Round-trip shaped nodes carry the hub-written keys, so they take no gap.
def rtLeaf = { Map extra = [:] -> leafNode(['$': 2, ct: 'c'] + extra) }
def rtStmt = { String t, Map extra -> stmtNode(t, ['$': 1, tcp: 'c'] + extra) }
def rtIf = { List c, Map extra = [:] -> rtStmt('if', [o: 'and', c: c, s: [], ei: [], e: []] + extra) }
def rtAction = { Map taskExtra = [:] -> rtStmt('action', [d: [], k: [[c: 'on', p: [], '$': 3] + taskExtra]]) }
def levelOf = { Object w, Map out, String id ->
    w.webcoreCensusAchievedLevel(((registry.constructs as Map)[id] as Map).level as String, (out.constructOccurrences as Map)[id], (out.constructCounts as Map)[id])
}
def gapsOf = { Map out, String id -> (occurrence(out, id).evidenceGaps ?: [:]) as Map }
assertThat(((registry.constructs as Map).findAll { k, v -> "${k}".startsWith('wc.statement.') }.values()*.level as Set) == (['L3'] as Set),
    'every statement ceiling in the registry is L3')
Map allValid = census(walker, [s: [rtIf([rtLeaf()]), rtIf([rtLeaf()])]])
Map mixed = census(walker, [s: [rtIf([rtLeaf()]), rtIf([rtLeaf()], [ok: true])]])
assertThat(levelOf(walker, allValid, 'wc.statement.if') == 'L3' && levelOf(walker, mixed, 'wc.statement.if') == 'L2' &&
           (mixed.levelCounts as Map).L3 == (allValid.levelCounts as Map).L3 - 1,
    "an L3 ceiling holds only when every occurrence is valid (${allValid.levelCounts} ${mixed.levelCounts})")
assertThat(walker.webcoreCensusAchievedLevel('L3', [structurallyValid: 1, structurallyInvalid: 0, evidenceGapped: 0], 2) == 'L2' &&
           walker.webcoreCensusAchievedLevel('L3', [structurallyValid: 1, structurallyInvalid: 0], 1) == 'L2' &&
           walker.webcoreCensusAchievedLevel('L3', null, 1) == 'L2' && walker.webcoreCensusAchievedLevel('L2', null, 1) == 'L2',
    'an occurrence left unvalidated, never recorded, or without a gap count caps an L3 ceiling at L2')

// Evidence gaps hold only the occurrence that takes an uncovered branch, never the family.
Map closedGaps = shapes.evidenceGaps as Map
assertThat(occurrence(roundTripOut, 'wc.statement.if').evidenceGapped == 0 && occurrence(roundTripOut, 'wc.statement.action').evidenceGapped == 0 &&
           levelOf(walker, roundTripOut, 'wc.statement.if') == 'L3' && levelOf(walker, roundTripOut, 'wc.statement.action') == 'L3',
    "a round-trip shape takes no evidence gap and reaches L3 (${roundTripOut.constructOccurrences})")
assertThat(gapsOf(firstSaveOut, 'wc.statement.if').containsKey('statement/$/absent') &&
           (gapsOf(firstSaveOut, 'wc.statement.if').keySet() + gapsOf(firstSaveOut, 'wc.statement.action').keySet()).every { closedGaps[it] == 'editor-authored-only' } &&
           levelOf(walker, firstSaveOut, 'wc.statement.if') == 'L2',
    "a first-save shape is valid but held at L2 by editor-authored-only branches alone (${firstSaveOut.constructOccurrences})")
Map cmMixed = census(walker, [s: [rtAction(), rtAction([cm: true])]])
assertThat(occurrence(cmMixed, 'wc.statement.action').structurallyValid == 2 && occurrence(cmMixed, 'wc.statement.action').evidenceGapped == 1 &&
           gapsOf(cmMixed, 'wc.statement.action') == ['task/cm/present': 1] && levelOf(walker, cmMixed, 'wc.statement.action') == 'L2' &&
           levelOf(walker, census(walker, [s: [rtAction(), rtAction()]]), 'wc.statement.action') == 'L3',
    "a task carrying cm holds its own occurrence, and ordinary actions reach L3 (${cmMixed.constructOccurrences})")
Map smIf = census(walker, [s: [rtIf([rtLeaf()], [sm: 'CANARYsm'])]])
assertThat(gapsOf(smIf, 'wc.statement.if') == ['statement/sm/present': 1] && levelOf(walker, smIf, 'wc.statement.if') == 'L2' &&
           !JsonOutput.toJson(smIf).contains('CANARY'),
    "an if carrying sm is held by a gap id that names the branch, never the saved value (${smIf.constructOccurrences})")
Map triggerIf = census(walker, [s: [rtIf([rtLeaf([ct: 't'])])]])
assertThat(gapsOf(triggerIf, 'wc.statement.if') == ['condition/ct/value:t': 1],
    "a condition whose reloaded ct is a trigger is held by the canonical-only gap (${triggerIf.constructOccurrences})")
Map nestedGap = census(walker, [s: [rtIf([rtLeaf()], [s: [rtStmt('do', [s: [], sm: 'always'])]])]])
assertThat(occurrence(nestedGap, 'wc.statement.if').evidenceGapped == 0 && gapsOf(nestedGap, 'wc.statement.do') == ['statement/sm/present': 1],
    'a gap in a nested statement belongs to that statement, not the one containing it')
Map invalidGap = census(walker, [s: [rtIf([rtLeaf()], [sm: 'always', ok: true])]])
assertThat(occurrence(invalidGap, 'wc.statement.if').structurallyInvalid == 1 && occurrence(invalidGap, 'wc.statement.if').evidenceGapped == 0 &&
           gapsOf(invalidGap, 'wc.statement.if').isEmpty(),
    'an invalid occurrence records no gap: it is already held below its ceiling')
Map outsideDoc = census(walker, [s: [rtIf([rtLeaf()], [CANARYinside: 1])], CANARYroot: 1])
assertThat(outsideDoc.unrecognisedOutsideStatements == 1 && (outsideDoc.unrecognised as List).size() == 2 &&
           (walker.collectWebcoreDecodeCoverage([s: [], CANARYroot: 1], registry) as Map).unrecognisedOutsideStatements == null,
    'unrecognised positions outside every statement occurrence are counted apart, and only when statements are validated')

// Bounded, fixed output.
Map many = census(walker, [s: (1..60).collect { ifNode([leafNode()], [ok: true, CANARYkeyA1b: 'CANARYvalA1b']) }])
assertThat((many.structureFindings as List).size() == 50 && many.structureFindingsOverflow == 10,
    "structure findings are capped with an overflow count (${(many.structureFindings as List).size()} + ${many.structureFindingsOverflow})")
Set allowedSegments = walker.webcoreCensusSchemaKeys() as Set
assertThat((many.structureFindings as List).every { Map f ->
        walker.webcoreCensusStructureCategories().contains(f.category) && (f.path as String).length() <= 200 &&
        (f.path as String).tokenize('.').every { String seg -> String bare = seg.replaceAll(/\[\d+\]/, ''); bare == '$' || bare.startsWith('<') || allowedSegments.contains(bare) } } &&
        !JsonOutput.toJson(many).contains('CANARY'),
    'every structure finding is a closed category with a bounded path of reviewed key names, and no value or unknown key name')

// Mutation evidence: each rule, removed from a copy of the walker source, lets a scenario above pass wrongly.
def mutant = { String from, String to ->
    assert block.contains(from): "mutation anchor not found: ${from}"
    new GroovyClassLoader(this.class.classLoader).parseClass("class WebcoreCensusMutant {\n" + block.replace(from, to) + "\n}").newInstance()
}
def broadS = mutant("if (key == 's') return (t == 'switch') ? null : 'statement'", "if (key == 's') return 'statement'")
assertThat(((broadS.collectWebcoreDecodeCoverage(switchInjected, registry) as Map).constructCounts as Map).containsKey('wc.statement.action'),
    "mutation: restoring the broad s route walks an injected switch list as statements")
def noUnexpected = mutant("else if (schemaKeys.contains(key)) webcoreCensusMismatch(acc, path + '.' + key, 'unexpected-key')", "")
assertThat(isValid(census(noUnexpected, [s: [ifNode([leafNode()], [ok: true])]]), 'wc.statement.if'),
    'mutation: without the unexpected-key rule an extra own key no longer caps the occurrence')
def noTaint = mutant("if (stack) (stack[stack.size() - 1] as Map).invalid = true", "if (false) (stack[stack.size() - 1] as Map).invalid = true")
assertThat(isValid(census(noTaint, [s: [ifNode([leafNode()], [CANARYkeyA1b: 1])]]), 'wc.statement.if'),
    'mutation: without taint an existing unknown finding no longer caps the occurrence')
def noChildren = mutant("if (context in ['elseif', 'case', 'event', 'task']) return [keys: (subs[context] as Map).keys as Map, foreign: [] as Set]",
                        "if (context in ['elseif', 'case', 'event', 'task']) return null")
assertThat(isValid(census(noChildren, [s: [ifNode([leafNode()], [ei: [[o: 'and', c: [leafNode()], s: [], ok: true]]])]]), 'wc.statement.if'),
    'mutation: without child shapes an unexpected else-if key no longer fails its if')
def noRoute = mutant("if (known && childContext != null && shape != null) childContext = webcoreCensusShapeRoute(node, context, key, child, childContext, shape)", "")
assertThat(count(census(noRoute, [s: [everyNode('m')]]), 'wc.operand.c') == 3,
    'mutation: without shape routing an unconsumed every lo2 and lo3 are classified')
def fallThroughRoute = mutant("if (!(s instanceof Map)) return null", "if (!(s instanceof Map)) return childContext")
assertThat(count(census(fallThroughRoute, [s: [stmtNode('break', [lo: [t: 'c', vt: 'integer', c: 1]])]]), 'wc.operand.c') == 1 &&
           (census(fallThroughRoute, selectorDoc).constructCounts as Map).containsKey('wc.device-selector.direct-identifier'),
    'mutation: restoring the fall-through route counts the injected operand and the excluded selector')
def noFirstStep = mutant("return index == 0 ? 'followed-by-first-step' : 'followed-by-later-step'", "return 'followed-by-later-step'")
assertThat(count(census(noFirstStep, [s: [ifNode([leafNode([wd: waitOp()]), leafNode([wd: waitOp(), wt: 'l'])], [o: 'followed by'])]]), 'wc.operand.c') ==
           count(firstStepWd, 'wc.operand.c') + 1,
    "mutation: without the first-step context the first step's wd is classified")
def noRetainedKind = mutant("else if (!webcoreCensusKindOk(spec.kind as String, v)) webcoreCensusMismatch(acc, at, 'wrong-kind')",
                            "else if (false) webcoreCensusMismatch(acc, at, 'wrong-kind')")
assertThat(isValid(census(noRetainedKind, [s: [ifNode([leafNode([wd: 'not-an-operand'])])]]), 'wc.statement.if'),
    'mutation: without the retained kind check a malformed retained wd passes')
def ceilingIgnored = mutant("return ((invalid as Integer) == 0 && (gapped as Integer) == 0 && (valid as Integer) == (count as Integer)) ? ceiling : 'L2'", "return ceiling")
assertThat(levelOf(ceilingIgnored, census(ceilingIgnored, [s: [rtIf([rtLeaf()]), rtIf([rtLeaf()], [ok: true])]]), 'wc.statement.if') == 'L3',
    'mutation: a level that ignores invalid occurrences reports L3 for a mixed construct')
def gapsIgnoredByLevel = mutant("(invalid as Integer) == 0 && (gapped as Integer) == 0 &&", "(invalid as Integer) == 0 &&")
assertThat(levelOf(gapsIgnoredByLevel, census(gapsIgnoredByLevel, [s: [rtAction([cm: true])]]), 'wc.statement.action') == 'L3',
    'mutation: a level that ignores evidence gaps reports L3 for an action whose task carries cm')
def noGapRecording = mutant("if (gaps.containsKey(id)) recorded << id", "")
assertThat(occurrence(census(noGapRecording, [s: [rtAction([cm: true])]]), 'wc.statement.action').evidenceGapped == 0,
    'mutation: without gap recording a task carrying cm is no longer held')
def gapsOnInvalid = mutant("if (top.invalid != true && (top.gaps as Set)) {", "if ((top.gaps as Set)) {")
assertThat(occurrence(census(gapsOnInvalid, [s: [rtIf([rtLeaf()], [sm: 'always', ok: true])]]), 'wc.statement.if').evidenceGapped == 1,
    'mutation: counting gaps on an invalid occurrence reports a held occurrence twice')

// ---- summary ---------------------------------------------------------------

int total = results.size()
int bad = results.count { !it }
println "${total - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
