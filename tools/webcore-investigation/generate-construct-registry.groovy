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
boolean emit = args.length > 1 && (args[1] == '--emit' || args[1] == '--candidate')
// --candidate prints the literal and nothing else, so its determinism can be
// asserted directly rather than by grepping report lines out of --emit.
boolean candidateOnly = args.length > 1 && args[1] == '--candidate'

// In --candidate mode the ONLY thing on stdout is the literal, so its
// determinism can be asserted with a plain diff. Report lines are suppressed
// rather than filtered, because filtering is what produced a determinism claim
// that was true of the filtered output and false of the actual output.
// Restored before any gate failure so those stay visible.
PrintStream realOut = System.out
if (candidateOnly) System.setOut(new PrintStream(new OutputStream() { void write(int b) {} }))
if (!root.isDirectory()) {
    System.err.println "not a directory: ${root}"
    System.exit 2
}

// ---------------------------------------------------------------- provenance

// Recorded per review 526: repository, tracking branch, immutable SHA, source
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
// Missing provenance fails exactly like wrong provenance. The earlier form
// guarded on `remote &&`, so an absent remote passed silently - fail-open on
// the one check whose whole job is establishing what source this is.
if (!remote) fail(failures, 'no remote.origin.url - repository provenance cannot be established')
else if (!remote.contains('imnotbob/webCoRE')) fail(failures, "remote is ${remote}, expected ${EXPECTED_REPO}")

String PISTON_PATH = 'smartapps/ady624/webcore-piston.src/webcore-piston.groovy'
String APP_PATH = 'smartapps/ady624/webcore.src/webcore.groovy'
File pistonFile = new File(root, PISTON_PATH)
File appFile = new File(root, APP_PATH)
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

// review 528: extraction reads only explicitly named, reviewed sites. A broad
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

// ----------------------------------------------------- dispatch site freeze

// Thirteen dispatch sites, frozen 2026-09-10 after review in queue 532/533/534
// and approval in 535. EXACT MEMBER SETS ARE THE PRIMARY GATE, counts are a
// secondary sanity check: a count-only gate cannot see a substitution where one
// member disappears and a phantom appears in the same run.
//
// sharedBranch records members that fall through to a common body. It is
// PROVENANCE ONLY and never collapses members (review 533): sunriseTime and
// sunsetTime are semantically different values even where execution shares a
// path, and the five numeric labels at expression.item.type are five recognised
// serialized forms handled by one conversion branch.
//
// Sites are keyed by method + switch subject + ordinal within that method, so a
// line shift is reported as informational drift rather than failing the gate.
Map<String, Map> FROZEN_SITES = [
  'operand.event-match.type': [
    method: 'executeStatement', subject: 'sMt(operand)', ordinal: 0, line: 4117, hasDefault: false,
    members: ['p', 'v', 'x'], shared: []],
  'operand.evaluate.type': [
    method: 'evaluateOperand', subject: 'sMt(operand)', ordinal: 0, line: 7532, hasDefault: false,
    members: ['', 'p', 'd', 'v', 's', 'x', 'c', 'e', 'u'], shared: []],
  'operand.subscribe.type': [
    method: 'subscribeAll', subject: 'sMt(operand)', ordinal: 0, line: 8855, hasDefault: false,
    members: ['p', 'v', 'x', 'c', 'e'], shared: [['c', 'e']]],
  'expression.item.type': [
    method: 'evaluateExpression', subject: 'sMt(item)', ordinal: 0, line: 10718, hasDefault: true,
    members: ['integer', 'float', 'double', 'decimal', 'number'],
    shared: [['integer', 'float', 'double', 'decimal', 'number']]],
  'virtual-device.evaluate.name': [
    method: 'evaluateOperand', subject: 'oV', ordinal: 0, line: 7570, hasDefault: false,
    members: ['time', 'date', 'datetime', 'mode', 'powerSource', 'hsmStatus', 'hsmAlert',
              'hsmSetArm', 'hsmRule', 'hsmRules', 'pistonResume', 'cloudBackup', 'lowMemory',
              'manualReboot', 'update', 'systemStart', 'severeLoad', 'zigbeeOff', 'zigbeeOn',
              'zwaveCrashed', 'sunriseTime', 'sunsetTime', 'tile', 'ifttt', 'email', 'routine'],
    shared: [['time', 'date', 'datetime'], ['mode', 'powerSource', 'hsmStatus'],
             ['hsmSetArm', 'hsmRule', 'hsmRules', 'pistonResume', 'cloudBackup', 'lowMemory',
              'manualReboot', 'update', 'systemStart', 'severeLoad', 'zigbeeOff', 'zigbeeOn',
              'zwaveCrashed', 'sunriseTime', 'sunsetTime', 'tile'], ['ifttt', 'email']]],
  'virtual-device.subscribe.name': [
    method: 'subscribeAll', subject: 'operV', ordinal: 0, line: 8917, hasDefault: false,
    members: ['pistonResume', 'time', 'date', 'datetime', 'mode', 'tile', 'powerSource',
              'cloudBackup', 'lowMemory', 'systemStart', 'severeLoad', 'zigbeeOff', 'zigbeeOn',
              'zwaveCrashed', 'sunriseTime', 'sunsetTime', 'hsmStatus', 'hsmAlert', 'hsmSetArm',
              'hsmRule', 'hsmRules', 'email', 'ifttt'],
    shared: [['time', 'date', 'datetime', 'mode', 'tile', 'powerSource', 'cloudBackup', 'lowMemory',
              'systemStart', 'severeLoad', 'zigbeeOff', 'zigbeeOn', 'zwaveCrashed', 'sunriseTime',
              'sunsetTime', 'hsmStatus', 'hsmAlert', 'hsmSetArm', 'hsmRule', 'hsmRules']]],
  'preset.evaluate.value-type': [
    method: 'evaluateOperand', subject: 'ovt', ordinal: 0, line: 7617, hasDefault: true,
    members: ['time', 'datetime'], shared: [['time', 'datetime']]],
  'preset.evaluate.name': [
    method: 'evaluateOperand', subject: 'sMs(operand,sS)', ordinal: 0, line: 7621, hasDefault: false,
    members: ['sunset', 'sunrise', 'midnight', 'noon'], shared: []],
  'constant.evaluate.value-type': [
    method: 'evaluateOperand', subject: 'ovt', ordinal: 1, line: 7662, hasDefault: true,
    members: ['time', 'date', 'datetime'], shared: [['date', 'datetime']]],
  'expression.evaluate.result-type': [
    method: 'evaluateExpression', subject: 'exprType', ordinal: 0, line: 10523, hasDefault: false,
    members: ['integer', 'long', 'decimal', 'time', 'datetime', 'int32', 'int64', 'date', 'bool',
              'boolean', 'dynamic', 'string', 'enum', 'error', 'phone', 'uri', 'text', 'number',
              'float', 'double', 'duration', 'variable', 'device', 'operand', 'function',
              'expression'],
    shared: [['integer', 'long', 'decimal'], ['time', 'datetime'], ['int32', 'int64', 'date'],
             ['bool', 'boolean'], ['string', 'enum', 'error', 'phone', 'uri', 'text'],
             ['number', 'float', 'double']]],
  'task.variable.value-type': [
    method: 'executeTask', subject: 'vt', ordinal: 0, line: 4502, hasDefault: true,
    members: ['variable'], shared: []],
  'statement.subscribe.timer-type': [
    method: 'subscribeAll', subject: 't', ordinal: 0, line: 9135, hasDefault: false,
    members: ['every', 'on'], shared: []],
  'statement.subscribe.type': [
    method: 'subscribeAll', subject: 't', ordinal: 1, line: 9143, hasDefault: false,
    members: ['action', 'if', 'while', 'repeat', 'on', 'switch', 'every'],
    shared: [['while', 'repeat']]],
]

// Device-selector grammar. NOT a numeric vocabulary gate (review 533/535):
// selectors are not switch-dispatched, so there is no discriminator population
// to count. Source basis is expandDeviceList (line 9435), which serves both
// physical operands and action targets.
// Saved structural forms. Not dispatch-site members: a shape the saved document
// carries whose meaning comes from how the executor consumes it rather than from
// a discriminator spelling. Kept separate from switch membership for the same
// reason the device-selector grammar is.
Map<String, Map> STRUCTURAL_FORMS = [
  'task-parameter.unselected': [
    refs: ['executor.execute-task', 'executor.evaluate-operand'],
    recognition: 'a saved task parameter Map carrying no t at all. executeTask evaluates every ' +
                 'parameter through mevaluateOperand, and evaluateOperand switches on sMt(operand) ' +
                 'with no default, so a Map without t matches no case and yields a dynamic null ' +
                 'rather than throwing. That null is the unselected state of an optional parameter: ' +
                 'cmd_setColor reads position 1 as the optional "only if switch is" enum and ' +
                 'ntMatSw() skips the restriction when it is null, and vcmd_toggleRandom falls back ' +
                 'to 50 when its optional probability does not cast. Observed on two of the six Dev ' +
                 'pistons, and confirmed against both consumers before being registered.'],
]

Map<String, Map> DEVICE_SELECTOR_GRAMMAR = [
  'device-selector.direct-identifier': [
    status: 'active', staticallyResolvable: true,
    recognition: 'isWcDev(entry): length 34, colon-delimited. Automation Map additionally requires ' +
                 '32 lowercase hex, which is deliberately STRICTER than the source. A 34-character ' +
                 'colon-delimited non-hex value stays unknown-device-selector rather than being ' +
                 'resolved (review 535).'],
  'device-selector.variable-device-list': [
    status: 'active', staticallyResolvable: false,
    recognition: 'variable of type device whose value is a List'],
  'device-selector.variable-name-cast': [
    status: 'active', staticallyResolvable: false,
    recognition: 'any other variable; value cast to string, resolved via getDevice, then hashed'],
  'device-selector.empty': [
    status: 'active', staticallyResolvable: true,
    recognition: 'falsy entry, skipped silently, recognised as no selector'],
  'device-selector.variable-device-map': [
    status: 'unreachable', staticallyResolvable: false,
    recognition: 'Intended branch, DEAD in the pinned source. expandDeviceList sets ' +
                 'Boolean mlocalVars=false and never assigns its own localVarsOnly parameter, so ' +
                 'the map branch cannot execute. Three call sites (4119, 8858, 9145) pass true ' +
                 'expecting the restriction. Recorded as a known compatibility/dead-path form, not ' +
                 'an active selector claim. Automation Map does not emulate the defect and does ' +
                 'not evaluate live variable state during a structural census.'],
]

// Reviewed aliases, established by the source survey and required by the spec
// to carry canonicalTarget. Distinct from sharedBranchGroup: these are cases
// where the source explicitly delegates one implementation to another, not
// merely cases sharing a switch branch.
Map<String, String> REVIEWED_ALIASES = [
    'bool'  : 'boolean',
    'substr': 'substring',
    'mid'   : 'substring',
    'text'  : 'string',
]

// --------------------------------------------------------------------- gates

// Reviewed expectations. A mismatch is drift requiring review, never a value
// to absorb. Populations are labelled separately per review 526: there is no
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
// Report metadata only. Deliberately NOT part of the candidate literal: an
// earlier version put a timestamp inside the emitted registry, which made
// "the candidate is deterministic" untestable and led to a byte-identical
// claim that was false as stated (review 537).
println "generated  : ${new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))}  (report only, not in candidate)"
println ''
actual.each { String k, List<String> v -> printf('%-22s %4d  (expected %d)%n', k, v.size(), EXPECTED[k]) }
println ''


/** Enclosing method name for a character offset. */
String enclosingMethodName(String text, int off) {
    int best = -1; String name = '(top-level)'
    def pat = java.util.regex.Pattern.compile(
        '(?m)^\\s*(?:private|public|protected|static|def|@Field)[^\\n]*?\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\([^\\n]*\\)\\s*\\{')
    def mm = pat.matcher(text)
    while (mm.find()) { if (mm.start() < off && mm.start() > best) { best = mm.start(); name = mm.group(1) } }
    return name
}

/**
 * Depth-1 case members of the switch opening at `off`, plus shared-branch
 * groups. Comments and string literals are skipped so a bracket or a key-like
 * token inside either cannot corrupt the walk - the same trap that produced a
 * phantom construct in the catalogue parser.
 */
Map switchDetail(String text, int off, Map<String, String> consts) {
    int open = text.indexOf('{', off)
    int depth = 0
    List<Map> labels = []
    for (int p = open; p < text.length(); p++) {
        char ch = text.charAt(p)
        if (ch == '/' && p + 1 < text.length()) {
            char n = text.charAt(p + 1)
            if (n == '/') { int e = text.indexOf('\n', p); if (e < 0) break; p = e; continue }
            if (n == '*') { int e = text.indexOf('*/', p); if (e < 0) break; p = e + 1; continue }
        }
        if (ch == '"' || ch == '\'') {
            char q = ch; p++
            while (p < text.length() && text.charAt(p) != q) { if (text.charAt(p) == ('\\' as char)) p++; p++ }
            continue
        }
        if (ch == '{') { depth++; continue }
        if (ch == '}') { depth--; if (depth == 0) break; continue }
        if (depth == 1 && text.startsWith('case ', p)) {
            String tail = text.substring(p, Math.min(p + 80, text.length()))
            def m = (tail =~ /^case\s+([A-Za-z_][A-Za-z0-9_]*|'[^']*')\s*:/)
            if (m) labels << [raw: m[0][1] as String, end: p + ((m[0][0] as String).length())]
        }
        if (depth == 1 && text.startsWith('default', p)) labels << [raw: 'default', end: p + 8]
    }
    List<String> members = []
    List<List<String>> shared = []
    List<String> run = []
    for (int k = 0; k < labels.size(); k++) {
        Map c = labels[k]
        String raw = c.raw as String
        if (raw == 'default') { if (run.size() > 1) shared << new ArrayList<String>(run); run = []; continue }
        String lit = raw.startsWith("'") ? raw[1..-2] : (consts.containsKey(raw) ? consts[raw] : ('?' + raw))
        members << lit
        run << lit
        boolean sharesNext = false
        if (k + 1 < labels.size()) {
            String between = text.substring(c.end as int, labels[k + 1].end as int)
            between = between.replaceAll(/case\s+[A-Za-z_'][^:]*:\s*$/, '').replaceAll(/default\s*:\s*$/, '')
            between = between.replaceAll(/(?s)\/\*.*?\*\//, '').replaceAll(/(?m)\/\/[^\n]*/, '')
            sharesNext = between.trim().isEmpty()
        }
        if (!sharesNext) { if (run.size() > 1) shared << new ArrayList<String>(run); run = [] }
    }
    return [members: members, shared: shared, hasDefault: labels.any { it.raw == 'default' }]
}

/** Every switch in the file, keyed method + subject + ordinal within method. */
Map<String, Map> extractSites(String text, Map<String, String> consts) {
    // One level of nested parens: switch(sMt(operand)){ is the important shape
    // and a flat [^)]* cannot span it. Missing that form is how the thirteenth
    // site stayed invisible through the first review pass.
    def pat = java.util.regex.Pattern.compile('(?m)^\\s*switch\\s*\\(((?:[^()]|\\([^()]*\\))*)\\)\\s*\\{')
    def mm = pat.matcher(text)
    Map<String, Integer> seen = [:]
    Map<String, Map> out = [:]
    while (mm.find()) {
        String subject = mm.group(1).trim()
        String method = enclosingMethodName(text, mm.start())
        String base = method + '|' + subject
        int ord = seen.containsKey(base) ? seen[base] : 0
        seen[base] = ord + 1
        Map d = switchDetail(text, mm.start(), consts)
        d.method = method; d.subject = subject; d.ordinal = ord
        d.line = text.substring(0, mm.start()).count('\n') + 1
        out[base + '|' + ord] = d
    }
    return out
}

Map<String, Map> observedSites = extractSites(piston, constants)

println ''
println 'Dispatch sites (membership is the gate, count is a sanity check):'
int siteOk = 0
FROZEN_SITES.each { String id, Map want ->
    String key = "${want.method}|${want.subject}|${want.ordinal}"
    Map got = observedSites[key]
    if (got == null) {
        // Fail closed: a configured site that cannot be located is drift, never
        // an absence to shrug at (review 531).
        fail(failures, "${id}: dispatch site not found (${key})")
        printf('  %-32s NOT FOUND%n', id)
        return
    }
    List<String> wantM = want.members as List
    List<String> gotM = got.members as List
    List<String> missing = wantM - gotM
    List<String> extra = gotM - wantM
    boolean membersOk = missing.isEmpty() && extra.isEmpty()
    boolean countOk = wantM.size() == gotM.size()
    boolean defaultOk = want.hasDefault == got.hasDefault
    Set<String> wantShared = (want.shared as List).collect { (it as List).join('+') } as Set
    Set<String> gotShared = (got.shared as List).collect { (it as List).join('+') } as Set
    boolean sharedOk = wantShared == gotShared

    if (!membersOk) {
        if (missing) fail(failures, "${id}: members missing from source ${missing}")
        if (extra) fail(failures, "${id}: unreviewed members present in source ${extra}")
    }
    if (!countOk) fail(failures, "${id}: count ${gotM.size()}, frozen expectation ${wantM.size()}")
    if (!defaultOk) fail(failures, "${id}: default branch ${got.hasDefault}, frozen ${want.hasDefault}")
    if (!sharedOk) fail(failures, "${id}: shared-branch groups changed, frozen ${wantShared} observed ${gotShared}")
    if (got.line != want.line) {
        // Informational only: the sites are keyed by method and ordinal, so a
        // line shift is provenance drift rather than a vocabulary change.
        printf('  %-32s ok (%2d)  [line %d, frozen %d]%n', id, gotM.size(), got.line, want.line)
    } else if (membersOk && countOk && defaultOk && sharedOk) {
        printf('  %-32s ok (%2d)%n', id, gotM.size())
    }
    if (membersOk && countOk && defaultOk && sharedOk) siteOk++
}
printf('%n  %d of %d sites match their frozen sets.%n', siteOk, FROZEN_SITES.size())
printf('  device-selector grammar: %d forms (%d active, %d unreachable), no numeric gate.%n',
       DEVICE_SELECTOR_GRAMMAR.size(),
       DEVICE_SELECTOR_GRAMMAR.count { it.value.status == 'active' },
       DEVICE_SELECTOR_GRAMMAR.count { it.value.status == 'unreachable' })

if (failures) {
    System.setOut(realOut)
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

// availability, per review 526, so the real catalogue/executor mismatches are
// representable as known constructs rather than as unknowns.
// Case-insensitive on BOTH sides. functionsFLD keys are lowercase
// (`previousage`) while virtualCommands() keys are camelCase (`waitRandom`),
// and the executor symbols are camelCase for both. Lowercasing only one side
// silently reported all 69 virtual commands as executor-only.
Set<String> declFuncSet = declaredFuncs.collect { it.toLowerCase() } as Set
Set<String> declVcmdSet = declaredVcmds.collect { it.toLowerCase() } as Set
Set<String> implFuncSet = functions.collect { it.substring('func_'.length()).toLowerCase() } as Set
Set<String> implVcmdSet = vcmds.collect { it.substring('vcmd_'.length()).toLowerCase() } as Set
boolean isDeclared(String bare, Set<String> declaredLower) { declaredLower.contains(bare.toLowerCase()) }
String availability(String bare, boolean implemented, Set<String> declaredLower) {
    boolean d = isDeclared(bare, declaredLower)
    if (implemented && d) return 'current'
    if (implemented && !d) return 'executor-only'
    if (!implemented && d) return 'catalog-only'
    return 'unknown'
}

// ------------------------------------------------- claim-scoped region hashes

// Evidence hashes must cover exactly the code a claim rests on. The earlier
// version hashed fixed character windows (2k/12k/20k/40k), which could truncate
// a method, swallow unrelated following code, or - worst - hash the SHA-256 of
// an empty string when an anchor was missing, silently producing a stable hash
// for no evidence at all (review 539).
//
// Every region below is brace- or bracket-matched from a named anchor, and a
// missing or unbalanced boundary FAILS rather than degrading.

/** Balanced region from `anchor`, matching on `open`/`close`, skipping strings and comments. */
String balancedRegion(String src, String anchor, char open, char close, List<String> f, String label) {
    int start = src.indexOf(anchor)
    if (start < 0) { fail(f, "region ${label}: anchor not found"); return null }
    int i = src.indexOf(open as String, start)
    if (i < 0) { fail(f, "region ${label}: no opening ${open} after anchor"); return null }
    int depth = 0
    for (int p = i; p < src.length(); p++) {
        char ch = src.charAt(p)
        if (ch == '/' && p + 1 < src.length()) {
            char n = src.charAt(p + 1)
            if (n == '/') { int e = src.indexOf('\n', p); if (e < 0) break; p = e; continue }
            if (n == '*') { int e = src.indexOf('*/', p); if (e < 0) break; p = e + 1; continue }
        }
        if (ch == '"' || ch == '\'') {
            char q = ch; p++
            while (p < src.length() && src.charAt(p) != q) { if (src.charAt(p) == ('\\' as char)) p++; p++ }
            continue
        }
        if (ch == open) depth++
        else if (ch == close) { depth--; if (depth == 0) return src.substring(start, p + 1) }
    }
    fail(f, "region ${label}: unbalanced ${open}${close} - boundary ambiguous")
    return null
}

/**
 * Deterministic normalized collection of every definition matching a prefix.
 * Used for the func_/vcmd_ populations, which have no single enclosing region
 * but are emitted as constructs and therefore need evidence of their own.
 * Sorted by name so the hash cannot depend on source ordering.
 */
String definitionCorpus(String src, String prefix, List<String> f, String label) {
    String rx = '(?m)^\\s*(?:private\\s+|static\\s+|public\\s+)*[A-Za-z<>,\\[\\]\\s]*\\b(' +
                java.util.regex.Pattern.quote(prefix) + '[a-zA-Z0-9_]+)\\s*\\('
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(rx).matcher(src)
    Map<String, String> bodies = new TreeMap<>()
    while (m.find()) {
        String name = m.group(1)
        String body = balancedRegion(src.substring(m.start()), name, '{' as char, '}' as char, f,
                                     "${label}:${name}")
        if (body == null) return null
        bodies[name] = body
    }
    if (bodies.isEmpty()) { fail(f, "region ${label}: no definitions matched ${prefix}"); return null }
    // Deterministic length-prefixed textual encoding. An earlier version used
    // literal NUL and SOH separators, which made this file read as binary to
    // grep and git and recreated an encoding class of problem this project has
    // hit before. Length prefixes are unambiguous without needing any
    // character that cannot appear in the content.
    return bodies.collect { k, v -> "${k.length()}:${k}${v.length()}:${v}" }.join('')
}

String sha256(String text) {
    java.security.MessageDigest.getInstance('SHA-256')
        .digest(text.getBytes('UTF-8')).collect { String.format('%02x', it & 0xFF) }.join()
}

Map<String, String> regionSource = [:]
regionSource['executor.statement-dispatch'] = balancedRegion(piston, 'private Boolean executeStatement(', '{' as char, '}' as char, failures, 'executor.statement-dispatch')
regionSource['executor.evaluate-operand']   = balancedRegion(piston, 'private evaluateOperand(', '{' as char, '}' as char, failures, 'executor.evaluate-operand')
regionSource['executor.subscribe-all']      = balancedRegion(piston, 'private void subscribeAll(', '{' as char, '}' as char, failures, 'executor.subscribe-all')
regionSource['executor.evaluate-expression']= balancedRegion(piston, 'private Map evaluateExpression(', '{' as char, '}' as char, failures, 'executor.evaluate-expression')
regionSource['executor.execute-task']       = balancedRegion(piston, 'private Boolean executeTask(', '{' as char, '}' as char, failures, 'executor.execute-task')
regionSource['executor.expand-device-list'] = balancedRegion(piston, 'private List<String> expandDeviceList(', '{' as char, '}' as char, failures, 'executor.expand-device-list')
regionSource['catalogue.virtual-commands']  = balancedRegion(app, 'private static Map<String,Map> virtualCommands(){', '{' as char, '}' as char, failures, 'catalogue.virtual-commands')
regionSource['catalogue.functions-fld']     = balancedRegion(app, '@Field final Map<String,Map> functionsFLD=[', '[' as char, ']' as char, failures, 'catalogue.functions-fld')
regionSource['executor.functions']          = definitionCorpus(piston, 'func_', failures, 'executor.functions')
regionSource['executor.virtual-commands']   = definitionCorpus(piston, 'vcmd_', failures, 'executor.virtual-commands')

// An empty or null region can never be hashed into the registry: that is how a
// missing anchor previously became a stable hash of nothing.
Map<String, String> REGION_HASHES = [:]
regionSource.each { String k, String v ->
    if (v == null || v.trim().isEmpty()) fail(failures, "region ${k}: empty, refusing to hash")
    else REGION_HASHES[k] = sha256(v)
}

// Region hashing runs in the emit path, AFTER the main gate check, so failures
// recorded above would otherwise be written to a list nobody reads. A missing
// anchor produced "All gates passed" and a registry with one fewer hash. Caught
// by the negative test review 539 asked for, which is the entire argument for
// having written it.
if (failures) {
    System.setOut(realOut)
    println 'GATE FAILURES (evidence regions):'
    failures.each { println "  - ${it}" }
    println ''
    println 'No registry emitted. Evidence boundaries must be exact.'
    System.exit 1
}

// One helper so every population emits the same schema. sourceRef may be null
// at L2 but the field exists, because this is the static runtime shape being
// frozen and a consumer should not have to guess whether a key is absent or
// merely unset.
String NS = 'wc.'
String REGISTRY_VERSION = '1'
List<String> emittedIds = []
Map<String, List> emittedRefs = [:]
// A closure, not a method: a Groovy script's local variables are not visible
// inside a separately-declared method, so entry() as a method could not see
// emittedIds, NS or REGISTRY_VERSION. Same shape as the Hubitat sandbox
// scoping trap this project hit in v2.2.8, in a different guise.
def entry
entry = { String id, Map f ->
    emittedIds << id
    if (f.refs) emittedRefs[id] = (f.refs as List)
    String canon = f.canonicalTarget ? "'${f.canonicalTarget}'" : 'null'
    String extra = ''
    // Structured lists rather than comma-joined strings: a CSV pushes parsing
    // ambiguity into the walker for no benefit, and a reference that is not a
    // resolvable key is not evidence.
    if (f.refs) extra += ", sourceRef:[" + (f.refs as List).collect { "'${it}'" }.join(',') + "]"
    else extra += ", sourceRef:" + (f.sourceRef ? "'${f.sourceRef}'" : 'null')
    if (f.consumedByList) extra += ", consumedBy:[" + (f.consumedByList as List).collect { "'${it}'" }.join(',') + "]"
    if (f.dispatchSiteList) extra += ", dispatchSite:[" + (f.dispatchSiteList as List).collect { "'${it}'" }.join(',') + "]"
    // Shared-branch evidence per site. One member can sit in a three-member
    // branch at one site and a twenty-member branch at another, and a single
    // string cannot represent both.
    if (f.sharedBySite) extra += ", sharedBranchBySite:[" +
        (f.sharedBySite as Map).collect { k, v -> "'${k}':'${v}'" }.join(',') + "]"
    if (f.status) extra += ", status:'${f.status}'"
    if (f.staticallyResolvable != null) extra += ", staticallyResolvable:${f.staticallyResolvable}"
    return "    '${id}': [kind:'${f.kind}', name:'${f.name}', declared:${f.declared}, " +
           "implemented:${f.implemented}, canonicalTarget:${canon}, availability:'${f.availability}', " +
           "level:'${f.level}', sinceRegistryVersion:'${REGISTRY_VERSION}'${extra}],\n"
}

// Each frozen site resolves to real evidence: source file, dispatch anchor and
// the region-hash key covering it. Without this a construct's sourceRef named a
// site that was not a key in regionHashes, so the chain stopped short of
// anything actually hashed.
Map<String, String> METHOD_REGION = [
    'executeStatement'   : 'executor.statement-dispatch',
    'evaluateOperand'    : 'executor.evaluate-operand',
    'subscribeAll'       : 'executor.subscribe-all',
    'evaluateExpression' : 'executor.evaluate-expression',
    'executeTask'        : 'executor.execute-task',
]
Map<String, Map> SITE_PROVENANCE = [:]
FROZEN_SITES.each { String sid, Map site ->
    String rk = METHOD_REGION[site.method as String]
    if (rk == null) fail(failures, "site ${sid}: method '${site.method}' has no evidence region")
    SITE_PROVENANCE[sid] = [file: PISTON_PATH, method: site.method, subject: site.subject,
                            ordinal: site.ordinal, line: site.line, regionHash: rk]
}

// ------------------------------------------------- source evidence vs identity

// A source dispatch membership is NOT automatically a saved construct identity
// (review 539). The thirteen sites are evidence about which runtime paths consume
// a serialized value; the saved document contains one node regardless of how
// many passes inspect it. A saved {t:'p'} operand is ONE persisted operand that
// evaluateOperand evaluates and subscribeAll inspects - not two constructs.
//
// This table is the reviewed normalization. It is deliberately many-to-one, and
// a distinct canonical ID is retained ONLY where the SAVED PARENT POSITION
// changes meaning (the event matcher inside an `on` statement), never merely
// because a different runtime method reads the same node.
//
//   (dispatchSite, serializedMember) -> canonical saved-construct ID
//
Map<String, Map> SITE_NORMALIZATION = [
  // Base operand types. The evaluate site defines identity; the subscribe site
  // is the same saved nodes seen by a different pass.
  'operand.evaluate.type':            [prefix: 'operand.',              consumer: 'evaluateOperand'],
  'operand.subscribe.type':           [prefix: 'operand.',              consumer: 'subscribeAll'],
  // Retained as distinct: an operand inside an `on` statement's event matcher
  // occupies a different saved parent position from an ordinary value operand.
  'operand.event-match.type':         [prefix: 'operand.event-match.',  consumer: 'executeStatement.on'],
  // Virtual-device names are saved as {t:'v', v:<name>}. Evaluate defines the
  // population; subscribe is a strict subset seen by the subscription pass.
  'virtual-device.evaluate.name':     [prefix: 'virtual-device.',       consumer: 'evaluateOperand'],
  'virtual-device.subscribe.name':    [prefix: 'virtual-device.',       consumer: 'subscribeAll'],
  // Statement identities already exist as wc.statement.*; these two sites add
  // consumer provenance only and create nothing new.
  'statement.subscribe.type':         [prefix: 'statement.',            consumer: 'subscribeAll'],
  'statement.subscribe.timer-type':   [prefix: 'statement.',            consumer: 'subscribeAll.timer'],
  // Saved attributes in their own right, each a distinct document position.
  'expression.item.type':             [prefix: 'expression.item.',      consumer: 'evaluateExpression'],
  'expression.evaluate.result-type':  [prefix: 'expression.result-type.', consumer: 'evaluateExpression'],
  'preset.evaluate.name':             [prefix: 'preset.',               consumer: 'evaluateOperand'],
  'preset.evaluate.value-type':       [prefix: 'preset.value-type.',    consumer: 'evaluateOperand'],
  'constant.evaluate.value-type':     [prefix: 'constant.value-type.',  consumer: 'evaluateOperand'],
  'task.variable.value-type':         [prefix: 'task.value-type.',      consumer: 'executeTask'],
]

// Build canonical identities from the frozen sites. Membership stays gated at
// the site level; identity is what the walker will look up.
// Identity ownership is explicit. A consumer-only site may only contribute
// provenance to an identity that its owner already defines; it may never mint
// one. Without this, a subscribe-only member absent from the evaluate site
// silently became a new construct - an orphan promoted to an identity.
//
// 540 claimed this check existed. It did not: the subset relationship had been
// verified by hand once and then described as if enforced. It is enforced now.
// Only CONSUMER-ONLY sites need subset enforcement. Most sites are the sole
// definer of their own canonical prefix and therefore own it by definition;
// requiring them to find an external owner made every one of their members an
// orphan on the first attempt. These four read nodes another site defines.
Map<String, String> CONSUMER_ONLY_SITES = [
    'operand.subscribe.type'        : 'operand.evaluate.type',
    'virtual-device.subscribe.name' : 'virtual-device.evaluate.name',
    'statement.subscribe.type'      : '(statement dispatch forms)',
    'statement.subscribe.timer-type': '(statement dispatch forms)',
]
List<String> ownerSites = ['operand.evaluate.type', 'virtual-device.evaluate.name']

Map<String, Map> canonical = [:]
int siteMemberships = 0

// Owner sites first, so ownership cannot depend on map iteration order.
// Owners first, then the rest. Written as an explicit concatenation because
// `owners + all - owners` evaluates left to right and removes the owners,
// which silently made every member an orphan.
List<String> orderedSites = ownerSites + ((FROZEN_SITES.keySet() as List) - ownerSites)
// Statements are owned by the dispatch population, not by a site.
Set<String> statementIds = statements.collect { NS + 'statement.' + it } as Set

// An owner or consumer site naming something absent from FROZEN_SITES is a
// configuration error, and previously threw a NullPointerException rather than
// reporting it.
(ownerSites + (CONSUMER_ONLY_SITES.keySet() as List)).unique().each { String sid ->
    if (!FROZEN_SITES.containsKey(sid) && !sid.startsWith('(')) {
        fail(failures, "configured site '${sid}' is not present in FROZEN_SITES")
    }
}

orderedSites.each { String siteId ->
    Map site = FROZEN_SITES[siteId]
    if (site == null) { fail(failures, "site '${siteId}' has no frozen definition"); return }
    Map norm = SITE_NORMALIZATION[siteId]
    if (norm == null) { fail(failures, "site ${siteId} has no reviewed normalization"); return }
    Map<String, String> groupOf = [:]
    (site.shared as List).each { List g -> (g as List).each { groupOf[it as String] = (g as List).join('+') } }
    (site.members as List).each { String m ->
        siteMemberships++
        String label = (m == '') ? 'empty' : m
        String id = NS + norm.prefix + label
        Map c = canonical[id]
        boolean ownedElsewhere = statementIds.contains(id)
        if (c == null && CONSUMER_ONLY_SITES.containsKey(siteId) && !ownedElsewhere) {
            // A consumer-only site named something its owner never defines.
            // Promoting it would turn an orphan into a construct identity.
            fail(failures, "orphan member: consumer site '${siteId}' member '${m}' -> '${id}' " +
                           "is not defined by its owner (${CONSUMER_ONLY_SITES[siteId]})")
            return
        }
        if (c == null) {
            canonical[id] = [kind: 'operand', name: label, consumedBy: [norm.consumer] as TreeSet,
                             sites: [siteId] as TreeSet, sharedBySite: new TreeMap<String, String>()]
        } else {
            (c.consumedBy as Set) << norm.consumer
            (c.sites as Set) << siteId
        }
        // Shared-branch evidence is per site. One canonical member can sit in a
        // three-member branch at one site and a twenty-member branch at another,
        // and a single string cannot represent both. No site wins by being
        // visited first.
        if (groupOf[m] && canonical[id] != null) (canonical[id].sharedBySite as Map)[siteId] = groupOf[m]
    }
}

// Statement consumer provenance folds into the existing statement entries
// rather than creating wc.statement.subscribe.type.* duplicates.
Map<String, Set<String>> statementConsumers = [:]
Map<String, Set<String>> statementRefs = [:]
statements.each { String st ->
    statementConsumers[st] = ['executeStatement'] as TreeSet
    statementRefs[st] = ['executor.statement-dispatch'] as TreeSet
}
canonical.keySet().findAll { it.startsWith(NS + 'statement.') }.each { String id ->
    String st = id.substring((NS + 'statement.').length())
    if (statementConsumers.containsKey(st)) {
        statementConsumers[st].addAll(canonical[id].consumedBy as Set)
        // Carry the site references too. Folding kept consumedBy and dropped
        // the evidence supporting it, so a subscription consumer claim had no
        // resolvable reference behind it.
        statementRefs[st].addAll(canonical[id].sites as Set)
    } else {
        fail(failures, "statement consumer site references unknown statement '${st}'")
    }
    canonical.remove(id)
}

StringBuilder sb = new StringBuilder()
sb << "// GENERATED by tools/webcore-investigation/generate-construct-registry.groovy v${GENERATOR_VERSION}\n"
sb << "// Source: ${remote} @ ${branch} ${sha}\n"
sb << "// Reviewed as a diff before use. Not self-authorizing.\n"
sb << "@Field static final Map WEBCORE_CONSTRUCT_REGISTRY = [\n"
sb << "  provenance: [repo: '${remote}', branch: '${branch}', commit: '${sha}',\n"
sb << "               generator: '${GENERATOR_VERSION}', registryVersion: '${REGISTRY_VERSION}',\n"
sb << "               sourcePaths: ['${PISTON_PATH}', '${APP_PATH}'],\n"
sb << "               sites: [\n"
SITE_PROVENANCE.sort().each { String sid, Map pv ->
    sb << "                 '${sid}': [file:'${pv.file}', method:'${pv.method}', " +
          "subject:'${pv.subject}', ordinal:${pv.ordinal}, line:${pv.line}, " +
          "regionHash:'${pv.regionHash}'],\n"
}
sb << "               ],\n"
sb << "               regionHashes: [\n"
REGION_HASHES.sort().each { String k, String v -> sb << "                 '${k}': '${v}',\n" }
sb << "               ]],\n"
sb << "  constructs: [\n"

// statements
statements.each { String st ->
    sb << entry("${NS}statement.${st}", [kind: 'statement', name: st, declared: true, implemented: true,
        canonicalTarget: null, availability: 'current', level: 'L2',
        refs: (statementRefs[st] as List),
        consumedByList: (statementConsumers[st] as List)])
}
// execution policy flags - part of the agreed population and previously omitted
['tep', 'tsp', 'tcp'].each { String pf ->
    sb << entry("${NS}policy.${pf}", [kind: 'policy', name: pf, declared: false, implemented: true,
        canonicalTarget: null, availability: 'current', level: 'L2',
        refs: ['executor.statement-dispatch']])
}
// functions
functions.sort().each { String f ->
    String bare = f.substring('func_'.length())
    String canon = REVIEWED_ALIASES[bare.toLowerCase()]
    boolean decl = isDeclared(bare, declFuncSet)
    // Evidence must contain the entry: an executor-only function cannot cite
    // the catalogue it is absent from (review 539).
    sb << entry("${NS}function.${bare}", [kind: 'function', name: bare,
        declared: decl, implemented: true,
        canonicalTarget: canon ? "${NS}function.${canon}" : null,
        availability: availability(bare, true, declFuncSet), level: 'L2',
        refs: decl ? ['executor.functions', 'catalogue.functions-fld'] : ['executor.functions']])
}
// virtual commands
vcmds.sort().each { String v ->
    String bare = v.substring('vcmd_'.length())
    boolean vdecl = isDeclared(bare, declVcmdSet)
    sb << entry("${NS}vcmd.${bare}", [kind: 'vcmd', name: bare,
        declared: vdecl, implemented: true,
        canonicalTarget: null, availability: availability(bare, true, declVcmdSet), level: 'L2',
        refs: vdecl ? ['executor.virtual-commands', 'catalogue.virtual-commands'] : ['executor.virtual-commands']])
}
// catalogue-only reconciliation, now case-normalized on both sides like the
// availability lookup already was
declaredFuncs.findAll { !implFuncSet.contains((it as String).toLowerCase()) }.each { String d ->
    sb << entry("${NS}function.${d}", [kind: 'function', name: d, declared: true, implemented: false,
        canonicalTarget: null, availability: 'catalog-only', level: 'L2',
        refs: ['catalogue.functions-fld']])
}
declaredVcmds.findAll { !implVcmdSet.contains((it as String).toLowerCase()) }.each { String d ->
    sb << entry("${NS}vcmd.${d}", [kind: 'vcmd', name: d, declared: true, implemented: false,
        canonicalTarget: null, availability: 'catalog-only', level: 'L2',
        refs: ['catalogue.virtual-commands']])
}
// SmartThings-only, not derivable: absent from this source entirely
sb << entry("${NS}vcmd.executeRoutine", [kind: 'vcmd', name: 'executeRoutine', declared: false,
    implemented: false, canonicalTarget: null, availability: 'smartthings-only', level: 'L2',
    refs: []])

// Dispatch-site members, context-qualified. Previously validated only and never
// emitted, which would have left the Increment 2 walker unable to resolve
// wc.operand.evaluate.type.d from the registry it is supposed to consult.
canonical.sort().each { String id, Map c ->
    // Every site reference resolves through SITE_PROVENANCE to a region hash.
    sb << entry(id, [kind: 'operand', name: c.name,
        declared: false, implemented: true, canonicalTarget: null, availability: 'current',
        level: 'L2',
        refs: (c.sites as List),
        dispatchSiteList: (c.sites as List),
        consumedByList: (c.consumedBy as List),
        sharedBySite: (c.sharedBySite as Map)])
}

// Device-selector grammar. Value-shape metadata, deliberately kept distinct
// from switch membership: these are not discriminator members and carry no
// numeric gate.
// Structural forms, emitted through the same explicit path as the selector
// grammar so a saved shape cannot enter the registry without a reviewed entry.
STRUCTURAL_FORMS.each { String id, Map g ->
    sb << entry("${NS}${id}", [kind: 'structural', name: id.tokenize('.').last(),
        declared: false, implemented: true, canonicalTarget: null, availability: 'current',
        level: 'L2', refs: (g.refs as List)])
}

DEVICE_SELECTOR_GRAMMAR.each { String id, Map g ->
    sb << entry("${NS}${id}", [kind: 'deviceSelector', name: id.tokenize('.').last(),
        declared: false, implemented: g.status == 'active',
        canonicalTarget: null,
        availability: g.status == 'active' ? 'current' : 'unreachable',
        level: 'L2', refs: ['executor.expand-device-list'],
        status: g.status, staticallyResolvable: g.staticallyResolvable])
}
sb << "  ]\n]\n"

// Post-assembly validation across ALL populations, not per-population. Two
// families could each be internally unique and still collide once combined.
List<String> dupes = emittedIds.countBy { it }.findAll { it.value > 1 }.keySet() as List
if (dupes) { System.err.println "GATE: duplicate registry ids after assembly ${dupes}"; System.exit 1 }

// Every reference must resolve: either directly to a region-hash key, or to a
// frozen site that SITE_PROVENANCE maps to one. A reference that resolves to
// nothing is not evidence, and the chain previously stopped at site names that
// were not keys in regionHashes at all.
Set<String> resolvable = (REGION_HASHES.keySet() as Set) + (SITE_PROVENANCE.keySet() as Set)
List<String> dangling = []
emittedRefs.each { String id, List refs ->
    refs.each { String r -> if (!resolvable.contains(r)) dangling << "${id} -> ${r}" }
}
if (dangling) {
    System.setOut(realOut)
    System.err.println "GATE: dangling evidence references: ${dangling.take(5)}"
    System.exit 1
}
// Each site reference must itself terminate in a region hash.
SITE_PROVENANCE.each { String sid, Map pv ->
    if (!REGION_HASHES.containsKey(pv.regionHash)) {
        System.setOut(realOut)
        System.err.println "GATE: site ${sid} names region ${pv.regionHash}, which was not hashed"
        System.exit 1
    }
}

// Alias targets must exist, or canonicalTarget points at nothing.
Set<String> idSet = emittedIds as Set
REVIEWED_ALIASES.each { String from, String to ->
    String target = "${NS}function.${to}"
    if (!idSet.contains(target)) {
        System.err.println "GATE: alias ${from} -> ${to} but ${target} is not in the registry"
        System.exit 1
    }
}

// Single pre-output gate. Failures are recorded by several phases and were
// previously read only by whichever gate happened to follow that phase, so a
// later phase's failures went unread. Twice: region-boundary failures, then
// orphan failures. Nothing is emitted while any failure stands, wherever it
// was recorded.
if (failures) {
    System.setOut(realOut)
    println 'GATE FAILURES:'
    failures.unique().each { println "  - ${it}" }
    println ''
    println 'No registry emitted.'
    System.exit 1
}

if (candidateOnly) { System.setOut(realOut); print sb.toString(); System.exit 0 }

println ''
println "Emitted ${emittedIds.size()} constructs:"
printf('  statements %d, policy %d, functions %d, vcmds %d, canonical operand-family %d, selectors %d, structural %d%n',
    statements.size(), 3,
    emittedIds.count { it.startsWith(NS + 'function.') },
    emittedIds.count { it.startsWith(NS + 'vcmd.') },
    canonical.size(),
    DEVICE_SELECTOR_GRAMMAR.size(),
    STRUCTURAL_FORMS.size())
// The two numbers review 539 asked to be reported separately, because conflating
// them is precisely the error this rework corrects.
printf('  source-site memberships %d  ->  unique canonical saved constructs %d%n',
    siteMemberships, canonical.size())
printf('  %d memberships merged as consumer provenance rather than new identities%n',
    siteMemberships - canonical.size())
println "  aliases with canonicalTarget: ${REVIEWED_ALIASES.size()}"
println "  region hashes: ${REGION_HASHES.size()}"
println ''
println sb.toString()
