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
String shapesBlock = between(source, '// --- webcore statement shapes: begin ---',
                                     '// --- webcore statement shapes: end ---')
String semanticEvidenceBlock = between(source, '// --- webcore semantic evidence: begin ---',
                                               '// --- webcore semantic evidence: end ---')
String semanticBlock = between(source, '// --- webcore semantic normalizer: begin ---',
                                       '// --- webcore semantic normalizer: end ---')

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
    // raced. A script plays back exact values in order; otherwise a step of zero
    // means the real clock.
    Long nowValue = 0L
    Long nowStep = 0L
    List<Long> nowScript = null
    int nowIndex = 0

    void clearAbandonedScan() { clearAbandonedScanCalls++ }
    boolean scanEffectivelyActive() { return stubScanActive }
    Long now() {
        if (nowScript != null) {
            Long v = nowScript[Math.min(nowIndex, nowScript.size() - 1)]
            nowIndex++
            return v
        }
        if (nowStep == 0L) return System.currentTimeMillis()
        nowValue = nowValue + nowStep
        return nowValue
    }
    Map httpFetch(String uri, int timeoutSec, Map extraOpts = [:]) { fetchedUris << uri; return stubFetch }
    Map render(Map args) { return args }

${decoder.replace('@Field ', '')}
${walker.replace('@Field ', '')}
${projection.replace('@Field ', '')}
${shapesBlock.replace('@Field ', '')}
${semanticEvidenceBlock.replace('@Field ', '')}
${semanticBlock.replace('@Field ', '')}
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
        [t: 'action', d: [':0123456789abcdef0123456789abcdef:'], ok: true,
         k: [[c: 'setVariable', p: [[t: 'x', x: 'counter', vt: 'variable']]]]],
        [t: 'if', c: [[t: 'condition', co: 'is', lo: [t: 'v', v: 'mode']],
                      [t: 'condition', co: 'is', lo: [t: 'no-such-operand-type']]]]
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
                                  'constructCounts', 'constructLevels', 'constructOccurrences', 'structurallyCapped',
                                  'evidenceCapped', 'evidenceGaps', 'statementAssessment', 'nonStatementAssessment', 'semanticAssessment',
                                  'levelCounts', 'unrecognised', 'unrecognisedOverflow', 'structureFindings',
                                  'structureFindingsOverflow', 'truncation', 'meta'] as Set),
    'the serialized response carries exactly the allowlisted fields')

// ---- structural validity ---------------------------------------------------

assertThat(((body.constructOccurrences as Map)['wc.statement.if'] as Map)?.structurallyInvalid == 1 &&
           ((body.constructOccurrences as Map)['wc.statement.action'] as Map)?.structurallyInvalid == 1,
    "the endpoint validates statement occurrences against the shipped shapes (${body.constructOccurrences})")
assertThat((reparsed.structureFindings as List).every { Map f -> f.keySet() == (['path', 'category'] as Set) } &&
           (reparsed.structureFindings as List).any { it.category == 'unexpected-key' && it.path == '$.s[0].ok' },
    'structure findings reach the response as a closed category and a structural path only')
assertThat(reparsed.structurallyCapped == ['wc.statement.action', 'wc.statement.if'] && (reparsed.constructLevels as Map)['wc.statement.if'] == 'L2' &&
           reparsed.evidenceCapped == [],
    "an invalid occurrence lowers the shipped L3 statement ceiling and names the capped id (${reparsed.structurallyCapped})")

Map shippedConstructs = app.webcoreCensusRegistry().constructs as Map
Map allValidBody = new LinkedHashMap(body)
allValidBody.constructOccurrences = ['wc.statement.if': [structurallyValid: 1, structurallyInvalid: 0, evidenceGapped: 0, evidenceGaps: [:]]]
Map allValidOut = new groovy.json.JsonSlurper().parseText(app.webcoreCoverageJson(allValidBody, shippedConstructs) as String) as Map
assertThat((allValidOut.constructLevels as Map)['wc.statement.if'] == 'L3' && allValidOut.structurallyCapped == ['wc.statement.action'],
    'an L3 ceiling holds when every occurrence is valid and gap-free, and an id with no occurrence record is capped')

// ---- evidence gaps and the two assessments -----------------------------------

Map gappedBody = new LinkedHashMap(body)
gappedBody.constructOccurrences = [
    'wc.statement.if': [structurallyValid: 1, structurallyInvalid: 0, evidenceGapped: 1,
                        evidenceGaps: ['statement/sm/present': 1, 'SECRETGAP/x/present': 1, 'task/cm/present': 'SECRETCOUNT']],
    'wc.statement.action': [structurallyValid: 1, structurallyInvalid: 0, evidenceGapped: 0, evidenceGaps: [:]]]
gappedBody.unrecognisedOutsideStatements = 6
String gappedJson = app.webcoreCoverageJson(gappedBody, shippedConstructs) as String
Map gappedOut = new groovy.json.JsonSlurper().parseText(gappedJson) as Map
assertThat((gappedOut.constructLevels as Map)['wc.statement.if'] == 'L2' && (gappedOut.constructLevels as Map)['wc.statement.action'] == 'L3' &&
           gappedOut.evidenceCapped == ['wc.statement.if'] && gappedOut.structurallyCapped == [],
    "a valid occurrence taking an evidence gap holds only its own id, reported as evidence-capped (${gappedOut.evidenceCapped} ${gappedOut.structurallyCapped})")
assertThat(((gappedOut.constructOccurrences as Map)['wc.statement.if'] as Map).evidenceGaps == ['statement/sm/present': 1] &&
           gappedOut.evidenceGaps == [[id: 'statement/sm/present', reason: 'observed-at-capture', occurrences: 1]] && !gappedJson.contains('SECRET'),
    "only closed gap ids with a count reach the response, each with its fixed reason (${gappedOut.evidenceGaps})")
assertThat((gappedOut.statementAssessment as Map)?.level == 'L2' && (gappedOut.statementAssessment as Map)?.evidenceGapped == 1 &&
           (gappedOut.statementAssessment as Map)?.structurallyInvalid == 0 && gappedOut.nonStatementAssessment == [unrecognised: 6],
    "statement confidence is the lowest statement level with its totals, and positions outside statements are counted apart (${gappedOut.statementAssessment})")
Map provenBody = new LinkedHashMap(gappedBody)
provenBody.constructOccurrences = ['wc.statement.if': [structurallyValid: 1, structurallyInvalid: 0, evidenceGapped: 0, evidenceGaps: [:]],
                                   'wc.statement.action': [structurallyValid: 1, structurallyInvalid: 0, evidenceGapped: 0, evidenceGaps: [:]]]
Map provenOut = new groovy.json.JsonSlurper().parseText(app.webcoreCoverageJson(provenBody, shippedConstructs) as String) as Map
assertThat((provenOut.statementAssessment as Map)?.level == 'L3' && provenOut.nonStatementAssessment == [unrecognised: 6] && provenOut.evidenceGaps == [],
    "unrecognised positions outside statements do not lower a proven statement result (${provenOut.statementAssessment} ${body.constructCounts})")
assertThat(body.unrecognisedOutsideStatements instanceof Number && reparsed.statementAssessment instanceof Map,
    'the live endpoint passes the outside-statement count through and reports a statement assessment')

// ---- the semantic (L4) assessment ------------------------------------------------

Map semantic = reparsed.semanticAssessment as Map
assertThat(semantic.keySet() == (['status', 'occurrences', 'explained', 'explainable', 'gaps', 'claims'] as Set) &&
           semantic.status == 'complete' && semantic.occurrences == 2 && semantic.explainable == false &&
           (semantic.gaps as List) && (semantic.gaps as List).every { Map g -> g.keySet() == (['id', 'reason', 'occurrences'] as Set) },
    "the live endpoint reports a separate, counted semantic assessment with closed gaps (${semantic})")
Map smuggledSemantic = new LinkedHashMap(body)
smuggledSemantic.semanticAssessment = [status: 'complete', occurrences: 1, explained: 1, explainable: true,
    gaps: [[id: 'SECRETGAP', occurrences: 1], [id: 'condition.leaf-opaque', occurrences: 'x', reason: 'SECRETREASON']],
    claims: ['SECRETCLAIM', 'statement.if.branch-order.v1']]
String smuggledSemanticJson = app.webcoreCoverageJson(smuggledSemantic, shippedConstructs) as String
Map smuggledSemanticOut = (new groovy.json.JsonSlurper().parseText(smuggledSemanticJson) as Map).semanticAssessment as Map
assertThat(!smuggledSemanticJson.contains('SECRET') && smuggledSemanticOut.gaps == [] && smuggledSemanticOut.claims == ['statement.if.branch-order.v1'],
    'unknown gap and claim ids, bad counts and supplied reasons never reach the semantic assessment')
Map oddStatus = new LinkedHashMap(body)
oddStatus.semanticAssessment = [status: 'partial', occurrences: 5, explained: 5, explainable: true, gaps: [], claims: ['statement.if.branch-order.v1']]
assertThat((new groovy.json.JsonSlurper().parseText(app.webcoreCoverageJson(oddStatus, shippedConstructs) as String) as Map).semanticAssessment ==
           [status: 'not-evaluated', occurrences: 0, explained: 0, explainable: false, gaps: [], claims: []],
    'any status other than complete is reported as not evaluated, with no counts or claims')
Map withoutSemantic = new LinkedHashMap(body)
withoutSemantic.semanticAssessment = null
assertThat((new groovy.json.JsonSlurper().parseText(app.webcoreCoverageJson(withoutSemantic, shippedConstructs) as String) as Map).constructLevels == reparsed.constructLevels,
    'the semantic assessment never changes a structural level')

Map smuggledStructure = new LinkedHashMap(body)
smuggledStructure.constructOccurrences = ['wc.statement.if': [structurallyValid: 1, structurallyInvalid: 0, detail: 'SECRETOCC'],
                                          'wc.statement.do': [structurallyValid: 1, structurallyInvalid: 0]]
smuggledStructure.structureFindings = [[path: '$.s[0].ok', category: 'SECRETCATEGORY'],
                                       [path: '$.s[0].ok', category: 'unexpected-key', value: 'SECRETVALUE']] +
                                      (1..80).collect { [path: "\$.s[${it}].ok".toString(), category: 'unexpected-key'] }
String smuggledStructureJson = app.webcoreCoverageJson(smuggledStructure, app.webcoreCensusRegistry().constructs as Map) as String
Map smuggledStructureOut = new groovy.json.JsonSlurper().parseText(smuggledStructureJson) as Map
assertThat(!smuggledStructureJson.contains('SECRET') && !(smuggledStructureOut.constructOccurrences as Map).containsKey('wc.statement.do') &&
           (smuggledStructureOut.structureFindings as List).size() == 50,
    'unknown categories, extra fields and uncounted ids are dropped, and findings stay within their cap')

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

// ---- the request deadline at both walk boundaries --------------------------

// A scripted clock: each call returns the next value, and the last repeats. The
// endpoint reads the clock for its start, for the analysis deadline, and then
// the walker reads it through the closure on entry and on exit. None of these
// documents is large enough to reach a periodic check, which is the point.
Map shortPiston = [s: [[t: 'action', k: [[c: 'setVariable', p: [[t: 'x', x: 'counter', vt: 'variable']]]]]]]

// Fetch and decode spent the whole 12s request budget before traversal began:
// start 0, deadline min(0 + 12000, 13000 + 5000) = 12000, entry check at 13001.
arm(app, shortPiston)
app.nowScript = [0L, 13000L, 13001L]
app.nowIndex = 0
Map spentBeforeWalk = app.webcoreDecodeCoverageResult('77') as Map
app.nowScript = null
assertThat(spentBeforeWalk.http == 422 &&
           (spentBeforeWalk.body as Map).status == 'analysis-timeout' &&
           (spentBeforeWalk.body as Map).error == 'analysis-deadline',
    'a small document is refused when fetch and decode already exhausted the request budget')
assertThat((spentBeforeWalk.body as Map).accounting == null &&
           (spentBeforeWalk.body as Map).constructCounts == [:] &&
           (spentBeforeWalk.body as Map).unrecognised == [],
    'that refusal exposes no partial counts')

// Inside budget on entry, past it on exit: start 0, deadline 5000, entry 100,
// exit 99999. The walk runs to completion and the exit check refuses it.
arm(app, shortPiston)
app.nowScript = [0L, 0L, 100L, 99999L]
app.nowIndex = 0
Map expiredDuringWalk = app.webcoreDecodeCoverageResult('77') as Map
app.nowScript = null
assertThat(expiredDuringWalk.http == 422 &&
           (expiredDuringWalk.body as Map).status == 'analysis-timeout',
    'a sub-interval document is refused when the deadline passes during its walk')
assertThat((expiredDuringWalk.body as Map).accounting == null &&
           (expiredDuringWalk.body as Map).constructCounts == [:],
    'a deadline passing during the walk exposes no partial counts either')

// The same document, same clock shape, never crossing the deadline.
arm(app, shortPiston)
app.nowScript = [0L, 0L, 100L, 200L, 300L]
app.nowIndex = 0
Map shortInsideBudget = app.webcoreDecodeCoverageResult('77') as Map
app.nowScript = null
assertThat(shortInsideBudget.http == 200 && (shortInsideBudget.body as Map).status == 'complete',
    'the same small document completes while inside budget')
assertThat(((shortInsideBudget.body as Map).constructCounts as Map).containsKey('wc.statement.action'),
    'and its census is real rather than empty')

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

// ---- per-construct evidence levels ------------------------------------------------

arm(app, piston)
Map levelled = app.webcoreDecodeCoverageResult('77') as Map
Map levelledJson = new groovy.json.JsonSlurper().parseText(app.webcoreCoverageJson(levelled.body as Map, constructs) as String) as Map
assertThat(levelledJson.containsKey('constructLevels') && !(levelledJson.constructLevels as Map).isEmpty(),
    'the response carries a level for each construct')
assertThat((levelledJson.constructLevels as Map).keySet() == (levelledJson.constructCounts as Map).keySet(),
    'construct level keys match construct count keys exactly')
assertThat((levelledJson.constructLevels as Map).values().every { it in ['L0', 'L1', 'L2', 'L3', 'L4', 'L5'] },
    'every level is from the closed L0 to L5 set')
assertThat((levelledJson.constructLevels as Map).keySet().toList() == (levelledJson.constructLevels as Map).keySet().toList().sort(),
    'construct levels are emitted in sorted order')

// Levels come from the registry, never from the body, and an id whose registered
// level is outside L0 to L5 is left out of both maps.
Map fakeRegistry = ['wc.statement.if': [level: 'L2'], 'wc.statement.while': [level: 'L9'], 'wc.statement.action': [level: 'L3']]
Map levelBody = [status: 'complete', appId: '77',
                 constructCounts: ['wc.statement.while': 1, 'wc.statement.if': 2, 'wc.statement.action': 3, 'wc.unregistered.x': 4],
                 constructLevels: ['wc.statement.if': 'L5', ('wc.injected.' + canary2): 'L2', 'wc.statement.action': canary2],
                 // An L3 ceiling holds only with every occurrence structurally valid.
                 constructOccurrences: ['wc.statement.action': [structurallyValid: 3, structurallyInvalid: 0, evidenceGapped: 0]]]
String levelJsonText = app.webcoreCoverageJson(levelBody, fakeRegistry) as String
Map levelOut = new groovy.json.JsonSlurper().parseText(levelJsonText) as Map
assertThat((levelOut.constructLevels as Map) == ['wc.statement.action': 'L3', 'wc.statement.if': 'L2'],
    "levels come from the registry, not the body (${levelOut.constructLevels})")
assertThat((levelOut.constructCounts as Map).keySet() == (['wc.statement.action', 'wc.statement.if'] as Set),
    'an id with a level outside L0 to L5 is dropped from the counts as well')
assertThat(!levelJsonText.contains(canary2) && !levelJsonText.contains('wc.injected'),
    'nothing placed in the body under constructLevels reaches the response')

int bad = results.count { !it }
println "${results.size() - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
