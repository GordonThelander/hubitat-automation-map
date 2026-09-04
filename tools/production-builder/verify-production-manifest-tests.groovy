// Focused positive/negative tests for production-manifest.groovy (phase 2d,
// backlog item 16 / production_build_methodology.md; review, queue 456,
// 459+). Loads the SAME functions production-manifest.groovy actually uses
// (via GroovyClassLoader.parseClass(), same sibling-of-running-script
// resolution every tool in this directory uses) - never a copy that could
// drift. parseClass() only compiles the class; production-manifest.groovy's
// own top-level CLI body never runs from this file - only its static
// functions are called directly.
//
// Usage: groovy verify-production-manifest-tests.groovy
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

File thisScriptFile = new File(this.class.protectionDomain.codeSource.location.toURI())
File manifestBuilderFile = new File(thisScriptFile.parentFile, 'production-manifest.groovy')
File profileFile = new File(thisScriptFile.parentFile, 'production-profile.groovy')
def Mfst = new GroovyClassLoader(this.class.classLoader).parseClass(manifestBuilderFile)
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

// A small Dev-shaped fixture manifest. Its own releaseNotes is left in the
// Dev-tracking shape deliberately (DEV CHANNEL banner, dev-branch link) to
// prove that field is NEVER read from here at all - the production value
// comes only from the separately supplied releaseNotesText argument now
// (review, queue 459: an earlier draft derived it from this field via a
// strip-the-banner transform; Gordon reviewed that output directly and
// asked for a short curated summary instead, which cannot be derived
// mechanically from the accumulated Dev history).
Map fixtureManifest() {
    return [
        packageName: 'Automation Map (Dev)',
        author: 'Gordon Thelander',
        version: '2.2.0',
        minimumHEVersion: '2.5.1',
        dateReleased: '2026-09-02',
        documentationLink: 'https://github.com/GordonThelander/hubitat-automation-map/blob/dev/README.md',
        communityLink: 'https://community.hubitat.com/t/release-hubitat-automation-map/165524',
        releaseNotes: 'DEV CHANNEL - this manifest tracks the development branch, not the production release.\n\n' +
            '2.2.0 - Fixture release notes mentioning Automation Map (Dev) as plain prose, not a declaration.\n\n' +
            'Complete history: https://github.com/GordonThelander/hubitat-automation-map/blob/dev/CHANGELOG.md',
        apps: [
            [
                id: '2c2c28d6-f6eb-4c88-afad-d9d32a6013bc',
                name: 'Automation Map (Dev)',
                namespace: 'Hubitat Integrations',
                location: 'https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/dev/apps/automation_map.groovy',
                required: true,
                oauth: true,
                primary: true,
                version: '2.2.0',
            ],
        ],
    ]
}

// A representative curated production release-notes fixture, matching the
// actual production-release-notes.txt shape: a one-line description, then
// bullets of user-facing changes. Deliberately mentions "Automation Map
// (Dev)" nowhere - a real curated file must not.
String FIXTURE_RELEASE_NOTES = 'v2.2.0: Automation Map visualizes how your installed Hubitat apps and devices ' +
    'relate to each other.\n\nSince the last production release (2.1.1):\n\n- Improved nested Device discovery\n' +
    '- Refreshed the visual style.\n'

def runPipeline = { Map fixture, String releaseNotesText ->
    Map original = new JsonSlurper().parseText(JsonOutput.toJson(fixture))
    Map candidateSeed = new JsonSlurper().parseText(JsonOutput.toJson(fixture))
    List<Map> allowlist = Mfst.manifestAllowlist(releaseNotesText)
    Map substitution = Mfst.applyManifestAllowlist(candidateSeed, allowlist)
    if (!substitution.ok) return [ok: false, stage: 'substitution', reason: substitution.reason]
    Map structureCheck = Mfst.compareManifestStructures(original, substitution.candidate, allowlist)
    if (!structureCheck.ok) return [ok: false, stage: 'structure', reason: structureCheck.reason]
    String candidateJson = JsonOutput.prettyPrint(JsonOutput.toJson(substitution.candidate)) + '\n'
    Map leakCheck = Mfst.verifyNoLeakedDevValues(candidateJson, allowlist)
    if (!leakCheck.ok) return [ok: false, stage: 'leak', reason: leakCheck.reason]
    return [ok: true, candidateJson: candidateJson, candidate: substitution.candidate]
}

println '--- Positive: full pipeline on a Dev-shaped fixture manifest ---'
Map result = runPipeline(fixtureManifest(), FIXTURE_RELEASE_NOTES)
check('pipeline succeeds on a well-formed fixture') {
    assert result.ok : "expected success, got: ${result}"
}
check('packageName and apps[0].name drop the (Dev) suffix') {
    Map c = result.candidate as Map
    assert c.packageName == 'Automation Map'
    assert (c.apps[0] as Map).name == 'Automation Map'
}
check('documentationLink and apps[0].location swap dev -> main') {
    Map c = result.candidate as Map
    assert c.documentationLink == 'https://github.com/GordonThelander/hubitat-automation-map/blob/main/README.md'
    assert (c.apps[0] as Map).location == 'https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/main/apps/automation_map.groovy'
}
check('apps[0].id becomes the fixed production id, not a transform of the Dev id') {
    Map c = result.candidate as Map
    assert (c.apps[0] as Map).id == Mfst.productionAppId()
    assert (c.apps[0] as Map).id != '2c2c28d6-f6eb-4c88-afad-d9d32a6013bc'
}
check('releaseNotes is the externally supplied curated text verbatim, NOT derived from the Dev manifest field') {
    assert (result.candidate as Map).releaseNotes == FIXTURE_RELEASE_NOTES
}
check('the Dev manifest\'s own releaseNotes (DEV CHANNEL banner, fixture prose) does not survive anywhere') {
    String candidateJson = result.candidateJson as String
    assert !candidateJson.contains('DEV CHANNEL')
    assert !candidateJson.contains('Fixture release notes mentioning Automation Map (Dev) as plain prose')
}
check('carried-through fields (author, version, dateReleased, minimumHEVersion, communityLink) are unchanged') {
    Map c = result.candidate as Map
    assert c.author == 'Gordon Thelander'
    assert c.version == '2.2.0'
    assert c.dateReleased == '2026-09-02'
    assert c.minimumHEVersion == '2.5.1'
    assert c.communityLink == 'https://community.hubitat.com/t/release-hubitat-automation-map/165524'
}
check('carried-through apps[0] fields (namespace, required, oauth, primary, version) are unchanged') {
    Map a = (result.candidate as Map).apps[0] as Map
    assert a.namespace == 'Hubitat Integrations'
    assert a.required == true
    assert a.oauth == true
    assert a.primary == true
    assert a.version == '2.2.0'
}
check('the candidate is valid, re-parseable JSON with the same key set as the original') {
    Map reparsed = new JsonSlurper().parseText(result.candidateJson as String)
    assert reparsed.keySet() == fixtureManifest().keySet()
}

println '--- Negative: a fixed-value entry whose input does not match expectedFrom fails closed ---'
check('an already-transformed packageName is refused, not silently re-accepted') {
    Map fixture = fixtureManifest()
    fixture.packageName = 'Automation Map'
    Map parsed = new JsonSlurper().parseText(JsonOutput.toJson(fixture))
    Map r = Mfst.applyManifestAllowlist(parsed, Mfst.manifestAllowlist(FIXTURE_RELEASE_NOTES))
    assert !r.ok
    assert (r.reason as String).contains('package-name')
}
// Review, queue 461: apps[0].id previously had no expectedFrom at all, so
// ANY input value - an unrecognised id, or one already equal to the
// production target - was silently accepted and overwritten rather than
// asserted. Both cases below are the exact regression this closes.
check('an unrecognised apps[0].id (not the known Dev id) is refused, not silently normalized to production') {
    Map fixture = fixtureManifest()
    fixture.apps[0].id = 'totally-unknown-id-not-dev-not-production'
    Map parsed = new JsonSlurper().parseText(JsonOutput.toJson(fixture))
    Map r = Mfst.applyManifestAllowlist(parsed, Mfst.manifestAllowlist(FIXTURE_RELEASE_NOTES))
    assert !r.ok
    assert (r.reason as String).contains('app-id')
}
check('an already-production apps[0].id is refused, not silently re-accepted') {
    Map fixture = fixtureManifest()
    fixture.apps[0].id = Mfst.productionAppId()
    Map parsed = new JsonSlurper().parseText(JsonOutput.toJson(fixture))
    Map r = Mfst.applyManifestAllowlist(parsed, Mfst.manifestAllowlist(FIXTURE_RELEASE_NOTES))
    assert !r.ok
    assert (r.reason as String).contains('app-id')
}
check('the known Dev id is accepted and becomes the fixed production id') {
    Map fixture = fixtureManifest()
    assert fixture.apps[0].id == Mfst.devAppId() : 'fixture setup assumption broken'
    Map parsed = new JsonSlurper().parseText(JsonOutput.toJson(fixture))
    Map r = Mfst.applyManifestAllowlist(parsed, Mfst.manifestAllowlist(FIXTURE_RELEASE_NOTES))
    assert r.ok
    assert (r.candidate as Map).apps[0].id == Mfst.productionAppId()
}

println '--- verifyReleaseNotesText(): fails closed on an empty, Dev-shaped, or stale-version file, independent of the allowlist mechanics ---'
check('an empty release-notes text is refused') {
    Map r = Mfst.verifyReleaseNotesText('', '2.2.0')
    assert !r.ok
    assert (r.reason as String).contains('empty')
}
check('a whitespace-only release-notes text is refused') {
    Map r = Mfst.verifyReleaseNotesText('   \n  \n', '2.2.0')
    assert !r.ok
}
check('release-notes text still carrying the DEV CHANNEL banner is refused') {
    Map r = Mfst.verifyReleaseNotesText('v2.2.0: DEV CHANNEL - this manifest tracks the development branch.\n\nSomething.', '2.2.0')
    assert !r.ok
    assert (r.reason as String).contains('DEV CHANNEL')
}
check('release-notes text containing a stray dev-branch link is refused') {
    Map r = Mfst.verifyReleaseNotesText('v2.2.0: See https://github.com/GordonThelander/hubitat-automation-map/blob/dev/CHANGELOG.md for more.', '2.2.0')
    assert !r.ok
    assert (r.reason as String).contains('blob/dev/')
}
check('a genuinely curated production release-notes text passes for its own version') {
    Map r = Mfst.verifyReleaseNotesText(FIXTURE_RELEASE_NOTES, '2.2.0')
    assert r.ok
}
check('review 461: a clean, well-formed, but STALE release-notes file (wrong version prefix) is refused, not silently shipped') {
    Map r = Mfst.verifyReleaseNotesText(FIXTURE_RELEASE_NOTES, '2.3.0')
    assert !r.ok
    assert (r.reason as String).contains('stale')
}
check('a release-notes text missing the version prefix entirely is refused') {
    Map r = Mfst.verifyReleaseNotesText('Automation Map visualizes things.\n\n- A change.\n', '2.2.0')
    assert !r.ok
}

println '--- Negative: structural comparison rejects an unexplained change and a structural mismatch ---'
// Each case below starts from a PROPERLY substituted candidate (via
// applyManifestAllowlist on a fresh copy) and applies exactly ONE further
// illegitimate mutation, so the failure it triggers is unambiguous -
// starting from the raw untransformed fixture instead would also trip the
// "allowlisted path did not change" case first every time, masking the
// specific failure mode each test means to isolate.
def properlySubstitutedCandidate = {
    Map seed = new JsonSlurper().parseText(JsonOutput.toJson(fixtureManifest()))
    Map r = Mfst.applyManifestAllowlist(seed, Mfst.manifestAllowlist(FIXTURE_RELEASE_NOTES))
    assert r.ok : "fixture setup itself failed: ${r}"
    return r.candidate as Map
}
check('a value change with no matching allowlist entry is rejected') {
    Map original = new JsonSlurper().parseText(JsonOutput.toJson(fixtureManifest()))
    Map candidate = properlySubstitutedCandidate()
    candidate.author = 'Someone Else'
    Map r = Mfst.compareManifestStructures(original, candidate, Mfst.manifestAllowlist(FIXTURE_RELEASE_NOTES))
    assert !r.ok
    assert (r.reason as String).contains('matches no allowlist entry')
}
check('an allowlisted path that was NOT actually changed is rejected (must fire, not silently pass through)') {
    Map original = new JsonSlurper().parseText(JsonOutput.toJson(fixtureManifest()))
    Map candidate = properlySubstitutedCandidate()
    // Revert just packageName back to the Dev value after an otherwise
    // correct substitution - the allowlist entry effectively never fired.
    candidate.packageName = 'Automation Map (Dev)'
    Map r = Mfst.compareManifestStructures(original, candidate, Mfst.manifestAllowlist(FIXTURE_RELEASE_NOTES))
    assert !r.ok
    assert (r.reason as String).contains('did not change the value')
}
check('a candidate releaseNotes that does not match the externally supplied value is rejected') {
    Map original = new JsonSlurper().parseText(JsonOutput.toJson(fixtureManifest()))
    Map candidate = properlySubstitutedCandidate()
    candidate.releaseNotes = 'Something the caller never actually supplied.'
    Map r = Mfst.compareManifestStructures(original, candidate, Mfst.manifestAllowlist(FIXTURE_RELEASE_NOTES))
    assert !r.ok
    assert (r.reason as String).contains('externally supplied value')
}
check('a removed key is rejected as a structural change, not silently accepted') {
    Map original = new JsonSlurper().parseText(JsonOutput.toJson(fixtureManifest()))
    Map candidate = properlySubstitutedCandidate()
    candidate.remove('communityLink')
    Map r = Mfst.compareManifestStructures(original, candidate, Mfst.manifestAllowlist(FIXTURE_RELEASE_NOTES))
    assert !r.ok
    assert (r.reason as String).contains('key set differs')
}
check('an array length change is rejected as a structural change') {
    Map original = new JsonSlurper().parseText(JsonOutput.toJson(fixtureManifest()))
    Map candidate = properlySubstitutedCandidate()
    (candidate.apps as List) << [id: 'extra', name: 'Extra']
    Map r = Mfst.compareManifestStructures(original, candidate, Mfst.manifestAllowlist(FIXTURE_RELEASE_NOTES))
    assert !r.ok
    assert (r.reason as String).contains('array length differs')
}

println '--- No leakage: verifyNoLeakedDevValues() catches leaked Dev declarations by PATH, not prose data anywhere in the text ---'
check('a leaked, unsubstituted packageName declaration is caught') {
    String leakedJson = JsonOutput.prettyPrint(JsonOutput.toJson([packageName: 'Automation Map (Dev)']))
    Map r = Mfst.verifyNoLeakedDevValues(leakedJson, Mfst.manifestAllowlist(FIXTURE_RELEASE_NOTES))
    assert !r.ok
    assert (r.reason as String).contains('package-name')
}
check('a clean candidate triggers no forbidden-marker match') {
    Map r = Mfst.verifyNoLeakedDevValues(result.candidateJson as String, Mfst.manifestAllowlist(FIXTURE_RELEASE_NOTES))
    assert r.ok : "expected no leak, got: ${r}"
}

println '--- Reproducibility: two pipeline runs from the same fixture are byte-identical ---'
check('two runPipeline() calls produce identical candidateJson') {
    Map r1 = runPipeline(fixtureManifest(), FIXTURE_RELEASE_NOTES)
    Map r2 = runPipeline(fixtureManifest(), FIXTURE_RELEASE_NOTES)
    assert r1.ok && r2.ok
    assert r1.candidateJson == r2.candidateJson
}

// The curated notes are embedded verbatim as a JSON string value, so a CRLF
// checkout of that file would otherwise escape as \r\n inside the candidate
// and change its bytes for the same commit. Exercises the real
// generateManifestCandidateJson() against files on disk for that reason.
println '--- Line-ending canonicalization: LF and CRLF release-notes checkouts generate identical bytes ---'
File eolTestDir = new File(thisScriptFile.parentFile.parentFile.parentFile, 'tmp/production-manifest-eol-test')
eolTestDir.deleteDir()
eolTestDir.mkdirs()
File eolManifestFile = new File(eolTestDir, 'packageManifest.json')
eolManifestFile.setText(JsonOutput.prettyPrint(JsonOutput.toJson(fixtureManifest())) + '\n', 'UTF-8')
String NOTES_LF = (FIXTURE_RELEASE_NOTES + '\n\nSecond paragraph, so there is a line break to get wrong.\n')
        .replace('\r\n', '\n').replace('\r', '\n')
String NOTES_CRLF = NOTES_LF.replace('\n', '\r\n')
File notesLfFile = new File(eolTestDir, 'notes-lf.txt')
File notesCrlfFile = new File(eolTestDir, 'notes-crlf.txt')
notesLfFile.setText(NOTES_LF, 'UTF-8')
notesCrlfFile.setText(NOTES_CRLF, 'UTF-8')

check('the release-notes fixtures genuinely differ on disk (guards this test against testing nothing)') {
    assert notesLfFile.getText('UTF-8') != notesCrlfFile.getText('UTF-8')
    assert !notesLfFile.getText('UTF-8').contains('\r')
    assert notesCrlfFile.getText('UTF-8').contains('\r')
}
check('generateManifestCandidateJson() produces byte-identical output from LF and CRLF release notes') {
    Map lfResult = Mfst.generateManifestCandidateJson(eolManifestFile, notesLfFile, Profile)
    Map crlfResult = Mfst.generateManifestCandidateJson(eolManifestFile, notesCrlfFile, Profile)
    assert lfResult.ok : "LF build failed at ${lfResult.stage}: ${lfResult.reason}"
    assert crlfResult.ok : "CRLF build failed at ${crlfResult.stage}: ${crlfResult.reason}"
    assert lfResult.candidateJson == crlfResult.candidateJson
}
check('the generated manifest carries no escaped carriage return in its embedded notes') {
    Map crlfResult = Mfst.generateManifestCandidateJson(eolManifestFile, notesCrlfFile, Profile)
    assert crlfResult.ok
    String json = crlfResult.candidateJson as String
    assert !json.contains('\\r') : 'release notes embedded an escaped carriage return into the candidate'
    assert !json.contains('\r')
}
eolTestDir.deleteDir()

println "${pass} passed, ${fail} failed"
if (fail > 0) System.exit(1)
