// Screen audit batch 1 (backlog 29), against the REAL label builders, filter
// function and markup extracted from apps/automation_map.groovy.
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

function extractLine(prefix) {
    const start = source.indexOf(prefix);
    if (start < 0) throw new Error('could not find ' + prefix);
    return source.slice(start, source.indexOf('\n', start));
}

let pass = 0, fail = 0;
function check(name, fn) {
    try { fn(); console.log('PASS  ' + name); pass++; }
    catch (e) { console.log('FAIL  ' + name + ' - ' + e.message); fail++; }
}
function assert(cond, msg) { if (!cond) throw new Error(msg || 'assertion failed'); }

const sandbox = {
    APP_TYPE_TAGS: { 'Tapo Integration': 'INT', 'Rule-5.1': 'RM5', 'webCoRE Piston': 'WCP', 'Maker API': 'INT' },
    APP_TITLE_BY_ID: { a1: '__AM WC Read B Write A (Paused) (webCoRE Piston)', a2: 'Perimeter Open (Rule-5.1)' },
    ALL_NODES: [{ id: 'a2', title: 'Perimeter Open (Rule-5.1)' }]
};
vm.createContext(sandbox);
vm.runInContext([extractFunction('appOptionText'), extractFunction('localVarDisplay'), extractFunction('localVarOptionText'),
    extractFunction('localVarCanvasText'),
    extractLine('const RULE_LINK_KINDS = '), extractLine('const VARIABLE_KINDS = '), extractFunction('edgesForKindFilter'),
    'function kinds(list) { return list.map(function (e) { return e.id; }).join(","); }'].join('\n'), sandbox);

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

check('the canvas label names a Local Variable owner once', function () {
    const text = sandbox.localVarCanvasText({ title: 'localFlag (Local Variable in ___ Random WC Piston test (Paused) (webCoRE Piston))' });
    assert(text === 'localFlag (in ___ Random WC Piston test (Paused) (webCoRE Piston))', text);
});

check('dropdowns, Quick Search, canvas and panel share one Local Variable builder', function () {
    assert(extractFunction('localVarOptionText').indexOf('localVarDisplay(n)') >= 0, 'option text has its own split');
    assert(extractFunction('localVarCanvasText').indexOf('localVarDisplay(n)') >= 0, 'canvas text has its own split');
    assert(source.indexOf("n.group === 'localVariable' ? localVarCanvasText(n)") >= 0, 'canvas label does not use the builder');
    assert(extractFunction('pickOptionText').indexOf('localVarOptionText(n)') >= 0, 'Quick Search does not use the builder');
    const panel = extractFunction('showUnreferencedLocalPanel');
    assert(panel.indexOf('localVarOptionText(node)') >= 0 && panel.indexOf("'Declared in ' + (owner") < 0, 'panel repeats the owner');
    assert((source.match(/indexOf\(' \(Local Variable in '\)/g) || []).length === 0, 'a second title split exists');
});

// ---- F. App labels ------------------------------------------------------------------

check('an app whose label is exactly its type name does not repeat it', function () {
    assert(sandbox.appOptionText({ title: 'Tapo Integration (Tapo Integration)', appType: 'Tapo Integration' }) === '[INT] Tapo Integration', 'type repeated');
});

check('a label that differs from the type keeps the type, even when it starts with it', function () {
    [['Tapo Integration (Paused) (Tapo Integration)', 'Tapo Integration', '[INT] Tapo Integration (Paused) (Tapo Integration)'],
     ['Maker API Local (Maker API)', 'Maker API', '[INT] Maker API Local (Maker API)'],
     ['Perimeter Open (Rule-5.1)', 'Rule-5.1', '[RM5] Perimeter Open (Rule-5.1)']].forEach(function (c) {
        const text = sandbox.appOptionText({ title: c[0], appType: c[1] });
        assert(text === c[2], text);
    });
});

// ---- D. Show filter --------------------------------------------------------------

const EDGES = [
    { id: 'rmRead', kind: 'read' }, { id: 'wcWrite', kind: 'write' }, { id: 'wcUses', kind: 'usesVar' },
    { id: 'connector', kind: 'synchronizedWith' }, { id: 'wcDeviceRead', kind: 'deviceRead' },
    { id: 'wcAction', kind: 'action', from: 'webCoRE Piston' }, { id: 'rmAction', kind: 'action', from: 'Rule-5.1' },
    { id: 'runs', kind: 'runs' }, { id: 'trigger', kind: 'trigger' }
];

check('Variable use keeps reads, writes and direction-unknown use, and nothing else', function () {
    assert(sandbox.kinds(sandbox.edgesForKindFilter('variables', EDGES)) === 'rmRead,wcWrite,wcUses', sandbox.kinds(sandbox.edgesForKindFilter('variables', EDGES)));
});

check('Variable connectors keeps only connector synchronisation, which is not variable use', function () {
    assert(sandbox.kinds(sandbox.edgesForKindFilter('synchronizedWith', EDGES)) === 'connector', 'connector filter wrong');
    assert(sandbox.edgesForKindFilter('variables', EDGES).every(function (e) { return e.kind !== 'synchronizedWith'; }), 'connector counted as use');
});

check('webCoRE device state reads keeps only deviceRead edges', function () {
    assert(sandbox.kinds(sandbox.edgesForKindFilter('deviceRead', EDGES)) === 'wcDeviceRead', 'device read filter wrong');
});

check('webCoRE commands stay under Actions only, beside other engines', function () {
    assert(sandbox.kinds(sandbox.edgesForKindFilter('action', EDGES)) === 'wcAction,rmAction', 'actions filter wrong');
});

check('Rule to rule and All relationships are unchanged', function () {
    assert(sandbox.kinds(sandbox.edgesForKindFilter('rulelinks', EDGES)) === 'runs', 'rule links wrong');
    assert(sandbox.edgesForKindFilter('all', EDGES) === EDGES, 'all changed');
});

check('the filter labels say what each keeps', function () {
    const select = source.slice(source.indexOf('<select id="kindFilter">'), source.indexOf('</select>', source.indexOf('<select id="kindFilter">')));
    ['<option value="variables">Variable use only</option>',
     '<option value="synchronizedWith">Variable connectors only</option>',
     '<option value="deviceRead">webCoRE device state reads only</option>'].forEach(function (option) {
        assert(select.indexOf(option) >= 0, 'missing ' + option);
    });
    assert(select.indexOf('webCoRE variable use only') < 0 && select.indexOf('value="usesVar"') < 0, 'old empty filter still offered');
});

check('every variable and device-read edge kind has a filter that keeps it', function () {
    const select = source.slice(source.indexOf('<select id="kindFilter">'), source.indexOf('</select>', source.indexOf('<select id="kindFilter">')));
    const values = (select.match(/value="([^"]+)"/g) || []).map(function (v) { return v.slice(7, -1); });
    ['read', 'write', 'usesVar', 'synchronizedWith', 'deviceRead'].forEach(function (kind) {
        const kept = values.some(function (v) { return v !== 'all' && sandbox.edgesForKindFilter(v, [{ id: kind, kind: kind }]).length === 1; });
        assert(kept, kind + ' has no filter');
    });
});

check('applyFilters takes its edges from the tested filter function', function () {
    assert(extractFunction('applyFilters').indexOf('const pool = edgesForKindFilter(kindVal, ALL_EDGES);') >= 0, 'applyFilters does not use edgesForKindFilter');
});

check('connector synchronisation has a legend row in the colour it is drawn', function () {
    assert(source.indexOf("{ key: 'synchronizedWith', html: '<span class=\"line\" style=\"border-color:' + roleColors.synchronizedWith + '\"></span>Connector - a Hub Variable and its connector device hold the same value' },") >= 0, 'legend row missing');
    assert(source.indexOf("synchronizedWith: '#999' };") >= 0, 'role colour missing');
    assert(source.indexOf("color: roleColors[e.kind] || '#999',") >= 0, 'edge colour no longer comes from roleColors');
});

check('the Full legend lists connector synchronisation too, not only the compact legend', function () {
    const start = source.indexOf('<div id="legendPanel">');
    const full = source.slice(start, source.indexOf('</div></div>', start + 1000) > 0 ? source.indexOf('\n</div>', start) : start + 20000);
    assert(start >= 0, 'Full legend markup not found');
    assert(full.indexOf('<div class="legend-row"><span class="line" style="border-color:#999"></span>Connector - a Hub Variable and its connector device hold the same value</div>') >= 0,
        'Full legend has no Connector row');
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
