// Synthetic fixture for the v2.1.7 increment 2 hierarchy-edge rendering
// (Bucket/Queue 340/342/355). Modeled directly on Codex's live Dev fixture:
// parent device "Parentify" (3611) with children Anne/Bob/Cory/Dean/Emily
// (3612-3616) - acceptance is exactly five deterministic hasComponent edges,
// with no change to any other relationship kind.
// Run with: groovy tests/has-component-edges.groovy
//
// buildHasComponentEdges() below is copied verbatim from
// apps/automation_map.groovy - keep the two in sync by hand.

List buildHasComponentEdges(Set nodeIds, Map deviceParents) {
    List result = []
    deviceParents.each { childId, parentId ->
        String childNodeId = "d${childId}"
        String parentNodeId = "d${parentId}"
        if (!nodeIds.contains(childNodeId) || !nodeIds.contains(parentNodeId)) return
        result << [from: parentNodeId, to: childNodeId, kind: 'hasComponent']
    }
    return result
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

println '--- 1. Parentify fixture: exactly seven deterministic edges (live fixture, 355/359) ---'
// Anne/Bob/Cory/Dean/Emily (3612-3616) plus Felicity/Gary (3619/3620, added
// later, 359 - intentionally unused by any rule, added purely to prove
// discovery and this edge builder never gate on app usage).
Set parentifyNodes = ['d3611', 'd3612', 'd3613', 'd3614', 'd3615', 'd3616', 'd3619', 'd3620', 'a3060'] as Set
Map parentifyParents = ['3612': '3611', '3613': '3611', '3614': '3611', '3615': '3611', '3616': '3611',
                        '3619': '3611', '3620': '3611']
def r1 = buildHasComponentEdges(parentifyNodes, parentifyParents)
check('exactly seven edges') { assert r1.size() == 7 }
check('every edge is Parentify -> one child, kind hasComponent') {
    assert r1.every { it.from == 'd3611' && it.kind == 'hasComponent' }
}
check('the seven children are exactly Anne/Bob/Cory/Dean/Emily/Felicity/Gary\'s device ids') {
    assert (r1.collect { it.to } as Set) == (['d3612', 'd3613', 'd3614', 'd3615', 'd3616', 'd3619', 'd3620'] as Set)
}
check('Felicity and Gary get an edge exactly like every other child, despite being unused by any app') {
    assert r1.any { it.to == 'd3619' } && r1.any { it.to == 'd3620' }
}
check('the pre-existing a3060 -> d3611 owns edge (a different kind, built elsewhere) is untouched by this function - it never sees app-owns edges at all') {
    assert r1.every { it.from != 'a3060' && it.to != 'a3060' }
}

println '--- 2. No edge for a parent this scan never resolved to a node ---'
Set r2Nodes = ['d20'] as Set  // only the child is a known node, the parent id 10 never got one
def r2 = buildHasComponentEdges(r2Nodes, ['20': '10'])
check('missing parent node: no edge emitted') { assert r2.isEmpty() }

println '--- 3. No edge for a child this scan never resolved to a node ---'
Set r3Nodes = ['d10'] as Set  // only the parent is known
def r3 = buildHasComponentEdges(r3Nodes, ['20': '10'])
check('missing child node: no edge emitted') { assert r3.isEmpty() }

println '--- 4. Empty deviceParents produces no edges (ordinary flat devices, no hierarchy) ---'
def r4 = buildHasComponentEdges(['d1', 'd2'] as Set, [:])
check('no parent/child relationships at all: no edges') { assert r4.isEmpty() }

println '--- 5. Multiple independent parents, each with their own children ---'
Set r5Nodes = ['d30', 'd31', 'd32', 'd40', 'd41'] as Set
Map r5Parents = ['31': '30', '32': '30', '41': '40']
def r5 = buildHasComponentEdges(r5Nodes, r5Parents)
check('three edges total across two independent parents') { assert r5.size() == 3 }
check('first parent has two children') {
    assert r5.findAll { it.from == 'd30' }.collect { it.to }.sort() == ['d31', 'd32']
}
check('second parent has one child') {
    assert r5.findAll { it.from == 'd40' }*.to == ['d41']
}

println '--- 6. A device that is both a child and, separately, a parent of its own child (chain) ---'
// d31 is a child of d30 AND the parent of d32 - two distinct edges, not merged
// or dropped, and neither direction confused with the other.
Set r6Nodes = ['d30', 'd31', 'd32'] as Set
Map r6Parents = ['31': '30', '32': '31']
def r6 = buildHasComponentEdges(r6Nodes, r6Parents)
check('chain produces two edges, correctly directed') {
    assert r6.size() == 2
    assert r6.any { it.from == 'd30' && it.to == 'd31' }
    assert r6.any { it.from == 'd31' && it.to == 'd32' }
}

println ''
println "${pass} passed, ${fail} failed"
System.exit(fail == 0 ? 0 : 1)
