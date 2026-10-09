// 2.4.18: who uses each Hub Variable, from the hub's own /hub2/variables (firmware 2.5.2.133 and later).
// Sliced from apps/automation_map.groovy, never copied. The row shape is the one a 2.5.2.134 hub returned
// (name, type, ..., usedBy [{id, label}], connectorUsedBy); the names and ids here are invented.
//
// Run with: groovy tests/hub-variable-users.groovy
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String source = AppSource.read()
def script = new GroovyShell().parse(AppSource.functions(source, ['hubVariableUsersByName', 'hubVariableUsersExcept']))

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

// The retry after a refusal: once, and never a second time in a row.
String retryFn = source.substring(source.indexOf('void scheduleRetryAfterRefusal()'), source.indexOf('void retryScanAfterRefusal()'))
check('a refusal schedules one retry, recorded in atomicState') {
    assert retryFn.contains("atomicState.refusalRetry = [pending: true") && retryFn.contains("runIn(REFUSAL_RETRY_SECONDS, 'retryScanAfterRefusal')")
}
check('a refused retry is not retried again') {
    assert retryFn.indexOf('.pending)') < retryFn.indexOf('runIn(')
}
String finish = source.substring(source.indexOf('String why = publishRefusal('), source.indexOf('Map graph = buildGraph()', source.indexOf('String why = publishRefusal(')))
check('the refusal branch schedules the retry, and a publish clears it') {
    assert finish.contains('scheduleRetryAfterRefusal()') && finish.contains('atomicState.refusalRetry = null')
}

println "${pass} passed, ${fail} failed"
if (fail > 0) System.exit(1)
