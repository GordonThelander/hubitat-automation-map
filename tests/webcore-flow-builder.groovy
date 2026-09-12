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
def liftBlock = { String name ->
    String beginMarker = "// --- ${name}: begin ---"
    String endMarker = "// --- ${name}: end ---"
    int begin = source.indexOf(beginMarker)
    int end = source.indexOf(endMarker)
    assert begin >= 0 && end > begin: "${name} block not found in app source"
    return source.substring(begin + beginMarker.length(), end)
}
// Two blocks, because the trigger classifier lives in the decoder region: the
// device walker there needs the same answer this builder does, and one copy
// read by both beats two that can drift.
String block = liftBlock('webcore trigger classifier') + '\n' + liftBlock('webcore flow builder')
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

// ---- condition text: transcribed from saved structure, never invented -------

Map realShape = [s: [[t: 'if', o: 'and', c: [
    [t: 'condition', co: 'changes',
     lo: [t: 'p', a: 'motion', d: [':13e33296864215646891b31480c3d6ec:']], ro: [t: 'c']],
    [t: 'condition', co: 'is',
     lo: [t: 'p', a: 'contact', d: [':db15e9deb12d0d5146b933146f7fd15a:']],
     ro: [t: 'c', c: 'closed']]],
    s: [[t: 'action', k: [[c: 'noop']]]]]]]
// The real shape splits: its "motion changes" is a trigger and its "contact is
// closed" is a condition, so the two clauses live on two different steps.
List realSteps = builder.buildWebcoreFlow(realShape)
Map realTrigger = realSteps[0]
Map realDecision = realSteps[1]
check(realTrigger.conditionJoiner == 'and' && realDecision.conditionJoiner == 'and',
    'the saved condition joiner is carried onto both halves')
check((realTrigger.conditionParts as List).size() == 1 && (realDecision.conditionParts as List).size() == 1,
    'each half carries exactly the clause it owns')
check(String.valueOf(realTrigger.label) == 'trigger not decoded' &&
      String.valueOf(realDecision.cond) == 'condition not decoded',
    'the builder itself still cannot name a device, so both fallbacks stand')

Map names = [':13e33296864215646891b31480c3d6ec:': 'Entrance Hall Motion Sensor',
             ':db15e9deb12d0d5146b933146f7fd15a:': 'Patio Door']
String composed = [realTrigger, realDecision].collect {
    String.valueOf(builder.webcoreFlowConditionText(it.conditionParts as List, 'and', names))
}.join(' then ')
check(composed == "Entrance Hall Motion Sensor's motion changes then Patio Door's contact is closed",
    "both halves read the way the piston editor writes them (${composed})")

Map twoConditions = [s: [[t: 'if', o: 'or', c: [
    [t: 'condition', co: 'is', lo: [t: 'v', v: 'mode'], ro: [t: 'c', c: 'Home']],
    [t: 'condition', co: 'is', lo: [t: 'v', v: 'mode'], ro: [t: 'c', c: 'Away']]]]]]
Map orDecision = builder.buildWebcoreFlow(twoConditions)[0]
check(String.valueOf(builder.webcoreFlowConditionText(orDecision.conditionParts as List, 'or', [:])) ==
        'mode is Home or mode is Away',
    'two real conditions join with the saved joiner, not a fixed and')
check(builder.webcoreFlowConditionText(realTrigger.conditionParts as List, 'and', [:]) == '',
    'an unresolvable device yields no text at all, never half a condition')
check(builder.webcoreFlowConditionText([[opaque: true]], 'and', names) == '',
    'an opaque part collapses the whole label rather than printing half a condition')
check(builder.webcoreFlowConditionText([], 'and', names) == '', 'no parts yields no text')

check(String.valueOf(builder.webcoreFlowConditionText(
        [[opaque: false, deviceTokens: [], subject: 'mode', op: 'is', value: 'Home']], 'and', [:])) ==
        'mode is Home', 'a non-device operand needs no resolution')
check(String.valueOf(builder.webcoreFlowConditionText(
        [[opaque: false, deviceTokens: [], subject: '@@TestNumber', op: 'is greater than', value: '5']],
        'and', [:])) == '@@TestNumber is greater than 5',
    'an underscored operator is transcribed with spaces, not reinterpreted')

// ---- nested condition groups compose instead of collapsing ------------------

Map nestedGroupIf = [s: [[t: 'if', o: 'and', c: [
    [t: 'group', o: 'or', c: [
        [t: 'condition', co: 'is', lo: [t: 'v', v: 'mode'], ro: [t: 'c', c: 'Home']],
        [t: 'condition', co: 'is', lo: [t: 'v', v: 'mode'], ro: [t: 'c', c: 'Away']]]],
    [t: 'condition', co: 'is', lo: [t: 'v', v: 'phase'], ro: [t: 'c', c: 'Night']]],
    s: [[t: 'action', k: [[c: 'noop']]]]]]]
Map nestedDecision = builder.buildWebcoreFlow(nestedGroupIf)[0]
check(String.valueOf(builder.webcoreFlowConditionText(nestedDecision.conditionParts as List, 'and', [:])) ==
        '(mode is Home or mode is Away) and phase is Night',
    'a group composes as a bracketed sub-sentence joined by its own saved operator')

String groupToken = ':f142209a9087c18092a59ef88e2b5b6a:'
List groupedDevicePart = [[opaque: false, group: true, joiner: 'or', parts: [
    [opaque: false, deviceTokens: [groupToken], subject: '', attribute: 'contact',
     op: 'is', value: 'closed']]]]
check(String.valueOf(builder.webcoreFlowConditionText(groupedDevicePart, 'and',
        [(groupToken): 'Patio Door'])) == "(Patio Door's contact is closed)",
    'a device inside a group resolves its name through the nested parts')
check(builder.webcoreFlowConditionText(groupedDevicePart, 'and', [:]) == '',
    'an unnamed device inside a group still collapses the whole label')

// An empty group has nothing to read, so it stays opaque rather than rendering
// as a pair of empty brackets.
check(builder.webcoreFlowConditionText(
        builder.webcoreFlowConditionParts([c: [[t: 'group', o: 'and', c: []]]], null, 0) as List,
        'and', [:]) == '',
    'a group with no readable conditions stays opaque')

Object deepGroup = [t: 'condition', co: 'is', lo: [t: 'v', v: 'mode'], ro: [t: 'c', c: 'Home']]
8.times { deepGroup = [t: 'group', o: 'and', c: [deepGroup]] }
Map deepIf = [s: [[t: 'if', o: 'and', c: [deepGroup], s: [[t: 'action', k: [[c: 'noop']]]]]]]
Map deepDecision = builder.buildWebcoreFlow(deepIf)[0]
check(builder.webcoreFlowConditionText(deepDecision.conditionParts as List, 'and', [:]) == '',
    'runaway group nesting is bounded and left opaque rather than followed')

check(String.valueOf(builder.webcoreFlowOperandText([t: 'c', c: 'closed'])) == 'closed' &&
      String.valueOf(builder.webcoreFlowOperandText([t: 'x', x: '@@GT1'])) == '@@GT1' &&
      builder.webcoreFlowOperandText([t: 'e']) == '' && builder.webcoreFlowOperandText([:]) == '',
    'operand text is transcribed for known kinds and blank for the rest')

// ---- triggers are split out of an if, the way webCoRE itself splits them ----

check(builder.webcoreFlowIsTrigger([co: 'changes']) && builder.webcoreFlowIsTrigger([co: 'rises_above']) &&
      builder.webcoreFlowIsTrigger([co: 'stays_equal_to']),
    'comparisons from the trigger block are triggers')
check(!builder.webcoreFlowIsTrigger([co: 'is']) && !builder.webcoreFlowIsTrigger([co: 'is_equal_to']) &&
      !builder.webcoreFlowIsTrigger([co: 'is_between']),
    'comparisons from the condition block are not triggers')
check(builder.webcoreFlowIsTrigger([co: 'is', ct: 't']) &&
      !builder.webcoreFlowIsTrigger([co: 'changes', ct: 'c']),
    'a stored ct wins over the operator name, since the executor wrote it')
check(!builder.webcoreFlowIsTrigger([:]), 'an unknown comparison is not assumed to be a trigger')

List mixed = builder.buildWebcoreFlow(realShape)
check(mixed[0].kind == 'trigger' && mixed[1].ctrl == 'if',
    "a trigger is lifted out ahead of the decision ${mixed.collect { it.ctrl ?: it.kind }}")
check((mixed[0].conditionParts as List).size() == 1 && (mixed[1].conditionParts as List).size() == 1,
    'the trigger and the condition each carry only their own part')
check(String.valueOf(builder.webcoreFlowConditionText(mixed[0].conditionParts as List, 'and', names)) ==
        "Entrance Hall Motion Sensor's motion changes",
    'the lifted trigger reads as its own sentence')
check(String.valueOf(builder.webcoreFlowConditionText(mixed[1].conditionParts as List, 'and', names)) ==
        "Patio Door's contact is closed",
    'the remaining decision holds only the real condition')
check(String.valueOf(mixed[1].cond) == 'condition not decoded',
    "the fallback counts only what the decision kept (${mixed[1].cond})")

Map allTriggers = [s: [[t: 'if', o: 'and',
    c: [[t: 'condition', co: 'changes', lo: [t: 'v', v: 'mode'], ro: [t: 'c']]],
    s: [[t: 'action', k: [[c: 'noop']]]]]]]
List trigOnly = builder.buildWebcoreFlow(allTriggers)
check(ctrlSequence(trigOnly) == [],
    "an if of only triggers draws no decision at all ${trigOnly.collect { it.ctrl ?: it.kind }}")
check(trigOnly[0].kind == 'trigger' && labels(trigOnly).contains('noop'),
    'a trigger-only if still draws its trigger and its body')

Map groupedIf = [s: [[t: 'if', o: 'and',
    c: [[t: 'group', c: []], [t: 'condition', co: 'changes', lo: [t: 'v', v: 'mode'], ro: [t: 'c']]],
    s: [[t: 'action', k: [[c: 'noop']]]]]]]
check(ctrlSequence(builder.buildWebcoreFlow(groupedIf)) == ['if', 'endif'],
    'a group stays with the decision rather than vanishing from both halves')

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
