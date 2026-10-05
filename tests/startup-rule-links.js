// A rule whose only trigger is the hub's start event does its work once, at
// boot. On Gordon's hub one such rule contributes 43 of the 70 cross-rule
// links, every one of them resetting a Private Boolean, which buries the 27
// links that change what another rule actually does.
//
// Marked with a flag rather than given a fifth kind: the four rule-link kinds
// are a published contract, and the constraint edge's own `unused` flag is the
// precedent for "same kind, drawn differently, filterable".
const fs = require('fs');
const vm = require('vm');
const source = fs.readFileSync('apps/automation_map.groovy', 'utf8');

let passed = 0;
const check = (cond, what) => {
  if (!cond) { console.log('FAIL  ' + what); process.exit(1); }
  passed++; console.log('PASS  ' + what);
};

// --- the filter, run for real ------------------------------------------
const fnStart = source.indexOf('function edgesForKindFilter(');
const NL = String.fromCharCode(10);
const code = source.slice(fnStart, source.indexOf(NL + '}', fnStart) + 2);
const ctx = { RULE_LINK_KINDS: ['runs', 'cancelTimedActions', 'setspb', 'pauseResume'],
              VARIABLE_KINDS: ['write', 'read', 'usesVar'] };
vm.createContext(ctx);
vm.runInContext(code, ctx);

const edges = [
  { kind: 'setspb', startup: true,  from: 'a2096', to: 'a1999' },
  { kind: 'setspb', startup: true,  from: 'a2096', to: 'a2100' },
  { kind: 'setspb', startup: false, from: 'a3009', to: 'a2351' },
  { kind: 'runs', startup: false, from: 'a2972', to: 'a2973' },
  { kind: 'trigger', startup: false, from: 'a1', to: 'd1' }
];
const f = (v) => ctx.edgesForKindFilter(v, edges);

check(f('rulelinks').length === 4, 'Rule to rule only still returns every rule link, startup included');
check(f('rulelinkslive').length === 2, 'except-startup drops the boot-time links');
check(f('rulelinkslive').every(e => e.startup !== true), 'and keeps none of them');
check(f('rulelinksstartup').length === 2, 'startup-only returns just those');
check(f('rulelinksstartup').every(e => e.startup === true), 'and nothing else');
check(f('rulelinkslive').length + f('rulelinksstartup').length === f('rulelinks').length,
      'the two halves partition the whole: no link is lost or counted twice');
check(f('all').length === 5, 'All relationships is untouched');
check(f('trigger').length === 1, 'a plain kind filter is untouched');

// --- classification is on the trigger, never the name ------------------
const g = source.slice(source.indexOf('Set<String> startupRules'),
                       source.indexOf('// App-to-app edges are emitted in a second pass'));
check(g.includes("contains('systemStart')"),
      'a startup rule is identified by its trigger construct');
check(!/_System Start|'startup'\s*==|name/i.test(g.replace(/\/\/.*$/gm, '')),
      'the rule name is never used to classify it');
check(g.includes('.every {'),
      'every trigger must be systemStart: a rule that also fires on something else does real work');

// --- the contract the four kinds represent is unchanged ----------------
check(source.includes("RULE_LINK_KIND_NAMES = ['runs', 'cancelTimedActions', 'setspb', 'pauseResume']"),
      'no fifth rule-link kind was introduced');
check(source.includes("const RULE_LINK_KINDS = ['runs', 'cancelTimedActions', 'setspb', 'pauseResume']"),
      'and the client agrees with the server on exactly four');

// --- it survives the rendering literal and reaches the legend ----------
check(/startup: e\.startup === true/.test(source),
      'the flag is carried explicitly through edge styling, which drops anything unlisted');
check(source.includes("kindsShown['startupReset'] = true"),
      'a startup reset gets its own legend row');
check(source.includes("e.startup === true) dashes = [1, 6]"),
      'it is drawn distinctly from a live Private Boolean link');

console.log(passed + ' startup rule-link assertions passed');
