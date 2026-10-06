// The action half of the HAI decode contract: the six constructs that are half
// of every action on this hub, resolved so a rebuild can bind to them.
//
// Every shape here was measured against this hub before it was written, and
// four of the proposed shapes turned out to be wrong. Those four are the
// assertions that matter; the rest is bookkeeping.
String source = new File('apps/automation_map.groovy').getText('UTF-8')

int start = source.indexOf('Map hamDetailDuration(')
int end = source.indexOf('// --- HAI decode detail: end ---')
assert start >= 0 : 'action resolver not found'
assert end > start : 'action resolver not terminated'
String block = source.substring(start, end)

// The helpers the resolver calls, sliced from the app rather than stubbed. The stubs these replace
// disagreed with the app: hamDetailInt returned null where the app returns 0, hamDetailBool was
// case-sensitive where the app is not, and hamDetailJsonList returned [] for text the app keeps as [s].
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String stubs = 'import groovy.transform.Field\n' +
    AppSource.functions(source, ['stripTags', 'hamDetailInt', 'hamDetailBool', 'hamDetailJsonList']) + '\n'
def script = new GroovyShell().parse(stubs + block + '\nvoid noop() { }\n')

// The slices are the app's, not a stub's: each returns what only the real one returns.
assert script.hamDetailInt('') == 0 : 'hamDetailInt is a stub: the app returns 0 for an empty value'
assert script.hamDetailBool('TRUE') == true : 'hamDetailBool is a stub: the app ignores case'
assert script.hamDetailJsonList('not json') == ['not json'] : 'hamDetailJsonList is a stub: the app keeps unparseable text'

int passed = 0
Closure check = { boolean cond, String what ->
    if (cond) { passed++; println "PASS  ${what}" }
    else { println "FAIL  ${what}"; System.exit(1) }
}

// Rule Machine keys every action setting as name.<number>, so the harness
// suffixes them rather than letting a bare key silently miss every lookup and
// make the resolver look like it returns defaults.
Closure V = { Map m -> m.collectEntries { k, v -> [(k as String), v == null ? null : "${v}"] } }
Closure S = { String num, Map m -> m.collectEntries { k, v -> ["${k}.${num}".toString(), v == null ? null : "${v}"] } }
Closure ops = { String num, String method, Map v, Map d = [:] ->
    return script.hamDetailActionOperands(num, method, S(num, v), d)
}

// --- getSetPrivateBoolean: the inversion, measured on three rules ---------
// Rules 2325, 2031 and 2283 all render their first Set Private Boolean as
// False and their last as True. The first stores pvTF 'true'; the last stores
// nothing. Read the obvious way round, every migrated rule sets every Private
// Boolean backwards.
Map pbFalse = ops('7', 'getSetPrivateBoolean', [privateT: '["*"]', pvTF: 'true'])
check(pbFalse.value == false, 'pvTF true stores False, the opposite of how it reads')
Map pbTrue = ops('8', 'getSetPrivateBoolean', [privateT: '["*"]', pvTF: ''])
check(pbTrue.value == true, 'an absent pvTF stores True')
check(pbTrue.self == true, 'a star target means the rule own Private Boolean')
check(!pbTrue.containsKey('rules'), 'and carries no cross-rule target')

Map pbBoth = ops('9', 'getSetPrivateBoolean', [privateT: '["*","1809"]', pvTF: ''])
check(pbBoth.self == true && (pbBoth.rules as List) == ['1809'],
      'a step can set its own boolean and another rule in the same action')
Map pbOther = ops('9', 'getSetPrivateBoolean', [privateT: '["2351"]', pvTF: 'true'])
check(pbOther.self == false && (pbOther.rules as List) == ['2351'],
      'a cross-rule write names the target rule by id')

// --- getMsg: not one kind -------------------------------------------------
// Rule 2112 renders: Notify Mobile Proxy and Speak on Security Speaker.
Map msg = ops('5', 'getMsg', [msg: 'Standing down.', speakVolume: '30'],
    ['note.5': [[id: '101', name: 'Mobile Proxy']],
     'speakDevice.5': [[id: '202', name: 'Security Speaker']]])
check(msg.text == 'Standing down.', 'the message text travels')
check(((msg.notify as List)[0] as Map).id == '101', 'notify devices carry ids')
check(((msg.speak as List)[0] as Map).id == '202', 'speak devices carry ids')
check(msg.volume == 30, 'the speak volume travels as a number')
check(!msg.containsKey('kind'),
      'no single kind is emitted: one step can both notify and speak')

Map notifyOnly = ops('6', 'getMsg', [msg: 'Hello'], ['note.6': [[id: '101', name: 'Mobile Proxy']]])
check(!notifyOnly.containsKey('speak'), 'a notify-only step carries no speak list')
check(!notifyOnly.containsKey('volume'), 'no volume is invented when none is stored')

// --- getLogMsg: no level is stored ----------------------------------------
Map log = ops('3', 'getLogMsg', [logmsg: 'gauntlet start'])
check(log.text == 'gauntlet start', 'the log text travels')
check(!log.containsKey('level'),
      'no level is emitted: all 23 uses on this hub store only the message')

// --- getOnOffSwitch -------------------------------------------------------
Map on = ops('1', 'getOnOffSwitch', [onOff: 'true'],
    ['onOffSwitch.1': [[id: '2450', name: 'Fireplace']]])
check(on.command == 'on', 'onOff true is the on command')
check(((on.devices as List)[0] as Map).id == '2450', 'and the device travels as an id')
check(ops('3', 'getOnOffSwitch', [onOff: 'false']).command == 'off', 'onOff false is off')
check(!ops('3', 'getOnOffSwitch', [onOff: 'false']).containsKey('devices'),
      'a switch action with no devices omits the key rather than sending an empty list')

// --- getDelay: three fields, and sometimes no number at all ---------------
Map d1 = ops('2', 'getDelay', [delayMinute: '30'])
check(d1.seconds == 1800, 'minutes convert to seconds')
Map d2 = ops('2', 'getDelay', [delayHour: '1', delayMinute: '5', delaySecond: '4'])
check(d2.seconds == 3904, 'hours, minutes and seconds combine')
Map d3 = ops('2', 'getDelay', [delaySecond: '2', cancelAct: 'true', randomAct: 'true'])
check(d3.seconds == 2 && d3.cancelable == true && d3.random == true,
      'cancelable and random travel as booleans beside the duration')
Map d4 = ops('2', 'getDelay', [xVar: 'AMShow_LocalCount', uVar: 'true'])
check(d4.seconds == null && d4.variable == 'AMShow_LocalCount',
      'a delay driven by a variable has no number: seconds stays null, never zero')

// --- an action can carry its own delay, separately from a Delay step ------
Map ad = script.hamDetailActionDelay('3', S('3', [delayAct: 'hrs:min:sec', delaySec: '4', cancelAct: 'true']))
check(ad != null && ad.seconds == 4 && ad.cancelable == true,
      'an action delay is read from delayAct, rendered by RM as a delayed suffix')
check(script.hamDetailActionDelay('3', S('3', [delayAct: 'none'])) == null,
      'delayAct none is no delay, not a zero-second one')

// --- everything else refuses by name -------------------------------------
// --- Capture, Restore and Set Colour, as rule 3593 stores them --------------
// Read off rule 3593's status page on 2026-10-06: capture.1 holds the devices
// (capability.switch), Restore stores nothing under its own index, and Set
// Colour keeps the device in bulbs.2 with the picker's mode in color.2.
Map desk = [id: '3601', name: 'Gordon Study Desk']
Map cap = ops('1', 'getCapture', [:], ['capture.1': [desk]])
check(cap != null && cap.type == 'capture' && (cap.devices as List) == [desk], 'capture publishes the devices in capture.<n>')
Map res = ops('4', 'getRestore', [:])
check(res == [type: 'restore'], 'restore publishes no devices: it puts back what the rule captured')

Map green = ops('2', 'getSetColor', [color: 'Green', colorLevel: '100'], ['bulbs.2': [desk]])
check(green.type == 'setColor' && green.colorMode == 'Green', 'set colour carries the stored colour mode')
check(green.hue == 33 && green.saturation == 100, 'a named colour resolves to what Rule Machine sends for it, measured: Green is 33/100')
check(green.level == 100 && (green.devices as List) == [desk], 'with its level and its devices from bulbs.<n>')
Map soft = ops('2', 'getSetColor', [color: 'Soft White'], ['bulbs.2': [desk]])
check(soft.hue == 11 && soft.saturation == 30 && !soft.containsKey('level'),
      'Soft White is hue 11 saturation 30, and no stored level means no level, not zero')
Map hsb = ops('2', 'getSetColor', [color: 'Custom HSB color', colorHex: '62', colorSat: '80', colorLevel: '40'])
check(hsb.hue == 62 && hsb.saturation == 80 && hsb.level == 40,
      'custom HSB reads its hue from colorHex, the key named the opposite way round')
Map pick = ops('2', 'getSetColor', [color: 'Pick a Color', colorH: '#33cc66'])
check(pick.colorMode == 'Pick a Color' && !pick.containsKey('hue'),
      'a mode not resolved here travels as the mode alone, to be refused by name')
Map byVar = ops('2', 'getSetColor', [color: 'Custom HSB color', uVar: 'true', colorHex: '10', colorSat: '20'])
check((byVar.variableSourced as List) == ['level'], 'a variable-sourced field is named, so it is never taken as a fixed value')

check(ops('4', 'getSetColorTemp', [:]) == null, 'an unsupported construct resolves to nothing')
check(ops('4', 'getWaitEvents', [:]) == null, 'including the ones with a measured shape waiting')

check(!block.contains('httpGet') && !block.contains('httpPost'),
      'the action block performs no hub I/O of its own')
check(!block.contains("'toggle'"),
      'no toggle command is invented: this family does not store one on this hub')

// --- the whole extractor, where the real trap was --------------------------
// The operand resolvers above all passed while disabled actions were silently
// never marked, because the disabled list was built as GStrings and a GString
// never equals the String it is compared against. Only a test that runs the
// extractor end to end sees that.
Map data = [
  appSettings: [[name: 'actSubType.7', value: 'getOnOffSwitch'],
                [name: 'onOff.7', value: 'true'],
                [name: 'actSubType.8', value: 'getLogMsg'],
                [name: 'logmsg.8', value: 'disabled step'],
                [name: 'actSubType.9', value: 'getSetColorTemp']],
  appState: [[name: 'actionList', value: ['7', '8', '9']],
             [name: 'disabledActions', value: ['8']]]
]
List steps = script.extractRuleActions(data)
check(steps.size() == 3, 'one record per action in actionList')
check(steps.collect { it.index } == ['7', '8', '9'], 'emitted in actionList order, which is the stored order')
check(steps[0].disabled == null, 'a live action is not marked disabled')
check(steps[1].disabled == true,
      'a disabled action IS marked: Rule Machine keeps it in actionList and renders it Disabled')
check(steps[1].supported == true && steps[1].type == 'log',
      'and is still resolved, so a consumer can see what it would have done')
check(steps[2].supported == false && steps[2].method == 'getSetColorTemp',
      'an unsupported construct carries its method name to be refused by')

// A branch carries the eval group its condition lives in, which keys the
// expressions map already published, so control flow joins to its conditions
// without a second decoder. Measured on rule 3577: getIfThen 4 -> group 1,
// the nested getIfThen 19 -> group 3, getElseIf 8 -> group 2.
Map branchy = [
  appSettings: [[name: 'actSubType.4', value: 'getIfThen'],
                [name: 'actSubType.8', value: 'getElseIf'],
                [name: 'actSubType.13', value: 'getEndIf'],
                [name: 'actSubType.1', value: 'getLogMsg'],
                [name: 'logmsg.1', value: 'x']],
  appState: [[name: 'actionList', value: ['4', '8', '13', '1']],
             [name: 'actions', value: ['4': [rule: 1], '8': [rule: 2], '13': [:], '1': [:]]]]
]
List bs = script.extractRuleActions(branchy)
check(bs[0].expressionGroup == '1', 'an IF carries the eval group its condition lives in')
check(bs[1].expressionGroup == '2', 'an ELSE-IF carries its own group, not the IF one')
check(!bs[2].containsKey('expressionGroup'), 'an END IF has no condition and carries no group')
check(!bs[3].containsKey('expressionGroup'), 'an ordinary action carries no group')

// A wait's duration is not a delay before the action. Rule 2279 action 6
// stores delayAct 'hrs:min:sec' with no delaySec, and the real 0:05:00 lives
// in the compiled actions entry. Emitted as `delay`, a consumer that turns
// per-action delays into waits makes a rebuilt rule wait twice.
Map waity = [
  appSettings: [[name: 'actSubType.6', value: 'getWaitRule'],
                [name: 'delayAct.6', value: 'hrs:min:sec'],
                [name: 'actSubType.7', value: 'getOnOffSwitch'],
                [name: 'onOff.7', value: 'true'],
                [name: 'delayAct.7', value: 'hrs:min:sec'],
                [name: 'delaySec.7', value: '4']],
  appState: [[name: 'actionList', value: ['6', '7']]]
]
List ws = script.extractRuleActions(waity)
check(!ws[0].containsKey('delay'),
      'a wait carries no delay: its delayAct describes the wait, not a pause before it')

// Control flow was claimed done because the display steps carry `ctrl`. The
// actions array is what a rebuild reads, and there every marker was
// supported:false - 83 records across 25 rules refusing on a structure this
// app had already decoded. Claiming it done and publishing it are not the
// same thing.
check((ops('4', 'getIfThen', [:]) as Map).branch == 'if', 'an IF resolves as a branch')
check((ops('8', 'getElseIf', [:]) as Map).branch == 'elseif', 'an ELSE-IF is distinct from an IF')
check((ops('11', 'getElse', [:]) as Map).branch == 'else', 'an ELSE resolves')
check((ops('13', 'getEndIf', [:]) as Map).branch == 'endif', 'an END IF resolves')
check((ops('4', 'getIfThen', [:]) as Map).type == 'branch', 'all four share one type')

// --- getDefinedAction: a custom command with ordered parameters -----------
// Rule 1230 renders breathe('blue', 'Yellow', '20', '90', '5'). The values
// live in cpVal<i> with their type in cpType<i>, and i is neither contiguous
// nor 1-based across this hub: 1 to 9 with 8 absent.
Map da = ops('29', 'getDefinedAction',
    [cCmd: 'breathe', myCapab: 'Actuator',
     cpVal2: 'blue', cpType2: 'string', cpVal3: 'Yellow', cpType3: 'string',
     cpVal4: '20', cpType4: 'string', cpVal6: '5', cpType6: 'string'],
    ['devices.29': [[id: '3002', name: 'Entrance Hall Light 1']]])
check(da.command == 'breathe', 'the command name travels, where the label said only Run defined actions')
check(da.capability == 'Actuator', 'the capability it belongs to travels')
check(((da.devices as List)[0] as Map).id == '3002', 'the target device is an id')
check((da.parameters as List).size() == 4, 'every stored parameter travels')
check((da.parameters as List)*.value == ['blue', 'Yellow', '20', '5'],
      'parameters are ordered by index, and a gap does not shift the order')
check(((da.parameters as List)[2] as Map).value == '20',
      'a numeric-looking value stays a string: its declared type says string and coercing changes the call')
check(((da.parameters as List)[3] as Map).index == 6,
      'the stored index travels, because the sequence is not contiguous')

Map noParams = ops('9', 'getDefinedAction', [cCmd: 'applyDefault', myCapab: 'Actuator'])
check(!noParams.containsKey('parameters'), 'a command with no parameters sends none rather than an empty list')
check(!noParams.containsKey('devices'), 'and no devices key when it targets none')

// --- getSetVariable: three forms sharing no fields -----------------------
// xVarV names the variable set; the value shape depends on its type.
Map vb = ops('6', 'getSetVariable', [xVarV: 'Front Walkway Limiter', valBool: 'true'])
check(vb.name == 'Front Walkway Limiter', 'the variable being set is named')
check((vb.value as Map).kind == 'boolean' && (vb.value as Map).value == true,
      'a boolean variable resolves to a real boolean')

Map vn = ops('19', 'getSetVariable', [xVarV: 'Overloadcount', numOp: 'number', valNumber: '0'])
check((vn.value as Map).kind == 'number' && (vn.value as Map).value == '0',
      'a number assignment carries its literal')
Map va = ops('17', 'getSetVariable', [xVarV: 'Overloadcount', numOp: 'add number', valNumber: '1'])
check((va.value as Map).kind == 'addNumber',
      'adding to a variable is NOT an assignment: the distinction is kept')

// counter = counter + 1, which RM renders "Set counter to (counter + 1)".
Map vm = ops('2', 'getSetVariable',
    [xVarV: 'counter', numOp: 'variable math', xVar3: 'counter',
     valMathOp: '+', xVar4: '(constant)', valConst2: '1'])
Map mv = vm.value as Map
check(mv.kind == 'math' && mv.left == 'counter' && mv.operator == '+',
      'variable math resolves its left operand and operator')
check((mv.right as Map).constant == '1',
      'and a constant right operand is a constant, not a variable called "(constant)"')
Map vm2 = ops('2', 'getSetVariable',
    [xVarV: 'counter', numOp: 'variable math', xVar3: 'counter',
     valMathOp: '+', xVar4: 'TestNumber'])
check(((vm2.value as Map).right as Map).variable == 'TestNumber',
      'a variable right operand is named as a variable')

Map vs = ops('3', 'getSetVariable', [xVarV: 'outcome', valStringOp: 'Set string', valString: 'Working'])
check((vs.value as Map).kind == 'string' && (vs.value as Map).value == 'Working',
      'a string assignment carries its text')

Map vd = ops('2', 'getSetVariable',
    [xVarV: 'TestHubUptime.', valStringOp: 'Device attribute', tCustomAttr: 'formattedUptime'],
    ['customDev.2': [[id: '3574', name: 'Hub Information Driver']]])
Map dv = vd.value as Map
check(dv.kind == 'deviceAttribute' && dv.attribute == 'formattedUptime',
      'a variable set from a device attribute names the attribute')
check(((dv.devices as List)[0] as Map).id == '3574', 'and the source device as an id')

Map lastDev = ops('9', 'getDefinedAction', [cCmd: 'flashOff', myCapab: 'Switch', useLastDev: 'true'])
check(lastDev.useLastDevice == true,
      'targeting the triggering device is stated: there is no device to bind')
check(ws[1].delay?.seconds == 4,
      'an ordinary action still carries its own delay')

println "${passed} HAM decode action assertions passed"
