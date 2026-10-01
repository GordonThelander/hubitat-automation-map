// The 2026-10-01 preprod incident: a fresh install enumerated 165 apps, went
// backwards to 18, and published a 0-app graph as a successful scan, while the
// settings page showed "Starting first scan..." beside live app progress and a
// false stale-format warning. These are the source-level invariants that
// together make each of those symptoms impossible.
//
// Run with: groovy tests/scan-generation-integrity.groovy
String source = new File('apps/automation_map.groovy').getText('UTF-8')

List<Boolean> results = []
def check = { boolean cond, String label ->
    println "${cond ? 'PASS' : 'FAIL'}  ${label}"
    results << cond
}
Closure<String> slice = { String from, String to ->
    int a = source.indexOf(from)
    int b = to ? source.indexOf(to, a) : -1
    return a < 0 ? '' : (b < 0 ? source.substring(a) : source.substring(a, b))
}

// 1. Progress is generation-bound and is cleared when a generation ends.
check(source.contains('String currentGenerationToken()'),
      'a single helper names the generation that owns the live progress')
check(source.contains('gen: currentGenerationToken()'),
      'every progress tuple is stamped with its generation')
check(source.contains('SCAN_PROGRESS.remove('),
      'terminal cleanup removes the progress record, which it never used to')
String clearFn = slice('void clearScanProgressFor(String token)', '\n}')
check(clearFn.contains('live.gen') && clearFn.contains('== token'),
      'cleanup removes only the matching token, so a late finalizer cannot erase a newer generation')
String finallyBlock = slice('TERMINAL_TOMBSTONES.put(tombstoneKey, now())', 'SCAN_LOCKS.remove(')
check(finallyBlock.contains('clearScanProgressFor'),
      'the progress record is cleared before the lock is released, while the token can still be matched')

// 2. A dead generation's counters can never render as live.
String liveFn = slice('boolean scanProgressIsLive()', '\n}')
check(liveFn.contains("SCAN_LOCKS.get(") && liveFn.contains('live.gen'),
      'liveness compares the record owner against the current lock holder')
String progressFn = slice('Map scanProgress() {', '\n}')
check(progressFn.contains('scanProgressIsLive()'),
      'the reader returns the live tuple only while its generation still owns the lock')
check(progressFn.contains('state.scanPhase'),
      'with no live generation the reader falls back to durable completed status, not a dead tuple')

// 3. One coherent render: no second decision taken later in the same page.
check(source.contains('String scanButtonHtml(boolean scanActive, boolean autoStarting)'),
      'the button takes the auto-start decision as an argument')
String buttonFn = slice('String scanButtonHtml(boolean scanActive, boolean autoStarting)', 'String disabled')
// Comment lines stripped: the function explains what it used to do, and the
// assertion is about the code, not the prose describing it.
String buttonCode = buttonFn.readLines().findAll { !it.trim().startsWith('//') }.join(System.lineSeparator())
check(!buttonCode.contains('shouldAutoScan()'),
      'the button no longer re-runs shouldAutoScan() against different storage mid-render')
check(source.contains('boolean autoStarting = !scanActive && shouldAutoScan()'),
      'the auto-start decision is taken once, beside scanActive')
check(source.contains('scanButtonHtml(scanActive, autoStarting)'),
      'the render passes both halves of that one snapshot')

// 4. No obsolete-format warning while work is in flight.
check(source.contains('boolean graphStale = graphIsStale() && !scanActive && !scanProgressIsLive()'),
      'a graph being replaced by an active scan is not reported as an unreadable saved format')

// 5. Publication fails closed on an impossible collapse.
String finishSlice = slice('Integer enumeratedApps =', 'atomicState.graphVersion = GRAPH_SCHEMA')
check(finishSlice.contains('enumeratedApps > 0 && collectedApps != enumeratedApps'),
      'a generation that enumerated apps and holds a different number refuses to publish, which covers holding none')
check(finishSlice.contains('state.scanError = why'),
      'the refusal surfaces an error rather than failing silently')
check(finishSlice.indexOf('return') in 0..finishSlice.indexOf('Map graph = buildGraph()'),
      'the refusal returns before the graph is built or the version is stamped')
check(!finishSlice.contains('partial'),
      'no partial graph is published, per the ruling in queue 891')

// 6. The trace that did not exist during the incident.
String traceFn = slice('void genTrace(String event', '\n}')
check(!traceFn.contains('diagOn()'),
      'the generation trace is recorded even with diagnostic logging off, which is what hid the incident')
check(traceFn.contains('GEN_TRACE_MAX') || source.contains('trail.takeRight(GEN_TRACE_MAX)'),
      'the trace is bounded')
check(traceFn.contains('catch (Exception'),
      'a failing trace can never break the scan it describes')
['installed', 'scheduled', 'endpoint', 'button'].each { String entry ->
    check(source.contains("startScan('${entry}')"), "the ${entry} entry path names itself to the trace")
}
['lock-acquired', 'lock-refused', 'apps-enumerated', 'app-phase-finalized',
 'publish-accepted', 'publish-refused', 'generation-terminated'].each { String ev ->
    check(source.contains("genTrace('${ev}'"), "the trace records ${ev}")
}

// 7. Publication enforces the equality the pipeline's own model guarantees.
check(finishSlice.contains('collectedApps != enumeratedApps'),
      'publication requires every enumerated app to be present, not merely non-zero')
check(source.contains('appInfoSize == total && decoded + unreadable == total'),
      'both finalize entry points still require that equality upstream, which is what makes it valid here')
check(source.contains('failing closed, no map published for this scan'),
      'the watchdog path fails closed rather than publishing a partial inventory')

// 8. The client poll reconciles the render it used to leave contradicting itself.
String poll = slice("function amReconcileRunning()", "function amProgressPoll")
check(poll.contains("getElementById('amScanBtn')") && poll.contains('Scanning...'),
      'a running generation corrects a stale scan button')
check(poll.contains("getElementById('amStaleWarning')") && poll.contains('hidden = true'),
      'a running generation hides a stale obsolete-format warning')
check(source.contains("id='amStaleWarning'"),
      'the server-rendered warning carries the id the poll reconciles')
check(slice('if (d.running) {', 'var isDevicePhase').contains('amReconcileRunning()'),
      'reconciliation runs only while /scan-status reports a generation running')
check(!poll.contains('location.reload'),
      'reconciliation never reloads mid-scan, it only corrects the two stale elements')

int bad = results.count { !it }
println "${results.size() - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
