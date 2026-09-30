// Backend tests for the four hub writes Room Manager makes: device move, room
// create, rename and delete. The hub is stubbed, so what is under test is this
// app's own preservation contract rather than the hub's behaviour.
String source = new File('apps/automation_map.groovy').getText('UTF-8')
String slice = source.substring(source.indexOf("@Field static final String ROOM_CLEARED_ID"),
                                source.indexOf('Map roomPlanGetMapping()'))
String preamble = 'import groovy.transform.Field\nimport groovy.json.JsonOutput\n'
String stubs = """
@Field static final String LOOPBACK_BASE = 'http://127.0.0.1:8080'
Map httpFetch(String url, int t) { return binding.getVariable('onFetch').call(url) }
void httpPost(Map args, Closure c) {
    binding.getVariable('posts') << args
    binding.getVariable('onPost').call(args)
    c.call([status: binding.getVariable('postStatus')])
}
List hubRoomList() { return binding.getVariable('rooms') }
"""

def make = { ->
    def script = new GroovyShell().parse(preamble + slice + stubs)
    script.binding.setVariable('state', [:])
    script.binding.setVariable('rooms', [])
    script.binding.setVariable('posts', [])
    script.binding.setVariable('postStatus', 200)
    script.binding.setVariable('onPost', { Map a -> })
    script.binding.setVariable('onFetch', { String url -> [ok: false, data: null] })
    return script
}

// A device record carrying every field the app promises to carry forward.
Map device(Map over = [:]) {
    Map d = [id: '42', name: 'Zen Thermostat', label: 'Study Heater', zigbeeId: 'ABC1',
             maxEvents: 200, maxStates: 200, spammyThreshold: 10, deviceNetworkId: '0A0B',
             deviceTypeId: 55, deviceTypeReadableType: 'Zen Thermostat', roomId: '133',
             meshEnabled: true, retryEnabled: false, meshFullSync: false, locationId: 1,
             hubId: 1, groupId: null, tags: ['upstairs', 'winter'], defaultIcon: 'st.Home.home1',
             notes: 'calibrated 2026-03-02', version: 7, controllerType: 'ZGB']
    d.putAll(over)
    return d
}
Map record(Map dev, List dashboards = [], Object homeKit = false) {
    return [device: dev, dashboards: dashboards, homeKitEnabled: homeKit]
}
// Two reads happen per move. This serves the first record then the second.
Closure pair = { Map first, Map second ->
    List queue = [first, second]
    return { String url -> [ok: true, data: queue.size() > 1 ? queue.remove(0) : queue[0]] }
}
Closure tree = { String name, List devIds ->
    return [roomNodes: [[data: [id: '133', name: name],
                         children: devIds.collect { [data: [id: it]] }]]]
}

int checks = 0
def expect = { String what, boolean cond -> checks++; assert cond : what }

// 1. Every carried field survives, so the move reports success.
def s = make()
s.binding.setVariable('onFetch', pair(record(device()), record(device(roomId: '900', version: 8))))
Map res = s.roomPlanWriteDeviceRoom('42', '900')
expect('a clean move succeeds', res.ok)
expect('a clean move reports no drift', !res.changed)

// 2. The version token is expected to move and must not read as drift.
expect('version is not a preserved field', !s.ROOM_PRESERVED_FIELDS.contains('version'))

// 3. Clearing a room posts roomId 0 and accepts an empty read-back.
s = make()
s.binding.setVariable('onFetch', pair(record(device()), record(device(roomId: null))))
res = s.roomPlanWriteDeviceRoom('42', '0')
expect('clearing a room succeeds', res.ok)
expect('clearing posts roomId 0', s.binding.getVariable('posts')[0].body.roomId == '0')

// 4. Only the selected dashboards are carried, out of several on the hub.
s = make()
List dashes = [[id: 11, selected: true], [id: 12, selected: false],
               [id: 13, selected: true], [id: 14, selected: null]]
s.binding.setVariable('onFetch', pair(record(device(), dashes), record(device(roomId: '900'), dashes)))
res = s.roomPlanWriteDeviceRoom('42', '900')
expect('multiple dashboards move cleanly', res.ok)
expect('only selected dashboards are posted', s.binding.getVariable('posts')[0].body.dashboardIds == '11,13')

// 5. One selected dashboard is the common case and must post just that one.
s = make()
List one = [[id: 11, selected: true], [id: 12, selected: false]]
s.binding.setVariable('onFetch', pair(record(device(), one), record(device(roomId: '900'), one)))
res = s.roomPlanWriteDeviceRoom('42', '900')
expect('one selected dashboard posts alone', s.binding.getVariable('posts')[0].body.dashboardIds == '11')

// 6. A dashboard assignment gained during the write is drift, not success.
s = make()
s.binding.setVariable('onFetch', pair(record(device(), one),
        record(device(roomId: '900'), [[id: 11, selected: true], [id: 12, selected: true]])))
res = s.roomPlanWriteDeviceRoom('42', '900')
expect('a gained dashboard fails the move', !res.ok)
expect('a gained dashboard is named', res.changed == ['dashboards'])

// 7. Empty, null and the string "null" all mean not set, so none is drift.
s = make()
s.binding.setVariable('onFetch', pair(
        record(device(notes: null, groupId: null, zigbeeId: '')),
        record(device(roomId: '900', notes: '', groupId: 'null', zigbeeId: null))))
res = s.roomPlanWriteDeviceRoom('42', '900')
expect('equivalent empty representations are not drift', res.ok)

// 8. A protected field that really did change fails the move and is named.
s = make()
s.binding.setVariable('onFetch', pair(record(device()),
        record(device(roomId: '900', notes: '', tags: []))))
res = s.roomPlanWriteDeviceRoom('42', '900')
expect('a lost field fails the move', !res.ok)
expect('the lost fields are named', res.changed.containsAll(['notes', 'tags']))
expect('the failure says what was broken', res.reason.contains('promises not to do'))

// 9. homeKitEnabled sits outside the device object and is checked too.
s = make()
s.binding.setVariable('onFetch', pair(record(device(), [], true),
        record(device(roomId: '900'), [], false)))
res = s.roomPlanWriteDeviceRoom('42', '900')
expect('a lost homeKit flag fails the move', !res.ok)
expect('homeKitEnabled is named', res.changed == ['homeKitEnabled'])

// A: the shapes 12 real records on the hub actually use, read on 2026-09-30:
// roomId and the counters are integers, tags comes back as a string rather
// than a list, and label, zigbeeId, groupId and controllerType are null while
// notes, defaultIcon and tags are the empty string. None of that is drift.
Map hubShaped(Map over = [:]) {
    Map d = [id: 3610, name: 'P100', label: null, zigbeeId: null, maxEvents: 11,
             maxStates: 20, spammyThreshold: 300, deviceNetworkId: '40AE30D03569',
             deviceTypeId: 1575, deviceTypeReadableType: 'User', roomId: 14,
             meshEnabled: false, retryEnabled: false, meshFullSync: false,
             locationId: 1, hubId: 1, groupId: null, tags: '', defaultIcon: '',
             notes: '', version: 2, controllerType: null]
    d.putAll(over)
    return d
}
s = make()
List hubDash = [[id: 3513, selected: false], [id: 3514, selected: false]]
s.binding.setVariable('onFetch', pair(record(hubShaped(), hubDash),
        record(hubShaped(roomId: 900, version: 3), hubDash)))
res = s.roomPlanWriteDeviceRoom('3610', '900')
expect('real hub field shapes do not read as drift', res.ok)
expect('an integer roomId reads back as moved', res.roomId == '900')

// B: tags survive the round trip whether they come back as a list or a string.
s = make()
s.binding.setVariable('onFetch', pair(record(hubShaped(tags: ['upstairs', 'winter'])),
        record(hubShaped(roomId: 900, tags: 'upstairs,winter'))))
res = s.roomPlanWriteDeviceRoom('3610', '900')
expect('tags as a list and as a string are the same tags', res.ok)

s = make()
s.binding.setVariable('onFetch', pair(record(hubShaped(tags: ['upstairs', 'winter'])),
        record(hubShaped(roomId: 900, tags: 'upstairs'))))
res = s.roomPlanWriteDeviceRoom('3610', '900')
expect('a dropped tag is still drift', !res.ok && res.changed == ['tags'])

// 10. The room landing is checked as well as the fields.
s = make()
s.binding.setVariable('onFetch', pair(record(device()), record(device())))
res = s.roomPlanWriteDeviceRoom('42', '900')
expect('a room that did not move fails', !res.ok)

// 11. A non-2xx is a failure even when the read-back looks right.
s = make()
s.binding.setVariable('postStatus', 302)
s.binding.setVariable('onFetch', pair(record(device()), record(device(roomId: '900'))))
res = s.roomPlanWriteDeviceRoom('42', '900')
expect('a 302 fails the move', !res.ok)
expect('the status is reported', res.status == 302)

// 12. No read-back means unconfirmed, which is not success.
s = make()
List queue = [[ok: true, data: record(device())], [ok: false, data: null]]
s.binding.setVariable('onFetch', { String url -> queue.size() > 1 ? queue.remove(0) : queue[0] })
res = s.roomPlanWriteDeviceRoom('42', '900')
expect('a failed read-back fails the move', !res.ok)
expect('the failure says it is unconfirmed', res.reason.contains('confirmed'))

// 13. Create refuses a duplicate name and confirms a new room landed.
s = make()
s.binding.setVariable('rooms', [[id: '133', name: 'Study']])
res = s.roomPlanCreateRoom('study')
expect('a duplicate room name is refused', !res.ok)

s = make()
s.binding.setVariable('rooms', [[id: '133', name: 'Study']])
res = s.roomPlanCreateRoom('Garage')
expect('a room that never appears fails', !res.ok)

s = make()
List live = [[id: '133', name: 'Study']]
s.binding.setVariable('rooms', live)
s.binding.setVariable('onPost', { Map a -> live << [id: '140', name: 'Garage'] })
res = s.roomPlanCreateRoom('Garage ')
expect('a created room that appears succeeds', res.ok)
expect('create posts roomId 0', s.binding.getVariable('posts')[0].body.contains('"roomId":0'))

// 14. Rename fails closed when it cannot read the room, and verifies the name.
s = make()
res = s.roomPlanRenameRoom('133', 'Den')
expect('rename refuses when the room cannot be read', !res.ok)
expect('rename says nothing was written', res.reason.contains('not renamed'))

s = make()
s.binding.setVariable('onFetch', { String url -> [ok: true, data: tree('Study', ['42', '43'])] })
res = s.roomPlanRenameRoom('133', 'Den')
expect('a rename that does not land fails', !res.ok)
expect('the rename failure names both sides', res.reason.contains('Den') && res.reason.contains('Study'))
expect('rename carries the existing membership',
       s.binding.getVariable('posts')[0].body.contains('"deviceIds":["42","43"]'))

s = make()
List names = ['Study', 'Den']
s.binding.setVariable('onFetch', { String url ->
    [ok: true, data: tree(names.size() > 1 ? names.remove(0) : names[0], ['42', '43'])] })
res = s.roomPlanRenameRoom('133', 'Den')
expect('a rename that lands succeeds', res.ok)
expect('the rename reports its membership', res.devices == 2)

// A rename that quietly empties the room is caught by the membership check.
s = make()
List trees = [tree('Study', ['42', '43']), tree('Den', [])]
s.binding.setVariable('onFetch', { String url ->
    [ok: true, data: trees.size() > 1 ? trees.remove(0) : trees[0]] })
res = s.roomPlanRenameRoom('133', 'Den')
expect('a rename that empties the room fails', !res.ok)

// 15. Delete fails closed when it cannot establish what the room holds.
s = make()
res = s.roomPlanDeleteRoom('133')
expect('delete refuses when the room cannot be read', !res.ok)
expect('delete says nothing was deleted', res.reason.contains('not deleted'))

s = make()
s.binding.setVariable('onFetch', { String url -> [ok: true, data: tree('Study', ['42', '43'])] })
res = s.roomPlanDeleteRoom('133')
expect('a room that goes succeeds', res.ok)
expect('delete reports the devices it freed', res.freed == 2)
expect('freed devices are unallocated in panel state',
       s.binding.getVariable('state').deviceRooms == ['42': '', '43': ''])

s = make()
s.binding.setVariable('rooms', [[id: '133', name: 'Study']])
s.binding.setVariable('onFetch', { String url -> [ok: true, data: tree('Study', [])] })
res = s.roomPlanDeleteRoom('133')
expect('a room the hub still lists fails', !res.ok)

println "${checks} room write assertions passed"
