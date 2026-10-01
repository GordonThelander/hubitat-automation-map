// Room Manager live-room source-of-truth contract.
//
// Revision 55 rendered rooms from the live hub payload but still staged moves
// and counted unallocation writes from the older scan field `d.room`. A device
// displayed in its live room could therefore be dragged to the stale scan room
// and silently produce no staged move. These tests execute the real browser
// functions extracted from the Groovy page source.
//
// Usage: node tests/room-manager-live-source.js
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SRC = path.join(__dirname, '..', 'apps', 'automation_map.groovy');
const source = fs.readFileSync(SRC, 'utf8');

function extractFunction(name) {
    const start = source.indexOf('function ' + name + '(');
    if (start < 0) throw new Error('could not find function ' + name);
    let depth = 0;
    for (let i = source.indexOf('{', start); i < source.length; i++) {
        if (source[i] === '{') depth++;
        else if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
    }
    throw new Error('unbalanced ' + name);
}

let pass = 0, fail = 0;
function check(name, fn) {
    try { fn(); console.log('PASS  ' + name); pass++; }
    catch (e) { console.log('FAIL  ' + name + ' - ' + e.message); fail++; }
}
function assert(cond, msg) { if (!cond) throw new Error(msg || 'assertion failed'); }

function make(live, scanRoom) {
    const sandbox = {
        ROOMPLAN: { live: live, deviceRooms: live ? { '1': 'Live Room' } : {} },
        ICONS: { devices: [{ id: '1', name: 'Test device', room: scanRoom }] },
        roomPending: {},
        renderCount: 0,
        roomPlanRender: function () { sandbox.renderCount++; }
    };
    vm.createContext(sandbox);
    vm.runInContext(
        "const RP_UNASSIGNED = '';" +
        "const RP_NO_ROOM_NAMES = ['unassigned','no assigned room','no room assigned','none'];\n" +
        extractFunction('roomPlanNormalise') + '\n' +
        extractFunction('roomPlanActual') + '\n' +
        extractFunction('roomPlanCurrent') + '\n' +
        extractFunction('roomPlanStage'),
        sandbox
    );
    return sandbox;
}

check('live room overrides a stale scan room', function () {
    const sb = make(true, 'Stale Room');
    assert(sb.roomPlanActual(sb.ICONS.devices[0]) === 'Live Room');
});

check('dragging to the stale scan room stages a real move', function () {
    const sb = make(true, 'Stale Room');
    sb.roomPlanStage('1', 'Stale Room', true);
    assert(sb.roomPending['1'] === 'Stale Room', 'move was silently discarded');
});

check('dragging back to the live origin clears the staged move', function () {
    const sb = make(true, 'Stale Room');
    sb.roomPlanStage('1', 'Stale Room', true);
    sb.roomPlanStage('1', 'Live Room', true);
    assert(!Object.prototype.hasOwnProperty.call(sb.roomPending, '1'));
});

check('current room shows a staged target without changing the actual origin', function () {
    const sb = make(true, 'Stale Room');
    sb.roomPlanStage('1', 'Stale Room', true);
    assert(sb.roomPlanCurrent(sb.ICONS.devices[0]) === 'Stale Room');
    assert(sb.roomPlanActual(sb.ICONS.devices[0]) === 'Live Room');
});

check('a device absent from a successful live read is unallocated', function () {
    const sb = make(true, 'Stale Room');
    sb.ROOMPLAN.deviceRooms = {};
    assert(sb.roomPlanActual(sb.ICONS.devices[0]) === '');
});

check('scan room is used only when the live read failed', function () {
    const sb = make(false, 'Scan Room');
    assert(sb.roomPlanActual(sb.ICONS.devices[0]) === 'Scan Room');
});

check('Room Manager no longer reads d.room outside the fallback function', function () {
    const names = extractFunction('roomPlanNames');
    const actual = extractFunction('roomPlanActual');
    const stage = extractFunction('roomPlanStage');
    const apply = extractFunction('roomPlanApply');
    const codeOnly = function (s) { return s.split(/\r?\n/).filter(line => !line.trim().startsWith('//')).join('\n'); };
    assert(names.includes('if (!ROOMPLAN.live)'), 'room-name fallback is not live-read guarded');
    assert((codeOnly(names).match(/\bd\.room\b/g) || []).length === 1, 'room names have an unguarded scan-room read');
    assert((codeOnly(actual).match(/\bd\.room\b/g) || []).length === 1, 'actual-room resolver has extra scan-room reads');
    assert(!/\bd\.room\b|\bdev\.room\b/.test(codeOnly(stage)), 'staging still reads a scan room');
    assert(!/\bd\.room\b|\bdev\.room\b/.test(codeOnly(apply)), 'apply cost still reads a scan room');
});

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
