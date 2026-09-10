// Stale flowchart renders, against the REAL selection functions and listeners
// extracted from apps/automation_map.groovy. A Mermaid render is asynchronous, so
// it can settle after the user has picked something else. Every entry point that
// selects, closes or replaces the flow panel must make that render stale before
// it can write the chart, the cards or the title, or reopen the panel.
//
// Usage: node tests/flow-render-guard.js
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SRC = path.join(__dirname, '..', 'apps', 'automation_map.groovy');
const source = fs.readFileSync(SRC, 'utf8');

function balancedFrom(start, open, close) {
    const first = source.indexOf(open, start);
    let depth = 0;
    for (let i = first; i < source.length; i++) {
        const c = source[i];
        if (c === open) depth++;
        else if (c === close) {
            depth--;
            if (depth === 0) return source.slice(start, i + 1);
        }
    }
    throw new Error('unbalanced extraction from ' + start);
}

function extractFunction(name) {
    const start = source.indexOf('function ' + name + '(');
    if (start < 0) throw new Error('could not find function ' + name);
    return balancedFrom(start, '{', '}');
}

function extractAnonymous(anchor, signature) {
    const at = source.indexOf(anchor);
    if (at < 0) throw new Error('could not find ' + anchor);
    const start = source.indexOf(signature, at);
    if (start < 0 || start - at > 600) throw new Error('no ' + signature + ' after ' + anchor);
    return balancedFrom(start, '{', '}');
}

const functionNames = ['beginSelectionGeneration', 'showFlow', 'focusNode', 'exitToWholeMap',
    'onAppFocusChange', 'onDeviceFocusChange', 'onHubVarFocusChange', 'onLocalVarFocusChange',
    'bringToFront', 'secondaryPanels', 'allPanels'];
const listeners = {
    searchChange: ['const searchAllSelect = createCombobox({', 'function (value, item)'],
    resetClick: ["document.getElementById('resetBtn').addEventListener('click', ", 'function ()'],
    closeClick: ["flowCloseBtn.addEventListener('click', ", 'function ()'],
    insightsClick: ["document.getElementById('insightsBtn').addEventListener('click', ", 'function ()'],
    extClick: ["document.getElementById('extBtn').addEventListener('click', ", 'function ()'],
    iconsClick: ["document.getElementById('iconsBtn').addEventListener('click', ", 'function ()'],
    releaseClick: ["document.getElementById('releaseActivityBtn').addEventListener('click', ", 'function ()'],
    pivotClick: ["document.getElementById('pivotBtn').addEventListener('click', ", 'function ()'],
    legendClick: ["document.getElementById('legendMoreBtn').addEventListener('click', ", 'function ()']
};
const SECONDARY = [['extClick', 'extPanel', 'External systems'], ['iconsClick', 'iconsPanel', 'Device icons'],
    ['releaseClick', 'releaseActivityPanel', 'Hubitat release activity'], ['pivotClick', 'pivotPanel', 'Pivot tables'],
    ['legendClick', 'legendPanel', 'Full legend']];

let pass = 0, fail = 0;
function check(name, fn) {
    try {
        fn();
        console.log('PASS  ' + name);
        pass++;
    } catch (e) {
        console.log('FAIL  ' + name + ' - ' + e.message);
        fail++;
    }
}
function assert(cond, msg) { if (!cond) throw new Error(msg || 'assertion failed'); }
function tick() { return new Promise(function (r) { setImmediate(r); }); }

const NODES = [
    { id: 'a1', group: 'app', title: 'Rule A', appType: 'Rule-5.1' },
    { id: 'a2', group: 'app', title: 'Rule B', appType: 'Rule-5.1' },
    { id: 'd1', group: 'device', title: 'Lamp' },
    { id: 'hv1', group: 'hubVariable', title: 'Mode var' },
    { id: 'lv1', group: 'localVariable', title: 'Counter' },
    { id: 'lv2', group: 'localVariable', title: 'Unused', unreferencedLocal: true },
    { id: 'e1', group: 'external', title: 'Cloud' }
];

function makeSandbox() {
    const log = [];
    const renders = [];
    const elements = { flowTitle: { textContent: '' }, hint: { style: {} }, kindFilter: { value: 'all' }, ruleVariablesCard: { innerHTML: 'PREVIOUS VARIABLES' } };
    const combo = function () { return { setValue: function () { } }; };
    const sandbox = {
        log: log,
        renders: renders,
        title: elements.flowTitle,
        flowChart: { innerHTML: '', textContent: '' },
        flowPanel: { style: { display: 'none' }, classList: { contains: function () { return false; } } },
        panelCustomPosition: new WeakMap(),
        sizeModernPanel: function () { },
        extLoad: function () { }, iconsLoad: function () { }, releaseActivityLoad: function () { }, pivotOpen: function () { },
        ALL_NODES: NODES,
        FLOWS: { a1: ['A'], a2: ['B'] },
        document: { getElementById: function (id) { return elements[id] || null; } },
        history: { state: null, pushState: function () { }, back: function () { } },
        network: { fit: function () { }, getViewPosition: function () { return { x: 0, y: 0 }; }, getScale: function () { return 1; }, moveTo: function () { } },
        appSelect: combo(), deviceSelect: combo(), hubVarSelect: combo(), localVarSelect: combo(), searchAllSelect: combo(),
        setTimeout: function (fn) { fn(); },
        Date: Date, Error: Error
    };
    sandbox.mermaid = {
        render: function (id, text) {
            return new Promise(function (resolve, reject) { renders.push({ text: text, resolve: resolve, reject: reject }); });
        }
    };
    sandbox.window = { mermaid: sandbox.mermaid };
    SECONDARY.forEach(function (entry) {
        sandbox[entry[1]] = { style: { display: 'none' }, classList: { contains: function () { return false; } } };
    });
    function note(label) { return function (arg) { log.push(label + (arg === undefined ? '' : ':' + (arg && arg.id !== undefined ? arg.id : arg))); }; }
    Object.assign(sandbox, {
        renderRuleVariablesCard: note('vars'), noteFlowItem: note('item'), renderDecodeCoverageCard: note('coverage'),
        renderCommunityCard: note('community'), setFlowSizeMode: note('size'),
        setFlowSub: function () { }, setFlowWebcoreIndent: function () { },
        webcorePistonDeviceCoverageMessage: function () { return ''; },
        appOptionText: function (n) { return n.title; },
        mermaidFor: function (steps) { return 'chart:' + steps[0]; },
        showInertPanel: note('inert'),
        showUnreferencedLocalPanel: function (n) {
            elements.flowTitle.textContent = n.title + ' (Local Variable)';
            sandbox.flowChart.innerHTML = 'UNREFERENCED';
            sandbox.flowPanel.style.display = 'flex';
            log.push('unreferenced:' + n.id);
        },
        buildInsights: function () { return 'INSIGHTS'; },
        applyFilters: function () { }, closeSecondaryPanels: function () { }, syncLegendVisibility: function () { },
        fitCurrentView: function () { }, renderBackLink: function () { }, currentFocus: function () { return null; }
    });
    const script =
        'let focusGenerationSeq = 0;\nlet poppingHistory = false;\nlet panelTopZ = 0;\n' +
        functionNames.map(extractFunction).join('\n') + '\n' +
        Object.keys(listeners).map(function (name) {
            return 'var ' + name + ' = ' + extractAnonymous(listeners[name][0], listeners[name][1]) + ';';
        }).join('\n') + '\n' +
        'function generation() { return focusGenerationSeq; }\n';
    vm.createContext(sandbox);
    vm.runInContext(script, sandbox);
    return sandbox;
}

function snapshot(sb) {
    return { chart: sb.flowChart.innerHTML, text: sb.flowChart.textContent, title: sb.title.textContent,
             display: sb.flowPanel.style.display, logLength: sb.log.length,
             secondary: SECONDARY.map(function (entry) { return sb[entry[1]].style.display; }).join(',') };
}

// Opens rule A from its dropdown, runs the interruption while A's render is still
// pending, then settles A's render and checks that nothing it would write lands.
async function staleCase(name, interrupt, settle, extra) {
    const sb = makeSandbox();
    sb.onAppFocusChange('a1');
    assert(sb.renders.length === 1, 'rule A did not start a render');
    const stale = sb.renders[0];
    interrupt(sb);
    const before = snapshot(sb);
    if (settle === 'reject') stale.reject(new Error('stale failure'));
    else stale.resolve({ svg: '<svg>A</svg>' });
    await tick();
    check(name, function () {
        assert(sb.flowChart.innerHTML === before.chart && sb.flowChart.innerHTML.indexOf('<svg>A') < 0, 'stale chart written');
        assert(sb.flowChart.textContent === before.text, 'stale failure text written');
        assert(sb.title.textContent === before.title, 'title rewritten');
        assert(sb.flowPanel.style.display === before.display, 'panel display changed to ' + sb.flowPanel.style.display);
        assert(sb.log.length === before.logLength, 'stale writes: ' + sb.log.slice(before.logLength).join(', '));
        assert(snapshot(sb).secondary === before.secondary, 'secondary panels changed to ' + snapshot(sb).secondary);
        if (extra) extra(sb);
    });
    return sb;
}

async function main() {

    // ---- the current render still lands ----------------------------------------

    await (async function () {
        const sb = makeSandbox();
        sb.onAppFocusChange('a1');
        sb.renders[0].resolve({ svg: '<svg>A</svg>' });
        await tick();
        check('a render with no later selection writes the chart and opens the panel', function () {
            assert(sb.flowChart.innerHTML === '<svg>A</svg>', 'chart not written');
            assert(sb.flowPanel.style.display === 'flex', 'panel not opened');
            assert(sb.log.indexOf('coverage:a1') >= 0 && sb.log.indexOf('community:a1') >= 0, 'cards not rendered');
        });
    })();

    await (async function () {
        const sb = makeSandbox();
        sb.focusNode('a1');
        sb.renders[0].resolve({ svg: '<svg>A</svg>' });
        await tick();
        check('a render opened from the canvas still lands', function () {
            assert(sb.flowChart.innerHTML === '<svg>A</svg>' && sb.flowPanel.style.display === 'flex', 'canvas render lost');
        });
    })();

    // ---- another app, from each entry point ----------------------------------------

    const fromDropdown = await staleCase('rule A pending, then rule B from the app dropdown: A is discarded',
        function (sb) { sb.onAppFocusChange('a2'); });
    fromDropdown.renders[1].resolve({ svg: '<svg>B</svg>' });
    await tick();
    check('after a stale render is discarded, the current render for rule B lands', function () {
        assert(fromDropdown.flowChart.innerHTML === '<svg>B</svg>', 'rule B chart not written');
        assert(fromDropdown.flowPanel.style.display === 'flex', 'panel not opened for rule B');
        assert(fromDropdown.log.indexOf('coverage:a2') >= 0, 'rule B cards not rendered');
        assert(fromDropdown.log.indexOf('coverage:a1') < 0, 'rule A cards rendered');
    });

    await staleCase('rule A pending, then rule B from Quick Search: A is discarded',
        function (sb) { sb.searchChange('a2', { group: 'app' }); });
    await staleCase('rule A pending, then rule B from the canvas: A is discarded',
        function (sb) { sb.focusNode('a2'); });

    // ---- a selection that closes or replaces the panel ---------------------------------

    await staleCase('rule A pending, then a device from its dropdown: the panel stays closed',
        function (sb) { sb.onDeviceFocusChange('d1'); });
    await staleCase('rule A pending, then a Hub Variable from its dropdown: the panel stays closed',
        function (sb) { sb.onHubVarFocusChange('hv1'); });
    await staleCase('rule A pending, then a referenced Local Variable from its dropdown: the panel stays closed',
        function (sb) { sb.onLocalVarFocusChange('lv1', { id: 'lv1', unreferencedLocal: false }); });
    await staleCase('rule A pending, then an unreferenced Local Variable: its own panel is not overwritten',
        function (sb) { sb.onLocalVarFocusChange('lv2', NODES[5]); });
    await staleCase('rule A pending, then a device from Quick Search: the panel stays closed',
        function (sb) { sb.searchChange('d1', { group: 'device' }); });
    await staleCase('rule A pending, then a Hub Variable from Quick Search: the panel stays closed',
        function (sb) { sb.searchChange('hv1', { group: 'hubVariable' }); });
    await staleCase('rule A pending, then an external system from Quick Search: the panel stays closed',
        function (sb) { sb.searchChange('e1', { group: 'external' }); });
    await staleCase('rule A pending, then a device from the canvas: the panel stays closed',
        function (sb) { sb.focusNode('d1'); });
    await staleCase('rule A pending, then Exit to whole map: the panel stays closed',
        function (sb) { sb.exitToWholeMap(); });
    await staleCase('rule A pending, then Show all: the panel stays closed',
        function (sb) { sb.resetClick(); });
    await staleCase('rule A pending, then the close button: the panel stays closed',
        function (sb) { sb.closeClick(); });
    await staleCase('rule A pending, then Insights: the Insights content is not replaced',
        function (sb) { sb.insightsClick(); });
    await staleCase('a failed render for a superseded rule writes no error text',
        function (sb) { sb.onDeviceFocusChange('d1'); }, 'reject');

    // ---- a secondary panel opened over a pending render ----------------------------------

    for (const entry of SECONDARY) {
        for (const settle of ['resolve', 'reject']) {
            await staleCase('rule A pending, then ' + entry[2] + (settle === 'reject' ? ', render fails' : '') +
                ': the flow panel stays hidden and ' + entry[2] + ' stays open',
                function (sb) {
                    sb[entry[0]]();
                    assert(sb[entry[1]].style.display === 'flex' && sb.flowPanel.style.display === 'none', entry[2] + ' did not open');
                }, settle,
                function (sb) { assert(sb[entry[1]].style.display === 'flex', entry[2] + ' was closed by the stale render'); });
        }
    }

    await (async function () {
        const sb = makeSandbox();
        sb.extClick();
        sb.onAppFocusChange('a2');
        sb.renders[0].resolve({ svg: '<svg>B</svg>' });
        await tick();
        check('a rule picked after a secondary panel opened still renders and replaces that panel', function () {
            assert(sb.flowChart.innerHTML === '<svg>B</svg>' && sb.flowPanel.style.display === 'flex', 'current render lost');
            assert(sb.extPanel.style.display === 'none', 'secondary panel left open over the rule');
        });
    })();

    check('bringToFront invalidates pending renders only when it opens a panel other than the flow panel', function () {
        const sb = makeSandbox();
        const start = sb.generation();
        sb.bringToFront(sb.flowPanel);
        assert(sb.generation() === start, 'opening the flow panel itself made its own render stale');
        sb.bringToFront(sb.pivotPanel);
        assert(sb.generation() === start + 1, 'opening a secondary panel did not begin a generation');
    });

    // ---- one generation ------------------------------------------------------------

    check('the selection generation is advanced in exactly one place', function () {
        const writes = source.match(/focusGenerationSeq\s*(\+\+|\+=|-=|=(?!=))/g) || [];
        assert(writes.length === 2, 'generation written ' + writes.length + ' times');
        assert(extractFunction('beginSelectionGeneration').indexOf('focusGenerationSeq += 1') >= 0, 'not advanced by beginSelectionGeneration');
    });

    check('every entry point begins a generation before it touches the panel', function () {
        const bodies = functionNames.slice(2, 8).map(function (name) { return [name, extractFunction(name)]; })
            .concat(['closeClick', 'insightsClick']
                .map(function (name) { return [name, extractAnonymous(listeners[name][0], listeners[name][1])]; }));
        bodies.forEach(function (pair) {
            const at = pair[1].indexOf('beginSelectionGeneration()');
            assert(at >= 0, pair[0] + ' does not begin a generation');
            ['flowPanel.style', 'flowChart.', 'showFlow(', 'flowTitle'].forEach(function (touch) {
                const t = pair[1].indexOf(touch);
                assert(t < 0 || at < t, pair[0] + ' touches ' + touch + ' before beginning a generation');
            });
        });
    });

    check('Insights hides the decode coverage and rule variables cards of the item it replaces', function () {
        const sb = makeSandbox();
        sb.insightsClick();
        assert(sb.log.indexOf('coverage:null') >= 0, 'coverage card left in place under Insights');
        assert(sb.document.getElementById('ruleVariablesCard').innerHTML === '', 'variables card left in place under Insights');
    });

    check('the generation comment no longer claims only focusNode advances it', function () {
        assert(source.indexOf('Bumped once per focusNode() call') < 0, 'false comment still present');
    });

    console.log('\n' + pass + ' passed, ' + fail + ' failed');
    process.exit(fail ? 1 : 0);
}

main();
