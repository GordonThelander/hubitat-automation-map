/*
 * Piston Local Variable Extractor - freestanding investigation tool, not part of
 * Automation Map. Picks a named webCoRE piston and lists its own declared local
 * variables (name + type), decoded straight from the piston's saved chunk:N
 * configuration - the same proven algorithm Automation Map's own webCoRE Hub
 * Variable decoder uses (contiguous chunk:N settings -> concatenate -> Base64
 * UTF-8 -> webCoRE emoji decode -> JSON parse), just reading the document's own
 * "v" array (piston-local declarations) instead of @@ Hub Variable operands.
 *
 * Read-only: fetches the piston's own statusJson via loopback HTTP, decodes it,
 * displays results. Never writes to the piston or to any Hub Variable/device.
 */
import groovy.json.JsonSlurper
import java.net.URLDecoder

definition(
    name: "WC Local Variable Extract",
    namespace: "gordonthelander-tools",
    author: "Gordon Thelander",
    description: "Lists a named webCoRE piston's own declared local variables, decoded from its saved configuration.",
    category: "Utility",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "WC Local Variable Extract", install: true, uninstall: true) {
        section("") {
            List<Map> pistons = listWebcorePistons()
            if (!pistons) {
                paragraph "No webCoRE pistons found on this hub (or webCoRE is not installed)."
            } else {
                Map<String, String> options = [:]
                pistons.each { Map p -> options["${p.id}"] = "${p.name}" }
                input(name: "pistonId", type: "enum", title: "Piston", options: options, required: false, submitOnChange: true)
            }
            input(name: "runExtract", type: "button", title: "Extract local variables")
            if (state.result) {
                paragraph state.result
            }
        }
    }
}

void appButtonHandler(String btn) {
    if (btn == "runExtract") {
        state.result = pistonId ? extractLocalVariables(pistonId as String) : "Pick a piston first."
    }
}

// Enumerates every installed webCoRE piston via the same hub2/appsList endpoint
// thebearmay's wcDevAudit uses - {id, name} pairs, friendlier than raw app IDs.
List<Map> listWebcorePistons() {
    List<Map> found = []
    try {
        httpGet([uri: "http://127.0.0.1:8080", path: "/hub2/appsList"]) { resp ->
            (resp?.data?.apps ?: []).each { app ->
                if (app?.data?.type == "webCoRE") {
                    (app.children ?: []).each { child ->
                        if (child?.data?.type == "webCoRE Piston") {
                            found << [id: child.data.id, name: child.data.name]
                        }
                    }
                }
            }
        }
    } catch (Exception ex) {
        log.error "listWebcorePistons: ${ex.message}"
    }
    return found
}

String extractLocalVariables(String pistonId) {
    Map statusJson
    try {
        httpGet([uri: "http://127.0.0.1:8080/installedapp/statusJson/${pistonId}", contentType: "application/json"]) { resp ->
            statusJson = resp?.data as Map
        }
    } catch (Exception ex) {
        return "Could not read piston ${pistonId}: ${ex.message}"
    }
    if (!statusJson) return "No response reading piston ${pistonId}."

    Map decoded = decodePistonDocument(statusJson)
    if (decoded.status != 'complete') {
        return "Decode failed for piston ${pistonId}: ${decoded.status}${decoded.error ? " (${decoded.error})" : ''}"
    }

    List locals = (decoded.document.v instanceof List) ? decoded.document.v as List : []
    if (!locals) return "Piston ${pistonId} declares no local variables (empty or absent v array)."

    // Referenced-vs-unreferenced is a bonus, not the core ask - cheap to add
    // since the decoded document is already in hand, and matches how Automation
    // Map already treats an unused local variable as still worth showing.
    Set<String> referenced = [] as Set<String>
    collectReferencedNames(decoded.document, referenced)

    StringBuilder out = new StringBuilder()
    out << "<h4>${locals.size()} local variable(s) declared</h4><ul>"
    locals.each { Object declaration ->
        if (!(declaration instanceof Map)) return
        Map d = declaration as Map
        String name = "${d.n ?: '(unnamed)'}"
        String type = "${d.t ?: 'unknown'}"
        boolean isReferenced = referenced.contains(sanitizeLocalName(name))
        out << "<li><b>${name}</b> (${type})${isReferenced ? '' : ' - <i>not referenced anywhere in this piston</i>'}</li>"
    }
    out << "</ul>"
    return out.toString()
}

// --- Below: the exact decode algorithm Automation Map's decodeWebcoreHubVariableUses()
// uses, unmodified in substance - only the caller differs (reads v, not @@ operands).

Map decodePistonDocument(Map data) {
    Map<Integer, String> chunks = [:]
    Set<Integer> duplicates = [] as Set<Integer>
    if (data.appSettings != null && !(data.appSettings instanceof List)) {
        return [status: 'error', error: 'unexpected-settings']
    }
    (data.appSettings instanceof List ? data.appSettings : []).each { Object raw ->
        if (!(raw instanceof Map)) return
        Map setting = raw as Map
        def match = ("${setting.name ?: ''}" =~ /^chunk:([0-9]+)$/)
        if (!match.matches()) return
        int index = match[0][1] as int
        if (chunks.containsKey(index)) duplicates << index
        chunks[index] = setting.value == null ? null : "${setting.value}"
    }
    if (!chunks) return [status: 'not-present']
    if (duplicates) return [status: 'error', error: 'duplicate-chunk']
    if (!chunks.containsKey(0)) return [status: 'error', error: 'missing-chunk-zero']

    int maximum = chunks.keySet().max() as int
    if (maximum > 255) return [status: 'error', error: 'chunk-index-out-of-range']
    if ((0..maximum).any { !chunks.containsKey(it) }) return [status: 'error', error: 'missing-chunk']
    if ((0..maximum).any { chunks[it] == null || chunks[it].isEmpty() }) return [status: 'error', error: 'empty-chunk']
    int encodedLength = (0..maximum).sum { chunks[it].length() } as int
    if (encodedLength > 2_000_000) return [status: 'error', error: 'configuration-too-large']

    byte[] decodedBytes
    try {
        decodedBytes = (0..maximum).collect { chunks[it] }.join('').decodeBase64()
    } catch (Exception ignored) {
        return [status: 'error', error: 'invalid-base64']
    }

    Object document
    try {
        String json = decodeWebcoreEmoji(new String(decodedBytes, 'UTF-8'))
        document = new JsonSlurper().parseText(json)
    } catch (Exception ignored) {
        return [status: 'error', error: 'invalid-json']
    }
    if (!(document instanceof Map)) return [status: 'error', error: 'unexpected-root']

    return [status: 'complete', document: document]
}

String decodeWebcoreEmoji(String value) {
    if (!value) return ''
    return value.replaceAll(/(:%[0-9A-F]{2}%[0-9A-F]{2}%[0-9A-F]{2}%[0-9A-F]{2}:)/) { Object match ->
        String token = (match instanceof List ? match[0] : match) as String
        URLDecoder.decode(token.substring(1, 13), 'UTF-8')
    }
}

// Walks every typed variable operand (t:'x') in the document and records its
// base name, so a declared local can be flagged as referenced or not.
void collectReferencedNames(Object value, Set<String> names) {
    if (value instanceof List) {
        (value as List).each { Object child -> collectReferencedNames(child, names) }
        return
    }
    if (!(value instanceof Map)) return
    Map item = value as Map
    if (item.t == 'x' && item.x instanceof String) {
        names << sanitizeLocalName(baseVariableName(item.x as String))
    }
    item.values().each { Object child ->
        if (child instanceof Map || child instanceof List) collectReferencedNames(child, names)
    }
}

String baseVariableName(String name) {
    if (name && !name.startsWith('$') && !name.startsWith('@') && name.endsWith(']')) {
        List<String> parts = name.substring(0, name.length() - 1).tokenize('[')
        if (parts.size() == 2) return parts[0]
    }
    return name ?: ''
}

String sanitizeLocalName(String name) {
    name ? name.trim().replace(' ', '_') : ''
}
