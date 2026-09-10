// External-system focus (backlog 29 G), against the REAL focus functions, Quick
// Search, Show all and popstate listeners extracted from apps/automation_map.groovy,
// with a working history stack. An external system has no Focus dropdown, so it
// must be held as its own focus while all four dropdowns show All.
//
// Usage: node tests/external-focus.js
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SRC = path.join(__dirname, '..', 'apps', 'automation_map.groovy');
const source = fs.readFileSync(SRC, 'utf8');

function balancedFrom(start) {
    let depth = 0;
    for (let i = source.indexOf('{', start); i < source.length; i++) {
        if (source[i] === '{') depth++;
        else if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
    }
    throw new Error('unbalanced extraction from ' + start);
}
function extractFunction(name) {
    const start = source.indexOf('function ' + name + '(');
    if (start < 0) throw new Error('could not find function ' + name);
    return balancedFrom(start);
}
function extractAnonymous(anchor, signature) {
    const at = source.indexOf(anchor);
    if (at < 0) throw new Error('could not find ' + anchor);
    const start = source.indexOf(signature, at);
    if (start < 0 || start - at > 600) throw new Error('no ' + signature + ' after ' + anchor);
    return balancedFrom(start);
}

let pass = 0, fail = 0;
function check(name, fn) {
    try { fn(); console.log('PASS  ' + name); pass++; }
    catch (e) { console.log('FAIL  ' + name + ' - ' + e.message); fail++; }
}
function assert(cond, msg) { if (!cond) throw new Error(msg || 'assertion failed'); }

const NODES = [
    { id: 'a1', group: 'app', title: 'Rule A', appType: 'Rule-5.1' },
    { id: 'd1', group: 'device', title: 'Lamp' },
    { id: 'e1', group: 'external', title: 'Cloud (Another platform)' },
    { id: 'e2', group: 'external', title: 'Weather service' }
];

function makeSandbox() {
    const focusLog = [];
    const popListeners = [];
    const stack = [{ amFocus: null, cameFrom: null }];
    let index = 0;
    function combo() {
        let value = '__all__';
        return { getValue: function () { return value; }, setValue: function (id) { value = (id == null) ? '__all__' : id; } };
    }
    const history = {
        get state() { return stack[index]; },
        pushState: function (state) { stack.splice(index + 1); stack.push(state); index = stack.length - 1; },
        back: function () { if (index > 0) { index--; popListeners.forEach(function (fn) { fn({ state: stack[index] }); }); } },
        forward: function () { if (index < stack.length - 1) { index++; popListeners.forEach(function (fn) { fn({ state: stack[index] }); }); } }
    };
    const sandbox = {
        focusLog: focusLog,
        history: history,
        ALL_NODES: NODES,
        appSelect: combo(), deviceSelect: combo(), hubVarSelect: combo(), localVarSelect: combo(), searchAllSelect: combo(),
        flowPanel: { style: { display: 'flex' } },
        document: { getElementById: function (id) { return id === 'kindFilter' ? { value: 'all' } : (id === 'hint' ? { style: {} } : null); } },
        window: { addEventListener: function (type, fn) { if (type === 'popstate') popListeners.push(fn); } },
        network: { fit: function () { }, getViewPosition: function () { return { x: 0, y: 0 }; }, getScale: function () { return 1; }, moveTo: function () { } },
        showFlow: function () { }, showUnreferencedLocalPanel: function () { }, syncLegendVisibility: function () { },
        closeSecondaryPanels: function () { }, renderBackLink: function () { }, setTimeout: function (fn) { fn(); }
    };
    const script =
        'let focusGenerationSeq = 0;\nlet poppingHistory = false;\n' +
        source.slice(source.indexOf('var externalFocusId = null;'), source.indexOf('\n', source.indexOf('var externalFocusId = null;'))) + '\n' +
        ['beginSelectionGeneration', 'currentFocus', 'focusNode', 'exitToWholeMap', 'onAppFocusChange', 'onDeviceFocusChange',
         'onHubVarFocusChange', 'onLocalVarFocusChange'].map(extractFunction).join('\n') + '\n' +
        'function applyFilters() { focusLog.push(currentFocus()); }\n' +
        'var searchChange = ' + extractAnonymous('const searchAllSelect = createCombobox({', 'function (value, item)') + ';\n' +
        'var resetClick = ' + extractAnonymous("document.getElementById('resetBtn').addEventListener('click', ", 'function ()') + ';\n' +
        'window.addEventListener(\'popstate\', ' + extractAnonymous("window.addEventListener('popstate', ", 'function (ev)') + ');\n' +
        'function external() { return externalFocusId; }\n' +
        'function dropdowns() { return [appSelect.getValue(), deviceSelect.getValue(), hubVarSelect.getValue(), localVarSelect.getValue()].join(","); }\n';
    vm.createContext(sandbox);
    vm.runInContext(script, sandbox);
    return sandbox;
}

const ALL_FOUR = '__all__,__all__,__all__,__all__';

check('Quick Search on an external system focuses its neighbourhood with every dropdown at All', function () {
    const sb = makeSandbox();
    sb.searchChange('e1', { group: 'external' });
    assert(sb.dropdowns() === ALL_FOUR, 'dropdowns ' + sb.dropdowns());
    assert(sb.currentFocus() === 'e1' && sb.external() === 'e1', 'focus ' + sb.currentFocus());
    assert(sb.focusLog[sb.focusLog.length - 1] === 'e1', 'map filtered to ' + sb.focusLog[sb.focusLog.length - 1]);
    assert(sb.flowPanel.style.display === 'none', 'flow panel left open');
    assert(sb.history.state.amFocus === 'e1' && sb.history.state.cameFrom === null, 'history ' + JSON.stringify(sb.history.state));
});

check('the full Quick Search, Back, Forward and Show all sequence keeps focus and history in step', function () {
    const sb = makeSandbox();
    sb.searchChange('e1', { group: 'external' });
    sb.focusNode('a1');
    assert(sb.currentFocus() === 'a1' && sb.external() === null, 'app focus did not replace the external one');
    assert(sb.history.state.cameFrom === 'e1', 'Back target not recorded as the external system');

    sb.history.back();
    assert(sb.currentFocus() === 'e1' && sb.external() === 'e1' && sb.dropdowns() === ALL_FOUR, 'Back did not restore the external focus: ' + sb.currentFocus() + ' ' + sb.dropdowns());
    assert(sb.focusLog[sb.focusLog.length - 1] === 'e1', 'Back did not refilter to the external system');

    sb.history.forward();
    assert(sb.currentFocus() === 'a1' && sb.external() === null, 'Forward did not return to the app');

    sb.history.back();
    sb.resetClick();
    assert(sb.currentFocus() === null && sb.external() === null && sb.dropdowns() === ALL_FOUR, 'Show all left a focus');
    assert(sb.focusLog[sb.focusLog.length - 1] === null, 'Show all did not refilter to the whole map');
});

check('Back from an external system to the whole map clears it', function () {
    const sb = makeSandbox();
    sb.searchChange('e1', { group: 'external' });
    sb.history.back();
    assert(sb.currentFocus() === null && sb.external() === null, 'external focus survived Back to the whole map');
});

check('a Focus dropdown pick replaces an external focus', function () {
    [['onAppFocusChange', 'a1'], ['onDeviceFocusChange', 'd1'], ['onHubVarFocusChange', 'v1'], ['onLocalVarFocusChange', 'l1']].forEach(function (c) {
        const sb = makeSandbox();
        sb.focusNode('e1');
        const select = { onAppFocusChange: 'appSelect', onDeviceFocusChange: 'deviceSelect', onHubVarFocusChange: 'hubVarSelect', onLocalVarFocusChange: 'localVarSelect' }[c[0]];
        sb[select].setValue(c[1]);
        sb[c[0]](c[1], { unreferencedLocal: false });
        assert(sb.external() === null && sb.currentFocus() === c[1], c[0] + ' left focus ' + sb.currentFocus());
    });
});

check('one external system replaces another, and a device is never written into the device dropdown', function () {
    const sb = makeSandbox();
    sb.focusNode('e1');
    sb.focusNode('e2');
    assert(sb.currentFocus() === 'e2' && sb.dropdowns() === ALL_FOUR, 'focus ' + sb.currentFocus() + ' ' + sb.dropdowns());
    sb.focusNode('d1');
    assert(sb.deviceSelect.getValue() === 'd1' && sb.external() === null, 'device focus wrong');
});

check('the map filter takes its focus from currentFocus, so it sees the external system', function () {
    assert(extractFunction('applyFilters').indexOf('const focusId = currentFocus();') >= 0, 'applyFilters derives focus separately');
});

check('the external focus is declared with var, so an early applyFilters cannot hit it uninitialised', function () {
    assert(source.indexOf('var externalFocusId = null;') >= 0 && source.indexOf('let externalFocusId') < 0, 'declaration wrong');
});

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
