/*
 * Hub Map Probe
 *
 * Throwaway diagnostic app - not the real Hub Map app.
 *
 * Hubitat's app sandbox only gives an app authorized access to devices the
 * user explicitly picks, and there is no official API for "list every
 * installed app and what it's configured with". The Hub Map app needs that
 * data, so it has to fetch it from the hub's own internal admin endpoints
 * (the same ones the hub's own web UI calls), via a self-request to
 * 127.0.0.1. Nobody has documented which paths exist or what shape they
 * return, so this probe fetches a batch of likely candidates and prints
 * whatever comes back, so the real app can be written against confirmed
 * facts instead of guesses.
 *
 * Install, run the probe once, copy the results, then delete this app.
 */
import groovy.transform.Field

@Field static final String APP_NAME = 'Hub Map Probe'
@Field static final String APP_VERSION = '0.3.0'

definition(
    name: APP_NAME,
    namespace: 'Hubitat Integrations',
    author: 'Gordon Thelander',
    description: 'Throwaway diagnostic - discovers which internal hub endpoints are reachable, to inform the real Hub Map app.',
    category: 'Utility',
    iconUrl: '',
    iconX2Url: '',
    singleInstance: true,
)

preferences {
    page name: 'main'
}

void installed() {
    log.info "${app.label} installed"
}

void updated() {
    log.info "${app.label} updated"
}

Map main() {
    return dynamicPage(name: 'main', title: "<b>${APP_NAME} v${APP_VERSION}</b>", install: true, uninstall: true) {
        section {
            paragraph 'Throwaway diagnostic for the Hub Map app. Set a real Rule Machine rule ID (from its URL in the hub UI), then turn on "Run probe now". Copy the results text back out once it renders.'
            input name: 'sampleRuleId', type: 'number', title: 'A real Rule Machine rule ID to probe', required: true, defaultValue: 2816
            input name: 'runProbe', type: 'bool', title: 'Run probe now', submitOnChange: true, defaultValue: false
        }
        if (runProbe) {
            section('Results (copy this out, then delete this app)') {
                paragraph buildProbeReport()
            }
        }
    }
}

String buildProbeReport() {
    // Round 3: hunting for REAL Rule Machine rule structure (trigger vs
    // condition vs action), which /device/fullJson cannot provide - it only
    // says which apps touch a device, not how. The App Status page is the
    // best lead: it is an older server-rendered page (not the SPA shell that
    // /installedapp/configure returned) and lists Event Subscriptions, which
    // is how Rule Machine registers its trigger devices specifically.
    //
    // HTML bodies are searched for keywords rather than dumped from the top,
    // because round 1 showed the first several KB of any hub page is just
    // boilerplate <head> shell.
    List<String> paths = [
        "/installedapp/status/${sampleRuleId}",
        "/installedapp/statusJson/${sampleRuleId}",
        "/installedapp/settings/${sampleRuleId}",
        "/installedapp/subscriptions/${sampleRuleId}",
        "/installedapp/eventSubscriptions/${sampleRuleId}",
        "/hub/rules/${sampleRuleId}",
    ]

    List<String> keywords = ['subscription', 'Presence Manager', 'trigger', 'Guest Mode', 'setting']

    StringBuilder out = new StringBuilder()
    out << '<div style="white-space:normal; font-family:monospace; font-size:0.8em">'
    paths.each { String path -> out << probeOne(path, keywords) }
    out << '</div>'
    return out.toString()
}

String probeOne(String path, List<String> keywords) {
    StringBuilder out = new StringBuilder()
    out << "<div style='margin-top:1em; padding:.5em; border:1px solid #ccc'>"
    out << "<b>${path}</b><br>"
    try {
        httpGet([uri: "http://127.0.0.1:8080${path}", textParser: true, timeout: 10, ignoreSSLIssues: true]) { resp ->
            String body = resp?.data?.text ?: ''
            String contentType = "${resp.headers?.'Content-Type'}"
            out << "Status: ${resp.status}, Content-Type: ${contentType}, Full length: ${body.length()}<br>"

            String extract
            if (contentType.contains('json')) {
                extract = body.length() > 6000 ? body.substring(0, 6000) + '... [truncated]' : body
            } else {
                extract = extractKeywordWindows(body, keywords, 700)
            }
            String escaped = extract.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
            out << "<pre style='white-space:pre-wrap; word-break:break-all'>${escaped}</pre>"
        }
    } catch (Exception ex) {
        out << "ERROR: ${ex.message}"
    }
    out << '</div>'
    return out.toString()
}

String extractKeywordWindows(String body, List<String> keywords, int window) {
    String lowerBody = body.toLowerCase()
    StringBuilder found = new StringBuilder()
    keywords.each { String kw ->
        int idx = lowerBody.indexOf(kw.toLowerCase())
        if (idx < 0) {
            found << "\n=== no match for '${kw}' ===\n"
            return
        }
        int start = (idx - window) < 0 ? 0 : (idx - window)
        int end = (idx + window) > body.length() ? body.length() : (idx + window)
        found << "\n=== match '${kw}' at offset ${idx} ===\n"
        found << body.substring(start, end)
        found << '\n'
    }
    return found.toString()
}
