String source = new File('apps/automation_map.groovy').getText('UTF-8')

int mappingStart = source.indexOf('@Field static final Map RM_CONSTRUCT_TO_HAI = [')
int mappingEnd = source.indexOf('@Field static final String HAI_DEVICE_TRIGGER_ID', mappingStart)
int definitionsStart = source.indexOf('@Field static final Map<String, String> RM_CONSTRUCT_DEFINITIONS = [')
int definitionsEnd = source.indexOf('// The Rule Machine constructs one rule actually uses', definitionsStart)
assert mappingStart >= 0 && mappingEnd > mappingStart
assert definitionsStart >= 0 && definitionsEnd > definitionsStart

String mappingSource = source.substring(mappingStart, mappingEnd)
String definitionsSource = source.substring(definitionsStart, definitionsEnd)
def script = new GroovyShell().parse('''
import groovy.transform.Field
''' + mappingSource + '\n' + definitionsSource + '''
Map mappings() { RM_CONSTRUCT_TO_HAI }
Map definitions() { RM_CONSTRUCT_DEFINITIONS }
''')

Map mappings = script.mappings()
Map definitions = script.definitions()
assert definitions.size() == 35
assert definitions.keySet().count { it.startsWith('action:') } == 32
assert definitions.keySet().count { it.startsWith('structure:') } == 2
assert definitions.keySet().count { it.startsWith('option:') } == 1
assert definitions.keySet().every { mappings[it] }
assert definitions.values().every { "${it}".trim().endsWith('.') }
assert definitions['structure:actionDelay'].contains('delayed action')
assert definitions['structure:conditionalTrigger'].contains('conditional trigger')
assert definitions['option:displayCurrentValues'].contains('display current values')
assert !definitions.keySet().any { it.startsWith('trigger:') || it.startsWith('condition:') }

// Export wiring and the fixed-family caveat are part of the contract, not
// merely an in-memory dictionary that never reaches the downloaded file.
assert source.contains('const RM_CONSTRUCT_VOCABULARY = ${rmConstructVocabularyJsonStr};')
assert source.contains('rmConstructVocabulary: RM_CONSTRUCT_VOCABULARY')
assert source.contains("appMap.containsKey('rmConstructs')")
assert source.contains('if (Array.isArray(n.rmConstructs)) out.rmConstructs = n.rmConstructs.slice().sort();')
assert source.contains('setting family this release does not recognise')
assert source.contains('Conditions are not step-associated')

println '18 Rule Machine construct vocabulary assertions passed'
