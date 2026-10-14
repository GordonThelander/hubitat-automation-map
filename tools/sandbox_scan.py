#!/usr/bin/env python3
"""Refuse an app that calls something the Hubitat sandbox forbids.

  python3 tools/sandbox_scan.py                 scan apps/automation_map.groovy
  python3 tools/sandbox_scan.py FILE [FILE...]  scan exactly these files

WHY. The hub's sandbox refuses some calls only when the code is saved, so the suites and a Groovy 2.4 compile,
which run on a plain JVM, cannot see them. This scans the app as the hub receives it, before it is deployed.
Approved by Gordon on 2026-10-14, after the hub refused another app of his for System.currentTimeMillis().

WHAT IS SCANNED. Groovy code only. Comments, string literals and the triple-quoted HTML/JavaScript templates
are blanked first, because a template that mentions, say, `System.` in JavaScript is not a Groovy call. The
${...} expressions inside a GString ARE Groovy code and are scanned. The region tracking is the one
check_template.sh and validate.ps1 use (PR #63): triple-quoted regions, /* */ comments, single-line strings,
and a slashy string after one of ~(,=!&|?:[{;+-*%<> or `return`.

EXIT. 0 when nothing is found; 1 with file:line, the rule and the line text for every hit; 2 on a usage error.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_FILES = ['apps/automation_map.groovy']

# (name, regex, why). Each regex runs on one line of Groovy code, with comments, strings and templates blanked.
FORBIDDEN = [
    ('System.', r'\bSystem\s*\.', 'java.lang.System is not on the sandbox allow list (System.currentTimeMillis() was refused on 2026-10-14)'),
    ('currentTimeMillis', r'\bcurrentTimeMillis\b', 'refused by the hub on 2026-10-14; use now() or new Date().getTime()'),
    ('nanoTime', r'\bnanoTime\b', 'System.nanoTime, not allowed'),
    ('getenv', r'\bgetenv\b', 'environment access, not allowed'),
    ('exit(', r'\bexit\s*\(', 'System.exit or Runtime.exit, not allowed'),
    ('Thread.', r'\bThread\s*\.|\bnew\s+Thread\b', 'threads are not allowed; use runIn or pauseExecution'),
    ('Runtime.', r'\bRuntime\s*\.', 'java.lang.Runtime, not allowed'),
    ('ProcessBuilder', r'\bProcessBuilder\b', 'process creation, not allowed'),
    ('Class.forName', r'\bClass\s*\.\s*forName\b', 'reflection, not allowed'),
    ('.forName(', r'\.\s*forName\s*\(', 'reflection, not allowed'),
    ('getClass()', r'\bgetClass\s*\(', 'refused by the sandbox'),
    ('.class', r'\.\s*class\b(?!\s*\.\s*(?:name|simpleName)\s*=)', 'compiles to getClass(), which the sandbox refuses'),
    ('java.io.File', r'\bjava\.io\.File\b', 'file system access, not allowed'),
    ('new File(', r'\bnew\s+File\s*\(', 'file system access, not allowed'),
    ('ClassLoader', r'\bClassLoader\b', 'class loading, not allowed'),
    ('Eval.me', r'\bEval\s*\.\s*(?:me|x|xy|xyz)\b', 'dynamic evaluation, not allowed'),
    ('GroovyShell', r'\bGroovyShell\b', 'dynamic evaluation, not allowed'),
    ('metaClass', r'\bmetaClass\b', 'metaprogramming, not allowed'),
    ('@Grab', r'@Grab\b', 'dependency fetching, not allowed'),
    ('Comparable', r'\bComparable\b', 'refused by the sandbox ("Importing [java.lang.Comparable] is not allowed")'),
    ('java.lang.reflect', r'\bjava\.lang\.reflect\b', 'reflection, not allowed'),
    ('groovy.lang.Reference', r'\bgroovy\.lang\.Reference\b', 'refused by the sandbox: an inner class capturing a local compiles to it'),
    ('anonymous inner class', r'\bnew\s+[A-Z][\w.]*\s*(?:<[^>]*>)?\s*\([^()]*\)\s*\{',
     'an inner class compiles to groovy.lang.Reference when it captures a local, which the sandbox refuses'),
    ('class declaration', r'^\s*(?:(?:public|private|protected|abstract|final|static)\s+)*(?:class|interface|enum|trait)\s+\w+',
     'custom classes are refused: "Importing [X] is not allowed"'),
]

# An entry allows ONE exact line, after surrounding whitespace is stripped, for ONE rule, in ONE file name.
# Every entry says why that line is safe on the hub. Keep this list empty if at all possible.
#   {'file': 'automation_map.groovy', 'rule': 'System.', 'line': '<exact stripped line text>', 'why': '...'}
ALLOW = [
    # A Map read, not a getClass() call: on a Map, Groovy reads `.class` as the key 'class'. `d` is declared
    # `Map d` on the line above it, and the registry's dependency entries carry a "class" field.
    {'file': 'automation_map.groovy', 'rule': '.class',
     'line': """String kind = (REGISTRY_CLASS_TO_KIND["${d.class}"] ?: 'internet') as String""",
     'why': 'Map key read on a declared Map'},
    # The next three ARE getClass() on an exception, inside catch blocks. The hub accepts them at save (they
    # are in the released app), so they are not a save-time refusal, but on the hub the call may itself throw
    # and take the error reply with it (HAI met exactly that on 2026-09-25). Allowed so the scan passes on
    # the current release without an app change; replace each with ex.toString() in the next apps/ release
    # and delete these entries. An entry matches its exact line only, so editing a line removes its pass.
    {'file': 'automation_map.groovy', 'rule': '.class',
     'line': """data: JsonOutput.toJson([ok: false, reason: 'error', error: "${ex.class.simpleName}"]))""",
     'why': 'in the released app; runtime getClass() risk, to be replaced with ex.toString()'},
    {'file': 'automation_map.groovy', 'rule': '.class',
     'line': """haiAssessReply(rid, [ok: false, reason: 'error', message: "${ex.class.simpleName}".toString()])""",
     'why': 'in the released app; runtime getClass() risk, to be replaced with ex.toString()'},
    {'file': 'automation_map.groovy', 'rule': '.class',
     'line': """data: JsonOutput.toJson([ok: false, error: "${ex.class.simpleName}: ${ex.message}"]))""",
     'why': 'in the released app; runtime getClass() risk, to be replaced with ex.toString()'},
]

SLASHY_AFTER = '~(,=!&|?:[{;+-*%<>'


def groovy_code_only(src):
    """Return src with comments, string contents and triple-quoted templates blanked; newlines are kept.

    The ${...} expressions inside double-quoted and triple-double-quoted GStrings are kept, since they run.
    """
    out = list(src)
    n = len(src)

    def blank(a, b):
        for k in range(a, b):
            if out[k] != '\n':
                out[k] = ' '

    def slashy_can_start(i):
        p = i - 1
        while p >= 0 and src[p] in ' \t':
            p -= 1
        if p < 0 or src[p] in '\r\n':
            return True
        if src[p] in SLASHY_AFTER:
            return True
        line_start = src.rfind('\n', 0, p + 1) + 1
        return re.search(r'(^|[^A-Za-z0-9_])return$', src[line_start:p + 1]) is not None

    def string(i, close, gstring, multiline):
        # i is just after the opening quote(s). Blanks the contents and returns the index after the close.
        while i < n:
            if src[i] == '\\':
                blank(i, min(i + 2, n))
                i += 2
                continue
            if gstring and src.startswith('${', i):
                blank(i, i + 2)
                i = code(i + 2, True)
                continue
            if src.startswith(close, i):
                return i + len(close)
            if not multiline and src[i] == '\n':
                return i + 1  # unterminated single-line string: resynchronise at the line end
            blank(i, i + 1)
            i += 1
        return n

    def code(i, in_interp):
        depth = 0
        while i < n:
            ch = src[i]
            if in_interp and ch == '{':
                depth += 1
            elif in_interp and ch == '}':
                if depth == 0:
                    blank(i, i + 1)
                    return i + 1
                depth -= 1
            if src.startswith('//', i):
                e = src.find('\n', i)
                e = n if e < 0 else e
                blank(i, e)
                i = e
                continue
            if src.startswith('/*', i):
                e = src.find('*/', i + 2)
                e = n if e < 0 else e + 2
                blank(i, e)
                i = e
                continue
            if src.startswith('"""', i):
                i = string(i + 3, '"""', True, True)
                continue
            if src.startswith("'''", i):
                i = string(i + 3, "'''", False, True)
                continue
            if ch == '"':
                i = string(i + 1, '"', True, False)
                continue
            if ch == "'":
                i = string(i + 1, "'", False, False)
                continue
            if src.startswith('$/', i):
                e = src.find('/$', i + 2)
                e = n if e < 0 else e + 2
                blank(i, e)
                i = e
                continue
            if ch == '/' and slashy_can_start(i):
                j = i + 1
                while j < n and src[j] != '/' and src[j] != '\n':
                    j += 2 if src[j] == '\\' else 1
                if j < n and src[j] == '/':
                    blank(i, j + 1)
                    i = j + 1
                    continue
            i += 1
        return n

    code(0, False)
    return ''.join(out)


def scan(path):
    text = pathlib.Path(path).read_text(encoding='utf-8')
    raw_lines = text.split('\n')
    code_lines = groovy_code_only(text).split('\n')
    name = pathlib.Path(path).name
    hits = []
    for idx, code in enumerate(code_lines):
        if not code.strip():
            continue
        for rule, rx, why in FORBIDDEN:
            if re.search(rx, code):
                line = raw_lines[idx].strip()
                if any(a['file'] == name and a['rule'] == rule and a['line'] == line for a in ALLOW):
                    continue
                hits.append((path, idx + 1, rule, why, line))
    return hits


def main(argv):
    files = argv[1:] or [str(ROOT / f) for f in DEFAULT_FILES]
    missing = [f for f in files if not pathlib.Path(f).is_file()]
    if missing:
        print('sandbox_scan: no such file: ' + ', '.join(missing), file=sys.stderr)
        return 2
    hits = []
    for f in files:
        hits.extend(scan(f))
    for path, line_no, rule, why, line in hits:
        print('SANDBOX FORBIDS: %s:%d: [%s] %s' % (path, line_no, rule, line[:200]))
        print('    why: %s' % why)
    if hits:
        print('sandbox_scan: %d forbidden call(s) in %d file(s). The hub would refuse this app.' % (len(hits), len(files)))
        return 1
    print('sandbox_scan: clean, %d file(s), %d rule(s).' % (len(files), len(FORBIDDEN)))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
