// Flow panel resize (v2.2.9) against the REAL functions extracted from
// apps/automation_map.groovy, never a copy. The page script lives inside a
// Groovy GString, so each function is located by brace matching and evaluated
// here with a stubbed panel, grip, document and window.
//
// The defaults matter as much as the feature: a panel the user never resizes
// must behave exactly as before, and a size chosen for the normal flow view must
// never leak into the full-area Insights view.
//
// Usage: node tests/flow-panel-resize.js
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SRC = path.join(__dirname, '..', 'apps', 'automation_map.groovy');
const source = fs.readFileSync(SRC, 'utf8');

function extractFunction(name) {
    const start = source.indexOf('function ' + name + '(');
    if (start < 0) throw new Error('could not find function ' + name);
    const open = source.indexOf('{', start);
    let depth = 0;
    for (let i = open; i < source.length; i++) {
        const c = source[i];
        if (c === '{') depth++;
        else if (c === '}') {
            depth--;
            if (depth === 0) return source.slice(start, i + 1);
        }
    }
    throw new Error('unbalanced braces extracting ' + name);
}

const blockStart = source.indexOf('// Flow panel resize (v2.2.9).');
const blockEnd = source.indexOf('// End flow panel resize.');
const block = blockStart >= 0 && blockEnd > blockStart ? source.slice(blockStart, blockEnd) : '';

let pass = 0, fail = 0;
function check(name, fn) {
    try { fn(); console.log('PASS  ' + name); pass++; }
    catch (e) { console.log('FAIL  ' + name + ' - ' + e.message); fail++; }
}
function assert(cond, msg) { if (!cond) throw new Error(msg || 'assertion failed'); }

function classList(initial) {
    const set = new Set(initial || []);
    return {
        contains: function (c) { return set.has(c); },
        add: function (c) { set.add(c); },
        remove: function (c) { set.delete(c); },
        toggle: function (c, on) { if (on) set.add(c); else set.delete(c); }
    };
}

function makeSandbox(options) {
    const opts = options || {};
    const listeners = { mousemove: [], mouseup: [] };
    const gripListeners = {};
    const rect = { left: 10, top: 200, width: 360, height: 300 };
    const panel = {
        style: {},
        classList: classList(opts.large ? ['modernPanel', 'modernPanelLarge'] : ['modernPanel', 'flowClassicSize']),
        getBoundingClientRect: function () { return rect; }
    };
    const grip = { addEventListener: function (type, fn) { gripListeners[type] = fn; } };
    const sizeCalls = [];
    const sandbox = {
        Math: Math,
        rect: rect,
        flowPanel: panel,
        grip: grip,
        gripListeners: gripListeners,
        listeners: listeners,
        sizeCalls: sizeCalls,
        panelCustomPosition: new WeakMap(),
        sizeModernPanel: function (p) { sizeCalls.push(p); },
        window: { innerWidth: opts.innerWidth || 1600, innerHeight: opts.innerHeight || 1000 },
        document: { addEventListener: function (type, fn) { if (listeners[type]) listeners[type].push(fn); } }
    };
    const script =
        'var flowUserSize = null;\nconst FLOW_MIN_WIDTH = 280;\nconst FLOW_MIN_HEIGHT = 160;\n' +
        ['clampFlowSize', 'applyFlowUserSize', 'clearFlowInlineSize', 'makeFlowResizable',
         'resetFlowPanelLayout', 'setFlowSizeMode'].map(extractFunction).join('\n') + '\n' +
        'makeFlowResizable(flowPanel, grip);\n' +
        'function userSize() { return flowUserSize; }\n';
    vm.createContext(sandbox);
    vm.runInContext(script, sandbox);
    return sandbox;
}

function drag(sb, dx, dy) {
    let prevented = false, stopped = false;
    sb.gripListeners.mousedown({ clientX: 500, clientY: 500,
        preventDefault: function () { prevented = true; }, stopPropagation: function () { stopped = true; } });
    sb.listeners.mousemove.forEach(function (fn) { fn({ clientX: 500 + dx, clientY: 500 + dy }); });
    sb.listeners.mouseup.forEach(function (fn) { fn({}); });
    return { prevented: prevented, stopped: stopped };
}

// ---- resizing -----------------------------------------------------------------

check('dragging the grip sets an explicit size and lifts both caps', function () {
    const sb = makeSandbox();
    drag(sb, 240, 180);
    const st = sb.flowPanel.style;
    assert(st.width === '600px' && st.height === '480px', 'size ' + st.width + ' x ' + st.height);
    assert(st.maxWidth === 'none' && st.maxHeight === 'none', 'caps not lifted');
    assert(sb.flowPanel.classList.contains('flowUserSized'), 'text sections not released');
});

check('the grip press does not start a header drag or text selection', function () {
    const sb = makeSandbox();
    const r = drag(sb, 10, 10);
    assert(r.prevented && r.stopped, 'event not contained');
});

check('a resize keeps the panel where it is on its next open', function () {
    const sb = makeSandbox();
    drag(sb, 40, 40);
    assert(sb.panelCustomPosition.get(sb.flowPanel) === true, 'position not kept');
});

check('the size cannot go below the minimum', function () {
    const sb = makeSandbox();
    drag(sb, -900, -900);
    assert(sb.flowPanel.style.width === '280px' && sb.flowPanel.style.height === '160px',
        'size ' + sb.flowPanel.style.width + ' x ' + sb.flowPanel.style.height);
});

check('the size cannot run past the edge of the window', function () {
    const sb = makeSandbox({ innerWidth: 1000, innerHeight: 700 });
    drag(sb, 5000, 5000);
    // left 10 and top 200, with a 10px margin kept at the far edges.
    assert(sb.flowPanel.style.width === '980px' && sb.flowPanel.style.height === '490px',
        'size ' + sb.flowPanel.style.width + ' x ' + sb.flowPanel.style.height);
});

check('the grip does nothing in the full-area Insights view', function () {
    const sb = makeSandbox({ large: true });
    drag(sb, 200, 200);
    assert(!sb.flowPanel.style.width && !sb.flowPanel.style.height, 'resized a large panel');
    assert(sb.userSize() === null, 'recorded a size');
});

check('moving the mouse without pressing the grip changes nothing', function () {
    const sb = makeSandbox();
    sb.listeners.mousemove.forEach(function (fn) { fn({ clientX: 900, clientY: 900 }); });
    assert(!sb.flowPanel.style.width && sb.userSize() === null, 'resized without a press');
});

// ---- opening the panel again ----------------------------------------------------

check('without a user size, opening the normal view behaves exactly as before', function () {
    const sb = makeSandbox();
    sb.flowPanel.style.height = '400px';
    sb.setFlowSizeMode(false);
    assert(sb.flowPanel.style.width === '', 'width not cleared');
    assert(sb.flowPanel.style.height === '400px' && sb.flowPanel.style.maxWidth === undefined,
        'touched properties the old code never touched');
    assert(!sb.flowPanel.classList.contains('flowUserSized'), 'text sections released');
});

check('without a user size, opening Insights behaves exactly as before', function () {
    const sb = makeSandbox();
    sb.flowPanel.style.width = '1200px';
    sb.flowPanel.style.height = '900px';
    sb.setFlowSizeMode(true);
    assert(sb.flowPanel.style.width === '1200px' && sb.flowPanel.style.height === '900px', 'size changed');
    assert(sb.sizeCalls.length === 0, 'resized Insights unasked');
});

check('the normal view reopens at the size the user chose', function () {
    const sb = makeSandbox();
    drag(sb, 240, 180);
    sb.flowPanel.style.width = '';
    sb.flowPanel.style.maxHeight = '300px';
    sb.setFlowSizeMode(false);
    assert(sb.flowPanel.style.width === '600px' && sb.flowPanel.style.height === '480px', 'size not reapplied');
    assert(sb.flowPanel.style.maxHeight === 'none', 'height cap came back');
});

check('a user size is never carried into Insights', function () {
    const sb = makeSandbox();
    drag(sb, 240, 180);
    sb.setFlowSizeMode(true);
    const st = sb.flowPanel.style;
    assert(st.width === '' && st.height === '' && st.maxWidth === '' && st.maxHeight === '', 'user size leaked');
    assert(!sb.flowPanel.classList.contains('flowUserSized'), 'text release leaked');
    assert(sb.sizeCalls.length === 1 && sb.sizeCalls[0] === sb.flowPanel, 'Insights not re-measured');
});

check('returning from Insights restores the chosen size to the normal view', function () {
    const sb = makeSandbox();
    drag(sb, 240, 180);
    sb.setFlowSizeMode(true);
    sb.setFlowSizeMode(false);
    assert(sb.flowPanel.style.width === '600px' && sb.flowPanel.style.height === '480px', 'size lost');
});

// ---- reset ------------------------------------------------------------------------

check('resetting clears the size, the kept position and re-measures the panel', function () {
    const sb = makeSandbox();
    drag(sb, 240, 180);
    sb.resetFlowPanelLayout();
    const st = sb.flowPanel.style;
    assert(sb.userSize() === null, 'size kept');
    assert(sb.panelCustomPosition.get(sb.flowPanel) === undefined, 'position kept');
    assert(st.width === '' && st.height === '' && st.maxWidth === '' && st.maxHeight === '', 'inline size kept');
    assert(sb.sizeCalls.length === 1, 'not re-measured');
    sb.setFlowSizeMode(false);
    assert(sb.flowPanel.style.width === '', 'size came back after reset');
});

// ---- source and markup ------------------------------------------------------------

check('the resize block is present and delimited', function () {
    assert(block.length > 0, 'block markers missing');
});
check('the resize block is plain ASCII', function () {
    for (let i = 0; i < block.length; i++) {
        if (block.charCodeAt(i) > 126) throw new Error('non-ASCII at offset ' + i);
    }
});
check('the resize block has no backslash, dollar sign or template literal for Groovy to consume', function () {
    assert(block.indexOf('\\') < 0, 'backslash present');
    assert(block.indexOf('$') < 0, 'dollar sign present');
    assert(block.indexOf('`') < 0, 'backtick present');
});
check('the drag helper and the other panels are untouched', function () {
    // Its own comment already mentions resizing, so look for what this change
    // would introduce rather than for the word.
    const drag = extractFunction('makePanelDraggable');
    ['flowUserSize', 'clampFlowSize', 'applyFlowUserSize', 'clearFlowInlineSize', 'flowUserSized', 'flowResize']
        .forEach(function (n) { assert(drag.indexOf(n) < 0, 'drag helper references ' + n); });
    assert(block.indexOf('allPanels') < 0 && block.indexOf('extPanel') < 0 && block.indexOf('pivotPanel') < 0,
        'reaches other panels');
});
check('the grip sits outside the scrolling body, as the last child of the panel', function () {
    assert(source.indexOf('<div id="communityCard"></div></div><div id="flowResize" class="panelResizeGrip" title="Drag to resize"></div></div>') >= 0,
        'grip markup missing or misplaced');
});
check('the grip is hidden for Insights and the release rule wins over the column caps', function () {
    const grip = source.indexOf('#flow.modernPanelLarge .panelResizeGrip { display:none; }');
    const release = source.indexOf('#flow.flowUserSized #communityCard { max-width:none; }');
    const cap = source.indexOf('#flow.wcIndent #flowSub { max-width:');
    assert(grip >= 0, 'grip not hidden for Insights');
    assert(release > cap && cap >= 0, 'release rule does not come after the column caps');
});

console.log(pass + ' passed, ' + fail + ' failed');
if (fail > 0) process.exit(1);
