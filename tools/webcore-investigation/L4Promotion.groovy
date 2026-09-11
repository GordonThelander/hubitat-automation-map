// Decides whether each L4 semantic claim meets its evidence contract, from committed data only: the
// semantic manifest, the registry's construct levels and region hashes, the L3 fixture manifest and
// capture lineage, and the hand-authored traces. It never runs the normaliser. A cited region whose
// hash differs from the registry withdraws only the claims that cite it.
class L4Promotion {
    static Map derive(Map manifest, Map registry, Map fixtureManifest, Map captureLineage, Map traces) {
        Map regionHashes = ((registry?.provenance as Map)?.regionHashes ?: [:]) as Map
        Map constructs = (registry?.constructs ?: [:]) as Map
        Map byName = [:]
        ((fixtureManifest?.fixtures ?: []) as List).each { Object f ->
            byName[((f as Map).file as String).replaceFirst(/\.json$/, '')] = f
        }
        Map saveKindOf = [:]
        ((captureLineage ?: [:]) as Map).each { Object save, Object roundTrip -> saveKindOf[roundTrip as String] = save as String }
        Map promoted = [:]
        Map problems = [:]
        ((manifest?.claims ?: []) as List).each { Object raw ->
            Map c = raw as Map
            String id = c.id as String
            List p = []
            List structural = (c.structural ?: []) as List
            if (!structural) p << 'structural construct'
            structural.each { Object s ->
                if (((constructs[s as String] ?: [:]) as Map).level != 'L3') p << "structure ${s} is not L3".toString()
            }
            if (!(c.meaning instanceof String) || !(c.meaning as String)) p << 'meaning'
            List docs = (c.docs ?: []) as List
            if (!docs || !docs.every { Object d -> (d as Map).page instanceof String && (d as Map).disposition instanceof String && ((d as Map).disposition as String) }) {
                p << 'documentation disposition'
            }
            List sources = (c.sources ?: []) as List
            if (!sources) p << 'source region'
            sources.each { Object s ->
                Map sm = s as Map
                if (regionHashes[sm.region] == null) { p << "unknown region ${sm.region}".toString() }
                else if (regionHashes[sm.region] != sm.sha256) { p << "region ${sm.region} drifted".toString() }
            }
            List fixtures = (c.fixtures ?: []) as List
            if (!fixtures) p << 'canonical fixture'
            fixtures.each { Object fo ->
                String n = fo as String
                Map f = byName[n] as Map
                if (f == null) { p << "fixture ${n} is not committed".toString(); return }
                String saveKind = saveKindOf[f.capture as String]
                String save = saveKind ? n.substring(0, n.length() - (f.capture as String).length()) + saveKind : null
                if (save == null || !byName.containsKey(save)) p << "fixture ${n} has no committed save in its lineage".toString()
                Map t = traces[n] as Map
                if (t == null) { p << "fixture ${n} has no trace".toString() }
                else if (!((t.occurrences ?: [:]) as Map).values().any { Object o -> (((o as Map).claims ?: []) as List).contains(id) }) {
                    p << "trace ${n} never reaches the claim".toString()
                }
            }
            List edges = (c.edges ?: []) as List
            if (!edges) p << 'edge occurrence'
            edges.each { Object e ->
                Map em = e as Map
                Map t = traces[em.fixture as String] as Map
                if (t == null || !((t.occurrences ?: [:]) as Map).containsKey(em.path)) p << "edge ${em.fixture} ${em.path} is not traced".toString()
            }
            if (!(c.negative instanceof String) || !(c.negative as String)) p << 'named negative'
            promoted[id] = p.isEmpty()
            problems[id] = p
        }
        return [promoted: promoted, problems: problems]
    }
}
