// Structural (L3) evidence manifest for webCoRE operands. First increment: the constant operand
// (t: 'c') only, the most common of the twelve registered operand constructs and the one every other
// operand kind is validated against for exclusivity (its own keys must never appear on another kind).
//
// Same authority rule as the statement manifest: the pinned executor and editor serializer govern.
// A statement family reaches L3 only when the pinned serializer shape, the pinned runtime consumer
// and a sanitised editor-saved occurrence agree. This manifest does not reuse fixtures across a
// save/reload lineage claim by itself; that gate is built in the walker and fixture-gate increment
// that follows this one.
//
// Not yet covered by this manifest, deliberately: the other seven core operand kinds (p physical
// device, d device list, v virtual device/event, s preset, x variable, e expression, u? unresolved -
// confirm), the three event-match operand kinds, and the empty (nothing-selected) kind. Each needs
// its own reviewed slice the same way this one does.
[
  provenance: [
    repo: 'https://github.com/imnotbob/webCoRE.git',
    branch: 'hubitat-patches',
    commit: '0a37eee2537accd706aaaeeed5a7b4bb0c82646e',
    executorPath: 'smartapps/ady624/webcore-piston.src/webcore-piston.groovy',
    editorPath: 'dashboard/js/modules/piston.module.js'
  ],

  // The closed discriminator set cleanCode validates operands against (ListAL, line ~1524). Every
  // operand this manifest, present or future, must discriminate on t within this set, plus the
  // event-match and empty forms the registry already carries separately.
  discriminatorValues: ['p', 'd', 'v', 's', 'x', 'c', 'e', 'u'],

  kinds: ['constant'],

  // persisted, unless-empty, user-optional etc. carry the same meaning as the statement manifest.
  // exclusive: this key is stripped from every operand whose t is not the owning kind
  // (executor.clean-code, unconditional, not inMem-guarded, so it holds for the saved IDE copy too):
  //   if(ty!=sC && item[sC]!=null) item.remove(sC)
  // and the symmetric rule holds for x, e, v, s, u and (for p only) a - each key belongs to exactly
  // one operand kind at the saved-shape level.
  operands: [
    'wc.operand.c': [
      family: 'constant',
      discriminator: [key: 't', value: 'c'],
      keys: [
        'vt': [kind: 'scalar', persisted: 'always', consumed: 'read',
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               // Value-type discriminator. The registered catalogue of vt values is not yet
               // reconciled here; treating any string as valid pending that cross-check.
               notes: 'value-type discriminator, closed vocabulary not yet reconciled'],
        'c':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'read', exclusive: true,
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               notes: 'the literal value itself; never read into any coverage result, opaque by design'],
        'f':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'format flag; unless-empty because cleanCode removes a saved f of l (the default) in memory - executor.clean-code: if(sMs(item,sF)==sL) item.remove(sF), inMem-guarded so this is about the runtime copy, not yet proven for the saved IDE copy'],
        'g':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'grouping function; present on multi-value operands, stripped in memory when avg or any and the type is in ListC1 - inMem-guarded, not yet proven for the saved copy'],
        'exp': [kind: 'expression', persisted: 'always', consumed: 'read', exclusive: true,
                readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
                notes: 'the expression container; exp survives only for t in [e, c] (ListEC) - executor.clean-code: if(!(ty in ListEC) && item[sEXP]) item.remove(sEXP)']
      ]
    ]
  ],

  // The expression container shape, shared by every operand kind that carries one.
  expression: [
    't':   [kind: 'scalar', persisted: 'always', consumed: 'not-cited', values: ['expression']],
    'i':   [kind: 'expression-item-list', persisted: 'always', consumed: 'read',
            notes: 'ordered list of expression items; order and item content not yet proven'],
    'str': [kind: 'scalar', persisted: 'always', consumed: 'not-cited',
            notes: 'a rendered form of the expression; never read into any coverage result'],
    'ok':  [kind: 'scalar', persisted: 'always', consumed: 'not-cited', values: [true, false]]
  ],

  // Literal source text each claim above rests on, checked against the pinned checkout the same way
  // the statement manifest's sourceAssertions are.
  sourceAssertions: [
    [region: 'executor.clean-code', contains: 'ListAL=[sP,sD,sV,sS,sX,sC,sE,sU]', supports: 'discriminatorValues is the closed operand-kind set'],
    [region: 'executor.clean-code', contains: 'if(ty!=sC && item[sC]!=null) item.remove(sC)', supports: 'operand.c.c is exclusive to t: c, unconditional, holds on the saved copy'],
    [region: 'executor.clean-code', contains: 'ListEC=[sE,sC]', supports: 'exp is exclusive to t in [e, c]'],
    [region: 'executor.clean-code', contains: 'if(!(ty in ListEC) && item[sEXP]) item.remove(sEXP)', supports: 'operand.c.exp is exclusive to t in [e, c], unconditional, holds on the saved copy'],
    [region: 'executor.clean-code', contains: 'if(sMs(item,sF)==sL) item.remove(sF)', supports: 'f defaulting to l is inMem-guarded, not yet proven for the saved copy'],
    [region: 'executor.clean-code', contains: 'if(g in ListAVANY) item.remove(sG)', supports: 'g defaulting to avg/any is inMem-guarded, not yet proven for the saved copy']
  ],

  // Open questions this increment does not resolve. Recorded so the next increment starts from them
  // rather than rediscovering them.
  openQuestions: [
    'the closed vocabulary of vt (value-type) values is not yet reconciled against the executor',
    'whether f and g are ever absent on the saved IDE copy, or only stripped in memory, is unverified - needs the same IDE round-trip evidence the statement manifest built for statement keys',
    'expression item (exp.i[]) shape is undefined here: item kind, operator vocabulary, and nesting rules all remain open',
    'the other eleven operand constructs (p, d, v, s, x, e, u, the three event-match kinds, empty) have no manifest entry yet'
  ]
]
