#!/usr/bin/env groovy
//
// Committed L3 fixtures must be structure-only. Every fixture document is scanned for device
// or mode hashes, identifiers, addresses, tokens, canaries, hub status fields and unreviewed
// key names. Negative controls prove the scan rejects each kind of leak.
// Run with: groovy tests/webcore-l3-fixture-privacy.groovy

import groovy.json.JsonSlurper

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('..').canonicalFile

List<Boolean> results = []
def check = { boolean cond, String label ->
    println "${cond ? 'PASS' : 'FAIL'}  ${label}"
    results << cond
}

String app = new File(repoRoot, 'apps/automation_map.groovy').getText('UTF-8')
def quoted = { String anchor -> String body = app.substring(app.indexOf(anchor), app.indexOf(']', app.indexOf(anchor))); (body =~ /'([^']+)'/).collect { it[1] } as Set }
Set reviewedKeys = quoted('List<String> webcoreCensusSchemaKeys() {') + quoted('List<String> webcoreCensusOpaqueKeys() {')

def scan = { String text ->
    List problems = []
    (text =~ /[0-9a-fA-F]{32}/).each { String m -> if (!m.startsWith('000000000000000000000000')) problems << 'hash' }
    if ((text =~ /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/).find()) problems << 'uuid'
    if ((text =~ /\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b/).find()) problems << 'address'
    if ((text =~ /(?i)https?:|access_token|token=/).find()) problems << 'url-or-token'
    if ((text =~ /(?i)canary/).find()) problems << 'canary'
    if ((text =~ /"(appState|eventSubscriptions|scheduledJobs|installedApp|appSettings|label)"\s*:/).find()) problems << 'hub-status-field'
    Closure keys
    keys = { Object node -> if (node instanceof Map) (node as Map).each { k, v -> if (!(reviewedKeys.contains(k.toString()) || k.toString() ==~ /^<key#\d+>$/)) problems << 'unreviewed-key'; keys(v) }
                            else if (node instanceof List) (node as List).each { keys(it) } }
    try { keys(new JsonSlurper().parseText(text)) } catch (Exception e) { problems << 'not-json' }
    problems.unique()
}

check(scan('{"s": [{"t": "if", "d": [":00000000000000000000000000000001:"], "c": "<string#1>", "<key#1>": 2}]}').isEmpty(),
    'a structure-only document passes the scan')
[['hash', '{"d": [":0123456789abcdef0123456789abcdef:"]}'],
 ['uuid', '{"id": "123e4567-e89b-12d3-a456-426614174000"}'],
 ['address', '{"c": "10.0.0.125"}'],
 ['url-or-token', '{"c": "http://hub/x?access_token=abc"}'],
 ['canary', '{"z": "L3CANARY-01"}'],
 ['hub-status-field', '{"appState": []}'],
 ['unreviewed-key', '{"secretKey": 1}']].each { List control ->
    check(scan(control[1] as String).contains(control[0]), "negative control: the scan rejects a ${control[0]}")
}

File dir = new File(repoRoot, 'tests/fixtures/webcore-l3')
List<File> fixtures = dir.isDirectory() ? dir.listFiles().findAll { it.name ==~ /.+\.(first-save|round-trip)\.json/ }.sort { it.name } : []
if (fixtures.isEmpty()) {
    println 'SKIP  no L3 fixtures are committed yet'
} else {
    fixtures.each { File f ->
        List problems = scan(f.getText('UTF-8'))
        check(problems.isEmpty(), "${f.name} is structure-only ${problems}")
    }
}

int bad = results.count { !it }
println "${results.size() - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
