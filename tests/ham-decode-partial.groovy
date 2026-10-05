// One throw in the rule decode used to cost the whole rule. A wrong argument
// order in the trigger resolver published six readable rules as "unreadable"
// with nothing in them, and the status said only that - not which half failed.
//
// A consumer cannot act on that. A rule with no triggers and a rule whose
// triggers could not be read look identical, and the second is the one where
// inventing a trigger does real harm: the sibling app gave two such rules an
// invocation trigger, producing a rule that runs when another rule calls it
// and never at its scheduled time.
String source = new File('apps/automation_map.groovy').getText('UTF-8')

int start = source.indexOf('Object hamDetailSafely(')
assert start >= 0 : 'hamDetailSafely not found'
int end = source.indexOf('// --- HAI decode detail: end ---')
String block = source.substring(start, end)

def script = new GroovyShell().parse('''
@groovy.transform.Field Map app = [label: 'Automation Map (Dev)']
@groovy.transform.Field List warnings = []
boolean diagOn() { return true }
@groovy.transform.Field def log = [warn: { String m -> warnings << m }]
''' + block + '''
void noop() { }
''')

int passed = 0
Closure check = { boolean cond, String what ->
    if (cond) { passed++; println "PASS  ${what}" }
    else { println "FAIL  ${what}"; System.exit(1) }
}

// --- a half that works is untouched ---------------------------------------
Map failures = [:]
Object ok = script.hamDetailSafely('conditions', failures) { return ['a', 'b'] }
check(ok == ['a', 'b'], 'a half that succeeds returns its own result')
check(failures.isEmpty(), 'and records no failure')

// --- a half that throws costs only itself, and is named -------------------
failures = [:]
Object bad = script.hamDetailSafely('triggers', failures) {
    throw new IllegalStateException('No signature of method: java.lang.String.call()')
}
check(bad == null, 'a half that throws returns nothing rather than propagating')
check(failures.containsKey('triggers'), 'and the failing half is named')
check("${failures.triggers}".contains('String.call'),
      'with the reason kept: "triggers failed" alone sends the next person back to the hub')

// --- the other halves still run -------------------------------------------
// This is the whole point: the trigger throw must not take conditions with it.
failures = [:]
Object conds = script.hamDetailSafely('conditions', failures) { return [[index: '1']] }
Object trigs = script.hamDetailSafely('triggers', failures) { throw new RuntimeException('boom') }
Object acts = script.hamDetailSafely('actions', failures) { return [[index: '7']] }
check((conds as List).size() == 1 && (acts as List).size() == 1,
      'conditions and actions survive a trigger failure')
check(failures.keySet() == ['triggers'] as Set,
      'and exactly one half is reported failed, not the whole rule')

// --- the distinction a consumer needs -------------------------------------
// Empty alone cannot be read as "none": it is also what a failure leaves.
check(!failures.containsKey('conditions'),
      'a half that returned an empty result is NOT recorded as a failure')

// --- the call sites are all guarded ---------------------------------------
check(source.count('hamDetailSafely(') >= 5,
      'every decode half runs under the guard, not just the one that broke')
['conditions', 'expressions', 'actions', 'triggers'].each { String part ->
    check(source.contains("hamDetailSafely('${part}'"), "the ${part} half is guarded")
}
check(source.contains('decodeFailures: ((info.decodeFailures'),
      'and the result is published, so a consumer can see which half is missing')

// --- a failure must never be silent ---------------------------------------
check(block.contains('log.warn'),
      'a failed half is logged as well as published, so it is visible without the file')

println "${passed} partial-decode assertions passed"
