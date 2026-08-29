/**
 * Pure, value-free classifier for Rule Machine variable references.
 *
 * Gate B deliberately keeps this outside apps/automation_map.groovy. The class
 * consumes saved fixture data only and performs no Hubitat access, graph writes,
 * logging, or export mutation.
 */
class VariableReferenceClassifier {
    static final Set<String> DEFAULT_BUILT_IN_TOKENS = [
        'device', 'time', 'date', 'value', 'text'
    ] as Set<String>

    static List<Map> classifyAll(List references, Map context) {
        return (references ?: []).collect { Object reference ->
            classify((reference ?: [:]) as Map, context ?: [:])
        }
    }

    static Map classify(Map reference, Map context) {
        String rawName = text(reference.name)
        if (!rawName) throw new IllegalArgumentException('reference.name is required')

        String ownerAppId = text(context.ownerAppId)
        Map<String, Map> localDefinitions = definitionsByName(context.localDefinitions)
        Map<String, Map> hubDefinitions = definitionsByName(context.hubDefinitions)
        Set<String> builtInTokens = ((context.builtInTokens ?: DEFAULT_BUILT_IN_TOKENS) as Collection)
            .collect { text(it).toLowerCase() }
            .findAll { it } as Set<String>

        String evidenceKind = text(reference.evidenceKind ?: 'structured-setting')
        String provenScope = text(reference.provenScope ?: reference.scopeHint).toLowerCase()
        String scopeSource = text(reference.scopeSource)

        if (provenScope && !(provenScope in ['local', 'hub'])) {
            throw new IllegalArgumentException("Unsupported proven scope '${provenScope}'")
        }
        if (provenScope && !scopeSource) {
            throw new IllegalArgumentException('A proven scope requires scopeSource evidence')
        }

        // Free-text substitution proves only that text contains %Name%. It
        // does not independently prove that Name is a variable or which scope
        // owns it. A separately established scope may promote it.
        if (evidenceKind == 'text-token' && !provenScope) {
            provenScope = independentlyEstablishedScope(rawName, context.establishedScopes)
            if (provenScope) scopeSource = 'independently-established-reference'
        }

        if (evidenceKind == 'text-token' && !provenScope) {
            boolean builtIn = builtInTokens.contains(rawName.toLowerCase())
            return baseResult(reference, rawName, ownerAppId, evidenceKind, scopeSource) + [
                scope: null,
                status: 'ignored',
                canonicalName: null,
                localIdentity: null,
                candidateScopes: [],
                candidates: [:],
                reason: builtIn ? 'built-in-token-without-independent-scope' :
                    'weak-text-reference-without-independent-scope'
            ]
        }

        Map localMatch = matchWithinScope(rawName, localDefinitions)
        Map hubMatch = matchWithinScope(rawName, hubDefinitions)

        if (provenScope) {
            Map selected = provenScope == 'local' ? localMatch : hubMatch
            return classifiedForProvenScope(reference, rawName, ownerAppId, evidenceKind,
                scopeSource, provenScope, selected)
        }

        List<String> candidateScopes = []
        if (localMatch.status == 'matched') candidateScopes << 'local'
        if (hubMatch.status == 'matched') candidateScopes << 'hub'

        if (candidateScopes == ['local']) {
            return resolved(reference, rawName, ownerAppId, evidenceKind, scopeSource,
                'local', localMatch.canonicalName as String, localMatch.matchKind as String)
        }
        if (candidateScopes == ['hub']) {
            return resolved(reference, rawName, ownerAppId, evidenceKind, scopeSource,
                'hub', hubMatch.canonicalName as String, hubMatch.matchKind as String)
        }

        Map candidates = [
            local: localMatch.candidates ?: [],
            hub: hubMatch.candidates ?: []
        ]
        if (candidateScopes.size() == 2 || localMatch.status == 'multiple' || hubMatch.status == 'multiple') {
            return baseResult(reference, rawName, ownerAppId, evidenceKind, scopeSource) + [
                scope: 'ambiguous',
                status: 'ambiguous',
                canonicalName: null,
                localIdentity: null,
                candidateScopes: candidateScopes,
                candidates: candidates,
                reason: candidateScopes.size() == 2 ? 'same-name-cross-scope' :
                    'normalization-produced-multiple-candidates'
            ]
        }

        return baseResult(reference, rawName, ownerAppId, evidenceKind, scopeSource) + [
            scope: 'unresolved',
            status: 'unresolved',
            canonicalName: null,
            localIdentity: null,
            candidateScopes: [],
            candidates: candidates,
            reason: 'no-definition-in-either-scope'
        ]
    }

    private static Map classifiedForProvenScope(Map reference, String rawName, String ownerAppId,
            String evidenceKind, String scopeSource, String provenScope, Map selected) {
        if (selected.status == 'matched') {
            return resolved(reference, rawName, ownerAppId, evidenceKind, scopeSource,
                provenScope, selected.canonicalName as String, selected.matchKind as String)
        }
        if (selected.status == 'multiple') {
            return baseResult(reference, rawName, ownerAppId, evidenceKind, scopeSource) + [
                scope: 'ambiguous',
                status: 'ambiguous',
                canonicalName: null,
                localIdentity: null,
                candidateScopes: [provenScope],
                candidates: [(provenScope): selected.candidates ?: []],
                reason: 'normalization-produced-multiple-candidates'
            ]
        }
        return baseResult(reference, rawName, ownerAppId, evidenceKind, scopeSource) + [
            scope: 'unresolved',
            status: 'unresolved',
            canonicalName: null,
            localIdentity: null,
            candidateScopes: [provenScope],
            candidates: [(provenScope): []],
            reason: 'definition-missing-in-proven-scope'
        ]
    }

    private static Map resolved(Map reference, String rawName, String ownerAppId, String evidenceKind,
            String scopeSource, String scope, String canonicalName, String matchKind) {
        if (scope == 'local' && !ownerAppId) {
            throw new IllegalArgumentException('context.ownerAppId is required for a Local Variable')
        }
        return baseResult(reference, rawName, ownerAppId, evidenceKind, scopeSource) + [
            scope: scope,
            status: 'resolved',
            canonicalName: canonicalName,
            localIdentity: scope == 'local' ? "${ownerAppId}:${canonicalName}" : null,
            candidateScopes: [scope],
            candidates: [(scope): [canonicalName]],
            reason: matchKind == 'exact' ? 'exact-name-in-one-scope' :
                'one-unambiguous-normalized-match-in-one-scope'
        ]
    }

    private static Map baseResult(Map reference, String rawName, String ownerAppId,
            String evidenceKind, String scopeSource) {
        return [
            name: rawName,
            ownerAppId: ownerAppId ?: null,
            operation: text(reference.operation ?: 'read'),
            usageRole: reference.containsKey('usageRole') ? reference.usageRole : null,
            evidence: [
                kind: evidenceKind,
                field: text(reference.field) ?: null,
                scopeSource: scopeSource ?: null
            ]
        ]
    }

    private static Map matchWithinScope(String rawName, Map<String, Map> definitions) {
        if (definitions.containsKey(rawName)) {
            return [status: 'matched', canonicalName: rawName, matchKind: 'exact', candidates: [rawName]]
        }

        String comparable = removeOneTrailingPeriod(rawName)
        List<String> matches = definitions.keySet().findAll { String candidate ->
            removeOneTrailingPeriod(candidate) == comparable
        }.sort()

        if (matches.size() == 1) {
            return [status: 'matched', canonicalName: matches[0], matchKind: 'normalized', candidates: matches]
        }
        if (matches.size() > 1) {
            return [status: 'multiple', canonicalName: null, matchKind: 'normalized', candidates: matches]
        }
        return [status: 'none', canonicalName: null, matchKind: null, candidates: []]
    }

    private static Map<String, Map> definitionsByName(Object rawDefinitions) {
        Map<String, Map> out = new LinkedHashMap<>()
        if (rawDefinitions instanceof Map) {
            (rawDefinitions as Map).each { Object name, Object definition ->
                String key = text(name)
                if (key) out[key] = definition instanceof Map ? new LinkedHashMap(definition as Map) : [:]
            }
        } else if (rawDefinitions instanceof Collection) {
            (rawDefinitions as Collection).each { Object definition ->
                if (!(definition instanceof Map)) return
                String key = text((definition as Map).name)
                if (key) out[key] = new LinkedHashMap(definition as Map)
            }
        }
        return out
    }

    private static String independentlyEstablishedScope(String rawName, Object establishedScopes) {
        if (!(establishedScopes instanceof Map)) return ''
        String scope = text((establishedScopes as Map)[rawName]).toLowerCase()
        return scope in ['local', 'hub'] ? scope : ''
    }

    private static String removeOneTrailingPeriod(String value) {
        return value?.endsWith('.') ? value.substring(0, value.length() - 1) : value
    }

    private static String text(Object value) {
        return value == null ? '' : "${value}".trim()
    }
}
