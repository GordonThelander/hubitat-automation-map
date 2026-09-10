// Inert-shelf pins in a narrowed view (backlog 29 G framing), against the REAL
// helper and applyFilters source extracted from apps/automation_map.groovy. On the
// dev hub an external system's inert app stayed pinned to the whole-map shelf
// inside the system's own two-node view, so the pair sat at opposite canvas edges.
//
// Usage: node tests/narrow-view-shelf.js
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

const sandbox = { INERT_POS: { a3057: { x: -2000, y: 1400 } } };
vm.createContext(sandbox);
vm.runInContext(extractFunction('releaseShelfPins'), sandbox);

function pinned() {
    return [
        { id: 'a3057', x: -2000, y: 1400, fixed: { x: true, y: true }, physics: false, shapeProperties: { borderDashes: [4, 3] } },
        { id: 'xmcp', mass: 3 }
    ];
}

check('a shelf-pinned node in a narrowed view is released to the layout', function () {
    const styled = pinned();
    sandbox.releaseShelfPins(styled);
    const n = styled[0];
    assert(n.x === undefined && n.y === undefined, 'shelf coordinates kept');
    assert(n.fixed === false && n.physics === true, 'still pinned: fixed ' + JSON.stringify(n.fixed) + ' physics ' + n.physics);
});

check('releasing the pin keeps how the inert node looks', function () {
    const styled = pinned();
    sandbox.releaseShelfPins(styled);
    assert(styled[0].shapeProperties.borderDashes.join(',') === '4,3', 'dashed outline lost');
});

check('nodes that were never on the shelf are left alone', function () {
    const styled = pinned();
    sandbox.releaseShelfPins(styled);
    assert(JSON.stringify(styled[1]) === JSON.stringify({ id: 'xmcp', mass: 3 }), 'other node changed');
});

check('applyFilters releases the pins only for a narrowed view, before the nodes are drawn', function () {
    const body = extractFunction('applyFilters');
    const call = body.indexOf('if (ids !== null) releaseShelfPins(styled);');
    assert(call >= 0, 'applyFilters does not release pins for a narrowed view');
    assert(call < body.indexOf('nodes.add(styled)'), 'pins released after the nodes are drawn');
});

check('the whole map still pins the shelf', function () {
    const styledNode = extractFunction('styledNode');
    assert(styledNode.indexOf('styled.fixed = { x: true, y: true };') >= 0, 'shelf pin removed from the whole-map styling');
});

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
