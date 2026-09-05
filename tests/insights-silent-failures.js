// Tests the silent-failure Insights findings (v2.2.1) against the REAL
// deriveInsightData() extracted from apps/automation_map.groovy, never a copy -
// the function lives inside a Groovy GString, so it cannot be imported and is
// instead located by brace matching and evaluated here.
//
// Covers the exclusions specifically, since those are what separate a useful
// finding from noise: a pause/resume caller is not an invoker, a constraint read
// is not a command, and a rule reported under Needs attention must not also be
// counted as an expected pattern.
//
// Usage: node tests/insights-silent-failures.js
'use strict';
const fs = require('fs');
const path = require('path');

const SRC = path.join(__dirname, '..', 'apps', 'automation_map.groovy');
const source = fs.readFileSync(SRC, 'utf8');

function extractFunction(name) {
    const start = source.indexOf('function ' + name + '(');
    if (start < 0) throw new Error('could not find function ' + name);
    const open = source.indexOf('{', start);
    let depth = 0;
    for (let i = open; i < source.length; i++) {
        const c = source[i];
        if (c === '{') depth++;
        else if (c === '}') {
            depth--;
            if (depth === 0) return source.slice(start, i + 1);
        }
    }
    throw new Error('unbalanced braces extracting ' + name);
}

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

// Fixture: every case the findings must separate, in one graph.
const NODES = [
    { id: 'a1', group: 'app', title: 'Caller Rule' },
    { id: 'a2', group: 'app', title: 'Paused And Called', paused: true },
    { id: 'a3', group: 'app', title: 'Paused Managed By Another', paused: true },
    { id: 'a4', group: 'app', title: 'Paused And Quiet', paused: true },
    { id: 'a5', group: 'app', title: 'Disabled And Called', disabled: true },
    { id: 'a6', group: 'app', title: 'Broken Rule', broken: true },
    { id: 'd1', group: 'device', title: 'Disabled Commanded Light', disabled: true },
    { id: 'd2', group: 'device', title: 'Disabled Only Read', disabled: true },
    { id: 'd3', group: 'device', title: 'Disabled And Unused', disabled: true },
    { id: 'd4', group: 'device', title: 'Healthy Light' },
    { id: 'lv1', group: 'localVariable', title: 'Unused Local', unreferencedLocal: true },
    { id: 'lv2', group: 'localVariable', title: 'Used Local' }
];
const EDGES = [
    { from: 'a1', to: 'a2', kind: 'runs' },
    { from: 'a1', to: 'a3', kind: 'pauseResume' },
    { from: 'a1', to: 'a5', kind: 'cancelTimedActions' },
    { from: 'a1', to: 'd1', kind: 'action', stateful: true },
    { from: 'a1', to: 'd2', kind: 'constraint' },
    { from: 'a1', to: 'd4', kind: 'action', stateful: true },
    { from: 'a1', to: 'lv2', kind: 'read' }
];

global.ALL_NODES = NODES;
global.ALL_EDGES = EDGES;
global.SCAN_META = { appsUnreadable: 0, devicesUnreadable: 0, scanError: null };
global.GRAPH = { hubVariableUnresolvedReferences: [] };
// Evaluated as an expression: a strict-mode eval keeps function declarations in
// its own scope, so the declaration form would not be visible here.
const deriveInsightData = eval('(' + extractFunction('deriveInsightData') + ')');
const D = deriveInsightData();

check('a paused rule another rule runs is reported as still called', function () {
    if (D.inactiveInvoked.indexOf('a2') < 0) throw new Error('a2 missing from inactiveInvoked');
});
check('a disabled rule another rule cancels is reported as still called', function () {
    if (D.inactiveInvoked.indexOf('a5') < 0) throw new Error('a5 missing from inactiveInvoked');
});
check('a paused rule reached ONLY by pauseResume is not treated as still called', function () {
    if (D.inactiveInvoked.indexOf('a3') >= 0) {
        throw new Error('a3 wrongly flagged - pause/resume is the mechanism working, not a failure');
    }
});
check('a paused rule nothing calls is not reported as still called', function () {
    if (D.inactiveInvoked.indexOf('a4') >= 0) throw new Error('a4 wrongly flagged');
});
check('every paused or disabled rule appears in inactiveApps as context', function () {
    ['a2', 'a3', 'a4', 'a5'].forEach(function (id) {
        if (D.inactiveApps.indexOf(id) < 0) throw new Error(id + ' missing from inactiveApps');
    });
    if (D.inactiveApps.indexOf('a1') >= 0) throw new Error('a healthy rule must not be listed');
});
check('inactiveInvoked is a strict subset of inactiveApps, so the panel can subtract safely', function () {
    D.inactiveInvoked.forEach(function (id) {
        if (D.inactiveApps.indexOf(id) < 0) throw new Error(id + ' is invoked but absent from inactiveApps');
    });
});
check('a rule Hubitat marks broken is reported', function () {
    if (D.brokenApps.indexOf('a6') < 0) throw new Error('a6 missing from brokenApps');
    if (D.brokenApps.length !== 1) throw new Error('unexpected extra broken rules: ' + D.brokenApps);
});
check('a disabled device something commands is reported', function () {
    if (D.disabledDevicesInUse.indexOf('d1') < 0) throw new Error('d1 missing');
    if ((D.disabledDeviceUsers['d1'] || []).indexOf('a1') < 0) throw new Error('caller not recorded');
});
check('a disabled device only read as a constraint is not reported', function () {
    if (D.disabledDevicesInUse.indexOf('d2') >= 0) {
        throw new Error('d2 wrongly flagged - a stale read is a weaker, noisier claim than a command that cannot land');
    }
});
check('a disabled device nothing references at all is not reported', function () {
    if (D.disabledDevicesInUse.indexOf('d3') >= 0) throw new Error('d3 wrongly flagged');
});
check('a healthy commanded device is never reported as disabled-in-use', function () {
    if (D.disabledDevicesInUse.indexOf('d4') >= 0) throw new Error('d4 wrongly flagged');
});
check('an unreferenced local variable is reported', function () {
    if (D.unreferencedLocals.indexOf('lv1') < 0) throw new Error('lv1 missing');
});
check('a referenced local variable is not reported', function () {
    if (D.unreferencedLocals.indexOf('lv2') >= 0) throw new Error('lv2 wrongly flagged');
});
check('existing findings still behave (disabled devices do not leak into unreferenced devices)', function () {
    if (D.untouched.indexOf('d1') >= 0) throw new Error('d1 is referenced, must not be untouched');
    if (D.untouched.indexOf('d3') < 0) throw new Error('d3 has no edges, should be untouched');
});

// Guidance keys must exist for every finding the panel renders, or advice()
// silently returns an empty string and the row ships with no explanation.
const insightGuidance = eval('(' + extractFunction('insightGuidance') + ')');
const GUIDE = insightGuidance();
check('every new finding has a guidance entry', function () {
    ['inactiveRuleInvoked', 'ruleFlaggedBroken', 'disabledDeviceInUse', 'inactiveRule',
     'unreferencedLocalVariable'].forEach(function (k) {
        const g = GUIDE.findings[k];
        if (!g) throw new Error('missing guidance for ' + k);
        if (!g.meaning || !g.next) throw new Error(k + ' must have both meaning and next');
    });
});

console.log(pass + ' passed, ' + fail + ' failed');
if (fail > 0) process.exit(1);
