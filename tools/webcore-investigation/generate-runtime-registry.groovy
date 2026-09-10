#!/usr/bin/env groovy
//
// Projects the reviewed construct registry down to what the runtime actually
// reads: the construct identity, its evidence level, and the provenance the
// endpoint reports. Everything else in the reviewed registry is review evidence
// (sourceRef, consumedBy, dispatchSite, sharedBranchBySite, declared,
// implemented, canonicalTarget, availability) and has no runtime consumer, so
// shipping 91KB of it inside a Hubitat app would buy nothing.
//
// The projection is generated, never hand-edited, and
// tests/webcore-runtime-registry.groovy asserts it still agrees with the
// reviewed registry and that the app carries it verbatim.
//
// Run with: groovy tools/webcore-investigation/generate-runtime-registry.groovy

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tools').isDirectory()) repoRoot = new File('..').canonicalFile

File full = new File(repoRoot, 'tools/webcore-investigation/generated/webcore_construct_registry.groovy')
if (!full.isFile()) { println "FAIL  reviewed registry not found at ${full}"; System.exit 1 }

String text = full.getText('UTF-8')
String anchor = 'WEBCORE_CONSTRUCT_REGISTRY = '
int start = text.indexOf(anchor)
if (start < 0) { println 'FAIL  reviewed registry constant not found'; System.exit 1 }
Map registry = new GroovyShell().evaluate(text.substring(start + anchor.length())) as Map

Map provenance = registry.provenance as Map
Map constructs = registry.constructs as Map

List<String> missingLevel = constructs.findAll { Object id, Object entry ->
    !(entry instanceof Map) || !((entry as Map).level instanceof String)
}.keySet().collect { "${it}" }
if (missingLevel) {
    println "FAIL  ${missingLevel.size()} construct(s) carry no evidence level, first: ${missingLevel[0]}"
    System.exit 1
}

StringBuilder out = new StringBuilder()
out << "// GENERATED from tools/webcore-investigation/generated/webcore_construct_registry.groovy\n"
out << "// by tools/webcore-investigation/generate-runtime-registry.groovy. Do not hand-edit.\n"
out << "// Projection only: construct identity, evidence level and provenance. The reviewed\n"
out << "// registry keeps the source evidence and stays out of the shipped app.\n"
out << "Map webcoreCensusRegistry() {\n"
out << "    return [provenance: [commit: '${provenance.commit}', registryVersion: '${provenance.registryVersion}'],\n"
out << "            constructs: [\n"
constructs.keySet().sort().each { Object id ->
    out << "        '${id}': [level: '${(constructs[id] as Map).level}'],\n"
}
out << "    ]]\n"
out << "}\n"

File target = new File(repoRoot, 'tools/webcore-investigation/generated/webcore_runtime_registry.groovy')
target.setText(out.toString(), 'UTF-8')

println "wrote ${target.name}"
println "  constructs: ${constructs.size()}"
println "  bytes: ${target.length()}"
println "  commit: ${provenance.commit}"
println "  registryVersion: ${provenance.registryVersion}"
