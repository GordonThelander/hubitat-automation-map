// Daily scanning is off unless the setting is an explicit true (2.4.37). A schedule made by an earlier version
// survives a code update, because a code update does not run updated(), so the handler itself must check the
// setting: otherwise an install that never saved it keeps scanning every night while its page says it is off.
String source = new File('apps/automation_map.groovy').getText('UTF-8')
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String stubs = '''
import groovy.transform.Field
@groovy.transform.Field Map settings = [:]
@groovy.transform.Field Map state = [:]
@groovy.transform.Field Map app = [label: 'Automation Map', id: 1]
@groovy.transform.Field List calls = []
@groovy.transform.Field Map log = [info: { String s -> }]
void ensureHaiAssessSubscription() { calls << 'subscribe' }
boolean diagOn() { false }
void unschedule(String h) { calls << "unschedule:${h}".toString() }
Map startScan(String entry) { calls << "startScan:${entry}".toString(); return [acquired: true] }
// Script properties set from outside land in the binding, not in these fields, so the test goes through these.
List runHandler(Map saved) { settings = saved; state = [:]; calls = []; scheduledScanHandler(); return calls }
'''
def script = new GroovyShell().parse(stubs + AppSource.field(source, 'BUILD_CHANNEL') + '\n' +
    AppSource.functions(source, ['autoScanEffectivelyEnabled', 'isDevBuild', 'scheduledScanHandler']))

void check(boolean ok, String what) { if (!ok) throw new AssertionError(what) ; println "ok   ${what}" }

[null, false].each { Object saved ->
    List calls = script.runHandler(saved == null ? [:] : [autoScanEnabled: saved])
    check(!calls.any { it.startsWith('startScan') }, "a leftover schedule does not scan when the setting is ${saved}")
    check(calls.contains('unschedule:scheduledScanHandler'), "a leftover schedule removes itself when the setting is ${saved}")
}

List on = script.runHandler([autoScanEnabled: true])
check(on.contains('startScan:scheduled'), 'switched on, the scheduled scan runs')
check(!on.contains('unschedule:scheduledScanHandler'), 'switched on, the schedule is kept')
