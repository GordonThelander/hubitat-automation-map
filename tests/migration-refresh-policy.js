// The Migration Assessment panel used to re-rate every piston on every open,
// because a rating goes stale the moment the graph is rebuilt and the panel
// treated stale as "rate it again". Rating a piston costs a hub read and a
// decode, so 22 of them ran each time the panel was opened.
//
// The policy these assertions hold: a cached rating is shown however old it
// is, a piston that has never been rated is rated, and only an upgrade of
// this app re-rates a hub unasked, because the equivalence table it rates
// against lives in this app.
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const source = fs.readFileSync('apps/automation_map.groovy', 'utf8');
const code = source.slice(source.indexOf('function mrName('), source.indexOf('function mrLoadMatrix('));

let passed = 0;
const check = (cond, what) => {
  if (!cond) { console.log('FAIL  ' + what); process.exit(1); }
  passed++; console.log('PASS  ' + what);
};

function run(ratings, pistons) {
  const rated = [];
  const context = {
    ALL_NODES: pistons.map(p => ({id: p.id, appType: 'webCoRE Piston', title: p.id})),
    MR: {results: null, running: false, matrix: null, tab: 'pistons', runSeq: 0, ratedAt: null, versionMoved: false},
    MIGRATION_RATINGS_URL: 'ratings', MIGRATION_URL: 'rate',
    coverageHubAppId: id => String(id),
    bringToFront: () => {}, migrationReportPanel: {},
    mrRender: () => {},
    extEsc: s => String(s),
    Date: Date, JSON: JSON, isNaN: isNaN, Number: Number, setTimeout: setTimeout,
    fetch: (url) => {
      if (url === 'ratings') return Promise.resolve({json: () => Promise.resolve({ratings, lastRatedAt: 1760000000000})});
      rated.push(url);
      return Promise.resolve({json: () => Promise.resolve({status: 'complete'})});
    }
  };
  vm.createContext(context);
  vm.runInContext(code + '\nmrOpen();', context);
  return new Promise(r => setTimeout(() => r({rated, MR: context.MR}), 30));
}

const full = {components: 3, partsNeedingRework: 0, level: 1, label: 'Direct equivalent, simple'};
const complete = (appId, extra) => Object.assign(
  {appId, status: 'complete', ruleMachine: full, visualRuleBuilder: full, hai: full}, extra || {});

(async () => {
  // A rating the graph rebuild aged is still a rating.
  let r = await run([complete('1', {stale: true, staleReason: 'graph-rebuilt', appVersionMoved: false})], [{id: '1'}]);
  check(r.rated.length === 0, 'a stale rating is shown rather than rated again');
  check(r.MR.ratedAt === 1760000000000, 'the panel reports when the ratings were taken');

  // The initial scan: nothing has ever been rated here.
  r = await run([{appId: '1', status: 'not-rated', ratedAt: null}], [{id: '1'}]);
  check(r.rated.length === 1, 'a piston that has never been rated is rated on open');

  // A piston added since the last assessment.
  r = await run([complete('1', {stale: false, appVersionMoved: false})], [{id: '1'}, {id: '2'}]);
  check(r.rated.length === 1 && r.rated[0].indexOf('2') !== -1,
        'only the new piston is rated, not the one already held');

  // The one event that re-rates a whole hub unasked.
  r = await run([complete('1', {stale: false, appVersionMoved: true}),
                 complete('2', {stale: false, appVersionMoved: true})], [{id: '1'}, {id: '2'}]);
  check(r.rated.length === 2, 'an upgrade of this app re-rates every piston');
  check(r.MR.versionMoved === true, 'the upgrade is recorded rather than inferred twice');

  // One rating left behind by an upgrade does not drag the other 24 with it.
  r = await run([complete('1', {stale: false, appVersionMoved: true}),
                 complete('2', {stale: false, appVersionMoved: false}),
                 complete('3', {stale: false, appVersionMoved: false})],
                [{id: '1'}, {id: '2'}, {id: '3'}]);
  check(r.rated.length === 1 && r.rated[0].indexOf('1') !== -1,
        'only the piston whose rating the upgrade invalidated is rated again');

  // Nothing to do at all.
  r = await run([complete('1', {stale: false, appVersionMoved: false})], [{id: '1'}]);
  check(r.rated.length === 0, 'opening a panel with current ratings costs no hub reads');

  const ctx = {Date: Date, isNaN: isNaN, Number: Number};
  vm.createContext(ctx);
  vm.runInContext(code + '\nglobalThis.out = mrDateLabel(Date.UTC(2026, 9, 5, 4, 0));', ctx);
  check(ctx.out === '05 Oct 2026' || ctx.out === '04 Oct 2026',
        'the date renders as dd mmm yyyy');
  vm.runInContext('globalThis.empty = mrDateLabel(null);', ctx);
  check(ctx.empty === '', 'no date renders as nothing rather than as Invalid Date');

  check(source.includes('>Refresh Scan<'), 'the control is labelled Refresh Scan');
  check(source.includes('Previous scan was on '), 'the panel says when the previous scan ran');
  check(!source.includes('>Reassess<'), 'the old Reassess button is gone, not left beside the new one');

  console.log(passed + ' migration refresh policy assertions passed');
})();
