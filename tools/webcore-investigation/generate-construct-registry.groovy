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

// ----------------------------------------------------- dispatch site freeze

// Thirteen dispatch sites, frozen 2026-09-10 after review in queue 532/533/534
// and approval in 535. EXACT MEMBER SETS ARE THE PRIMARY GATE, counts are a
// secondary sanity check: a count-only gate cannot see a substitution where one
// member disappears and a phantom appears in the same run.
//
// sharedBranch records members that fall through to a common body. It is
// PROVENANCE ONLY and never collapses members (Codex 533): sunriseTime and
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

// Device-selector grammar. NOT a numeric vocabulary gate (Codex 533/535):
// selectors are not switch-dispatched, so there is no discriminator population
// to count. Source basis is expandDeviceList (line 9435), which serves both
// physical operands and action targets.
Map<String, Map> DEVICE_SELECTOR_GRAMMAR = [
  'device-selector.direct-identifier': [
    status: 'active', staticallyResolvable: true,
    recognition: 'isWcDev(entry): length 34, colon-delimited. Automation Map additionally requires ' +
                 '32 lowercase hex, which is deliberately STRICTER than the source. A 34-character ' +
                 'colon-delimited non-hex value stays unknown-device-selector rather than being ' +
                 'resolved (Codex 535).'],
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
        // an absence to shrug at (Codex 531).
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
