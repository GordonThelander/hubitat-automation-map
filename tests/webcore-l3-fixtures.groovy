#!/usr/bin/env groovy
//
// The committed L3 fixtures: every file the fixture manifest names exists with its recorded hash,
// was captured from an inert paused piston, and gives the walker exactly its recorded statement
// counts with every occurrence structurally valid. Together they cover all twelve statement types.
// When the raw captures are present outside the repository, regeneration is byte-identical.
// Run with: groovy tests/webcore-l3-fixtures.groovy

import groovy.json.JsonSlurper

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('..').canonicalFile

List<Boolean> results = []
def check = { boolean cond, String label ->
    println "${cond ? 'PASS' : 'FAIL'}  ${label}"
    results << cond
}

File dir = new File(repoRoot, 'tests/fixtures/webcore-l3')
Map fixtureManifest = new JsonSlurper().parse(new File(dir, 'manifest.json')) as Map
List<Map> fixtures = fixtureManifest.fixtures as List<Map>
def sanitiser = new GroovyClassLoader(this.class.classLoader)
        .parseClass(new File(repoRoot, 'tools/webcore-investigation/L3CaptureSanitiser.groovy'))
        .getConstructor(File).newInstance(repoRoot)
Map registry = sanitiser.support.webcoreCensusRegistry() as Map
Map shapes = sanitiser.support.webcoreStatementShapes() as Map
Map evidence = new GroovyShell().evaluate(new File(repoRoot, 'tools/webcore-investigation/evidence/statement-l3.groovy').getText('UTF-8')) as Map

Map sourcePin = fixtureManifest.sourcePin as Map
Map environment = fixtureManifest.environment as Map
check(sourcePin.commit == (evidence.provenance as Map).commit && sourcePin.manifest == 'tools/webcore-investigation/evidence/statement-l3.groovy',
    'the fixture manifest cites the evidence manifest and its pinned commit')
check(['editor', 'hostedIdeVersion', 'hubitatImplementation', 'captureDate', 'timeZone', 'workflow'].every { environment[it] instanceof String && environment[it] },
    'the observed editor environment is recorded in full')
check(fixtureManifest.sanitiserVersion == "L3CaptureSanitiser ${sanitiser.VERSION}".toString(), 'fixtures name the sanitiser version that produced them')

Set listed = fixtures.collect { it.file } as Set
Set present = dir.listFiles().findAll { it.name != 'manifest.json' }.collect { it.name } as Set
check(listed == present && listed.size() == fixtures.size(), "every fixture file is listed exactly once and nothing unlisted is present (${listed.size()})")
check(fixtures.every { (fixtureManifest.captureKinds as Map).containsKey(it.capture) && (it.file as String).endsWith(".${it.capture}.json") },
    'every fixture names a declared capture kind')

// The walker's runtime gaps must agree with the evidence table on real captures: the statement
// occurrences of each fixture take exactly the gap branches the table attributes to that fixture.
List gapDrift = []
fixtures.each { Map fx ->
    File f = new File(dir, fx.file as String)
    // Hashes are over LF text, so a CRLF checkout still verifies.
    String text = f.isFile() ? f.getText('UTF-8').replace('\r\n', '\n') : ''
    Map census = f.isFile() ? sanitiser.support.collectWebcoreDecodeCoverage(new JsonSlurper().parseText(text), registry, null, shapes) as Map : [:]
    Map statements = new TreeMap(((census.constructCounts ?: [:]) as Map).findAll { k, v -> "${k}".startsWith('wc.statement.') })
    int invalid = ((census.constructOccurrences ?: [:]) as Map).values().collect { (it as Map).structurallyInvalid as int }.sum(0)
    check(f.isFile() && sanitiser.sha256(text) == fx.sha256 && fx.activeAtCapture == false && fx.inertAtCapture == true &&
          statements == new TreeMap(fx.statements as Map) && invalid == 0 && fx.structurallyInvalid == 0 &&
          (census.structureFindings as List)?.isEmpty() && (census.unrecognised as List)?.size() == fx.unrecognised,
        "${fx.file}: hash, inert capture, statement counts ${fx.statements} all structurally valid")
    String name = (fx.file as String).replaceFirst(/\.json$/, '')
    Set walkerGaps = ((census.constructOccurrences ?: [:]) as Map).values().collectMany { (((it as Map).evidenceGaps ?: [:]) as Map).keySet() } as Set
    Set tableGaps = (evidence.branchEvidence as List).findAll { Map r ->
        r.gap != null && (((r.editorAuthored ?: []) as List) + ((r.canonicalOnly ?: []) as List)).contains(name)
    }.collect { Map r -> "${r.structure}/${r.key}/${r.branch}".toString() } as Set
    if (walkerGaps != tableGaps) gapDrift << "${name}: walker only ${walkerGaps - tableGaps}, table only ${tableGaps - walkerGaps}".toString()
}
check(gapDrift.isEmpty(), "on every fixture the walker records exactly the evidence gaps the table attributes to it ${gapDrift.take(3)}")

Set covered = fixtures.collectMany { ((it.statements ?: [:]) as Map).keySet().collect { k -> k.toString() } } as Set
check(covered == (evidence.statements as Map).keySet().collect { it.toString() } as Set, "the fixtures cover all twelve statement types ${covered.size()}")

File rawDir = new File(repoRoot.parentFile, '_captures/webcore-l3')
if (!rawDir.isDirectory()) {
    println 'SKIP  regeneration comparison: raw captures are not present outside the repository'
} else {
    List drift = []
    fixtures.each { Map fx ->
        String stem = (fx.file as String).replaceFirst(/^l3-/, 'zz-L3-').replaceFirst(/\.json$/, '.statusJson.json')
        File rawFile = new File(rawDir, stem)
        if (!rawFile.isFile()) { drift << "${fx.file} (raw missing)"; return }
        String regenerated = sanitiser.render(sanitiser.sanitiseCapture(new JsonSlurper().parse(rawFile) as Map))
        if (regenerated != new File(dir, fx.file as String).getText('UTF-8').replace('\r\n', '\n')) drift << fx.file
    }
    check(drift.isEmpty(), "regenerating every fixture from its raw capture is byte-identical ${drift}")
}

int bad = results.count { !it }
println "${results.size() - bad} passed, ${bad} failed"
if (bad > 0) System.exit(1)
