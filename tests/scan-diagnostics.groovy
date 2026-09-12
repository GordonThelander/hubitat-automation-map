#!/usr/bin/env groovy
//
// The scan diagnostic helpers, executed straight out of the app source so this
// cannot pass against a drifted copy. They only ever run behind diagOn(), which
// is exactly why they are worth testing: nothing else reads their output, so a
// wrong count would never be contradicted, and Gordon would be troubleshooting
// from numbers no one had checked.
//
// Run with: groovy tests/scan-diagnostics.groovy

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('..').canonicalFile

List<Boolean> results = []
def check = { boolean cond, String label ->
    println "${cond ? 'PASS' : 'FAIL'}  ${label}"
    results << cond
}

// ---- load the helpers straight out of the app --------------------------------

String source = new File(repoRoot, 'apps/automation_map.groovy').getText('UTF-8')
String beginMarker = '// --- scan diagnostics: begin ---'
String endMarker = '// --- scan diagnostics: end ---'
int begin = source.indexOf(beginMarker)
int end = source.indexOf(endMarker)
assert begin >= 0 && end > begin: 'scan diagnostics block not found in app source'
String block = source.substring(begin + beginMarker.length(), end)

// state, atomicState, SCAN_LOCKS, app and now() are the only things the block
// touches, so the stub supplies exactly those and nothing is mocked away.
String harness = '''
class ScanDiagnosticsUnderTest {
    Map state = [:]
    Map atomicState = [:]
    Map SCAN_LOCKS = [:]
    Map app = [id: '3083']
    long stubNow = 1_000_000L
    long now() { return stubNow }
''' + block + '''
}
'''
def diag = new GroovyClassLoader(this.class.classLoader).parseClass(harness).newInstance()

// ---- phaseElapsedSeconds -----------------------------------------------------

// A phase that never stamped a timestamp must read as missing, not as a
// suspiciously fast phase. An app updated mid-scan produces exactly this.
check(diag.phaseElapsedSeconds(null) == -1, 'a null phase timestamp reads as missing, not as zero seconds')
check(diag.phaseElapsedSeconds(0) == -1, 'a zero phase timestamp reads as missing')
check(diag.phaseElapsedSeconds(-5) == -1, 'a negative phase timestamp reads as missing')

diag.stubNow = 1_000_000L
check(diag.phaseElapsedSeconds(1_000_000L - 31_000L) == 31, 'elapsed seconds are computed from the stamp')
check(diag.phaseElapsedSeconds(1_000_000L - 999L) == 0, 'under a second reads as zero, never as missing')

// ---- snapshotPredatesGraphCommit ---------------------------------------------

// Both halves are written together at every commit site, so equal means this
// execution's snapshot already contains that commit.
diag.state = [graphCommittedAtLocal: 500L]
diag.atomicState = [graphCommittedAt: 500L]
check(!diag.snapshotPredatesGraphCommit(), 'a snapshot holding the latest commit is not stale')

// The case from the hub log: a render whose snapshot predates finishScan.
diag.state = [graphCommittedAtLocal: 400L]
diag.atomicState = [graphCommittedAt: 500L]
check(diag.snapshotPredatesGraphCommit(), 'a snapshot older than the last commit is stale')

// Never treat an app that has simply never committed a graph as stale: that is
// a genuinely missing graph and must keep its warning.
diag.state = [:]
diag.atomicState = [:]
check(!diag.snapshotPredatesGraphCommit(), 'no commit marker at all is not stale, it is a real gap')

diag.state = [:]
diag.atomicState = [graphCommittedAt: 500L]
check(diag.snapshotPredatesGraphCommit(), 'a snapshot predating the first ever marker is stale')

diag.state = [graphCommittedAtLocal: 500L]
diag.atomicState = [:]
check(!diag.snapshotPredatesGraphCommit(), 'a missing atomic marker never reports stale')

// ---- webcoreDecodeSummary ----------------------------------------------------

check(String.valueOf(diag.webcoreDecodeSummary()) ==
        'pistons=0 withDeviceReads=0 decodeErrors=0 unresolvedDeviceRefs=0',
    'a hub with no apps at all reports zeros rather than failing')

diag.state = [appInfo: 'not a map']
check(String.valueOf(diag.webcoreDecodeSummary()) ==
        'pistons=0 withDeviceReads=0 decodeErrors=0 unresolvedDeviceRefs=0',
    'an appInfo that is not a map is survived, not thrown on')

// Only webCoRE pistons are counted. A Rule Machine app carrying similar-looking
// keys must not inflate the piston count.
diag.state = [appInfo: [
    '1': [type: 'Rule-5.1', webcoreDeviceReads: [[token: ':a:']]],
    '2': [type: 'webCoRE Piston'],
    '3': [type: 'webCoRE'],
    '4': 'not a map'
]]
check(String.valueOf(diag.webcoreDecodeSummary()) ==
        'pistons=1 withDeviceReads=0 decodeErrors=0 unresolvedDeviceRefs=0',
    'only pistons are counted, and a non-map entry is skipped rather than thrown on')

// The parent app type is 'webCoRE' and must never be counted as a piston.
diag.state = [appInfo: ['1': [type: 'webCoRE', webcoreDeviceReads: [[token: ':a:']]]]]
check(String.valueOf(diag.webcoreDecodeSummary()) ==
        'pistons=0 withDeviceReads=0 decodeErrors=0 unresolvedDeviceRefs=0',
    'the webCoRE parent app is not counted as a piston')

// A trailing space in a saved type must not lose a piston, which is why the
// production code trims before comparing.
diag.state = [appInfo: ['1': [type: 'webCoRE Piston ']]]
check(String.valueOf(diag.webcoreDecodeSummary()).startsWith('pistons=1'),
    'a padded app type still counts as a piston')

diag.state = [appInfo: [
    '1': [type: 'webCoRE Piston', webcoreDeviceReads: [[token: ':a:'], [token: ':b:']]],
    '2': [type: 'webCoRE Piston', webcoreDeviceReads: []],
    '3': [type: 'webCoRE Piston']
]]
check(String.valueOf(diag.webcoreDecodeSummary()) ==
        'pistons=3 withDeviceReads=1 decodeErrors=0 unresolvedDeviceRefs=0',
    'withDeviceReads counts pistons holding reads, not the reads themselves')

diag.state = [appInfo: [
    '1': [type: 'webCoRE Piston', webcoreVariableDecodeStatus: 'error'],
    '2': [type: 'webCoRE Piston', webcoreVariableDecodeStatus: 'complete'],
    '3': [type: 'webCoRE Piston']
]]
check(String.valueOf(diag.webcoreDecodeSummary()) ==
        'pistons=3 withDeviceReads=0 decodeErrors=1 unresolvedDeviceRefs=0',
    'only an error status counts as a decode error')

// Unresolved refs are a total across every code and every piston, because that
// is the number worth acting on: one piston with five is the same amount of
// missing evidence as five pistons with one.
diag.state = [appInfo: [
    '1': [type: 'webCoRE Piston', webcoreUnsupportedDeviceRefs:
            ['variable-backed-device-list': 2, 'runtime-selected-device': 1]],
    '2': [type: 'webCoRE Piston', webcoreUnsupportedDeviceRefs: ['non-physical-device': 3]],
    '3': [type: 'webCoRE Piston', webcoreUnsupportedDeviceRefs: [:]]
]]
check(String.valueOf(diag.webcoreDecodeSummary()) ==
        'pistons=3 withDeviceReads=0 decodeErrors=0 unresolvedDeviceRefs=6',
    'unresolved refs are summed across every code and every piston')

// ---- lockVsState -------------------------------------------------------------

diag.state = [:]
diag.atomicState = [:]
diag.SCAN_LOCKS = [:]
String idle = String.valueOf(diag.lockVsState())
check(idle.contains('lock=none'), "an absent lock reads as none: ${idle}")
check(idle.contains('gen=none'), "an absent generation reads as none: ${idle}")
check(idle.contains('running=false'), "scanRunning absent reads as false: ${idle}")
check(idle.contains('graph=false'), "an absent graph reads as false: ${idle}")
check(idle.contains('appInfo=0'), "an absent appInfo reads as zero: ${idle}")

// SCAN_LOCKS is keyed by a GString. Every one of the app's fifteen accesses to
// it uses "${app.id}", puts and gets alike, so the map is self-consistent and
// the lock machinery works. It matters here because a GString and a String are
// NOT interchangeable as keys: their hashCodes differ and equals() is false,
// while two GStrings of equal content do match. Seeding this with a plain
// String would test a key shape production never creates, and "fixing"
// lockVsState() to match it would break it against every real key in the map.
String lockKeyId = diag.app.id
Map<Object, String> gstringKeyed = [:]
gstringKeyed.put("${lockKeyId}", 'lock-1789200000000-482913')
diag.SCAN_LOCKS = gstringKeyed
diag.state = [activeGenerationToken: 'lock-1789200000000-117755', scanRunning: true,
              scanPhase: 'apps', graph: [nodes: []], appInfo: ['a': 1, 'b': 2],
              appResultsReady: true]
diag.atomicState = [graphVersion: '15']
String live = String.valueOf(diag.lockVsState())
check(live.contains('lock=482913'), "the lock tail is reported: ${live}")
check(live.contains('gen=117755'), "the generation tail is reported: ${live}")
check(!live.contains('1789200000000'), "the full token is never logged: ${live}")
check(live.contains('running=true') && live.contains('phase=apps'), "phase and running are reported: ${live}")
check(live.contains('graph=true') && live.contains('graphVersion=15'), "graph presence and version: ${live}")
check(live.contains('appInfo=2') && live.contains('appResultsReady=true'), "appInfo size and readiness: ${live}")

// Documents the trap rather than leaving it to be rediscovered: a String key is
// invisible to the GString lookup. This is not an endorsement of the shape, it
// is a record of why lockVsState() must keep using the same "${app.id}" form as
// every other SCAN_LOCKS access in the file.
Map<Object, String> stringKeyed = [:]
stringKeyed.put('3083', 'lock-1789200000000-482913')
diag.SCAN_LOCKS = stringKeyed
check(String.valueOf(diag.lockVsState()).contains('lock=none'),
    'a String-keyed lock is invisible to the GString lookup the whole file uses')

// The combination backlog item 30 is trying to tell apart: results published
// and readiness committed, but the graph gone from this execution's snapshot.
diag.state = [activeGenerationToken: 'lock-1789200000000-117755', scanRunning: true,
              scanPhase: 'apps', appInfo: ['a': 1], appResultsReady: true]
diag.SCAN_LOCKS = [:]
String stale = String.valueOf(diag.lockVsState())
check(stale.contains('lock=none') && stale.contains('graph=false') && stale.contains('appResultsReady=true'),
    "a stale snapshot is distinguishable from an early one: ${stale}")

// ---- summary -----------------------------------------------------------------

int passed = results.count { it }
int failed = results.size() - passed
println ''
println "${passed} passed, ${failed} failed"
if (failed > 0) System.exit(1)
