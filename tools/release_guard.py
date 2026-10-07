#!/usr/bin/env python3
"""HPM's Dev channel is `dev`: what a tester gets is the app on `dev`, offered when packageManifest.json's version
rises. So app code must never reach `dev` without a version that says so (Gordon, 2026-10-07).

It did on 2026-10-07: the merge of PR #2 put 600 lines of new decoding on `dev` still labelled 2.4.4, so testers
already on 2.4.4 were offered nothing and new installs got different code under the same number.

  python tools/release_guard.py <base-sha>     fail if apps/ changed since <base-sha> without a higher version
  python tools/release_guard.py                only check that the version is stated consistently

Consistent means APP_VERSION in the app, the manifest's version, its app entry's version, and the start of its
release notes all name the same version. Run by .github/workflows/release-guard.yml on every push and pull request
to `dev`.
"""
import json, pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
APP = 'apps/automation_map.groovy'
VERSION = re.compile(r"^@Field static final String APP_VERSION = '([0-9.]+)'", re.M)


def app_version(text):
    m = VERSION.search(text)
    return m.group(1) if m else None


def key(v):
    return tuple(int(p) for p in v.split('.'))


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, capture_output=True, text=True)


def main():
    problems = []
    head = app_version((ROOT / APP).read_text(encoding='utf-8'))
    m = json.loads((ROOT / 'packageManifest.json').read_text(encoding='utf-8'))
    stated = {'APP_VERSION': head, 'manifest version': m.get('version'),
              'manifest app version': ((m.get('apps') or [{}])[0]).get('version')}
    if len(set(stated.values())) != 1:
        problems.append('the version is not stated consistently: %s' % stated)
    if not str(m.get('releaseNotes', '')).startswith('%s - ' % head):
        problems.append("the release notes do not begin with '%s - '" % head)

    base = sys.argv[1] if len(sys.argv) > 1 else ''
    if base and set(base) != {'0'}:
        changed = git('diff', '--name-only', base, 'HEAD', '--', 'apps/')
        if changed.returncode != 0:
            problems.append('cannot compare against %s: %s' % (base, changed.stderr.strip()))
        elif changed.stdout.strip():
            before = git('show', '%s:%s' % (base, APP))
            was = app_version(before.stdout) if before.returncode == 0 else None
            if was and head and key(head) <= key(was):
                problems.append('apps/ changed (%s) but APP_VERSION is %s, not above %s. A tester on %s would be '
                                'offered nothing, and a new install would get different code under the same '
                                'number.' % (', '.join(changed.stdout.split()), head, was, was))

    if problems:
        print('RELEASE GUARD:')
        for p in problems:
            print('  - ' + p)
        print('Bump APP_VERSION, packageManifest.json version and apps[0].version together, and start the release '
              'notes with the new version. Patch bumps for Dev builds are a standing permission (CLAUDE.md).')
        return 1
    print('release guard: %s, stated consistently%s' % (head, ' and raised for the app change' if base else ''))
    return 0


if __name__ == '__main__':
    sys.exit(main())
