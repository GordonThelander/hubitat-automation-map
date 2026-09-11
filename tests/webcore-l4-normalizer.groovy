#!/usr/bin/env groovy
//
// The L4 normaliser, executed from the app source, against the hand-authored traces committed before
// it existed. Also covers every committed fixture, the save/reload lineage, privacy, runtime withdrawal
// of a claim, the assessment, one mutation per named misreading, and the generated evidence projection.
// Run with: groovy tests/webcore-l4-normalizer.groovy

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('..').canonicalFile

List<Boolean> results = []
def check = { boolean cond, String label ->
    println "${cond ? 'PASS' : 'FAIL'}  ${label}"
    results << cond
}
def copy = { Object o -> new JsonSlurper().parseText(JsonOutput.toJson(o)) }

String source = new File(repoRoot, 'apps/automation_map.groovy').getText('UTF-8').replace('\r\n', '\n')
def between = { String from, String to ->
    int a = source.indexOf(from)
    int b = source.indexOf(to, a)
    assert a >= 0 && b > a: "block not found: ${from}"
    source.substring(a + from.length(), b)
}
String evidenceBlock = between('// --- webcore semantic evidence: begin ---', '// --- webcore semantic evidence: end ---')
String normalizerBlock = between('// --- webcore semantic normalizer: begin ---', '// --- webcore semantic normalizer: end ---')
def load = { String block ->
    new GroovyClassLoader(this.class.classLoader).parseClass("class SemanticUnderTest {\n" + evidenceBlock + "\n" + block + "\n}").newInstance()
}
def normalizer = load(normalizerBlock)
Map evidence = normalizer.webcoreSemanticEvidence() as Map

Map manifest = new GroovyShell().evaluate(new File(repoRoot, 'tools/webcore-investigation/evidence/semantic-l4.groovy').getText('UTF-8')) as Map
Map l3 = new GroovyShell().evaluate(new File(repoRoot, 'tools/webcore-investigation/evidence/statement-l3.groovy').getText('UTF-8')) as Map
Map fixtureManifest = new JsonSlurper().parse(new File(repoRoot, 'tests/fixtures/webcore-l3/manifest.json')) as Map
Map fixtureByName = (fixtureManifest.fixtures as List).collectEntries { Map f -> [((f.file as String) - '.json'): f] }
File l3Dir = new File(repoRoot, 'tests/fixtures/webcore-l3')
Map traces = [:]
new File(repoRoot, manifest.traceDirectory as String).listFiles().findAll { it.name.endsWith('.json') }.sort { it.name }.each { File f ->
    traces[f.name - '.json'] = new JsonSlurper().parse(f)
}
def doc = { String name -> new JsonSlurper().parseText(new File(l3Dir, name + '.json').getText('UTF-8')) }
def model = { Object n, Object document, Map ev = evidence -> n.webcoreSemanticModel(document, ev, 100) as Map }
def strip = { Object entry ->
    Map e = new LinkedHashMap(entry as Map)
    e.remove('cite')
    e.claims = ((e.claims ?: []) as List).collect { "${it}" }.sort()
    e.gaps = ((e.gaps ?: []) as List).collect { "${it}" }.sort()
    e
}
def stripAll = { Map occurrences -> occurrences.collectEntries { k, v -> [("${k}".toString()): strip(v)] } }
def traceMismatches = { Object n ->
    List out = []
    traces.each { String name, Object t ->
        Map m = model(n, doc(name))
        if (m.truncated) out << "${name} truncated".toString()
        Map expected = stripAll((t as Map).occurrences as Map)
        Map actual = stripAll(m.occurrences as Map)
        (expected.keySet() + actual.keySet()).each { Object p ->
            if (expected[p] != actual[p]) out << "${name} ${p}: expected ${expected[p]} got ${actual[p]}".toString()
        }
    }
    out
}

// ---- the traces committed before the normaliser --------------------------------------

List mismatches = traceMismatches(normalizer)
check(mismatches.isEmpty(), "the normaliser reproduces every hand-authored trace exactly ${mismatches.take(2)}")

Map saveKindOf = (l3.captureLineage as Map).collectEntries { k, v -> [(v): k] }
List lineageDiffs = (traces.keySet() as List).findAll { Object name ->
    Map f = fixtureByName[name] as Map
    String save = name.substring(0, name.length() - (f.capture as String).length()) + saveKindOf[f.capture]
    stripAll(model(normalizer, doc(name)).occurrences as Map) != stripAll(model(normalizer, doc(save)).occurrences as Map)
}
check(lineageDiffs.isEmpty(), "each traced fixture and its save in the lineage give the same semantic model ${lineageDiffs}")

List countDrift = []
fixtureByName.each { String name, Object f ->
    Map m = model(normalizer, doc(name))
    int expected = (((f as Map).statements ?: [:]) as Map).values().sum(0) as int
    if (m.truncated || (m.occurrences as Map).size() != expected) countDrift << "${name} ${(m.occurrences as Map).size()} of ${expected}".toString()
}
check(countDrift.isEmpty(), "every committed fixture is normalized without truncation, one entry per statement occurrence ${countDrift}")

// ---- privacy -------------------------------------------------------------------------

Set closed = (['or', 'decision', 'sequential-block', 'then', 'else', 'group', 'opaque-condition', 'opaque-followed-by-group',
               'multi-way-decision', 'switch-scoped-control-transfer', 'piston-terminate', 'i', 'e',
               'pre-condition-loop', 'post-condition-loop', 'step-iteration', 'device-iteration', 'loop-scoped-control-transfer',
               'own-timer-only', 'any-event-match', 'targeted-tasks', 'static', 'dynamic'] +
              (evidence.claims as Map).keySet() + (evidence.gaps as Map).keySet()) as Set
def strings
strings = { Object o, List acc ->
    if (o instanceof Map) (o as Map).values().each { strings(it, acc) }
    else if (o instanceof List) (o as List).each { strings(it, acc) }
    else if (o instanceof String) acc << o
    acc
}
Map canaryModel = model(normalizer, [s: [[t: 'CANARYtype', a: 'CANARYasync', tcp: 'CANARYtcp', tep: 'CANARYtep',
    c: [[t: 'group', o: 'CANARYop', n: 'CANARYneg', c: [[t: 'condition', ct: 'CANARYct', lo: [t: 'v', v: 'CANARYvalue']]]]]],
    [t: 'if', a: '0', tcp: 'c', o: 'CANARYop', c: [[t: 'condition', ct: 't', co: 'CANARYcomparison', ro: [t: 'c', c: 'CANARYliteral']]]]]])
List leaked = []
(fixtureByName.keySet().collect { model(normalizer, doc(it)) } + [canaryModel]).each { Map m ->
    (m.occurrences as Map).each { k, v ->
        if (!("${k}" ==~ /\$(\.(s|e|ei\[\d+\]\.s|cs\[\d+\]\.s|c\[\d+\](\.c\[\d+\])*\.(ts|fs))\[\d+\])+/)) leaked << "path ${k}".toString()
        strings(v, []).each { String s ->
            if (!(closed.contains(s) || s ==~ /wc\.statement\.[a-z]+/ || s ==~ /else-if\[\d+\]/)) leaked << s
        }
    }
}
check(leaked.isEmpty() && !JsonOutput.toJson(canaryModel).contains('CANARY'),
    "every string in every model is a closed token, construct id or structural path, and no saved value appears ${leaked.unique().take(4)}")

// ---- runtime withdrawal and assessment ------------------------------------------------

Map withdrawnEvidence = copy(evidence) as Map
(withdrawnEvidence.claims as Map)['statement.if.branch-order.v1'] = false
Map withdrawn = model(normalizer, doc('l3-01-conditional.edit-round-trip'), withdrawnEvidence).occurrences as Map
check(!((withdrawn['$.s[0]'] as Map).claims as List).contains('statement.if.branch-order.v1') &&
      ((withdrawn['$.s[0]'] as Map).gaps as List).contains('claim.statement.if.branch-order.v1.not-promoted') &&
      (withdrawn['$.s[0].s[0]'] as Map).gaps == ['statement.action.task-order-unresolved'],
    'a claim the evidence withdraws becomes a gap on the occurrences that took it, and only those')

Map assessed = normalizer.webcoreSemanticAssessment(model(normalizer, doc('l3-01-conditional.edit-round-trip')), evidence) as Map
Map gapCount = (assessed.gaps as List).collectEntries { Map g -> [(g.id): g.occurrences] }
check(assessed.status == 'complete' && assessed.occurrences == 7 && assessed.explained == 0 && assessed.explainable == false &&
      gapCount['statement.action.task-order-unresolved'] == 5 && gapCount['condition.leaf-opaque'] == 2 &&
      (assessed.gaps as List).every { Map g -> g.reason == (evidence.gaps as Map)[g.id] },
    "the assessment counts occurrences, explained occurrences and closed gaps with their fixed reasons (${assessed.occurrences}, ${gapCount})")
Map shallow = normalizer.webcoreSemanticModel(doc('l3-01-conditional.edit-round-trip'), evidence, 1) as Map
check(shallow.truncated == true && (normalizer.webcoreSemanticAssessment(shallow, evidence) as Map).status == 'not-evaluated',
    'a model cut short by the depth bound is not evaluated rather than partial')
Map provenDo = model(normalizer, [s: [[t: 'do', a: '0', tcp: 'c', s: []]]])
check((normalizer.webcoreSemanticAssessment(provenDo, evidence) as Map).explainable == true,
    'a piston whose every statement occurrence is proven is semantically explainable')

// ---- one mutation per named misreading --------------------------------------------------

def mutant = { String from, String to ->
    assert normalizerBlock.contains(from): "mutation anchor not found: ${from}"
    load(normalizerBlock.replace(from, to))
}
String elseIfLine = "branches << [name: 'else-if[' + i + ']', condition: webcoreSemanticConditionList(elseIfs[i] as Map, path + '.ei[' + i + ']', depth, acc, claims, gaps)]"
Map mutations = [
    'else-if branches are unordered or evaluated together': [elseIfLine, "branches.add(1, [name: 'else-if[' + i + ']', condition: webcoreSemanticConditionList(elseIfs[i] as Map, path + '.ei[' + i + ']', depth, acc, claims, gaps)])"],
    'a negated condition list is read as not negated': ['negated: owner.n == true', 'negated: false'],
    'an unproven operator such as and is read as or': ["if (owner.o == 'or') {", "if (owner.o != 'followed by') {"],
    'a followed-by group is read as an ordinary boolean and group': ["if (g.o == 'followed by') {", 'if (false) {'],
    'do is read as a loop or as transparent to statement level': ['entry.lowersStatementLevel = true', 'entry.lowersStatementLevel = false'],
    'a saved absent tcp, which is never cancel, is read as the default': ["if (node.tcp != 'c') {", "if (node.containsKey('tcp') && node.tcp != 'c') {"],
    'if has no side effect beyond branch selection': ["gaps << 'statement.if.automatic-piston-state-unresolved'", ''],
    'a saved ct decides a condition leaf role': ["(out.children as List) << [kind: 'opaque-condition']",
        "(out.children as List) << ((k instanceof Map && (k as Map).ct) ? [kind: 'condition', role: (k as Map).ct] : [kind: 'opaque-condition'])"],
    'every case is tried regardless of ctp, so fall-through and auto-break read the same':
        ["entry.ctp = (node.ctp == 'e') ? 'e' : 'i'", "entry.ctp = 'i'"],
    'the default section always runs after the case list, whether or not a case matched or broke':
        ["if (node.e instanceof List && (node.e as List)) claims << 'statement.switch.default.v1'", ''],
    'a break inside a switch case terminates the whole piston, as exit does':
        ["claims << 'statement.break.switch-scope.v1'", "claims << 'statement.exit.terminate-piston.v1'"],
    'exit only exits its immediate containing statement, the way break does':
        ["claims << 'statement.exit.terminate-piston.v1'", "claims << 'statement.break.switch-scope.v1'"],
    'a break with neither a switch nor a loop as its nearest container is read as scoped to one anyway':
        ["gaps << 'statement.break.container-unresolved'", "claims << 'statement.break.loop-scope.v1'"],
    'a while runs its body once unconditionally before checking the condition, the way repeat does':
        ["entry.role = 'pre-condition-loop'", "entry.role = 'post-condition-loop'"],
    'a repeat continues running while its condition holds, the same as while':
        ["entry.role = 'post-condition-loop'", "entry.role = 'pre-condition-loop'"],
    'a for runs its body exactly once, ignoring its start, end and step operands':
        ["claims << 'statement.for.step-iteration.v1'", ''],
    'an each runs its body exactly once, ignoring the device list it saves':
        ["claims << 'statement.each.device-iteration.v1'", ''],
    'a break inside a loop terminates the whole piston, as exit does':
        ["claims << 'statement.break.loop-scope.v1'", "claims << 'statement.exit.terminate-piston.v1'"],
    'an on requires every saved event matcher to match, rather than any one of them':
        ["claims << 'statement.on.any-event-match.v1'", ''],
    'an every continues to its following siblings after its own timer fires, the way an ordinary statement does':
        ["claims << 'statement.every.own-timer-only.v1'", ''],
    'an absent tep is read as never executing the tasks, rather than always':
        ["if (node.containsKey('tep')) claims << 'statement.tep.execution-policy.v1'", "if (!node.containsKey('tep')) claims << 'statement.tep.execution-policy.v1'"],
    'a saved tsp of a is read as override, dropping the earlier schedule, rather than allowing both':
        ["if (node.containsKey('tsp')) claims << 'statement.tsp.scheduling-policy.v1'", ''],
    'a saved tcp of c is read as never cancel':
        ["if (node.tcp != null && node.tcp != 'c') claims << 'statement.tcp.cancellation-policy.v1'", "if (node.tcp != null) claims << 'statement.tcp.cancellation-policy.v1'"],
    'a saved device list is expanded per task rather than once and shared by the whole action':
        ["claims << 'statement.action.device-list.v1'", '']
]
List named = (manifest.claims as List).collect { (it as Map).negative } + (manifest.limits as List).collect { (it as Map).negative }
check((mutations.keySet() as Set) == (named as Set), 'every named misreading in the manifest has a mutation here')
def detected = { Object n ->
    Map absentTcp = (model(n, [s: [[t: 'action', a: '0']]]).occurrences as Map)['$.s[0]'] as Map
    Map topLevelBreak = (model(n, [s: [[t: 'break', a: '0', tcp: 'c']]]).occurrences as Map)['$.s[0]'] as Map
    !traceMismatches(n).isEmpty() || !((absentTcp.gaps as List).contains('statement.envelope.tcp-non-default')) ||
        !((topLevelBreak.gaps as List).contains('statement.break.container-unresolved')) ||
        (topLevelBreak.claims as List).any { it in ['statement.break.switch-scope.v1', 'statement.break.loop-scope.v1'] }
}
mutations.each { String misreading, List fromTo ->
    check(detected(mutant(fromTo[0] as String, fromTo[1] as String)), "mutation: the misreading '${misreading}' fails the gates")
}

// ---- the generated evidence projection ------------------------------------------------

File generated = new File(repoRoot, 'tools/webcore-investigation/generated/webcore_semantic_evidence.groovy')
String before = generated.getText('UTF-8')
check(evidenceBlock.trim() == before.replace('\r\n', '\n').trim(), 'the app carries the generated semantic evidence verbatim')
check((evidence.claims as Map).keySet() == ((manifest.claims as List).collect { (it as Map).id } as Set) &&
      (evidence.claims as Map).values().every { it == true } && evidence.gaps == manifest.gaps,
    'the projection promotes every claim and carries exactly the closed gaps')
boolean ran = false
int rc = -1
for (String launcher in ['groovy.bat', 'groovy']) {
    try {
        List<String> env = System.getenv().collect { k, v -> "${k}=${v}" as String }.findAll { !it.startsWith('JAVA_HOME=') }
        env << ("JAVA_HOME=" + new File(System.getProperty('java.home')).canonicalPath)
        def proc = [launcher, 'tools/webcore-investigation/generate-semantic-evidence.groovy'].execute(env as String[], repoRoot)
        proc.consumeProcessOutput(new StringWriter(), new StringWriter())
        proc.waitFor()
        rc = proc.exitValue()
        ran = true
        break
    } catch (IOException ignored) { }
}
if (!ran) {
    println 'SKIP  regeneration comparison - no runnable groovy launcher found'
} else {
    check(rc == 0 && before == generated.getText('UTF-8'), 'the checked-in projection is byte-identical to a fresh regeneration')
}

String outside = source.replace(normalizerBlock, '')
check((outside =~ /webcoreSemanticModel\(/).count == 1 && outside.indexOf('webcoreSemanticModel(') > outside.indexOf('Map webcoreDecodeCoverageResult('),
    'only the coverage endpoint builds a semantic model, so the relationship scan and export are untouched')

int bad = results.count { !it }
println "${results.size() - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
