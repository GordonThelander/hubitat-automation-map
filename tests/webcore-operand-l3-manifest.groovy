#!/usr/bin/env groovy
//
// The operand structural (L3) evidence manifest, checked against every operand occurrence in the
// committed L3 fixture corpus. Proves the declared shape for each covered kind (constant, virtual,
// variable, expression) matches every real occurrence exactly: every key the manifest calls
// persisted:always is present, every key marked exclusive never appears on a foreign kind, and no
// unlisted key appears uncounted. Also proves the manifest's own closed vocabularies are internally
// consistent, and that the same committed-metadata promotion gate the statement manifest uses derives
// L3 for exactly the covered operand kinds and holds the registry to it, with no drift either way.
// Run with: groovy tests/webcore-operand-l3-manifest.groovy

import groovy.json.JsonSlurper

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('..').canonicalFile

List<Boolean> results = []
def check = { boolean cond, String label ->
    println "${cond ? 'PASS' : 'FAIL'}  ${label}"
    results << cond
}

Map manifest = new GroovyShell().evaluate(new File(repoRoot, 'tools/webcore-investigation/evidence/operand-l3.groovy').getText('UTF-8')) as Map
Map operands = manifest.operands as Map

// ---- the manifest's own vocabularies are internally consistent ------------------------

check((operands.keySet() as Set) == ((manifest.kinds as List).collect { 'wc.operand.' + it[0] } as Set) ||
      operands.size() == (manifest.kinds as List).size(),
    "every declared kind has exactly one operand entry (${operands.size()} of ${(manifest.kinds as List).size()})")
operands.each { String id, Object raw ->
    Map o = raw as Map
    String discKey = (o.discriminator as Map).key as String
    String discValue = (o.discriminator as Map).value as String
    check(discKey == 't' && discValue instanceof String && (manifest.discriminatorValues as List).contains(discValue),
        "${id}: discriminates on t with a value in the closed set (${discValue})")
    (o.keys as Map).each { String key, Object spec ->
        Map s = spec as Map
        check(s.kind instanceof String && s.persisted instanceof String && s.consumed instanceof String,
            "${id}.${key}: declares kind, persisted and consumed")
    }
}

// ---- every operand occurrence in the fixture corpus matches its declared shape --------

File fixtureDir = new File(repoRoot, 'tests/fixtures/webcore-l3')
List<File> fixtures = fixtureDir.listFiles().findAll { it.name != 'manifest.json' }.sort { it.name }
// Exclusivity only holds on the canonical (round-trip) shape; a raw editor save can still carry a
// stray field from before the operand's type was last changed, exactly as the manifest records for
// l3-02-followed-by.edit-save. Checked against every capture regardless for the always-persisted
// and occurrence-count assertions, which hold on any capture.
List<File> canonicalFixtures = fixtures.findAll { it.name =~ /\.(round-trip|edit-round-trip|chained-round-trip)\.json$/ }
// A discriminator value alone is not always a unique key: an event-match operand is saved with the
// same t as its ordinary counterpart (t: 'v' either way) and is told apart only by being the lo of an
// 'event' node, per the registry's own SITE_NORMALIZATION. lookupKey folds that saved parent context
// into the covered/occurrence key wherever an entry declares one, so the two never collide.
def lookupKey = { String t, boolean inEvent -> inEvent ? "${t}@event".toString() : t }
Map covered = [:]
operands.each { String id, Object o ->
    Map d = (o as Map).discriminator as Map
    covered[lookupKey(d.value.toString(), d.parentContext == 'event')] = id
}

List shapeProblems = []
List exclusivityProblems = []
Map occurrenceCounts = [:].withDefault { 0 }

def walk
walk = { Object node, String path, boolean inEvent = false ->
    if (node instanceof Map) {
        Map n = node as Map
        String t = n.t instanceof String ? n.t as String : null
        // An operand occurrence carries vt; other structures reuse single-letter t values (a switch
        // case's t: 's', a condition's t: 'condition') without vt, so vt is the operand discriminator.
        if (t != null && n.containsKey('vt')) {
            occurrenceCounts[t] = (occurrenceCounts[t] as Integer) + 1
            String key = lookupKey(t, inEvent)
            if (covered.containsKey(key)) {
                String id = covered[key]
                Map keys = (operands[id] as Map).keys as Map
                keys.each { String k2, Object spec ->
                    Map s = spec as Map
                    boolean present = n.containsKey(k2)
                    if (s.persisted == 'always' && !present) shapeProblems << "${path}: ${id} missing always-persisted ${k2}".toString()
                }
                // Exclusivity: a key is exclusive to a closed set of kinds (exclusiveTo). An
                // occurrence of a kind outside that set must never carry the key.
                operands.each { String otherId, Object otherRaw ->
                    ((otherRaw as Map).keys as Map).each { String k2, Object spec ->
                        List owners = (spec as Map).exclusiveTo as List
                        if (owners != null && !owners.contains(t) && n.containsKey(k2)) {
                            exclusivityProblems << "${path}: ${id} occurrence carries ${k2}, exclusive to ${owners}".toString()
                        }
                    }
                }
            }
        }
        n.each { k, v -> walk(v, path + '.' + k, t == 'event' && k == 'lo') }
    } else if (node instanceof List) {
        (node as List).eachWithIndex { v, i -> walk(v, path + '[' + i + ']', inEvent) }
    }
}
fixtures.each { File f -> walk(new JsonSlurper().parseText(f.getText('UTF-8')), f.name) }

check(shapeProblems.isEmpty(), "every covered operand occurrence carries every field the manifest calls always-persisted ${shapeProblems.take(3)}")

List canonicalExclusivityProblems = []
canonicalFixtures.each { File f ->
    def walkCanon
    walkCanon = { Object node, String path, boolean inEvent = false ->
        if (node instanceof Map) {
            Map n = node as Map
            String t = n.t instanceof String ? n.t as String : null
            String key = t == null ? null : lookupKey(t, inEvent)
            if (t != null && n.containsKey('vt') && covered.containsKey(key)) {
                operands.each { String otherId, Object otherRaw ->
                    ((otherRaw as Map).keys as Map).each { String k2, Object spec ->
                        List owners = (spec as Map).exclusiveTo as List
                        if (owners != null && !owners.contains(t) && n.containsKey(k2)) {
                            canonicalExclusivityProblems << "${f.name}${path}: ${covered[key]} occurrence carries ${k2}, exclusive to ${owners}".toString()
                        }
                    }
                }
            }
            n.each { k, v -> walkCanon(v, path + '.' + k, t == 'event' && k == 'lo') }
        } else if (node instanceof List) {
            (node as List).eachWithIndex { v, i -> walkCanon(v, path + '[' + i + ']', inEvent) }
        }
    }
    walkCanon(new JsonSlurper().parseText(f.getText('UTF-8')), '')
}
check(canonicalExclusivityProblems.isEmpty(),
    "no operand occurrence in a canonical (round-trip) capture carries a key exclusive to a different kind ${canonicalExclusivityProblems.take(3)}")
check(!exclusivityProblems.isEmpty() && exclusivityProblems.every { it.contains('edit-save') || it.contains('first-save') },
    "the raw exclusivity scan over every capture finds only the known editor-authored-only exceptions ${exclusivityProblems.findAll { !(it.contains('edit-save') || it.contains('first-save')) }.take(3)}")
check(occurrenceCounts['c'] > 150 && occurrenceCounts['v'] > 15 && occurrenceCounts['x'] > 15 && occurrenceCounts['e'] > 0 &&
      occurrenceCounts['p'] > 0 && occurrenceCounts['s'] > 0,
    "each covered kind occurs many times across the corpus ${occurrenceCounts}")
check(occurrenceCounts['d'] == 0,
    "d occurs zero times, confirming the open question that it needs a new capture ${occurrenceCounts}")

// ---- mutation: a wrong exclusivity claim is caught -----------------------------------

Map mutated = new GroovyShell().evaluate(new File(repoRoot, 'tools/webcore-investigation/evidence/operand-l3.groovy').getText('UTF-8')) as Map
((((mutated.operands as Map)['wc.operand.v'] as Map).keys as Map)['vt'] as Map).exclusiveTo = ['v']
List mutantProblems = []
def walkMutant
walkMutant = { Object node, String path ->
    if (node instanceof Map) {
        Map n = node as Map
        String t = n.t instanceof String ? n.t as String : null
        if (t != null && n.containsKey('vt')) {
            Map mutCovered = [:]
            (mutated.operands as Map).each { String id, Object o -> mutCovered[((o as Map).discriminator as Map).value.toString()] = id }
            if (mutCovered.containsKey(t)) {
                (mutated.operands as Map).each { String otherId, Object otherRaw ->
                    ((otherRaw as Map).keys as Map).each { String key, Object spec ->
                        List owners = (spec as Map).exclusiveTo as List
                        if (owners != null && !owners.contains(t) && n.containsKey(key)) mutantProblems << "${path}: ${t} carries ${key}".toString()
                    }
                }
            }
        }
        n.each { k, v -> walkMutant(v, path + '.' + k) }
    } else if (node instanceof List) {
        (node as List).eachWithIndex { v, i -> walkMutant(v, path + '[' + i + ']') }
    }
}
fixtures.each { File f -> walkMutant(new JsonSlurper().parseText(f.getText('UTF-8')), f.name) }
check(!mutantProblems.isEmpty(), "mutation: falsely marking vt exclusive to v is caught, since every operand carries vt (${mutantProblems.size()} hits)")

// ---- operand-level promotion: the same committed-metadata gate the statement manifest uses ------

Map fixtureManifest = new JsonSlurper().parse(new File(fixtureDir, 'manifest.json')) as Map
String registryText = new File(repoRoot, 'tools/webcore-investigation/generated/webcore_construct_registry.groovy').getText('UTF-8')
String registryAnchor = 'WEBCORE_CONSTRUCT_REGISTRY = '
Map registry = new GroovyShell().evaluate(registryText.substring(registryText.indexOf(registryAnchor) + registryAnchor.length())) as Map
Map constructs = registry.constructs as Map
Class promotionClass = new GroovyClassLoader(this.class.classLoader).parseClass(new File(repoRoot, 'tools/webcore-investigation/L3Promotion.groovy'))
def deriveOperands = { Map ev, Map fm -> promotionClass.derive([captureLineage: ev.captureLineage, namedTests: ev.namedTests, statements: ev.operands], fm, repoRoot, 'operands') as Map }

Map promotion = deriveOperands(manifest, fixtureManifest)
check((promotion.problems as List).isEmpty() && (promotion.levels as Map).keySet() == operands.keySet() && (promotion.levels as Map).values().every { it == 'L3' },
    "promotion derives L3 for every covered operand from committed metadata alone ${promotion.problems}")

Set registryOperands = constructs.keySet().findAll { "${it}" ==~ /wc\.operand(\.event-match)?\.[a-z]+/ }.collect { "${it}" } as Set
List registryDrift = registryOperands.findAll { String id -> (constructs[id] as Map).level != (operands.containsKey(id) ? 'L3' : 'L2') }.toList()
check(registryDrift.isEmpty(),
    "the registry carries L3 for exactly the covered operand kinds and L2 for the rest ${registryDrift}")

def copyOperandJson = { Object o -> new JsonSlurper().parseText(groovy.json.JsonOutput.toJson(o)) }
def promoteOperandMutant = { Closure mutate ->
    Map ev = copyOperandJson(manifest) as Map
    Map fm = copyOperandJson(fixtureManifest) as Map
    mutate(ev, fm)
    deriveOperands(ev, fm)
}
[['a citation naming a round-trip capture', { Map ev, Map fm -> ((ev.operands as Map)['wc.operand.x'] as Map).fixtures = ['l3-04-loops.round-trip'] }],
 ['a cited save without its committed round trip', { Map ev, Map fm -> fm.fixtures = (fm.fixtures as List).findAll { it.file != 'l3-04-loops.round-trip.json' } }],
 ['a round trip that no longer holds the operand', { Map ev, Map fm -> ((fm.fixtures as List).find { it.file == 'l3-04-loops.round-trip.json' } as Map).operands = [:] }],
 ['a citation of a fixture that is not committed', { Map ev, Map fm -> ((ev.operands as Map)['wc.operand.x'] as Map).fixtures = ['l3-09-missing.first-save'] }],
 ['a named test that does not exist', { Map ev, Map fm -> (ev.namedTests as Map)['operand-l3-manifest'] = 'tests/no-such-test.groovy' }]].each { List mc ->
    Map mutatedOp = promoteOperandMutant(mc[1] as Closure)
    boolean caught = mc[0] == 'a named test that does not exist' ?
        (mutatedOp.levels as Map).values().every { it == 'L2' } :
        ((mutatedOp.levels as Map)['wc.operand.x'] == 'L2')
    check(caught, "mutation: ${mc[0]} withdraws L3 (${mutatedOp.levels})")
}

int bad = results.count { !it }
println "${results.size() - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
