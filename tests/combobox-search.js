// The Focus and Quick Search combobox filters on the text each row shows, run against
// the REAL createCombobox extracted from apps/automation_map.groovy with a stub DOM.
// A visible tag such as [WCP] or [LOC] must be searchable, and a row without
// decorated text still matches on its title.
//
// Usage: node tests/combobox-search.js
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.join(__dirname, '..', 'apps', 'automation_map.groovy'), 'utf8').replace(/\r\n/g, '\n');
const start = source.indexOf("(function (root) {\n  'use strict';\n\n  var ALL = '__all__';");
const end = source.indexOf('}(window));', start);
if (start < 0 || end < 0) throw new Error('combobox block not found');
const block = source.slice(start, end + '}(window));'.length);

let pass = 0, fail = 0;
function check(name, fn) {
    try { fn(); console.log('PASS  ' + name); pass++; }
    catch (e) { console.log('FAIL  ' + name + ' - ' + e.message); fail++; }
}
function assert(cond, msg) { if (!cond) throw new Error(msg || 'assertion failed'); }

function makeEl(tag) {
    const el = {
        tagName: tag, children: [], attrs: {}, listeners: {}, style: {}, hidden: false,
        className: '', id: '', value: '', text: '', offsetTop: 0, offsetHeight: 0, scrollTop: 0, clientHeight: 0,
        classList: { add: function () {}, remove: function () {} },
        setAttribute: function (k, v) { this.attrs[k] = String(v); },
        getAttribute: function (k) { return this.attrs[k]; },
        removeAttribute: function (k) { delete this.attrs[k]; },
        appendChild: function (c) { this.children.push(c); return c; },
        addEventListener: function (t, f) { (this.listeners[t] = this.listeners[t] || []).push(f); },
        removeEventListener: function () {},
        contains: function () { return false; },
        focus: function () {}
    };
    Object.defineProperty(el, 'innerHTML', { get: function () { return ''; }, set: function () { el.children = []; } });
    Object.defineProperty(el, 'textContent', { get: function () { return el.text; }, set: function (v) { el.text = String(v); } });
    return el;
}

const sandbox = { window: {}, document: { createElement: makeEl, addEventListener: function () {}, removeEventListener: function () {} } };
vm.createContext(sandbox);
vm.runInContext(block, sandbox);

const items = [
    { id: 'a1', title: '___ Random WC Piston test (Paused)', optionText: 'App - [WCP] ___ Random WC Piston test (Paused) (webCoRE Piston)' },
    { id: 'a2', title: '_AM Variable Scope Showcase (Paused)', optionText: 'App - [RM5] _AM Variable Scope Showcase (Paused) (Rule-5.1)' },
    { id: 'l1', title: 'AMShow_LocalCount', optionText: 'Local Variable - [LOC] AMShow_LocalCount (in _AM Variable Scope Showcase (Paused))' },
    { id: 'x1', title: 'Plain Title Only' }
];

function search(term) {
    const cb = sandbox.window.createCombobox({ mount: makeEl('div'), items: items, allLabel: 'All' });
    cb.open();
    cb.searchInput.value = term;
    cb.searchInput.listeners.input.forEach(function (f) { f(); });
    const popup = cb.element.children[1];
    return { labels: popup.children[1].children.map(function (li) { return li.textContent; }), count: popup.children[2].textContent };
}

check('a visible app tag is searchable: WCP finds only the piston', function () {
    const r = search('WCP');
    assert(r.count === '1 of 4 shown', 'count was ' + r.count);
    assert(r.labels.length === 1 && r.labels[0].indexOf('[WCP]') >= 0, 'labels were ' + JSON.stringify(r.labels));
});

check('tag search ignores case', function () {
    assert(search('wcp').count === '1 of 4 shown', 'lower-case tag did not match');
});

check('a local variable tag is searchable', function () {
    const r = search('[LOC]');
    assert(r.count === '1 of 4 shown' && r.labels[0].indexOf('AMShow_LocalCount') >= 0, 'labels were ' + JSON.stringify(r.labels));
});

check('a name still matches, including inside a longer word', function () {
    const r = search('WC');
    assert(r.count === '3 of 4 shown', 'count was ' + r.count + ' ' + JSON.stringify(r.labels));
});

check('a row without decorated text matches on its title', function () {
    const r = search('plain');
    assert(r.count === '1 of 4 shown' && r.labels[0] === 'Plain Title Only', 'labels were ' + JSON.stringify(r.labels));
});

check('an empty search lists the reset row and every item', function () {
    const r = search('');
    assert(r.count === '4 of 4 shown' && r.labels.length === 5 && r.labels[0] === 'All', 'labels were ' + JSON.stringify(r.labels));
});

console.log(pass + ' passed, ' + fail + ' failed');
if (fail > 0) process.exit(1);
