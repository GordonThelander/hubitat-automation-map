// Production-manifest substitution builder, phase 2d (backlog item 16 /
// production_build_methodology.md; review, queue 456). Generates a
// production-shaped packageManifest.json from the annotated Dev manifest,
// the same way production-profile.groovy generates a production-shaped app
// source - a small, explicit, versioned allowlist of substitutions, verified
// provenance bound to Git (not caller-controlled), atomic tmp/-only writes.
//
// Reuses production-profile.groovy's own resolveVerifiedProvenance() and
// writeCandidateAtomically() directly via parseClass() rather than
// duplicating the same git-plumbing/write-safety logic a second time -
// resolveVerifiedProvenance() was generalized to take a relativePath
// argument specifically for this reuse (its only other caller,
// production-profile.groovy's own CLI, passes 'apps/automation_map.groovy'
// explicitly and is unaffected).
//
// Manifest allowlist, scoped to what actually differs between the current
// Dev manifest and the current live production manifest (inspected via
// `git show origin/main:packageManifest.json` before writing this):
//
//   - packageName / apps[0].name: drop the '(Dev)' suffix (same rule as
//     APP_NAME in the app source).
//   - documentationLink / apps[0].location: dev branch -> main branch.
//   - apps[0].id: NOT derived from the Dev manifest's own id - Dev and
//     production are permanently distinct, coexisting HPM packages (the Dev
//     instance installs ALONGSIDE the production one on the same hub, per
//     repository.json's own "Installs alongside the release version"
//     description), so they have always had, and must keep, their own
//     separate stable identity. This entry substitutes to the FIXED value
//     already live in production's own manifest today
//     (184c8f7d-5b2a-4352-a883-45d36ef4860b), not a transform of Dev's id.
//   - releaseNotes: NOT derived from the Dev manifest's own accumulated-
//     history text at all (an earlier draft stripped the DEV CHANNEL banner
//     and swapped the CHANGELOG link, carrying the rest through - Gordon
//     reviewed that shape directly and asked for a short, curated summary
//     instead: a one-line description of what the app does, then bullets of
//     user-facing changes since the last production release, matching his
//     own past production releaseNotes style). Read verbatim from the
//     separately tracked, separately verified production-release-notes.txt
//     at the repository root - a human-authored, human-approved input, not
//     something this builder derives mechanically. Fails closed if that
//     file is missing, dirty, untracked, empty, or contains a Dev-only
//     marker (the file is meant to be curated once per release and simply
//     carried through, never machine-generated).
//
// Carried through UNCHANGED, not substituted (validated for internal
// consistency, not transformed): author, version, apps[0].version,
// minimumHEVersion, communityLink, apps[0].namespace, apps[0].required,
// apps[0].oauth, apps[0].primary. dateReleased is also carried through
// unchanged - it is Gordon's own hand-set release date already present in
// the Dev manifest, never regenerated from wall-clock time (reproducibility
// requires the whole candidate be a pure function of the source commit).
//
// The Dev manifest has no "drivers" key at all; production's currently does
// (an empty array). This builder preserves the Dev manifest's own shape
// (omits the key) rather than inventing structure not present in the
// source - flagged as an observed pre-existing inconsistency between the
// two files, not something this builder is asked to reconcile.
//
// Usage: groovy production-manifest.groovy <output.json>
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.security.MessageDigest
import java.nio.file.Files

static int manifestProfileVersion() { return 1 }

// Fixed constants, not derived at runtime - see the file header comment for
// why apps[0].id must never be transformed from Dev's own id. Both are
// stable, known identities, asserted exactly (review, queue 461) the same
// as every other allowlist entry's expectedFrom/expectedTo.
static String devAppId() { return '2c2c28d6-f6eb-4c88-afad-d9d32a6013bc' }
static String productionAppId() { return '184c8f7d-5b2a-4352-a883-45d36ef4860b' }

// Reads a value at a dotted/indexed path (e.g. ['apps', 0, 'id']) out of a
// nested Map/List structure, as JsonSlurper produces. Returns null (via
// Groovy's own null-safe-ish chaining) if any intermediate segment is
// missing - callers treat that as "path not found," a structural mismatch.
static Object getAtPath(Object root, List path) {
    Object cur = root
    for (seg in path) {
        if (cur == null) return null
        if (seg instanceof Integer) {
            List l = cur as List
            if (seg >= l.size()) return null
            cur = l[seg as int]
        } else {
            Map m = cur as Map
            if (!m.containsKey(seg)) return null
            cur = m[seg]
        }
    }
    return cur
}

// Sets a value at a dotted/indexed path in-place, mutating the nested
// structure directly (Groovy Maps/Lists from JsonSlurper are mutable).
static void setAtPath(Object root, List path, Object value) {
    Object cur = root
    for (int i = 0; i < path.size() - 1; i++) {
        def seg = path[i]
        cur = (seg instanceof Integer) ? (cur as List)[seg as int] : (cur as Map)[seg]
    }
    def lastSeg = path[-1]
    if (lastSeg instanceof Integer) {
        (cur as List)[lastSeg as int] = value
    } else {
        (cur as Map)[lastSeg] = value
    }
}

static String pathToString(List path) {
    return path.collect { it instanceof Integer ? "[${it}]" : it }.join('.')
}

// The versioned allowlist. Three shapes:
//   - fixed-value entries (expectedFrom/expectedTo): the value at path must
//     equal expectedFrom exactly, and becomes expectedTo.
//   - transform entries (transform closure + describe): the value at path
//     is passed through transform(original) to produce the new value;
//     transform itself is responsible for failing closed (returning null)
//     if the original does not look as expected, so an already-transformed
//     or unrecognisable input is refused rather than double-applied or
//     silently mishandled.
//   - external entries (value): the path is set to a value supplied from
//     OUTSIDE the Dev manifest entirely - the Dev manifest's own value at
//     that path is not read, checked, or transformed at all. Currently only
//     releaseNotes, whose curated text comes from
//     production-release-notes.txt (see the file header comment for why).
//
// releaseNotesText is required - the CLI resolves it from the separately
// verified production-release-notes.txt before calling this, and every
// caller (including the test suites, via a small fixture string) must
// supply one explicitly, so a missing/forgotten input cannot silently fall
// through to some earlier mechanically-derived text.
static List<Map> manifestAllowlist(String releaseNotesText) {
    return [
        [
            name: 'package-name', kind: 'fixed', path: ['packageName'],
            expectedFrom: 'Automation Map (Dev)', expectedTo: 'Automation Map',
        ],
        [
            name: 'app-name', kind: 'fixed', path: ['apps', 0, 'name'],
            expectedFrom: 'Automation Map (Dev)', expectedTo: 'Automation Map',
        ],
        [
            name: 'documentation-link', kind: 'fixed', path: ['documentationLink'],
            expectedFrom: 'https://github.com/GordonThelander/hubitat-automation-map/blob/dev/README.md',
            expectedTo: 'https://github.com/GordonThelander/hubitat-automation-map/blob/main/README.md',
        ],
        [
            name: 'app-location', kind: 'fixed', path: ['apps', 0, 'location'],
            expectedFrom: 'https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/dev/apps/automation_map.groovy',
            expectedTo: 'https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/main/apps/automation_map.groovy',
        ],
        [
            // Review, queue 461: Dev's id IS a stable, known identity (just
            // a different one from production's) and must be asserted
            // exactly, the same as every other entry - an earlier draft
            // omitted expectedFrom, which meant applyManifestAllowlist()
            // would silently accept and overwrite ANY value found here,
            // including an already-production id or a completely unknown
            // one, rather than failing closed.
            name: 'app-id', kind: 'fixed', path: ['apps', 0, 'id'],
            expectedFrom: devAppId(), expectedTo: productionAppId(),
        ],
        [
            name: 'release-notes', kind: 'external', path: ['releaseNotes'],
            value: releaseNotesText,
        ],
    ]
}

// production-release-notes.txt must not contain the markers that would
// signal it was accidentally left in its Dev-tracking shape (or a stray
// dev-branch link) rather than genuinely curated for this release - checked
// on the raw file content before it is ever embedded into a candidate.
static List<String> releaseNotesForbiddenMarkers() {
    return [
        'DEV CHANNEL',
        'blob/dev/',
    ]
}

// expectedVersion binds the curated text to the exact release it was
// written for (review, queue 461) - a clean, well-formed, but STALE notes
// file left over from a previous version must fail closed rather than
// silently ship under the wrong version number. Requires the file's first
// content line to begin with the exact "v<expectedVersion>:" prefix.
static Map verifyReleaseNotesText(String text, String expectedVersion) {
    if (text == null || text.trim().isEmpty()) {
        return [ok: false, reason: 'production-release-notes.txt is empty']
    }
    List<String> found = releaseNotesForbiddenMarkers().findAll { text.contains(it) }
    if (!found.isEmpty()) {
        return [ok: false, reason: "production-release-notes.txt contains forbidden marker(s): ${found.join(', ')}"]
    }
    String requiredPrefix = "v${expectedVersion}:"
    if (!text.startsWith(requiredPrefix)) {
        return [ok: false, reason: "production-release-notes.txt does not begin with the expected '${requiredPrefix}' prefix for manifest version '${expectedVersion}' - looks stale or mismatched"]
    }
    return [ok: true]
}

// Applies every allowlist entry, fixed-value entries checked-then-set,
// transform entries run through their own closure (which itself fails
// closed by returning null on unexpected input). Fails closed on the first
// problem, with no partial edit in the returned candidate.
static Map applyManifestAllowlist(Map parsedManifest, List<Map> allowlist) {
    for (entry in allowlist) {
        List path = entry.path as List
        Object current = getAtPath(parsedManifest, path)
        if (entry.kind == 'fixed') {
            String expectedFrom = entry.expectedFrom as String
            if (expectedFrom != null && current != expectedFrom) {
                return [ok: false, reason: "allowlist entry '${entry.name}' at ${pathToString(path)}: expected '${expectedFrom}', found '${current}'"]
            }
            setAtPath(parsedManifest, path, entry.expectedTo)
        } else if (entry.kind == 'transform') {
            Closure xform = entry.transform as Closure
            Object result = xform((current as String))
            if (result == null) {
                return [ok: false, reason: "allowlist entry '${entry.name}' at ${pathToString(path)}: input did not match the expected shape for '${entry.describe}'"]
            }
            setAtPath(parsedManifest, path, result)
        } else if (entry.kind == 'external') {
            // Not derived from the Dev manifest's own value at this path at
            // all (current is intentionally ignored) - set unconditionally
            // to the separately verified external value.
            setAtPath(parsedManifest, path, entry.value)
        } else {
            return [ok: false, reason: "allowlist entry '${entry.name}' has an unrecognised kind '${entry.kind}'"]
        }
    }
    return [ok: true, candidate: parsedManifest]
}

// Deep-walks both structures in parallel, leaf by leaf, asserting every
// value is identical EXCEPT at exactly the allowlisted paths - and that
// every allowlisted path actually differs (never silently unchanged) in
// precisely the way its entry describes. Also asserts identical key sets
// and array lengths everywhere (item 3: preserve structure, not just
// substitute text) - a key added, removed, or reordered into a different
// type is a structural change no allowlist entry is meant to cover.
static Map compareManifestStructures(Object original, Object candidate, List<Map> allowlist, List curPath = []) {
    Map matchingEntry = allowlist.find { (it.path as List) == curPath }
    if (original instanceof Map && candidate instanceof Map) {
        Map o = original as Map
        Map c = candidate as Map
        if (o.keySet() != c.keySet()) {
            return [ok: false, reason: "key set differs at ${pathToString(curPath)}: expected ${o.keySet()}, got ${c.keySet()}"]
        }
        for (key in o.keySet()) {
            Map r = compareManifestStructures(o[key], c[key], allowlist, curPath + [key])
            if (!r.ok) return r
        }
        return [ok: true]
    }
    if (original instanceof List && candidate instanceof List) {
        List o = original as List
        List c = candidate as List
        if (o.size() != c.size()) {
            return [ok: false, reason: "array length differs at ${pathToString(curPath)}: expected ${o.size()}, got ${c.size()}"]
        }
        for (int i = 0; i < o.size(); i++) {
            Map r = compareManifestStructures(o[i], c[i], allowlist, curPath + [i])
            if (!r.ok) return r
        }
        return [ok: true]
    }
    // Leaf value. 'external' entries are checked against their own supplied
    // value regardless of whether that happens to equal the original Dev
    // value - unlike 'fixed'/'transform', there is no requirement that an
    // externally supplied value differ from what the Dev manifest happened
    // to hold, so this is checked before the original==candidate shortcut
    // below would otherwise wrongly demand a difference.
    if (matchingEntry != null && matchingEntry.kind == 'external') {
        if (candidate != matchingEntry.value) {
            return [ok: false, reason: "allowlist entry '${matchingEntry.name}' at ${pathToString(curPath)}: does not equal the externally supplied value"]
        }
        return [ok: true]
    }
    if (original == candidate) {
        if (matchingEntry != null) {
            return [ok: false, reason: "allowlist entry '${matchingEntry.name}' at ${pathToString(curPath)} did not change the value - expected it to fire"]
        }
        return [ok: true]
    }
    if (matchingEntry == null) {
        return [ok: false, reason: "value differs at ${pathToString(curPath)} but matches no allowlist entry: expected '${original}', got '${candidate}'"]
    }
    if (matchingEntry.kind == 'fixed') {
        if (candidate != matchingEntry.expectedTo) {
            return [ok: false, reason: "allowlist entry '${matchingEntry.name}' at ${pathToString(curPath)}: expected result '${matchingEntry.expectedTo}', got '${candidate}'"]
        }
    } else if (matchingEntry.kind == 'transform') {
        Object expected = (matchingEntry.transform as Closure)(original as String)
        if (candidate != expected) {
            return [ok: false, reason: "allowlist entry '${matchingEntry.name}' at ${pathToString(curPath)}: transform result does not match"]
        }
    }
    return [ok: true]
}

// Independent fail-closed backstop against a future bug in
// applyManifestAllowlist()'s own bookkeeping (same purpose as production-
// profile.groovy's forbiddenInCandidate(), OPERATING_RULES.md #12) - but
// deliberately PATH-SCOPED, not a whole-text substring scan. A first draft
// here scanned the whole serialized candidate for any allowlist entry's
// expectedFrom value anywhere - caught by this file's own test suite before
// it shipped: releaseNotes prose legitimately mentioning the Dev app name
// as DATA (e.g. "only affected Automation Map (Dev)") produced a false
// positive, the exact same class of bug production-profile.groovy's
// forbiddenInCandidate() hit and was fixed for (review, queue 440) - JSON
// gives real structure to exploit here, so re-parses the final candidate
// text fresh (never trusts applyManifestAllowlist()'s own return value) and
// checks ONLY whether each fixed entry's OWN path still holds its
// pre-substitution value, not whether that value appears anywhere at all.
static Map verifyNoLeakedDevValues(String candidateJsonText, List<Map> allowlist) {
    Object parsed
    try {
        parsed = new JsonSlurper().parseText(candidateJsonText)
    } catch (Exception e) {
        return [ok: false, reason: "candidate is not valid JSON: ${e.message}"]
    }
    List<String> leaked = []
    for (entry in allowlist) {
        if (entry.kind != 'fixed' || entry.expectedFrom == null) continue
        List path = entry.path as List
        Object current = getAtPath(parsed, path)
        if (current == entry.expectedFrom) {
            leaked << "'${entry.name}' at ${pathToString(path)} still holds the pre-substitution value '${entry.expectedFrom}'"
        }
    }
    if (!leaked.isEmpty()) {
        return [ok: false, reason: leaked.join('; ')]
    }
    return [ok: true]
}

static String sha256(byte[] bytes) {
    MessageDigest md = MessageDigest.getInstance('SHA-256')
    return md.digest(bytes).collect { String.format('%02x', it) }.join('')
}

// --- Core generation (extracted, review queue 461 - same reasoning as
// production-profile.groovy's generateAppCandidateSource(): the package-
// build orchestrator calls this in-process against already-verified,
// already-resolved File objects, rather than a second CLI invocation that
// would redundantly re-verify provenance itself. Pure: no provenance check,
// no disk write - just parse, substitute, compare, leak-check. ---
// Profile supplies canonicalizeLineEndings() rather than this file duplicating
// it: the curated release-notes text is embedded verbatim as a JSON string
// value, so its checkout line endings would otherwise change the candidate's
// bytes for the same commit. The manifest's own line endings are irrelevant
// (JsonOutput re-emits the structure with LF regardless).
static Map generateManifestCandidateJson(File manifestFile, File releaseNotesFile, def Profile) {
    String originalText = manifestFile.getText('UTF-8')
    Object originalParsed
    try {
        originalParsed = new JsonSlurper().parseText(originalText)
    } catch (Exception e) {
        return [ok: false, stage: 'manifest JSON parse', reason: "${manifestFile.name} is not valid JSON: ${e.message}"]
    }

    String releaseNotesText = Profile.canonicalizeLineEndings(releaseNotesFile.getText('UTF-8')).trim()
    String manifestVersion = (originalParsed as Map).version as String
    Map notesCheck = verifyReleaseNotesText(releaseNotesText, manifestVersion)
    if (!notesCheck.ok) {
        return [ok: false, stage: 'release-notes verification', reason: notesCheck.reason]
    }

    List<Map> allowlistEntries = manifestAllowlist(releaseNotesText)
    Map substitution = applyManifestAllowlist(originalParsed, allowlistEntries)
    if (!substitution.ok) {
        return [ok: false, stage: 'allowlist substitution', reason: substitution.reason]
    }
    Object candidateParsed = substitution.candidate

    // Re-parse the ORIGINAL text fresh for the comparison, since
    // applyManifestAllowlist() mutated originalParsed in place (Groovy Maps
    // from JsonSlurper are mutable, and setAtPath() writes through them) -
    // the comparison must be against the untouched original structure, not
    // the same object graph the candidate now shares.
    Object originalForComparison = new JsonSlurper().parseText(originalText)
    Map structureCheck = compareManifestStructures(originalForComparison, candidateParsed, allowlistEntries)
    if (!structureCheck.ok) {
        return [ok: false, stage: 'structural comparison', reason: structureCheck.reason]
    }

    String candidateJson = JsonOutput.prettyPrint(JsonOutput.toJson(candidateParsed)) + '\n'
    Map leakCheck = verifyNoLeakedDevValues(candidateJson, allowlistEntries)
    if (!leakCheck.ok) {
        return [ok: false, stage: 'Dev-marker leak check', reason: leakCheck.reason]
    }

    // Same fail-closed backstop as the app builder: a stray CR (raw, or escaped
    // into a JSON string value) means canonicalization was bypassed somewhere.
    if (candidateJson.contains('\r') || candidateJson.contains('\\r')) {
        return [ok: false, stage: 'line-ending canonicalization check', reason: 'generated manifest candidate contains a carriage return; output must be LF-only to stay reproducible across checkouts']
    }

    return [ok: true, candidateJson: candidateJson, allowlistEntries: allowlistEntries]
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
if (!profileFile.exists()) {
    System.err.println("Expected production-profile.groovy next to this script at ${profileFile} - not found. " +
        'Run this script from its own checked-out location, not a copy.')
    System.exit(1)
}
// production-profile.groovy is itself a runnable script (top-level CLI
// logic), but parseClass() only compiles the class - its own top-level body
// never runs from here. Only resolveVerifiedProvenance()/
// writeCandidateAtomically() (both static) are called below.
def Profile = new GroovyClassLoader(this.class.classLoader).parseClass(profileFile)

if (args.size() != 1) {
    System.err.println('Usage: groovy production-manifest.groovy <output.json>')
    System.exit(1)
}
File outFile = new File(args[0])

File tmpRoot = new File(repoRoot, 'tmp')
String outCanonicalPath = outFile.canonicalFile.path
if (!outCanonicalPath.startsWith(tmpRoot.canonicalPath + File.separator)) {
    System.err.println("Refusing to run: output path ${outCanonicalPath} is not under ${tmpRoot.canonicalPath} - " +
        'generated candidates must never land at the repository root or overwrite the tracked Dev manifest.')
    System.exit(1)
}

// Same "invalidate the requested destination before any real work" ordering
// production-profile.groovy uses (review, queue 452) - any later failure
// leaves this path absent, not a stale prior success.
if (outFile.exists()) {
    if (!outFile.delete()) {
        System.err.println("Refusing to run: could not remove the pre-existing destination ${outFile.canonicalFile} before regenerating it.")
        System.exit(1)
    }
}

Map provenance = Profile.resolveVerifiedProvenance(repoRoot, 'packageManifest.json')
if (!provenance.ok) {
    System.err.println("FAIL provenance verification: ${provenance.reason}")
    System.exit(1)
}
String devCommitSha = provenance.sha as String
File inFile = provenance.file as File

if (inFile.canonicalPath == outFile.canonicalPath) {
    System.err.println('Refusing to run: input and output resolve to the same file. This tool must never ' +
        'overwrite the annotated manifest it reads from.')
    System.exit(1)
}

// production-release-notes.txt: a separate, human-curated, human-approved
// input - verified with the exact same git-bound provenance rigor as the
// manifest itself (tracked, present in HEAD, clean, content-hash-matched),
// not merely read off disk. Bound to the SAME commit devCommitSha already
// names, since HEAD cannot change between these two provenance checks
// within one run.
Map notesProvenance = Profile.resolveVerifiedProvenance(repoRoot, 'production-release-notes.txt')
if (!notesProvenance.ok) {
    System.err.println("FAIL provenance verification (production-release-notes.txt): ${notesProvenance.reason}")
    System.exit(1)
}
String releaseNotesSha = notesProvenance.sha as String
if (releaseNotesSha != devCommitSha) {
    System.err.println("FAIL: packageManifest.json and production-release-notes.txt resolved to different commits (${devCommitSha} vs ${releaseNotesSha}) - refusing to bind a candidate to an ambiguous source state.")
    System.exit(1)
}
File releaseNotesFile = notesProvenance.file as File

Map genResult = generateManifestCandidateJson(inFile, releaseNotesFile, Profile)
if (!genResult.ok) {
    System.err.println("FAIL ${genResult.stage}: ${genResult.reason}")
    System.exit(1)
}
String candidateJson = genResult.candidateJson as String
List<Map> allowlistEntries = genResult.allowlistEntries as List<Map>

Map writeResult = Profile.writeCandidateAtomically(outFile, candidateJson, null)
if (!writeResult.ok) {
    System.err.println("FAIL writing candidate: ${writeResult.reason}")
    System.exit(1)
}

long originalBytes = inFile.length()
long candidateBytes = outFile.length()
String candidateHash = sha256(Files.readAllBytes(outFile.toPath()))

println "OK - production manifest candidate generated (profile v${manifestProfileVersion()})."
println "Provenance: packageManifest.json at verified clean commit ${devCommitSha} (same git-bound checks as the app builder)."
println "Allowlist: ${allowlistEntries.size()} entries applied, each firing exactly once: ${allowlistEntries.collect { it.name }}."
println 'Dev-marker leak check: clean (every fixed-entry path re-verified against its pre-substitution value in the final candidate).'
println "Original: ${originalBytes} bytes (physical file size)."
println "Candidate: ${candidateBytes} bytes (physical file size). SHA-256: ${candidateHash}"
