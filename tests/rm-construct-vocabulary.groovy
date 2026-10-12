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
assert source.contains("const RM_CONSTRUCT_VOCABULARY = \${mapSlot('rmConstructVocabularyJsonStr')};")
assert source.contains('rmConstructVocabulary: RM_CONSTRUCT_VOCABULARY')
assert source.contains("appMap.containsKey('rmConstructs')")
assert source.contains('if (Array.isArray(n.rmConstructs)) out.rmConstructs = n.rmConstructs.slice().sort();')
assert source.contains('setting family this release does not recognise')
assert source.contains('Conditions are not step-associated')

// Every action subtype Rule Machine was measured storing on the hub (HAI #85, 2026-10-10/11, listed in the
// RM action storage reference) must map to a capability. Five map keys were names RM never stores
// (getArmHSM, getLockUnlock, ...), so rules using HSM, locks and others went uncounted; this catches a
// missing or misspelt subtype name before it ships.
List measuredSubtypes = [
    'getActivateScene', 'getActivateScenePerMode', 'getAdjustDimmer', 'getAdjustFan', 'getAppendLocalFile', 'getCancelDelay',
    'getCapture', 'getChime', 'getChooseButton', 'getChooseSwitch', 'getColorPerMode', 'getColorTempPerMode',
    'getComment', 'getCondAct', 'getDefinedAction', 'getDelay', 'getDelayPerMode', 'getDeleteLocalFile',
    'getDimmersPerMode', 'getDisable', 'getElse', 'getElseIf', 'getEndIf', 'getEndRepeat',
    'getExitRule', 'getFadeCT', 'getFadeDimmer', 'getFanSpeed', 'getFlashSwitch', 'getHTTPGet',
    'getHTTPPost', 'getIfThen', 'getLULock', 'getLogMsg', 'getModeSwitch', 'getMsg',
    'getMuteUnmute', 'getOCGarage', 'getOCValve', 'getOnOffSwitch', 'getPauseResumeRules', 'getPingIP',
    'getPollSwitch', 'getPushButton', 'getPushButtonPerMode', 'getRLDimmer', 'getRLShade', 'getRefreshSwitch',
    'getRepeat', 'getRestore', 'getRuleActions', 'getSetColor', 'getSetColorTemp', 'getSetDimmer',
    'getSetHSM', 'getSetMode', 'getSetMusicPlayer', 'getSetPrivateBoolean', 'getSetThermostat', 'getSetVariable',
    'getSetVolume', 'getShadePosition', 'getSiren', 'getStartStopZPoll', 'getStopActions', 'getStopCTFade',
    'getStopDimmer', 'getStopFade', 'getStopRepeat', 'getStopShade', 'getToggleColor', 'getToggleColorTemp',
    'getToggleDimmer', 'getToggleSwitch', 'getTone', 'getUntil', 'getWaitEvents', 'getWaitRule',
    'getWhile', 'getWriteLocalFile'
]
List unmapped = measuredSubtypes.findAll { !mappings["action:${it}"] }
assert unmapped.isEmpty() : "measured Rule Machine subtypes with no capability mapping: ${unmapped}"

println '19 Rule Machine construct vocabulary assertions passed'
