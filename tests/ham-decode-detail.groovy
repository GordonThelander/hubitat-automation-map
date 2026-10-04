// The detail half of the HAI decode contract: resolved condition operands and
// the expression as a token sequence. Written before the implementation,
// against ideation/ham_decode_detail_contract.md in the HAI repository.
//
// The traps these assertions exist for are all measured on Gordon's hub, not
// imagined: a stale sibling operand on rule 1775, mode ids that render as
// names, and an expression whose left-to-right short-circuit disagrees with
// any tree.

String source = new File('apps/automation_map.groovy').getText('UTF-8')

int start = source.indexOf('// --- HAI decode detail: start ---')
int end = source.indexOf('// --- HAI decode detail: end ---')
assert start >= 0 : 'decode detail block not found'
assert end > start : 'decode detail block not terminated'
String block = source.substring(start, end)

def script = new GroovyShell().parse('''
import groovy.transform.Field
@Field Map location = [modes: [[id: 5, name: 'Home'], [id: 6, name: 'Visitor'], [id: 2, name: 'Night']]]
''' + block + '''
void noop() { }
''')

@groovy.transform.Field int passed = 0
@groovy.transform.Field Closure check = { boolean cond, String what ->
    if (cond) { passed++; println "PASS  ${what}" }
    else { println "FAIL  ${what}"; System.exit(1) }
}

// settings are name -> value, exactly as appSettings flattens them
Map S(Map m) { return m.collectEntries { k, v -> [(k as String), v == null ? null : "${v}"] } }

// --- Between two times: the stale sibling must be unreachable --------------

// Rule 1775 as stored: ending5 says Sunrise, so endingA5 '22:00' is a leftover
// from an earlier edit. RM renders "between 06:00 and Sunrise+10 minutes".
Map t = script.hamDetailCondition('5', 'Between two times', S([
    'rCapab_5': 'Between two times', 'starting5': 'A specific time', 'startingA5': '06:00',
    'ending5': 'Sunrise', 'endSunriseOffset5': '10', 'endingA5': '22:00', 'atOrBetween5': 'false'
]))
Map ops = t.operands as Map
check(t.index == '5', 'the condition carries its own Rule Machine number')
check(t.capability == 'Between two times', 'the capability is the summary token')
check((ops.from as Map).kind == 'clock', 'a specific start time resolves to kind clock')
check((ops.from as Map).at == '06:00', 'the start operand is the stored string, unnormalised')
check((ops.to as Map).kind == 'sunrise', 'a sunrise end resolves to kind sunrise')
check((ops.to as Map).offsetMinutes == 10, 'the sunrise offset travels as a number')
check((ops.to as Map).at == null, 'the stale endingA sibling is not reachable through the resolved operand')
check(ops.atOrBetween == false, 'atOrBetween is a boolean, not the string it is stored as')
check((t.raw as Map)['endingA5'] == '22:00', 'raw still carries the stale value so a disagreement is settleable')
check((t.raw as Map)['ending5'] == 'Sunrise', 'raw carries the selector too')

Map t2 = script.hamDetailCondition('7', 'Between two times', S([
    'starting7': 'Sunset', 'startSunsetOffset7': '-15', 'ending7': 'A specific time', 'endingA7': '21:30'
]))
Map ops2 = t2.operands as Map
check((ops2.from as Map).kind == 'sunset' && (ops2.from as Map).offsetMinutes == -15,
      'a negative sunset offset resolves with its sign')
check((ops2.to as Map).at == '21:30', 'the specific end time resolves from endingA')

// --- Mode: ids resolved to names, ids kept --------------------------------

Map m = script.hamDetailCondition('5', 'Mode', S(['modes5': '["5","6"]']))
Map mo = m.operands as Map
check((mo.modes as List) == ['Home', 'Visitor'], 'mode ids resolve to names against the hub mode list')
check((mo.ids as List) == ['5', '6'], 'the ids travel beside the names so a rename stays traceable')
check(!mo.containsKey('anyAll'), 'no any/all marker is invented: Rule Machine has none')

Map mUnknown = script.hamDetailCondition('9', 'Mode', S(['modes9': '["99"]']))
check(((mUnknown.operands as Map).modes as List) == [null],
      'an id with no matching mode resolves to null rather than to the id as a name')

// --- Private Boolean: no rule field, source named -------------------------

Map pb = script.hamDetailCondition('30', 'Private Boolean', S(['state_30': 'true', 'not30': '']))
check((pb.operands as Map).value == true, 'the private boolean value is a boolean')
check((pb.operands as Map).source == 'private', 'the source names which of private/p.PB/predPB was read')
check(!(pb.operands as Map).containsKey('rule'),
      'a private boolean condition carries no rule: cross-rule writes are an action, not a condition')

// --- negation ------------------------------------------------------------

check(script.hamDetailCondition('1', 'Switch', S(['not1': 'true'])).negated == true,
      'not<n> true becomes a real boolean')
check(script.hamDetailCondition('1', 'Switch', S(['not1': ''])).negated == false,
      'the empty string Rule Machine stores for false becomes false, not an empty string')
check(script.hamDetailCondition('1', 'Switch', S([:])).negated == false,
      'an absent not<n> is false')

// --- comparator travels untranslated --------------------------------------

Map cmp = script.hamDetailCondition('12', 'Illuminance',
    S(['RelrDev_12': '>', 'state_12': '200', 'rDev_12': 'x']))
check((cmp.operands as Map).comparator == '>', 'the comparator is RM\'s own string')
Map odd = script.hamDetailCondition('12', 'Illuminance', S(['RelrDev_12': 'betwixt']))
check((odd.operands as Map).comparator == 'betwixt',
      'an unrecognised comparator arrives raw rather than defaulting to equality')

// --- Variable -------------------------------------------------------------

Map v = script.hamDetailCondition('3', 'Variable',
    S(['xVar3': 'TestBoolean', 'RelrDev_3': '=', 'state_3': 'true']))
check((v.operands as Map).name == 'TestBoolean', 'the variable name travels')
check((v.operands as Map).comparator == '=', 'the variable comparator travels untranslated')

// --- expression: tokens, never a tree -------------------------------------

Map e = script.hamDetailExpression(['5', 'AND', '7', 'OR', '9'])
check((e.tokens as List) == ['5', 'AND', '7', 'OR', '9'], 'the expression is the stored token order')
check(e.evaluation == 'left-to-right-short-circuit',
      'the evaluation semantics are named rather than left to be assumed')
check(!e.containsKey('tree') && !e.containsKey('root'),
      'no tree is emitted: Rule Machine does not evaluate by precedence')
check(script.hamDetailExpression([]) == null, 'an empty expression is absent rather than an empty shell')

// --- the block resolves, it does not interpret ----------------------------

check(!block.contains('capabstrue') && !block.contains('capabsfalse'),
      'resolution reads the stored settings, never Rule Machine\'s rendered prose')
check(!block.contains('httpGet') && !block.contains('httpPost'),
      'the detail block performs no hub I/O of its own')

println "${passed} HAM decode detail assertions passed"
