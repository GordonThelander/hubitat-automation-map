// Synthetic-JSON fixture for the v2.1.7 hierarchical device-discovery fix
// (Bucket/Queue 338-342). /hub2/devicesList returns every entry as
// {key, data, child, parent, children} - a device-owned component device
// (isComponent: true, created by a parent device driver - Shelly, Bond, a
// Matter bridge, this hub's own Hub Variable Connectors) can be represented
// nested inside its parent's own children array rather than as a top-level
// sibling. The pre-fix fetchDeviceListBulk() only read top-level entries.
// Run with: groovy tests/device-tree-discovery.groovy
//
// aggregateDeviceTree() below is copied verbatim from
// apps/automation_map.groovy - keep the two in sync by hand; there is no
// shared-module mechanism between this standalone script and the Hubitat
// app source.

Map aggregateDeviceTree(Map data) {
    Map out = [labels: [:], rooms: [:], types: [:], typeGroups: [:], parents: [:], disabledDevices: [], error: null]
    Map<String, Map> byId = [:]
    List order = []
    List pending = []
    (data.devices ?: []).each { pending << [node: it, parentId: null] }
    while (pending) {
        Map item = pending.remove(0) as Map
        def node = item.node
        if (!(node instanceof Map)) continue
        Map entry = node as Map
        Map d = (entry.data instanceof Map) ? (entry.data as Map) : null
        String entryId = (d && d.id != null) ? "${d.id}" : null
        List kids = (entry.children instanceof List) ? (entry.children as List) : null
        if (kids) kids.each { pending << [node: it, parentId: entryId] }
        if (entryId == null || d == null) continue
        Map agg = byId[entryId]
        if (agg == null) {
            agg = [:]
            byId[entryId] = agg
            order << entryId
        }
        if (!agg.name && d.name) agg.name = "${d.name}"
        String room = d.roomName == null ? '' : "${d.roomName}".trim()
        if (!agg.room && room) agg.room = room
        if (!agg.type && d.type) agg.type = "${d.type}"
        if (agg.deviceTypeId == null && d.deviceTypeId != null) agg.deviceTypeId = "${d.deviceTypeId}"
        if (!agg.parentId && item.parentId) agg.parentId = item.parentId as String
        if (agg.disabled == null && d.containsKey('disabled')) agg.disabled = (d.disabled == true)
    }
    Map typeGroups = [:]
    order.each { String devId ->
        Map agg = byId[devId] as Map
        out.labels[devId] = (agg.name ?: "Device ${devId}") as String
        if (agg.room) out.rooms[devId] = agg.room as String
        if (agg.type) out.types[devId] = agg.type as String
        if (agg.parentId) out.parents[devId] = agg.parentId as String
        if (agg.disabled == true) (out.disabledDevices as List) << devId
        String typeKey = (agg.room && agg.deviceTypeId != null) ? "${agg.deviceTypeId}" : "room:${devId}"
        List group = (typeGroups[typeKey] = typeGroups[typeKey] ?: []) as List
        group << devId
    }
    out.typeGroups = typeGroups
    return out
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

def entry = { int id, String name, String room = null, String type = null, Object typeId = null,
              boolean isChild = false, boolean isParent = false, List children = [] ->
    [key: "DEV-${id}", data: [id: id, name: name, roomName: room, type: type, deviceTypeId: typeId],
     child: isChild, parent: isParent, children: children]
}

println '--- 1. Flat response, ordinary devices (baseline, no nesting involved) ---'
def d1 = [devices: [
    entry(1, 'Kitchen Light', 'Kitchen', 'Generic Zigbee Bulb', 100),
    entry(2, 'Lounge Switch', 'Lounge', 'Generic Zigbee Switch', 101),
]]
def r1 = aggregateDeviceTree(d1)
check('flat: both devices discovered') { assert r1.labels == ['1': 'Kitchen Light', '2': 'Lounge Switch'] }
check('flat: rooms correct') { assert r1.rooms == ['1': 'Kitchen', '2': 'Lounge'] }
check('flat: types correct') { assert r1.types == ['1': 'Generic Zigbee Bulb', '2': 'Generic Zigbee Switch'] }
check('flat: grouped by deviceTypeId, one device each') {
    assert r1.typeGroups == ['100': ['1'], '101': ['2']]
}
check('flat: no parent recorded for top-level devices') { assert r1.parents.isEmpty() }

println '--- 2. Parent with one unused nested component (the reported defect) ---'
def d2 = [devices: [
    entry(10, 'Shelly Uni Plus', 'Garage', 'Shelly Plus/Pro xPM', 200, false, true,
        [entry(11, 'Shelly Uni Plus Input 1', null, 'Generic Component Input Event', 201, true)])
]]
def r2 = aggregateDeviceTree(d2)
check('nested: parent discovered') { assert r2.labels['10'] == 'Shelly Uni Plus' }
check('nested: previously-invisible child now discovered') {
    assert r2.labels['11'] == 'Shelly Uni Plus Input 1'
}
check('nested: child parent identity recorded') { assert r2.parents['11'] == '10' }
check('nested: child has no parent entry recorded for itself, only as a value') {
    assert !r2.parents.containsKey('10')
}
check('nested: child with no room gets the one-device room:<id> fallback group') {
    assert r2.typeGroups['room:11'] == ['11']
}

println '--- 3. Multiple nested siblings ---'
def d3 = [devices: [
    entry(20, 'Aqara M100 Matter Bridge', 'Weather Station', 'Generic Matter Bridge', 300, false, true, [
        entry(21, 'Aqara Contact Sensor', 'Weather Station', 'Generic Component Contact Sensor', 301, true),
        entry(22, 'Aqara Temp Sensor - Battery', 'Weather Station', 'Generic Component Battery', 302, true),
        entry(23, 'Aqara Temp Sensor - Temperature', 'Weather Station', 'Generic Component Temperature Sensor', 303, true),
    ])
]]
def r3 = aggregateDeviceTree(d3)
check('siblings: all three children discovered') {
    assert r3.labels.keySet().containsAll(['21', '22', '23'])
}
check('siblings: all three share the same parent') {
    assert r3.parents['21'] == '20' && r3.parents['22'] == '20' && r3.parents['23'] == '20'
}
check('siblings: each sibling is its own typeGroup (different deviceTypeId)') {
    assert r3.typeGroups['301'] == ['21'] && r3.typeGroups['302'] == ['22'] && r3.typeGroups['303'] == ['23']
}

println '--- 4. Nesting deeper than one level ---'
def d4 = [devices: [
    entry(30, 'Bridge', 'Hub Room', 'Bridge Driver', 400, false, true, [
        entry(31, 'Sub-bridge', null, 'Sub-bridge Driver', 401, true, true, [
            entry(32, 'Leaf Component', null, 'Generic Component Switch', 402, true)
        ])
    ])
]]
def r4 = aggregateDeviceTree(d4)
check('deep nesting: grandchild discovered') { assert r4.labels['32'] == 'Leaf Component' }
check('deep nesting: grandchild parent is its IMMEDIATE parent, not the root') {
    assert r4.parents['32'] == '31'
}
check('deep nesting: middle node also correctly parented to the root') {
    assert r4.parents['31'] == '30'
}

println '--- 5. Same device ID at top level and nested (dedup) ---'
def d5 = [devices: [
    entry(40, 'Duplicate Top', 'Office', 'Driver A', 500),
    entry(41, 'Other Parent', 'Office', 'Driver B', 501, false, true, [
        entry(40, 'Duplicate Nested', 'Office', 'Driver A', 500, true),
    ]),
]]
def r5 = aggregateDeviceTree(d5)
check('duplicate id: appears exactly once across all typeGroups') {
    int occurrences = r5.typeGroups.values().flatten().count { it == '40' }
    assert occurrences == 1
}
check('duplicate id: first-seen (top-level) label wins, not overwritten by the second encounter') {
    assert r5.labels['40'] == 'Duplicate Top'
}

println '--- 5b. Partial top-level record enriched by a richer nested record (Codex correction 339#1) ---'
def d5b = [devices: [
    [key: 'DEV-50', data: [id: 50, name: 'Partial Device'], child: false, parent: false, children: []],
    entry(60, 'Enricher Parent', 'Office', 'Driver C', 600, false, true, [
        [key: 'DEV-50', data: [id: 50, name: 'Partial Device', roomName: 'Office',
                                type: 'Generic Component Something', deviceTypeId: 700],
         child: true, parent: false, children: []],
    ]),
]]
def r5b = aggregateDeviceTree(d5b)
check('enrichment: room filled in from the nested record') { assert r5b.rooms['50'] == 'Office' }
check('enrichment: type filled in from the nested record') {
    assert r5b.types['50'] == 'Generic Component Something'
}
check('enrichment: typeGroups reflects the ENRICHED state (real deviceTypeId key), not the room:<id> fallback the sparse top-level record alone would have produced') {
    assert r5b.typeGroups['700'] == ['50']
    assert !r5b.typeGroups.containsKey('room:50')
}
check('enrichment: appears exactly once, not duplicated by the second encounter') {
    assert r5b.typeGroups.values().flatten().count { it == '50' } == 1
}

println '--- 6. Nested device with no room (fallback grouping) ---'
// Covered directly by test 2's dedicated assertion above; repeated here with
// a sibling that DOES have a room, to prove the fallback is per-device, not
// driven by whether ANY device in the tree has a room.
def d6 = [devices: [
    entry(70, 'Parent With Room', 'Den', 'Driver D', 800, false, true, [
        entry(71, 'Roomed Child', 'Den', 'Generic Component Switch', 801, true),
        entry(72, 'Roomless Child', null, 'Generic Component Switch', 801, true),
    ])
]]
def r6 = aggregateDeviceTree(d6)
check('mixed room presence: roomed child groups by deviceTypeId') {
    assert r6.typeGroups['801'] == ['71']
}
check('mixed room presence: roomless sibling gets its own one-device fallback group') {
    assert r6.typeGroups['room:72'] == ['72']
}

println '--- 7. Nested device with missing optional fields (graceful degradation) ---'
def d7 = [devices: [
    [key: 'DEV-80', data: [id: 80, name: 'Bare Component'], child: true, parent: false, children: []],
]]
def r7 = aggregateDeviceTree(d7)
check('bare component: name still discovered') { assert r7.labels['80'] == 'Bare Component' }
check('bare component: no room recorded') { assert !r7.rooms.containsKey('80') }
check('bare component: no type recorded') { assert !r7.types.containsKey('80') }
check('bare component: does not throw, still lands in a typeGroup') {
    assert r7.typeGroups['room:80'] == ['80']
}

println '--- 8. Null/malformed data on an entry does not block its valid children ---'
def d8 = [devices: [
    [key: 'DEV-BROKEN', data: null, child: false, parent: true, children: [
        entry(90, 'Recoverable Child', 'Lounge', 'Generic Component Switch', 900, true),
    ]],
    [key: 'DEV-BROKEN-2', child: false, parent: true, children: [
        entry(91, 'Recoverable Child 2', 'Lounge', 'Generic Component Switch', 900, true),
    ]],
]]
def r8 = aggregateDeviceTree(d8)
check('malformed parent (null data): its own id is skipped without throwing') {
    assert !r8.labels.containsKey('null')
}
check('malformed parent (null data): valid child still discovered') {
    assert r8.labels['90'] == 'Recoverable Child'
}
check('malformed parent (missing data key entirely): valid child still discovered') {
    assert r8.labels['91'] == 'Recoverable Child 2'
}

println '--- 9. Empty or absent children field ---'
def d9 = [devices: [
    entry(100, 'Empty Children', 'Kitchen', 'Driver E', 1000, false, false, []),
    [key: 'DEV-101', data: [id: 101, name: 'No Children Key', roomName: 'Kitchen', type: 'Driver E', deviceTypeId: 1000],
     child: false, parent: false],
]]
def r9 = aggregateDeviceTree(d9)
check('empty children list does not throw and device is still discovered') {
    assert r9.labels['100'] == 'Empty Children'
}
check('absent children key does not throw and device is still discovered') {
    assert r9.labels['101'] == 'No Children Key'
}

println '--- 10. Existing flat behaviour and type grouping unchanged (regression) ---'
def d10 = [devices: [
    entry(110, 'Roomless Top-Level Device', null, 'Driver F', 1100),
    entry(111, 'Roomed Top-Level Device', 'Study', 'Driver F', 1100),
]]
def r10 = aggregateDeviceTree(d10)
check('regression: roomless top-level device keeps the room:<id> fallback') {
    assert r10.typeGroups['room:110'] == ['110']
}
check('regression: roomed top-level device groups normally by deviceTypeId') {
    assert r10.typeGroups['1100'] == ['111']
}

println '--- 11. Wrong-type data and children (Codex correction 346#1) ---'
def d11 = [devices: [
    // data is a String, not a Map - must be treated as absent, not thrown on,
    // and must not block a well-formed sibling from being processed.
    [key: 'DEV-BAD-1', data: 'not-a-map', child: false, parent: true, children: [
        entry(120, 'Sibling Of Bad Data Parent', 'Attic', 'Driver G', 1200, true),
    ]],
    // children is a Map, not a List - must be treated as absent (no further
    // descent), not thrown on. The entry's own valid data is still used.
    [key: 'DEV-BAD-2', data: [id: 121, name: 'Bad Children Type', roomName: 'Attic',
                              type: 'Driver G', deviceTypeId: 1200],
     child: false, parent: true, children: [orphan: 'this should be a List']],
]]
def r11 = aggregateDeviceTree(d11)
check('wrong-type data: does not throw, and does not fabricate an entry for it') {
    assert !r11.labels.containsKey('null')
}
check('wrong-type data: sibling nested beneath the bad parent is still discovered') {
    assert r11.labels['120'] == 'Sibling Of Bad Data Parent'
}
check('wrong-type children: the entry\'s own valid data is still used') {
    assert r11.labels['121'] == 'Bad Children Type'
}
check('wrong-type children: no exception propagates from the malformed collection') {
    assert true  // reaching this line at all is the assertion
}

println '--- 12. Device ID with no name anywhere (Codex correction 346#2) ---'
def d12 = [devices: [
    [key: 'DEV-130', data: [id: 130, roomName: 'Garage', type: 'Driver H', deviceTypeId: 1300],
     child: false, parent: false, children: []],
]]
def r12 = aggregateDeviceTree(d12)
check('id-only device retained under the "Device <id>" fallback identity') {
    assert r12.labels['130'] == 'Device 130'
}
check('id-only device still gets its real room and type despite the missing name') {
    assert r12.rooms['130'] == 'Garage' && r12.types['130'] == 'Driver H'
}

println '--- 13. Roomed entry with no deviceTypeId (Codex correction 346#3) ---'
def d13 = [devices: [
    [key: 'DEV-140', data: [id: 140, name: 'Roomed No TypeId A', roomName: 'Study'],
     child: false, parent: false, children: []],
    [key: 'DEV-141', data: [id: 141, name: 'Roomed No TypeId B', roomName: 'Study'],
     child: false, parent: false, children: []],
]]
def r13 = aggregateDeviceTree(d13)
check('roomed device with no deviceTypeId gets its own one-device fallback group, not a shared "null" group') {
    assert r13.typeGroups['room:140'] == ['140']
    assert r13.typeGroups['room:141'] == ['141']
    assert !r13.typeGroups.containsKey('null')
}

println '--- 14. disabled boolean captured per device (item 18) ---'
def d14 = [devices: [
    [key: 'DEV-150', data: [id: 150, name: 'Disabled Sensor', roomName: 'Hall', disabled: true],
     child: false, parent: false, children: []],
    [key: 'DEV-151', data: [id: 151, name: 'Active Sensor', roomName: 'Hall', disabled: false],
     child: false, parent: false, children: []],
    [key: 'DEV-152', data: [id: 152, name: 'No Field Reported'],
     child: false, parent: false, children: []],
]]
def r14 = aggregateDeviceTree(d14)
check('a device reporting disabled true is in disabledDevices') { assert r14.disabledDevices.contains('150') }
check('a device reporting disabled false is not in disabledDevices') { assert !r14.disabledDevices.contains('151') }
check('a device that never reports the field at all is not in disabledDevices') { assert !r14.disabledDevices.contains('152') }
check('disabledDevices contains exactly the one truly-disabled device') { assert r14.disabledDevices == ['150'] }

println '--- 15. disabled: false from the first record is not shadowed by a later record reporting true (Codex review 372: the discriminating case) ---'
def d15 = [devices: [
    [key: 'DEV-160', data: [id: 160, name: 'Reports False Then True', disabled: false],
     child: false, parent: true, children: [
        [key: 'DEV-160', data: [id: 160, name: 'Reports False Then True', disabled: true],
         child: false, parent: true, children: []],
     ]],
]]
def r15 = aggregateDeviceTree(d15)
check('the first record explicitly reporting false wins - a later record reporting true does not overwrite it') {
    assert !r15.disabledDevices.contains('160')
    assert r15.disabledDevices.isEmpty()
}

println '--- 16. disabled: false from the first record is not shadowed by a later record omitting the field entirely ---'
def d16 = [devices: [
    [key: 'DEV-161', data: [id: 161, name: 'Reports False Then Omits', disabled: false],
     child: false, parent: true, children: [
        [key: 'DEV-161', data: [id: 161, name: 'Reports False Then Omits'],
         child: false, parent: true, children: []],
     ]],
]]
def r16 = aggregateDeviceTree(d16)
check('the first record explicitly reporting false wins - a later record silent on the field does not overwrite it') {
    assert !r16.disabledDevices.contains('161')
    assert r16.disabledDevices.isEmpty()
}

println '--- 17. disabled: true from the first record is not shadowed by a later record reporting false (the reverse direction) ---'
def d17 = [devices: [
    [key: 'DEV-162', data: [id: 162, name: 'Reports True Then False', disabled: true],
     child: false, parent: true, children: [
        [key: 'DEV-162', data: [id: 162, name: 'Reports True Then False', disabled: false],
         child: false, parent: true, children: []],
     ]],
]]
def r17 = aggregateDeviceTree(d17)
check('the first record explicitly reporting true wins - a later record reporting false does not overwrite it') {
    assert r17.disabledDevices == ['162']
}

println ''
println "${pass} passed, ${fail} failed"
System.exit(fail == 0 ? 0 : 1)
