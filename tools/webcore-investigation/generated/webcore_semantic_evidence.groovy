// GENERATED from tools/webcore-investigation/evidence/semantic-l4.groovy
// by tools/webcore-investigation/generate-semantic-evidence.groovy. Do not hand-edit.
// Promotion outcome and closed gap reasons only. The manifest keeps the evidence.
Map webcoreSemanticEvidence() {
    return [
        claims: [
            'statement.if.branch-order.v1': true,
            'condition.list.negation.v1': true,
            'condition.list.operator-or.v1': true,
            'condition.followed-by.opaque-group.v1': true,
            'statement.do.sequential-block.v1': true,
            'statement.envelope.default.v1': true
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
}
