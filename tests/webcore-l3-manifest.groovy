#!/usr/bin/env groovy
//
// The structural (L3) evidence manifest must stay consistent with the registry, the
// walker and, when a pinned checkout is present, the source it cites. An inventory gate
// requires every key the reviewed regions assign, number or read to be in the manifest or
// explicitly excluded. A reference validator driven only by the manifest runs shape
// scenarios, and removing a discriminator, contextual condition or inventoried key is
// proven to fail a check.

File repoRoot = new File('.').canonicalFile
if (!new File(repoRoot, 'tests').isDirectory()) repoRoot = new File('..').canonicalFile

int passed = 0
int failed = 0
def check = { boolean cond, String label ->
    if (cond) { println "PASS  ${label}"; passed++ } else { println "FAIL  ${label}"; failed++ }
}

Map manifest = new GroovyShell().evaluate(new File(repoRoot, 'tools/webcore-investigation/evidence/statement-l3.groovy').getText('UTF-8')) as Map

String registryText = new File(repoRoot, 'tools/webcore-investigation/generated/webcore_construct_registry.groovy').getText('UTF-8')
String anchor = 'WEBCORE_CONSTRUCT_REGISTRY = '
Map registry = new GroovyShell().evaluate(registryText.substring(registryText.indexOf(anchor) + anchor.length())) as Map
Map regionHashes = (registry.provenance as Map).regionHashes as Map
Map constructs = registry.constructs as Map

String app = new File(repoRoot, 'apps/automation_map.groovy').getText('UTF-8')
int keysAt = app.indexOf('List<String> webcoreCensusSchemaKeys() {')
String keysBody = app.substring(keysAt, app.indexOf(']', keysAt))
Set allowlist = (keysBody =~ /'([^']+)'/).collect { it[1] } as Set

String beginMarker = '// --- webcore census walker: begin ---'
String walkerBlock = app.substring(app.indexOf(beginMarker) + beginMarker.length(), app.indexOf('// --- webcore census walker: end ---'))
def walker = new GroovyClassLoader(this.class.classLoader).parseClass("class WebcoreCensusRouting {\n" + walkerBlock + "\n}").newInstance()

// ---- provenance --------------------------------------------------------------

Map prov = manifest.provenance as Map
check(prov.commit == (registry.provenance as Map).commit, 'manifest cites the registry commit')
check(((registry.provenance as Map).sourcePaths as List).containsAll([prov.executorPath, prov.editorPath]),
    'registry provenance records both the executor and the editor source paths')
Map observed = manifest.observedEditorEnvironment as Map
check(!prov.containsKey('hostedEditorObserved') && observed?.ideVersion instanceof String && observed.ideVersion &&
      observed.containsKey('captureDate') && observed.workflow instanceof String,
    'the hosted editor is recorded as a separate observed environment, not as a source pin')
check((manifest.l3Agreement as List) == ['pinned-serializer-shape', 'pinned-runtime-consumer', 'sanitised-editor-occurrence'],
    'L3 requires the pinned serializer, the pinned runtime consumer and a sanitised editor occurrence to agree')

// ---- population ----------------------------------------------------------------

Set registryStatements = constructs.keySet().findAll { "${it}".startsWith('wc.statement.') }.collect { "${it}" } as Set
check((manifest.statements as Map).keySet() as Set == registryStatements,
    "the manifest covers exactly the registry statements (${registryStatements.size()})")

// ---- closed vocabulary and evidence ---------------------------------------------

List<Map> specs = []
(manifest.common as Map).each { k, v -> specs << [owner: 'common', key: k, spec: v] }
(manifest.statements as Map).each { id, e -> ((e as Map).keys as Map).each { k, v -> specs << [owner: id, key: k, spec: v] } }
(manifest.substructures as Map).each { String sub, Map s ->
    if (s.discriminator) {
        specs << [owner: sub, key: (s.discriminator as Map).key, spec: s.discriminator]
        (s.variants as Map).each { vn, keys -> (keys as Map).each { k, v -> specs << [owner: "${sub}.${vn}", key: k, spec: v] } }
    } else {
        (s.keys as Map).each { k, v -> specs << [owner: sub, key: k, spec: v] }
    }
}
def label = { Map s -> "${s.owner}.${s.key}" }

Set kinds = manifest.kinds as Set
Set contexts = manifest.contexts as Set
check(specs.every { kinds.contains((it.spec as Map).kind) }, 'every key has a kind from the closed set')
check(specs.every { (manifest.persistence as List).contains((it.spec as Map).persisted) }, 'every key has a closed persisted value')
check(specs.every { (manifest.consumption as List).contains((it.spec as Map).consumed) }, 'every key has a closed consumed value')

List whenBad = specs.findAll { Map s ->
    Map sp = s.spec as Map
    boolean isWhen = sp.persisted == 'when'
    isWhen != sp.containsKey('persistedWhen') || isWhen != sp.containsKey('outsideWhen') ||
        (isWhen && !(manifest.outsideWhen as List).contains(sp.outsideWhen))
}.collect(label)
check(whenBad.isEmpty(), "persistedWhen and outsideWhen appear exactly on when keys ${whenBad}")

Closure<Boolean> validPredicate
validPredicate = { Object p ->
    if (!(p instanceof Map)) return false
    Map m = p as Map
    Set forms = m.keySet().findAll { it in ['all', 'context', 'oneOf', 'noneOf'] } as Set
    if (forms.size() != 1) return false
    if (forms.contains('all')) return m.keySet() == ['all'] as Set && m.all instanceof List && (m.all as List) && (m.all as List).every { validPredicate(it) }
    if (forms.contains('context')) return m.keySet() == ['context'] as Set && m.context instanceof List && (m.context as List) && contexts.containsAll(m.context as List)
    String form = forms.iterator().next()
    return m.keySet() == ['key', form] as Set && m.key instanceof String && (m[form] as List) &&
        (m.key as String).tokenize('.').size() in [1, 2] && allowlist.containsAll((m.key as String).tokenize('.'))
}
List predBad = specs.findAll { Map s ->
    Map sp = s.spec as Map
    (sp.containsKey('persistedWhen') && !validPredicate(sp.persistedWhen)) || (sp.containsKey('consumedWhen') && !validPredicate(sp.consumedWhen))
}.collect(label)
check(predBad.isEmpty(), "every predicate takes one closed form over allowlisted keys and declared contexts ${predBad}")

List consumedWhenBad = specs.findAll { (it.spec as Map).containsKey('consumedWhen') && (it.spec as Map).consumed != 'read' }.collect(label)
check(consumedWhenBad.isEmpty(), "consumedWhen appears only on read keys ${consumedWhenBad}")

List consumptionBad = specs.findAll { Map s ->
    Map sp = s.spec as Map
    switch (sp.consumed) {
        case 'read': return !sp.readBy
        case 'not-cited': return sp.readBy as boolean
        case 'replaced-on-load': return !sp.normalisedBy
        default: return true
    }
}.collect(label)
check(consumptionBad.isEmpty(), "read keys cite a reader, not-cited keys cite none, replaced keys cite the replacing region ${consumptionBad}")

List notAllowed = specs.findAll { !allowlist.contains(it.key) }.collect(label)
check(notAllowed.isEmpty(), "every manifest key is a walker allowlist key ${notAllowed}")

List dangling = []
specs.each { Map s ->
    ['readBy', 'writtenBy', 'normalisedBy'].each { String field ->
        ((s.spec as Map)[field] ?: []).each { String region -> if (!regionHashes.containsKey(region)) dangling << "${label(s)} ${field} ${region}" }
    }
}
(manifest.sourceAssertions as List).each { Map a -> if (a.region && !regionHashes.containsKey(a.region)) dangling << "sourceAssertion ${a.region}" }
(manifest.inventory as List).each { Map e -> if (!regionHashes.containsKey(e.region)) dangling << "inventory ${e.region}" }
(manifest.roundTrip as List).each { Map e -> if (!regionHashes.containsKey(e.region)) dangling << "roundTrip ${e.region}" }
check(dangling.isEmpty(), "every cited region is a registry region hash ${dangling.take(5)}")

List unsourced = specs.findAll { Map s -> !((s.spec as Map).readBy) && !((s.spec as Map).writtenBy) && !((s.spec as Map).normalisedBy) }.collect(label)
check(unsourced.isEmpty(), "no key is claimed without any source region ${unsourced}")

Map lists = manifest.lists as Map
check(lists.every { k, v -> kinds.contains(k) && (manifest.substructures as Map).containsKey((v as Map).element) },
    'every validated list kind names a substructure')
Map condList = lists['condition-list'] as Map
check(condList.ownerKey == 'o' && condList.ownerOneOf == ['followed by'] &&
      contexts.containsAll([condList.first, condList.rest, condList.otherwise]),
    'a condition list derives each element context from its owner operator')
check(kinds.contains('restriction-list') && !lists.containsKey('restriction-list'),
    'restriction-list members are outside this increment: only the container kind is validated')

List badAssertions = (manifest.sourceAssertions as List).findAll { Map a ->
    int forms = ['contains', 'pattern', 'excludes'].count { a.containsKey(it) }
    boolean regionForm = a.region instanceof String && forms == 1 && !a.containsKey('constant') && !a.containsKey('constantValueAbsent')
    boolean constantForm = a.constant instanceof String && a.value instanceof String && forms == 0 && !a.containsKey('region') && !a.containsKey('constantValueAbsent')
    boolean absentForm = a.constantValueAbsent instanceof String && forms == 0 && !a.containsKey('region') && !a.containsKey('constant')
    !(a.supports instanceof String && a.supports) || !(regionForm || constantForm || absentForm)
}
check(badAssertions.isEmpty(), "every source assertion takes one closed form and says what it supports ${badAssertions.size()}")

// ---- positions ---------------------------------------------------------------------

Map st = manifest.statements as Map
Map subs = manifest.substructures as Map
Map variants = (subs.condition as Map).variants as Map
def specFor = { Map m, String structure, String key ->
    Map ss = m.substructures as Map
    if (structure == 'statement') return (m.common as Map)[key] as Map
    if (structure in ['condition', 'group']) return ((((ss.condition as Map).variants as Map)[structure]) as Map)[key] as Map
    return ((ss[structure] as Map).keys as Map)[key] as Map
}
check(((st['wc.statement.on'] as Map).keys as Map).c.kind == 'event-list', "on's c is an event list")
check(['if', 'while', 'repeat'].every { (((st["wc.statement.${it}"] as Map).keys as Map).c as Map).kind == 'condition-list' },
    'if, while and repeat hold conditions in c')
check(['for', 'every'].every { (((st["wc.statement.${it}"] as Map).keys as Map).lo2 as Map).kind == 'operand' },
    'lo2 is an operand under for and every')
check(!['if', 'while', 'switch', 'each'].any { ((st["wc.statement.${it}"] as Map).keys as Map).containsKey('lo2') },
    'no other statement claims lo2')
check(((subs.condition as Map).discriminator as Map).key == 't' && variants.keySet() == ['condition', 'group'] as Set,
    'conditions are discriminated by t into leaf and group variants')
check(!(manifest.common as Map).containsKey('wd') && variants.every { vn, keys ->
        Map wd = (keys as Map).wd as Map
        wd.persisted == 'when' && wd.persistedWhen == [context: ['followed-by-later-step']] && wd.consumedWhen == [context: ['followed-by-later-step']]
    }, 'wd is persisted and consumed only on a later followed-by step, never as a statement key')
check(['statement', 'elseif', 'case', 'task', 'event', 'condition', 'group'].every {
        Map sp = specFor(manifest, it, '$')
        sp?.persisted == 'round-trip' && sp.consumed == 'replaced-on-load' && (sp.normalisedBy as List).contains('executor.clear-ids')
    }, 'every numbered structure carries $ as a hub-written number replaced on load')
check(!specFor(manifest, 'case', '$').readBy && !specFor(manifest, 'event', '$').readBy,
    'no cited region reads a case or event number')
check(specFor(manifest, 'event', 't')?.persisted == 'always' && specFor(manifest, 'event', 't').values == ['event'],
    "an event's t is always persisted with the single value event")
Map cmSpec = specFor(manifest, 'task', 'cm')
check(cmSpec?.persisted == 'unless-empty' && cmSpec.values == [true] && cmSpec.consumed == 'not-cited',
    'task cm is a true-only flag that no cited region reads')
check(specFor(manifest, 'task', 'm')?.kind == 'scalar-list', 'task m is a list of mode ids, never a device list')
int taskCase = app.indexOf("case 'task':", app.indexOf('String webcoreCensusChildContext('))
check(taskCase > 0 && !app.substring(taskCase, app.indexOf('return null', taskCase)).contains("'m'"),
    'the walker gives task m no device-list or operand context')

// ---- statement promotion -------------------------------------------------------------

File fixtureDir = new File(repoRoot, 'tests/fixtures/webcore-l3')
Map fixtureManifest = new groovy.json.JsonSlurper().parse(new File(fixtureDir, 'manifest.json')) as Map
Map fixtureMeta = [:]
Map fixtureDocs = [:]
(fixtureManifest.fixtures as List).each { Map f ->
    String name = (f.file as String).replaceFirst(/\.json$/, '')
    fixtureMeta[name] = f
    fixtureDocs[name] = new groovy.json.JsonSlurper().parseText(new File(fixtureDir, f.file as String).getText('UTF-8'))
}
Map lineage = manifest.captureLineage as Map
check(manifest.captureKinds == ['first-save', 'round-trip', 'edit-save', 'edit-round-trip'] &&
      (fixtureManifest.captureKinds as Map).keySet() == (manifest.captureKinds as Set) &&
      lineage == ['first-save': 'round-trip', 'edit-save': 'edit-round-trip'],
    'the manifest declares the four committed capture kinds and pairs each save with its round trip')
def roundTripOf = { String name ->
    Map f = fixtureMeta[name] as Map
    (f && lineage[f.capture]) ? name.substring(0, name.length() - (f.capture as String).length()) + lineage[f.capture] : null
}
def holds = { String name, String id -> (((((fixtureMeta[name] as Map)?.statements ?: [:]) as Map)[id] ?: 0) as int) > 0 }
Map derivedStatementFixtures = st.collectEntries { id, e ->
    [(id): fixtureMeta.keySet().findAll { String n -> roundTripOf(n) && fixtureMeta.containsKey(roundTripOf(n)) && holds(n, id as String) && holds(roundTripOf(n), id as String) }.sort()]
}
List citationDrift = st.findAll { id, e -> ((e as Map).fixtures as List) != derivedStatementFixtures[id] }.keySet().collect { "${it}" }
check(citationDrift.isEmpty(), "every statement cites exactly the committed saves whose round trip also holds it ${citationDrift}")
check(st.every { id, e -> ((e as Map).tests as List) && ((e as Map).tests as List).every { (manifest.namedTests as Map).containsKey(it) } } &&
      (manifest.namedTests as Map).values().every { new File(repoRoot, it as String).isFile() },
    'every statement declares named tests, and every named test exists')

Class promotionClass = new GroovyClassLoader(this.class.classLoader).parseClass(new File(repoRoot, 'tools/webcore-investigation/L3Promotion.groovy'))
Map promotion = promotionClass.derive(manifest, fixtureManifest, repoRoot) as Map
check((promotion.problems as List).isEmpty() && (promotion.levels as Map).keySet() == st.keySet() && (promotion.levels as Map).values().every { it == 'L3' },
    "promotion derives L3 for every statement from committed metadata alone ${promotion.problems}")
List registryDrift = registryStatements.findAll { (constructs[it] as Map).level != (promotion.levels as Map)[it] }.toList()
check(registryDrift.isEmpty(), "the registry carries exactly the promoted statement levels ${registryDrift}")

def copyJson = { Object o -> new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(o)) }
def promoteMutant = { Closure mutate ->
    Map ev = copyJson(manifest) as Map
    Map fm = copyJson(fixtureManifest) as Map
    mutate(ev, fm)
    promotionClass.derive(ev, fm, repoRoot) as Map
}
[['a citation naming a round-trip capture', { Map ev, Map fm -> ((ev.statements as Map)['wc.statement.every'] as Map).fixtures = ['l3-06-timers.round-trip'] }],
 ['a cited save without its committed round trip', { Map ev, Map fm -> fm.fixtures = (fm.fixtures as List).findAll { it.file != 'l3-06-timers.round-trip.json' } }],
 ['a round trip that no longer holds the statement', { Map ev, Map fm -> ((fm.fixtures as List).find { it.file == 'l3-06-timers.round-trip.json' } as Map).statements = [:] }],
 ['a citation of a fixture that is not committed', { Map ev, Map fm -> ((ev.statements as Map)['wc.statement.every'] as Map).fixtures = ['l3-09-missing.first-save'] }],
 ['a named test that does not exist', { Map ev, Map fm -> (ev.namedTests as Map)['census-walker'] = 'tests/no-such-test.groovy' }]].each { List mc ->
    Map out = promoteMutant(mc[1] as Closure)
    check((out.problems as List) && (out.levels as Map)['wc.statement.every'] == 'L2', "promotion mutation: ${mc[0]} fails and leaves the family at L2")
}
Map uncited = promoteMutant { Map ev, Map fm -> ((ev.statements as Map)['wc.statement.every'] as Map).fixtures = [] }
check((uncited.problems as List).isEmpty() && (uncited.levels as Map)['wc.statement.every'] == 'L2', 'a family that cites no save stays L2 without a problem')

// ---- exhaustiveness: inventory, exclusions, regions and reconciliation --------------

Set knownStructures = ['statement', 'elseif', 'case', 'task', 'event', 'condition', 'group', 'restriction'] as Set
def manifestKeysFor = { Map m, String structure ->
    Map ss = m.substructures as Map
    switch (structure) {
        case 'statement':
            return ((m.common as Map).keySet() + (m.statements as Map).values().collectMany { ((it as Map).keys as Map).keySet() as List }) as Set
        case 'condition':
        case 'group':
            Map c = ss.condition as Map
            if (c.discriminator) return (((((c.variants as Map)[structure]) as Map)?.keySet() ?: []) + [(c.discriminator as Map).key]) as Set
            return (((c.keys as Map)?.keySet()) ?: []) as Set
        case 'restriction':
            return [] as Set
        default:
            return (((((ss[structure] as Map)?.keys) as Map)?.keySet()) ?: []) as Set
    }
}
def entryKeys = { Map e -> (((e.assigns ?: []) as List) + ((e.literals ?: []) as List) + ((e.reads ?: []) as List)).unique() }
def covered = { Map m, String structure, String key ->
    manifestKeysFor(m, structure).contains(key) ||
        ((m.exclusions ?: []) as List).any { Map x -> x.key == key && (x.structures as List).contains(structure) }
}
def completenessGaps = { Map m ->
    List gaps = []
    (m.inventory as List).each { Map e ->
        List structures = e.structures as List
        entryKeys(e).each { String k ->
            boolean each = e.assignsTo == 'each' && ((e.assigns ?: []) as List).contains(k)
            boolean ok = each ? structures.every { covered(m, it as String, k) } : structures.any { covered(m, it as String, k) }
            if (!ok) gaps << "${e.region} ${e.receiver}.${k}"
        }
    }
    gaps.unique()
}

List inventoryBad = (manifest.inventory as List).findAll { Map e ->
    !(e.receiver instanceof String) || !(e.structures as List) || !knownStructures.containsAll(e.structures as List) ||
        !['assigns', 'literals', 'reads'].every { e[it] instanceof List } || (e.assignsTo != null && e.assignsTo != 'each') ||
        (!(e.region as String).startsWith('editor.') && (e.literals as List))
}.collect { "${it.region} ${it.receiver}" }
check(inventoryBad.isEmpty(), "every inventory entry names a receiver, known structures and literal key lists ${inventoryBad}")
List gaps = completenessGaps(manifest)
check(gaps.isEmpty(), "every inventoried key is in the manifest or explicitly excluded ${gaps}")

List exclusionBad = (manifest.exclusions as List).findAll { Map x ->
    !(manifest.exclusionReasons as List).contains(x.reason) || !(x.key instanceof String) || !(x.structures as List) ||
        !knownStructures.containsAll(x.structures as List) ||
        (x.structures as List).any { manifestKeysFor(manifest, it as String).contains(x.key) } ||
        !(manifest.inventory as List).any { Map e -> (e.structures as List).any { (x.structures as List).contains(it) } && entryKeys(e).contains(x.key) }
}.collect { "${it.structures}.${it.key}" }
check(exclusionBad.isEmpty(), "every exclusion has a closed reason, is used by the inventory and contradicts no manifest key ${exclusionBad}")

Set inventoried = (manifest.inventory as List).collect { it.region } as Set
Map uninventoried = manifest.uninventoriedRegions as Map
List regionCoverageBad = regionHashes.keySet().findAll { !(inventoried.contains(it) ^ uninventoried.containsKey(it)) }.toList() +
    uninventoried.findAll { k, v -> !regionHashes.containsKey(k) || !(manifest.uninventoriedReasons as List).contains(v) }.keySet().toList()
check(regionCoverageBad.isEmpty(), "every registry region is inventoried or uninventoried for a fixed reason, never both ${regionCoverageBad}")

String positionMap = new File(repoRoot, 'tools/webcore-investigation/saved-position-map.md').getText('UTF-8')
int allowSection = positionMap.indexOf('## 5. Path key allowlist')
int fenceOpen = positionMap.indexOf('```', allowSection)
String fenced = positionMap.substring(positionMap.indexOf('\n', fenceOpen) + 1, positionMap.indexOf('```', fenceOpen + 3))
check(allowSection > 0 && (fenced.split(/\s+/).findAll { it } as Set) == allowlist,
    'saved-position-map.md lists exactly the walker allowlist')

Map kindRoutes = ['statement-list': ['statement'], 'condition-list': ['condition', 'followed-by-step'], 'event-list': ['event'],
                  'elseif-list': ['elseif'], 'case-list': ['case'], 'task-list': ['task'], 'restriction-list': ['restriction'],
                  'operand': ['operand', 'event-operand'], 'device-list': ['device-list'], 'operand-list': ['param'],
                  'scalar': [null], 'scalar-list': [null]]
List routing = []
def reconcile = { String owner, Map node, String ctx, Map keys ->
    keys.each { String k, Map spec ->
        String got = walker.webcoreCensusChildContext(node, ctx, k)
        List expected = kindRoutes[spec.kind] as List
        boolean ok = spec.consumedWhen ? (got == null || expected.contains(got)) : expected.contains(got)
        if (!ok) routing << "${owner}.${k} kind ${spec.kind} walker ${got}".toString()
    }
}
st.each { String id, Map e -> String t = id - 'wc.statement.'; reconcile(t, [t: t], 'statement', (manifest.common as Map) + (e.keys as Map)) }
['elseif', 'case', 'event', 'task'].each { String s -> reconcile(s, [:], s, (subs[s] as Map).keys as Map) }
Map discriminator = [t: (subs.condition as Map).discriminator]
reconcile('condition', [t: 'condition'], 'condition', (variants.condition as Map) + discriminator)
reconcile('group', [t: 'group'], 'condition', (variants.group as Map) + discriminator)
['followed-by-first-step', 'followed-by-later-step'].each { String ctx ->
    reconcile("${ctx}.condition".toString(), [t: 'condition'], ctx, variants.condition as Map)
    reconcile("${ctx}.group".toString(), [t: 'group'], ctx, variants.group as Map)
}
check(routing.sort() == ((manifest.walkerRoutingDifferences ?: []) as List).sort(),
    "the walker routes every manifest key by its kind, apart from the reviewed differences ${routing}")

// ---- reference validator, driven only by the manifest -------------------------------

def resolvePath = { Map node, String path ->
    Object cur = node
    for (String seg : path.tokenize('.')) { if (!(cur instanceof Map)) return null; cur = (cur as Map)[seg] }
    return cur
}
Closure<Boolean> evalPredicate
evalPredicate = { Map p, Map node, String ctx ->
    if (p.containsKey('all')) return (p.all as List).every { evalPredicate(it as Map, node, ctx) }
    if (p.containsKey('context')) return (p.context as List).contains(ctx)
    Object v = resolvePath(node, p.key as String)
    if (p.containsKey('oneOf')) return (p.oneOf as List).contains(v)
    return !(p.noneOf as List).contains(v)
}
def isScalar = { Object v -> v instanceof String || v instanceof Number || v instanceof Boolean }
def kindOk = { String kind, Object v ->
    switch (kind) {
        case 'scalar': return isScalar(v)
        case 'scalar-list': return v instanceof List && (v as List).every { isScalar(it) }
        case 'operand': return v instanceof Map
        case 'operand-list': return v instanceof List && (v as List).every { it instanceof Map }
        default: return v instanceof List
    }
}
def isPruned = { Object v -> v == null || (v instanceof Boolean && !(v as Boolean)) || (v instanceof String && (v as String).isEmpty()) }
def isConsumed = { Map spec, Map node, String ctx -> spec.consumed == 'read' && (!spec.consumedWhen || evalPredicate(spec.consumedWhen as Map, node, ctx)) }

Closure validateSub
Closure validateNode = { Map m, Map keySpecs, Set foreign, Map node, String ctx, String path, List out ->
    keySpecs.each { String key, Map spec ->
        String at = "${path}.${key}"
        boolean present = node.containsKey(key)
        Object v = node[key]
        if (spec.persisted == 'when' && !evalPredicate(spec.persistedWhen as Map, node, ctx)) {
            if (!present) return
            if (spec.outsideWhen != 'retained-unconsumed') { out << [code: 'outside-condition', path: at]; return }
            String code = !kindOk(spec.kind as String, v) ? 'wrong-kind' :
                ((spec.values != null && !(spec.values as List).contains(v)) ? 'bad-value' : 'retained-unconsumed')
            out << [code: code, path: at]
            return
        }
        if (!present) {
            if (spec.persisted in ['always', 'when']) out << [code: isConsumed(spec, node, ctx) || spec.consumed != 'read' ? 'missing-key' : 'missing-unconsumed-key', path: at]
            return
        }
        if (spec.persisted == 'never') { out << [code: 'never-persisted', path: at]; return }
        if (spec.persisted == 'unless-empty' && isPruned(v)) { out << [code: 'empty-persisted', path: at]; return }
        if (!kindOk(spec.kind as String, v)) { out << [code: 'wrong-kind', path: at]; return }
        if (spec.values != null && !(spec.values as List).contains(v)) { out << [code: 'bad-value', path: at]; return }
        Map list = (m.lists as Map)[spec.kind] as Map
        if (list == null) return
        (v as List).eachWithIndex { Object el, int i ->
            String elAt = "${at}[${i}]"
            if (!(el instanceof Map)) { out << [code: 'wrong-kind', path: elAt]; return }
            String elCtx = null
            if (list.ownerKey) elCtx = (list.ownerOneOf as List).contains(node[list.ownerKey]) ? (i == 0 ? list.first : list.rest) : list.otherwise
            validateSub(m, list.element as String, el as Map, elCtx, elAt, out)
        }
    }
    node.keySet().each { k -> if (foreign.contains(k)) out << [code: 'variant-foreign-key', path: "${path}.${k}"] }
}
validateSub = { Map m, String name, Map node, String ctx, String path, List out ->
    Map sub = (m.substructures as Map)[name] as Map
    if (!sub.discriminator) { validateNode(m, sub.keys as Map, [] as Set, node, ctx, path, out); return }
    String dk = (sub.discriminator as Map).key
    if (!node.containsKey(dk)) { out << [code: 'missing-discriminator', path: "${path}.${dk}"]; return }
    Map variant = (sub.variants as Map)[node[dk]] as Map
    if (variant == null) { out << [code: 'unknown-variant', path: "${path}.${dk}"]; return }
    Set foreign = ((sub.variants as Map).findAll { k, keys -> k != node[dk] }.collectMany { k, keys -> (keys as Map).keySet() as List } as Set) - variant.keySet()
    validateNode(m, variant, foreign, node, ctx, path, out)
}
def validateStatement = { Map m, Map node ->
    List out = []
    Map entry = (m.statements as Map)["wc.statement.${node.t}"] as Map
    if (entry == null) return [[code: 'unknown-statement', path: '$.t']]
    validateNode(m, (m.common as Map) + (entry.keys as Map), [] as Set, node, null, '$', out)
    return out
}

def wdOp = { [t: 'c', c: 1, vt: 'm'] }
def leaf = { Map extra = [:] -> [t: 'condition', lo: [t: 'p'], co: 'is', ro: [t: 'c'], ro2: [t: 'c'], to: [t: 'c', c: 0], to2: [t: 'c', c: 0], ts: [], fs: [], sm: 'auto'] + extra }
def without = { Map node, String key -> node.findAll { k, v -> k != key } }
def group = { List c, Map extra = [:] -> [t: 'group', c: c, o: 'and', ts: [], fs: [], sm: 'auto'] + extra }
def evt = { Map extra = [:] -> [t: 'event', lo: [t: 'p'], sm: 'auto'] + extra }
def stmt = { String t, Map extra -> [t: t, a: '0', r: [], rop: 'and'] + extra }
def ifStmt = { List c, Map extra = [:] -> stmt('if', [o: 'and', c: c, s: [], ei: [], e: []] + extra) }
def followed = { List c -> ifStmt(c, [o: 'followed by']) }
def onStmt = { List c -> stmt('on', [a: '1', c: c, o: 'or', s: []]) }
def action = { List k -> stmt('action', [d: [':d1:'], k: k]) }
def task = { Map extra = [:] -> [c: 'on', p: []] + extra }
def switchStmt = { List cs, Map extra = [:] -> stmt('switch', [lo: [t: 'c'], cs: cs, e: [], ctp: 'i'] + extra) }
def every = { String unit, Map extra = [:] -> stmt('every', [a: '1', lo: [t: 'c', c: 1, vt: unit], lo2: [t: 'c', c: 0], lo3: [t: 'c', c: 0, vt: 'm'], s: []] + extra) }
def everySpec = { Map m, String key -> ((((m.statements as Map)['wc.statement.every'] as Map).keys as Map)[key]) as Map }

List<Map> scenarios = [
    [name: 'every statement type in its current-editor shape is valid', expect: [], run: { Map m -> [
        action([task()]), ifStmt([leaf()]), stmt('while', [o: 'and', c: [leaf()], s: []]), stmt('repeat', [o: 'and', c: [leaf()], s: []]),
        every('d'), onStmt([evt()]), stmt('each', [x: 'dev', lo: [t: 'd'], s: []]),
        stmt('for', [lo: [t: 'c'], lo2: [t: 'c'], lo3: [t: 'c'], s: []]), switchStmt([]), stmt('do', [s: []]), stmt('break', [:]), stmt('exit', [lo: [t: 'c']])
    ].collectMany { validateStatement(m, it) } }],
    [name: 'a group holding a leaf is valid', expect: [], run: { Map m -> validateStatement(m, ifStmt([group([leaf()])])) }],
    [name: 'a condition without t', expect: ['missing-discriminator'], run: { Map m -> validateStatement(m, ifStmt([without(leaf(), 't')])) }],
    [name: 'a condition with an unknown t', expect: ['unknown-variant'], run: { Map m -> validateStatement(m, ifStmt([leaf([t: 'bogus'])])) }],
    [name: 'a group carrying a leaf-only key', expect: ['variant-foreign-key'], run: { Map m -> validateStatement(m, ifStmt([group([leaf()], [lo: [t: 'p']])])) }],
    [name: 'a leaf carrying a group-only key', expect: ['variant-foreign-key'], run: { Map m -> validateStatement(m, ifStmt([leaf([c: []])])) }],
    [name: 'a condition without sm', expect: ['missing-key'], run: { Map m -> validateStatement(m, ifStmt([without(leaf(), 'sm')])) }],
    [name: 'a later followed-by step with wd and wt', expect: [], run: { Map m -> validateStatement(m, followed([leaf(), leaf([wd: wdOp(), wt: 'l'])])) }],
    [name: 'a later followed-by step missing wd', expect: ['missing-key'], run: { Map m -> validateStatement(m, followed([leaf(), leaf([wt: 'l'])])) }],
    [name: 'a later followed-by step missing wt', expect: ['missing-key'], run: { Map m -> validateStatement(m, followed([leaf(), leaf([wd: wdOp()])])) }],
    [name: 'a later followed-by step whose wd is not an operand', expect: ['wrong-kind'], run: { Map m -> validateStatement(m, followed([leaf(), leaf([wd: 'x', wt: 'l'])])) }],
    [name: 'wd on an ordinary condition is retained and not read as an operand', expect: ['retained-unconsumed'], run: { Map m -> validateStatement(m, ifStmt([leaf([wd: wdOp()])])) }],
    [name: 'a malformed retained wd still fails its container kind', expect: ['wrong-kind'], run: { Map m -> validateStatement(m, ifStmt([leaf([wd: 'not-an-operand'])])) }],
    [name: 'a retained wt outside its value set still fails', expect: ['bad-value'], run: { Map m -> validateStatement(m, ifStmt([leaf([wt: 'x'])])) }],
    [name: 'wt alone on an ordinary condition is retained', expect: ['retained-unconsumed'], run: { Map m -> validateStatement(m, ifStmt([leaf([wt: 'l'])])) }],
    [name: 'wd on a condition inside an and group is retained', expect: ['retained-unconsumed'], run: { Map m -> validateStatement(m, ifStmt([group([leaf([wd: wdOp()])])])) }],
    [name: 'wd on an and group itself is retained', expect: ['retained-unconsumed'], run: { Map m -> validateStatement(m, ifStmt([group([leaf()], [wd: wdOp()])])) }],
    [name: 'wt alone on an and group itself is retained', expect: ['retained-unconsumed'], run: { Map m -> validateStatement(m, ifStmt([group([leaf()], [wt: 'l'])])) }],
    [name: 'the first followed-by step keeps no wd', expect: ['retained-unconsumed'], run: { Map m -> validateStatement(m, followed([leaf([wd: wdOp()]), leaf([wd: wdOp(), wt: 'l'])])) }],
    [name: 'a group as a later followed-by step', expect: [], run: { Map m -> validateStatement(m, followed([leaf(), group([leaf()], [wd: wdOp(), wt: 'l'])])) }],
    [name: 'a group as a later followed-by step missing wt', expect: ['missing-key'], run: { Map m -> validateStatement(m, followed([leaf(), group([leaf()], [wd: wdOp()])])) }],
    [name: 'a followed-by group gives its own children step context', expect: ['missing-key'], run: { Map m -> validateStatement(m, ifStmt([group([leaf(), leaf()], [o: 'followed by'])])) }],
    [name: 'an else-if as the editor saves it', expect: [], run: { Map m -> validateStatement(m, ifStmt([leaf()], [ei: [[o: 'and', c: [leaf()], s: []]]])) }],
    [name: 'a negated else-if', expect: [], run: { Map m -> validateStatement(m, ifStmt([leaf()], [ei: [[o: 'or', n: true, c: [leaf()], s: []]]])) }],
    [name: 'an else-if missing o', expect: ['missing-key'], run: { Map m -> validateStatement(m, ifStmt([leaf()], [ei: [[c: [leaf()], s: []]]])) }],
    [name: 'an else-if keeping n false, which the serializer deletes', expect: ['empty-persisted'], run: { Map m -> validateStatement(m, ifStmt([leaf()], [ei: [[o: 'and', n: false, c: [leaf()], s: []]]])) }],
    [name: 'an if keeping n false', expect: ['empty-persisted'], run: { Map m -> validateStatement(m, ifStmt([leaf()], [n: false])) }],
    [name: 'an on statement carrying n', expect: ['never-persisted'], run: { Map m -> validateStatement(m, stmt('on', [c: [], o: 'or', n: true, s: []])) }],
    [name: 'an event without t', expect: ['missing-key'], run: { Map m -> validateStatement(m, onStmt([without(evt(), 't')])) }],
    [name: 'an event with another t', expect: ['bad-value'], run: { Map m -> validateStatement(m, onStmt([evt([t: 'condition'])])) }],
    [name: 'a range case with ro2', expect: [], run: { Map m -> validateStatement(m, switchStmt([[t: 'r', ro: [t: 'c'], ro2: [t: 'c'], s: []]])) }],
    [name: 'a range case missing ro2', expect: ['missing-key'], run: { Map m -> validateStatement(m, switchStmt([[t: 'r', ro: [t: 'c'], s: []]])) }],
    [name: 'a single-value case missing ro2, which the runtime does not read', expect: ['missing-unconsumed-key'], run: { Map m -> validateStatement(m, switchStmt([[t: 's', ro: [t: 'c'], s: []]])) }],
    [name: 'a case with an unknown type', expect: ['bad-value'], run: { Map m -> validateStatement(m, switchStmt([[t: 'x', ro: [t: 'c'], ro2: [t: 'c'], s: []]])) }],
    [name: 'task m as selected mode ids', expect: [], run: { Map m -> validateStatement(m, action([task([m: [':m1:', ':m2:']])])) }],
    [name: 'task m absent', expect: [], run: { Map m -> validateStatement(m, action([task()])) }],
    [name: 'task m as an empty list', expect: [], run: { Map m -> validateStatement(m, action([task([m: []])])) }],
    [name: 'task m as an empty string, which the serializer deletes', expect: ['wrong-kind'], run: { Map m -> validateStatement(m, action([task([m: ''])])) }],
    [name: 'task m as a map', expect: ['wrong-kind'], run: { Map m -> validateStatement(m, action([task([m: [x: 1]])])) }],
    [name: 'task m as a list of device operands', expect: ['wrong-kind'], run: { Map m -> validateStatement(m, action([task([m: [[t: 'd', d: [':d1:']]]])])) }],
    [name: 'a custom command task', expect: [], run: { Map m -> validateStatement(m, action([task([c: 'refreshNow', cm: true])])) }],
    [name: 'a task keeping cm false', expect: ['empty-persisted'], run: { Map m -> validateStatement(m, action([task([cm: false])])) }],
    [name: 'a task with a non-boolean cm', expect: ['bad-value'], run: { Map m -> validateStatement(m, action([task([cm: 'yes'])])) }],
    [name: 'hub-numbered nodes as the editor re-saves them', expect: [], run: { Map m -> [
        ifStmt([leaf(['$': 3]), group([leaf(['$': 5])], ['$': 4])], ['$': 1, ei: [['$': 2, o: 'and', c: [leaf(['$': 6])], s: []]]]),
        action([task(['$': 8])]) + ['$': 7], switchStmt([[t: 's', ro: [t: 'c'], ro2: [t: 'c'], s: [], '$': 10]], ['$': 9]),
        onStmt([evt(['$': 12])]) + ['$': 11]
    ].collectMany { validateStatement(m, it) } }],
    [name: 'a condition with the ct and s the hub writes', expect: [], run: { Map m -> validateStatement(m, ifStmt([leaf([ct: 't', s: true])])) }],
    [name: 'a condition keeping s false', expect: ['bad-value'], run: { Map m -> validateStatement(m, ifStmt([leaf([s: false])])) }],
    [name: 'a condition with an unknown ct', expect: ['bad-value'], run: { Map m -> validateStatement(m, ifStmt([leaf([ct: 'x'])])) }],
    [name: 'a switch with the ct and s the hub writes', expect: [], run: { Map m -> validateStatement(m, switchStmt([], [ct: 'c', s: true])) }],
    [name: 'an event with the ct and s the hub writes', expect: [], run: { Map m -> validateStatement(m, onStmt([evt([ct: 't', s: true])])) }],
    [name: 'a calendar every missing lo2', expect: ['missing-key'], run: { Map m -> validateStatement(m, without(every('d'), 'lo2')) }],
    [name: 'a short-interval every missing lo2 and lo3', expect: ['missing-unconsumed-key'], run: { Map m -> validateStatement(m, without(without(every('m'), 'lo2'), 'lo3')) }],
    [name: 'every lo2 and lo3 are consumed for a calendar unit', expect: ['consumed'], run: { Map m ->
        ['lo2', 'lo3'].collect { [code: isConsumed(everySpec(m, it), every('w'), null) ? 'consumed' : 'unconsumed'] } }],
    [name: 'every lo2 and lo3 are not consumed for a short interval', expect: ['unconsumed'], run: { Map m ->
        ['lo2', 'lo3'].collect { [code: isConsumed(everySpec(m, it), every('h'), null) ? 'consumed' : 'unconsumed'] } }]
]

def codesOf = { List out -> out.collect { it.code }.unique().sort() }
def failingScenarios = { Map m ->
    scenarios.findAll { Map s ->
        try { codesOf((s.run as Closure).call(m) as List) != s.expect } catch (Exception e) { true }
    }.collect { it.name }
}

scenarios.each { Map s ->
    List got
    try { got = codesOf((s.run as Closure).call(manifest) as List) } catch (Exception e) { got = ["threw ${e.class.simpleName}: ${e.message}"] }
    check(got == s.expect, "shape: ${s.name} ${got == s.expect ? '' : "got ${got}"}")
}

// ---- mutations: removing a discriminator, condition or inventoried key fails a check ----

def copyOf = { Map m -> new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(m)) as Map }
def variantKeys = { Map m, String variant -> ((((m.substructures as Map).condition as Map).variants as Map)[variant]) as Map }
def subKeys = { Map m, String sub -> (((m.substructures as Map)[sub] as Map).keys) as Map }
def unconditional = { Map spec -> spec.remove('persistedWhen'); spec.remove('outsideWhen'); spec.persisted = 'user-optional' }

List<Map> mutations = [
    [name: 'condition discriminator removed', apply: { Map m ->
        Map c = (m.substructures as Map).condition as Map
        (m.substructures as Map).condition = [keys: ((c.variants as Map).condition as Map) + ((c.variants as Map).group as Map)] }],
    [name: 'followed-by context removed from condition lists', apply: { Map m -> ((m.lists as Map)['condition-list'] as Map).remove('ownerKey') }],
    [name: 'first and later followed-by steps no longer distinguished', apply: { Map m ->
        Map l = (m.lists as Map)['condition-list'] as Map; l.first = l.rest }],
    [name: 'leaf wd persistence condition removed', apply: { Map m -> unconditional(variantKeys(m, 'condition').wd as Map) }],
    [name: 'leaf wt persistence condition removed', apply: { Map m -> unconditional(variantKeys(m, 'condition').wt as Map) }],
    [name: 'group wd persistence condition removed', apply: { Map m -> unconditional(variantKeys(m, 'group').wd as Map) }],
    [name: 'group wt persistence condition removed', apply: { Map m -> unconditional(variantKeys(m, 'group').wt as Map) }],
    [name: 'else-if o removed', apply: { Map m -> subKeys(m, 'elseif').remove('o') }],
    [name: 'else-if n treated as always persisted', apply: { Map m -> (subKeys(m, 'elseif').n as Map).persisted = 'always' }],
    [name: 'case ro2 consumption condition removed', apply: { Map m -> (subKeys(m, 'case').ro2 as Map).remove('consumedWhen') }],
    [name: 'every lo2 consumption condition removed', apply: { Map m -> everySpec(m, 'lo2').remove('consumedWhen') }],
    [name: 'every lo3 consumption condition removed', apply: { Map m -> everySpec(m, 'lo3').remove('consumedWhen') }],
    [name: 'task m narrowed to a scalar', apply: { Map m -> (subKeys(m, 'task').m as Map).kind = 'scalar' }],
    [name: 'task cm removed', apply: { Map m -> subKeys(m, 'task').remove('cm') }],
    [name: 'event t removed', apply: { Map m -> subKeys(m, 'event').remove('t') }],
    [name: 'condition sm made optional', apply: { Map m -> (variantKeys(m, 'condition').sm as Map).persisted = 'user-optional' }]
]
mutations.each { Map mu ->
    Map mutated = copyOf(manifest)
    (mu.apply as Closure).call(mutated)
    List broken = failingScenarios(mutated)
    check(!broken.isEmpty(), "mutation: ${mu.name} fails ${broken.size()} scenario(s)")
}

List<Map> gateMutations = [
    [name: 'task cm removed', apply: { Map m -> subKeys(m, 'task').remove('cm') }],
    [name: 'task z removed', apply: { Map m -> subKeys(m, 'task').remove('z') }],
    [name: 'event t removed', apply: { Map m -> subKeys(m, 'event').remove('t') }],
    [name: 'statement $ removed', apply: { Map m -> (m.common as Map).remove('$') }],
    [name: 'else-if $ removed', apply: { Map m -> subKeys(m, 'elseif').remove('$') }],
    [name: 'case $ removed', apply: { Map m -> subKeys(m, 'case').remove('$') }],
    [name: 'task $ removed', apply: { Map m -> subKeys(m, 'task').remove('$') }],
    [name: 'event $ removed', apply: { Map m -> subKeys(m, 'event').remove('$') }],
    [name: 'leaf condition $ removed', apply: { Map m -> variantKeys(m, 'condition').remove('$') }],
    [name: 'group $ removed', apply: { Map m -> variantKeys(m, 'group').remove('$') }],
    [name: 'leaf condition ct removed', apply: { Map m -> variantKeys(m, 'condition').remove('ct') }],
    [name: 'leaf condition s removed', apply: { Map m -> variantKeys(m, 'condition').remove('s') }],
    [name: 'event ct removed', apply: { Map m -> subKeys(m, 'event').remove('ct') }],
    [name: 'switch ct removed', apply: { Map m -> (((m.statements as Map)['wc.statement.switch'] as Map).keys as Map).remove('ct') }],
    [name: 'exclusion for w removed', apply: { Map m -> m.exclusions = (m.exclusions as List).findAll { it.key != 'w' } }],
    [name: 'exclusion for a restriction number removed', apply: { Map m -> m.exclusions = (m.exclusions as List).findAll { !(it.key == '$' && it.structures == ['restriction']) } }]
]
gateMutations.each { Map mu ->
    Map mutated = copyOf(manifest)
    (mu.apply as Closure).call(mutated)
    List found = completenessGaps(mutated)
    check(!found.isEmpty(), "gate mutation: ${mu.name} leaves ${found.size()} inventoried key(s) unaccounted ${found.take(2)}")
}

// ---- the IDE round trip ----------------------------------------------------------------

List roundTripBad = (manifest.roundTrip as List).findAll { Map e ->
    !(manifest.roundTripGuards as List).contains(e.guard) || !(manifest.roundTripEffects as List).contains(e.effect) ||
        !(e.source instanceof String && e.source) || !(e.key instanceof String) ||
        (e.when != null && !(manifest.roundTripConditions as List).contains(e.when))
}.collect { "${it.region}: ${it.source}" }
check(roundTripBad.isEmpty(), "every round-trip entry takes closed guard, effect and condition values ${roundTripBad}")

def deepCopy = { Object o -> new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(o)) }
// Applies only the entries the manifest says reach the IDE copy, so the prediction follows the manifest.
def predictRoundTrip = { Map m, Map first ->
    Map b = deepCopy(first) as Map
    List rules = (m.roundTrip as List).findAll { Map e -> e.guard == 'always' }
    def on = { String key, String effect, String when = null -> rules.any { Map e -> e.key == key && e.effect == effect && (when == null || e.when == when) } }
    int number = 0
    def numbered = { Map node -> if (on('$', 'assign')) node['$'] = ++number }
    def anyNode = { Map node ->
        if (on('data', 'remove', 'empty-map') && node.data instanceof Map && (node.data as Map).isEmpty()) node.remove('data')
        if (on('sm', 'remove', 'value-auto') && node.sm == 'auto') node.remove('sm')
        if (on('z', 'remove')) node.remove('z')
    }
    Closure statementNode
    Closure conditionNode
    conditionNode = { Map node ->
        numbered(node); anyNode(node)
        if (node.t == 'condition') {
            if (on('s', 'remove')) node.remove('s')
            if (on('ct', 'assign')) node.ct = 'c'
            if (on('ro2', 'remove', 'comparison-arity')) node.remove('ro2')
        }
        ((node.c ?: []) as List).each { conditionNode(it as Map) }
        (((node.ts ?: []) as List) + ((node.fs ?: []) as List)).each { statementNode(it as Map) }
    }
    statementNode = { Map node ->
        numbered(node); anyNode(node)
        if (on('w', 'remove')) node.remove('w')
        if (on('a', 'remove', 'value-0') && node.a == '0') node.remove('a')
        if (!node.containsKey('tcp') && on('tcp', 'assign', 'absent')) node.tcp = 'n'
        else if (node.tcp == 'c' && on('tcp', 'remove', 'value-c')) node.remove('tcp')
        if (on('rop', 'remove', 'empty-restrictions') && node.rop && !node.r) { node.remove('rop'); node.remove('rn') }
        if (on('ctp', 'remove', 'value-i') && node.ctp == 'i') node.remove('ctp')
        if (node.t == 'every' && on('lo2', 'remove', 'short-interval') && ((node.lo as Map)?.vt in ['ms', 's', 'm', 'h'])) { node.remove('lo2'); node.remove('lo3') }
        if (node.t == 'switch') { if (on('s', 'remove')) node.remove('s'); if (on('ct', 'remove')) node.remove('ct') }
        if (node.ei instanceof List && on('ei', 'remove-node')) node.ei = (node.ei as List).findAll { Map e -> (e.c as List) || (e.s as List) }
        ((node.ei ?: []) as List).each { Map e -> numbered(e); anyNode(e); ((e.c ?: []) as List).each { conditionNode(it as Map) }; ((e.s ?: []) as List).each { statementNode(it as Map) } }
        ((node.cs ?: []) as List).each { Map cs -> numbered(cs); anyNode(cs); ((cs.s ?: []) as List).each { statementNode(it as Map) } }
        ((node.k ?: []) as List).each { Map k -> numbered(k); anyNode(k); if (on('m', 'remove', 'empty-list') && k.m instanceof List && (k.m as List).isEmpty()) k.remove('m') }
        if (node.t == 'on') ((node.c ?: []) as List).each { Map ev -> numbered(ev); anyNode(ev) }
        else ((node.c ?: []) as List).each { conditionNode(it as Map) }
        (((node.s instanceof List ? node.s : []) as List) + ((node.e ?: []) as List)).each { statementNode(it as Map) }
    }
    statementNode(b)
    b
}

List<Map> firstSaves = [
    ifStmt([leaf(), group([leaf()], [n: true])], [tcp: 'c', sm: 'always', ei: [[o: 'and', n: true, c: [leaf()], s: []], [o: 'and', c: [], s: []]]]),
    action([task([m: []]), task([m: [':m1:']])]) + [tcp: 'c'],
    every('m') + [tcp: 'c'],
    every('d') + [tcp: 'c'],
    switchStmt([[t: 's', ro: [t: 'c'], ro2: [t: 'c'], s: []]], [tcp: 'c']),
    onStmt([evt()]) + [tcp: 'c'],
    stmt('while', [o: 'and', c: [leaf()], s: [stmt('break', [:])]])
]
List abProblems = []
firstSaves.eachWithIndex { Map a, int i ->
    List aCodes = codesOf(validateStatement(manifest, a))
    List bCodes = codesOf(validateStatement(manifest, predictRoundTrip(manifest, a)))
    if (aCodes || bCodes) abProblems << "#${i} first-save ${aCodes} round-trip ${bCodes}"
}
check(abProblems.isEmpty(), "every first-save shape and its predicted round-trip shape validate ${abProblems}")
Map predictedIf = predictRoundTrip(manifest, firstSaves[0])
Map predictedAction = predictRoundTrip(manifest, firstSaves[1])
check(predictedIf['$'] == 1 && (predictedIf.ei as List).size() == 1 && ((predictedIf.c as List)[0] as Map).ct == 'c' &&
      predictedIf.a == '0' && predictedIf.rop == 'and' && predictedIf.tcp == 'c' && predictedIf.sm == 'always' &&
      !((predictedAction.k as List)[0] as Map).containsKey('m') && ((predictedAction.k as List)[1] as Map).m == [':m1:'],
    'the prediction numbers nodes, writes ct, prunes the empty else-if and the empty mode list, and keeps a, rop, tcp and sm')

def flipGuard = { String key, String effect ->
    Map mm = deepCopy(manifest) as Map
    (mm.roundTrip as List).findAll { Map e -> e.key == key && e.effect == effect && e.region == 'executor.clean-code' }.each { Map e -> e.guard = (e.guard == 'always' ? 'inMem' : 'always') }
    mm
}
[['a', 'remove'], ['rop', 'remove'], ['tcp', 'assign']].each { List kv ->
    Map mm = flipGuard(kv[0] as String, kv[1] as String)
    check(firstSaves.any { Map a -> codesOf(validateStatement(manifest, predictRoundTrip(mm, a))) },
        "round-trip mutation: if the ${kv[0]} ${kv[1]} ran on the IDE copy, a round-trip shape would fail validation")
}

// ---- branch evidence --------------------------------------------------------------------

def branchesOf = { Map m ->
    Set out = [] as Set
    def add = { String structure, Map keys ->
        keys.each { String key, Map spec ->
            if (spec.persisted in ['unless-empty', 'user-optional', 'round-trip']) { out << [structure, key, 'present']; out << [structure, key, 'absent'] }
            if (spec.persisted == 'when') { out << [structure, key, 'present']; if (spec.outsideWhen == 'retained-unconsumed') out << [structure, key, 'retained'] }
            if (spec.values && spec.values != [true]) (spec.values as List).each { out << [structure, key, "value:${it}".toString()] }
            if (spec.consumedWhen) { out << [structure, key, 'consumed']; out << [structure, key, 'unconsumed'] }
        }
    }
    add('statement', m.common as Map)
    (m.statements as Map).each { id, e -> add(id as String, (e as Map).keys as Map) }
    (m.substructures as Map).each { String name, Map s ->
        if (s.discriminator) (s.variants as Map).each { vn, keys -> add(vn as String, keys as Map) }
        else add(name, s.keys as Map)
    }
    out
}
def tableBranches = { Map m -> ((m.branchEvidence ?: []) as List).collect { Map r -> [r.structure, r.key, r.branch] } as Set }
Set expectedBranches = branchesOf(manifest)
Set listedBranches = tableBranches(manifest)
check(expectedBranches == listedBranches && (manifest.branchEvidence as List).size() == listedBranches.size(),
    "the branch table lists every optional and conditional branch exactly once (${expectedBranches.size()}) missing ${(expectedBranches - listedBranches).take(3)} extra ${(listedBranches - expectedBranches).take(3)}")
// Which committed fixtures take each branch, read from the documents with the manifest's own closed
// rules and independently of the walker. A fixture takes a branch when any node of that structure does.
def fixtureBranches = { Map m, Map docs ->
    Map subStructures = m.substructures as Map
    Map taken = [:]
    def mark = { String structure, String key, String branch, String name ->
        List k = [structure, key, branch]
        if (!taken.containsKey(k)) taken[k] = [] as Set
        (taken[k] as Set) << name
    }
    def predicate
    predicate = { Map p, Map node, String ctx ->
        if (p.all) return (p.all as List).every { predicate(it as Map, node, ctx) }
        if (p.context) return (p.context as List).contains(ctx)
        Object v = node
        (p.key as String).tokenize('.').each { String seg -> v = (v instanceof Map) ? (v as Map)[seg] : null }
        if (p.oneOf) return (p.oneOf as List).contains(v)
        if (p.noneOf) return !(p.noneOf as List).contains(v)
        false
    }
    def record = { String structure, Map keys, Map node, String ctx, String name ->
        keys.each { String key, Map spec ->
            boolean present = node.containsKey(key)
            if (spec.persisted in ['unless-empty', 'user-optional', 'round-trip']) mark(structure, key, present ? 'present' : 'absent', name)
            if (present && spec.persisted == 'when') mark(structure, key, predicate(spec.persistedWhen as Map, node, ctx) ? 'present' : 'retained', name)
            if (present && spec.values && spec.values != [true]) mark(structure, key, "value:${node[key]}".toString(), name)
            if (present && spec.consumedWhen) mark(structure, key, predicate(spec.consumedWhen as Map, node, ctx) ? 'consumed' : 'unconsumed', name)
        }
    }
    def stepContext = { boolean followedBy, int i -> followedBy ? (i == 0 ? 'followed-by-first-step' : 'followed-by-later-step') : 'condition-list-member' }
    def maps = { Object l -> (l instanceof List) ? (l as List).findAll { it instanceof Map } : [] }
    def conditionList = { Object l -> (l instanceof List) ? (l as List) : [] }
    def statement
    def condition
    condition = { Map node, String ctx, String name ->
        Map keys = ((subStructures.condition as Map).variants as Map)[node.t] as Map
        if (keys) record(node.t as String, keys, node, ctx, name)
        boolean fb = node.o == 'followed by'
        conditionList(node.c).eachWithIndex { Object c, int i -> if (c instanceof Map) condition(c as Map, stepContext(fb, i), name) }
        (maps(node.ts) + maps(node.fs)).each { statement(it as Map, name) }
    }
    statement = { Map node, String name ->
        String id = "wc.statement.${node.t}".toString()
        record('statement', m.common as Map, node, null, name)
        Map entry = (m.statements as Map)[id] as Map
        if (entry) record(id, entry.keys as Map, node, null, name)
        boolean fb = node.o == 'followed by'
        if (node.t == 'on') maps(node.c).each { record('event', (subStructures['event'] as Map).keys as Map, it as Map, null, name) }
        else conditionList(node.c).eachWithIndex { Object c, int i -> if (c instanceof Map) condition(c as Map, stepContext(fb, i), name) }
        maps(node.ei).each { Map ei ->
            record('elseif', (subStructures['elseif'] as Map).keys as Map, ei, null, name)
            boolean efb = ei.o == 'followed by'
            conditionList(ei.c).eachWithIndex { Object c, int i -> if (c instanceof Map) condition(c as Map, stepContext(efb, i), name) }
            maps(ei.s).each { statement(it as Map, name) }
        }
        maps(node.cs).each { Map cs -> record('case', (subStructures['case'] as Map).keys as Map, cs, null, name); maps(cs.s).each { statement(it as Map, name) } }
        maps(node.k).each { record('task', (subStructures['task'] as Map).keys as Map, it as Map, null, name) }
        (maps(node.s) + maps(node.e)).each { statement(it as Map, name) }
    }
    docs.each { Object name, Object doc -> maps((doc as Map).s).each { statement(it as Map, name as String) } }
    taken
}

// Both directions. A row cites exactly the committed saves that take its branch and whose round trip
// still takes it, and records every save whose round trip dropped it and every round trip whose save
// lacked it. A row with no promoting save carries one gap that matches why. No taken branch is unlisted.
def branchGate = { Map m, Map meta, Map docs ->
    List problems = []
    Map lin = m.captureLineage as Map
    Set saveKinds = lin.keySet() as Set
    def roundTripName = { String n -> Map f = meta[n] as Map; (f && lin[f.capture]) ? n.substring(0, n.length() - (f.capture as String).length()) + lin[f.capture] : null }
    def saveName = { String n ->
        Map f = meta[n] as Map
        Object saveKind = f ? lin.find { k, v -> v == f.capture }?.key : null
        saveKind ? n.substring(0, n.length() - (f.capture as String).length()) + saveKind : null
    }
    Map taken = fixtureBranches(m, docs)
    Set rows = tableBranches(m)
    taken.keySet().findAll { !rows.contains(it) }.each { problems << "a fixture takes ${it}, which the table does not list".toString() }
    (m.branchEvidence as List).each { Map r ->
        String rowLabel = "${r.structure}.${r.key} ${r.branch}"
        Set t = (taken[[r.structure, r.key, r.branch]] ?: []) as Set
        List cited = ((r.fixtures ?: []) as List).collect { it as String }
        cited.each { String n ->
            if (!meta.containsKey(n)) { problems << "${rowLabel} cites ${n}, which is not a committed fixture".toString(); return }
            if (!saveKinds.contains((meta[n] as Map).capture)) { problems << "${rowLabel} cites ${n}, which is not a save capture".toString(); return }
            String rt = roundTripName(n)
            if (!meta.containsKey(rt)) { problems << "${rowLabel} cites ${n}, which has no committed round trip".toString(); return }
            if (!t.contains(n)) problems << "${rowLabel} cites ${n}, which does not take it".toString()
            else if (!t.contains(rt)) problems << "${rowLabel} cites ${n}, whose round trip no longer takes it".toString()
        }
        List saves = t.findAll { saveKinds.contains((meta[it] as Map)?.capture) }.sort()
        List canonical = saves.findAll { t.contains(roundTripName(it as String)) }
        List editorAuthored = saves.findAll { !t.contains(roundTripName(it as String)) }
        List canonicalOnly = t.findAll { !saveKinds.contains((meta[it] as Map)?.capture) && !t.contains(saveName(it as String)) }.sort()
        if (cited.sort(false) != canonical) problems << "${rowLabel} cites ${cited}, but the promoting saves are ${canonical}".toString()
        if (((r.editorAuthored ?: []) as List).sort(false) != editorAuthored) problems << "${rowLabel} records editorAuthored ${r.editorAuthored}, derived ${editorAuthored}".toString()
        if (((r.canonicalOnly ?: []) as List).sort(false) != canonicalOnly) problems << "${rowLabel} records canonicalOnly ${r.canonicalOnly}, derived ${canonicalOnly}".toString()
        boolean hasGap = r.gap != null
        if (cited.isEmpty() != hasGap) problems << "${rowLabel} must carry fixtures or one gap, never both or neither".toString()
        if (hasGap) {
            String derivedGap = editorAuthored ? 'editor-authored-only' : (canonicalOnly ? 'canonical-only' : null)
            if (!(m.branchGaps as List).contains(r.gap)) problems << "${rowLabel} gap ${r.gap} is not a closed gap".toString()
            else if (derivedGap != null ? r.gap != derivedGap : r.gap in ['editor-authored-only', 'canonical-only']) problems << "${rowLabel} gap ${r.gap} does not match the fixtures".toString()
        }
    }
    problems
}
List branchProblems = branchGate(manifest, fixtureMeta, fixtureDocs)
check(branchProblems.isEmpty(), "every branch cites exactly its promoting saves, records save and round-trip differences, and otherwise carries one matching gap ${branchProblems.take(3)}")
Map observedGaps = (fixtureManifest.observedEditorGaps as List).collectEntries { [(it.branch): it.observation] }
check(['statement sm present', 'statement tcp absent'].every { String b ->
        List p = b.tokenize(' ')
        observedGaps.containsKey(b) && (manifest.branchEvidence as List).find { Map r -> r.structure == p[0] && r.key == p[1] && r.branch == p[2] }?.gap == 'observed-at-capture' },
    'the two editor refusals recorded at capture are observed-at-capture gaps')
check((manifest.branchEvidence as List).find { Map r -> r.structure == 'condition' && r.key == 'ct' && r.branch == 'value:t' }?.gap == 'canonical-only',
    'a trigger ct written only by the reload after a save kept a stale ct does not promote the branch')
check((manifest.branchEvidence as List).find { Map r -> r.structure == 'task' && r.key == 'cm' && r.branch == 'present' }?.gap == 'needs-physical-device',
    'a task carrying cm stays capped by a fixed evidence gap')
Map droppedBranch = deepCopy(manifest) as Map
droppedBranch.branchEvidence = (droppedBranch.branchEvidence as List).findAll { Map r -> !(r.structure == 'task' && r.key == 'cm' && r.branch == 'present') }
check(branchesOf(droppedBranch) != tableBranches(droppedBranch), 'branch mutation: dropping the cm row leaves the table incomplete')

def eachMap
eachMap = { Object node, Closure f ->
    if (node instanceof Map) { f(node as Map); (node as Map).values().each { eachMap(it, f) } }
    else if (node instanceof List) (node as List).each { eachMap(it, f) }
}
def gateMutant = { Closure mutate ->
    Map m = deepCopy(manifest) as Map
    Map meta = deepCopy(fixtureMeta) as Map
    Map docs = deepCopy(fixtureDocs) as Map
    mutate(m, meta, docs)
    branchGate(m, meta, docs)
}
def rowOf = { Map m, String structure, String key, String branch -> (m.branchEvidence as List).find { Map r -> r.structure == structure && r.key == key && r.branch == branch } as Map }
[['dropping one citation', { Map m, Map meta, Map docs -> Map r = rowOf(m, 'statement', 'a', 'value:1'); r.fixtures = (r.fixtures as List).drop(1) }],
 ['citing a fixture that does not take the branch', { Map m, Map meta, Map docs -> rowOf(m, 'statement', 'tep', 'present').fixtures = ['l3-01-conditional.first-save'] }],
 ['citing a round-trip capture', { Map m, Map meta, Map docs -> rowOf(m, 'statement', 'tep', 'present').fixtures = ['l3-08-policies.round-trip'] }],
 ['a cited save whose round trip is not committed', { Map m, Map meta, Map docs -> meta.remove('l3-08-policies.round-trip'); docs.remove('l3-08-policies.round-trip') }],
 ['round-trip normalisation removing the claimed branch', { Map m, Map meta, Map docs -> eachMap(docs['l3-08-policies.round-trip']) { Map n -> n.remove('tep') } }],
 ['round-trip normalisation changing the claimed value', { Map m, Map meta, Map docs -> eachMap(docs['l3-08-policies.round-trip']) { Map n -> if (n.tep == 'c') n.tep = 'p' } }],
 ['deleting a committed fixture file', { Map m, Map meta, Map docs -> meta.remove('l3-06-timers.first-save'); docs.remove('l3-06-timers.first-save') }],
 ['a gap on a branch the fixtures canonically take', { Map m, Map meta, Map docs -> Map r = rowOf(m, 'statement', 'tep', 'present'); r.remove('fixtures'); r.gap = 'not-in-matrix' }],
 ['an editor-authored save left unrecorded', { Map m, Map meta, Map docs -> rowOf(m, 'condition', 'ct', 'absent').remove('editorAuthored') }],
 ['a taken branch missing from the table', { Map m, Map meta, Map docs -> m.branchEvidence = (m.branchEvidence as List).findAll { Map r -> !(r.structure == 'statement' && r.key == 'tep' && r.branch == 'present') } }]
].each { List mc ->
    check(!gateMutant(mc[1] as Closure).isEmpty(), "branch mutation: ${mc[0]} fails the gate")
}

// ---- source cross-check, when the pinned checkout is present ------------------------

File srcRoot = new File(repoRoot, 'tmp/webcore-source')
if (!new File(srcRoot, (prov.executorPath as String)).isFile() || !new File(srcRoot, (prov.editorPath as String)).isFile()) {
    println 'SKIP  source cross-check: pinned webCoRE checkout not present at tmp/webcore-source'
} else {
    String piston = new File(srcRoot, prov.executorPath as String).getText('UTF-8')
    String editor = new File(srcRoot, prov.editorPath as String).getText('UTF-8')
    String generator = new File(repoRoot, 'tools/webcore-investigation/generate-construct-registry.groovy').getText('UTF-8')

    Map<String, List> constantNames = [:].withDefault { [] }
    Map<String, String> constantValue = [:]
    piston.eachLine { String line ->
        def m = line =~ /^@Field static final String (s[A-Z][A-Za-z0-9_]*)\s*=\s*'([^']*)'/
        if (m) { (constantNames[m[0][2] as String] as List) << (m[0][1] as String); constantValue[m[0][1] as String] = m[0][2] as String }
    }

    // Same boundary rule as the generator's balancedRegion: braces inside strings and
    // comments do not count, or a region would be cut short at the first quoted brace.
    def regionText = { String src, String anchorText ->
        int start = src.indexOf(anchorText)
        if (start < 0) return null
        int i = src.indexOf('{', start)
        int depth = 0
        for (int p = i; p < src.length(); p++) {
            char ch = src.charAt(p)
            if (ch == ('/' as char) && p + 1 < src.length()) {
                char n = src.charAt(p + 1)
                if (n == ('/' as char)) { int e = src.indexOf(10, p); if (e < 0) break; p = e; continue }
                if (n == ('*' as char)) { int e = src.indexOf('*/', p); if (e < 0) break; p = e + 1; continue }
            }
            if (ch == (char) 34 || ch == (char) 39) {
                char q = ch; p++
                while (p < src.length() && src.charAt(p) != q) { if (src.charAt(p) == (char) 92) p++; p++ }
                continue
            }
            if (ch == ('{' as char)) depth++
            else if (ch == ('}' as char)) { depth--; if (depth == 0) return src.substring(start, p + 1) }
        }
        return null
    }

    Map<String, String> regions = [:]
    (generator =~ /regionSource\['((?:executor|editor)\.[a-z-]+)'\]\s*=\s*balancedRegion\((piston|editor), '([^']+)'/).each { m ->
        String text = regionText(m[2] == 'editor' ? editor : piston, m[3] as String)
        if (text != null) regions[m[1] as String] = text
    }

    Set cited = [] as Set
    specs.each { Map s -> ['readBy', 'writtenBy', 'normalisedBy'].each { f -> cited.addAll(((s.spec as Map)[f] ?: []) as List) } }
    (manifest.sourceAssertions as List).each { Map a -> if (a.region) cited << a.region }
    (manifest.inventory as List).each { Map e -> cited << e.region }
    List unextracted = cited.findAll { !regions.containsKey(it) }.toList()
    check(unextracted.isEmpty(), "every cited region is extractable from the pinned source (${cited.size()}) ${unextracted}")

    def stripComments = { String t -> t.replaceAll('(?s)/\\*.*?\\*/', '').replaceAll('(?m)(^|\\s)//.*$', '$1') }
    String K = '(s[A-Z][A-Za-z0-9_]*)'
    Map accessorKeys = [sMt: 't', sMvt: 'vt', stmtNum: '$', sMa: 'a']
    def extractFor = { String regionName, String recv ->
        String t = stripComments(regions[regionName])
        String R = java.util.regex.Pattern.quote(recv)
        Set a = [] as Set
        Set l = [] as Set
        Set rd = [] as Set
        if (regionName.startsWith('editor.')) {
            (t =~ ('\\b' + R + '\\.([A-Za-z$][A-Za-z0-9]*)\\s*=(?!=)')).each { a << it[1] }
            (t =~ ('\\b(?:var\\s+)?' + R + '\\s*=\\s*[^;=]*?\\{([^{}]*)\\}')).each { m ->
                ((m[1] as String) =~ '(?:^|,)\\s*([A-Za-z$][A-Za-z0-9]*)\\s*:').each { l << it[1] }
            }
            (t =~ ('\\b' + R + '\\.([A-Za-z$][A-Za-z0-9]*)\\b(?!\\s*=(?!=))')).each { rd << it[1] }
        } else {
            (t =~ ('\\b' + R + '\\[' + K + '\\]\\s*(=(?!=))?')).each { m -> String k = constantValue[m[1] as String]; if (k != null) (m[2] ? a : rd) << k }
            (t =~ ('\\b' + R + '\\.remove\\(' + K + '\\)')).each { m -> String k = constantValue[m[1] as String]; if (k != null) a << k }
            (t =~ ('\\b' + R + '\\.containsKey\\(' + K + '\\)')).each { m -> String k = constantValue[m[1] as String]; if (k != null) rd << k }
            (t =~ ('\\b(?:mMs|liMs|sMs|iMs|bIs|lMs|oMs|dMs)\\(\\s*' + R + '\\s*,\\s*' + K + '\\s*\\)')).each { m -> String k = constantValue[m[1] as String]; if (k != null) rd << k }
            (t =~ ('\\b(sMt|sMvt|stmtNum|sMa)\\(\\s*' + R + '\\s*\\)')).each { m -> rd << accessorKeys[m[1] as String] }
            if ((t =~ ('\\b' + R + '\\?\\.\\$')).find()) rd << '$'
        }
        [assigns: a, literals: l, reads: rd]
    }
    def inventoryDrift = { List inv ->
        inv.findAll { Map e ->
            if (regions[e.region as String] == null) return true
            Map got = extractFor(e.region as String, e.receiver as String)
            got.assigns != ((e.assigns ?: []) as Set) || got.literals != ((e.literals ?: []) as Set) || got.reads != ((e.reads ?: []) as Set)
        }.collect { Map e ->
            Map got = regions[e.region as String] == null ? [:] : extractFor(e.region as String, e.receiver as String)
            "${e.region} ${e.receiver} extracted ${got}"
        }
    }
    List drift = inventoryDrift(manifest.inventory as List)
    check(drift.isEmpty(), "the inventory is exactly what the pinned regions assign, create and read ${drift.take(3)}")

    Map droppedCm = copyOf(manifest)
    ((droppedCm.inventory as List).find { it.region == 'editor.update-task' } as Map).assigns = ((droppedCm.inventory as List).find { it.region == 'editor.update-task' }.assigns as List) - ['cm']
    check(!inventoryDrift(droppedCm.inventory as List).isEmpty(), 'drift mutation: an inventory missing task.cm no longer matches the source')
    Map addedKey = copyOf(manifest)
    ((addedKey.inventory as List).find { it.region == 'editor.edit-event' } as Map).reads = ((addedKey.inventory as List).find { it.region == 'editor.edit-event' }.reads as List) + ['bogus']
    check(!inventoryDrift(addedKey.inventory as List).isEmpty(), 'drift mutation: an inventory claiming an unread key no longer matches the source')

    Map accessors = ['$': ['stmtNum(', '?.$'], 't': ['sMt('], 'a': ['sMa('], 'vt': ['sMvt(']]
    List readMisses = []
    List writeMisses = []
    specs.each { Map s ->
        String key = s.key as String
        ((s.spec as Map).readBy ?: []).each { String region ->
            String text = regions[region]
            if (text == null) return
            List names = constantNames[key] as List
            boolean seen = names.any { text.contains(it as String) } || text.contains("'${key}'") || text.contains(".${key}") ||
                ((accessors[key] ?: []) as List).any { text.contains(it as String) }
            if (!seen) readMisses << "${label(s)} in ${region}"
        }
        ((s.spec as Map).writtenBy ?: []).each { String region ->
            String text = regions[region]
            if (text == null) return
            boolean assigned = (text =~ /\.${java.util.regex.Pattern.quote(key)}\s*=/).find() ||
                (constantNames[key] as List).any { (text =~ ('\\[' + it + '\\]\\s*=(?!=)')).find() }
            if (!assigned) writeMisses << "${label(s)} in ${region}"
        }
    }
    check(readMisses.isEmpty(), "every readBy region actually references its key ${readMisses.take(8)}")
    check(writeMisses.isEmpty(), "every writtenBy region actually assigns its key ${writeMisses.take(8)}")

    List assertionMisses = (manifest.sourceAssertions as List).findAll { Map a ->
        if (a.containsKey('constantValueAbsent')) return (constantNames[a.constantValueAbsent as String] as List) as boolean
        if (a.constant) return !((constantNames[a.value as String] as List).contains(a.constant))
        String text = regions[a.region as String]
        if (text == null) return true
        if (a.containsKey('contains')) return !text.contains(a.contains as String)
        if (a.containsKey('excludes')) return text.contains(a.excludes as String)
        return !java.util.regex.Pattern.compile(a.pattern as String).matcher(text).find()
    }.collect { Map a -> "${a.region ?: a.constant ?: a.constantValueAbsent}: ${a.supports}" }
    check(assertionMisses.isEmpty(), "every source assertion holds in the pinned source ${assertionMisses}")

    // The guard a source position runs under: inside an inMem block, inside a doit block, or always.
    // The region's own header is skipped, since every one of these methods takes inMem as a parameter.
    def guardAt = { String text, int idx ->
        List stack = []
        int p = 0
        while (p < idx) {
            char ch = text.charAt(p)
            if (ch == ('/' as char) && p + 1 < text.length()) {
                char n = text.charAt(p + 1)
                if (n == ('/' as char)) { int e = text.indexOf(10, p); if (e < 0 || e >= idx) break; p = e; continue }
                if (n == ('*' as char)) { int e = text.indexOf('*/', p); if (e < 0) break; p = e + 2; continue }
            }
            if (ch == (char) 34 || ch == (char) 39) {
                char q = ch; p++
                while (p < idx && text.charAt(p) != q) { if (text.charAt(p) == (char) 92) p++; p++ }
                p++
                continue
            }
            if (ch == ('{' as char)) stack << text.substring(text.lastIndexOf('\n', p) + 1, p)
            else if (ch == ('}' as char) && stack) stack.remove((int) (stack.size() - 1))
            p++
        }
        List scope = (stack.size() > 1 ? stack.subList(1, stack.size()) : []) + [text.substring(text.lastIndexOf('\n', idx) + 1, idx)]
        if (scope.any { (it as String).contains('inMem') }) return 'inMem'
        if (scope.any { ((it as String) =~ /\bdoit\b/).find() }) return 'doit'
        return 'always'
    }
    def roundTripGuardMisses = { Map m ->
        List misses = []
        (m.roundTrip as List).each { Map e ->
            String text = regions[e.region as String]
            List hits = []
            int from = 0
            while (text != null && (from = text.indexOf(e.source as String, from)) >= 0) { hits << from; from++ }
            List guards = hits.collect { guardAt(text, it as int) }
            if (hits.isEmpty() || guards.any { it != e.guard }) misses << "${e.region}: ${e.source} ${guards}"
        }
        misses
    }
    List guardMisses = roundTripGuardMisses(manifest)
    check(guardMisses.isEmpty(), "every round-trip entry's guard matches its position in the pinned source ${guardMisses.take(3)}")

    String cleanText = regions['executor.clean-code']
    def unlistedIn = { Map m ->
        List unlisted = []
        def transform = java.util.regex.Pattern.compile('(\\.remove\\(s[A-Z]\\w*\\)|\\[s[A-Z]\\w*\\]\\s*=(?!=))').matcher(cleanText)
        while (transform.find()) {
            int idx = transform.start()
            int lineStart = cleanText.lastIndexOf('\n', idx) + 1
            int lineEnd = cleanText.indexOf('\n', idx)
            String line = cleanText.substring(lineStart, lineEnd < 0 ? cleanText.length() : lineEnd)
            if (line.trim().startsWith('//') || guardAt(cleanText, idx) != 'always') continue
            if (!(m.roundTrip as List).any { Map e -> e.region == 'executor.clean-code' && line.contains(e.source as String) }) unlisted << line.trim()
        }
        unlisted.unique()
    }
    List unlistedTransforms = unlistedIn(manifest)
    check(unlistedTransforms.isEmpty(), "every cleanCode transformation that reaches the IDE copy is in the round-trip table ${unlistedTransforms.take(3)}")

    [['a', 'remove'], ['tcp', 'remove'], ['tcp', 'assign'], ['rop', 'remove'], ['w', 'remove'], ['m', 'remove']].each { List kv ->
        Map mm = deepCopy(manifest) as Map
        (mm.roundTrip as List).findAll { Map e -> e.key == kv[0] && e.effect == kv[1] && e.region == 'executor.clean-code' }.each { Map e ->
            e.guard = (e.guard == 'always' ? 'inMem' : 'always')
        }
        check(!roundTripGuardMisses(mm).isEmpty(), "round-trip mutation: misclassifying the ${kv[0]} ${kv[1]} guard is caught against the source")
    }
    Map unlistedMutant = deepCopy(manifest) as Map
    unlistedMutant.roundTrip = (unlistedMutant.roundTrip as List).findAll { Map e -> e.key != 'm' }
    check(unlistedIn(unlistedMutant).any { (it as String).contains('item.remove(sM)') },
        'round-trip mutation: dropping the empty mode-list entry leaves an unguarded transformation unlisted')
}

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)
