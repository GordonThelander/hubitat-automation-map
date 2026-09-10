// Screen audit batch 1 (backlog 29), against the REAL label builders and filter
// markup extracted from apps/automation_map.groovy.
//
// Usage: node tests/map-labels-and-filters.js
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

const sandbox = {
    APP_TYPE_TAGS: { 'Tapo Integration': 'INT', 'Rule-5.1': 'RM5', 'webCoRE Piston': 'WCP', 'Maker API': 'INT' },
    APP_TITLE_BY_ID: { a1: '__AM WC Read B Write A (Paused) (webCoRE Piston)', a2: 'Perimeter Open (Rule-5.1)' }
};
vm.createContext(sandbox);
vm.runInContext(extractFunction('appOptionText') + '\n' + extractFunction('localVarOptionText'), sandbox);

// ---- E. Local Variable labels -----------------------------------------------------

check('a Local Variable label names its owner once', function () {
    const text = sandbox.localVarOptionText({ title: 'WC_Local_Test_Var (Local Variable in __AM WC Read B Write A (Paused) (webCoRE Piston))',
        ownerAppId: 'a1', unreferencedLocal: true });
    assert(text === '[LOC] WC_Local_Test_Var (in __AM WC Read B Write A (Paused) (webCoRE Piston), unused)', text);
});

check('a referenced Local Variable label has no unused marker', function () {
    const text = sandbox.localVarOptionText({ title: 'AmbianceCheck (Local Variable in Perimeter Open (Rule-5.1))', ownerAppId: 'a2' });
    assert(text === '[LOC] AmbianceCheck (in Perimeter Open (Rule-5.1))', text);
});

check('a title without the owner subtitle is used as it is', function () {
    assert(sandbox.localVarOptionText({ title: 'plain', ownerAppId: 'a2' }) === '[LOC] plain (in Perimeter Open (Rule-5.1))', 'plain title changed');
});

check('the unused Local Variable panel title adds nothing after the option text', function () {
    assert(source.indexOf("localVarOptionText(node) + ' (Local Variable)'") < 0, 'panel title still appends (Local Variable)');
});

// ---- F. App labels ------------------------------------------------------------------

check('an app whose label is its type name does not repeat it', function () {
    assert(sandbox.appOptionText({ title: 'Tapo Integration (Tapo Integration)', appType: 'Tapo Integration' }) === '[INT] Tapo Integration', 'type repeated');
});

check('the repeat is removed when the label adds its own detail after the type name', function () {
    const text = sandbox.appOptionText({ title: 'Tapo Integration (Paused) (Tapo Integration)', appType: 'Tapo Integration' });
    assert(text === '[INT] Tapo Integration (Paused)', text);
});

check('an ordinary app keeps its type', function () {
    const text = sandbox.appOptionText({ title: 'Perimeter Open (Rule-5.1)', appType: 'Rule-5.1' });
    assert(text === '[RM5] Perimeter Open (Rule-5.1)', text);
});

check('a label that only starts with the type name keeps the type', function () {
    const text = sandbox.appOptionText({ title: 'Maker API Local (Maker API)', appType: 'Maker API' });
    assert(text === '[INT] Maker API Local (Maker API)', text);
});

// ---- D. Show filter --------------------------------------------------------------

check('the empty webCoRE-only variable filter is gone', function () {
    assert(source.indexOf('webCoRE variable use only') < 0, 'old filter label still present');
    assert(source.indexOf('<option value="usesVar">') < 0, 'old filter value still present');
});

check('one filter covers variable reads and writes from every engine', function () {
    assert(source.indexOf('<option value="variables">Variable reads and writes only</option>') >= 0, 'new option missing');
    assert(source.indexOf("const VARIABLE_KINDS = ['write', 'read', 'usesVar'];") >= 0, 'kinds missing');
    const applyFilters = extractFunction('applyFilters');
    assert(applyFilters.indexOf("kindVal === 'variables'") >= 0 && applyFilters.indexOf('VARIABLE_KINDS.indexOf(e.kind)') >= 0, 'filter not applied');
});

// ---- B and C ---------------------------------------------------------------------------

check('Insights clears the rule variables card', function () {
    const at = source.indexOf("document.getElementById('insightsBtn').addEventListener('click'");
    const block = source.slice(at, source.indexOf('});', at));
    assert(block.indexOf("getElementById('ruleVariablesCard')") >= 0 && block.indexOf("innerHTML = ''") >= 0, 'variables card left under Insights');
});

check('the standalone large panels end in the flow view bar', function () {
    assert(source.indexOf('#ext, #pivot, #icons, #releaseActivity { border-bottom:2px solid #81BC00; }') >= 0, 'bar missing');
});

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
