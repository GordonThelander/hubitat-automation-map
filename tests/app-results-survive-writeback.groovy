// A stale whole-snapshot write-back used to empty state.appInfo between the app phase's finalize and the graph
// build (measured 2026-10-10 on 2.4.19: 216 apps at finalize, 0 at publish). Since 2.4.35 the finished list is
// never in state: the finalize keeps it in a static store keyed by generation, which no write-back reaches, and
// in a pending File Manager file, which a code reload does not wipe. These run the app's own functions.
int passed = 0  // a failed assert stops the run with exit 1, so reaching the end means none failed
String source = new File('apps/automation_map.groovy').getText('UTF-8')
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String fns = AppSource.functions(source, ['genKey', 'pendingAppResultsFileName', 'keepAppResults', 'heldAppResults',
                                          'scanGenerationToken', 'latestAppResults', 'sweepGenerationRecords', 'publishRefusal'])
String fields = ['APP_RESULTS', 'REGISTRY_RESULTS', 'TERMINAL_TOMBSTONES', 'SCAN_LOCKS', 'GENERATION_RECORD_RETENTION_MS'].collect { AppSource.field(source, it) }.join('\n')
List logged = []
long clock = 1_000_000L
Map state = [:]
Map<String, byte[]> files = [:]
def script = new GroovyShell(new Binding([state: state, app: [id: 77, label: 'Map'], log: [info: { logged << it }, warn: { logged << it }]])).parse(
    'import groovy.transform.Field\nimport groovy.json.JsonOutput\nimport java.util.concurrent.ConcurrentHashMap\n' + fields + '\n' + fns + '\n')
script.metaClass.now = { -> clock }
script.metaClass.diagOn = { -> false }
script.metaClass.uploadHubFile = { String name, byte[] bytes -> files[name] = bytes }
script.metaClass.downloadHubFile = { String name -> files[name] }

Map apps = (1..216).collectEntries { ["${it}".toString(), [type: 'x', flow: [[step: it]]]] }
List ids = apps.keySet() as List
script.keepAppResults('tok1', apps, ids)
assert files.containsKey('automation-map-77-scan-pending.json') : 'the finalize did not write the pending file'

// The write-back: state now holds an older snapshot with both lists empty. The kept list is not in state.
state.appInfo = [:]
state.appIds = []
Map held = script.heldAppResults('tok1')
assert held != null && (held.appInfo as Map).size() == 216 && (held.appIds as List).size() == 216 :
    'a stale state write-back reached the kept app list'
assert ((held.appInfo as Map)['5'] as Map).flow : 'the kept list lost its flowcharts'
passed++; println 'ok   a stale state write-back cannot shorten the kept app list or its enumeration'

// A code reload wipes every static. A status poll never reads the file; the registry step and the publish do,
// and clearAbandonedScan()'s recovery finishes under a fresh token, so the file is matched on the generation.
script.APP_RESULTS.clear()
assert script.heldAppResults('tok1') == null : 'a status-poll read went to the file'
Map reloaded = script.heldAppResults('recovered-1', 'tok1', true)
assert reloaded != null && (reloaded.appInfo as Map).size() == 216 && (reloaded.appIds as List).size() == 216 :
    'after a code reload the app list was not read back from the pending file'
assert script.heldAppResults('recovered-1') != null && script.heldAppResults('tok1') != null :
    'the list read back from the file was not kept in memory for the steps after it'
passed++; println 'ok   after a code reload the publish reads the list back from the pending file'

// Never another generation's list.
script.APP_RESULTS.clear()
assert script.heldAppResults('tok2', null, true) == null : 'a different generation picked up the pending file'
passed++; println 'ok   the pending file never crosses generations'

// The zero-app incident: with the list lost from memory and file alike, nothing is held, and the publish guard
// refuses rather than publishing an empty map, whether or not the enumeration and witness survived.
files.clear()
assert script.heldAppResults('tok1', null, true) == null
assert script.publishRefusal(216, 0, 216) != null
assert script.publishRefusal(0, 0, 216) != null
assert script.publishRefusal(0, 0, null) != null
passed++; println 'ok   a lost list is never published as a zero-app map'

// The kept copy is the JSON round trip, the plain types the graph build read back from state before 2.4.35.
script.APP_RESULTS.clear()
String gKey = "${'4'}"
script.keepAppResults('tok3', [(gKey): [type: "${'Rule'}-5.1"]], [gKey])
Map rt = script.heldAppResults('tok3').appInfo as Map
assert rt.keySet().every { it instanceof String } && (rt['4'] as Map).type instanceof String
passed++; println 'ok   the kept list has the same plain types the graph build read from state'

// The readers of an active scan find their generation through the lock, including during the publish.
script.SCAN_LOCKS.put("${77}", 'finishing:lock-123-456:999')
assert script.scanGenerationToken() == 'lock-123-456'
script.SCAN_LOCKS.clear()
state.activeGenerationToken = 'lock-9-9'
assert script.scanGenerationToken() == 'lock-9-9'
passed++; println 'ok   readers of an active scan resolve its generation, publish included'

// The self-heal finds the latest generation of THIS app only.
script.APP_RESULTS.put('88:tokX', [appInfo: [a: 1], createdAt: clock + 50])
script.APP_RESULTS.put(script.genKey('tok2'), [appInfo: [b: 1], createdAt: clock + 10])
assert script.latestAppResults().appInfo == [b: 1]
passed++; println 'ok   the self-heal takes the latest kept list of this app'

clock += 16 * 60 * 1000L
script.sweepGenerationRecords()
assert script.APP_RESULTS.isEmpty() : 'kept lists outlived the retention window'
passed++; println 'ok   kept lists are swept with the other generation records'

// Wiring: the finalize keeps the list and writes none of it to state; the registry step and the publish read the
// kept list (file allowed) before they count it; the publish strips flowcharts from copies; the self-heal reads
// the kept list.
String finalizeFn = AppSource.function(source, 'finalizeAppPhase')
assert finalizeFn.contains('keepAppResults(scan.lockToken as String, scan.appInfo as Map, enumeratedIds)')
assert !finalizeFn.contains('state.appInfo =')
assert !AppSource.function(source, 'startScan').contains('state.appInfo =')
String fin = AppSource.function(source, 'finishScan')
assert fin.indexOf('heldAppResults(lockToken, logicalGen, true)') in 0..fin.indexOf('publishRefusal(enumeratedApps')
assert fin.contains('buildGraph(heldInfo)') && !fin.contains('state.appInfo')
String reg = AppSource.function(source, 'fetchRegistry')
assert reg.indexOf('heldAppResults(lockToken, null, true)') in 0..reg.indexOf('int heldApps =')
assert AppSource.function(source, 'selfHealGraphIfNeeded').contains('Map kept = latestAppResults()')
passed++; println 'ok   finalize keeps it out of state; registry, publish and self-heal read the kept list'

// Registry: the last good matches live in atomicState too, and fill in when state has lost them on a failed read.
int fs = source.indexOf('void finishScan(')
assert source.indexOf('atomicState.registryMatchesLastGood = (regResult.matches as List)', fs) > 0
assert source.indexOf('if (!(state.registryMatches) && atomicState.registryMatchesLastGood)', fs) > 0
assert source.indexOf('atomicState.registryMatchesLastGood = matches', source.indexOf('void fetchRegistry(')) > 0
passed++; println 'ok   last good registry matches survive a stale write-back'
println "${passed} passed, 0 failed"
