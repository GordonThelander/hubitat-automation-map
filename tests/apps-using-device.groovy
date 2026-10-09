// HAI #69: apps the hub's app list leaves out (Easy Mobile Dashboard instances, measured 2026-10-09) are found
// through getAppsUsingDevice, bounded in time, and skipped on a hub without the call. Ids here are invented.
String source = new File('apps/automation_map.groovy').getText('UTF-8')
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String fn = AppSource.functions(source, ['appsUsingDevicesNotListed'])
String field = AppSource.field(source, 'APPS_USING_DEVICE_BUDGET_MS')

Closure make = { Closure users, Closure clock ->
    def s = new GroovyShell().parse('import groovy.transform.Field\n' + field + '\n' + fn + '\n')
    s.metaClass.getAppsUsingDevice = users
    s.metaClass.now = clock
    s
}
Map byDevice = [10L: [[id: 1], [id: 900, label: 'Dash A']], 11L: [[id: 2], [id: 900], [id: 901]], 12L: []]
def s = make({ Long d -> byDevice[d] }, { -> 0L })
Map r = s.appsUsingDevicesNotListed(['10', '11', '12', 'x'], ['1', '2'])
assert r.ids == ['900', '901'] && r.note == null : r
println 'ok   unlisted apps found once each, listed ones not repeated'

def old = make({ Long d -> throw new MissingMethodException('getAppsUsingDevice', Object, [d] as Object[]) }, { -> 0L })
Map r2 = old.appsUsingDevicesNotListed(['10'], [])
assert r2.ids == [] && r2.note.contains('does not report') : r2
println 'ok   a hub without the call is skipped and says so'

long t = 0L
def slow = make({ Long d -> t += 15000L; [[id: 700 + d]] }, { -> t })
Map r3 = slow.appsUsingDevicesNotListed(['10', '11', '12'], [])
assert r3.ids.size() == 2 && r3.note.contains('stopped looking') : r3
println 'ok   bounded in time, keeping what it found'

def flaky = make({ Long d -> if (d == 10L) throw new RuntimeException('busy'); [[id: 5]] }, { -> 0L })
assert flaky.appsUsingDevicesNotListed(['10', '11'], []).ids == ['5']
println 'ok   one device failing does not stop the rest'

int at = source.indexOf('Map unlisted = appsUsingDevicesNotListed(')
assert at > 0 && source.indexOf('appIds.addAll(unlisted.ids as List)', at) in (at..(at + 400)) : 'the app phase does not use the second source'
println 'ok   the app phase adds them to the enumeration'
