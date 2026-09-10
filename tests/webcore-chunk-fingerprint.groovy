#!/usr/bin/env groovy
//
// Keeps the boundary-collision property proven while the fingerprint itself
// stays out of the request path. The endpoint ships uncached, so nothing on the
// hub computes this; if a cache is ever built, this is the implementation and
// these are its gates.
// Run with: groovy tests/webcore-chunk-fingerprint.groovy

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('..').canonicalFile

List<Boolean> results = []
def check = { boolean cond, String label ->
    println "${cond ? 'PASS' : 'FAIL'}  ${label}"
    results << cond
}

File helper = new File(repoRoot, 'tools/webcore-investigation/chunk-fingerprint.groovy')
check(helper.isFile(), 'the offline fingerprint reference is checked in')

Class fp = new GroovyClassLoader(this.class.classLoader).parseClass(helper)

Map twoChunks  = [appSettings: [[name: 'chunk:0', value: 'AAAA'], [name: 'chunk:1', value: 'BBBB']]]
Map shifted    = [appSettings: [[name: 'chunk:0', value: 'AAAAB'], [name: 'chunk:1', value: 'BBB']]]
Map reordered  = [appSettings: [[name: 'chunk:1', value: 'BBBB'], [name: 'chunk:0', value: 'AAAA']]]
Map changed    = [appSettings: [[name: 'chunk:0', value: 'AAAA'], [name: 'chunk:1', value: 'BBBC']]]
Map regrouped  = [appSettings: [[name: 'chunk:0', value: 'AAAABBBB']]]

String base = fp.of(twoChunks)
check(base != null && base ==~ /^[0-9a-f]{64}$/, 'a full SHA-256 hex digest')
check(base == fp.of(twoChunks), 'identical chunks produce the same fingerprint')
check(base != fp.of(shifted),
    'a different boundary over the same joined content changes the fingerprint')
check(base != fp.of(regrouped),
    'the same joined content in one chunk instead of two changes the fingerprint')
check(base == fp.of(reordered), 'payload order does not matter, only chunk index')
check(base != fp.of(changed), 'a content change changes the fingerprint')
check(fp.of([appSettings: []]) == null, 'a piston with no chunks has no fingerprint')

// The endpoint must not compute this. That is the decision, not an oversight.
String app = new File(repoRoot, 'apps/automation_map.groovy').getText('UTF-8')
check(!app.contains('webcoreChunkFingerprint') && !app.contains('fingerprinted'),
    'the shipped app does not compute a fingerprint on any request')
check(!app.contains("MessageDigest.getInstance('SHA-256')"),
    'no SHA-256 work sits in the request path')

int bad = results.count { !it }
println "${results.size() - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
