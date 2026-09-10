#!/usr/bin/env groovy
//
// The read-only decode coverage endpoint, executed from the app source with the
// hub surfaces stubbed. Covers the implementation map's T6: authorization,
// failure behaviour, the response allowlist and the fingerprint.
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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class CoverageUnderTest {
    static final String LOOPBACK_BASE = 'http://127.0.0.1:8080'
    Map state = [:]
    Map params = [:]
    boolean stubScanActive = false
    Map stubFetch = [ok: false, data: null, error: 'not configured']
    List<String> fetchedUris = []
    int clearAbandonedScanCalls = 0

    void clearAbandonedScan() { clearAbandonedScanCalls++ }
    boolean scanEffectivelyActive() { return stubScanActive }
    Long now() { return System.currentTimeMillis() }
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
app.stubFetch = [ok: false, data: null, error: 'Connection refused to 127.0.0.1:8080 SECRETDETAIL']
Map unreachable = app.webcoreDecodeCoverageResult('77') as Map
assertThat(unreachable.http == 422 && (unreachable.body as Map).error == 'source-unavailable',
    'a loopback failure returns the fixed source-unavailable code')
assertThat(!JsonOutput.toJson(unreachable).contains('SECRETDETAIL'),
    'no loopback error text reaches the response')

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
           (body.meta as Map).fingerprinted == true && (body.meta as Map).cached == false,
    'instrumentation reports timing, size, fingerprint presence and cache state')

// ---- the response allowlist ------------------------------------------------

String serialized = app.webcoreCoverageJson(body) as String
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
String smuggledJson = app.webcoreCoverageJson(smuggled) as String
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

// ---- fingerprint -----------------------------------------------------------

Map twoChunks = [appSettings: [[name: 'chunk:0', value: 'AAAA'], [name: 'chunk:1', value: 'BBBB']]]
Map shifted = [appSettings: [[name: 'chunk:0', value: 'AAAAB'], [name: 'chunk:1', value: 'BBB']]]
Map reordered = [appSettings: [[name: 'chunk:1', value: 'BBBB'], [name: 'chunk:0', value: 'AAAA']]]
Map changed = [appSettings: [[name: 'chunk:0', value: 'AAAA'], [name: 'chunk:1', value: 'BBBC']]]

String fp = app.webcoreChunkFingerprint(twoChunks)
assertThat(fp != null && fp.length() == 64 && fp ==~ /^[0-9a-f]{64}$/,
    'the fingerprint is a full SHA-256 hex digest')
assertThat(fp == app.webcoreChunkFingerprint(twoChunks), 'identical chunks produce the same fingerprint')
assertThat(fp != app.webcoreChunkFingerprint(shifted),
    'a different chunk boundary over the same joined content changes the fingerprint')
assertThat(fp == app.webcoreChunkFingerprint(reordered),
    'chunk order in the payload does not matter, only chunk index')
assertThat(fp != app.webcoreChunkFingerprint(changed), 'a content change changes the fingerprint')
assertThat(app.webcoreChunkFingerprint([appSettings: []]) == null,
    'a piston with no chunks has no fingerprint')

int bad = results.count { !it }
println "${results.size() - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
