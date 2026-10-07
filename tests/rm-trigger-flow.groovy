String source = new File('apps/automation_map.groovy').getText('UTF-8')

int flowStart = source.indexOf('String triggerConstructToken(')
int flowEnd = source.indexOf('// Visual Rule Builder 2.0 (appTypeId', flowStart)
int actionStart = source.indexOf('Map actionStep(')
int actionEnd = source.indexOf('String actionLabel(', actionStart)
int constructsStart = source.indexOf('List extractRuleConstructs(')
int constructsEnd = source.indexOf('// Variable constructs for one rule', constructsStart)
int constructEngineStart = source.indexOf('boolean supportsRmConstructExtraction(')
assert flowStart >= 0 && flowEnd > flowStart
assert actionStart >= 0 && actionEnd > actionStart
assert constructsStart >= 0 && constructsEnd > constructsStart
assert constructEngineStart >= 0 && constructEngineStart < constructsStart

String flowSource = source.substring(flowStart, flowEnd)
String actionSource = source.substring(actionStart, actionEnd)
String constructsSource = source.substring(constructEngineStart, constructsEnd)

// buildRuleFlow gates its required step through rulePredicateIsLive(), which
// lives outside every slice above. Sliced in from the real source rather than
// stubbed, so this harness cannot pass against a helper that has drifted.
int predStart = source.indexOf('boolean rulePredicateIsLive(Map st) {')
int predEnd = source.indexOf('List unusedConstraintDeviceIds(Map data) {', predStart)
assert predStart >= 0 && predEnd > predStart
String predSource = source.substring(predStart, predEnd)
// The display helpers buildRuleFlow calls are the app's. They were stubbed to return their input, an
// empty string or an empty list - so a flow step whose label the app would have cleaned, or whose required
// devices it would have listed, read identically to one where it had not.
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String helpers = AppSource.functions(source, ['cleanCondition', 'stripTags', 'buildVisualRuleBuilderFlow',
    'buildNotifierFlow', 'expressionText', 'requiredDevices', 'actionLabel'])
def script = new GroovyShell().parse('''
import groovy.transform.Field
@Field static final String RM_LOCATION_EVENT_PREFIX = 'trigger:Location Event:'
@Field static final Map RULE_LINK_ACTIONS = [:]
''' + AppSource.field(source, 'DEVICELESS_TRIGGERS') + '\n' + predSource + '\n' + flowSource + '\n' + actionSource + '\n' + constructsSource + '''
''' + '\n' + helpers + '\n')

// The slices are the app's, not the identity stubs they replace.
assert script.stripTags('<b>Hall</b>') == 'Hall' : 'stripTags is a stub: the app removes markup'
assert script.cleanCondition('Hall <i>(on)</i>  open') == 'Hall open' : 'cleanCondition is a stub: the app strips markup and asides'

List settings = [
    [name: 'tCapab2', value: 'Switch'],
    [name: 'tDev2', deviceList: ['1': 'Kitchen Switch']],
    [name: 'tCapab3', value: 'Variable'],
    [name: 'xVar3', value: 'TestHubUptime'],
    [name: 'tstate3', value: 'changed'],
    [name: 'tCapab4', value: 'Certain Time'],
    [name: 'tCapab5', value: 'Certain Time (and optional date)'],
    [name: 'tCapab6', value: 'Periodic Schedule'],
    [name: 'tCapab7', value: 'Location Event'],
    [name: 'tstate7', value: 'systemStart'],
    [name: 'tCapab8', value: 'Custom Attribute'],
    [name: 'tDev8', deviceList: ['2': 'Weather Sensor']],
    [name: 'tCustomAttr8', value: 'battery'],
    // A leftover: a device family with no devices (rules 814, 2816, 2865). Not a trigger.
    [name: 'tCapab13', value: 'Switch'],
    [name: 'tCapab9', value: 'Switch'],
    [name: 'tDev9', deviceList: ['3': 'Hall Switch']],
    [name: 'tCapab10', value: 'Contact'],
    [name: 'tDev10', deviceList: ['4': 'Front Door']],
    [name: 'actSubType.1', value: 'getDelay'],
    // Wait-for-Events rows are rule-scoped action data, not triggers.
    [name: 'tCapab-4', value: 'Motion'],
    [name: 'tDev-4', deviceList: ['5': 'Wait Motion']]
]

Map data = [
    appState: [
        [name: 'actionList', value: ['1']],
        [name: 'actions', value: ['1': [action: 'noop']]],
        [name: 'eval', value: [:]],
        [name: 'capabstrue', value: [
            '2': 'Kitchen Switch turns on',
            '5': 'When time is 21:05 on 2026-10-03',
            '6': 'Every 5 minutes',
            '8': 'Weather Sensor battery changes',
            '9': 'Hall Switch turns off',
            '10': 'Front Door opens'
        ]]
    ],
    appSettings: settings
]

List flow = script.buildRuleFlow(data)
List triggers = flow.findAll { it.kind == 'trigger' }

// One step per positive saved row, in numeric order, with no tDev-derived
// duplicate and no negative Wait-for-Events row.
assert triggers.size() == 9
assert triggers*.label == [
    'Kitchen Switch turns on',
    'Variable TestHubUptime changed',
    'Certain Time',
    'When time is 21:05 on 2026-10-03',
    'Every 5 minutes',
    'Location Event: systemStart',
    'Weather Sensor battery changes',
    'Hall Switch turns off',
    'Front Door opens'
]
assert triggers[0].devices == ['Kitchen Switch']
assert triggers[1].devices == []
assert triggers[5].devices == []
assert triggers[6].devices == ['Weather Sensor']
assert triggers[7].devices == ['Hall Switch']
assert triggers[8].devices == ['Front Door']
assert !triggers*.label.contains('Motion')
assert !triggers*.label.contains('Switch') : 'a leftover tCapab with no devices is not shown as a trigger'
assert !triggers.collectMany { it.devices as List }.contains('Wait Motion')
Map action = flow.find { it.kind == 'action' }
assert action.constructs == ['action:getDelay']

// Rendering can fall back to act.method for an old row, but a missing saved
// actSubType must not manufacture a token absent from the rule inventory.
Map unprovenAction = script.actionStep('99', [method: 'getDelay'], [:], [:], [:], [:])
assert !unprovenAction.containsKey('constructs')

List constructs = script.extractRuleConstructs(data)
List triggerConstructs = constructs.findAll { it.startsWith('trigger:') }

assert script.supportsRmConstructExtraction('Rule-5.1')
assert script.supportsRmConstructExtraction('Button Rule-5.1')
assert !script.supportsRmConstructExtraction('Rule Machine')
assert !script.supportsRmConstructExtraction('Visual Rule Builder 2.0')

// The duplicate Switch rows produce two flow steps but one family token.
assert triggerConstructs.size() == 8
assert triggerConstructs.count { it == 'trigger:Switch' } == 1
assert triggerConstructs.containsAll([
    'trigger:Switch',
    'trigger:Variable',
    'trigger:Certain Time',
    'trigger:Certain Time (and optional date)',
    'trigger:Periodic Schedule',
    'trigger:Location Event:systemStart',
    'trigger:Custom Attribute',
    'trigger:Contact'
])
assert !triggerConstructs.contains('trigger:Motion')

println '23 Rule Machine trigger flow and construct assertions passed'
