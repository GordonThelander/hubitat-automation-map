#!/usr/bin/env python3
"""Ask Gordon before a LOCAL Claude session edits code (Gordon, 2026-10-07).

Code is the cloud session's work; local sessions do hub work. Four times on 6-7 October a local session
wrote code the cloud session was already writing on a branch, and the two collided or duplicated. A rule
in CLAUDE.md did not stop it, so this makes it a question Gordon answers rather than a habit.

A PreToolUse hook on Edit, Write, MultiEdit and NotebookEdit. In the cloud (CLAUDE_CODE_REMOTE=true) it
does nothing. Locally, an edit to a code path asks Gordon first, with the reason; anything else - docs,
Issues, captured fixtures, notes - goes through. Bash is not inspected, so this is a guard against the
habit, not against a determined session.

The same file is in both repositories; each lists its own code paths below.
"""
import json, os, sys

# Paths relative to the repository root. A prefix match, then the exceptions.
CODE = ['engine/lib/', 'engine/test/', 'engine/app/', 'engine/eval/', 'engine/m4/', 'engine/m5/',
        'engine/probe/', 'apps/', 'tests/', 'tools/', '.github/']
NOT_CODE = ['engine/test/fixtures/', 'tests/fixtures/']


def main():
    if os.environ.get('CLAUDE_CODE_REMOTE') == 'true':
        return 0
    try:
        call = json.load(sys.stdin)
    except ValueError:
        return 0
    path = ((call.get('tool_input') or {}).get('file_path') or (call.get('tool_input') or {}).get('notebook_path') or '')
    root = os.environ.get('CLAUDE_PROJECT_DIR') or os.getcwd()
    try:
        rel = os.path.relpath(os.path.abspath(path), os.path.abspath(root)).replace(os.sep, '/')
    except ValueError:
        return 0
    if rel.startswith('..') or any(rel.startswith(p) for p in NOT_CODE) or not any(rel.startswith(p) for p in CODE):
        return 0
    print(json.dumps({'hookSpecificOutput': {
        'hookEventName': 'PreToolUse', 'permissionDecision': 'ask',
        'permissionDecisionReason':
            ('%s is code, and code is the cloud session\'s work (CLAUDE.md, "Working together"). Local sessions '
             'do hub work. If this edit is needed for a hub check, Gordon can allow it here. Otherwise describe '
             'it on a to:cloud Issue, or as a comment on the cloud session\'s open PR for that area, which wakes '
             'it within seconds.') % rel}}))
    return 0


if __name__ == '__main__':
    sys.exit(main())
