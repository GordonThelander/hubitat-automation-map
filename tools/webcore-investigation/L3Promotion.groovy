// Derives each statement family's evidence ceiling from committed metadata only: the evidence
// manifest, the fixture manifest and the named test files. It never runs a test. A family is L3
// when it cites at least one committed save whose matching round trip is also committed, both hold
// the statement and are inert and structurally valid, and every named test it declares exists.
// Every citation that does not hold is a problem, and the registry generator then emits nothing.
class L3Promotion {
    // countsField selects which per-fixture occurrence-count map a family is checked against
    // ('statements' for statement families, 'operands' for operand families sharing this same
    // gate through a caller-built evidence shim). The family list itself always comes from
    // evidence.statements, whatever the caller populates there.
    static Map derive(Map evidence, Map fixtureManifest, File repoRoot, String countsField = 'statements') {
        Map lineage = (evidence?.captureLineage ?: [:]) as Map
        Map named = (evidence?.namedTests ?: [:]) as Map
        Map byName = [:]
        ((fixtureManifest?.fixtures ?: []) as List).each { Object f ->
            byName[((f as Map).file as String).replaceFirst(/\.json$/, '')] = f
        }
        Map levels = [:]
        List problems = []
        ((evidence?.statements ?: [:]) as Map).each { Object rawId, Object rawEntry ->
            String id = rawId as String
            Map entry = rawEntry as Map
            List cited = (entry.fixtures ?: []) as List
            List tests = (entry.tests ?: []) as List
            int before = problems.size()
            cited.each { Object c ->
                String name = c as String
                Map save = byName[name] as Map
                if (save == null) { problems << "${id} cites ${name}, which is not a committed fixture".toString(); return }
                String roundTripKind = lineage[save.capture] as String
                if (roundTripKind == null || !name.endsWith('.' + save.capture)) {
                    problems << "${id} cites ${name}, which is not a save capture".toString(); return
                }
                String roundTripName = name.substring(0, name.length() - (save.capture as String).length()) + roundTripKind
                Map roundTrip = byName[roundTripName] as Map
                if (roundTrip == null) { problems << "${id} cites ${name}, which has no committed round trip ${roundTripName}".toString(); return }
                [save, roundTrip].each { Map f ->
                    Object n = ((f[countsField] ?: [:]) as Map)[id]
                    if (!(n instanceof Number) || (n as int) < 1) problems << "${id}: ${f.file} holds no occurrence of it".toString()
                    if (f.structurallyInvalid != 0 || f.inertAtCapture != true) problems << "${id}: ${f.file} is not an inert, structurally valid capture".toString()
                }
            }
            tests.each { Object t ->
                String path = named[t as String] as String
                if (path == null) problems << "${id} declares an unnamed test ${t}".toString()
                else if (!new File(repoRoot, path).isFile()) problems << "${id} declares test ${t}, but ${path} is missing".toString()
            }
            levels[id] = (cited && tests && problems.size() == before) ? 'L3' : 'L2'
        }
        return [levels: levels, problems: problems]
    }
}
