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
                                 'hamDetailModeNames', 'hamDetailDeviceRefs',
                                 // Wait for Events reuses this resolver against a dash namespace.
                                 'hamDetailWaitTime', 'hamDetailWaitEventsOperands']) + '\n'
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

// --- rule 814: a leftover tCapab is not a trigger, even beside a condition --
// 814 stores tCapab27='Presence' with no tDev27, and condition 27's settings
// (rDev_27, state_27) beside it. Rule Machine shows one trigger, at 20:00;
// trigDevs is empty. Reading the condition namespace published a phantom
// Presence trigger that would fire whenever a repeater dropped (Claude HAM).
List r814 = script.extractRuleTriggers([appSettings: [
    [name: 'tCapab27', value: 'Presence'],
    [name: 'rCapab_27', value: 'Presence'],
    [name: 'rDev_27', value: null, deviceList: ['665': 'Lounge Repeater']],
    [name: 'state_27', value: 'not present'],
    [name: 'isCondTrig.27', value: 'true'],
    [name: 'condTrig.27', value: '27'],
    [name: 'tCapab30', value: 'Certain Time (and optional date)'],
    [name: 'time30', value: '20:00']
]])
check(r814*.index == ['30'], "rule 814 publishes its one real trigger, not the leftover beside a condition (${r814*.index})".toString())

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

// Conditional triggers, as rule 3448 stores them: isCondTrig.<n> and
// condTrig.<n> with a dot, beside a tCapab<n> without one. Trigger 6 fires
// only while condition 5 holds; trigger 9 is flagged false and is ordinary.
List conditional = script.extractRuleTriggers([appSettings: [
    [name: 'tCapab6', value: 'Periodic Schedule'], [name: 'whichPeriod6', value: 'Hours'],
    [name: 'isCondTrig.6', value: 'true'], [name: 'condTrig.6', value: '5'],
    [name: 'tCapab9', value: 'Periodic Schedule'], [name: 'whichPeriod9', value: 'Hours'],
    [name: 'isCondTrig.9', value: 'false'], [name: 'condTrig.9', value: '7'],
    [name: 'tCapab11', value: 'Periodic Schedule'], [name: 'whichPeriod11', value: 'Hours'],
    [name: 'isCondTrig11', value: 'true']
]])
check(conditional[0].operands.condition == '5', 'a conditional trigger names the condition it is gated on')
check(!conditional[1].operands.containsKey('condition'), 'a trigger flagged false is not conditional, whatever condTrig holds')
check(conditional[2].operands.conditionMissing == true && !conditional[2].operands.containsKey('condition'),
      'flagged with no condition number is published as missing, never as unconditional')

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
// Rule 1230 stores the older picker label 'Certain Time'. It is the same trigger, and was published as no
// trigger at all: the short spelling read as a device family with no devices.
Map shortLabel = ops('7', 'Certain Time', [time: 'A specific time', atTime: '21:05'])
check(shortLabel.at != null, "the older label 'Certain Time' resolves its time like the long one (\${shortLabel})")
List r1230 = script.extractRuleTriggers([appSettings: [[name: 'tCapab1', value: 'Certain Time'],
                                                       [name: 'time1', value: 'A specific time'], [name: 'atTime1', value: '21:05']]])
check(r1230*.index == ['1'], "rule 1230's Certain Time trigger is published, not dropped as a leftover")
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

// --- Wait for Events: the trigger shape in a rule-global dash namespace (contract 2) -----------------
// Measured by Claude HAM on the Dev hub, 2026-10-07 (HAM cloud/more-rm-actions 6c0616ad): the events live
// at tCapab-1, tDev-1, tstate-1, stays-1 and modesX-1 rather than under the action, which is why Rule Machine
// allows one Wait for Events per rule. A Mode event is modesX-<n>, the dash form of the trigger's modesX<n>.
Map wv = ['tCapab-1': 'Motion', 'tstate-1': 'active', 'durChoice.7': 'true',
          'tCapab-3': 'Mode', 'modesX-3': '["5"]',
          'tCapab-2': 'Switch',                     // a leftover: a device family with no device
          'tCapab1': 'Contact']                     // a TRIGGER (no dash), never an event
Map wd = ['tDev-1': [[id: '2386', name: 'Hallway Motion']]]
Map we = script.hamDetailWaitEventsOperands('7', [delay: '0:02:00'], wv, wd)
check(we.type == 'waitEvents' && we.useDuration == true && we.waitSeconds == 120,
      'Wait for Events carries its time and what it means, as Wait for Expression does')
check((we.events as List).collect { it.index } == ['-1', '-3'],
      'events come from the dash namespace in order; a no-dash trigger and a device-less leftover are not events')
Map ev1 = (we.events as List)[0] as Map
check(ev1.capability == 'Motion' && (ev1.devices as List) == [[id: '2386', name: 'Hallway Motion']] && ev1.value == 'active',
      'a device event resolves through the trigger resolver: devices as {id, name} and the awaited value')
Map ev3 = (we.events as List)[1] as Map
check(ev3.capability == 'Mode' && "${ev3.modes ?: ev3.value ?: ''}".contains('Home'),
      'a Mode event reads modesX-<n>, not the trigger spelling, so it is not published empty: ' + ev3)
check(script.hamDetailWaitEventsOperands('7', [:], [:], [:]) == [type: 'waitEvents', useDuration: false, events: []],
      'no events and no time is an empty wait, stated, not a failure')

check(!(block =~ /\bhttp(Get|Post|Put|Delete)\s*\(/).find(),
      'the trigger block performs no hub I/O of its own')

println "${passed} HAM decode trigger assertions passed"
