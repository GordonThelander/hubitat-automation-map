#!/usr/bin/env python3
"""Guard the RM parity feed from HAI Dev to the production Automation Map instance.

Gordon, 2026-10-06: the feed must not be disturbed without his go-ahead. It has been, once, by a session
that changed what it published, and production showed it. This pins every piece that decides what
production's table 1 shows - the files HAI publishes from, the public copy Automation Map's production app
fetches from GitHub, and the code on both sides that serves, fetches and renders it - by hash, in
FEED_LOCK.json at the repository root. The same script runs in both repositories.

  python tools/feed_guard.py              check; exit 1 naming each piece that changed
  python tools/feed_guard.py --approve "Gordon approved <what>, <date>"
                                          re-pin after Gordon's go-ahead, recording it in the lock

  python tools/feed_guard.py --hook       for a Claude Code PostToolUse hook: silent when unchanged, otherwise
                                          exit 2, which puts the message in front of the session
  python tools/feed_guard.py --pre        for a PreToolUse hook on Bash: asks Gordon before a command that
                                          publishes the capability list

Approving is Gordon's decision, not a session's: run --approve only when he has said yes to that change,
and quote him. CI runs the check on every push, so an unapproved change is red where everyone can see it.
"""
import hashlib, json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOCK = ROOT / 'FEED_LOCK.json'


def lines_of(path):
    return (ROOT / path).read_text(encoding='utf-8').splitlines()


def groovy_function(path, name):
    """A top-level method: its head at column 0, through the next lone `}` at column 0."""
    ls = lines_of(path)
    head = re.compile(r'^(?:private\s+|static\s+)*[A-Za-z][\w<>\[\], ]*\s+' + re.escape(name) + r'\s*\(')
    at = [i for i, l in enumerate(ls) if head.match(l)]
    if len(at) != 1:
        raise LookupError('%s defined %d times in %s' % (name, len(at), path))
    for j in range(at[0] + 1, len(ls)):
        if ls[j] == '}':
            return '\n'.join(ls[at[0]:j + 1])
    raise LookupError('%s has no closing brace at column 0 in %s' % (name, path))


def js_function(path, name):
    """A function in a page embedded in the app: from its head to the next function head."""
    ls = lines_of(path)
    at = [i for i, l in enumerate(ls) if re.search(r'\bfunction ' + re.escape(name) + r'\s*\(', l)]
    if len(at) != 1:
        raise LookupError('function %s defined %d times in %s' % (name, len(at), path))
    end = next((j for j in range(at[0] + 1, len(ls)) if re.search(r'\bfunction [A-Za-z_$][\w$]*\s*\(', ls[j])), len(ls))
    return '\n'.join(ls[at[0]:end])


def one_line(path, marker):
    hits = [l for l in lines_of(path) if marker in l]
    if len(hits) != 1:
        raise LookupError('%r appears on %d lines of %s' % (marker, len(hits), path))
    return hits[0]


def content(entry):
    # Line endings are normalised: a Windows checkout holds CRLF where the repository holds LF, and on
    # 2026-10-06 that alone made the guard report an unchanged file as changed - and a re-pin was approved
    # for a change that never happened. What the feed serves is the content, not the checkout's endings.
    kind, path = entry['kind'], entry['path']
    if kind == 'file':
        return (ROOT / path).read_bytes().replace(b'\r\n', b'\n')
    text = {'groovy': groovy_function, 'js': js_function, 'line': one_line}[kind](path, entry['name'])
    return text.replace('\r\n', '\n').encode('utf-8')


def label(entry):
    return entry['path'] if entry['kind'] == 'file' else '%s: %s %s' % (entry['path'], entry['kind'], entry['name'])


def digest(entry):
    try:
        return hashlib.sha256(content(entry)).hexdigest()
    except (OSError, LookupError) as e:
        return 'MISSING: %s' % e


PUBLISHING = re.compile(r'publish_public_list|publish_cycle|public_capabilities\.py|feed_guard\.py\s+--approve')


def pre_hook():
    try:
        call = json.load(sys.stdin)
    except ValueError:
        return 0
    command = ((call.get('tool_input') or {}).get('command') or '')
    if PUBLISHING.search(command):
        print(json.dumps({'hookSpecificOutput': {
            'hookEventName': 'PreToolUse', 'permissionDecision': 'ask',
            'permissionDecisionReason': 'This publishes or re-pins the RM parity feed that production Automation '
                                        "Map shows. It needs Gordon's go-ahead (CLAUDE.md)."}}))
    return 0


def main():
    if sys.argv[1:2] == ['--pre']:
        return pre_hook()
    lock = json.loads(LOCK.read_text(encoding='utf-8'))
    if len(sys.argv) > 2 and sys.argv[1] == '--approve':
        for e in lock['pinned']:
            e['sha256'] = digest(e)
            if e['sha256'].startswith('MISSING'):
                sys.exit('cannot pin %s: %s' % (label(e), e['sha256']))
        lock.setdefault('approvals', []).append(sys.argv[2])
        LOCK.write_text(json.dumps(lock, indent=2) + '\n', encoding='utf-8')
        print('re-pinned %d pieces: %s' % (len(lock['pinned']), sys.argv[2]))
        return 0
    changed = [(label(e), d) for e in lock['pinned'] for d in [digest(e)] if d != e['sha256']]
    hook = sys.argv[1:2] == ['--hook']
    if not changed:
        if not hook:
            print('feed guard: %d pinned pieces unchanged' % len(lock['pinned']))
        return 0
    out = sys.stderr if hook else sys.stdout
    print('FEED GUARD: the RM parity feed to production Automation Map changed without a recorded approval.', file=out)
    for name, d in changed:
        print('  - %s%s' % (name, ' (' + d + ')' if d.startswith('MISSING') else ''), file=out)
    print("Revert it, or get Gordon's go-ahead and run: python tools/feed_guard.py --approve \"<his words, date>\"", file=out)
    return 2 if hook else 1


if __name__ == '__main__':
    sys.exit(main())
