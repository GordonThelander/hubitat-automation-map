// Production package build orchestrator, phase 2d (backlog item 16 /
// production_build_methodology.md; review, queue 461 finding 2). Ties the
// app-source and manifest builders together into ONE supported entry point
// that binds both to a single, structurally-guaranteed source commit -
// closing the gap in an earlier design where two independent CLI
// invocations each verified their own provenance separately: nothing
// prevented HEAD changing between them, and no durable artifact proved a
// later app candidate and manifest candidate actually belonged together.
//
// Sequence:
//   1. Resolve ONE clean, Git-verified commit and verify the app source,
//      Dev manifest, and release-notes blobs against that SAME commit
//      (Profile.resolveVerifiedProvenanceMulti() - one HEAD read, not
//      three).
//   2. Generate both candidates in-process (Profile.generateAppCandidateSource(),
//      Mfst.generateManifestCandidateJson() - the same extracted, already-
//      tested core logic each standalone CLI uses) into an isolated
//      temporary sibling directory. Neither the app nor manifest candidate
//      is written to the real output location yet.
//   3. Compile-check the app candidate (groovyc, temporary output dir).
//   4. Run validate.ps1 in -BuildProfile ProductionCandidate mode against
//      the temporary candidates - the full production identity gate, and
//      the slowest step in the whole build.
//   5. Recheck the SAME three paths against git ONE MORE TIME - the LAST
//      check before anything is published, deliberately placed after every
//      long-running external gate (compilation, and especially the
//      validate.ps1 subprocess in step 4) rather than before them: an
//      earlier draft ran this recheck before step 4, leaving the exact
//      window it exists to close open for the whole duration of the
//      slowest gate (review, queue 463). If HEAD moved, or any of the
//      three files changed, since step 1, fail closed before anything is
//      published - this is what makes "mid-build change" fail closed
//      rather than silently publishing stale-relative-to-HEAD content.
//   6. Write a deterministic, machine-readable provenance sidecar
//      (provenance.json: source commit, both profile versions, both
//      candidate SHA-256 hashes - no wall-clock or machine-specific data)
//      into the same temporary directory.
//   7. Atomically publish: the temporary directory becomes the final output
//      directory via directory rename, only after every gate above passed.
//      A pre-existing output directory is invalidated (removed) up front,
//      before any of the above work begins (same "invalidate before real
//      work" discipline production-profile.groovy's own destination safety
//      uses, review queue 452) - any later failure at any step, including
//      steps 3-6, leaves the requested output directory absent, never a
//      stale prior success or a partially-written new one.
//
// Also exposes verifyPackageDirectory() - an independent check that a
// PUBLISHED package directory's app candidate, manifest candidate, and
// provenance.json are actually mutually consistent (their real SHA-256
// hashes match what the sidecar claims, and the app candidate's own
// embedded commit-SHA header agrees with the sidecar too) - the "package
// verifier" review 461 asks for, able to catch a directory reassembled by
// hand from two different builds' outputs even though this orchestrator's
// own atomic publish never produces one.
//
// Usage: groovy production-package.groovy <output-dir>
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

static int packageFormatVersion() { return 1 }

static String appRelativePath() { return 'apps/automation_map.groovy' }
static String manifestRelativePath() { return 'packageManifest.json' }
static String releaseNotesRelativePath() { return 'production-release-notes.txt' }

static String appCandidateFileName() { return 'automation_map.groovy' }
static String manifestCandidateFileName() { return 'packageManifest.json' }
static String sidecarFileName() { return 'provenance.json' }

static String sha256(byte[] bytes) {
    MessageDigest md = MessageDigest.getInstance('SHA-256')
    return md.digest(bytes).collect { String.format('%02x', it) }.join('')
}

static String sha256OfFile(File f) {
    return sha256(Files.readAllBytes(f.toPath()))
}

// 'groovy'/'groovyc' alone are shell scripts ProcessBuilder cannot launch
// directly on Windows - need the .bat launcher explicitly.
static String toolExecutable(String baseName) {
    return System.getProperty('os.name', '').toLowerCase().contains('windows') ? "${baseName}.bat" : baseName
}

static Map runProc(List<String> cmd, File cwd = null) {
    ProcessBuilder pb = new ProcessBuilder(cmd)
    if (cwd != null) pb.directory(cwd)
    // Same JAVA_HOME fix the integration test suites needed - the inherited
    // environment can carry a stale/invalid JAVA_HOME that breaks a nested
    // .bat launcher even though this running JVM is fine.
    pb.environment().put('JAVA_HOME', System.getProperty('java.home'))
    Process p = pb.start()
    String out = p.inputStream.getText('UTF-8')
    String err = p.errorStream.getText('UTF-8')
    int code = p.waitFor()
    return [exitCode: code, stdout: out, stderr: err]
}

static void deleteRecursive(File f) {
    if (f == null || !f.exists()) return
    if (f.isDirectory()) {
        f.listFiles()?.each { deleteRecursive(it) }
    }
    f.delete()
}

// Compiles the app candidate with the pinned groovyc into a throwaway
// output directory (deleted immediately after) - proves the candidate is
// syntactically valid Groovy, the one gate the two standalone generator
// scripts never run themselves (they prove token/structural equivalence
// with the Dev source, not that the RESULT still compiles).
static Map compileCheckAppCandidate(File repoRoot, File appCandidateFile) {
    File compileOut = new File(repoRoot, "tmp/production-package-compile-check-${UUID.randomUUID().toString().take(8)}")
    compileOut.mkdirs()
    try {
        Map r = runProc([toolExecutable('groovyc'), '-cp', repoRoot.absolutePath, '-d', compileOut.absolutePath, appCandidateFile.absolutePath], repoRoot)
        if (r.exitCode != 0) {
            return [ok: false, reason: "groovyc failed: ${(r.stderr as String).trim()}\n${(r.stdout as String).trim()}"]
        }
        return [ok: true]
    } finally {
        deleteRecursive(compileOut)
    }
}

// Runs the real validate.ps1 (never a copy) against the temporary candidate
// pair in -BuildProfile ProductionCandidate mode - the full production
// identity gate (app name, BUILD_CHANNEL, DIAGNOSTIC_LEVEL, manifest
// package/app identity, the fixed production app id, app/manifest version
// agreement, all manifest URLs on main, release-notes version binding and
// Dev-marker freedom).
static Map runProductionProfileValidation(File repoRoot, File appCandidateFile, File manifestCandidateFile) {
    File validatePs1 = new File(repoRoot, 'validate.ps1')
    Map r = runProc([
        'powershell', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', validatePs1.absolutePath,
        '-AppFile', appCandidateFile.absolutePath,
        '-ManifestFile', manifestCandidateFile.absolutePath,
        '-BuildProfile', 'ProductionCandidate',
    ], repoRoot)
    if (r.exitCode != 0) {
        return [ok: false, reason: "validate.ps1 -BuildProfile ProductionCandidate failed:\n${(r.stdout as String).trim()}\n${(r.stderr as String).trim()}"]
    }
    return [ok: true]
}

// The full generate-and-publish sequence described in the file header
// comment. Callers (the CLI body below, or a test harness) get a single
// entry point; every gate runs before anything is published, and any
// failure leaves outDir exactly as it would if this had never been called
// (absent, if it did not already exist - review, queue 452's destination-
// safety discipline extended to a whole directory).
//
// afterValidationHook exists ONLY for the test suite (review 463's required
// "deterministic negative test proving a HEAD or bound-input change after
// validation begins and before the final recheck leaves the requested
// output directory absent" - a controlled seam, not a timing-dependent
// race): a normal call passes null and it does nothing; the test harness
// passes a closure that mutates the fixture repo (e.g. commits a change)
// at the exact point between validate.ps1 succeeding and the final
// pre-publish recheck running, deterministically exercising the ordering
// this file's own header comment documents rather than hoping a real race
// happens to land in that window.
static Map buildPackage(File repoRoot, File toolDir, def Comparison, def StripComments, def Profile, def Mfst, File outDir, Closure afterValidationHook = null) {
    List<String> paths = [appRelativePath(), manifestRelativePath(), releaseNotesRelativePath()]

    Map provenance = Profile.resolveVerifiedProvenanceMulti(repoRoot, paths)
    if (!provenance.ok) {
        return [ok: false, stage: 'provenance verification', reason: provenance.reason]
    }
    String sha = provenance.sha as String
    Map<String, File> files = provenance.files as Map<String, File>

    File tempDir = new File(outDir.parentFile, ".production-package-build-${UUID.randomUUID().toString().take(8)}")
    tempDir.mkdirs()
    try {
        Map appResult = Profile.generateAppCandidateSource(Comparison, StripComments, files[appRelativePath()], sha)
        if (!appResult.ok) {
            return [ok: false, stage: "app generation (${appResult.stage})", reason: appResult.reason]
        }
        File tempAppFile = new File(tempDir, appCandidateFileName())
        tempAppFile.setText(appResult.finalSrc as String, 'UTF-8')

        Map manifestResult = Mfst.generateManifestCandidateJson(files[manifestRelativePath()], files[releaseNotesRelativePath()], Profile)
        if (!manifestResult.ok) {
            return [ok: false, stage: "manifest generation (${manifestResult.stage})", reason: manifestResult.reason]
        }
        File tempManifestFile = new File(tempDir, manifestCandidateFileName())
        tempManifestFile.setText(manifestResult.candidateJson as String, 'UTF-8')

        Map compileResult = compileCheckAppCandidate(repoRoot, tempAppFile)
        if (!compileResult.ok) {
            return [ok: false, stage: 'compilation', reason: compileResult.reason]
        }

        Map validationResult = runProductionProfileValidation(repoRoot, tempAppFile, tempManifestFile)
        if (!validationResult.ok) {
            return [ok: false, stage: 'production-profile validation', reason: validationResult.reason]
        }

        if (afterValidationHook != null) {
            afterValidationHook()
        }

        // Recheck the SAME three paths against git again, right before
        // publishing - review, queue 463: this must be the LAST check
        // before hashing/sidecar/publish, after every long-running external
        // gate (compilation, and especially the validate.ps1 subprocess,
        // the slowest step in the whole build), not before them. An
        // earlier draft ran this recheck before validate.ps1 rather than
        // after it, leaving exactly the window it exists to close open for
        // the whole duration of the slowest gate - caught in review, not by
        // this file's own tests, which is why item 3 below adds a
        // deterministic seam so a future reordering mistake like this one
        // would be caught here instead. If HEAD moved, or any of the three
        // files changed, since the FIRST resolution above, this build no
        // longer corresponds to a single verified commit and must not be
        // published.
        Map recheck = Profile.resolveVerifiedProvenanceMulti(repoRoot, paths)
        if (!recheck.ok) {
            return [ok: false, stage: 'pre-publish recheck', reason: recheck.reason]
        }
        if (recheck.sha != sha) {
            return [ok: false, stage: 'pre-publish recheck', reason: "source commit changed mid-build (was ${sha}, now ${recheck.sha}) - refusing to publish a package that no longer corresponds to one verified commit"]
        }

        String appHash = sha256OfFile(tempAppFile)
        String manifestHash = sha256OfFile(tempManifestFile)
        Map sidecar = [
            packageFormatVersion: packageFormatVersion(),
            sourceCommit: sha,
            appProfileVersion: Profile.profileVersion(),
            manifestProfileVersion: Mfst.manifestProfileVersion(),
            appCandidateSha256: appHash,
            manifestCandidateSha256: manifestHash,
        ]
        String sidecarJson = JsonOutput.prettyPrint(JsonOutput.toJson(sidecar)) + '\n'
        new File(tempDir, sidecarFileName()).setText(sidecarJson, 'UTF-8')

        // Atomic publish: remove any pre-existing outDir (already
        // invalidated by the CLI body before this function was even
        // called, per the same discipline - this is a defensive repeat,
        // matching writeCandidateAtomically()'s own idempotent redundancy),
        // then rename the fully-built temp directory into place. A rename
        // within the same parent directory is the closest thing to an
        // atomic directory swap the filesystem offers.
        if (outDir.exists()) {
            deleteRecursive(outDir)
        }
        if (!tempDir.renameTo(outDir)) {
            return [ok: false, stage: 'atomic publish', reason: "could not rename the built package directory into place at ${outDir.canonicalFile}"]
        }
        return [
            ok: true, sha: sha, appCandidateSha256: appHash, manifestCandidateSha256: manifestHash,
            appStageAStream: appResult.stageAStream, appStageAStrings: appResult.stageAStrings,
            appStageBStream: appResult.stageBStream, appAllowlistEntries: appResult.allowlistEntries,
            manifestAllowlistEntries: manifestResult.allowlistEntries,
        ]
    } finally {
        // No-op once renameTo() has already moved tempDir away (renameTo
        // leaves nothing at the old path to delete); only cleans up on a
        // failure path where the temp directory still exists at its
        // original location.
        deleteRecursive(tempDir)
    }
}

// Independent verification that a PUBLISHED package directory is internally
// consistent - the app candidate, manifest candidate, and provenance.json
// actually belong together, checked by recomputing real hashes rather than
// trusting the sidecar's own claims at face value. Catches a directory
// reassembled by hand from two different builds' outputs (review, queue
// 461's required negative case), even though buildPackage()'s own atomic
// publish never produces one itself.
static Map verifyPackageDirectory(File packageDir) {
    File appFile = new File(packageDir, appCandidateFileName())
    File manifestFile = new File(packageDir, manifestCandidateFileName())
    File sidecarFile = new File(packageDir, sidecarFileName())
    for (f in [appFile, manifestFile, sidecarFile]) {
        if (!f.exists()) {
            return [ok: false, reason: "expected package file not found: ${f.name}"]
        }
    }
    Object sidecar
    try {
        sidecar = new JsonSlurper().parseText(sidecarFile.getText('UTF-8'))
    } catch (Exception e) {
        return [ok: false, reason: "${sidecarFile.name} is not valid JSON: ${e.message}"]
    }
    String actualAppHash = sha256OfFile(appFile)
    if (actualAppHash != sidecar.appCandidateSha256) {
        return [ok: false, reason: "app candidate hash does not match provenance.json (recorded ${sidecar.appCandidateSha256}, actual ${actualAppHash}) - this file does not belong to the same build as the sidecar"]
    }
    String actualManifestHash = sha256OfFile(manifestFile)
    if (actualManifestHash != sidecar.manifestCandidateSha256) {
        return [ok: false, reason: "manifest candidate hash does not match provenance.json (recorded ${sidecar.manifestCandidateSha256}, actual ${actualManifestHash}) - this file does not belong to the same build as the sidecar"]
    }
    // Second, independent signal: the app candidate's OWN embedded commit
    // SHA (in its header comment) must agree with the sidecar's claimed
    // sourceCommit too - not just the hash bookkeeping above.
    String appText = appFile.getText('UTF-8')
    def m = (appText =~ /commit ([0-9a-f]{40})/)
    if (!m.find()) {
        return [ok: false, reason: 'could not find an embedded commit SHA in the app candidate header']
    }
    String embeddedSha = m.group(1)
    if (embeddedSha != sidecar.sourceCommit) {
        return [ok: false, reason: "app candidate's embedded commit (${embeddedSha}) does not match provenance.json's sourceCommit (${sidecar.sourceCommit})"]
    }
    return [ok: true, sourceCommit: sidecar.sourceCommit]
}

// --- CLI ---

File thisScriptFile
try {
    thisScriptFile = new File(this.class.protectionDomain.codeSource.location.toURI())
} catch (Exception e) {
    System.err.println("Could not resolve this script's own source location - refusing to run: ${e.message}")
    System.exit(1)
}
File toolDir = thisScriptFile.parentFile
File repoRoot = toolDir.parentFile.parentFile
File profileFile = new File(toolDir, 'production-profile.groovy')
File manifestFile = new File(toolDir, 'production-manifest.groovy')
File guardFile = new File(toolDir, 'pinned-guard.groovy')
File comparisonFile = new File(toolDir, 'comparison.groovy')
File stripperFile = new File(toolDir, 'strip-comments.groovy')
[profileFile, manifestFile, guardFile, comparisonFile, stripperFile].each { File f ->
    if (!f.exists()) {
        System.err.println("Expected ${f.name} next to this script at ${f} - not found. " +
            'Run this script from its own checked-out location, not a copy.')
        System.exit(1)
    }
}
def guard = new GroovyClassLoader(this.class.classLoader).parseClass(guardFile)
guard.require()
def Comparison = new GroovyClassLoader(this.class.classLoader).parseClass(comparisonFile)
def StripComments = new GroovyClassLoader(this.class.classLoader).parseClass(stripperFile)
def Profile = new GroovyClassLoader(this.class.classLoader).parseClass(profileFile)
def Mfst = new GroovyClassLoader(this.class.classLoader).parseClass(manifestFile)

if (args.size() != 1) {
    System.err.println('Usage: groovy production-package.groovy <output-dir>')
    System.exit(1)
}
File outDir = new File(args[0])
File tmpRoot = new File(repoRoot, 'tmp')
String outCanonicalPath = outDir.canonicalFile.path
if (!outCanonicalPath.startsWith(tmpRoot.canonicalPath + File.separator)) {
    System.err.println("Refusing to run: output path ${outCanonicalPath} is not under ${tmpRoot.canonicalPath} - " +
        'generated packages must never land at the repository root or overwrite tracked Dev source.')
    System.exit(1)
}

// Invalidate any pre-existing output directory NOW, before any provenance
// or generation work begins - review, queue 452's discipline extended to a
// whole directory: any later failure leaves this path absent, not a stale
// prior success sitting at the exact path just requested.
deleteRecursive(outDir)

Map result = buildPackage(repoRoot, toolDir, Comparison, StripComments, Profile, Mfst, outDir)
if (!result.ok) {
    System.err.println("FAIL ${result.stage}: ${result.reason}")
    System.exit(1)
}

println "OK - production package built (format v${packageFormatVersion()})."
println 'NOTE: this package has not been deployed, promoted, or released - generation and local ' +
    'validation only. Production Hub deployment, GitHub main, promotion, tagging, release, and HPM ' +
    'publication remain separately gated.'
println "Source commit: ${result.sha} (single verified commit for app source, Dev manifest, and release notes together)."
println "App candidate:      SHA-256 ${result.appCandidateSha256}"
println "Manifest candidate: SHA-256 ${result.manifestCandidateSha256}"
println "Gates passed, in order: app stage A/B token-structure equivalence, manifest allowlist/structural comparison, " +
    "compilation (groovyc), production-profile validation (validate.ps1), pre-publish source-identity recheck (last, after every long-running gate)."
println "Published to: ${outDir.canonicalFile}"
println "  ${appCandidateFileName()}, ${manifestCandidateFileName()}, ${sidecarFileName()}"
