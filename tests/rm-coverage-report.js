const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const source = fs.readFileSync('apps/automation_map.groovy', 'utf8');
const code = source.slice(source.indexOf('function rmcRender()'), source.indexOf('let migrationRequestSeq'));
const box = {innerHTML: ''};
const context = {document: {getElementById: () => box}, extEsc: s => String(s), rmcConstructText: s => s,
  RMC_VERDICT_TEXT: {}, RMC: {body: {ok: true, engine: {name:'HAI-1'},
  summary: {rules:65, constructs:68, unassessedRules:1, unassessedConstructs:2},
  categories: [
    {name:'Actions', dimensions:58, runs:55, scoped:3, hubProven:40, usedHere:28, usedSupported:26, usedUnsupported:1, usedUnassessed:1},
    {name:'Variables', dimensions:17, runs:17, hubProven:7, usedHere:0}],
  rules:[{name:'Unassessed rule',covered:false,gaps:[{token:'assessmentUnavailable'}]}], constructs:[]}}};
vm.createContext(context);
vm.runInContext(code + '\nrmcRender();', context);
const html = box.innerHTML;
assert(html.includes('Detected capabilities'));
assert(html.includes('<td>28</td><td>26</td><td>1</td><td>1</td>'));
assert(html.includes('<td>Variables</td><td>None detected</td><td>-</td><td>-</td><td>-</td>'));
assert(html.includes('2 distinct requirement(s)'));
assert(html.includes('No requirement assessment available'));
assert(!html.includes('Nothing your rules do'));
assert(!html.includes('rules use only what works'));
assert(!html.includes('Of those, seen on a hub'));
assert(source.includes('covered: !tokens.isEmpty() && gaps.isEmpty()'));
console.log('10 reporting assertions passed');
if (process.argv.includes('--preview')) {
  require('http').createServer((req,res) => {
    res.setHeader('Content-Type','text/html');
    res.end('<style>body{background:#062029;color:#ddd;font:15px Arial;padding:24px}table{width:100%;border-collapse:collapse}td,th{text-align:left;padding:10px;border-bottom:1px solid #29414a}p{line-height:1.5}</style>'+html);
  }).listen(8769,'127.0.0.1');
}
