// Patio Night (2283) stores no hasPredicate while eval group '0' holds
// ["13","AND","11"], and its own Rule Machine page renders that expression.
// Reading the flag alone showed a gated rule as ungated, on 1 rule of 71,
// which is why only a comparison against the page found it.
//
// hasPredicate is a convenience flag beside the expression rather than the
// expression itself. That is the same shape as every other decoding defect
// found on this hub: capabstrue is a cached rendering, indent disagrees with
// the real nesting, pvTF is inverted. The data has been right every time; the
// field summarising it has not.
String source = new File('apps/automation_map.groovy').getText('UTF-8')

int start = source.indexOf('boolean rulePredicateIsLive(Map st) {')
assert start >= 0 : 'rulePredicateIsLive not found'
int end = source.indexOf('\n}', start) + 2
String block = source.substring(start, end)

def script = new GroovyShell().parse(block + '\nvoid noop() { }\n')

int passed = 0
Closure check = { boolean cond, String what ->
    if (cond) { passed++; println "PASS  ${what}" }
    else { println "FAIL  ${what}"; System.exit(1) }
}

// The 30 ordinary rules on this hub: flag set, expression present.
check(script.rulePredicateIsLive([hasPredicate: true, eval: ['0': [3, 'AND', '16']]]),
      'a rule with the flag set and an expression is live')

// The 40 rules with no Required Expression at all.
check(!script.rulePredicateIsLive([eval: ['0': []]]),
      'a rule with no expression is not live')
check(!script.rulePredicateIsLive([:]), 'a rule with no eval at all is not live')
check(!script.rulePredicateIsLive([eval: [:]]), 'an empty eval map is not live')

// Patio Night, the one rule of 71 that this exists for. Its condition numbers
// are strings where every other rule stores integers, so its expression was
// written by a different Rule Machine path - the same path that left the flag
// unset.
check(script.rulePredicateIsLive([eval: ['0': ['13', 'AND', '11']]]),
      'an expression with no flag is still live: the expression is the fact')

// The flag alone still wins, so a rule that sets it keeps working even if the
// expression were read differently.
check(script.rulePredicateIsLive([hasPredicate: true, eval: [:]]),
      'the flag alone is enough, checked before the expression')

// Null-safe: the callers pass a map built from appState, which can be empty.
check(!script.rulePredicateIsLive(null), 'a null state is not live rather than throwing')

// Group '0' is the Required Expression. Any other group belongs to an action's
// own IF and must never make the predicate look live.
check(!script.rulePredicateIsLive([eval: ['1': ['5', 'AND', '7']]]),
      'an action IF in another eval group does not make the Required Expression live')

// Every call site goes through this, so the correction applies everywhere
// rather than only where it was noticed.
check(source.count('rulePredicateIsLive(st)') == 3,
      'all three predicate gates route through one helper')
check(!source.contains('st.hasPredicate == true'),
      'no call site reads the flag directly any more')

println "${passed} rule predicate assertions passed"
