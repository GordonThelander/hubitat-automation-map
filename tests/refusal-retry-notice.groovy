// After a refused scan the settings page says when the automatic retry runs (Gordon, 2026-10-10: the page
// showed only the error and looked hung for three minutes).
String source = new File('apps/automation_map.groovy').getText('UTF-8')
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
def script = new GroovyShell().parse('import groovy.transform.Field\n' + AppSource.field(source, 'REFUSAL_RETRY_SECONDS') + '\n' +
                                     AppSource.functions(source, ['refusalRetryNotice']) + '\n')
TimeZone utc = TimeZone.getTimeZone('UTC')
long refusedAt = Date.parse('yyyy-MM-dd HH:mm:ss', '2026-10-10 07:02:10', utc).time
assert script.refusalRetryNotice(null, refusedAt, utc) == null
assert script.refusalRetryNotice([pending: false], refusedAt, utc) == null
String waiting = script.refusalRetryNotice([pending: true, at: refusedAt], refusedAt + 1000L, utc)
assert waiting.contains('at 07:05') : waiting
assert script.refusalRetryNotice([pending: true, at: refusedAt], refusedAt + 181000L, utc).contains('now')
assert source.contains("refusalRetryNotice(atomicState.refusalRetry as Map, now(), location.timeZone)")
assert source.contains("refreshInterval: (ready && (scanActive || (atomicState.refusalRetry as Map)?.pending)) ? 60 : 0)")
println 'ok   a pending retry is announced with its time, and the page refreshes until it is done'
