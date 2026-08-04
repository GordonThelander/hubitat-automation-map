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
@Field static final String APP_VERSION = '0.5.0'

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
    // Round 5: round 4 proved the rule structure IS in statusJson (rDev_NN
    // settings hold condition devices; state vars eval / predCapabs /
    // capabstrue / actionList tie those numbers to trigger vs required-
    // expression vs action). String-searching a 140KB blob is the wrong tool
    // for confirming the rest, so this round lets Hubitat parse the JSON and
    // walks the structure instead.
    //
    // Priority question: does this payload carry EVENT SUBSCRIPTIONS? An app
    // subscribes only to devices it listens to, which separates trigger from
    // target for EVERY app, not just Rule Machine - a far more general and
    // durable signal than reverse-engineering RM's private rule format.
    StringBuilder out = new StringBuilder()
    out << '<div style="white-space:normal; font-family:monospace; font-size:0.8em">'
    out << "<div style='margin-top:1em; padding:.5em; border:1px solid #ccc'>"
    out << "<b>/installedapp/statusJson/${sampleRuleId} (parsed)</b><br>"
    try {
        httpGet([uri: "http://127.0.0.1:8080/installedapp/statusJson/${sampleRuleId}", timeout: 20]) { resp ->
            Map data = (resp.data instanceof Map) ? (resp.data as Map) : null
            if (data == null) {
                out << 'Response was not parsed into a Map.'
            } else {
                out << describeTopLevel(data)
                out << describeSettingsWithDevices(data)
                out << describeStateEntries(data)
            }
        }
    } catch (Exception ex) {
        out << "ERROR: ${ex.message}"
    }
    out << '</div></div>'
    return out.toString()
}

String describeTopLevel(Map data) {
    StringBuilder out = new StringBuilder()
    out << "\n<pre style='white-space:pre-wrap; word-break:break-all'>=== TOP-LEVEL KEYS ===\n"
    data.each { k, v ->
        if (v instanceof List) {
            List list = v as List
            String firstKeys = (list && list[0] instanceof Map) ? " firstElementKeys=${(list[0] as Map).keySet()}" : ''
            out << "${k}: List(size=${list.size()})${firstKeys}\n"
        } else if (v instanceof Map) {
            out << "${k}: Map keys=${(v as Map).keySet()}\n"
        } else {
            out << "${k}: ${trunc(v, 120)}\n"
        }
    }
    out << '</pre>'
    return out.toString()
}

String describeSettingsWithDevices(Map data) {
    StringBuilder out = new StringBuilder()
    out << "\n<pre style='white-space:pre-wrap; word-break:break-all'>=== SETTINGS THAT RESOLVE TO DEVICES ===\n"
    List settings = (data.appSettings ?: data.settings ?: []) as List
    int shown = 0
    settings.each { s ->
        if (!(s instanceof Map)) return
        Map sm = s as Map
        if (sm.deviceList) {
            out << "${sm.name} (type=${sm.type}) -> ${sm.deviceList}\n"
            shown++
        }
    }
    if (shown == 0) out << '(none found - check the top-level key holding settings)\n'
    out << '</pre>'
    return out.toString()
}

String describeStateEntries(Map data) {
    StringBuilder out = new StringBuilder()
    // The state list is whichever top-level List whose elements look like
    // {name:..., value:..., type:...} - discovered rather than assumed.
    List stateList = null
    String stateKey = null
    data.each { k, v ->
        if (stateList != null) return
        if (!(v instanceof List)) return
        List list = v as List
        if (list && list[0] instanceof Map) {
            Map first = list[0] as Map
            if (first.containsKey('name') && first.containsKey('value') && first.containsKey('type')) {
                stateList = list
                stateKey = k as String
            }
        }
    }

    out << "\n<pre style='white-space:pre-wrap; word-break:break-all'>=== STATE ENTRIES (from key '${stateKey}') ===\n"
    if (stateList == null) {
        out << '(no state-shaped list found)\n'
    } else {
        stateList.each { s ->
            Map sm = s as Map
            out << "${sm.name} [${sm.type}] = ${trunc(sm.value, 400)}\n"
        }
    }
    out << '</pre>'
    return out.toString()
}

String trunc(Object value, int limit) {
    String s = "${value}"
    return s.length() > limit ? s.substring(0, limit) + '...[cut]' : s
}
