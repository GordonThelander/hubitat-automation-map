// HAI Issue #44: haiContainerFacts(), the scan's reading of one HAI Rule Container's own state.
// Sliced from apps/automation_map.groovy, never copied. Its verdicts are drawn in deriveInsightData();
// tests/insights-hai-containers.js covers that half against the same shapes.
//
// The statusJson shape below - appState as [{name, value}], eventSubscriptions as a list - is the one
// processAppRelationships() already reads for every app. The rt map is HAI's own state initialiser's
// shape (ruleId and staged among its keys, v always present). Names are invented.
//
// Run with: groovy tests/hai-rule-containers.groovy
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String source = AppSource.read()
def script = new GroovyShell().parse(AppSource.functions(source, ['haiContainerFacts']))

int pass = 0, fail = 0
def check = { String name, Closure body ->
    try {
        body()
        println "PASS  ${name}"
        pass++
    } catch (Throwable t) {
        println "FAIL  ${name} - ${t.class.simpleName}: ${t.message}"
        fail++
    }
}

def rt = { Map extra = [:] -> [v: 1, ruleId: null, staged: null, docs: [:], world: null] + extra }
def status = { Object rtValue, List subs = [] ->
    [installedApp: [id: 4100, name: 'HAI Rule Container (DEV)'],
     appState: [[name: 'editorLink', value: 'http://example.invalid/'], [name: 'rt', value: rtValue]],
     eventSubscriptions: subs, appSettings: []]
}
List deviceSub = [[type: 'DEVICE', typeId: 17, typeName: 'Hall Light', name: 'switch']]

println '--- an orphan, found by its state ---'
def orphan = script.haiContainerFacts(status(rt()), 'HAI rule HR-12')
check('a container with no rule id, nothing staged and no subscriptions is checked and holds no rule') {
    assert orphan == [checked: true, ruleId: null, staged: false, subs: 0, nameKind: 'placeholder']
}
check('the verdict does not depend on the name: the same state under any other name reads the same') {
    Map other = script.haiContainerFacts(status(rt()), 'Something Else')
    assert other.checked == true && other.ruleId == null && other.staged == false && other.subs == 0
    assert other.nameKind == 'other'
}

println '--- a healthy container ---'
def healthy = script.haiContainerFacts(status(rt(ruleId: 'HR-7'), deviceSub), '[HAI] Hall Light')
check('a container whose state holds a rule id reports it, with its subscriptions') {
    assert healthy == [checked: true, ruleId: 'HR-7', staged: false, subs: 1, nameKind: 'rule']
}
check('a rule staged and not yet committed is a rule, not an orphan') {
    Map s = script.haiContainerFacts(status(rt(ruleId: 'HR-8', staged: [claimId: 'c1'])), '[HAI] Front Door')
    assert s.checked == true && s.ruleId == 'HR-8' && s.staged == true
}

println '--- name and state disagreeing ---'
check('named as a rule while the state holds none: both facts reported, for the insight to call it a defect') {
    Map m = script.haiContainerFacts(status(rt()), '[HAI] Front Door')
    assert m.checked == true && m.ruleId == null && m.nameKind == 'rule'
}
check('the placeholder name while the state holds a rule') {
    Map m = script.haiContainerFacts(status(rt(ruleId: 'HR-9'), deviceSub), 'HAI rule HR-9')
    assert m.checked == true && m.ruleId == 'HR-9' && m.nameKind == 'placeholder'
}
check('a name with more words after "HAI rule" is not the placeholder') {
    assert script.haiContainerFacts(status(rt()), 'HAI rule for the Hall Light').nameKind == 'other'
}

println '--- could not check: never a verdict ---'
check('no appState in the response') {
    Map r = script.haiContainerFacts([installedApp: [id: 1], eventSubscriptions: []], 'HAI rule HR-1')
    assert r.checked == false && r.reason
}
check('no rt entry in the state') {
    Map d = status(rt())
    d.appState = [[name: 'editorLink', value: 'x']]
    Map r = script.haiContainerFacts(d, 'HAI rule HR-1')
    assert r.checked == false && r.reason.contains('rt')
}
check('an rt entry that is not a map') {
    Map r = script.haiContainerFacts(status('not json at all'), 'HAI rule HR-1')
    assert r.checked == false && r.reason.contains('not a map')
}
check('an rt map without the version marker HAI always writes') {
    Map r = script.haiContainerFacts(status([ruleId: null, staged: null]), 'HAI rule HR-1')
    assert r.checked == false && r.reason.contains('version')
}
check('subscriptions missing from the response') {
    Map d = status(rt())
    d.remove('eventSubscriptions')
    Map r = script.haiContainerFacts(d, 'HAI rule HR-1')
    assert r.checked == false && r.reason.contains('subscriptions')
}
check('a staged rule with no rule id is not a shape HAI writes') {
    Map r = script.haiContainerFacts(status(rt(staged: [claimId: 'c2'])), 'HAI rule HR-1')
    assert r.checked == false && r.reason.contains('staged')
}
check('no rule id while holding event subscriptions is not called an orphan') {
    Map r = script.haiContainerFacts(status(rt(), deviceSub), 'HAI rule HR-1')
    assert r.checked == false && r.reason.contains('1 event subscription')
}
check('an rt entry delivered as JSON text is read the same as a map') {
    Map r = script.haiContainerFacts(status('{"v":1,"ruleId":null,"staged":null}'), 'HAI rule HR-2')
    assert r == [checked: true, ruleId: null, staged: false, subs: 0, nameKind: 'placeholder']
}

println '--- wiring ---'
check('the scan reads it for HAI Rule Container apps only, from the response already in hand') {
    assert source.contains('if ("${out.type}".contains(HAI_RULE_CONTAINER_TYPE)) {')
    assert source.contains('out.haiContainer = haiContainerFacts(data, out.drawLabel as String)')
    assert source.contains("@Field static final String HAI_RULE_CONTAINER_TYPE = 'HAI Rule Container'")
}
check('and the facts reach the graph node the insight reads') {
    assert source.contains('if (appMap.haiContainer instanceof Map) nodes[appNodeId].haiContainer = appMap.haiContainer')
}

println ''
println "${pass} passed, ${fail} failed"
System.exit(fail == 0 ? 0 : 1)
