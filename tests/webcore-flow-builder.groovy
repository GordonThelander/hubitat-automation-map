#!/usr/bin/env groovy
//
// The webCoRE flow builder, executed straight out of the app source so this
// cannot pass against a drifted copy. Covers statement ordering, branch
// structure, the deliberately opaque condition labels, device-token carriage and
// the traversal bounds - and runs the builder over a real committed L3 fixture,
// not only hand-built shapes.
// Run with: groovy tests/webcore-flow-builder.groovy

import groovy.json.JsonSlurper

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('..').canonicalFile

List<Boolean> results = []
def check = { boolean cond, String label ->
    println "${cond ? 'PASS' : 'FAIL'}  ${label}"
    results << cond
}

// ---- load the builder straight out of the app ------------------------------

String source = new File(repoRoot, 'apps/automation_map.groovy').getText('UTF-8')
String beginMarker = '// --- webcore flow builder: begin ---'
String endMarker = '// --- webcore flow builder: end ---'
int begin = source.indexOf(beginMarker)
int end = source.indexOf(endMarker)
assert begin >= 0 && end > begin: 'flow builder block not found in app source'
String block = source.substring(begin + beginMarker.length(), end)
def builder = new GroovyClassLoader(this.class.classLoader)
        .parseClass("class WebcoreFlowUnderTest {\n" + block + "\n}").newInstance()

// String, not GString: Groovy's == coerces the two, but List.contains and
// containsAll are plain Java calls where a GString never equals a String.
def ctrlSequence = { List steps -> steps.findAll { it.ctrl }.collect { String.valueOf(it.ctrl) } }
def labels = { List steps -> steps.collect { String.valueOf(it.label) } }

// ---- nothing in, nothing out -----------------------------------------------

check(builder.buildWebcoreFlow(null) == [], 'a null document produces no steps')
check(builder.buildWebcoreFlow('not a map') == [], 'a non-map document produces no steps')
check(builder.buildWebcoreFlow([:]) == [], 'a document with no statements produces no steps')
check(builder.buildWebcoreFlow([s: 'not a list']) == [], 'a non-list statement set produces no steps')

// ---- an on statement becomes triggers, then its body ------------------------

Map onPiston = [s: [[t: 'on',
    c: [[t: 'event', lo: [t: 'p', a: 'switch', d: [':f142209a9087c18092a59ef88e2b5b6a:']]],
        [t: 'event', lo: [t: 'x', x: '@@TestNumber']]],
    s: [[t: 'action', d: [':f142209a9087c18092a59ef88e2b5b6a:'], k: [[c: 'noop']]]]]]]
List onSteps = builder.buildWebcoreFlow(onPiston)
check(onSteps.size() == 3, "an on statement emits one step per event then its body (${onSteps.size()})")
check(onSteps[0].kind == 'trigger' && onSteps[1].kind == 'trigger' && onSteps[2].kind == 'action',
    'events render as triggers and the body as an action')
check("${onSteps[0].label}" == 'When switch changes',
    "a physical event is labelled from its own attribute key (${onSteps[0].label})")
check("${onSteps[1].label}" == 'When @@TestNumber changes',
    "a variable event is labelled from its own variable key (${onSteps[1].label})")
check("${onSteps[2].label}" == 'noop', "a task renders as its saved command name (${onSteps[2].label})")

// ---- device tokens are carried, never resolved here -------------------------

check(onSteps[0].deviceTokens == [':f142209a9087c18092a59ef88e2b5b6a:'],
    'a physical event carries its device token')
check(onSteps[0].devices == [], 'the builder resolves no device names itself')
check(onSteps[2].deviceTokens == [':f142209a9087c18092a59ef88e2b5b6a:'],
    "an action carries its statement's device token")
Map mixedTokens = [s: [[t: 'action', k: [[c: 'noop']],
    d: [':f142209a9087c18092a59ef88e2b5b6a:', 'not-a-token', 42, ':SHORT:',
        ':f142209a9087c18092a59ef88e2b5b6a:']]]]
check(builder.buildWebcoreFlow(mixedTokens)[0].deviceTokens == [':f142209a9087c18092a59ef88e2b5b6a:'],
    'only webCoRE device tokens are carried, and each only once')

// ---- branch structure -------------------------------------------------------

Map ifPiston = [s: [[t: 'if', c: [[t: 'condition']],
    s: [[t: 'action', k: [[c: 'on']]]],
    ei: [[t: 'elseif', c: [[t: 'condition'], [t: 'condition']], s: [[t: 'action', k: [[c: 'off']]]]]],
    e: [[t: 'action', k: [[c: 'toggle']]]]]]]
List ifSteps = builder.buildWebcoreFlow(ifPiston)
check(ctrlSequence(ifSteps) == ['if', 'elseif', 'else', 'endif'],
    "an if/elseif/else closes exactly once (${ctrlSequence(ifSteps)})")
check(labels(ifSteps).containsAll(['on', 'off', 'toggle']),
    "every branch body is drawn, not just the first ${labels(ifSteps)}")
check("${ifSteps[0].cond}" == 'condition not decoded',
    "a single condition is stated as undecoded (${ifSteps[0].cond})")
check("${ifSteps.find { it.ctrl == 'elseif' }.cond}" == '2 conditions not decoded',
    'a multi-condition branch reports its count without claiming meaning')

Map noElse = [s: [[t: 'if', c: [[t: 'condition']], s: [[t: 'action', k: [[c: 'on']]]]]]]
check(ctrlSequence(builder.buildWebcoreFlow(noElse)) == ['if', 'endif'],
    'an if with no else still closes')

// ---- switch renders as an ordered decision chain, with no invented default ---

Map switchPiston = [s: [[t: 'switch', cs: [
    [t: 'case', s: [[t: 'action', k: [[c: 'first']]]]],
    [t: 'case', s: [[t: 'action', k: [[c: 'second']]]]]]]]]
check(ctrlSequence(builder.buildWebcoreFlow(switchPiston)) == ['if', 'elseif', 'endif'],
    'ordered cases become one decision chain')
check(!ctrlSequence(builder.buildWebcoreFlow(switchPiston)).contains('else'),
    'no default branch is invented for a switch')
check(builder.buildWebcoreFlow([s: [[t: 'switch', cs: []]]]) == [],
    'a switch with no cases draws nothing rather than an empty decision')

// ---- loops are delimited, never drawn as a decision --------------------------

// Stated outright rather than derived from the builder, so a closer that stops
// matching its own opener is caught instead of mirrored.
Map loopEnds = ['while': 'end while', 'repeat': 'end repeat',
                'for': 'end for each step', 'each': 'end for each device']
['while', 'repeat', 'for', 'each'].each { String type ->
    List loopSteps = builder.buildWebcoreFlow([s: [[t: type, s: [[t: 'action', k: [[c: 'noop']]]]]]])
    check(loopSteps.size() == 3 && loopSteps[0].ctrl == null &&
          String.valueOf(loopSteps[2].label) == loopEnds[type],
        "${type} is delimited by enter and exit blocks that match ${labels(loopSteps)}")
    check(ctrlSequence(loopSteps) == [], "${type} draws no decision diamond")
}

// ---- ordering and pass-through statements -----------------------------------

Map manyTasks = [s: [[t: 'action', k: [[c: 'first'], [c: 'second'], [c: 'third']]]]]
check(labels(builder.buildWebcoreFlow(manyTasks)) == ['first', 'second', 'third'],
    'tasks are drawn one per step in saved order')
check(labels(builder.buildWebcoreFlow([s: [[t: 'do', s: [[t: 'action', k: [[c: 'inner']]]]]]])) == ['inner'],
    'a do block contributes its contents with no wrapper of its own')
check(labels(builder.buildWebcoreFlow([s: [[t: 'break'], [t: 'exit']]])) == ['break', 'exit piston'],
    'break and exit are drawn')
check(builder.buildWebcoreFlow([s: [[t: 'action']]])[0].label == 'action',
    'an action with no tasks still draws one block')

// ---- an unknown statement is visible, never silently dropped ----------------

List unknown = builder.buildWebcoreFlow([s: [[t: 'somethingNew', s: [[t: 'action', k: [[c: 'inner']]]]]]])
check("${unknown[0].label}" == 'somethingNew not decoded',
    "an unrecognised statement names itself as undecoded (${unknown[0].label})")
check(labels(unknown).contains('inner'), 'an unrecognised statement still contributes its body')

// ---- bounds -----------------------------------------------------------------

Map deep = [t: 'do', s: [[t: 'action', k: [[c: 'bottom']]]]]
40.times { deep = [t: 'do', s: [deep]] }
List deepSteps = builder.buildWebcoreFlow([s: [deep]])
check(deepSteps.size() == 0 || deepSteps.size() < 40, 'runaway nesting is bounded rather than followed')
List wide = (1..500).collect { [t: 'action', k: [[c: "task${it}"]]] }
check(builder.buildWebcoreFlow([s: wide]).size() <= 400, 'the step count is capped')

// ---- a real committed fixture ----------------------------------------------

File fixture = new File(repoRoot, 'tests/fixtures/webcore-l3/l3-16-variable-trigger.round-trip.json')
if (!fixture.isFile()) {
    println 'SKIP  real fixture: l3-16-variable-trigger.round-trip.json is not committed'
} else {
    List real = builder.buildWebcoreFlow(new JsonSlurper().parse(fixture) as Map)
    check(real.size() >= 2, "a real captured piston produces steps (${real.size()})")
    check(real[0].kind == 'trigger', 'the captured on statement leads with a trigger')
    check(real.any { it.kind == 'action' }, 'the captured body renders as an action')
    check(real.every { it.devices == [] }, 'no device name is invented from a fixture')
}

int bad = results.count { !it }
println "${results.size() - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
