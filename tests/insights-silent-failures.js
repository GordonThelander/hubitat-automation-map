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
    { id: 'a7', group: 'app', title: 'webCoRE Consumer', appType: 'webCoRE Piston' },
    { id: 'a8', group: 'app', title: 'Unreadable webCoRE Variables', appType: 'webCoRE Piston', webcoreVariableDecodeStatus: 'error', webcoreVariableDecodeError: 'invalid-json' },
    { id: 'd1', group: 'device', title: 'Disabled Commanded Light', disabled: true },
    { id: 'd2', group: 'device', title: 'Disabled Only Read', disabled: true },
    { id: 'd3', group: 'device', title: 'Disabled And Unused', disabled: true },
    { id: 'd4', group: 'device', title: 'Healthy Light' },
    { id: 'lv1', group: 'localVariable', title: 'Unused Local', unreferencedLocal: true },
    { id: 'lv2', group: 'localVariable', title: 'Used Local' },
    { id: 'v1', group: 'hubVariable', title: 'Used Direction Unknown' },
    { id: 'v2', group: 'hubVariable', title: 'No Decoded Usage' }
];
const EDGES = [
    { from: 'a1', to: 'a2', kind: 'runs' },
    { from: 'a1', to: 'a3', kind: 'pauseResume' },
    { from: 'a1', to: 'a5', kind: 'cancelTimedActions' },
    { from: 'a1', to: 'd1', kind: 'action', stateful: true },
    { from: 'a1', to: 'd2', kind: 'constraint' },
    { from: 'a1', to: 'd4', kind: 'action', stateful: true },
    { from: 'a1', to: 'lv2', kind: 'read' },
    { from: 'a7', to: 'v1', kind: 'usesVar' }
];

global.ALL_NODES = NODES;
global.ALL_EDGES = EDGES;
global.SCAN_META = { appsUnreadable: 0, devicesUnreadable: 0, scanError: null };
global.GRAPH = {
    hubVariableUnresolvedReferences: [],
    webcoreVariableDecodeIssues: [{ appId: 'a8', error: 'invalid-json' }]
};
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
check('webCoRE use is retained separately without inventing read or write direction', function () {
    if ((D.hubVar.users.v1 || []).indexOf('a7') < 0) throw new Error('webCoRE consumer missing');
    if (D.hubVar.directionUnknownUsage.indexOf('v1') < 0) throw new Error('v1 missing from directionUnknownUsage');
    if (D.hubVar.readers.v1 || D.hubVar.writers.v1) throw new Error('usesVar leaked into read/write');
});
check('a Hub Variable used only by webCoRE is not called unused', function () {
    if (D.hubVar.noDecodedUsage.indexOf('v1') >= 0) throw new Error('v1 wrongly reported as unused');
    if (D.hubVar.noDecodedUsage.indexOf('v2') < 0) throw new Error('v2 should remain unused');
});
check('webCoRE decoder failures are explicit scan gaps', function () {
    if (D.scan.status !== 'complete-with-gaps') throw new Error('decode issue did not affect scan status');
    if (D.scan.webcoreVariableDecodeIssues !== 1) throw new Error('decode issue count is wrong');
    if (D.hubVar.webcoreDecodeIssues.length !== 1) throw new Error('decode issue details missing');
});

// Guidance keys must exist for every finding the panel renders, or advice()
// silently returns an empty string and the row ships with no explanation.
const insightGuidance = eval('(' + extractFunction('insightGuidance') + ')');
const GUIDE = insightGuidance();
check('every new finding has a guidance entry', function () {
    ['inactiveRuleInvoked', 'ruleFlaggedBroken', 'disabledDeviceInUse', 'inactiveRule',
     'unreferencedLocalVariable', 'variableDirectionUnknown', 'webcoreVariableDecodeIssue'].forEach(function (k) {
        const g = GUIDE.findings[k];
        if (!g) throw new Error('missing guidance for ' + k);
        if (!g.meaning || !g.next) throw new Error(k + ' must have both meaning and next');
    });
});

// Exercise the real export builder against the same fixture. This catches
// drift where Insights is correct on screen but the downloaded contract loses
// the relationship, direction, decoder gap or summary counts.
const ref = eval('(' + extractFunction('ref') + ')');
const buildExportPayload = eval('(' + extractFunction('buildExportPayload') + ')');
const payload = buildExportPayload(null, null, []);
check('export preserves the distinct usesVar relationship and unknown direction', function () {
    const edge = payload.edges.find(function (e) { return e.relationship === 'usesVar'; });
    if (!edge) throw new Error('usesVar edge missing');
    if (edge.fromId !== 'a7' || edge.toId !== 'v1') throw new Error('usesVar endpoints changed');
    if (edge.direction !== 'unknown') throw new Error('usesVar direction was not unknown');
    if (edge.usageRole !== null || edge.writeSource !== null) throw new Error('read/write details were invented');
});
check('export reports webCoRE app decoder state without adding a rule flow', function () {
    const complete = payload.apps.find(function (a) { return a.id === 'a7'; });
    const failed = payload.apps.find(function (a) { return a.id === 'a8'; });
    if (!complete || complete.hubVariableDecode.status !== 'not-present') throw new Error('default decoder state missing');
    if (!failed || failed.hubVariableDecode.status !== 'error' || failed.hubVariableDecode.error !== 'invalid-json') {
        throw new Error('fixed decoder failure missing from app');
    }
    if (payload.ruleFlows.some(function (f) { return f.appId === 'a7' || f.appId === 'a8'; })) {
        throw new Error('webCoRE gained a synthetic decoded flow');
    }
});
check('export summary, scan gap and neutral insight match the relationship data', function () {
    if (payload.summary.webcoreHubVariableUseCount !== 1) throw new Error('usesVar summary count wrong');
    if (payload.summary.webcoreVariableDecodeIssueCount !== 1) throw new Error('decode issue summary count wrong');
    if (payload.scan.status !== 'complete-with-gaps') throw new Error('export scan gap missing');
    if (payload.scan.webcoreVariableDecodeIssues.length !== 1) throw new Error('export decode issue missing');
    if (payload.insights.hubVariables.directionUnknownUsage.length !== 1) throw new Error('unknown-use insight missing');
});

console.log(pass + ' passed, ' + fail + ' failed');
if (fail > 0) process.exit(1);
