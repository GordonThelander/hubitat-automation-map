#!/usr/bin/env groovy
//
// The L3 capture sanitiser, loaded from its checked-in source. A synthetic capture carries
// canaries in key and value positions, including nested opaque maps. The sanitised document
// must contain none of them, keep only reviewed keys and vocabulary, preserve equality, be
// deterministic, and give the walker exactly the structural result the raw document gives.
// Source mutations prove each privacy rule is exercised.
// Run with: groovy tests/webcore-l3-sanitiser.groovy

import groovy.json.JsonOutput

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('..').canonicalFile

List<Boolean> results = []
def check = { boolean cond, String label ->
    println "${cond ? 'PASS' : 'FAIL'}  ${label}"
    results << cond
}

File sanitiserFile = new File(repoRoot, 'tools/webcore-investigation/L3CaptureSanitiser.groovy')
String sanitiserSource = sanitiserFile.getText('UTF-8')
def load = { String source ->
    new GroovyClassLoader(this.class.classLoader).parseClass(source).getConstructor(File).newInstance(repoRoot)
}
def sanitiser = load(sanitiserSource)
def support = sanitiser.support
Map registry = support.webcoreCensusRegistry() as Map
Map shapes = support.webcoreStatementShapes() as Map
String realFunction = (registry.constructs as Map).keySet().collect { it.toString() }.find { it.startsWith('wc.function.') }.substring('wc.function.'.length())

String hex = '0123456789abcdef0123456789abcdef'
String hex2 = 'fedcba9876543210fedcba9876543210'
Map leaf = [t: 'condition', '$': 3, lo: [t: 'p', d: [':' + hex + ':'], a: 'switch', g: 'any'], co: 'is',
            ro: [t: 'c', c: 'CANARY literal', vt: 'string'], ro2: [t: 'c', c: 'CANARY literal', vt: 'string'],
            to: [t: 'c', c: 0, vt: 'integer'], to2: [t: 'c', c: 0, vt: 'integer'], ts: [], fs: [], sm: 'auto']
Map document = [
    n: 'CANARY piston name', id: 'CANARYpistonid', rop: 'and', rn: false, z: 'CANARY description',
    l: [(':' + hex + ':'): [t: 'device', n: 'CANARY device label']],
    v: [[n: 'CANARYvariable', t: 'integer', v: [t: 'c', c: 42, vt: 'integer']]],
    s: [
        [t: 'if', '$': 1, a: '0', r: [], rop: 'and', tcp: 'c', o: 'and', z: 'CANARY statement', s: [], ei: [], e: [],
         CANARYkeyTop: 'CANARYvalueTop', zc: 'CANARY comment',
         data: [CANARYopaqueKey: [CANARYnestedKey: 'CANARYnestedValue', n: 7, t: 'if']],
         c: [leaf,
             leaf + ['$': 4, lo: [t: 'v', v: 'orientation'], ro: [t: 'e', exp: [t: 'expression', i: [[t: 'function', n: 'CANARYfunction'],
                                                                                              [t: 'function', n: realFunction.toUpperCase()]]]],
                     ro2: [t: 'c', c: 'CANARY other', vt: 'string']]]],
        [t: 'every', '$': 5, a: '1', r: [], rop: 'and', tcp: 'c', lo: [t: 'c', c: 1, vt: 'd'], lo2: [t: 'c', c: 540, vt: 'time'],
         lo3: [t: 'c', c: 0, vt: 'm'], s: [[t: 'action', '$': 6, a: '0', r: [], rop: 'and', tcp: 'c', d: [':' + hex + ':'],
         k: [[c: 'noop', p: [], m: [':' + hex2 + ':'], z: 'CANARY task']]]]],
        [t: 'switch', '$': 7, a: '0', r: [], rop: 'and', tcp: 'c', lo: [t: 'x', x: 'CANARYvariable'], ctp: 'e', e: [],
         cs: [[t: 's', ro: [t: 'c', c: 1, vt: 'integer'], ro2: [t: 'c', c: 1, vt: 'integer'], s: []]]],
        [t: 'CANARYstatement', '$': 8],
        [t: 'action', '$': 9, a: '0', r: [], rop: 'and', tcp: 'c', d: [], k: [[c: 'noop', p: [[t: 'v', v: 'CANARYvirtual']]]]]
    ]
]
String encoded = JsonOutput.toJson(document).getBytes('UTF-8').encodeBase64().toString()
Map raw = [installedApp: [label: 'CANARY label'], appSettings: [[name: 'chunk:0', type: 'text', value: encoded]],
           appState: [[name: 'build', value: 3], [name: 'active', value: false], [name: 'logs', value: 'CANARY log']],
           eventSubscriptions: [[name: 'CANARY subscription']], scheduledJobs: []]

Map out = sanitiser.sanitiseCapture(raw) as Map
String json = sanitiser.render(out) as String

check(!json.toUpperCase().contains('CANARY'), 'no canary key, value, label, log or subscription survives')
check((json =~ /[0-9a-f]{32}/).collect { it.toString() }.every { it.startsWith('000000000000000000000000') },
    'every device or mode identifier is replaced by a synthetic zero-padded identifier')

List keyProblems = []
Closure walkKeys
walkKeys = { Object node, boolean opaque, String at ->
    if (node instanceof Map) {
        (node as Map).each { Object k, Object v ->
            String key = k.toString()
            boolean placeholder = key ==~ /^<key#\d+>$/
            boolean allowed = sanitiser.schemaKeys.contains(key) || sanitiser.opaqueKeys.contains(key)
            if (opaque ? !placeholder : !(placeholder || allowed)) keyProblems << "${at}.${key}"
            walkKeys(v, opaque || (!opaque && sanitiser.opaqueKeys.contains(key)), "${at}.${key}")
        }
    } else if (node instanceof List) {
        (node as List).eachWithIndex { Object el, int i -> walkKeys(el, opaque, "${at}[${i}]") }
    }
}
walkKeys(out, false, '$')
check(keyProblems.isEmpty(), "every key is reviewed or a placeholder, and every key inside an opaque field is a placeholder ${keyProblems.take(3)}")
check(((out.s as List)[0] as Map).data == ['<key#2>': ['<key#3>': '<string#6>', '<key#4>': 3, '<key#5>': '<string#7>']] ||
      (((out.s as List)[0] as Map).data as Map).values().every { Object inner -> (inner as Map).keySet().every { it ==~ /^<key#\d+>$/ } },
    'an opaque map keeps only its container shape with placeholder keys and values')

Map firstIf = (out.s as List)[0] as Map
Map firstLeaf = (firstIf.c as List)[0] as Map
Map secondLeaf = (firstIf.c as List)[1] as Map
check((firstLeaf.ro as Map).c == (firstLeaf.ro2 as Map).c && (firstLeaf.ro as Map).c != (secondLeaf.ro2 as Map).c,
    'equal literals share a placeholder and different literals do not')
check(firstIf.t == 'if' && firstLeaf.t == 'condition' && (firstLeaf.lo as Map).t == 'p' && ((secondLeaf.lo as Map).v == 'orientation') &&
      (((out.s as List)[1] as Map).lo as Map).vt == 'd' && firstIf.'$' == 1 &&
      ((((secondLeaf.ro as Map).exp as Map).i as List)[1] as Map).n == realFunction.toUpperCase(),
    'vocabulary spellings, a legacy virtual-device name, a case-insensitive function name and node numbers are kept')

def census = { Map doc, Map withShapes ->
    Map r = support.collectWebcoreDecodeCoverage(doc, registry, null, withShapes) as Map
    [r.status, r.accounting, r.constructCounts, r.constructOccurrences, r.unrecognised, r.unrecognisedOverflow,
     r.structureFindings, r.structureFindingsOverflow, r.levelCounts]
}
Map rawDocument = (support.decodeWebcorePistonDocument(raw) as Map).document as Map
check(census(rawDocument, null) == census(out, null) && census(rawDocument, shapes) == census(out, shapes),
    'the walker gives the sanitised document exactly the structural result of the raw document, with and without shapes')
check(sanitiser.render(load(sanitiserSource).sanitiseCapture(raw) as Map) == json && sanitiser.render(sanitiser.sanitiseCapture(raw) as Map) == json,
    'sanitising is deterministic across instances and repeated runs')
check(sanitiser.provenance(raw) == [pistonBuild: 3, activeAtCapture: false], 'provenance reads only the build and active flags')

// Mutations: each privacy rule removed from a copy of the source lets a canary through.
def mutant = { String from, String to ->
    assert sanitiserSource.contains(from): "mutation anchor not found: ${from}"
    load(sanitiserSource.replace(from, to))
}
def keepKeys = mutant('boolean keep = !opaque && (schemaKeys.contains(key) || opaqueKeys.contains(key))', 'boolean keep = true')
check(keepKeys.render(keepKeys.sanitiseCapture(raw) as Map).contains('CANARYkeyTop'), 'mutation: keeping every key lets a canary key through')
def keepOpaqueWords = mutant('boolean keep = !opaque && (schemaKeys.contains(key) || opaqueKeys.contains(key))',
                             'boolean keep = (schemaKeys.contains(key) || opaqueKeys.contains(key))')
keyProblems.clear()
walkKeys(keepOpaqueWords.sanitiseCapture(raw), false, '$')
check(!keyProblems.isEmpty(), 'mutation: keeping reviewed key names inside an opaque field exposes them')
def keepValues = mutant('if (s.isEmpty()) return s', 'if (true) return s')
check(keepValues.render(keepValues.sanitiseCapture(raw) as Map).contains('CANARY literal'), 'mutation: keeping every string lets a canary value through')
def plainIds = mutant("case 'id': placeholders[k] = ':' + String.format('%032x', n) + ':'; break", "case 'id': placeholders[k] = '<id#' + n + '>'; break")
check(census(rawDocument, shapes) != census(plainIds.sanitiseCapture(raw) as Map, shapes),
    'mutation: an identifier placeholder outside identifier form changes the structural result')

// The command-line entry point writes what the library produces.
File rawFile = File.createTempFile('l3-sanitiser-raw', '.json')
File outFile = File.createTempFile('l3-sanitiser-out', '.json')
rawFile.setText(JsonOutput.toJson(raw), 'UTF-8')
int rc = -1
boolean ran = false
for (String launcher in ['groovy.bat', 'groovy']) {
    try {
        List<String> env = System.getenv().collect { k, v -> "${k}=${v}" as String }.findAll { !it.startsWith('JAVA_HOME=') }
        env << ("JAVA_HOME=" + new File(System.getProperty('java.home')).canonicalPath)
        def proc = [launcher, 'tools/webcore-investigation/L3CaptureSanitiser.groovy', rawFile.path, outFile.path].execute(env as String[], repoRoot)
        proc.consumeProcessOutput(new StringWriter(), new StringWriter())
        proc.waitFor()
        rc = proc.exitValue()
        ran = true
        break
    } catch (IOException ignored) { }
}
if (!ran) {
    println 'SKIP  command-line comparison - no runnable groovy launcher found'
} else {
    check(rc == 0 && outFile.getText('UTF-8') == json, 'the command-line sanitiser writes exactly the library result')
}
rawFile.delete()
outFile.delete()

int bad = results.count { !it }
println "${results.size() - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
