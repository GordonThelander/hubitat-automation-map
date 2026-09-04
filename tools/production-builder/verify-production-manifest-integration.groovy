// Integration-level tests for production-manifest.groovy's actual CLI path,
// plus the two cross-cutting proofs phase 2d requires (backlog item 16;
// review, queue 456): the generated app and manifest are bound to the same
// verified source commit, and the generated pair together passes
// validate.ps1's whole-package production-profile mode.
//
// The manifest CLI reuses production-profile.groovy's own
// resolveVerifiedProvenance()/writeCandidateAtomically() (via parseClass()),
// already covered by verify-production-profile-integration.groovy's 28
// assertions for the shared git-provenance/destination-safety logic itself
// - this file keeps its OWN provenance-negative coverage to a representative
// smoke set (dirty tree, wrong argument count, output outside tmp/) proving
// the manifest CLI correctly wires that shared logic for ITS OWN path,
// rather than re-litigating every edge case the shared function already has
// full coverage for elsewhere.
//
// Usage: groovy verify-production-manifest-integration.groovy
File thisScriptFile = new File(this.class.protectionDomain.codeSource.location.toURI())
File toolDir = thisScriptFile.parentFile
File repoRoot = toolDir.parentFile.parentFile
File tmpRoot = new File(repoRoot, 'tmp')
tmpRoot.mkdirs()

int pass = 0, fail = 0
def check = { String name, Closure body ->
    try {
        body()
        println "PASS  ${name}"
        pass++
    } catch (Throwable t) {
        println "FAIL  ${name} - ${t.class.simpleName}: ${t.message}"
        fail++
    }
}

Map runProc(List<String> cmd, File cwd = null) {
    ProcessBuilder pb = new ProcessBuilder(cmd)
    if (cwd != null) pb.directory(cwd)
    // Same JAVA_HOME fix as verify-production-profile-integration.groovy -
    // the inherited environment can carry a stale/invalid JAVA_HOME that
    // breaks a nested groovy.bat launcher even though this running JVM is
    // fine.
    pb.environment().put('JAVA_HOME', System.getProperty('java.home'))
    Process p = pb.start()
    String out = p.inputStream.getText('UTF-8')
    String err = p.errorStream.getText('UTF-8')
    int code = p.waitFor()
    return [exitCode: code, stdout: out, stderr: err]
}

String groovyExecutable() {
    return System.getProperty('os.name', '').toLowerCase().contains('windows') ? 'groovy.bat' : 'groovy'
}

// Builds one throwaway fixture repo under <repoRoot>/tmp/ with copies of
// every production-builder tool file this needs (both generators plus their
// shared dependencies) and a small Dev-shaped apps/automation_map.groovy
// PLUS packageManifest.json, committed together as the initial commit.
File buildFixtureRepo(File tmpRoot, File toolDir, String label) {
    File fixtureRoot = new File(tmpRoot, "production-manifest-it-${label}-${UUID.randomUUID().toString().take(8)}")
    File fixtureTools = new File(fixtureRoot, 'tools/production-builder')
    File fixtureApps = new File(fixtureRoot, 'apps')
    File fixtureTmp = new File(fixtureRoot, 'tmp')
    [fixtureTools, fixtureApps, fixtureTmp].each { it.mkdirs() }

    ['production-profile.groovy', 'production-manifest.groovy', 'pinned-guard.groovy', 'comparison.groovy', 'strip-comments.groovy'].each { String name ->
        File src = new File(toolDir, name)
        File dst = new File(fixtureTools, name)
        dst.setText(src.getText('UTF-8'), 'UTF-8')
    }

    new File(fixtureApps, 'automation_map.groovy').setText('''/*
 * Fixture Dev architecture header - must not survive comment-stripping.
 */
import groovy.transform.Field

@Field static final String APP_NAME = 'Automation Map (Dev)'
@Field static final String BUILD_CHANNEL = 'dev'
@Field static final int DIAGNOSTIC_LEVEL = 2

boolean isDevBuild() { return BUILD_CHANNEL == 'dev' }
''', 'UTF-8')

    new File(fixtureRoot, 'packageManifest.json').setText('''{
  "packageName": "Automation Map (Dev)",
  "author": "Gordon Thelander",
  "version": "9.9.9",
  "minimumHEVersion": "2.5.1",
  "dateReleased": "2026-01-01",
  "documentationLink": "https://github.com/GordonThelander/hubitat-automation-map/blob/dev/README.md",
  "communityLink": "https://community.hubitat.com/t/release-hubitat-automation-map/165524",
  "releaseNotes": "DEV CHANNEL - this manifest tracks the development branch, not the production release.\\n\\nFixture release.\\n\\nComplete history: https://github.com/GordonThelander/hubitat-automation-map/blob/dev/CHANGELOG.md",
  "apps": [
    {
      "id": "2c2c28d6-f6eb-4c88-afad-d9d32a6013bc",
      "name": "Automation Map (Dev)",
      "namespace": "Hubitat Integrations",
      "location": "https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/dev/apps/automation_map.groovy",
      "required": true,
      "oauth": true,
      "primary": true,
      "version": "9.9.9"
    }
  ]
}
''', 'UTF-8')

    // The externally-supplied, human-curated release notes input (review,
    // queue 459) - a separate tracked file, mentioning the Dev app name
    // nowhere, matching production-release-notes.txt's real shape.
    new File(fixtureRoot, 'production-release-notes.txt').setText(
        'v9.9.9: Fixture app description.\n\nSince the last production release:\n\n- A fixture change.\n', 'UTF-8')

    Map init = runProc(['git', 'init', '-q'], fixtureRoot)
    if (init.exitCode != 0) throw new RuntimeException("git init failed: ${init.stderr}")
    runProc(['git', 'config', 'user.email', 'fixture@example.invalid'], fixtureRoot)
    runProc(['git', 'config', 'user.name', 'Fixture'], fixtureRoot)
    Map add = runProc(['git', 'add', '-A'], fixtureRoot)
    if (add.exitCode != 0) throw new RuntimeException("git add failed: ${add.stderr}")
    Map commit = runProc(['git', 'commit', '-q', '-m', 'fixture commit'], fixtureRoot)
    if (commit.exitCode != 0) throw new RuntimeException("git commit failed: ${commit.stderr}")
    return fixtureRoot
}

void deleteRecursive(File f) {
    if (f.isDirectory()) {
        f.listFiles()?.each { deleteRecursive(it) }
    }
    f.delete()
}

String fixtureHeadSha(File fixtureRoot) {
    Map r = runProc(['git', 'rev-parse', '--verify', 'HEAD'], fixtureRoot)
    return (r.stdout as String).trim()
}

List<String> manifestCliCmd(File fixtureRoot, String outRelPath) {
    File script = new File(fixtureRoot, 'tools/production-builder/production-manifest.groovy')
    File out = new File(fixtureRoot, outRelPath)
    return [groovyExecutable(), script.absolutePath, out.absolutePath]
}

List<String> appCliCmd(File fixtureRoot, String outRelPath) {
    File script = new File(fixtureRoot, 'tools/production-builder/production-profile.groovy')
    File out = new File(fixtureRoot, outRelPath)
    return [groovyExecutable(), script.absolutePath, out.absolutePath]
}

println '--- Happy path: clean fixture repo, real manifest CLI subprocess ---'
File happyRepo = buildFixtureRepo(tmpRoot, toolDir, 'happy')
try {
    String expectedSha = fixtureHeadSha(happyRepo)
    Map result = runProc(manifestCliCmd(happyRepo, 'tmp/manifest-out.json'))
    check('exits 0 on a clean fixture repo') {
        assert result.exitCode == 0 : "stderr: ${result.stderr}"
    }
    File outFile = new File(happyRepo, 'tmp/manifest-out.json')
    check('produces the output file') {
        assert outFile.exists()
    }
    check('the output is valid JSON with the Dev suffix dropped and the fixed production app id') {
        def parsed = new groovy.json.JsonSlurper().parseText(outFile.getText('UTF-8'))
        assert parsed.packageName == 'Automation Map'
        assert parsed.apps[0].id == '184c8f7d-5b2a-4352-a883-45d36ef4860b'
    }
} finally {
    deleteRecursive(happyRepo)
}

println '--- Negative: a dirty working tree for packageManifest.json is rejected ---'
File dirtyRepo = buildFixtureRepo(tmpRoot, toolDir, 'dirty')
try {
    File manifestFile = new File(dirtyRepo, 'packageManifest.json')
    manifestFile.setText(manifestFile.getText('UTF-8').replace('9.9.9', '9.9.10'), 'UTF-8')
    Map result = runProc(manifestCliCmd(dirtyRepo, 'tmp/out-dirty.json'))
    check('exits non-zero on a dirty working tree') {
        assert result.exitCode != 0
    }
    check('stderr explains the dirty-tree rejection') {
        assert (result.stderr as String).contains('dirty')
    }
    check('no output artifact is left after the failed build') {
        assert !(new File(dirtyRepo, 'tmp/out-dirty.json').exists())
    }
} finally {
    deleteRecursive(dirtyRepo)
}

println '--- Negative: an output path outside the fixture repo\'s tmp/ is rejected ---'
File outsideRepo = buildFixtureRepo(tmpRoot, toolDir, 'outside')
try {
    Map result = runProc(manifestCliCmd(outsideRepo, 'outside-tmp.json'))
    check('exits non-zero for an output path outside tmp/') {
        assert result.exitCode != 0
    }
    check('no output artifact is left after the failed build') {
        assert !(new File(outsideRepo, 'outside-tmp.json').exists())
    }
} finally {
    deleteRecursive(outsideRepo)
}

println '--- Negative: an unexpected extra argument is rejected ---'
File extraArgRepo = buildFixtureRepo(tmpRoot, toolDir, 'extra-arg')
try {
    File script = new File(extraArgRepo, 'tools/production-builder/production-manifest.groovy')
    File out = new File(extraArgRepo, 'tmp/out-extra.json')
    Map result = runProc([groovyExecutable(), script.absolutePath, out.absolutePath, 'unexpected-second-arg'])
    check('exits non-zero when given two arguments instead of exactly one') {
        assert result.exitCode != 0
    }
    check('no output artifact is created') {
        assert !out.exists()
    }
} finally {
    deleteRecursive(extraArgRepo)
}

println '--- Cross-cutting: app candidate and manifest candidate are bound to the SAME verified source commit (item 4) ---'
File boundRepo = buildFixtureRepo(tmpRoot, toolDir, 'bound')
try {
    String expectedSha = fixtureHeadSha(boundRepo)
    Map appResult = runProc(appCliCmd(boundRepo, 'tmp/app-out.groovy'))
    Map manifestResult = runProc(manifestCliCmd(boundRepo, 'tmp/manifest-out.json'))
    check('both generators succeed against the same fixture commit') {
        assert appResult.exitCode == 0 : "app stderr: ${appResult.stderr}"
        assert manifestResult.exitCode == 0 : "manifest stderr: ${manifestResult.stderr}"
    }
    String appText = new File(boundRepo, 'tmp/app-out.groovy').getText('UTF-8')
    def manifestParsed = new groovy.json.JsonSlurper().parseText(new File(boundRepo, 'tmp/manifest-out.json').getText('UTF-8'))
    check('the app candidate\'s embedded commit SHA is this fixture\'s real HEAD') {
        assert appText.contains(expectedSha)
    }
    // The manifest candidate does not embed the commit SHA itself (there is
    // no per-file provenance header for JSON the way the app source has a
    // Groovy comment header) - the binding proof here is that BOTH
    // generators independently derived the SAME SHA from the SAME commit,
    // which the manifest CLI's own stdout reports explicitly.
    check('the manifest CLI\'s own reported provenance names the same commit SHA') {
        assert (manifestResult.stdout as String).contains(expectedSha)
    }
} finally {
    deleteRecursive(boundRepo)
}

println '--- Cross-cutting: the generated app + manifest pair passes validate.ps1 in whole-package production-profile mode (item 5) ---'
// Built as a throwaway fixture repo (not the real checkout): the real
// checkout's own production-release-notes.txt is not committed yet - it is
// part of this very review, so resolveVerifiedProvenance() correctly
// refuses to treat it as trustworthy input until it actually lands in HEAD.
// This fixture copies the REAL, already-tracked app source and manifest
// (validate.ps1 checks the app's full real definition() shape - APP_VERSION,
// namespace, iconUrl/iconX2Url - which a minimal synthetic fixture does not
// and should not reproduce) and adds its own committed
// production-release-notes.txt, so the whole chain is exercised faithfully
// without depending on real-repo state this review has not landed yet.
File wholePackageRepo = new File(tmpRoot, "production-manifest-it-whole-package-${UUID.randomUUID().toString().take(8)}")
try {
    File fixtureTools = new File(wholePackageRepo, 'tools/production-builder')
    File fixtureApps = new File(wholePackageRepo, 'apps')
    File fixtureTmp = new File(wholePackageRepo, 'tmp')
    [fixtureTools, fixtureApps, fixtureTmp].each { it.mkdirs() }
    ['production-profile.groovy', 'production-manifest.groovy', 'pinned-guard.groovy', 'comparison.groovy', 'strip-comments.groovy'].each { String name ->
        new File(fixtureTools, name).setText(new File(toolDir, name).getText('UTF-8'), 'UTF-8')
    }
    new File(fixtureApps, 'automation_map.groovy').setText(new File(repoRoot, 'apps/automation_map.groovy').getText('UTF-8'), 'UTF-8')
    new File(wholePackageRepo, 'packageManifest.json').setText(new File(repoRoot, 'packageManifest.json').getText('UTF-8'), 'UTF-8')
    new File(wholePackageRepo, 'production-release-notes.txt').setText(
        'v2.2.0: Fixture app description for the whole-package integration check.\n\n' +
        'Since the last production release:\n\n- A fixture change.\n', 'UTF-8')
    runProc(['git', 'init', '-q'], wholePackageRepo)
    runProc(['git', 'config', 'user.email', 'fixture@example.invalid'], wholePackageRepo)
    runProc(['git', 'config', 'user.name', 'Fixture'], wholePackageRepo)
    Map add = runProc(['git', 'add', '-A'], wholePackageRepo)
    if (add.exitCode != 0) throw new RuntimeException("git add failed: ${add.stderr}")
    Map commit = runProc(['git', 'commit', '-q', '-m', 'fixture commit'], wholePackageRepo)
    if (commit.exitCode != 0) throw new RuntimeException("git commit failed: ${commit.stderr}")

    File appOut = new File(wholePackageRepo, 'tmp/app-out.groovy')
    File manifestOut = new File(wholePackageRepo, 'tmp/manifest-out.json')
    Map appResult = runProc(appCliCmd(wholePackageRepo, 'tmp/app-out.groovy'))
    Map manifestResult = runProc(manifestCliCmd(wholePackageRepo, 'tmp/manifest-out.json'))
    check('both generators succeed against the real app+manifest content, copied into a fresh throwaway commit') {
        assert appResult.exitCode == 0 : "app stderr: ${appResult.stderr}"
        assert manifestResult.exitCode == 0 : "manifest stderr: ${manifestResult.stderr}"
    }
    File validatePs1 = new File(repoRoot, 'validate.ps1')
    Map validateResult = runProc([
        'powershell', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', validatePs1.absolutePath,
        '-AppFile', appOut.absolutePath,
        '-ManifestFile', manifestOut.absolutePath,
        '-BuildProfile', 'ProductionCandidate',
    ], repoRoot)
    check('the real validate.ps1 passes the generated app+manifest pair in production-profile mode') {
        assert validateResult.exitCode == 0 : "stdout: ${validateResult.stdout}\nstderr: ${validateResult.stderr}"
    }
    check('validate.ps1 reports the production-shaped name and main branch') {
        assert (validateResult.stdout as String).contains('Automation Map v') && !(validateResult.stdout as String).contains('(Dev)')
        assert (validateResult.stdout as String).contains('on branch main')
    }
    check('normal (non-override) validate.ps1 against the real tracked Dev source is unaffected (item 6)') {
        Map normalResult = runProc([
            'powershell', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', validatePs1.absolutePath,
        ], repoRoot)
        assert normalResult.exitCode == 0 : "stdout: ${normalResult.stdout}\nstderr: ${normalResult.stderr}"
        // The branch half depends on how this checkout was materialized rather
        // than on the code under test - an isolated-worktree run sits on a
        // detached HEAD with no branch name to report. Accept either form; what
        // this case actually proves is that the plain, profile-less invocation
        // still validates the real Dev source cleanly.
        String normalOut = normalResult.stdout as String
        assert normalOut.contains('(Dev)')
        assert normalOut.contains('on branch dev') || normalOut.contains('on a detached HEAD') : "unexpected summary: ${normalOut}"
    }

    // Two more required negative cases (review, queue 461), run against
    // COPIES of the already-proven-good candidates so each isolates exactly
    // one corruption.
    check('a manifest with a stray dev-branch app location URL fails the production-candidate profile') {
        // Corrupts the field validate.ps1's existing branch-consistency
        // check actually reads (manifest apps[0].location) - the app
        // source's own font/watermark/sound URLs are runtime
        // ${isDevBuild() ? 'dev' : 'main'} expressions gated by
        // BUILD_CHANNEL itself (already independently asserted above), not
        // static text validate.ps1 inspects, so corrupting one of those
        // would not exercise this particular check.
        File corruptedManifest = new File(wholePackageRepo, 'tmp/manifest-out-bad-url.json')
        def parsed = new groovy.json.JsonSlurper().parseText(manifestOut.getText('UTF-8'))
        parsed.apps[0].location = parsed.apps[0].location.toString().replace('/main/', '/dev/')
        corruptedManifest.setText(groovy.json.JsonOutput.toJson(parsed), 'UTF-8')
        Map r = runProc([
            'powershell', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', validatePs1.absolutePath,
            '-AppFile', appOut.absolutePath, '-ManifestFile', corruptedManifest.absolutePath,
            '-BuildProfile', 'ProductionCandidate',
        ], repoRoot)
        assert r.exitCode != 0 : "expected failure, got: ${r.stdout}"
    }
    check('a manifest whose version disagrees with the app\'s APP_VERSION fails the production-candidate profile') {
        File corruptedManifest = new File(wholePackageRepo, 'tmp/manifest-out-bad-version.json')
        def parsed = new groovy.json.JsonSlurper().parseText(manifestOut.getText('UTF-8'))
        parsed.version = '1.0.0'
        corruptedManifest.setText(groovy.json.JsonOutput.toJson(parsed), 'UTF-8')
        Map r = runProc([
            'powershell', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', validatePs1.absolutePath,
            '-AppFile', appOut.absolutePath, '-ManifestFile', corruptedManifest.absolutePath,
            '-BuildProfile', 'ProductionCandidate',
        ], repoRoot)
        assert r.exitCode != 0 : "expected failure, got: ${r.stdout}"
    }
} finally {
    deleteRecursive(wholePackageRepo)
}

println '--- Negative: production-release-notes.txt with a DEV CHANNEL marker is rejected (review, queue 459) ---'
File badNotesRepo = buildFixtureRepo(tmpRoot, toolDir, 'bad-notes')
try {
    new File(badNotesRepo, 'production-release-notes.txt').setText(
        'DEV CHANNEL - this manifest tracks the development branch, not the production release.\n\nBad.\n', 'UTF-8')
    Map commitFix = runProc(['git', 'commit', '-a', '-q', '-m', 'dirty the release notes with a Dev marker'], badNotesRepo)
    check('sanity: the corrupted release-notes commit succeeds (it is valid text, just wrongly shaped)') {
        assert commitFix.exitCode == 0 : commitFix.stderr
    }
    Map result = runProc(manifestCliCmd(badNotesRepo, 'tmp/out-bad-notes.json'))
    check('the CLI rejects a committed, clean, but Dev-shaped release-notes file') {
        assert result.exitCode != 0
    }
    check('stderr explains the DEV CHANNEL rejection') {
        assert (result.stderr as String).contains('DEV CHANNEL')
    }
    check('no output artifact is created') {
        assert !(new File(badNotesRepo, 'tmp/out-bad-notes.json').exists())
    }
} finally {
    deleteRecursive(badNotesRepo)
}

println '--- Negative: production-release-notes.txt with a stale version prefix is rejected (review, queue 461) ---'
File staleNotesRepo = buildFixtureRepo(tmpRoot, toolDir, 'stale-notes')
try {
    // The fixture manifest's version is '9.9.9' (see buildFixtureRepo) - a
    // clean, well-formed, non-Dev-shaped notes file for a DIFFERENT version
    // must still be refused, not silently shipped under the wrong number.
    new File(staleNotesRepo, 'production-release-notes.txt').setText(
        'v1.0.0: Stale fixture notes for the wrong version.\n\n- A change.\n', 'UTF-8')
    Map commitFix = runProc(['git', 'commit', '-a', '-q', '-m', 'stale release-notes version'], staleNotesRepo)
    check('sanity: the stale-version commit succeeds (it is valid, well-formed text)') {
        assert commitFix.exitCode == 0 : commitFix.stderr
    }
    Map result = runProc(manifestCliCmd(staleNotesRepo, 'tmp/out-stale-notes.json'))
    check('the CLI rejects a clean, well-formed, but version-mismatched release-notes file') {
        assert result.exitCode != 0
    }
    check('stderr explains the stale-version rejection') {
        assert (result.stderr as String).contains('stale')
    }
    check('no output artifact is created') {
        assert !(new File(staleNotesRepo, 'tmp/out-stale-notes.json').exists())
    }
} finally {
    deleteRecursive(staleNotesRepo)
}

println '--- Negative: an unrecognised or already-production apps[0].id in packageManifest.json is rejected (review, queue 461) ---'
File unknownIdRepo = buildFixtureRepo(tmpRoot, toolDir, 'unknown-id')
try {
    File manifestFile = new File(unknownIdRepo, 'packageManifest.json')
    manifestFile.setText(manifestFile.getText('UTF-8').replace('2c2c28d6-f6eb-4c88-afad-d9d32a6013bc', 'totally-unrecognised-id'), 'UTF-8')
    Map commitFix = runProc(['git', 'commit', '-a', '-q', '-m', 'corrupt the Dev app id'], unknownIdRepo)
    check('sanity: the corrupted-id commit succeeds') {
        assert commitFix.exitCode == 0 : commitFix.stderr
    }
    Map result = runProc(manifestCliCmd(unknownIdRepo, 'tmp/out-unknown-id.json'))
    check('the CLI rejects an unrecognised apps[0].id, not silently normalizing it to production') {
        assert result.exitCode != 0
    }
    check('stderr names the app-id entry') {
        assert (result.stderr as String).contains('app-id')
    }
    check('no output artifact is created') {
        assert !(new File(unknownIdRepo, 'tmp/out-unknown-id.json').exists())
    }
} finally {
    deleteRecursive(unknownIdRepo)
}
File alreadyProdIdRepo = buildFixtureRepo(tmpRoot, toolDir, 'already-prod-id')
try {
    File manifestFile = new File(alreadyProdIdRepo, 'packageManifest.json')
    manifestFile.setText(manifestFile.getText('UTF-8').replace('2c2c28d6-f6eb-4c88-afad-d9d32a6013bc', '184c8f7d-5b2a-4352-a883-45d36ef4860b'), 'UTF-8')
    Map commitFix = runProc(['git', 'commit', '-a', '-q', '-m', 'set the id to the production value already'], alreadyProdIdRepo)
    check('sanity: the already-production-id commit succeeds') {
        assert commitFix.exitCode == 0 : commitFix.stderr
    }
    Map result = runProc(manifestCliCmd(alreadyProdIdRepo, 'tmp/out-already-prod-id.json'))
    check('the CLI rejects an already-production apps[0].id in the Dev manifest, not silently re-accepting it') {
        assert result.exitCode != 0
    }
    check('no output artifact is created') {
        assert !(new File(alreadyProdIdRepo, 'tmp/out-already-prod-id.json').exists())
    }
} finally {
    deleteRecursive(alreadyProdIdRepo)
}

println "${pass} passed, ${fail} failed"
if (fail > 0) System.exit(1)
