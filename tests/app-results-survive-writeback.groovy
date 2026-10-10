// A stale whole-snapshot write-back can empty state.appInfo between the app phase's finalize and the graph
// build (measured 2026-10-10 on 2.4.19: 216 apps at finalize, 0 at publish). The finalize keeps the list in a
// static store keyed by generation, and the later steps put it back. These run the app's own functions.
String source = new File('apps/automation_map.groovy').getText('UTF-8')
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String fns = AppSource.functions(source, ['genKey', 'restoreAppResultsIfLost', 'latestAppResults', 'sweepGenerationRecords'])
String fields = ['APP_RESULTS', 'REGISTRY_RESULTS', 'TERMINAL_TOMBSTONES', 'GENERATION_RECORD_RETENTION_MS'].collect { AppSource.field(source, it) }.join('\n')
List logged = []
long clock = 1_000_000L
Map state = [:]
def script = new GroovyShell(new Binding([state: state, app: [id: 77, label: 'Map'], log: [info: { logged << it }]])).parse(
    'import groovy.transform.Field\nimport java.util.concurrent.ConcurrentHashMap\n' + fields + '\n' + fns + '\n')
script.metaClass.now = { -> clock }

Map apps = (1..216).collectEntries { ["${it}".toString(), [type: 'x']] }
script.APP_RESULTS.put(script.genKey('tok1'), [appInfo: new LinkedHashMap(apps), appIds: apps.keySet() as List, createdAt: clock])

// The write-back: state now holds the older, empty snapshot.
state.appInfo = [:]
state.appIds = apps.keySet() as List
assert script.restoreAppResultsIfLost('tok1') : 'an erased app list was not restored'
assert state.appInfo.size() == 216 && logged.any { it.contains('0 of 216') }
println 'ok   an app list erased after the app phase is put back'

// Never shrinks a list: a state already holding as many (or more) is left alone.
state.appInfo = new LinkedHashMap(apps); state.appInfo['999'] = [type: 'y']
assert !script.restoreAppResultsIfLost('tok1') && state.appInfo.size() == 217
assert !script.restoreAppResultsIfLost('other-generation')
println 'ok   it never shrinks state and never crosses generations'

// The self-heal finds the latest generation of THIS app only.
script.APP_RESULTS.put('88:tokX', [appInfo: [a: 1], createdAt: clock + 50])
script.APP_RESULTS.put(script.genKey('tok2'), [appInfo: [b: 1], createdAt: clock + 10])
assert script.latestAppResults().appInfo == [b: 1]
println 'ok   the self-heal takes the latest kept list of this app'

clock += 16 * 60 * 1000L
script.sweepGenerationRecords()
assert script.APP_RESULTS.isEmpty() : 'kept lists outlived the retention window'
println 'ok   kept lists are swept with the other generation records'

// Wiring: the finalize keeps the list, and the registry step, the publish and the self-heal read it back.
assert source.contains('APP_RESULTS.put(genKey(scan.lockToken as String), [appInfo: new LinkedHashMap(scan.appInfo as Map)')
int fin = source.indexOf('void finishScan(')
assert source.indexOf('restoreAppResultsIfLost(lockToken)', fin) < source.indexOf('publishRefusal(enumeratedApps', fin)
int reg = source.indexOf('void fetchRegistry(')
assert source.indexOf('restoreAppResultsIfLost(lockToken)', reg) < source.indexOf('int heldApps =', reg)
assert source.indexOf('Map kept = latestAppResults()', source.indexOf('void selfHealGraphIfNeeded(')) > 0
println 'ok   finalize keeps it; registry, publish and self-heal restore it first'
