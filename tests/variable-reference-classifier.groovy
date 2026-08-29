// Gate B regression suite for owner-scoped Local, Hub, and ambiguous variable
// identity. Run with: groovy tests/variable-reference-classifier.groovy
import groovy.json.JsonSlurper

File repoRoot = new File(getClass().protectionDomain.codeSource.location.path).parentFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('.').canonicalFile

GroovyClassLoader loader = new GroovyClassLoader(this.class.classLoader)
Class classifier = loader.parseClass(new File(repoRoot, 'tests/support/VariableReferenceClassifier.groovy'))
Map fixture = new JsonSlurper().parse(new File(repoRoot, 'tests/fixtures/local-hub-variable-gate-a.json')) as Map

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
def classify = { Map reference, Map context -> classifier.classify(reference, context) as Map }

println '--- Saved Gate A fixtures ---'
fixture.rules.each { Map rule ->
    Map context = [
        ownerAppId: rule.ownerAppId,
        localDefinitions: rule.localDefinitions,
        hubDefinitions: fixture.hubDefinitions
    ]
    rule.references.each { Map reference ->
        Map result = classify(reference, context)
        check("rule ${rule.ownerAppId} ${reference.field} classifies ${reference.expectedScope}") {
            assert result.scope == reference.expectedScope
            if (reference.platformBrokenMarker == true) {
                assert result.status == 'unresolved'
                assert result.canonicalName == null
                assert result.reason == 'no-definition-in-either-scope'
            }
        }
    }
}

println '--- Scope and owner identity ---'
Map base = [
    ownerAppId: 'a10',
    localDefinitions: [[name: 'LocalOnly'], [name: 'Shared']],
    hubDefinitions: [[name: 'HubOnly'], [name: 'Shared']]
]
check('local-only structured name resolves Local') {
    Map result = classify([name: 'LocalOnly', operation: 'write', field: 'xVarV.1'], base)
    assert result.scope == 'local'
    assert result.localIdentity == 'a10:LocalOnly'
}
check('hub-only structured name resolves Hub') {
    Map result = classify([name: 'HubOnly', operation: 'read', field: 'xVar_2'], base)
    assert result.scope == 'hub'
    assert result.localIdentity == null
}
check('same name across scopes is ambiguous and cannot emit a Hub edge') {
    Map result = classify([name: 'Shared', operation: 'write', field: 'xVarV.2'], base)
    assert result.scope == 'ambiguous'
    assert result.status == 'ambiguous'
    assert result.candidateScopes == ['local', 'hub']
    assert result.canonicalName == null
}
check('a fixture-proven Local discriminator resolves only Local') {
    Map result = classify([name: 'Shared', provenScope: 'local', scopeSource: 'fixture-field'], base)
    assert result.scope == 'local'
    assert result.localIdentity == 'a10:Shared'
}
check('a fixture-proven Hub discriminator resolves only Hub') {
    Map result = classify([name: 'Shared', provenScope: 'hub', scopeSource: 'fixture-field'], base)
    assert result.scope == 'hub'
}
check('same local name in two rules produces two identities') {
    Map first = classify([name: 'Shared'], base)
    Map second = classify([name: 'Shared', provenScope: 'local', scopeSource: 'fixture-field'],
        base + [ownerAppId: 'a11', hubDefinitions: []])
    assert first.scope == 'ambiguous'
    assert second.localIdentity == 'a11:Shared'
    assert second.localIdentity != 'a10:Shared'
}

println '--- Bounded normalization ---'
check('one trailing-period match resolves inside Local scope') {
    Map result = classify([name: 'OldName.', provenScope: 'local', scopeSource: 'fixture-field'], [
        ownerAppId: 'a20', localDefinitions: [[name: 'OldName']], hubDefinitions: [[name: 'Other']]
    ])
    assert result.scope == 'local'
    assert result.canonicalName == 'OldName'
    assert result.reason == 'one-unambiguous-normalized-match-in-one-scope'
}
check('normalization does not cross scopes to manufacture certainty') {
    Map result = classify([name: 'Collision.'], [
        ownerAppId: 'a20', localDefinitions: [[name: 'Collision']], hubDefinitions: [[name: 'Collision.']]
    ])
    assert result.scope == 'ambiguous'
}
check('exact identity wins before trailing-period normalization') {
    Map result = classify([name: 'Twin..', provenScope: 'hub', scopeSource: 'fixture-field'], [
        ownerAppId: 'a20', localDefinitions: [], hubDefinitions: [[name: 'Twin.'], [name: 'Twin..']]
    ])
    assert result.scope == 'hub'
    assert result.canonicalName == 'Twin..'
}

println '--- Weak text and built-in tokens ---'
check('free-text token without independent scope is ignored') {
    Map result = classify([name: 'HubOnly', evidenceKind: 'text-token', field: 'valString.1'], base)
    assert result.status == 'ignored'
    assert result.scope == null
}
check('built-in token is ignored even when a same-named definition exists without proven scope') {
    Map result = classify([name: 'device', evidenceKind: 'text-token'], [
        ownerAppId: 'a30', localDefinitions: [], hubDefinitions: [[name: 'device']]
    ])
    assert result.status == 'ignored'
    assert result.reason == 'built-in-token-without-independent-scope'
}
check('free-text token may resolve after scope is independently established') {
    Map result = classify([name: 'HubOnly', evidenceKind: 'text-token'], base + [
        establishedScopes: [HubOnly: 'hub']
    ])
    assert result.scope == 'hub'
    assert result.evidence.scopeSource == 'independently-established-reference'
}

println '--- Failure discipline and privacy ---'
check('missing definition is unresolved and does not invent a node') {
    Map result = classify([name: 'DeletedName', operation: 'read'], base)
    assert result.scope == 'unresolved'
    assert result.canonicalName == null
}
check('missing definition inside a proven scope stays unresolved') {
    Map result = classify([name: 'DeletedName', provenScope: 'local', scopeSource: 'fixture-field'], base)
    assert result.scope == 'unresolved'
    assert result.candidateScopes == ['local']
}
check('Local resolution fails without owner app ID') {
    boolean threw = false
    try {
        classify([name: 'LocalOnly'], base + [ownerAppId: null, hubDefinitions: []])
    } catch (IllegalArgumentException expected) {
        threw = true
    }
    assert threw
}
check('scope claims without evidence are rejected') {
    boolean threw = false
    try {
        classify([name: 'HubOnly', provenScope: 'hub'], base)
    } catch (IllegalArgumentException expected) {
        threw = true
    }
    assert threw
}
check('classification output contains no definition values or connector metadata') {
    Map result = classify([name: 'AMGateA_Connector'], [
        ownerAppId: 'a40',
        localDefinitions: [],
        hubDefinitions: [[name: 'AMGateA_Connector', value: 'fixture-value', deviceId: 3601, attribute: 'Variable']]
    ])
    assert result.scope == 'hub'
    assert !result.containsKey('value')
    assert !result.containsKey('deviceId')
    assert !result.containsKey('attribute')
}

println ''
println "${pass} passed, ${fail} failed"
System.exit(fail == 0 ? 0 : 1)
