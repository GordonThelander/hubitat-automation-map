String source = new File('apps/automation_map.groovy').getText('UTF-8')
String report = source.substring(source.indexOf('Map rmCoverageReport()'), source.indexOf("// The hub's app-to-device relationships"))
String categories = source.substring(source.indexOf('Map haiCategoryRows('), source.indexOf('Map rmCoverageMapping()'))
String varConstructs = source.substring(source.indexOf('List variableConstructsFor('), source.indexOf('// Which capability answers one construct'))
// haiCapabilityIdFor is the app's, with the table it reads. Its stub mapped every token to itself, a
// mapping the app never performs, so the tokens below are real Rule Machine constructs and the feed
// carries the HAI ids the app maps them to. fetchHaiFeed stays a stub: it is the HTTP boundary, and its
// signature is the app's.
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String mapper = 'import groovy.transform.Field\n' +
    ['RM_CONSTRUCT_TO_HAI', 'HAI_DEVICE_TRIGGER_ID', 'HAI_DEVICE_CONDITION_ID', 'HAI_LOCATION_EVENT_CAPABILITY',
     'RM_LOCATION_EVENT_PREFIX'].collect { AppSource.field(source, it) }.join('\n') + '\n' +
    AppSource.function(source, 'haiCapabilityIdFor') + '\n'
def script = new GroovyShell().parse(mapper + report + '\n' + categories + '\n' + varConstructs + '''
Map fetchHaiFeed() { binding.getVariable('feed') }
long now() { 1L }
''')
assert AppSource.function(source, 'fetchHaiFeed').readLines()[0] == 'Map fetchHaiFeed() {' :
    'fetchHaiFeed changed signature in the app; update the boundary stub above'
// Supported, scoped, an unknown status, no mapping at all, and mapped to a capability the feed lacks.
String SUPPORTED = 'action:getOnOffSwitch', SCOPED = 'action:getToggleSwitch', FUTURE = 'action:getFlashSwitch'
String UNMAPPED = 'action:notARuleMachineConstruct', ABSENT = 'action:getPerModeSwitch'
assert script.haiCapabilityIdFor(UNMAPPED) == null : 'haiCapabilityIdFor is a stub: the app maps nothing for an unknown action'
assert script.haiCapabilityIdFor(SUPPORTED) == 'action.switches-turn-switches-on-off' : 'the app table has moved under this fixture'
script.binding.setVariable('feed', [state:'OK', capabilities:[
    [id:script.haiCapabilityIdFor(SUPPORTED), status:'Runs', evidence:[level:'hub'], rm51:'5. Actions: Supported'],
    [id:script.haiCapabilityIdFor(SCOPED), status:'Scoped', rm51:'5. Actions: Scoped'],
    [id:script.haiCapabilityIdFor(FUTURE), status:'FutureStatus', rm51:'5. Actions: Unknown']]])
script.binding.setVariable('state', [appInfo:[
    '1':[type:'Rule-5.1',label:'Empty'],
    '2':[type:'Rule-5.1',label:'Known',rmConstructs:[SUPPORTED]],
    '3':[type:'Rule-5.1',label:'Review',rmConstructs:[SCOPED, FUTURE, UNMAPPED, ABSENT]]]])
Map result = script.rmCoverageReport()
assert result.summary.covered == 1
assert result.summary.unassessedRules == 1
assert result.summary.unassessedConstructs == 2
assert result.rules.find { it.id == 'a1' }.covered == false
Map row = result.categories[0]
assert row.usedHere == 3
assert row.usedSupported == 1
assert row.usedUnsupported == 1
assert row.usedUnassessed == 1
assert row.usedHere == row.usedSupported + row.usedUnsupported + row.usedUnassessed
println '9 reporting backend assertions passed'
