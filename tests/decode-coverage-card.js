// T7 for the Decode coverage card (v2.2.9), against the REAL functions extracted
// from apps/automation_map.groovy, never a copy. The card lives inside a Groovy
// GString, so it cannot be imported; each function and constant is located by
// brace matching and evaluated here with a stubbed document and fetch.
//
// Covers the lifecycle rather than only the formatting: focusing does not fetch,
// one press makes one request, a late response for a superseded selection is
// discarded, busy and transient failures stay retryable while validation failures
// and failures that need different saved input are final, the table is grouped with an
// evidence level on every row, and a truncated walk never shows a percentage.
//
// Usage: node tests/decode-coverage-card.js
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

function extractConst(name) {
    const start = source.indexOf('const ' + name + ' = {');
    if (start < 0) throw new Error('could not find const ' + name);
    return balancedFrom(start, '{', '}') + ';';
}

function extractLineConst(name) {
    const start = source.indexOf('const ' + name + ' = ');
    if (start < 0) throw new Error('could not find const ' + name);
    return source.slice(start, source.indexOf('\n', start));
}

const functionNames = ['extEsc', 'coverageHubAppId', 'coverageConstructParts', 'coverageFamilyLabel',
    'decodeCoverageIdleHtml', 'decodeCoverageMessageHtml', 'decodeCoverageResultHtml', 'decodeCoverageOutcomeHtml',
    'renderDecodeCoverageCard', 'requestDecodeCoverage'];
const objectConsts = ['COVERAGE_REASON_LABELS', 'COVERAGE_STRUCTURE_LABELS', 'COVERAGE_GAP_LABELS', 'COVERAGE_ERROR_TEXT', 'COVERAGE_FINAL_ERRORS',
    'COVERAGE_FAMILY_LABELS', 'COVERAGE_OPERAND_NAMES', 'COVERAGE_LEVEL_NAMES', 'COVERAGE_RECOGNISED_LEVELS'];

const cardBlock = source.slice(source.indexOf('// Decode coverage card (v2.2.9).'),
                               source.indexOf('function renderCommunityCard(node) {'));

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

// ---- a sandbox wired to controllable fetch and selection state -------------

function makeSandbox(mutate) {
    const box = { innerHTML: '', hidden: true };
    const pending = [];
    const sandbox = {
        box: box,
        fetchCalls: [],
        document: { getElementById: function (id) { return id === 'decodeCoverageCard' ? box : null; } },
        fetch: function (url, opts) {
            sandbox.fetchCalls.push({ url: url, opts: opts });
            return new Promise(function (resolve, reject) { pending.push({ resolve: resolve, reject: reject }); });
        },
        pending: pending,
        encodeURIComponent: encodeURIComponent,
        Math: Math, Object: Object, String: String
    };
    const script =
        'let focusGenerationSeq = 0;\n' +
        'const COVERAGE_URL = "http://hub/coverage?access_token=T";\n' +
        extractLineConst('COVERAGE_FOOTER') + '\n' +
        'let coverageRequestSeq = 0;\nlet coverageAppId = null;\nlet coverageInFlight = false;\n' +
        objectConsts.map(extractConst).join('\n') + '\n' +
        functionNames.map(extractFunction).join('\n') + '\n' +
        'function bumpSelection() { focusGenerationSeq++; }\n' +
        'function inFlight() { return coverageInFlight; }\n';
    const run = mutate ? mutate(script) : script;
    if (mutate && run === script) throw new Error('mutation did not apply');
    vm.createContext(sandbox);
    vm.runInContext(run, sandbox);
    return sandbox;
}

function respond(sb, index, body) {
    sb.pending[index].resolve({ json: function () { return Promise.resolve(body); } });
    return new Promise(function (r) { setImmediate(r); });
}

const piston = { id: 'a3095', appType: 'webCoRE Piston' };
const ruleMachine = { id: 'a2279', appType: 'Rule-5.1' };

const completeBody = {
    status: 'complete', appId: '3095', registryVersion: '1',
    provenance: { observedWebcoreVersion: null, referenceSourceCommit: 'abc', compatibilityStatus: 'unknown' },
    accounting: { objectsVisited: 41, arraysVisited: 45, fieldsVisited: 192, arrayElementsVisited: 30,
                  scalarsVisited: 137, constructCandidates: 4, constructsIdentified: 4, defaultBranchOccurrences: 15 },
    constructCounts: { 'wc.statement.if': 1, 'wc.operand.p': 2, 'wc.task-parameter.unselected': 1 },
    constructLevels: { 'wc.statement.if': 'L2', 'wc.operand.p': 'L2', 'wc.task-parameter.unselected': 'L2' },
    levelCounts: { L0: 0, L1: 0, L2: 3, L3: 0, L4: 0, L5: 0 },
    unrecognised: [], unrecognisedOverflow: 0, truncation: null,
    meta: { elapsedMs: 113, resultBytes: 961, decoderSchema: '1', cached: false }
};

function rendered(body) {
    const sb = makeSandbox();
    return sb.decodeCoverageResultHtml(body);
}

async function main() {

    // ---- focusing -----------------------------------------------------------

    check('a non-piston selection hides the card and fetches nothing', function () {
        const sb = makeSandbox();
        sb.renderDecodeCoverageCard(ruleMachine);
        assert(sb.box.hidden === true && sb.box.innerHTML === '', 'card not hidden');
        assert(sb.fetchCalls.length === 0, 'fetched on focus');
    });

    check('focusing a piston shows the button and fetches nothing', function () {
        const sb = makeSandbox();
        sb.renderDecodeCoverageCard(piston);
        assert(sb.box.hidden === false, 'card hidden');
        assert(sb.box.innerHTML.indexOf('Check decode coverage') >= 0, 'no button');
        assert(sb.fetchCalls.length === 0, 'fetched on focus');
    });

    check('the resting card describes privacy accurately', function () {
        const sb = makeSandbox();
        sb.renderDecodeCoverageCard(piston);
        assert(sb.box.innerHTML.indexOf('It examines the saved piston structure and does not expose piston values.') >= 0,
            'corrected wording missing');
        assert(sb.box.innerHTML.indexOf('never values') < 0, 'overstated wording still present');
    });

    // ---- one press, one request ---------------------------------------------

    await (async function () {
        const sb = makeSandbox();
        sb.renderDecodeCoverageCard(piston);
        sb.requestDecodeCoverage();
        check('a press makes exactly one request for the hub app id', function () {
            assert(sb.fetchCalls.length === 1, 'fetch count ' + sb.fetchCalls.length);
            assert(sb.fetchCalls[0].url === 'http://hub/coverage?access_token=T&appId=3095', 'url ' + sb.fetchCalls[0].url);
            assert(sb.fetchCalls[0].opts.credentials === 'omit', 'credentials not omitted');
        });
        check('the button is disabled while the request is pending', function () {
            assert(sb.box.innerHTML.indexOf('disabled') >= 0 && sb.box.innerHTML.indexOf('Checking') >= 0, 'not disabled');
        });
        sb.requestDecodeCoverage();
        sb.requestDecodeCoverage();
        check('further presses while pending make no further request', function () {
            assert(sb.fetchCalls.length === 1, 'fetch count ' + sb.fetchCalls.length);
        });
        await respond(sb, 0, completeBody);
        check('a complete result renders accounting, rate, the unknown count and footer', function () {
            const h = sb.box.innerHTML;
            assert(h.indexOf('visited and accounted for') >= 0, 'no accounting line');
            assert(h.indexOf('223 values across 192 fields') >= 0, 'accounting figures wrong');
            assert(h.indexOf('100%') >= 0 && h.indexOf('4 of 4 construct positions recognised at L2 or above') >= 0, 'rate wrong');
            assert(h.indexOf('positions identified') < 0, 'still labelled as identified rather than recognised');
            assert(h.indexOf('below L2') < 0, 'below-L2 line shown when nothing is below L2');
            assert(h.indexOf('0 unrecognised positions.') >= 0, 'zero unknowns not stated');
            assert(h.indexOf('3 identified (L2)') >= 0, 'evidence line missing');
            assert(h.indexOf('15 values took a documented default path') >= 0, 'default path line missing');
            assert(h.indexOf('opaque constructs are not silently omitted') >= 0, 'footer missing');
        });
        check('the accounting line comes before the rate, and the rate before the unknown count', function () {
            const h = sb.box.innerHTML;
            assert(h.indexOf('accounted for') < h.indexOf('dcRate'), 'rate precedes accounting');
            assert(h.indexOf('dcRate') < h.indexOf('unrecognised position'), 'unknown count precedes rate');
        });
        check('the request is no longer marked in flight once answered', function () {
            assert(sb.inFlight() === false, 'still in flight');
        });
    })();

    // ---- the grouped construct table ----------------------------------------------

    check('constructs are grouped under a heading for each family', function () {
        const h = rendered(completeBody);
        ['Operand', 'Statement', 'Task parameter'].forEach(function (family) {
            assert(h.indexOf('<tr class="dcFamily"><td colspan="3">' + family + '</td></tr>') >= 0, 'no heading for ' + family);
        });
        assert(h.split('class="dcFamily"').length - 1 === 3, 'wrong number of family headings');
    });

    check('families appear in label order, each with its own rows beneath it', function () {
        const h = rendered(completeBody);
        const operand = h.indexOf('>Operand<'), statement = h.indexOf('>Statement<'), task = h.indexOf('>Task parameter<');
        assert(operand < statement && statement < task, 'family order wrong');
        assert(h.indexOf('<td>physical device</td>') > operand && h.indexOf('<td>physical device</td>') < statement, 'operand row misplaced');
        assert(h.indexOf('<td>if</td>') > statement && h.indexOf('<td>if</td>') < task, 'statement row misplaced');
        assert(h.indexOf('<td>unselected</td>') > task, 'task parameter row misplaced');
    });

    check('every construct row carries its occurrence count and evidence level', function () {
        const h = rendered(completeBody);
        assert(h.indexOf('<th class="n">Evidence</th>') >= 0, 'no evidence column');
        assert(h.indexOf('<tr><td>physical device</td><td class="n">2</td><td class="n"><span class="dcLevel" title="identified">L2</span></td></tr>') >= 0,
            'row does not carry count and level');
        assert(h.split('class="dcLevel"').length - 1 === 3, 'not every row has a level');
    });

    check('a family label is a table cell, not a sticky header cell', function () {
        const h = rendered(completeBody);
        assert(h.indexOf('<th colspan') < 0, 'family label rendered as th');
    });

    check('a construct without a listable level is not rendered as a row', function () {
        const body = JSON.parse(JSON.stringify(completeBody));
        body.constructLevels['wc.statement.if'] = 'L9<script>';
        const h = rendered(body);
        assert(h.indexOf('L9') < 0 && h.indexOf('<script>') < 0, 'untrusted level rendered');
        assert(h.indexOf('<td>if</td>') < 0, 'row listed without a listable level');
        assert(h.indexOf('3 of 4 construct positions recognised at L2 or above') >= 0, 'unlisted construct counted as recognised');
    });

    check('a construct lowered by structural validity shows how many occurrences were valid', function () {
        const body = JSON.parse(JSON.stringify(completeBody));
        body.constructOccurrences = { 'wc.statement.if': { structurallyValid: 1, structurallyInvalid: 2 } };
        body.structurallyCapped = ['wc.statement.if'];
        const h = rendered(body);
        assert(h.indexOf('<td>if <span class="sub">1 of 3 structurally valid</span></td>') >= 0, 'structural line missing');
        assert(h.split('structurally valid').length - 1 === 1, 'structural line on an uncapped row');
    });

    check('an uncapped construct shows no structural line, even with occurrences reported', function () {
        const body = JSON.parse(JSON.stringify(completeBody));
        body.constructOccurrences = { 'wc.statement.if': { structurallyValid: 0, structurallyInvalid: 1 } };
        body.structurallyCapped = [];
        assert(rendered(body).indexOf('structurally valid') < 0, 'structural line shown for an uncapped row');
    });

    check('untrusted structural counts cannot inject markup', function () {
        const body = JSON.parse(JSON.stringify(completeBody));
        body.constructOccurrences = { 'wc.statement.if': { structurallyValid: '<b>x</b>', structurallyInvalid: '<i>' } };
        body.structurallyCapped = ['wc.statement.if'];
        const h = rendered(body);
        assert(h.indexOf('<b>') < 0 && h.indexOf('<i>') < 0 && h.indexOf('0 of 0 structurally valid') >= 0, 'markup injected');
    });

    // ---- statement confidence and evidence gaps ------------------------------------------

    check('statement confidence is stated apart from unrecognised positions outside statements', function () {
        const body = JSON.parse(JSON.stringify(completeBody));
        body.statementAssessment = { level: 'L3', occurrences: 14, structurallyValid: 14, structurallyInvalid: 0, evidenceGapped: 0 };
        body.nonStatementAssessment = { unrecognised: 6 };
        const h = rendered(body);
        assert(h.indexOf('<p class="sub">Statements: structural (L3) across 14 occurrences.</p>') >= 0, 'statement line missing');
        assert(h.indexOf('6 unrecognised positions are outside every statement and do not lower the statement result.') >= 0, 'outside line missing');
    });

    check('held statement occurrences are counted by cause, and one outside position reads singular', function () {
        const body = JSON.parse(JSON.stringify(completeBody));
        body.statementAssessment = { level: 'L2', occurrences: 3, structurallyValid: 2, structurallyInvalid: 1, evidenceGapped: 1 };
        body.nonStatementAssessment = { unrecognised: 1 };
        const h = rendered(body);
        assert(h.indexOf('Statements: identified (L2) across 3 occurrences; 1 structurally invalid, 1 held by an evidence gap.') >= 0, 'held line wrong');
        assert(h.indexOf('1 unrecognised position is outside every statement and does not lower the statement result.') >= 0, 'singular outside line wrong');
    });

    check('no statement or outside line without a listable level or a count', function () {
        const body = JSON.parse(JSON.stringify(completeBody));
        body.statementAssessment = { level: 'L9<b>', occurrences: 2 };
        body.nonStatementAssessment = { unrecognised: 0 };
        const h = rendered(body);
        assert(h.indexOf('Statements:') < 0 && h.indexOf('<b>') < 0 && h.indexOf('outside every statement') < 0, 'line rendered without a level or count');
    });

    check('evidence gaps are listed with a fixed label, the gap id and an occurrence count', function () {
        const body = JSON.parse(JSON.stringify(completeBody));
        body.evidenceGaps = [{ id: 'task/cm/present', reason: 'needs-physical-device', occurrences: 2 },
                             { id: 'condition/ct/value:t', reason: 'canonical-only', occurrences: 1 }];
        const h = rendered(body);
        assert(h.indexOf('<h5>Evidence gaps</h5>') >= 0, 'no gap heading');
        assert(h.indexOf('<li><span class="dcReason">Needs a physical device to capture</span> <code>task/cm/present</code> <span class="sub">2 occurrences</span></li>') >= 0, 'gap row wrong');
        assert(h.indexOf('<code>condition/ct/value:t</code> <span class="sub">1 occurrence</span>') >= 0, 'singular gap count wrong');
        assert(h.indexOf('3 of 4 construct positions') >= 0 || h.indexOf('%') >= 0, 'an evidence gap must not count as an unidentified position');
    });

    check('an evidence-capped row says how many occurrences are held, with no structural line', function () {
        const body = JSON.parse(JSON.stringify(completeBody));
        body.constructOccurrences = { 'wc.statement.if': { structurallyValid: 2, structurallyInvalid: 0, evidenceGapped: 1 } };
        body.structurallyCapped = [];
        body.evidenceCapped = ['wc.statement.if'];
        const h = rendered(body);
        assert(h.indexOf('<td>if <span class="sub">1 held by an evidence gap</span></td>') >= 0, 'held phrase missing');
        assert(h.indexOf('structurally valid') < 0, 'structural line on an evidence-capped row');
    });

    check('untrusted gap reasons, ids and counts cannot inject markup', function () {
        const body = JSON.parse(JSON.stringify(completeBody));
        body.evidenceGaps = [{ id: '<img src=x>', reason: '<script>', occurrences: '<i>' }];
        const h = rendered(body);
        assert(h.indexOf('<img') < 0 && h.indexOf('<script>') < 0 && h.indexOf('<i>') < 0, 'markup injected');
        assert(h.indexOf('<span class="dcReason">Evidence gap</span>') >= 0 && h.indexOf('0 occurrences') >= 0, 'unknown reason not given the fixed label');
    });

    // ---- meaning (L4) ---------------------------------------------------------------------

    function withMeaning(sem) {
        const b = JSON.parse(JSON.stringify(completeBody));
        b.semanticAssessment = sem;
        return b;
    }

    check('meaning is stated apart from structure, with the proven count', function () {
        const h = rendered(withMeaning({ status: 'complete', occurrences: 7, explained: 2, explainable: false,
            gaps: [{ id: 'condition.leaf-opaque', reason: 'A condition comparison is shown as opaque until its meaning is proven', occurrences: 2 }], claims: [] }));
        assert(h.indexOf('<p class="sub">Meaning: proven for 2 of 7 statement occurrences.</p>') >= 0, 'meaning line missing');
        assert(h.indexOf('<h5>Meaning not yet proven</h5>') >= 0, 'no meaning gap heading');
        assert(h.indexOf('<li><span class="dcReason">A condition comparison is shown as opaque until its meaning is proven</span> <code>condition.leaf-opaque</code> <span class="sub">2 occurrences</span></li>') >= 0, 'meaning gap row wrong');
    });

    check('a fully explained piston says so, and meaning never changes the percentage', function () {
        const plain = rendered(completeBody);
        const h = rendered(withMeaning({ status: 'complete', occurrences: 1, explained: 1, explainable: true, gaps: [], claims: [] }));
        assert(h.indexOf('Meaning: proven for all 1 statement occurrence.') >= 0, 'fully proven line wrong');
        assert(h.indexOf('Meaning not yet proven') < 0, 'gap heading shown with no gaps');
        const pct = function (s) { const m = s.match(/(\d+(\.\d+)?)% /); return m ? m[1] : null; };
        assert(pct(h) === pct(plain), 'meaning changed the percentage');
    });

    check('no meaning line or list when the assessment was not evaluated or is absent', function () {
        const skipped = rendered(withMeaning({ status: 'not-evaluated', occurrences: 3, explained: 0, explainable: false,
            gaps: [{ id: 'condition.leaf-opaque', reason: 'x', occurrences: 1 }], claims: [] }));
        assert(skipped.indexOf('Meaning') < 0, 'meaning shown for a not-evaluated assessment');
        assert(rendered(completeBody).indexOf('Meaning') < 0, 'meaning shown with no assessment');
    });

    check('untrusted meaning reasons, ids and counts cannot inject markup', function () {
        const h = rendered(withMeaning({ status: 'complete', occurrences: '<b>', explained: 1, explainable: false,
            gaps: [{ id: '<img src=x>', reason: '<script>', occurrences: '<i>' }], claims: [] }));
        assert(h.indexOf('<img') < 0 && h.indexOf('<script>') < 0 && h.indexOf('<i>') < 0 && h.indexOf('<b>') < 0, 'markup injected');
    });

    // ---- structural mismatches ----------------------------------------------------------

    function withStructure(findings, overflow) {
        const b = JSON.parse(JSON.stringify(completeBody));
        b.structureFindings = findings;
        b.structureFindingsOverflow = overflow;
        return b;
    }

    check('a single structure finding withholds the percentage and is listed', function () {
        const h = rendered(withStructure([{ path: '$.s[0].ok', category: 'unexpected-key' }], 0));
        assert(h.indexOf('%') < 0, 'percentage shown with a structure finding');
        assert(h.indexOf('Coverage incomplete. 4 construct positions recognised at L2 or above; 1 position not identified.') >= 0, 'incomplete line wrong');
        assert(h.indexOf('0 unrecognised positions.') >= 0, 'unrecognised count not kept separate');
        assert(h.indexOf('1 structure mismatch, listed below.') >= 0, 'singular mismatch line wrong');
        assert(h.indexOf('<h5>Structure not matched</h5>') >= 0, 'no structure list');
        assert(h.indexOf('<li><span class="dcReason">Field not expected here</span> <code>$.s[0].ok</code></li>') >= 0, 'finding not listed with its label');
    });

    check('unrecognised and structural totals combine, with both overflow counters and plural wording', function () {
        const b = withStructure([{ path: '$.s[0].ok', category: 'unexpected-key' }, { path: '$.s[1].c[0].t', category: 'missing-discriminator' }], 3);
        b.unrecognised = [{ path: '$.s[2].t', reason: 'unknown-statement-type', nodeKind: 'scalar' },
                          { path: '$.s[3].t', reason: 'unknown-statement-type', nodeKind: 'scalar' }];
        b.unrecognisedOverflow = 1;
        const h = rendered(b);
        assert(h.indexOf('%') < 0, 'percentage shown');
        assert(h.indexOf('; 8 positions not identified.') >= 0, 'combined total wrong');
        assert(h.indexOf('3 unrecognised positions, the first 2 listed below.') >= 0, 'unrecognised line wrong');
        assert(h.indexOf('5 structure mismatches, the first 2 listed below.') >= 0, 'plural mismatch line wrong');
        assert(h.indexOf('Condition type missing') >= 0, 'second label missing');
    });

    check('a truncated walk counts structure findings as not identified', function () {
        const b = withStructure([{ path: '$.s[0].ok', category: 'unexpected-key' }], 0);
        b.status = 'truncated';
        assert(rendered(b).indexOf('; 1 position not identified') >= 0, 'truncated line ignores structure findings');
    });

    check('an unknown category renders only the fixed label, and markup in path or category is escaped', function () {
        const h = rendered(withStructure([{ path: '<script>alert(1)</script>', category: '<img src=x onerror=1>' },
                                          { path: '$.s[0].ok', category: 'constructor' }], 0));
        assert(h.indexOf('<script>') < 0 && h.indexOf('<img') < 0, 'markup injected');
        assert(h.indexOf('onerror') < 0 || h.indexOf('&lt;img') >= 0, 'category text rendered');
        assert(h.split('<span class="dcReason">Structure not matched</span>').length - 1 === 2, 'unknown categories not given the generic label');
    });

    check('mutation: leaving structure findings out of the total restores a false percentage', function () {
        const sb = makeSandbox(function (s) {
            return s.replace('const unidentifiedTotal = unknownTotal + mismatchTotal;', 'const unidentifiedTotal = unknownTotal;');
        });
        const h = sb.decodeCoverageResultHtml(withStructure([{ path: '$.s[0].ok', category: 'unexpected-key' }], 0));
        assert(h.indexOf('100%') >= 0, 'the mutant did not show the false percentage, so the rule is untested');
    });

    // ---- the evidence ladder ----------------------------------------------------------

    const ladderBody = {
        status: 'complete', appId: '3095', registryVersion: '1',
        provenance: { observedWebcoreVersion: null, referenceSourceCommit: 'abc', compatibilityStatus: 'unknown' },
        accounting: { objectsVisited: 20, arraysVisited: 10, fieldsVisited: 40, arrayElementsVisited: 10,
                      scalarsVisited: 30, constructCandidates: 4, constructsIdentified: 3, defaultBranchOccurrences: 0 },
        constructCounts: { 'wc.function.ladderzero': 1, 'wc.function.ladderone': 1, 'wc.statement.if': 1 },
        constructLevels: { 'wc.function.ladderzero': 'L0', 'wc.function.ladderone': 'L1', 'wc.statement.if': 'L2' },
        levelCounts: { L0: 1, L1: 1, L2: 1, L3: 0, L4: 0, L5: 0 },
        unrecognised: [{ path: '$.s[2].t', reason: 'unknown-statement-type', nodeKind: 'scalar' }],
        unrecognisedOverflow: 0, truncation: null
    };

    function withoutGaps(body) {
        const b = JSON.parse(JSON.stringify(body));
        b.unrecognised = [];
        b.unrecognisedOverflow = 0;
        return b;
    }

    check('only an occurrence at L2 or above counts as recognised', function () {
        const h = rendered(withoutGaps(ladderBody));
        assert(h.indexOf('25% ') >= 0, 'percentage is not L2-or-above occurrences over candidates');
        assert(h.indexOf('1 of 4 construct positions recognised at L2 or above') >= 0, 'recognised figure wrong');
    });

    check('an L1 construct is listed as present and holds the result below complete', function () {
        const h = rendered(withoutGaps(ladderBody));
        assert(h.indexOf('<tr><td>ladderone</td><td class="n">1</td><td class="n"><span class="dcLevel" title="present">L1</span></td></tr>') >= 0,
            'L1 row missing or mislabelled');
        assert(h.indexOf('dcRateGap') >= 0 && h.indexOf('100%') < 0, 'L1 allowed a complete result');
    });

    check('L0 is not listed or named anywhere in the panel', function () {
        const h = rendered(ladderBody);
        assert(h.indexOf('ladderzero') < 0, 'L0 construct listed');
        assert(h.indexOf('L0') < 0 && h.indexOf('unseen') < 0, 'L0 named');
    });

    check('matches below L2 are reported apart from the unrecognised paths', function () {
        const h = rendered(ladderBody);
        assert(h.indexOf('2 matched positions are below L2 and not counted as recognised.') >= 0, 'below-L2 count missing or wrong');
        assert(h.indexOf('1 unrecognised position, listed below.') >= 0, 'unrecognised count not kept distinct');
    });

    check('a single match below L2 is stated in the singular', function () {
        const body = JSON.parse(JSON.stringify(ladderBody));
        delete body.constructCounts['wc.function.ladderzero'];
        assert(rendered(body).indexOf('1 matched position is below L2') >= 0, 'singular wording wrong');
    });

    check('every construct recognised but fields unidentified withholds the percentage (fixture 3097)', function () {
        const body = JSON.parse(JSON.stringify(completeBody));
        body.unrecognised = [{ path: '$.s[0].k[0].p[1].exp.<unknown-key#0>', reason: 'unknown-key', nodeKind: 'scalar' },
                             { path: '$.s[0].k[0].p[1].exp.<unknown-key#1>', reason: 'unknown-key', nodeKind: 'scalar' }];
        const h = rendered(body);
        assert(h.indexOf('%') < 0 && h.indexOf('dcRate') < 0, 'a percentage is shown beside unidentified positions');
        assert(h.indexOf('Coverage incomplete. 4 construct positions recognised at L2 or above; 2 positions not identified.') >= 0, 'incomplete line wrong');
    });

    check('unrecognised positions withhold the percentage even when constructs sit below L2', function () {
        const h = rendered(ladderBody);
        assert(h.indexOf('%') < 0, 'percentage shown');
        assert(h.indexOf('Coverage incomplete. 1 construct position recognised at L2 or above; 1 position not identified.') >= 0, 'incomplete line wrong');
        assert(h.indexOf('2 matched positions are below L2 and not counted as recognised.') >= 0, 'below-L2 line lost');
    });

    check('a truncated walk states unrecognised fields on its partial count line too', function () {
        const body = JSON.parse(JSON.stringify(completeBody));
        body.status = 'truncated';
        body.unrecognised = [{ path: '$.s[0].<unknown-key#1>', reason: 'unknown-key', nodeKind: 'scalar' }];
        assert(rendered(body).indexOf('visited construct positions recognised at L2 or above; 1 position not identified') >= 0, 'partial line missing the count');
    });

    check('a response where every construct is at L2 still reads 100 percent', function () {
        const h = rendered(completeBody);
        assert(h.indexOf('100% ') >= 0 && h.indexOf('dcRateGap') < 0 && h.indexOf('not identified') < 0, 'all-L2 response no longer a clean complete');
    });

    // ---- truncated walks ----------------------------------------------------------

    check('a truncated walk shows a labelled partial count and no percentage', function () {
        const body = JSON.parse(JSON.stringify(completeBody));
        body.status = 'truncated';
        body.truncation = { reason: 'depth-limit' };
        body.accounting.constructCandidates = 12;
        const h = rendered(body);
        assert(h.indexOf('4 of 12 visited construct positions recognised at L2 or above') >= 0, 'partial count missing');
        assert(h.indexOf('%') < 0 && h.indexOf('dcRate') < 0, 'percentage shown for a truncated walk');
        assert(h.indexOf('A safety bound was reached') >= 0, 'safety warning missing');
    });

    // ---- late responses ------------------------------------------------------

    await (async function () {
        const sb = makeSandbox();
        sb.renderDecodeCoverageCard(piston);
        sb.requestDecodeCoverage();
        sb.renderDecodeCoverageCard({ id: 'a3089', appType: 'webCoRE Piston' });
        await respond(sb, 0, completeBody);
        check('a response for a piston no longer shown is discarded', function () {
            assert(sb.box.innerHTML.indexOf('Check decode coverage') >= 0, 'stale result rendered');
            assert(sb.box.innerHTML.indexOf('4 of 4') < 0, 'stale figures shown');
        });
    })();

    await (async function () {
        const sb = makeSandbox();
        sb.renderDecodeCoverageCard(piston);
        sb.requestDecodeCoverage();
        sb.bumpSelection();
        await respond(sb, 0, completeBody);
        check('a response arriving after any newer selection is discarded', function () {
            assert(sb.box.innerHTML.indexOf('4 of 4') < 0, 'stale figures shown');
        });
    })();

    // ---- gaps, drift, busy, failure -----------------------------------------

    await (async function () {
        const sb = makeSandbox();
        sb.renderDecodeCoverageCard(piston);
        sb.requestDecodeCoverage();
        const withGaps = JSON.parse(JSON.stringify(completeBody));
        withGaps.accounting.constructCandidates = 6;
        withGaps.unrecognised = [{ path: '$.s[4].t', reason: 'unknown-statement-type', nodeKind: 'scalar' },
                                 { path: '$.s[0].<script>', reason: 'unknown-key', nodeKind: 'scalar' }];
        withGaps.unrecognisedOverflow = 3;
        withGaps.provenance.compatibilityStatus = 'version-drift';
        await respond(sb, 0, withGaps);
        const h = sb.box.innerHTML;
        check('gaps are listed by fixed reason and path', function () {
            assert(h.indexOf('Unrecognised statement') >= 0 && h.indexOf('$.s[4].t') >= 0, 'gap missing');
            assert(h.indexOf('Coverage incomplete.') >= 0 && h.indexOf('%') < 0, 'percentage shown beside unidentified positions');
        });
        check('the unknown count includes positions past the listing cap', function () {
            assert(h.indexOf('5 unrecognised positions, the first 2 listed below.') >= 0, 'total or listing note wrong');
            assert(h.indexOf('more not listed') < 0, 'old overflow line still shown');
        });
        check('a path is escaped rather than injected', function () {
            assert(h.indexOf('<script>') < 0 && h.indexOf('&lt;script&gt;') >= 0, 'path not escaped');
        });
        check('drift is a caution, visually distinct from a failure', function () {
            assert(h.indexOf('dcCaution') >= 0 && h.indexOf('dcError') < 0, 'drift styled as failure');
            assert(h.indexOf('caution, not a failure') >= 0, 'drift wording missing');
        });
    })();

    check('a known opaque field has its own fixed label', function () {
        const body = JSON.parse(JSON.stringify(completeBody));
        body.unrecognised = [{ path: '$.s[0].zc', reason: 'known-opaque-field', nodeKind: 'scalar' }];
        const h = rendered(body);
        assert(h.indexOf('Opaque field, not interpreted') >= 0 && h.indexOf('$.s[0].zc') >= 0, 'opaque field label or path missing');
        assert(h.indexOf('Coverage incomplete.') >= 0, 'an opaque field did not count as unidentified');
    });

    check('a single unrecognised position is stated in the singular', function () {
        const body = JSON.parse(JSON.stringify(completeBody));
        body.unrecognised = [{ path: '$.s[1].t', reason: 'unknown-statement-type', nodeKind: 'scalar' }];
        assert(rendered(body).indexOf('1 unrecognised position, listed below.') >= 0, 'singular wording wrong');
    });

    const outcomes = [
        { label: 'busy', body: { status: 'busy', error: 'scan-active' }, cls: 'dcBusy', retry: true, text: 'relationship scan is running' },
        { label: 'in flight', body: { status: 'busy', error: 'coverage-in-flight' }, cls: 'dcBusy', retry: true, text: 'already running' },
        { label: 'source timeout', body: { status: 'error', error: 'source-timeout' }, cls: 'dcError', retry: true, text: 'took too long' },
        { label: 'analysis timeout', body: { status: 'analysis-timeout', error: 'analysis-deadline' }, cls: 'dcError', retry: true, text: 'No partial result' },
        { label: 'decode failure', body: { status: 'error', error: 'decode-failed' }, cls: 'dcError', retry: false, text: 'could not be decoded' },
        { label: 'not a piston', body: { status: 'invalid-request', error: 'not-a-piston' }, cls: 'dcError', retry: false, text: 'only available for webCoRE' },
        { label: 'not present', body: { status: 'not-present' }, cls: 'sub', retry: false, text: 'no saved configuration' },
        { label: 'unknown code', body: { status: 'error', error: 'something-new' }, cls: 'dcError', retry: true, text: 'did not complete' }
    ];
    for (const o of outcomes) {
        await (async function () {
            const sb = makeSandbox();
            sb.renderDecodeCoverageCard(piston);
            sb.requestDecodeCoverage();
            await respond(sb, 0, o.body);
            const h = sb.box.innerHTML;
            check(o.label + ': fixed text, ' + (o.retry ? 'retryable' : 'final') + ', no partial result', function () {
                assert(h.indexOf(o.text) >= 0, 'text missing');
                assert(h.indexOf('class="' + o.cls + '"') >= 0, 'tone class missing');
                assert((h.indexOf('Try again</button>') >= 0) === o.retry, 'retry wrong');
                assert(h.indexOf('dcTable') < 0 && h.indexOf('dcRate') < 0 && h.indexOf('dcPartial') < 0, 'partial result shown');
            });
            if (o.retry) {
                sb.requestDecodeCoverage();
                check(o.label + ': retry makes a fresh request', function () {
                    assert(sb.fetchCalls.length === 2, 'fetch count ' + sb.fetchCalls.length);
                });
            }
        })();
    }

    await (async function () {
        const sb = makeSandbox();
        sb.renderDecodeCoverageCard(piston);
        sb.requestDecodeCoverage();
        sb.pending[0].reject(new Error('network down SECRETDETAIL'));
        await new Promise(function (r) { setImmediate(r); });
        check('a network failure is retryable and shows no error text', function () {
            assert(sb.box.innerHTML.indexOf('could not be reached') >= 0, 'message missing');
            assert(sb.box.innerHTML.indexOf('SECRETDETAIL') < 0, 'error text leaked');
            assert(sb.box.innerHTML.indexOf('Try again</button>') >= 0, 'not retryable');
        });
    })();

    // ---- source hygiene -------------------------------------------------------

    check('the card source is plain ASCII', function () {
        for (let i = 0; i < cardBlock.length; i++) {
            if (cardBlock.charCodeAt(i) > 126) throw new Error('non-ASCII at offset ' + i);
        }
    });
    check('the card source has no backslash for Groovy to consume', function () {
        assert(cardBlock.indexOf('\\') < 0, 'backslash present');
    });
    check('the card source has no template literal Groovy would interpolate', function () {
        assert(cardBlock.indexOf('`') < 0, 'backtick present');
        const dollars = cardBlock.split('$').length - 1;
        assert(dollars === 2, 'unexpected $ count ' + dollars + ' (only the two endpoint URL interpolations are allowed)');
    });
    check('no apostrophe inside any user-facing string', function () {
        const strings = cardBlock.match(/'[^'\n]*'/g) || [];
        const texts = strings.filter(function (t) { return t.length > 20 && t.indexOf(' ') > 0; });
        texts.forEach(function (t) { assert(t.slice(1, -1).indexOf("'") < 0, 'apostrophe in ' + t); });
        assert(cardBlock.indexOf("n't") < 0 && cardBlock.indexOf("'s ") < 0, 'contraction or possessive present');
    });
    check('the card never calls the export builder or touches graph data', function () {
        ['buildAiExport', 'ALL_NODES', 'ALL_EDGES', 'exportJson', 'aiExport'].forEach(function (n) {
            assert(cardBlock.indexOf(n) < 0, 'references ' + n);
        });
    });
    check('the card resets wherever the community card renders', function () {
        const renders = source.split('renderCommunityCard(node);').length - 1;
        const resets = (source.match(/renderDecodeCoverageCard\(node\);\r?\n[ \t]*renderCommunityCard\(node\);/g) || []).length;
        assert(renders >= 5 && resets === renders, 'renders ' + renders + ', resets ' + resets);
    });
    check('the card sits above the variables list, with the community card last', function () {
        assert(source.indexOf('<div id="flowChart"></div><div id="decodeCoverageCard" hidden></div><div id="ruleVariablesCard"></div><div id="communityCard"></div>') >= 0,
            'markup order wrong');
    });

    console.log(pass + ' passed, ' + fail + ' failed');
    if (fail > 0) process.exit(1);
}

main();
