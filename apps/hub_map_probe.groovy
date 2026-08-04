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
@Field static final String APP_VERSION = '0.2.0'

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
            paragraph 'Throwaway diagnostic for the Hub Map app. Pick a real device ID and a real installed-app ID (visible in their URLs in the hub UI), then turn on "Run probe now". Copy the results text back out once it renders.'
            input name: 'sampleDeviceId', type: 'number', title: 'A real device ID to probe', required: true, defaultValue: 2999
            input name: 'sampleAppId', type: 'number', title: 'A real installed-app ID to probe (e.g. Zigbee Map)', required: true, defaultValue: 2943
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
    // Round 2: round 1 confirmed /device/fullJson/<id> returns real JSON with an
    // "appsUsingForDialog" list (device -> apps that reference it = usage edges).
    // This round dumps that endpoint IN FULL (looking for parentAppId/parentDeviceId/
    // childDevices = ownership edges), tries the equivalent guess for installed apps,
    // and tries two guesses at bulk list endpoints (would remove the need to derive
    // the app list from the union of every device's appsUsingForDialog).
    List<String> paths = [
        "/device/fullJson/${sampleDeviceId}",
        "/installedapp/fullJson/${sampleAppId}",
        '/installedapp/list.json',
        '/device/list.json',
    ]

    StringBuilder out = new StringBuilder()
    out << '<div style="white-space:normal; font-family:monospace; font-size:0.8em">'
    paths.each { String path -> out << probeOne(path, 6000) }
    out << '</div>'
    return out.toString()
}

String probeOne(String path, int limit) {
    StringBuilder out = new StringBuilder()
    out << "<div style='margin-top:1em; padding:.5em; border:1px solid #ccc'>"
    out << "<b>${path}</b><br>"
    try {
        httpGet([uri: "http://127.0.0.1:8080${path}", textParser: true, timeout: 8, ignoreSSLIssues: true]) { resp ->
            String body = resp?.data?.text ?: ''
            int fullLength = body.length()
            if (body.length() > limit) body = body.substring(0, limit) + '... [truncated]'
            String escaped = body.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
            out << "Status: ${resp.status}, Content-Type: ${resp.headers?.'Content-Type'}, Full length: ${fullLength}<br>"
            out << "<pre style='white-space:pre-wrap; word-break:break-all'>${escaped}</pre>"
        }
    } catch (Exception ex) {
        out << "ERROR: ${ex.message}"
    }
    out << '</div>'
    return out.toString()
}
