// Regression suite for v2.2.6 webCoRE saved Hub Variable direction discovery.
// It executes the decoder methods extracted directly from the app source, so
// the tests cannot silently pass against a separate helper that has drifted.
// Run with: groovy tests/webcore-variable-integration.groovy
import groovy.json.JsonOutput

File repoRoot = new File(getClass().protectionDomain.codeSource.location.path).parentFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('.').canonicalFile
File appFile = new File(repoRoot, 'apps/automation_map.groovy')
String source = appFile.getText('UTF-8')

String decoderStart = 'Map decodeWebcoreHubVariableUses(Map data) {'
String decoderEnd = '// Pure processing, split out of fetchAppRelationships'
int start = source.indexOf(decoderStart)
int end = source.indexOf(decoderEnd, start)
assert start >= 0 && end > start: 'Could not locate decoder methods in app source'

String decoderSource = '''
import groovy.json.JsonSlurper
class WebcoreDecoderUnderTest {
''' + source.substring(start, end) + '''
}
'''
Class decoderClass = new GroovyClassLoader(this.class.classLoader).parseClass(decoderSource)
def decoder = decoderClass.newInstance()

int pass = 0
int fail = 0
def check = { String name, Closure body ->
    try {
        body()
        println "PASS  ${name}"
        pass++
    } catch (Throwable t) {
        println "FAIL  ${name} - ${t.class.simpleName}: ${t.message}"
        fail++
    }
}

Map fixtureStatus(Map document, List<Integer> cutPoints = []) {
    String encoded = JsonOutput.toJson(document).getBytes('UTF-8').encodeBase64().toString()
    List<Integer> cuts = ([0] + cutPoints.findAll { it > 0 && it < encoded.length() }.sort().unique() + [encoded.length()])
    List settings = []
    for (int i = 0; i < cuts.size() - 1; i++) {
        settings << [name: "chunk:${i}", type: 'text', value: encoded.substring(cuts[i], cuts[i + 1])]
    }
    return [appSettings: settings]
}

Map fixture = [
    n: 'Fixture :%F0%9F%98%80:',
    s: [[
        c: [
            [t: 'x', x: '@@HubShared', f: 'l'],
            [t: 'x', x: '@@HubList[1]', xi: [t: 'c', c: 1]],
            [t: 'x', x: '@WebcoreGlobal', f: 'l'],
            [t: 'x', x: 'localCounter', f: 'l'],
            [t: 'c', c: 'ordinary text containing @@NotAReference'],
            [t: 'x', x: '$time', f: 'l'],
            [t: 'x', x: '@@HubShared', f: 'l']
        ]
    ]]
]

check('single chunk classifies typed Hub Variable operands as reads and deduplicates') {
    Map result = decoder.decodeWebcoreHubVariableUses(fixtureStatus(fixture)) as Map
    assert result == [status: 'complete', reads: ['HubList', 'HubShared'], writes: [], hubVariables: []]
}
check('arbitrary multi-chunk boundaries produce the same result') {
    Map result = decoder.decodeWebcoreHubVariableUses(fixtureStatus(fixture, [1, 7, 43, 101])) as Map
    assert result == [status: 'complete', reads: ['HubList', 'HubShared'], writes: [], hubVariables: []]
}
check('setVariable task target is a write while its value operand is a read') {
    Map document = [s: [[t: 'action', k: [[c: 'setVariable', p: [
        [t: 'x', x: '@@WriteTarget', vt: 'variable'],
        [t: 'x', x: '@@ReadSource', vt: 'dynamic']
    ]]]]]]
    assert decoder.decodeWebcoreHubVariableUses(fixtureStatus(document)) ==
        [status: 'complete', reads: ['ReadSource'], writes: ['WriteTarget'], hubVariables: []]
}
check('the same Hub Variable can carry both read and write roles') {
    Map document = [s: [[t: 'action', k: [[c: 'setVariable', p: [
        [t: 'x', x: '@@Both'], [t: 'x', x: '@@Both']
    ]]]]]]
    assert decoder.decodeWebcoreHubVariableUses(fixtureStatus(document)) ==
        [status: 'complete', reads: ['Both'], writes: ['Both'], hubVariables: []]
}
check('expression variables and variable-backed device operands are reads') {
    Map document = [s: [[
        [t: 'expression', i: [[t: 'variable', x: '@@ExpressionRead']]],
        [t: 'device', x: '@@DeviceRead'],
        [t: 'action', d: ['@@DeviceListRead']]
    ]]]
    assert decoder.decodeWebcoreHubVariableUses(fixtureStatus(document)) ==
        [status: 'complete', reads: ['DeviceListRead', 'DeviceRead', 'ExpressionRead'], writes: [], hubVariables: []]
}
check('loop counters and matching-device captures are writes') {
    Map document = [s: [
        [t: 'for', x: '@@LoopCounter'],
        [t: 'each', x: '@@EachCounter'],
        [t: 'condition', lo: [t: 'p', d: ['12'], dm: '@@Matched', dn: '@@Unmatched']]
    ]]
    assert decoder.decodeWebcoreHubVariableUses(fixtureStatus(document)) ==
        [status: 'complete', reads: [], writes: ['EachCounter', 'LoopCounter', 'Matched', 'Unmatched'], hubVariables: []]
}
check('setVariable expression function accepts only a static literal target') {
    Map staticCall = [t: 'function', n: 'setvariable', i: [
        [t: 'expression', i: [[t: 'string', v: '@@LiteralTarget']]],
        [t: 'expression', i: [[t: 'variable', x: '@@ReadValue']]]
    ]]
    Map dynamicCall = [t: 'function', n: 'setvariable', i: [
        [t: 'expression', i: [[t: 'variable', x: '@@DynamicNameSource']]],
        [t: 'expression', i: [[t: 'integer', v: 1]]]
    ]]
    Map result = decoder.decodeWebcoreHubVariableUses(fixtureStatus([s: [staticCall, dynamicCall]])) as Map
    assert result == [status: 'complete', reads: ['DynamicNameSource', 'ReadValue'], writes: ['LiteralTarget'], hubVariables: []]
}
check('no chunks is a non-error absence of saved configuration') {
    assert decoder.decodeWebcoreHubVariableUses([appSettings: []]) == [status: 'not-present', hubVariables: []]
}
check('unexpected settings shape fails closed') {
    assert decoder.decodeWebcoreHubVariableUses([appSettings: [bad: true]]).error == 'unexpected-settings'
}
check('missing chunk zero fails closed') {
    assert decoder.decodeWebcoreHubVariableUses([appSettings: [[name: 'chunk:1', value: 'e30=']]]).error == 'missing-chunk-zero'
}
check('missing middle chunk fails closed') {
    Map data = fixtureStatus(fixture, [7, 43])
    data.appSettings.remove(1)
    assert decoder.decodeWebcoreHubVariableUses(data).error == 'missing-chunk'
}
check('an extreme chunk index is rejected before a large range is constructed') {
    assert decoder.decodeWebcoreHubVariableUses([appSettings: [[name: 'chunk:999999999', value: 'e30='], [name: 'chunk:0', value: 'e30=']]]).error == 'chunk-index-out-of-range'
}
check('duplicate chunk fails closed') {
    Map data = fixtureStatus(fixture)
    data.appSettings << new LinkedHashMap(data.appSettings[0])
    assert decoder.decodeWebcoreHubVariableUses(data).error == 'duplicate-chunk'
}
check('empty chunk fails closed') {
    assert decoder.decodeWebcoreHubVariableUses([appSettings: [[name: 'chunk:0', value: '']]]).error == 'empty-chunk'
}
check('invalid Base64 fails with a fixed code') {
    assert decoder.decodeWebcoreHubVariableUses([appSettings: [[name: 'chunk:0', value: '***']]]).error == 'invalid-base64'
}
check('invalid JSON and non-object roots fail with fixed codes') {
    String invalidJson = 'not-json'.getBytes('UTF-8').encodeBase64().toString()
    String listRoot = '[]'.getBytes('UTF-8').encodeBase64().toString()
    assert decoder.decodeWebcoreHubVariableUses([appSettings: [[name: 'chunk:0', value: invalidJson]]]).error == 'invalid-json'
    assert decoder.decodeWebcoreHubVariableUses([appSettings: [[name: 'chunk:0', value: listRoot]]]).error == 'unexpected-root'
}
check('error results expose no decoded configuration, values or exception text') {
    Map result = decoder.decodeWebcoreHubVariableUses([appSettings: [[name: 'chunk:1', value: 'secret-value']]]) as Map
    assert result.keySet() == ['status', 'error', 'hubVariables'] as Set
    assert !result.toString().contains('secret-value')
}

println '--- Source integration contract ---'
check('Hubitat source uses allowed UTF-8 string APIs') {
    String decoderBlock = source.substring(start, end)
    assert decoderBlock.contains("new String(decoded, 'UTF-8')")
    assert decoderBlock.contains("URLDecoder.decode(token.substring(1, 13), 'UTF-8')")
    assert !decoderBlock.contains('StandardCharsets')
}
check('webCoRE is detected by installed app name and reconciled before an edge is made') {
    assert source.contains('"${out.type}" == \'webCoRE Piston\'')
    assert source.contains('canonicalHubVariableName(originalName, hubVarInventoryVars)')
    assert source.contains("edges << [from: appNodeId, to: varNodeId, kind: 'read', usageRole: 'unknown-read']")
    assert source.contains("edges << [from: appNodeId, to: varNodeId, kind: 'write']")
}
check('usesVar remains a distinct fail-safe visual and pivot relationship') {
    assert source.contains("usesVar: 'Uses (direction unknown)'")
    assert source.contains("arrows: directionUnknown ? ''")
    assert source.contains("kinds: ['write', 'read', 'usesVar']")
    assert source.contains("n.appType === 'webCoRE Piston'")
}
check('schema, scan gaps and export semantics are explicit') {
    assert source.contains("GRAPH_SCHEMA = '12'")
    assert source.contains('exportSchemaVersion: 10')
    assert source.contains('webcoreVariableDecodeIssues: webcoreVariableDecodeIssues')
    assert source.contains("direction: e.kind === 'usesVar' ? 'unknown' : null")
    assert source.contains("relationships: ['read', 'write', 'usesVar']")
}
check('decoder failures do not mislabel a piston as inert') {
    assert source.contains('!webcoreVariableDecodeFailed && !roles')
    assert source.contains("webcoreVariableDecodeStatus == 'error'")
}

println ''
println "${pass} passed, ${fail} failed"
System.exit(fail == 0 ? 0 : 1)
