// Fixture for nodeEntry()'s statusSuffix parameter (BACKLOG items 9/18,
// Bucket/Queue 370-372). Replaces the earlier app-status-label.groovy -
// that file tested a separate appStatusLabel() wrapper Codex's review (372)
// showed was the wrong place to fix this: the actual defects (truncation
// eating the suffix, decorated names leaking into the AI export, hub
// context being discarded) all live in nodeEntry() itself, so this tests
// that function directly instead.
// Run with: groovy tests/node-entry-status.groovy
//
// nodeEntry() below is copied verbatim from apps/automation_map.groovy -
// keep the two in sync by hand.

Map nodeEntry(String id, String fullLabel, String group, String subtitle = null, String drawLabel = null,
              String statusSuffix = null, boolean statusInTitle = true) {
    String label = fullLabel ?: id
    String clean = drawLabel ?: label
    String shortLabel = clean
    if (shortLabel.length() > 24) shortLabel = "${shortLabel.substring(0, 22)}…"
    if (statusSuffix) shortLabel = "${shortLabel} (${statusSuffix})"
    String canonicalName = subtitle ? "${clean} (${subtitle})" : clean
    String drawText = statusSuffix ? "${canonicalName} (${statusSuffix})" : canonicalName
    String titleText = subtitle ? "${label} (${subtitle})" : label
    if (statusSuffix && statusInTitle) titleText = "${titleText} (${statusSuffix})"
    return [
        id: id,
        label: shortLabel,
        draw: drawText,
        title: titleText,
        name: canonicalName,
        group: group,
    ]
}

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

println '--- 1. No statusSuffix: byte-identical to the pre-item-18 behaviour ---'
def r1 = nodeEntry('d1', 'Kitchen Light', 'device')
check('label/draw/title/name all just the plain name') {
    assert r1.label == 'Kitchen Light'
    assert r1.draw == 'Kitchen Light'
    assert r1.title == 'Kitchen Light'
    assert r1.name == 'Kitchen Light'
}

println '--- 2. Regression fixture for Codex review 372, issue 1: a long name must not lose its suffix to truncation ---'
// 30 characters on its own - already over the 24-char truncation
// threshold before any suffix is even considered. The bug this reproduces:
// baking "(Disabled)" into the string BEFORE truncation silently dropped
// it once the combined length pushed past the cut point.
def longName = 'Garage Door Sensor Assembly Three'
def r2 = nodeEntry('d2', longName, 'device', null, null, 'Disabled')
check('label is truncated but the suffix survives, appended after truncation') {
    assert (r2.label as String).endsWith('(Disabled)')
    assert (r2.label as String).contains('…')
}
check('draw carries the full name (untruncated) plus the suffix') {
    assert r2.draw == "${longName} (Disabled)"
}

println '--- 3. Regression fixture for Codex review 372, issue 2: name must stay undecorated for the AI export ---'
def r3 = nodeEntry('a3', 'Mode Alarm Reminder', 'app', 'Rule-5.1', 'Mode Alarm Reminder', 'Paused')
check('name has no live-status suffix and no truncation - safe as an export identity') {
    assert r3.name == 'Mode Alarm Reminder (Rule-5.1)'
}
check('draw and title DO carry the live-status suffix - it belongs in rendering, not identity') {
    assert r3.draw == 'Mode Alarm Reminder (Rule-5.1) (Paused)'
    assert r3.title == 'Mode Alarm Reminder (Rule-5.1) (Paused)'
}

println '--- 4a. Live-shaped fixture for Codex review 374: real Dev hub data, statusInTitle=false ---'
// The exact shape confirmed live on Dev: Hubitat's own paused-app label
// injection already reads literally "(Paused)" - identical wording to this
// function's own suffix. fullLabel carries it as plain text (stripTags
// removes only the <span> markup, not the words), drawLabel is the
// separately already-stripped version with no such text at all. This is
// the real call the app node site makes - statusInTitle explicitly false.
def r4a = nodeEntry('a4a', "__Parent's test (Paused)", 'app', 'Rule-5.1', "__Parent's test", 'Paused', false)
check('title contains Paused exactly once, not duplicated') {
    assert (r4a.title as String).count('Paused') == 1
}
check('title is built from the untouched fullLabel plus subtitle - nothing stripped, nothing re-added') {
    assert r4a.title == "__Parent's test (Paused) (Rule-5.1)"
}
check('label and draw (built from the clean drawLabel) are unaffected by statusInTitle and still show Paused') {
    assert r4a.label == "__Parent's test (Paused)"
    assert r4a.draw == "__Parent's test (Rule-5.1) (Paused)"
}

println '--- 4b. Unrelated hub context (not a Paused/Disabled duplicate) still survives in the title with statusInTitle=false ---'
// Proves 4a's fix does not come at the cost of 372's original concern:
// title is built from the untouched fullLabel regardless of statusInTitle,
// so genuinely different hub-injected context is never discarded either.
def r4b = nodeEntry('a4b', 'Christmas Cheer (Required Expression false)', 'app', 'Rule-5.1', 'Christmas Cheer', 'Paused', false)
check('unrelated hub-injected context is preserved in the title even though our own suffix is not appended there') {
    assert r4b.title == 'Christmas Cheer (Required Expression false) (Rule-5.1)'
}
check('name (export identity) is still built from the already-stripped drawLabel, not the hub-injected text') {
    assert r4b.name == 'Christmas Cheer (Rule-5.1)'
}

println '--- 5. No duplicate text: our own suffix is appended exactly once regardless of subtitle/drawLabel content ---'
def r5 = nodeEntry('a5', 'Some Rule', 'app', 'Rule Machine', 'Some Rule', 'Disabled')
check('exactly one "(Disabled)" in draw') {
    assert (r5.draw as String).count('(Disabled)') == 1
}
check('exactly one "(Disabled)" in title') {
    assert (r5.title as String).count('(Disabled)') == 1
}
check('exactly one "(Disabled)" in the truncation-protected label') {
    assert (r5.label as String).count('(Disabled)') == 1
}

println ''
println "${pass} passed, ${fail} failed"
System.exit(fail == 0 ? 0 : 1)
