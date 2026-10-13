// The map's deep link (?focus=<node id>) and the settings page's hand-on of #focus=, which HAI Migration links to
// because it holds no token for this app. The settings script is sliced from the app and run on a fake page.
const fs = require('fs');
const vm = require('vm');
const src = fs.readFileSync(__dirname + '/../apps/automation_map.groovy', 'utf8');
let bad = 0, ok = 0;
const check = (what, cond) => { if (cond) ok++; else { bad++; console.log('FAIL ' + what); } };

// The map: the first settle reads ?focus= and opens that node, before falling back to the whole map.
const settle = src.indexOf("network.once('stabilizationIterationsDone', function () {\n  setTimeout(");
const settleBody = src.slice(settle, src.indexOf('});', src.indexOf('exitToWholeMap();', settle)));
check('the first settle reads ?focus=', settleBody.indexOf("get('focus')") > 0);
check('a found focus opens the node instead of the whole map', settleBody.indexOf('focusNode(wanted)') > 0 &&
  settleBody.indexOf('focusNode(wanted)') < settleBody.indexOf('exitToWholeMap();'));

// The settings page script.
const m = /paragraph '''<script>(\(function \(\) \{\n  var h = window\.location\.hash[\s\S]*?\}\)\(\);)<\/script>'''/.exec(src);
check('the settings page carries the hand-on script', !!m);
const run = (hash, href) => {
  let went = null;
  const ctx = {window: {location: {hash: hash, replace: u => { went = u; }}},
    document: {querySelector: () => href == null ? null : {getAttribute: () => href}}, encodeURIComponent, decodeURIComponent};
  vm.createContext(ctx);
  vm.runInContext(m[1], ctx);
  return went;
};
if (m) {
  check('a rule id goes on to the map with focus', run('#focus=a123', '/apps/api/9/automation-map.html?access_token=t&scan=1') ===
    '/apps/api/9/automation-map.html?access_token=t&scan=1&focus=a123');
  check('no hash, no redirect', run('', '/x?access_token=t') === null);
  check('something that is not a node id is ignored', run('#focus=a1%3Cscript', '/x?access_token=t') === null);
  check('no map link (a scan running), no redirect', run('#focus=a1', null) === null);
}
console.log(ok + ' passed');
process.exit(bad ? 1 : 0);
