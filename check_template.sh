#!/usr/bin/env bash
# Guards the one mistake this project keeps making.
#
# buildMapHtml returns a Groovy GString, so Groovy consumes backslash escapes
# before the browser ever sees them. A JS string written as 'the app\'s state'
# is served as 'the app's state', which ends the string early and kills the
# whole page script - the graph then renders empty with no console error that
# survives to the served file. This has happened three times: once with a regex
# literal, once with lines.join('\n'), and once with an apostrophe.
#
# Only three backslashes are legitimate in the template region:
#   <\\/script>   escaping a closing script tag inside the JSON blob
#   """\          the opening line continuation
#   join('\\n')   an intentionally double-escaped newline
#
# Usage: ./check_template.sh apps/automation_map.groovy
set -euo pipefail
FILE="${1:-apps/automation_map.groovy}"

START=$(grep -n 'String buildMapHtml' "$FILE" | head -1 | cut -d: -f1)
if [ -z "$START" ]; then
  echo "could not find buildMapHtml in $FILE" >&2
  exit 2
fi

BAD=$(awk -v s="$START" 'NR>=s && index($0,"\\")>0' "$FILE" \
  | grep -v '<\\\\/script>' \
  | grep -v '"""\\' \
  | grep -v "join('\\\\\\\\n')" \
  || true)

if [ -n "$BAD" ]; then
  echo "Suspect backslash in the HTML template region of $FILE:"
  echo "$BAD"
  echo
  echo "Groovy will consume these before the browser sees them."
  exit 1
fi

echo "template backslash check: clean"
