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
# Only backslashes INSIDE a triple-quoted region ("""...""" or '''...''') are
# checked: those regions are the HTML/JavaScript templates. Code outside them
# is plain Groovy, so a slashy regex there (~/\d+/, ==~ /\d+/, =~ /\d+/) is
# safe and needs no whitelist entry. The awk scanner below tracks
# triple-quoted regions and /* */ comments across lines, and skips quoted
# strings, slashy strings and // comments within their own line, so a """
# inside one of those does not toggle a region. An unterminated region fails
# the check rather than passing the rest of the file. validate.ps1 applies the
# same rule (Find-TemplateBackslashLines).
#
# Legitimate template backslashes, whitelisted below:
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

for tool in grep awk; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "check_template.sh requires $tool; validation did not run." >&2
    exit 2
  fi
done

if [ ! -f "$FILE" ]; then
  echo "App source not found: $FILE" >&2
  exit 2
fi

# Prints "N:line" for each line with a backslash inside a triple-quoted
# region, and "N:unterminated ..." if a region never closes.
SCAN=$(cat <<'AWK'
BEGIN { state = ""; opened = 0; slashyAfter = "~(,=!&|?:[{;+-*%<>"; tq = "\"\"\""; ts = "'''" }
{
  line = $0; sub(/\r$/, "", line); len = length(line); flagged = 0; i = 1
  while (i <= len) {
    c = substr(line, i, 1)
    if (state == "/*") {
      if (substr(line, i, 2) == "*/") { state = ""; i += 2 } else { i++ }
      continue
    }
    if (state != "") {
      if (c == "\\") { flagged = 1; i += 2; continue }
      if (substr(line, i, 3) == state) { state = ""; i += 3; continue }
      i++; continue
    }
    three = substr(line, i, 3)
    if (three == tq || three == ts) { state = three; opened = NR; i += 3; continue }
    if (c == "\"" || c == "'") {
      j = i + 1
      while (j <= len && substr(line, j, 1) != c) { if (substr(line, j, 1) == "\\") j += 2; else j++ }
      i = j + 1; continue
    }
    two = substr(line, i, 2)
    if (two == "//") break
    if (two == "/*") { state = "/*"; i += 2; continue }
    if (c == "/") {
      p = i - 1
      while (p >= 1 && substr(line, p, 1) ~ /[ \t]/) p--
      if (p < 1 || index(slashyAfter, substr(line, p, 1)) > 0 || substr(line, 1, p) ~ /(^|[^A-Za-z0-9_])return$/) {
        j = i + 1
        while (j <= len && substr(line, j, 1) != "/") { if (substr(line, j, 1) == "\\") j += 2; else j++ }
        i = j + 1; continue
      }
    }
    i++
  }
  if (flagged) print NR ":" line
}
END {
  if (state == tq || state == ts) print opened ":unterminated " state " region opened here; the backslash scan could not tell template from code after it"
}
AWK
)

IN_TEMPLATE=$(awk "$SCAN" "$FILE")

BAD=$(printf '%s\n' "$IN_TEMPLATE" \
  | grep '.' \
  | grep -v '^[0-9]*:[[:space:]]*return """\\[[:space:]]*$' \
  | grep -v "join('\\\\\\\\n')" \
  | grep -v '\[",\\\\n\]' \
  | grep -v 'replace(/\\\\s+/g' \
  | grep -v 'access_token=\[^&\\\\s\]' \
  | grep -vE "[a-z]+: '.u[0-9a-f]{4}'" \
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
