// Regression fixture for the v2.1.0 production upgrade crash (Bucket/Queue
// 165-168). A real v2.0.4 state predates state.deviceScanId/appScanId, so
// ConcurrentHashMap.get(null) threw NullPointerException on the settings page.
// Run with: groovy tests/scan-id-null-guard.groovy
import java.util.concurrent.ConcurrentHashMap

// Stand-ins for the app's own @Field statics and state map. Real
// ConcurrentHashMap, so its null-key behavior is exactly what production hit.
def DEVICE_SCANS = new ConcurrentHashMap<String, ConcurrentHashMap>()
def APP_SCANS = new ConcurrentHashMap<String, ConcurrentHashMap>()
def state = [:]

// The OLD, unguarded code from apps/automation_map.groovy - reproduces the
// exact crash before proving the fix.
def oldLiveAppScan = { ->
    return APP_SCANS[state.appScanId as String]
}
def oldLiveDeviceScan = { ->
    return DEVICE_SCANS[state.deviceScanId as String]
}

// The FIXED code, copied verbatim from the app.
def liveDeviceScan = { ->
    String id = state.deviceScanId as String
    return id ? DEVICE_SCANS[id] : null
}
def liveAppScan = { ->
    String id = state.appScanId as String
    return id ? APP_SCANS[id] : null
}

int pass = 0, fail = 0
def check = { String name, Closure body ->
    try {
        body()
        println "PASS  ${name}"
        pass++
    } catch (Throwable t) {
        println "FAIL  ${name} - ${t.class.simpleName}: ${t.message}"
        fail++
    }
}

println '--- Reproduce the original crash (proves the fixture is faithful) ---'
state = [scanPhase: 'apps', scanTotal: 113, appScanId: null]
def threw = false
try { oldLiveAppScan() } catch (NullPointerException npe) { threw = true }
check('old code throws NullPointerException on legacy state (appScanId null)') {
    assert threw : 'expected the old code to throw, it did not'
}

threw = false
state = [scanPhase: 'devices', scanTotal: 113, deviceScanId: null]
try { oldLiveDeviceScan() } catch (NullPointerException npe) { threw = true }
check('old code throws NullPointerException on legacy state (deviceScanId null)') {
    assert threw : 'expected the old code to throw, it did not'
}

println '--- Fixed code: legacy state (fixture 1 and 2) ---'
state = [scanPhase: 'apps', scanTotal: 113, appScanId: null]
check("liveAppScan() returns null, does not throw, scanPhase='apps' appScanId absent") {
    assert liveAppScan() == null
}
state = [scanPhase: 'devices', scanTotal: 113, deviceScanId: null]
check("liveDeviceScan() returns null, does not throw, scanPhase='devices' deviceScanId absent") {
    assert liveDeviceScan() == null
}

println '--- Fixed code: id present, accumulator absent (fixture: simulates a code reload) ---'
state = [scanPhase: 'apps', scanTotal: 50, appScanId: 'apps-9999999999-1234']
check('liveAppScan() returns null when the id is a real string but the map was cleared by a reload') {
    assert liveAppScan() == null
}
state = [scanPhase: 'devices', scanTotal: 50, deviceScanId: 'devices-9999999999-5678']
check('liveDeviceScan() returns null under the same reload scenario') {
    assert liveDeviceScan() == null
}

println '--- Fixed code: normal in-progress scan (regression check - happy path unchanged) ---'
def realAppScan = new ConcurrentHashMap([processed: new java.util.concurrent.atomic.AtomicInteger(7)])
APP_SCANS['apps-real-id'] = realAppScan
state = [scanPhase: 'apps', scanTotal: 50, appScanId: 'apps-real-id']
check('liveAppScan() returns the real live accumulator when everything is normal') {
    assert liveAppScan().is(realAppScan)
}
def realDeviceScan = new ConcurrentHashMap([processed: new java.util.concurrent.atomic.AtomicInteger(3)])
DEVICE_SCANS['devices-real-id'] = realDeviceScan
state = [scanPhase: 'devices', scanTotal: 50, deviceScanId: 'devices-real-id']
check('liveDeviceScan() returns the real live accumulator when everything is normal') {
    assert liveDeviceScan().is(realDeviceScan)
}

println ''
println "${pass} passed, ${fail} failed"
System.exit(fail == 0 ? 0 : 1)
