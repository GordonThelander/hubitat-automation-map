// Regression suite for v2.2.7 webCoRE variable discovery and device containment.
// It executes the decoder methods extracted directly from the app source, so
// the tests cannot silently pass against a separate helper that has drifted.
// Run with: groovy tests/webcore-variable-integration.groovy
import groovy.json.JsonOutput

File repoRoot = new File(getClass().protectionDomain.codeSource.location.path).parentFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('.').canonicalFile
File appFile = new File(repoRoot, 'apps/automation_map.groovy')
String source = appFile.getText('UTF-8')

String decoderStart = 'Map decodeWebcorePistonDocument(Map data) {'
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

println '--- v2.2.8: piston-local variables ---'
check('no declarations yields empty definitions, reads and writes') {
    Map result = decoder.collectWebcorePistonLocalVariables([v: [], s: []]) as Map
    assert result == [definitions: [], reads: [], writes: []]
}
check('one unused dynamic declaration appears with no read or write') {
    Map doc = [v: [[n: 'Counter', t: 'dynamic']], s: []]
    Map result = decoder.collectWebcorePistonLocalVariables(doc) as Map
    assert result == [definitions: [[name: 'Counter', engineVariableType: 'dynamic']], reads: [], writes: []]
}
check('a declared local evaluated as an operand is a read') {
    Map doc = [v: [[n: 'Counter', t: 'dynamic']], s: [[t: 'x', x: 'Counter']]]
    Map result = decoder.collectWebcorePistonLocalVariables(doc) as Map
    assert result.reads == ['Counter']
    assert result.writes == []
}
check('the first setVariable operand is a write target, not a read') {
    Map doc = [v: [[n: 'Counter', t: 'dynamic']], s: [[t: 'action', k: [[c: 'setVariable', p: [
        [t: 'x', x: 'Counter']
    ]]]]]]
    Map result = decoder.collectWebcorePistonLocalVariables(doc) as Map
    assert result.reads == []
    assert result.writes == ['Counter']
}
check('reading one local while writing another produces both edges only on their own names') {
    Map doc = [v: [[n: 'A', t: 'dynamic'], [n: 'B', t: 'dynamic']], s: [
        [t: 'action', k: [[c: 'setVariable', p: [[t: 'x', x: 'A']]]]],
        [t: 'x', x: 'B']
    ]]
    Map result = decoder.collectWebcorePistonLocalVariables(doc) as Map
    assert result.reads == ['B']
    assert result.writes == ['A']
}
check('an indexed reference resolves to its declared base name') {
    Map doc = [v: [[n: 'local List', t: 'dynamic']], s: [[t: 'x', x: 'local List[2]']]]
    Map result = decoder.collectWebcorePistonLocalVariables(doc) as Map
    assert result.definitions == [[name: 'local_List', engineVariableType: 'dynamic']]
    assert result.reads == ['local_List']
}
check('@ webCoRE globals and @@ Hub Variables never become local, even with a colliding declared name') {
    Map doc = [v: [[n: 'Shared', t: 'dynamic']], s: [
        [t: 'x', x: '@Shared'],
        [t: 'x', x: '@@Shared']
    ]]
    Map result = decoder.collectWebcorePistonLocalVariables(doc) as Map
    assert result.reads == []
    assert result.writes == []
}
check('an unprefixed name with no matching declaration creates no local variable entry') {
    Map doc = [v: [], s: [[t: 'x', x: 'Undeclared']]]
    Map result = decoder.collectWebcorePistonLocalVariables(doc) as Map
    assert result == [definitions: [], reads: [], writes: []]
}

println '--- v2.2.8: piston-to-device references ---'
check('a direct physical-device read resolves with its attribute') {
    Map doc = [s: [[t: 'p', d: [':f142209a9087c18092a59ef88e2b5b6a:'], a: 'switch']]]
    Map result = decoder.collectWebcorePistonDeviceReferences(doc) as Map
    assert result.reads == [[token: ':f142209a9087c18092a59ef88e2b5b6a:', attribute: 'switch']]
    assert result.actions == []
    assert result.unsupported == [:]
}
check('a direct device action resolves with its command list') {
    Map doc = [s: [[t: 'action', d: [':252e3db9c501eef295bae52a99e633cc:'], k: [[c: 'setColor']]]]]
    Map result = decoder.collectWebcorePistonDeviceReferences(doc) as Map
    assert result.actions == [[token: ':252e3db9c501eef295bae52a99e633cc:', commands: ['setColor']]]
    assert result.reads == []
}
check('the same device can be both read and acted on') {
    String token = ':aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:'
    Map doc = [s: [
        [t: 'p', d: [token], a: 'switch'],
        [t: 'action', d: [token], k: [[c: 'on']]]
    ]]
    Map result = decoder.collectWebcorePistonDeviceReferences(doc) as Map
    assert result.reads.size() == 1 && result.reads[0].token == token
    assert result.actions.size() == 1 && result.actions[0].token == token
}
check('two different devices from one piston are both captured') {
    Map doc = [s: [
        [t: 'p', d: [':11111111111111111111111111111111:'], a: 'switch'],
        [t: 'p', d: [':22222222222222222222222222222222:'], a: 'motion']
    ]]
    Map result = decoder.collectWebcorePistonDeviceReferences(doc) as Map
    assert result.reads*.token as Set == [':11111111111111111111111111111111:', ':22222222222222222222222222222222:'] as Set
}
check('a malformed d entry is counted, not resolved as a token') {
    Map doc = [s: [[t: 'p', d: [123], a: 'switch']]]
    Map result = decoder.collectWebcorePistonDeviceReferences(doc) as Map
    assert result.reads == []
    assert result.unsupported == ['malformed-device-node': 1]
}
check('a variable-backed device list is a fixed unsupported category, never a token') {
    Map doc = [s: [[t: 'action', d: ['@@DeviceListVar'], k: [[c: 'on']]]]]
    Map result = decoder.collectWebcorePistonDeviceReferences(doc) as Map
    assert result.actions == []
    assert result.unsupported == ['variable-backed-device-list': 1]
}
check('the current-triggering-device placeholder is a fixed unsupported category') {
    Map doc = [s: [[t: 'action', d: ['$currentEventDevice'], k: [[c: 'on']]]]]
    Map result = decoder.collectWebcorePistonDeviceReferences(doc) as Map
    assert result.unsupported == ['runtime-selected-device': 1]
}
check('a non-hash-shaped string is a fixed non-physical-device category') {
    Map doc = [s: [[t: 'p', d: ['not-a-hash'], a: 'switch']]]
    Map result = decoder.collectWebcorePistonDeviceReferences(doc) as Map
    assert result.unsupported == ['non-physical-device': 1]
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
    assert source.contains("GRAPH_SCHEMA = '14'")
    assert source.contains('exportSchemaVersion: 12')
    assert source.contains('webcoreVariableDecodeIssues: webcoreVariableDecodeIssues')
    assert source.contains("direction: (e.kind === 'usesVar' || e.kind === 'deviceRead') ? 'unknown' : null")
    assert source.contains("relationships: ['read', 'write', 'usesVar']")
}
check('v2.2.8: device edges, coverage and local variables reach the client and export') {
    assert source.contains("kind: 'deviceRead'")
    assert source.contains("edges << [from: appNodeId, to: devNodeId, kind: 'action', stateful: null, commands: ref.commands]")
    assert source.contains('webcoreDeviceRelationshipCoverage[appNodeId] = coverage')
    assert source.contains("deviceRelationshipCoverage: n.appType === 'webCoRE Piston' ? (n.webcoreDeviceRelationshipCoverage || 'none')")
    assert source.contains('localVariables: ALL_NODES.filter(function (n) { return n.group === \'localVariable\'; })')
    assert source.contains("e.stateful === null ? null : !!e.stateful")
}
check('decoder failures do not mislabel a piston as inert') {
    assert source.contains('!webcoreVariableDecodeFailed && !webcorePistonDeviceRelationshipsUndecoded && !roles')
    assert source.contains("webcoreVariableDecodeStatus == 'error'")
}
check('webCoRE device permissions and partial subscriptions are suppressed without removing variables') {
    assert source.contains("if (normalizedAppType == 'webCoRE' || normalizedAppType == 'webCoRE Piston')")
    assert source.contains('roles.clear()')
    assert source.contains('stateful.clear()')
    assert source.contains('out.webcoreDeviceRelationshipsSuppressed = true')
    assert source.contains("out.webcoreHubVarReads = hubRoles.findAll { String n, Set<String> f -> f.contains('read') }.keySet().sort()")
    assert source.contains("out.webcoreHubVarWrites = hubRoles.findAll { String n, Set<String> f -> f.contains('write') }.keySet().sort()")
}
check('v2.2.8: parent permitted-device ids are retained before suppression, local variables and devices are decoded once per piston') {
    assert source.contains('out.webcorePermittedDeviceIds = permitted.toList()')
    assert source.contains('Map decodedPiston = decodeWebcorePistonDocument(data)')
    assert source.contains('Map localVars = collectWebcorePistonLocalVariables(document)')
    assert source.contains('Map deviceRefs = collectWebcorePistonDeviceReferences(document)')
}
check('webCoRE pistons are not called inert and UI and export disclose device coverage') {
    assert source.contains("webcorePistonDeviceRelationshipsUndecoded = normalizedAppType == 'webCoRE Piston'")
    assert source.contains("normalizedAppType == 'webCoRE' || normalizedAppType == 'webCoRE Piston'")
    assert source.contains('Map roles = webcoreDeviceRelationshipsSuppressed ? [:]')
    assert source.contains('webCoRE parent device permissions are not shown because they do not prove which piston reads or controls a device.')
    assert source.contains('function webcorePistonDeviceCoverageMessage(node)')
    assert source.contains('#flowSub.webcoreNotice { color:#ff6b6b; font-weight:700; }')
    assert source.contains("n.appType === 'webCoRE Piston' ? (n.webcoreDeviceRelationshipCoverage || 'none')")
    assert source.contains("n.appType === 'webCoRE' ? 'parent-permissions-omitted'")
    assert source.contains('<b>Your map contains:</b>')
    assert source.contains('including ${inert} freestanding apps')
    assert source.contains('local variables and direct device reads/actions are also decoded from webCoRE pistons; step-by-step webCoRE flow is not.')
}

println ''
println "${pass} passed, ${fail} failed"
System.exit(fail == 0 ? 0 : 1)
