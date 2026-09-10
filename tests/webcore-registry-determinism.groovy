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

// Every consumer claim must have evidence behind it. Folding the statement
// consumer sites previously kept consumedBy and dropped the site references
// that supported it, leaving two of three claims unresolvable.
String everyLine = text.readLines().find { it.contains("'wc.statement.every'") }
check(everyLine != null, 'wc.statement.every is present')
['executeStatement', 'subscribeAll', 'subscribeAll.timer'].each { String c ->
    check(everyLine.contains("'" + c + "'"), "wc.statement.every records consumer ${c}")
}
['executor.statement-dispatch', 'statement.subscribe.timer-type', 'statement.subscribe.type'].each { String r ->
    check(everyLine.contains("'" + r + "'"), "wc.statement.every references evidence ${r}")
}
passed += 7

int ids = (text =~ /(?m)^\s+'wc\./).count
check(ids == 279, "registry holds the reviewed population (${ids} of 279)")
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

// Negative tests for region boundaries (Codex 539). The previous fixed-window
// hashing could return the SHA-256 of an empty string when an anchor was
// missing, producing a stable hash for no evidence at all. These prove the
// generator now fails instead.
// UUID-suffixed so concurrent runs cannot collide, and only this exact
// directory is ever deleted.
File tmpRoot = new File(System.getProperty('java.io.tmpdir'),
    'wc-region-neg-' + UUID.randomUUID().toString())
void rmrf(File f) { if (f.isDirectory()) f.listFiles().each { rmrf(it) }; f.delete() }

Closure runGen = { File root ->
    List<String> env = System.getenv().collect { k, v -> "${k}=${v}" as String }
        .findAll { !it.startsWith('JAVA_HOME=') }
    env << ("JAVA_HOME=" + new File(System.getProperty('java.home')).canonicalPath)
    def proc = ['groovy.bat', 'tools/webcore-investigation/generate-construct-registry.groovy',
                root.path, '--emit'].execute(env as String[], new File('.'))
    StringWriter o = new StringWriter(); StringWriter e = new StringWriter()
    proc.consumeProcessOutput(o, e); proc.waitFor()
    return [rc: proc.exitValue(), out: o.toString() + e.toString()]
}

rmrf(tmpRoot)
tmpRoot.mkdirs()
// Registered now so any failing check() below still cleans up. Previously
// cleanup ran only on the successful path and a failure left a full copy of the
// checkout in the temp directory.
Runtime.runtime.addShutdownHook(new Thread({ rmrf(tmpRoot) } as Runnable))
Closure copyTree
copyTree = { File src, File dst ->
    dst.mkdirs()
    src.listFiles().each { File f -> f.isDirectory() ? copyTree(f, new File(dst, f.name)) : (new File(dst, f.name).bytes = f.bytes) }
}
copyTree(srcRoot, tmpRoot)

// Missing anchor: remove the expandDeviceList definition entirely.
File pistonCopy = new File(tmpRoot, 'smartapps/ady624/webcore-piston.src/webcore-piston.groovy')
String orig = pistonCopy.getText('UTF-8')
pistonCopy.setText(orig.replace('private List<String> expandDeviceList(', 'private List<String> REMOVED_expandDeviceList('), 'UTF-8')
def missing = runGen(tmpRoot)
check(missing.rc != 0, 'missing region anchor fails the generator')
check(missing.out.contains('anchor not found'), 'missing anchor is reported as such, not hashed as empty')
passed += 2

// Truncated region: unbalance the braces of a named region.
pistonCopy.setText(orig.replace('private List<String> expandDeviceList(Map r9,List<String> devs,Boolean localVarsOnly=false){',
                                'private List<String> expandDeviceList(Map r9,List<String> devs,Boolean localVarsOnly=false){ /* unbalanced'), 'UTF-8')
def trunc = runGen(tmpRoot)
check(trunc.rc != 0, 'unbalanced region boundary fails the generator')
passed++
rmrf(tmpRoot)

// C0 control characters make a source file read as binary to grep and git.
// The generator briefly contained literal NUL and SOH separators, which is the
// encoding class of problem this project has hit before.
Closure noControlChars = { File f, String label ->
    String t = f.getText('UTF-8')
    List<Integer> bad = []
    t.each { String ch ->
        int c = ch.charAt(0) as int
        if (c < 32 && c != 9 && c != 10 && c != 13) bad << c
    }
    check(bad.isEmpty(), "${label} contains no C0 control characters${bad ? ' (found ' + bad.unique() + ')' : ''}")
}
noControlChars(new File('tools/webcore-investigation/generate-construct-registry.groovy'), 'generator')
noControlChars(checkedIn, 'generated candidate')
passed += 2

// Durable orphan-gate test. The gate is enforced in the generator, but it has
// twice been a thing that existed in a report rather than in verification, so it
// gets an assertion of its own.
//
// Mutates NORMALIZATION, not FROZEN_SITES: injecting a bogus member into the
// frozen set is caught by the membership gate first, which is correct behaviour
// but proves the wrong gate.
File genSrc = new File('tools/webcore-investigation/generate-construct-registry.groovy')
String genOriginal = genSrc.getText('UTF-8')
String mutated = genOriginal.replace(
    "'operand.subscribe.type':           [prefix: 'operand.',              consumer: 'subscribeAll'],",
    "'operand.subscribe.type':           [prefix: 'operand.nonexistent.',  consumer: 'subscribeAll'],")
check(mutated != genOriginal, 'orphan mutation target found in generator')
passed++

try {
    genSrc.setText(mutated, 'UTF-8')
    def orph = runGen(srcRoot)
    check(orph.rc != 0, 'orphan in a consumer-only site fails the generator')
    check(orph.out.contains('orphan member'), 'orphan is reported with the fixed reason')
    check(!orph.out.contains('@Field static final Map WEBCORE_CONSTRUCT_REGISTRY'),
        'no candidate registry is emitted when an orphan is present')
    passed += 3
} finally {
    genSrc.setText(genOriginal, 'UTF-8')
}

// Prove the restore worked, so a failure here cannot leave the generator mutated.
check(genSrc.getText('UTF-8') == genOriginal, 'generator restored after orphan mutation')
passed++

println "${passed} passed, 0 failed"
