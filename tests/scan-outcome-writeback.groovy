// /scan-status reports a finished scan's outcome even after a stale state write-back (2.4.38). The outcome lived
// only in atomicState.lastScanOutcome, which another execution's write-back of an older snapshot can null after
// the scan has finished, so a caller polling for it saw nothing. These run the app's own functions; only the hub's
// objects (state, atomicState, settings, the File Manager) are supplied.
String source = new File('apps/automation_map.groovy').getText('UTF-8')
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String fields = ['SCAN_OUTCOMES', 'SCAN_LOCKS', 'PUBLISHED_SCAN', 'PUBLISHED_SCAN_MISSING'].collect { AppSource.field(source, it) }.join('\n')
String fns = AppSource.functions(source, ['scanOutcomeNow', 'graphVersionNow', 'scanEffectivelyActive', 'graphData',
                                          'publishedScan', 'publishedScanFileName', 'diagOn'])
Map state = [:]
Map atomicState = [:]
Map<String, byte[]> files = [:]
def script = new GroovyShell(new Binding([state: state, atomicState: atomicState, settings: [:], app: [id: 77, label: 'Map'],
                                          log: [info: { }, warn: { }]])).parse(
    'import groovy.transform.Field\nimport java.util.concurrent.ConcurrentHashMap\n' + fields + '\n' + fns + '\n')
script.metaClass.now = { -> 1_000_000L }
script.metaClass.downloadHubFile = { String name -> files[name] }

int passed = 0
void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); println "PASS  ${what}" }
Closure ok = { boolean c, String w -> check(c, w); passed++ }

// While this app holds the scan lock it is running, without reading atomicState at all.
script.SCAN_LOCKS.put('77', 'tok1')
atomicState.lastScanOutcome = 'completed'
ok(script.scanOutcomeNow(true) == 'running', 'with the scan lock held the outcome is running')
script.SCAN_LOCKS.clear()

// startScan and finishGeneration keep the outcome where no state commit can reach it.
script.SCAN_OUTCOMES.put('77', 'completed')
atomicState.lastScanOutcome = null
state.clear()
ok(script.scanOutcomeNow(false) == 'completed', 'a write-back that nulled atomicState does not lose a completed scan')
script.SCAN_OUTCOMES.put('77', 'failed')
ok(script.scanOutcomeNow(false) == 'failed', 'nor a failed one')
atomicState.lastScanOutcome = 'running'
script.SCAN_OUTCOMES.put('77', 'completed')
ok(script.scanOutcomeNow(false) == 'completed', 'the static record wins over a stale atomicState written before the scan ended')

// A code reload wipes every static. atomicState is the fallback.
script.SCAN_OUTCOMES.clear()
atomicState.lastScanOutcome = 'completed'
ok(script.scanOutcomeNow(false) == 'completed', 'after a code reload the stored outcome is read')

// A reload and a write-back: the outcome is derived from what the scan left.
atomicState.lastScanOutcome = null
state.scanRunning = true
ok(script.scanOutcomeNow(false) == 'running', 'a scan still marked running is running')
state.scanRunning = false
state.scanError = 'app phase timed out'
ok(script.scanOutcomeNow(false) == 'failed', 'a scan that left an error failed')
state.scanError = null
atomicState.graphVersion = '26'
ok(script.scanOutcomeNow(false) == 'completed', 'a map with a version is a completed scan')
atomicState.graphVersion = null
files['automation-map-77-scan.json'] = '{"committedAt":5,"graph":{"schema":"26"},"appInfo":{}}'.getBytes('UTF-8')
state.graphCommittedAtLocal = 5L
ok(script.scanOutcomeNow(false) == 'completed', 'a saved map file with a version is a completed scan too')
files.clear()
script.PUBLISHED_SCAN.clear()
script.PUBLISHED_SCAN_MISSING.clear()
state.clear()
ok(script.scanOutcomeNow(false) == null, 'no scan at all is no outcome, not an invented one')

// The writers and the endpoint, by source: the static is set beside every atomicState write, and /scan-status
// answers under both names.
String start = AppSource.function(source, 'startScan')
ok(start.contains("SCAN_OUTCOMES.put(\"\${app.id}\".toString(), 'running')"), 'startScan records running in the static')
String finish = AppSource.function(source, 'finishGeneration')
int fin = finish.indexOf('} finally {')
ok(fin > 0 && finish.indexOf('SCAN_OUTCOMES.put("${app.id}".toString(), outcome)', fin) > fin,
   'finishGeneration records the outcome in the static in its finally, whatever happened')
String status = AppSource.function(source, 'scanStatusJson')
ok(status.contains('String outcome = scanOutcomeNow(scanLockHeld)') && status.contains('lastScanOutcome: outcome,') &&
   status.contains('outcome: outcome,'), '/scan-status reports the outcome as lastScanOutcome and as outcome')

println "${passed} scan outcome assertions passed"
