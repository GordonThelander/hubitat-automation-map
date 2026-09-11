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
// Second and third increments add the virtual operand (t: 'v': hub/location-level readings such as
// mode, power source and HSM status) and the variable operand (t: 'x': a Hub, global, superglobal or
// piston-local variable reference). Not yet covered, deliberately: p, d, s, e, u, the three
// event-match kinds, and the empty (nothing-selected) kind. Each needs its own reviewed slice the
// same way these three do.
//
// A canonical-persistence finding carried over from the statement work: cleanCode's exclusivity and
// default-stripping rules run only during recreatePiston (executor.recreate-piston, called on load),
// not during the editor's own save (compilePiston). An edit-save capture can therefore still carry a
// field a later canonical reload strips - observed directly: l3-02-followed-by.edit-save keeps a
// stray c on a v-type operand that l3-01-conditional.edit-round-trip and .first-save do not. Treat
// exclusivity claims as proven for the canonical (round-trip) shape, and editor-save-only otherwise,
// the same distinction the statement manifest already uses.
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

  kinds: ['constant', 'virtual', 'variable', 'expression'],

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
    ],

    'wc.operand.v': [
      family: 'virtual',
      discriminator: [key: 't', value: 'v'],
      keys: [
        'vt': [kind: 'scalar', persisted: 'always', consumed: 'read',
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               notes: 'value-type discriminator, closed vocabulary not yet reconciled'],
        'v':  [kind: 'scalar', persisted: 'always', consumed: 'read', exclusive: true,
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               values: ['mode', 'time', 'date', 'dtime', 'pwrSrc', 'hsmSts', 'hsmAlrt', 'hsmSArm', 'hsmRule', 'hsmRules',
                        'pstnRsm', 'cloudBackup', 'lowMemory', 'manualReboot', 'update', 'systemStart', 'severeLoad',
                        'zigbeeOff', 'zigbeeOn', 'zwaveCrashed', 'sunriseTime', 'sunsetTime', 'tile', 'ifttt', 'email', 'routine'],
               notes: 'the closed case list of executor.evaluate-operand, region-hashed but not yet reconciled key by key against a source string dump the way construct catalogues are'],
        'f':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.f, inMem-guarded, canonical-copy status unproven'],
        'g':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.g, inMem-guarded, canonical-copy status unproven'],
        'd':  [kind: 'device-list', persisted: 'never', consumed: 'not-cited',
               notes: 'editor-authored-only: cleanCode strips d unconditionally for t in ListC2 (which includes v) during recreatePiston; observed present on l3-01-conditional.first-save and absent on its edit-round-trip']
      ]
    ],

    'wc.operand.x': [
      family: 'variable',
      discriminator: [key: 't', value: 'x'],
      keys: [
        'vt': [kind: 'scalar', persisted: 'always', consumed: 'read',
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               notes: 'value-type discriminator; when device, x holds a list of variable names instead of one, per executor.evaluate-operand'],
        'x':  [kind: 'scalar', persisted: 'always', consumed: 'read', exclusive: true,
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               notes: 'the referenced variable name, opaque by design; a leading @ names a global variable and @@ a superglobal, per the existing webCoRE variable investigation in Supporting Docs, confirmed again here at executor.evaluate-operand: operX.startsWith(sAT) / sAT2'],
        'xi': [kind: 'scalar', persisted: 'user-optional', consumed: 'read',
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               notes: 'an optional index into a list-valued variable; consumedWhen the variable holds a list is not yet proven'],
        'f':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.f, inMem-guarded, canonical-copy status unproven'],
        'g':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'stripped in memory only when vt is device and g is avg/any (ty==sX && vt!=sDEV branch is the one that applies here since vt is otherwise not device in every fixture occurrence seen); canonical-copy status unproven']
      ]
    ],

    'wc.operand.e': [
      family: 'expression',
      discriminator: [key: 't', value: 'e'],
      keys: [
        'vt':  [kind: 'scalar', persisted: 'always', consumed: 'not-cited',
                writtenBy: ['editor.edit-statement'],
                notes: 'evaluateOperand evaluates exp directly for this kind and does not read vt; value-type discriminator elsewhere, purpose here unproven'],
        'exp': [kind: 'expression', persisted: 'always', consumed: 'read', exclusive: true,
                readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
                notes: 'the only field evaluateOperand reads for this kind: mv=movt+evaluateExpression(r9,mMs(operand,sEXP)) - exp survives only for t in [e, c] (ListEC), same rule as operand.c.exp'],
        'e':   [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
                writtenBy: ['editor.edit-statement'],
                notes: 'saved alongside t: e but not read by evaluateOperand in the reviewed region; purpose unproven, possibly an editor-only display flag'],
        'f':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.f, inMem-guarded, canonical-copy status unproven'],
        'g':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.g, inMem-guarded, canonical-copy status unproven']
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
    [region: 'executor.clean-code', contains: 'if(g in ListAVANY) item.remove(sG)', supports: 'g defaulting to avg/any is inMem-guarded, not yet proven for the saved copy'],
    [region: 'executor.clean-code', contains: 'ListC2=[      sV,sS,sX,sC,sE,sU]', supports: 'operand.v.d is stripped because v is in ListC2'],
    [region: 'executor.clean-code', contains: 'if(ty in ListC2 && item[sD] instanceof List) item.remove(sD)', supports: 'operand.v.d is unconditionally stripped, not inMem-guarded, but only on the path through recreatePiston/cleanCode'],
    [region: 'executor.recreate-piston', contains: 'msetIds(shorten,inMem,piston)', supports: 'cleanCode only runs reached from msetIds during recreatePiston (load), not from the editor compilePiston save path'],
    [region: 'executor.evaluate-operand', contains: 'if(operX.startsWith(sAT2)){', supports: 'operand.x.x: a leading @@ marks a superglobal variable name'],
    [region: 'executor.evaluate-operand', contains: 'if(operX && operX.startsWith(sAT)){', supports: 'operand.x.x: a leading @ marks a global variable name'],
    [region: 'executor.evaluate-operand', contains: 'mv=movt+evaluateExpression(r9,mMs(operand,sEXP))', supports: 'operand.e evaluates only its exp; e and vt are not read here']
  ],

  // Open questions this increment does not resolve. Recorded so the next increment starts from them
  // rather than rediscovering them.
  openQuestions: [
    'the closed vocabulary of vt (value-type) values is not yet reconciled against the executor',
    'whether f and g are ever absent on the saved IDE copy, or only stripped in memory, is unverified - needs the same IDE round-trip evidence the statement manifest built for statement keys',
    'expression item (exp.i[]) shape is undefined here: item kind, operator vocabulary, and nesting rules all remain open',
    'the other six operand constructs (p, d, s, u, the three event-match kinds, empty) have no manifest entry yet - p, d and s in particular have zero occurrences anywhere in the current fixture corpus (every saved device list is empty, deliberately), the same gap blocking the L4.5 action-target claim; the new captures already requested (a multi-task action, a static device target) would also supply this evidence',
    'operand.e.e has no proven purpose; not read by the one evaluateOperand region reviewed here',
    'the v value vocabulary is transcribed from the evaluateOperand switch cases but not yet cross-checked against a full source string dump the way the statement construct catalogue was',
    'whether cleanCode ever runs on a path other than recreatePiston (for example inside compilePiston itself) is asserted from call-site inspection, not yet proven exhaustively'
  ]
]
