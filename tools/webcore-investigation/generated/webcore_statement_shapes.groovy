// GENERATED from tools/webcore-investigation/evidence/statement-l3.groovy
// by tools/webcore-investigation/generate-statement-shapes.groovy. Do not hand-edit.
// Shape and predicate data only. The reviewed manifest keeps the source evidence.
Map webcoreStatementShapes() {
    return [
        contexts: ['condition-list-member', 'followed-by-first-step', 'followed-by-later-step'],
        lists: [
            'condition-list': ['element': 'condition', 'ownerKey': 'o', 'ownerOneOf': ['followed by'], 'first': 'followed-by-first-step', 'rest': 'followed-by-later-step', 'otherwise': 'condition-list-member'],
            'event-list': ['element': 'event'],
            'elseif-list': ['element': 'elseif'],
            'case-list': ['element': 'case'],
            'task-list': ['element': 'task']
        ],
        common: [
            't': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
            '$': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load'],
            'a': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read', 'values': ['0', '1']],
            'tep': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read', 'values': ['c', 'p', 'b']],
            'tsp': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read', 'values': ['a']],
            'tcp': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited', 'values': ['c', 'p', 'b']],
            'r': ['kind': 'restriction-list', 'persisted': 'always', 'consumed': 'read'],
            'rop': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
            'rn': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read', 'values': [true]],
            'di': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited', 'values': [true]],
            'z': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited'],
            'sm': ['kind': 'scalar', 'persisted': 'user-optional', 'consumed': 'not-cited']
        ],
        statements: [
            'wc.statement.action': [keys: [
                'd': ['kind': 'device-list', 'persisted': 'always', 'consumed': 'read'],
                'k': ['kind': 'task-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.if': [keys: [
                'o': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
                'n': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read', 'values': [true]],
                'c': ['kind': 'condition-list', 'persisted': 'always', 'consumed': 'read'],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read'],
                'ei': ['kind': 'elseif-list', 'persisted': 'always', 'consumed': 'read'],
                'e': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.while': [keys: [
                'o': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
                'n': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read', 'values': [true]],
                'c': ['kind': 'condition-list', 'persisted': 'always', 'consumed': 'read'],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.repeat': [keys: [
                'o': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
                'n': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read', 'values': [true]],
                'c': ['kind': 'condition-list', 'persisted': 'always', 'consumed': 'read'],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.every': [keys: [
                'lo': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                'lo2': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read', 'consumedWhen': ['key': 'lo.vt', 'oneOf': ['d', 'w', 'n', 'y']]],
                'lo3': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read', 'consumedWhen': ['key': 'lo.vt', 'oneOf': ['d', 'w', 'n', 'y']]],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.on': [keys: [
                'c': ['kind': 'event-list', 'persisted': 'always', 'consumed': 'read'],
                'o': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'not-cited', 'values': ['or']],
                'n': ['kind': 'scalar', 'persisted': 'never', 'consumed': 'not-cited'],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.each': [keys: [
                'x': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read'],
                'lo': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.for': [keys: [
                'x': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read'],
                'lo': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                'lo2': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                'lo3': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.switch': [keys: [
                'lo': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                'cs': ['kind': 'case-list', 'persisted': 'always', 'consumed': 'read'],
                'e': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read'],
                'ctp': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read', 'values': ['i', 'e']],
                'ct': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load', 'values': ['c']],
                's': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load', 'values': [true]]
            ]],
            'wc.statement.do': [keys: [
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.break': [keys: [:]],
            'wc.statement.exit': [keys: [
                'lo': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read']
            ]]
        ],
        substructures: [
            'elseif': [keys: [
                '$': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load'],
                'o': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
                'n': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read', 'values': [true]],
                'c': ['kind': 'condition-list', 'persisted': 'always', 'consumed': 'read'],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'case': [keys: [
                '$': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load'],
                't': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read', 'values': ['s', 'r']],
                'ro': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                'ro2': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read', 'consumedWhen': ['key': 't', 'oneOf': ['r']]],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read'],
                'z': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited']
            ]],
            'event': [keys: [
                '$': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load'],
                't': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read', 'values': ['event']],
                'lo': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                'sm': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'not-cited'],
                'z': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited'],
                'ct': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'read', 'values': ['t']],
                's': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load', 'values': [true]]
            ]],
            'task': [keys: [
                '$': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load'],
                'c': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
                'cm': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited', 'values': [true]],
                'p': ['kind': 'operand-list', 'persisted': 'always', 'consumed': 'read'],
                'a': ['kind': 'scalar', 'persisted': 'user-optional', 'consumed': 'not-cited'],
                'm': ['kind': 'scalar-list', 'persisted': 'user-optional', 'consumed': 'read'],
                'z': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited']
            ]],
            'condition': [
                discriminator: ['key': 't', 'kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
                variants: [
                    'condition': [
                        '$': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load'],
                        'lo': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                        'co': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
                        'ro': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                        'ro2': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                        'to': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                        'to2': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                        'ts': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read'],
                        'fs': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read'],
                        'sm': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'not-cited'],
                        'z': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited'],
                        'ct': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load', 'values': ['t', 'c']],
                        's': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load', 'values': [true]],
                        'wd': ['kind': 'operand', 'persisted': 'when', 'persistedWhen': ['context': ['followed-by-later-step']], 'outsideWhen': 'retained-unconsumed', 'consumed': 'read', 'consumedWhen': ['context': ['followed-by-later-step']]],
                        'wt': ['kind': 'scalar', 'persisted': 'when', 'persistedWhen': ['context': ['followed-by-later-step']], 'outsideWhen': 'retained-unconsumed', 'consumed': 'read', 'consumedWhen': ['context': ['followed-by-first-step', 'followed-by-later-step']], 'values': ['l', 's', 'n']]
                    ],
                    'group': [
                        '$': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load'],
                        'c': ['kind': 'condition-list', 'persisted': 'always', 'consumed': 'read'],
                        'o': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
                        'n': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read', 'values': [true]],
                        'ts': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read'],
                        'fs': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read'],
                        'sm': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'not-cited'],
                        'z': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited'],
                        'wd': ['kind': 'operand', 'persisted': 'when', 'persistedWhen': ['context': ['followed-by-later-step']], 'outsideWhen': 'retained-unconsumed', 'consumed': 'read', 'consumedWhen': ['context': ['followed-by-later-step']]],
                        'wt': ['kind': 'scalar', 'persisted': 'when', 'persistedWhen': ['context': ['followed-by-later-step']], 'outsideWhen': 'retained-unconsumed', 'consumed': 'read', 'consumedWhen': ['context': ['followed-by-first-step', 'followed-by-later-step']], 'values': ['l', 's', 'n']]
                    ]
                ]
            ]
        ]
    ]
}
