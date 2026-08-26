#!/usr/bin/env bash
# Guards the one mistake this project keeps making.
#
# This app builds HTML inside Groovy GStrings, so Groovy consumes backslash
# escapes before the browser ever sees them. A JS string written as
# 'the app\'s state' is served as 'the app's state', which ends the string
# early and kills the whole page script - the page then renders empty or dead
# with no error surviving into the served file. This has happened three times:
# a regex literal, then lines.join('\n'), then an apostrophe.
#
# Checks the WHOLE file rather than one method, because there is now more than
# one place that emits HTML (the map page and the scan button).
#
# Legitimate backslashes, whitelisted below:
#   ~/^https?:\/\/.../   the URL pattern constant (a Groovy slashy regex)
#   ~/^(https?:\/\/...   the origin pattern constant (a Groovy slashy regex),
#                         same reasoning as the line above
#   /\([^)]*\)/          condition-cleanup regexes (Groovy source, not template)
#   /\s+/                    "  (Groovy-side use, via replaceAll)
#   /\.$/                    Groovy-side use, via replaceAll - strips a
#                             trailing period from a Hub Variable name
#   /^tCapab\d+$/            Groovy-side use, via ==~ - matches a numbered
#                             trigger setting name
#   /%([A-Za-z_]...          Groovy-side use, via =~ - finds %Variable%
#                             interpolation syntax in free text
#   u003c                 encoding every '<' in the embedded JSON blobs as
#                         the escape \u003c, so no literal '<' survives for a
#                         browser's HTML tag scanner to match regardless of
#                         how a closing tag is spelled or spaced - see
#                         jsonForScriptEmbed()
#   """\                 an opening line continuation
#   join('\\n')          an intentionally double-escaped newline
#   [",\\n]              a CSV-escaping regex's own double-escaped newline -
#                         same reasoning as join('\\n'), different call site
#   replace(/\\s+/g,     JS-side whitespace regex (CSV filename sanitising) -
#                         \s+ alone here would be Groovy's, not the browser's
#   [^&\\s]*             JS-side token-redaction regex in the scan diagnostic,
#                         same double-escape reasoning as the line above
#   replace(/\\r?\\n/g   JS-side newline cleanup in the comparison CSV
#   '\\ufeff' / '\\r\\n'  JS-side CSV BOM and row separators
#   key: '\uXXXX'        ICON_GLYPHS entries (device icon font codepoints) -
#                         Groovy consuming \uXXXX and emitting the raw glyph
#                         character is the INTENDED behaviour here, not a bug:
#                         a JS string literal containing that one raw
#                         character is exactly equivalent to one containing
#                         the escape, so either way is correct in the browser.
#
# Usage: ./check_template.sh apps/automation_map.groovy
set -euo pipefail
FILE="${1:-apps/automation_map.groovy}"

if ! command -v grep >/dev/null 2>&1; then
  echo "check_template.sh requires grep; validation did not run." >&2
  exit 2
fi

if [ ! -f "$FILE" ]; then
  echo "App source not found: $FILE" >&2
  exit 2
fi

BAD=$(grep -n '\\' "$FILE" \
  | grep -v 'Pattern URL_PATTERN' \
  | grep -v 'Pattern ORIGIN_PATTERN' \
  | grep -v "replaceAll(/\\\\(\[^)\]\*\\\\)/" \
  | grep -v "replaceAll(/\\\\s+/" \
  | grep -v "replaceAll(/\\\\.\\$/" \
  | grep -v "==~ /\\^tCapab\\\\d+\\$/" \
  | grep -v '"""\\$' \
  | grep -v "join('\\\\\\\\n')" \
  | grep -v '\[",\\\\n\]' \
  | grep -v 'replace(/\\\\s+/g' \
  | grep -v 'access_token=\[^&\\\\s\]' \
  | grep -vE "[a-z]+: '.u[0-9a-f]{4}'" \
  | grep -v 'u003c' \
  | grep -v 'replace(/\\\\r?\\\\n/g' \
  | grep -v '\\\\ufeff' \
  || true)

if [ -n "$BAD" ]; then
  echo "Suspect backslash in $FILE:"
  echo "$BAD"
  echo
  echo "Groovy consumes these before the browser sees them. If one of these is"
  echo "genuinely fine, whitelist it in this script rather than ignoring it."
  exit 1
fi

echo "template backslash check: clean (whole file)"
