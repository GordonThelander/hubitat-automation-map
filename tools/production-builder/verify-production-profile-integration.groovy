// Integration-level tests for production-profile.groovy's actual CLI path
// (review, queue 450, blocking finding 2): the unit-level tests in
// verify-production-profile-tests.groovy exercise the shared transformation
// functions directly and never touch the CLI's own argument handling, output
// guard, or provenance verification - which is exactly how the caller-
// controlled-provenance bug in finding 1 passed that suite undetected.
//
// Builds a small, real, throwaway git repository shaped like this one (a
// tools/production-builder/ directory holding copies of the four real tool
// files, plus apps/automation_map.groovy) entirely under THIS repository's
// own tmp/ (never system temp - keeps every fixture and generated file
// under repository tmp/, as required), then invokes the real
// production-profile.groovy as an actual subprocess against that fixture
// repo. Proves the CLI's own provenance/output-guard/write path, not a copy
// of its logic.
//
// Usage: groovy verify-production-profile-integration.groovy
import java.nio.file.Files
import java.nio.file.Path

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
    // The environment ProcessBuilder inherits can carry a stale/invalid
    // JAVA_HOME that breaks the nested groovy.bat launcher even though the
    // CURRENTLY RUNNING JVM (this very test) is perfectly valid - observed
    // live, not theoretical. java.home is this running JVM's own actual
    // home directory, always correct by construction, so set it explicitly
    // rather than trust whatever JAVA_HOME happened to be inherited.
    pb.environment().put('JAVA_HOME', System.getProperty('java.home'))
    Process p = pb.start()
    String out = p.inputStream.getText('UTF-8')
    String err = p.errorStream.getText('UTF-8')
    int code = p.waitFor()
    return [exitCode: code, stdout: out, stderr: err]
}

// Builds one throwaway fixture repo under <repoRoot>/tmp/, with the four
// real tool files copied in (not retyped - always exercises the actual
// current implementation) and a small Dev-shaped apps/automation_map.groovy,
// committed as the initial commit. Returns the fixture root.
File buildFixtureRepo(File tmpRoot, File toolDir, String label) {
    File fixtureRoot = new File(tmpRoot, "production-profile-it-${label}-${UUID.randomUUID().toString().take(8)}")
    File fixtureTools = new File(fixtureRoot, 'tools/production-builder')
    File fixtureApps = new File(fixtureRoot, 'apps')
    File fixtureTmp = new File(fixtureRoot, 'tmp')
    [fixtureTools, fixtureApps, fixtureTmp].each { it.mkdirs() }

    ['production-profile.groovy', 'pinned-guard.groovy', 'comparison.groovy', 'strip-comments.groovy'].each { String name ->
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

// 'groovy' alone is a shell script ProcessBuilder cannot launch directly on
// Windows - needs the .bat launcher explicitly (or a shell in between).
String groovyExecutable() {
    return System.getProperty('os.name', '').toLowerCase().contains('windows') ? 'groovy.bat' : 'groovy'
}

List<String> cliCmd(File fixtureRoot, String outRelPath) {
    File script = new File(fixtureRoot, 'tools/production-builder/production-profile.groovy')
    File out = new File(fixtureRoot, outRelPath)
    return [groovyExecutable(), script.absolutePath, out.absolutePath]
}

println '--- Happy path: clean fixture repo, real CLI subprocess ---'
File happyRepo = buildFixtureRepo(tmpRoot, toolDir, 'happy')
try {
    String expectedSha = fixtureHeadSha(happyRepo)
    Map result = runProc(cliCmd(happyRepo, 'tmp/out.groovy'))
    check('exits 0 on a clean fixture repo') {
        assert result.exitCode == 0 : "stderr: ${result.stderr}"
    }
    File outFile = new File(happyRepo, 'tmp/out.groovy')
    check('produces the output file') {
        assert outFile.exists()
    }
    check('embeds the fixture repo\'s own real HEAD SHA, not a caller-supplied one') {
        String content = outFile.getText('UTF-8')
        assert content.contains(expectedSha)
    }
    check('stdout reports the app-source-only caveat (review 450 point 2)') {
        assert (result.stdout as String).contains('NOT a complete or deployable production artifact')
    }
    check('candidate has no leading blank-line run before the first import') {
        String content = outFile.getText('UTF-8')
        int firstImport = content.indexOf('import groovy.transform.Field')
        String beforeImport = content.substring(0, firstImport)
        assert beforeImport.trim().endsWith('*/') : "expected the header comment to end immediately before the import, got tail: ${beforeImport.takeRight(20).inspect()}"
    }
} finally {
    deleteRecursive(happyRepo)
}

println '--- Negative: a dirty working tree for apps/automation_map.groovy is rejected ---'
File dirtyRepo = buildFixtureRepo(tmpRoot, toolDir, 'dirty')
try {
    File appFile = new File(dirtyRepo, 'apps/automation_map.groovy')
    appFile.setText(appFile.getText('UTF-8') + '\n// an uncommitted local edit\n', 'UTF-8')
    Map result = runProc(cliCmd(dirtyRepo, 'tmp/out-dirty.groovy'))
    check('exits non-zero on a dirty working tree') {
        assert result.exitCode != 0
    }
    check('stderr explains the dirty-tree rejection') {
        assert (result.stderr as String).contains('dirty')
    }
    check('no output artifact is left after the failed build') {
        assert !(new File(dirtyRepo, 'tmp/out-dirty.groovy').exists())
    }
} finally {
    deleteRecursive(dirtyRepo)
}

println '--- Negative: an output path outside the fixture repo\'s tmp/ is rejected ---'
File outsideRepo = buildFixtureRepo(tmpRoot, toolDir, 'outside')
try {
    Map result = runProc(cliCmd(outsideRepo, 'outside-tmp.groovy'))
    check('exits non-zero for an output path outside tmp/') {
        assert result.exitCode != 0
    }
    check('stderr explains the tmp/-only requirement') {
        assert (result.stderr as String).contains('not under')
    }
    check('no output artifact is left after the failed build') {
        assert !(new File(outsideRepo, 'outside-tmp.groovy').exists())
    }
} finally {
    deleteRecursive(outsideRepo)
}

// Same input/output: NOT exercised as a check (review, queue 454 - a
// tautological assertion must not be reported as a passing behavioral
// test). The input file is no longer a CLI argument at all (hard-coded to
// <repoRoot>/apps/automation_map.groovy), and every valid output must
// resolve under <repoRoot>/tmp/ - these two directories cannot overlap by
// construction, so a caller cannot even attempt to point output back at the
// tracked Dev source through the CLI's own argument surface. The in-code
// same-path guard remains as defense-in-depth but has no reachable
// integration scenario to exercise it through the CLI any more.

println '--- Negative: an unexpected extra argument is rejected (review, queue 452/454 exact-cardinality requirement) ---'
File extraArgRepo = buildFixtureRepo(tmpRoot, toolDir, 'extra-arg')
try {
    File script = new File(extraArgRepo, 'tools/production-builder/production-profile.groovy')
    File out = new File(extraArgRepo, 'tmp/out-extra.groovy')
    Map result = runProc([groovyExecutable(), script.absolutePath, out.absolutePath, 'unexpected-second-arg'])
    check('exits non-zero when given two arguments instead of exactly one') {
        assert result.exitCode != 0
    }
    check('stderr prints usage') {
        assert (result.stderr as String).contains('Usage:')
    }
    check('no output artifact is created') {
        assert !out.exists()
    }
} finally {
    deleteRecursive(extraArgRepo)
}

println '--- Destination safety: a failed rerun leaves the requested destination ABSENT, not a stale prior success (review, queue 452) ---'
File safetyRepo = buildFixtureRepo(tmpRoot, toolDir, 'safety')
try {
    Map first = runProc(cliCmd(safetyRepo, 'tmp/out-safety.groovy'))
    check('first run succeeds and creates the destination') {
        assert first.exitCode == 0
        assert new File(safetyRepo, 'tmp/out-safety.groovy').exists()
    }
    File appFile = new File(safetyRepo, 'apps/automation_map.groovy')
    appFile.setText(appFile.getText('UTF-8') + '\n// dirty for the rerun\n', 'UTF-8')
    Map second = runProc(cliCmd(safetyRepo, 'tmp/out-safety.groovy'))
    check('the dirty rerun fails') {
        assert second.exitCode != 0
    }
    // Queue 452 point 2: a caller checking this exact path after a failed
    // command must never find a file there and mistake it for that failed
    // run's output - even though it happens to be a valid OLDER artifact.
    // The destination is invalidated immediately after the output-path
    // safety check, before provenance/transformation work runs, so ANY
    // later failure (dirty tree included) leaves it absent.
    check('the requested destination is absent after the failed rerun, not the stale prior success') {
        assert !(new File(safetyRepo, 'tmp/out-safety.groovy').exists())
    }
} finally {
    deleteRecursive(safetyRepo)
}

println '--- Provenance: the positive fixture is genuinely tracked (sanity-checks the fixture itself) ---'
File trackedCheckRepo = buildFixtureRepo(tmpRoot, toolDir, 'tracked-check')
try {
    Map lsFiles = runProc(['git', 'ls-files', '--error-unmatch', '--', 'apps/automation_map.groovy'], trackedCheckRepo)
    check('the happy-path fixture\'s apps/automation_map.groovy is actually tracked by git') {
        assert lsFiles.exitCode == 0 : "stderr: ${lsFiles.stderr}"
    }
} finally {
    deleteRecursive(trackedCheckRepo)
}

println '--- Negative: an ignored, untracked file at the expected path (absent from HEAD) is rejected ---'
File ignoredRepo = new File(tmpRoot, "production-profile-it-ignored-${UUID.randomUUID().toString().take(8)}")
try {
    File fixtureTools = new File(ignoredRepo, 'tools/production-builder')
    File fixtureApps = new File(ignoredRepo, 'apps')
    File fixtureTmp = new File(ignoredRepo, 'tmp')
    [fixtureTools, fixtureApps, fixtureTmp].each { it.mkdirs() }
    ['production-profile.groovy', 'pinned-guard.groovy', 'comparison.groovy', 'strip-comments.groovy'].each { String name ->
        new File(fixtureTools, name).setText(new File(toolDir, name).getText('UTF-8'), 'UTF-8')
    }
    // .gitignore covers apps/automation_map.groovy specifically, and it is
    // NEVER `git add`ed - present on disk, absent from HEAD and the index,
    // exactly the gap review 452 identified: `git status --porcelain` alone
    // would report nothing wrong here.
    new File(ignoredRepo, '.gitignore').setText("apps/automation_map.groovy\n", 'UTF-8')
    new File(fixtureApps, 'automation_map.groovy').setText("// present on disk, never committed, ignored - must not be trusted\n", 'UTF-8')
    runProc(['git', 'init', '-q'], ignoredRepo)
    runProc(['git', 'config', 'user.email', 'fixture@example.invalid'], ignoredRepo)
    runProc(['git', 'config', 'user.name', 'Fixture'], ignoredRepo)
    Map add = runProc(['git', 'add', '-A'], ignoredRepo)
    if (add.exitCode != 0) throw new RuntimeException("git add failed: ${add.stderr}")
    Map commit = runProc(['git', 'commit', '-q', '-m', 'fixture without the ignored app file'], ignoredRepo)
    if (commit.exitCode != 0) throw new RuntimeException("git commit failed: ${commit.stderr}")

    Map lsFiles = runProc(['git', 'ls-files', '--error-unmatch', '--', 'apps/automation_map.groovy'], ignoredRepo)
    check('sanity: the fixture app file is confirmed NOT tracked before running the CLI against it') {
        assert lsFiles.exitCode != 0
    }
    Map statusCheck = runProc(['git', 'status', '--porcelain', '--', 'apps/automation_map.groovy'], ignoredRepo)
    check('sanity: this is exactly the gap review 452 named - plain git status reports nothing wrong') {
        assert (statusCheck.stdout as String).trim().isEmpty()
    }

    Map result = runProc(cliCmd(ignoredRepo, 'tmp/out-ignored.groovy'))
    check('the CLI rejects an ignored/untracked file at the expected path, despite clean git status') {
        assert result.exitCode != 0
    }
    check('stderr explains the not-tracked rejection') {
        assert (result.stderr as String).contains('not tracked')
    }
    check('no output artifact is created') {
        assert !(new File(ignoredRepo, 'tmp/out-ignored.groovy').exists())
    }
} finally {
    deleteRecursive(ignoredRepo)
}

println '--- Negative: assume-unchanged masks a real content difference from git status - direct blob comparison still catches it (review, queue 454) ---'
File assumeRepo = buildFixtureRepo(tmpRoot, toolDir, 'assume-unchanged')
try {
    Map assumeSet = runProc(['git', 'update-index', '--assume-unchanged', 'apps/automation_map.groovy'], assumeRepo)
    if (assumeSet.exitCode != 0) throw new RuntimeException("git update-index --assume-unchanged failed: ${assumeSet.stderr}")
    try {
        File appFile = new File(assumeRepo, 'apps/automation_map.groovy')
        appFile.setText(appFile.getText('UTF-8') + '\n// modified on disk while assume-unchanged is set\n', 'UTF-8')

        Map statusCheck = runProc(['git', 'status', '--porcelain', '--', 'apps/automation_map.groovy'], assumeRepo)
        check('sanity: assume-unchanged genuinely suppresses git status for this real content difference') {
            assert (statusCheck.stdout as String).trim().isEmpty() : "expected empty porcelain, got: ${statusCheck.stdout}"
        }
        Map lsFiles = runProc(['git', 'ls-files', '--error-unmatch', '--', 'apps/automation_map.groovy'], assumeRepo)
        check('sanity: the path is still reported as tracked (assume-unchanged does not untrack it)') {
            assert lsFiles.exitCode == 0
        }

        Map result = runProc(cliCmd(assumeRepo, 'tmp/out-assume.groovy'))
        check('the CLI still rejects the modified content despite clean status and a tracked path') {
            assert result.exitCode != 0
        }
        check('stderr explains the blob-mismatch rejection') {
            assert (result.stderr as String).contains('does not match')
        }
        check('no output artifact is created') {
            assert !(new File(assumeRepo, 'tmp/out-assume.groovy').exists())
        }
    } finally {
        // Clear the index flag before deleteRecursive - the fixture repo is
        // about to be removed entirely regardless, but leaving no local git
        // state mutated outside this test's own lifetime is cheap and
        // matches the review's explicit ask.
        runProc(['git', 'update-index', '--no-assume-unchanged', 'apps/automation_map.groovy'], assumeRepo)
    }
} finally {
    deleteRecursive(assumeRepo)
}

println "${pass} passed, ${fail} failed"
if (fail > 0) System.exit(1)
