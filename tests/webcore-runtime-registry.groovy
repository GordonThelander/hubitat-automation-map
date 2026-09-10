#!/usr/bin/env groovy
//
// The app ships a projection of the reviewed construct registry, not the
// registry itself. This asserts the projection is genuinely derived: same IDs,
// same evidence levels, same provenance, regenerable byte for byte, and present
// in the app source verbatim.
// Run with: groovy tests/webcore-runtime-registry.groovy

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('..').canonicalFile

List<Boolean> results = []
def check = { boolean cond, String label ->
    println "${cond ? 'PASS' : 'FAIL'}  ${label}"
    results << cond
}

File fullFile = new File(repoRoot, 'tools/webcore-investigation/generated/webcore_construct_registry.groovy')
File projFile = new File(repoRoot, 'tools/webcore-investigation/generated/webcore_runtime_registry.groovy')
File appFile = new File(repoRoot, 'apps/automation_map.groovy')

check(fullFile.isFile(), 'reviewed registry is checked in')
check(projFile.isFile(), 'runtime projection is checked in')

String fullText = fullFile.getText('UTF-8')
String anchor = 'WEBCORE_CONSTRUCT_REGISTRY = '
Map full = new GroovyShell().evaluate(fullText.substring(fullText.indexOf(anchor) + anchor.length())) as Map
Map fullConstructs = full.constructs as Map

String projText = projFile.getText('UTF-8')
def projClass = new GroovyClassLoader(this.class.classLoader)
        .parseClass("class ProjectionUnderTest {\n" + projText + "\n}")
Map projection = projClass.newInstance().webcoreCensusRegistry() as Map
Map projConstructs = projection.constructs as Map

check(projConstructs.keySet() == fullConstructs.keySet(),
    "projection carries exactly the reviewed IDs (${projConstructs.size()} of ${fullConstructs.size()})")

List<String> levelMismatch = projConstructs.keySet().findAll { Object id ->
    (projConstructs[id] as Map).level != (fullConstructs[id] as Map).level
}.collect { "${it}" }
check(levelMismatch.isEmpty(),
    "every projected evidence level matches the reviewed registry${levelMismatch ? ' (first: ' + levelMismatch[0] + ')' : ''}")

Map fullProv = full.provenance as Map
Map projProv = projection.provenance as Map
check(projProv.commit == fullProv.commit, 'projection carries the pinned commit')
check(projProv.registryVersion == fullProv.registryVersion, 'projection carries the registry version')
check(projProv.commit == '0a37eee2537accd706aaaeeed5a7b4bb0c82646e', 'the pinned commit is unchanged')

// Nothing but identity, level and provenance may reach the app. Source evidence
// belongs in the reviewed registry, which is not shipped.
List<String> extraFields = projConstructs.keySet().findAll { Object id ->
    (projConstructs[id] as Map).keySet() != (['level'] as Set)
}.collect { "${it}" }
check(extraFields.isEmpty(),
    "projected entries carry the level and nothing else${extraFields ? ' (first: ' + extraFields[0] + ')' : ''}")
check(projProv.keySet() == (['commit', 'registryVersion'] as Set),
    'projected provenance carries the commit and version and nothing else')

// Regeneration is deterministic and the checked-in file is what the generator
// currently produces, so the projection cannot drift by hand-editing.
// groovy is a .bat on Windows and is not directly executable from
// ProcessBuilder, so try the batch launcher first and fall back. JAVA_HOME in
// the inherited environment is invalid on this machine, while the JVM running
// this test plainly has a working one, so derive it rather than trust it.
String before = projFile.getText('UTF-8')
int rc = -1
boolean ran = false
for (String launcher in ['groovy.bat', 'groovy']) {
    try {
        List<String> env = System.getenv().collect { k, v -> "${k}=${v}" as String }
            .findAll { !it.startsWith('JAVA_HOME=') }
        env << ("JAVA_HOME=" + new File(System.getProperty('java.home')).canonicalPath)
        def proc = [launcher, 'tools/webcore-investigation/generate-runtime-registry.groovy']
                .execute(env as String[], repoRoot)
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
    check(before == projFile.getText('UTF-8'),
        'checked-in projection is byte-identical to a fresh regeneration')
}

String appText = appFile.getText('UTF-8')
String beginMarker = '// --- webcore runtime registry: begin ---'
String endMarker = '// --- webcore runtime registry: end ---'
int begin = appText.indexOf(beginMarker)
int end = appText.indexOf(endMarker)
check(begin >= 0 && end > begin, 'app source carries the runtime registry block')
String inApp = appText.substring(begin + beginMarker.length(), end)
check(inApp.replace('\r\n', '\n').trim() == projText.replace('\r\n', '\n').trim(),
    'app carries the generated projection verbatim')
check(!appText.contains('sharedBranchBySite') && !appText.contains('dispatchSite'),
    'no reviewed-registry source evidence reached the app')

int bad = results.count { !it }
println "${results.size() - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
