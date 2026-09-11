#!/usr/bin/env groovy
//
// Projects the L4 semantic evidence manifest down to what the app reads at runtime: which claims are
// promoted and the closed gap ids with their fixed reasons. Promotion comes from L4Promotion over
// committed data only, so the projection never depends on whether a test ran.
//
// The projection is generated, never hand-edited, and tests/webcore-l4-normalizer.groovy asserts it
// regenerates byte for byte and that the app carries it verbatim.
//
// Run with: groovy tools/webcore-investigation/generate-semantic-evidence.groovy

import groovy.json.JsonSlurper

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tools').isDirectory()) repoRoot = new File('..').canonicalFile

Map manifest = new GroovyShell().evaluate(new File(repoRoot, 'tools/webcore-investigation/evidence/semantic-l4.groovy').getText('UTF-8')) as Map
String registryText = new File(repoRoot, 'tools/webcore-investigation/generated/webcore_construct_registry.groovy').getText('UTF-8')
String anchor = 'WEBCORE_CONSTRUCT_REGISTRY = '
Map registry = new GroovyShell().evaluate(registryText.substring(registryText.indexOf(anchor) + anchor.length())) as Map
Map l3 = new GroovyShell().evaluate(new File(repoRoot, 'tools/webcore-investigation/evidence/statement-l3.groovy').getText('UTF-8')) as Map
Map fixtureManifest = new JsonSlurper().parse(new File(repoRoot, 'tests/fixtures/webcore-l3/manifest.json')) as Map
Map traces = [:]
new File(repoRoot, manifest.traceDirectory as String).listFiles().findAll { it.name.endsWith('.json') }.sort { it.name }.each { File f ->
    traces[f.name - '.json'] = new JsonSlurper().parse(f)
}
def promotion = new GroovyClassLoader(this.class.classLoader).parseClass(new File(repoRoot, 'tools/webcore-investigation/L4Promotion.groovy'))
Map outcome = promotion.derive(manifest, registry, fixtureManifest, l3.captureLineage as Map, traces) as Map

String q(Object v) { return "'" + v.toString().replace('\\', '\\\\').replace("'", "\\'") + "'" }

StringBuilder out = new StringBuilder()
out << '// GENERATED from tools/webcore-investigation/evidence/semantic-l4.groovy\n'
out << '// by tools/webcore-investigation/generate-semantic-evidence.groovy. Do not hand-edit.\n'
out << '// Promotion outcome and closed gap reasons only. The manifest keeps the evidence.\n'
out << 'Map webcoreSemanticEvidence() {\n'
out << '    return [\n'
out << '        claims: [\n'
out << (manifest.claims as List).collect { Object c -> '            ' + q((c as Map).id) + ': ' + ((outcome.promoted as Map)[(c as Map).id] == true) }.join(',\n')
out << '\n        ],\n'
out << '        gaps: [\n'
out << (manifest.gaps as Map).collect { k, v -> '            ' + q(k) + ': ' + q(v) }.join(',\n')
out << '\n        ]\n'
out << '    ]\n'
out << '}\n'

File target = new File(repoRoot, 'tools/webcore-investigation/generated/webcore_semantic_evidence.groovy')
target.setText(out.toString(), 'UTF-8')
println "wrote ${target.name}"
println "  promoted: ${(outcome.promoted as Map).findAll { k, v -> v }.size()} of ${(manifest.claims as List).size()}"
(outcome.problems as Map).findAll { k, v -> v }.each { k, v -> println "  not promoted ${k}: ${v}" }
