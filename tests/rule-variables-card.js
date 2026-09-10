// Rule variables card rows (backlog 29), against the REAL renderRuleVariablesCard
// extracted from apps/automation_map.groovy. Rule Machine keeps one saved reference
// per field, so two writes to one Local Variable showed as two identical lines on
// the Dev hub. Identical visible rows merge; the saved records do not.
//
// Usage: node tests/rule-variables-card.js
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

function ref(name, scope, operation, usageRole, field) {
    return { name: name, canonicalName: name, status: 'resolved', scope: scope, operation: operation,
             usageRole: usageRole || null, evidence: { field: field } };
}

function render(variableReferences, nonResolved) {
    const box = { innerHTML: '' };
    const records = { a1: { variableReferences: variableReferences, nonResolvedVariableReferences: nonResolved || [] } };
    const sandbox = {
        box: box,
        records: records,
        RULE_VARIABLES: records,
        ALL_NODES: [{ id: 'a1', group: 'app', appType: 'Rule-5.1', title: 'Rule A' }],
        ALL_EDGES: [],
        DEVICE_ICON_TAGS: {},
        document: { getElementById: function (id) { return id === 'ruleVariablesCard' ? box : null; } },
        localVarTag: function () { return 'LOC'; },
        extEsc: function (s) { return String(s); }
    };
    vm.createContext(sandbox);
    vm.runInContext(extractFunction('renderRuleVariablesCard'), sandbox);
    sandbox.renderRuleVariablesCard('a1');
    return { html: box.innerHTML, records: records };
}
function rows(html, text) { return html.split('<li>' + text + '</li>').length - 1; }

check('two writes to one Local Variable from different fields show once, and both records remain', function () {
    const out = render([ref('Overloadcount', 'local', 'write', null, 'xVar.1'), ref('Overloadcount', 'local', 'write', null, 'xVar.2')]);
    assert(rows(out.html, '[LOC] Overloadcount - writes') === 1, 'rows ' + rows(out.html, '[LOC] Overloadcount - writes'));
    assert(out.records.a1.variableReferences.length === 2, 'saved references changed');
});

check('a read and a write of the same variable stay two rows', function () {
    const out = render([ref('Count', 'local', 'write', null, 'f1'), ref('Count', 'local', 'read', 'condition', 'f2')]);
    assert(rows(out.html, '[LOC] Count - writes') === 1 && rows(out.html, '[LOC] Count - reads (condition)') === 1, out.html);
});

check('the same name in different scopes is not merged', function () {
    const out = render([ref('Shared', 'local', 'write', null, 'f1'), ref('Shared', 'hub', 'write', null, 'f2')]);
    assert(rows(out.html, '[LOC] Shared - writes') === 1 && rows(out.html, '[HVR] Shared - writes') === 1, out.html);
});

check('reads with different roles stay separate, while identical roles merge', function () {
    const out = render([ref('Mode', 'hub', 'read', 'trigger', 'f1'), ref('Mode', 'hub', 'read', 'condition', 'f2'), ref('Mode', 'hub', 'read', 'condition', 'f3')]);
    assert(rows(out.html, '[HVR] Mode - reads (trigger)') === 1, 'trigger row count wrong');
    assert(rows(out.html, '[HVR] Mode - reads (condition)') === 1, 'condition rows not merged to one');
});

check('Needs review rows merge only when name, operation and status match', function () {
    const nr = function (name, operation, status) { return { name: name, operation: operation, status: status }; };
    const out = render([], [nr('Ghost', 'read', 'unresolved'), nr('Ghost', 'read', 'unresolved'), nr('Ghost', 'write', 'unresolved'), nr('Ghost', 'read', 'ambiguous')]);
    assert(rows(out.html, 'Ghost - reads, no matching definition found') === 1, 'identical review rows not merged');
    assert(rows(out.html, 'Ghost - writes, no matching definition found') === 1, 'write merged into read');
    assert(rows(out.html, 'Ghost - reads, scope not distinguishable from configuration') === 1, 'different status merged');
});

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
