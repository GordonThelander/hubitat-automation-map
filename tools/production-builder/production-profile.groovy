// Production-profile substitution builder, phase 2c (backlog item 16 /
// production_build_methodology.md; review, queue 448/450). Composes the
// reviewed comment stripper (strip-comments.groovy's own functions, loaded
// directly - not duplicated) with a small, explicit, versioned allowlist of
// production substitutions, then prepends the required compact production
// header.
//
// Guard, comparison logic, and the stripper itself are all loaded as siblings
// of THIS running script's own file (this.class.protectionDomain.codeSource.
// location), same pattern every other tool in this directory uses - never a
// hard-coded repo-root-relative path, which would silently depend on the
// caller's working directory.
//
// profileVersion() below is the versioned part of "small, versioned, explicit
// allowlist" - bump it, with a review, whenever an entry is added, removed,
// or its from/to text changes. The allowlist is scoped to what is actually
// present in apps/automation_map.groovy today, not a generic checklist:
//
//   - app identity: APP_NAME's '(Dev)' Apps-Code-instance suffix.
//   - build channel / diagnostic level: the two phase-2b constants.
//
// Branch URLs (the three ${isDevBuild() ? 'dev' : 'main'} asset sites) are
// NOT a separate allowlist entry - they already read BUILD_CHANNEL through
// isDevBuild() at hub runtime, so substituting BUILD_CHANNEL alone is
// sufficient; a second, redundant text substitution of the same URLs would
// only create a second way for the two to drift apart. Package identifiers,
// a driver import URL, and version/release metadata have no corresponding
// pattern in this file (packageManifest.json is a separate file, outside
// this builder's scope; APP_VERSION's value ships identical in both
// channels) - the methodology's substitution categories are a reusable
// checklist across projects, not a claim that every category applies here.
// Confirmed correct scope in review, queue 450.
//
// NOT YET A COMPLETE OR DEPLOYABLE PRODUCTION CANDIDATE (review, queue 450
// point 2): this builder covers apps/automation_map.groovy only. There is no
// production packageManifest.json yet, and no production-profile validation
// path that covers the whole installable package - both are required before
// anything this generates can be called ready to deploy or promote. That is
// explicitly the next production-builder increment, not this one.
//
// Provenance (review, queue 450/452/454): the input is always THIS
// checkout's own tracked apps/automation_map.groovy, and the embedded
// commit SHA is always derived from `git rev-parse HEAD` in this checkout,
// never a free-form argument - a caller cannot make this tool assert a false
// or unverified provenance. Requires the path to be genuinely tracked and
// present in HEAD's own tree (not just "git status is quiet," which an
// ignored/untracked file at the same path would also produce), the worktree
// clean per git status, AND the worktree content's own git object ID to
// match HEAD's tracked blob ID directly - the last check is independent of
// git's change-detection heuristics, so an assume-unchanged/skip-worktree
// index flag cannot mask a real difference.
//
// Usage: groovy production-profile.groovy <output.groovy>
import org.apache.groovy.parser.antlr4.GroovyLexer
import groovyjarjarantlr4.v4.runtime.CharStreams
import groovyjarjarantlr4.v4.runtime.CommonTokenStream
import groovyjarjarantlr4.v4.runtime.Token
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileAttribute

// Definitions below (profileVersion/allowlist/forbiddenInCandidate and every
// static function) are called both by this script's own CLI flow further
// down AND by the test files via parseClass() - kept as static functions
// rather than top-level script variables specifically so the tests call the
// SAME logic this script actually uses, not a copy that could drift (same
// reasoning as comparison.groovy's extraction).
static int profileVersion() { return 1 }

// --- Git-bound provenance (review, queue 450, blocking finding 1) ---

// Runs a git command with the given working directory, returning exit code
// and captured stdout/stderr rather than throwing - callers decide what a
// non-zero exit means in context.
static Map runGit(File repoRoot, List<String> gitArgs) {
    List<String> cmd = ['git'] + gitArgs
    ProcessBuilder pb = new ProcessBuilder(cmd)
    pb.directory(repoRoot)
    Process p = pb.start()
    String out = p.inputStream.getText('UTF-8')
    String err = p.errorStream.getText('UTF-8')
    int code = p.waitFor()
    return [exitCode: code, stdout: out, stderr: err]
}

// The single source of truth for "what am I generating from, and is it
// trustworthy": the input file must be exactly this checkout's own tracked
// file at relativePath (never an arbitrary path), the commit SHA is derived
// from git itself (never trusted as a caller-supplied argument), the path
// must be genuinely tracked BY HEAD (not just absent from `git status`,
// which says nothing about an ignored/untracked file at the same path - an
// ignored+untracked file at the expected path would otherwise produce empty
// porcelain output too, letting unrelated, uncommitted bytes be labelled as
// this commit's tracked source - review, queue 452), the working tree must
// be clean for that exact file, AND the worktree content's own git object
// ID must match HEAD's tracked blob ID directly - status/porcelain rely on
// git's own change-detection, which an assume-unchanged/skip-worktree index
// flag can suppress even though the actual bytes differ (review, queue
// 454). Every one of these checks review 450/452/454 required.
//
static Map resolveHeadSha(File repoRoot) {
    Map headResult = runGit(repoRoot, ['rev-parse', '--verify', 'HEAD'])
    if (headResult.exitCode != 0) {
        return [ok: false, reason: "git rev-parse --verify HEAD failed: ${(headResult.stderr as String).trim()}"]
    }
    String sha = (headResult.stdout as String).trim()
    if (!(sha ==~ /^[0-9a-f]{40}$/)) {
        return [ok: false, reason: "git rev-parse --verify HEAD did not return a 40-character lowercase hex SHA: '${sha}'"]
    }
    return [ok: true, sha: sha]
}

// Verifies ONE path against an ALREADY-RESOLVED HEAD sha - factored out of
// resolveVerifiedProvenance() (backlog item 16 phase 2d) so
// resolveVerifiedProvenanceMulti() below can verify several paths against
// the exact same sha without re-resolving HEAD once per path, which is
// itself the whole point: a single package build must bind every input to
// one shared commit, not merely "several HEAD reads that happened to agree."
static Map verifyPathAgainstSha(File repoRoot, String sha, String relativePath) {
    File expected = new File(repoRoot, relativePath)
    if (!expected.exists()) {
        return [ok: false, reason: "expected tracked source not found at ${expected.canonicalFile}"]
    }
    // Proves the path is genuinely tracked in the index. `git ls-files
    // --error-unmatch` exits non-zero for any path git is not actually
    // tracking - including an ignored file, which `git status --porcelain`
    // silently omits by default rather than flagging.
    Map trackedResult = runGit(repoRoot, ['ls-files', '--error-unmatch', '--', relativePath])
    if (trackedResult.exitCode != 0) {
        return [ok: false, reason: "${relativePath} is not tracked by git: ${(trackedResult.stderr as String).trim()}"]
    }
    // Proves HEAD's own tree actually contains this exact path as a real
    // blob - not just "currently in the index," which alone would still
    // pass for a brand-new file staged but never committed.
    // ('HEAD:' + relativePath), not a GString: ProcessBuilder's internal
    // array copy requires actual java.lang.String elements and throws
    // ArrayStoreException on a GString, which Groovy does not auto-coerce
    // in this position - caught immediately by rerunning the existing
    // suites after this generalization, not by inspection.
    Map blobResult = runGit(repoRoot, ['rev-parse', '--verify', ('HEAD:' + relativePath)])
    if (blobResult.exitCode != 0) {
        return [ok: false, reason: "HEAD does not contain ${relativePath}: ${(blobResult.stderr as String).trim()}"]
    }
    Map statusResult = runGit(repoRoot, ['status', '--porcelain', '--', relativePath])
    if (statusResult.exitCode != 0) {
        return [ok: false, reason: "git status failed: ${(statusResult.stderr as String).trim()}"]
    }
    String statusOut = (statusResult.stdout as String).trim()
    if (!statusOut.isEmpty()) {
        return [ok: false, reason: "working tree is dirty relative to HEAD for ${relativePath}, refusing to claim provenance to a commit the actual source does not match: ${statusOut}"]
    }
    // Direct content-identity proof, independent of git's own change-
    // detection heuristics (review, queue 454): `ls-files`/`status` above
    // rely on git noticing a difference, which index flags like
    // assume-unchanged/skip-worktree can suppress - status can report clean
    // while the actual on-disk bytes differ from HEAD's tracked blob.
    // git hash-object computes the worktree file's own object ID using the
    // same path-aware clean filters git would apply if re-adding it, with
    // no dependency on the index's change-tracking state at all - comparing
    // that directly against the HEAD blob ID already resolved above closes
    // this gap regardless of any index flag.
    String headBlobSha = (blobResult.stdout as String).trim()
    Map hashResult = runGit(repoRoot, ['hash-object', '--path', relativePath, relativePath])
    if (hashResult.exitCode != 0) {
        return [ok: false, reason: "git hash-object failed: ${(hashResult.stderr as String).trim()}"]
    }
    String worktreeBlobSha = (hashResult.stdout as String).trim()
    if (worktreeBlobSha != headBlobSha) {
        return [ok: false, reason: "worktree content for ${relativePath} does not match HEAD's tracked blob " +
            "(HEAD blob: ${headBlobSha}, worktree content hashes to: ${worktreeBlobSha}) - refusing to claim provenance " +
            'to a commit the actual source does not match (possibly assume-unchanged/skip-worktree masking a real difference)']
    }
    return [ok: true, sha: sha, file: expected]
}

// The single source of truth for "what am I generating from, and is it
// trustworthy" for ONE file - the input file must be exactly this
// checkout's own tracked file at relativePath (never an arbitrary path),
// the commit SHA is derived from git itself (never trusted as a caller-
// supplied argument). Every check review 450/452/454 required.
//
// Generalized to accept relativePath (backlog item 16 phase 2d) so the
// manifest builder can reuse this exact function via parseClass() for
// packageManifest.json's own provenance, rather than duplicating the same
// git-plumbing a second time - production-profile.groovy's own CLI passes
// 'apps/automation_map.groovy' explicitly below; this generalization changes
// no behaviour for that one call site.
static Map resolveVerifiedProvenance(File repoRoot, String relativePath) {
    Map headResult = resolveHeadSha(repoRoot)
    if (!headResult.ok) return headResult
    return verifyPathAgainstSha(repoRoot, headResult.sha as String, relativePath)
}

// Resolves HEAD exactly ONCE, then verifies every path in relativePaths
// against that SAME sha (backlog item 16 phase 2d; review, queue 461, "two
// independent commands do not bind a package to one commit") - the
// structural guarantee a package build needs: one shared, git-verified
// commit for the app source, the Dev manifest, and the release-notes input
// together, not three separate resolutions that merely happened not to
// race. Fails closed on the first path that does not verify, naming which
// one.
static Map resolveVerifiedProvenanceMulti(File repoRoot, List<String> relativePaths) {
    Map headResult = resolveHeadSha(repoRoot)
    if (!headResult.ok) return headResult
    String sha = headResult.sha as String
    Map<String, File> files = [:]
    for (path in relativePaths) {
        Map r = verifyPathAgainstSha(repoRoot, sha, path)
        if (!r.ok) {
            return [ok: false, reason: "${path}: ${r.reason}"]
        }
        files[path] = r.file as File
    }
    return [ok: true, sha: sha, files: files]
}

// --- The versioned allowlist ---

// Each entry's sourceFrom/sourceTo is the exact full-line declaration text
// (matched and replaced literally, never as a regex, so no escaping pitfall
// can widen or narrow the match); tokenFrom/tokenTo is the specific token
// text expected to change as a result, used by compareSubstitutionAwareStreams()
// below to prove nothing else changed.
static List<Map> allowlist() {
    return [
        [
            name: 'app-identity',
            description: "APP_NAME: drop the '(Dev)' Apps Code instance suffix for the production listing",
            sourceFrom: "@Field static final String APP_NAME = 'Automation Map (Dev)'",
            sourceTo: "@Field static final String APP_NAME = 'Automation Map'",
            tokenFrom: "'Automation Map (Dev)'",
            tokenTo: "'Automation Map'",
        ],
        [
            name: 'build-channel',
            description: 'BUILD_CHANNEL: dev -> production',
            sourceFrom: "@Field static final String BUILD_CHANNEL = 'dev'",
            sourceTo: "@Field static final String BUILD_CHANNEL = 'production'",
            tokenFrom: "'dev'",
            tokenTo: "'production'",
        ],
        [
            name: 'diagnostic-level',
            description: 'DIAGNOSTIC_LEVEL: 2 -> 0',
            sourceFrom: '@Field static final int DIAGNOSTIC_LEVEL = 2',
            sourceTo: '@Field static final int DIAGNOSTIC_LEVEL = 0',
            tokenFrom: '2',
            tokenTo: '0',
        ],
    ]
}

// The exact pre-substitution declaration text of every allowlist entry must
// not survive into the generated candidate - checked independently of
// applyAllowlist()'s own internal count-then-replace logic, as a fail-closed
// backstop against a future bug in that logic (OPERATING_RULES.md #12 / the
// methodology's "must fail closed if any allowlisted Dev marker... survives
// generation"). Deliberately derived from allowlist()'s own sourceFrom
// values, not a separately hand-maintained substring list: an earlier draft
// used loose substrings like the bare app name, which produced a false
// positive against ordinary runtime string CONTENT that happens to mention
// the Dev app name as data (a plausible real settings-page string, proven by
// a test fixture) - checking the exact unsubstituted DECLARATION text is
// both more precise and guaranteed to stay in sync with the allowlist itself.
static List<String> forbiddenInCandidate() {
    return allowlist().collect { it.sourceFrom as String }
}

static List<Token> lexAllChannels(def StripComments, String src) {
    return StripComments.lexAllChannels(src)
}

// Applies every allowlist entry's literal sourceFrom -> sourceTo exactly
// once each. Fails closed (returns ok:false, no partial edit applied to the
// returned candidate) if any entry's sourceFrom does not appear in the
// source exactly once - a missing, duplicated, or already-substituted
// pattern is refused rather than silently matching zero, two, or the wrong
// occurrence.
static Map applyAllowlist(String src, List<Map> allowlist) {
    Map<String, Integer> counts = [:]
    for (entry in allowlist) {
        int count = src.count(entry.sourceFrom as String)
        counts[entry.name as String] = count
        if (count != 1) {
            return [ok: false, reason: "allowlist entry '${entry.name}' matched ${count} time(s) in source, expected exactly 1: ${entry.sourceFrom}"]
        }
    }
    String result = src
    for (entry in allowlist) {
        result = result.replace(entry.sourceFrom as CharSequence, entry.sourceTo as CharSequence)
    }
    return [ok: true, candidateSrc: result, counts: counts]
}

// Proves the ordered comparison stream between the stripped-Dev candidate
// and the final production candidate is identical EXCEPT at exactly the
// positions the allowlist intends to change, and that every allowlist entry
// actually fired exactly once. Reuses Comparison.buildComparisonRecords() -
// the same TOK/NL record shape the phase-1/2a comment-stream check already
// proved correct - so this is an extension of that check, not a parallel
// reimplementation of it.
static Map compareSubstitutionAwareStreams(List<Token> strippedTokens, List<Token> candidateTokens, List<Map> allowlist, def comparisonClass) {
    List strippedRecords = comparisonClass.buildComparisonRecords(strippedTokens)
    List candidateRecords = comparisonClass.buildComparisonRecords(candidateTokens)
    if (strippedRecords.size() != candidateRecords.size()) {
        return [ok: false, reason: "record count differs: stripped ${strippedRecords.size()}, candidate ${candidateRecords.size()}"]
    }
    Map<String, Integer> fired = [:]
    allowlist.each { fired[it.name as String] = 0 }
    for (int i = 0; i < strippedRecords.size(); i++) {
        List s = strippedRecords[i] as List
        List c = candidateRecords[i] as List
        if (s == c) continue
        // A record differs. Only an allowlisted TOK->TOK substitution, at
        // this exact position, is acceptable.
        if (s[0] != 'TOK' || c[0] != 'TOK') {
            return [ok: false, reason: "record ${i} differs and is not a TOK substitution: expected ${s}, got ${c}"]
        }
        String sText = s[2] as String
        String cText = c[2] as String
        Map match = allowlist.find { it.tokenFrom == sText && it.tokenTo == cText }
        if (match == null) {
            return [ok: false, reason: "record ${i} differs but matches no allowlist entry: expected [${sText}], got [${cText}]"]
        }
        fired[match.name as String] = (fired[match.name as String] as int) + 1
    }
    List notFiredOnce = fired.findAll { k, v -> v != 1 }.collect { k, v -> "${k} fired ${v} time(s)" }
    if (!notFiredOnce.isEmpty()) {
        return [ok: false, reason: "allowlist entries did not each fire exactly once: ${notFiredOnce.join(', ')}"]
    }
    return [ok: true, recordCount: strippedRecords.size()]
}

static String sha256(byte[] bytes) {
    MessageDigest md = MessageDigest.getInstance('SHA-256')
    return md.digest(bytes).collect { String.format('%02x', it) }.join('')
}

// Canonicalizes line endings to LF. Without this the same commit can produce
// different candidate bytes: a checkout may legitimately hold CRLF (git's own
// core.autocrlf) while provenance hashing normalizes it away. Applied to every
// text input AND to this tool's own header literal below, which carries
// whatever line endings this file itself was checked out with.
static String canonicalizeLineEndings(String text) {
    if (text == null) return null
    return text.replace('\r\n', '\n').replace('\r', '\n')
}

// The complete required production header (methodology "Production header"
// section): Apache licence/copyright notice, a short generated-file notice,
// the exact Dev source commit SHA, and a link to the canonical annotated
// Dev source at that exact commit. Deliberately carries no wall-clock
// timestamp or other non-deterministic content - reproducibility (item 7)
// depends on the header being a pure function of devCommitSha alone. The
// long Dev architecture/commentary block is not reproduced here because it
// is a single Groovy comment token at the top of the Dev source and is
// already removed by the comment-stripping stage before this header is
// ever prepended - nothing extra to strip for that on this file's part.
static String buildHeader(String devCommitSha) {
    return canonicalizeLineEndings("""/*
 * Automation Map
 *
 * Copyright 2026 Gordon Thelander
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 * GENERATED FILE - do not edit directly. Produced by the production-profile
 * builder from the annotated Dev source at commit ${devCommitSha}; developer
 * comments and Dev-only build markers are not present in this file.
 *
 * Canonical annotated source:
 * https://github.com/GordonThelander/hubitat-automation-map/blob/${devCommitSha}/apps/automation_map.groovy
 */
""")
}

// Removes ONLY a leading run of pure line-ending bytes (review, queue 450,
// scope decision 3) - the byte span that remains at the very start of the
// candidate body after comment-stripping removed the Dev architecture
// header comment and preserved its own internal line breaks as blank lines
// (phase 1's byte/line-exactness design). Proven safe, not assumed: locates
// the first non-EOF token's startIndex in the ALREADY-VERIFIED candidate
// token stream, and refuses to trim anything unless every character before
// that index is a bare CR or LF - i.e. it can never remove an executable
// token, a string/GString, or any other real content, only blank lines.
// Deterministic: a pure function of src and tokens, no randomness or time.
static Map trimLeadingBlankLines(String src, List<Token> tokens) {
    // A real newline is its own NL-type token (the same lexer behaviour
    // phase 1's line-ending-preservation design already relies on) - so the
    // FIRST token in a candidate that starts with blank lines is itself an
    // NL token, not the real content that follows it. Must skip every
    // leading NL token, not just EOF, or nothing is ever trimmed (caught by
    // this function's own test asserting removedChars > 0 against a fixture
    // with a genuinely blank-line-leading body).
    int nlType = GroovyLexer.NL
    Token first = tokens.find { it.type != nlType && it.type != groovyjarjarantlr4.v4.runtime.Token.EOF }
    if (first == null) {
        return [ok: true, trimmedSrc: src, removedChars: 0]
    }
    int firstStart = first.startIndex
    String prefix = src.substring(0, firstStart)
    boolean allLineEndings = prefix.chars().allMatch { int c -> c == ('\r' as char) || c == ('\n' as char) }
    if (!allLineEndings) {
        return [ok: false, reason: "leading prefix before the first token contains non-line-ending content, refusing to trim: ${prefix.take(80)}"]
    }
    return [ok: true, trimmedSrc: src.substring(firstStart), removedChars: firstStart]
}

// --- Destination safety (review, queue 450, blocking finding 3) ---

// Writes finalSrc to outFile such that a failure at any point during this
// function leaves outFile either absent or exactly as it was before this
// call, NEVER a partial write and never a stale file silently presented as
// current. The CLI flow already invalidates the requested destination much
// earlier (immediately after the output-path safety check, before
// provenance/transformation work - review, queue 452) specifically so a
// LATER failure of any kind leaves the destination absent rather than a
// stale prior success; the delete here is a harmless, idempotent defense-in-
// depth repeat for any caller of this function that has not already done
// so. Sequence: remove any pre-existing destination (no-op if already
// gone), write to a unique temporary sibling in the same directory, verify
// its content read back matches exactly, then move it into place - via
// Java's ATOMIC_MOVE where the filesystem supports it, falling back to a
// plain same-directory REPLACE_EXISTING move otherwise (still a rename on
// the same volume in practice, not a copy). The temporary sibling is always
// cleaned up on any failure path.
//
// failAfterDeleteHook exists ONLY for the test suite (review 450's required
// "prove a deliberately failed rerun does not leave the previous destination
// presented as new" test) - a normal call passes null and it does nothing.
static Map writeCandidateAtomically(File outFile, String finalSrc, Closure failAfterDeleteHook = null) {
    outFile.parentFile?.mkdirs()
    if (outFile.exists()) {
        if (!outFile.delete()) {
            return [ok: false, reason: "could not remove the pre-existing destination ${outFile.canonicalFile} before regenerating it"]
        }
    }
    if (failAfterDeleteHook != null) {
        try {
            failAfterDeleteHook()
        } catch (Throwable t) {
            return [ok: false, reason: "test-injected failure after destination removal: ${t.message}"]
        }
    }
    File tempSibling = File.createTempFile('production-profile-', '.tmp', outFile.parentFile)
    try {
        tempSibling.setText(finalSrc, 'UTF-8')
        String readBack = tempSibling.getText('UTF-8')
        if (readBack != finalSrc) {
            return [ok: false, reason: 'content verification failed after writing the temporary sibling - refusing to move it into place']
        }
        try {
            Files.move(tempSibling.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(tempSibling.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return [ok: true]
    } catch (Exception e) {
        return [ok: false, reason: "write/move failed: ${e.message}"]
    } finally {
        if (tempSibling.exists()) {
            tempSibling.delete()
        }
    }
}

// --- Core generation (extracted, review queue 461, so the package-build
// orchestrator can call this in-process against an already-verified commit
// instead of shelling out to a second, independently re-verifying CLI
// invocation - the whole point being one shared, structurally-guaranteed
// commit for the whole package, not "two commands that happened not to
// race"). Pure: takes an already-resolved inFile/devCommitSha, performs no
// provenance check of its own (the caller - CLI body below, or the
// orchestrator - is responsible for that), and does not write anything to
// disk. Returns ok:false with a stage/reason on the first failure,
// otherwise ok:true plus finalSrc and every reporting field the CLI's own
// stdout uses. ---
static Map generateAppCandidateSource(def Comparison, def StripComments, File inFile, String devCommitSha) {
    String originalSrc = canonicalizeLineEndings(inFile.getText('UTF-8'))
    List<Token> originalTokens = lexAllChannels(StripComments, originalSrc)

    // Stage A: comment removal only, via the SAME functions strip-comments.groovy
    // itself uses - proven correct there, not reimplemented here.
    String strippedSrc = StripComments.stripSource(originalSrc, originalTokens, Comparison)
    List<Token> strippedTokens = lexAllChannels(StripComments, strippedSrc)
    Map stageAStream = Comparison.compareComparisonStreams(originalTokens, strippedTokens)
    if (!stageAStream.ok) {
        return [ok: false, stage: 'stage A (comment removal) ordered comparison-stream equivalence', reason: stageAStream.reason]
    }
    Set<Integer> stringTypes = StripComments.stringLikeTypes()
    Map stageAStrings = StripComments.compareStringTokens(originalTokens, strippedTokens, stringTypes, Comparison)
    if (!stageAStrings.ok) {
        return [ok: false, stage: 'stage A (comment removal) string-token equivalence', reason: stageAStrings.reason]
    }

    // Stage B: apply the allowlist to the STRIPPED (comment-free) source, then
    // prove the only executable-token differences from stage A's output are
    // exactly the allowlisted substitutions, each firing exactly once.
    List<Map> allowlistEntries = allowlist()
    Map substitution = applyAllowlist(strippedSrc, allowlistEntries)
    if (!substitution.ok) {
        return [ok: false, stage: 'allowlist substitution', reason: substitution.reason]
    }
    String candidateBodySrc = substitution.candidateSrc as String
    List<Token> candidateBodyTokens = lexAllChannels(StripComments, candidateBodySrc)
    Map stageBStream = compareSubstitutionAwareStreams(strippedTokens, candidateBodyTokens, allowlistEntries, Comparison)
    if (!stageBStream.ok) {
        return [ok: false, stage: 'stage B (allowlist substitution) token equivalence', reason: stageBStream.reason]
    }
    // Line-ending count must be identical between stripped and candidate bodies -
    // substitutions replace single-line declarations with single-line
    // declarations, never introducing or removing a line break.
    int strippedLineEndings = Comparison.lineEndingPattern().matcher(strippedSrc).with { m -> int c = 0; while (m.find()) c++; c }
    int candidateLineEndings = Comparison.lineEndingPattern().matcher(candidateBodySrc).with { m -> int c = 0; while (m.find()) c++; c }
    if (strippedLineEndings != candidateLineEndings) {
        return [ok: false, stage: 'line-ending count check', reason: "changed by substitution: stripped ${strippedLineEndings}, candidate ${candidateLineEndings}"]
    }

    // Trim the leading blank-line run left by the removed Dev architecture
    // header comment - proven safe against the already-verified token stream,
    // not assumed (review, queue 450, scope decision 3).
    Map trim = trimLeadingBlankLines(candidateBodySrc, candidateBodyTokens)
    if (!trim.ok) {
        return [ok: false, stage: 'leading-blank-line trim', reason: trim.reason]
    }
    String trimmedBodySrc = trim.trimmedSrc as String

    String header = buildHeader(devCommitSha)
    String finalSrc = header + trimmedBodySrc

    List<String> forbidden = forbiddenInCandidate()
    List<String> leaked = forbidden.findAll { finalSrc.contains(it) }
    if (!leaked.isEmpty()) {
        return [ok: false, stage: 'Dev-marker leak check', reason: "found forbidden text in the generated candidate: ${leaked.join(', ')}"]
    }

    // Fail-closed backstop proving canonicalization actually held, rather than
    // trusting it - a stray CR anywhere means some path bypassed it and the
    // output is no longer a pure function of the source commit.
    int strayCr = finalSrc.count('\r')
    if (strayCr > 0) {
        return [ok: false, stage: 'line-ending canonicalization check', reason: "generated candidate contains ${strayCr} carriage return(s); output must be LF-only to stay reproducible across checkouts"]
    }

    return [
        ok: true, finalSrc: finalSrc,
        stageAStream: stageAStream, stageAStrings: stageAStrings,
        stageBStream: stageBStream, allowlistEntries: allowlistEntries, substitutionCounts: substitution.counts,
        trimRemovedChars: trim.removedChars, forbiddenCount: forbidden.size(),
    ]
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
File guardFile = new File(toolDir, 'pinned-guard.groovy')
File comparisonFile = new File(toolDir, 'comparison.groovy')
File stripperFile = new File(toolDir, 'strip-comments.groovy')
[guardFile, comparisonFile, stripperFile].each { File f ->
    if (!f.exists()) {
        System.err.println("Expected ${f.name} next to this script at ${f} - not found. " +
            'Run this script from its own checked-out location, not a copy.')
        System.exit(1)
    }
}
def guard = new GroovyClassLoader(this.class.classLoader).parseClass(guardFile)
guard.require()
def Comparison = new GroovyClassLoader(this.class.classLoader).parseClass(comparisonFile)
// strip-comments.groovy is itself a runnable script (it has top-level CLI
// logic - arg parsing, System.exit), but parseClass() only compiles the
// class; it does not invoke that top-level body. Only its static functions
// (lexAllChannels, stripSource, stringLikeTypes, compareStringTokens) are
// called below - the same "load and call statics, never run()" composition
// pinned-guard.groovy/comparison.groovy already use.
def StripComments = new GroovyClassLoader(this.class.classLoader).parseClass(stripperFile)

if (args.size() != 1) {
    System.err.println('Usage: groovy production-profile.groovy <output.groovy>')
    System.exit(1)
}
File outFile = new File(args[0])

File tmpRoot = new File(repoRoot, 'tmp')
String outCanonicalPath = outFile.canonicalFile.path
if (!outCanonicalPath.startsWith(tmpRoot.canonicalPath + File.separator)) {
    System.err.println("Refusing to run: output path ${outCanonicalPath} is not under ${tmpRoot.canonicalPath} - " +
        'generated candidates must never land at the repository root or overwrite tracked Dev source.')
    System.exit(1)
}

// Invalidate any pre-existing destination NOW, immediately after the output
// path itself is confirmed safe (under tmp/) but BEFORE provenance or any
// transformation work runs - review, queue 452: a failed build (for ANY
// later reason, provenance included) must leave the requested destination
// absent, not a stale prior success sitting at the exact path a caller just
// asked to regenerate. Safe only because the path is already proven to be
// under repository tmp/, never tracked Dev source or anything else real.
if (outFile.exists()) {
    if (!outFile.delete()) {
        System.err.println("Refusing to run: could not remove the pre-existing destination ${outFile.canonicalFile} before regenerating it.")
        System.exit(1)
    }
}

Map provenance = resolveVerifiedProvenance(repoRoot, 'apps/automation_map.groovy')
if (!provenance.ok) {
    System.err.println("FAIL provenance verification: ${provenance.reason}")
    System.exit(1)
}
String devCommitSha = provenance.sha as String
File inFile = provenance.file as File

if (inFile.canonicalPath == outFile.canonicalPath) {
    System.err.println('Refusing to run: input and output resolve to the same file. This tool must never ' +
        'overwrite the annotated source it reads from.')
    System.exit(1)
}

Map genResult = generateAppCandidateSource(Comparison, StripComments, inFile, devCommitSha)
if (!genResult.ok) {
    System.err.println("FAIL ${genResult.stage}: ${genResult.reason}")
    System.exit(1)
}
String finalSrc = genResult.finalSrc as String

Map writeResult = writeCandidateAtomically(outFile, finalSrc)
if (!writeResult.ok) {
    System.err.println("FAIL writing candidate: ${writeResult.reason}")
    System.exit(1)
}

long originalBytes = inFile.length()
long candidateBytes = outFile.length()
String candidateHash = sha256(Files.readAllBytes(outFile.toPath()))
long byteReduction = originalBytes - candidateBytes
double pct = originalBytes > 0 ? (byteReduction * 100.0 / originalBytes) : 0

println "OK - production candidate generated (profile v${profileVersion()})."
println 'NOTE: this is an app-source-only candidate. There is no production packageManifest.json yet ' +
    'and no whole-package production validation path - this is NOT a complete or deployable production ' +
    'artifact on its own (review, queue 450).'
println "Provenance: apps/automation_map.groovy at verified clean commit ${devCommitSha} (git rev-parse --verify HEAD, working tree confirmed clean for this file)."
println "Stage A (comment removal): ${genResult.stageAStream.recordCount} comparison records identical; ${genResult.stageAStrings.stringTokenCount} string/GString tokens byte-identical."
println "Stage B (allowlist substitution): ${genResult.stageBStream.recordCount} comparison records identical except ${genResult.allowlistEntries.size()} allowlisted substitution(s), each fired exactly once: ${genResult.substitutionCounts}."
println "Leading blank-line trim: removed ${genResult.trimRemovedChars} pure line-ending byte(s), verified to contain no token or other content."
println "Dev-marker leak check: clean (none of ${genResult.forbiddenCount} forbidden pattern(s) found)."
println "Original: ${originalBytes} bytes (physical file size)."
println "Candidate: ${candidateBytes} bytes (physical file size). SHA-256: ${candidateHash}"
println "Reduction: ${byteReduction} bytes (${String.format('%.1f', pct)}%)."
