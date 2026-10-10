// The hub's userAppTypes table decides [CUS] against [INT]. From 2.4.3 a GString containsKey against its String
// keys answered false for every app, so every app on the hub was tagged [INT] (Gordon, 2026-10-10).
// Runs on Groovy 2.4, the hub's version, where the defect reproduced.
String source = new File('apps/automation_map.groovy').getText('UTF-8')
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
def script = new GroovyShell().parse(AppSource.functions(source, ['isUserAppType']) + '\n')

// Built the way fetchAppTypeNamespaces builds it, then copied as the scan copies it.
Map ns = [:]
[[id: 1143, namespace: 'example'], [id: 1062, namespace: 'example']].each { e -> ns.put("${e.id}".toString(), "${e.namespace}".toString()) }
Map scanCopy = new java.util.concurrent.ConcurrentHashMap<String, String>(ns)
assert script.isUserAppType(scanCopy, 1143L) && script.isUserAppType(scanCopy, '1062') : 'a user-installed type read as built-in'
assert !script.isUserAppType(scanCopy, 574) : 'a built-in type read as user-installed'
assert !script.isUserAppType(scanCopy, null)
// The defect itself, so this file fails if the call site goes back to it.
assert !scanCopy.containsKey("${1143}") : 'Groovy changed: a GString now matches a String key'
assert !source.contains('appTypeNamespaces.containsKey("${') : 'the GString containsKey is back'
println 'ok   user-installed app types are recognised, built-in ones are not'
