// Triggers for the HAI decode contract. Until now the detail file published no
// triggers at all: steps[] carried a rendered label and device NAMES, which is
// the display layer and the exact shape we agreed not to rebuild from. A rule
// rebuilt without its trigger is inert while looking complete on the page, so
// this is the blocker, not a nicety.
//
// Every shape measured against this hub before being written. The traps below
// are all real and all cost something if read the obvious way.
String source = new File('apps/automation_map.groovy').getText('UTF-8')

int start = source.indexOf('Map hamDetailTriggerOperands(')
int end = source.indexOf('// --- HAI decode detail: end ---')
assert start >= 0 : 'trigger resolver not found'
assert end > start
String block = source.substring(start, end)

// Everything the resolver calls, sliced from the app rather than stubbed: the stubs disagreed with it
// (hamDetailInt returned null where the app returns 0, hamDetailBool was case-sensitive, and
// hamDetailJsonList dropped text the app keeps). Modes are objects with properties, as the hub's Mode
// is, never plain maps - a map-shaped mode is what hid a real defect on 2026-10-05.
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String stubs = 'import groovy.transform.Field\n' +
    AppSource.field(source, 'DEVICELESS_TRIGGERS') + '\n' +
    'class HubMode { Integer id; String name }\n' +
    "@Field Map location = [modes: [new HubMode(id: 5, name: 'Home'), new HubMode(id: 2, name: 'Away')]]\n" +
    AppSource.functions(source, ['stripTags', 'hamDetailInt', 'hamDetailBool', 'hamDetailJsonList',
                                 'hamDetailModeNames', 'hamDetailDeviceRefs']) + '\n'
def script = new GroovyShell().parse(stubs + block + '\nvoid noop() { }\n')

// The slices are the app's, not a stub's: each returns what only the real one returns.
assert script.hamDetailInt('') == 0 : 'hamDetailInt is a stub: the app returns 0 for an empty value'
assert script.hamDetailBool('TRUE') == true : 'hamDetailBool is a stub: the app ignores case'
assert script.hamDetailJsonList('not json') == ['not json'] : 'hamDetailJsonList is a stub: the app keeps unparseable text'
assert !(script.location.modes[0] instanceof Map) : 'modes must be objects, as the hub supplies them, not maps'

int passed = 0
Closure check = { boolean cond, String what ->
    if (cond) { passed++; println "PASS  ${what}" }
    else { println "FAIL  ${what}"; System.exit(1) }
}

// Trigger settings carry NO dot suffix, unlike action settings.
Closure T = { String num, Map m -> m.collectEntries { k, v -> ["${k}${num}".toString(), v == null ? null : "${v}"] } }
Closure ops = { String num, String cap, Map v, Map d = [:] ->
    return script.hamDetailTriggerOperands(num, cap, T(num, v), d)
}

// --- the comparator that was unread until late September ------------------
// A '>' trigger flattening to equality is silent and changes when a rule runs.
Map temp = ops('12', 'Temperature', [tstate: '28', ReltDev: '>'],
    ['tDev12': [[id: '2386', name: 'Hallway Motion']]])
check(temp.comparator == '>', 'the trigger comparator travels untranslated')
check(temp.value == '28', 'the compared value travels as stored')
check(((temp.devices as List)[0] as Map).id == '2386', 'devices carry ids, not just names')

// --- devices: any vs all --------------------------------------------------
Map motion = ops('1', 'Motion', [tstate: 'active', AlltDev: 'true'],
    ['tDev1': [[id: '1', name: 'A'], [id: '2', name: 'B']]])
check(motion.allDevices == true, 'all-devices travels as a boolean')
check(ops('1', 'Motion', [tstate: 'active', AlltDev: 'false'], ['tDev1': [[id: '1', name: 'A']]]).allDevices == false,
      'any-devices is false rather than absent')

// --- stays that way for ---------------------------------------------------
Map stays = ops('3', 'Contact', [tstate: 'open', stays: 'true', SHours: '0', SMins: '0', SSecs: '5'],
    ['tDev3': [[id: '9', name: 'Front Door']]])
check(stays.staysForSeconds == 5, 'a stays clause resolves to seconds')
Map noStays = ops('3', 'Contact', [tstate: 'open', stays: 'false', SSecs: '5'],
    ['tDev3': [[id: '9', name: 'Front Door']]])
check(!noStays.containsKey('staysForSeconds'),
      'stays false carries no duration: absent is not zero, the trigger fires at once')

// --- Mode: modesX on a trigger, modes on a condition ----------------------
Map mode = ops('4', 'Mode', [modesX: '["2"]'])
check((mode.modes as List) == ['Away'], 'a mode trigger reads modesX and resolves to names')
check((mode.ids as List) == ['2'], 'mode ids travel beside the names')
check(ops('4', 'Mode', [modes: '["2"]']).ids == [],
      'the condition spelling is NOT read for a trigger: that is a different field')

// --- Button ----------------------------------------------------------------
Map btn = ops('5', 'Button', [tstate: 'pushed', ButtontDev: '1'],
    ['tDev5': [[id: '7', name: 'Hallway Button']]])
check(btn.button == 1, 'the button number travels as a number')
check(btn.value == 'pushed', 'and the action is the stored word')

// --- Periodic Schedule and Location Event: no devices, still real ---------
Map per = ops('11', 'Periodic Schedule', [whichPeriod: 'Seconds', everyNSecs: '10'])
check(per.period == 'Seconds' && per.every == 10, 'a periodic schedule resolves its period and interval')
check(ops('47', 'Location Event', [tstate: 'severeLoad']).value == 'severeLoad',
      'a location event carries its event name')

// --- Custom Attribute ------------------------------------------------------
Map attr = ops('1', 'Custom Attribute', [tCustomAttr: 'formattedUptime', tstate: '*changed*', ReltDev: '*changed*'],
    ['tDev1': [[id: '3574', name: 'Hub Information Driver']]])
check(attr.attribute == 'formattedUptime', 'a custom attribute names the attribute')
check(attr.comparator == '*changed*', 'and a changed comparator is not mistaken for a value')

// --- a conditional trigger lives in the CONDITION namespace ---------------
// Rule 814's Presence trigger 27 stores rCapab_27/state_27/rDev_27 and has no
// tDev27 at all. Reading only the trigger spelling publishes an empty trigger.
Map cond = script.hamDetailTriggerOperands('27', 'Presence',
    ['state_27': 'not present', 'AllrDev_27': 'false'],
    ['rDev_27': [[id: '665', name: 'Lounge Repeater']]])
check((cond.devices as List).size() == 1,
      'a conditional trigger finds its devices in the condition namespace')
check(cond.value == 'not present', 'and its compared value too')

// --- the phantom triggers from the rule-page audit ------------------------
// 2865 and 2816 both store tCapab13='Switch' with no tDev13. Rule Machine
// shows one trigger; the map showed two. 2816's phantom even collects
// condition 13's rendering, so it reads as entirely plausible.
List phantom = script.extractRuleTriggers([appSettings: [
    [name: 'tCapab40', value: 'Switch'],
    [name: 'tDev40', value: null, deviceList: ['3544': 'Guest Mode']],
    [name: 'tstate40', value: 'on'],
    [name: 'tCapab13', value: 'Switch']
]])
check(phantom.size() == 1, 'a device family with no devices is a leftover, not a trigger')
check(phantom[0].index == '40', 'the real trigger is the one that has devices')

// A device-less family with no devices is still a real trigger, which is why
// the test is the family and not simply "has devices".
List timed = script.extractRuleTriggers([appSettings: [
    [name: 'tCapab7', value: 'Periodic Schedule'],
    [name: 'whichPeriod7', value: 'Hours'],
    [name: 'everyNSecs7', value: '2']
]])
check(timed.size() == 1, 'a periodic schedule survives having no device')

// Negative suffixes are Wait-for-Events targets, not triggers.
List waits = script.extractRuleTriggers([appSettings: [
    [name: 'tCapab-4', value: 'Motion'],
    [name: 'tDev-4', value: null, deviceList: ['1': 'X']]
]])
check(waits.isEmpty(), 'a negative suffix is a Wait-for-Events target, never a trigger')

// Emitted in numeric order, not the arbitrary order settings arrive in.
List ordered = script.extractRuleTriggers([appSettings: [
    [name: 'tCapab10', value: 'Motion'],
    [name: 'tDev10', value: null, deviceList: ['1': 'A']],
    [name: 'tCapab2', value: 'Motion'],
    [name: 'tDev2', value: null, deviceList: ['2': 'B']]
]])
check(ordered*.index == ['2', '10'], 'triggers are emitted in numeric order')

// --- Certain Time: the trigger's own fields, not the condition ones -------
// This is the regression that cost six rules EVERYTHING. The block called
// hamDetailTimeOperand(num, v, 'at') against a real signature of
// (which, num, settingValues), which throws, and a throw in this extractor
// loses the whole app record rather than just the trigger. The suite passed
// because the harness stubbed that function with the invented signature.
// Nothing stubs it now, so a wrong call cannot compile past here.
check(!block.contains('hamDetailTimeOperand'),
      'the trigger block does not reuse the condition time resolver: different fields entirely')

Map clock = ops('7', 'Certain Time (and optional date)', [time: 'A specific time', atTime: '23:59'])
check((clock.at as Map).at == '23:59', 'a specific time resolves from atTime')
check((clock.at as Map).kind == 'clock', 'and is named a clock time')
Map sunset = ops('7', 'Certain Time (and optional date)', [time: 'Sunset', atSunsetOffset: '-15'])
check((sunset.at as Map).kind == 'sunset' && (sunset.at as Map).offsetMinutes == -15,
      'a sunset trigger resolves with its signed offset')
Map sunrise = ops('7', 'Certain Time (and optional date)', [time: 'Sunrise'])
check((sunrise.at as Map).kind == 'sunrise', 'a sunrise trigger resolves with no offset stored')
Map dated = ops('7', 'Certain Time (and optional date)',
    [time: 'A specific time', atTime: '13:12', date: 'true', atDate: '2027-01-01'])
check(dated.date == '2027-01-01', 'the optional date travels when its toggle is on')
check(!ops('7', 'Certain Time (and optional date)',
    [time: 'A specific time', atTime: '13:12', atDate: '2027-01-01']).containsKey('date'),
      'and is ignored when the toggle is off, rather than scheduling a one-off')

// --- Periodic Schedule: startingTime is where an Hourly minute lives ------
Map hourly = ops('11', 'Periodic Schedule', [whichPeriod: 'Hourly', startingTime: '00:25'])
check(hourly.startingTime == '00:25',
      'an hourly schedule publishes its starting time: absent invites a consumer to assume the top of the hour')
check(!ops('11', 'Periodic Schedule', [whichPeriod: 'Hourly']).containsKey('startingTime'),
      'and absent stays absent rather than becoming a zero minute')

check(!block.contains('httpGet') && !block.contains('httpPost'),
      'the trigger block performs no hub I/O of its own')

println "${passed} HAM decode trigger assertions passed"
