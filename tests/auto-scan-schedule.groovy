// Daily scanning is off unless the setting is an explicit true (2.4.37). A schedule made by an earlier version
// survives a code update, because a code update does not run updated(), so the handler itself must check the
// setting: otherwise an install that never saved it keeps scanning every night while its page says it is off.
String source = new File('apps/automation_map.groovy').getText('UTF-8')
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String stubs = '''
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
def script = new GroovyShell().parse(stubs + AppSource.functions(source, ['autoScanEffectivelyEnabled', 'scheduledScanHandler']))

void check(boolean ok, String what) { if (!ok) throw new AssertionError(what) ; println "ok   ${what}" }

[null, false].each { Object saved ->
    List calls = script.runHandler(saved == null ? [:] : [autoScanEnabled: saved])
    check(!calls.any { it.startsWith('startScan') }, "a leftover schedule does not scan when the setting is ${saved}")
    check(calls.contains('unschedule:scheduledScanHandler'), "a leftover schedule removes itself when the setting is ${saved}")
}

List on = script.runHandler([autoScanEnabled: true])
check(on.contains('startScan:scheduled'), 'switched on, the scheduled scan runs')
check(!on.contains('unschedule:scheduledScanHandler'), 'switched on, the schedule is kept')

// --- 2.4.37: off unless an explicit true, everywhere the setting is read -----------------------------------
// The setting was read as "on unless false", so an install that never saved the page scanned every night.
String sched = '''
@groovy.transform.Field Map settings = [:]
@groovy.transform.Field Map state = [:]
@groovy.transform.Field Map app = [label: 'Automation Map', id: 1]
@groovy.transform.Field List calls = []
@groovy.transform.Field Map log = [info: { String s -> }]
void unschedule(String h) { calls << "unschedule:${h}".toString() }
void schedule(String when, String h) { calls << "schedule:${when}:${h}".toString() }
List runSchedule(Map saved) { settings = saved; state = [:]; calls = []; scheduleAutoScan(); return calls }
boolean enabled(Map saved) { settings = saved; return autoScanEffectivelyEnabled() }
'''
def scheduler = new GroovyShell().parse('import groovy.transform.Field\n' + AppSource.field(source, 'BUILD_CHANNEL') + '\n' + sched +
    AppSource.functions(source, ['autoScanEffectivelyEnabled', 'scheduleAutoScan', 'defaultAutoScanCron', 'defaultAutoScanTime',
                                 'isDevBuild', 'diagOn']))

check(scheduler.enabled([:]) == false, 'a setting never saved is off')
check(scheduler.enabled([autoScanEnabled: null]) == false, 'a null setting is off')
check(scheduler.enabled([autoScanEnabled: false]) == false, 'false is off')
check(scheduler.enabled([autoScanEnabled: 'true']) == false, 'only the boolean true is on, not text that reads true')
check(scheduler.enabled([autoScanEnabled: true]) == true, 'an explicit true is on')

List unsaved = scheduler.runSchedule([:])
check(unsaved == ['unschedule:scheduledScanHandler'], "an install that never saved the setting schedules nothing (${unsaved})")
check(scheduler.runSchedule([autoScanEnabled: false, autoScanTime: '2026-10-13T02:15:00.000+0000']) == ['unschedule:scheduledScanHandler'],
      'switched off, a saved time schedules nothing')
List onDefault = scheduler.runSchedule([autoScanEnabled: true])
check(onDefault == ['unschedule:scheduledScanHandler', 'schedule:0 00 01 * * ?:scheduledScanHandler'],
      "switched on with no time, the default time is scheduled (${onDefault})")
List onTimed = scheduler.runSchedule([autoScanEnabled: true, autoScanTime: '2026-10-13T02:15:00.000+0000'])
check(onTimed == ['unschedule:scheduledScanHandler', 'schedule:2026-10-13T02:15:00.000+0000:scheduledScanHandler'],
      'switched on with a time, that time is scheduled')

// The page's toggle shows the same default the code applies.
int toggle = source.indexOf("input name: 'autoScanEnabled', type: 'bool',")
String toggleDecl = toggle < 0 ? '' : source.substring(toggle, source.indexOf('submitOnChange', toggle))
check(toggleDecl.contains('defaultValue: false'), 'the settings toggle shows off by default')
