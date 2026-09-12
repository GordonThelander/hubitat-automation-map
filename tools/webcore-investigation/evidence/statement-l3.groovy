// Structural (L3) evidence manifest for the twelve webCoRE statement constructs.
//
// Reviewed as a diff, like the registry. For every saved key it records the kind of
// value, how it comes to be persisted, whether a cited executor region reads the saved
// value, any closed condition on either, and the pinned regions that read, write or
// normalise it. Region names are keys of the registry's regionHashes. The inventory
// and exclusions below make the key list exhaustive for the reviewed regions.
//
// Source authority is the pinned executor and editor serializer. The hosted editor
// is an observed environment, never assumed byte-identical to the pinned code, and
// sanitised fixtures of what it actually saved are the bridge between the two.
// Evidence here raises no level by itself. The registry generator promotes a statement family
// to L3 only when the pinned serializer shape and runtime consumer are cited and at least one
// committed editor save of it, with its matching round trip, is sanitised, inert and valid.
// A branch without that evidence is an explicit gap and holds only the occurrence taking it.
[
  provenance: [
    repo: 'https://github.com/imnotbob/webCoRE.git',
    branch: 'hubitat-patches',
    commit: '0a37eee2537accd706aaaeeed5a7b4bb0c82646e',
    executorPath: 'smartapps/ady624/webcore-piston.src/webcore-piston.groovy',
    editorPath: 'dashboard/js/modules/piston.module.js'
  ],

  // The hosted editor that saves fixture pistons. Recorded as observed, not pinned.
  observedEditorEnvironment: [
    url: 'https://dashboard.webcore.co',
    ideVersion: 'v0.3.114.20220203',
    captureDate: null,
    workflow: 'supported editor: create or edit a paused piston, save, never execute'
  ],

  // What an L3 claim requires for every family, checked by the L3 gate in A3.
  l3Agreement: ['pinned-serializer-shape', 'pinned-runtime-consumer', 'sanitised-editor-occurrence'],

  kinds: ['scalar', 'scalar-list', 'operand', 'device-list', 'statement-list', 'condition-list', 'event-list',
          'elseif-list', 'case-list', 'task-list', 'restriction-list', 'operand-list'],

  // persisted, judged after compilePiston deletes every false, null and empty-string property:
  //   always         always assigned a value that survives that deletion
  //   unless-empty   always assigned, but present only when not false, null or empty
  //   when           assigned only while persistedWhen holds
  //   user-optional  assigned only from an optional user choice
  //   round-trip     written by the hub on the copy the IDE opens, kept and re-saved by the editor,
  //                  so absent on nodes created since the piston was opened
  //   never          the serializer's only value is deleted
  persistence: ['always', 'unless-empty', 'when', 'user-optional', 'round-trip', 'never'],
  // read: a cited executor region reads the saved value. replaced-on-load: the loader overwrites
  // it first (normalisedBy names where), and readBy lists regions that read the replacement.
  // not-cited: no cited region reads it, which is not a claim about the rest of the executor.
  consumption: ['read', 'replaced-on-load', 'not-cited'],
  // A when key present outside its condition is a mismatch, or was kept by an edit path that
  // never deletes it: its container kind is still validated, but it is never consumed there.
  outsideWhen: ['mismatch', 'retained-unconsumed'],
  // Predicates take exactly one form: [key: path, oneOf: [...]], [key: path, noneOf: [...]],
  // [context: [...]] or [all: [predicates]]. A path is key or key.key on the node itself.
  contexts: ['condition-list-member', 'followed-by-first-step', 'followed-by-later-step'],

  // Lists whose elements are validated within the occurrence. Statement lists are their own
  // occurrences. Restriction-list members are outside this increment: only the container is validated.
  lists: [
    'condition-list': [element: 'condition', ownerKey: 'o', ownerOneOf: ['followed by'],
                       first: 'followed-by-first-step', rest: 'followed-by-later-step', otherwise: 'condition-list-member'],
    'event-list':  [element: 'event'],
    'elseif-list': [element: 'elseif'],
    'case-list':   [element: 'case'],
    'task-list':   [element: 'task']
  ],

  // Keys any statement may carry.
  common: [
    't':   [kind: 'scalar', persisted: 'always',        consumed: 'read',      readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']],
    '$':   [kind: 'scalar', persisted: 'round-trip',    consumed: 'replaced-on-load', readBy: ['executor.stmt-num', 'executor.statement-dispatch', 'executor.execute-action', 'executor.schedule-timer'],
            writtenBy: ['executor.set-ids'], normalisedBy: ['executor.clear-ids', 'executor.set-ids']],
    'a':   [kind: 'scalar', persisted: 'always',        consumed: 'read',      values: ['0', '1'], readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement'], normalisedBy: ['executor.clean-code']],
    'tep': [kind: 'scalar', persisted: 'unless-empty',  consumed: 'read',      values: ['c', 'p', 'b'], readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']],
    'tsp': [kind: 'scalar', persisted: 'unless-empty',  consumed: 'read',      values: ['a'], readBy: ['executor.execute-action'], writtenBy: ['editor.update-statement']],
    'tcp': [kind: 'scalar', persisted: 'unless-empty',  consumed: 'not-cited', values: ['c', 'p', 'b'], readBy: [], writtenBy: ['editor.update-statement'], normalisedBy: ['executor.clean-code']],
    'r':   [kind: 'restriction-list', persisted: 'always', consumed: 'read',   readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']],
    'rop': [kind: 'scalar', persisted: 'always',        consumed: 'read',      readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-statement'], normalisedBy: ['executor.clean-code']],
    'rn':  [kind: 'scalar', persisted: 'unless-empty',  consumed: 'read',      values: [true], readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-statement'], normalisedBy: ['executor.clean-code']],
    'di':  [kind: 'scalar', persisted: 'unless-empty',  consumed: 'not-cited', values: [true], readBy: [], writtenBy: ['editor.update-statement'], normalisedBy: ['executor.clean-code']],
    'z':   [kind: 'scalar', persisted: 'unless-empty',  consumed: 'not-cited', readBy: [], writtenBy: ['editor.update-statement'], normalisedBy: ['executor.clean-code']],
    'sm':  [kind: 'scalar', persisted: 'user-optional', consumed: 'not-cited', readBy: [], writtenBy: ['editor.update-statement'], normalisedBy: ['executor.clean-code']]
  ],

  statements: [
    'wc.statement.action': [family: 'action', keys: [
      'd': [kind: 'device-list', persisted: 'always', consumed: 'read', readBy: ['executor.execute-action'], writtenBy: ['editor.update-statement']],
      'k': [kind: 'task-list',   persisted: 'always', consumed: 'read', readBy: ['executor.execute-action'], writtenBy: ['editor.update-statement']]],
      fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-03-switch.edit-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-05-iteration.edit-save', 'l3-05-iteration.first-save', 'l3-06-timers.first-save', 'l3-07-events.edit-save', 'l3-07-events.first-save', 'l3-08-policies.first-save', 'l3-09-tasks.first-save', 'l3-10-targets.first-save'],
      tests: ['l3-manifest', 'l3-fixtures', 'census-walker']],
    'wc.statement.if': [family: 'conditional', keys: [
      'o':  [kind: 'scalar',         persisted: 'always',       consumed: 'read', readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-statement']],
      'n':  [kind: 'scalar',         persisted: 'unless-empty', consumed: 'read', values: [true], readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-statement']],
      'c':  [kind: 'condition-list', persisted: 'always',       consumed: 'read', readBy: ['executor.statement-dispatch', 'executor.evaluate-conditions'], writtenBy: ['editor.update-statement']],
      's':  [kind: 'statement-list', persisted: 'always',       consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']],
      'ei': [kind: 'elseif-list',    persisted: 'always',       consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']],
      'e':  [kind: 'statement-list', persisted: 'always',       consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']]],
      fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-11-physical.first-save', 'l3-12-preset.first-save'],
      tests: ['l3-manifest', 'l3-fixtures', 'census-walker']],
    'wc.statement.while': [family: 'conditional-loop', keys: [
      'o': [kind: 'scalar',         persisted: 'always',       consumed: 'read', readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-statement']],
      'n': [kind: 'scalar',         persisted: 'unless-empty', consumed: 'read', values: [true], readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-statement']],
      'c': [kind: 'condition-list', persisted: 'always',       consumed: 'read', readBy: ['executor.statement-dispatch', 'executor.evaluate-conditions'], writtenBy: ['editor.update-statement']],
      's': [kind: 'statement-list', persisted: 'always',       consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']]],
      fixtures: ['l3-04-loops.edit-save', 'l3-04-loops.first-save'],
      tests: ['l3-manifest', 'l3-fixtures', 'census-walker']],
    'wc.statement.repeat': [family: 'conditional-loop', keys: [
      'o': [kind: 'scalar',         persisted: 'always',       consumed: 'read', readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-statement']],
      'n': [kind: 'scalar',         persisted: 'unless-empty', consumed: 'read', values: [true], readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-statement']],
      'c': [kind: 'condition-list', persisted: 'always',       consumed: 'read', readBy: ['executor.statement-dispatch', 'executor.evaluate-conditions'], writtenBy: ['editor.update-statement']],
      's': [kind: 'statement-list', persisted: 'always',       consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']]],
      fixtures: ['l3-04-loops.edit-save', 'l3-04-loops.first-save'],
      tests: ['l3-manifest', 'l3-fixtures', 'census-walker']],
    // scheduleTimer evaluates lo2 and lo3 only for day, week, month and year intervals.
    'wc.statement.every': [family: 'timer', keys: [
      'lo':  [kind: 'operand', persisted: 'always', consumed: 'read', readBy: ['executor.schedule-timer'], writtenBy: ['editor.update-statement']],
      'lo2': [kind: 'operand', persisted: 'always', consumed: 'read', consumedWhen: [key: 'lo.vt', oneOf: ['d', 'w', 'n', 'y']],
              readBy: ['executor.schedule-timer'], writtenBy: ['editor.update-statement'], normalisedBy: ['executor.clean-code']],
      'lo3': [kind: 'operand', persisted: 'always', consumed: 'read', consumedWhen: [key: 'lo.vt', oneOf: ['d', 'w', 'n', 'y']],
              readBy: ['executor.schedule-timer'], writtenBy: ['editor.update-statement'], normalisedBy: ['executor.clean-code']],
      's':   [kind: 'statement-list', persisted: 'always', consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']]],
      fixtures: ['l3-06-timers.first-save'],
      tests: ['l3-manifest', 'l3-fixtures', 'census-walker']],
    'wc.statement.on': [family: 'event', keys: [
      'c': [kind: 'event-list',     persisted: 'always', consumed: 'read',      readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']],
      'o': [kind: 'scalar',         persisted: 'always', consumed: 'not-cited', values: ['or'], readBy: [], writtenBy: ['editor.update-statement']],
      'n': [kind: 'scalar',         persisted: 'never',  consumed: 'not-cited', readBy: [], writtenBy: ['editor.update-statement']],
      's': [kind: 'statement-list', persisted: 'always', consumed: 'read',      readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']]],
      fixtures: ['l3-07-events.edit-save', 'l3-07-events.first-save'],
      tests: ['l3-manifest', 'l3-fixtures', 'census-walker']],
    'wc.statement.each': [family: 'iteration', keys: [
      'x':  [kind: 'scalar',         persisted: 'unless-empty', consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']],
      'lo': [kind: 'operand',        persisted: 'always',       consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']],
      's':  [kind: 'statement-list', persisted: 'always',       consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']]],
      fixtures: ['l3-05-iteration.edit-save', 'l3-05-iteration.first-save'],
      tests: ['l3-manifest', 'l3-fixtures', 'census-walker']],
    'wc.statement.for': [family: 'iteration', keys: [
      'x':   [kind: 'scalar',         persisted: 'unless-empty', consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']],
      'lo':  [kind: 'operand',        persisted: 'always',       consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']],
      'lo2': [kind: 'operand',        persisted: 'always',       consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']],
      'lo3': [kind: 'operand',        persisted: 'always',       consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']],
      's':   [kind: 'statement-list', persisted: 'always',       consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']]],
      fixtures: ['l3-05-iteration.edit-save', 'l3-05-iteration.first-save'],
      tests: ['l3-manifest', 'l3-fixtures', 'census-walker']],
    // subscribeAll removes a switch's s and ct, then subscribes its operand and writes them again.
    'wc.statement.switch': [family: 'switch', keys: [
      'lo':  [kind: 'operand',        persisted: 'always', consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']],
      'cs':  [kind: 'case-list',      persisted: 'always', consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']],
      'e':   [kind: 'statement-list', persisted: 'always', consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']],
      'ctp': [kind: 'scalar',         persisted: 'always', consumed: 'read', values: ['i', 'e'], readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement'], normalisedBy: ['executor.clean-code']],
      'ct':  [kind: 'scalar', persisted: 'round-trip', consumed: 'replaced-on-load', values: ['c'], readBy: [], writtenBy: ['executor.subscribe-all'], normalisedBy: ['executor.subscribe-all']],
      's':   [kind: 'scalar', persisted: 'round-trip', consumed: 'replaced-on-load', values: [true], readBy: [], writtenBy: ['executor.subscribe-all'], normalisedBy: ['executor.subscribe-all']]],
      fixtures: ['l3-03-switch.edit-save', 'l3-03-switch.first-save'],
      tests: ['l3-manifest', 'l3-fixtures', 'census-walker']],
    'wc.statement.do': [family: 'block', keys: [
      's': [kind: 'statement-list', persisted: 'always', consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']]],
      fixtures: ['l3-04-loops.edit-save'],
      tests: ['l3-manifest', 'l3-fixtures', 'census-walker']],
    'wc.statement.break': [family: 'block', keys: [:],
      fixtures: ['l3-03-switch.edit-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save'],
      tests: ['l3-manifest', 'l3-fixtures', 'census-walker']],
    'wc.statement.exit': [family: 'block', keys: [
      'lo': [kind: 'operand', persisted: 'always', consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-statement']]],
      fixtures: ['l3-05-iteration.edit-save', 'l3-05-iteration.first-save'],
      tests: ['l3-manifest', 'l3-fixtures', 'census-walker']]
  ],

  substructures: [
    // Created by updateCondition as {o: 'and', n: false, c: [], s: []}; the operator and
    // negation are then edited by updateConditionGroup.
    'elseif': [keys: [
      '$': [kind: 'scalar',         persisted: 'round-trip',   consumed: 'replaced-on-load', readBy: ['executor.statement-dispatch'], writtenBy: ['executor.set-ids'], normalisedBy: ['executor.clear-ids', 'executor.set-ids']],
      'o': [kind: 'scalar',         persisted: 'always',       consumed: 'read', readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-condition-group']],
      'n': [kind: 'scalar',         persisted: 'unless-empty', consumed: 'read', values: [true], readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-condition-group']],
      'c': [kind: 'condition-list', persisted: 'always',       consumed: 'read', readBy: ['executor.statement-dispatch', 'executor.evaluate-conditions'], writtenBy: []],
      's': [kind: 'statement-list', persisted: 'always',       consumed: 'read', readBy: ['executor.statement-dispatch'], writtenBy: []]]],
    'case': [keys: [
      '$':   [kind: 'scalar',         persisted: 'round-trip',   consumed: 'replaced-on-load', readBy: [], writtenBy: ['executor.set-ids'], normalisedBy: ['executor.clear-ids', 'executor.set-ids']],
      't':   [kind: 'scalar',         persisted: 'always',       consumed: 'read',      values: ['s', 'r'], readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-case']],
      'ro':  [kind: 'operand',        persisted: 'always',       consumed: 'read',      readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-case']],
      'ro2': [kind: 'operand',        persisted: 'always',       consumed: 'read',      consumedWhen: [key: 't', oneOf: ['r']], readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-case', 'editor.edit-case']],
      's':   [kind: 'statement-list', persisted: 'always',       consumed: 'read',      readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-case']],
      'z':   [kind: 'scalar',         persisted: 'unless-empty', consumed: 'not-cited', readBy: [], writtenBy: ['editor.update-case']]]],
    // subscribeAll sets an event's ct only when it is absent, so a saved ct is read.
    'event': [keys: [
      '$':  [kind: 'scalar',  persisted: 'round-trip',   consumed: 'replaced-on-load', readBy: [], writtenBy: ['executor.set-ids'], normalisedBy: ['executor.clear-ids', 'executor.set-ids']],
      't':  [kind: 'scalar',  persisted: 'always',       consumed: 'read',      values: ['event'], readBy: ['executor.set-ids', 'executor.subscribe-all'], writtenBy: ['editor.edit-event']],
      'lo': [kind: 'operand', persisted: 'always',       consumed: 'read',      readBy: ['executor.statement-dispatch'], writtenBy: ['editor.update-event']],
      'sm': [kind: 'scalar',  persisted: 'always',       consumed: 'not-cited', readBy: [], writtenBy: ['editor.update-event'], normalisedBy: ['executor.clean-code']],
      'z':  [kind: 'scalar',  persisted: 'unless-empty', consumed: 'not-cited', readBy: [], writtenBy: ['editor.update-event']],
      'ct': [kind: 'scalar',  persisted: 'round-trip',   consumed: 'read',      values: ['t'], readBy: ['executor.subscribe-all'], writtenBy: ['executor.subscribe-all']],
      's':  [kind: 'scalar',  persisted: 'round-trip',   consumed: 'replaced-on-load', values: [true], readBy: [], writtenBy: ['executor.subscribe-all'], normalisedBy: ['executor.subscribe-all']]]],
    // m is bound to a multiple select seeded with an empty string, which compilePiston deletes.
    // cm is true for a custom command; the executor runs the command named in c either way.
    'task': [keys: [
      '$':  [kind: 'scalar',       persisted: 'round-trip',    consumed: 'replaced-on-load', readBy: ['executor.execute-action', 'executor.execute-task'], writtenBy: ['executor.set-ids'], normalisedBy: ['executor.clear-ids', 'executor.set-ids']],
      'c':  [kind: 'scalar',       persisted: 'always',        consumed: 'read',      readBy: ['executor.execute-task'], writtenBy: ['editor.update-task']],
      'cm': [kind: 'scalar',       persisted: 'unless-empty',  consumed: 'not-cited', values: [true], readBy: [], writtenBy: ['editor.update-task']],
      'p':  [kind: 'operand-list', persisted: 'always',        consumed: 'read',      readBy: ['executor.execute-task'], writtenBy: ['editor.update-task']],
      'a':  [kind: 'scalar',       persisted: 'user-optional', consumed: 'not-cited', readBy: [], writtenBy: ['editor.update-task']],
      'm':  [kind: 'scalar-list',  persisted: 'user-optional', consumed: 'read',      readBy: ['executor.execute-task'], writtenBy: ['editor.update-task', 'editor.edit-task'], normalisedBy: ['executor.clean-code']],
      'z':  [kind: 'scalar',       persisted: 'unless-empty',  consumed: 'not-cited', readBy: [], writtenBy: ['editor.update-task']]]],
    // wd and wt are written only on a step after the first of a followed-by list, and no
    // edit path deletes them when that stops being true. The ladder reads wt on every
    // step and wd only after the first. A leaf's ct and s are rewritten by subscribeAll.
    'condition': [
      discriminator: [key: 't', kind: 'scalar', persisted: 'always', consumed: 'read', readBy: ['executor.evaluate-conditions', 'executor.evaluate-condition'], writtenBy: []],
      variants: [
        'condition': [
          '$':   [kind: 'scalar',         persisted: 'round-trip',    consumed: 'replaced-on-load', readBy: ['executor.evaluate-condition'], writtenBy: ['executor.set-ids'], normalisedBy: ['executor.clear-ids', 'executor.set-ids']],
          'lo':  [kind: 'operand',        persisted: 'always',        consumed: 'read',      readBy: ['executor.evaluate-condition'], writtenBy: ['editor.update-condition']],
          'co':  [kind: 'scalar',         persisted: 'always',        consumed: 'read',      readBy: ['executor.evaluate-condition'], writtenBy: ['editor.update-condition']],
          'ro':  [kind: 'operand',        persisted: 'always',        consumed: 'read',      readBy: ['executor.evaluate-condition'], writtenBy: ['editor.update-condition']],
          'ro2': [kind: 'operand',        persisted: 'always',        consumed: 'read',      readBy: ['executor.evaluate-condition'], writtenBy: ['editor.update-condition']],
          'to':  [kind: 'operand',        persisted: 'always',        consumed: 'read',      readBy: ['executor.evaluate-condition'], writtenBy: ['editor.update-condition']],
          'to2': [kind: 'operand',        persisted: 'always',        consumed: 'read',      readBy: ['executor.evaluate-condition'], writtenBy: ['editor.update-condition']],
          'ts':  [kind: 'statement-list', persisted: 'always',        consumed: 'read',      readBy: ['executor.evaluate-condition'], writtenBy: ['editor.update-condition']],
          'fs':  [kind: 'statement-list', persisted: 'always',        consumed: 'read',      readBy: ['executor.evaluate-condition'], writtenBy: ['editor.update-condition']],
          'sm':  [kind: 'scalar',         persisted: 'always',        consumed: 'not-cited', readBy: [], writtenBy: ['editor.update-condition'], normalisedBy: ['executor.clean-code']],
          'z':   [kind: 'scalar',         persisted: 'unless-empty',  consumed: 'not-cited', readBy: [], writtenBy: ['editor.update-condition']],
          'ct':  [kind: 'scalar',         persisted: 'round-trip',    consumed: 'replaced-on-load', values: ['t', 'c'], readBy: ['executor.evaluate-conditions'], writtenBy: ['executor.subscribe-all'], normalisedBy: ['executor.subscribe-all']],
          's':   [kind: 'scalar',         persisted: 'round-trip',    consumed: 'replaced-on-load', values: [true], readBy: ['executor.evaluate-conditions', 'executor.evaluate-condition'], writtenBy: ['executor.subscribe-all'], normalisedBy: ['executor.subscribe-all']],
          'wd':  [kind: 'operand', persisted: 'when', persistedWhen: [context: ['followed-by-later-step']], outsideWhen: 'retained-unconsumed',
                  consumed: 'read', consumedWhen: [context: ['followed-by-later-step']], readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-condition']],
          'wt':  [kind: 'scalar', persisted: 'when', persistedWhen: [context: ['followed-by-later-step']], outsideWhen: 'retained-unconsumed',
                  consumed: 'read', consumedWhen: [context: ['followed-by-first-step', 'followed-by-later-step']], values: ['l', 's', 'n'],
                  readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-condition']]],
        'group': [
          '$':   [kind: 'scalar',         persisted: 'round-trip',    consumed: 'replaced-on-load', readBy: ['executor.evaluate-conditions', 'executor.evaluate-condition'], writtenBy: ['executor.set-ids'], normalisedBy: ['executor.clear-ids', 'executor.set-ids']],
          'c':   [kind: 'condition-list', persisted: 'always',        consumed: 'read',      readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-condition']],
          'o':   [kind: 'scalar',         persisted: 'always',        consumed: 'read',      readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-condition', 'editor.update-condition-group']],
          'n':   [kind: 'scalar',         persisted: 'unless-empty',  consumed: 'read',      values: [true], readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-condition', 'editor.update-condition-group']],
          'ts':  [kind: 'statement-list', persisted: 'always',        consumed: 'read',      readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-condition']],
          'fs':  [kind: 'statement-list', persisted: 'always',        consumed: 'read',      readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-condition']],
          'sm':  [kind: 'scalar',         persisted: 'always',        consumed: 'not-cited', readBy: [], writtenBy: ['editor.update-condition'], normalisedBy: ['executor.clean-code']],
          'z':   [kind: 'scalar',         persisted: 'unless-empty',  consumed: 'not-cited', readBy: [], writtenBy: ['editor.update-condition-group']],
          'wd':  [kind: 'operand', persisted: 'when', persistedWhen: [context: ['followed-by-later-step']], outsideWhen: 'retained-unconsumed',
                  consumed: 'read', consumedWhen: [context: ['followed-by-later-step']], readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-condition-group']],
          'wt':  [kind: 'scalar', persisted: 'when', persistedWhen: [context: ['followed-by-later-step']], outsideWhen: 'retained-unconsumed',
                  consumed: 'read', consumedWhen: [context: ['followed-by-first-step', 'followed-by-later-step']], values: ['l', 's', 'n'],
                  readBy: ['executor.evaluate-conditions'], writtenBy: ['editor.update-condition-group']]]
      ]
    ]
  ],

  // Reviewed source-position inventory. For each region and the receiver name it uses for a
  // saved structure: exactly the keys it assigns (or removes), creates as a literal, or reads.
  // The test re-extracts these from the pinned source. Every key must be in the manifest for
  // one of the structures, or for each of them where assignsTo is each, or be excluded below.
  inventory: [
    [region: 'editor.update-statement', receiver: 'statement', structures: ['statement'], assigns: ['a', 'c', 'cs', 'ctp', 'd', 'di', 'e', 'ei', 'k', 'lo', 'lo2', 'lo3', 'n', 'o', 'r', 'rn', 'rop', 's', 'sm', 't', 'tcp', 'tep', 'tsp', 'x', 'z'], literals: ['t'], reads: ['c', 'cs', 'e', 'ei', 'k', 'lo2', 'r', 's', 'sm', 't']],
    [region: 'editor.edit-statement', receiver: 'statement', structures: ['statement'], assigns: ['a', 'ctp', 'd', 'di', 'n', 'o', 'rn', 'rop', 's', 't', 'tcp', 'tep', 'tsp', 'z'], literals: [], reads: ['a', 'ctp', 'd', 'di', 'lo', 'lo2', 'lo3', 'n', 'o', 'rn', 'rop', 'sm', 't', 'tcp', 'tep', 'tsp', 'x', 'z']],
    [region: 'editor.update-condition', receiver: 'condition', structures: ['condition', 'group'], assigns: ['c', 'co', 'fs', 'lo', 'n', 'o', 'ro', 'ro2', 'sm', 'to', 'to2', 'ts', 'wd', 'wt', 'z'], literals: ['t'], reads: ['c', 'fs', 't', 'ts']],
    [region: 'editor.update-condition', receiver: 'elseIf', structures: ['elseif'], assigns: [], literals: ['c', 'n', 'o', 's'], reads: ['c']],
    [region: 'editor.edit-condition', receiver: 'condition', structures: ['condition', 'group'], assigns: ['co', 'd', 'fs', 'lo', 'n', 'o', 'ro', 'ro2', 'sm', 't', 'to', 'to2', 'ts', 'z'], literals: [], reads: ['co', 'd', 'lo', 'n', 'o', 'ro', 'ro2', 'sm', 't', 'to', 'to2', 'wd', 'wt', 'z']],
    [region: 'editor.update-condition-group', receiver: 'group', structures: ['elseif', 'group', 'statement'], assigns: ['n', 'o', 'wd', 'wt', 'z', 'zc'], literals: [], reads: ['t']],
    [region: 'editor.edit-condition-group', receiver: 'group', structures: ['elseif', 'group', 'statement'], assigns: [], literals: [], reads: ['n', 'o', 't', 'wd', 'wt', 'z', 'zc']],
    [region: 'editor.update-case', receiver: '_case', structures: ['case'], assigns: ['ro', 'ro2', 's', 't', 'z'], literals: [], reads: ['s', 't']],
    [region: 'editor.edit-case', receiver: '_case', structures: ['case'], assigns: ['ro', 'ro2', 's', 't', 'z'], literals: [], reads: ['ro', 'ro2', 't', 'z']],
    [region: 'editor.update-event', receiver: 'event', structures: ['event'], assigns: ['lo', 'sm', 'z'], literals: ['t'], reads: ['t']],
    [region: 'editor.edit-event', receiver: 'event', structures: ['event'], assigns: ['lo', 'sm', 't', 'z'], literals: [], reads: ['lo', 'sm', 't', 'z']],
    [region: 'editor.update-task', receiver: 'task', structures: ['task'], assigns: ['a', 'c', 'cm', 'm', 'p', 'z'], literals: [], reads: ['c', 'p']],
    [region: 'editor.edit-task', receiver: 'task', structures: ['task'], assigns: ['a', 'c', 'm', 'z'], literals: [], reads: ['c', 'cm', 'm', 'z']],
    [region: 'executor.statement-dispatch', receiver: 'statement', structures: ['statement'], assigns: [], literals: [], reads: ['$', 'a', 'c', 'cs', 'ctp', 'e', 'ei', 'lo', 'lo2', 'lo3', 'r', 's', 't', 'tep', 'x']],
    [region: 'executor.statement-dispatch', receiver: '_case', structures: ['case'], assigns: [], literals: [], reads: ['ro', 'ro2', 's', 't']],
    [region: 'executor.statement-dispatch', receiver: 'elseIf', structures: ['elseif'], assigns: [], literals: [], reads: ['s']],
    [region: 'executor.execute-action', receiver: 'statement', structures: ['statement'], assigns: [], literals: [], reads: ['$', 'd', 'k', 'tsp']],
    [region: 'executor.execute-action', receiver: 'task', structures: ['task'], assigns: [], literals: [], reads: ['$']],
    [region: 'executor.execute-task', receiver: 'task', structures: ['task'], assigns: [], literals: [], reads: ['$', 'c', 'm', 'p']],
    [region: 'executor.schedule-timer', receiver: 'timer', structures: ['statement'], assigns: [], literals: [], reads: ['$', 'lo', 'lo2', 'lo3']],
    [region: 'executor.evaluate-conditions', receiver: 'cndtns', structures: ['elseif', 'group', 'statement'], assigns: [], literals: [], reads: ['$', 'fs', 'n', 'o', 'rn', 'rop', 'ts']],
    [region: 'executor.evaluate-conditions', receiver: 'cndtn', structures: ['condition', 'group'], assigns: [], literals: [], reads: ['ct', 's', 't', 'wd', 'wt']],
    [region: 'executor.evaluate-condition', receiver: 'cndtn', structures: ['condition', 'group'], assigns: [], literals: [], reads: ['$', 'co', 'fs', 'lo', 'n', 'ro', 'ro2', 's', 't', 'to', 'to2', 'ts']],
    [region: 'executor.stmt-num', receiver: 'stmt', structures: ['condition', 'elseif', 'group', 'statement', 'task'], assigns: [], literals: [], reads: ['$']],
    [region: 'executor.set-ids', receiver: 'node', structures: ['condition', 'event', 'group', 'restriction', 'statement'], assigns: [], literals: [], reads: ['$', 'cs', 'ei', 'k', 't']],
    [region: 'executor.set-ids', receiver: 'elseIf', structures: ['elseif'], assigns: [], literals: [], reads: ['$']],
    [region: 'executor.set-ids', receiver: '_case', structures: ['case'], assigns: [], literals: [], reads: ['$']],
    [region: 'executor.set-ids', receiver: 'task', structures: ['task'], assigns: [], literals: [], reads: ['$']],
    [region: 'executor.set-ids', receiver: 'item', structures: ['case', 'condition', 'elseif', 'event', 'group', 'restriction', 'statement', 'task'], assignsTo: 'each', assigns: ['$'], literals: [], reads: []],
    [region: 'executor.clear-ids', receiver: 'node', structures: ['case', 'condition', 'elseif', 'event', 'group', 'restriction', 'statement', 'task'], assignsTo: 'each', assigns: ['$'], literals: [], reads: ['$']],
    [region: 'executor.add-warning', receiver: 'node', structures: ['condition', 'statement'], assigns: ['w'], literals: [], reads: ['w']],
    [region: 'executor.subscribe-all', receiver: 'cndtn', structures: ['condition', 'event', 'statement'], assignsTo: 'each', assigns: ['ct', 's'], literals: [], reads: ['$', 'co', 'ct', 'fs', 'lo', 'ro', 'ro2', 's', 'sm', 't', 'ts']],
    [region: 'executor.subscribe-all', receiver: 'ei', structures: ['elseif'], assigns: [], literals: [], reads: ['c', 's']],
    [region: 'executor.subscribe-all', receiver: 'k', structures: ['task'], assigns: [], literals: [], reads: ['$', 'c', 'p']],
    [region: 'executor.subscribe-all', receiver: 'c', structures: ['case'], assigns: [], literals: [], reads: ['ro', 'ro2', 's', 't']],
    [region: 'executor.subscribe-all', receiver: 'event', structures: ['event'], assigns: [], literals: [], reads: ['lo']],
    [region: 'executor.subscribe-all', receiver: 'node', structures: ['condition', 'event', 'group', 'restriction', 'statement'], assigns: ['ct', 's', 'w'], literals: [], reads: ['a', 'c', 'co', 'cs', 'ct', 'ei', 'k', 'lo', 'r', 's', 't']],
    [region: 'executor.subscribe-all', receiver: 'restriction', structures: ['restriction'], assigns: [], literals: [], reads: ['co', 'lo', 'ro', 'ro2']]
  ],

  exclusionReasons: ['editor-seed-only', 'opaque-field', 'deleted-by-serializer', 'restriction-member-outside-increment'],
  exclusions: [
    // editCondition seeds d on the object it edits, but a new condition is saved from a fresh object.
    [structures: ['condition'], key: 'd', reason: 'editor-seed-only'],
    [structures: ['elseif', 'statement'], key: 'zc', reason: 'opaque-field'],
    [structures: ['condition', 'statement'], key: 'w', reason: 'deleted-by-serializer'],
    [structures: ['restriction'], key: '$', reason: 'restriction-member-outside-increment'],
    [structures: ['restriction'], key: 'co', reason: 'restriction-member-outside-increment'],
    [structures: ['restriction'], key: 'lo', reason: 'restriction-member-outside-increment'],
    [structures: ['restriction'], key: 'ro', reason: 'restriction-member-outside-increment'],
    [structures: ['restriction'], key: 'ro2', reason: 'restriction-member-outside-increment']
  ],

  // Where the walker's routing disagrees with a manifest kind. None remain.
  walkerRoutingDifferences: [],

  // What the Hub changes on the copy the IDE opens, which a later save keeps. get() calls
  // getRunTimeData() with inMem and doit false, so only transformations outside an inMem or doit
  // guard reach that copy. Each guard is checked against its source line, and every unguarded
  // cleanCode transformation must be listed.
  roundTripGuards: ['always', 'inMem', 'doit'],
  roundTripEffects: ['assign', 'remove', 'remove-node', 'operand-internal'],
  roundTripConditions: ['empty-list', 'empty-map', 'no-conditions-and-no-statements', 'value-0', 'absent', 'value-c',
                        'empty-restrictions', 'value-auto', 'value-i', 'short-interval', 'comparison-arity'],
  roundTrip: [
    [region: 'executor.set-ids', source: 'item[sDLR]=maxId', guard: 'always', effect: 'assign', key: '$'],
    [region: 'executor.set-ids', source: 'liMs(node,sEI).removeAll{ Map it -> !it.c && !it.s }', guard: 'always', effect: 'remove-node', key: 'ei', when: 'no-conditions-and-no-statements'],
    [region: 'executor.clean-code', source: 'if(item[sC] && item[sM] instanceof List && !(List)item[sM]) item.remove(sM)', guard: 'always', effect: 'remove', key: 'm', when: 'empty-list'],
    [region: 'executor.clean-code', source: 'if(item[sDATA] instanceof Map && !mMs(item,sDATA)) item.remove(sDATA)', guard: 'always', effect: 'remove', key: 'data', when: 'empty-map'],
    [region: 'executor.subscribe-all', source: 'cndtn[sCT]=(String)cmpTyp.take(i1)', guard: 'always', effect: 'assign', key: 'ct'],
    [region: 'executor.subscribe-all', source: 'if(cndtn.containsKey(sS)) cndtn.remove(sS)', guard: 'always', effect: 'remove', key: 's'],
    [region: 'executor.subscribe-all', source: 'if(node.containsKey(sS)) node.remove(sS)', guard: 'always', effect: 'remove', key: 's'],
    [region: 'executor.subscribe-all', source: 'if(node.containsKey(sCT)){ node.remove(sCT) }', guard: 'always', effect: 'remove', key: 'ct'],
    [region: 'executor.subscribe-all', source: 'node.remove(sW)', guard: 'always', effect: 'remove', key: 'w'],
    [region: 'executor.clean-code', source: 'if(item[sA] instanceof String && sMa(item)==s0) item.remove(sA)', guard: 'inMem', effect: 'remove', key: 'a', when: 'value-0'],
    [region: 'executor.clean-code', source: 'if(!sMs(item,sTCP))item[sTCP]=sN', guard: 'inMem', effect: 'assign', key: 'tcp', when: 'absent'],
    [region: 'executor.clean-code', source: 'else if(sMs(item,sTCP)==sC) item.remove(sTCP)', guard: 'inMem', effect: 'remove', key: 'tcp', when: 'value-c'],
    [region: 'executor.clean-code', source: 'if(item[sROP] && (!item[sR] || liMs(item,sR).size()==iZ)){ item.remove(sROP); item.remove(sRN) }', guard: 'inMem', effect: 'remove', key: 'rop', when: 'empty-restrictions'],
    [region: 'executor.clean-code', source: 'if(item[sW] instanceof List) item.remove(sW)', guard: 'inMem', effect: 'remove', key: 'w'],
    [region: 'executor.clean-code', source: 'if(sMs(item,sSM)==sAUTO) item.remove(sSM)', guard: 'inMem', effect: 'remove', key: 'sm', when: 'value-auto'],
    [region: 'executor.clean-code', source: 'if(sMs(item,sCTP)==sI) item.remove(sCTP)', guard: 'inMem', effect: 'remove', key: 'ctp', when: 'value-i'],
    [region: 'executor.clean-code', source: 'if(item[sZ]!=null) item.remove(sZ)', guard: 'inMem', effect: 'remove', key: 'z'],
    [region: 'executor.clean-code', source: 'if(sMvt(mMs(item,sLO)) in [sMS,sS,sM,sH]){ item.remove(sLO2); item.remove(sLO3) }', guard: 'inMem', effect: 'remove', key: 'lo2', when: 'short-interval'],
    [region: 'executor.clean-code', source: 'if(item[sRO2]!=null)item.remove(sRO2)', guard: 'inMem', effect: 'remove', key: 'ro2', when: 'comparison-arity'],
    // Operand and task-parameter internals, below the statement shapes.
    [region: 'executor.clean-code', source: 'if(item[sX]!=null){ item.remove(sX); item.remove(sXI)}', guard: 'always', effect: 'operand-internal', key: 'x'],
    [region: 'executor.clean-code', source: 'if(item[sE]!=null) item.remove(sE)', guard: 'always', effect: 'operand-internal', key: 'e'],
    [region: 'executor.clean-code', source: 'if(item[sC]!=null) item.remove(sC)', guard: 'always', effect: 'operand-internal', key: 'c'],
    [region: 'executor.clean-code', source: 'if(item[sV]!=null) item.remove(sV)', guard: 'always', effect: 'operand-internal', key: 'v'],
    [region: 'executor.clean-code', source: 'if(item[sS]!=null) item.remove(sS)', guard: 'always', effect: 'operand-internal', key: 's'],
    [region: 'executor.clean-code', source: 'if(item[sU]!=null) item.remove(sU)', guard: 'always', effect: 'operand-internal', key: 'u'],
    [region: 'executor.clean-code', source: 'if(item[sEXP]) item.remove(sEXP)', guard: 'always', effect: 'operand-internal', key: 'exp'],
    [region: 'executor.clean-code', source: 'if(item[sA]!=null) item.remove(sA)', guard: 'always', effect: 'operand-internal', key: 'a'],
    [region: 'executor.clean-code', source: 'if(item[sD] instanceof List && item[sD]) item[sD]=[]', guard: 'always', effect: 'operand-internal', key: 'd'],
    [region: 'executor.clean-code', source: 'if(ty in ListC2 && item[sD] instanceof List) item.remove(sD)', guard: 'always', effect: 'operand-internal', key: 'd'],
    [region: 'executor.clean-code', source: 'if(!(ty in ListEC) && item[sEXP]) item.remove(sEXP)', guard: 'always', effect: 'operand-internal', key: 'exp'],
    [region: 'executor.clean-code', source: 'if(ty!=sX && item[sX]!=null){ item.remove(sX); item.remove(sXI)}', guard: 'always', effect: 'operand-internal', key: 'x'],
    [region: 'executor.clean-code', source: 'if(ty!=sE && item[sE]!=null) item.remove(sE)', guard: 'always', effect: 'operand-internal', key: 'e'],
    [region: 'executor.clean-code', source: 'if(ty!=sC && item[sC]!=null) item.remove(sC)', guard: 'always', effect: 'operand-internal', key: 'c'],
    [region: 'executor.clean-code', source: 'if(ty!=sV && oMv(item)!=null) item.remove(sV)', guard: 'always', effect: 'operand-internal', key: 'v'],
    [region: 'executor.clean-code', source: 'if(ty!=sS && item[sS]!=null) item.remove(sS)', guard: 'always', effect: 'operand-internal', key: 's'],
    [region: 'executor.clean-code', source: 'if(ty!=sU && item[sU]!=null) item.remove(sU)', guard: 'always', effect: 'operand-internal', key: 'u'],
    [region: 'executor.clean-code', source: 'if(ty!=sP && item[sA]!=null) item.remove(sA)', guard: 'always', effect: 'operand-internal', key: 'a']
  ],

  // Fixture captures and their lineage. A save records what the editor authored; the round trip of the
  // same piston and capture records what the hub canonically persists after loading it. An edit round
  // trip is itself the save of a chained round trip where one is committed.
  captureKinds: ['first-save', 'round-trip', 'edit-save', 'edit-round-trip', 'chained-round-trip'],
  captureLineage: ['first-save': 'round-trip', 'edit-save': 'edit-round-trip', 'edit-round-trip': 'chained-round-trip'],
  namedTests: ['l3-manifest': 'tests/webcore-l3-manifest.groovy', 'l3-fixtures': 'tests/webcore-l3-fixtures.groovy',
               'census-walker': 'tests/webcore-census-walker.groovy'],
  branchGaps: ['needs-physical-device', 'not-in-matrix', 'observed-at-capture', 'editor-authored-only', 'canonical-only'],

  // Every optional or conditional branch. fixtures names each committed save that takes the branch and
  // whose round trip still takes it; only those promote it. editorAuthored names saves whose round trip
  // no longer takes it, canonicalOnly round trips whose save did not. A branch with no promoting save
  // carries one gap, and holds any occurrence that takes it at L2.
  branchEvidence: [
    [structure: 'statement', key: '$', branch: 'present', fixtures: ['l3-01-conditional.edit-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-03-switch.edit-save', 'l3-04-loops.edit-save', 'l3-05-iteration.edit-save', 'l3-07-events.edit-save', 'l3-08-policies.first-save'], canonicalOnly: ['l3-01-conditional.round-trip', 'l3-02-followed-by.round-trip', 'l3-03-switch.round-trip', 'l3-04-loops.round-trip', 'l3-05-iteration.round-trip', 'l3-06-timers.round-trip', 'l3-07-events.round-trip', 'l3-09-tasks.round-trip', 'l3-10-targets.round-trip', 'l3-11-physical.round-trip', 'l3-12-preset.round-trip']],
    [structure: 'statement', key: '$', branch: 'absent', gap: 'editor-authored-only', editorAuthored: ['l3-01-conditional.first-save', 'l3-02-followed-by.first-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-05-iteration.first-save', 'l3-06-timers.first-save', 'l3-07-events.first-save', 'l3-09-tasks.first-save', 'l3-10-targets.first-save', 'l3-11-physical.first-save', 'l3-12-preset.first-save']],
    [structure: 'statement', key: 'a', branch: 'value:0', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-03-switch.edit-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-05-iteration.edit-save', 'l3-05-iteration.first-save', 'l3-06-timers.first-save', 'l3-07-events.edit-save', 'l3-07-events.first-save', 'l3-08-policies.first-save', 'l3-09-tasks.first-save', 'l3-10-targets.first-save', 'l3-11-physical.first-save', 'l3-12-preset.first-save']],
    [structure: 'statement', key: 'a', branch: 'value:1', fixtures: ['l3-06-timers.first-save', 'l3-07-events.edit-save', 'l3-07-events.first-save']],
    [structure: 'statement', key: 'tep', branch: 'present', fixtures: ['l3-08-policies.first-save']],
    [structure: 'statement', key: 'tep', branch: 'absent', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-03-switch.edit-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-05-iteration.edit-save', 'l3-05-iteration.first-save', 'l3-06-timers.first-save', 'l3-07-events.edit-save', 'l3-07-events.first-save', 'l3-09-tasks.first-save', 'l3-10-targets.first-save', 'l3-11-physical.first-save', 'l3-12-preset.first-save']],
    [structure: 'statement', key: 'tep', branch: 'value:c', fixtures: ['l3-08-policies.first-save']],
    [structure: 'statement', key: 'tep', branch: 'value:p', fixtures: ['l3-08-policies.first-save']],
    [structure: 'statement', key: 'tep', branch: 'value:b', fixtures: ['l3-08-policies.first-save']],
    [structure: 'statement', key: 'tsp', branch: 'present', fixtures: ['l3-08-policies.first-save']],
    [structure: 'statement', key: 'tsp', branch: 'absent', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-03-switch.edit-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-05-iteration.edit-save', 'l3-05-iteration.first-save', 'l3-06-timers.first-save', 'l3-07-events.edit-save', 'l3-07-events.first-save', 'l3-08-policies.first-save', 'l3-09-tasks.first-save', 'l3-10-targets.first-save', 'l3-11-physical.first-save', 'l3-12-preset.first-save']],
    [structure: 'statement', key: 'tsp', branch: 'value:a', fixtures: ['l3-08-policies.first-save']],
    [structure: 'statement', key: 'tcp', branch: 'present', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-03-switch.edit-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-05-iteration.edit-save', 'l3-05-iteration.first-save', 'l3-06-timers.first-save', 'l3-07-events.edit-save', 'l3-07-events.first-save', 'l3-08-policies.first-save', 'l3-09-tasks.first-save', 'l3-10-targets.first-save', 'l3-11-physical.first-save', 'l3-12-preset.first-save']],
    [structure: 'statement', key: 'tcp', branch: 'absent', gap: 'not-in-matrix'],
    [structure: 'statement', key: 'tcp', branch: 'value:c', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-03-switch.edit-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-05-iteration.edit-save', 'l3-05-iteration.first-save', 'l3-06-timers.first-save', 'l3-07-events.edit-save', 'l3-07-events.first-save', 'l3-08-policies.first-save', 'l3-09-tasks.first-save', 'l3-10-targets.first-save', 'l3-11-physical.first-save', 'l3-12-preset.first-save']],
    [structure: 'statement', key: 'tcp', branch: 'value:p', fixtures: ['l3-08-policies.first-save']],
    [structure: 'statement', key: 'tcp', branch: 'value:b', fixtures: ['l3-08-policies.first-save']],
    [structure: 'statement', key: 'rn', branch: 'present', fixtures: ['l3-08-policies.first-save']],
    [structure: 'statement', key: 'rn', branch: 'absent', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-03-switch.edit-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-05-iteration.edit-save', 'l3-05-iteration.first-save', 'l3-06-timers.first-save', 'l3-07-events.edit-save', 'l3-07-events.first-save', 'l3-08-policies.first-save', 'l3-09-tasks.first-save', 'l3-10-targets.first-save', 'l3-11-physical.first-save', 'l3-12-preset.first-save']],
    [structure: 'statement', key: 'di', branch: 'present', fixtures: ['l3-08-policies.first-save']],
    [structure: 'statement', key: 'di', branch: 'absent', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-03-switch.edit-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-05-iteration.edit-save', 'l3-05-iteration.first-save', 'l3-06-timers.first-save', 'l3-07-events.edit-save', 'l3-07-events.first-save', 'l3-08-policies.first-save', 'l3-09-tasks.first-save', 'l3-10-targets.first-save', 'l3-11-physical.first-save', 'l3-12-preset.first-save']],
    [structure: 'statement', key: 'z', branch: 'present', fixtures: ['l3-03-switch.edit-save', 'l3-03-switch.first-save', 'l3-07-events.edit-save', 'l3-07-events.first-save', 'l3-08-policies.first-save']],
    [structure: 'statement', key: 'z', branch: 'absent', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-03-switch.edit-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-05-iteration.edit-save', 'l3-05-iteration.first-save', 'l3-06-timers.first-save', 'l3-07-events.edit-save', 'l3-07-events.first-save', 'l3-08-policies.first-save', 'l3-09-tasks.first-save', 'l3-10-targets.first-save', 'l3-11-physical.first-save', 'l3-12-preset.first-save']],
    [structure: 'statement', key: 'sm', branch: 'present', gap: 'observed-at-capture'],
    [structure: 'statement', key: 'sm', branch: 'absent', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-03-switch.edit-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-05-iteration.edit-save', 'l3-05-iteration.first-save', 'l3-06-timers.first-save', 'l3-07-events.edit-save', 'l3-07-events.first-save', 'l3-08-policies.first-save', 'l3-09-tasks.first-save', 'l3-10-targets.first-save', 'l3-11-physical.first-save', 'l3-12-preset.first-save']],
    [structure: 'wc.statement.if', key: 'n', branch: 'present', fixtures: ['l3-01-conditional.edit-save']],
    [structure: 'wc.statement.if', key: 'n', branch: 'absent', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-11-physical.first-save', 'l3-12-preset.first-save']],
    [structure: 'wc.statement.while', key: 'n', branch: 'present', fixtures: ['l3-04-loops.edit-save']],
    [structure: 'wc.statement.while', key: 'n', branch: 'absent', fixtures: ['l3-04-loops.edit-save', 'l3-04-loops.first-save']],
    [structure: 'wc.statement.repeat', key: 'n', branch: 'present', fixtures: ['l3-04-loops.edit-save']],
    [structure: 'wc.statement.repeat', key: 'n', branch: 'absent', fixtures: ['l3-04-loops.edit-save', 'l3-04-loops.first-save']],
    [structure: 'wc.statement.every', key: 'lo2', branch: 'consumed', fixtures: ['l3-06-timers.first-save']],
    [structure: 'wc.statement.every', key: 'lo2', branch: 'unconsumed', fixtures: ['l3-06-timers.first-save']],
    [structure: 'wc.statement.every', key: 'lo3', branch: 'consumed', fixtures: ['l3-06-timers.first-save']],
    [structure: 'wc.statement.every', key: 'lo3', branch: 'unconsumed', fixtures: ['l3-06-timers.first-save']],
    [structure: 'wc.statement.on', key: 'o', branch: 'value:or', fixtures: ['l3-07-events.edit-save', 'l3-07-events.first-save']],
    [structure: 'wc.statement.each', key: 'x', branch: 'present', fixtures: ['l3-05-iteration.edit-save']],
    [structure: 'wc.statement.each', key: 'x', branch: 'absent', fixtures: ['l3-05-iteration.first-save']],
    [structure: 'wc.statement.for', key: 'x', branch: 'present', fixtures: ['l3-05-iteration.edit-save', 'l3-05-iteration.first-save']],
    [structure: 'wc.statement.for', key: 'x', branch: 'absent', gap: 'not-in-matrix'],
    [structure: 'wc.statement.switch', key: 'ctp', branch: 'value:i', fixtures: ['l3-03-switch.edit-save', 'l3-03-switch.first-save']],
    [structure: 'wc.statement.switch', key: 'ctp', branch: 'value:e', fixtures: ['l3-03-switch.edit-save']],
    [structure: 'wc.statement.switch', key: 'ct', branch: 'present', fixtures: ['l3-03-switch.edit-save'], canonicalOnly: ['l3-03-switch.round-trip']],
    [structure: 'wc.statement.switch', key: 'ct', branch: 'absent', fixtures: ['l3-03-switch.edit-save', 'l3-03-switch.first-save']],
    [structure: 'wc.statement.switch', key: 'ct', branch: 'value:c', fixtures: ['l3-03-switch.edit-save'], canonicalOnly: ['l3-03-switch.round-trip']],
    [structure: 'wc.statement.switch', key: 's', branch: 'present', fixtures: ['l3-03-switch.edit-save'], canonicalOnly: ['l3-03-switch.round-trip']],
    [structure: 'wc.statement.switch', key: 's', branch: 'absent', fixtures: ['l3-03-switch.edit-save', 'l3-03-switch.first-save']],
    [structure: 'elseif', key: '$', branch: 'present', fixtures: ['l3-01-conditional.edit-save'], canonicalOnly: ['l3-01-conditional.round-trip']],
    [structure: 'elseif', key: '$', branch: 'absent', gap: 'editor-authored-only', editorAuthored: ['l3-01-conditional.first-save']],
    [structure: 'elseif', key: 'n', branch: 'present', fixtures: ['l3-01-conditional.edit-save']],
    [structure: 'elseif', key: 'n', branch: 'absent', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save']],
    [structure: 'case', key: '$', branch: 'present', fixtures: ['l3-03-switch.edit-save'], canonicalOnly: ['l3-03-switch.round-trip']],
    [structure: 'case', key: '$', branch: 'absent', gap: 'editor-authored-only', editorAuthored: ['l3-03-switch.first-save']],
    [structure: 'case', key: 't', branch: 'value:s', fixtures: ['l3-03-switch.edit-save', 'l3-03-switch.first-save']],
    [structure: 'case', key: 't', branch: 'value:r', fixtures: ['l3-03-switch.edit-save', 'l3-03-switch.first-save']],
    [structure: 'case', key: 'ro2', branch: 'consumed', fixtures: ['l3-03-switch.edit-save', 'l3-03-switch.first-save']],
    [structure: 'case', key: 'ro2', branch: 'unconsumed', fixtures: ['l3-03-switch.edit-save', 'l3-03-switch.first-save']],
    [structure: 'case', key: 'z', branch: 'present', fixtures: ['l3-03-switch.edit-save']],
    [structure: 'case', key: 'z', branch: 'absent', fixtures: ['l3-03-switch.edit-save', 'l3-03-switch.first-save']],
    [structure: 'event', key: '$', branch: 'present', fixtures: ['l3-07-events.edit-save'], canonicalOnly: ['l3-07-events.round-trip']],
    [structure: 'event', key: '$', branch: 'absent', gap: 'editor-authored-only', editorAuthored: ['l3-07-events.first-save']],
    [structure: 'event', key: 't', branch: 'value:event', fixtures: ['l3-07-events.edit-save', 'l3-07-events.first-save']],
    [structure: 'event', key: 'z', branch: 'present', fixtures: ['l3-07-events.edit-save', 'l3-07-events.first-save']],
    [structure: 'event', key: 'z', branch: 'absent', fixtures: ['l3-07-events.edit-save']],
    [structure: 'event', key: 'ct', branch: 'present', fixtures: ['l3-07-events.edit-save'], canonicalOnly: ['l3-07-events.round-trip']],
    [structure: 'event', key: 'ct', branch: 'absent', gap: 'editor-authored-only', editorAuthored: ['l3-07-events.first-save']],
    [structure: 'event', key: 'ct', branch: 'value:t', fixtures: ['l3-07-events.edit-save'], canonicalOnly: ['l3-07-events.round-trip']],
    [structure: 'event', key: 's', branch: 'present', fixtures: ['l3-07-events.edit-save'], canonicalOnly: ['l3-07-events.round-trip']],
    [structure: 'event', key: 's', branch: 'absent', gap: 'editor-authored-only', editorAuthored: ['l3-07-events.first-save']],
    [structure: 'task', key: '$', branch: 'present', fixtures: ['l3-01-conditional.edit-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-03-switch.edit-save', 'l3-04-loops.edit-save', 'l3-05-iteration.edit-save', 'l3-07-events.edit-save', 'l3-08-policies.first-save'], canonicalOnly: ['l3-01-conditional.round-trip', 'l3-02-followed-by.round-trip', 'l3-03-switch.round-trip', 'l3-04-loops.round-trip', 'l3-05-iteration.round-trip', 'l3-06-timers.round-trip', 'l3-07-events.round-trip', 'l3-09-tasks.round-trip', 'l3-10-targets.round-trip']],
    [structure: 'task', key: '$', branch: 'absent', gap: 'editor-authored-only', editorAuthored: ['l3-01-conditional.first-save', 'l3-02-followed-by.first-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-05-iteration.first-save', 'l3-06-timers.first-save', 'l3-07-events.first-save', 'l3-09-tasks.first-save', 'l3-10-targets.first-save']],
    [structure: 'task', key: 'cm', branch: 'present', gap: 'needs-physical-device'],
    [structure: 'task', key: 'cm', branch: 'absent', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-03-switch.edit-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-05-iteration.edit-save', 'l3-05-iteration.first-save', 'l3-06-timers.first-save', 'l3-07-events.edit-save', 'l3-07-events.first-save', 'l3-08-policies.first-save', 'l3-09-tasks.first-save', 'l3-10-targets.first-save']],
    [structure: 'task', key: 'a', branch: 'present', gap: 'observed-at-capture'],
    [structure: 'task', key: 'a', branch: 'absent', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-03-switch.edit-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-05-iteration.edit-save', 'l3-05-iteration.first-save', 'l3-06-timers.first-save', 'l3-07-events.edit-save', 'l3-07-events.first-save', 'l3-08-policies.first-save', 'l3-09-tasks.first-save', 'l3-10-targets.first-save']],
    [structure: 'task', key: 'm', branch: 'present', fixtures: ['l3-08-policies.first-save']],
    [structure: 'task', key: 'm', branch: 'absent', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-03-switch.edit-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-05-iteration.edit-save', 'l3-05-iteration.first-save', 'l3-06-timers.first-save', 'l3-07-events.edit-save', 'l3-07-events.first-save', 'l3-08-policies.first-save', 'l3-09-tasks.first-save', 'l3-10-targets.first-save']],
    [structure: 'task', key: 'z', branch: 'present', fixtures: ['l3-08-policies.first-save']],
    [structure: 'task', key: 'z', branch: 'absent', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-03-switch.edit-save', 'l3-03-switch.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-05-iteration.edit-save', 'l3-05-iteration.first-save', 'l3-06-timers.first-save', 'l3-07-events.edit-save', 'l3-07-events.first-save', 'l3-08-policies.first-save', 'l3-09-tasks.first-save', 'l3-10-targets.first-save']],
    [structure: 'condition', key: '$', branch: 'present', fixtures: ['l3-01-conditional.edit-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-04-loops.edit-save'], canonicalOnly: ['l3-01-conditional.round-trip', 'l3-02-followed-by.round-trip', 'l3-04-loops.round-trip', 'l3-11-physical.round-trip', 'l3-12-preset.round-trip']],
    [structure: 'condition', key: '$', branch: 'absent', gap: 'editor-authored-only', editorAuthored: ['l3-01-conditional.first-save', 'l3-02-followed-by.first-save', 'l3-04-loops.first-save', 'l3-11-physical.first-save', 'l3-12-preset.first-save']],
    [structure: 'condition', key: 'z', branch: 'present', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save']],
    [structure: 'condition', key: 'z', branch: 'absent', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save', 'l3-11-physical.first-save', 'l3-12-preset.first-save']],
    [structure: 'condition', key: 'ct', branch: 'present', fixtures: ['l3-01-conditional.edit-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-04-loops.edit-save'], canonicalOnly: ['l3-01-conditional.round-trip', 'l3-02-followed-by.round-trip', 'l3-04-loops.round-trip', 'l3-11-physical.round-trip', 'l3-12-preset.round-trip']],
    [structure: 'condition', key: 'ct', branch: 'absent', gap: 'editor-authored-only', editorAuthored: ['l3-01-conditional.first-save', 'l3-02-followed-by.first-save', 'l3-04-loops.first-save', 'l3-11-physical.first-save', 'l3-12-preset.first-save']],
    [structure: 'condition', key: 'ct', branch: 'value:t', fixtures: ['l3-02-followed-by.edit-round-trip'], canonicalOnly: ['l3-02-followed-by.edit-round-trip']],
    [structure: 'condition', key: 'ct', branch: 'value:c', fixtures: ['l3-01-conditional.edit-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-04-loops.edit-save'], canonicalOnly: ['l3-01-conditional.round-trip', 'l3-02-followed-by.round-trip', 'l3-04-loops.round-trip', 'l3-11-physical.round-trip', 'l3-12-preset.round-trip']],
    [structure: 'condition', key: 's', branch: 'present', fixtures: ['l3-01-conditional.edit-save', 'l3-02-followed-by.edit-round-trip'], canonicalOnly: ['l3-01-conditional.round-trip', 'l3-02-followed-by.edit-round-trip', 'l3-11-physical.round-trip', 'l3-12-preset.round-trip']],
    [structure: 'condition', key: 's', branch: 'absent', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save', 'l3-04-loops.edit-save', 'l3-04-loops.first-save'], editorAuthored: ['l3-11-physical.first-save', 'l3-12-preset.first-save']],
    [structure: 'condition', key: 'wd', branch: 'present', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'condition', key: 'wd', branch: 'retained', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'condition', key: 'wd', branch: 'consumed', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'condition', key: 'wd', branch: 'unconsumed', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'condition', key: 'wt', branch: 'present', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'condition', key: 'wt', branch: 'retained', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'condition', key: 'wt', branch: 'value:l', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'condition', key: 'wt', branch: 'value:s', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'condition', key: 'wt', branch: 'value:n', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'condition', key: 'wt', branch: 'consumed', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'condition', key: 'wt', branch: 'unconsumed', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'group', key: '$', branch: 'present', fixtures: ['l3-01-conditional.edit-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save'], canonicalOnly: ['l3-01-conditional.round-trip', 'l3-02-followed-by.round-trip']],
    [structure: 'group', key: '$', branch: 'absent', gap: 'editor-authored-only', editorAuthored: ['l3-01-conditional.first-save', 'l3-02-followed-by.first-save']],
    [structure: 'group', key: 'n', branch: 'present', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save']],
    [structure: 'group', key: 'n', branch: 'absent', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'group', key: 'z', branch: 'present', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'group', key: 'z', branch: 'absent', fixtures: ['l3-01-conditional.edit-save', 'l3-01-conditional.first-save', 'l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'group', key: 'wd', branch: 'present', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'group', key: 'wd', branch: 'retained', gap: 'not-in-matrix'],
    [structure: 'group', key: 'wd', branch: 'consumed', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'group', key: 'wd', branch: 'unconsumed', gap: 'not-in-matrix'],
    [structure: 'group', key: 'wt', branch: 'present', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'group', key: 'wt', branch: 'retained', gap: 'not-in-matrix'],
    [structure: 'group', key: 'wt', branch: 'value:l', gap: 'not-in-matrix'],
    [structure: 'group', key: 'wt', branch: 'value:s', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'group', key: 'wt', branch: 'value:n', gap: 'not-in-matrix'],
    [structure: 'group', key: 'wt', branch: 'consumed', fixtures: ['l3-02-followed-by.edit-round-trip', 'l3-02-followed-by.edit-save', 'l3-02-followed-by.first-save']],
    [structure: 'group', key: 'wt', branch: 'unconsumed', gap: 'not-in-matrix']
  ],

  // Registry regions that are not inventoried, each for a fixed reason.
  uninventoriedReasons: ['operand-internal', 'catalogue', 'generic-normalisation', 'round-trip-chain'],
  uninventoriedRegions: [
    'executor.evaluate-operand':    'operand-internal',
    'executor.evaluate-expression': 'operand-internal',
    'executor.expand-device-list':  'operand-internal',
    'catalogue.virtual-commands':   'catalogue',
    'catalogue.functions-fld':      'catalogue',
    'executor.functions':           'catalogue',
    'executor.virtual-commands':    'catalogue',
    'executor.clean-code':          'generic-normalisation',
    'editor.compile-piston':        'generic-normalisation',
    'executor.get':                 'round-trip-chain',
    'executor.runtime-data':        'round-trip-chain',
    'executor.recreate-piston':     'round-trip-chain',
    'editor.load-piston':           'round-trip-chain'
  ],

  // Literal source text each conditional, persistence or exclusion claim rests on, checked against
  // the pinned checkout. A constant entry checks an executor @Field value.
  sourceAssertions: [
    [region: 'editor.compile-piston', contains: 'if ((v === false) || (v === null) || (v === \'\')) {', supports: 'persisted unless-empty and never'],
    [region: 'editor.compile-piston', contains: 'delete(object[property]);', supports: 'persisted unless-empty and never'],
    [region: 'editor.compile-piston', contains: 'delete(object.w);', supports: 'exclusion w deleted-by-serializer'],
    [region: 'executor.get', contains: 'r9= getRunTimeData()', supports: 'persisted round-trip'],
    [region: 'executor.get', contains: '(sPISTN): (LinkedHashMap)r9[sPISTN]', supports: 'persisted round-trip'],
    [region: 'executor.runtime-data', contains: 'Boolean doit=false,Boolean shorten=true,Boolean inMem=false', supports: 'persisted round-trip'],
    [region: 'executor.runtime-data', contains: 'if(piston==null)piston=recreatePiston(shorten,inMem)', supports: 'persisted round-trip'],
    [region: 'executor.runtime-data', contains: 'subscribeAll(r9,doit,inMem)', supports: 'persisted round-trip'],
    [region: 'executor.recreate-piston', contains: 'clearMsetIds(piston)', supports: '$ consumed replaced-on-load'],
    [region: 'executor.recreate-piston', contains: 'msetIds(shorten,inMem,piston)', supports: '$ persisted round-trip'],
    [region: 'editor.load-piston', contains: '$scope.piston = response.data.piston;', supports: 'persisted round-trip'],
    [region: 'executor.clear-ids', contains: 'node[sDLR]=null', supports: '$ consumed replaced-on-load'],
    [region: 'executor.set-ids', contains: 'ListCmd', supports: '$ numbered on statements, conditions, groups, events and restrictions'],
    [region: 'executor.set-ids', contains: 'for(Map elseIf in liMs(node,sEI)){', supports: '$ numbered on else-ifs'],
    [region: 'executor.set-ids', contains: 'for(Map _case in liMs(node,sCS)){', supports: '$ numbered on cases'],
    [region: 'executor.set-ids', contains: 'for(Map task in liMs(node,sK)){', supports: '$ numbered on tasks'],
    [region: 'executor.subscribe-all', contains: 'cndtn[sCT]=(String)cmpTyp.take(i1)', supports: 'condition ct replaced-on-load'],
    [region: 'executor.subscribe-all', contains: 'if(sMt(cndtn)==sEVENT){ cndtn[sCT]=sT; ct=sT }', supports: 'event ct read when present'],
    [region: 'executor.subscribe-all', contains: 'if(sMt(cndtn)==sSWITCH){ cndtn[sCT]=sC; ct=sC }', supports: 'switch ct'],
    [region: 'executor.subscribe-all', contains: 'if(node.containsKey(sCT)){ node.remove(sCT) }', supports: 'switch ct replaced-on-load'],
    [region: 'executor.subscribe-all', contains: 'if(cndtn.containsKey(sS)) cndtn.remove(sS)', supports: 's replaced-on-load'],
    [region: 'executor.subscribe-all', contains: 'cndtn[sS]= t1!=never && (ct==sT || t1==always || !hasTriggers)', supports: 's persisted round-trip'],
    [region: 'editor.update-condition', contains: 'var elseIf = {o: \'and\', n: false, c: [], s: []};', supports: 'substructures.elseif persisted'],
    [region: 'executor.statement-dispatch', contains: 'perform=evaluateConditions(r9,elseIf,sC,async)', supports: 'substructures.elseif consumed'],
    [region: 'executor.evaluate-conditions', contains: 'String grouping= collC ? sMs(cndtns,sO):sMs(cndtns,sROP)', supports: 'elseif and group o consumed'],
    [region: 'executor.evaluate-conditions', contains: 'Boolean not= collC ? !!cndtns[sN]:!!cndtns[sRN]', supports: 'elseif and group n consumed'],
    [region: 'executor.evaluate-conditions', contains: 'Boolean isFlwby= grouping==sFLWBY', supports: 'lists condition-list ownerOneOf'],
    [region: 'editor.edit-condition', contains: '(list[0] != condition)', supports: 'followed-by-first-step has no wd or wt'],
    [region: 'editor.edit-condition-group', contains: '(parent[0] != group)', supports: 'followed-by-first-step has no wd or wt'],
    [region: 'editor.update-condition', pattern: 'if \\(\\$scope\\.designer\\.followedBy\\) \\{\\s*condition\\.wd = ', supports: 'condition wd and wt persistedWhen'],
    [region: 'editor.update-condition-group', pattern: 'if \\(\\$scope\\.designer\\.followedBy\\) \\{\\s*group\\.wd = ', supports: 'group wd and wt persistedWhen'],
    [region: 'editor.update-condition', excludes: 'delete', supports: 'wd and wt outsideWhen retained-unconsumed'],
    [region: 'editor.update-condition-group', excludes: 'delete', supports: 'wd and wt outsideWhen retained-unconsumed'],
    [region: 'executor.evaluate-conditions', pattern: 'if\\(ladderIndex\\)\\{\\s*Map tv=mevaluateOperand\\(r9,mMs\\(cndtn,sWD\\)\\)', supports: 'wd consumedWhen'],
    [region: 'executor.evaluate-conditions', contains: 'String wt=sMs(cndtn,sWT)', supports: 'wt consumedWhen'],
    [region: 'editor.edit-condition', contains: 'condition.sm = \'auto\';', supports: 'condition and group sm persisted always'],
    [region: 'editor.edit-condition', contains: 'condition.d = [];', supports: 'exclusion d editor-seed-only'],
    [region: 'editor.update-condition', contains: 'var condition = $scope.designer.$new ? {t: $scope.designer.type} : $scope.designer.$condition;', supports: 'exclusion d editor-seed-only'],
    [region: 'editor.edit-event', contains: 'event.t = \'event\';', supports: 'event t values'],
    [region: 'editor.edit-event', contains: 'event.sm = \'auto\';', supports: 'event sm persisted always'],
    [region: 'editor.update-event', contains: 'if (event.t) {', supports: 'event t persisted always'],
    [region: 'editor.edit-case', contains: '_case.ro2 = {};', supports: 'case ro2 persisted always'],
    [region: 'editor.update-case', contains: '_case.ro2 = $scope.designer.operand2.data;', supports: 'case ro2 persisted always'],
    [region: 'executor.statement-dispatch', contains: 'Boolean isR=sMt(_case)==sR', supports: 'case ro2 consumedWhen'],
    [region: 'executor.statement-dispatch', contains: 'Map ro2=isR ? [(sOPERAND): mMs(_case,sRO2)', supports: 'case ro2 consumedWhen'],
    [region: 'editor.edit-statement', contains: 'if (designer.$new) designer.operand2.data = {t: \'c\', c: d};', supports: 'every lo2 persisted always'],
    [region: 'editor.edit-statement', contains: 'if (designer.$new) designer.operand3.data = {t: \'c\', c: 0, vt: \'m\'};', supports: 'every lo3 persisted always'],
    [region: 'executor.schedule-timer', pattern: 'if\\(delta==lZ\\)\\{[\\s\\S]*?dtime= evalRO1\\(r9,tlo2,rightNow,tlo3,false\\)', supports: 'every lo2 and lo3 consumedWhen'],
    [region: 'executor.clean-code', contains: 'if(sMvt(mMs(item,sLO)) in [sMS,sS,sM,sH]){ item.remove(sLO2); item.remove(sLO3) }', supports: 'every lo2 and lo3 consumedWhen'],
    [constant: 'sD', value: 'd', supports: 'every consumedWhen units'],
    [constant: 'sW', value: 'w', supports: 'every consumedWhen units'],
    [constant: 'sN', value: 'n', supports: 'every consumedWhen units'],
    [constant: 'sY', value: 'y', supports: 'every consumedWhen units'],
    [constant: 'sMS', value: 'ms', supports: 'every consumedWhen units'],
    [constant: 'sM', value: 'm', supports: 'every consumedWhen units'],
    [constant: 'sH', value: 'h', supports: 'every consumedWhen units'],
    [region: 'executor.execute-task', contains: 'List<String> mds=(List<String>)task[sM]', supports: 'task m kind scalar-list'],
    [region: 'editor.edit-task', contains: 'task.m = \'\';', supports: 'task m persisted user-optional'],
    [region: 'editor.update-task', contains: 'task.m = $scope.designer.mode;', supports: 'task m persisted user-optional'],
    [region: 'editor.update-task', contains: 'task.cm = $scope.designer.custom;', supports: 'task cm persisted unless-empty'],
    [region: 'editor.edit-task', contains: '$scope.designer.custom = !!task.cm;', supports: 'task cm is a boolean'],
    [constantValueAbsent: 'cm', supports: 'task cm not-cited: the executor names no cm key'],
    [region: 'editor.update-statement', contains: 'statement.n = false;', supports: 'on n persisted never'],
    [region: 'editor.update-statement', contains: 'delete statement.sm;', supports: 'statement sm persisted user-optional'],
    [region: 'editor.edit-statement', contains: 'statement.o = \'and\'; //operator', supports: 'if, while and repeat o persisted always'],
    [region: 'editor.edit-statement', contains: 'statement.d = []; //devices', supports: 'action d persisted always'],
    [region: 'editor.edit-condition', contains: 'time: {data: condition.to ? $scope.copy(condition.to) : {t:\'c\', c: 0}, dataType: \'duration\'},', supports: 'condition to persisted always']
  ]
]
