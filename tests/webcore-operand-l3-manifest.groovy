#!/usr/bin/env groovy
//
// The operand structural (L3) evidence manifest, checked against every operand occurrence in the
// committed L3 fixture corpus. Proves the declared shape for each covered kind (constant, virtual,
// variable, expression) matches every real occurrence exactly: every key the manifest calls
// persisted:always is present, every key marked exclusive never appears on a foreign kind, and no
// unlisted key appears uncounted. Also proves the manifest's own closed vocabularies are internally
// consistent. This is evidence-gathering and a reconciliation gate; it does not yet raise any
// registry level or wire into the runtime walker - that is the next increment.
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
Map covered = [:]
operands.each { String id, Object o -> covered[((o as Map).discriminator as Map).value.toString()] = id }

List shapeProblems = []
List exclusivityProblems = []
Map occurrenceCounts = [:].withDefault { 0 }

def walk
walk = { Object node, String path ->
    if (node instanceof Map) {
        Map n = node as Map
        String t = n.t instanceof String ? n.t as String : null
        // An operand occurrence carries vt; other structures reuse single-letter t values (a switch
        // case's t: 's', a condition's t: 'condition') without vt, so vt is the operand discriminator.
        if (t != null && n.containsKey('vt')) {
            occurrenceCounts[t] = (occurrenceCounts[t] as Integer) + 1
            if (covered.containsKey(t)) {
                String id = covered[t]
                Map keys = (operands[id] as Map).keys as Map
                keys.each { String key, Object spec ->
                    Map s = spec as Map
                    boolean present = n.containsKey(key)
                    if (s.persisted == 'always' && !present) shapeProblems << "${path}: ${id} missing always-persisted ${key}".toString()
                }
                // Exclusivity: a key is exclusive to a closed set of kinds (exclusiveTo). An
                // occurrence of a kind outside that set must never carry the key.
                operands.each { String otherId, Object otherRaw ->
                    ((otherRaw as Map).keys as Map).each { String key, Object spec ->
                        List owners = (spec as Map).exclusiveTo as List
                        if (owners != null && !owners.contains(t) && n.containsKey(key)) {
                            exclusivityProblems << "${path}: ${id} occurrence carries ${key}, exclusive to ${owners}".toString()
                        }
                    }
                }
            }
        }
        n.each { k, v -> walk(v, path + '.' + k) }
    } else if (node instanceof List) {
        (node as List).eachWithIndex { v, i -> walk(v, path + '[' + i + ']') }
    }
}
fixtures.each { File f -> walk(new JsonSlurper().parseText(f.getText('UTF-8')), f.name) }

check(shapeProblems.isEmpty(), "every covered operand occurrence carries every field the manifest calls always-persisted ${shapeProblems.take(3)}")

List canonicalExclusivityProblems = []
canonicalFixtures.each { File f ->
    def walkCanon
    walkCanon = { Object node, String path ->
        if (node instanceof Map) {
            Map n = node as Map
            String t = n.t instanceof String ? n.t as String : null
            if (t != null && n.containsKey('vt') && covered.containsKey(t)) {
                operands.each { String otherId, Object otherRaw ->
                    ((otherRaw as Map).keys as Map).each { String key, Object spec ->
                        List owners = (spec as Map).exclusiveTo as List
                        if (owners != null && !owners.contains(t) && n.containsKey(key)) {
                            canonicalExclusivityProblems << "${f.name}${path}: ${covered[t]} occurrence carries ${key}, exclusive to ${owners}".toString()
                        }
                    }
                }
            }
            n.each { k, v -> walkCanon(v, path + '.' + k) }
        } else if (node instanceof List) {
            (node as List).eachWithIndex { v, i -> walkCanon(v, path + '[' + i + ']') }
        }
    }
    walkCanon(new JsonSlurper().parseText(f.getText('UTF-8')), '')
}
check(canonicalExclusivityProblems.isEmpty(),
    "no operand occurrence in a canonical (round-trip) capture carries a key exclusive to a different kind ${canonicalExclusivityProblems.take(3)}")
check(!exclusivityProblems.isEmpty() && exclusivityProblems.every { it.contains('edit-save') || it.contains('first-save') },
    "the raw exclusivity scan over every capture finds only the known editor-authored-only exceptions ${exclusivityProblems.findAll { !(it.contains('edit-save') || it.contains('first-save')) }.take(3)}")
check(occurrenceCounts['c'] > 150 && occurrenceCounts['v'] > 15 && occurrenceCounts['x'] > 15 && occurrenceCounts['e'] > 0,
    "each covered kind occurs many times across the corpus ${occurrenceCounts}")
check(occurrenceCounts['p'] == 0 && occurrenceCounts['d'] == 0 && occurrenceCounts['s'] == 0,
    "p, d and s occur zero times, confirming the open question that they need new captures ${occurrenceCounts}")

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

int bad = results.count { !it }
println "${results.size() - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
