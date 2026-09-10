#!/usr/bin/env groovy
//
// The checked-in static registry must exactly match what the generator
// deterministically produces from the pinned source, or the reviewed artefact
// and the tool have silently diverged.
//
// Runs only when a pinned source checkout is present. Skipping is reported
// explicitly rather than passing quietly, because a test that says nothing on
// absence is indistinguishable from one that verified something.

File checkedIn = new File('tools/webcore-investigation/generated/webcore_construct_registry.groovy')
File srcRoot = new File('tmp/webcore-source')

int passed = 0
void check(boolean cond, String label) {
    if (!cond) { println "FAIL  ${label}"; System.exit 1 }
    println "PASS  ${label}"
}

check(checkedIn.isFile(), 'static registry is checked in')
passed++

String text = checkedIn.getText('UTF-8')
check(text.contains("@Field static final Map WEBCORE_CONSTRUCT_REGISTRY"), 'registry declares the expected constant')
check(text.contains("0a37eee2537accd706aaaeeed5a7b4bb0c82646e"), 'registry records the pinned commit')
check(text.contains("regionHashes:"), 'registry carries per-region evidence hashes')
check(text.contains("'wc.statement.if'"), 'registry uses the wc. namespace')
check(text.contains("canonicalTarget:'wc.function.boolean'"), 'reviewed aliases carry canonicalTarget')
check(text.contains("'wc.device-selector.variable-device-map'") && text.contains("status:'unreachable'"),
    'unreachable device-map form is present and marked unreachable')
passed += 6

int ids = (text =~ /(?m)^\s+'wc\./).count
check(ids == 316, "registry holds the reviewed population (${ids} of 316)")
passed++

if (!srcRoot.isDirectory()) {
    println "SKIP  candidate comparison - no pinned source checkout at ${srcRoot}"
    println "${passed} passed, 0 failed, 1 skipped"
    return
}

// groovy is a .bat on Windows and is not directly executable from
// ProcessBuilder, so try the batch launcher first and fall back.
String fresh = null
int rc = -1
for (String launcher in ['groovy.bat', 'groovy']) {
    try {
        // JAVA_HOME in the inherited environment is invalid on this machine
        // (a known local issue), while the JVM running this test plainly has a
        // working one. Derive it rather than trusting the environment.
        List<String> env = System.getenv().collect { k, v -> "${k}=${v}" as String }
            .findAll { !it.startsWith('JAVA_HOME=') }
        env << ("JAVA_HOME=" + new File(System.getProperty('java.home')).canonicalPath)
        def proc = [launcher, 'tools/webcore-investigation/generate-construct-registry.groovy',
                    srcRoot.path, '--candidate'].execute(env as String[], new File('.'))
        StringWriter out = new StringWriter()
        proc.consumeProcessOutput(out, new StringWriter())
        proc.waitFor()
        rc = proc.exitValue(); fresh = out.toString()
        break
    } catch (IOException ignored) { }
}
if (fresh == null) {
    println "SKIP  candidate comparison - no runnable groovy launcher found"
    println "${passed} passed, 0 failed, 1 skipped"
    return
}
check(rc == 0, 'generator runs clean against the pinned source')
check(fresh == text, 'checked-in registry is byte-identical to the freshly generated candidate')
passed += 2

println "${passed} passed, 0 failed"
