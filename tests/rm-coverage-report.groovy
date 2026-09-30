String source = new File('apps/automation_map.groovy').getText('UTF-8')
String report = source.substring(source.indexOf('Map rmCoverageReport()'), source.indexOf("// The hub's app-to-device relationships"))
String categories = source.substring(source.indexOf('Map haiCategoryRows('), source.indexOf('Map rmCoverageMapping()'))
String varConstructs = source.substring(source.indexOf('List variableConstructsFor('), source.indexOf('// Which capability answers one construct'))
def script = new GroovyShell().parse(report + '\n' + categories + '\n' + varConstructs + '''
Map fetchHaiFeed() { binding.getVariable('feed') }
String haiCapabilityIdFor(String token) { token == 'unmapped' ? null : token }
long now() { 1L }
''')
script.binding.setVariable('feed', [state:'OK', capabilities:[
    [id:'supported', status:'Runs', evidence:[level:'hub'], rm51:'5. Actions: Supported'],
    [id:'scoped', status:'Scoped', rm51:'5. Actions: Scoped'],
    [id:'future', status:'FutureStatus', rm51:'5. Actions: Unknown']]])
script.binding.setVariable('state', [appInfo:[
    '1':[type:'Rule-5.1',label:'Empty'],
    '2':[type:'Rule-5.1',label:'Known',rmConstructs:['supported']],
    '3':[type:'Rule-5.1',label:'Review',rmConstructs:['scoped','future','unmapped','absent']]]])
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
