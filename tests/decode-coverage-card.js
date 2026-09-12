// The Decode coverage card, against the REAL functions extracted from
// apps/automation_map.groovy, never a copy. The card lives inside a Groovy
// GString, so it cannot be imported; each function is located by brace matching
// and evaluated here with a stubbed document and fetch.
//
// The card used to render a full construct table behind a button. The flow chart
// now shows what a piston does and draws anything it cannot decode as a visible
// block, so the only thing left worth reporting is a field this decoder has never
// seen - a webCoRE version saving something new. That is not something a person
// can act on, and should not have to be asked for, so the check runs on selection
// and the card stays hidden unless it finds one.
//
// Covers the lifecycle rather than formatting: focusing fetches once, a clean
// piston stays hidden, findings render one line, requests do not stack, and a
// response for a superseded selection is discarded.
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

const functionNames = ['extEsc', 'coverageHubAppId', 'decodeCoverageResultHtml',
    'decodeCoverageOutcomeHtml', 'renderDecodeCoverageCard', 'requestDecodeCoverage'];

let pass = 0, fail = 0;
function check(name, fn) {
    try { fn(); console.log('PASS  ' + name); pass++; }
    catch (e) { console.log('FAIL  ' + name + ' - ' + e.message); fail++; }
}
function assert(cond, msg) { if (!cond) throw new Error(msg || 'assertion failed'); }

// ---- a sandbox wired to controllable fetch and selection state -------------

function makeSandbox() {
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
        Math: Math, Object: Object, String: String, Number: Number
    };
    const script =
        'let focusGenerationSeq = 0;\n' +
        'const COVERAGE_URL = "http://hub/coverage?access_token=T";\n' +
        'let coverageRequestSeq = 0;\nlet coverageAppId = null;\nlet coverageInFlight = false;\n' +
        functionNames.map(extractFunction).join('\n') + '\n' +
        'function bumpSelection() { focusGenerationSeq++; }\n' +
        'function inFlight() { return coverageInFlight; }\n';
    vm.createContext(sandbox);
    vm.runInContext(script, sandbox);
    return sandbox;
}

function respond(sb, index, body) {
    sb.pending[index].resolve({ json: function () { return Promise.resolve(body); } });
    return new Promise(function (r) { setImmediate(r); });
}

const piston = { id: 'a3095', appType: 'webCoRE Piston' };
const ruleMachine = { id: 'a2279', appType: 'Rule-5.1' };

const cleanBody = {
    status: 'complete', appId: '3095',
    unrecognised: [], unrecognisedOverflow: 0, structureFindings: [], structureFindingsOverflow: 0
};
function withFindings(unrecognised, unrecognisedOverflow, structureFindings, structureOverflow) {
    return {
        status: 'complete', appId: '3095',
        unrecognised: unrecognised || [], unrecognisedOverflow: unrecognisedOverflow || 0,
        structureFindings: structureFindings || [], structureFindingsOverflow: structureOverflow || 0
    };
}
function rendered(body) { return makeSandbox().decodeCoverageResultHtml(body); }

async function main() {

    // ---- selection ----------------------------------------------------------

    check('a non-piston selection hides the card and fetches nothing', function () {
        const sb = makeSandbox();
        sb.renderDecodeCoverageCard(ruleMachine);
        assert(sb.box.hidden === true && sb.box.innerHTML === '', 'card not hidden');
        assert(sb.fetchCalls.length === 0, 'fetched for a non-piston');
    });

    check('focusing a piston checks once, with no button and nothing shown yet', function () {
        const sb = makeSandbox();
        sb.renderDecodeCoverageCard(piston);
        assert(sb.fetchCalls.length === 1, 'fetch count ' + sb.fetchCalls.length);
        assert(sb.fetchCalls[0].url === 'http://hub/coverage?access_token=T&appId=3095', 'url ' + sb.fetchCalls[0].url);
        assert(sb.fetchCalls[0].opts.credentials === 'omit', 'credentials not omitted');
        assert(sb.box.hidden === true && sb.box.innerHTML === '', 'card shown before any result');
    });

    await (async function () {
        const sb = makeSandbox();
        sb.renderDecodeCoverageCard(piston);
        sb.requestDecodeCoverage();
        check('a further request while one is pending does not stack', function () {
            assert(sb.fetchCalls.length === 1, 'fetch count ' + sb.fetchCalls.length);
        });
        await respond(sb, 0, cleanBody);
        check('a piston with nothing unidentified leaves the card hidden', function () {
            assert(sb.box.innerHTML === '', 'rendered: ' + sb.box.innerHTML);
            assert(sb.box.hidden === true, 'card shown with nothing to say');
        });
        check('the request is no longer marked in flight once answered', function () {
            assert(sb.inFlight() === false, 'still in flight');
        });
    })();

    // ---- the one line it still reports --------------------------------------

    await (async function () {
        const sb = makeSandbox();
        sb.renderDecodeCoverageCard(piston);
        await respond(sb, 0, withFindings([{ path: '$.s[0].t', reason: 'unknown-key' }], 0, [], 0));
        check('a single unidentified field is shown, and the card becomes visible', function () {
            assert(sb.box.hidden === false, 'card still hidden');
            assert(sb.box.innerHTML.indexOf('1 field not identified') >= 0, 'rendered: ' + sb.box.innerHTML);
        });
    })();

    check('unrecognised and structure findings combine, with both overflow counters', function () {
        const h = rendered(withFindings([{ path: '$.s[2].t' }, { path: '$.s[3].t' }], 1,
                                        [{ path: '$.s[0].ok' }, { path: '$.s[1].c[0].t' }], 3));
        assert(h.indexOf('8 fields not identified') >= 0, 'combined total wrong: ' + h);
    });

    check('a count is escaped rather than trusted into markup', function () {
        const b = withFindings([{ path: '$.s[0].t' }], 0, [], 0);
        b.structureFindingsOverflow = '<img src=x onerror=1>';
        const h = rendered(b);
        assert(h.indexOf('<img') < 0, 'markup injected: ' + h);
    });

    check('a truncated walk says the piston was not fully walked', function () {
        const b = withFindings([], 0, [], 0);
        b.status = 'truncated';
        const h = rendered(b);
        assert(h.indexOf('A safety bound was reached') >= 0, 'no truncation notice: ' + h);
        assert(h.indexOf('not identified') < 0, 'a count claimed for an incomplete walk');
    });

    check('an outcome that is not a completed walk says nothing at all', function () {
        const sb = makeSandbox();
        ['not-present', 'busy', 'error', 'analysis-timeout'].forEach(function (status) {
            assert(sb.decodeCoverageOutcomeHtml({ status: status, error: 'decode-failed' }) === '',
                status + ' rendered something');
        });
    });

    // ---- late responses ------------------------------------------------------

    await (async function () {
        const sb = makeSandbox();
        sb.renderDecodeCoverageCard(piston);
        sb.renderDecodeCoverageCard({ id: 'a3089', appType: 'webCoRE Piston' });
        await respond(sb, 0, withFindings([{ path: '$.s[0].t' }], 0, [], 0));
        check('a response for a piston no longer shown is discarded', function () {
            assert(sb.box.innerHTML.indexOf('not identified') < 0, 'stale result rendered');
            assert(sb.box.hidden === true, 'stale result shown');
        });
    })();

    await (async function () {
        const sb = makeSandbox();
        sb.renderDecodeCoverageCard(piston);
        sb.bumpSelection();
        await respond(sb, 0, withFindings([{ path: '$.s[0].t' }], 0, [], 0));
        check('a response arriving after any newer selection is discarded', function () {
            assert(sb.box.innerHTML.indexOf('not identified') < 0, 'stale result rendered');
        });
    })();

    console.log('');
    console.log(pass + ' passed, ' + fail + ' failed');
    if (fail > 0) process.exit(1);
}

main();
