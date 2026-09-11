#!/usr/bin/env groovy
//
// Projects the reviewed statement evidence manifest down to what the census walker reads to
// validate an occurrence: kinds, persistence, consumption, closed predicates, value sets, the
// validated lists, the condition discriminator and the branches that are evidence gaps. Source evidence (readBy, writtenBy,
// normalisedBy, source assertions, the inventory and exclusions) stays in the manifest.
//
// The projection is generated, never hand-edited, and tests/webcore-statement-shapes.groovy
// asserts it still agrees with the manifest and that the app carries it verbatim.
//
// Run with: groovy tools/webcore-investigation/generate-statement-shapes.groovy

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tools').isDirectory()) repoRoot = new File('..').canonicalFile

File manifestFile = new File(repoRoot, 'tools/webcore-investigation/evidence/statement-l3.groovy')
if (!manifestFile.isFile()) { println "FAIL  manifest not found at ${manifestFile}"; System.exit 1 }
Map manifest = new GroovyShell().evaluate(manifestFile.getText('UTF-8')) as Map

String lit(Object v) {
    if (v == null) return 'null'
    if (v instanceof Boolean || v instanceof Number) return v.toString()
    if (v instanceof CharSequence) return "'" + v.toString().replace('\\', '\\\\').replace("'", "\\'") + "'"
    if (v instanceof List) return '[' + (v as List).collect { lit(it) }.join(', ') + ']'
    if (v instanceof Map) {
        if ((v as Map).isEmpty()) return '[:]'
        return '[' + (v as Map).collect { k, x -> lit(k.toString()) + ': ' + lit(x) }.join(', ') + ']'
    }
    throw new IllegalArgumentException("unsupported value type ${v.getClass().name}")
}

List<String> specFields = ['key', 'kind', 'persisted', 'persistedWhen', 'outsideWhen', 'consumed', 'consumedWhen', 'values']
def project = { Map spec ->
    Map out = [:]
    specFields.each { String f -> if (spec.containsKey(f)) out[f] = spec[f] }
    out
}
def keyLines = { Map keys, String indent ->
    keys.collect { k, spec -> indent + lit(k.toString()) + ': ' + lit(project(spec as Map)) }.join(',\n')
}

StringBuilder out = new StringBuilder()
out << '// GENERATED from tools/webcore-investigation/evidence/statement-l3.groovy\n'
out << '// by tools/webcore-investigation/generate-statement-shapes.groovy. Do not hand-edit.\n'
out << '// Shape and predicate data only. The reviewed manifest keeps the source evidence.\n'
out << 'Map webcoreStatementShapes() {\n'
out << '    return [\n'
out << '        contexts: ' + lit(manifest.contexts) + ',\n'
out << '        lists: [\n'
out << (manifest.lists as Map).collect { k, v -> '            ' + lit(k.toString()) + ': ' + lit(v) }.join(',\n')
out << '\n        ],\n'
out << '        common: [\n'
out << keyLines(manifest.common as Map, '            ')
out << '\n        ],\n'
out << '        statements: [\n'
out << (manifest.statements as Map).collect { id, e ->
    Map keys = (e as Map).keys as Map
    if (keys.isEmpty()) return '            ' + lit(id.toString()) + ': [keys: [:]]'
    return '            ' + lit(id.toString()) + ': [keys: [\n' + keyLines(keys, '                ') + '\n            ]]'
}.join(',\n')
out << '\n        ],\n'
out << '        substructures: [\n'
out << (manifest.substructures as Map).collect { name, s ->
    Map sub = s as Map
    if (!sub.discriminator) {
        return '            ' + lit(name.toString()) + ': [keys: [\n' + keyLines(sub.keys as Map, '                ') + '\n            ]]'
    }
    String variants = (sub.variants as Map).collect { vn, keys ->
        '                    ' + lit(vn.toString()) + ': [\n' + keyLines(keys as Map, '                        ') + '\n                    ]'
    }.join(',\n')
    return '            ' + lit(name.toString()) + ': [\n' +
        '                discriminator: ' + lit(project(sub.discriminator as Map)) + ',\n' +
        '                variants: [\n' + variants + '\n                ]\n            ]'
}.join(',\n')
out << '\n        ],\n'
// Branches with no promoting fixture, keyed structure/key/branch, with only the gap reason.
List gapRows = (manifest.branchEvidence as List).findAll { Map r -> r.gap != null }
if (gapRows) {
    out << '        evidenceGaps: [\n'
    out << gapRows.collect { Map r -> '            ' + lit("${r.structure}/${r.key}/${r.branch}".toString()) + ': ' + lit(r.gap) }.join(',\n')
    out << '\n        ]\n'
} else {
    out << '        evidenceGaps: [:]\n'
}
out << '    ]\n'
out << '}\n'

File target = new File(repoRoot, 'tools/webcore-investigation/generated/webcore_statement_shapes.groovy')
target.setText(out.toString(), 'UTF-8')

println "wrote ${target.name}"
println "  statements: ${(manifest.statements as Map).size()}"
println "  substructures: ${(manifest.substructures as Map).size()}"
println "  bytes: ${target.length()}"
