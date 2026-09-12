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
// piston-local variable reference). A fourth adds the expression operand (t: 'e'). A fifth adds the
// physical-device operand (t: 'p': a device attribute read) and the preset operand (t: 's': a named
// time-of-day value). A sixth adds one of the three event-match kinds (an operand inside an on
// statement's event matcher, saved like an ordinary operand but read by a different, simpler
// consumer): the virtual form only, from occurrences already present in the existing zz-L3-07 events
// fixture, no new capture needed. A seventh increment adds the bare device-list operand (t: 'd': a raw
// device selection, seen here as a Device-typed piston-local variable's initial value) and the
// argument operand (t: 'u': one named entry of a piston's $args, e.g. from an external trigger). An
// eighth adds the remaining two event-match kinds: physical (t: 'p' in event position, matched by
// attribute name and device membership) and variable (t: 'x' in event position, matched by variable
// name). Not yet covered, deliberately: the empty (nothing-selected) kind, which the pinned source
// shows is a real, distinct saved shape (executor.clean-code's ty==sNL branch) but has not yet been
// reproduced from the hosted editor.
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

  kinds: ['constant', 'virtual', 'variable', 'expression', 'physical', 'preset', 'event-match-virtual',
          'device-list', 'argument', 'event-match-physical', 'event-match-variable'],

  // Same lineage and named-test shape the statement manifest uses, reused by the registry
  // generator's operand-level promotion (L3Promotion.derive against a shim pointing at
  // operands below and at each fixture's own 'operands' occurrence-count map).
  captureLineage: ['first-save': 'round-trip', 'edit-save': 'edit-round-trip', 'edit-round-trip': 'chained-round-trip'],
  namedTests: ['operand-l3-manifest': 'tests/webcore-operand-l3-manifest.groovy'],

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
        'c':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'read', exclusiveTo: ['c'],
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               notes: 'the literal value itself; never read into any coverage result, opaque by design'],
        'f':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'format flag; unless-empty because cleanCode removes a saved f of l (the default) in memory - executor.clean-code: if(sMs(item,sF)==sL) item.remove(sF), inMem-guarded so this is about the runtime copy, not yet proven for the saved IDE copy'],
        'g':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'grouping function; present on multi-value operands, stripped in memory when avg or any and the type is in ListC1 - inMem-guarded, not yet proven for the saved copy'],
        'exp': [kind: 'expression', persisted: 'always', consumed: 'read', exclusiveTo: ['c', 'e'],
                readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
                notes: 'the expression container, shared by exactly two kinds; exp survives only for t in [e, c] (ListEC) - executor.clean-code: if(!(ty in ListEC) && item[sEXP]) item.remove(sEXP)']
      ],
      fixtures: ['l3-01-conditional.first-save'],
      tests: ['operand-l3-manifest']
    ],

    'wc.operand.v': [
      family: 'virtual',
      discriminator: [key: 't', value: 'v'],
      keys: [
        'vt': [kind: 'scalar', persisted: 'always', consumed: 'read',
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               notes: 'value-type discriminator, closed vocabulary not yet reconciled'],
        'v':  [kind: 'scalar', persisted: 'always', consumed: 'read', exclusiveTo: ['v'],
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
               notes: 'editor-authored-only, not exclusive to v: cleanCode strips d unconditionally for t in ListC2 (which includes v, s, x, c, e, u) during recreatePiston; observed present on l3-01-conditional.first-save (on both v and c operands) and absent on its edit-round-trip']
      ],
      fixtures: ['l3-01-conditional.first-save'],
      tests: ['operand-l3-manifest']
    ],

    'wc.operand.x': [
      family: 'variable',
      discriminator: [key: 't', value: 'x'],
      keys: [
        'vt': [kind: 'scalar', persisted: 'always', consumed: 'read',
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               notes: 'value-type discriminator; when device, x holds a list of variable names instead of one, per executor.evaluate-operand'],
        'x':  [kind: 'scalar', persisted: 'always', consumed: 'read', exclusiveTo: ['x'],
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
      ],
      fixtures: ['l3-04-loops.first-save'],
      tests: ['operand-l3-manifest']
    ],

    'wc.operand.e': [
      family: 'expression',
      discriminator: [key: 't', value: 'e'],
      keys: [
        'vt':  [kind: 'scalar', persisted: 'always', consumed: 'not-cited',
                writtenBy: ['editor.edit-statement'],
                notes: 'evaluateOperand evaluates exp directly for this kind and does not read vt; value-type discriminator elsewhere, purpose here unproven'],
        'exp': [kind: 'expression', persisted: 'always', consumed: 'read', exclusiveTo: ['c', 'e'],
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
      ],
      fixtures: ['l3-03-switch.first-save'],
      tests: ['operand-l3-manifest']
    ],

    'wc.operand.p': [
      family: 'physical',
      discriminator: [key: 't', value: 'p'],
      keys: [
        'vt': [kind: 'scalar', persisted: 'always', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'value-type discriminator; not read by evaluateOperand for this kind, which reads the attribute catalogue instead'],
        'a':  [kind: 'scalar', persisted: 'always', consumed: 'read', exclusiveTo: ['p'],
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               notes: 'the device attribute name, looked up in the Attributes() catalogue; exclusive to t: p'],
        'd':  [kind: 'device-list', persisted: 'always', consumed: 'read',
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               notes: 'the device list read, expanded through expandDeviceList the same way an action target is; not exclusive to p, since cleanCode only strips d for t in ListC2, which excludes both p and d'],
        'p':  [kind: 'scalar', persisted: 'user-optional', consumed: 'read',
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               notes: 'physical/digital/any read preference, read only when the Attributes() catalogue entry for a names a physical/digital distinction; absent in the one capture reviewed here, so its saved shape when present is unproven'],
        'f':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.f, inMem-guarded, canonical-copy status unproven'],
        'g':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.g, inMem-guarded, canonical-copy status unproven']
      ],
      fixtures: ['l3-11-physical.first-save'],
      tests: ['operand-l3-manifest']
    ],

    'wc.operand.s': [
      family: 'preset',
      discriminator: [key: 't', value: 's'],
      keys: [
        'vt': [kind: 'scalar', persisted: 'always', consumed: 'read',
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               notes: 'value-type discriminator; only time and datetime route through the named-preset switch (sunset/sunrise/midnight/noon), every other vt reads s as a plain passthrough value'],
        's':  [kind: 'scalar', persisted: 'always', consumed: 'read', exclusiveTo: ['s'],
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               values: ['sunset', 'sunrise', 'midnight', 'noon'],
               notes: 'the closed preset name case list of the frozen preset.evaluate.name site, proven here only for sunset; only meaningful when vt is time or datetime, otherwise passed through unvalidated'],
        'f':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.f, inMem-guarded, canonical-copy status unproven'],
        'g':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.g, inMem-guarded, canonical-copy status unproven']
      ],
      fixtures: ['l3-12-preset.first-save'],
      tests: ['operand-l3-manifest']
    ],

    // Not a seventh saved shape: an event-match operand is saved exactly like an ordinary virtual
    // operand (vt, v, f, g), the same keys and the same closed value vocabulary. It is tracked as its
    // own registry identity only because of WHERE it is saved and who reads it - the registry
    // generator's SITE_NORMALIZATION keeps operand.event-match.* distinct from operand.* for exactly
    // this reason. Here that means: same keys block as wc.operand.v, a discriminator that also names
    // the saved parent context so the gate test can tell the two apart, and its own citation.
    'wc.operand.event-match.v': [
      family: 'virtual',
      discriminator: [key: 't', value: 'v', parentContext: 'event'],
      keys: [
        'vt': [kind: 'scalar', persisted: 'always', consumed: 'not-cited',
               readBy: [], writtenBy: ['editor.edit-statement'],
               notes: 'saved like any virtual operand but not read here: the event-match switch on sMt(operand) only reads v'],
        'v':  [kind: 'scalar', persisted: 'always', consumed: 'read', exclusiveTo: ['v'],
               readBy: ['executor.statement-dispatch'], writtenBy: ['editor.edit-statement'],
               values: ['mode', 'time', 'date', 'dtime', 'pwrSrc', 'hsmSts', 'hsmAlrt', 'hsmSArm', 'hsmRule', 'hsmRules',
                        'pstnRsm', 'cloudBackup', 'lowMemory', 'manualReboot', 'update', 'systemStart', 'severeLoad',
                        'zigbeeOff', 'zigbeeOn', 'zwaveCrashed', 'sunriseTime', 'sunsetTime', 'tile', 'ifttt', 'email', 'routine'],
               notes: 'compared for exact string equality against the triggering event name (evntName==sMv(operand)); proven here for mode and powerSource only'],
        'f':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.f, inMem-guarded, canonical-copy status unproven'],
        'g':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.g, inMem-guarded, canonical-copy status unproven']
      ],
      fixtures: ['l3-07-events.first-save'],
      tests: ['operand-l3-manifest']
    ],

    'wc.operand.d': [
      family: 'device-list',
      discriminator: [key: 't', value: 'd'],
      keys: [
        'vt': [kind: 'scalar', persisted: 'always', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'value-type discriminator; evaluateOperand.case-sD hard-codes its result type to sDEV and does not read vt'],
        'd':  [kind: 'device-list', persisted: 'always', consumed: 'read',
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               notes: 'the device list, expanded through expandDeviceList and returned as the operand value; not exclusive to d, since cleanCode only strips d for t in ListC2, which excludes both d and p; observed here as a Device-typed piston-local variable\'s initial value (v[].v), not inside a condition or action target'],
        'f':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.f, inMem-guarded, canonical-copy status unproven'],
        'g':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'not read by evaluateOperand.case-sD (unlike case-sP, this case applies no grouping function to a multi-device result); saved here as avg on a single-device selection, so whether the editor ever offers or persists a different value is unproven']
      ],
      fixtures: ['l3-14-device-var.first-save'],
      tests: ['operand-l3-manifest']
    ],

    'wc.operand.u': [
      family: 'argument',
      discriminator: [key: 't', value: 'u'],
      keys: [
        'vt': [kind: 'scalar', persisted: 'always', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'value-type discriminator; getArgument returns whatever is stored in $args under this name, untyped by vt'],
        'u':  [kind: 'scalar', persisted: 'always', consumed: 'read', exclusiveTo: ['u'],
               readBy: ['executor.evaluate-operand'], writtenBy: ['editor.edit-statement'],
               notes: 'the argument name, looked up by getArgument against the piston\'s $args system variable (getJsonData(gtSysVarVal(r9,sDARGS), name)); exclusive to t: u'],
        'f':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.f, inMem-guarded, canonical-copy status unproven'],
        'g':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.g, inMem-guarded, canonical-copy status unproven']
      ],
      fixtures: ['l3-13-argument.first-save'],
      tests: ['operand-l3-manifest']
    ],

    // The physical event-match: same keys (vt, a, d, p, f, g) as the ordinary physical operand, but
    // read by the on-statement's own event dispatcher, not evaluateOperand - the same distinction
    // event-match.v documents against the ordinary virtual operand above.
    'wc.operand.event-match.p': [
      family: 'physical',
      discriminator: [key: 't', value: 'p', parentContext: 'event'],
      keys: [
        'vt': [kind: 'scalar', persisted: 'always', consumed: 'not-cited',
               readBy: [], writtenBy: ['editor.edit-statement'],
               notes: 'saved like any physical operand but not read here: the event-match switch on sMt(operand) reads only a and d'],
        'a':  [kind: 'scalar', persisted: 'always', consumed: 'read', exclusiveTo: ['p'],
               readBy: ['executor.statement-dispatch'], writtenBy: ['editor.edit-statement'],
               notes: 'compared for exact string equality against the triggering event name (evntName==sMa(operand)); same exclusivity rule as the ordinary physical operand\'s a key'],
        'd':  [kind: 'device-list', persisted: 'always', consumed: 'read',
               readBy: ['executor.statement-dispatch'], writtenBy: ['editor.edit-statement'],
               notes: 'the triggering device must expand into this list (deviceId in expandDeviceList(r9,liMd(operand),true)); not exclusive to p, same as the ordinary physical operand\'s d key'],
        'p':  [kind: 'scalar', persisted: 'user-optional', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'the physical/digital/any read preference; the on-statement event dispatcher does not reference operand[p] at all, unlike evaluateOperand\'s ordinary physical-operand path - purpose in event-match position unproven'],
        'f':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.f, inMem-guarded, canonical-copy status unproven'],
        'g':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.g, inMem-guarded, canonical-copy status unproven']
      ],
      fixtures: ['l3-15-device-trigger.first-save'],
      tests: ['operand-l3-manifest']
    ],

    // The variable event-match: same keys (vt, x, xi, f, g) as the ordinary variable operand, but read
    // by the on-statement's own event dispatcher.
    'wc.operand.event-match.x': [
      family: 'variable',
      discriminator: [key: 't', value: 'x', parentContext: 'event'],
      keys: [
        'vt': [kind: 'scalar', persisted: 'always', consumed: 'not-cited',
               readBy: [], writtenBy: ['editor.edit-statement'],
               notes: 'saved like any variable operand but not read here: the event-match switch on sMt(operand) reads only x'],
        'x':  [kind: 'scalar', persisted: 'always', consumed: 'read', exclusiveTo: ['x'],
               readBy: ['executor.statement-dispatch'], writtenBy: ['editor.edit-statement'],
               notes: 'the referenced variable name; matched by (ce[sVAL]==operX && evntName==sMs(r9,sINSTID)+sDOT+operX) - the event\'s value must equal the variable name and the event name must equal instanceId.variableName; how this reconciles with the sVARIABLE:name event-naming evaluateOperand uses for a leading @@ superglobal (see wc.operand.x.x) is not traced here, open question below'],
        'f':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.f, inMem-guarded, canonical-copy status unproven'],
        'g':  [kind: 'scalar', persisted: 'unless-empty', consumed: 'not-cited',
               writtenBy: ['editor.edit-statement'],
               notes: 'same default-stripping caveat as operand.c.g, inMem-guarded, canonical-copy status unproven']
      ],
      fixtures: ['l3-16-variable-trigger.first-save'],
      tests: ['operand-l3-manifest']
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
    [region: 'executor.evaluate-operand', contains: 'mv=movt+evaluateExpression(r9,mMs(operand,sEXP))', supports: 'operand.e evaluates only its exp; e and vt are not read here'],
    [region: 'executor.clean-code', contains: 'if(ty!=sP && item[sA]!=null) item.remove(sA)', supports: 'operand.p.a is exclusive to t: p, unconditional, holds on the saved copy'],
    [region: 'executor.clean-code', contains: 'if(ty!=sS && item[sS]!=null) item.remove(sS)', supports: 'operand.s.s is exclusive to t: s, unconditional, holds on the saved copy'],
    [region: 'executor.evaluate-operand', contains: 'case sSUNSET: v= getSunsetTime(r9,dayBasis); break', supports: 'operand.s.s: sunset is a member of the closed preset-name case list, proven for this one value'],
    [region: 'executor.statement-dispatch', contains: 'if(evntName==sMv(operand))', supports: 'operand.event-match.v.v: the event-match consumer reads only v, compared by exact string equality against the triggering event name'],
    [region: 'executor.clean-code', contains: 'if(ty!=sU && item[sU]!=null) item.remove(sU)', supports: 'operand.u.u is exclusive to t: u, unconditional, holds on the saved copy'],
    [region: 'executor.evaluate-operand', contains: 'mv=getArgument(r9,sMs(operand,sU))', supports: 'operand.u.u: getArgument is the only site that reads this key, looking it up in the $args system variable'],
    [region: 'executor.evaluate-operand', contains: 'mv=rtnMap(sDEV,deviceIds.unique())', supports: 'operand.d.d: the device list is expanded and returned as a device-type result; vt and g are not referenced in this case'],
    [region: 'executor.clean-code', contains: 'if(ty in ListC2 && item[sD] instanceof List) item.remove(sD)', supports: 'operand.d.d is not exclusive but persists because d, like p, is excluded from ListC2'],
    [region: 'executor.statement-dispatch', contains: 'if(deviceId!=sNL && evntName==sMa(operand) && liMd(operand) && deviceId in expandDeviceList(r9,liMd(operand),true))', supports: 'operand.event-match.p: the event-match consumer reads a and d, requiring the device id to be in the expanded device list and the attribute name to equal the event name'],
    [region: 'executor.clean-code', contains: 'if(ty!=sP && item[sA]!=null) item.remove(sA)', supports: 'operand.event-match.p.a is exclusive to t: p, the same unconditional rule proven for the ordinary physical operand'],
    [region: 'executor.statement-dispatch', contains: 'if(ce[sVAL]==operX && evntName==sMs(r9,sINSTID)+sDOT+operX)', supports: 'operand.event-match.x.x: the event-match consumer reads only x, matched against the triggering event\'s value and a computed instanceId-qualified event name']
  ],

  // Open questions this increment does not resolve. Recorded so the next increment starts from them
  // rather than rediscovering them.
  openQuestions: [
    'the closed vocabulary of vt (value-type) values is not yet reconciled against the executor',
    'whether f and g are ever absent on the saved IDE copy, or only stripped in memory, is unverified - needs the same IDE round-trip evidence the statement manifest built for statement keys',
    'expression item (exp.i[]) shape is undefined here: item kind, operator vocabulary, and nesting rules all remain open',
    'the empty (nothing-selected) operand construct has no manifest entry yet and no fixture evidence; executor.clean-code shows a distinct ty==sNL cleanup branch for it, but a direct, hands-on attempt to reproduce it from the hosted editor (2026-09-12, an unsaved zz-L3-17 exploration, not a committed fixture) did not reach it. Four things were tried against a Set Variable task\'s value parameter, which does expose "Nothing selected" as one of its own type-dropdown options (alongside Physical device(s)/Virtual device/Value/Variable/Expression/Argument): (1) choosing "Nothing selected" fresh, with no prior value, saved as t: c with an empty-string exp, not as typeless; (2) an incomplete physical-device pick (type chosen, device list never actually selected) saved as t: p with d: [] - populated type, empty list, still not typeless; (3) a completed physical-device pick round-tripped normally; (4) reopening that completed pick and re-choosing "Nothing selected" left the saved shape completely unchanged (still t: p with the device attached) - the editor appears to leave the on-screen dropdown able to show "Nothing selected" without it ever taking effect on an already-populated parameter, though whether that is a genuine editor limitation or an artifact of the automation used to drive it is unresolved. The comment on the ty==sNL branch ("task parameters (sP) with \'Nothing selected\'") most plausibly describes a task parameter that was cleared by some other path (an older UI version, direct API edit, or unwinding a different sequence of choices) rather than the ordinary type-dropdown interaction tried here. Next attempt should either look for a parameter position that lets a value be cleared after being set (not just switched to another concrete type), or check the webCoRE wiki/community forum for how other builders have described this state before spending further live hub time guessing at it',
    'operand.event-match.x.x match semantics for a leading @@ superglobal variable are unproven: the on-statement dispatcher compares evntName to instanceId+"."+operX unconditionally, while evaluateOperand routes a superglobal to a differently-named node (sVARIABLE:name); whether a superglobal variable can even fire this event-match branch, or only plain/global variables can, is not traced here',
    'operand.d.g is proven only as avg on a single-device selection; whether the editor offers other grouping values for a Device-typed variable, and whether any of them persist through the same ListAVANY-based stripping other operand kinds show, is unproven',
    'operand.e.e has no proven purpose; not read by the one evaluateOperand region reviewed here',
    'the v value vocabulary is transcribed from the evaluateOperand switch cases but not yet cross-checked against a full source string dump the way the statement construct catalogue was',
    'the s (preset) value vocabulary is proven for sunset only; sunrise, midnight and noon are transcribed from the same switch but not independently captured',
    'operand.p.p (the physical/digital/any read preference) has no captured occurrence; its saved shape when present, and which attributes actually offer the choice, are both unproven',
    'operand.event-match.v.v is proven for mode and powerSource only; the other 24 members of the shared virtual-device value vocabulary are transcribed, not independently exercised in event-match position',
    'whether cleanCode ever runs on a path other than recreatePiston (for example inside compilePiston itself) is asserted from call-site inspection, not yet proven exhaustively'
  ]
]
