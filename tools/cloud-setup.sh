#!/usr/bin/env bash
# The hub's toolchain for a Claude Code cloud session: Groovy 2.4.21 on a Java 8 JDK, PowerShell 7 for the
# .ps1 runners, and `python` for the scripts that call it by that name. Run by the SessionStart hook in
# .claude/settings.json, and only in a cloud session - a local machine keeps its own toolchain.
#
# A fresh cloud container has none of these, and the 2026-10-05/06 sessions spent their first half hour
# installing them by hand each time. The suites must run on 2.4, not on whatever Groovy is newer: TestRuntime
# compiled on newer Groovy and not on 2.4 for weeks, which hid a real defect behind it.
#
# Idempotent and quiet: a tool already present is left alone, and a failed download is reported and
# skipped rather than failing the session start.
set -u
[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || [ "${HAI_FORCE_SETUP:-}" = "1" ] || exit 0

TC="${HAI_TOOLCHAIN:-$HOME/.cache/hai-toolchain}"
JAR="$TC/groovy-all-2.4.21.jar"
JAVA8=/usr/lib/jvm/java-8-openjdk-amd64/bin/java
PWSH_VER=7.4.6
mkdir -p "$TC"

fetch() {  # url out - retried, because the cloud proxy drops the odd transfer
    for wait in 2 4 8 16; do
        curl -sSLf -o "$2.part" "$1" && mv "$2.part" "$2" && return 0
        sleep "$wait"
    done
    rm -f "$2.part"; echo "cloud-setup: could not download $1" >&2; return 1
}

if [ ! -x "$JAVA8" ]; then
    (apt-get update -qq && apt-get install -y -qq openjdk-8-jdk-headless) >"$TC/apt.log" 2>&1 \
        || echo "cloud-setup: Java 8 install failed, see $TC/apt.log" >&2
fi

[ -s "$JAR" ] || fetch https://repo1.maven.org/maven2/org/codehaus/groovy/groovy-all/2.4.21/groovy-all-2.4.21.jar "$JAR"

if ! command -v pwsh >/dev/null 2>&1; then
    if fetch "https://github.com/PowerShell/PowerShell/releases/download/v$PWSH_VER/powershell-$PWSH_VER-linux-x64.tar.gz" "$TC/pwsh.tgz"; then
        mkdir -p "$TC/pwsh" && tar xzf "$TC/pwsh.tgz" -C "$TC/pwsh" && chmod +x "$TC/pwsh/pwsh" \
            && ln -sf "$TC/pwsh/pwsh" /usr/local/bin/pwsh
    fi
fi

command -v python >/dev/null 2>&1 || ln -sf "$(command -v python3)" /usr/local/bin/python

# `groovy` on PATH as 2.4, for Automation Map's tests/run-all.ps1, which calls it by that name.
if [ -x "$JAVA8" ] && [ -s "$JAR" ] && ! command -v groovy >/dev/null 2>&1; then
    cat >/usr/local/bin/groovy <<EOF
#!/bin/bash
exec $JAVA8 -Dfile.encoding=UTF-8 -cp $JAR groovy.ui.GroovyMain "\$@" 2> >(grep -v '^Picked up JAVA_TOOL_OPTIONS' >&2)
EOF
    chmod +x /usr/local/bin/groovy
fi

# engine/run-tests.sh and make_preview.py read these.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
    echo "export HAI_GROOVY_LIB=$JAR" >>"$CLAUDE_ENV_FILE"
    echo "export HAI_JAVA=$JAVA8" >>"$CLAUDE_ENV_FILE"
fi

echo "cloud-setup: java8 $([ -x "$JAVA8" ] && echo ok || echo MISSING), groovy 2.4 $([ -s "$JAR" ] && echo ok || echo MISSING), pwsh $(command -v pwsh >/dev/null && echo ok || echo MISSING)"
exit 0
