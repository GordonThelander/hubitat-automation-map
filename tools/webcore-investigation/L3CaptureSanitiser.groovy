#!/usr/bin/env groovy
//
// Turns a raw hub statusJson capture of an L3 fixture piston into a structure-only fixture.
// Only the saved piston document is read. A key name is kept only when the walker's reviewed
// allowlist names it outside an opaque field; a string is kept only when a generated closed
// vocabulary contains it. Everything else becomes a deterministic typed placeholder numbered by
// first occurrence, so equal inputs share a placeholder, a device identifier stays an
// identifier, and the walker's structural result is unchanged.
//
// Usage: groovy tools/webcore-investigation/L3CaptureSanitiser.groovy <raw statusJson> <fixture json>

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest

class L3CaptureSanitiser {
    static final String VERSION = '1'

    final Set<String> schemaKeys
    final Set<String> opaqueKeys
    final Set<String> vocabulary
    final Set<String> functionNames
    final Object support

    private final Map<String, Object> placeholders = [:]
    private final Map<String, Integer> counters = [:]

    static String between(String text, String from, String to) {
        int a = text.indexOf(from)
        int b = a < 0 ? -1 : text.indexOf(to, a + from.length())
        if (a < 0 || b < 0) throw new IllegalStateException('could not locate ' + from)
        return text.substring(a + from.length(), b)
    }

    L3CaptureSanitiser(File repoRoot) {
        String app = new File(repoRoot, 'apps/automation_map.groovy').getText('UTF-8')
        String walker = between(app, '// --- webcore census walker: begin ---', '// --- webcore census walker: end ---')
        String decoder = 'Map decodeWebcorePistonDocument(Map data) {' +
            between(app, 'Map decodeWebcorePistonDocument(Map data) {', '// Pure processing, split out of fetchAppRelationships')
        String registry = new File(repoRoot, 'tools/webcore-investigation/generated/webcore_runtime_registry.groovy').getText('UTF-8')
        String shapes = new File(repoRoot, 'tools/webcore-investigation/generated/webcore_statement_shapes.groovy').getText('UTF-8')
        support = new GroovyClassLoader(L3CaptureSanitiser.classLoader).parseClass(
            'class L3SanitiserSupport {\n' + decoder.replace('@Field ', '') + '\n' + walker + '\n' + registry + '\n' + shapes + '\n}').newInstance()

        schemaKeys = (support.webcoreCensusSchemaKeys() as List).collect { it.toString() } as Set
        opaqueKeys = (support.webcoreCensusOpaqueKeys() as List).collect { it.toString() } as Set
        Set<String> words = [] as Set
        Set<String> functions = [] as Set
        ((support.webcoreCensusRegistry() as Map).constructs as Map).keySet().each { Object raw ->
            String id = raw.toString()
            if (id.startsWith('wc.function.')) functions << id.substring('wc.function.'.length()).toLowerCase()
            else words << id.substring(id.lastIndexOf('.') + 1)
        }
        collectShapeWords(support.webcoreStatementShapes(), words)
        String fixAttr = between(walker, 'String webcoreCensusFixAttr(String attr) {', '\n}')
        (fixAttr =~ /'([A-Za-z]+)'/).each { words << (it[1] as String) }
        vocabulary = words
        functionNames = functions
    }

    // Saved spellings the generated shapes name: value sets, predicate values and variants.
    static void collectShapeWords(Object node, Set<String> words) {
        if (node instanceof Map) {
            (node as Map).each { Object k, Object v ->
                if (k in ['values', 'oneOf', 'noneOf', 'ownerOneOf'] && v instanceof List) {
                    (v as List).each { if (it instanceof String) words << (it as String) }
                }
                if (k == 'variants' && v instanceof Map) (v as Map).keySet().each { words << it.toString() }
                collectShapeWords(v, words)
            }
        } else if (node instanceof List) {
            (node as List).each { collectShapeWords(it, words) }
        }
    }

    Map sanitiseCapture(Map statusJson) {
        Map decoded = support.decodeWebcorePistonDocument(statusJson) as Map
        if (decoded.status != 'complete' || !(decoded.document instanceof Map)) {
            throw new IllegalArgumentException('capture did not decode to a piston document: ' + decoded.status)
        }
        return sanitiseDocument(decoded.document as Map)
    }

    Map sanitiseDocument(Map document) {
        placeholders.clear()
        counters.clear()
        return sanitiseMap(document, false)
    }

    private Map sanitiseMap(Map node, boolean opaque) {
        Map out = new LinkedHashMap()
        node.each { Object k, Object v ->
            String key = k.toString()
            boolean keep = !opaque && (schemaKeys.contains(key) || opaqueKeys.contains(key))
            String outKey = keep ? key : (placeholder('key', key) as String)
            boolean childOpaque = opaque || (keep && opaqueKeys.contains(key))
            out[outKey] = (keep && key == '$' && v instanceof Number) ? v : sanitiseValue(v, childOpaque)
        }
        return out
    }

    private Object sanitiseValue(Object v, boolean opaque) {
        if (v instanceof Map) return sanitiseMap(v as Map, opaque)
        if (v instanceof List) return (v as List).collect { sanitiseValue(it, opaque) }
        if (v == null || v instanceof Boolean) return v
        if (v instanceof Number) return placeholder('number', v.toString())
        String s = v.toString()
        if (s.isEmpty()) return s
        if (!opaque && (vocabulary.contains(s) || functionNames.contains(s.toLowerCase()))) return s
        return placeholder((s ==~ /^:[0-9a-f]{32}:$/) ? 'id' : 'string', s)
    }

    // A device identifier stays in identifier form, with an obviously synthetic zero-padded
    // value, so the walker still classifies it as a direct identifier.
    private Object placeholder(String type, String original) {
        String k = type + '|' + original
        if (!placeholders.containsKey(k)) {
            int n = (counters[type] ?: 0) + 1
            counters[type] = n
            switch (type) {
                case 'id': placeholders[k] = ':' + String.format('%032x', n) + ':'; break
                case 'number': placeholders[k] = n; break
                default: placeholders[k] = '<' + type + '#' + n + '>'
            }
        }
        return placeholders[k]
    }

    static Map provenance(Map statusJson) {
        Map state = [:]
        (statusJson.appState instanceof List ? statusJson.appState as List : []).each { Object e ->
            if (e instanceof Map && (e as Map).name in ['build', 'active']) state[(e as Map).name] = (e as Map).value
        }
        return [pistonBuild: state.build, activeAtCapture: state.active]
    }

    static String render(Map document) {
        return JsonOutput.prettyPrint(JsonOutput.toJson(document)) + '\n'
    }

    static String sha256(String text) {
        return MessageDigest.getInstance('SHA-256').digest(text.getBytes('UTF-8')).collect { String.format('%02x', it & 0xFF) }.join()
    }

    static void main(String[] args) {
        if (args.length != 2) {
            System.err.println 'usage: groovy tools/webcore-investigation/L3CaptureSanitiser.groovy <raw statusJson> <fixture json>'
            System.exit(2)
        }
        File repoRoot = new File('.').canonicalFile
        if (!new File(repoRoot, 'apps/automation_map.groovy').isFile()) repoRoot = new File('..').canonicalFile
        Map raw = new JsonSlurper().parse(new File(args[0])) as Map
        String text = render(new L3CaptureSanitiser(repoRoot).sanitiseCapture(raw))
        File out = new File(args[1])
        out.setText(text, 'UTF-8')
        Map prov = provenance(raw)
        println 'wrote ' + out.name
        println '  sanitiserVersion: ' + VERSION
        println '  pistonBuild: ' + prov.pistonBuild
        println '  activeAtCapture: ' + prov.activeAtCapture
        println '  sha256: ' + sha256(text)
    }
}
