// Inert app panel wording (backlog 29 I), against the REAL source in
// apps/automation_map.groovy. The webCoRE parent's permissions sentence is an
// explanation, not a fault, and a heading must not repeat what the title says.
//
// Usage: node tests/inert-panel-wording.js
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

const inert = extractFunction('showInertPanel');
const showFlow = extractFunction('showFlow');

const headingStart = inert.indexOf('let html = node.unreadable ?');
const headingStatement = inert.slice(headingStart, inert.indexOf(';', inert.indexOf("'</h3>')", headingStart)) + 1);
const sandbox = { extEsc: function (s) { return String(s).replace(/</g, '&lt;'); } };
vm.createContext(sandbox);
vm.runInContext('function heading(node) { ' + headingStatement + ' return html; }', sandbox);

check('the webCoRE parent panel does not draw its explanation in the attention style', function () {
    assert(inert.indexOf('isWebcoreNotice') < 0, 'inert panel still computes a notice flag');
    assert(inert.indexOf("What the hub does report about it is below.')), false);") >= 0, 'inert caption not passed as ordinary');
});

check('the no-flow app panel keeps red only for a partial or failed piston coverage', function () {
    assert(showFlow.indexOf("const isWebcoreNotice = isPistonNotice &&") >= 0, 'notice not limited to pistons');
    assert(showFlow.indexOf("node.webcoreDeviceRelationshipsSuppressed && node.appType === 'webCoRE');") < 0, 'webCoRE parent still red');
});

check('a heading already given in the title is not repeated', function () {
    const html = sandbox.heading({ title: 'webCoRE (holds 6 apps)', reason: 'holds 6 apps' });
    assert(html.indexOf('<h3>') < 0, 'heading repeated: ' + html);
});

check('a reason that is not in the title is still shown as the heading', function () {
    assert(sandbox.heading({ title: 'Old Notifier', reason: 'no devices selected' }) === '<h3>no devices selected</h3>', 'heading lost');
    assert(sandbox.heading({ title: 'Old Notifier' }) === '<h3>References nothing</h3>', 'default heading lost');
});

check('an unreadable app keeps its own heading', function () {
    const html = sandbox.heading({ title: 'Broken (holds 2 apps)', reason: 'holds 2 apps', unreadable: true, errorDetail: 'timeout' });
    assert(html.indexOf('<h3>Could not be read</h3>') === 0, html);
});

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
