// Zoomed flowchart scrolling (backlog 29), against the REAL zoom functions and CSS
// in apps/automation_map.groovy. Zoomed in on the Dev hub, the chart's sideways
// scrollbar sat at the bottom of the chart, out of reach until scrolled to the end.
//
// Usage: node tests/flow-zoom-scroll.js
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
    const classes = {};
    const inner = { style: { zoom: '' } };
    const sandbox = {
        inner: inner,
        flowPanel: { classList: {
            toggle: function (c, on) { classes[c] = !!on; },
            remove: function (c) { classes[c] = false; },
            contains: function (c) { return !!classes[c]; }
        } },
        document: { getElementById: function (id) { return id === 'flowZoom' ? inner : null; } }
    };
    vm.createContext(sandbox);
    vm.runInContext('var flowZoom = 1;\n' + extractFunction('applyFlowZoom') + '\n' + extractFunction('clearFlowZoomStyle') +
        '\nfunction setZoom(z) { flowZoom = z; }', sandbox);
    return sandbox;
}

check('zooming in marks the panel as zoomed', function () {
    const sb = makeSandbox();
    sb.setZoom(1.82);
    sb.applyFlowZoom();
    assert(sb.inner.style.zoom === '1.82' && sb.flowPanel.classList.contains('flowZoomed'), 'not marked zoomed');
});

check('back at 100 percent the panel is not marked zoomed', function () {
    const sb = makeSandbox();
    sb.setZoom(1.5);
    sb.applyFlowZoom();
    sb.setZoom(1);
    sb.applyFlowZoom();
    assert(sb.inner.style.zoom === '' && !sb.flowPanel.classList.contains('flowZoomed'), 'still marked zoomed');
});

check('clearing the zoom for Insights clears the mark too', function () {
    const sb = makeSandbox();
    sb.setZoom(2);
    sb.applyFlowZoom();
    sb.clearFlowZoomStyle();
    assert(sb.inner.style.zoom === '' && !sb.flowPanel.classList.contains('flowZoomed'), 'mark left for Insights');
});

check('a zoomed chart scrolls in the panel body, while an unzoomed chart keeps its own sideways scroll', function () {
    assert(source.indexOf('#flowChart { overflow-x:auto; }') >= 0, 'unzoomed rule removed');
    assert(source.indexOf('#flow.flowZoomed #flowChart { overflow-x:visible; }') >= 0, 'zoomed rule missing');
    assert(source.indexOf('.panelBody { flex:1; min-height:0; overflow:auto; }') >= 0, 'panel body no longer scrolls');
});

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
