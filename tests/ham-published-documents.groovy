// The published documents themselves, not the resolvers that build them.
//
// On 2026-10-05 five defects reached the hub behind a fully green suite, and every one was plainly visible
// in the published output: a delay on 13 wait steps, all 83 control-flow records published unsupported,
// six readable rules published unreadable and empty. The suites sliced a resolver out of the app and
// asserted on its return value; nothing asserted on the document a rebuild actually reads.
//
// These read tests/fixtures/ham-decode-detail.json and tests/fixtures/ham-decode.json - real output
// captured from Gordon's hub, 76 Rule Machine rules - and assert on their shape and their counts. The
// fixtures are the evidence. When an assertion here fails, the assertion or the app is in question, never
// the fixture: do not regenerate or edit them to make this pass.
//
// Run with: groovy tests/ham-published-documents.groovy

import groovy.json.JsonSlurper

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('..').canonicalFile
Map detail = (Map) new JsonSlurper().parse(new File(repoRoot, 'tests/fixtures/ham-decode-detail.json'), 'UTF-8')
Map summary = (Map) new JsonSlurper().parse(new File(repoRoot, 'tests/fixtures/ham-decode.json'), 'UTF-8')

int passed = 0
List<String> failed = []
// Every assertion runs, rather than stopping at the first failure: a document with two defects should
// report two, and an early exit is how a second defect hides behind a first.
Closure check = { boolean cond, String what, Object evidence = null ->
    if (cond) { passed++; println "PASS  ${what}" }
    else {
        failed << what
        println "FAIL  ${what}"
        if (evidence != null) println "      ${evidence.toString().take(400)}"
    }
}

List<Map> rules = (List<Map>) detail.rules
List<Map> actions = rules.collectMany { Map r -> ((List<Map>) r.actions).collect { [rule: r.id, a: it] } }
List<Map> triggers = rules.collectMany { Map r -> ((List<Map>) r.triggers).collect { [rule: r.id, t: it] } }
List<Map> conditions = rules.collectMany { Map r -> ((List<Map>) r.conditions).collect { [rule: r.id, c: it] } }
Closure where = { Map x, Map item -> "${x.rule}#${item.index}" }

// ---- the envelopes --------------------------------------------------------------------------------

check(detail.contract == 'ham.decode.detail/1' && summary.contract == 'ham.decode/1', 'each document names its contract')
check(detail.ok == true && detail.issue == null && summary.ok == true && summary.issue == null,
      'both documents are a successful scan with no issue')
check(detail.supportedEngine == 'Rule-5.1' && summary.supportedEngine == 'Rule-5.1', 'both name the supported engine')
check(rules*.id.sort() == ((List<Map>) summary.rules)*.id.sort(),
      'the detail and the summary describe the same rules', (rules*.id - ((List<Map>) summary.rules)*.id))

// ---- counts, so a silent collapse to zero fails -------------------------------------------------------
// Exact for this capture. A change here means the app now publishes something different from the same
// hub state, which is exactly what this suite exists to notice.

// Captured from 2.4.11 on the Dev hub (contract 3). The 76 include five paused probe rules Claude HAM
// built, the only examples of their cases on this hub: 3594 (Use Duration beside a Timeout), 3595 and 3597
// (several events, with and without a stays clause), 3596 (carries on after a timeout), 3598 (Wait for
// Events with a timeout). Deleting one moves these counts. 2.4.11 dropped two phantom triggers, 814's 27
// and 2816's 13: leftover tCapab settings the condition namespace used to fill in. 2.4.12 restored
// 1230's trigger 26, Certain Time at 21:05, stored under the older label and dropped until then.
check(rules.size() == 76, 'the capture holds 76 rules', rules.size())
check(actions.size() == 519, 'the rules publish 519 actions', actions.size())
check(actions.count { it.a.supported == true } == 518, '518 actions are supported', actions.count { it.a.supported == true })
check(triggers.size() == 108, 'the rules publish 108 triggers', triggers.size())
List phantoms = triggers.findAll { ("${it.rule}".replaceFirst(/^a/, '') + '#' + it.t.index) in ['814#27', '2816#13'] }
check(triggers.any { "${it.rule}".replaceFirst(/^a/, '') == '1230' && it.t.index == '26' },
      "rule 1230's Certain Time trigger is published (stored under the older label 'Certain Time')")
check(phantoms.isEmpty(), "rule 814's trigger 27 and rule 2816's trigger 13 are leftovers, not triggers", phantoms)
check(conditions.size() == 169, 'the rules publish 169 conditions', conditions.size())

// ---- every rule ----------------------------------------------------------------------------------

List noFailures = rules.findAll { !it.containsKey('decodeFailures') }*.id
check(noFailures.isEmpty(), 'every rule carries decodeFailures (an absent key is a failure, an empty map is not)', noFailures)
List failuresNotMap = rules.findAll { it.containsKey('decodeFailures') && !(it.decodeFailures instanceof Map) }*.id
check(failuresNotMap.isEmpty(), 'decodeFailures is always a map', failuresNotMap)

List unreadableWithContent = rules.findAll {
    it.status == 'unreadable' && (!((List) it.conditions).isEmpty() || !((List) it.actions).isEmpty())
}*.id
check(unreadableWithContent.isEmpty(), 'no rule is unreadable while also publishing conditions or actions', unreadableWithContent)

// ---- actions ------------------------------------------------------------------------------------

List waitsWithDelay = actions.findAll { (it.a.method in ['getWaitRule', 'getWaitEvents']) && it.a.containsKey('delay') }
    .collect { where(it, it.a) }
check(waitsWithDelay.isEmpty(),
      "no wait carries a delay: a wait's duration is not a delay before it, and a rebuilt rule would wait twice",
      waitsWithDelay)
check(actions.count { it.a.method in ['getWaitRule', 'getWaitEvents'] } == 29, 'the capture holds 29 wait steps to check')

List flow = actions.findAll { it.a.method in ['getIfThen', 'getElseIf', 'getElse', 'getEndIf'] }
check(flow.size() == 83, 'the capture holds 83 control-flow records', flow.size())
List flowUnsupported = flow.findAll { it.a.supported != true }.collect { where(it, it.a) }
check(flowUnsupported.isEmpty(), 'every control-flow record is supported', flowUnsupported)
List flowNoBranch = flow.findAll { !((it.a.operands ?: [:]) as Map).branch }.collect { where(it, it.a) }
check(flowNoBranch.isEmpty(), 'every control-flow record carries operands.branch', flowNoBranch)

List supportedThin = actions.findAll { it.a.supported == true && !(it.a.containsKey('type') && it.a.operands instanceof Map) }
    .collect { "${where(it, it.a)} ${it.a.method}" }
check(supportedThin.isEmpty(), 'every supported action carries type and operands', supportedThin)
List unsupportedNameless = actions.findAll { it.a.supported == false && !it.a.method }.collect { where(it, it.a) }
check(unsupportedNameless.isEmpty(), 'every unsupported action carries its method, so a consumer can refuse it by name',
      unsupportedNameless)
List supportedNotBool = actions.findAll { !(it.a.supported instanceof Boolean) }.collect { where(it, it.a) }
check(supportedNotBool.isEmpty(), 'supported is a boolean on every action', supportedNotBool)

List pb = actions.findAll { it.a.method == 'getSetPrivateBoolean' && it.a.supported == true }
check(pb.size() == 36, 'the capture holds 36 Set Private Boolean actions', pb.size())
List pbNotBool = pb.findAll { !(((it.a.operands ?: [:]) as Map).value instanceof Boolean) }.collect { where(it, it.a) }
check(pbNotBool.isEmpty(),
      'every Set Private Boolean carries a boolean value (pvTF is stored inverted, so this catches a regression)',
      pbNotBool)

// A volume only means something where something speaks. tests/ham-decode-actions.groovy asserts the same
// of the resolver; this is the published consequence.
List volumeWithoutSpeech = actions.findAll {
    Map o = (it.a.operands ?: [:]) as Map
    it.a.method == 'getMsg' && o.containsKey('volume') && !(o.speak instanceof List && !((List) o.speak).isEmpty())
}.collect { "${where(it, it.a)} volume=${((Map) it.a.operands).volume}" }
check(volumeWithoutSpeech.isEmpty(), 'no message without a speaker publishes a volume', volumeWithoutSpeech)

// ---- triggers -----------------------------------------------------------------------------------

List triggersThin = triggers.findAll { it.t.index == null || !it.t.capability }.collect { "${it.rule} ${it.t}" }
check(triggersThin.isEmpty(), 'every trigger carries index and capability', triggersThin)

// ---- device references ----------------------------------------------------------------------------
// A name cannot bind: two devices can share a label, and one on this hub carries a trailing space. So
// every device reference is {id, name} with a non-empty id. Checked per place it is published, because
// the places are built by different code and fail independently.

Closure refBad = { Object e ->
    !(e instanceof Map) || !((Map) e).containsKey('name') || !"${((Map) e).id ?: ''}".trim()
}
Closure refsIn = { List<Map> items, Closure ops, List<String> keys ->
    int n = 0
    List bad = []
    items.each { Map it ->
        Map o = (ops(it) ?: [:]) as Map
        keys.each { String k ->
            if (!(o[k] instanceof List)) return
            ((List) o[k]).each { Object e -> n++; if (refBad(e)) bad << "${it.rule}: ${e}" }
        }
    }
    return [n: n, bad: bad]
}

Map actRefs = refsIn(actions, { it.a.operands }, ['devices', 'notify', 'speak'])
check(actRefs.n > 0 && actRefs.bad.isEmpty(), "every action device reference is {id, name} (${actRefs.n} of them)", actRefs.bad.take(5))
Map trigRefs = refsIn(triggers, { it.t.operands }, ['devices'])
check(trigRefs.n > 0 && trigRefs.bad.isEmpty(), "every trigger device reference is {id, name} (${trigRefs.n} of them)", trigRefs.bad.take(5))
Map condRefs = refsIn(conditions, { it.c.operands }, ['devices'])
// Backlog 55, closed by contract 2: conditions carry {id, name} like everything else.
check(condRefs.n > 0 && condRefs.bad.isEmpty(), "every condition device reference is {id, name} (${condRefs.n} of them)",
      "${condRefs.bad.size()} are bare names, e.g. ${condRefs.bad.take(3)}")
// `steps` is the display and rule-link layer, decided by Gordon 2026-10-05: its devices are names for
// people to read and nothing binds a device from it, so it is not held to {id, name}. What a consumer
// does take from it is `ruleTargets` - HAI orders a migration and warns about coupled rules from them -
// so those must be rule ids, and each must be a rule this document holds.
Set ruleIds = rules.collect { "${it.id}".replaceFirst(/^a/, '') } as Set
List targets = rules.collectMany { Map r -> ((List<Map>) r.steps).collectMany { Map st -> ((List) (st.ruleTargets ?: [])).collect { [rule: r.id, t: it] } } }
check(targets.size() == 68, 'the capture holds 68 rule links in steps', targets.size())
List badTargets = targets.findAll { !("${it.t}" ==~ /\d+/) || !ruleIds.contains(it.t.toString()) }.collect { "${it.rule} -> ${it.t}" }
// Backlog 56, closed by contract 2: 2096's link to the deleted rule 2354 is dropped and listed as a
// finding (deletedRuleReferences) rather than handed to a consumer that cannot resolve it.
check(badTargets.isEmpty(), 'every step ruleTarget is a bare rule id naming a rule in this document', badTargets)

// Contract 2 replaced the parallel deviceIds list with {id, name} on each device, so the old list must be
// gone: a consumer that still found one would be reading two shapes for the same thing.
List oldIds = conditions.findAll { ((it.c.operands ?: [:]) as Map).containsKey('deviceIds') }.collect { where(it, it.c) }
check(oldIds.isEmpty(), 'no condition still carries the parallel deviceIds list contract 2 replaced', oldIds)

println ''
println "${passed} passed, ${failed.size()} failed"
if (failed) {
    failed.each { println "  - ${it}" }
    System.exit(1)
}
