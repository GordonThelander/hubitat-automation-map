#!/usr/bin/env groovy
//
// The read-only decode coverage endpoint, executed from the app source with the
// hub surfaces stubbed. Covers the implementation map's T6: authorization,
// failure behaviour, the layered timeout contract, claim ownership and the
// response allowlist at every nesting level.
// Run with: groovy tests/webcore-coverage-endpoint.groovy

import groovy.json.JsonOutput

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('..').canonicalFile

List<Boolean> results = []
def assertThat = { boolean cond, String label ->
    println "${cond ? 'PASS' : 'FAIL'}  ${label}"
    results << cond
}

String source = new File(repoRoot, 'apps/automation_map.groovy').getText('UTF-8')

String between(String text, String from, String to) {
    int a = text.indexOf(from)
    int b = text.indexOf(to, a)
    assert a >= 0 && b > a: "could not locate ${from}"
    return text.substring(a + from.length(), b)
}

// The decoder and its helpers, the walker, the shipped registry projection and
// the endpoint, all taken from the app so nothing here can pass against a copy
// that has drifted.
String decoder = 'Map decodeWebcorePistonDocument(Map data) {' +
    between(source, 'Map decodeWebcorePistonDocument(Map data) {',
                    '// Pure processing, split out of fetchAppRelationships')
String walker = between(source, '// --- webcore census walker: begin ---',
                                '// --- webcore census walker: end ---')
String projection = between(source, '// --- webcore runtime registry: begin ---',
                                    '// --- webcore runtime registry: end ---')
String endpoint = between(source, '// --- webcore coverage endpoint: begin ---',
                                  '// --- webcore coverage endpoint: end ---')

// @Field is a script-scoping directive and is not valid on a class member, so it
// is dropped when the block is wrapped. Nothing about the behaviour under test
// depends on it.
String harness = """
import groovy.json.JsonOutput
import java.util.concurrent.ConcurrentHashMap

class CoverageUnderTest {
    static final String LOOPBACK_BASE = 'http://127.0.0.1:8080'
    Map state = [:]
    Map params = [:]
    boolean stubScanActive = false
    Map stubFetch = [ok: false, data: null, error: 'not configured', timedOut: false]
    List<String> fetchedUris = []
    int clearAbandonedScanCalls = 0
    // A controllable clock, so the cooperative deadline can be forced instead of
    // raced. A step of zero means the real clock.
    Long nowValue = 0L
    Long nowStep = 0L

    void clearAbandonedScan() { clearAbandonedScanCalls++ }
    boolean scanEffectivelyActive() { return stubScanActive }
    Long now() {
        if (nowStep == 0L) return System.currentTimeMillis()
        nowValue = nowValue + nowStep
        return nowValue
    }
    Map httpFetch(String uri, int timeoutSec, Map extraOpts = [:]) { fetchedUris << uri; return stubFetch }
    Map render(Map args) { return args }

${decoder.replace('@Field ', '')}
${walker.replace('@Field ', '')}
${projection.replace('@Field ', '')}
${endpoint.replace('@Field ', '')}
}
"""

def app = new GroovyClassLoader(this.class.classLoader).parseClass(harness).newInstance()
assertThat(true, 'endpoint, walker, registry and decoder compile together from the app source')

// ---- helpers ---------------------------------------------------------------

Map statusFor(Map document) {
    String encoded = JsonOutput.toJson(document).getBytes('UTF-8').encodeBase64().toString()
    return [appSettings: [[name: 'chunk:0', type: 'text', value: encoded]]]
}

void arm(Object a, Map document, String type = 'webCoRE Piston', String appId = '77') {
    a.state = [appInfo: [(appId): [id: appId, type: type]]]
    a.stubFetch = [ok: true, data: statusFor(document), error: null]
}

Map piston = [
    v: [[n: 'counter', t: 'integer', v: [t: 'c', c: 1]]],
    s: [
        [t: 'action', d: [':0123456789abcdef0123456789abcdef:'],
         c: [[t: 'condition', co: 'is', lo: [t: 'v', v: 'mode']]],
         k: [[c: 'setVariable', p: [[t: 'x', x: 'counter', vt: 'variable']]]]],
        [t: 'if', c: [[t: 'condition', co: 'is', lo: [t: 'no-such-operand-type']]]]
    ]
]

// ---- authorization ---------------------------------------------------------

arm(app, piston)
app.stubScanActive = true
Map busy = app.webcoreDecodeCoverageResult('77') as Map
assertThat(busy.http == 409 && (busy.body as Map).status == 'busy' && (busy.body as Map).error == 'scan-active',
    'an active scan returns busy without reading anything')
assertThat(app.fetchedUris.isEmpty(), 'an active scan performs no loopback request')
app.stubScanActive = false

['', ' ', 'abc', '77x', '-1', '1234567890123', '77; DROP', '../77'].each { String bad ->
    app.fetchedUris = []
    Map out = app.webcoreDecodeCoverageResult(bad) as Map
    assertThat(out.http == 400 && (out.body as Map).error == 'invalid-app-id' && app.fetchedUris.isEmpty(),
        "a malformed id is refused before any loopback request (${bad ? bad : 'empty'})")
}

app.fetchedUris = []
Map unknown = app.webcoreDecodeCoverageResult('99') as Map
assertThat(unknown.http == 400 && (unknown.body as Map).error == 'unknown-app-id' && app.fetchedUris.isEmpty(),
    'an id this app has not scanned is refused before any loopback request')

arm(app, piston, 'Rule Machine')
app.fetchedUris = []
Map notPiston = app.webcoreDecodeCoverageResult('77') as Map
assertThat(notPiston.http == 400 && (notPiston.body as Map).error == 'not-a-piston' && app.fetchedUris.isEmpty(),
    'a non-piston app is refused before any loopback request')

// ---- failure behaviour -----------------------------------------------------

arm(app, piston)
app.stubFetch = [ok: false, data: null, timedOut: false, error: 'Connection refused to 127.0.0.1:8080 SECRETDETAIL']
Map unreachable = app.webcoreDecodeCoverageResult('77') as Map
assertThat(unreachable.http == 422 && (unreachable.body as Map).error == 'source-unavailable',
    'a loopback failure returns the fixed source-unavailable code')
assertThat(!JsonOutput.toJson(unreachable).contains('SECRETDETAIL'),
    'no loopback error text reaches the response')

arm(app, piston)
app.stubFetch = [ok: false, data: null, timedOut: true, error: 'Read timed out SECRETDETAIL']
Map sourceTimeout = app.webcoreDecodeCoverageResult('77') as Map
assertThat(sourceTimeout.http == 422 && (sourceTimeout.body as Map).error == 'source-timeout',
    'a loopback timeout returns the fixed source-timeout code')
assertThat(!JsonOutput.toJson(sourceTimeout).contains('SECRETDETAIL'),
    'no timeout error text reaches the response')

arm(app, piston)
app.stubFetch = [ok: true, data: 'not a map', error: null]
Map malformed = app.webcoreDecodeCoverageResult('77') as Map
assertThat(malformed.http == 422 && (malformed.body as Map).error == 'source-malformed',
    'a malformed status payload returns the fixed source-malformed code')

arm(app, piston)
app.stubFetch = [ok: true, data: [appSettings: [[name: 'chunk:0', type: 'text', value: 'not base64 !!']]], error: null]
Map badDecode = app.webcoreDecodeCoverageResult('77') as Map
assertThat(badDecode.http == 422 && (badDecode.body as Map).error == 'decode-failed',
    'a decode failure returns the fixed decode-failed code')

arm(app, piston)
app.stubFetch = [ok: true, data: [appSettings: []], error: null]
Map absent = app.webcoreDecodeCoverageResult('77') as Map
assertThat(absent.http == 200 && (absent.body as Map).status == 'not-present' && (absent.body as Map).error == null,
    'a piston with no saved configuration is not-present, not an error')

// ---- the success path ------------------------------------------------------

arm(app, piston)
Map ok = app.webcoreDecodeCoverageResult('77') as Map
Map body = ok.body as Map
assertThat(ok.http == 200 && body.status == 'complete', 'a saved piston returns a complete census')
assertThat(app.clearAbandonedScanCalls > 0, 'the abandoned-scan recovery runs on every request')
assertThat((body.constructCounts as Map).containsKey('wc.statement.action') &&
           (body.constructCounts as Map).containsKey('wc.virtual-device.mode') &&
           (body.constructCounts as Map).containsKey('wc.device-selector.direct-identifier'),
    'the census identifies constructs through the shipped registry projection')
assertThat((body.unrecognised as List).any { it.reason == 'unknown-operand-type' },
    'a gap in the piston is reported')
assertThat((body.provenance as Map).referenceSourceCommit == '0a37eee2537accd706aaaeeed5a7b4bb0c82646e',
    'the response carries the pinned reference commit')
assertThat((body.meta as Map).elapsedMs != null && (body.meta as Map).resultBytes > 0 &&
           (body.meta as Map).cached == false,
    'instrumentation reports timing, size and cache state')

// ---- the response allowlist ------------------------------------------------

String serialized = app.webcoreCoverageJson(body, app.webcoreCensusRegistry().constructs as Map) as String
Map reparsed = new groovy.json.JsonSlurper().parseText(serialized) as Map
assertThat(reparsed.keySet() == (['status', 'appId', 'registryVersion', 'provenance', 'accounting',
                                  'constructCounts', 'levelCounts', 'unrecognised',
                                  'unrecognisedOverflow', 'truncation', 'meta'] as Set),
    'the serialized response carries exactly the allowlisted fields')

// A field smuggled onto the body cannot reach the browser, because the response
// is built field by field rather than copied.
Map smuggled = new LinkedHashMap(body)
smuggled.document = piston
smuggled.appSettings = [[name: 'chunk:0', value: 'SECRETCHUNK']]
smuggled.rawError = 'SECRETERROR'
String smuggledJson = app.webcoreCoverageJson(smuggled, app.webcoreCensusRegistry().constructs as Map) as String
assertThat(!smuggledJson.contains('SECRETCHUNK') && !smuggledJson.contains('SECRETERROR') &&
           !smuggledJson.contains('document'),
    'a field added to the body cannot reach the response without being allowlisted')
assertThat(!serialized.contains('counter') && !serialized.contains('no-such-operand-type') &&
           !serialized.contains('0123456789abcdef'),
    'no piston value, variable name or device token reaches the response')

// ---- single flight ---------------------------------------------------------

// A claim left behind by an interrupted request must not lock the piston, and a
// live claim must refuse a second concurrent caller.
app.WEBCORE_COVERAGE_CLAIMS.put('77', app.now())
Map concurrent = app.webcoreDecodeCoverageResult('77') as Map
assertThat(concurrent.http == 409 && (concurrent.body as Map).error == 'coverage-in-flight',
    'a second concurrent request for the same piston is refused')
app.WEBCORE_COVERAGE_CLAIMS.put('77', app.now() - 120000L)
Map stale = app.webcoreDecodeCoverageResult('77') as Map
assertThat(stale.http == 200, 'an expired claim is taken over rather than locking the piston')
assertThat(app.WEBCORE_COVERAGE_CLAIMS.get('77') == null, 'the claim is released when the request ends')

// ---- the cooperative deadline ----------------------------------------------

// Forced through the harness clock rather than raced: every now() call jumps far
// past the budget, so the walker's first deadline check fails deterministically.
// The document is large enough to reach that check at all.
arm(app, [s: (1..1200).collect { [t: 'do'] }])
app.nowValue = 0L
app.nowStep = 100000L
Map analysisTimeout = app.webcoreDecodeCoverageResult('77') as Map
app.nowStep = 0L
assertThat(analysisTimeout.http == 422 && (analysisTimeout.body as Map).status == 'analysis-timeout' &&
           (analysisTimeout.body as Map).error == 'analysis-deadline',
    'an exhausted analysis budget returns the fixed analysis-timeout outcome')
assertThat((analysisTimeout.body as Map).accounting == null &&
           (analysisTimeout.body as Map).constructCounts == [:],
    'an analysis timeout carries no partial counts')

// The same document inside its budget completes, so the test above proves the
// deadline rather than the document size.
arm(app, [s: (1..1200).collect { [t: 'do'] }])
Map withinBudget = app.webcoreDecodeCoverageResult('77') as Map
assertThat((withinBudget.body as Map).status == 'complete',
    'the same document completes when the budget is not exhausted')

// ---- claim ownership -------------------------------------------------------

// The interleaving an unconditional remove gets wrong: A stalls, B takes over
// A's expired claim, and A then reaches its cleanup.
app.WEBCORE_COVERAGE_CLAIMS.clear()
Long stampA = 1000000L
Long claimedA = app.webcoreCoverageClaim('77', stampA, 60000L) as Long
Long stampB = stampA + 120000L
Long claimedB = app.webcoreCoverageClaim('77', stampB, 60000L) as Long
assertThat(claimedA == stampA, 'the first request takes the claim')
assertThat(claimedB == stampB, 'a second request takes over an expired claim')
app.webcoreCoverageRelease('77', stampA)
assertThat(app.WEBCORE_COVERAGE_CLAIMS.get('77') == stampB,
    'a stalled cleanup cannot release the replacement claim')
assertThat(app.webcoreCoverageClaim('77', stampB + 10L, 60000L) == null,
    'a third request is still refused while the replacement holds the claim')
app.webcoreCoverageRelease('77', stampB)
assertThat(app.WEBCORE_COVERAGE_CLAIMS.get('77') == null, 'a request releases its own claim')

// Two callers arriving inside the TTL: exactly one proceeds.
app.WEBCORE_COVERAGE_CLAIMS.clear()
assertThat(app.webcoreCoverageClaim('77', 5000L, 60000L) == 5000L, 'one concurrent caller proceeds')
assertThat(app.webcoreCoverageClaim('77', 5000L, 60000L) == null, 'the other is refused')
app.WEBCORE_COVERAGE_CLAIMS.clear()

// ---- the response allowlist reaches every level ----------------------------

Map constructs = app.webcoreCensusRegistry().constructs as Map
String canary2 = 'CANARYd41d8c'
Map smuggledDeep = [
    status: 'complete', appId: '77', registryVersion: '1',
    document: [secret: canary2],
    provenance: [observedWebcoreVersion: null, referenceSourceCommit: 'abc',
                 compatibilityStatus: 'unknown', extra: canary2],
    accounting: [objectsVisited: 1, arraysVisited: 1, fieldsVisited: 1, arrayElementsVisited: 1,
                 scalarsVisited: 1, constructCandidates: 1, constructsIdentified: 1,
                 defaultBranchOccurrences: 0, rawDocument: canary2],
    constructCounts: ['wc.statement.if': 2, ('wc.not.registered.' + canary2): 7],
    levelCounts: [L0: 0, L1: 0, L2: 1, L3: 0, L4: 0, L5: 0, (canary2): 9],
    unrecognised: [[path: '$.s[0].t', reason: 'unknown-key', nodeKind: 'scalar',
                    rawKey: canary2, value: canary2]],
    unrecognisedOverflow: 0,
    truncation: [reason: 'depth-limit', detail: canary2],
    meta: [elapsedMs: 5, resultBytes: 9, decoderSchema: '1', cached: false, token: canary2]
]
String deepJson = app.webcoreCoverageJson(smuggledDeep, constructs) as String
assertThat(!deepJson.contains(canary2), 'no canary survives at any nesting level')
Map deepParsed = new groovy.json.JsonSlurper().parseText(deepJson) as Map
assertThat(!deepParsed.containsKey('document'), 'a smuggled top-level field is dropped')
assertThat((deepParsed.provenance as Map).keySet() ==
           (['observedWebcoreVersion', 'referenceSourceCommit', 'compatibilityStatus'] as Set),
    'provenance carries exactly its three fields')
assertThat((deepParsed.accounting as Map).keySet() ==
           (['objectsVisited', 'arraysVisited', 'fieldsVisited', 'arrayElementsVisited',
             'scalarsVisited', 'constructCandidates', 'constructsIdentified',
             'defaultBranchOccurrences'] as Set),
    'accounting carries exactly the normative keys')
assertThat((deepParsed.levelCounts as Map).keySet() == (['L0', 'L1', 'L2', 'L3', 'L4', 'L5'] as Set),
    'levelCounts carries exactly the closed level keys')
assertThat((deepParsed.unrecognised as List).every {
        (it as Map).keySet() == (['path', 'reason', 'nodeKind'] as Set) },
    'each unrecognised record carries exactly path, reason and nodeKind')
assertThat((deepParsed.truncation as Map).keySet() == (['reason'] as Set),
    'truncation carries exactly its reason')
assertThat((deepParsed.meta as Map).keySet() ==
           (['elapsedMs', 'resultBytes', 'decoderSchema', 'cached'] as Set),
    'meta carries exactly the endpoint metadata fields')
assertThat((deepParsed.constructCounts as Map).keySet() == (['wc.statement.if'] as Set),
    'constructCounts is bounded by the registry population')

// Fixed order, not insertion order, so two runs of the same census serialize
// identically.
Map unordered = new LinkedHashMap(smuggledDeep)
unordered.constructCounts = ['wc.statement.while': 1, 'wc.statement.if': 2, 'wc.statement.action': 3]
Map ordered = new LinkedHashMap(smuggledDeep)
ordered.constructCounts = ['wc.statement.action': 3, 'wc.statement.if': 2, 'wc.statement.while': 1]
assertThat(app.webcoreCoverageJson(unordered, constructs) == app.webcoreCoverageJson(ordered, constructs),
    'construct counts serialize in a fixed order regardless of insertion order')

int bad = results.count { !it }
println "${results.size() - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
