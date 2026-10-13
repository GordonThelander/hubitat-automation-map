// The settings page's scan progress poll (2.4.37, hub-cost review). Each poll is an execution of this app on the
// hub, at its busiest time, so the poll runs every 4 s rather than every 1.5 s, and not at all while the tab is
// hidden. These run the page's own functions, taken from the app source; only the Groovy placeholders in them are
// filled in, with invented addresses.
//
// Usage: node tests/scan-progress-poll.js
'use strict';

const fs = require('fs');
const vm = require('vm');
const source = fs.readFileSync('apps/automation_map.groovy', 'utf8');

function extractFunction(name, from) {
  const start = source.indexOf('function ' + name + '(', from || 0);
  if (start < 0) throw new Error('could not find function ' + name);
  let depth = 0;
  for (let i = source.indexOf('{', start); i < source.length; i++) {
    if (source[i] === '{') depth++;
    else if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  throw new Error('unbalanced ' + name);
}

// The settings page's script, not the map page's (which has its own amPickURL further down).
const page = source.indexOf('function amIsLocalAccess(');
const code = ['amIsLocalAccess', 'amPickURL', 'amReconcileRunning', 'amPollLater', 'amProgressPoll']
  .map(function (n) { return extractFunction(n, page); }).join('\n')
  .replace(/\$\{getLocalOrigin\(\)\}/g, 'http://hub.example')
  .replace(/\$\{getLocalURL\('([^']+)'\)\}/g, '/apps/api/1/$1')
  .replace(/\$\{getCloudURL\('([^']+)'\)\}/g, 'https://cloud.example/$1');
if (code.indexOf('${') >= 0) throw new Error('a Groovy placeholder was left in the page script');

let passed = 0;
const check = (cond, what) => {
  if (!cond) { console.log('FAIL  ' + what); process.exit(1); }
  passed++; console.log('PASS  ' + what);
};

function page_(hidden, answers) {
  const listeners = {};
  const timers = [];
  const fetched = [];
  const progress = { textContent: '' };
  const document = {
    hidden: hidden,
    getElementById: function (id) { return id === 'amProgress' ? progress : null; },
    addEventListener: function (ev, fn) { (listeners[ev] = listeners[ev] || []).push(fn); },
    removeEventListener: function (ev, fn) { listeners[ev] = (listeners[ev] || []).filter(function (f) { return f !== fn; }); }
  };
  const ctx = {
    document: document, URL: URL, window: { location: { hostname: 'hub.example' } },
    location: { reload: function () { ctx.reloaded = true; } },
    setTimeout: function (fn, ms) { timers.push({ fn: fn, ms: ms }); },
    fetch: function (url) {
      fetched.push(url);
      const d = answers.length ? answers.shift() : { running: false };
      return Promise.resolve({ json: function () { return Promise.resolve(d); } });
    }
  };
  vm.createContext(ctx);
  vm.runInContext('var amPolling = false; var amSawRunning = false;\n' + code, ctx);
  return { ctx: ctx, listeners: listeners, timers: timers, fetched: fetched, progress: progress, document: document };
}

const settle = () => new Promise(function (r) { setImmediate(r); });

(async function () {
  const running = { running: true, phase: 'apps', done: 3, total: 10 };

  // A visible tab polls again in 4 s, not 1.5 s.
  const vis = page_(false, [running]);
  vis.ctx.amProgressPoll();
  await settle();
  check(vis.fetched.length === 1 && vis.fetched[0] === '/apps/api/1/scan-status', 'the poll reads /scan-status locally');
  check(vis.timers.length === 1 && vis.timers[0].ms === 4000, 'a running scan is polled again after 4 s (was 1.5 s)');
  check(vis.timers[0].fn === vis.ctx.amProgressPoll, 'and the timer is the poll itself');
  check(vis.progress.textContent.indexOf('3 of 10') >= 0, 'the progress line still updates');

  // A hidden tab schedules nothing until it is shown again.
  const hid = page_(true, [running, running]);
  hid.ctx.amProgressPoll();
  await settle();
  check(hid.timers.length === 0, 'a hidden tab sets no timer');
  check((hid.listeners.visibilitychange || []).length === 1, 'it waits for the tab to be shown');
  // Still hidden: a visibility event that leaves it hidden does nothing.
  hid.listeners.visibilitychange[0]();
  await settle();
  check(hid.fetched.length === 1 && (hid.listeners.visibilitychange || []).length === 1, 'still hidden, it does not poll');
  hid.document.hidden = false;
  hid.listeners.visibilitychange[0]();
  await settle();
  check(hid.fetched.length === 2, 'shown again, it polls at once');
  check((hid.listeners.visibilitychange || []).length === 0, 'and stops listening, so one showing is one poll');
  check(hid.timers.length === 1 && hid.timers[0].ms === 4000, 'then carries on every 4 s while visible');

  // A finished scan reloads once rather than polling on.
  const done = page_(false, [running, { running: false }]);
  done.ctx.amProgressPoll();
  await settle();
  done.timers[0].fn();
  await settle();
  check(done.ctx.reloaded === true && done.timers.length === 1, 'a finished scan reloads the page and sets no further timer');

  check(!/setTimeout\(amProgressPoll, 1500\)/.test(source), 'no 1.5 s progress poll is left in the page');

  console.log(passed + ' scan progress poll checks passed');
})().catch(function (e) { console.log('FAIL  ' + e.stack); process.exit(1); });
