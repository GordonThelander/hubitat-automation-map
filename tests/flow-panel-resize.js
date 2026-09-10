// Flow panel resize and placement (v2.2.9) against the REAL functions extracted
// from apps/automation_map.groovy, never a copy. The page script lives inside a
// Groovy GString, so each function is located by brace matching and evaluated
// here with a stubbed panel, header, grip, document and window.
//
// The defaults matter as much as the feature: a panel the user never resizes
// must open where it always did, every new selection starts from the default
// position, a size chosen for the normal flow view never leaks into the
// full-area Insights view, and the header can never end up off screen.
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

const DEFAULT_LEFT = '10px';
const DEFAULT_TOP = '367px';

function makeSandbox(options) {
    const opts = options || {};
    const listeners = { mousemove: [], mouseup: [] };
    const gripListeners = {};
    const rect = { left: 10, top: 200, width: 360, height: 300 };
    const panel = {
        style: {},
        classList: classList(opts.large ? ['modernPanel', 'modernPanelLarge'] : ['modernPanel', 'flowClassicSize']),
        getBoundingClientRect: function () { return rect; },
        get offsetWidth() { return parseFloat(panel.style.width) || 375; }
    };
    const header = { offsetHeight: 46 };
    const zoomEl = { style: {} };
    const bodyEl = { scrollLeft: 0, scrollTop: 0, getBoundingClientRect: function () { return { left: 10, top: 400 }; } };
    const grip = { addEventListener: function (type, fn) { gripListeners[type] = fn; } };
    const sizeCalls = [];
    const sandbox = {
        Math: Math,
        parseFloat: parseFloat,
        rect: rect,
        zoomEl: zoomEl,
        bodyEl: bodyEl,
        flowPanel: panel,
        grip: grip,
        gripListeners: gripListeners,
        listeners: listeners,
        sizeCalls: sizeCalls,
        panelCustomPosition: new WeakMap(),
        // Stands in for the measured placement. The normal view opens below the
        // legend; the full-area view opens under the status bar.
        sizeModernPanel: function (p) {
            sizeCalls.push(p);
            p.style.left = DEFAULT_LEFT;
            p.style.top = p.classList.contains('modernPanelLarge') ? '63px' : DEFAULT_TOP;
        },
        window: { innerWidth: opts.innerWidth || 1600, innerHeight: opts.innerHeight || 1000 },
        document: {
            addEventListener: function (type, fn) { if (listeners[type]) listeners[type].push(fn); },
            getElementById: function (id) {
                return id === 'flowHeader' ? header : (id === 'flowZoom' ? zoomEl : (id === 'flowBody' ? bodyEl : null));
            }
        }
    };
    const script =
        'var flowUserSize = null;\nvar flowUserPosition = null;\nvar flowItemId = null;\nvar flowShownItemId = null;\n' +
        'const FLOW_MIN_WIDTH = 280;\nconst FLOW_MIN_HEIGHT = 160;\n' +
        'var flowZoom = 1;\nconst FLOW_ZOOM_MIN = 0.5;\nconst FLOW_ZOOM_MAX = 2.5;\n' +
        ['clampFlowSize', 'applyFlowUserSize', 'clearFlowInlineSize', 'makeFlowResizable',
         'restoreFlowUserPosition', 'noteFlowItem', 'flowStylePosition', 'clampFlowPosition', 'startFlowItemIfNew',
         'resetFlowPanelLayout', 'setFlowSizeMode', 'applyFlowZoom', 'clearFlowZoomStyle', 'nextFlowZoom',
         'handleFlowWheel'].map(extractFunction).join('\n') + '\n' +
        'makeFlowResizable(flowPanel, grip);\n' +
        'var nextItemId = 0;\nfunction select(id) { noteFlowItem({ id: id === undefined ? ++nextItemId : id }); }\n' +
        'function userSize() { return flowUserSize; }\n' +
        'function userPosition() { return flowUserPosition; }\n' +
        'function zoom() { return flowZoom; }\n';
    vm.createContext(sandbox);
    vm.runInContext(script, sandbox);
    return sandbox;
}

// What the page does when an item is picked: the item is recorded, the normal
// view opens, then bringToFront places it only if it is not user-positioned.
function openItem(sb) {
    sb.select();
    sb.setFlowSizeMode(false);
    if (!sb.panelCustomPosition.get(sb.flowPanel)) sb.sizeModernPanel(sb.flowPanel);
}

// A header drag as makePanelDraggable does it: moves the panel and marks it.
function moveTo(sb, left, top) {
    sb.flowPanel.style.left = left + 'px';
    sb.flowPanel.style.top = top + 'px';
    sb.panelCustomPosition.set(sb.flowPanel, true);
}

function dragGrip(sb, dx, dy) {
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
    dragGrip(sb, 240, 180);
    const st = sb.flowPanel.style;
    assert(st.width === '600px' && st.height === '480px', 'size ' + st.width + ' x ' + st.height);
    assert(st.maxWidth === 'none' && st.maxHeight === 'none', 'caps not lifted');
    assert(sb.flowPanel.classList.contains('flowUserSized'), 'text sections not released');
});

check('the grip press does not start a header drag or text selection', function () {
    const sb = makeSandbox();
    const r = dragGrip(sb, 10, 10);
    assert(r.prevented && r.stopped, 'event not contained');
});

check('the size cannot go below the minimum', function () {
    const sb = makeSandbox();
    dragGrip(sb, -900, -900);
    assert(sb.flowPanel.style.width === '280px' && sb.flowPanel.style.height === '160px',
        'size ' + sb.flowPanel.style.width + ' x ' + sb.flowPanel.style.height);
});

check('the size cannot run past the edge of the window', function () {
    const sb = makeSandbox({ innerWidth: 1000, innerHeight: 700 });
    dragGrip(sb, 5000, 5000);
    // left 10 and top 200, with a 10px margin kept at the far edges.
    assert(sb.flowPanel.style.width === '980px' && sb.flowPanel.style.height === '490px',
        'size ' + sb.flowPanel.style.width + ' x ' + sb.flowPanel.style.height);
});

check('the grip does nothing in the full-area Insights view', function () {
    const sb = makeSandbox({ large: true });
    dragGrip(sb, 200, 200);
    assert(!sb.flowPanel.style.width && !sb.flowPanel.style.height, 'resized a large panel');
    assert(sb.userSize() === null, 'recorded a size');
});

check('moving the mouse without pressing the grip changes nothing', function () {
    const sb = makeSandbox();
    sb.listeners.mousemove.forEach(function (fn) { fn({ clientX: 900, clientY: 900 }); });
    assert(!sb.flowPanel.style.width && sb.userSize() === null, 'resized without a press');
});

// ---- a new selection starts from the default position --------------------------

check('the first open places the panel exactly as before', function () {
    const sb = makeSandbox();
    openItem(sb);
    assert(sb.flowPanel.style.left === DEFAULT_LEFT && sb.flowPanel.style.top === DEFAULT_TOP, 'not at default');
    assert(sb.flowPanel.style.width === '' && !sb.flowPanel.classList.contains('flowUserSized'), 'sized unasked');
});

check('a dragged panel goes back to the default position for a new item', function () {
    const sb = makeSandbox();
    openItem(sb);
    moveTo(sb, 208, -40);
    openItem(sb);
    assert(sb.flowPanel.style.left === DEFAULT_LEFT && sb.flowPanel.style.top === DEFAULT_TOP,
        'new item opened at ' + sb.flowPanel.style.left + ', ' + sb.flowPanel.style.top);
    assert(sb.panelCustomPosition.get(sb.flowPanel) === undefined, 'still marked user-positioned');
});

check('a resized panel goes back to the default position for a new item and keeps its size', function () {
    const sb = makeSandbox();
    openItem(sb);
    dragGrip(sb, 240, 180);
    moveTo(sb, 377, 221);
    openItem(sb);
    const st = sb.flowPanel.style;
    assert(st.left === DEFAULT_LEFT && st.top === DEFAULT_TOP, 'new item opened at ' + st.left + ', ' + st.top);
    assert(st.width === '600px' && st.height === '480px', 'size ' + st.width + ' x ' + st.height);
    assert(st.maxHeight === 'none', 'height cap came back');
});

check('a size too tall for the default position is fitted to it', function () {
    const sb = makeSandbox({ innerHeight: 800 });
    openItem(sb);
    moveTo(sb, 10, 20);
    sb.rect.top = 20;
    dragGrip(sb, 0, 400);
    openItem(sb);
    // Default top 367 in an 800px window leaves 423px with the 10px margin.
    assert(sb.flowPanel.style.height === '423px', 'height ' + sb.flowPanel.style.height);
});

check('reopening the same item does not reset the position', function () {
    const sb = makeSandbox();
    openItem(sb);
    moveTo(sb, 377, 221);
    sb.setFlowSizeMode(false);
    assert(sb.flowPanel.style.left === '377px' && sb.flowPanel.style.top === '221px', 'position reset');
});

check('two different items with the same label are still two items', function () {
    // The Dev hub has two Rule Machine rules both named _Testy import.
    const sb = makeSandbox();
    sb.select('a3007');
    sb.setFlowSizeMode(false);
    moveTo(sb, 377, 221);
    sb.select('a3009');
    sb.setFlowSizeMode(false);
    if (!sb.panelCustomPosition.get(sb.flowPanel)) sb.sizeModernPanel(sb.flowPanel);
    assert(sb.flowPanel.style.left === DEFAULT_LEFT && sb.flowPanel.style.top === DEFAULT_TOP,
        'treated as the same item');
});

check('reselecting the item already shown keeps its position', function () {
    const sb = makeSandbox();
    sb.select('a2279');
    sb.setFlowSizeMode(false);
    moveTo(sb, 377, 221);
    sb.select('a2279');
    sb.setFlowSizeMode(false);
    assert(sb.flowPanel.style.left === '377px' && sb.flowPanel.style.top === '221px', 'reset for the same item');
});

// ---- Insights ---------------------------------------------------------------------

check('without a user size, opening Insights leaves size and position alone', function () {
    const sb = makeSandbox();
    openItem(sb);
    sb.flowPanel.style.width = '1200px';
    sb.flowPanel.style.height = '900px';
    const calls = sb.sizeCalls.length;
    sb.setFlowSizeMode(true);
    assert(sb.flowPanel.style.width === '1200px' && sb.flowPanel.style.height === '900px', 'size changed');
    assert(sb.sizeCalls.length === calls, 'resized Insights unasked');
});

check('a user size is never carried into Insights', function () {
    const sb = makeSandbox();
    openItem(sb);
    dragGrip(sb, 240, 180);
    sb.setFlowSizeMode(true);
    const st = sb.flowPanel.style;
    assert(st.width === '' && st.height === '' && st.maxWidth === '' && st.maxHeight === '', 'user size leaked');
    assert(!sb.flowPanel.classList.contains('flowUserSized'), 'text release leaked');
});

check('returning from Insights to the same item restores its position and size', function () {
    const sb = makeSandbox();
    openItem(sb);
    dragGrip(sb, 240, 180);
    moveTo(sb, 377, 221);
    sb.setFlowSizeMode(true);
    sb.setFlowSizeMode(false);
    const st = sb.flowPanel.style;
    assert(st.left === '377px' && st.top === '221px', 'reopened at ' + st.left + ', ' + st.top);
    assert(st.width === '600px' && st.height === '480px', 'size ' + st.width + ' x ' + st.height);
    assert(sb.userPosition() === null, 'remembered position not released');
});

check('opening Insights twice still returns the same item to its own position', function () {
    const sb = makeSandbox();
    openItem(sb);
    dragGrip(sb, 240, 180);
    moveTo(sb, 377, 221);
    sb.setFlowSizeMode(true);
    sb.setFlowSizeMode(true);
    sb.setFlowSizeMode(false);
    assert(sb.flowPanel.style.left === '377px' && sb.flowPanel.style.top === '221px',
        'reopened at ' + sb.flowPanel.style.left + ', ' + sb.flowPanel.style.top);
});

check('picking a new item after Insights starts from the default position', function () {
    const sb = makeSandbox();
    openItem(sb);
    dragGrip(sb, 240, 180);
    moveTo(sb, 377, 221);
    sb.setFlowSizeMode(true);
    openItem(sb);
    assert(sb.flowPanel.style.left === DEFAULT_LEFT && sb.flowPanel.style.top === DEFAULT_TOP,
        'new item opened at ' + sb.flowPanel.style.left + ', ' + sb.flowPanel.style.top);
    assert(sb.userPosition() === null, 'stale remembered position kept');
});

// ---- the header stays on screen ------------------------------------------------------

check('a header dragged above the window is pulled back to the top edge', function () {
    const sb = makeSandbox();
    moveTo(sb, 208, -40);
    const changed = sb.clampFlowPosition();
    assert(changed === true && sb.flowPanel.style.top === '0px' && sb.flowPanel.style.left === '208px',
        'at ' + sb.flowPanel.style.left + ', ' + sb.flowPanel.style.top);
});

check('a header dragged past the left, right or bottom edge is pulled back inside', function () {
    const sb = makeSandbox({ innerWidth: 1600, innerHeight: 1000 });
    moveTo(sb, -200, 500);
    sb.clampFlowPosition();
    assert(sb.flowPanel.style.left === '0px', 'left ' + sb.flowPanel.style.left);
    moveTo(sb, 1500, 500);
    sb.clampFlowPosition();
    assert(sb.flowPanel.style.left === '1225px', 'right edge ' + sb.flowPanel.style.left);
    moveTo(sb, 100, 990);
    sb.clampFlowPosition();
    assert(sb.flowPanel.style.top === '954px', 'bottom edge ' + sb.flowPanel.style.top);
});

check('a panel already on screen is not moved', function () {
    const sb = makeSandbox();
    moveTo(sb, 377, 221);
    assert(sb.clampFlowPosition() === false, 'reported a change');
    assert(sb.flowPanel.style.left === '377px' && sb.flowPanel.style.top === '221px', 'moved');
});

check('a remembered position that is now off screen is pulled back on return from Insights', function () {
    const sb = makeSandbox({ innerWidth: 1600, innerHeight: 1000 });
    openItem(sb);
    dragGrip(sb, 240, 180);
    moveTo(sb, 377, -50);
    sb.setFlowSizeMode(true);
    sb.setFlowSizeMode(false);
    assert(sb.flowPanel.style.top === '0px', 'top ' + sb.flowPanel.style.top);
});

// ---- reset ---------------------------------------------------------------------------

check('resetting clears the size, the kept position and re-measures the panel', function () {
    const sb = makeSandbox();
    openItem(sb);
    dragGrip(sb, 240, 180);
    const calls = sb.sizeCalls.length;
    sb.resetFlowPanelLayout();
    const st = sb.flowPanel.style;
    assert(sb.userSize() === null && sb.userPosition() === null, 'size or remembered position kept');
    assert(sb.panelCustomPosition.get(sb.flowPanel) === undefined, 'position kept');
    assert(st.width === '' && st.height === '' && st.maxWidth === '' && st.maxHeight === '', 'inline size kept');
    assert(sb.sizeCalls.length === calls + 1, 'not re-measured');
});

// ---- zoom -----------------------------------------------------------------------------

function wheel(sb, deltaY, ctrl, x, y) {
    let prevented = false;
    sb.handleFlowWheel({ ctrlKey: ctrl, deltaY: deltaY,
        clientX: x === undefined ? 100 : x, clientY: y === undefined ? 100 : y,
        preventDefault: function () { prevented = true; } });
    return prevented;
}

check('ctrl with the wheel zooms the panel content in and stops the page zooming', function () {
    const sb = makeSandbox();
    openItem(sb);
    const prevented = wheel(sb, -100, true);
    assert(prevented, 'page zoom not stopped');
    assert(sb.zoom() > 1 && sb.zoomEl.style.zoom === String(sb.zoom()),
        'zoom ' + sb.zoom() + ', style ' + sb.zoomEl.style.zoom);
});

check('ctrl with the wheel the other way zooms out', function () {
    const sb = makeSandbox();
    openItem(sb);
    wheel(sb, 100, true);
    assert(sb.zoom() < 1, 'zoom ' + sb.zoom());
});

check('the wheel without ctrl scrolls as normal and is not intercepted', function () {
    const sb = makeSandbox();
    openItem(sb);
    const prevented = wheel(sb, -100, false);
    assert(!prevented && sb.zoom() === 1 && !sb.zoomEl.style.zoom, 'intercepted a plain scroll');
});

check('in the full-area Insights view ctrl with the wheel is left to the page', function () {
    const sb = makeSandbox({ large: true });
    const prevented = wheel(sb, -100, true);
    assert(!prevented && sb.zoom() === 1, 'intercepted in Insights');
});

check('zoom stops at 50 and 250 percent', function () {
    const sb = makeSandbox();
    openItem(sb);
    for (let i = 0; i < 40; i++) wheel(sb, -200, true);
    assert(sb.zoom() === 2.5, 'max ' + sb.zoom());
    for (let i = 0; i < 80; i++) wheel(sb, 200, true);
    assert(sb.zoom() === 0.5, 'min ' + sb.zoom());
});

check('the content under the pointer stays in place as it zooms', function () {
    const sb = makeSandbox();
    openItem(sb);
    sb.bodyEl.scrollLeft = 50;
    sb.bodyEl.scrollTop = 80;
    // The body corner is at (10, 400), so this pointer is 120 across and 200 down.
    wheel(sb, -100, true, 130, 600);
    const r = sb.zoom();
    const expectedLeft = (50 + 120) * r - 120;
    const expectedTop = (80 + 200) * r - 200;
    assert(Math.abs(sb.bodyEl.scrollLeft - expectedLeft) < 0.01 && Math.abs(sb.bodyEl.scrollTop - expectedTop) < 0.01,
        'scroll ' + sb.bodyEl.scrollLeft + ', ' + sb.bodyEl.scrollTop);
});

check('Insights drops the zoom and the normal view gets it back', function () {
    const sb = makeSandbox();
    openItem(sb);
    wheel(sb, -100, true);
    const z = sb.zoomEl.style.zoom;
    sb.setFlowSizeMode(true);
    assert(sb.zoomEl.style.zoom === '', 'zoom leaked into Insights');
    sb.setFlowSizeMode(false);
    assert(sb.zoomEl.style.zoom === z, 'zoom not restored');
});

check('the zoom is kept when a new item is picked', function () {
    const sb = makeSandbox();
    openItem(sb);
    wheel(sb, -100, true);
    const z = sb.zoom();
    openItem(sb);
    assert(sb.zoom() === z && sb.zoomEl.style.zoom === String(z), 'zoom lost on a new item');
});

check('resetting puts the zoom back to 100 percent', function () {
    const sb = makeSandbox();
    openItem(sb);
    wheel(sb, -100, true);
    sb.resetFlowPanelLayout();
    assert(sb.zoom() === 1 && sb.zoomEl.style.zoom === '', 'zoom ' + sb.zoom());
});

check('the wheel listener is non-passive, or preventDefault would be ignored', function () {
    assert(block.indexOf("addEventListener('wheel', handleFlowWheel, { passive: false })") >= 0, 'not non-passive');
});

check('the zoom wrapper sits inside the scrolling body, around all four sections', function () {
    assert(source.indexOf('<div class="panelBody" id="flowBody"><div id="flowZoom"><div id="flowChart"></div><div id="decodeCoverageCard" hidden></div><div id="ruleVariablesCard"></div><div id="communityCard"></div></div></div>') >= 0,
        'wrapper markup missing or misplaced');
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
check('the shared drag helper and the other panels are untouched', function () {
    // Its own comment already mentions resizing, so look for what this change
    // would introduce rather than for the word.
    const drag = extractFunction('makePanelDraggable');
    ['flowUserSize', 'clampFlowSize', 'clampFlowPosition', 'flowItemId', 'flowUserSized', 'flowResize']
        .forEach(function (n) { assert(drag.indexOf(n) < 0, 'drag helper references ' + n); });
    assert(block.indexOf('allPanels') < 0 && block.indexOf('extPanel') < 0 && block.indexOf('pivotPanel') < 0,
        'reaches other panels');
});
check('a new item is recognised by the item shown, not by the focusNode counter', function () {
    // The four Focus dropdowns open the panel through showFlow without calling
    // focusNode, so focusGenerationSeq never moves for them. Found on the Dev hub.
    assert(extractFunction('startFlowItemIfNew').indexOf('focusGenerationSeq') < 0, 'still keyed on focusGenerationSeq');
});
check('every item-open site records the item before the panel is sized', function () {
    const pairs = (source.match(/noteFlowItem\(node\);\r?\n[ \t]*renderDecodeCoverageCard\(node\);\r?\n[ \t]*renderCommunityCard\(node\);\r?\n[ \t]*setFlowSizeMode\(false\);/g) || []).length;
    assert(pairs === 5, 'recorded before sizing at ' + pairs + ' of 5 sites');
});
check('the header clamp is registered after the shared drag helper', function () {
    const helper = source.indexOf("makePanelDraggable(flowPanel, document.getElementById('flowHeader'));");
    const clamp = source.indexOf('let flowHeaderDragging = false;');
    assert(helper >= 0 && clamp > helper, 'clamp listeners would run before the helper moves the panel');
});
check('the grip sits outside the scrolling body, as the last child of the panel', function () {
    assert(source.indexOf('<div id="communityCard"></div></div></div><div id="flowResize" class="panelResizeGrip" title="Drag to resize"></div></div>') >= 0,
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
