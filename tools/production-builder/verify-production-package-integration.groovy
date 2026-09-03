// Integration-level tests for production-package.groovy, the package-build
// orchestrator (phase 2d, backlog item 16 / production_build_methodology.md;
// review, queue 461 finding 2). Builds real throwaway git fixture
// repositories under this repository's own tmp/ and runs the real
// orchestrator CLI as a subprocess against them - proves the actual
// published behaviour, not a copy of its logic.
//
// Usage: groovy verify-production-package-integration.groovy
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

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

String groovyExecutable() {
    return System.getProperty('os.name', '').toLowerCase().contains('windows') ? 'groovy.bat' : 'groovy'
}

Map runProc(List<String> cmd, File cwd = null) {
    ProcessBuilder pb = new ProcessBuilder(cmd)
    if (cwd != null) pb.directory(cwd)
    pb.environment().put('JAVA_HOME', System.getProperty('java.home'))
    Process p = pb.start()
    String out = p.inputStream.getText('UTF-8')
    String err = p.errorStream.getText('UTF-8')
    int code = p.waitFor()
    return [exitCode: code, stdout: out, stderr: err]
}

void deleteRecursive(File f) {
    if (f == null || !f.exists()) return
    if (f.isDirectory()) {
        f.listFiles()?.each { deleteRecursive(it) }
    }
    f.delete()
}

// Builds a throwaway fixture repo under <repoRoot>/tmp/ carrying the REAL,
// already-tracked app source and manifest (the production-profile
// validation gate needs the app's full real definition() shape - APP_VERSION,
// namespace, iconUrl/iconX2Url - a minimal synthetic app does not and
// should not reproduce), plus this repo's real validate.ps1 and
// repository.json (both required inputs the orchestrator's gates read),
// and its own fixture production-release-notes.txt, all committed together
// as one initial commit.
File buildFixtureRepo(File tmpRoot, File toolDir, File repoRoot, String label, String manifestVersionOverride = null, String releaseNotesOverride = null) {
    File fixtureRoot = new File(tmpRoot, "production-package-it-${label}-${UUID.randomUUID().toString().take(8)}")
    File fixtureTools = new File(fixtureRoot, 'tools/production-builder')
    File fixtureApps = new File(fixtureRoot, 'apps')
    File fixtureTmp = new File(fixtureRoot, 'tmp')
    [fixtureTools, fixtureApps, fixtureTmp].each { it.mkdirs() }
    ['production-profile.groovy', 'production-manifest.groovy', 'production-package.groovy', 'pinned-guard.groovy', 'comparison.groovy', 'strip-comments.groovy'].each { String name ->
        new File(fixtureTools, name).setText(new File(toolDir, name).getText('UTF-8'), 'UTF-8')
    }
    new File(fixtureApps, 'automation_map.groovy').setText(new File(repoRoot, 'apps/automation_map.groovy').getText('UTF-8'), 'UTF-8')
    new File(fixtureRoot, 'validate.ps1').setText(new File(repoRoot, 'validate.ps1').getText('UTF-8'), 'UTF-8')
    new File(fixtureRoot, 'repository.json').setText(new File(repoRoot, 'repository.json').getText('UTF-8'), 'UTF-8')

    def manifestParsed = new JsonSlurper().parseText(new File(repoRoot, 'packageManifest.json').getText('UTF-8'))
    if (manifestVersionOverride != null) {
        manifestParsed.version = manifestVersionOverride
        manifestParsed.apps[0].version = manifestVersionOverride
    }
    new File(fixtureRoot, 'packageManifest.json').setText(JsonOutput.prettyPrint(JsonOutput.toJson(manifestParsed)), 'UTF-8')

    String releaseNotes = releaseNotesOverride ?: (
        "v${manifestParsed.version}: Fixture app description for the orchestrator integration check.\n\n" +
        'Since the last production release:\n\n- A fixture change.\n')
    new File(fixtureRoot, 'production-release-notes.txt').setText(releaseNotes, 'UTF-8')

    runProc(['git', 'init', '-q'], fixtureRoot)
    runProc(['git', 'config', 'user.email', 'fixture@example.invalid'], fixtureRoot)
    runProc(['git', 'config', 'user.name', 'Fixture'], fixtureRoot)
    Map add = runProc(['git', 'add', '-A'], fixtureRoot)
    if (add.exitCode != 0) throw new RuntimeException("git add failed: ${add.stderr}")
    Map commit = runProc(['git', 'commit', '-q', '-m', 'fixture commit'], fixtureRoot)
    if (commit.exitCode != 0) throw new RuntimeException("git commit failed: ${commit.stderr}")
    return fixtureRoot
}

List<String> packageCliCmd(File fixtureRoot, String outRelPath) {
    File script = new File(fixtureRoot, 'tools/production-builder/production-package.groovy')
    File out = new File(fixtureRoot, outRelPath)
    return [groovyExecutable(), script.absolutePath, out.absolutePath]
}

println '--- Happy path: real fixture content, real orchestrator subprocess ---'
File happyRepo = buildFixtureRepo(tmpRoot, toolDir, repoRoot, 'happy')
try {
    Map result = runProc(packageCliCmd(happyRepo, 'tmp/pkg-out'))
    check('exits 0 on a clean fixture repo') {
        assert result.exitCode == 0 : "stdout: ${result.stdout}\nstderr: ${result.stderr}"
    }
    File pkgDir = new File(happyRepo, 'tmp/pkg-out')
    check('publishes all three package files') {
        assert new File(pkgDir, 'automation_map.groovy').exists()
        assert new File(pkgDir, 'packageManifest.json').exists()
        assert new File(pkgDir, 'provenance.json').exists()
    }
    check('provenance.json has no wall-clock or machine-specific fields, only the documented ones') {
        def sidecar = new JsonSlurper().parseText(new File(pkgDir, 'provenance.json').getText('UTF-8'))
        assert sidecar.keySet() == ['packageFormatVersion', 'sourceCommit', 'appProfileVersion', 'manifestProfileVersion', 'appCandidateSha256', 'manifestCandidateSha256'] as Set
    }
    check('stdout reports the not-deployed/not-released caveat') {
        assert (result.stdout as String).contains('has not been deployed, promoted, or released')
    }
} finally {
    deleteRecursive(happyRepo)
}

println '--- Reproducibility: two builds from the same commit produce byte-identical app, manifest, and sidecar ---'
File reproRepo = buildFixtureRepo(tmpRoot, toolDir, repoRoot, 'repro')
try {
    Map r1 = runProc(packageCliCmd(reproRepo, 'tmp/pkg-out-1'))
    Map r2 = runProc(packageCliCmd(reproRepo, 'tmp/pkg-out-2'))
    check('both builds succeed') {
        assert r1.exitCode == 0 && r2.exitCode == 0
    }
    File dir1 = new File(reproRepo, 'tmp/pkg-out-1')
    File dir2 = new File(reproRepo, 'tmp/pkg-out-2')
    check('automation_map.groovy is byte-identical across both builds') {
        assert new File(dir1, 'automation_map.groovy').getText('UTF-8') == new File(dir2, 'automation_map.groovy').getText('UTF-8')
    }
    check('packageManifest.json is byte-identical across both builds') {
        assert new File(dir1, 'packageManifest.json').getText('UTF-8') == new File(dir2, 'packageManifest.json').getText('UTF-8')
    }
    check('provenance.json is byte-identical across both builds') {
        assert new File(dir1, 'provenance.json').getText('UTF-8') == new File(dir2, 'provenance.json').getText('UTF-8')
    }
} finally {
    deleteRecursive(reproRepo)
}

println '--- Negative: a dirty working tree for any of the three bound inputs is rejected, no package published ---'
File dirtyRepo = buildFixtureRepo(tmpRoot, toolDir, repoRoot, 'dirty')
try {
    File manifestFile = new File(dirtyRepo, 'packageManifest.json')
    manifestFile.setText(manifestFile.getText('UTF-8').replace('"author"', '"AUTHOR"'), 'UTF-8')
    Map result = runProc(packageCliCmd(dirtyRepo, 'tmp/pkg-out-dirty'))
    check('exits non-zero') {
        assert result.exitCode != 0
    }
    check('no package directory is created') {
        assert !(new File(dirtyRepo, 'tmp/pkg-out-dirty').exists())
    }
} finally {
    deleteRecursive(dirtyRepo)
}

println '--- Negative: an output path outside tmp/ is rejected ---'
File outsideRepo = buildFixtureRepo(tmpRoot, toolDir, repoRoot, 'outside')
try {
    Map result = runProc(packageCliCmd(outsideRepo, 'outside-pkg'))
    check('exits non-zero') {
        assert result.exitCode != 0
    }
    check('no package directory is created') {
        assert !(new File(outsideRepo, 'outside-pkg').exists())
    }
} finally {
    deleteRecursive(outsideRepo)
}

println '--- Negative: a wrong argument count is rejected ---'
File argCountRepo = buildFixtureRepo(tmpRoot, toolDir, repoRoot, 'arg-count')
try {
    File script = new File(argCountRepo, 'tools/production-builder/production-package.groovy')
    Map result = runProc([groovyExecutable(), script.absolutePath])
    check('exits non-zero with zero arguments') {
        assert result.exitCode != 0
    }
} finally {
    deleteRecursive(argCountRepo)
}

println '--- Destination safety: a failed rebuild leaves the requested output directory absent, not a stale prior success ---'
File safetyRepo = buildFixtureRepo(tmpRoot, toolDir, repoRoot, 'safety')
try {
    Map first = runProc(packageCliCmd(safetyRepo, 'tmp/pkg-out-safety'))
    check('first build succeeds') {
        assert first.exitCode == 0 : first.stderr
    }
    File manifestFile = new File(safetyRepo, 'packageManifest.json')
    manifestFile.setText(manifestFile.getText('UTF-8').replace('"author"', '"AUTHOR"'), 'UTF-8')
    Map second = runProc(packageCliCmd(safetyRepo, 'tmp/pkg-out-safety'))
    check('the dirty rebuild fails') {
        assert second.exitCode != 0
    }
    check('the output directory is absent after the failed rebuild, not the stale prior success') {
        assert !(new File(safetyRepo, 'tmp/pkg-out-safety').exists())
    }
} finally {
    deleteRecursive(safetyRepo)
}

println '--- Negative: production-profile validation catches an app/manifest version mismatch neither generator checks alone ---'
// Neither generator cross-checks apps/automation_map.groovy's own
// APP_VERSION against packageManifest.json's version field - only
// validate.ps1's whole-package production-candidate gate does. A fixture
// manifest at a DIFFERENT version than the real tracked app source
// (APP_VERSION '2.2.0') proves the orchestrator's own publication gate
// catches something no individual generation step would.
File versionMismatchRepo = buildFixtureRepo(tmpRoot, toolDir, repoRoot, 'version-mismatch', '9.9.9')
try {
    Map result = runProc(packageCliCmd(versionMismatchRepo, 'tmp/pkg-out-mismatch'))
    check('exits non-zero') {
        assert result.exitCode != 0
    }
    check('stderr names the production-profile validation stage') {
        assert (result.stderr as String).contains('production-profile validation')
    }
    check('no package directory is created') {
        assert !(new File(versionMismatchRepo, 'tmp/pkg-out-mismatch').exists())
    }
} finally {
    deleteRecursive(versionMismatchRepo)
}

println '--- verifyPackageDirectory(): a genuinely mixed pair (different source commits) is rejected ---'
File mixedRepoA = buildFixtureRepo(tmpRoot, toolDir, repoRoot, 'mixed-a')
try {
    Map buildA = runProc(packageCliCmd(mixedRepoA, 'tmp/pkg-out-a'))
    check('package A builds successfully') {
        assert buildA.exitCode == 0 : buildA.stderr
    }
    // A second, genuinely different commit in the SAME repo (manifest
    // dateReleased changed, so the manifest CANDIDATE - not just the Dev
    // source - is provably different, not merely from a different SHA that
    // happens to produce identical content).
    File manifestFile = new File(mixedRepoA, 'packageManifest.json')
    def parsed = new JsonSlurper().parseText(manifestFile.getText('UTF-8'))
    parsed.dateReleased = '2099-01-01'
    manifestFile.setText(JsonOutput.prettyPrint(JsonOutput.toJson(parsed)), 'UTF-8')
    Map commitB = runProc(['git', 'commit', '-a', '-q', '-m', 'genuinely different commit B'], mixedRepoA)
    check('sanity: the second commit succeeds') {
        assert commitB.exitCode == 0 : commitB.stderr
    }
    Map buildB = runProc(packageCliCmd(mixedRepoA, 'tmp/pkg-out-b'))
    check('package B (from the different commit) builds successfully') {
        assert buildB.exitCode == 0 : buildB.stderr
    }

    File mixedDir = new File(mixedRepoA, 'tmp/mixed-package')
    mixedDir.mkdirs()
    new File(mixedDir, 'automation_map.groovy').setText(new File(mixedRepoA, 'tmp/pkg-out-a/automation_map.groovy').getText('UTF-8'), 'UTF-8')
    new File(mixedDir, 'provenance.json').setText(new File(mixedRepoA, 'tmp/pkg-out-a/provenance.json').getText('UTF-8'), 'UTF-8')
    // The manifest from package B, mismatched against package A's own app
    // candidate and provenance.json - simulates a directory hand-assembled
    // from two different builds' outputs.
    new File(mixedDir, 'packageManifest.json').setText(new File(mixedRepoA, 'tmp/pkg-out-b/packageManifest.json').getText('UTF-8'), 'UTF-8')

    File pkgScript = new File(mixedRepoA, 'tools/production-builder/production-package.groovy')
    def Pkg = new GroovyClassLoader(this.class.classLoader).parseClass(pkgScript)
    check('verifyPackageDirectory() accepts the genuine package A as internally consistent') {
        Map r = Pkg.verifyPackageDirectory(new File(mixedRepoA, 'tmp/pkg-out-a'))
        assert r.ok : "expected package A to verify, got: ${r}"
    }
    check('verifyPackageDirectory() accepts the genuine package B as internally consistent') {
        Map r = Pkg.verifyPackageDirectory(new File(mixedRepoA, 'tmp/pkg-out-b'))
        assert r.ok : "expected package B to verify, got: ${r}"
    }
    check('verifyPackageDirectory() rejects the hand-mixed directory (manifest from a different commit than the app+sidecar)') {
        Map r = Pkg.verifyPackageDirectory(mixedDir)
        assert !r.ok : "expected rejection, got: ${r}"
        assert (r.reason as String).contains('does not belong to the same build')
    }
} finally {
    deleteRecursive(mixedRepoA)
}

println '--- Deterministic negative: a source change AFTER validation but BEFORE the final recheck is caught, no package published (review, queue 463) ---'
// Calls buildPackage() directly, in-process, via the fixture's own copy of
// every tool file (parseClass, same "always exercise the actual current
// implementation" principle every fixture in this file already uses) -
// exercises afterValidationHook, the deterministic test seam added
// specifically so this scenario does not depend on a real race actually
// landing in the (now correctly small) window between validate.ps1
// finishing and the final recheck running.
File hookRepo = buildFixtureRepo(tmpRoot, toolDir, repoRoot, 'hook')
try {
    File pinnedGuardFile = new File(hookRepo, 'tools/production-builder/pinned-guard.groovy')
    File comparisonFile = new File(hookRepo, 'tools/production-builder/comparison.groovy')
    File stripperFile = new File(hookRepo, 'tools/production-builder/strip-comments.groovy')
    File profileFile = new File(hookRepo, 'tools/production-builder/production-profile.groovy')
    File manifestFile = new File(hookRepo, 'tools/production-builder/production-manifest.groovy')
    File packageFile = new File(hookRepo, 'tools/production-builder/production-package.groovy')
    def loader = new GroovyClassLoader(this.class.classLoader)
    def guard = loader.parseClass(pinnedGuardFile)
    guard.require()
    def Comparison = loader.parseClass(comparisonFile)
    def StripComments = loader.parseClass(stripperFile)
    def Profile = loader.parseClass(profileFile)
    def Mfst = loader.parseClass(manifestFile)
    def Pkg = loader.parseClass(packageFile)
    File hookToolDir = new File(hookRepo, 'tools/production-builder')
    File hookOutDir = new File(hookRepo, 'tmp/pkg-out-hook')

    // The hook fires after validate.ps1 has already succeeded, and mutates
    // + commits a real change to the tracked manifest - simulating exactly
    // the "source changed while the slow gate was running" scenario the
    // recheck exists to catch, deterministically rather than by timing.
    Closure mutateAfterValidation = {
        File manifestOnDisk = new File(hookRepo, 'packageManifest.json')
        def parsed = new JsonSlurper().parseText(manifestOnDisk.getText('UTF-8'))
        parsed.dateReleased = '2099-12-31'
        manifestOnDisk.setText(JsonOutput.prettyPrint(JsonOutput.toJson(parsed)), 'UTF-8')
        Map commitResult = runProc(['git', 'commit', '-a', '-q', '-m', 'mutate mid-build via test hook'], hookRepo)
        if (commitResult.exitCode != 0) throw new RuntimeException("hook commit failed: ${commitResult.stderr}")
    }

    Map result = Pkg.buildPackage(hookRepo, hookToolDir, Comparison, StripComments, Profile, Mfst, hookOutDir, mutateAfterValidation)
    check('the build fails when the source changes between validation and the final recheck') {
        assert !result.ok : "expected failure, got: ${result}"
    }
    check('the failure is attributed to the pre-publish recheck stage specifically') {
        assert (result.stage as String).contains('pre-publish recheck')
    }
    check('no output directory is published') {
        assert !hookOutDir.exists()
    }
} finally {
    deleteRecursive(hookRepo)
}

println "${pass} passed, ${fail} failed"
if (fail > 0) System.exit(1)
