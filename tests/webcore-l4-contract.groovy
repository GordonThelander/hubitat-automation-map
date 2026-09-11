#!/usr/bin/env groovy
//
// The L4 semantic evidence contract, checked without the normaliser. Every claim must meet the
// contract, every hand-authored trace must be well formed, cited and complete for its fixture, and the
// gap list must be closed. Mutations prove each missing contract item withdraws only its own claim.
// Run with: groovy tests/webcore-l4-contract.groovy

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('..').canonicalFile

List<Boolean> results = []
def check = { boolean cond, String label ->
    println "${cond ? 'PASS' : 'FAIL'}  ${label}"
    results << cond
}
def copy = { Object o -> new JsonSlurper().parseText(JsonOutput.toJson(o)) }

Map manifest = new GroovyShell().evaluate(new File(repoRoot, 'tools/webcore-investigation/evidence/semantic-l4.groovy').getText('UTF-8')) as Map
String registryText = new File(repoRoot, 'tools/webcore-investigation/generated/webcore_construct_registry.groovy').getText('UTF-8')
String anchor = 'WEBCORE_CONSTRUCT_REGISTRY = '
Map registry = new GroovyShell().evaluate(registryText.substring(registryText.indexOf(anchor) + anchor.length())) as Map
Map regionHashes = (registry.provenance as Map).regionHashes as Map
Map l3 = new GroovyShell().evaluate(new File(repoRoot, 'tools/webcore-investigation/evidence/statement-l3.groovy').getText('UTF-8')) as Map
Map fixtureManifest = new JsonSlurper().parse(new File(repoRoot, 'tests/fixtures/webcore-l3/manifest.json')) as Map
File traceDir = new File(repoRoot, manifest.traceDirectory as String)
Map traces = [:]
traceDir.listFiles().findAll { it.name.endsWith('.json') }.sort { it.name }.each { File f -> traces[f.name - '.json'] = new JsonSlurper().parse(f) }
def promotion = new GroovyClassLoader(this.class.classLoader).parseClass(new File(repoRoot, 'tools/webcore-investigation/L4Promotion.groovy'))

List claimIds = (manifest.claims as List).collect { (it as Map).id }
Map gaps = manifest.gaps as Map

// ---- the contract holds today ------------------------------------------------------

check(claimIds.size() == (claimIds as Set).size() && claimIds.every { "${it}" ==~ /[a-z-]+(\.[a-z-]+)+\.v\d+/ }, "claim ids are unique and versioned ${claimIds}")
Map outcome = promotion.derive(manifest, registry, fixtureManifest, l3.captureLineage as Map, traces) as Map
check((outcome.promoted as Map).values().every { it == true }, "every claim meets its evidence contract ${(outcome.problems as Map).findAll { k, v -> v }}")
check((manifest.limits as List).every { Map l -> gaps.containsKey(l.gap) && l.negative && regionHashes.containsKey(l.source) },
    'each recorded limit names a closed gap, its nearest misreading and a hashed source region')

// ---- closed gap list -----------------------------------------------------------------

List statementTypes = (registry.constructs as Map).keySet().findAll { "${it}".startsWith('wc.statement.') }.collect { "${it}".substring('wc.statement.'.length()) }
Set expectedNotInIncrement = statementTypes.findAll { !(it in ['if', 'do']) }.collect { "statement.${it}.not-in-increment".toString() } as Set
check(expectedNotInIncrement.every { gaps.containsKey(it) } && !gaps.containsKey('statement.if.not-in-increment') && !gaps.containsKey('statement.do.not-in-increment'),
    'every statement type outside this increment has a closed gap, and if and do do not')
check(claimIds.every { gaps.containsKey("claim.${it}.not-promoted".toString()) }, 'every claim has a closed not-promoted gap')
check(gaps.every { k, v -> "${k}" ==~ /[a-z0-9.-]+/ && v instanceof String && v && !(v as String).contains('\u2014') },
    'gap ids are closed tokens and every reason is fixed plain text')

// ---- traces ---------------------------------------------------------------------------

Map fixtureStatements = (fixtureManifest.fixtures as List).collectEntries { Map f -> [((f.file as String) - '.json'): ((f.statements ?: [:]) as Map).values().sum(0)] }
traces.each { String name, Object raw ->
    Map t = raw as Map
    Map citations = (t.citations ?: [:]) as Map
    Map occurrences = (t.occurrences ?: [:]) as Map
    List problems = []
    if (t.fixture != name) problems << 'fixture name'
    if (!fixtureStatements.containsKey(name)) problems << 'fixture not committed'
    if (!((t.authored ?: '') as String).contains('Hand-authored')) problems << 'authorship statement'
    citations.each { k, c -> if (!((c as Map).docs) || !regionHashes.containsKey((c as Map).source)) problems << "citation ${k}".toString() }
    occurrences.each { String path, Object o ->
        Map om = o as Map
        if (!(path ==~ /\$(\.(s|e|ei\[\d+\]\.s|cs\[\d+\]\.s|c\[\d+\](\.c\[\d+\])*\.(ts|fs))\[\d+\])+/)) problems << "path ${path}".toString()
        if (!(om.construct instanceof String)) problems << "${path} construct".toString()
        ((om.claims ?: []) as List).each { if (!claimIds.contains(it)) problems << "${path} unknown claim ${it}".toString() }
        ((om.gaps ?: []) as List).each { if (!gaps.containsKey(it)) problems << "${path} unknown gap ${it}".toString() }
        Map cite = (om.cite ?: [:]) as Map
        if (!cite) problems << "${path} has no citations".toString()
        cite.values().each { if (!citations.containsKey(it)) problems << "${path} cites missing ${it}".toString() }
        (om.keySet() - (['construct', 'role', 'claims', 'gaps', 'cite'] as Set)).each { String field ->
            if (!cite.containsKey(field) && !(field == 'branches' && cite.containsKey('branches'))) problems << "${path} field ${field} is uncited".toString()
        }
    }
    check(problems.isEmpty() && occurrences.size() == fixtureStatements[name],
        "${name}: well formed, every field cited, and every statement occurrence traced (${occurrences.size()} of ${fixtureStatements[name]}) ${problems.take(3)}")
}

// ---- mutations: each missing contract item withdraws only its own claims -------------

def withdrawn = { Closure mutate ->
    Map m = copy(manifest) as Map
    Map r = copy(registry) as Map
    Map fm = copy(fixtureManifest) as Map
    Map tr = copy(traces) as Map
    mutate(m, r, fm, tr)
    Map out = promotion.derive(m, r, fm, l3.captureLineage as Map, tr) as Map
    (out.promoted as Map).findAll { k, v -> v != true }.keySet().collect { "${it}" } as Set
}
def claimOf = { Map m, String id -> (m.claims as List).find { (it as Map).id == id } as Map }
List conditionClaims = ['condition.followed-by.opaque-group.v1', 'condition.list.negation.v1', 'condition.list.operator-or.v1']
[
  ['a missing documentation disposition', ['statement.if.branch-order.v1'], { Map m, Map r, Map fm, Map tr -> ((claimOf(m, 'statement.if.branch-order.v1').docs as List)[0] as Map).disposition = '' }],
  ['a missing source region', ['statement.do.sequential-block.v1'], { Map m, Map r, Map fm, Map tr -> claimOf(m, 'statement.do.sequential-block.v1').sources = [] }],
  ['source drift in the condition evaluator', conditionClaims, { Map m, Map r, Map fm, Map tr -> ((r.provenance as Map).regionHashes as Map)['executor.evaluate-conditions'] = '0' * 64 }],
  ['a structure falling below L3', ['statement.do.sequential-block.v1'], { Map m, Map r, Map fm, Map tr -> ((r.constructs as Map)['wc.statement.do'] as Map).level = 'L2' }],
  ['a fixture that is not committed', ['statement.envelope.default.v1'], { Map m, Map r, Map fm, Map tr -> claimOf(m, 'statement.envelope.default.v1').fixtures = ['l3-09-missing.round-trip'] }],
  ['a fixture whose save is not committed', ['statement.do.sequential-block.v1'], { Map m, Map r, Map fm, Map tr -> fm.fixtures = (fm.fixtures as List).findAll { (it as Map).file != 'l3-04-loops.edit-save.json' } }],
  ['a trace that never reaches the claim', ['condition.followed-by.opaque-group.v1'], { Map m, Map r, Map fm, Map tr -> (((tr['l3-02-followed-by.edit-round-trip'] as Map).occurrences as Map)['$.s[0]'] as Map).claims = [] }],
  ['an edge that is not traced', ['statement.if.branch-order.v1'], { Map m, Map r, Map fm, Map tr -> ((claimOf(m, 'statement.if.branch-order.v1').edges as List)[0] as Map).path = '$.s[9]' }],
  ['a missing named negative', ['condition.list.negation.v1'], { Map m, Map r, Map fm, Map tr -> claimOf(m, 'condition.list.negation.v1').negative = '' }]
].each { List mc ->
    Set got = withdrawn(mc[2] as Closure)
    check(got == ((mc[1] as List) as Set), "mutation: ${mc[0]} withdraws exactly ${mc[1]} (got ${got})")
}

int bad = results.count { !it }
println "${results.size() - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
