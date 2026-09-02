// Targeted behavior coverage for BUILD_CHANNEL/DIAGNOSTIC_LEVEL (item 16
// phase 2b, queue 437 point 6): proves each of the five APP_NAME
// checks it replaced - automatic-scan default, trace availability, font
// branch, watermark branch, sound-asset branch - actually follows the new
// constants, plus the debugForceWatchdogWin gate that got the same channel
// check added alongside traceOn(). Logic below is copied verbatim from
// apps/automation_map.groovy so a regression there is caught here without
// needing the full Hubitat sandbox.
//
// The copied-closures section above only proves the LOGIC is correct in
// isolation - it would stay green even if a real call site in the app drifted
// from that logic, or were hardcoded back to a literal (review, queue 440).
// The source-bound section below closes that gap by reading the actual app
// file and asserting the expected call-site shapes/counts are present in it.
//
// Run with: groovy tests/build-channel-behavior.groovy

// --- isDevBuild(), copied verbatim ---
def isDevBuildFn = { String buildChannel ->
    return buildChannel == 'dev'
}

// --- 1. defaultAutoScanTime(), copied verbatim (isDevBuild() ? '01:00' : '00:30') ---
def defaultAutoScanTime = { String buildChannel ->
    return isDevBuildFn(buildChannel) ? '01:00' : '00:30'
}

// --- 2. traceOn() gate, copied verbatim ---
def traceOnGate = { boolean traceEnabled, String buildChannel, int diagnosticLevel, boolean diagOn ->
    return traceEnabled && isDevBuildFn(buildChannel) && diagnosticLevel == 2 && diagOn
}

// --- 3. debugForceWatchdogWin gate, copied verbatim ---
def debugForceWatchdogWinGate = { boolean traceEnabled, boolean devTestForceWatchdogWin, String buildChannel, int diagnosticLevel ->
    return traceEnabled && devTestForceWatchdogWin && isDevBuildFn(buildChannel) && diagnosticLevel == 2
}

// --- 4/5/6. asset-URL branches, all three use the identical ternary shape ---
def assetBranch = { String buildChannel -> isDevBuildFn(buildChannel) ? 'dev' : 'main' }

int pass = 0, fail = 0
def check = { String name, Closure body ->
    try {
        body()
        println "PASS  ${name}"
        pass++
    } catch (Throwable t) {
        println "FAIL  ${name} - ${t.class.simpleName}: ${t.message}"
        fail++
    }
}

println '--- 1. defaultAutoScanTime() follows BUILD_CHANNEL ---'
check('dev channel scans at 01:00') {
    assert defaultAutoScanTime('dev') == '01:00'
}
check('production channel scans at 00:30') {
    assert defaultAutoScanTime('production') == '00:30'
}

println '--- 2. traceOn() requires dev channel AND diagnostic level 2, on top of the existing booleans ---'
check('all four conditions true -> trace on') {
    assert traceOnGate(true, 'dev', 2, true) == true
}
check('production channel blocks trace even with everything else true') {
    assert traceOnGate(true, 'production', 2, true) == false
}
check('diagnostic level 0 blocks trace even on dev channel') {
    assert traceOnGate(true, 'dev', 0, true) == false
}
check('TRACE_ENABLED false still blocks trace (pre-existing gate unaffected)') {
    assert traceOnGate(false, 'dev', 2, true) == false
}
check('diagOn() false still blocks trace (pre-existing gate unaffected)') {
    assert traceOnGate(true, 'dev', 2, false) == false
}

println '--- 3. debugForceWatchdogWin requires dev channel AND diagnostic level 2 (new safety gate, previously absent) ---'
check('all four conditions true -> watchdog-win capture armed') {
    assert debugForceWatchdogWinGate(true, true, 'dev', 2) == true
}
check('production channel blocks it even with the dev-test boolean true - the actual bug this closes') {
    assert debugForceWatchdogWinGate(true, true, 'production', 2) == false
}
check('diagnostic level 0 blocks it even on dev channel') {
    assert debugForceWatchdogWinGate(true, true, 'dev', 0) == false
}
check('DEV_TEST_FORCE_WATCHDOG_WIN false still blocks it (pre-existing gate unaffected)') {
    assert debugForceWatchdogWinGate(true, false, 'dev', 2) == false
}

println '--- 4/5/6. font, watermark, and sound-asset branches all resolve to dev/main by channel ---'
check('dev channel resolves asset branch to dev') {
    assert assetBranch('dev') == 'dev'
}
check('production channel resolves asset branch to main') {
    assert assetBranch('production') == 'main'
}

// --- Source-bound assertions (review, queue 440): read the real app file,
// resolved as a sibling of THIS script's own location (not the process cwd -
// same pattern tools/production-builder/*.groovy use), and prove each
// expected call-site shape/count is actually present in it. ---
File thisScriptFile = new File(this.class.protectionDomain.codeSource.location.toURI())
File appFile = new File(thisScriptFile.parentFile.parentFile, 'apps/automation_map.groovy')
String src = appFile.getText('UTF-8')

println '--- Source-bound: the app file itself matches the behavioral matrix above ---'
check('appFile resolves and is readable') {
    assert appFile.exists() : "expected ${appFile.absolutePath} to exist"
    assert src.length() > 0
}
check('the Dev/production asset branch appears at all three asset URL sites (font, watermark, sound)') {
    String needle = "\${isDevBuild() ? 'dev' : 'main'}"
    int count = src.count(needle)
    assert count == 3 : "expected exactly 3 occurrences of ${needle}, found ${count}"
}
check('defaultAutoScanTime() uses isDevBuild() for the 01:00/00:30 split') {
    assert src.contains("isDevBuild() ? '01:00' : '00:30'")
}
check('traceOn() includes both channel and diagnostic level 2') {
    def m = (src =~ /(?s)boolean traceOn\(\)\s*\{(.*?)\}/)
    assert m.find() : 'traceOn() function body not found in source'
    String body = m.group(1)
    assert body.contains('isDevBuild()') : 'traceOn() body missing isDevBuild()'
    assert body.contains('DIAGNOSTIC_LEVEL == 2') : 'traceOn() body missing DIAGNOSTIC_LEVEL == 2'
}
check('debugForceWatchdogWin includes both channel and diagnostic level 2') {
    def m = (src =~ /boolean debugForceWatchdogWin = ([^\n]+)/)
    assert m.find() : 'debugForceWatchdogWin assignment not found in source'
    String line = m.group(1)
    assert line.contains('isDevBuild()') : 'debugForceWatchdogWin assignment missing isDevBuild()'
    assert line.contains('DIAGNOSTIC_LEVEL == 2') : 'debugForceWatchdogWin assignment missing DIAGNOSTIC_LEVEL == 2'
}
check('no executable app-name-derived channel check remains') {
    assert !src.contains('APP_NAME.contains') : 'found a reintroduced APP_NAME.contains(...) channel check'
}

println "${pass} passed, ${fail} failed"
if (fail > 0) System.exit(1)
