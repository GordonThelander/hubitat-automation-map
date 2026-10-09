// Visual Rule Builder 2.0's repeated action (Hubitat 2.5.2.135): the flowchart words it as the builder's card
// does, and a device named only in its stop condition is a constraint, not a trigger. The node shape is the
// one measured on the hub on 2026-10-09 (HAI #34); device names here are invented.
String source = new File('apps/automation_map.groovy').getText('UTF-8')
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String fns = AppSource.functions(source, ['buildVisualRuleBuilderFlow', 'prettyMethod', 'vrbGraphDeviceRoles', 'addRole'])
def script = new GroovyShell(new Binding([state: [deviceLabels: ['11': 'Hall Motion', '12': 'Landing Motion', '13': 'Hall Light']]])).parse(fns + '\n')

Map graph = [version: 1, nodes: [
    [id: 'trigger-1', kind: 'trigger', type: 'motion', config: [motionSensors: [11], motionEvent: 'Motion becomes active']],
    [id: 'trigger-merge', kind: 'merge', type: 'merge', config: [:]],
    [id: 'decision', kind: 'decision', type: 'all', config: [conditions: []]],
    [id: 'repeatAction', kind: 'action', type: 'repeatAction', config: [
        action: [type: 'turnOn', config: [switches: [13]]], hours: 0, minutes: 2, seconds: 5,
        stopWhen: [type: 'all', conditions: [[id: 'stop-condition-1', type: 'motionCondition',
                                              config: [motionSensors: [12], motionSensorState: 'Motion is active']]]]]],
    [id: 'join', kind: 'merge', type: 'merge', config: [:]]],
  edges: [[from: 'trigger-1', to: 'trigger-merge', port: 'next'], [from: 'trigger-merge', to: 'decision', port: 'next'],
          [from: 'decision', to: 'repeatAction', port: 'true'], [from: 'decision', to: 'join', port: 'false'],
          [from: 'repeatAction', to: 'join', port: 'next']]]

List steps = script.buildVisualRuleBuilderFlow([graphDocument: graph])
Map repeat = steps.find { it.label?.startsWith('Repeat') }
assert repeat != null : "no repeat step drawn: ${steps}"
assert repeat.label == 'Repeat On Hall Light, every 2m 5s, until Motion is active on Landing Motion' : repeat.label
assert repeat.devices.contains('Hall Light') : 'the repeated action names no device'
println 'ok   repeat step reads like the builder card'

Map roles = script.vrbGraphDeviceRoles([appState: [[name: 'graphDocument', value: graph]]])
assert roles.triggers == ['11'] as Set && roles.conditions == ['12'] as Set : roles
assert script.vrbGraphDeviceRoles([appState: []]) == null : 'a non-VRB app was given VRB roles'
println 'ok   stop-condition device is a condition, the trigger device stays a trigger'

// The scan applies it: the subscription made 12 a trigger, and the graph corrects it.
int at = source.indexOf('Map vrbDevices = vrbGraphDeviceRoles(data)')
assert at > 0 && source.indexOf("addRole(roles, devId, 'constraint')", at) in (at..(at + 800)) : 'the scan does not apply the VRB roles'
println 'ok   scan applies the graph roles'
