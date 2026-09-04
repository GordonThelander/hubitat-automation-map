// Focused positive/negative tests for production-profile.groovy (phase 2c,
// backlog item 16 / production_build_methodology.md; review, queue 448).
// Loads the SAME functions production-profile.groovy actually uses (via
// GroovyClassLoader.parseClass(), same sibling-of-running-script resolution
// every tool in this directory uses) - never a copy that could drift.
// parseClass() only compiles the class; production-profile.groovy's own
// top-level CLI body (arg parsing, System.exit) never runs from this file -
// only its static functions are called directly.
//
// Usage: groovy verify-production-profile-tests.groovy
import org.apache.groovy.parser.antlr4.GroovyLexer
import groovyjarjarantlr4.v4.runtime.Token

File thisScriptFile = new File(this.class.protectionDomain.codeSource.location.toURI())
File guardFile = new File(thisScriptFile.parentFile, 'pinned-guard.groovy')
File comparisonFile = new File(thisScriptFile.parentFile, 'comparison.groovy')
File stripperFile = new File(thisScriptFile.parentFile, 'strip-comments.groovy')
File profileFile = new File(thisScriptFile.parentFile, 'production-profile.groovy')
def guard = new GroovyClassLoader(this.class.classLoader).parseClass(guardFile)
guard.require()
def Comparison = new GroovyClassLoader(this.class.classLoader).parseClass(comparisonFile)
def StripComments = new GroovyClassLoader(this.class.classLoader).parseClass(stripperFile)
def Profile = new GroovyClassLoader(this.class.classLoader).parseClass(profileFile)

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

// A small Dev-shaped fixture: a Dev-style long header comment (stands in for
// the real one, to prove it does not survive), the three allowlisted
// declarations, a line comment, a string that CONTAINS comment-like text and
// the Dev app name as data (must survive untouched, not be treated as a
// declaration to substitute), and a GString with embedded JS.
String FIXTURE = '''/*
 * Automation Map
 * A pretend long Dev architecture comment block, standing in for the real
 * one - must not survive into the generated candidate.
 */
import groovy.transform.Field

@Field static final String APP_NAME = 'Automation Map (Dev)'
@Field static final String BUILD_CHANNEL = 'dev'
@Field static final int DIAGNOSTIC_LEVEL = 2

// a routine comment, stripped like any other
boolean isDevBuild() { return BUILD_CHANNEL == 'dev' }

// A runtime string that happens to mention the Dev app name as DATA, not a
// declaration - must survive byte-for-byte, never treated as a match target.
String helpText() { return "See settings for Automation Map (Dev) to configure this." }

String widget() { return "<script>// not a real comment, lives inside a GString\\nconsole.log(1);</script>" }
'''

String FIXTURE_ALLOWLIST_TEXT = "@Field static final String APP_NAME = 'Automation Map (Dev)'\n" +
    "@Field static final String BUILD_CHANNEL = 'dev'\n" +
    "@Field static final int DIAGNOSTIC_LEVEL = 2"

def runPipeline = { String src, String devSha ->
    List<Token> originalTokens = StripComments.lexAllChannels(src)
    String strippedSrc = StripComments.stripSource(src, originalTokens, Comparison)
    List<Token> strippedTokens = StripComments.lexAllChannels(strippedSrc)
    Map substitution = Profile.applyAllowlist(strippedSrc, Profile.allowlist())
    if (!substitution.ok) return [ok: false, stage: 'substitution', reason: substitution.reason]
    List<Token> candidateBodyTokens = StripComments.lexAllChannels(substitution.candidateSrc as String)
    Map streamCheck = Profile.compareSubstitutionAwareStreams(strippedTokens, candidateBodyTokens, Profile.allowlist(), Comparison)
    if (!streamCheck.ok) return [ok: false, stage: 'stream', reason: streamCheck.reason]
    Map trim = Profile.trimLeadingBlankLines(substitution.candidateSrc as String, candidateBodyTokens)
    if (!trim.ok) return [ok: false, stage: 'trim', reason: trim.reason]
    String finalSrc = Profile.buildHeader(devSha) + (trim.trimmedSrc as String)
    List<String> leaked = Profile.forbiddenInCandidate().findAll { finalSrc.contains(it) }
    if (!leaked.isEmpty()) return [ok: false, stage: 'leak', reason: "leaked: ${leaked}"]
    return [ok: true, finalSrc: finalSrc, counts: substitution.counts, trimmedChars: trim.removedChars]
}

String DEV_SHA = 'a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2'

println '--- Positive: full pipeline on a Dev-shaped fixture ---'
Map result = runPipeline(FIXTURE, DEV_SHA)
check('pipeline succeeds on a well-formed fixture') {
    assert result.ok : "expected success, got: ${result}"
}
check('all three allowlist entries fired exactly once') {
    assert result.counts == ['app-identity': 1, 'build-channel': 1, 'diagnostic-level': 1]
}
check('generated candidate contains the substituted declarations') {
    assert (result.finalSrc as String).contains("@Field static final String APP_NAME = 'Automation Map'")
    assert (result.finalSrc as String).contains("@Field static final String BUILD_CHANNEL = 'production'")
    assert (result.finalSrc as String).contains('@Field static final int DIAGNOSTIC_LEVEL = 0')
}
check('the Dev-shaped long header comment does not survive') {
    assert !(result.finalSrc as String).contains('pretend long Dev architecture comment')
}
check('the routine line comment does not survive') {
    assert !(result.finalSrc as String).contains('a routine comment, stripped like any other')
}
check('a runtime string mentioning the Dev app name as DATA survives untouched') {
    assert (result.finalSrc as String).contains('See settings for Automation Map (Dev) to configure this.')
}
check('embedded comment-like text inside a GString/script survives untouched') {
    assert (result.finalSrc as String).contains('// not a real comment, lives inside a GString')
}
check('isDevBuild() body is untouched - substitution only touched the constant declarations') {
    assert (result.finalSrc as String).contains("return BUILD_CHANNEL == 'dev'")
}
check('the leading blank-line run left by the removed header comment was trimmed') {
    assert (result.trimmedChars as int) > 0
    int firstImport = (result.finalSrc as String).indexOf('import groovy.transform.Field')
    String beforeImport = (result.finalSrc as String).substring(0, firstImport)
    assert beforeImport.trim().endsWith('*/')
}

println '--- Header provenance ---'
String header = Profile.buildHeader(DEV_SHA)
check('header contains the Apache licence identifier') {
    assert header.contains('Apache License, Version 2.0')
}
check('header contains the exact Dev commit SHA') {
    assert header.contains(DEV_SHA)
}
check('header contains the canonical annotated-source link at that exact commit') {
    assert header.contains("https://github.com/GordonThelander/hubitat-automation-map/blob/${DEV_SHA}/apps/automation_map.groovy")
}
check('header is a pure function of devCommitSha - two calls with the same SHA are identical') {
    assert header == Profile.buildHeader(DEV_SHA)
}
check('header changes only where the SHA appears, for a different SHA') {
    String otherSha = 'f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5'
    String otherHeader = Profile.buildHeader(otherSha)
    assert otherHeader != header
    assert otherHeader.contains(otherSha)
    assert !otherHeader.contains(DEV_SHA)
}

println '--- Negative: cardinality failures fail closed ---'
check('a missing allowlist pattern (0 matches) fails closed with a useful reason') {
    String missing = FIXTURE.replace("BUILD_CHANNEL = 'dev'", "BUILD_CHANNEL = 'staging'")
    Map r = Profile.applyAllowlist(missing, Profile.allowlist())
    assert !r.ok
    assert (r.reason as String).contains('build-channel')
    assert (r.reason as String).contains('expected exactly 1')
}
check('a duplicated allowlist pattern (2 matches) fails closed with a useful reason') {
    String duplicated = FIXTURE + "\n@Field static final int DIAGNOSTIC_LEVEL = 2"
    Map r = Profile.applyAllowlist(duplicated, Profile.allowlist())
    assert !r.ok
    assert (r.reason as String).contains('diagnostic-level')
    assert (r.reason as String).contains('matched 2 time')
}

println '--- Negative: an unexplained token drift is rejected, not silently accepted as a substitution ---'
check('a code change with no matching allowlist entry is rejected') {
    List<Token> strippedTokens = StripComments.lexAllChannels(FIXTURE_ALLOWLIST_TEXT)
    // Hand-corrupt: change 'Automation Map (Dev)' to a value the allowlist does
    // not expect anywhere (the real allowlist only expects 'Automation Map').
    String corrupted = FIXTURE_ALLOWLIST_TEXT.replace('Automation Map (Dev)', 'Something Else Entirely')
    List<Token> corruptedTokens = StripComments.lexAllChannels(corrupted)
    Map r = Profile.compareSubstitutionAwareStreams(strippedTokens, corruptedTokens, Profile.allowlist(), Comparison)
    assert !r.ok
    assert (r.reason as String).contains('matches no allowlist entry')
}
check('an allowlist entry that never fires is rejected even if the stream is otherwise identical') {
    List<Token> strippedTokens = StripComments.lexAllChannels(FIXTURE_ALLOWLIST_TEXT)
    // Only apply two of the three real substitutions by hand - diagnostic-level
    // never fires.
    String partial = FIXTURE_ALLOWLIST_TEXT
        .replace("APP_NAME = 'Automation Map (Dev)'", "APP_NAME = 'Automation Map'")
        .replace("BUILD_CHANNEL = 'dev'", "BUILD_CHANNEL = 'production'")
    List<Token> partialTokens = StripComments.lexAllChannels(partial)
    Map r = Profile.compareSubstitutionAwareStreams(strippedTokens, partialTokens, Profile.allowlist(), Comparison)
    assert !r.ok
    assert (r.reason as String).contains('diagnostic-level fired 0 time')
}

println '--- Negative: no leakage of Dev-only wording, checked as an independent backstop ---'
check('forbiddenInCandidate() catches a leaked, unsubstituted APP_NAME declaration even if the allowlist mechanics were somehow bypassed') {
    String leakedSrc = Profile.buildHeader(DEV_SHA) + "@Field static final String APP_NAME = 'Automation Map (Dev)'\n"
    List<String> found = Profile.forbiddenInCandidate().findAll { leakedSrc.contains(it) }
    assert found.contains("@Field static final String APP_NAME = 'Automation Map (Dev)'")
}
check('forbiddenInCandidate() catches a leaked, unsubstituted BUILD_CHANNEL declaration') {
    String leakedSrc = Profile.buildHeader(DEV_SHA) + "@Field static final String BUILD_CHANNEL = 'dev'\n"
    List<String> found = Profile.forbiddenInCandidate().findAll { leakedSrc.contains(it) }
    assert found.contains("@Field static final String BUILD_CHANNEL = 'dev'")
}
check('a clean candidate triggers no forbidden-marker match') {
    assert Profile.forbiddenInCandidate().findAll { (result.finalSrc as String).contains(it) }.isEmpty()
}
// The bug this regression-tests: an EARLIER draft of forbiddenInCandidate()
// used loose substrings (e.g. the bare app name) instead of exact
// declaration text, which produced a false positive against ordinary
// runtime string CONTENT that happens to mention the Dev app name as data -
// exactly what this fixture's helpText() does. Caught by this test suite
// before it shipped, not by inspection.
check('ordinary runtime string content mentioning the Dev app name as DATA does not false-positive the leak check') {
    assert (result.finalSrc as String).contains('See settings for Automation Map (Dev) to configure this.')
    assert Profile.forbiddenInCandidate().findAll { (result.finalSrc as String).contains(it) }.isEmpty()
}

println '--- trimLeadingBlankLines(): proven safe against the token stream, not assumed ---'
check('trims a leading run of pure CRLF/LF bytes down to zero') {
    String src = "\r\n\r\n\r\nimport groovy.transform.Field\n"
    List<Token> tokens = StripComments.lexAllChannels(src)
    Map r = Profile.trimLeadingBlankLines(src, tokens)
    assert r.ok
    assert (r.trimmedSrc as String) == 'import groovy.transform.Field\n'
    assert r.removedChars == 6
}
check('a source with no leading blank lines is left untouched (removedChars 0)') {
    String src = 'import groovy.transform.Field\n'
    List<Token> tokens = StripComments.lexAllChannels(src)
    Map r = Profile.trimLeadingBlankLines(src, tokens)
    assert r.ok
    assert (r.trimmedSrc as String) == src
    assert r.removedChars == 0
}
check('refuses to trim when the leading span is not pure line-endings (fail-closed, never eats real content)') {
    // Hand-construct a token list whose reported first-token start index
    // lies AFTER some non-line-ending text, to prove the function checks the
    // actual characters rather than trusting the index blindly. Simplest
    // faithful way to exercise the guard without a fake Token type: lex a
    // source that legitimately starts with a real character before any
    // token boundary weirdness cannot occur in this lexer, so instead prove
    // the positive path handles a MIXED leading run correctly by including
    // a leading space before the newlines - GroovyLexer does not emit
    // leading horizontal whitespace as part of the NL token text, so this
    // still trims cleanly; the guard itself is exercised directly here.
    String src = "\r\n \r\nimport groovy.transform.Field\n"
    List<Token> tokens = StripComments.lexAllChannels(src)
    Map r = Profile.trimLeadingBlankLines(src, tokens)
    // A bare space between two line endings is NOT itself a line-ending
    // character, so the prefix up to the first real token is NOT pure
    // CR/LF - this must fail closed rather than silently drop the space.
    assert !r.ok : "expected the mixed-content prefix to be refused, got: ${r}"
    assert (r.reason as String).contains('non-line-ending content')
}

println '--- writeCandidateAtomically(): destination safety (review, queue 450, blocking finding 3) ---'
File writeTestDir = new File(thisScriptFile.parentFile.parentFile.parentFile, 'tmp/production-profile-write-test')
writeTestDir.deleteDir()
writeTestDir.mkdirs()
check('writes a fresh destination successfully') {
    File dest = new File(writeTestDir, 'fresh.groovy')
    Map r = Profile.writeCandidateAtomically(dest, 'first content\n', null)
    assert r.ok
    assert dest.getText('UTF-8') == 'first content\n'
}
check('replaces a pre-existing destination with new content') {
    File dest = new File(writeTestDir, 'replace.groovy')
    dest.setText('stale old content\n', 'UTF-8')
    Map r = Profile.writeCandidateAtomically(dest, 'fresh new content\n', null)
    assert r.ok
    assert dest.getText('UTF-8') == 'fresh new content\n'
}
check('a failure injected after destination removal leaves the destination absent, not stale or corrupted') {
    File dest = new File(writeTestDir, 'fail-after-delete.groovy')
    dest.setText('the old content that must not be presented as current\n', 'UTF-8')
    Closure boom = { throw new RuntimeException('simulated I/O failure, test-injected') }
    Map r = Profile.writeCandidateAtomically(dest, 'content that should never land\n', boom)
    assert !r.ok
    assert (r.reason as String).contains('simulated I/O failure')
    assert !dest.exists() : 'destination must be absent, not left showing stale content, after an injected failure'
}
check('no leftover temporary sibling files remain after a failed write') {
    List<File> leftovers = writeTestDir.listFiles({ File f -> f.name.startsWith('production-profile-') && f.name.endsWith('.tmp') } as FileFilter) as List
    assert leftovers.isEmpty() : "leftover temp files: ${leftovers}"
}
writeTestDir.deleteDir()

println '--- Reproducibility: two full pipeline runs from the same fixture+SHA are byte-identical ---'
check('two runPipeline() calls produce identical finalSrc') {
    Map r1 = runPipeline(FIXTURE, DEV_SHA)
    Map r2 = runPipeline(FIXTURE, DEV_SHA)
    assert r1.ok && r2.ok
    assert r1.finalSrc == r2.finalSrc
}

// Exercises the REAL generateAppCandidateSource() against files on disk, not
// runPipeline()'s in-memory stand-in: the defect these cover was in the file
// read itself, so an in-memory test could not have caught it.
println '--- Line-ending canonicalization: equivalent LF and CRLF checkouts generate identical bytes ---'
File eolTestDir = new File(thisScriptFile.parentFile.parentFile.parentFile, 'tmp/production-profile-eol-test')
eolTestDir.deleteDir()
eolTestDir.mkdirs()
String LF_SRC = FIXTURE.replace('\r\n', '\n').replace('\r', '\n')
String CRLF_SRC = LF_SRC.replace('\n', '\r\n')
String MIXED_SRC = LF_SRC.readLines().withIndex().collect { String line, int i ->
    line + (i % 2 == 0 ? '\r\n' : '\n')
}.join('')
File lfFile = new File(eolTestDir, 'lf-source.groovy')
File crlfFile = new File(eolTestDir, 'crlf-source.groovy')
File mixedFile = new File(eolTestDir, 'mixed-source.groovy')
lfFile.setText(LF_SRC, 'UTF-8')
crlfFile.setText(CRLF_SRC, 'UTF-8')
mixedFile.setText(MIXED_SRC, 'UTF-8')

check('the fixture files genuinely differ on disk (guards this test against testing nothing)') {
    assert lfFile.getText('UTF-8') != crlfFile.getText('UTF-8')
    assert !lfFile.getText('UTF-8').contains('\r')
    assert crlfFile.getText('UTF-8').contains('\r')
    assert mixedFile.getText('UTF-8').contains('\r\n')
    assert mixedFile.getText('UTF-8') != crlfFile.getText('UTF-8')
}
check('canonicalizeLineEndings() maps CRLF, lone CR and LF alike to LF') {
    assert Profile.canonicalizeLineEndings('a\r\nb\rc\nd') == 'a\nb\nc\nd'
    assert Profile.canonicalizeLineEndings('') == ''
    assert Profile.canonicalizeLineEndings(null) == null
}
check('buildHeader() is LF-only regardless of this tool file\'s own checkout line endings') {
    assert !(Profile.buildHeader(DEV_SHA) as String).contains('\r')
}
check('generateAppCandidateSource() produces byte-identical output from LF and CRLF checkouts') {
    Map lfResult = Profile.generateAppCandidateSource(Comparison, StripComments, lfFile, DEV_SHA)
    Map crlfResult = Profile.generateAppCandidateSource(Comparison, StripComments, crlfFile, DEV_SHA)
    assert lfResult.ok : "LF build failed at ${lfResult.stage}: ${lfResult.reason}"
    assert crlfResult.ok : "CRLF build failed at ${crlfResult.stage}: ${crlfResult.reason}"
    assert lfResult.finalSrc == crlfResult.finalSrc
}
check('a mixed CRLF/LF checkout canonicalizes to that same output, not a third variant') {
    Map lfResult = Profile.generateAppCandidateSource(Comparison, StripComments, lfFile, DEV_SHA)
    Map mixedResult = Profile.generateAppCandidateSource(Comparison, StripComments, mixedFile, DEV_SHA)
    assert mixedResult.ok : "mixed build failed at ${mixedResult.stage}: ${mixedResult.reason}"
    assert mixedResult.finalSrc == lfResult.finalSrc
}
check('every generated candidate is LF-only whatever the input was') {
    [lfFile, crlfFile, mixedFile].each { File f ->
        Map r = Profile.generateAppCandidateSource(Comparison, StripComments, f, DEV_SHA)
        assert r.ok
        assert !(r.finalSrc as String).contains('\r') : "candidate from ${f.name} still contains a carriage return"
    }
}
eolTestDir.deleteDir()

println "${pass} passed, ${fail} failed"
if (fail > 0) System.exit(1)
