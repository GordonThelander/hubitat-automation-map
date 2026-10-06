// Room Manager writes room membership, never the device record. The old path
// posted all 24 device fields per device, turned a null groupId into 0 on
// every move, and cost 3 serial requests per device: queue 897, measured as
// "0 moved, 18 failed" on Gordon's hub. Replaced after the controlled
// experiment in queue 906 proved /room/save moves a device in one write,
// removes it from its old room by itself, and leaves the whole device record
// untouched.
//
// These assert the request-count contract from queue 907 as well as the
// outcome, because an accidental per-device read or write would be invisible
// in the result alone.
//
// Run with: groovy tests/room-write-contract.groovy
String source = new File('apps/automation_map.groovy').getText('UTF-8')
String slice = source.substring(source.indexOf('Map roomPlanApplyMoves(Map moves) {'),
                                source.indexOf('Map roomPlanGetMapping()'))
String preamble = 'import groovy.transform.Field\nimport groovy.json.JsonOutput\n'
// The constants are sliced from the app. httpFetch stays a stub because it is the network boundary, but
// with the app's signature: the stub had invented a two-argument one, so any call the app makes with
// options - five of its callers do - would have thrown here instead of being recorded.
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String stubs = AppSource.field(source, 'LOOPBACK_BASE') + '\n' + AppSource.field(source, 'ROOM_NO_ROOM_NAMES') + """
Map httpFetch(String uri, int timeoutSec, Map extraOpts = [:]) {
    String url = uri
    binding.getVariable('requests') << ['GET', url]
    return binding.getVariable('onFetch').call(url)
}
long now() { return System.nanoTime() / 1000000L }
void httpPost(Map args, Closure c) {
    binding.getVariable('requests') << ['POST', "\${args.path}"]
    binding.getVariable('posts') << args
    c.call([status: binding.getVariable('postStatus')])
}
"""

// The boundary stub must keep the app's signature. If the app's changes, this fails rather than the stub
// quietly accepting calls the app can no longer make, or refusing ones it now does.
assert AppSource.function(source, 'httpFetch').readLines()[0] == 'Map httpFetch(String uri, int timeoutSec, Map extraOpts = [:]) {' :
    'httpFetch changed signature in the app; update the stub above to match it'

int checks = 0
List<Boolean> results = []
def expect = { String what, boolean cond ->
    checks++
    println "${cond ? 'PASS' : 'FAIL'}  ${what}"
    results << cond
}

// A room tree in the shape /hub2/roomsList returns.
Closure tree = { Map roomsToDevices ->
    List nodes = []
    roomsToDevices.each { Object name, Object spec ->
        Map m = spec as Map
        nodes << [data: [id: m.id, name: name],
                  children: (m.devices as List).collect { [data: [id: it]] }]
    }
    return [roomNodes: nodes]
}

Closure make = { Map roomsToDevices ->
    def script = new GroovyShell().parse(preamble + slice + stubs)
    script.binding.setVariable('state', [:])
    script.binding.setVariable('requests', [])
    script.binding.setVariable('posts', [])
    script.binding.setVariable('postStatus', 200)
    List trees = [tree(roomsToDevices)]
    script.binding.setVariable('onFetch', { String url -> [ok: true, data: trees[-1]] })
    script.binding.setVariable('trees', trees)
    return script
}

// After a write lands, the stub advances the tree so the verification read
// sees the new membership, exactly as the hub would.
Closure applyWritesToTree = { def script, Map roomsToDevices ->
    script.binding.setVariable('onFetch', { String url ->
        List posts = script.binding.getVariable('posts')
        Map live = [:]
        roomsToDevices.each { Object nm, Object spec ->
            Map m = spec as Map
            live[nm] = [id: "${m.id}", devices: (m.devices as List).collect { "${it}" }]
        }
        posts.each { Object p ->
            Map body = new groovy.json.JsonSlurper().parseText("${(p as Map).body}") as Map
            String rid = "${body.roomId}"
            List ids = (body.deviceIds as List).collect { "${it}" }
            Map next = [:]
            live.each { Object nm, Object spec ->
                Map m = spec as Map
                if (m.id == rid) next[nm] = [id: m.id, devices: ids]
                // The hub removes an arriving device from wherever it was.
                else next[nm] = [id: m.id, devices: (m.devices as List).findAll { !ids.contains(it) }]
            }
            live = next
        }
        return [ok: true, data: tree(live)]
    })
}

Closure counts = { def s ->
    List r = s.binding.getVariable('requests')
    return [gets: r.count { it[0] == 'GET' }, posts: r.count { it[0] == 'POST' },
            deviceUpdates: r.count { "${it[1]}".contains('device/update') },
            deviceReads: r.count { "${it[1]}".contains('device/fullJson') }]
}

// --- 1. many devices into one target, the 18-device shape ------------------
Map hub = ['Temp': [id: '47', devices: ['1', '2', '3']], 'Spare': [id: '9', devices: ['4', '5']],
           'Lounge': [id: '12', devices: ['6']], 'Virtual': [id: '80', devices: []]]
def s = make(hub)
applyWritesToTree(s, hub)
Map res = s.roomPlanApplyMoves(['1': 'Virtual', '2': 'Virtual', '4': 'Virtual', '6': 'Virtual'])
Map c = counts(s)
expect('four devices from three rooms into one target succeeds', res.ok)
expect('that batch issues exactly one write', c.posts == 1)
expect('that batch issues exactly two reads, the snapshot and the verification', c.gets == 2)
expect('no device record is ever written', c.deviceUpdates == 0)
expect('no per-device verification read is issued', c.deviceReads == 0)
expect('it reports what changed', res.changed == 4 && res.requested == 4)
List body = new groovy.json.JsonSlurper().parseText("${(s.binding.getVariable('posts')[0] as Map).body}").deviceIds
expect('the target receives its complete intended membership', body == ['1', '2', '4', '6'])

// --- 2. rooms that only lose devices are not written ----------------------
expect('source rooms are not written at all', s.binding.getVariable('posts').size() == 1)

// --- 3. a two-room swap ---------------------------------------------------
hub = ['A': [id: '1', devices: ['a1', 'a2']], 'B': [id: '2', devices: ['b1']]]
s = make(hub); applyWritesToTree(s, hub)
res = s.roomPlanApplyMoves(['a1': 'B', 'b1': 'A'])
List w = s.binding.getVariable('posts').collect { new groovy.json.JsonSlurper().parseText("${(it as Map).body}") }
expect('a swap succeeds', res.ok)
expect('a swap writes both rooms, since both gain', w.size() == 2)
Map wa = w.find { "${it.roomId}" == '1' }
Map wb = w.find { "${it.roomId}" == '2' }
expect('the swap sends complete memberships, not current-plus-arrivals',
       (wa.deviceIds as List) == ['a2', 'b1'] && (wb.deviceIds as List) == ['a1'])

// --- 4. a three-room chain ------------------------------------------------
hub = ['A': [id: '1', devices: ['a']], 'B': [id: '2', devices: ['b']], 'C': [id: '3', devices: ['c']]]
s = make(hub); applyWritesToTree(s, hub)
res = s.roomPlanApplyMoves(['a': 'B', 'b': 'C', 'c': 'A'])
w = s.binding.getVariable('posts').collect { new groovy.json.JsonSlurper().parseText("${(it as Map).body}") }
expect('a three-room cycle succeeds', res.ok)
expect('a cycle writes each room once', w.size() == 3)
expect('each room in the cycle gets only its arrival',
       w.every { (it.deviceIds as List).size() == 1 })

// --- 5. moves to Not Allocated --------------------------------------------
hub = ['Temp': [id: '47', devices: ['1', '2', '3']]]
s = make(hub); applyWritesToTree(s, hub)
res = s.roomPlanApplyMoves(['2': ''])
w = s.binding.getVariable('posts').collect { new groovy.json.JsonSlurper().parseText("${(it as Map).body}") }
expect('unallocating succeeds', res.ok)
expect('unallocating writes the source room, the only way to express it', w.size() == 1)
expect('the source room is rewritten without the departing device', (w[0].deviceIds as List) == ['1', '3'])

// --- 6. a room that both gains and sends to Not Allocated -----------------
hub = ['A': [id: '1', devices: ['a1', 'a2']], 'B': [id: '2', devices: ['b1']]]
s = make(hub); applyWritesToTree(s, hub)
res = s.roomPlanApplyMoves(['b1': 'A', 'a1': ''])
w = s.binding.getVariable('posts').collect { new groovy.json.JsonSlurper().parseText("${(it as Map).body}") }
expect('a room that both gains and loses to unallocated succeeds', res.ok)
expect('that room is written once, not twice', w.count { "${it.roomId}" == '1' } == 1)
expect('its membership excludes the departure and includes the arrival',
       (w.find { "${it.roomId}" == '1' }.deviceIds as List) == ['a2', 'b1'])

// --- 7. already-correct devices mixed with real moves ---------------------
hub = ['A': [id: '1', devices: ['a1']], 'B': [id: '2', devices: ['b1']]]
s = make(hub); applyWritesToTree(s, hub)
res = s.roomPlanApplyMoves(['a1': 'A', 'b1': 'A'])
expect('a device already in its target is reported, not written', res.alreadyCorrect == 1)
expect('only the real move is counted as changed', res.changed == 1)
expect('an already-correct device does not add a write', s.binding.getVariable('posts').size() == 1)

// --- 8. an unknown room name fails before any write ----------------------
hub = ['A': [id: '1', devices: ['a1']]]
s = make(hub); applyWritesToTree(s, hub)
res = s.roomPlanApplyMoves(['a1': 'Nowhere'])
expect('an unknown room name fails', !res.ok)
expect('an unknown room name names itself', "${res.reason}".contains('Nowhere'))
expect('an unknown room name writes nothing', counts(s).posts == 0)

// --- 9. a write failure stops the batch ----------------------------------
hub = ['A': [id: '1', devices: ['a1']], 'B': [id: '2', devices: ['b1']], 'C': [id: '3', devices: []]]
s = make(hub); applyWritesToTree(s, hub)
s.binding.setVariable('postStatus', 500)
res = s.roomPlanApplyMoves(['a1': 'C', 'b1': 'C'])
expect('a non-2xx write fails the batch', !res.ok)
expect('a failed write stops rather than continuing', s.binding.getVariable('posts').size() == 1)

// --- 10. verification mismatch after the writes --------------------------
hub = ['A': [id: '1', devices: ['a1']], 'B': [id: '2', devices: []]]
s = make(hub)
// The hub accepts the write but the membership does not change.
s.binding.setVariable('onFetch', { String url -> [ok: true, data: tree(hub)] })
res = s.roomPlanApplyMoves(['a1': 'B'])
expect('a write that does not land is reported as failed', !res.ok)
expect('the unlanded device is named', (res.mismatched as List) == ['a1'])
expect('it is not reported as changed', res.changed == 0)

// --- 11. an unreadable room tree writes nothing --------------------------
s = make(['A': [id: '1', devices: []]])
s.binding.setVariable('onFetch', { String url -> [ok: false, data: null] })
res = s.roomPlanApplyMoves(['a1': 'A'])
expect('an unreadable room tree fails closed', !res.ok)
expect('an unreadable room tree writes nothing', counts(s).posts == 0)

// --- 12. duplicate staged entries ----------------------------------------
hub = ['A': [id: '1', devices: ['a1']], 'B': [id: '2', devices: []]]
s = make(hub); applyWritesToTree(s, hub)
res = s.roomPlanApplyMoves(['a1': 'B', ' a1 ': 'B'])
w = s.binding.getVariable('posts').collect { new groovy.json.JsonSlurper().parseText("${(it as Map).body}") }
expect('a duplicate staged entry does not duplicate membership',
       (w[0].deviceIds as List).count { it == 'a1' } == 1)

// --- 13. nested component devices use their own roomId -------------------
// /hub2/roomsList keeps a component below its parent even when the two have
// different rooms. The old one-level parser omitted the child completely;
// assigning by tree position would be just as wrong.
Closure nestedTree = { String componentRoomId ->
    String componentRoomName = componentRoomId == '80' ? 'Virtual' : 'Temp'
    return [roomNodes: [
        [data: [id: '47', name: 'Temp'], children: [
            [data: [id: '3593', name: 'Variable Connectors', roomId: 47, roomName: 'Temp'], children: [
                [data: [id: '3601', name: 'AMGateA_Connector', roomId: componentRoomId as Integer,
                        roomName: componentRoomName], children: []]
            ]]
        ]],
        [data: [id: '80', name: 'Virtual'], children: [
            [data: [id: '9000', name: 'Existing Virtual', roomId: 80, roomName: 'Virtual'], children: []]
        ]]
    ]]
}

s = make([:])
s.binding.setVariable('onFetch', { String url -> [ok: true, data: nestedTree('47')] })
Map nested = s.roomPlanMembershipSnapshot()
expect('a nested component is discovered', nested.deviceRoom['3601'] == '47')
expect('a nested component is included in its room membership',
       (nested.members['47'] as List).contains('3601'))

s = make([:])
List nestedReads = [nestedTree('47'), nestedTree('80')]
s.binding.setVariable('onFetch', { String url ->
    [ok: true, data: nestedReads.size() > 1 ? nestedReads.remove(0) : nestedReads[0]]
})
res = s.roomPlanApplyMoves(['3601': 'Virtual'])
expect('a nested component move verifies against its own updated roomId', res.ok && res.changed == 1)
expect('the component move performs one room write', counts(s).posts == 1)
Map nestedBody = new groovy.json.JsonSlurper().parseText("${(s.binding.getVariable('posts')[0] as Map).body}") as Map
expect('the component is included in the target complete membership',
       (nestedBody.deviceIds as List) == ['9000', '3601'])

s = make([:])
s.binding.setVariable('onFetch', { String url -> [ok: true, data: nestedTree('80')] })
nested = s.roomPlanMembershipSnapshot()
expect('tree nesting does not override a component own different roomId', nested.deviceRoom['3601'] == '80')
expect('the differently-roomed child belongs to its own room write set',
       (nested.members['80'] as List) == ['3601', '9000'])

// --- 14. the source forbids the old path ---------------------------------
String sliceCode = slice.readLines().findAll { !it.trim().startsWith('//') }.join(System.lineSeparator())
expect('no room write path calls /device/update', !sliceCode.contains('device/update'))

// --- 15. phase instrumentation, so 15s over four requests can be attributed --
hub = ['A': [id: '1', devices: ['a1']], 'B': [id: '2', devices: []]]
s = make(hub); applyWritesToTree(s, hub)
res = s.roomPlanApplyMoves(['a1': 'B'])
Map tm = res.timings as Map
expect('the result carries phase timings', tm != null)
['snapshotMs', 'planMs', 'verifyReadMs', 'compareMs', 'writeMs', 'totalMs'].each { String k ->
    expect("timings include " + k, tm.containsKey(k))
}
expect('each write is timed and identified by room and membership size',
       (tm.writes as List).every { Map it2 -> it2.containsKey('roomId') && it2.containsKey('members') && it2.containsKey('ms') })
expect('one write is timed for this batch', (tm.writes as List).size() == 1)

// --- 15. the panel reports rooms written, not per-device work ---------------
// The whole function, not a fixed window: a fixed length silently excluded
// assertions as the function grew, which read as a failure in the code.
int uiStart = source.indexOf('function roomPlanApply()')
int uiEnd = source.indexOf(System.lineSeparator() + "function ", uiStart + 10)
String ui = source.substring(uiStart, uiEnd > uiStart ? uiEnd : source.length())
expect('the pending message states the bounded number of room writes',
       ui.contains("'room write'") && ui.contains('Applying '))
expect('a pending phase states it is applying and verifying',
       ui.contains('Applying and verifying'))
expect('completion reports elapsed seconds', ui.contains("+ 's.'"))
expect('completion reports the outcome counts separately',
       ui.contains('d.changed') && ui.contains('d.alreadyCorrect') && ui.contains('d.failed'))
expect('the panel no longer reads per-device result rows', !ui.contains('d.results'))
expect('the panel keeps only mismatched devices staged', ui.contains('d.mismatched'))

// --- 16. the pending wording matches what was measured, not a guess --------
expect('the pending text no longer claims reads are the slow part',
       !ui.contains('slow part, not the writes'))
expect('the pending text is neutral about where the time goes',
       ui.contains('Reading and updating hub rooms can take several seconds'))

// --- 17. the user is told the time they actually waited --------------------
expect('elapsed is measured at the client, not taken from the server total',
       ui.contains('Date.now() - started'))
expect('the server phase total is not used for the user-facing elapsed figure',
       !ui.contains('timings.totalMs'))
expect('the phase breakdown is kept for evidence, off the user-facing line',
       ui.contains('console.log') && ui.contains('d.timings'))

// --- 18. one source of truth: the panel reads the live hub, not scan state --
String jsonFn = source.substring(source.indexOf('String roomPlanJson(String message)'),
                                 source.indexOf('Map roomPlanSaveMapping()'))
expect('the rooms payload is built from the live membership read',
       jsonFn.contains('roomPlanMembershipSnapshot()'))
expect('the payload carries the live device to room map',
       jsonFn.contains('deviceRooms : deviceRooms'))
expect('the payload says whether the live read succeeded',
       jsonFn.contains('live        : snap.ok'))
expect('devices the scan never saw are published so the panel cannot omit them',
       jsonFn.contains('hubOnlyDevices'))

int curStart = source.indexOf('function roomPlanCurrent(d)')
String cur = source.substring(curStart, source.indexOf('function roomPlanLayoutKey', curStart + 10))
int actualStart = source.indexOf('function roomPlanActual(d)')
String actual = source.substring(actualStart, curStart)
expect('a device actual room comes from the live map first', actual.contains('ROOMPLAN.deviceRooms'))
expect('with a live read, a device absent from it is unallocated, not stale scan state',
       actual.contains('ROOMPLAN.live') && actual.contains('RP_UNASSIGNED'))
expect('scan state is only the fallback when the live read failed',
       actual.indexOf('ROOMPLAN.live') < actual.indexOf('roomPlanNormalise(d.room)'))
expect('the displayed current room overlays pending state on the actual live origin',
       cur.contains('roomPending') && cur.contains('roomPlanActual(d)'))

int stageStart = source.indexOf('function roomPlanStage(devId, targetRoom, defer)')
String stage = source.substring(stageStart, source.indexOf('function roomPlanWire', stageStart + 10))
expect('staging compares the target with the actual live origin, not stale scan state',
       stage.contains('roomPlanActual(d)') && !stage.contains('roomPlanNormalise(d.room)'))

expect('the panel merges devices the hub has that the scan never saw',
       source.contains('ROOMPLAN.hubOnlyDevices || []'))
expect('an apply re-reads live state instead of patching the in-memory list',
       ui.contains('roomPlanRefreshLive(keep)'))
expect('the apply no longer patches dev.room by hand', !ui.contains('dev.room = roomPending'))
int refreshStart = source.indexOf('function roomPlanRefreshLive(keep)')
int refreshEnd = source.indexOf('function roomPlanNames()', refreshStart + 10)
String refresh = source.substring(refreshStart, refreshEnd)
expect('the lightweight refresh reads only live room membership',
       refresh.contains('fetch(ROOMPLAN_URL') && !refresh.contains('ICONS_URL'))
expect('the lightweight refresh does not clear the canvas', !refresh.contains('Loading...'))
expect('the lightweight refresh renders exactly once',
       refresh.count('roomPlanRender()') == 1)
expect('the lightweight refresh preserves valid staged moves',
       refresh.contains('Object.keys(keep || {})'))
expect('the lightweight refresh drops staged targets removed or renamed on the hub',
       refresh.contains('validRooms[target.toLowerCase()]'))
int crudStart = source.indexOf('function roomPlanCrud(body, busy)')
int crudEnd = source.indexOf('function roomPlanActual(d)', crudStart + 10)
String crud = source.substring(crudStart, crudEnd)
expect('create, rename and delete use the lightweight refresh',
       crud.contains('roomPlanRefreshLive(keep)') && !crud.contains('roomPlanLoad()'))

int bad = results.count { !it }
println "${results.size() - bad} room write assertions passed, ${bad} failed"
if (bad > 0) System.exit(1)
