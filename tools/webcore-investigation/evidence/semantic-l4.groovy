// Semantic (L4) evidence manifest, first increment.
//
// A claim states what a saved construct means at runtime, never a live outcome. It is promoted only
// when L4Promotion finds every contract item: the structure it rests on is L3, a documentation
// disposition, hashed pinned-source regions equal to the registry, a canonical fixture with its
// save in the committed lineage, a hand-authored trace that reaches it, a traced edge occurrence and
// a named nearest misreading. Source drift in a cited region withdraws only the claims citing it.
// Everything a claim does not cover is an occurrence-scoped gap from the closed list below.
[
  provenance: [
    commit: '0a37eee2537accd706aaaeeed5a7b4bb0c82646e',
    registry: 'tools/webcore-investigation/generated/webcore_construct_registry.groovy',
    structuralEvidence: 'tools/webcore-investigation/evidence/statement-l3.groovy'
  ],
  contract: ['structural-l3', 'documentation-disposition', 'hashed-source-regions', 'canonical-fixture-lineage',
             'hand-authored-trace', 'traced-edge-occurrence', 'named-negative'],
  traceDirectory: 'tests/fixtures/webcore-l4/traces',

  claims: [
    [id: 'statement.if.branch-order.v1', structural: ['wc.statement.if'],
     meaning: 'An if takes then when its condition list holds, otherwise tests each else-if in saved order, and takes else only when none matched.',
     docs: [[page: 'https://wiki.webcore.co/Piston', disposition: 'Documents the If Block as the statement for checking conditions; the else-if order is undocumented, source only']],
     sources: [[region: 'executor.statement-dispatch', sha256: 'a9eb246dda94cdd5d03d246ba51cd89d5c7e628f57b9d9fdaac88190c95cd643']],
     fixtures: ['l3-01-conditional.edit-round-trip'],
     edges: [[fixture: 'l3-01-conditional.edit-round-trip', path: '$.s[1]', exercises: 'an if with no else-if and no else']],
     negative: 'else-if branches are unordered or evaluated together'],

    [id: 'condition.list.negation.v1', structural: ['wc.statement.if'],
     meaning: 'A condition list or group with n true inverts its combined result; without n it does not.',
     docs: [[page: 'https://wiki.webcore.co/Piston', disposition: 'Documents condition groups; negation is undocumented, source only']],
     sources: [[region: 'executor.evaluate-conditions', sha256: '6972420b01a18e8ee17a2c727fedf6852bc3ae56ff66879d4accc932a6b280ca']],
     fixtures: ['l3-01-conditional.edit-round-trip'],
     edges: [[fixture: 'l3-02-followed-by.edit-round-trip', path: '$.s[1]', exercises: 'a group without negation']],
     negative: 'a negated condition list is read as not negated'],

    [id: 'condition.list.operator-or.v1', structural: ['wc.statement.if'],
     meaning: 'A condition list or group whose operator is or holds when any of its ordered children holds.',
     docs: [[page: 'https://wiki.webcore.co/Piston', disposition: 'Documents a condition group as conditions joined by a logical operator such as and, or, xor']],
     sources: [[region: 'executor.evaluate-conditions', sha256: '6972420b01a18e8ee17a2c727fedf6852bc3ae56ff66879d4accc932a6b280ca']],
     fixtures: ['l3-01-conditional.edit-round-trip'],
     edges: [[fixture: 'l3-01-conditional.edit-round-trip', path: '$.s[1]', exercises: 'operators the fixtures do not prove']],
     negative: 'an unproven operator such as and is read as or'],

    [id: 'condition.followed-by.opaque-group.v1', structural: ['wc.statement.if'],
     meaning: 'A group whose operator is followed by is a timed sequence of events, kept opaque, never a boolean and.',
     docs: [[page: 'https://community.webcore.co/t/if-this-then-wait-then-if/1393', disposition: 'Community guidance uses followed by for a sequence of events over time'],
            [page: 'https://community.webcore.co/t/sequence-of-events/5037', disposition: 'Community guidance uses followed by to detect an ordered sequence of events']],
     sources: [[region: 'executor.evaluate-conditions', sha256: '6972420b01a18e8ee17a2c727fedf6852bc3ae56ff66879d4accc932a6b280ca']],
     fixtures: ['l3-02-followed-by.edit-round-trip'],
     edges: [[fixture: 'l3-02-followed-by.edit-round-trip', path: '$.s[1]', exercises: 'an ordinary group beside a followed-by group']],
     negative: 'a followed-by group is read as an ordinary boolean and group'],

    [id: 'statement.do.sequential-block.v1', structural: ['wc.statement.do'],
     meaning: 'A do executes its saved child statements once, in order, and lowers the statement level while they run.',
     docs: [[page: 'https://wiki.webcore.co/Piston', disposition: 'Documents Do as a construct for grouping other statements together; its statement-level effect is undocumented, source only']],
     sources: [[region: 'executor.statement-dispatch', sha256: 'a9eb246dda94cdd5d03d246ba51cd89d5c7e628f57b9d9fdaac88190c95cd643']],
     fixtures: ['l3-04-loops.edit-round-trip'],
     edges: [[fixture: 'l3-04-loops.edit-round-trip', path: '$.s[0]', exercises: 'a loop containing the do']],
     negative: 'do is read as a loop or as transparent to statement level'],

    [id: 'statement.envelope.default.v1', structural: ['wc.statement.if', 'wc.statement.action'],
     meaning: 'A statement with no restrictions, synchronous a, no task execution or scheduling policy and saved tcp c runs with the default envelope.',
     docs: [[page: 'https://wiki.webcore.co/Execution_Method', disposition: 'Documents synchronous execution as the normal default'],
            [page: 'https://wiki.webcore.co/Task_Cancellation_Policy', disposition: 'Documents the cancellation policy; saved c as the default comes from the pinned editor and executor']],
     sources: [[region: 'executor.clean-code', sha256: '4522bc571384d95ee726e3e8a0b2fcad2a99553a96e866614e50e5515dc250e2']],
     fixtures: ['l3-01-conditional.edit-round-trip'],
     edges: [[fixture: 'l3-08-policies.round-trip', path: '$.s[0]', exercises: 'restrictions and explicit policies'],
             [fixture: 'l3-08-policies.round-trip', path: '$.s[2]', exercises: 'an execution policy with the default tcp']],
     negative: 'a saved absent tcp, which is never cancel, is read as the default']
  ],

  // Named misreadings for the recorded limits. They cap occurrences and are never promoted.
  limits: [
    [gap: 'statement.if.automatic-piston-state-unresolved', negative: 'if has no side effect beyond branch selection', source: 'executor.statement-dispatch'],
    [gap: 'condition.leaf-opaque', negative: 'a saved ct decides a condition leaf role', source: 'executor.evaluate-conditions']
  ],

  gaps: [
    'statement.envelope.restrictions-present': 'Restrictions gate this statement and their meaning is not yet proven',
    'statement.envelope.async': 'The execution method is not the proven synchronous default',
    'statement.envelope.tep-present': 'A task execution policy is set and its meaning is not yet proven',
    'statement.envelope.tsp-present': 'A task scheduling policy is set and its meaning is not yet proven',
    'statement.envelope.tcp-non-default': 'The task cancellation policy is not the proven default',
    'statement.if.automatic-piston-state-unresolved': 'A top-level if may set the automatic piston state, which is not yet explained',
    'statement.if.fast-forward-resumption-unresolved': 'Resumed execution may enter a branch regardless of the condition, which is not yet explained',
    'statement.action.not-in-increment': 'The meaning of this statement type is not yet proven',
    'statement.while.not-in-increment': 'The meaning of this statement type is not yet proven',
    'statement.every.not-in-increment': 'The meaning of this statement type is not yet proven',
    'statement.repeat.not-in-increment': 'The meaning of this statement type is not yet proven',
    'statement.on.not-in-increment': 'The meaning of this statement type is not yet proven',
    'statement.each.not-in-increment': 'The meaning of this statement type is not yet proven',
    'statement.for.not-in-increment': 'The meaning of this statement type is not yet proven',
    'statement.switch.not-in-increment': 'The meaning of this statement type is not yet proven',
    'statement.break.not-in-increment': 'The meaning of this statement type is not yet proven',
    'statement.exit.not-in-increment': 'The meaning of this statement type is not yet proven',
    'statement.unrecognised': 'The statement type is not recognised',
    'condition.leaf-opaque': 'A condition comparison is shown as opaque until its meaning is proven',
    'condition.operator-unproven': 'This condition operator is not yet proven',
    'condition.group-depth-unproven': 'A group inside a group is not yet proven',
    'condition.followed-by-timing-unproven': 'The timing of a followed-by sequence is not yet explained',
    'claim.statement.if.branch-order.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
    'claim.condition.list.negation.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
    'claim.condition.list.operator-or.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
    'claim.condition.followed-by.opaque-group.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
    'claim.statement.do.sequential-block.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
    'claim.statement.envelope.default.v1.not-promoted': 'This claim lost its evidence, for example after source drift'
  ]
]
