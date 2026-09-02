// Targeted behavior coverage for BUILD_CHANNEL/DIAGNOSTIC_LEVEL (item 16
// phase 2b, queue 437 point 6). Covers the five intentional channel uses
// that remain after the obsolete trace/watchdog-test cleanup (review, queue
// 440/442/444): the isDevBuild() helper itself, the automatic-scan default,
// and the three asset URL branches (font, watermark, sound). traceOn() and
// debugForceWatchdogWin no longer exist in source - the sections that used
// to model their logic here were removed with them, not left behind as
// dead assertions about deleted code. Logic below is copied verbatim from
// apps/automation_map.groovy so a regression there is caught here without
// needing the full Hubitat sandbox.
//
// The copied-closures section only proves the LOGIC is correct in isolation
// - it would stay green even if a real call site in the app drifted from
// that logic, or were hardcoded back to a literal (review, queue 440). The
// source-bound section below closes that gap by reading the actual app file
// and asserting the expected call-site shapes/counts are present in it.
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

// --- 2/3/4. asset-URL branches, all three use the identical ternary shape ---
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

println '--- 2/3/4. font, watermark, and sound-asset branches all resolve to dev/main by channel ---'
check('dev channel resolves asset branch to dev') {
    assert assetBranch('dev') == 'dev'
}
check('production channel resolves asset branch to main') {
    assert assetBranch('production') == 'main'
}

// --- Source-bound assertions (review, queue 440/444): read the real app
// file, resolved as a sibling of THIS script's own location (not the
// process cwd - same pattern tools/production-builder/*.groovy use), and
// prove each expected call-site shape/count is actually present in it. ---
File thisScriptFile = new File(this.class.protectionDomain.codeSource.location.toURI())
File appFile = new File(thisScriptFile.parentFile.parentFile, 'apps/automation_map.groovy')
String src = appFile.getText('UTF-8')

println '--- Source-bound: the app file itself matches the behavioral matrix above ---'
check('appFile resolves and is readable') {
    assert appFile.exists() : "expected ${appFile.absolutePath} to exist"
    assert src.length() > 0
}
check('isDevBuild() helper is defined') {
    assert src.contains('boolean isDevBuild()') : 'isDevBuild() definition not found in source'
}
check('defaultAutoScanTime() uses isDevBuild() for the 01:00/00:30 split') {
    assert src.contains("isDevBuild() ? '01:00' : '00:30'")
}
check('the Dev/production asset branch appears at all three asset URL sites (font, watermark, sound)') {
    String needle = "\${isDevBuild() ? 'dev' : 'main'}"
    int count = src.count(needle)
    assert count == 3 : "expected exactly 3 occurrences of ${needle}, found ${count}"
}
check('no executable app-name-derived channel check remains') {
    assert !src.contains('APP_NAME.contains') : 'found a reintroduced APP_NAME.contains(...) channel check'
}

println "${pass} passed, ${fail} failed"
if (fail > 0) System.exit(1)
