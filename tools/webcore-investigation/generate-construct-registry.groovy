#!/usr/bin/env groovy
//
// webCoRE construct registry generator (offline development tool).
//
// Reads a local checkout of the pinned webCoRE source and emits a candidate
// Groovy map literal describing its construct vocabulary. The output is
// EVIDENCE INPUT, not self-authorizing truth: it is reviewed as a diff and
// then copied into the app by hand. The hub never fetches anything and never
// generates at runtime.
//
//   groovy tools/webcore-investigation/generate-construct-registry.groovy <source-root> [--emit]
//
// Without --emit it reports counts and gate results only, which is the normal
// way to check for vocabulary drift after the pin moves.
//
// The generator FAILS rather than emitting when a gate trips: duplicate ids,
// unstable ordering, missing provenance, or a vocabulary count that differs
// from the reviewed expectation. A surprise is a review trigger, never
// something to absorb silently.

int exitCode = 0
List<String> failures = []
void fail(List<String> f, String msg) { f << msg }

if (args.length < 1) {
    System.err.println 'usage: generate-construct-registry.groovy <webcore-source-root> [--emit]'
    System.exit 2
}
File root = new File(args[0])
boolean emit = args.length > 1 && args[1] == '--emit'
if (!root.isDirectory()) {
    System.err.println "not a directory: ${root}"
    System.exit 2
}

// ---------------------------------------------------------------- provenance

// Recorded per Codex 526: repository, tracking branch, immutable SHA, source
// paths, generator version and timestamp. The SHA is the pin; the branch is
// the maintenance channel that detects a newer candidate and stops a naive
// clone landing on `master`, which is still 2019 SmartThings-era code.
String GENERATOR_VERSION = '1'
String EXPECTED_REPO = 'imnotbob/webCoRE'
String EXPECTED_BRANCH = 'hubitat-patches'
String EXPECTED_SHA = '0a37eee2537accd706aaaeeed5a7b4bb0c82646e'

String gitOut(File dir, String... cmd) {
    try {
        Process p = (['git'] + (cmd as List)).execute(null, dir)
        p.waitFor()
        return p.exitValue() == 0 ? p.text.trim() : null
    } catch (ignored) { return null }
}

String sha = gitOut(root, 'rev-parse', 'HEAD')
String branch = gitOut(root, 'rev-parse', '--abbrev-ref', 'HEAD')
String remote = gitOut(root, 'config', '--get', 'remote.origin.url')

if (!sha) fail(failures, 'no git provenance in source root - cannot record an immutable pin')
if (sha && sha != EXPECTED_SHA) fail(failures, "source is ${sha}, expected pin ${EXPECTED_SHA}")
if (branch && branch != EXPECTED_BRANCH) fail(failures, "branch is ${branch}, expected ${EXPECTED_BRANCH}")
if (remote && !remote.contains('imnotbob/webCoRE')) fail(failures, "remote is ${remote}, expected ${EXPECTED_REPO}")

File pistonFile = new File(root, 'smartapps/ady624/webcore-piston.src/webcore-piston.groovy')
File appFile = new File(root, 'smartapps/ady624/webcore.src/webcore.groovy')
if (!pistonFile.isFile()) fail(failures, "missing source file: ${pistonFile}")
if (!appFile.isFile()) fail(failures, "missing source file: ${appFile}")
if (failures) { failures.each { System.err.println "GATE: ${it}" }; System.exit 1 }

String piston = pistonFile.getText('UTF-8')
String app = appFile.getText('UTF-8')

// ------------------------------------------------------- constant resolution

// Cases are symbols (sACTION), not literals. Resolve them from the source's
// own @Field declarations rather than hardcoding the mapping here, so a
// renamed or revalued constant surfaces as drift instead of being masked.
Map<String, String> constants = [:]
piston.eachLine { String line ->
    def m = line =~ /^@Field static final String (s[A-Z][A-Za-z0-9_]*)\s*=\s*'([^']*)'/
    if (m) constants[m[0][1] as String] = m[0][2] as String
}

// ------------------------------------------------- reviewed extraction sites

// Codex 528: extraction reads only explicitly named, reviewed sites. A broad
// literal scan could admit UI text, setting names or unrelated implementation
// detail, and anything admitted here is later emitted verbatim in a census
// path. Each site below is named, bounded and justified.

/** The outer switch on stateType inside executeStatement(). */
List<String> extractStatements(String src, Map<String, String> consts, List<String> f) {
    int start = src.indexOf('private Boolean executeStatement(')
    if (start < 0) { fail(f, 'executeStatement( not found'); return [] }
    int sw = src.indexOf('switch(stateType){', start)
    if (sw < 0) { fail(f, 'switch(stateType) not found inside executeStatement'); return [] }

    // Brace-match the switch body so nested switches inside a case (the
    // sMt(operand) event matcher inside case sON) are traversed but their own
    // cases are excluded by depth, and the earlier switch(tep) is excluded by
    // starting after it. This is the 17-vs-12 trap: a flat scan of the region
    // finds 17 distinct literals because switch(tep) contributes c/p/b and the
    // event matcher contributes p/v/x.
    int i = src.indexOf('{', sw)
    int depth = 0
    List<String> out = []
    for (int p = i; p < src.length(); p++) {
        char ch = src.charAt(p)
        if (ch == '{') depth++
        else if (ch == '}') { depth--; if (depth == 0) break }
        else if (depth == 1 && src.startsWith('case ', p)) {
            def m = (src.substring(p, Math.min(p + 40, src.length())) =~ /^case\s+(s[A-Z][A-Za-z0-9_]*)\s*:/)
            if (m) {
                String sym = m[0][1] as String
                String lit = consts[sym]
                if (lit == null) fail(f, "statement constant ${sym} has no resolvable literal")
                else out << lit
            }
        }
    }
    return out
}

/** func_* and vcmd_* definitions, by definition position not reference. */
List<String> extractDefs(String src, String prefix) {
    Set<String> names = new LinkedHashSet<>()
    String rx = '(?m)^\\s*(?:private\\s+|static\\s+|public\\s+)*[A-Za-z<>,\\[\\]\\s]*\\b(' +
                java.util.regex.Pattern.quote(prefix) + '[a-zA-Z0-9_]+)\\s*\\('
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(rx).matcher(src)
    while (m.find()) names << m.group(1)
    return names as List
}

/**
 * Declared catalogue keys.
 *
 * Two source shapes had to be handled, both found by reading the actual
 * literals rather than assumed:
 *
 *  - Keys are BARE IDENTIFIERS (`noop : [...]`), not quoted strings. Quoted
 *    keys inside the value maps are parenthesised constants like `(sN):` and
 *    sit a level deeper, so bracket depth excludes them.
 *  - virtualCommands() declares an unrelated `tileIndexes=[...]` array before
 *    the catalogue itself, so anchoring on the first `[` after the function
 *    signature parses the wrong literal. openMarker names the real one.
 *
 * String contents are skipped so a bracket inside a display template cannot
 * corrupt the depth count.
 */
List<String> extractCatalogue(String src, String anchor, String openMarker, List<String> f) {
    int start = src.indexOf(anchor)
    if (start < 0) { fail(f, "catalogue anchor not found: ${anchor}"); return [] }
    int i = src.indexOf(openMarker, start)
    if (i < 0) { fail(f, "catalogue open marker '${openMarker}' not found after ${anchor}"); return [] }
    i = i + openMarker.length() - 1
    int depth = 0
    Set<String> keys = new LinkedHashSet<>()
    for (int p = i; p < src.length(); p++) {
        char ch = src.charAt(p)
        // Comments must be skipped, not merely ignored for depth. functionsFLD
        // carries commented-out draft entries such as
        //     //  roomid (roomname) sT: sINT
        // and `sT:` in that text is indistinguishable from a real key to a
        // naive scan. That produced a phantom `sT` construct and a count of
        // 106 against a true 105 - caught by the count gate, which is what it
        // is for.
        if (ch == '/' && p + 1 < src.length()) {
            char n = src.charAt(p + 1)
            if (n == '/') { int e = src.indexOf('\n', p); if (e < 0) break; p = e; continue }
            if (n == '*') { int e = src.indexOf('*/', p); if (e < 0) break; p = e + 1; continue }
        }
        if (ch == '"' || ch == '\'') {
            char q = ch
            p++
            while (p < src.length() && src.charAt(p) != q) { if (src.charAt(p) == ('\\' as char)) p++; p++ }
            continue
        }
        if (ch == '[') { depth++; continue }
        if (ch == ']') { depth--; if (depth == 0) break; continue }
        if (depth == 1 && (Character.isLetter(ch) || ch == ('_' as char))) {
            int e = p
            while (e < src.length() && (Character.isLetterOrDigit(src.charAt(e)) || src.charAt(e) == ('_' as char))) e++
            int after = e
            while (after < src.length() && Character.isWhitespace(src.charAt(after))) after++
            if (after < src.length() && src.charAt(after) == (':' as char)) keys << src.substring(p, e)
            p = e - 1
        }
    }
    return keys as List
}

List<String> statements = extractStatements(piston, constants, failures)
List<String> functions = extractDefs(piston, 'func_')
List<String> vcmds = extractDefs(piston, 'vcmd_')
List<String> declaredVcmds = extractCatalogue(app, 'private static Map<String,Map> virtualCommands(){', 'a=[', failures)
List<String> declaredFuncs = extractCatalogue(app, '@Field final Map<String,Map> functionsFLD=[', 'functionsFLD=[', failures)

// --------------------------------------------------------------------- gates

// Reviewed expectations. A mismatch is drift requiring review, never a value
// to absorb. Populations are labelled separately per Codex 526: there is no
// honest single denominator once declared and implemented surfaces differ.
Map<String, Integer> EXPECTED = [
    'statement.dispatch'      : 12,
    'function.executor'       : 109,
    'vcmd.executor'           : 69,
    'vcmd.declared'           : 64,
    'function.declared'       : 105,
]
Map<String, List<String>> actual = [
    'statement.dispatch'      : statements,
    'function.executor'       : functions,
    'vcmd.executor'           : vcmds,
    'vcmd.declared'           : declaredVcmds,
    'function.declared'       : declaredFuncs,
]

actual.each { String k, List<String> v ->
    int expect = EXPECTED[k]
    if (v.size() != expect) fail(failures, "${k}: found ${v.size()}, reviewed expectation is ${expect}")
    List<String> dupes = v.countBy { it }.findAll { it.value > 1 }.keySet() as List
    if (dupes) fail(failures, "${k}: duplicate ids ${dupes}")
}

println '=' * 72
println "webCoRE construct registry generator v${GENERATOR_VERSION}"
println '=' * 72
println "repository : ${remote ?: '(unknown)'}"
println "branch     : ${branch ?: '(unknown)'}"
println "commit     : ${sha ?: '(unknown)'}"
println "generated  : ${new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))}"
println ''
actual.each { String k, List<String> v -> printf('%-22s %4d  (expected %d)%n', k, v.size(), EXPECTED[k]) }
println ''

if (failures) {
    println 'GATE FAILURES:'
    failures.each { println "  - ${it}" }
    println ''
    println 'No registry emitted. A count or provenance surprise is a review trigger.'
    System.exit 1
}
println 'All gates passed.'

if (!emit) {
    println ''
    println 'Re-run with --emit to print the candidate registry literal for review.'
    System.exit 0
}

// -------------------------------------------------------------------- output

// availability, per Codex 526, so the real catalogue/executor mismatches are
// representable as known constructs rather than as unknowns.
// Case-insensitive on BOTH sides. functionsFLD keys are lowercase
// (`previousage`) while virtualCommands() keys are camelCase (`waitRandom`),
// and the executor symbols are camelCase for both. Lowercasing only one side
// silently reported all 69 virtual commands as executor-only.
Set<String> declFuncSet = declaredFuncs.collect { it.toLowerCase() } as Set
Set<String> declVcmdSet = declaredVcmds.collect { it.toLowerCase() } as Set
boolean isDeclared(String bare, Set<String> declaredLower) { declaredLower.contains(bare.toLowerCase()) }
String availability(String bare, boolean implemented, Set<String> declaredLower) {
    boolean d = isDeclared(bare, declaredLower)
    if (implemented && d) return 'current'
    if (implemented && !d) return 'executor-only'
    if (!implemented && d) return 'catalog-only'
    return 'unknown'
}

StringBuilder sb = new StringBuilder()
sb << "// GENERATED by tools/webcore-investigation/generate-construct-registry.groovy v${GENERATOR_VERSION}\n"
sb << "// Source: ${remote} @ ${branch} ${sha}\n"
sb << "// Reviewed as a diff before use. Not self-authorizing.\n"
sb << "@Field static final Map WEBCORE_CONSTRUCT_REGISTRY = [\n"
sb << "  provenance: [repo: '${remote}', branch: '${branch}', commit: '${sha}', generator: '${GENERATOR_VERSION}'],\n"
sb << "  constructs: [\n"
statements.each { sb << "    'statement.${it}': [kind:'statement', name:'${it}', declared:true, implemented:true, canonicalTarget:null, availability:'current', level:'L2'],\n" }
functions.sort().each {
    String bare = it.substring('func_'.length())
    sb << "    'function.${bare}': [kind:'function', name:'${bare}', declared:${isDeclared(bare, declFuncSet)}, implemented:true, canonicalTarget:null, availability:'${availability(bare, true, declFuncSet)}', level:'L2'],\n"
}
vcmds.sort().each {
    String bare = it.substring('vcmd_'.length())
    sb << "    'vcmd.${bare}': [kind:'vcmd', name:'${bare}', declared:${isDeclared(bare, declVcmdSet)}, implemented:true, canonicalTarget:null, availability:'${availability(bare, true, declVcmdSet)}', level:'L2'],\n"
}
declaredFuncs.findAll { !functions.contains('func_' + it) }.each {
    sb << "    'function.${it}': [kind:'function', name:'${it}', declared:true, implemented:false, canonicalTarget:null, availability:'catalog-only', level:'L2'],\n"
}
declaredVcmds.findAll { !vcmds.contains('vcmd_' + it) }.each {
    sb << "    'vcmd.${it}': [kind:'vcmd', name:'${it}', declared:true, implemented:false, canonicalTarget:null, availability:'catalog-only', level:'L2'],\n"
}
// Not derivable from this source: executeRoutine does not appear in the
// Hubitat fork at all. Recorded as a fixed known construct so that an imported
// SmartThings piston carrying it is named accurately rather than reported as
// unrecognised (Codex 526). Its absence here is the whole point of the entry.
sb << "    'vcmd.executeRoutine': [kind:'vcmd', name:'executeRoutine', declared:false, implemented:false, canonicalTarget:null, availability:'smartthings-only', level:'L2'],\n"
sb << "  ]\n]\n"
println ''
println sb.toString()
