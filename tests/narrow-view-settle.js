// Narrowed-view layout (backlog 29 H), against the REAL settle() and the drawing
// and start-up hooks in apps/automation_map.groovy. On the Dev hub a Hub Variable
// froze 41px from its connector device because the 1.5s fallback switched physics
// off before the layout spread, and an older settle's pending listener could shelve
// inert nodes into a focused view.
//
// Usage: node tests/narrow-view-settle.js
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

function makeSandbox() {
    const log = [];
    const pending = [];
    const timers = [];
    const sandbox = {
        log: log,
        timers: timers,
        document: { getElementById: function () { return { style: {} }; } },
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
    vm.runInContext('var settleSeq = 0;\n' + extractFunction('settle'), sandbox);
    return sandbox;
}
function count(sb, entry) { return sb.log.filter(function (x) { return x === entry; }).length; }

check('a narrowed view runs the layout to rest before it is framed', function () {
    const sb = makeSandbox();
    sb.settle(false);
    assert(sb.log[0] === 'stabilize:200', 'no stabilize for a narrowed view: ' + sb.log.join(','));
    sb.network.emitDone();
    assert(count(sb, 'fit') === 1 && count(sb, 'shelve') === 0, sb.log.join(','));
});

check('the whole map keeps its original start-up path, with no forced stabilize', function () {
    const sb = makeSandbox();
    sb.settle(true);
    assert(count(sb, 'stabilize:200') === 0 && sb.timers.length === 0, 'whole-map settle changed: ' + sb.log.join(','));
    sb.network.emitDone();
    assert(count(sb, 'shelve') === 1 && count(sb, 'fit') === 1, sb.log.join(','));
});

check('an older whole-map settle cannot shelve inert nodes into a newer focused view', function () {
    const sb = makeSandbox();
    sb.settle(true);
    sb.settle(false);
    sb.network.emitDone();
    assert(count(sb, 'shelve') === 0, 'stale settle shelved the focused view');
    assert(count(sb, 'fit') === 1, 'framed ' + count(sb, 'fit') + ' times');
});

check('an older focused settle cannot re-frame a newer one', function () {
    const sb = makeSandbox();
    sb.settle(false);
    const staleTimer = sb.timers[0];
    sb.settle(false);
    staleTimer.fn();
    assert(count(sb, 'fit') === 0, 'stale timer framed the newer view');
    sb.network.emitDone();
    assert(count(sb, 'fit') === 1, 'current settle did not frame');
});

check('the event and the fallback timer together still frame only once', function () {
    const sb = makeSandbox();
    sb.settle(false);
    sb.network.emitDone();
    sb.timers.forEach(function (t) { t.fn(); });
    assert(count(sb, 'fit') === 1, 'framed ' + count(sb, 'fit') + ' times');
});

check('the shelf divider is drawn only for the whole map', function () {
    assert(source.indexOf('if (!shelfDivider || !shelfDividerShown) return;') >= 0, 'divider drawn in every view');
    assert(extractFunction('applyFilters').indexOf('shelfDividerShown = wholeMap;') >= 0, 'applyFilters does not set the divider visibility');
    assert(source.indexOf('var shelfDividerShown = true;') >= 0, 'divider flag not declared with var');
});

check('the start-up Show all does not reset a focus picked before the first settle', function () {
    const at = source.indexOf("network.once('stabilizationIterationsDone', function () {\n  setTimeout(function () {");
    const at2 = at >= 0 ? at : source.indexOf("network.once('stabilizationIterationsDone', function () {\r\n  setTimeout(function () {");
    assert(at2 >= 0, 'start-up listener not found');
    const block = source.slice(at2, source.indexOf('exitToWholeMap();', at2));
    assert(block.indexOf('if (currentFocus()) return;') >= 0, 'start-up listener resets an existing focus');
});

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
