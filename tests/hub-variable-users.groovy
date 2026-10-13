// 2.4.18: who uses each Hub Variable, from the hub's own /hub2/variables (firmware 2.5.2.133 and later).
// Sliced from apps/automation_map.groovy, never copied. The row shape is the one a 2.5.2.134 hub returned
// (name, type, ..., usedBy [{id, label}], connectorUsedBy); the names and ids here are invented.
//
// Run with: groovy tests/hub-variable-users.groovy
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String source = AppSource.read()
def script = new GroovyShell().parse(AppSource.functions(source, ['hubVariableUsersByName', 'hubVariableUsersExcept', 'reconcileHubVariableUsers']))

int pass = 0, fail = 0
def check = { String name, Closure body ->
    try { body(); println "PASS  ${name}"; pass++ }
    catch (Throwable t) { println "FAIL  ${name} - ${t.class.simpleName}: ${t.message}"; fail++ }
}

def row = { String name, List users -> [name: name, type: 'string', value: 'x', linked: false, meshEnabled: false,
    sourceName: 'Local', connectorOptions: ['Variable'], usedBy: users, connectorUsedBy: []] }
List rows = [
    row('Hall Mode', [[id: 900, label: 'Rule Engine (DEV)'], [id: 501, label: 'Hall Lights']]),
    row('Spare Flag', [[id: 900, label: 'Rule Engine (DEV)']]),
    row('No Users', []),
    [name: null, usedBy: []],
    'not a row'
]

Map byName = script.hubVariableUsersByName(rows)
check('every named row is read, and unnamed or malformed rows are skipped') {
    assert byName.keySet() == ['Hall Mode', 'Spare Flag', 'No Users'] as Set
}
check('users keep their id, as text, and their label') {
    assert byName['Hall Mode'] == [[id: '900', label: 'Rule Engine (DEV)'], [id: '501', label: 'Hall Lights']]
}
check("the engine's permission list is not counted as use") {
    assert script.hubVariableUsersExcept(byName['Hall Mode'], ['900'] as Set) == [[id: '501', label: 'Hall Lights']]
    assert script.hubVariableUsersExcept(byName['Spare Flag'], ['900'] as Set) == []
}
check('no users at all stays an empty list, which reads as "no app uses it"') {
    assert script.hubVariableUsersExcept(byName['No Users'], ['900'] as Set) == []
}

// HAI #60: the hub's list against the decoded edges. Invented hub: engine 900 names every variable; 501 is a rule
// the decode reads; 502 is a rule the decode cannot see; 600 is a webCoRE parent, 601 its piston; 700 is a
// dashboard the hub names but the map does not hold; 503 reads a variable the hub does not list for it.
List hubRows = [
    row('Hall Mode', [[id: 900, label: 'Rule Engine (DEV)'], [id: 501, label: 'Hall Lights'], [id: 502, label: 'Night Check']]),
    row('Porch Count', [[id: 900, label: 'Rule Engine (DEV)'], [id: 600, label: 'webCoRE']]),
    row('Away Flag', [[id: 900, label: 'Rule Engine (DEV)'], [id: 700, label: 'Front Panel']]),
    row('Guest Flag', [[id: 900, label: 'Rule Engine (DEV)']]),
    row('Not In Inventory', [[id: 502, label: 'Night Check']])
]
List decodedEdges = [
    [from: 'a501', to: 'vHall Mode', kind: 'read', usageRole: 'condition'],
    [from: 'a601', to: 'vPorch Count', kind: 'usesVar', direction: 'unknown'],
    [from: 'a503', to: 'vGuest Flag', kind: 'write'],
    [from: 'a501', to: 'd12', kind: 'action']
]
Map appInfo = ['501': [:], '502': [:], '503': [:], '600': [:], '601': [parent: '600'], '900': [:]]
Set appNodeIds = ['a501', 'a502', 'a503', 'a600', 'a601', 'a900'] as Set
Set varNames = ['Hall Mode', 'Porch Count', 'Away Flag', 'Guest Flag'] as Set
Map rec = script.reconcileHubVariableUsers(script.hubVariableUsersByName(hubRows), ['900'] as Set, decodedEdges,
                                           appInfo, appNodeIds, varNames)

check('an app the hub names, on the map, with no decoded edge gets one usesVar edge marked as from the hub list') {
    assert rec.edges == [[from: 'a502', to: 'vHall Mode', kind: 'usesVar', direction: 'unknown', source: 'hub-variables']]
}
check('the engine permission list adds no edge and is not a disagreement') {
    assert !rec.edges.any { it.from == 'a900' }
    assert !rec.summary.decodedOnly.any { it.app == 'a900' } && !rec.summary.hubOnlyNotInMap.any { it.appId == '900' }
}
check('a webCoRE parent the hub names agrees with its piston the decode names, in both directions') {
    assert !rec.edges.any { it.to == 'vPorch Count' }
    assert !rec.summary.decodedOnly.any { it.variable == 'Porch Count' }
}
check('an app the hub names that is not on the map gets no invented node, and is reported') {
    assert !rec.edges.any { it.from == 'a700' }
    assert rec.summary.hubOnlyNotInMap == [[variable: 'Away Flag', appId: '700', label: 'Front Panel']]
}
check('a decoded use the hub does not list is reported, and the decoded edge is left alone') {
    assert rec.summary.decodedOnly == [[variable: 'Guest Flag', app: 'a503']]
    assert decodedEdges.size() == 4
}
check('only inventory variables are compared, and agreement counts exact matches') {
    assert rec.summary.compared == 4
    assert rec.summary.agreeing == 1   // Porch Count only
    assert rec.summary.hubListedEdges == 1
}
check('a second pass over the merged edges adds nothing: hub-listed edges are not mistaken for decoded ones') {
    Map again = script.reconcileHubVariableUsers(script.hubVariableUsersByName(hubRows), ['900'] as Set,
                                                 decodedEdges + rec.edges, appInfo, appNodeIds, varNames)
    assert again.edges == rec.edges
}

// The scan reads the list once, in finishScan, and buildGraph only reads what that stored.
check('/hub2/variables is requested in one place only, and buildGraph makes no request of its own') {
    assert source.count('/hub2/variables"') == 1
    String build = source.substring(source.indexOf('Map buildGraph('), source.indexOf('void rebuildStoredGraph()'))
    assert build.contains('reconcileHubVariableUsers(') && !build.contains('fetchHubVariableUsers(') && !build.contains('httpFetch(')
}

// The retry after a refusal: once, and never a second time in a row.
String retryFn = source.substring(source.indexOf('void scheduleRetryAfterRefusal()'), source.indexOf('void retryScanAfterRefusal()'))
check('a refusal schedules one retry, recorded in atomicState') {
    assert retryFn.contains("atomicState.refusalRetry = [pending: true") && retryFn.contains("runIn(REFUSAL_RETRY_SECONDS, 'retryScanAfterRefusal')")
}
check('a refused retry is not retried again') {
    assert retryFn.indexOf('.pending)') < retryFn.indexOf('runIn(')
}
String finish = source.substring(source.indexOf('String why = publishRefusal('), source.indexOf('Map graph = buildGraph(', source.indexOf('String why = publishRefusal(')))
check('the refusal branch schedules the retry, and a publish clears it') {
    assert finish.contains('scheduleRetryAfterRefusal()') && finish.contains('atomicState.refusalRetry = null')
}

println "${pass} passed, ${fail} failed"
if (fail > 0) System.exit(1)
