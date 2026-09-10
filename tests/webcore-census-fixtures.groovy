#!/usr/bin/env groovy
//
// Increment 1 fixture integrity. The census walker does not exist yet
// (Increment 2), so this cannot assert walker output. What it CAN assert is
// that the fixture corpus stays honest: every fixture is valid, every one is
// declared in the manifest, none drifts out of the set silently, and the
// manifest does not claim a fixture that is missing.
//
// Deliberately not asserting per-member semantics - Codex 533/535 scoped these
// fixtures to proving the extractor and lookup model.

import groovy.json.JsonSlurper

int passed = 0
void check(boolean cond, String label) {
    if (!cond) { println "FAIL  ${label}"; System.exit 1 }
    println "PASS  ${label}"
}

File dir = new File('tests/fixtures/webcore-census')
check(dir.isDirectory(), 'fixture directory exists')
passed++

def slurper = new JsonSlurper()
Map manifest = slurper.parse(new File(dir, 'manifest.json')) as Map
List<Map> declared = manifest.fixtures as List

List<String> onDisk = dir.listFiles()
    .findAll { it.name.endsWith('.json') && it.name != 'manifest.json' }
    .collect { it.name }.sort()
List<String> inManifest = declared.collect { it.file as String }.sort()

check(onDisk == inManifest,
    "manifest and directory agree (disk ${onDisk.size()}, manifest ${inManifest.size()})")
passed++

// Every fixture parses and carries its own _proves line, so a file cannot sit
// in the corpus without stating why it is there.
declared.each { Map f ->
    File file = new File(dir, f.file as String)
    check(file.isFile(), "${f.file} exists")
    Map doc = slurper.parse(file) as Map
    check(f.proves != null && !(f.proves as String).trim().isEmpty(), "${f.file} states what it proves in the manifest")
    // Commentary inside the document is an unknown key to the walker, so it
    // would manufacture a gap in every positive fixture. It lives in the
    // manifest instead, and is not stripped before walking, because the input
    // has to be the document whose completeness is being claimed.
    check(doc.keySet().every { !"${it}".startsWith('_') }, "${f.file} carries no descriptive keys")
    check(doc.s instanceof List && (doc.s as List).size() > 0, "${f.file} carries a statement array")
    check(f.expectReasons instanceof List, "${f.file} declares an exact expected reason set")
    passed += 5
}

// The shared-branch fixture is the one whose whole point is a count, so assert
// it directly: five members must be present, because collapsing them to one
// construct is the specific error this fixture exists to catch.
Map numeric = slurper.parse(new File(dir, 'shared-branch-numeric.json')) as Map
Map numericTask = ((numeric.s as List)[0].k as List)[0] as Map
List items = (((numericTask.p as List)[0] as Map).exp as Map).i as List
check(items.size() == 5, 'shared-branch fixture carries all five numeric forms')
check(items.collect { it.t }.toSet() == ['integer', 'float', 'double', 'decimal', 'number'].toSet(),
    'shared-branch fixture members match expression.evaluate.result-type exactly')
passed += 2

// The multisite fixture must use the same spelling at two different sites, or
// it cannot prove context-qualified lookup.
Map multi = slurper.parse(new File(dir, 'same-spelling-multisite.json')) as Map
List stmts = multi.s as List
Set<String> hosts = stmts.collect { it.t as String } as Set
check(stmts.size() == 2 && hosts.size() == 2, 'multisite fixture uses two different host statements')
check(stmts.every { ((it.c as List)[0].lo as Map).t == 'p' }, 'multisite fixture uses the same spelling p at both')
passed += 2

// The empty-operand fixture must contain BOTH a present-but-empty t and an
// absent t, since the distinction is the thing being proven.
Map empty = slurper.parse(new File(dir, 'empty-operand.json')) as Map
List conds = (empty.s as List)[0].c as List
check(conds.any { (it.lo as Map).containsKey('t') && (it.lo as Map).t == '' }, 'empty fixture has a present-but-empty t')
check(conds.any { !(it.lo as Map).containsKey('t') }, 'empty fixture has an absent t for contrast')
passed += 2

// Site coverage. Codex 537 item 4: the README claimed one ordinary member per
// dispatch site while the manifest named only three site families. Increment 2
// narrowed the claim again: four sites are consumer-only and one is not
// reachable from a saved document at all, so the corpus is compared against the
// saved-reachable list rather than the frozen list.
List<String> frozenSites = manifest.frozenSites as List
check(frozenSites != null && frozenSites.size() == 13, 'manifest declares all thirteen frozen sites')
passed++

List<String> savedSites = manifest.savedReachableSites as List
check(savedSites != null && (savedSites as Set).every { frozenSites.contains(it) },
    'every saved-reachable site is one of the frozen sites')
check((frozenSites as Set) - (savedSites as Set) ==
        ['operand.subscribe.type', 'virtual-device.subscribe.name',
         'statement.subscribe.timer-type', 'expression.item.type'] as Set,
    'the excluded sites are exactly the consumer-only ones plus the runtime-only item site')
passed += 2

Map<String, Object> ordinary = declared.find { it.file == 'ordinary-members.json' } as Map
Map cov = ordinary.siteCoverage as Map
check(cov != null, 'ordinary-members declares per-site coverage')
check((cov.keySet() as Set) == (savedSites as Set),
    "every saved-reachable site has an ordinary member (covered ${cov?.size()}, reachable ${savedSites.size()})")
passed += 2

// Every expected construct id must carry the wc. namespace from the
// implementation map's fixed contract.
declared.each { Map f ->
    List ids = (f.expectConstructs ?: []) as List
    List bad = ids.findAll { !(it as String).startsWith('wc.') }
    check(bad.isEmpty(), "${f.file} expectations use the wc. namespace${bad ? ' (offenders: ' + bad + ')' : ''}")
    passed++
}

println "${passed} passed, 0 failed"
