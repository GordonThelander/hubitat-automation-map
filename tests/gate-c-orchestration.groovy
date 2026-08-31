// Gate C regression suite for the orchestration logic layered on top of the
// Gate B classifier: combining raw writes/reads into per-reference input with
// same-rule-only weak-text promotion, and the post-classification flow-label
// join. These are duplicated here in minimal form (matching how
// variable-reference-classifier.groovy already duplicates the classifier
// itself) because they live inside apps/automation_map.groovy, a Hubitat app
// script that cannot be parsed standalone outside the platform sandbox.
// Keep behaviourally in sync with automation_map.groovy's
// classifyRuleVariableReferences() and correctFlowVariableLabels() by hand.
// Run with: groovy tests/gate-c-orchestration.groovy
import groovy.json.JsonSlurper

File repoRoot = new File(getClass().protectionDomain.codeSource.location.path).parentFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('.').canonicalFile

GroovyClassLoader loader = new GroovyClassLoader(this.class.classLoader)
Class classifier = loader.parseClass(new File(repoRoot, 'tests/support/VariableReferenceClassifier.groovy'))

int pass = 0
int fail = 0
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

// Minimal reproduction of classifyRuleVariableReferences() - combines raw
// writes/reads, classifies structured references first then weak text so
// same-rule promotion sees resolved structured scopes, seeds
// establishedScopes under both canonicalName and raw name.
Closure classifyRuleVariableReferences = { List hubVarWrites, List hubVarReads, List localDefinitions,
        Map hubDefinitions, String ownerAppId ->
    List raw = []
    (hubVarWrites ?: []).each { Map w ->
        if (!w.variable) return
        raw << [name: w.variable, operation: 'write', evidenceKind: 'structured-setting', field: w.field]
    }
    (hubVarReads ?: []).each { Map r ->
        if (!r.variable) return
        raw << [name: r.variable, operation: 'read', usageRole: r.usageRole,
                 evidenceKind: r.evidenceKind, field: r.field]
    }
    Map establishedScopes = [:]
    Map context = [ownerAppId: ownerAppId, localDefinitions: localDefinitions,
                   hubDefinitions: hubDefinitions, establishedScopes: establishedScopes]
    List structured = raw.findAll { it.evidenceKind != 'text-token' }
    List weakText = raw.findAll { it.evidenceKind == 'text-token' }
    List resolved = []
    List nonResolved = []
    (structured + weakText).each { Map reference ->
        Map result = classifier.classify(reference, context) as Map
        if (result.status == 'resolved') {
            String canonical = result.canonicalName as String
            if (canonical && !establishedScopes.containsKey(canonical)) establishedScopes[canonical] = result.scope
            String rawResultName = result.name as String
            if (rawResultName && !establishedScopes.containsKey(rawResultName)) establishedScopes[rawResultName] = result.scope
        }
        if (result.status == 'ignored') return
        if (result.status == 'resolved') resolved << result
        else nonResolved << result
    }
    return [variableReferences: resolved, nonResolvedVariableReferences: nonResolved]
}

println '--- Combining writes and reads ---'
check('a rule with a hub-only write and a local-only read classifies both correctly') {
    Map result = classifyRuleVariableReferences([[variable: 'HubOnly', field: 'xVarV.1']],
        [[variable: 'LocalOnly', usageRole: 'condition', evidenceKind: 'structured-setting', field: 'xVar_1']],
        [[name: 'LocalOnly']], [HubOnly: [:]], 'a10')
    assert result.variableReferences.size() == 2
    assert result.variableReferences.find { it.name == 'HubOnly' }.scope == 'hub'
    assert result.variableReferences.find { it.name == 'LocalOnly' }.scope == 'local'
}

println '--- Same-rule-only weak text promotion ---'
check('a same-rule structured write establishes scope for a later weak-text read of the same name') {
    Map result = classifyRuleVariableReferences([[variable: 'Shared', field: 'xVarV.1']],
        [[variable: 'Shared', evidenceKind: 'text-token', field: 'valString.1']],
        [], [Shared: [:]], 'a10')
    assert result.variableReferences.size() == 2
    assert result.variableReferences.every { it.scope == 'hub' }
}
check('a canonical-name-only structured match still promotes a weak text token using the canonical spelling') {
    // Structured reference is "Example." (trailing period), canonical resolves to "Example".
    // The weak-text token uses the bare canonical spelling with no period - this only works
    // if establishedScopes was seeded under canonicalName, not just the raw stored name.
    Map result = classifyRuleVariableReferences([[variable: 'Example.', field: 'xVarV.1']],
        [[variable: 'Example', evidenceKind: 'text-token', field: 'valString.1']],
        [], [Example: [:]], 'a10')
    Map textResult = result.variableReferences.find { it.evidence.field == 'valString.1' }
    assert textResult != null
    assert textResult.scope == 'hub'
    assert textResult.evidence.scopeSource == 'independently-established-reference'
}
check('a different rule cannot promote this rule\'s weak text token') {
    // No structured reference in THIS rule for "OnlyInOtherRule" - only a text-token guess.
    Map result = classifyRuleVariableReferences([], [[variable: 'OnlyInOtherRule', evidenceKind: 'text-token', field: 'valString.1']],
        [], [OnlyInOtherRule: [:]], 'a10')
    assert result.variableReferences.size() == 0
    assert result.nonResolvedVariableReferences.size() == 0 // ignored, not non-resolved
}

// Minimal reproduction of correctFlowVariableLabels() - joins already-built
// flow steps to their classified reference by variableField, rewrites the
// neutral "Set Variable X" label actionLabel() emits into the scope-aware
// form. Mutates step maps in place, same as the app's own version.
Closure correctFlowVariableLabels = { Map flows, Map ruleVariablesArg ->
    flows.each { String appNodeId, Object stepsObj ->
        if (!(stepsObj instanceof List)) return
        List refs = (ruleVariablesArg[appNodeId]?.variableReferences ?: []) as List
        List nonResolved = (ruleVariablesArg[appNodeId]?.nonResolvedVariableReferences ?: []) as List
        Map byField = [:]
        (refs + nonResolved).each { Map r -> if (r.evidence?.field) byField["${r.evidence.field}"] = r }
        (stepsObj as List).each { Object step ->
            if (!(step instanceof Map)) return
            Map s = step as Map
            String field = s.variableField as String
            if (!field) return
            Map r = byField[field] as Map
            if (!r) return
            String currentLabel = s.label as String
            if (!currentLabel?.startsWith('Set Variable ')) return
            String rest = currentLabel.substring('Set Variable '.length())
            int fromIdx = rest.indexOf(' from ')
            String suffix = fromIdx >= 0 ? rest.substring(fromIdx) : ''
            String varName = (r.canonicalName ?: r.name) as String
            if (r.status == 'resolved' && r.scope == 'local') {
                s.label = "Set Local Variable ${varName}${suffix}"
            } else if (r.status == 'resolved' && r.scope == 'hub') {
                s.label = "Set Hub Variable ${varName}${suffix}"
            } else if (r.status == 'ambiguous') {
                s.label = "Set Variable ${varName} (scope ambiguous)${suffix}"
            } else if (r.status == 'unresolved') {
                s.label = "Set Variable ${varName} (unresolved)${suffix}"
            }
        }
    }
}

println '--- correctFlowVariableLabels() ---'
check('resolved Local reference relabels to Set Local Variable') {
    Map flows = [a10: [[kind: 'action', label: 'Set Variable Foo', variableField: 'xVarV.1']]]
    Map rv = [a10: [variableReferences: [[canonicalName: 'Foo', name: 'Foo', status: 'resolved', scope: 'local',
        evidence: [field: 'xVarV.1']]], nonResolvedVariableReferences: []]]
    correctFlowVariableLabels(flows, rv)
    assert flows.a10[0].label == 'Set Local Variable Foo'
}
check('resolved Hub reference relabels to Set Hub Variable') {
    Map flows = [a10: [[kind: 'action', label: 'Set Variable Foo', variableField: 'xVarV.1']]]
    Map rv = [a10: [variableReferences: [[canonicalName: 'Foo', name: 'Foo', status: 'resolved', scope: 'hub',
        evidence: [field: 'xVarV.1']]], nonResolvedVariableReferences: []]]
    correctFlowVariableLabels(flows, rv)
    assert flows.a10[0].label == 'Set Hub Variable Foo'
}
check('ambiguous reference relabels with a neutral scope-ambiguous suffix') {
    Map flows = [a10: [[kind: 'action', label: 'Set Variable Foo', variableField: 'xVarV.1']]]
    Map rv = [a10: [variableReferences: [], nonResolvedVariableReferences: [
        [canonicalName: null, name: 'Foo', status: 'ambiguous', scope: 'ambiguous', evidence: [field: 'xVarV.1']]]]]
    correctFlowVariableLabels(flows, rv)
    assert flows.a10[0].label == 'Set Variable Foo (scope ambiguous)'
}
check('unresolved reference relabels with a neutral unresolved suffix') {
    Map flows = [a10: [[kind: 'action', label: 'Set Variable Foo', variableField: 'xVarV.1']]]
    Map rv = [a10: [variableReferences: [], nonResolvedVariableReferences: [
        [canonicalName: null, name: 'Foo', status: 'unresolved', scope: 'unresolved', evidence: [field: 'xVarV.1']]]]]
    correctFlowVariableLabels(flows, rv)
    assert flows.a10[0].label == 'Set Variable Foo (unresolved)'
}
check('a device-attribute source suffix is preserved across the relabel') {
    Map flows = [a10: [[kind: 'action', label: 'Set Variable Foo from Kitchen Sensor.temperature', variableField: 'xVarV.1']]]
    Map rv = [a10: [variableReferences: [[canonicalName: 'Foo', name: 'Foo', status: 'resolved', scope: 'hub',
        evidence: [field: 'xVarV.1']]], nonResolvedVariableReferences: []]]
    correctFlowVariableLabels(flows, rv)
    assert flows.a10[0].label == 'Set Hub Variable Foo from Kitchen Sensor.temperature'
}
check('a non-variable flow step is left completely unchanged') {
    Map flows = [a10: [[kind: 'trigger', label: 'Motion Sensor active', devices: ['Motion Sensor']]]]
    Map rv = [a10: [variableReferences: [], nonResolvedVariableReferences: []]]
    correctFlowVariableLabels(flows, rv)
    assert flows.a10[0].label == 'Motion Sensor active'
}
check('two writes to the same variable name are distinguished by variableField, not display name') {
    Map flows = [a10: [
        [kind: 'action', label: 'Set Variable Foo', variableField: 'xVarV.1'],
        [kind: 'action', label: 'Set Variable Foo', variableField: 'xVarV.2'],
    ]]
    Map rv = [a10: [variableReferences: [
        [canonicalName: 'Foo', name: 'Foo', status: 'resolved', scope: 'hub', evidence: [field: 'xVarV.1']],
    ], nonResolvedVariableReferences: [
        [canonicalName: null, name: 'Foo', status: 'unresolved', scope: 'unresolved', evidence: [field: 'xVarV.2']],
    ]]]
    correctFlowVariableLabels(flows, rv)
    assert flows.a10[0].label == 'Set Hub Variable Foo'
    assert flows.a10[1].label == 'Set Variable Foo (unresolved)'
}

// Minimal reproduction of the graph read-edge usageRole aggregation (review 292
// correction): only a role set that is BOTH size-one AND non-null is trusted.
Closure aggregateReadUsageRole = { List roles ->
    Set<String> distinct = roles as Set<String>
    return (distinct.size() == 1 && distinct.first() != null) ? distinct.first() : 'unknown-read'
}

println '--- Read-edge usageRole aggregation ---'
check('[condition, null] falls back to unknown-read, not condition-only') {
    assert aggregateReadUsageRole(['condition', null]) == 'unknown-read'
}
check('[condition, trigger] falls back to unknown-read') {
    assert aggregateReadUsageRole(['condition', 'trigger']) == 'unknown-read'
}
check('[condition, condition] trusts the single shared role') {
    assert aggregateReadUsageRole(['condition', 'condition']) == 'condition'
}

println ''
println "${pass} passed, ${fail} failed"
System.exit(fail == 0 ? 0 : 1)
