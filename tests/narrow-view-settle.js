// Narrowed-view layout and view ownership (backlog 29 H), against the REAL
// layoutView(), settle() and revealNetwork() and the drawing and start-up hooks in
// apps/automation_map.groovy. On the Dev hub a Hub Variable froze 41px from its
// connector device because the 1.5s fallback switched physics off before the layout
// spread, and an older settle's pending listener could shelve inert nodes into,
// reframe, or reveal a newer view, including a placed app view that never settles.
//
// Usage: node tests/narrow-view-settle.js
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SRC = process.env.AM_SOURCE || path.join(__dirname, '..', 'apps', 'automation_map.groovy');
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

function makeSandbox() {
    const log = [];
    const pending = [];
    const timers = [];
    const canvas = { style: {} };
    const styleProxy = new Proxy(canvas.style, {
        set: function (target, key, value) { if (key === 'opacity') log.push(value === '' ? 'reveal' : 'hide'); target[key] = value; return true; }
    });
    const canvasEl = { style: styleProxy };
    const sandbox = {
        log: log,
        timers: timers,
        canvas: canvas,
        document: { getElementById: function (id) { return id === 'network' ? canvasEl : null; } },
        network: {
            once: function (event, fn) { if (event === 'stabilizationIterationsDone') pending.push(fn); },
            stabilize: function (n) { log.push('stabilize:' + n); },
            setOptions: function (o) { log.push('physics:' + o.physics.enabled); },
            emitDone: function () { pending.splice(0).forEach(function (fn) { fn(); }); }
        },
        setTimeout: function (fn, ms) { timers.push({ fn: fn, ms: ms }); },
        shelveInertNodes: function () { log.push('shelve'); },
        fitCurrentView: function () { log.push('fit'); }
    };
    vm.createContext(sandbox);
    vm.runInContext('var settleSeq = 0;\n' + ['revealNetwork', 'layoutView', 'settle'].map(extractFunction).join('\n'), sandbox);
    sandbox.runTimers = function (list) { list.forEach(function (t) { t.fn(); }); };
    return sandbox;
}
function count(sb, entry, from) { return sb.log.slice(from || 0).filter(function (x) { return x === entry; }).length; }

// ---- the current view -----------------------------------------------------------

check('a narrowed view runs the layout to rest, then frames and reveals', function () {
    const sb = makeSandbox();
    sb.layoutView(false, false);
    assert(sb.log.indexOf('stabilize:200') >= 0 && count(sb, 'hide') === 1, 'no stabilize or hide: ' + sb.log.join(','));
    sb.network.emitDone();
    assert(count(sb, 'fit') === 1 && count(sb, 'reveal') === 1 && count(sb, 'shelve') === 0, sb.log.join(','));
    assert(sb.canvas.style.opacity === '', 'canvas left hidden');
});

check('a narrowed view that never receives its event is still framed and revealed by its own timers', function () {
    const sb = makeSandbox();
    sb.layoutView(false, false);
    sb.runTimers(sb.timers);
    assert(count(sb, 'fit') === 1 && count(sb, 'reveal') >= 1 && sb.canvas.style.opacity === '', sb.log.join(','));
});

check('a placed app view is framed and revealed once, with no settle', function () {
    const sb = makeSandbox();
    sb.layoutView(true, false);
    assert(count(sb, 'fit') === 1 && count(sb, 'reveal') === 1 && count(sb, 'stabilize:200') === 0 && sb.timers.length === 0, sb.log.join(','));
});

check('the whole map keeps its original path, with no forced stabilize', function () {
    const sb = makeSandbox();
    sb.layoutView(false, true);
    assert(count(sb, 'stabilize:200') === 0 && sb.timers.length === 0, 'whole-map settle changed: ' + sb.log.join(','));
    sb.network.emitDone();
    assert(count(sb, 'shelve') === 1 && count(sb, 'fit') === 1, sb.log.join(','));
});

check('the event and the fallback timers together still frame only once', function () {
    const sb = makeSandbox();
    sb.layoutView(false, false);
    sb.network.emitDone();
    sb.runTimers(sb.timers);
    assert(count(sb, 'fit') === 1, 'framed ' + count(sb, 'fit') + ' times');
});

// ---- stale owners ------------------------------------------------------------------

check('pending whole-map settle, then a placed app view: the old event shelves, frames and reveals nothing', function () {
    const sb = makeSandbox();
    sb.layoutView(false, true);
    sb.layoutView(true, false);
    const mark = sb.log.length;
    sb.network.emitDone();
    assert(count(sb, 'shelve', mark) === 0, 'stale whole-map settle shelved the placed view');
    assert(count(sb, 'fit', mark) === 0, 'stale whole-map settle reframed the placed view');
    assert(count(sb, 'reveal', mark) === 0 && count(sb, 'hide', mark) === 0, 'stale whole-map settle changed visibility');
});

check('pending narrowed settle, then a placed app view: the old event and both old timers do nothing', function () {
    const sb = makeSandbox();
    sb.layoutView(false, false);
    const oldTimers = sb.timers.slice();
    sb.layoutView(true, false);
    const mark = sb.log.length;
    sb.network.emitDone();
    sb.runTimers(oldTimers);
    assert(count(sb, 'fit', mark) === 0, 'stale narrowed settle reframed the placed view');
    assert(count(sb, 'reveal', mark) === 0 && count(sb, 'hide', mark) === 0, 'stale timer changed visibility');
});

check('an old safety timer cannot reveal a newer narrowed view while it is still laying out', function () {
    const sb = makeSandbox();
    sb.layoutView(false, false);
    const oldTimers = sb.timers.slice();
    sb.layoutView(false, false);
    assert(sb.canvas.style.opacity === '0', 'newer view not hidden');
    sb.runTimers(oldTimers);
    assert(sb.canvas.style.opacity === '0' && count(sb, 'fit') === 0, 'stale timer revealed or framed the newer view');
    sb.network.emitDone();
    assert(count(sb, 'fit') === 1 && sb.canvas.style.opacity === '', 'current view did not settle');
});

check('an older whole-map settle cannot shelve inert nodes into a newer focused view', function () {
    const sb = makeSandbox();
    sb.layoutView(false, true);
    sb.layoutView(false, false);
    sb.network.emitDone();
    assert(count(sb, 'shelve') === 0 && count(sb, 'fit') === 1, sb.log.join(','));
});

// ---- wiring in the page ---------------------------------------------------------------

check('applyFilters hands every drawn view to layoutView', function () {
    const body = extractFunction('applyFilters');
    assert(body.indexOf('layoutView(placed, wholeMap);') >= 0, 'applyFilters does not use layoutView');
    assert(body.indexOf('settle(') < 0 && body.indexOf('fitCurrentView()') < 0, 'applyFilters still settles or frames directly');
});

check('the shelf divider is drawn only for the whole map', function () {
    assert(source.indexOf('if (!shelfDivider || !shelfDividerShown) return;') >= 0, 'divider drawn in every view');
    assert(extractFunction('applyFilters').indexOf('shelfDividerShown = wholeMap;') >= 0, 'applyFilters does not set the divider visibility');
    assert(source.indexOf('var shelfDividerShown = true;') >= 0, 'divider flag not declared with var');
});

check('the start-up Show all does not reset a focus picked before the first settle', function () {
    let at = source.indexOf("network.once('stabilizationIterationsDone', function () {\n  setTimeout(function () {");
    if (at < 0) at = source.indexOf("network.once('stabilizationIterationsDone', function () {\r\n  setTimeout(function () {");
    assert(at >= 0, 'start-up listener not found');
    const block = source.slice(at, source.indexOf('exitToWholeMap();', at));
    assert(block.indexOf('if (currentFocus()) return;') >= 0, 'start-up listener resets an existing focus');
});

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
