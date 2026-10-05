// Rule Machine's stored condition text is a cache written at save time. A mode
// rename leaves it naming a mode that no longer exists, and the flow label used
// it verbatim: mode 6 was renamed Visitor to Guest and the map drew "Mode in
// Home, Visitor" while the rule page drew "Mode in [Home, Guest]".
//
// Both stored wordings below were read off Gordon's hub, not invented: rule
// 2031 condition 17 stores "Mode is Away", rule 2325 condition 5 stores
// "Mode in [Home, Visitor]".
String source = new File('apps/automation_map.groovy').getText('UTF-8')

int start = source.indexOf('    settingValues.keySet().toList().each { Object rawKey ->')
assert start >= 0 : 'mode label rebuild not found'
int end = source.indexOf('    List steps = []', start)
assert end > start : 'mode label rebuild not terminated'
String block = source.substring(start, end)

def script = new GroovyShell().parse('''
import groovy.transform.Field
@Field Map location = [modes: [[id: 5, name: 'Home'], [id: 6, name: 'Guest'], [id: 2, name: 'Away']]]
@Field Map capabs = [:]
@Field Map settingValues = [:]

List hamDetailJsonList(Object raw) {
    String s = "${raw ?: ''}".trim()
    if (!s) return []
    try { return new groovy.json.JsonSlurper().parseText(s) as List } catch (Exception ignored) { return [] }
}

List hamDetailModeNames(List ids) {
    List modes = (location?.modes ?: []) as List
    return (ids ?: []).collect { Object id ->
        Object hit = modes.find { Object m -> "${m?.id}" == "${id}" }
        return hit != null ? "${hit.name}" : null
    }
}

Map rebuild(Map values, Map stored) {
    settingValues.clear(); settingValues.putAll(values)
    capabs.clear(); capabs.putAll(stored)
''' + block + '''
    return capabs
}
''')

int passed = 0
Closure check = { boolean cond, String what ->
    if (cond) { passed++; println "PASS  ${what}" }
    else { println "FAIL  ${what}"; System.exit(1) }
}

// The defect, exactly as measured on rule 2325.
Map r = script.rebuild(['rCapab_5': 'Mode', 'modes5': '["5","6"]'], ['5': 'Mode in [Home, Visitor]'])
check(r['5'] == 'Mode in [Home, Guest]', 'a renamed mode is rebuilt from the live list, not read from the cache')

// RM's singular wording, as measured on rule 2031 condition 17.
r = script.rebuild(['rCapab_17': 'Mode', 'modes17': '["2"]'], ['17': 'Mode is Away'])
check(r['17'] == 'Mode is Away', 'one mode keeps Rule Machine own singular wording')

r = script.rebuild(['rCapab_17': 'Mode', 'modes17': '["5"]'], ['17': 'Mode is Visitor'])
check(r['17'] == 'Mode is Home', 'a single renamed mode is corrected too')

// A deleted mode: the stored text is the only record of its name.
r = script.rebuild(['rCapab_3': 'Mode', 'modes3': '["99"]'], ['3': 'Mode is Holiday'])
check(r['3'] == 'Mode is Holiday', 'an unresolvable mode keeps the stored text rather than losing the name')

r = script.rebuild(['rCapab_3': 'Mode', 'modes3': '["5","99"]'], ['3': 'Mode in [Home, Holiday]'])
check(r['3'] == 'Mode in [Home, Holiday]', 'one unresolvable mode leaves the whole clause alone')

// An unconfigured Mode condition stores no ids and renders nothing.
r = script.rebuild(['rCapab_9': 'Mode'], ['9': ''])
check(r['9'] == '', 'a mode condition with no mode chosen is left as it is')

// Everything that is not a mode is untouched: those sentences carry device
// names this cannot rebuild, and a half-rewrite would be worse than the cache.
r = script.rebuild(['rCapab_12': 'Temperature', 'modes12': '["5"]'],
                   ['12': 'Temperature of _ Average External Temperature is <= 15.0'])
check(r['12'] == 'Temperature of _ Average External Temperature is <= 15.0',
      'a non-mode condition is never rewritten')

// Mode triggers are keyed the same way and get the same correction.
r = script.rebuild(['tCapab4': 'Mode', 'modes4': '["6"]'], ['4': 'Mode is Visitor'])
check(r['4'] == 'Mode is Guest', 'a mode trigger is corrected on the same key')

// Negative suffixes are Wait-for-Events targets, not a number this walks.
r = script.rebuild(['tCapab-4': 'Mode', 'modes-4': '["6"]'], ['-4': 'Mode is Visitor'])
check(r['-4'] == 'Mode is Visitor', 'a non-integer suffix is skipped rather than throwing')

println "${passed} flow mode label assertions passed"
