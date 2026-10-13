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

// --- Placement of rooms with no saved position (#91, AM #54) ----------------------------------------------
// The real geometry, and the real placement lines of roomPlanRender (from its room index to the first use of a
// box), run over invented rooms. Not Allocated can be dragged and resized like any room; when it was moved onto
// the grid a room created on the hub could still land under it.
function placer(layout) {
    const render = extractFunction('roomPlanRender');
    const from = render.indexOf('  let roomIndex = -1;');
    const to = render.indexOf('    maxBottom = Math.max(maxBottom');
    if (from < 0 || to < from) throw new Error('roomPlanRender placement block not found');
    const sandbox = { ROOMPLAN: { layout: layout || {} } };
    vm.createContext(sandbox);
    const consts = ['RP_UNASSIGNED', 'RP_W', 'RP_UNALLOC_W'].map(function (n) {
        const at = source.indexOf('const ' + n + ' =');
        return source.slice(at, source.indexOf('\n', at));
    }).join('\n');
    vm.runInContext(consts + '\n' + extractFunction('roomPlanLayoutKey') + '\n' + extractFunction('roomPlanGeom') + '\n' +
        'function place(buckets, perRow, columnHeight) {\n  const out = {};\n' + render.slice(from, to) +
        '    out[key] = g;\n  });\n  return out;\n}', sandbox);
    return sandbox;
}
function overlaps(a, b) { return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h; }
function noOverlaps(boxes) {
    const keys = Object.keys(boxes);
    for (let i = 0; i < keys.length; i++) for (let j = i + 1; j < keys.length; j++) {
        if (overlaps(boxes[keys[i]], boxes[keys[j]])) return keys[i] + ' overlaps ' + keys[j];
    }
    return null;
}
const ROOMS = ['', 'Attic', 'Hall', 'Kitchen', 'Office', 'Porch'];

check('with nothing saved, Not Allocated leads and rooms fill the grid in reading order', function () {
    const sb = placer({});
    const b = sb.place(ROOMS, 3, 600);
    const left = 215 + 10;
    assert(b.__unallocated__.x === 0 && b.__unallocated__.y === 0, 'Not Allocated moved');
    assert(b.Attic.x === left && b.Attic.y === 0, 'first room not in the first slot');
    assert(b.Hall.x === left + 188 && b.Kitchen.x === left + 376 && b.Office.x === left && b.Office.y === 114,
           'rooms are not in reading order: ' + JSON.stringify(b));
    const why = noOverlaps(b);
    assert(!why, why);
});

check('a saved room keeps its saved geometry exactly', function () {
    const saved = { x: 640, y: 300, w: 250, h: 180 };
    const b = placer({ Kitchen: saved }).place(ROOMS, 3, 600);
    assert(JSON.stringify(b.Kitchen) === JSON.stringify(saved), 'saved geometry changed: ' + JSON.stringify(b.Kitchen));
});

check('a new room avoids rooms dragged onto its slot', function () {
    const left = 215 + 10;
    const b = placer({ Attic: { x: left, y: 0, w: 178, h: 104 }, Hall: { x: left + 188, y: 0, w: 178, h: 104 } })
        .place(ROOMS, 3, 600);
    const why = noOverlaps(b);
    assert(!why, why);
});

check('a new room never lands under a Not Allocated box moved onto the grid', function () {
    // Not Allocated dragged right and widened over the first two slots of the top row.
    const b = placer({ __unallocated__: { x: 230, y: 0, w: 360, h: 220 } }).place(ROOMS, 3, 600);
    assert(JSON.stringify(b.__unallocated__) === JSON.stringify({ x: 230, y: 0, w: 360, h: 220 }), 'Not Allocated moved');
    const why = noOverlaps(b);
    assert(!why, why);
});

check('an unmoved Not Allocated places rooms exactly as before it counted', function () {
    // Its default column ends before the first grid slot, so taking it into account changes nothing.
    const sb = placer({});
    const b = sb.place(ROOMS, 2, 600);
    const left = 215 + 10;
    ROOMS.slice(1).forEach(function (n, i) {
        const want = { x: left + (i % 2) * 188, y: Math.floor(i / 2) * 114, w: 178, h: 104 };
        assert(JSON.stringify(b[n]) === JSON.stringify(want), n + ' is at ' + JSON.stringify(b[n]));
    });
});

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
