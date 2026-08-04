/*
 * Automation Map Probe
 *
 * Throwaway diagnostic app - not the real Automation Map app.
 *
 * Hubitat's app sandbox only gives an app authorized access to devices the
 * user explicitly picks, and there is no official API for "list every
 * installed app and what it's configured with". The Automation Map app needs that
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

@Field static final String APP_NAME = 'Automation Map Probe'
@Field static final String APP_VERSION = '0.9.0'

definition(
    name: APP_NAME,
    namespace: 'Hubitat Integrations',
    author: 'Gordon Thelander',
    description: 'Throwaway diagnostic - discovers which internal hub endpoints are reachable, to inform the real Automation Map app.',
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
            paragraph 'Throwaway diagnostic for the Automation Map app. Set a Rule Machine rule id. Default 2279 = "Back Door Night", deliberately a small rule so its whole structure can be decoded against what the rule page shows.'
            input name: 'sampleAppId', type: 'number', title: 'Installed app id', required: true, defaultValue: 2279
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
    // Round 9: everything needed to render a rule as an ordered FLOWCHART
    // rather than a star of relationships. Known so far from bigger rules:
    //   actionList  ordered action numbers
    //   actions     action number -> {method, indent, rule, delay, wait}
    //   eval        rule/branch number -> condition numbers
    //   predCapabs  condition numbers forming the required expression
    //   capabstrue  trigger number -> human readable trigger text
    // and device settings hang off those numbers (rDev_<n>, tDev<n>,
    // onOffSwitch.<n>, volume.<n>, ...). What is still unknown is whether a
    // human-readable description exists for CONDITIONS the way capabstrue
    // provides one for triggers, and what the full method-name vocabulary is.
    //
    // So: dump the complete state and settings of one small rule whose logic
    // is already known from its own page, and decode against that.
    StringBuilder out = new StringBuilder()
    out << '<div style="white-space:normal; font-family:monospace; font-size:0.8em">'
    out << "<div style='margin-top:1em; padding:.5em; border:2px solid #666'>"
    out << "<b>/installedapp/statusJson/${sampleAppId}</b><br>"
    try {
        httpGet([uri: "http://127.0.0.1:8080/installedapp/statusJson/${sampleAppId}", timeout: 20]) { resp ->
            Map data = (resp.data instanceof Map) ? (resp.data as Map) : null
            if (data == null) {
                out << 'Response was not parsed into a Map.'
                return
            }
            Map installedApp = data.installedApp as Map
            out << "<pre style='white-space:pre-wrap; word-break:break-all'>label: ${installedApp?.label}   name: ${installedApp?.name}</pre>"
            out << describeState(data)
            out << describeSettings(data)
            out << describeSubscriptions(data)
        }
    } catch (Exception ex) {
        out << "ERROR: ${ex.message}"
    }
    out << '</div></div>'
    return out.toString()
}

String describeState(Map data) {
    StringBuilder out = new StringBuilder()
    out << "\n<pre style='white-space:pre-wrap; word-break:break-all'>=== APP STATE (all entries) ===\n"
    List entries = (data.appState ?: []) as List
    if (!entries) {
        out << '(none)\n'
    } else {
        entries.each { e ->
            if (!(e instanceof Map)) return
            Map em = e as Map
            out << "${em.name} [${em.type}] = ${trunc(em.value, 900)}\n"
        }
    }
    out << '</pre>'
    return out.toString()
}

String describeSettings(Map data) {
    StringBuilder out = new StringBuilder()
    out << "\n<pre style='white-space:pre-wrap; word-break:break-all'>=== APP SETTINGS (non-empty, or resolving to devices) ===\n"
    List settings = (data.appSettings ?: []) as List
    settings.each { s ->
        if (!(s instanceof Map)) return
        Map sm = s as Map
        boolean hasDevices = sm.deviceList as boolean
        String value = "${sm.value ?: ''}"
        if (!hasDevices && !value) return
        if (hasDevices) {
            out << "${sm.name} (${sm.type}) -> ${sm.deviceList}\n"
        } else {
            out << "${sm.name} (${sm.type}) = ${trunc(value, 200)}\n"
        }
    }
    out << '</pre>'
    return out.toString()
}

String describeSubscriptions(Map data) {
    StringBuilder out = new StringBuilder()
    out << "\n<pre style='white-space:pre-wrap; word-break:break-all'>=== EVENT SUBSCRIPTIONS ===\n"
    List subs = (data.eventSubscriptions ?: []) as List
    if (!subs) {
        out << '(none)\n'
    } else {
        subs.each { s ->
            if (!(s instanceof Map)) return
            Map sm = s as Map
            out << "${sm.typeName} (id ${sm.typeId}) attr=${sm.name} handler=${sm.handler} subscriptionData=${sm.subscriptionData}\n"
        }
    }
    out << '</pre>'
    return out.toString()
}

String trunc(Object value, int limit) {
    String s = "${value}"
    return s.length() > limit ? s.substring(0, limit) + '...[cut]' : s
}
