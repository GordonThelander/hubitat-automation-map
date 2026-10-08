// HAI Issue #44: the HAI Rule Container findings, against the REAL deriveInsightData(),
// insightGuidance() and buildExportPayload() extracted from apps/automation_map.groovy, never a copy.
// The node facts are haiContainerFacts()'s shape (tests/hai-rule-containers.groovy covers that half).
// Names are invented.
//
// Usage: node tests/insights-hai-containers.js
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

const C = 'HAI Rule Container (DEV)';
const NODES = [
    // Healthy: a rule, a matching name.
    { id: 'a1', group: 'app', title: '[HAI] Hall Light', appType: C,
      haiContainer: { checked: true, ruleId: 'HR-1', staged: false, subs: 2, nameKind: 'rule' } },
    // Orphan by state, under the placeholder name: one finding only.
    { id: 'a2', group: 'app', title: 'HAI rule HR-2', appType: C, inert: true,
      haiContainer: { checked: true, ruleId: null, staged: false, subs: 0, nameKind: 'placeholder' } },
    // Orphan by state, named as a rule: both an orphan and an HAI defect.
    { id: 'a3', group: 'app', title: '[HAI] Front Door', appType: C,
      haiContainer: { checked: true, ruleId: null, staged: false, subs: 0, nameKind: 'rule' } },
    // Placeholder name while holding a rule: an HAI defect, not an orphan.
    { id: 'a4', group: 'app', title: 'HAI rule HR-4', appType: C,
      haiContainer: { checked: true, ruleId: 'HR-4', staged: false, subs: 1, nameKind: 'placeholder' } },
    // State not in the expected shape.
    { id: 'a5', group: 'app', title: 'HAI rule HR-5', appType: C,
      haiContainer: { checked: false, reason: 'its state holds no rt entry' } },
    // A container scanned before the check existed: no facts at all.
    { id: 'a6', group: 'app', title: 'HAI rule HR-6', appType: 'HAI Rule Container' },
    // An app the scan could not read: its type is unknown.
    { id: 'a7', group: 'app', title: 'App 7', appType: 'null', unreadable: true },
    // An ordinary app is never a container, whatever it is named.
    { id: 'a8', group: 'app', title: 'HAI rule HR-8', appType: 'Rule-5.1' },
    // A staged rule is a rule.
    { id: 'a9', group: 'app', title: '[HAI] Porch Light', appType: C,
      haiContainer: { checked: true, ruleId: 'HR-9', staged: true, subs: 0, nameKind: 'rule' } },
    // The parent engine is not a container.
    { id: 'a10', group: 'app', title: 'HAI Engine', appType: 'HAI Engine (DEV)' }
];

global.ALL_NODES = NODES;
global.ALL_EDGES = [];
global.SCAN_META = { appsUnreadable: 1, devicesUnreadable: 0, scanError: null };
global.RM_CONSTRUCT_VOCABULARY = {};
global.GRAPH = { hubVariableUnresolvedReferences: [], webcoreVariableDecodeIssues: [] };

const deriveInsightData = eval('(' + extractFunction('deriveInsightData') + ')');
const D = deriveInsightData();
const H = D.haiContainers;

check('an orphan is found by its state: no rule id, nothing staged, no subscriptions', function () {
    if (H.withoutRule.indexOf('a2') < 0) throw new Error('a2 missing');
    if (H.withoutRule.indexOf('a3') < 0) throw new Error('a3 missing: the name must not hide the state');
});
check('exactly the two orphans, and no healthy or unchecked container among them', function () {
    if (H.withoutRule.join(',') !== 'a2,a3') throw new Error('withoutRule is ' + H.withoutRule.join(','));
});
check('a healthy container is not flagged anywhere', function () {
    ['a1', 'a9'].forEach(function (id) {
        if (H.withoutRule.indexOf(id) >= 0) throw new Error(id + ' called an orphan');
        if (H.nameStateMismatches.some(function (m) { return m.id === id; })) throw new Error(id + ' called a mismatch');
        if (H.couldNotCheck.some(function (c) { return c.id === id; })) throw new Error(id + ' called unchecked');
        if (H.checked.indexOf(id) < 0) throw new Error(id + ' missing from checked');
    });
});
check('named as a rule while holding none is a name/state disagreement', function () {
    const m = H.nameStateMismatches.find(function (x) { return x.id === 'a3'; });
    if (!m || m.problem !== 'named-as-rule-without-rule' || m.ruleId !== null) throw new Error(JSON.stringify(m));
});
check('the placeholder name while holding a rule is a disagreement, and not an orphan', function () {
    const m = H.nameStateMismatches.find(function (x) { return x.id === 'a4'; });
    if (!m || m.problem !== 'placeholder-name-with-rule' || m.ruleId !== 'HR-4') throw new Error(JSON.stringify(m));
    if (H.withoutRule.indexOf('a4') >= 0) throw new Error('a4 holds a rule');
});
check('the placeholder name on an orphan agrees with its state, so it is no disagreement', function () {
    if (H.nameStateMismatches.some(function (m) { return m.id === 'a2'; })) throw new Error('a2 wrongly a mismatch');
    if (H.nameStateMismatches.length !== 2) throw new Error('mismatches: ' + JSON.stringify(H.nameStateMismatches));
});
check('a state in an unexpected shape is "could not check", with the reason, never an orphan or a pass', function () {
    const c = H.couldNotCheck.find(function (x) { return x.id === 'a5'; });
    if (!c || c.reason !== 'its state holds no rt entry') throw new Error(JSON.stringify(c));
    if (H.withoutRule.indexOf('a5') >= 0 || H.checked.indexOf('a5') >= 0) throw new Error('a5 was judged');
});
check('a container with no facts at all is "could not check", never counted as fine', function () {
    const c = H.couldNotCheck.find(function (x) { return x.id === 'a6'; });
    if (!c || !c.reason) throw new Error('a6 missing from couldNotCheck');
    if (H.checked.indexOf('a6') >= 0) throw new Error('a6 counted as checked');
});
check('an unreadable app of unknown type is counted, since it may be a container', function () {
    if (H.appsOfUnknownType !== 1) throw new Error('appsOfUnknownType is ' + H.appsOfUnknownType);
});
check('a non-container app is never a container, even under a placeholder-like name', function () {
    ['a8', 'a10'].forEach(function (id) {
        if (H.checked.indexOf(id) >= 0 || H.couldNotCheck.some(function (c) { return c.id === id; })) throw new Error(id);
    });
});

const insightGuidance = eval('(' + extractFunction('insightGuidance') + ')');
const GUIDE = insightGuidance();
check('every HAI container finding has guidance with meaning and next', function () {
    ['haiContainerWithoutRule', 'haiContainerNameStateMismatch', 'haiContainerNotChecked'].forEach(function (k) {
        const g = GUIDE.findings[k];
        if (!g || !g.meaning || !g.next) throw new Error('guidance incomplete for ' + k);
    });
    if (GUIDE.findings.haiContainerNameStateMismatch.meaning.indexOf('defect in HAI') < 0) {
        throw new Error('the disagreement must be labelled a defect in HAI');
    }
});

const ref = eval('(' + extractFunction('ref') + ')');
const buildExportPayload = eval('(' + extractFunction('buildExportPayload') + ')');
const payload = buildExportPayload(null, null, []);
const X = payload.insights.haiRuleContainers;
check('the export publishes the finding beside the other insights, every app as {id, name}', function () {
    if (!X) throw new Error('insights.haiRuleContainers missing');
    if (X.withoutRule.map(function (r) { return r.id; }).join(',') !== 'a2,a3') throw new Error(JSON.stringify(X.withoutRule));
    if (X.withoutRule[1].name !== '[HAI] Front Door') throw new Error('name not resolved: ' + JSON.stringify(X.withoutRule[1]));
    if (X.checkedCount !== 5) throw new Error('checkedCount ' + X.checkedCount);
});
check('the export labels each disagreement a defect in HAI', function () {
    if (X.nameStateMismatches.length !== 2) throw new Error(JSON.stringify(X.nameStateMismatches));
    X.nameStateMismatches.forEach(function (m) {
        if (m.defectIn !== 'HAI' || !m.problem || !m.app || !m.app.id) throw new Error(JSON.stringify(m));
    });
});
check('the export carries could-not-check and unknown-type apps, so an empty list is never a clean bill alone', function () {
    if (X.couldNotCheck.map(function (c) { return c.app.id; }).join(',') !== 'a5,a6') throw new Error(JSON.stringify(X.couldNotCheck));
    if (X.appsOfUnknownType !== 1) throw new Error('appsOfUnknownType ' + X.appsOfUnknownType);
});
check('the summary count equals the array it counts', function () {
    if (payload.summary.haiRuleContainerWithoutRuleCount !== X.withoutRule.length) throw new Error('count drifted');
});
check('the limitations say why an empty withoutRule is not a clean result by itself', function () {
    if (!payload.limitations.some(function (l) { return l.indexOf('insights.haiRuleContainers') === 0; })) {
        throw new Error('no limitations entry');
    }
});

// The Insights panel renders from the same facts: a disagreement and an unchecked container under
// Needs attention, an orphan under Possibly unused.
global.deriveInsightData = deriveInsightData;
global.insightGuidance = insightGuidance;
global.amPlural = eval('(' + extractFunction('amPlural') + ')');
global.extEsc = eval('(' + extractFunction('extEsc') + ')');
const buildInsights = eval('(' + extractFunction('buildInsights') + ')');
const html = buildInsights();
function sectionOf(key) {
    const at = html.indexOf('data-sec="' + key + '"');
    const next = html.indexOf('<section', at + 1);
    return html.slice(at, next < 0 ? html.length : next);
}
check('the panel lists disagreements and unchecked containers under Needs attention', function () {
    const a = sectionOf('attention');
    if (a.indexOf('HAI Rule Containers have names that disagree') < 0) throw new Error('no disagreement lead');
    if (a.indexOf('This is a defect in HAI') < 0) throw new Error('not labelled a defect in HAI');
    if (a.indexOf('could not be checked for a rule') < 0 || a.indexOf('its state holds no rt entry') < 0) {
        throw new Error('no could-not-check lead with its reason');
    }
});
check('the panel lists orphans under Possibly unused', function () {
    if (sectionOf('cleanup').indexOf('HAI Rule Containers never received a rule') < 0) throw new Error('no orphan lead');
});

console.log('');
console.log(pass + ' passed, ' + fail + ' failed');
process.exit(fail === 0 ? 0 : 1);
