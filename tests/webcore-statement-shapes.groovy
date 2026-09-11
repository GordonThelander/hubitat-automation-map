#!/usr/bin/env groovy
//
// The census walker validates statement occurrences against a generated projection of the
// reviewed evidence manifest. This asserts the projection carries exactly the manifest's
// shape and predicate data and no source evidence, regenerates byte for byte, and is in the
// app verbatim.
// Run with: groovy tests/webcore-statement-shapes.groovy

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('..').canonicalFile

List<Boolean> results = []
def check = { boolean cond, String label ->
    println "${cond ? 'PASS' : 'FAIL'}  ${label}"
    results << cond
}

File manifestFile = new File(repoRoot, 'tools/webcore-investigation/evidence/statement-l3.groovy')
File shapesFile = new File(repoRoot, 'tools/webcore-investigation/generated/webcore_statement_shapes.groovy')
File appFile = new File(repoRoot, 'apps/automation_map.groovy')
check(shapesFile.isFile(), 'the statement shape projection is checked in')

Map manifest = new GroovyShell().evaluate(manifestFile.getText('UTF-8')) as Map
String shapesText = shapesFile.getText('UTF-8')
Map shapes = new GroovyClassLoader(this.class.classLoader)
        .parseClass("class ShapesUnderTest {\n" + shapesText + "\n}").newInstance().webcoreStatementShapes() as Map

List fields = ['key', 'kind', 'persisted', 'persistedWhen', 'outsideWhen', 'consumed', 'consumedWhen', 'values']
def project = { Map spec -> spec.findAll { k, v -> k in fields } }
def projectKeys = { Map keys -> keys.collectEntries { k, v -> [(k): project(v as Map)] } }
Map expected = [
    contexts: manifest.contexts,
    lists: manifest.lists,
    common: projectKeys(manifest.common as Map),
    statements: (manifest.statements as Map).collectEntries { id, e -> [(id): [keys: projectKeys((e as Map).keys as Map)]] },
    substructures: (manifest.substructures as Map).collectEntries { name, s ->
        Map sub = s as Map
        [(name): sub.discriminator
            ? [discriminator: project(sub.discriminator as Map),
               variants: (sub.variants as Map).collectEntries { vn, keys -> [(vn): projectKeys(keys as Map)] }]
            : [keys: projectKeys(sub.keys as Map)]]
    }
]
check(shapes == expected, 'the projection carries exactly the manifest shape and predicate data')
check(shapes.keySet() == (['contexts', 'lists', 'common', 'statements', 'substructures'] as Set),
    'the projection carries nothing beyond shapes, lists and contexts')

List evidenceWords = ['readBy', 'writtenBy', 'normalisedBy', 'sourceAssertions', 'inventory', 'exclusions',
                      'supports', 'fixtures', 'provenance', 'executor.', 'editor.']
List leaked = evidenceWords.findAll { shapesText.contains(it) }
check(leaked.isEmpty(), "no source evidence or region name reaches the projection ${leaked}")

// Same launcher handling as the runtime registry test: groovy is a .bat on Windows, and
// JAVA_HOME in the inherited environment is not trusted.
String before = shapesText
int rc = -1
boolean ran = false
for (String launcher in ['groovy.bat', 'groovy']) {
    try {
        List<String> env = System.getenv().collect { k, v -> "${k}=${v}" as String }.findAll { !it.startsWith('JAVA_HOME=') }
        env << ("JAVA_HOME=" + new File(System.getProperty('java.home')).canonicalPath)
        def proc = [launcher, 'tools/webcore-investigation/generate-statement-shapes.groovy'].execute(env as String[], repoRoot)
        proc.consumeProcessOutput(new StringWriter(), new StringWriter())
        proc.waitFor()
        rc = proc.exitValue()
        ran = true
        break
    } catch (IOException ignored) { }
}
if (!ran) {
    println 'SKIP  regeneration comparison - no runnable groovy launcher found'
} else {
    check(rc == 0, 'generator exits cleanly')
    check(before == shapesFile.getText('UTF-8'), 'checked-in projection is byte-identical to a fresh regeneration')
}

String appText = appFile.getText('UTF-8')
String beginMarker = '// --- webcore statement shapes: begin ---'
String endMarker = '// --- webcore statement shapes: end ---'
int begin = appText.indexOf(beginMarker)
int end = appText.indexOf(endMarker)
check(begin >= 0 && end > begin, 'app source carries the statement shapes block')
if (begin >= 0 && end > begin) {
    String inApp = appText.substring(begin + beginMarker.length(), end)
    check(inApp.replace('\r\n', '\n').trim() == shapesText.replace('\r\n', '\n').trim(), 'app carries the generated projection verbatim')
}

int bad = results.count { !it }
println "${results.size() - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
