// The HAI-facing decode contract: two read-only endpoints, a fixed envelope and
// fixed issue codes. Written before the implementation, against the shape HAI
// stated it needs, so the endpoints answer the contract rather than the
// contract describing whatever the endpoints happened to return.

String source = new File('apps/automation_map.groovy').getText('UTF-8')

int start = source.indexOf('// --- HAI decode contract: start ---')
int end = source.indexOf('// --- HAI decode contract: end ---')
assert start >= 0 : 'decode contract block not found'
assert end > start : 'decode contract block not terminated'

String block = source.substring(start, end)

// Stubs for the hub-provided surfaces the block reads. state and app are the
// only two it may touch; anything else is a contract violation this harness
// would not catch, so they are asserted below as well.
def script = new GroovyShell().parse('''
import groovy.transform.Field
@Field static final String APP_NAME = 'Automation Map (Dev)'
@Field static final String APP_VERSION = '2.4.3'
@Field static final String BUILD_CHANNEL = 'dev'
@Field static final String SUPPORTED_RULE_ENGINE = 'Rule-5.1'
@Field static final int HAM_DECODE_CONTRACT_VERSION = 1
@Field static final String HAM_SUMMARY_CONTRACT = 'ham.decode/1'
@Field static final String HAM_DETAIL_CONTRACT = 'ham.decode.detail/1'
@Field Map state = [:]
@Field Map app = [id: 3547]
@Field Map settings = [:]
boolean autoScanEffectivelyEnabled() { settings.autoScanEnabled != false }
boolean diagOn() { false }
String engineOfNode(Map n) { "${n.appType ?: ''}".startsWith('Rule-') ? 'RM' : 'other' }
''' + block + '''
void setState(Map s) { state.clear(); state.putAll(s) }
void setSettings(Map s) { settings.clear(); settings.putAll(s) }
''')

Map graphWith(List nodes, Map flows) {
    return [nodes: nodes.collectEntries { [(it.id): it] }, flows: flows]
}

@groovy.transform.Field int passed = 0
@groovy.transform.Field Closure check = { boolean cond, String what ->
    if (cond) { passed++; println "PASS  ${what}" }
    else { println "FAIL  ${what}"; System.exit(1) }
}

Map ruleNode(String id, String name, Map extra = [:]) {
    return [id: id, name: name, label: name, group: 'app', appType: 'Rule-5.1'] + extra
}

// --- envelope ------------------------------------------------------------

script.setState([
    graph: graphWith([ruleNode('a1', 'Alpha', [rmConstructs: ['action:getDelay', 'trigger:Motion']])],
                     ['a1': [[kind: 'trigger', label: 'Motion'], [kind: 'action', label: 'Delay', ctrl: null]]]),
    scanHeartbeat: 1790000000000L, scanError: null, appsUnreadable: 0, deviceIdsUnreadable: []
])

Map summary = script.hamDecodeSummary()
check(summary.contract == 'ham.decode/1', 'envelope names the contract')
check(summary.contractSchemaVersion == 1, 'contract schema version is published')
check(summary.rmConstructVocabularyVersion == 1, 'construct vocabulary version is published separately')
check(summary.supportedEngine == 'Rule-5.1', 'supported engine is published separately')
check(summary.ok == true, 'a served response is ok')
check(summary.issue == null, 'a served response carries no issue code')

Map inst = summary.instance as Map
check(inst.appId == 3547, 'instance names the installed app id')
check("${inst.appName}" == 'Automation Map (Dev)', 'instance names the app')
check("${inst.buildChannel}" == 'dev', 'instance names the build channel so Dev cannot answer production')

Map scan = summary.scan as Map
check(scan.status == 'complete', 'scan status is stated')
check("${scan.lastScanCompletedAt}".startsWith('20'), 'scan completion is an ISO timestamp')

// --- summary payload -----------------------------------------------------

List rules = summary.rules as List
check(rules.size() == 1, 'one rule in, one rule out')
Map r = rules[0] as Map
check(r.id == 'a1' && r.name == 'Alpha', 'id and name are carried')
check(r.engine == 'Rule-5.1', 'engine is the string, not a supported flag')
check(r.status == 'active', 'status defaults to active')
check(r.hasDecodedFlow == true, 'hasDecodedFlow is stated')
check((r.constructs as List) == ['action:getDelay', 'trigger:Motion'], 'construct tokens are carried')
check(r.stepCount == 2, 'step count counts both non-marker steps')
check(!r.containsKey('steps'), 'the summary never carries full steps')

// --- step count excludes control-flow markers ----------------------------

script.setState([
    graph: graphWith([ruleNode('a2', 'Branchy')],
                     ['a2': [[kind: 'trigger', label: 'T', ctrl: null],
                             [kind: 'action', label: 'IF', ctrl: 'if'],
                             [kind: 'action', label: 'On', ctrl: null],
                             [kind: 'action', label: 'ELSE', ctrl: 'else'],
                             [kind: 'action', label: 'Off', ctrl: null],
                             [kind: 'action', label: 'END IF', ctrl: 'endif']]]),
    scanHeartbeat: 1790000000000L, scanError: null
])
Map branchy = (script.hamDecodeSummary().rules as List)[0] as Map
check(branchy.stepCount == 3, 'step count excludes if/else/endif markers')

// --- status precedence ---------------------------------------------------

Map statuses = [disabled: 'disabled', paused: 'paused', unreadable: 'unreadable',
                unscanned: 'unscanned', inert: 'inert', missing: 'deleted-but-referenced']
statuses.each { String flag, String expected ->
    script.setState([graph: graphWith([ruleNode('a9', 'S', [(flag): true])], [:]),
                     scanHeartbeat: 1790000000000L, scanError: null])
    Map got = (script.hamDecodeSummary().rules as List)[0] as Map
    check(got.status == expected, "a ${flag} rule reports status ${expected}")
}

script.setState([graph: graphWith([ruleNode('a9', 'S', [disabled: true, paused: true])], [:]),
                 scanHeartbeat: 1790000000000L, scanError: null])
check(((script.hamDecodeSummary().rules as List)[0] as Map).status == 'disabled',
      'disabled wins over paused, matching the map label precedence')

// --- engine filtering ----------------------------------------------------

script.setState([
    graph: graphWith([ruleNode('a1', 'Supported'),
                      ruleNode('a2', 'Older', [appType: 'Rule-4.1']),
                      [id: 'a3', name: 'Piston', group: 'app', appType: 'webCoRE Piston'],
                      [id: 'd1', name: 'Lamp', group: 'device']], [:]),
    scanHeartbeat: 1790000000000L, scanError: null
])
List mixed = script.hamDecodeSummary().rules as List
check(mixed.size() == 1 && mixed[0].id == 'a1',
      'only the supported engine is listed; other engines and devices are not')

// --- scan status ---------------------------------------------------------

script.setState([graph: graphWith([], [:]), scanHeartbeat: 1790000000000L,
                 scanError: null, appsUnreadable: 2, deviceIdsUnreadable: []])
check((script.hamDecodeSummary().scan as Map).status == 'complete-with-gaps',
      'an unreadable app makes the scan complete-with-gaps')

script.setState([graph: graphWith([], [:]), scanHeartbeat: 1790000000000L, scanError: 'boom'])
check((script.hamDecodeSummary().scan as Map).status == 'failed', 'a scan error reports failed')

// --- freshness policy ------------------------------------------------------

script.setSettings([:])
script.setState([graph: graphWith([], [:]), scanHeartbeat: 1790000000000L, scanError: null])
Map freshOn = script.hamDecodeSummary().scan as Map
check(freshOn.autoScanEnabled == true, 'the scan block states whether automatic scanning is on')
check(freshOn.staleAfterSeconds == 90000, 'with a daily scan the policy is stated as 25 hours')

script.setSettings([autoScanEnabled: false])
Map freshOff = script.hamDecodeSummary().scan as Map
check(freshOff.autoScanEnabled == false, 'automatic scanning off is stated')
check(freshOff.staleAfterSeconds == null,
      'with no schedule there is no policy, so none is invented')
script.setSettings([:])

// --- fail closed: no scan ------------------------------------------------

script.setState([:])
Map noScan = script.hamDecodeSummary()
check(noScan.ok == false, 'with no scan the response is not ok')
check(noScan.issue == 'no-scan', 'with no scan the issue code is no-scan')
check(noScan.message != null && "${noScan.message}".length() > 10, 'a failure carries human text too')
check(noScan.rules == null, 'a failed response carries no payload')
check(noScan.contract == 'ham.decode/1', 'a failure still carries the envelope')
check((noScan.instance as Map).appId == 3547, 'a failure still names the instance')

// --- the contract never writes -------------------------------------------

check(!block.contains('state.') || !block.find(/state\.[a-zA-Z]+\s*=[^=]/),
      'the contract block assigns nothing into state')
check(!block.contains('httpPost') && !block.contains('httpGet'),
      'the contract block performs no hub HTTP of its own')
check(block.contains('uploadHubFile('), 'the summary is published as a hub file')
check(block.contains("'ham-decode-dev.json'") && block.contains("'ham-decode.json'"),
      'the file name carries the build channel so Dev and production cannot collide')
check(block.contains('catch (Exception'), 'a file-write failure never fails the scan')

int publishCalls = source.count('hamDecodeWriteFile()')
check(publishCalls >= 2, 'the writer is both defined and invoked')
check(!source.contains("path('/decode/"), 'no OAuth decode endpoint is exposed')

// The two files must not be mistakable for each other. A detail file read as
// a summary would present rules with no constructs and every verdict would
// come back wrong rather than refused, which is the whole point of naming the
// contract.
check(source.contains("'ham.decode/1'") && source.contains("'ham.decode.detail/1'"),
      'the summary and the detail declare different contract names')
int summaryName = source.count("'ham.decode/1'")
int detailName = source.count("'ham.decode.detail/1'")
check(summaryName >= 1 && detailName >= 1, 'both contract names are present')
check(source.contains('state.graph = graph') && source.indexOf('hamDecodeWriteFile()', source.indexOf('state.graph = graph')) - source.indexOf('state.graph = graph') < 400,
      'the writer runs on scan completion, beside the graph commit')

println "${passed} HAM decode contract assertions passed"
