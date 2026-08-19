/*
 * Automation Map
 *
 * Copyright 2026 Gordon Thelander
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 * Visualizes how installed Hubitat apps and devices relate to each other, and
 * in what ROLE - which app owns a device, which devices trigger an app, which
 * constrain it, and which it acts on - as an interactive force-directed graph,
 * in the same visual style as Dan Danache's Zigbee Map app.
 *
 * There is no official Hubitat API for any of this. The data comes from the
 * hub's own internal endpoints (the ones the hub's own web UI calls), fetched
 * via a self-request to 127.0.0.1 - an established community technique, not a
 * public API:
 *
 *   /device/fullJson/<id>         parentApp + appsUsing (NOT appsUsingForDialog,
 *                                 which the hub caps at five entries per device
 *                                 with only a count of the remainder), used to
 *                                 DISCOVER which app ids exist
 *   /hub2/appsList                the complete installed-app tree in one call,
 *                                 unioned with device-led discovery so an app
 *                                 that touches no device is not invisible
 *   /installedapp/statusJson/<id> the real relationship data per app:
 *                                 childDevices, eventSubscriptions, and every
 *                                 setting that resolves to devices
 *
 * Role assignment, derived by probing apps whose source is known (Presence
 * Manager, LIFX Light Manager) plus real rules, and cross-checked against both
 * that source and the rules' own UI. Checked in this order:
 *
 *   in childDevices          -> owns       (LIFX: 12 child lights, no subs)
 *   setting named tDev*      -> trigger    (RM trigger devices)
 *   setting named rDev*      -> constraint (RM conditions + required expression)
 *   device is subscribed     -> trigger    (general: an app subscribes to what
 *                                           it listens to. Presence Manager's 5
 *                                           subs matched subscribeEvidence-
 *                                           Devices() exactly)
 *   capability has no commands -> monitor  (watched, not driven - Critical
 *                                           Device Monitor inspects contact and
 *                                           motion pickers it never commands)
 *   any other device setting -> action     (onOffSwitch.*, volume.*, note.*,
 *                                           siren.*, chime.*, speakDevice.*)
 *
 * Only the tDev/rDev rules are Rule Machine's private naming. childDevices,
 * eventSubscriptions and capability types are platform-level, so the graph
 * works for apps this was never written against - it handled all 17 app types
 * on the development hub, 12 of them integrations with no specific support.
 *
 * Rule FLOW decoding is different: it reads Rule Machine's internal layout and
 * is pinned to SUPPORTED_RULE_ENGINE. Rules on any other engine still appear in
 * the graph; they are counted and reported rather than silently empty.
 *
 * Known limitations:
 *  - Event subscriptions are a snapshot: Rule Machine drops trigger
 *    subscriptions while a Required Expression is false.
 *  - If Hub Login Security is enabled the internal endpoints may not return
 *    JSON at all; the scan probes for this and reports it rather than showing
 *    an empty map.
 */
import groovy.transform.Field
import groovy.json.JsonOutput
import java.util.regex.Pattern

@Field static final String APP_NAME = 'Automation Map (Dev)'
// Every build of this app excludes all of its own variants from the map,
// whatever each one calls itself. A dev copy installed beside the release would
// otherwise show up as an app referencing every device on the hub, and the
// release would do the same from the dev copy's point of view.
@Field static final String APP_FAMILY = 'Automation Map'
@Field static final String APP_VERSION = '2.0.0'
// Bumped ONLY when the shape of the scanned graph changes, so that a rendering
// or scanning fix does not needlessly invalidate a good scan and force the user
// to re-crawl every device and app.
// Bumped for the stops->cancelTimedActions / pauses->pauseResume kind rename
// and the addition of node.missing - a cached graph from schema 2 would
// otherwise render with edge kinds that no longer match any colour/dash
// lookup, degrading silently to the '#999' fallback instead of forcing the
// rescan that already exists for exactly this situation.
@Field static final String GRAPH_SCHEMA = '5'

// A once-a-year decoration, embedded as a data URI rather than hosted -
// this app has no other place to serve an asset from, and a ~29KB addition
// to the page for five days a year is not worth standing up file storage
// for. Source PNG is 21.7KB.
@Field static final String SANTA_PNG_B64 = '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'

// Confirmed on a live preview 2026-08-14 (placement, size and caption all
// checked against the actual rendered page before this gate went in).
boolean showSanta() {
    Calendar cal = Calendar.getInstance(location.timeZone)
    cal.setTime(new Date())
    int month = cal.get(Calendar.MONTH) + 1
    int day = cal.get(Calendar.DAY_OF_MONTH)
    return month == 12 && day >= 20 && day <= 25
}
// Rule flow decoding reads Rule Machine's private internals, so it is pinned to
// the version it was verified against. Rules on any other engine still appear
// in the graph with their device relationships; they are counted and reported
// rather than silently producing an empty flow.
@Field static final String SUPPORTED_RULE_ENGINE = 'Rule-5.1'
// Named once here, not repeated as a literal in compatibilitySummary(),
// so a future engine addition needs one edit rather than finding every
// place SUPPORTED_RULE_ENGINE used to stand in for "everything decoded".
@Field static final String DECODED_ENGINES_TEXT = 'Rule-5.1, Notifier, and Visual Rule Builder 2.0 (in Beta)'
@Field static final Pattern URL_PATTERN = ~/^https?:\/\/[^\/]+(.+)/
// Origin only (scheme+host), for the browser to compare against its own
// window.location.hostname at fetch time. Kept as its own pattern rather than
// reworking URL_PATTERN's grouping - that one is proven correct in production
// and touching its group indices to add a second capture risks breaking the
// local-path case for every user to fix a case that only affects some.
@Field static final Pattern ORIGIN_PATTERN = ~/^(https?:\/\/[^\/]+)/
@Field static final Integer DEVICE_BATCH_SIZE = 15
@Field static final Integer APP_BATCH_SIZE = 3

definition(
    name: APP_NAME,
    namespace: 'Hubitat Integrations',
    author: 'Gordon Thelander',
    description: 'Visualize how installed apps and devices relate to each other on the hub.',
    category: 'Utility',
    iconUrl: '',
    iconX2Url: '',
    singleInstance: true,
    oauth: true,
)

preferences {
    page name: 'main'
}

void installed() {
    log.info "${app.label} installed"
    // Pressing Done is the first moment the instance exists and work can be
    // scheduled for it, so the first scan starts here rather than asking the
    // user to press Done and then come back in to start one - which reads as
    // though the install did not take.
    log.info "${app.label}: starting first scan"
    startScan()
    scheduleAutoScan()
}

void updated() {
    log.info "${app.label} updated"
    // Rescheduled on every updated(), which is also how this survives a hub
    // reboot - Hubitat re-runs updated() for every installed app on boot, so
    // the schedule() call here re-establishes the cron rather than relying
    // on it having persisted through the restart. Not directly confirmed on
    // this hub; standard platform behaviour, worth a real reboot test if
    // this schedule is ever reported as silently not firing.
    scheduleAutoScan()
}

// On by default (00:30) - the app-wide scan-first-then-explore experience
// this app is built around is better served by a map that keeps itself
// current than by one that goes stale until someone remembers to press Scan.
// The toggle below is still there to opt out entirely. Rescheduled (not just
// scheduled once) every time this runs, so turning the toggle off actually
// cancels a previously-running schedule rather than leaving it firing.
void scheduleAutoScan() {
    unschedule('scheduledScanHandler')
    if (!settings.autoScanEnabled) return
    if (settings.autoScanTime) {
        // schedule() accepts the exact string a Hubitat "time" input stores
        // and reschedules it daily - standard, documented platform pattern,
        // not yet confirmed live against this specific input on this hub.
        schedule(settings.autoScanTime as String, 'scheduledScanHandler')
    } else {
        // Cron default: 00:30:00 every day (sec min hour day month weekday).
        schedule('0 30 0 * * ?', 'scheduledScanHandler')
    }
    log.info "${app.label}: automatic scan scheduled for ${settings.autoScanTime ?: '00:30 (default)'}"
}

// Guarded against overlapping a scan already in progress - a manual press
// via the Scan button, or a previous scheduled run that is still going on a
// large hub - rather than racing it. Skipping silently here is correct: the
// next scheduled run, or a manual press, covers it, and clearAbandonedScan()
// already handles a scan that genuinely got stuck.
void scheduledScanHandler() {
    if (state.scanRunning) {
        log.info "${app.label}: scheduled scan skipped, one is already running"
        return
    }
    log.info "${app.label}: starting scheduled overnight scan"
    startScan()
}

Map main() {
    // Until Done is pressed the instance does not exist yet, and Hubitat cannot
    // schedule work for it: runIn() silently does nothing, so a scan started
    // from this page would set itself running and then never execute a single
    // batch. So the scan is not offered at all until installation completes.
    boolean ready = app.installationState == 'COMPLETE'
    if (ready && !state.accessToken) createAccessToken()
    clearAbandonedScan()

    // A full scan takes a couple of minutes. Without this the page looked frozen
    // - the progress line only moved if you closed and reopened it, which reads
    // as a hang rather than as work in progress.
    return dynamicPage(name: 'main', title: "<b>${APP_NAME} v${APP_VERSION}</b>", install: true, uninstall: ready,
                       refreshInterval: (ready && state.scanRunning) ? 4 : 0) {
        // Scan status, the map link and the Scan button all sit ABOVE the device
        // picker. The picker renders as a list of every device on the hub, so
        // anything below it is off the bottom of the screen - which is where the
        // progress line and the link to the map used to be on every visit after
        // the first scan.
        if (ready) {
            section {
                // The scan is started by fetching the app's own /scan endpoint
                // rather than from a Hubitat button. runIn() called out of
                // appButtonHandler does not reliably schedule anything: on a
                // clean install the queue was populated, scanRunning was true,
                // no job was scheduled, and scanBatch never ran even once - its
                // heartbeat was never written. Driving it through the endpoint
                // runs the scan in an ordinary app execution, which works.
                paragraph scanButtonHtml()
                if (state.scanTotal) {
                    Integer done = (state.scanDone ?: 0) as Integer
                    Integer total = (state.scanTotal ?: 1) as Integer
                    Integer pct = total > 0 ? ((done * 100) / total) as Integer : 0
                    String phase = state.scanPhase == 'apps' ? 'Reading apps' : 'Reading devices'
                    String progress = "${phase}: ${done} of ${total} (${pct}%)"
                    if (state.scanRunning) {
                        progress += ' - this page updates itself, no need to reload.'
                    } else {
                        progress = "Last scan: ${done} of ${total} ${state.scanPhase == 'apps' ? 'apps' : 'devices'}."
                    }
                    paragraph progress
                }
                if (state.scanError) {
                    paragraph "<b style='color:#c0392b'>Scan error: ${state.scanError}</b>"
                }
                if (state.graph) {
                    Map g = state.graph as Map
                    if (state.scanRunning) {
                        // Nothing here while a scan is running. startScan clears
                        // graphVersion but keeps the old graph, so graphIsStale()
                        // is true for the whole scan - which used to show "run the
                        // scan again to rebuild it" to someone watching that very
                        // scan run. The else branch is no better mid-scan: it
                        // reports the previous graph's counts and offers a map
                        // link that refuses to render. The progress line above
                        // already says what is happening.
                    } else if (graphIsStale()) {
                        // A graph built by an older version can carry relationship
                        // kinds this version no longer renders, which silently draws
                        // as uncoloured edges rather than failing visibly.
                        paragraph "<b style='color:#c0392b'>This map was saved in a format this release no longer reads. Run the scan again to rebuild it.</b>"
                    } else {
                        paragraph "Map ready: ${(g.nodes ?: []).size()} nodes, ${(g.edges ?: []).size()} relationships."
                        paragraph compatibilitySummary()
                        href(
                            name: 'mapLink', title: 'View Automation Map',
                            description: 'Open the relationship graph',
                            url: getLocalURL('automation-map.html'),
                            style: 'embedded', state: 'complete', required: false,
                        )
                        paragraph "Need help or found a problem? Visit the <a href='https://community.hubitat.com/t/release-hubitat-automation-map/165524' target='_blank'>Automation Map community thread</a> for setup advice, known issues, and support."
                    }
                }
            }
            section {
                input name: 'autoScanEnabled', type: 'bool',
                    title: 'Scan automatically every day',
                    description: 'On by default at 00:30. Turn off if you would rather press Scan yourself.',
                    defaultValue: true, submitOnChange: true
                if (settings.autoScanEnabled) {
                    input name: 'autoScanTime', type: 'time',
                        title: 'Time to run the scan',
                        description: 'Leave blank for 00:30.', required: false
                }
            }
        }

        if (!ready) {
            section {
                paragraph '<b>Press <i>Done</i> to install Automation Map.</b> <span style="color:#c0392b"><b>Your first scan then starts by itself and takes a couple of minutes on a large hub. Open the app again to watch it and to view the map.</b></span>'
                paragraph '<span style="opacity:0.75">There is nothing to configure. Every device on the hub is scanned, and the apps are found by asking each device which apps use it.</span>'
            }
        }
    }
}

// Kept so an existing installation with the old button still works, but the
// page no longer renders that button - see scanButtonHtml().
void appButtonHandler(String btn) {
    if (btn == 'runScan') startScan()
}

// True when the app is ready to work but has never produced a map. Opening it in
// that state starts a scan on its own, so an install that somehow got past
// installed() without scanning still recovers rather than sitting idle.
boolean shouldAutoScan() {
    return app.installationState == 'COMPLETE' &&
           !state.graph &&
           !state.scanRunning &&
           !state.scanError
}

String scanButtonHtml() {
    String label = state.scanRunning ? 'Scanning...' : (shouldAutoScan() ? 'Starting first scan...' : 'Scan relationships now')
    String disabled = state.scanRunning ? ' disabled' : ''
    // Hubitat's UI is PrimeVue, so a plain button or Bootstrap classes render
    // unstyled. These are the classes and data attributes its own buttons carry.
    String cls = state.scanRunning ? 'p-button p-component p-disabled mr-2 mb-2' : 'p-button p-component mr-2 mb-2'
    return """\
<button type="button" id="amScanBtn" class="${cls}"${disabled} onclick="amStartScan()" aria-label="${label}" data-pc-name="button" data-pc-section="root" data-pd-ripple="true">${label}</button>
<span id="amScanMsg" style="margin-left:10px"></span>
<script type="text/javascript">
// Picks the local relative path when the browser is on the hub's own origin
// (the common case: fast, no internet dependency) and falls back to the
// absolute cloud URL otherwise - Remote Admin, or anything else that isn't
// the hub's own LAN address. Checked at click time against the real current
// origin, not guessed once at page-render time on the server, because the
// server has no reliable way to know which origin THIS page request came in
// on for a native Hubitat-rendered page.
function amPickURL(localPath, cloudUrl) {
  try {
    if (new URL('${getLocalOrigin()}').hostname === window.location.hostname) return localPath;
  } catch (ignore) { }
  return cloudUrl;
}
function amStartScan() {
  var b = document.getElementById('amScanBtn');
  var m = document.getElementById('amScanMsg');
  b.disabled = true;
  m.textContent = 'Starting...';
  // credentials:'omit' is load-bearing, not tidiness. Sending the Hubitat
  // session cookie makes the hub treat this as part of the open UI transaction,
  // and scheduled jobs created inside one are discarded - startScan() would
  // populate the queue and set scanRunning, then runIn() would silently
  // schedule nothing and scanBatch would never execute. Authenticating with the
  // access token alone runs it as an ordinary request, which schedules.
  // Reads the body as TEXT and parses it here, rather than calling r.json()
  // and letting the browser throw. A raw "Unexpected token '<'" tells you only
  // that something answered with HTML - not WHO answered, which is the entire
  // question when the same symptom can come from an expired token, Hub Login
  // Security, a cloud/remote origin, or the hub's own exception page. Status,
  // final URL after redirects, content-type and the first 200 characters of
  // the body separate all four; the parse error separates none of them.
  var scanUrl = amPickURL('${getLocalURL('scan')}', '${getCloudURL('scan')}');
  // Confirmed live against a real Remote Admin session: the cloud URL builds
  // correctly, but fetch() rejects with "Failed to fetch" - a CORS failure,
  // not a bad URL. Hubitat's cloud API does not send the headers a
  // cross-origin fetch() needs to read its response, and that is not
  // something either side of this app can configure around.
  //
  // CORS only restricts reading a cross-origin response - it does not stop
  // the request from being sent, and it does not apply to navigation at all.
  // So the cloud case fires the same URL as a hidden iframe navigation
  // instead of fetch(): scanMapping() still runs and still starts the scan,
  // this code just cannot see what it returned. The reload below then shows
  // the truth via the page's own state, the same way the success path
  // already relies on a reload rather than reading the response.
  if (scanUrl.indexOf('http') === 0) {
    m.textContent = 'Starting via Hubitat cloud - this page will reload in a few seconds to show the result.';
    var f = document.createElement('iframe');
    f.style.display = 'none';
    f.src = scanUrl;
    document.body.appendChild(f);
    setTimeout(function () { location.reload(); }, 4000);
    return;
  }
  fetch(scanUrl, { cache: 'no-store', credentials: 'omit' })
    .then(function (r) {
      return r.text().then(function (body) {
        var ct = r.headers.get('content-type') || 'none';
        // Origin + path ONLY, never the query string. getLocalURL() puts the
        // live OAuth access token in the URL, r.url carries it verbatim, and
        // this text is written to be pasted into a public forum thread. The
        // HOST is the whole diagnostic value here (local hub vs cloud vs
        // something else); the token adds nothing and leaks everything.
        var where = '(no response URL)';
        try { var u = new URL(r.url); where = u.origin + u.pathname; } catch (ignore) { }
        // Same reason: a Hubitat login or error page can echo the requested
        // URL back inside its own body.
        var safeBody = body.slice(0, 200).replace(/\\s+/g, ' ')
                           .replace(/access_token=[^&\\s]*/g, 'access_token=REDACTED');
        var detail = 'HTTP ' + r.status + ' | type ' + ct + ' | from ' + where +
                     ' | body starts: ' + safeBody;
        if (!r.ok) throw new Error(detail);
        var d;
        try { d = JSON.parse(body); }
        catch (parseErr) { throw new Error('the hub did not return JSON. ' + detail); }
        if (d && d.ok === false) throw new Error(d.error || 'the hub reported a failure with no detail.');
        return d;
      });
    })
    .then(function () { m.textContent = 'Scanning - this page updates itself.'; setTimeout(function () { location.reload(); }, 2000); })
    .catch(function (e) {
      b.disabled = false;
      var where = '(could not parse the attempted URL)';
      try { var u = new URL(scanUrl, window.location.href); where = u.origin + u.pathname; } catch (ignore) { }
      m.textContent = 'Could not start the scan: ' + e.message + ' | tried: ' + where;
    });
}
${autoScanScript()}
</script>"""
}

// Fired from the browser rather than from the page render, for the same reason
// the button is: a scan started inside Hubitat's UI transaction never gets
// scheduled. Guarded by shouldAutoScan(), which stops being true the moment the
// scan sets scanRunning, so a page refresh cannot start a second one.
String autoScanScript() {
    if (!shouldAutoScan()) return ''
    return '''
document.addEventListener('DOMContentLoaded', function () { amStartScan(); });
if (document.readyState !== 'loading') { amStartScan(); }
'''
}

boolean graphIsStale() {
    return state.graph && state.graphVersion != GRAPH_SCHEMA
}

// A scan that stops without finishing leaves scanRunning set, which disables the
// button and shows a progress line that never moves - the app looks permanently
// mid-scan with no way back. That happens if the hub restarts or the app is
// updated mid-scan, and it happened to anyone who pressed Scan before the app
// finished installing. scanBatch stamps a heartbeat every batch, so a scan whose
// heartbeat has stopped advancing is over whatever its flag says.
void clearAbandonedScan() {
    if (!state.scanRunning) return
    Long beat = (state.scanHeartbeat ?: 0) as Long
    if (beat > 0 && (now() - beat) < 90000) return

    // The batch-reading work itself can finish - queue empty, every app
    // already read into appInfo - while the scheduled finalization
    // (fetchRegistry -> finishScan, or its 45-second watchdog) never runs at
    // all. runIn() is already known to be unreliable on this platform - see
    // the Scan button's own comment on why it does not use
    // appButtonHandler - and this is the same failure class landing on a
    // different scheduled call. Confirmed live: a 98-app scan reached
    // scanDone == scanTotal with an empty scanQueue, sat past the 90-second
    // heartbeat timeout, and state.graph was still empty - nothing left to
    // read, nothing running, just never finished.
    //
    // finishScan() itself makes no HTTP calls - it is buildGraph() over data
    // this app already collected, plus bookkeeping - so calling it directly
    // here, synchronously, cannot fail the same way a scheduled job can.
    // Previously this branch discarded a fully-read scan and told the user
    // to start over from zero; now it finishes the one step that never got
    // the chance to run.
    List queue = (state.scanQueue ?: []) as List
    if (!queue && state.scanPhase == 'apps') {
        log.warn "${app.label}: scan batches finished but finalization never ran - finishing now instead of discarding it"
        finishScan()
        return
    }

    state.scanRunning = false
    state.scanError = 'The previous scan stopped before it finished. Press Scan to run it again.'
    log.warn "${app.label}: clearing an abandoned scan"
}

// Reports what this hub actually supported, so a user whose hub differs sees a
// reason rather than an unexplained gap. Rule flows are decoded from Rule
// Machine 5.1's private layout; other rule engines still appear in the graph
// but have no flow.
String compatibilitySummary() {
    int decoded = (state.appsDecoded ?: 0) as Integer
    int unreadable = (state.appsUnreadable ?: 0) as Integer
    int rules = (state.rulesDecoded ?: 0) as Integer

    StringBuilder s = new StringBuilder()
    if (state.compatOk == false) {
        s << "<b style='color:#c0392b'>${state.compatDetail}</b><br>"
    }
    int devUnreadable = ((state.deviceIdsUnreadable ?: []) as List).size()
    if (devUnreadable > 0) {
        s << "<b style='color:#c0392b'>${devUnreadable} device(s) could not be read</b> and are missing from this map, along with any app only discoverable through them. "
    }
    s << "Read ${decoded} app(s)"
    if (unreadable > 0) s << ", <b>${unreadable} could not be read</b>"
    s << ". Decoded ${rules} flow(s)."

    // Said out loud because the number is usually small and sometimes zero, and
    // a scan that quietly claims completeness it did not earn is the thing this
    // whole discovery change exists to prevent.
    int listed = (state.appsFromListing ?: 0) as Integer
    if (listed > 0) {
        s << " ${listed} of them reference no device and would not have been found by walking devices alone."
    }

    // The count above is apps READ. Until 1.8.1 the map drew fewer than it read
    // and said nothing about the difference, so the summary, the Focus app list
    // and the map itself disagreed with each other. They are reconciled here
    // rather than by quietly reporting the smaller number.
    int inert = (state.appsInert ?: 0) as Integer
    if (inert > 0) {
        s << " ${inert} touch no device and link to no rule; they are drawn apart from the network, each labelled with why."
    }

    int links = (state.ruleLinks ?: 0) as Integer
    if (links > 0) {
        s << " Found ${links} rule-to-rule link(s)."
    } else {
        s << " No rule-to-rule links found - no rule on this hub runs, cancels timed actions on, pauses/resumes, or sets the Private Boolean of another."
    }

    int skipped = (state.rulesSkipped ?: 0) as Integer
    if (skipped > 0) {
        List engines = (state.otherEngines ?: []) as List
        s << "<br><b style='color:#b9770e'>${skipped} rule(s) on ${engines.join(', ')} were not decoded</b> - flow decoding supports ${DECODED_ENGINES_TEXT} only. They still appear in the map with their device relationships."
    } else {
        s << "<br><span style='opacity:0.75'>Flow decoding supports ${DECODED_ENGINES_TEXT}. Apps that are not rules appear in the map with their device relationships.</span>"
    }
    return s.toString()
}

// ===================================================================================================================
// Scanning - phase 1 discovers app ids from devices, phase 2 pulls each app's real relationships
// ===================================================================================================================

// Everything this app knows comes from undocumented hub endpoints, so on a hub
// unlike the one it was written against it must say WHY it found nothing rather
// than presenting an empty map as if that were the answer. The most likely
// environmental difference is hub login security, which makes the internal
// endpoints answer with a login page instead of JSON.
Map probeCompatibility() {
    Map out = [ok: false, detail: '']
    try {
        httpGet([uri: "http://127.0.0.1:8080/installedapp/statusJson/${app.id}", timeout: 10]) { resp ->
            if (resp.data instanceof Map && (resp.data as Map).installedApp) {
                out.ok = true
                out.detail = 'Hub internal endpoints reachable.'
            } else {
                out.detail = 'The hub answered, but not with app JSON. If Hub Login Security is enabled, Automation Map cannot read app configuration.'
            }
        }
    } catch (Exception ex) {
        out.detail = "Could not reach the hub's internal app endpoint (${ex.message}). This Hubitat version may not expose /installedapp/statusJson."
    }
    return out
}

void startScan() {
    Map compat = probeCompatibility()
    state.compatOk = compat.ok
    state.compatDetail = compat.detail
    // Recorded but never checked - a hub that cannot return usable statusJson
    // was still allowed into phases that depend on that exact endpoint,
    // rather than failing here where the cause is still known.
    if (!compat.ok) {
        state.scanError = "${compat.detail}"
        state.scanRunning = false
        return
    }
    state.appsDecoded = 0
    state.appsUnreadable = 0
    state.rulesDecoded = 0
    state.rulesSkipped = 0
    state.ruleLinks = 0
    state.appsFromListing = 0
    state.appsInert = 0
    state.otherEngines = []
    // Cleared BEFORE fetchAllDeviceIds runs, not after. That call sets
    // scanError itself on failure - clearing it afterward silently erased
    // the one error a user most needed to see, the enumeration that made
    // the whole scan pointless before a single app was even queued.
    state.scanError = null
    // Also cleared here, not down with the rest of the reset block below -
    // that block sits after the scanError abort check, so on a failed
    // enumeration it never runs and this count would otherwise still be
    // whatever an unrelated earlier scan left behind, read back out through
    // compatibilitySummary/scanStatusJson/AI friendly export as if it described
    // the scan that just failed to even start.
    state.deviceIdsUnreadable = []
    state.scanQueue = fetchAllDeviceIds()
    // fetchAllDeviceIds sets scanError itself and returns [] on failure -
    // checked here, not assumed handled downstream. Before this check, a
    // failed enumeration still fell through into a full scan with zero
    // devices queued: scanRunning went true, the app phase ran anyway, and
    // a graph with no devices and no device relationships could be stamped
    // as a normal, complete result, with the one error a user needed to
    // see left to be found only by reading scanError separately rather
    // than the scan visibly having stopped.
    if (state.scanError) {
        state.scanRunning = false
        return
    }
    state.scanTotal = (state.scanQueue as List).size()
    state.scanDone = 0
    state.scanPhase = 'devices'
    state.scanRunning = true
    // Stamped here as well as in scanBatch, so a scan that never manages to run
    // a single batch still has a timestamp for clearAbandonedScan to age out.
    state.scanHeartbeat = now()
    state.deviceLabels = [:]
    // NOT state.deviceIconOverrides or state.deviceIconNotes - both are the
    // user's own input (a correction, and a freeform note on an
    // unrecognised device), same category as state.userRegistry for
    // external systems, and must survive a rescan the same way those
    // declarations do.
    state.deviceCapabilities = [:]
    state.deviceRooms = [:]
    state.appIds = []
    state.appInfo = [:]
    state.graphVersion = null
    // Dropped, not merely marked stale. Holding the previous graph while
    // appInfo fills doubles peak state for the whole scan, and on a 74-app hub
    // that was enough to kill a scan two apps from the end: no error logged, no
    // job scheduled, just a heartbeat that stopped. The old graph is unusable
    // during a scan anyway, since graphVersion is cleared on the line above.
    state.graph = null
    unschedule('scanBatch')
    runIn(1, 'scanBatch')
}

void scanBatch() {
    // Anything thrown out of this method is fatal to the whole scan: Hubitat
    // discards the state written during a failed execution, so the queue would
    // never advance AND no follow-up job would be scheduled, leaving the app
    // stuck at "scanning" with no error recorded. Every stage is therefore
    // guarded separately, and the reschedule happens no matter what.
    state.scanHeartbeat = now()
    boolean advanced = false
    try {
        if (state.scanPhase == 'devices') {
            scanDeviceBatch()
        } else {
            scanAppBatch()
        }
        advanced = true
    } catch (Exception ex) {
        log.warn "${app.label}: scanBatch failed: ${ex.message}"
        state.scanError = "${ex.message}"
        state.scanQueue = []
        // Stops here rather than falling into the block below, which decides
        // what runs next on the assumption the batch succeeded. An empty
        // queue after a genuine failure used to read exactly like an empty
        // queue after finishing normally, and the scan would proceed straight
        // into fetchRegistry/finishScan - building and stamping a graph from
        // data a failed batch never finished collecting.
        state.scanRunning = false
        return
    }

    try {
        if (state.scanQueue) {
            runIn(1, 'scanBatch')
        } else if (advanced && state.scanPhase == 'devices') {
            startAppPhase()
        } else {
            // Scheduled rather than called, so the graph build gets an
            // execution to itself. fetchRegistry chains on to finishScan.
            //
            // Called inline it ran in the same execution as the last batch of
            // app fetches, so one execution did up to three 20-second HTTP
            // fetches, then built a 285-node graph, then made up to three more
            // HTTP calls naming deleted rules, then wrote the whole state. That
            // execution died on a 74-app hub: no error, no scheduled job, just a
            // heartbeat that stopped two apps from the end.
            //
            // Splitting it also means the batch work is already committed if
            // the build itself fails.
            //
            // The PENDING marker is written HERE, not inside fetchRegistry,
            // because state is only committed at the END of an execution. An
            // execution that dies mid-fetch discards everything it wrote, so
            // fetchRegistry structurally cannot record that it started. Without
            // this marker, "never ran" and "died trying" look identical from the
            // outside, and the page told a user who had just run a scan that the
            // registry had never been fetched.
            state.registryMeta = [state: 'PENDING', fetched: null, entries: 0,
                                  matched: 0, error: null, schemaVersion: null]
            runIn(1, 'fetchRegistry')
            // Watchdog. finishScan is chained off fetchRegistry, so a fetch that
            // dies takes the graph build down with it and the scan never
            // completes at all. Scheduling finishScan again for the same handler
            // replaces this job, so the normal path cancels the watchdog simply
            // by rescheduling it one second out.
            runIn(45, 'finishScan')
        }
    } catch (Exception ex) {
        log.warn "${app.label}: scan could not continue: ${ex.message}"
        state.scanError = "${ex.message}"
        state.scanRunning = false
    }
}

void scanDeviceBatch() {
    List queue = state.scanQueue as List
    Map labels = state.deviceLabels as Map
    Map capsByDev = (state.deviceCapabilities ?: [:]) as Map
    Map roomsByDev = (state.deviceRooms ?: [:]) as Map
    List appIds = state.appIds as List
    int size = queue.size() < DEVICE_BATCH_SIZE ? queue.size() : DEVICE_BATCH_SIZE

    // This app's own device picker references every selected device, which would
    // otherwise draw ~200 meaningless "acts on" edges from Automation Map itself.
    String selfId = "${app.id}"

    // Same distinction already drawn for apps below: only a genuine fetch
    // failure counts as unreadable, tracked by id so the export/UI can name
    // which devices were missed, not just how many. Before this, a failed
    // device.fullJson call was indistinguishable from a device that simply
    // had nothing to report - the device silently dropped out of the scan
    // (no label, no capabilities, no room, and any app only discoverable
    // through it could be missed too) with the finished scan still able to
    // report no top-level error at all.
    List unreadable = (state.deviceIdsUnreadable ?: []) as List
    queue.take(size).each { String devId ->
        Map info = fetchDeviceApps(devId)
        if (info.error) {
            if (!unreadable.contains(devId)) unreadable << devId
        } else {
            if (info.label) labels[devId] = info.label
            capsByDev[devId] = info.capabilities
            if (info.room) roomsByDev[devId] = info.room
            (info.appIds as List).each { String appId ->
                if (appId != selfId && !appIds.contains(appId)) appIds << appId
            }
        }
    }

    state.deviceLabels = labels
    state.deviceCapabilities = capsByDev
    state.deviceRooms = roomsByDev
    state.deviceIdsUnreadable = unreadable
    state.appIds = appIds
    state.scanQueue = queue.drop(size)
    state.scanDone = (state.scanDone ?: 0) + size
}

void startAppPhase() {
    // The device walk is finished, so this is the point where the two discovery
    // channels are merged. Done here rather than before the device phase so a
    // failure of either one still leaves a usable scan.
    //
    // Order matters only for readability of the queue. Device-found ids stay
    // first, so the apps that will actually be drawn are read first and a scan
    // interrupted part way through has the useful half.
    List appIds = state.appIds as List
    String selfId = "${app.id}"
    int fromDevices = appIds.size()
    fetchInstalledAppIds().each { String appId ->
        if (appId != selfId && !appIds.contains(appId)) appIds << appId
    }
    state.appIds = appIds
    // Kept for the scan summary. The count is the honest way to describe what
    // this bought: on a hub where every app touches a device it is zero, and
    // saying so is better than implying the map gained something it did not.
    state.appsFromListing = appIds.size() - fromDevices

    state.scanPhase = 'apps'
    state.scanQueue = appIds
    state.scanTotal = appIds.size()
    state.scanDone = 0
    runIn(1, 'scanBatch')
}

void scanAppBatch() {
    List queue = state.scanQueue as List
    Map appInfo = state.appInfo as Map
    Map labels = state.deviceLabels as Map
    int size = queue.size() < APP_BATCH_SIZE ? queue.size() : APP_BATCH_SIZE

    queue.take(size).each { String appId ->
        Map info = fetchAppRelationships(appId, labels)
        appInfo[appId] = info
        // Only a genuine fetch failure counts as unreadable. An app with no
        // roles was read perfectly well - it simply has no device relationships
        // to draw, which is also true of Automation Map itself once it excludes
        // itself. Counting those as failures made every scan report "1 app
        // could not be read", which is what it does to its own entry.
        if (info.error) {
            state.appsUnreadable = (state.appsUnreadable ?: 0) + 1
        } else {
            state.appsDecoded = (state.appsDecoded ?: 0) + 1
        }
        if (info.flow) {
            state.rulesDecoded = (state.rulesDecoded ?: 0) + 1
        } else if ("${info.type}".startsWith('Rule-') && "${info.type}" != SUPPORTED_RULE_ENGINE) {
            // A rule engine this version does not decode. Counted so it is
            // reported rather than looking like a rule with nothing in it.
            List others = (state.otherEngines ?: []) as List
            if (!others.contains("${info.type}")) others << "${info.type}"
            state.otherEngines = others
            state.rulesSkipped = (state.rulesSkipped ?: 0) + 1
        }
    }

    state.appInfo = appInfo
    state.deviceLabels = labels
    state.scanQueue = queue.drop(size)
    state.scanDone = (state.scanDone ?: 0) + size
}

// Runs as its own scheduled execution between the app phase and the graph
// build. It fetches ~170KB over the internet and parses it, which is far too
// much to bolt onto a batch that is already doing HTTP work - the lesson from
// finishScan, which died when it was called inline.
//
// Failure here is not fatal. The registry is a convenience; the user's own
// declarations are the authority, and an unclassified app type is an explicit,
// visible state rather than a silent absence.
void fetchRegistry() {
    state.scanHeartbeat = now()
    List types = discoveredAppTypes()
    List matches = []
    Map meta = [state: 'OK', fetched: null, entries: 0, matched: 0, error: null, schemaVersion: null]

    try {
        httpGet([uri: REGISTRY_URL, contentType: 'application/json', timeout: 30]) { resp ->
            Map data = (resp.data instanceof Map) ? (resp.data as Map) : [:]
            List entries = (data.entries ?: []) as List
            meta.entries = entries.size()
            meta.schemaVersion = "${data.schemaVersion}"

            types.each { String appType ->
                entries.each { ent ->
                    if (!(ent instanceof Map)) return
                    Map e = ent as Map
                    if (registryEntryState(e, appType) != 'MATCH') return
                    (e.dependencies ?: []).each { dep ->
                        if (!(dep instanceof Map)) return
                        Map d = dep as Map
                        String name = "${d.name}".trim()
                        if (!name || name == 'null') return
                        String kind = (REGISTRY_CLASS_TO_KIND["${d.class}"] ?: 'internet') as String
                        String crit = "${d.runtimeCriticality}"
                        if (!EXTERNAL_CRITICALITY.containsKey(crit)) crit = 'RUNTIME'
                        matches << [type: appType, name: name, kind: kind, crit: crit, entry: "${e.id}"]
                    }
                }
            }
            meta.matched = matches.size()
            meta.fetched = new Date().format('yyyy-MM-dd HH:mm', location.timeZone)
        }
    } catch (Exception ex) {
        meta.state = 'ERROR'
        meta.error = "${ex.message}"
        log.warn "${app.label}: registry fetch failed, continuing without it: ${ex.message}"
    }

    // Only on success, so a failed fetch keeps the last good set rather than
    // silently emptying the map of everything the registry contributed.
    if (!meta.error) state.registryMatches = matches
    state.registryMeta = meta
    log.info "${app.label}: registry ${meta.error ? 'unavailable' : "gave ${meta.matched} dependency match(es) from ${meta.entries} entries"}"
    runIn(1, 'finishScan')
}

void finishScan() {
    // Runs as its own scheduled execution, so a failure here leaves the scan
    // data intact and reports itself, rather than silently stranding the app
    // mid-scan the way an inline call did.
    state.scanHeartbeat = now()

    // Still PENDING means fetchRegistry never reached its own bookkeeping, so
    // this execution is the watchdog firing rather than the normal chain. Say
    // so. The alternative is what shipped before: an app that had tried and
    // failed reporting that it had never tried, which is worse than an error.
    Map regMeta = (state.registryMeta ?: [:]) as Map
    if ("${regMeta.state}" == 'PENDING') {
        regMeta.state = 'FAILED'
        regMeta.error = 'the registry fetch did not complete'
        state.registryMeta = regMeta
        log.warn "${app.label}: registry fetch did not complete, continuing without it"
    }

    Map graph = [:]
    try {
        graph = buildGraph()
    } catch (Exception ex) {
        log.warn "${app.label}: graph build failed: ${ex.message}"
        state.scanError = "Graph build failed: ${ex.message}"
        state.scanRunning = false
        return
    }
    state.scanRunning = false
    state.graph = graph
    state.graphVersion = GRAPH_SCHEMA

    // Flowcharts are now in graph.flows, so drop the copy in appInfo. They were
    // 61KB of a 244KB state on this hub, a quarter of everything stored, held
    // twice for no reason. buildGraph falls back to the existing graph.flows on
    // a rebuild, so nothing is lost when the graph is rebuilt without a rescan.
    Map appInfo = (state.appInfo ?: [:]) as Map
    appInfo.each { String appId, info ->
        if (info instanceof Map) (info as Map).remove('flow')
    }
    state.appInfo = appInfo
    // Counted from the finished graph rather than tallied during the scan.
    // A rule that sets another rule's Private Boolean both true and false is
    // two actions but one relationship, so a running tally reported 8 where
    // the map drew 7.
    int links = 0
    ((graph.edges ?: []) as List).each { e ->
        // Compared through a String-typed local: a GString never matches a
        // String in contains(), because their hash codes differ.
        String kind = "${(e as Map).kind}"
        if (RULE_LINK_KIND_NAMES.contains(kind)) links++
    }
    state.ruleLinks = links

    // Counted off the built graph rather than off appInfo, so the summary can
    // only ever describe nodes that are really on the map.
    int inertCount = 0
    ((graph.nodes ?: []) as List).each { n ->
        if ((n as Map).inert == true) inertCount++
    }
    state.appsInert = inertCount

    log.info "${app.label}: scan complete - ${(state.appInfo as Map).size()} app(s), ${(state.deviceLabels as Map).size()} device(s)"
}

// Every device on the hub.
//
// This used to be a device picker, and it was the biggest piece of friction in
// the app: a new user had to select ~200 devices before anything worked. The
// picker was never a permission gate here - this app reads /device/fullJson
// directly, which does not consult app-device bindings - so the selection only
// ever served as a list of ids, and the hub will hand over that list anyway.
//
// It also fixes a correctness problem. With a picker, an app whose devices the
// user did not tick was simply invisible, so how complete the map was depended
// on a user action rather than on the hub.
//
// The capability parameter is required. Without it the endpoint returns [].
List fetchAllDeviceIds() {
    List ids = []
    try {
        httpGet([uri: 'http://127.0.0.1:8080/device/listJson?capability=capability.*', timeout: 30]) { resp ->
            def data = resp.data
            if (data instanceof List) {
                data.each { d ->
                    if (d instanceof Map && d.id != null) ids << "${d.id}"
                }
            }
        }
    } catch (Exception ex) {
        log.warn "${app.label}: could not list devices: ${ex.message}"
        state.scanError = "Could not list devices from the hub: ${ex.message}"
    }
    return ids.unique()
}

// Every installed app on the hub, in one request, whether or not it references
// a device.
//
// Device-led discovery answers "which apps touch a device", which is a
// different question from "which apps exist" and quietly misses every app that
// touches none. On the test hub four sibling Button Rule-5.1 rules split two
// and two on exactly that line: the two naming a device were found, the two
// naming none were not. Rule Functions are the case that matters most, since
// having no devices is normal for them rather than unusual.
//
// This does NOT replace the device walk. The listing says an app exists; it
// never says which devices the app touches, so both are needed and the answer
// is their union.
//
// Credit: the endpoint was found by reading Jean P. May Jr.'s (TheBearMay) Rule
// References Rule Table, then verified here. This project's own notes had
// recorded that no bulk app-list endpoint existed, which was wrong.
//
// Shape: { apps: [ { data: {id, appTypeId, name, type, disabled, ...},
//                    children: [ ...same again... ] } ] }
// Parents nest arbitrarily - Button Controllers holds a Button Controller,
// which holds four Button Rules - so it has to be walked recursively rather
// than read one level deep.
List fetchInstalledAppIds() {
    List ids = []
    try {
        httpGet([uri: 'http://127.0.0.1:8080/hub2/appsList', timeout: 30]) { resp ->
            Map data = (resp.data instanceof Map) ? (resp.data as Map) : [:]
            collectAppIds(data.apps, ids)
        }
    } catch (Exception ex) {
        // Deliberately not a scan error. Losing this costs completeness, not
        // correctness: every app found through a device is still found. An
        // older firmware without the endpoint should degrade to the previous
        // behaviour rather than fail the scan.
        log.warn "${app.label}: could not list installed apps, falling back to device-led discovery only: ${ex.message}"
    }
    return ids.unique()
}

// Iterative rather than recursive on purpose. A self-calling method inside a
// Hubitat app is a sandbox risk not worth taking for a tree that is three deep,
// and a stack of pending nodes does the same job with no such question.
void collectAppIds(def nodes, List ids) {
    if (!(nodes instanceof List)) return
    List pending = []
    pending.addAll(nodes as List)
    while (pending) {
        def node = pending.remove(0)
        if (!(node instanceof Map)) continue
        Map entry = node as Map
        Map data = entry.data as Map
        // Through a String-typed local, never appended straight as a GString. A
        // list of GStrings looks identical in a log and then fails contains()
        // and unique() against real Strings. Section 9.5 of the storage-format
        // notes covers it; this caught the first version of this walker, where
        // ids.contains('2973') was false against a list that plainly held it.
        if (data?.id != null) {
            String id = "${data.id}"
            ids << id
        }
        if (entry.children instanceof List) pending.addAll(entry.children as List)
    }
}

// Phase 1: only needs the app ids this device is attached to. Also harvests
// capabilities and room from the same response for the device icon feature -
// this is a field already sitting in a request this function was making
// anyway, not a new HTTP call.
Map fetchDeviceApps(String devId) {
    Map out = [label: null, appIds: [], capabilities: [], room: null, error: null]
    try {
        httpGet([uri: "http://127.0.0.1:8080/device/fullJson/${devId}", timeout: 10]) { resp ->
            Map data = (resp.data instanceof Map) ? (resp.data as Map) : [:]
            String breadcrumb = data.extraBreadcrumb as String
            if (breadcrumb) out.label = stripTags(breadcrumb)

            Map dev = data.device as Map
            if (dev) {
                out.capabilities = (dev.capabilities ?: []) as List
                if (dev.roomName) out.room = dev.roomName as String
            }

            List ids = []
            Map parentApp = data.parentApp as Map
            if (parentApp?.id != null) ids << "${parentApp.id}"

            // appsUsing, NOT appsUsingForDialog.
            //
            // appsUsingForDialog is capped at five entries on every device, with
            // appsUsingForDialogMore holding only a COUNT of the remainder, not
            // the ids. It exists to render a dialog, not to enumerate anything.
            // appsUsing sits beside it in the same response and is complete: on
            // one device here it holds 29 entries where the dialog field holds
            // five.
            //
            // Reading the dialog field made every app beyond the fifth on a
            // shared device invisible, which is not the rare edge case it sounds
            // like. A rule using only popular devices was missed entirely, and
            // was noticed only because another rule named it as a target.
            List using = (data.appsUsing ?: data.appsUsingForDialog ?: []) as List
            using.each { u ->
                if (u instanceof Map && u.id != null) ids << "${u.id}"
            }
            out.appIds = ids.unique()
        }
    } catch (Exception ex) {
        log.warn "${app.label}: device ${devId} lookup failed: ${ex.message}"
        out.error = "${ex.message}"
    }
    return out
}

// Phase 2: the real relationship data. Also harvests device labels for devices
// the user did not select, since settings carry {id: name} maps.
Map fetchAppRelationships(String appId, Map labels) {
    Map out = [id: appId, label: "App ${appId}", type: null, roles: [:], flow: [], stateful: [], ruleLinks: [], endpoints: [], hubVarWrites: [], hubVarReads: [], error: null]
    try {
        httpGet([uri: "http://127.0.0.1:8080/installedapp/statusJson/${appId}", timeout: 20]) { resp ->
            Map data = (resp.data instanceof Map) ? (resp.data as Map) : [:]

            Map installedApp = data.installedApp as Map
            String rawLabel = stripReplacementChar((installedApp?.label ?: installedApp?.trueLabel ?: installedApp?.name ?: "App ${appId}") as String)
            out.label = stripTags(rawLabel)
            // Kept alongside the full label rather than replacing it: the
            // status is real information, it just does not belong painted
            // across the canvas. See nodeEntry for which form goes where.
            out.drawLabel = stripStatusMarkup(rawLabel)
            out.type = stripReplacementChar(installedApp?.name as String)
            // Stored for EVERY app, not only the empty ones, because it is read
            // in the opposite direction from the one it is written in. A
            // container needs the names of its children, and a child is the only
            // record that the relationship exists - childAppCount gives Rule
            // Machine the number 46 and not one id. One short string per app is
            // worth it; the four counts above are not, hence the split.
            if (installedApp?.parentAppId != null) out.parent = "${installedApp.parentAppId}"

            // Skip every instance of this app, not just the one doing the
            // scanning. Its device picker references the whole hub, so a second
            // instance - including a half-created one left behind by an
            // abandoned "Add User App", which stays visible to devices - would
            // otherwise appear as an app with a couple of hundred meaningless
            // edges. Excluding by app.id alone missed exactly that case.
            if ("${out.type}".startsWith(APP_FAMILY)) {
                out.roles = [:]
                out.flow = []
                out.ruleLinks = []
                out.endpoints = []
                out.hubVarWrites = []
                out.hubVarReads = []
                return
            }

            // A paused rule still holds all its device references but is not
            // running. Shown identically to an active one it would send you
            // debugging an automation that cannot fire.
            boolean paused = false
            (data.appState ?: []).each { e ->
                if (e instanceof Map && e.name == 'paused' && e.value == true) paused = true
            }
            out.inactive = (installedApp?.disabled == true) || paused

            Map roles = [:]
            List stateful = []

            (data.childDevices ?: []).each { kid ->
                if (kid?.id == null) return
                String devId = "${kid.id}"
                if (kid.name && !labels[devId]) labels[devId] = stripTags(kid.name as String)
                addRole(roles, devId, 'owns')
            }

            List subscribed = []
            (data.eventSubscriptions ?: []).each { sub ->
                if (sub?.type != 'DEVICE' || sub?.typeId == null) return
                String devId = "${sub.typeId}"
                if (sub.typeName && !labels[devId]) labels[devId] = stripTags(sub.typeName as String)
                if (!subscribed.contains(devId)) subscribed << devId
            }

            (data.appSettings ?: []).each { s ->
                Map deviceList = s?.deviceList as Map
                if (!deviceList) return
                String settingName = "${s.name}"
                String settingType = "${s.type}"
                deviceList.each { devIdKey, devName ->
                    String devId = "${devIdKey}"
                    if (devName && !labels[devId]) labels[devId] = stripTags(devName as String)
                    String role = roleForSetting(settingName, settingType, devId, subscribed)
                    addRole(roles, devId, role)
                    // Remembered so conflict detection can ignore transient
                    // commands like notifications.
                    if (role == 'action' && isStatefulCapability(settingType) && !stateful.contains(devId)) {
                        stateful << devId
                    }
                }
            }

            // A subscribed device with no setting of its own is still a trigger,
            // unless this app owns it (a child device it also listens to).
            //
            // Note this signal is a snapshot: Rule Machine drops its trigger
            // subscriptions while a Required Expression is false, and subscribes
            // to the gate devices instead. Rule rules are unaffected because the
            // tDev/rDev checks above already claimed those devices, but a
            // non-rule app that subscribes conditionally can map differently
            // depending on when the scan ran.
            subscribed.each { String devId ->
                List existing = (roles[devId] ?: []) as List
                if (!existing) addRole(roles, devId, 'trigger')
            }

            out.roles = roles
            out.stateful = stateful
            out.flow = buildRuleFlow(data)
            out.ruleLinks = extractRuleLinks(data, appId)
            out.endpoints = extractRuleEndpoints(data)
            // Gated to Rule Machine, matching the existing engine check at
            // line ~663 ("${info.type}".startsWith('Rule-')) rather than
            // SUPPORTED_RULE_ENGINE's exact-version pin - the field names
            // (xVarV, rCapab_/xVar_, tCapab/xVar) were reverse-engineered
            // against Rule-5.1 specifically, but nothing about them looks
            // version-pinned the way flow decoding's layout reconstruction
            // is, so any Rule Machine engine is allowed rather than only
            // 5.1. The structured fields would simply find nothing on an
            // unrelated app type regardless, but the free-text scan is
            // broader - any text/textarea setting on ANY app - and its
            // hub-wide confirmation check only looks at the name, not which
            // app it came from. An unrelated app whose own text happened to
            // contain a confirmed Hub Variable's name would otherwise pick
            // up a false read edge. Gating here closes that off entirely
            // rather than trying to make the confirmation check smarter.
            if ("${out.type}".startsWith('Rule-')) {
                out.hubVarWrites = extractHubVariableWrites(data)
                out.hubVarReads = extractHubVariableReads(data)
            }

            // An app with no devices, no rule links and no endpoints used to be
            // dropped silently, which was defensible while device-led discovery
            // meant it was never found in the first place. Now that every
            // installed app is enumerated, dropping it makes the app report a
            // count it does not show, so it gets drawn instead - and a square
            // with nothing attached needs to say why it is empty.
            //
            // Captured only for those apps. On this hub that is 13 of 88, so
            // attaching it unconditionally would put four fields on 75 apps
            // that will never read them, and state size has killed a scan here
            // before.
            //
            // All four come from the response already in hand. No extra call.
            if (!roles && !out.ruleLinks && !out.endpoints) {
                // scheduledJobList rather than a cast: scheduledJobs has been seen
                // as both a list and a bare single-job map, and casting the wrong
                // one throws inside the scan loop.
                List jobs = scheduledJobList(data.scheduledJobs)
                out.inert = [
                    kids  : (data.childAppCount ?: 0) as Integer,
                    devs  : (data.childDeviceCount ?: 0) as Integer,
                    sched : jobs.size(),
                    // next/cron only - handler is a Groovy method name meaningful
                    // to nobody reading the map, and prevRunTime/status are the
                    // hub's bookkeeping rather than anything a user configured.
                    schedJobs : jobs.collect { Map j -> [next: "${j.nextRunTime}", cron: "${j.schedule}"] },
                    subs  : countOf(data.eventSubscriptions),
                ]
            }
        }
    } catch (Exception ex) {
        out.error = ex.message
        log.warn "${app.label}: app ${appId} lookup failed: ${ex.message}"
    }
    return out
}

// ===================================================================================================================
// Rule Machine flow decoding
//
// A relationship graph cannot show order, so for Rule Machine rules the ordered
// structure is decoded here into a plain list of steps that the map page draws
// as a flowchart. Verified end to end against rule 2279 "Back Door Night",
// whose own page shows exactly the trigger / required expression / four ordered
// actions this produces.
//
//   actionList          ordered action numbers
//   actions[n]          {method, indent, rule, delay}
//   actSubType.<n>      same method name, as a setting
//   capabstrue/false    human readable text per condition number
//   predCapabs          condition numbers forming the required expression
//   eval[b]             branch number -> condition expression, e.g. [3,"AND","16"]
//   tDev<n>             trigger devices for condition n
//   rDev_<n>            condition devices for condition n
//   <prefix>.<n>        devices for action n (onOffSwitch, ct, volume, note, ...)
//   onOff.<n>           whether a switch action is On or Off
//
// This is Rule Machine's private layout, not a documented API. Apps that are
// not rules simply have no actionList and produce no flow.
// ===================================================================================================================

// ===================================================================================================================
// Rule-to-rule links
//
// Requested on the community thread: show it when one rule runs another. Rule
// Machine stores every "act on another rule" action the same way, and the
// action object itself carries no target at all - only a method name. The
// target is in the app's SETTINGS, keyed by the action number:
//
//   actType.<n>        'rulesActs' for this whole family of actions
//   actSubType.<n>     which action it is, e.g. getRuleActions
//   ruleAct.<n>        target installed app ids, as a list: ["1806"]
//   runRuleType.<n>    engine of the target, e.g. Rule Machine
//
// Confirmed against a live hub for all three subtypes below.
//
// Two traps found while working this out. Every action object has a field
// literally called 'rule', and it is NOT a rule reference - it is a condition
// index used by getIfThen / getElseIf / getWaitRule. Keying on it produces
// confident, entirely fictional links. And a target of ["*"] means the rule
// itself, which is how Set Private Boolean is normally used, so it must not
// become an edge either.
// ===================================================================================================================

// 'targets' is a list, not a single setting name, because Rule Machine has
// been observed to store the same semantic action under more than one
// setting prefix (ruleAct.<n> and ruleActMain.<n> for Run Actions; privateT.<n>
// and privateF.<n> for Set Private Boolean). Both extractRuleLinks and
// actionStep must check every alias - checking only the first one means a
// rule using the less common storage form is silently dropped rather than
// linked. buildGraph's from/to/kind dedup means checking every alias can
// never produce a duplicate edge, only a missed one if an alias is skipped.
@Field static final Map RULE_LINK_ACTIONS = [
    getRuleActions      : [targets: ['ruleAct', 'ruleActMain'], engine: 'runRuleType',   kind: 'runs'],
    getStopActions      : [targets: ['stopAct'],                engine: 'stopRuleType',  kind: 'cancelTimedActions'],
    getSetPrivateBoolean: [targets: ['privateT', 'privateF'],   engine: 'pvRuleType',    kind: 'setspb'],
    getPauseResumeRules : [targets: ['pauseRule'],              engine: 'pauseRuleType', kind: 'pauseResume'],
]

@Field static final List<String> RULE_LINK_KIND_NAMES = ['runs', 'cancelTimedActions', 'setspb', 'pauseResume']

List extractRuleLinks(Map data, String appId) {
    Map vals = [:]
    (data.appSettings ?: []).each { s ->
        if (!(s instanceof Map) || s.name == null) return
        // Assigned through String-typed locals on purpose. Keying a map with a
        // GString and then looking it up with another GString of the same text
        // misses, because their hash codes differ.
        String n = "${s.name}"
        String v = "${s.value}"
        vals[n] = v
    }

    List out = []
    vals.each { String name, String value ->
        if (!name.startsWith('actType.') || value != 'rulesActs') return
        String num = name.substring(8)
        Map fam = RULE_LINK_ACTIONS[vals['actSubType.' + num]] as Map
        if (!fam) return
        String engine = vals[fam.engine + '.' + num] ?: ''

        // More than one setting can carry the same semantic target - see the
        // comment on RULE_LINK_ACTIONS. Every alias is checked.
        (fam.targets as List<String>).each { String targetSetting ->
            String raw = vals[targetSetting + '.' + num] ?: ''
            if (!raw) return

            // "*" means this rule, and it can appear ALONGSIDE other rules:
            // ["*","1809"] is Rule Machine's way of storing "set the Private
            // Boolean of this rule AND Perimeter Closed". Skipping the whole
            // action whenever a "*" was present therefore dropped a real
            // cross-rule link every time a rule also set its own boolean.
            // Stripping non-digits discards the "*" and keeps the ids.
            // Written with replaceAll rather than a regex literal - this file
            // also builds the map page inside a GString, so slash-delimited
            // patterns are avoided throughout for consistency.
            String cleaned = raw.replaceAll('[^0-9]', ' ').trim()
            if (!cleaned) return
            cleaned.split(' +').each { String targetId ->
                if (!targetId || targetId == appId) return
                out << [to: targetId, kind: fam.kind, engine: engine]
            }
        }
    }
    return out
}

// Hub Variable WRITE relationships for the graph, not the flow popup - see
// actionLabel()'s getSetVariable case for the same underlying settings
// (xVarV.<n> for the target, valStringOp.<n>/customDev.<n>/tCustomAttr.<n> for
// a device-attribute source) read for the popup's text instead. Kept as a
// separate pass over the same appSettings/appState data rather than shared
// with buildRuleFlow, matching how this file already computes roles and flow
// independently from one scan's data rather than threading one through the
// other.
//
// READ relationships (a rule that consumes a Hub Variable without writing it)
// are out of scope here - Phase 3 proves the WRITE side only. See handoff.md.
List extractHubVariableWrites(Map data) {
    Map st = [:]
    (data.appState ?: []).each { e ->
        if (e instanceof Map && e.name != null) st["${e.name}"] = e.value
    }
    Map actions = (st.actions ?: [:]) as Map

    Map settingValues = [:]
    Map settingDevices = [:]
    (data.appSettings ?: []).each { s ->
        if (!(s instanceof Map)) return
        String n = "${s.name}"
        Map dl = s.deviceList as Map
        if (dl) settingDevices[n] = dl.values().collect { stripTags("${it}") }
        if (s.value != null && "${s.value}") settingValues[n] = "${s.value}"
    }

    List out = []
    actions.each { num, actVal ->
        Map act = (actVal instanceof Map) ? (actVal as Map) : [:]
        String method = (act.method ?: settingValues["actSubType.${num}"] ?: '') as String
        if (method != 'getSetVariable') return
        // Trailing period observed on the one fixture verified so far (rule
        // 2981, "TestHubUptime.") - not yet confirmed as universal, so strip
        // rather than assume it is always present. See handoff.md Section 22.
        String varName = ("${settingValues["xVarV.${num}"] ?: ''}").replaceAll(/\.$/, '')
        if (!varName) return
        Map write = [variable: varName]
        // Only the device-attribute source has been observed. A fixed value or
        // another variable as the source is unconfirmed, so this is left
        // absent rather than guessed - the variable node and WRITE edge are
        // still created either way, only the source detail is conditional.
        if (settingValues["valStringOp.${num}"] == 'Device attribute') {
            String attr = settingValues["tCustomAttr.${num}"]
            List srcDevices = settingDevices["customDev.${num}"] ?: []
            if (attr && srcDevices) {
                write.sourceDevice = srcDevices[0]
                write.sourceAttr = attr
            }
        }
        out << write
    }
    return out
}

// Hub Variable READ relationships - a rule referencing a variable in a
// condition or Required Expression, without necessarily writing it. The
// eval-expression counterpart to extractHubVariableWrites' action-based
// detection, and the same relationship requiredDevices() finds for a device
// condition (rDev_<n>) - a Variable-typed condition has no device at all, so
// requiredDevices() correctly returns nothing for one, and this is the
// function that covers the case it can't.
//
// Verified against rule 2984, "_Test Variables Extended" (a clone of the
// canonical write fixture with an added IF): condition 3 reads as "Variable
// TestHubUptime is not equal to '0'", stored as rCapab_3=Variable (the
// condition-side counterpart to tCapab1 on triggers), xVar_3=TestHubUptime.
// (same trailing-period artifact as xVarV on the write side).
//
// Scans every eval group unconditionally rather than only ones reached from
// an IF/ELSEIF action, so a Required Expression (evalMap['0'], not tied to
// any action) is covered by the same pass without a second code path.
List extractHubVariableReads(Map data) {
    Map st = [:]
    (data.appState ?: []).each { e ->
        if (e instanceof Map && e.name != null) st["${e.name}"] = e.value
    }
    Map evalMap = (st.eval ?: [:]) as Map
    // buildRuleFlow() only trusts group '0' (Required Expression) when
    // hasPredicate is true - the same guard applies here, so a rule where
    // the toggle was switched off again can't have this read a stale
    // leftover group '0' as if it were still active. Every other group is
    // tied directly to an action's own presence in actionList, which has no
    // equivalent toggle to go stale against.
    boolean hasPredicate = st.hasPredicate == true

    Map settingValues = [:]
    (data.appSettings ?: []).each { s ->
        if (!(s instanceof Map)) return
        if (s.value != null && "${s.value}") settingValues["${s.name}"] = "${s.value}"
    }

    // confirmed=true: a structured field (rCapab_/xVar_ or tCapab/xVar)
    // named this variable explicitly - there is no ambiguity about what it
    // refers to. confirmed=false: only a %Name% text pattern matched - see
    // the free-text block below for why that alone is not proof. A name
    // seen both ways stays confirmed; structured evidence is never
    // downgraded by an unconfirmed match on the same name.
    Map found = [:]
    evalMap.each { groupId, expr ->
        if ("${groupId}" == '0' && !hasPredicate) return
        (expr instanceof List ? expr as List : []).each { item ->
            String s = "${item}"
            if (settingValues["rCapab_${s}"] != 'Variable') return
            String varName = ("${settingValues["xVar_${s}"] ?: ''}").replaceAll(/\.$/, '')
            if (varName) found[varName] = true
        }
    }

    // Trigger-by-variable: a rule that FIRES on a Hub Variable changing, not
    // just referencing one in a condition. Same picker convention as a
    // device trigger (tDev<n>/tCustomAttr<n>) but tCapab<n>=='Variable' and
    // xVar<n> - no underscore, unlike the condition-side xVar_<n> - holds the
    // name. Verified against rule 2988, "_Test Variables Trigger". This is
    // READ + TRIGGER per the spec (Section 6.3) - not yet distinguished from
    // a plain read at the edge-kind level, both land in the same 'read' set.
    settingValues.keySet().findAll { it ==~ /^tCapab\d+$/ }.each { String capabKey ->
        if (settingValues[capabKey] != 'Variable') return
        String num = capabKey.replaceAll('^tCapab', '')
        String varName = ("${settingValues["xVar${num}"] ?: ''}").replaceAll(/\.$/, '')
        if (varName) found[varName] = true
    }

    // Free-text interpolation - lowest priority per the spec's own
    // extraction order (Section 8.1: structured state first, visible text
    // only as a bounded fallback), used here because no structured field
    // captures a variable referenced inside typed text the way xVarV/xVar_
    // capture a picker selection. %Name% is RM's own reserved substitution
    // syntax, not something this app invented - verified against rule 2992,
    // "_ Test Variables Text": valString.1 = '%TestHubUptime%'.
    //
    // NOT proof on its own, and marked confirmed=false accordingly: Rule
    // Machine also reserves %device%/%time%/%date% (and others) as built-in
    // notification tokens with no relation to Hub Variables at all. Trusting
    // the pattern alone produced exactly this on Gordon's live hub -
    // "device"/"time"/"date" reported as Hub Variables read by real
    // production rules (Barking, Perimeter Closed, Mode Alarm Reminder),
    // none of which have ever created a variable by those names. buildGraph()
    // only keeps a candidate if the same name is independently confirmed
    // somewhere else on the hub via a structured reference - see the
    // confirmedVarNames pre-pass there.
    (data.appSettings ?: []).each { s ->
        if (!(s instanceof Map)) return
        String settingType = "${s.type}"
        if (!(settingType == 'text' || settingType == 'textarea')) return
        String val = "${s.value ?: ''}"
        (val =~ /%([A-Za-z_][A-Za-z0-9_]*)%/).findAll().each { m ->
            String varName = "${m[1]}"
            if (varName && !found.containsKey(varName)) found[varName] = false
        }
    }

    return found.collect { name, confirmed -> [variable: name, confirmed: confirmed] }
}

List buildRuleFlow(Map data) {
    Map st = [:]
    (data.appState ?: []).each { e ->
        if (e instanceof Map && e.name != null) st["${e.name}"] = e.value
    }

    List actionList = (st.actionList ?: []) as List
    if (!actionList) {
        // Visual Rule Builder 2.0 stores an explicit node/edge graph
        // (graphDocument) rather than Rule Machine's numbered actionList - a
        // different shape entirely, decoded by its own function.
        if (st.graphDocument) return buildVisualRuleBuilderFlow(st)
        // Built-in apps have no retrievable source - they are compiled classes,
        // and /app/ajax/code returns an empty body for them. Their runtime state
        // is still readable though, which is how Rule Machine was decoded too,
        // so other engines can be supported the same empirical way.
        return buildNotifierFlow(data, st)
    }

    Map actions = (st.actions ?: [:]) as Map
    Map evalMap = (st.eval ?: [:]) as Map

    // capabstrue / capabsfalse together describe every condition in plain text,
    // split only by whether it currently evaluates true.
    Map capabs = [:]
    (st.capabstrue ?: [:]).each { k, v -> capabs["${k}"] = cleanCondition("${v}") }
    (st.capabsfalse ?: [:]).each { k, v -> capabs["${k}"] = cleanCondition("${v}") }

    Map settingValues = [:]
    Map settingDevices = [:]
    (data.appSettings ?: []).each { s ->
        if (!(s instanceof Map)) return
        String n = "${s.name}"
        Map dl = s.deviceList as Map
        if (dl) settingDevices[n] = dl.values().collect { stripTags("${it}") }
        if (s.value != null && "${s.value}") settingValues[n] = "${s.value}"
    }

    List steps = []

    // Triggers: any condition that has a tDev setting behind it.
    settingDevices.keySet().findAll { it.startsWith('tDev') }.sort().each { String n ->
        String num = n.replaceAll('^tDev_?', '')
        steps << [kind: 'trigger', label: (capabs[num] ?: "Trigger ${num}"), devices: settingDevices[n]]
    }

    // Required expression: branch 0 of eval, present only when the rule has one.
    if (st.hasPredicate == true) {
        String text = expressionText((evalMap['0'] ?: []) as List, capabs)
        if (text) steps << [kind: 'required', label: text, devices: requiredDevices(evalMap['0'] as List, settingDevices)]
    }

    actionList.each { a ->
        steps << actionStep("${a}", (actions["${a}"] ?: [:]) as Map, settingValues, settingDevices, evalMap, capabs)
    }
    return steps
}

// Visual Rule Builder 2.0 (appTypeId 1084) stores an explicit node/edge graph
// in graphDocument - already a native Map/List once deserialized, not a
// string to parse - rather than Rule Machine's numbered-settings scheme.
// Decoded from one fixture (2026-08-16, "_Test Complex Visualisation Rule"):
// two triggers merging into one path, a single decision with true/false
// branches reconverging at one merge node, and turnOn/turnOff/wait/
// sendNotification/runRule actions. Handles that shape. A node, edge, or
// config field this has not seen degrades to a generic label or stops the
// walk rather than guessing, per this project's own rule against
// manufacturing meaning from an unconfirmed field (see the storage-format
// doc's design principle).
//
// The single-decision assumption is not a gap - confirmed live 2026-08-16
// that the builder itself rejects a prompt describing a nested decision
// with "Rule must contain exactly one decision node". Multiple/nested
// decisions are not a shape this format can currently produce at all, at
// least via the AI-prompt path, so the walker does not need to handle them.
List buildVisualRuleBuilderFlow(Map st) {
    Map graphDoc = (st.graphDocument instanceof Map) ? (st.graphDocument as Map) : [:]
    List nodes = (graphDoc.nodes ?: []) as List
    List edges = (graphDoc.edges ?: []) as List
    if (!nodes) return []

    Map deviceLabels = (state.deviceLabels ?: [:]) as Map
    Map nodesById = [:]
    nodes.each { n -> if (n instanceof Map) nodesById["${(n as Map).id}"] = n as Map }

    // Keyed by from-id. Only a decision node has more than one outgoing
    // edge (true/false); every other kind has exactly one, or none.
    Map outgoing = [:]
    edges.each { e ->
        if (!(e instanceof Map)) return
        Map edge = e as Map
        String from = "${edge.from}"
        List list = (outgoing[from] ?: []) as List
        list << [port: "${edge.port}", to: "${edge.to}"]
        outgoing[from] = list
    }

    // Device ids live under config keys following one naming pattern in
    // every node examined so far: switches, or anything ending Sensors/
    // Devices. A heuristic over that pattern, not a schema.
    Closure resolveDevices = { Map config ->
        List names = []
        (config ?: [:]).each { k, v ->
            String key = "${k}".toLowerCase()
            boolean looksLikeDevices = key == 'switches' || key.endsWith('sensors') || key.endsWith('devices')
            if (looksLikeDevices && v instanceof List) {
                (v as List).each { id ->
                    String nm = (deviceLabels["${id}"] ?: "Device ${id}") as String
                    if (!names.contains(nm)) names << nm
                }
            }
        }
        return names
    }

    Closure labelForNode = { Map node ->
        String type = "${node.type}"
        Map config = (node.config instanceof Map) ? (node.config as Map) : [:]
        switch (type) {
            case 'contact':
            case 'motion':
            case 'illuminanceCondition':
                // Every trigger/condition config seen so far carries its own
                // human-readable state text in a key ending Event or State -
                // reused rather than reconstructed from raw thresholds,
                // which would need its own case per condition type not yet
                // observed.
                String stateText = null
                config.each { k, v -> if ("${k}".endsWith('Event') || "${k}".endsWith('State')) stateText = "${v}" }
                return stateText ?: prettyMethod(type)
            case 'turnOn': return 'On'
            case 'turnOff': return 'Off'
            case 'wait':
                Integer mins = (config.minutes ?: 0) as Integer
                Integer secs = (config.seconds ?: 0) as Integer
                List parts = []
                if (mins) parts << "${mins}m"
                if (secs) parts << "${secs}s"
                return "Wait ${parts ? parts.join(' ') : '0s'}"
            case 'sendNotification':
                String msg = "${config.notificationMessage ?: ''}"
                return msg ? "Notify: ${msg}" : 'Notify'
            case 'runRule': return 'Run Rule Actions'
            default: return prettyMethod(type)
        }
    }

    // A decision node's own type ("all"/"any") is the AND/OR toggle, not the
    // condition itself - the real condition(s) sit nested in
    // config.conditions, each its own object with its own type/config,
    // exactly like a top-level node. This is what the earlier version of
    // this function missed: it called labelForNode on the decision node
    // directly, which only ever saw "all"/"any" and never the nested
    // condition - confirmed live, a diamond reading bare "all" instead of
    // "Illuminance is below 50 lux on Garage Motion Sensor".
    // The device name is baked directly into the returned text, not left to
    // a separate devices field - confirmed live that the diamond shape only
    // ever displays s.cond, never s.devices (that field only renders for
    // the plain box/trigger shapes elsewhere in the same function). Rule
    // Machine's own condition text already has this problem solved by
    // embedding the device name in the text itself (capabstrue/capabsfalse,
    // e.g. "Illuminance of X is < 200") - VRB's illuminanceSensorState is a
    // generic template ("Illuminance is below...") with the sensor stored
    // separately, so it needs the same treatment done explicitly here.
    Closure decisionText = { Map decisionNode ->
        Map dConfig = (decisionNode.config instanceof Map) ? (decisionNode.config as Map) : [:]
        List conditions = (dConfig.conditions ?: []) as List
        if (!conditions) return prettyMethod("${decisionNode.type}")
        String joiner = "${decisionNode.type}" == 'any' ? ' OR ' : ' AND '
        return conditions.collect { c ->
            Map cond = (c instanceof Map) ? (c as Map) : [:]
            String text = labelForNode(cond)
            List devs = resolveDevices(cond.config as Map)
            return devs ? "${text} on ${devs.join(', ')}" : text
        }.join(joiner)
    }
    Closure decisionDevices = { Map decisionNode ->
        Map dConfig = (decisionNode.config instanceof Map) ? (decisionNode.config as Map) : [:]
        List conditions = (dConfig.conditions ?: []) as List
        List names = []
        conditions.each { c ->
            Map cond = (c instanceof Map) ? (c as Map) : [:]
            resolveDevices(cond.config as Map).each { nm -> if (!names.contains(nm)) names << nm }
        }
        return names
    }

    List steps = []

    // One step per trigger-kind node, same convention as Rule Machine's
    // one-row-per-tDev.
    List triggerNodes = nodes.findAll { it instanceof Map && "${(it as Map).kind}" == 'trigger' }
    triggerNodes.each { Map t ->
        steps << [kind: 'trigger', label: labelForNode(t), devices: resolveDevices(t.config as Map)]
    }
    if (!triggerNodes) return steps

    // All triggers are expected to converge on one merge node before the
    // first real step - true in the one fixture examined. If that is not
    // the shape found, stop here rather than guess at a different one; the
    // triggers above are still shown even if nothing past them is.
    Set nextIds = [] as Set
    triggerNodes.each { Map t -> (outgoing["${t.id}"] ?: []).each { nextIds << "${it.to}" } }
    if (nextIds.size() != 1) return steps
    String cursor = nextIds.iterator().next()

    // Bounded so a graph shape this walker does not understand - a cycle,
    // or branching outside the single-decision/single-join case handled
    // below - cannot hang page generation.
    int guard = 0
    while (cursor && guard++ < 200) {
        Map node = nodesById["${cursor}"]
        if (!node) break
        String kind = "${node.kind}"
        List out = (outgoing["${cursor}"] ?: []) as List

        if (kind == 'merge') {
            cursor = out ? "${(out[0] as Map).to}" : null
            continue
        }

        if (kind == 'decision') {
            steps << [kind: 'action', ctrl: 'if', cond: decisionText(node), label: '', devices: decisionDevices(node)]
            Map trueEdge = out.find { "${(it as Map).port}" == 'true' } as Map
            Map falseEdge = out.find { "${(it as Map).port}" == 'false' } as Map
            String joinId = null

            if (trueEdge) {
                String c = "${trueEdge.to}"
                int g2 = 0
                while (c && g2++ < 200) {
                    Map n2 = nodesById["${c}"]
                    if (!n2 || "${n2.kind}" == 'merge') { joinId = c; break }
                    List rt = (n2.type == 'runRule' && n2.config instanceof Map && (n2.config as Map).appId != null) ?
                        ["${(n2.config as Map).appId}"] : []
                    steps << [kind: 'action', label: labelForNode(n2), devices: resolveDevices(n2.config as Map), ruleTargets: rt]
                    List o2 = (outgoing["${c}"] ?: []) as List
                    c = o2 ? "${(o2[0] as Map).to}" : null
                }
            }

            if (falseEdge) {
                steps << [kind: 'action', ctrl: 'else', cond: '', label: '', devices: []]
                String c = "${falseEdge.to}"
                int g3 = 0
                while (c && c != joinId && g3++ < 200) {
                    Map n3 = nodesById["${c}"]
                    if (!n3 || "${n3.kind}" == 'merge') { joinId = joinId ?: c; break }
                    List rt = (n3.type == 'runRule' && n3.config instanceof Map && (n3.config as Map).appId != null) ?
                        ["${(n3.config as Map).appId}"] : []
                    steps << [kind: 'action', label: labelForNode(n3), devices: resolveDevices(n3.config as Map), ruleTargets: rt]
                    List o3 = (outgoing["${c}"] ?: []) as List
                    c = o3 ? "${(o3[0] as Map).to}" : null
                }
            }

            steps << [kind: 'action', ctrl: 'endif', cond: '', label: '', devices: []]
            List joinOut = joinId ? ((outgoing[joinId] ?: []) as List) : []
            cursor = joinOut ? "${(joinOut[0] as Map).to}" : null
            continue
        }

        // Plain action node.
        List ruleTargets = (node.type == 'runRule' && node.config instanceof Map && (node.config as Map).appId != null) ?
            ["${(node.config as Map).appId}"] : []
        steps << [kind: 'action', label: labelForNode(node), devices: resolveDevices(node.config as Map), ruleTargets: ruleTargets]
        cursor = out ? "${(out[0] as Map).to}" : null
    }

    return steps
}

// Hubitat's built-in Notifier. Worth decoding because it stores an already
// rendered description of what it does in state.text, so the action step needs
// no reconstruction at all - only the trigger and the time window do.
List buildNotifierFlow(Map data, Map st) {
    Map text = st.text as Map
    if (!text) return []

    Map settingValues = [:]
    Map settingDevices = [:]
    (data.appSettings ?: []).each { s ->
        if (!(s instanceof Map)) return
        Map dl = s.deviceList as Map
        if (dl) settingDevices["${s.name}"] = dl.values().collect { stripTags("${it}") }
        if (s.value != null && "${s.value}") settingValues["${s.name}"] = "${s.value}"
    }

    List steps = []

    // Anything picked that is not an output device is what the Notifier watches.
    List triggerDevices = []
    settingDevices.each { String n, List d ->
        if (n in ['noteDev', 'speechDev', 'speakDevice']) return
        d.each { if (!triggerDevices.contains(it)) triggerDevices << it }
    }
    if (triggerDevices) {
        String devType = settingValues.devType ?: 'Device'
        String edge = settingValues.firstSwitch == 'true' ? 'on' : (settingValues.secondSwitch == 'true' ? 'off' : '')
        steps << [kind: 'trigger', ctrl: null, cond: '', devices: triggerDevices,
                  label: edge ? "${devType} turns ${edge}" : "${devType} event"]
    }

    String starting = settingValues.starting
    String ending = settingValues.ending
    if (starting && ending) {
        steps << [kind: 'required', ctrl: null, cond: '', devices: [],
                  label: "Only between ${starting} and ${ending}"]
    }

    ['text', 'audio'].each { String key ->
        String line = stripTags("${text[key] ?: ''}").trim()
        if (!line) return
        steps << [kind: 'action', ctrl: null, cond: '', devices: [], label: line]
    }

    return steps.size() > 1 ? steps : []
}

String expressionText(List expr, Map capabs) {
    if (!expr) return ''
    List parts = []
    expr.each { item ->
        String s = "${item}"
        if (s in ['AND', 'OR', 'NOT', '(', ')']) {
            parts << s
        } else {
            parts << (capabs[s] ?: "condition ${s}")
        }
    }
    // The same condition can be listed more than once by Rule Machine's internals.
    List deduped = []
    parts.each { if (!deduped || deduped[-1] != it) deduped << it }
    return deduped.join(' ')
}

List requiredDevices(List expr, Map settingDevices) {
    List devices = []
    (expr ?: []).each { item ->
        String s = "${item}"
        (settingDevices["rDev_${s}"] ?: []).each { if (!devices.contains(it)) devices << it }
    }
    return devices
}

Map actionStep(String num, Map act, Map settingValues, Map settingDevices, Map evalMap, Map capabs) {
    String method = (act.method ?: settingValues["actSubType.${num}"] ?: 'Action') as String

    List devices = []
    settingDevices.each { String n, List d ->
        if (n.endsWith(".${num}")) d.each { if (!devices.contains(it)) devices << it }
    }

    // Control-flow markers drive the branching layout. The rule's own `indent`
    // field is deliberately NOT used: on rule 2816 it disagrees with the real
    // nesting, so structure comes from these methods plus a stack instead.
    String ctrl = null
    if (method == 'getIfThen') ctrl = 'if'
    else if (method == 'getElseIf') ctrl = 'elseif'
    else if (method == 'getElse') ctrl = 'else'
    else if (method == 'getEndIf') ctrl = 'endif'

    String cond = ''
    if (ctrl == 'if' || ctrl == 'elseif') {
        cond = expressionText((evalMap["${act.rule}"] ?: []) as List, capabs)
        (requiredDevices((evalMap["${act.rule}"] ?: []) as List, settingDevices)).each {
            if (!devices.contains(it)) devices << it
        }
    }
    if (method == 'getWaitRule') {
        // A wait's devices come from the condition it waits on, not from an
        // action setting numbered after it.
        (requiredDevices((evalMap["${act.rule}"] ?: []) as List, settingDevices)).each {
            if (!devices.contains(it)) devices << it
        }
    }

    // An action that targets another rule names no device, so without this the
    // step reads as a bare "Run Actions" with nothing to say which rule. The
    // ids are carried through and turned into names in buildGraph, which is the
    // first point where every app label is known.
    List ruleTargets = []
    boolean selfTarget = false
    Map linkFam = RULE_LINK_ACTIONS[method] as Map
    if (linkFam) {
        // Same alias list as extractRuleLinks, and for the same reason: the
        // graph and this focused flowchart must read the same setting or they
        // can disagree about which rules an action targets.
        (linkFam.targets as List<String>).each { String targetSetting ->
            String rawTargets = settingValues["${targetSetting}.${num}"] ?: ''
            if (!rawTargets) return
            // A "*" entry is this rule itself, and can sit alongside real
            // targets - ["*","1809"] is "this rule AND Perimeter Closed".
            if (rawTargets.contains('*')) selfTarget = true
            String cleaned = rawTargets.replaceAll('[^0-9]', ' ').trim()
            if (cleaned) cleaned.split(' +').each { String t -> if (t && !ruleTargets.contains(t)) ruleTargets << t }
        }
    }

    return [
        kind: 'action',
        ctrl: ctrl,
        cond: cond,
        label: actionLabel(method, num, act, settingValues, settingDevices, evalMap, capabs),
        devices: devices,
        ruleTargets: ruleTargets,
        selfTarget: selfTarget,
    ]
}

String actionLabel(String method, String num, Map act, Map settingValues, Map settingDevices, Map evalMap, Map capabs) {
    switch (method) {
        case 'getSetVariable':
            // xVarV.<n> holds the target Hub Variable name. Verified against
            // one fixture (rule 2981, "_Test Variables") to carry a trailing
            // period - "TestHubUptime." - not yet confirmed whether that is
            // always present or an artifact of this specific picker state, so
            // it is stripped rather than assumed absent on other rules.
            String varName = (settingValues["xVarV.${num}"] ?: '').replaceAll(/\.$/, '')
            if (!varName) return 'Set Hub Variable [unresolved]'
            // valStringOp.<n> discriminates what the value is being set FROM.
            // Only the device-attribute source has been observed so far - a
            // fixed value or another variable as the source is unconfirmed
            // and falls through to the bare form below rather than guessing.
            if (settingValues["valStringOp.${num}"] == 'Device attribute') {
                String attr = settingValues["tCustomAttr.${num}"]
                List srcDevices = settingDevices["customDev.${num}"] ?: []
                if (attr && srcDevices) return "Set Hub Variable ${varName} from ${srcDevices[0]}.${attr}"
            }
            return "Set Hub Variable ${varName}"
        case 'getOnOffSwitch':
            return settingValues["onOff.${num}"] == 'true' ? 'On' : 'Off'
        case 'getSetColorTemp':
            String ctLabel = "Colour temperature ${settingValues["ctL.${num}"] ?: ''}K".trim()
            String level = settingValues["ctLevel.${num}"]
            return level ? "${ctLabel}, level ${level}" : ctLabel
        case 'getSetColor':
            return 'Set colour'
        case 'getWaitRule':
            String waitCond = expressionText((evalMap["${act.rule}"] ?: []) as List, capabs)
            String waitLabel = waitCond ? "Wait for: ${waitCond}" : 'Wait'
            return act.delay ? "${waitLabel} (timeout ${act.delay})" : waitLabel
        case 'getWaitEvents':
            return act.delay ? "Wait for event (timeout ${act.delay})" : 'Wait for event'
        case 'getDelay':
            return act.delay ? "Delay ${act.delay}" : 'Delay'
        case 'getMsg':
            String msg = settingValues["msg.${num}"]
            return msg ? "Notify: ${msg}" : 'Notify'
        case 'getSetPrivateBoolean':
            // pvTF.<n> holds the INVERSE of the value the rule page shows, and
            // an empty value counts as false. Verified against four rule pages
            // covering both stored forms: rule 1806 actions 31/33 (pvTF true
            // then false) and rule 1999 actions 8/7 (pvTF true then empty), each
            // displaying False then True in that order.
            //
            // The empty case is why this was shown as a bare 'Set Private
            // Boolean' until now. Nine of the 23 such actions on the test hub
            // store an empty string rather than 'false', which is how a Hubitat
            // bool input persists when it has never been switched on, so
            // defaulting to false here is what makes the negation total.
            return "Set Private Boolean ${settingValues["pvTF.${num}"] == 'true' ? 'False' : 'True'}"
        case 'getDefinedAction':
            return 'Run defined actions'
        case 'getSetVolume':
            // volumeVal.<n> holds the level; volume.<n> is the device picker.
            String vol = settingValues["volumeVal.${num}"] ?: settingValues["speakVolume.${num}"]
            return vol ? "Set volume ${vol}" : 'Set volume'
        case 'getChime':
            return 'Chime'
        case 'getCapture':
            return 'Capture device state'
        case 'getRestore':
            return 'Restore device state'
        case 'getStopActions':
            // Rule Machine's own wording for this action is "Cancel Timed
            // Actions". prettyMethod would derive "Stop Actions" from the
            // method name, which matches nothing the user sees on the rule.
            return 'Cancel Timed Actions'
        case 'getRuleActions':
            return 'Run Actions'
        case 'getPauseResumeRules':
            // One action type covers both directions, discriminated by pR.<n>.
            // Verified on one rule holding both: pR=true against a page reading
            // "Resume Rules: Back Door Night", pR empty against "Pause Rules:
            // Kettle button". Unlike pvTF this reads the right way round, so it
            // is used directly rather than negated. Empty is the default, which
            // is why an untouched action means Pause.
            return settingValues["pR.${num}"] == 'true' ? 'Resume Rules' : 'Pause Rules'
        case 'getSetMode':
            return 'Set mode'
        case 'getOCGarage':
            return 'Open / close garage'
        case 'getMuteUnmute':
            return 'Mute / unmute'
        case 'getHTTPPost':
            return 'HTTP request'
        case 'getFlashSwitch':
            return 'Flash'
        case 'getPollSwitch':
            return 'Poll'
        case 'getIfThen':
            return 'IF'
        case 'getElseIf':
            return 'ELSE IF'
        case 'getElse':
            return 'ELSE'
        case 'getEndIf':
            return 'END IF'
        default:
            return prettyMethod(method)
    }
}

// Rule Machine embeds the CURRENT reading in its condition text, e.g.
// "Illuminance of _ Average External Illuminance(9755) is < 200". That value is
// runtime noise in a static diagram and is stale the moment it is drawn.
String cleanCondition(String text) {
    String s = stripTags(text)
    s = s.replaceAll(/\([^)]*\)/, '')
    return s.replaceAll(/\s+/, ' ').trim()
}

String prettyMethod(String method) {
    String s = method.replaceAll('^get', '')
    s = s.replaceAll('([a-z0-9])([A-Z])', '$1 $2')
    return s ?: 'Action'
}

// Capabilities that expose no commands. A device selected through one of these
// is being READ, never driven - so it must not be reported as something the app
// acts on. Found via Critical Device Monitor, which subscribes only to its
// water/smoke/CO pickers but also has contact, motion, lock and garage-door
// pickers it merely inspects; without this check all of those were mislabelled
// as devices the app commands.
@Field static final List<String> SENSOR_CAPABILITIES = [
    'capability.contactSensor', 'capability.motionSensor', 'capability.waterSensor',
    'capability.smokeDetector', 'capability.carbonMonoxideDetector', 'capability.presenceSensor',
    'capability.illuminanceMeasurement', 'capability.temperatureMeasurement',
    'capability.relativeHumidityMeasurement', 'capability.battery', 'capability.powerMeter',
    'capability.energyMeter', 'capability.voltageMeasurement', 'capability.pressureMeasurement',
    'capability.carbonDioxideMeasurement', 'capability.ultravioletIndex', 'capability.accelerationSensor',
    'capability.shockSensor', 'capability.soundSensor', 'capability.tamperAlert',
    'capability.touchSensor', 'capability.sleepSensor', 'capability.stepSensor',
    'capability.threeAxis', 'capability.signalStrength', 'capability.pushableButton',
    'capability.holdableButton', 'capability.doubleTapableButton', 'capability.releasableButton',
]

// Device icon auto-detection, from a device's own RAW capability list (the
// /device/fullJson shape - PascalCase, e.g. "WaterSensor" - a different
// naming convention from the "capability.xxx" setting-type strings above, and
// not to be confused with them).
//
// Ordered, first match wins. Built against a 24-category taxonomy Gordon
// supplied (lighting, switches, dimmers, doors & windows, locks, motion,
// climate, environmental, safety, water, security, cameras, shades, energy,
// appliances, cleaning, media, buttons, presence, outdoor, vehicles,
// infrastructure, virtual, generic sensor, unknown), mapped onto what
// Hubitat's own capability model can actually tell them apart by - six of
// those categories cannot be, and are deliberately left uncaptured rather
// than faked; see the note below the table.
//
// Administrative/generic markers (Configuration, Refresh, Battery, the bare
// Sensor/Actuator markers) never appear in this table at all, so they can
// never win. Among what remains, a capability only a purpose-built device
// would declare (WaterSensor, GarageDoorControl, Thermostat...) is checked
// before a capability that commonly rides along on a device whose real
// purpose is something else (TemperatureMeasurement, Switch...), so a device
// with several capabilities resolves to the one that is actually its reason
// for existing.
@Field static final List ICON_RULES = [
    [key: 'locks',     label: 'Locks & access',       caps: ['Lock', 'LockCodes']],
    // Presence checked ahead of doors/motion on purpose - found live:
    // "Presence Manager Main Status" declares MotionSensor, ContactSensor AND
    // PresenceSensor together on one virtual status device. Its own name and
    // driver type ("Presence Manager Output") say what it is actually for;
    // PresenceSensor is never an incidental rider the way a contact/motion
    // marker can be on a multi-purpose virtual device, so it wins here.
    [key: 'presence',  label: 'Location & presence',  caps: ['PresenceSensor']],
    // Gordon's own taxonomy groups contact sensors, garage doors and gates
    // as one "Doors & windows" category rather than splitting garage doors
    // out - DoorControl is the generic (non-garage) door-actuator capability,
    // included for completeness even though no device on this hub uses it.
    [key: 'doors',     label: 'Doors & windows',      caps: ['ContactSensor', 'GarageDoorControl', 'DoorControl']],
    [key: 'water',     label: 'Water',                caps: ['WaterSensor', 'Valve']],
    [key: 'motion',    label: 'Motion & occupancy',   caps: ['MotionSensor']],
    [key: 'safety',    label: 'Safety',               caps: ['SmokeDetector', 'CarbonMonoxideDetector']],
    [key: 'buttons',   label: 'Buttons & remotes',    caps: ['PushableButton', 'HoldableButton',
                                                              'DoubleTapableButton', 'ReleasableButton']],
    [key: 'cameras',   label: 'Cameras & doorbells',  caps: ['ImageCapture']],
    [key: 'shades',    label: 'Shades & coverings',   caps: ['WindowShade']],
    // Found live: Gmail Broker (a notification-gateway integration device)
    // declares Notification alongside the same generic Actuator/Refresh
    // every device has - the only real signal it carries. Checked after
    // presence on purpose: Mobile Proxy and the phone devices also declare
    // Notification alongside PresenceSensor, and their actual purpose is
    // presence tracking, not notification delivery, so presence must win
    // for them.
    [key: 'broker',    label: 'Notification gateway', caps: ['Notification']],
    // Climate checked ahead of switch/lighting - found live: all three
    // Sensibo Pods carry Switch alongside Thermostat (their own on/off
    // baseline, same shape of problem as the Garage Dome Siren/security case
    // below) and were resolving to plain switches before this was added.
    // Fans are grouped into climate control per Gordon's own taxonomy
    // ("Thermostats, HVAC, air conditioners, fans"), not split out alone.
    [key: 'climate',   label: 'Climate control',      caps: ['Thermostat', 'ThermostatMode', 'ThermostatSetpoint',
                                                              'ThermostatCoolingSetpoint', 'ThermostatHeatingSetpoint',
                                                              'ThermostatOperatingState', 'ThermostatFanMode',
                                                              'FanControl']],
    [key: 'lighting',  label: 'Lighting',             caps: ['Light', 'ColorControl', 'ColorTemperature',
                                                              'ColorMode', 'SwitchLevel', 'LightEffects']],
    [key: 'security',  label: 'Security & alarms',    caps: ['Alarm', 'Chime', 'Tone']],
    [key: 'media',     label: 'Media & audio',        caps: ['AudioVolume', 'SpeechSynthesis', 'MediaTransport',
                                                              'MusicPlayer']],
    // Switch checked last among the "defining" tier, not alongside lighting -
    // found live: Garage Dome Siren carries Switch alongside Alarm/Chime/Tone
    // (its own on/off baseline) and was resolving to 'switch' before this was
    // reordered. Switch is the single most common baseline capability on any
    // actuator, so it must be the tier of last resort before the generic
    // measurement-capability fallback, not a peer of the capabilities that
    // are actually specific to a device's purpose.
    [key: 'switches',  label: 'Switches & outlets',   caps: ['Switch', 'Outlet']],
    // Checked AFTER switches on purpose: a smart plug used to power something
    // (Towel Rail, Patio Camera Charger) also reports PowerMeter/EnergyMeter
    // as a bonus, and should still read as what it is used for - a switch -
    // not as an energy monitor. This tier only wins for a device that meters
    // power with no on/off control of its own to be classified by first.
    [key: 'energy',    label: 'Energy',               caps: ['PowerMeter', 'EnergyMeter', 'VoltageMeasurement']],
    [key: 'environmental', label: 'Environmental sensors', caps: ['TemperatureMeasurement', 'IlluminanceMeasurement',
                                                              'RelativeHumidityMeasurement', 'PressureMeasurement',
                                                              'CarbonDioxideMeasurement', 'UltravioletIndex']],
    [key: 'sensor',    label: 'Generic sensor',       caps: ['Sensor']],
    // Override-only, on purpose: an empty caps list can never match in
    // autoDetectIconKey (.any{} on an empty list is always false), so
    // these two never win a scan. They exist so Gordon can manually tag a
    // device as Hub or AI in the Device icons panel even though nothing on
    // the hub currently justifies auto-detecting either - see the note
    // below the table for why CoCoHue Bridge and a hypothetical AI-node
    // device can't be told apart from an ordinary integration device by
    // capability alone. Kept in this table, not a separate list, so they
    // share the same label-building and ICON_KEYS-derivation code as every
    // real rule.
    [key: 'hub',       label: 'Hub & infrastructure', caps: []],
    [key: 'ai',        label: 'AI node',              caps: []],
    // These three also have an empty caps list - not override-only like
    // hub/ai above, but driven entirely by ICON_NAME_HINTS below rather
    // than capability. Appliance, Network and Display have no distinguishing
    // Hubitat capability at all (see the note further down), but their name
    // alone is usually unambiguous - Tuya Kettle, Internet Down, and the
    // Google Nest Hub "display" devices are all bare Virtual Switches or
    // Chromecast integration devices with nothing capability-wise to tell
    // them apart from any other switch or speaker.
    [key: 'appliance', label: 'Appliance',            caps: []],
    [key: 'network',   label: 'Internet/network',     caps: []],
    [key: 'display',   label: 'Display',              caps: []],
]

// Name-based hints, checked BEFORE capability - added at Gordon's explicit
// request after real misclassifications capability alone cannot fix:
// Festoon Lights, Tuya Kettle, Internet Down and the Google Nest Hub
// "display" devices are all either bare Virtual Switches or share a
// capability set with an unrelated device type, so nothing in ICON_RULES
// can tell them apart from a generic switch or speaker - but the device's
// own name already says exactly what it is.
//
// Ordered like ICON_RULES: more specific/unambiguous words checked first,
// so "Kitchen Downlight Button" matches "button" before this ever reaches
// the substring "light" inside "Downlight". Matched as whole words only
// (the name is split on anything that is not a letter or digit), not
// substrings - "highlight" and "flashlight" do not become lighting.
@Field static final List ICON_NAME_HINTS = [
    [key: 'buttons',   words: ['button', 'remote']],
    [key: 'appliance', words: ['kettle', 'oven', 'fridge', 'refrigerator', 'dishwasher',
                                'washer', 'dryer', 'microwave', 'toaster']],
    [key: 'network',   words: ['internet', 'wifi', 'router', 'modem']],
    // 'nest' rather than 'hub' for the Google Nest Hub devices - 'hub' alone
    // is too generic a word to risk matching against unrelated devices.
    [key: 'display',   words: ['display', 'monitor', 'tablet', 'nest']],
    // Both spellings kept - Gordon's own "Dehumidifyer" device is spelled
    // without the second i, and word-matching is exact, not fuzzy.
    [key: 'climate',   words: ['heater', 'dehumidifier', 'dehumidifyer', 'humidifier', 'aircon']],
    [key: 'lighting',  words: ['light', 'lights', 'lamp', 'bulb']],
]

// Splits a device name into lowercase whole words for ICON_NAME_HINTS -
// deliberately not a capability, this reads the label the user gave the
// device, which this project otherwise avoids doing anywhere else. Kept to
// a small, curated word list rather than fuzzy/partial matching so a false
// positive stays rare and easy to reason about.
List nameWords(String name) {
    return (name ?: '').toLowerCase().split('[^a-z0-9]+') as List
}

String autoDetectIconKeyForDevice(String name, List capabilities) {
    List words = nameWords(name)
    for (hint in ICON_NAME_HINTS) {
        Map h = hint as Map
        if ((h.words as List).any { words.contains(it) }) return h.key as String
    }
    return autoDetectIconKey(capabilities)
}
//
// Six of Gordon's 24 categories are deliberately not in the table above,
// checked directly against real devices before giving up on them rather
// than assumed:
// - Dimmers: not separable from Lighting. A dimmer module and a dimmable
//   bulb both declare plain SwitchLevel; nothing distinguishes "this is a
//   dimmer for an unknown fixture" from "this is a dimmable light."
// - Appliances (washers, ovens, fridges) and Cleaning (robot vacuums):
//   Hubitat has no base capability for either. Community drivers for both
//   typically expose nothing beyond Switch/Outlet, identical to any other
//   smart plug.
// - Outdoor (weather stations, pools/spas) and Vehicles (EV chargers,
//   vehicle presence): not a distinct capability grouping - a weather
//   station reads as Environmental sensors, an EV charger as Switch or
//   Energy, vehicle presence as Location & presence. Correct by capability,
//   just not separately labelled "outdoor" or "vehicle".
// - Hub & infrastructure (bridges, repeaters, network monitors): the one
//   capability that looks relevant, NetworkDevice, is also carried by
//   ordinary media devices (the Chromecast speakers all declare it), so
//   using it here would misclassify them. No safe signal found. Checked
//   again directly against CoCoHue Bridge and Hub Information Driver when
//   Gordon asked for a "Hub" category specifically: CoCoHue Bridge reports
//   only [Actuator, Refresh, Initialize] - no capability distinguishes a
//   bridge/hub device from any other integration-managed actuator.
// - Voice assistants (Google Home Mini, Google Nest Hub): also checked
//   directly when Gordon asked for an "Assistant" category. These report
//   the exact same capabilities as a plain Chromecast speaker (AudioVolume,
//   MediaTransport, SpeechSynthesis, NetworkDevice) - nothing marks a
//   device as an assistant rather than a speaker.
// - Virtual & coordination: device.virtual is a real, separate field, but
//   deliberately not used to override the table above - a virtual light
//   switch is still functionally a light switch on this map, and losing
//   that in favour of a generic "virtual" icon would make the map less
//   useful, not more. Also Heater/dehumidifier and Display-vs-speaker,
//   found while testing the table against real devices, not part of
//   Gordon's list but the same shape of gap: Gordon's Gas Heater and
//   Dehumidifyer report only [Switch, Refresh], and a Chromecast "display"
//   reports the exact same capabilities as a Chromecast speaker - identical
//   in both cases, nothing to key off without reading the device name.

// The keys a manual override may pick from - ICON_RULES' own keys plus
// 'unknown' and 'auto', the two states outside the table itself (nothing
// matched, and no override set). Written literally rather than derived from
// ICON_RULES to avoid relying on static-initializer ordering between two
// @Field constants.
@Field static final List<String> ICON_KEYS = [
    'locks', 'presence', 'doors', 'water', 'motion', 'safety', 'buttons',
    'cameras', 'shades', 'broker', 'climate', 'lighting', 'security', 'media',
    'switches', 'energy', 'environmental', 'sensor', 'hub', 'ai', 'appliance',
    'network', 'display', 'unknown',
]

// Nothing in ICON_RULES matched - not a guess, an honest "this app does not
// know what kind of device this is", drawn as a "?" rather than defaulting to
// some other icon that would claim more than is true.
String autoDetectIconKey(List capabilities) {
    List caps = (capabilities ?: []) as List
    for (rule in ICON_RULES) {
        Map r = rule as Map
        if ((r.caps as List).any { caps.contains(it) }) return r.key as String
    }
    return 'unknown'
}

// Commands that leave the device in a lasting state, so two apps driving the
// same device really can fight. Sending two notifications or two chimes is not
// a conflict, which is why Mobile Proxy topping a "contested" list by 20 apps
// would be noise rather than a finding.
@Field static final List<String> STATEFUL_CAPABILITIES = [
    'capability.switch', 'capability.switchLevel', 'capability.colorControl',
    'capability.colorTemperature', 'capability.lock', 'capability.garageDoorControl',
    'capability.doorControl', 'capability.windowShade', 'capability.thermostat',
    'capability.thermostatMode', 'capability.thermostatSetpoint', 'capability.fanControl',
    'capability.valve', 'capability.light', 'capability.bulb', 'capability.outlet',
]

boolean isStatefulCapability(String settingType) {
    return STATEFUL_CAPABILITIES.contains(settingType)
}

String roleForSetting(String settingName, String settingType, String devId, List subscribed) {
    // Rule Machine's private naming: tDev<n> = trigger device, rDev_<n> =
    // condition device (both plain IF conditions and the required expression).
    if (settingName.startsWith('tDev')) return 'trigger'
    if (settingName.startsWith('rDev')) return 'constraint'
    // The wildcard picker means the app took devices of ANY type, which is what
    // integrations that publish devices to an external system do - Maker API and
    // Google Home both use it. They neither react to nor drive these devices on
    // their own, so calling them triggers or actions misrepresents them (Maker
    // API Export alone contributed 192 bogus "commands this device" edges).
    // Checked before the subscription test because such apps do subscribe, to
    // push state outwards.
    if (settingType == 'capability.*') return 'exposed'
    // General signal: an app subscribes to what it listens to.
    if (subscribed.contains(devId)) return 'trigger'
    // Read-only by capability: watched, not driven.
    if (SENSOR_CAPABILITIES.contains(settingType)) return 'monitor'
    return 'action'
}

void addRole(Map roles, String devId, String role) {
    List existing = (roles[devId] ?: []) as List
    if (!existing.contains(role)) existing << role
    roles[devId] = existing
}

String stripTags(String s) {
    return s ? s.replaceAll('<[^>]*>', '').trim() : s
}

// Found live: a built-in app's own name ("Hubitat(R) Dashboards") came back
// from /installedapp/statusJson with its registered-trademark symbol replaced
// by the Unicode replacement character - always evidence of a decode mismatch
// somewhere in the fetch, never a character any real name would intentionally
// contain. Root cause not chased down (cosmetic, low priority - see
// BACKLOG.md); this just keeps the artifact from propagating any further than
// it already has. Written as a numeric codepoint rather than a unicode escape
// literal in this file's source, since an escape literal here is Groovy's own
// string syntax and would be consumed at parse time rather than reach this
// method - the same trap check_template.sh below exists to catch.
String stripReplacementChar(String s) {
    return s ? s.replace(new String(Character.toChars(0xFFFD)), '') : s
}

// Size of a hub collection whose shape is not guaranteed. Anything else,
// including null and a bare value, counts as zero rather than throwing.
int countOf(def v) {
    if (v instanceof List) return (v as List).size()
    if (v instanceof Map) return (v as Map).size()
    return 0
}

// scheduledJobs from statusJson is a List when an app has more than one job,
// but a single job comes back as a bare Map - one job's own fields (handler,
// nextRunTime, schedule, status, prevRunTime), not a collection of jobs at
// all. countOf's Map branch counts THOSE FIELDS, so a one-job app with five
// fields on its job record was reported as "5 scheduled jobs". Normalising
// to a list first, always of job maps and never of a job's own keys, is what
// makes both the count and the per-job detail below correct at once.
List scheduledJobList(def raw) {
    if (raw instanceof List) return raw as List
    if (raw instanceof Map && raw) return [raw as Map]
    return []
}

// Removes hub-injected status from an app label, CONTENT AND ALL, where
// stripTags removes only the markup and keeps the words.
//
// Hubitat wraps the status it appends in a span - "Christmas Cheer <span
// style='color:red'>(Required Expression false)</span>" - so the span is what
// identifies it, not the English inside it. Keying on the markup rather than on
// the text is the whole point: it holds for whatever status a future firmware
// injects, in whatever language, and it can never eat a name the USER wrote.
// A pattern matching a trailing parenthetical would turn "Front Walkway
// Announce (Day)" and "(Night)" into the same node.
String stripStatusMarkup(String s) {
    if (!s) return s
    // Non-greedy, so two spans in one label do not collapse into one match
    // taking everything between them. Removing a span from the middle of a
    // label leaves a double space behind, hence the squeeze.
    return stripTags(s.replaceAll('<span[^>]*>.*?</span>', '')).replaceAll(' +', ' ')
}

// ===================================================================================================================
// Graph building
// ===================================================================================================================

// Label only, for a rule named as the target of a rule-to-rule link that the
// device-driven scan never reached. Failure is not an error - the node is still
// drawn, just with its id for a name.
Map fetchAppName(String appId) {
    Map out = [label: null, type: null, drawLabel: null, missing: false]
    try {
        httpGet([uri: "http://127.0.0.1:8080/installedapp/statusJson/${appId}", timeout: 10]) { resp ->
            Map data = (resp.data instanceof Map) ? (resp.data as Map) : [:]
            Map installedApp = data.installedApp as Map
            if (installedApp?.label || installedApp?.name) {
                String rawLabel = (installedApp?.label ?: installedApp?.name) as String
                out.label = stripTags(rawLabel)
                out.drawLabel = stripStatusMarkup(rawLabel)
                out.type = installedApp?.name
            } else {
                // A deleted app still answers 200 here, with an empty shell
                // rather than a 404. So a rule naming a rule that no longer
                // exists is not an error to swallow, it is a dangling
                // reference worth showing: the action stays in the calling
                // rule and silently does nothing.
                out.missing = true
            }
        }
    } catch (Exception ex) {
        log.warn "${app.label}: could not name linked rule ${appId}: ${ex.message}"
    }
    return out
}

// Name (and deleted status) of a rule referenced by another rule. Prefers what
// the scan already read, falls back to a direct lookup, and finally to the
// bare id. Cached because a rule can be both a flowchart target and a graph
// edge target.
//
// Returns [label, missing] rather than a bare label. The label alone used to
// be the only record of a deleted target - "Rule 2328 - deleted" - which meant
// the only way to find deleted references again was to string-match that
// suffix. missing is now a fact a caller (Insights) can filter on directly,
// separate from unscanned: unscanned means a real app the scan never reached,
// missing means the id no longer resolves to anything at all.
Map linkedRuleName(String targetId, Map appInfo, Map cache) {
    if (cache.containsKey(targetId)) return cache[targetId] as Map
    Map target = appInfo[targetId] as Map
    String label = target?.label as String
    String draw = target?.drawLabel as String
    boolean missing = false
    if (!label) {
        Map named = fetchAppName(targetId)
        label = named.label as String
        draw = named.drawLabel as String
        missing = named.missing as boolean
        // Named so the user can act on it. "Rule 2328" invites a hunt for a
        // rule that is not there; saying so turns it into a finding.
        if (!label && missing) label = "Rule ${targetId} - deleted"
    }
    if (!label) label = "Rule ${targetId}"
    // Falls back to the full label, which is what a scan from before drawLabel
    // existed will have stored, and what a bare "Rule 2328" needs anyway.
    Map result = [label: label, draw: draw ?: label, missing: missing]
    cache[targetId] = result
    return result
}

// Action steps carry the ids of any rule they act on. Turned into names here,
// added to the step's device list so the flowchart renders them under the
// action exactly as it renders device names.
List resolveFlowTargets(List flow, Map appInfo, Map cache) {
    (flow ?: []).each { step ->
        if (!(step instanceof Map)) return
        Map s = step as Map
        List targets = (s.ruleTargets ?: []) as List
        if (!targets) return
        List devices = (s.devices ?: []) as List
        // Named the way the rule page names it: "This Rule, Perimeter Closed".
        // Only worth saying when the action reaches beyond this rule - a rule
        // setting only its own boolean needs no list at all.
        if (s.selfTarget && !devices.contains('This Rule')) devices << 'This Rule'
        targets.each { t ->
            String nm = (linkedRuleName("${t}", appInfo, cache).label) as String
            if (!devices.contains(nm)) devices << nm
        }
        s.devices = devices
    }
    return flow
}

// Three label forms, not two, and each is drawn somewhere different:
//
//   label  short, drawn on the canvas with nothing focused
//   draw   full identity, drawn on the canvas with an app focused
//   title  everything including hub status, shown only on hover
//
// draw exists because Hubitat injects live status into an app's label, and on
// a focused map that status was the widest thing on screen and identical on
// every node, so long names overwrote each other while carrying no information
// that told them apart. The status is still one hover away.
//
// drawLabel defaults to fullLabel, so a caller with nothing to strip - every
// device, and any app the hub has not annotated - passes one argument as before
// and gets identical output.
Map nodeEntry(String id, String fullLabel, String group, String subtitle = null, String drawLabel = null) {
    String label = fullLabel ?: id
    String clean = drawLabel ?: label
    // Truncation runs on the cleaned text, so a name that is short in its own
    // right survives whole. "Christmas Cheer" was reaching this as "Christmas
    // Cheer (Required Expression false)" and being cut to "Christmas Cheer
    // (Requi…", which is longer, uglier and no more informative.
    String shortLabel = clean
    if (shortLabel.length() > 24) shortLabel = "${shortLabel.substring(0, 22)}…"
    return [
        id: id,
        label: shortLabel,
        draw: subtitle ? "${clean} (${subtitle})" : clean,
        title: subtitle ? "${label} (${subtitle})" : label,
        group: group,
    ]
}

// Why an app with no device, no rule link and no endpoint is on the map anyway.
//
// Ordered by how completely each fact explains the emptiness. A container is
// fully explained by its children and nothing else needs saying. A schedule
// explains an app that acts on the hub rather than on devices, which is exactly
// what Rebooter does. Falling all the way through is itself the answer, and the
// only one of these worth a second look.
String inertReason(Map inert, Map appInfo, String parentId = null) {
    if (!inert) return 'no relationships found'

    int kids = (inert.kids ?: 0) as Integer
    if (kids > 0) return "holds ${kids} app${kids == 1 ? '' : 's'}"

    int devs = (inert.devs ?: 0) as Integer
    if (devs > 0) return "owns ${devs} device${devs == 1 ? '' : 's'}"

    int sched = (inert.sched ?: 0) as Integer
    if (sched > 0) return "runs on a schedule, ${sched} job${sched == 1 ? '' : 's'}"

    int subs = (inert.subs ?: 0) as Integer
    if (subs > 0) return "listens to ${subs} event${subs == 1 ? '' : 's'}"

    // Last, because being someone's child explains where an app came from but
    // not what it does. A button rule under a Button Controller is still an
    // app that references nothing this map can see.
    String parent = parentId
    if (parent) {
        Map p = appInfo[parent] as Map
        String name = (p?.drawLabel ?: p?.label) as String
        if (name) return "child of ${name}"
    }

    return 'references nothing'
}

Map buildGraph() {
    Map labels = (state.deviceLabels ?: [:]) as Map
    Map deviceCaps = (state.deviceCapabilities ?: [:]) as Map
    Map iconOverrides = (state.deviceIconOverrides ?: [:]) as Map
    Map iconNotes = (state.deviceIconNotes ?: [:]) as Map
    Map appInfo = (state.appInfo ?: [:]) as Map

    Map<String, Map> nodes = [:]
    List<Map> edges = []
    List<String> seen = []
    Map flows = [:]
    Map nameCache = [:]
    Map priorFlows = ((state.graph ?: [:]) as Map).flows as Map ?: [:]

    // Every Hub Variable name confirmed anywhere on the hub via a structured
    // reference (a write, or a condition/trigger read - never free text on
    // its own). Collected hub-wide, in its own pass, before any edge is
    // drawn: a free-text candidate found in one app can only be trusted
    // against structured evidence that might live in a completely different
    // app's rule. See extractHubVariableReads for why an unconfirmed match
    // cannot be trusted alone - Rule Machine's own %device%/%time%/%date%
    // notification tokens match the same pattern as a real Hub Variable
    // reference and are not one.
    Set confirmedVarNames = []
    appInfo.each { String appId, info ->
        if (!(info instanceof Map)) return
        Map appMap = info as Map
        (appMap.hubVarWrites ?: []).each { Map w -> if (w.variable) confirmedVarNames << "${w.variable}" }
        (appMap.hubVarReads ?: []).each { Map r -> if (r.variable && r.confirmed) confirmedVarNames << "${r.variable}" }
    }

    appInfo.each { String appId, info ->
        if (!(info instanceof Map)) return
        Map appMap = info as Map
        Map roles = (appMap.roles ?: [:]) as Map
        // A rule whose only relationship is to another rule has no device roles
        // at all, and used to be dropped here before it could be drawn.
        //
        // An app with nothing at all is no longer dropped either. It used to be,
        // back when device-led discovery meant such an app was never found - but
        // once the scan enumerates every installed app, silently dropping 13 of
        // them makes the summary claim a count the map does not show, and leaves
        // the Focus app list disagreeing with both. Drawn with a reason instead.
        // A fetch that threw never populated roles/ruleLinks/endpoints, so an
        // unreadable app looked identical to one that genuinely references
        // nothing - the exact same empty collections, for a completely
        // different reason. "Could not be read" and "references nothing" are
        // different findings and must not render the same way, so unreadable
        // is checked and excluded from inert rather than folded into it.
        boolean unreadable = appMap.error != null
        // A rule whose only relationship is to a Hub Variable (e.g. "_Test
        // Variables Trigger", which touches no device at all) is not inert,
        // and was being marked so before this - dimmed amber, labelled "no
        // device or rule relationship", despite drawing a real edge on the
        // map underneath that label. Checked against what will actually be
        // drawn, not just whether hubVarReads is non-empty - an unconfirmed
        // free-text candidate that confirmedVarNames goes on to filter out
        // must not itself count as a relationship, or an app with only a
        // false-positive candidate would be wrongly called non-inert too.
        boolean hasVarRelationship = (appMap.hubVarWrites ?: []) ||
            (appMap.hubVarReads ?: []).any { Map r -> r.confirmed == true || confirmedVarNames.contains("${r.variable}") }
        boolean inert = !unreadable && !roles && !(appMap.ruleLinks ?: []) && !(appMap.endpoints ?: []) && !hasVarRelationship
        // This app's own instances are the one exception, and stay hidden. They
        // are excluded from the graph deliberately, so drawing them as apps that
        // reference nothing would be actively misleading: they reference the
        // whole hub.
        if (inert && "${appMap.type}".startsWith(APP_FAMILY)) return

        String appNodeId = "a${appId}"
        String appLabel = appMap.inactive ? "${appMap.label} [paused]" : (appMap.label as String)
        // [paused] is this app's own annotation, not the hub's, so it belongs on
        // the drawn label too. drawLabel is absent from a scan taken before it
        // existed, hence the fallback rather than a forced rescan.
        String appDraw = (appMap.drawLabel ?: appMap.label) as String
        if (appMap.inactive) appDraw = "${appDraw} [paused]"
        // An inert app's subtitle carries why it is empty instead of its engine.
        // The engine is the less useful of the two here: "Rule Machine" on a
        // square with no edges raises the question, "holds 46 apps" answers it.
        String subtitle = unreadable ? 'could not be read' :
            (inert ? inertReason(appMap.inert as Map, appInfo, appMap.parent as String) : (appMap.type as String))
        nodes[appNodeId] = nodeEntry(appNodeId, appLabel, 'app', subtitle, appDraw)
        // The raw underlying type, unconditionally - subtitle above is
        // overwritten with the inert/unreadable reason for those nodes, so it
        // cannot be used to tell a rule apart from any other app once a node
        // is in either of those states. Needed so a pivot table can filter to
        // actual rules rather than "everything typed as an app", which was a
        // rule reached only as another rule's target counted the same as
        // LIFX Light Manager.
        nodes[appNodeId].appType = "${appMap.type}"
        if (appMap.inactive) nodes[appNodeId].inactive = true
        if (unreadable) {
            nodes[appNodeId].unreadable = true
            nodes[appNodeId].reason = subtitle
            nodes[appNodeId].errorDetail = "${appMap.error}"
        }
        if (inert) {
            nodes[appNodeId].inert = true
            // Carried as its own field rather than left for the page to pick
            // back out of the title. Parsing it out would mean a regex literal
            // inside the GString that builds the page, which is the single
            // mistake this file has been killed by three times.
            nodes[appNodeId].reason = subtitle
            // What the click opens. Without these, focusing one of these nodes
            // blanks the map to a lone square and opens no panel, because it has
            // no edges to draw and no rule flow to render - it looked like a
            // dead click rather than like an app with nothing attached.
            //
            // Child IDS, not names. Every child is already a node on this map
            // carrying its own label, so sending names too would ship 46
            // duplicate strings for Rule Machine alone.
            List kidIds = []
            appInfo.each { String otherId, other ->
                if (!(other instanceof Map)) return
                if ("${(other as Map).parent}" == appId) kidIds << "a${otherId}"
            }
            if (kidIds) nodes[appNodeId].kids = kidIds
            Map inertFacts = (appMap.inert ?: [:]) as Map
            // The COUNT, sent alongside the ids. It is what lets the panel tell
            // "holds nothing" apart from "holds something this scan did not
            // record the ids for", which is every graph built before parent ids
            // were captured.
            if ((inertFacts.kids ?: 0) as Integer) nodes[appNodeId].holds = inertFacts.kids
            if ((inertFacts.sched ?: 0) as Integer) {
                nodes[appNodeId].sched = inertFacts.sched
                // Absent rather than empty on a graph built before this was
                // captured, so the panel can tell "no detail recorded" apart
                // from "recorded, and there is genuinely nothing to add".
                if (inertFacts.schedJobs) nodes[appNodeId].schedJobs = inertFacts.schedJobs
            }
            if ((inertFacts.subs ?: 0) as Integer) nodes[appNodeId].subs = inertFacts.subs
            if ((inertFacts.devs ?: 0) as Integer) nodes[appNodeId].devs = inertFacts.devs
        }
        // Deliberately outside the if (inert) block above, unlike kids/sched/
        // subs/devs which exist specifically to give an EMPTY container node
        // something to show when focused. parent is a plain structural fact
        // true of the app whether or not it happens to be inert - a real
        // Button Rule child with its own actions is not inert, but still has
        // a parent Button Controller. Gating this the same way the inert-only
        // fields are gated left every non-inert child's own node with no
        // parent at all, an asymmetry an external export caught: 64 apps
        // appeared in a container's kids list but had parent: null
        // themselves, because kids is computed by scanning ALL of appInfo
        // for children regardless of the child's own inert status, while
        // parent was only ever set on a node already being built for the
        // inert-focus-panel reason.
        if (appMap.parent) nodes[appNodeId].parent = "a${appMap.parent}"
        // Flows come from appInfo during a scan, and from the previously built
        // graph on a rebuild - see finishScan, which strips them from appInfo
        // once they are here, so the same 60KB is not held twice.
        if (appMap.flow) flows[appNodeId] = resolveFlowTargets(appMap.flow as List, appInfo, nameCache)
        else if (priorFlows[appNodeId]) flows[appNodeId] = priorFlows[appNodeId]

        roles.each { String devId, devRoles ->
            String devNodeId = "d${devId}"
            if (!nodes[devNodeId]) {
                nodes[devNodeId] = nodeEntry(devNodeId, (labels[devId] ?: "Device ${devId}") as String, 'device')
                // The user's own correction wins outright when one exists;
                // only otherwise is it worth asking the name/capability
                // fallback what this device is.
                nodes[devNodeId].icon = (iconOverrides[devId] as String) ?:
                    autoDetectIconKeyForDevice((labels[devId] ?: '') as String, deviceCaps[devId] as List)
                // A freeform note on an unrecognised device surfaces in the
                // tooltip, not just the icon panel - otherwise the only place
                // that context exists is a table the user has to go find.
                String note = (iconNotes[devId] as String)?.trim()
                if (note) nodes[devNodeId].title = "${nodes[devNodeId].title} (noted: ${note})"
            }
            List statefulDevices = (appMap.stateful ?: []) as List
            (devRoles as List).each { String role ->
                String key = "${appNodeId}|${devNodeId}|${role}"
                if (seen.contains(key)) return
                seen << key
                Map edge = [from: appNodeId, to: devNodeId, kind: role]
                if (role == 'action' && statefulDevices.contains(devId)) edge.stateful = true
                edges << edge
            }
        }

        // Hub Variables: shared, hub-scoped state, not owned by any one app -
        // so unlike a device the node is identified by name, not by an id the
        // scan discovered it under.
        (appMap.hubVarWrites ?: []).each { Map w ->
            String varName = "${w.variable}"
            if (!varName) return
            String varNodeId = "v${varName}"
            if (!nodes[varNodeId]) nodes[varNodeId] = nodeEntry(varNodeId, varName, 'hubVariable')
            String key = "${appNodeId}|${varNodeId}|write"
            if (seen.contains(key)) return
            seen << key
            Map edge = [from: appNodeId, to: varNodeId, kind: 'write']
            if (w.sourceDevice && w.sourceAttr) edge.detail = "from ${w.sourceDevice}.${w.sourceAttr}"
            edges << edge
        }
        // Stored from-app-to-variable the same as a write, NOT reversed, even
        // though a read conceptually flows variable-to-rule - every edge on
        // this map has an app in `from` (see the comment on pivotKindOptions
        // in the page template), and other code depends on that holding for
        // every edge, not just device ones. The visual arrow is corrected
        // instead, the same way a device trigger already is: 'read' joins
        // 'trigger'/'constraint'/'monitor' in the JS inbound list so the
        // arrowhead still points at the app despite `from` being the app.
        (appMap.hubVarReads ?: []).each { Map r ->
            String varName = "${r.variable}"
            if (!varName) return
            // An unconfirmed (free-text-only) candidate is only drawn if
            // some app, anywhere on the hub, confirms the same name via a
            // structured reference. Otherwise it is exactly the RM-token
            // false positive this pre-pass exists to catch - dropped
            // silently rather than drawn as a guess.
            if (r.confirmed != true && !confirmedVarNames.contains(varName)) return
            String varNodeId = "v${varName}"
            if (!nodes[varNodeId]) nodes[varNodeId] = nodeEntry(varNodeId, varName, 'hubVariable')
            String key = "${appNodeId}|${varNodeId}|read"
            if (seen.contains(key)) return
            seen << key
            edges << [from: appNodeId, to: varNodeId, kind: 'read']
        }
    }

    // App-to-app edges are emitted in a second pass, so a link is still drawn
    // when it points at a rule that came later in the scan than the rule
    // pointing at it.
    appInfo.each { String appId, info ->
        if (!(info instanceof Map)) return
        List links = ((info as Map).ruleLinks ?: []) as List
        if (!links) return
        String fromId = "a${appId}"
        if (!nodes[fromId]) return

        links.each { link ->
            if (!(link instanceof Map)) return
            String targetId = "${(link as Map).to}"
            String kind = "${(link as Map).kind}"
            String toId = "a${targetId}"

            if (!nodes[toId]) {
                // The target was never reached by the scan. Apps are discovered
                // through the devices you selected, so a rule that touches no
                // selected device is invisible to phase one - which is normal
                // for a Rule Function. Drawn anyway, labelled for what it is,
                // rather than dropping the relationship on the floor.
                Map target = appInfo[targetId] as Map
                // Worth the lookup when the scan missed it: the alternative is
                // a node reading "Rule 1845", which tells the user nothing.
                Map named = linkedRuleName(targetId, appInfo, nameCache)
                // A deleted target gets no subtitle. Every other node uses it
                // for the engine, which a deleted rule no longer reports, and
                // "not scanned" is both redundant and self-contradictory next
                // to a label that already says deleted. That label is the only
                // name this node will ever have, so it carries the fact alone.
                String subtitle = named.missing ? null : (target?.type ?: 'not scanned') as String
                nodes[toId] = nodeEntry(toId, named.label as String, 'app', subtitle, named.draw as String)
                if (!target) nodes[toId].unscanned = true
                // Distinct from unscanned: unscanned is a real app the scan
                // never reached, missing is an id that no longer resolves to
                // anything. A deleted target is both (it was never reached
                // AND does not exist), but Insights needs to tell them apart
                // to report "broken reference" rather than "just not scanned".
                if (named.missing) nodes[toId].missing = true
            }

            String key = "${fromId}|${toId}|${kind}"
            if (seen.contains(key)) return
            seen << key
            edges << [from: fromId, to: toId, kind: kind]
        }
    }

    // External systems, declared by the user rather than discovered. Emitted
    // last so every app node exists.
    //
    // Nodes are keyed on the system NAME, so two apps naming the same bridge
    // share one node. That sharing is the whole point: it is what turns a list
    // of dependencies into "everything that stops working if this fails".
    // User declarations override the shared registry rather than adding to it.
    // A user who has said anything at all about an app type has looked at it,
    // and their answer beats a curated guess - Kasa and Tapo can each be local
    // or cloud depending on how they were set up, so the shipped answer is
    // right for roughly half of installs.
    List externals = []
    List userRows = userRegistry()
    List userTypes = classifiedTypes()
    registryMatches().each { row ->
        if (!(row instanceof Map)) return
        String t = "${(row as Map).type}"
        if (!userTypes.contains(t)) externals << row
    }
    userRows.each { externals << it }

    if (externals) {
        Map typeToApps = [:]
        appInfo.each { String appId, info ->
            if (!(info instanceof Map)) return
            String t = "${(info as Map).type}"
            if (!nodes["a${appId}"]) return
            if (!typeToApps.containsKey(t)) typeToApps[t] = []
            (typeToApps[t] as List) << "a${appId}"
        }

        externals.each { ext ->
            if (!(ext instanceof Map)) return
            Map e = ext as Map
            String name = "${e.name}"
            String extType = "${e.type}"
            if (name == EXTERNAL_NONE) return
            List appNodeIds = (typeToApps[extType] ?: []) as List
            if (!appNodeIds) return

            // Hex hash of the ORIGINAL name appended, not just its stripped
            // form - "OpenWeatherMap" and "Open Weather Map" reduce to the
            // identical stripped string and would otherwise collapse onto one
            // node, silently merging two different systems' dependencies. The
            // stripped prefix stays for a readable id in the raw page source;
            // the hash is what actually guarantees no collision.
            String extNodeId = "x${name.toLowerCase().replaceAll('[^a-z0-9]', '')}${Integer.toHexString(name.hashCode())}"
            if (!nodes[extNodeId]) {
                String kindLabel = (EXTERNAL_KINDS["${e.kind}"] ?: 'External system') as String
                nodes[extNodeId] = nodeEntry(extNodeId, name, 'external', kindLabel)
                nodes[extNodeId].kindKey = "${e.kind}"
            }

            appNodeIds.each { String appNodeId ->
                String key = "${appNodeId}|${extNodeId}|depends"
                if (seen.contains(key)) return
                seen << key
                edges << [from: appNodeId, to: extNodeId, kind: 'depends', crit: "${e.crit}"]
            }
        }
    }

    // Endpoints a rule calls directly, read from its own settings rather than
    // declared. These belong to ONE rule, not to its type, which is why they
    // cannot come through the registry: every rule on a hub shares the type
    // Rule-5.1, so a registry entry would attach the endpoint to all of them.
    appInfo.each { String appId, info ->
        if (!(info instanceof Map)) return
        List eps = ((info as Map).endpoints ?: []) as List
        if (!eps) return
        String fromId = "a${appId}"
        if (!nodes[fromId]) return

        eps.each { ep ->
            if (!(ep instanceof Map)) return
            Map e = ep as Map
            String host = "${e.host}"
            if (!host || host == 'null') return
            boolean loop = (e.loopback == true)

            // Same collision-resistant shape as the declared-external-systems
            // id above, and for the same reason: two different hosts (an IP
            // with punctuation stripped differently, say) could otherwise
            // reduce to the same stripped string.
            String nodeId = "x${host.toLowerCase().replaceAll('[^a-z0-9]', '')}${Integer.toHexString(host.hashCode())}"
            if (!nodes[nodeId]) {
                // A rule POSTing to the hub itself is worth showing, since one
                // of them reboots it, but it is not an external system and is
                // labelled for what it actually is.
                nodes[nodeId] = nodeEntry(nodeId, loop ? 'This hub' : host, 'external',
                                          loop ? 'the hub itself' : 'endpoint a rule calls')
                nodes[nodeId].kindKey = loop ? 'infra' : 'internet'
                nodes[nodeId].detected = true
            }

            String key = "${fromId}|${nodeId}|depends"
            if (seen.contains(key)) return
            seen << key
            edges << [from: fromId, to: nodeId, kind: 'depends', crit: 'RUNTIME', detected: true]
        }
    }

    return [nodes: nodes.values().toList(), edges: edges, flows: flows]
}

// ===================================================================================================================
// External systems
//
// What an app depends on OUTSIDE the hub: a Hue bridge, a vendor cloud, an MQTT
// broker. None of it is discoverable - a Hubitat app's dependency on the LIFX
// cloud is a fact about the integration, not something the hub records - so it
// is declared rather than detected.
//
// Declarations are keyed on the app TYPE, not on the installed app id, so one
// entry covers every instance and survives rules being added and removed. A hub
// with 61 installed apps has only 19 distinct types.
//
// Stored as a flat list rather than nested under each type, because the UI adds
// and removes single rows and a flat list leaves no orphans behind.
// ===================================================================================================================

@Field static final Map EXTERNAL_KINDS = [
    local_bridge : 'Bridge or hub on my network',
    local_device : 'Device on my network',
    internet     : 'Internet service',
    platform     : 'Another platform',
    infra        : 'Network infrastructure',
]

@Field static final Map EXTERNAL_CRITICALITY = [
    RUNTIME       : 'Needed all the time',
    MANAGEMENT    : 'Needed to configure it',
    SETUP_ONLY    : 'Needed only at setup',
    DISCOVERY_ONLY: 'Needed only to find devices',
]

// Marks an app type the user has looked at and decided needs nothing external.
// Distinct from never having been classified, which is the point: the map must
// be able to say "nothing needed" separately from "nobody has said".
@Field static final String EXTERNAL_NONE = '__none__'

// ===================================================================================================================
// Shared registry
//
// A curated list of what known integrations depend on, maintained separately
// and validated against every package published to Hubitat Package Manager.
// Fetched rather than embedded, so a new integration is one edit to a JSON
// file instead of a release of this app.
//
// Only the MATCHES are kept. The registry is ~170KB and this app's state is
// already large; storing it whole would roughly double state for data that is
// 95% irrelevant to any one hub. A hub with 20 app types keeps a handful of
// rows and discards the rest.
// ===================================================================================================================

// The SLIM registry, deliberately not the canonical one. The canonical file
// carries provenance, status and documentation evidence for human review, and
// had reached 165KB - enough to kill the execution that fetched it, silently.
// See fetchRegistry for why that failure was invisible.
//
// The slim file holds only the fields evaluated below. It is generated from the
// canonical registry by build_slim_registry.py, which fails the build if it ever
// grows past 64KB, so this cannot quietly regress.
@Field static final String REGISTRY_URL =
    'https://raw.githubusercontent.com/GordonThelander/HPM_Manifest_Crawl/main/hubitat_automation_map_app_integration_registry_slim.json'

// The registry's own vocabulary, mapped onto the four plain-English kinds the
// classification page offers.
@Field static final Map REGISTRY_CLASS_TO_KIND = [
    LOCAL_BRIDGE      : 'local_bridge',
    LOCAL_DEVICE      : 'local_device',
    LOCAL_SERVICE     : 'infra',
    INFRASTRUCTURE    : 'infra',
    EXTERNAL_PLATFORM : 'platform',
    EXTERNAL_SERVICE  : 'internet',
    UNKNOWN_EXTERNAL  : 'internet',
]

// Fields this app can evaluate. It knows an app's TYPE and nothing else about
// its identity, so a rule on a driver name or a user mapping is not false, it
// is unanswerable - which is a different thing and must be treated as such.
//
// parentAppName is deliberately NOT here despite matching registryRuleMatches'
// signature. Matching runs once per app TYPE across the whole hub, not per
// installed instance, and a parent is inherently a per-instance relationship
// - two instances of the same type can have different parents or none. No
// single value could be threaded in here that would be correct for both.
// Previously listed as evaluable while every rule was actually matched
// against appType regardless of its field, so a parentAppName rule was
// silently evaluated against the wrong datum rather than being marked
// unanswerable. Add it back only alongside a genuine per-instance matcher.
@Field static final List<String> REGISTRY_EVALUABLE_FIELDS = ['appName']

// ===================================================================================================================
// Endpoints a rule calls directly
//
// A rule with an HTTP action names its endpoint in its own settings, under
// httper.<n>. That makes it the one external dependency on the whole map that
// is detected rather than declared, and safely so:
//
//   the endpoint is CONFIGURED, not a string found in source, so there is no
//   iconUrl-versus-real-endpoint problem;
//   an HTTP action unambiguously calls it, so no judgement is needed about
//   whether it is a dependency;
//   it is an action the rule performs, so it is RUNTIME for that rule by
//   definition.
//
// It also fills a gap the shared registry structurally cannot. Registry entries
// key on app TYPE, and every rule on a hub shares the type Rule-5.1, so an
// entry there would attach the same endpoint to all 45 of them. A rule's
// endpoint belongs to that one rule.
// ===================================================================================================================

// The hub calling itself, as in a rule that POSTs to /hub/reboot. Worth showing,
// since a rule that reboots the hub is exactly what a dependency map should
// surface, but it is not an EXTERNAL system and must not be drawn as one.
@Field static final List<String> LOOPBACK_HOSTS = ['localhost', '127.0.0.1', '0.0.0.0', '[::1]', '::1']

// Written without regex literals, like everything else on the page-building
// path, because this file builds its HTML inside a GString.
String hostFromUrl(String url) {
    if (!url) return null
    String s = url.trim()
    int scheme = s.indexOf('://')
    if (scheme >= 0) s = s.substring(scheme + 3)
    int at = s.indexOf('@')
    if (at >= 0) s = s.substring(at + 1)
    int cut = s.length()
    ['/', '?', '#'].each { String c ->
        int i = s.indexOf(c)
        if (i >= 0 && i < cut) cut = i
    }
    s = s.substring(0, cut)
    int colon = s.lastIndexOf(':')
    if (colon > 0 && !s.contains(']')) s = s.substring(0, colon)
    s = s.trim().toLowerCase()
    return s ?: null
}

// Endpoints named by one rule's actions. Returns [[host: ..., url: ..., loopback: bool]].
List extractRuleEndpoints(Map data) {
    Map vals = [:]
    (data.appSettings ?: []).each { s ->
        if (!(s instanceof Map) || s.name == null) return
        String n = "${s.name}"
        String v = "${s.value}"
        vals[n] = v
    }

    List out = []
    List seen = []
    vals.each { String name, String value ->
        if (!name.startsWith('httper.')) return
        String host = hostFromUrl(value)
        if (!host) return
        if (seen.contains(host)) return
        seen << host
        out << [host: host, url: value.trim(), loopback: LOOPBACK_HOSTS.contains(host)]
    }
    return out
}

List userRegistry() {
    return (state.userRegistry ?: []) as List
}

List registryMatches() {
    return (state.registryMatches ?: []) as List
}

// Case-insensitive and whitespace-trimmed, matching the validator that checks
// this registry against live package data. Published names really are
// inconsistent: BOND against Bond, Ecowitt against EcoWitt.
boolean registryRuleMatches(String op, String value, String appType) {
    String n = value?.trim()?.toLowerCase()
    String h = appType?.trim()?.toLowerCase()
    if (!n || !h) return false
    if (op == 'equals') return h == n
    if (op == 'contains') return h.contains(n)
    return false
}

// Three states, not two.
//
// A rule this app cannot evaluate is NOT a failed rule. Treating it as one
// would let an ALL entry match on its remaining rules alone, which is exactly
// what the registry uses matchMode ALL to prevent: "Home Assistant via Maker
// API" is gated behind a user mapping precisely so it does NOT fire on every
// Maker API install. Ignoring that rule would attach Home Assistant to anyone
// running Maker API.
String registryEntryState(Map entry, String appType) {
    boolean anyMatch = false
    boolean anyFail = false
    boolean anyUnknown = false

    (entry.matchRules ?: []).each { rule ->
        if (!(rule instanceof Map)) return
        Map r = rule as Map
        String field = "${r.field}"
        if (!REGISTRY_EVALUABLE_FIELDS.contains(field)) { anyUnknown = true; return }
        if (registryRuleMatches("${r.operator}", "${r.value}", appType)) anyMatch = true
        else anyFail = true
    }

    boolean all = "${entry.matchMode}" == 'ALL'
    if (all) {
        if (anyFail) return 'NO_MATCH'
        if (anyUnknown) return 'NOT_EVALUABLE'
        return anyMatch ? 'MATCH' : 'NO_MATCH'
    }
    if (anyMatch) return 'MATCH'
    if (anyUnknown) return 'NOT_EVALUABLE'
    return 'NO_MATCH'
}

// Every app type the scan found, which is what the classification page offers.
// Types rather than installed apps, and sorted so the page does not reshuffle
// between visits.
List discoveredAppTypes() {
    List types = []
    ((state.appInfo ?: [:]) as Map).each { String appId, info ->
        if (!(info instanceof Map)) return
        String t = "${(info as Map).type}"
        if (!t || t == 'null') return
        // This app and its dev twin are already excluded from the graph.
        // Offering them for classification asks the user to declare what
        // Automation Map depends on, which is nothing and not their problem.
        if (t.startsWith(APP_FAMILY)) return
        if (!types.contains(t)) types << t
    }
    return types.sort()
}

// The declarations for one app type. Returns [] for an unclassified type and
// for one explicitly marked as needing nothing, which the caller separates by
// asking classifiedTypes().
// Every comparison below goes through a String-typed local on purpose. A GString
// never equals a String and never matches one as a map key, because their hash
// codes differ, and it fails silently rather than throwing.
List externalsForType(String appType) {
    List out = []
    userRegistry().each { entry ->
        if (!(entry instanceof Map)) return
        Map e = entry as Map
        String t = "${e.type}"
        String n = "${e.name}"
        if (t == appType && n != EXTERNAL_NONE) out << e
    }
    return out
}

List classifiedTypes() {
    List out = []
    userRegistry().each { entry ->
        if (!(entry instanceof Map)) return
        String t = "${(entry as Map).type}"
        if (t && !out.contains(t)) out << t
    }
    return out
}

String getLocalURL(String fileName) {
    String fullURL = "${fullLocalApiServerUrl}/${fileName}?access_token=${state.accessToken}"
    return (fullURL =~ URL_PATTERN).findAll()[0][1]
}

// The Remote Admin fix. getLocalURL() above returns a path with no scheme or
// host, which only resolves correctly when the browser is already on the hub's
// own origin. A page loaded through remoteaccess.aws.hubitat.com has a
// different origin, so that relative path 404s against the remote portal
// instead of ever reaching the hub - this is JimB's scan-start failure.
// These two give the browser both an absolute fallback and the origin to
// decide with; see amPickURL() in the two templates that call them.
String getLocalOrigin() {
    return (fullLocalApiServerUrl =~ ORIGIN_PATTERN).findAll()[0][1]
}

String getCloudURL(String fileName) {
    return "${fullApiServerUrl}/${fileName}?access_token=${state.accessToken}"
}

// ===================================================================================================================
// Map page
// ===================================================================================================================

mappings {
    path('/automation-map.html') { action: [ GET: 'renderMapMapping' ] }
    path('/scan') { action: [ GET: 'scanMapping' ] }
    path('/scan-status') { action: [ GET: 'scanStatusMapping' ] }
    path('/externals') { action: [ GET: 'externalsGetMapping', POST: 'externalsSaveMapping' ] }
    path('/icon-overrides') { action: [ GET: 'iconOverridesGetMapping', POST: 'iconOverridesSaveMapping' ] }
}

// The map page was read-only until this. It now accepts one write: the user's
// own declarations about what their apps depend on. Nothing here commands a
// device or alters another app, and the access token that already guards the
// map guards this too.
Map externalsGetMapping() {
    return render(status: 200, contentType: 'application/json', data: externalsJson())
}

Map externalsSaveMapping() {
    List incoming = []
    try {
        def body = request?.JSON
        List rows = (body instanceof Map) ? ((body as Map).entries as List) : (body as List)
        (rows ?: []).each { row ->
            if (!(row instanceof Map)) return
            Map r = row as Map
            String type = "${r.type}".trim()
            String name = "${r.name}".trim()
            if (!type || type == 'null' || !name || name == 'null') return
            String kind = "${r.kind}"
            String crit = "${r.crit}"
            Map entry = [type: type, name: name]
            // A "nothing needed" marker carries no kind or criticality; storing
            // them would imply a dependency that does not exist.
            if (name != EXTERNAL_NONE) {
                entry.kind = EXTERNAL_KINDS.containsKey(kind) ? kind : 'internet'
                entry.crit = EXTERNAL_CRITICALITY.containsKey(crit) ? crit : 'RUNTIME'
            }
            incoming << entry
        }
    } catch (Exception ex) {
        log.warn "${app.label}: could not read externals payload: ${ex.message}"
        return render(status: 400, contentType: 'application/json',
                      data: '{"ok":false,"error":"could not read payload"}')
    }

    state.userRegistry = incoming
    // The graph is rebuilt from stored scan data rather than rescanning: the
    // declarations changed, the hub did not.
    state.graph = buildGraph()
    state.graphVersion = GRAPH_SCHEMA
    log.info "${app.label}: saved ${incoming.size()} external system declaration(s)"
    return render(status: 200, contentType: 'application/json', data: externalsJson())
}

String externalsJson() {
    List types = discoveredAppTypes()
    List classified = classifiedTypes()
    List reg = registryMatches()
    List regTypes = []
    reg.each { r -> String t = "${(r as Map).type}"; if (t && !regTypes.contains(t)) regTypes << t }

    Map out = [
        ok: true,
        kinds: EXTERNAL_KINDS,
        criticality: EXTERNAL_CRITICALITY,
        noneMarker: EXTERNAL_NONE,
        appTypes: types,
        // Unclassified means nobody has said, by user OR registry. An app type
        // the registry covers is not a gap the user needs to fill.
        unclassified: types.findAll { !classified.contains(it) && !regTypes.contains(it) },
        entries: userRegistry(),
        registry: reg,
        registryMeta: (state.registryMeta ?: [:]),
    ]
    return groovy.json.JsonOutput.toJson(out)
}

// Device icons. Same exception as /externals above: read-only everywhere
// else, one write accepted here, guarded by the same access token as the map
// itself, and touching nothing but this app's own state - no device, no
// other app.
Map iconOverridesGetMapping() {
    return render(status: 200, contentType: 'application/json', data: iconOverridesJson())
}

Map iconOverridesSaveMapping() {
    Map incoming = [:]
    Map incomingNotes = [:]
    try {
        def body = request?.JSON
        Map payload = (body instanceof Map) ? (body as Map) : [:]
        Map overrides = payload.overrides as Map
        (overrides ?: [:]).each { k, v ->
            String devId = "${k}"
            String iconKey = "${v}"
            // 'auto' means the user cleared their override, not that they chose
            // an icon key called "auto" - dropping it here is what lets
            // autoDetectIconKey take over again on the next graph build.
            if (ICON_KEYS.contains(iconKey)) incoming[devId] = iconKey
        }
        Map notes = payload.notes as Map
        (notes ?: [:]).each { k, v ->
            String devId = "${k}"
            // Capped rather than rejected outright - a pasted paragraph is
            // still worth keeping the first bit of, and this is a tooltip
            // annotation, not a document.
            String note = "${v}".trim()
            if (note.length() > 200) note = note.substring(0, 200)
            if (note) incomingNotes[devId] = note
        }
    } catch (Exception ex) {
        log.warn "${app.label}: could not read icon override payload: ${ex.message}"
        return render(status: 400, contentType: 'application/json',
                      data: '{"ok":false,"error":"could not read payload"}')
    }

    state.deviceIconOverrides = incoming
    state.deviceIconNotes = incomingNotes
    // Same reasoning as externalsSaveMapping: rebuilt from stored scan data,
    // not a rescan - the overrides changed, the hub did not.
    state.graph = buildGraph()
    state.graphVersion = GRAPH_SCHEMA
    log.info "${app.label}: saved ${incoming.size()} device icon override(s), ${incomingNotes.size()} note(s)"
    return render(status: 200, contentType: 'application/json', data: iconOverridesJson())
}

String iconOverridesJson() {
    Map labels = (state.deviceLabels ?: [:]) as Map
    Map rooms = (state.deviceRooms ?: [:]) as Map
    Map caps = (state.deviceCapabilities ?: [:]) as Map
    Map overrides = (state.deviceIconOverrides ?: [:]) as Map
    Map notes = (state.deviceIconNotes ?: [:]) as Map

    List devices = labels.collect { String devId, label ->
        [
            id: devId,
            name: label,
            room: rooms[devId] ?: '',
            detected: autoDetectIconKeyForDevice(label as String, caps[devId] as List),
            override: overrides[devId] ?: 'auto',
            note: notes[devId] ?: '',
            capabilities: caps[devId] ?: [],
        ]
    }
    devices.sort { a, b -> (a.name as String).compareToIgnoreCase(b.name as String) }

    Map labelsByKey = [:]
    ICON_RULES.each { rule -> Map r = rule as Map; labelsByKey[r.key] = r.label }
    labelsByKey.unknown = 'Unknown'

    Map out = [
        ok: true,
        iconKeys: ICON_KEYS,
        iconLabels: labelsByKey,
        devices: devices,
    ]
    return groovy.json.JsonOutput.toJson(out)
}

// Starting a scan from a URL rather than only from the page button, so a stalled
// scan can be restarted (and diagnosed) without sitting in the app UI.
//
// startScan() ran unguarded here. Hubitat's own OAuth mapping layer renders an
// UNCAUGHT exception as an HTML error page - sometimes with a 200 status, which
// is what let this slip past the client-side r.ok check added for the same bug:
// the browser correctly parsed the response as "successful", then choked trying
// to read HTML as JSON, and the resulting SyntaxError was indistinguishable from
// the original report. Whatever throws inside startScan() on a given hub, this
// mapping must always answer with real JSON so the client has something to show
// instead of a raw parser error.
Map scanMapping() {
    // Unconditional, and FIRST. This is what makes the log test binary: if this
    // line does not appear when the user presses Scan, Hubitat never dispatched
    // the request into this app at all, and no handler here can be at fault.
    // Logging only from the catch below could not prove that - a throw in the
    // success path (scanStatusJson/render) would also leave the log silent while
    // Hubitat returned its HTML error page.
    log.warn "${app.label}: /scan endpoint reached"
    try {
        startScan()
        // Inside the try, not after it. Left outside, an exception in
        // scanStatusJson() or render() escaped this handler entirely and
        // produced the same unexplained HTML page the handler exists to stop.
        return render(status: 200, contentType: 'application/json', data: scanStatusJson())
    } catch (Exception ex) {
        log.warn "${app.label}: scanMapping failed to start a scan: ${ex.message}"
        // Serialised to a String, not passed as a Map. Every other render() in
        // this file passes a String, and this was the only one that did not -
        // if render() does not coerce a Map, this handler throws inside its own
        // catch, Hubitat renders its HTML error page, and the caller sees the
        // exact parser error this handler exists to prevent. Which would make
        // the 1.8.7 fix invisible rather than wrong. Caught by external review.
        return render(status: 200, contentType: 'application/json',
            data: JsonOutput.toJson([ok: false, error: "${ex.class.simpleName}: ${ex.message}"]))
    }
}

Map scanStatusMapping() {
    // Same self-heal main() already runs on every settings-page load. That path
    // only fires while the settings page specifically is open, in the
    // foreground, with its refreshInterval auto-refresh not throttled by a
    // backgrounded tab - confirmed live to leave a scan looking stuck for
    // several minutes when a user is instead watching the map page, or has
    // switched away, even though the scan itself finished reading every app.
    // This endpoint is already the documented "scan appears stuck" check
    // (README Troubleshooting), so running the same recovery here means any
    // status poll can un-stick a scan, not only a settings-page reload.
    clearAbandonedScan()
    return render(status: 200, contentType: 'application/json', data: scanStatusJson())
}

String scanStatusJson() {
    return JsonOutput.toJson([
        running: state.scanRunning as boolean,
        phase: state.scanPhase,
        done: state.scanDone,
        total: state.scanTotal,
        queued: (state.scanQueue ?: []).size(),
        apps: (state.appInfo ?: [:]).size(),
        devices: (state.deviceLabels ?: [:]).size(),
        error: state.scanError,
        compatOk: state.compatOk,
        compatDetail: state.compatDetail,
        appsDecoded: state.appsDecoded,
        appsUnreadable: state.appsUnreadable,
        devicesUnreadable: ((state.deviceIdsUnreadable ?: []) as List).size(),
        rulesDecoded: state.rulesDecoded,
        rulesSkipped: state.rulesSkipped,
        otherEngines: state.otherEngines,
        heartbeat: state.scanHeartbeat,
        graphVersion: state.graphVersion,
    ])
}

Map renderMapMapping() {
    if (graphIsStale()) {
        return render(
            status: 200,
            contentType: 'text/html',
            data: """<!doctype html><html><head><meta charset="utf-8"><title>Automation Map</title></head>
<body style="background:#062733; color:#eee; font-family:sans-serif; padding:2em; line-height:1.5">
<h2>This map is out of date</h2>
<p>It was saved in a format this release no longer reads.
Relationship types have changed since then, so the graph would render without role colours.</p>
<p>Open the Automation Map app and run <b>Scan relationships now</b>, then reload this page.</p>
</body></html>"""
        )
    }
    return render(status: 200, contentType: 'text/html', data: buildMapHtml())
}

// A device or app name is free text the hub owner controls, and it ends up
// inside a JSON blob embedded straight into a <script> block. Pattern-matching
// closing-tag spellings ('</script>', case-insensitively) is fragile: HTML
// also tolerates whitespace before the '>' ('</script >'), which slips past a
// pattern for the exact string, and there is no guarantee that is the last
// variant a browser accepts. Escaping every '<' as \u003c sidesteps
// enumerating tag spellings entirely - '<' never appears outside a JSON
// string value in the first place, so this changes nothing about how the
// JSON parses, and with no literal '<' left anywhere in the output there is
// nothing left for the browser's tag scanner to match, spelled any way at all.
String jsonForScriptEmbed(Object obj) {
    return JsonOutput.toJson(obj).replace('<', '\\u003c')
}

String buildMapHtml() {
    Map graph = (state.graph ?: [nodes: [], edges: []]) as Map
    int deviceCount = (graph.nodes ?: []).count { it.group == 'device' }
    int appCount = (graph.nodes ?: []).count { it.group == 'app' }
    String jsonStr = jsonForScriptEmbed(graph)
    // For the AI friendly export feature - scan provenance the client-side GRAPH
    // blob above does not carry on its own. Built the same safe way GRAPH
    // is (JsonOutput, not manual string splicing) so an exception message
    // in scanError can never break out of the embedding script tag.
    Map scanMeta = [
        exportSchemaVersion: 3,
        graphSchemaVersion: GRAPH_SCHEMA,
        scanHeartbeatMs: state.scanHeartbeat,
        scanError: state.scanError,
        appsUnreadable: state.appsUnreadable ?: 0,
        devicesUnreadable: ((state.deviceIdsUnreadable ?: []) as List).size(),
    ]
    String scanMetaJsonStr = jsonForScriptEmbed(scanMeta)
    // Positioned in the empty gap between the status box and the controls
    // panel, where Gordon pointed at it - not overlapping either.
    String santaHtml = showSanta() ?
        '<img id="santa" src="data:image/png;base64,' + SANTA_PNG_B64 + '" alt="Merry Christmas" ' +
        'style="position:absolute; top:6px; right:330px; width:150px; z-index:9; pointer-events:none;">' +
        // Source PNG is exactly square, so at width:150px its rendered
        // height is also 150px - the caption sits right at that bottom
        // edge (top 6 + height 150 + a small gap) rather than a value
        // guessed from how the image happens to look.
        '<div id="santaCaption" style="position:absolute; top:162px; right:330px; width:150px; ' +
        'text-align:center; color:#c0392b; font-weight:bold; font-size:0.85em; z-index:9; pointer-events:none;">' +
        'Wishing you a Blessed Christmas</div>' : ''

    return """\
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<!-- Without this a phone renders at a ~980px virtual width, so the small-screen
     media query never fires and the page silently shrinks to an unusable size
     instead of showing the desktop-only notice. -->
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Automation Map</title>
<!-- Pinned to exact versions, not 'latest'/'@10' - an upstream release could
     otherwise change behaviour under this app with no corresponding commit
     here to explain why the map suddenly looks or acts differently. Bump
     deliberately, not by whatever the CDN resolves to on a given day. -->
<script src="https://unpkg.com/vis-network@10.1.1/standalone/umd/vis-network.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/mermaid@10.9.8/dist/mermaid.min.js"></script>
<style>
  /* Device icons (light/door/water/etc, see styledNode). One glyph set at one
     weight, loaded directly as its own font-family rather than pulling in
     FontAwesome's full CSS - vis-network draws icon nodes on a canvas with a
     plain "<size>px <face>" string and no way to ask for a font-weight, and
     FontAwesome 6 Free's Solid glyphs (nearly this whole set) live only at
     weight 900, so requesting the family at the browser's default normal
     weight through FontAwesome's own CSS would silently render blank boxes.
     Re-declaring the same Solid file under its own family name at normal
     weight sidesteps the mismatch entirely - a known pattern for exactly
     this vis-network + FontAwesome combination. */
  @font-face {
    font-family: 'AMIcons';
    src: url('https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.2/webfonts/fa-solid-900.woff2') format('woff2');
    font-weight: normal;
    font-style: normal;
  }
  html, body { margin:0; padding:0; height:100%; background:#062733; color:#eee; font-family:sans-serif; }
  #status { position:absolute; top:10px; left:10px; z-index:10; background:rgba(0,0,0,0.55); padding:10px 14px; border-radius:6px; font-size:0.85em; }
  #legend { position:absolute; top:55px; left:10px; z-index:10; background:rgba(0,0,0,0.55); padding:10px 14px; border-radius:6px; font-size:0.8em; max-width:340px; }
  #controls { position:absolute; top:10px; right:10px; z-index:10; background:rgba(0,0,0,0.55); padding:10px 14px; border-radius:6px; font-size:0.8em; display:flex; flex-direction:column; gap:6px; width:230px; }
  #controls label { display:block; margin-bottom:2px; }
  #controls select { width:100%; box-sizing:border-box; }
  #controls input[type=search] { width:100%; box-sizing:border-box; margin-bottom:3px; padding:3px 5px; font-size:1em; }
  #controls button { margin-top:2px; cursor:pointer; }
  #network { width:100%; height:100vh; }
  /* First shipped as a bare 1em glyph with no background - reported as "had to
     go hunting for it". A visible pill with its own border and a hover state
     reads as a button; a lone triangle in a wall of text does not. */
  #legend-head { display:flex; align-items:center; gap:8px; cursor:pointer; user-select:none; font-weight:bold; padding:2px; border-radius:4px; }
  #legend-head:hover { background:rgba(255,255,255,0.10); }
  #legend-toggle { background:rgba(255,255,255,0.12); border:1px solid rgba(255,255,255,0.35); color:#fff; font-size:1.15em; line-height:1; width:22px; height:22px; border-radius:5px; padding:0; cursor:pointer; }
  #legend.collapsed #legend-body { display:none; }
  #legend.collapsed { padding:6px 10px; }
  .legend-row { display:flex; align-items:center; margin:4px 0; }
  /* Shape is per row now. The old single .swatch rule forced border-radius 50%
     on every swatch, so the legend drew a circle for an app that the map draws
     as a square, and rotating that circle 45 degrees for an external system was
     a no-op: a rotated circle is still a circle. Reported on the thread. */
  .swatch { width:12px; height:12px; margin-right:8px; display:inline-block; flex:none; }
  .sw-dot { border-radius:50%; }
  .sw-square { border-radius:2px; }
  .sw-diamond { width:10px; height:10px; border-radius:1px; transform:rotate(45deg); margin:1px 9px 1px 1px; }
  .sw-triangle { width:0; height:0; border-left:6px solid transparent; border-right:6px solid transparent; border-bottom:11px solid currentColor; background:none !important; margin-right:8px; }
  .sw-outline { background:#2b2b2b; border:2px solid #e8a33d; box-sizing:border-box; }
  /* Deliberately not a variant of sw-outline. A deleted target and an unscanned
     rule are different findings, and sharing a style is what made them
     indistinguishable on the map in the first place. */
  .sw-missing { background:#2b2b2b; border:2px solid #d9534f; box-sizing:border-box; }
  .sw-inert { background:#3d3222; border:2px dashed #e8a33d; box-sizing:border-box; }
  .sw-unreadable { background:#4a1f1f; border:2px solid #d9534f; box-sizing:border-box; }
  /* Dash patterns drawn to match the canvas. border-top-style has no dash-dot,
     which is why pause/resume used to look identical to stops in the legend.
     These variants take their colour from the row's inline color, not from
     border-color, so a row using one must set color rather than border-color. */
  .ln-pat { height:2px; border-top:none; }
  .ln-dashdot { background:repeating-linear-gradient(to right, currentColor 0 12px, transparent 12px 15px, currentColor 15px 17px, transparent 17px 22px); }
  .ln-thick { height:3px; }
  .line { width:22px; height:0; border-top:2px solid #fff; margin-right:8px; display:inline-block; flex:none; }
  .note { opacity:0.75; font-size:0.9em; margin-top:6px; line-height:1.35; }
  /* Was easy to miss entirely - same dark background as the page itself,
     no border, tucked in a corner. A first-time visitor's eye has nowhere
     else to land on page load but the graph, so this needs to actually
     compete for attention rather than blend in. The accent border reuses
     the app-node amber already established elsewhere on the page rather
     than introducing a new colour. */
  #hint { position:absolute; bottom:16px; right:16px; z-index:15; background:#0a2530; padding:14px 18px; border-radius:6px;
          max-width:320px; font-size:0.85em; line-height:1.5; border:2px solid #e8a33d;
          box-shadow:0 4px 28px rgba(0,0,0,0.6), 0 0 0 4px rgba(232,163,61,0.12); }
  #hint b:first-child { display:block; font-size:1.25em; color:#e8a33d; margin-bottom:6px; }
  #hint button { cursor:pointer; padding:5px 14px; font-weight:600; }
  /* Deliberately not made to work on a phone. A few hundred nodes, a filter
     panel and a flowchart need room and a pointer; a shrunken version would be
     frustrating rather than useful, so small screens get told plainly. */
  #smallscreen { display:none; }
  @media (max-width: 820px) {
    #controls, #legend, #hint, #network, #flow { display:none !important; }
    #smallscreen { display:block; padding:2em 1.5em; line-height:1.5; }
  }
  #flow { position:absolute; top:100px; left:10px; z-index:20; background:rgba(4,20,27,0.96); padding:12px 16px; border-radius:6px;
          max-width:min(62vw, 900px); max-height:90vh; overflow:auto; display:none; box-shadow:0 4px 24px rgba(0,0,0,0.5); }
  #flow h3 { margin:0 0 4px 0; font-size:0.95em; }
  #flow h4 { margin:14px 0 4px 0; font-size:0.9em; color:#cfe3ea; }
  #flow ul { margin:4px 0 0 0; padding-left:18px; }
  #flow li { margin:5px 0; font-size:0.82em; line-height:1.35; }
  #flow p { margin:4px 0; }
  #flow .sub { opacity:0.7; font-size:0.78em; margin-bottom:10px; }
  #flow a { color:#7fb6d6; text-decoration:none; }
  #flow a:hover { text-decoration:underline; }
  /* Above the title, where a back affordance is looked for, and clear of the
     close button in the same corner. */
  #flowBack { font-size:0.8em; margin:0 0 6px 0; padding-right:20px; display:flex; justify-content:space-between; align-items:baseline; gap:10px; }
  /* A link, not a button, so it reads as part of the same breadcrumb line
     rather than a separate control competing for attention. */
  #flowExit { color:#7fb8d4; cursor:pointer; text-decoration:none; white-space:nowrap; }
  #flowExit:hover { text-decoration:underline; }
  #flow ul { margin:4px 0 10px 0; padding-left:18px; }
  #flow li { margin:2px 0; font-size:0.85em; }
  #flowClose { position:absolute; top:8px; right:10px; cursor:pointer; background:none; border:none; color:#bbb; font-size:1.1em; }
  /* Fully opaque, not near-opaque: at 0.97 the legend behind it still showed
     through as ghost text across the middle of the table. */
  #ext { position:absolute; top:100px; left:10px; z-index:21; background:#041b23; padding:14px 18px; border-radius:6px;
         max-width:min(74vw, 1040px); max-height:90vh; overflow:auto; display:none; box-shadow:0 4px 24px rgba(0,0,0,0.55); }
  #ext h3 { margin:0 0 4px 0; font-size:0.95em; }
  #ext .sub { opacity:0.72; font-size:0.78em; margin:0 0 12px 0; line-height:1.4; }
  #ext table { border-collapse:collapse; width:100%; font-size:0.8em; }
  #ext th { text-align:left; padding:5px 8px; border-bottom:1px solid #2a4a57; color:#cfe3ea; font-weight:600; white-space:nowrap; }
  #ext td { padding:4px 8px; border-bottom:1px solid #16323c; vertical-align:top; }
  #ext tr.unclassified td { background:rgba(217,83,79,0.09); }
  #ext .tag { display:inline-block; padding:1px 6px; border-radius:3px; font-size:0.88em; }
  #ext .tag-none { background:#2c3e44; color:#9fb4bc; }
  #ext .tag-unset { background:#5a2b29; color:#f0b8b5; }
  #ext .tag-user { background:#2b4a2c; color:#b6e0b8; }
  #ext .tag-reg { background:#243c52; color:#a8c8e4; }
  #ext tr.fromreg td { opacity:0.86; }
  #ext input[type=text], #ext select { background:#0d2630; color:#e8f2f6; border:1px solid #2a4a57; border-radius:3px; padding:3px 5px; font-size:1em; font-family:inherit; }
  #ext input[type=text] { width:150px; }
  #ext button { margin:0 4px 0 0; }
  #ext .rowbtn { background:none; border:1px solid #2a4a57; color:#9fb4bc; border-radius:3px; cursor:pointer; padding:1px 6px; font-size:0.95em; }
  #ext .bar { margin-top:14px; padding-top:12px; border-top:1px solid #2a4a57; display:flex; gap:8px; flex-wrap:wrap; align-items:center; }
  #ext .msg { font-size:0.8em; margin-left:6px; }
  #extClose { position:absolute; top:8px; right:10px; cursor:pointer; background:none; border:none; color:#bbb; font-size:1.1em; }
  /* Its own panel rather than reusing #ext or #flow's markup - a table of
     links and a small query builder is a different shape of content from
     either (a settings form, a rule flowchart), and this file's convention
     throughout is one panel's CSS per panel rather than a shared class. */
  #pivot { position:absolute; top:100px; left:10px; z-index:21; background:#041b23; padding:14px 18px; border-radius:6px;
           max-width:min(80vw, 1100px); max-height:90vh; overflow:auto; display:none; box-shadow:0 4px 24px rgba(0,0,0,0.55); }
  #pivot h3 { margin:0 0 4px 0; font-size:0.95em; }
  #pivot .sub { opacity:0.72; font-size:0.78em; margin:0 0 12px 0; line-height:1.4; }
  #pivot a { color:#7fb6d6; text-decoration:none; }
  #pivot a:hover { text-decoration:underline; }
  #pivot table { border-collapse:collapse; width:100%; font-size:0.8em; }
  #pivot th { text-align:left; padding:5px 8px; border-bottom:1px solid #2a4a57; color:#cfe3ea; font-weight:600; white-space:nowrap; }
  #pivot td { padding:4px 8px; border-bottom:1px solid #16323c; vertical-align:top; }
  #pivot select { background:#0d2630; color:#e8f2f6; border:1px solid #2a4a57; border-radius:3px; padding:3px 5px; font-size:0.85em; font-family:inherit; }
  #pivot label { font-size:0.8em; display:flex; align-items:center; gap:4px; }
  #pivot .rowbtn { background:none; border:1px solid #2a4a57; color:#9fb4bc; border-radius:3px; cursor:pointer; padding:3px 8px; font-size:0.85em; margin:0 4px 4px 0; }
  #pivot .rowbtn:hover { border-color:#4a7a94; color:#cfe3ea; }
  #pivotClose { position:absolute; top:8px; right:10px; cursor:pointer; background:none; border:none; color:#bbb; font-size:1.1em; }
  /* Its own panel rather than reusing #ext's markup, same "one panel's CSS
     per panel" convention, even though the table shape is similar - this one
     needs a search box and can run to ~200 rows, #ext's does not. */
  #icons { position:absolute; top:100px; left:10px; z-index:21; background:#041b23; padding:14px 18px; border-radius:6px;
           max-width:min(74vw, 900px); max-height:90vh; overflow:auto; display:none; box-shadow:0 4px 24px rgba(0,0,0,0.55); }
  #icons h3 { margin:0 0 4px 0; font-size:0.95em; }
  #icons .sub { opacity:0.72; font-size:0.78em; margin:0 0 12px 0; line-height:1.4; }
  #icons input[type=search] { background:#0d2630; color:#e8f2f6; border:1px solid #2a4a57; border-radius:3px; padding:4px 7px; font-size:0.9em; font-family:inherit; width:240px; margin-bottom:10px; }
  #icons table { border-collapse:collapse; width:100%; font-size:0.8em; }
  #icons th { text-align:left; padding:5px 8px; border-bottom:1px solid #2a4a57; color:#cfe3ea; font-weight:600; white-space:nowrap; }
  #icons td { padding:4px 8px; border-bottom:1px solid #16323c; vertical-align:top; }
  #icons tr.overridden td { background:rgba(79,179,169,0.09); }
  #icons select { background:#0d2630; color:#e8f2f6; border:1px solid #2a4a57; border-radius:3px; padding:3px 5px; font-size:1em; font-family:inherit; }
  #icons .bar { margin-top:14px; padding-top:12px; border-top:1px solid #2a4a57; display:flex; gap:8px; flex-wrap:wrap; align-items:center; }
  #icons .msg { font-size:0.8em; margin-left:6px; }
  #iconsClose { position:absolute; top:8px; right:10px; cursor:pointer; background:none; border:none; color:#bbb; font-size:1.1em; }
</style>
</head>
<body>
<div id="status">Devices: ${deviceCount} &nbsp; Apps: ${appCount}</div>
${santaHtml}
<div id="legend">
  <div id="legend-head"><button id="legend-toggle" type="button" aria-expanded="true" aria-controls="legend-body">&#9662;</button><span>Legend</span></div>
  <div id="legend-body">
  <div class="legend-row"><span class="swatch sw-square" style="background:#e8a33d"></span>App</div>
  <div class="legend-row"><span class="swatch sw-square sw-outline"></span>Rule reached only as another rule's target</div>
  <div class="legend-row"><span class="swatch sw-square sw-missing"></span>Rule referenced but deleted - the action silently does nothing</div>
  <div class="legend-row"><span class="swatch sw-square" style="background:#6d6a5f"></span>App paused or disabled</div>
  <div class="legend-row"><span class="swatch sw-square sw-inert"></span>App with no device or rule relationship - its label says why</div>
  <div class="legend-row"><span class="swatch sw-square sw-unreadable"></span>Could not be read during the scan - rescan to retry</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#5f7d8c"></span>Device - icon by type (light, door, sensor...), grey with no app focused. Wrong? Device icons panel.</div>
  <div class="legend-row"><span class="swatch sw-diamond" style="background:#cfd8dc"></span>External system - declared, not detected</div>
  <div class="legend-row"><span class="swatch sw-triangle" style="color:#4fb3a9"></span>Hub Variable - shared state a rule writes or reads</div>
  <div class="note" style="margin:2px 0 6px 0">Focus an app and each device instead takes the colour of its role below, shown as both a line and the dot the device itself becomes.</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#9b59b6"></span><span class="line" style="border-color:#9b59b6"></span>Trigger - app listens to this device</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#16a085"></span><span class="line" style="border-color:#16a085"></span>Constraint - condition / required expression</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#3d7ea6"></span><span class="line" style="border-color:#3d7ea6"></span>Monitor - app reads this device's state</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#7fae42"></span><span class="line" style="border-color:#7fae42"></span>Action - app can command this device</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#c98b6b"></span><span class="line" style="border-color:#c98b6b; border-top-style:dotted"></span>Exposed - published to an external system</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#8090a0"></span><span class="line" style="border-color:#8090a0; border-top-style:dashed"></span>Owns - app created this device</div>
  <div class="legend-row"><span class="line" style="border-color:#4fb3a9"></span>Write - rule sets a Hub Variable's value</div>
  <div class="legend-row"><span class="line" style="border-color:#8fd6cc"></span>Read - rule uses a Hub Variable in a condition or action</div>
  <div class="legend-row"><span class="line" style="border-color:#d9534f"></span>Runs - rule runs another rule's actions</div>
  <div class="legend-row"><span class="line" style="border-color:#d9534f; border-top-style:dashed"></span>Cancel timed actions - rule cancels another rule's pending Wait/Delay</div>
  <div class="legend-row"><span class="line" style="border-color:#d9534f; border-top-style:dotted"></span>Private Boolean - rule sets another rule's</div>
  <div class="legend-row"><span class="line ln-pat ln-dashdot" style="color:#d9534f"></span>Pause / resume - rule pauses or resumes another rule (focus the rule to see which)</div>
  <div class="legend-row"><span class="line ln-pat ln-thick" style="border-color:#cfd8dc; background:repeating-linear-gradient(to right,#cfd8dc 0 6px,transparent 6px 9px)"></span>Depends on - needed all the time</div>
  <div class="legend-row"><span class="line ln-pat" style="background:repeating-linear-gradient(to right,#cfd8dc 0 2px,transparent 2px 7px)"></span>Depends on - needed only to set up or manage</div>
  <div class="note">Arrows follow the flow: triggers and constraints point into the app, actions and owned devices point out of it.</div>
  <div class="note">Focus one app to colour its devices by role. A device holding two roles in one app gets two edges, and is coloured by the more significant one.</div>
  </div>
</div>
<script>
  // Collapsible legend, asked for on the community thread: on a busy map it
  // covers the bottom-left corner and there was no way to get it out of the
  // way. The choice is remembered, because someone who folds it away once
  // almost certainly wants it folded away next time.
  //
  // The handler sits on the whole header rather than the arrow, so the target
  // is the full width rather than a 12px glyph. The button is inside the
  // header, so it must NOT get its own listener or a click would toggle twice.
  (function () {
    var lg = document.getElementById('legend');
    var tg = document.getElementById('legend-toggle');
    var hd = document.getElementById('legend-head');
    function apply(collapsed) {
      if (collapsed) { lg.classList.add('collapsed'); } else { lg.classList.remove('collapsed'); }
      tg.innerHTML = collapsed ? '&#9656;' : '&#9662;';
      tg.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
      try { localStorage.setItem('amLegendCollapsed', collapsed ? '1' : '0'); } catch (e) { }
    }
    var saved = '0';
    try { saved = localStorage.getItem('amLegendCollapsed') || '0'; } catch (e) { }
    apply(saved === '1');
    // syncLegendVisibility is declared later in the file (with bringToFront)
    // but this only runs on a later click, by which point it exists - same
    // forward-reference as everywhere else in this file. Needed here because
    // expanding the legend while a panel is open makes it tall enough to run
    // behind that panel's content again, the same overlap collapsing this
    // panel-open exemption was for in the first place - and collapsing it
    // back while a panel is still open should bring it back into view.
    hd.addEventListener('click', function () {
      apply(!lg.classList.contains('collapsed'));
      if (typeof syncLegendVisibility === 'function') syncLegendVisibility();
    });
  })();
</script>
<div id="smallscreen">
  <h2>Best viewed on a desktop</h2>
  <p>Automation Map shows every app and device on your hub at once, with filter controls and rule flowcharts alongside. That needs a large screen and a mouse, so it is not made to work on a phone.</p>
  <p>Open this same link on a computer.</p>
</div>
<div id="controls">
  <label>Focus app<input id="appSearch" type="search" placeholder="search apps..." autocomplete="off"><select id="appFilter" size="1"><option value="__all__">All apps</option></select></label>
  <label>Focus device<input id="deviceSearch" type="search" placeholder="search devices..." autocomplete="off"><select id="deviceFilter" size="1"><option value="__all__">All devices</option></select></label>
  <label>Show<select id="kindFilter">
    <option value="all">All relationships</option>
    <option value="trigger">Triggers only</option>
    <option value="constraint">Constraints only</option>
    <option value="monitor">Monitored only</option>
    <option value="action">Actions only</option>
    <option value="exposed">Exposed only</option>
    <option value="owns">Ownership only</option>
    <option value="rulelinks">Rule to rule only</option>
    <option value="depends">External systems only</option>
  </select></label>
  <button id="resetBtn" type="button" style="background:#d9822b; color:#121214;">Show all</button>
  <button id="insightsBtn" type="button">Insights</button>
  <button id="extBtn" type="button">External systems</button>
  <button id="pivotBtn" type="button">Pivot tables</button>
  <button id="iconsBtn" type="button">Device icons</button>
  <button id="exportBtn" type="button" title="Download the whole map as JSON, for an AI or other tool to read">AI friendly export</button>
  <button id="communityUtilitiesBtn" type="button" style="background:#81BC00; color:#121214;" title="Open the Hubitat Community Utilities site in a new tab">Community utilities</button>
  <button id="exitMapBtn" type="button" title="Return to this app's settings screen">Exit map</button>
</div>
<div id="flow"><button id="flowClose" type="button" title="Close">&times;</button><div id="flowBack" style="display:none"></div><h3 id="flowTitle"></h3><div class="sub" id="flowSub"></div><div id="flowChart"></div></div>
<div id="ext"><button id="extClose" type="button" title="Close">&times;</button><div id="extBody"></div></div>
<div id="pivot"><button id="pivotClose" type="button" title="Close">&times;</button><div id="pivotBody"></div></div>
<div id="icons"><button id="iconsClose" type="button" title="Close">&times;</button><div id="iconsBody"></div></div>
<div id="network"></div>
<div id="offline" style="display:none; position:absolute; top:40%; left:0; right:0; text-align:center; padding:0 2em">
  <h2>Could not load the drawing libraries</h2>
  <p>This page fetches its graph and flowchart libraries from the internet. The hub itself is fine - the browser you are viewing this in could not reach them.</p>
</div>
<script>
// The libraries come from a CDN, so a browser with no internet gets a blank
// page unless this is checked. Say so rather than showing nothing.
if (typeof window.vis === 'undefined') {
  document.getElementById('offline').style.display = 'block';
  document.getElementById('controls').style.display = 'none';
  document.getElementById('legend').style.display = 'none';
}
</script>
<script>
// Gives the entry the page loaded on a real state object, not just null.
// popstate on Back all the way to this entry would otherwise arrive with
// event.state === null, which the popstate handler further down treats as
// "not one of ours" and ignores - leaving the map showing whatever was last
// focused while the browser's own position had already moved back to the
// unfocused base entry. replaceState rather than pushState: this is the
// entry already open, not a new one.
try { history.replaceState({ amFocus: null, cameFrom: null }, ''); } catch (e) { }

const GRAPH = ${jsonStr};
const SCAN_META = ${scanMetaJsonStr};
const roleColors = { trigger: '#9b59b6', constraint: '#16a085', monitor: '#3d7ea6', action: '#7fae42', owns: '#8090a0', exposed: '#c98b6b',
                     runs: '#d9534f', cancelTimedActions: '#d9534f', setspb: '#d9534f', pauseResume: '#d9534f',
                     depends: '#cfd8dc', write: '#4fb3a9', read: '#8fd6cc' };
const groupColors = { app: '#e8a33d', device: '#5f7d8c', external: '#cfd8dc', hubVariable: '#4fb3a9' };

// Device icon glyphs, keyed by n.icon (see ICON_RULES/autoDetectIconKey in the
// Groovy source - the Groovy side decides WHICH key a device gets, this side
// only decides what that key looks like). FontAwesome 6 Free Solid codepoints,
// verified against the shipped CSS rather than guessed - a wrong codepoint
// fails silently as a blank glyph, which would be a bad first impression of
// this feature. Rendered through the 'AMIcons' face declared in <style>.
const ICON_GLYPHS = {
  locks: '\uf023', presence: '\uf007', doors: '\uf52b', water: '\uf043',
  motion: '\uf554', safety: '\uf06d', buttons: '\uf25a', cameras: '\uf030',
  shades: '\uf2d0', broker: '\uf0e0', climate: '\uf863', lighting: '\uf0eb',
  security: '\uf0f3', media: '\uf028', switches: '\uf205', energy: '\ue0b7',
  environmental: '\uf2c9', sensor: '\uf2db', hub: '\uf0e8', ai: '\uf544',
  appliance: '\ue51a', network: '\uf0ac', display: '\ue163',
  unknown: '\uf059',
};

// Renders one icon+colour combination to a small PNG data URL, once, on an
// offscreen canvas - see the comment in styledNode for why this exists
// instead of a native "icon on a filled circle" shape. Cached by key so a
// role colour shared by many devices (the common case) only pays the
// render cost once.
const ICON_IMAGE_CACHE = {};
function iconImageDataURL(iconKey, fillColor) {
  const cacheKey = iconKey + '|' + fillColor;
  if (ICON_IMAGE_CACHE[cacheKey]) return ICON_IMAGE_CACHE[cacheKey];
  const size = 44;
  const c = document.createElement('canvas');
  c.width = size; c.height = size;
  const ctx = c.getContext('2d');
  ctx.beginPath();
  ctx.arc(size / 2, size / 2, size / 2 - 2, 0, 2 * Math.PI);
  ctx.fillStyle = fillColor;
  ctx.fill();
  ctx.font = Math.round(size * 0.52) + 'px AMIcons';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  // Dark glyph on every fill colour rather than choosing per-colour
  // contrast - every role/group colour in this file is light-to-mid
  // toned, never dark enough that a dark glyph would disappear.
  ctx.fillStyle = '#062733';
  ctx.fillText(ICON_GLYPHS[iconKey] || ICON_GLYPHS.unknown, size / 2, size / 2 + 1);
  const url = c.toDataURL('image/png');
  ICON_IMAGE_CACHE[cacheKey] = url;
  return url;
}

// Rule-to-rule kinds. These join two apps rather than an app and a device, so
// they must never take part in colouring a device by its role.
const RULE_LINK_KINDS = ['runs', 'cancelTimedActions', 'setspb', 'pauseResume'];

// Human-readable form of every edge kind, reused by the legend's own wording
// so a pivot table and the graph never describe the same relationship two
// different ways.
const KIND_LABEL = {
  trigger: 'Trigger', constraint: 'Constraint', monitor: 'Monitor', action: 'Action',
  exposed: 'Exposed', owns: 'Owns', runs: 'Runs', cancelTimedActions: 'Cancel timed actions',
  setspb: 'Private Boolean', pauseResume: 'Pause/resume', depends: 'Depends on', write: 'Write', read: 'Read'
};
const GROUP_LABEL = { app: 'App', device: 'Device', external: 'External system', hubVariable: 'Hub Variable' };

// Which edge kinds actually connect two node groups, keyed order-independently
// (device|app and app|device are the same relationship read from either end).
// Every edge on this map has an app in `from` - buildGraph never creates one
// the other way round - so 'device' and 'external' never appear as a source
// group here, only as a target. That is a fact about the data, not a design
// choice made here, and it is what makes every combination this map can
// produce meaningful: there is no such thing as a Device x External pivot,
// because no edge on the graph could ever populate one.
function pivotKindOptions(g1, g2) {
  const key = [g1, g2].sort().join('|');
  if (key === 'app|app') return ['runs', 'cancelTimedActions', 'setspb', 'pauseResume'];
  if (key === 'app|device') return ['trigger', 'constraint', 'monitor', 'action', 'exposed', 'owns'];
  if (key === 'app|external') return ['depends'];
  if (key === 'app|hubVariable') return ['write', 'read'];
  return [];
}
function pivotColOptions(rowGroup) {
  return rowGroup === 'app' ? ['app', 'device', 'external', 'hubVariable'] : ['app'];
}

// The fixed menu (option A from the discussion): each entry is a ready-made
// query into pivotRows below, phrased the way a person would ask for it
// rather than in row/column/kind terms.
const PIVOT_PRESETS = [
  { button: 'Rule → Rules affected', rows: 'app', cols: 'app',
    kinds: ['runs', 'cancelTimedActions', 'setspb', 'pauseResume'],
    rowLabel: 'Rule', colLabel: 'Rules affected' },
  // ruleRows/ruleCols: without this, "Rule -> Devices" queried every app
  // typed as an app - LIFX Light Manager or any other integration with a
  // device edge would show up under a heading that says Rule. appType comes
  // from buildGraph and is checked against the Rule-<engine> prefix, not
  // against the display label, which the inert/unreadable states overwrite.
  { button: 'Rule → Devices', rows: 'app', cols: 'device',
    kinds: ['trigger', 'constraint', 'monitor', 'action', 'exposed', 'owns'],
    rowLabel: 'Rule', colLabel: 'Devices', opts: { ruleRows: true } },
  { button: 'Device → Rules', rows: 'device', cols: 'app',
    kinds: ['trigger', 'constraint', 'monitor', 'action', 'exposed', 'owns'],
    rowLabel: 'Device', colLabel: 'Rules', opts: { ruleCols: true } },
  { button: 'Rule → Hub Variables', rows: 'app', cols: 'hubVariable',
    kinds: ['write', 'read'],
    rowLabel: 'Rule', colLabel: 'Hub Variables', opts: { ruleRows: true } },
  { button: 'Hub Variable → Rules', rows: 'hubVariable', cols: 'app',
    kinds: ['write', 'read'],
    rowLabel: 'Hub Variable', colLabel: 'Rules', opts: { ruleCols: true } },
];

// The free-form builder (option B): same underlying query, but rows, columns
// and which relationship counts are chosen from dropdowns instead of being
// fixed in a preset. Built from ALL_NODES/ALL_EDGES, already fully loaded for
// this scan - a pivot is a different arrangement of data already in the
// browser, not a new fetch or a reason to rescan the hub.
// A node typed 'app' can be a Rule Machine rule or any other integration -
// LIFX Light Manager and _System Start are both 'app' nodes. appType carries
// the real underlying type so the two can be told apart without depending on
// the display label, which inert/unreadable states overwrite.
function isRuleNode(n) {
  return !!(n && n.appType && n.appType.indexOf('Rule-') === 0);
}

function pivotRows(rowGroup, colGroup, kinds, opts) {
  opts = opts || {};
  const byId = {};
  ALL_NODES.forEach(function (n) { byId[n.id] = n; });

  const groups = {};
  ALL_EDGES.forEach(function (e) {
    if (kinds.indexOf(e.kind) === -1) return;
    const fromNode = byId[e.from], toNode = byId[e.to];
    if (!fromNode || !toNode) return;
    let rowId, colId, rowNode, colNode;
    if (fromNode.group === rowGroup && toNode.group === colGroup) {
      rowId = e.from; colId = e.to; rowNode = fromNode; colNode = toNode;
    } else if (rowGroup !== colGroup && toNode.group === rowGroup && fromNode.group === colGroup) {
      // Only taken when rows and columns differ - when they are the same
      // group (Rule x Rule) this branch would also match every edge the IF
      // above already matched, doubling each relationship into both a row and
      // its own reverse.
      rowId = e.to; colId = e.from; rowNode = toNode; colNode = fromNode;
    } else {
      return;
    }
    if (opts.ruleRows && !isRuleNode(rowNode)) return;
    if (opts.ruleCols && !isRuleNode(colNode)) return;
    if (!groups[rowId]) groups[rowId] = [];
    const already = groups[rowId].some(function (t) { return t.id === colId && t.kind === e.kind; });
    if (!already) groups[rowId].push({ id: colId, title: colNode.title, kind: e.kind });
  });

  let typed = ALL_NODES.filter(function (n) { return n.group === rowGroup; });
  if (opts.ruleRows) typed = typed.filter(isRuleNode);
  const rows = typed.map(function (n) {
    const targets = (groups[n.id] || []).slice().sort(function (a, b) { return a.title.localeCompare(b.title); });
    return { id: n.id, title: n.title, targets: targets };
  })
    // Rows with nothing to show are counted, not listed - the same choice
    // Insights already makes for "devices nothing references": a number and
    // a sentence read better than a table that is mostly blank rows.
    .filter(function (r) { return r.targets.length > 0; })
    .sort(function (a, b) { return a.title.localeCompare(b.title); });

  return { rows: rows, total: typed.length };
}

function renderPivotTable(pivot, rowLabel, colLabel) {
  if (!pivot.rows.length) {
    return '<p class="sub">None of the ' + pivot.total + ' ' + rowLabel.toLowerCase() +
      (pivot.total === 1 ? '' : 's') + ' on this map has that relationship.</p>';
  }
  let html = '<table><thead><tr><th>' + rowLabel + '</th><th>' + colLabel + '</th></tr></thead><tbody>';
  pivot.rows.forEach(function (r) {
    html += '<tr><td><a href="#" data-node="' + r.id + '">' + extEsc(r.title) + '</a></td><td>';
    html += r.targets.map(function (t) {
      return '<a href="#" data-node="' + t.id + '">' + extEsc(t.title) + '</a> <span class="sub">(' + (KIND_LABEL[t.kind] || t.kind) + ')</span>';
    }).join(', ');
    html += '</td></tr>';
  });
  html += '</tbody></table>';
  return html;
}

// CSV of exactly what the table on screen shows - rows with no relationship
// are excluded from the table for the same reason they are excluded here:
// a count of things not shown was reported as confusing rather than useful,
// so this file does not carry a shadow list of them anywhere either.
function pivotToCSV(pivot, rowLabel, colLabel) {
  function esc(s) {
    s = String(s == null ? '' : s);
    return /[",\\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  }
  const lines = [esc(rowLabel) + ',' + esc(colLabel)];
  pivot.rows.forEach(function (r) {
    const targets = r.targets.map(function (t) { return t.title + ' (' + (KIND_LABEL[t.kind] || t.kind) + ')'; }).join('; ');
    lines.push(esc(r.title) + ',' + esc(targets));
  });
  return lines.join('\\n');
}

function pivotDownloadCSV(pivot, rowLabel, colLabel) {
  const csv = pivotToCSV(pivot, rowLabel, colLabel);
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = (rowLabel + '_to_' + colLabel).replace(/\\s+/g, '_') + '.csv';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

// Most-significant role first. Used to colour a device that holds more than one
// role in the same app - e.g. a motion sensor that is both a rule's trigger and
// part of that rule's Wait-for-Expression condition.
const ROLE_ORDER = ['trigger', 'constraint', 'monitor', 'action', 'exposed', 'owns'];

const ALL_NODES = GRAPH.nodes;

// Parallel edges between the same pair would otherwise be drawn exactly on top
// of each other, hiding the fact that a device holds two roles in one app.
const pairSeen = {};
const ALL_EDGES = GRAPH.edges.map(function (e, i) {
  // carry the stateful flag through for conflict detection
  const pairKey = e.from + '|' + e.to;
  const dupIndex = pairSeen[pairKey] === undefined ? 0 : pairSeen[pairKey] + 1;
  pairSeen[pairKey] = dupIndex;
  // Arrows follow the flow: a trigger or constraint feeds INTO the app, an
  // action or an owned device is driven BY it.
  const inbound = (e.kind === 'trigger' || e.kind === 'constraint' || e.kind === 'monitor' || e.kind === 'read');
  // A rule link always reads caller to target, and is drawn heavier than a
  // device relationship because it is the rarer and more surprising one.
  const isRuleLink = RULE_LINK_KINDS.indexOf(e.kind) !== -1;
  let dashes = false;
  if (e.kind === 'owns') dashes = true;
  else if (e.kind === 'exposed') dashes = [2, 4];
  else if (e.kind === 'cancelTimedActions') dashes = [8, 4];
  else if (e.kind === 'setspb') dashes = [2, 3];
  else if (e.kind === 'pauseResume') dashes = [12, 4, 2, 4];
  // Always dashed, because a dependency on an external system is asserted by a
  // person, not read off the hub. Weight carries the part that matters
  // operationally: whether losing it stops the automation or merely stops you
  // reconfiguring it.
  else if (e.kind === 'depends') dashes = (e.crit === 'RUNTIME') ? [6, 3] : [2, 5];
  let width = isRuleLink ? 2.4 : ((e.kind === 'owns' || e.kind === 'exposed') ? 1 : 1.6);
  if (e.kind === 'depends') width = (e.crit === 'RUNTIME') ? 2.2 : 1.2;
  const edge = {
    id: i, from: e.from, to: e.to, kind: e.kind, stateful: e.stateful === true,
    crit: e.crit || null,
    arrows: inbound ? 'from' : 'to',
    dashes: dashes,
    color: roleColors[e.kind] || '#999',
    width: width,
    smooth: { type: 'curvedCW', roundness: 0.12 + (dupIndex * 0.22) }
  };
  // A longer spring on dependency edges settles external systems out past the
  // ring of devices, so the outside world reads as outside rather than as one
  // more thing scattered among the hardware.
  if (e.kind === 'depends') edge.length = 380;
  return edge;
});

// When one app is focused, its devices are coloured by the role they play in
// THAT app - a device can legitimately be a trigger for one app and a target
// for another, so this colouring only makes sense scoped to a single app.
// Shelf coordinates for the unconnected apps: computed once by
// shelveInertNodes after the first stabilization, then treated as permanent.
//
// Declared HERE, above styledNode, and not next to the function that fills it.
// styledNode reads it, and the initial DataSet is built by calling styledNode,
// which happens before that function is reached - a const declared later is in
// its temporal dead zone at that moment, and the ReferenceError would kill the
// whole page script rather than just the shelf.
const INERT_POS = {};

function styledNode(n, useFullLabel, roleByDevice) {
  const role = roleByDevice ? roleByDevice[n.id] : null;
  let color = n.group === 'device'
    ? (role && roleColors[role] ? roleColors[role] : groupColors.device)
    : groupColors[n.group];
  // Paused and disabled apps are greyed so they are not mistaken for live ones.
  if (n.inactive) color = '#6d6a5f';
  // A rule reached only as the target of another rule, never scanned itself
  // because it touches none of the selected devices. Outlined rather than
  // filled so it does not look like a fully mapped app.
  if (n.unscanned) color = { background: '#2b2b2b', border: '#e8a33d' };
  // A target id that no longer resolves to anything. buildGraph sets missing
  // alongside unscanned, because a deleted rule is by definition also one the
  // scan never reached, so this must be tested AFTER unscanned to win. Red
  // rather than orange: it is the same finding Insights reports under "Broken
  // rule references", and it is not an app at all any more.
  if (n.missing) color = { background: '#2b2b2b', border: '#d9534f' };
  // Installed, scanned, and connected to nothing the map tracks. Still an app,
  // so it keeps the app colour, but dimmed and dashed so it does not read as a
  // peer of the apps that actually do something. Its subtitle says why.
  if (n.inert) color = { background: '#3d3222', border: '#e8a33d' };
  // The hub did not answer for this app - a different finding from n.inert
  // (which means the hub answered and there was genuinely nothing), so it
  // gets its own colour rather than reusing amber's "empty" or red-outline's
  // "does not exist". Filled, not outlined: the app is real and installed,
  // only unread.
  if (n.unreadable) color = { background: '#4a1f1f', border: '#d9534f' };
  // External systems get their own shape as well as their own colour, because
  // they are the only nodes on the map that nobody measured.
  //
  // Devices are 'icon' rather than the plain 'dot' this shape variable name
  // still suggests - a light, a door and a water sensor get their own
  // glyph (n.icon, set server-side by autoDetectIconKey or a manual
  // override) instead of all rendering as identical circles. color above is
  // unchanged and still tints the glyph, so "colour means role" on a focused
  // view survives; only the marker's shape is new.
  let shape = 'dot';
  if (n.group === 'app') shape = 'square';
  else if (n.group === 'external') shape = 'diamond';
  else if (n.group === 'hubVariable') shape = 'triangle';
  else if (n.group === 'device') shape = 'icon';
  const styled = {
    // n.draw is the full identity without the hub's live status; n.title keeps
    // the status and is what the hover tooltip shows. The fallback matters: a
    // graph cached before draw existed has only title, and rendering undefined
    // would blank every label on the map rather than fail visibly.
    id: n.id, label: useFullLabel ? (n.draw || n.title) : n.label, title: n.title, color: color,
    shape: shape,
    size: n.group === 'app' ? 17 : (n.group === 'external' ? 19 : 13),
    font: { color: '#fff', size: 13, strokeWidth: 5, strokeColor: '#062733', vadjust: -4 },
    // Wraps a long label over several lines instead of drawing one wide ribbon
    // of text. vis.js does no label collision avoidance at all, so width is the
    // only lever there is: on a crowded sector three long names were painting
    // straight through each other. 170px is a little under the arc spacing
    // sectorLayout uses at its tightest.
    //
    // It is widthConstraint that does this, NOT font.maxWdt. maxWdt is the
    // internal property vis sets FROM widthConstraint, and setting it directly
    // is silently ignored - the first attempt at this fix did exactly that and
    // changed nothing on screen.
    widthConstraint: { maximum: 170 }
  };
  // A bare icon glyph on the dark page background was hard to spot at
  // normal zoom - found live, screenshots of Garage Motion Sensor and
  // Guest Room 1 Button both needed zooming in 3x before the glyph read at
  // all. vis-network has no built-in "icon on a filled circle" shape, and
  // its ctxRenderer custom-drawing hook (which would let one be drawn
  // directly) was tested live against this exact page and does nothing -
  // 0 pixels changed where it should have painted a test circle, so this
  // build of vis-network does not support it. circularImage does the same
  // job a different way: iconImageDataURL below pre-renders the circle and
  // the glyph together on an offscreen canvas once per (icon, colour) pair
  // and hands vis-network a plain image, which is a shape it reliably
  // supports.
  if (shape === 'icon') {
    styled.shape = 'circularImage';
    styled.image = iconImageDataURL(n.icon, typeof color === 'string' ? color : groupColors.device);
    styled.size = 15;
  }
  // Dashed outline as well as the dimmed fill. Two signals rather than one,
  // because the fill alone is close to the paused colour at a glance and these
  // mean very different things: paused is an app that would do something, inert
  // is an app that has nothing to do it to.
  if (n.inert) {
    styled.shapeProperties = { borderDashes: [4, 3] };
    styled.size = 14;
    // The shelf position is re-applied on every render, not set once after the
    // first stabilization. Every filter change rebuilds the DataSet from this
    // function, so a position applied afterwards was thrown away the moment you
    // focused something and came back, and the physics scattered them.
    if (INERT_POS[n.id]) {
      styled.x = INERT_POS[n.id].x;
      styled.y = INERT_POS[n.id].y;
      styled.fixed = { x: true, y: true };
      styled.physics = false;
    }
  }
  // Heavier, so an external system shared by several apps holds its position
  // instead of being dragged about by whichever app pulls hardest.
  if (n.group === 'external') styled.mass = 3;
  return styled;
}

const nodes = new vis.DataSet(ALL_NODES.map(function (n) { return styledNode(n, false, null); }));
const edges = new vis.DataSet(ALL_EDGES);

const network = new vis.Network(document.getElementById('network'), { nodes: nodes, edges: edges }, {
  physics: {
    stabilization: { iterations: 300 },
    barnesHut: { gravitationalConstant: -26000, springLength: 220, springConstant: 0.02, avoidOverlap: 1 }
  },
  interaction: { hover: true, tooltipDelay: 100 },
  edges: { smooth: { type: 'continuous' } }
});

// The very first device icons can be drawn before the AMIcons webfont has
// actually finished downloading - @font-face loads asynchronously, but the
// DataSet above is built synchronously on page load. A glyph drawn to
// canvas before its font is ready silently falls back to the browser
// default font and bakes that wrong render into the cached data URL
// forever, since canvas text is a bitmap, not live text that reflows when
// the real font arrives. Once the font is confirmed ready, the cache is
// thrown away and every device node is re-rendered - a no-op if the icons
// were already correct, a real fix on the run where they were not.
document.fonts.ready.then(function () {
  Object.keys(ICON_IMAGE_CACHE).forEach(function (k) { delete ICON_IMAGE_CACHE[k]; });
  // Only nodes currently in the DataSet - update() upserts, so including an
  // id that a filter change has since removed would silently add it back.
  const presentIds = {};
  nodes.getIds().forEach(function (id) { presentIds[id] = true; });
  nodes.update(ALL_NODES.filter(function (n) { return n.group === 'device' && presentIds[n.id]; })
    .map(function (n) { return styledNode(n, false, null); }));
});

// A node with no edges has nothing pulling it in, so barnesHut repulsion alone
// decides where it goes and it ends up flung to whichever margin was emptiest.
// Thirteen of those look like debris scattered around the map.
//
// So they are not left to the physics. Once everything else has settled they are
// laid out in a tidy shelf under the graph, which reads as a deliberate group of
// apps standing apart from the network rather than as bits that drifted off.
// Done after stabilization rather than by pinning coordinates up front, because
// the graph's extent is not known until it has settled.
// Set by shelveInertNodes once the shelf's real extent is known, drawn every
// frame by the afterDrawing hook below. null means no inert nodes exist this
// scan, so nothing is drawn - a divider with nothing under it would be
// confusing rather than informative.
let shelfDivider = null;

function shelveInertNodes() {
  const inertIds = ALL_NODES.filter(function (n) { return n.inert; })
    .sort(function (a, b) { return a.title.localeCompare(b.title); })
    .map(function (n) { return n.id; });
  if (!inertIds.length) return;

  const positions = network.getPositions();
  let maxY = null;
  let minX = null;
  let maxX = null;
  Object.keys(positions).forEach(function (id) {
    if (inertIds.indexOf(id) !== -1) return;
    const p = positions[id];
    if (maxY === null || p.y > maxY) maxY = p.y;
    if (minX === null || p.x < minX) minX = p.x;
    if (maxX === null || p.x > maxX) maxX = p.x;
  });
  // Every node on the map is inert, which can only happen on a hub where
  // nothing references anything. Leave the physics result alone.
  if (maxY === null) return;

  const COL_W = 260;
  const ROW_H = 90;
  const width = Math.max(maxX - minX, COL_W);
  const perRow = Math.max(1, Math.min(inertIds.length, Math.floor(width / COL_W)));
  const startX = (minX + maxX) / 2 - ((perRow - 1) * COL_W) / 2;
  const startY = maxY + 200;

  const updates = inertIds.map(function (id, i) {
    const pos = {
      x: Math.round(startX + (i % perRow) * COL_W),
      y: Math.round(startY + Math.floor(i / perRow) * ROW_H)
    };
    INERT_POS[id] = pos;
    return {
      id: id,
      x: pos.x,
      y: pos.y,
      // Pinned, so a later drag of a connected node cannot drag the shelf out
      // of shape, and so re-enabling physics would not scatter them again.
      fixed: { x: true, y: true },
      physics: false
    };
  });
  nodes.update(updates);

  // Spans the shelf's own width, not the cluster's above it - a handful of
  // inert apps sit in a narrower row than the network they're parked under,
  // and a divider stretched to the cluster's width would float free of what
  // it's supposed to be marking.
  const shelfXs = inertIds.map(function (id) { return INERT_POS[id].x; });
  shelfDivider = {
    x1: Math.min.apply(null, shelfXs) - COL_W / 2,
    x2: Math.max.apply(null, shelfXs) + COL_W / 2,
    y: startY - ROW_H / 2
  };
}

// Runs on every canvas redraw, in the network's own coordinate space (the
// same one node.x/node.y live in), which is why the line and label track pan
// and zoom instead of needing to be repositioned by hand on every frame.
//
// That same coordinate space is what makes a fixed font size wrong: vis-
// network keeps its OWN node labels a constant size on screen regardless of
// zoom (scaling.label defaults to off), but a raw canvas draw here gets no
// such treatment - "13px" is 13 units in graph space, and at the zoom level
// needed to fit a few hundred nodes that renders as a handful of actual
// screen pixels. Dividing every screen-space size by network.getScale()
// counteracts the zoom the same way vis-network already does for its labels,
// so this reads at a constant size next to them rather than shrinking when
// the view zooms out to fit the whole graph.
network.on('afterDrawing', function (ctx) {
  if (!shelfDivider) return;
  const scale = network.getScale() || 1;
  ctx.save();
  ctx.strokeStyle = 'rgba(255,255,255,0.35)';
  ctx.lineWidth = 1 / scale;
  ctx.beginPath();
  ctx.moveTo(shelfDivider.x1, shelfDivider.y);
  ctx.lineTo(shelfDivider.x2, shelfDivider.y);
  ctx.stroke();
  ctx.fillStyle = 'rgba(255,255,255,0.55)';
  ctx.font = (13 / scale) + 'px sans-serif';
  ctx.textAlign = 'left';
  ctx.textBaseline = 'bottom';
  ctx.fillText('Inert Nodes', shelfDivider.x1, shelfDivider.y - 4 / scale);
  ctx.restore();
});

function settle() {
  network.once('stabilizationIterationsDone', function () {
    network.setOptions({ physics: { enabled: false } });
    shelveInertNodes();
    network.fit({ animation: false });
  });
}
settle();

// Gordon's own fix for the "no visible jiggle on first open" request, after
// the earlier attempt to rebuild this by changing the physics/stabilization
// mechanism itself broke the inert-node shelf live: just run the exact same
// Show all a user would click by hand, once, shortly after the page's own
// first (invisible, hidden-batch) settle finishes. No physics/stabilization
// option is touched by this at all - exitToWholeMap() is unchanged, already
// proven correct, and confirmed live to give the visible jiggle-then-settle
// on its own.
//
// A second, independent listener on the same event settle() already
// listens for, not something chained inside settle()'s own callback -
// settle() is shared with every other caller (e.g. the real Show all
// button), and must stay untouched so nothing here can affect it.
//
// setTimeout, not called directly from this listener: doing two full
// nodes.clear()/nodes.add() DataSet rebuilds back to back with no event-loop
// tick in between was confirmed live earlier this session to be unreliable
// (vis-network's internal sync did not consistently pick up the second one).
// exitToWholeMap() itself now does exactly one rebuild via applyFilters(),
// same as always, but only after this page's own initial settle has fully
// finished and yielded, not layered on top of it in the same tick.
//
// poppingHistory suppresses exitToWholeMap()'s own history.pushState() for
// this one programmatic call - without it, opening the map would silently
// push a second, identical history entry right after the page's own base
// entry, so the first Back press after opening would appear to do nothing.
network.once('stabilizationIterationsDone', function () {
  setTimeout(function () {
    poppingHistory = true;
    exitToWholeMap();
    poppingHistory = false;
  }, 0);
});

// fit() is calculated for the size the canvas had at the time, so without this
// the graph stays at the old zoom after the window changes size and can end up
// far too close in.
let refitTimer = null;
window.addEventListener('resize', function () {
  if (refitTimer) clearTimeout(refitTimer);
  refitTimer = setTimeout(function () { network.fit({ animation: false }); }, 200);
});

function neighborhood(nodeId, edgePool) {
  const ids = {};
  ids[nodeId] = true;
  const edgeList = [];
  edgePool.forEach(function (e) {
    if (e.from === nodeId || e.to === nodeId) {
      ids[e.from] = true; ids[e.to] = true;
      edgeList.push(e);
    }
  });
  return { ids: ids, edgeList: edgeList };
}

function applyFilters() {
  const appVal = document.getElementById('appFilter').value;
  const devVal = document.getElementById('deviceFilter').value;
  const kindVal = document.getElementById('kindFilter').value;

  let pool = ALL_EDGES;
  if (kindVal === 'rulelinks') {
    pool = ALL_EDGES.filter(function (e) { return RULE_LINK_KINDS.indexOf(e.kind) !== -1; });
  } else if (kindVal !== 'all') {
    pool = ALL_EDGES.filter(function (e) { return e.kind === kindVal; });
  }

  let ids = null;
  let shownEdges = pool;
  const focusId = appVal !== '__all__' ? appVal : (devVal !== '__all__' ? devVal : null);
  if (focusId) {
    const focus = neighborhood(focusId, pool);
    ids = focus.ids; shownEdges = focus.edgeList;
    ids[focusId] = true;
  } else if (kindVal !== 'all') {
    // Narrowing the relationship must narrow the NODES too, not just the lines.
    // Without this, "External systems only" kept drawing all 288 nodes and hid
    // every edge that was not a dependency, so a handful of real clusters sat in
    // a field of 250 unconnected dots. It looked like the filter was broken; it
    // was drawing exactly what it was told to.
    ids = {};
    pool.forEach(function (e) { ids[e.from] = true; ids[e.to] = true; });
  }

  let roleByDevice = null;
  if (appVal !== '__all__') {
    // A device can hold several roles in one app, so colour it by the most
    // significant rather than by whichever edge happened to be processed last.
    roleByDevice = {};
    shownEdges.forEach(function (e) {
      if (e.from !== appVal) return;
      // Rule links point at another app, and are not in ROLE_ORDER at all -
      // indexOf would return -1 and win every comparison.
      if (RULE_LINK_KINDS.indexOf(e.kind) !== -1) return;
      const prev = roleByDevice[e.to];
      if (!prev || ROLE_ORDER.indexOf(e.kind) < ROLE_ORDER.indexOf(prev)) roleByDevice[e.to] = e.kind;
    });
  }

  const shownNodes = ids ? ALL_NODES.filter(function (n) { return ids[n.id]; }) : ALL_NODES;
  const styled = shownNodes.map(function (n) { return styledNode(n, !!focusId, roleByDevice); });

  // With one app focused the whole neighbourhood is known, so it can be laid
  // out deliberately instead of being left to settle. See sectorLayout.
  const placed = (appVal !== '__all__') ? sectorLayout(appVal, styled, shownEdges) : false;

  // Physics is switched off BEFORE the positioned nodes are added, not after.
  //
  // Adding them first and disabling afterwards leaves a window in which the
  // engine is still running, and on the first focus after a page load it is
  // still working through its stabilisation pass, so it shoves the nodes off
  // their assigned positions before physics is stopped. Opening the same view
  // a second time looked correct purely because the engine had already settled
  // and stopped by then.
  // Physics off, but nodes are NOT marked fixed. Fixed pins a node against the
  // physics engine, which is already disabled here, so it bought nothing and
  // stopped you dragging a node out from under an overlapping label. Positions
  // are honoured because physics is off, and dragging still works.
  if (placed) network.setOptions({ physics: { enabled: false } });

  nodes.clear(); nodes.add(styled);
  edges.clear(); edges.add(shownEdges);

  if (placed) {
    network.fit({ animation: false });
  } else {
    network.setOptions({ physics: { enabled: true } });
    settle();
  }
}

// ---------------------------------------------------------------------------
// Deliberate layout for a focused app.
//
// Force-directed placement is right for the whole hub, where nothing is known
// in advance. Focus one app and that stops being true: every neighbour has a
// known relationship to it, and scattering them by physics throws that away.
//
// So each relationship gets a sector of the circle, and the arrangement reads
// the way a rule reads. What feeds the app sits on the left, what the app
// drives sits on the right, other rules sit above, and systems outside the hub
// sit below.
//
//                     external systems
//         triggers            |            actions
//        constraints  ---> [ app ] --->     owns
//         monitors            |            exposed
//                        other rules
//
// Angles run anticlockwise from east and y is negated, because screen y grows
// downwards. Each sector spans 80 degrees with a 10 degree gap either side.
//
// The gaps matter. An earlier version used -50..50 for outputs and 235..305
// for external, which look separate but are only five degrees apart once -50
// is read as 310, so the last output and the first external landed on top of
// each other. Keep every sector expressed in one continuous ascending range
// and keep the gaps, rather than relying on negative angles reading correctly.
// ---------------------------------------------------------------------------
// Each sector also has its own radius. Angular gaps alone are not enough: the
// last input at 220 and the first rule at 230 are only ten degrees apart, and
// on the same circle a node labelled "Mode Alarm Reminder (Required Expression
// false)" lands squarely on top of one labelled "Master Bedroom Button".
// Putting neighbouring sectors on different circles separates them regardless
// of how long the labels are.
const SECTORS = [
  { name: 'external', kinds: ['depends'],                          from: 55,  to: 125, radius: 430 },
  { name: 'inputs',   kinds: ['trigger', 'constraint', 'monitor'], from: 145, to: 215, radius: 300 },
  { name: 'rules',    kinds: RULE_LINK_KINDS,                      from: 235, to: 305, radius: 420 },
  { name: 'outputs',  kinds: ['action', 'owns', 'exposed'],        from: 325, to: 395, radius: 320 },
];

function sectorIndex(name) {
  for (let i = 0; i < SECTORS.length; i++) { if (SECTORS[i].name === name) return i; }
  return SECTORS.length - 1;
}

function sectorLayout(appId, styledNodes, shownEdges) {
  const byId = {};
  styledNodes.forEach(function (n) { byId[n.id] = n; });
  if (!byId[appId]) return false;

  // Assign each neighbour to a sector by its strongest relationship. A device
  // that is both a trigger and an action belongs on the input side, because
  // that is what ROLE_ORDER already decided it is.
  const assigned = {};
  shownEdges.forEach(function (e) {
    const other = (e.from === appId) ? e.to : ((e.to === appId) ? e.from : null);
    if (other === null || other === appId) return;
    for (let s = 0; s < SECTORS.length; s++) {
      if (SECTORS[s].kinds.indexOf(e.kind) === -1) continue;
      const prev = assigned[other];
      if (prev === undefined || s < prev) assigned[other] = s;
      break;
    }
  });

  // Placement has to be total. Physics is switched off once a layout is
  // produced, so any node left unassigned keeps whatever position it happened
  // to have from the previous view - which is how three rule targets ended up
  // sitting in the external systems sector at the top of the screen.
  //
  // So an edge kind that matches no sector falls back to the node's own group,
  // which is always known.
  function fallbackSector(node) {
    if (node.shape === 'diamond') return sectorIndex('external');
    if (node.shape === 'square') return sectorIndex('rules');
    return sectorIndex('outputs');
  }

  const buckets = SECTORS.map(function () { return []; });
  let anyPlaced = false;
  styledNodes.forEach(function (n) {
    if (n.id === appId) return;
    let s = assigned[n.id];
    if (s === undefined) s = fallbackSector(n);
    buckets[s].push(n);
    anyPlaced = true;
  });
  if (!anyPlaced) return false;

  byId[appId].x = 0;
  byId[appId].y = 0;

  buckets.forEach(function (list, s) {
    if (!list.length) return;
    list.sort(function (a, b) { return String(a.label).localeCompare(String(b.label)); });
    const sector = SECTORS[s];
    // Radius grows with crowding so labels keep their room as a sector fills.
    const radius = sector.radius + Math.max(0, list.length - 4) * 26;
    const span = sector.to - sector.from;
    list.forEach(function (n, i) {
      const t = (list.length === 1) ? 0.5 : (i / (list.length - 1));
      const deg = sector.from + (span * t);
      const rad = deg * Math.PI / 180;
      n.x = Math.round(Math.cos(rad) * radius);
      n.y = Math.round(-Math.sin(rad) * radius);
    });
  });
  return true;
}

// ---------------------------------------------------------------------------
// Rule flow panel. A force-directed graph cannot express order, so when the
// focused app is a rule its decoded steps are drawn as a real flowchart.
// ---------------------------------------------------------------------------
const FLOWS = GRAPH.flows || {};
if (window.mermaid) {
  mermaid.initialize({ startOnLoad: false, theme: 'dark', flowchart: { useMaxWidth: false } });
}

// Written without regex literals on purpose: this whole page is a Groovy
// GString, and backslash escapes inside one are a compile error.
function mermaidEscape(text) {
  let s = String(text).split('"').join("'");
  // Strip characters that would terminate a Mermaid node shape. Done before
  // entity encoding, so the entities' own semicolons survive.
  ['[', ']', '{', '}', '(', ')', '|', '#', ';'].forEach(function (ch) {
    s = s.split(ch).join(' ');
  });
  // Comparison operators matter in conditions ("is < 200"), so keep them as
  // entities rather than dropping them.
  s = s.split('&').join('&amp;');
  s = s.split('<').join('&lt;');
  s = s.split('>').join('&gt;');
  return s.split(' ').filter(function (p) { return p.length > 0; }).join(' ');
}

// Lays out IF / ELSE-IF / ELSE / END-IF as real branches.
//
// Rule Machine's own `indent` field cannot be trusted (rule 2816 has three IFs
// but only two END-IFs, and its indents disagree with the visible nesting), so
// structure is derived from the control-flow markers with a stack, and any
// block still open at the end is closed automatically rather than being lost.
function mermaidFor(steps) {
  const lines = ['flowchart TD'];
  const styles = [];
  let counter = 0;
  let tails = [];          // [{id, label}] - open ends awaiting the next node
  const stack = [];        // one frame per open IF block

  function emit(shape, text, kind) {
    const id = 'S' + (counter++);
    if (shape === 'stadium') lines.push('  ' + id + '(["' + text + '"])');
    else if (shape === 'hex') lines.push('  ' + id + '{{"' + text + '"}}');
    else if (shape === 'diamond') lines.push('  ' + id + '{"' + text + '"}');
    else lines.push('  ' + id + '["' + text + '"]');
    if (kind === 'trigger') styles.push('  style ' + id + ' fill:#4a2f5e,stroke:#9b59b6,color:#fff');
    else if (kind === 'required') styles.push('  style ' + id + ' fill:#0f4f45,stroke:#16a085,color:#fff');
    else if (kind === 'cond') styles.push('  style ' + id + ' fill:#123a4a,stroke:#4aa3c7,color:#fff');
    else styles.push('  style ' + id + ' fill:#33502a,stroke:#7fae42,color:#fff');
    return id;
  }
  function connect(to) {
    const drawn = {};
    tails.forEach(function (t) {
      const key = t.id + '|' + t.label;
      if (drawn[key]) return;
      drawn[key] = true;
      lines.push('  ' + t.id + (t.label ? ' -->|' + t.label + '| ' : ' --> ') + to);
    });
  }
  // Mermaid sizes a node to its longest line, so an action listing nine
  // speakers would stretch the whole diagram and shrink every other node into
  // illegibility. Long text is wrapped and long device lists are summarised.
  function wrap(text, width) {
    const words = String(text).split(' ');
    const out = [];
    let line = '';
    words.forEach(function (w) {
      if (line.length && (line.length + 1 + w.length) > width) { out.push(line); line = w; }
      else { line = line.length ? line + ' ' + w : w; }
    });
    if (line.length) out.push(line);
    return out.join('<br/>');
  }
  function deviceSummary(devices) {
    if (devices.length <= 3) return devices.join(', ');
    return devices.slice(0, 3).join(', ') + ' +' + (devices.length - 3) + ' more';
  }
  function nodeText(s) {
    let t = wrap(mermaidEscape(s.label), 46);
    if (s.devices && s.devices.length) {
      t += '<br/><i>' + wrap(mermaidEscape(deviceSummary(s.devices)), 46) + '</i>';
    }
    return t;
  }

  steps.forEach(function (s) {
    if (s.ctrl === 'if' || s.ctrl === 'elseif') {
      if (s.ctrl === 'elseif' && stack.length) {
        const f = stack[stack.length - 1];
        f.branchTails = f.branchTails.concat(tails);
        tails = f.pendingFalse;      // this branch is reached when the previous test failed
        f.pendingFalse = [];
      }
      // Diamonds grow in BOTH dimensions with their text, so they are wrapped
      // harder than boxes to stop one long condition dominating the diagram.
      const id = emit('diamond', wrap(mermaidEscape(s.cond || s.label), 30), 'cond');
      connect(id);
      if (s.ctrl === 'if') stack.push({ branchTails: [], pendingFalse: [] });
      if (stack.length) stack[stack.length - 1].pendingFalse = [{ id: id, label: 'no' }];
      tails = [{ id: id, label: 'yes' }];
    } else if (s.ctrl === 'else') {
      if (stack.length) {
        const f = stack[stack.length - 1];
        f.branchTails = f.branchTails.concat(tails);
        tails = f.pendingFalse;
        f.pendingFalse = [];
      }
    } else if (s.ctrl === 'endif') {
      if (stack.length) {
        const f = stack.pop();
        tails = f.branchTails.concat(tails).concat(f.pendingFalse);
      }
    } else {
      const shape = s.kind === 'trigger' ? 'stadium' : (s.kind === 'required' ? 'hex' : 'box');
      const id = emit(shape, nodeText(s), s.kind);
      connect(id);
      tails = [{ id: id, label: '' }];
    }
  });

  // Close anything the rule left open, so no branch is silently dropped.
  while (stack.length) {
    const f = stack.pop();
    tails = f.branchTails.concat(tails).concat(f.pendingFalse);
  }

  // Double-escaped on purpose. This page is a Groovy GString, so a single
  // backslash is consumed by Groovy and would emit a real newline inside this
  // string literal - a JavaScript syntax error that kills the whole page.
  return lines.concat(styles).join('\\n');
}

// flowPanel may be absent if the panel markup ever changes; the filter controls
// below must keep working regardless, so nothing here is allowed to throw.
const flowPanel = document.getElementById('flow') || { style: {} };
const flowChart = document.getElementById('flowChart') || document.createElement('div');

// The four floating panels (flow/Insights, External systems, Pivot tables,
// Device icons) started with fixed CSS z-index values, so whichever one
// happened to sit later in the page's own HTML always rendered on top
// regardless of which was actually opened most recently - found live,
// Pivot tables opened after Device icons still rendered behind it. Every
// panel-open call site now runs its show through this instead of a bare
// `.style.display = 'block'`, so the panel most recently brought up is
// always the one on top.
//
// They also used to be able to stack: opening Insights while Pivot tables
// was already up left both visible at once, reported as a "messy, multiple
// tabs open" look. Every panel-open call site already runs through here, so
// this is the one place that can hide the other three before showing this
// one, without touching any of the four buttons' own handlers.
//
// extPanel/pivotPanel/iconsPanel/hint are declared further down the file,
// but this function's body only runs on a later click, by which point the
// whole script has already finished its first pass and all of them exist -
// same as every other forward reference in this file.
//
// The collapsed legend is one line sitting entirely above where these panels
// start (top:100px, well below its own ~93px bottom edge), so it no longer
// needs to hide for a panel the way it used to - only the expanded legend is
// still tall enough to run behind panel content (the original "ghost text
// across the table" problem this hiding was built for). Hint has no
// collapsed form, so it keeps hiding for any open panel same as before.
function syncLegendVisibility() {
  const lg = document.getElementById('legend');
  const hn = document.getElementById('hint');
  const panelOpen = [flowPanel, extPanel, pivotPanel, iconsPanel].some(function (p) {
    return p && getComputedStyle(p).display !== 'none';
  });
  if (lg) lg.style.visibility = (panelOpen && !lg.classList.contains('collapsed')) ? 'hidden' : '';
  if (hn) hn.style.visibility = panelOpen ? 'hidden' : '';
}

// Legend/hint syncing used to be left to three of the four buttons to do for
// themselves (extBtn/iconsBtn/pivotBtn each set visibility:hidden before
// calling this) - Insights and a node click never did, so the legend could
// sit visibly behind those. Doing it here instead covers all four the same
// way, and only in one place.
let panelTopZ = 30;
function bringToFront(panel) {
  [flowPanel, extPanel, pivotPanel, iconsPanel].forEach(function (p) {
    if (p && p !== panel) p.style.display = 'none';
  });
  panelTopZ += 1;
  panel.style.zIndex = panelTopZ;
  panel.style.display = 'block';
  syncLegendVisibility();
}

// An app that references nothing has no flow to draw, but it is not true that
// there is nothing to say about it. Clicking one used to blank the map to a
// single square and open no panel at all, which reads as a broken click rather
// than as an app with nothing attached.
//
// So it gets a panel of its own: what the hub says it holds, and a way through
// to whatever it holds. For a container that turns a dead end into the most
// direct route to its children on the whole map.
function showInertPanel(node) {
  document.getElementById('flowTitle').textContent = node.title;
  // Two different findings that used to render identically: a fetch that
  // threw leaves the same empty roles/ruleLinks/endpoints as an app that
  // genuinely references nothing, but "the hub would not answer" and "this
  // app really does nothing" are not the same thing to tell a user.
  document.getElementById('flowSub').textContent = node.unreadable ?
    'The hub could not answer for this app during the scan. What it references is unknown, not empty - rescan to try again.' :
    'This app references no device, links to no rule and publishes no endpoint. What the hub does report about it is below.';

  let html = node.unreadable ?
    '<h3>Could not be read</h3><p class="sub">' + extEsc(node.errorDetail || 'No further detail was recorded.') + '</p>' :
    '<h3>' + extEsc(node.reason || 'References nothing') + '</h3>';
  const facts = [];
  if (node.sched) facts.push(node.sched + ' scheduled job' + (node.sched === 1 ? '' : 's'));
  if (node.subs) facts.push(node.subs + ' event subscription' + (node.subs === 1 ? '' : 's'));
  if (node.devs) facts.push(node.devs + ' child device' + (node.devs === 1 ? '' : 's'));
  if (facts.length) html += '<p class="sub">' + facts.join(' &middot; ') + '</p>';

  // The bare count used to be the whole story - clicking it did nothing,
  // because there was nothing behind it to show. next/cron come straight from
  // the hub's own scheduler. The cron pattern is shown as-is rather than
  // translated to English: a wrong "every Tuesday" from a mis-parsed field
  // would be worse than the raw pattern, which is at least never incorrect.
  if (node.schedJobs && node.schedJobs.length) {
    html += '<h4>Scheduled job' + (node.schedJobs.length === 1 ? '' : 's') + '</h4><ul>';
    node.schedJobs.forEach(function (j) {
      const when = j.next ? new Date(j.next).toLocaleString(undefined,
        { weekday: 'short', year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : 'unknown';
      html += '<li>Next run: ' + when +
        (j.cron ? '<br><span class="sub">Schedule: <code>' + j.cron + '</code></span>' : '') + '</li>';
    });
    html += '</ul>';
  }

  if (node.parent) {
    const p = ALL_NODES.filter(function (n) { return n.id === node.parent; })[0];
    if (p) {
      html += '<h4>Belongs to</h4><ul><li><a href="#" data-node="' + p.id + '">' + extEsc(p.title) + '</a></li></ul>';
    }
  }

  const kids = (node.kids || []).map(function (id) {
    return ALL_NODES.filter(function (n) { return n.id === id; })[0];
  }).filter(function (n) { return !!n; });

  if (kids.length) {
    html += '<h4>Holds ' + kids.length + ' app' + (kids.length === 1 ? '' : 's') + '</h4>';
    html += '<p class="sub">Each one is on the map in its own right. Click to go there.</p><ul>';
    kids.slice().sort(function (a, b) { return a.title.localeCompare(b.title); }).forEach(function (k) {
      html += '<li><a href="#" data-node="' + k.id + '">' + extEsc(k.title) + '</a></li>';
    });
    html += '</ul>';
  } else if (node.holds) {
    // Built by a scan from before parent ids were recorded, so it knows it
    // holds children but not which ones. Saying so is the only honest option:
    // the alternative is a heading claiming 46 apps above an empty space, which
    // is exactly what shipping this without the check did.
    html += '<h4>Holds ' + node.holds + ' app' + (node.holds === 1 ? '' : 's') + '</h4>';
    html += '<p class="sub">Which ones was not recorded by the scan that built this map. Run a scan to list them here.</p>';
  } else if (!facts.length && !node.parent) {
    html += '<p class="sub">Nothing at all: no children, no schedule, no subscriptions. Either it is not configured yet, or it is left over from something that has been removed.</p>';
  }

  flowChart.innerHTML = html;
  // Delegated, so the links keep working after the panel is rebuilt.
  flowChart.querySelectorAll('a[data-node]').forEach(function (a) {
    a.addEventListener('click', function (ev) {
      ev.preventDefault();
      focusNode(a.getAttribute('data-node'));
    });
  });
  bringToFront(flowPanel);
}

function showFlow(appId) {
  const target = ALL_NODES.filter(function (n) { return n.id === appId; })[0];
  if (target && (target.inert || target.unreadable)) { showInertPanel(target); return; }
  const steps = FLOWS[appId];
  if (!steps || !steps.length || !window.mermaid) {
    flowPanel.style.display = 'none';
    syncLegendVisibility();
    return;
  }
  const node = ALL_NODES.filter(function (n) { return n.id === appId; })[0];
  document.getElementById('flowTitle').textContent = node ? node.title : 'Rule flow';
  // Deliberately free of apostrophes. This page is a Groovy GString, so a
  // backslash-escaped quote is consumed by Groovy and ends the JS string early -
  // a syntax error that kills the entire page.
  document.getElementById('flowSub').textContent = 'Decoded execution order, reconstructed from the internal state of the app. A reading aid: the app page itself remains the authority.';
  flowChart.innerHTML = '';
  const id = 'mmd' + Date.now();
  mermaid.render(id, mermaidFor(steps)).then(function (res) {
    flowChart.innerHTML = res.svg;
    bringToFront(flowPanel);
  }).catch(function (err) {
    flowChart.textContent = 'Could not render this rule: ' + err.message;
    bringToFront(flowPanel);
  });
}

const flowCloseBtn = document.getElementById('flowClose');
if (flowCloseBtn) {
  flowCloseBtn.addEventListener('click', function () {
    flowPanel.style.display = 'none';
    syncLegendVisibility();
  });
}

// Short prefix tag for an app's building engine or origin, agreed with
// Gordon 2026-08-19 - purely a display label, sort order is untouched (the
// list below is already sorted on the real title before this ever runs).
// CUS is the deliberate catch-all: every app not specifically recognised
// gets it, so nothing is ever left with no tag, and nothing here has to be
// certain whether an unrecognised app is Gordon's own, a community app, or
// something else - only the HUB row needs that confidence.
const APP_TYPE_TAGS = {
  'Rule-5.1': 'RM5',
  'Visual Rule Builder 2.0': 'VRB',
  'Visual Rules Builder': 'VRB',
  'Basic Rule-1.0': 'BR1',
  'Basic Rules': 'BR1',
  'Notifier': 'NTF',
  'Button Rule-5.1': 'BTN',
  'Button Controller-5.1': 'BTN',
  'Button Controllers': 'BTN',
  'Chromecast Integration': 'INT',
  'CoCoHue - Hue Bridge Integration': 'INT',
  'Google Home': 'INT',
  'Kasa Integration': 'INT',
  'LIFX Light Manager': 'INT',
  'Meross MSG100 Garage Door Setup': 'INT',
  'Sensibo Integration': 'INT',
  'Tapo Integration': 'INT',
  'BOM Weather Alerts': 'INT',
  'Rule Machine': 'HUB',
  'Groups and Scenes': 'HUB',
  'Maker API': 'HUB',
  'Hubitat® Dashboard': 'HUB'
};
function appOptionText(n) {
  return '[' + (APP_TYPE_TAGS[n.appType] || 'CUS') + '] ' + n.title;
}

// Same purely-decorative prefix for devices, reusing n.icon - the existing
// auto-detected/user-overridden classification the Device icons panel
// already maintains, not a new scheme invented for this picklist. Every one
// of the 17 known categories maps to a fixed three-letter code, agreed with
// Gordon 2026-08-19; 'unknown' (n.icon's own fallback for anything neither
// capability nor name detection recognised) gets UNK rather than being left
// blank, same reasoning as CUS above - nothing in the list goes untagged.
const DEVICE_ICON_TAGS = {
  lighting: 'LGT',
  switches: 'SWT',
  buttons: 'BTN',
  motion: 'MOT',
  media: 'MED',
  presence: 'PRE',
  doors: 'DOR',
  climate: 'CLI',
  energy: 'NRG',
  appliance: 'APP',
  display: 'DIS',
  environmental: 'ENV',
  security: 'SEC',
  water: 'WTR',
  broker: 'BRK',
  hub: 'HUB',
  network: 'NET'
};
function deviceOptionText(n) {
  return '[' + (DEVICE_ICON_TAGS[n.icon] || 'UNK') + '] ' + n.title;
}
function pickOptionText(n, group) {
  if (group === 'app') return appOptionText(n);
  if (group === 'device') return deviceOptionText(n);
  return n.title;
}

// With 194 devices a plain dropdown is unusable, so each one gets a search box
// that filters its options as you type. Rebuilt rather than hidden, because
// hidden <option> elements are not reliably honoured across browsers.
function fillSelect(selectId, searchId, group, allLabel) {
  const sel = document.getElementById(selectId);
  const search = document.getElementById(searchId);
  const items = ALL_NODES.filter(function (n) { return n.group === group; })
    .slice().sort(function (a, b) { return a.title.localeCompare(b.title); });

  function render(term) {
    const q = (term || '').toLowerCase();
    const keep = sel.value;
    sel.innerHTML = '';
    const all = document.createElement('option');
    all.value = '__all__'; all.textContent = allLabel;
    sel.appendChild(all);
    let shown = 0;
    items.forEach(function (n) {
      if (q && n.title.toLowerCase().indexOf(q) < 0) return;
      const opt = document.createElement('option');
      opt.value = n.id; opt.textContent = pickOptionText(n, group);
      sel.appendChild(opt);
      shown++;
    });
    // Keep the current selection visible even if it no longer matches, so
    // typing does not silently reset the view.
    if (keep && keep !== '__all__' && !sel.querySelector('option[value="' + keep + '"]')) {
      const cur = items.filter(function (n) { return n.id === keep; })[0];
      if (cur) {
        const opt = document.createElement('option');
        opt.value = cur.id; opt.textContent = pickOptionText(cur, group);
        sel.appendChild(opt);
      }
    }
    sel.value = keep || '__all__';
    search.title = shown + ' of ' + items.length + ' shown';
  }

  render('');
  search.addEventListener('input', function () { render(search.value); });
  return sel;
}

// ---------------------------------------------------------------------------
// Insights. The graph answers "what is connected"; these answer the questions
// the hub itself cannot: which devices are driven by more than one app (the
// usual cause of automations fighting each other), and which devices nothing
// commands at all.
// ---------------------------------------------------------------------------
function buildInsights() {
  const nameOf = {};
  ALL_NODES.forEach(function (n) { nameOf[n.id] = n.title; });

  // A node this hub cannot resolve at all - the id was named by a rule-to-rule
  // link but the target no longer exists. Built from node.missing rather than
  // string-matching a "- deleted" label, so it survives whatever the display
  // label happens to say.
  const missingIds = {};
  ALL_NODES.forEach(function (n) { if (n.missing) missingIds[n.id] = true; });
  const referencesTo = {};   // deleted target -> apps that still reference it
  ALL_EDGES.forEach(function (e) {
    if (!missingIds[e.to]) return;
    if (!referencesTo[e.to]) referencesTo[e.to] = [];
    if (referencesTo[e.to].indexOf(e.from) < 0) referencesTo[e.to].push(e.from);
  });
  const brokenTargets = Object.keys(missingIds);

  const commanders = {};   // device -> apps that can leave it in a lasting state
  const touched = {};      // device -> any relationship at all
  ALL_EDGES.forEach(function (e) {
    touched[e.to] = true;
    // Only stateful commands can conflict. Two apps notifying the same phone
    // is normal; two apps driving the same light is what you want to find.
    if (e.kind === 'action' && e.stateful) {
      if (!commanders[e.to]) commanders[e.to] = [];
      if (commanders[e.to].indexOf(e.from) < 0) commanders[e.to].push(e.from);
    }
  });

  const contested = Object.keys(commanders)
    .filter(function (d) { return commanders[d].length > 1; })
    .sort(function (a, b) { return commanders[b].length - commanders[a].length; });

  const untouched = ALL_NODES
    .filter(function (n) { return n.group === 'device' && !touched[n.id]; })
    .map(function (n) { return n.id; });

  const readOnly = ALL_NODES.filter(function (n) {
    if (n.group !== 'device' || !touched[n.id]) return false;
    return !commanders[n.id];
  }).map(function (n) { return n.id; });

  let html = '<h3>Insights</h3>';
  html += '<div class="sub">Derived from the current scan. "Commanded by" counts apps with an action relationship.</div>';

  html += '<h4>Contested devices (' + contested.length + ')</h4>';
  if (!contested.length) {
    html += '<p class="sub">No device is commanded by more than one app.</p>';
  } else {
    html += '<p class="sub">More than one app can leave these in a lasting state. Where two disagree, the last to run wins. Notifications and chimes are excluded - repeating those is not a conflict.</p><ul>';
    contested.slice(0, 40).forEach(function (d) {
      html += '<li><b>' + extEsc(nameOf[d]) + '</b> &mdash; ' + commanders[d].length + ' apps<br><span class="sub">' +
        commanders[d].map(function (a) { return extEsc(nameOf[a]); }).join(' &middot; ') + '</span></li>';
    });
    html += '</ul>';
  }

  html += '<h4>Devices nothing references (' + untouched.length + ')</h4>';
  if (!untouched.length) {
    html += '<p class="sub">Every device in the map is referenced by at least one app.</p>';
  } else {
    html += '<p class="sub">No app owns, watches or drives these. Candidates for removal, or gaps in automation.</p><ul>';
    untouched.slice(0, 60).forEach(function (d) { html += '<li>' + extEsc(nameOf[d]) + '</li>'; });
    html += '</ul>';
  }

  html += '<h4>Read but never driven (' + readOnly.length + ')</h4>';
  html += '<p class="sub">Referenced only as triggers, constraints or monitored inputs. Expected for sensors.</p>';

  // Grouped by the reason rather than listed flat. Eleven containers and two
  // genuine orphans in one alphabetical list reads as thirteen problems; split
  // by reason it reads as one problem and twelve explanations.
  const inertNodes = ALL_NODES.filter(function (n) { return n.inert; });
  html += '<h4>Apps with no device or rule relationship (' + inertNodes.length + ')</h4>';
  if (!inertNodes.length) {
    html += '<p class="sub">Every app on the map references at least one device or rule.</p>';
  } else {
    html += '<p class="sub">These are installed and were read, but touch no device, link to no rule and publish no endpoint. Most are containers holding other apps, which is expected. The ones giving no reason at all are the ones worth a look.</p>';
    const byReason = {};
    inertNodes.forEach(function (n) {
      const reason = n.reason || 'no reason recorded';
      if (!byReason[reason]) byReason[reason] = [];
      byReason[reason].push(n);
    });
    // "references nothing" last: it is the finding, and a finding reads better
    // after the things that explain themselves.
    const reasons = Object.keys(byReason).sort(function (a, b) {
      if (a === 'references nothing') return 1;
      if (b === 'references nothing') return -1;
      return a.localeCompare(b);
    });
    html += '<ul>';
    reasons.forEach(function (r) {
      html += '<li><b>' + extEsc(r) + '</b><br><span class="sub">' +
        byReason[r].map(function (n) { return extEsc(nameOf[n.id]); }).join(' &middot; ') + '</span></li>';
    });
    html += '</ul>';
  }

  html += '<h4>Broken rule references (' + brokenTargets.length + ')</h4>';
  if (!brokenTargets.length) {
    html += '<p class="sub">No rule references a target that no longer exists.</p>';
  } else {
    html += '<p class="sub">These rule/action/pause/private-boolean targets no longer resolve to anything. The referencing action still runs and silently does nothing.</p><ul>';
    brokenTargets.forEach(function (id) {
      html += '<li><b>' + extEsc(nameOf[id]) + '</b><br><span class="sub">Referenced by ' +
        (referencesTo[id] || []).map(function (a) { return extEsc(nameOf[a]); }).join(' &middot; ') + '</span></li>';
    });
    html += '</ul>';
  }

  return html;
}

document.getElementById('insightsBtn').addEventListener('click', function () {
  document.getElementById('flowTitle').textContent = '';
  document.getElementById('flowSub').textContent = '';
  flowChart.innerHTML = buildInsights();
  bringToFront(flowPanel);
});

// ---------------------------------------------------------------------------
// External systems panel.
//
// The map can only show what the hub reports, and the hub does not know that
// CoCoHue needs a Hue bridge. That has to be declared. This is where.
//
// Every app type is listed, not only the unclassified ones, because the value
// is as much in correcting a wrong classification as in filling a gap - Kasa
// and Tapo can each be local or cloud depending on how they were set up.
// ---------------------------------------------------------------------------
// Same reasoning as amPickURL() on the config page's Scan button: a relative
// path only resolves correctly when this page itself is being served from
// the hub's own origin, which is not true through Remote Admin.
function amPickURL(localPath, cloudUrl) {
  try {
    if (new URL('${getLocalOrigin()}').hostname === window.location.hostname) return localPath;
  } catch (ignore) { }
  return cloudUrl;
}
const EXT_URL = amPickURL('${getLocalURL('externals')}', '${getCloudURL('externals')}');
const extPanel = document.getElementById('ext');
const extBody = document.getElementById('extBody');
let EXT = null;

const pivotPanel = document.getElementById('pivot');
const pivotBody = document.getElementById('pivotBody');

// Keeps the three selects consistent with each other after any change: the
// columns list depends on which row type is chosen, and the relationship
// list depends on both. Called with the values that SHOULD be selected once
// this returns - a caller does not need to know which combinations are valid,
// only what they are trying to show.
function pivotSyncSelects(rowGroup, colGroup, kindVal) {
  const rowsSel = document.getElementById('pivotRows');
  const colsSel = document.getElementById('pivotCols');
  const kindSel = document.getElementById('pivotKind');

  if (!rowsSel.options.length) {
    ['app', 'device', 'external'].forEach(function (g) {
      const o = document.createElement('option'); o.value = g; o.textContent = GROUP_LABEL[g]; rowsSel.appendChild(o);
    });
  }
  rowsSel.value = rowGroup;

  const validCols = pivotColOptions(rowGroup);
  colsSel.innerHTML = '';
  validCols.forEach(function (g) {
    const o = document.createElement('option'); o.value = g; o.textContent = GROUP_LABEL[g]; colsSel.appendChild(o);
  });
  colsSel.value = validCols.indexOf(colGroup) !== -1 ? colGroup : validCols[0];

  const kinds = pivotKindOptions(rowGroup, colsSel.value);
  kindSel.innerHTML = '<option value="__all__">All</option>';
  kinds.forEach(function (k) {
    const o = document.createElement('option'); o.value = k; o.textContent = KIND_LABEL[k] || k; kindSel.appendChild(o);
  });
  kindSel.value = kindVal && kinds.indexOf(kindVal) !== -1 ? kindVal : '__all__';
}

// Clicking through a pivot result behaves like every other click-through on
// this map: leave this panel, land on the app or device just clicked.
function pivotWireLinks() {
  document.querySelectorAll('#pivotResult a[data-node]').forEach(function (a) {
    a.addEventListener('click', function (ev) {
      ev.preventDefault();
      pivotPanel.style.display = 'none';
      syncLegendVisibility();
      focusNode(a.getAttribute('data-node'));
    });
  });
}

// The one path both a preset click and a builder change render through, so
// there is exactly one place that knows what is currently on screen - which
// is what Export CSV downloads. Without this, export would need its own copy
// of "what was rendered last", kept in step with two separate call sites by
// hand.
let CURRENT_PIVOT = null;
function pivotRenderResult(pivot, rowLabel, colLabel) {
  CURRENT_PIVOT = { pivot: pivot, rowLabel: rowLabel, colLabel: colLabel };
  document.getElementById('pivotResult').innerHTML = renderPivotTable(pivot, rowLabel, colLabel);
  pivotWireLinks();
  const exportBtn = document.getElementById('pivotExport');
  if (exportBtn) exportBtn.style.display = pivot.rows.length ? 'inline-block' : 'none';
}

function pivotRunCustom() {
  const rowsSel = document.getElementById('pivotRows');
  const colsSel = document.getElementById('pivotCols');
  const kindSel = document.getElementById('pivotKind');
  pivotSyncSelects(rowsSel.value, colsSel.value, kindSel.value);
  const rowGroup = rowsSel.value, colGroup = colsSel.value, kindVal = kindSel.value;
  const kinds = kindVal === '__all__' ? pivotKindOptions(rowGroup, colGroup) : [kindVal];
  pivotRenderResult(pivotRows(rowGroup, colGroup, kinds), GROUP_LABEL[rowGroup], GROUP_LABEL[colGroup]);
}

// Rebuilt in full on every open rather than kept alive in the background -
// this panel only reads what is already in ALL_NODES/ALL_EDGES, so there is
// nothing stale to refresh, and rebuilding is simpler than tracking whether
// the shell was already there from a previous open this page load.
function pivotOpen() {
  pivotBody.innerHTML =
    '<h3>Pivot tables</h3>' +
    '<p class="sub">Cross-reference what is already on the map - presets on the left, or build your own on the right. Both read the same relationships already drawn, so nothing here re-scans the hub.</p>' +
    '<div style="display:flex; justify-content:space-between; align-items:flex-start; flex-wrap:wrap; gap:14px; margin-bottom:14px">' +
    '<div>' + PIVOT_PRESETS.map(function (p, i) {
      return '<button type="button" class="rowbtn" data-preset="' + i + '">' + p.button + '</button>';
    }).join('') + '</div>' +
    '<div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap">' +
    '<label>Rows <select id="pivotRows"></select></label>' +
    '<label>Columns <select id="pivotCols"></select></label>' +
    '<label>Relationship <select id="pivotKind"></select></label>' +
    '<button type="button" id="pivotExport" class="rowbtn" style="display:none">Export CSV</button>' +
    '</div></div>' +
    '<div id="pivotResult"></div>';

  document.querySelectorAll('#pivotBody button[data-preset]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      const p = PIVOT_PRESETS[parseInt(btn.getAttribute('data-preset'), 10)];
      pivotSyncSelects(p.rows, p.cols, '__all__');
      pivotRenderResult(pivotRows(p.rows, p.cols, p.kinds, p.opts), p.rowLabel, p.colLabel);
    });
  });
  ['pivotRows', 'pivotCols', 'pivotKind'].forEach(function (id) {
    document.getElementById(id).addEventListener('change', pivotRunCustom);
  });
  document.getElementById('pivotExport').addEventListener('click', function () {
    if (CURRENT_PIVOT) pivotDownloadCSV(CURRENT_PIVOT.pivot, CURRENT_PIVOT.rowLabel, CURRENT_PIVOT.colLabel);
  });

  // Opens on the first preset so the panel shows something immediately,
  // rather than an empty shell the first click has to fill in.
  document.querySelector('#pivotBody button[data-preset="0"]').click();
}

function extEsc(s) {
  return String(s === null || s === undefined ? '' : s)
    .split('&').join('&amp;').split('<').join('&lt;')
    .split('>').join('&gt;').split('"').join('&quot;');
}

function extLoad() {
  extBody.innerHTML = '<h3>External systems</h3><p class="sub">Loading...</p>';
  fetch(EXT_URL, { cache: 'no-store', credentials: 'omit' })
    .then(function (r) { return r.json(); })
    .then(function (d) { EXT = d; extRender(''); })
    .catch(function (e) {
      extBody.innerHTML = '<h3>External systems</h3><p class="sub">Could not load: ' + extEsc(e) + '</p>';
    });
}

function extRowsFor(type) {
  return (EXT.entries || []).filter(function (e) { return e.type === type; });
}

// Rows the shared registry supplied, shown only where the user has said
// nothing about that app type. The moment they do, theirs replaces these.
function extRegistryFor(type) {
  const claimed = (EXT.entries || []).some(function (e) { return e.type === type; });
  if (claimed) return [];
  return (EXT.registry || []).filter(function (e) { return e.type === type; });
}

function extRender(message) {
  const kinds = EXT.kinds || {};
  const crits = EXT.criticality || {};
  const none = EXT.noneMarker;

  let h = '<h3>External systems</h3>';
  h += '<p class="sub">What each app needs <b>outside</b> your hub. The hub cannot detect this, so it is declared here and drawn on the map as a diamond with a dashed line. ' +
       'Apps sharing a system share one node, which is what makes it possible to ask what breaks if that system goes down.</p>';

  h += '<table><thead><tr><th>App type</th><th>Needs</th><th>Kind</th><th>Needed for</th><th></th></tr></thead><tbody>';

  (EXT.appTypes || []).forEach(function (type) {
    const rows = extRowsFor(type);
    if (!rows.length) {
      const fromRegistry = extRegistryFor(type);
      if (fromRegistry.length) {
        fromRegistry.forEach(function (r, i) {
          h += '<tr class="fromreg"><td>' + (i === 0 ? extEsc(type) : '') + '</td>' +
               '<td>' + extEsc(r.name) + '</td>' +
               '<td>' + extEsc(kinds[r.kind] || r.kind) + '</td>' +
               '<td>' + extEsc(crits[r.crit] || r.crit) + '</td>' +
               '<td>' + (i === 0 ? '<span class="tag tag-reg">from registry</span>' +
                                   '<button class="rowbtn" data-over="' + extEsc(type) + '">override</button>' : '') +
               '</td></tr>';
        });
        return;
      }
      h += '<tr class="unclassified"><td>' + extEsc(type) + '</td>' +
           '<td colspan="3"><span class="tag tag-unset">not classified</span></td>' +
           '<td><button class="rowbtn" data-add="' + extEsc(type) + '">add</button>' +
           '<button class="rowbtn" data-none="' + extEsc(type) + '">needs nothing</button></td></tr>';
      return;
    }
    rows.forEach(function (row, i) {
      const isNone = (row.name === none);
      h += '<tr><td>' + (i === 0 ? extEsc(type) : '') + '</td>';
      if (isNone) {
        h += '<td colspan="3"><span class="tag tag-none">nothing external needed</span></td>';
      } else {
        h += '<td><input type="text" data-f="name" data-t="' + extEsc(type) + '" data-i="' + i + '" value="' + extEsc(row.name) + '"></td>';
        h += '<td><select data-f="kind" data-t="' + extEsc(type) + '" data-i="' + i + '">';
        Object.keys(kinds).forEach(function (k) {
          h += '<option value="' + k + '"' + (k === row.kind ? ' selected' : '') + '>' + extEsc(kinds[k]) + '</option>';
        });
        h += '</select></td>';
        h += '<td><select data-f="crit" data-t="' + extEsc(type) + '" data-i="' + i + '">';
        Object.keys(crits).forEach(function (c) {
          h += '<option value="' + c + '"' + (c === row.crit ? ' selected' : '') + '>' + extEsc(crits[c]) + '</option>';
        });
        h += '</select></td>';
      }
      h += '<td><button class="rowbtn" data-del="' + extEsc(type) + '" data-i="' + i + '">remove</button>';
      if (i === rows.length - 1 && !isNone) {
        h += '<button class="rowbtn" data-add="' + extEsc(type) + '">add</button>';
      }
      h += '</td></tr>';
    });
  });
  h += '</tbody></table>';

  h += '<div class="bar">' +
       '<button id="extSave" type="button">Save</button>' +
       '<button id="extExport" type="button">Download backup</button>' +
       '<button id="extImport" type="button">Restore from file</button>' +
       '<input type="file" id="extFile" accept="application/json" style="display:none">' +
       '<span class="msg" id="extMsg">' + extEsc(message) + '</span></div>';
  const rm = EXT.registryMeta || {};
  let reg = '';
  const rs = rm.state ? String(rm.state) : '';
  if (rm.fetched && !rm.error) {
    reg = 'Shared registry: ' + extEsc(rm.matched) + ' match(es) from ' + extEsc(rm.entries) +
          ' entries, fetched ' + extEsc(rm.fetched) + '. Yours always wins.';
  } else if (rm.error) {
    // Tried and failed. Distinct from never having tried, which is what this
    // said before and was actively misleading to anyone who had just scanned.
    reg = 'Shared registry could not be read (' + extEsc(rm.error) + '). Re-scan to retry. ' +
          'Your own declarations are unaffected.';
  } else if (rs === 'PENDING') {
    reg = 'Shared registry is being read now. Re-open this page in a moment.';
  } else {
    reg = 'Shared registry not fetched yet. It is read during a scan.';
  }
  h += '<p class="sub" style="margin-top:10px">' + reg + '<br>' +
       'Your declarations live with this app. Removing the app removes them, so download a backup before you do.</p>';

  extBody.innerHTML = h;
  extWire();
}

function extWire() {
  extBody.querySelectorAll('input[data-f], select[data-f]').forEach(function (el) {
    el.addEventListener('change', function () {
      const rows = extRowsFor(el.getAttribute('data-t'));
      const row = rows[parseInt(el.getAttribute('data-i'), 10)];
      if (!row) return;
      const field = el.getAttribute('data-f');
      // Trimmed here, not only on save. The server trims too, so without this
      // a downloaded backup could carry "Hue Bridge " while the hub held
      // "Hue Bridge", and restoring it would build a different node.
      const value = (field === 'name') ? el.value.trim() : el.value;
      if (field === 'name' && el.value !== value) el.value = value;
      row[field] = value;
    });
  });

  extBody.querySelectorAll('[data-add]').forEach(function (b) {
    b.addEventListener('click', function () {
      const type = b.getAttribute('data-add');
      EXT.entries = (EXT.entries || []).filter(function (e) {
        return !(e.type === type && e.name === EXT.noneMarker);
      });
      EXT.entries.push({ type: type, name: '', kind: 'internet', crit: 'RUNTIME' });
      extRender('');
    });
  });

  // Overriding seeds the user's rows from the registry's, so correcting one
  // value does not mean retyping the rest.
  extBody.querySelectorAll('[data-over]').forEach(function (b) {
    b.addEventListener('click', function () {
      const type = b.getAttribute('data-over');
      (EXT.registry || []).filter(function (e) { return e.type === type; })
        .forEach(function (r) {
          EXT.entries.push({ type: type, name: r.name, kind: r.kind, crit: r.crit });
        });
      extRender('Copied from the registry. Edit and Save, and yours will be used instead.');
    });
  });

  extBody.querySelectorAll('[data-none]').forEach(function (b) {
    b.addEventListener('click', function () {
      const type = b.getAttribute('data-none');
      EXT.entries = (EXT.entries || []).filter(function (e) { return e.type !== type; });
      EXT.entries.push({ type: type, name: EXT.noneMarker });
      extRender('');
    });
  });

  extBody.querySelectorAll('[data-del]').forEach(function (b) {
    b.addEventListener('click', function () {
      const type = b.getAttribute('data-del');
      const idx = parseInt(b.getAttribute('data-i'), 10);
      const rows = extRowsFor(type);
      const target = rows[idx];
      EXT.entries = (EXT.entries || []).filter(function (e) { return e !== target; });
      extRender('');
    });
  });

  document.getElementById('extSave').addEventListener('click', extSave);
  document.getElementById('extExport').addEventListener('click', extExport);
  document.getElementById('extImport').addEventListener('click', function () {
    document.getElementById('extFile').click();
  });
  document.getElementById('extFile').addEventListener('change', extImport);
}

function extSave() {
  const rows = (EXT.entries || []).filter(function (e) {
    return e.name && String(e.name).trim() !== '';
  });
  const msg = document.getElementById('extMsg');
  msg.textContent = 'Saving...';
  fetch(EXT_URL, {
    method: 'POST', cache: 'no-store', credentials: 'omit',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ entries: rows })
  }).then(function (r) { return r.json(); })
    .then(function (d) {
      EXT = d;
      extRender('Saved. Reload the page to redraw the map.');
    })
    .catch(function (e) { msg.textContent = 'Save failed: ' + e; });
}

// Plain browser download. No hub involvement, so nothing to go wrong on an
// older platform, and the file lands wherever the user's downloads go.
function extExport() {
  // Normalised on the way out as well, so a backup taken with unsaved edits on
  // screen still restores to exactly what the hub would have stored.
  const clean = (EXT.entries || []).map(function (e) {
    const row = { type: String(e.type).trim(), name: String(e.name).trim() };
    if (row.name !== EXT.noneMarker) { row.kind = e.kind; row.crit = e.crit; }
    return row;
  }).filter(function (e) { return e.type && e.name; });

  const payload = {
    kind: 'automation-map-external-systems',
    version: 1,
    exported: new Date().toISOString(),
    entries: clean
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'automation-map-external-systems.json';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
  document.getElementById('extMsg').textContent = 'Downloaded.';
}

function extImport(evt) {
  const file = evt.target.files && evt.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = function () {
    let parsed = null;
    try { parsed = JSON.parse(reader.result); }
    catch (e) { document.getElementById('extMsg').textContent = 'That file is not valid JSON.'; return; }
    const rows = parsed && parsed.entries ? parsed.entries : (Array.isArray(parsed) ? parsed : null);
    if (!rows) { document.getElementById('extMsg').textContent = 'No entries found in that file.'; return; }
    EXT.entries = rows;
    extRender('Loaded ' + rows.length + ' entries from the file. Press Save to keep them.');
  };
  reader.readAsText(file);
  evt.target.value = '';
}

// Device icons panel.
//
// Icons are auto-detected from capability (ICON_RULES/autoDetectIconKey in
// the Groovy source), and a heuristic run over ~200 devices of wildly
// different drivers will occasionally pick the wrong one for a specific
// device. This is where that gets corrected - one override per device,
// saved here, applied the next time the graph is built.
const ICONS_URL = amPickURL('${getLocalURL('icon-overrides')}', '${getCloudURL('icon-overrides')}');
const iconsPanel = document.getElementById('icons');
const iconsBody = document.getElementById('iconsBody');
let ICONS = null;

function iconsLoad() {
  iconsBody.innerHTML = '<h3>Device icons</h3><p class="sub">Loading...</p>';
  fetch(ICONS_URL, { cache: 'no-store', credentials: 'omit' })
    .then(function (r) { return r.json(); })
    .then(function (d) { ICONS = d; iconsRender(''); })
    .catch(function (e) {
      iconsBody.innerHTML = '<h3>Device icons</h3><p class="sub">Could not load: ' + extEsc(e) + '</p>';
    });
}

function iconsEffectiveKey(d) {
  return (d.override && d.override !== 'auto') ? d.override : d.detected;
}

function iconsRender(message, filter) {
  const labels = ICONS.iconLabels || {};
  const term = (filter || '').toLowerCase();
  // ICONS.iconKeys is in detection-priority order (most specific capability
  // checked first) - correct for autoDetectIconKey, meaningless for a
  // human scanning a dropdown by eye. Sorted once here, by label, for
  // every row's <select> below.
  const sortedIconKeys = (ICONS.iconKeys || []).slice().sort(function (a, b) {
    return (labels[a] || a).localeCompare(labels[b] || b);
  });

  let h = '<h3>Device icons</h3>';
  h += '<p class="sub">Each device is drawn with an icon guessed from its capabilities - a light looks like a ' +
       'light, an unrecognised one gets a "?". Wrong for a particular device? Pick the right one below and Save. ' +
       'Left as "?"? Add a note so you remember what it actually is - it also appears in the tooltip for that ' +
       'device on the map. Reload the map page afterwards to see it redrawn.</p>';
  h += '<input type="search" id="iconsSearch" placeholder="Search devices or rooms..." value="' + extEsc(filter || '') + '">';
  h += '<table><thead><tr><th>Device</th><th>Room</th><th>Detected</th><th>Icon</th><th>Note (if unknown)</th></tr></thead><tbody>';

  const devices = (ICONS.devices || []).filter(function (d) {
    if (!term) return true;
    return (d.name || '').toLowerCase().indexOf(term) !== -1 || (d.room || '').toLowerCase().indexOf(term) !== -1;
  });

  devices.forEach(function (d) {
    const isOverridden = d.override && d.override !== 'auto';
    const isUnknown = iconsEffectiveKey(d) === 'unknown';
    h += '<tr' + (isOverridden ? ' class="overridden"' : '') + '>';
    h += '<td>' + extEsc(d.name) + '</td>';
    h += '<td>' + extEsc(d.room) + '</td>';
    h += '<td>' + extEsc(labels[d.detected] || d.detected) + '</td>';
    h += '<td><select data-dev="' + extEsc(d.id) + '">';
    h += '<option value="auto"' + (!isOverridden ? ' selected' : '') + '>Auto (' + extEsc(labels[d.detected] || d.detected) + ')</option>';
    sortedIconKeys.forEach(function (k) {
      h += '<option value="' + k + '"' + (d.override === k ? ' selected' : '') + '>' + extEsc(labels[k] || k) + '</option>';
    });
    h += '</select></td>';
    // The input always exists (so a note typed just before switching a
    // device to "unknown" is not lost), just hidden when not relevant -
    // matches how the override dropdown itself is always present.
    h += '<td><input type="text" maxlength="200" data-note="' + extEsc(d.id) + '" placeholder="What is this?" ' +
         'value="' + extEsc(d.note || '') + '" style="' + (isUnknown ? '' : 'display:none') + '"></td>';
    h += '</tr>';
  });

  h += '</tbody></table>';
  h += '<div class="bar"><button id="iconsSave" type="button">Save</button>' +
       '<button id="iconsExport" type="button">Download backup</button>' +
       '<button id="iconsImport" type="button">Restore from file</button>' +
       '<input type="file" id="iconsFile" accept="application/json" style="display:none">' +
       '<span class="msg" id="iconsMsg">' + extEsc(message || '') + '</span></div>';
  h += '<p class="sub" style="margin-top:10px">Your overrides and notes live with this app. Removing the app ' +
       'removes them, so download a backup before you do.</p>';

  iconsBody.innerHTML = h;
  iconsWire();
}

function iconsWire() {
  const search = document.getElementById('iconsSearch');
  search.addEventListener('input', function () { iconsRender('', search.value); });
  // Restores focus and cursor position after the re-render typing itself
  // triggers - without this every keystroke reset focus to the top of the
  // panel, making the search box unusable.
  search.focus();
  search.setSelectionRange(search.value.length, search.value.length);

  iconsBody.querySelectorAll('select[data-dev]').forEach(function (sel) {
    sel.addEventListener('change', function () {
      const dev = (ICONS.devices || []).find(function (d) { return d.id === sel.getAttribute('data-dev'); });
      if (!dev) return;
      dev.override = sel.value;
      const row = sel.closest('tr');
      if (row) {
        row.classList.toggle('overridden', sel.value !== 'auto');
        const noteInput = row.querySelector('input[data-note]');
        if (noteInput) noteInput.style.display = (iconsEffectiveKey(dev) === 'unknown') ? '' : 'none';
      }
    });
  });

  iconsBody.querySelectorAll('input[data-note]').forEach(function (inp) {
    inp.addEventListener('input', function () {
      const dev = (ICONS.devices || []).find(function (d) { return d.id === inp.getAttribute('data-note'); });
      if (dev) dev.note = inp.value;
    });
  });

  document.getElementById('iconsSave').addEventListener('click', iconsSave);
  document.getElementById('iconsExport').addEventListener('click', iconsExport);
  document.getElementById('iconsImport').addEventListener('click', function () {
    document.getElementById('iconsFile').click();
  });
  document.getElementById('iconsFile').addEventListener('change', iconsImportFile);
}

function iconsSave() {
  // Only the actual corrections/notes are sent - a device left on "Auto"
  // with no note carries no entry at all, so autoDetectIconKey keeps
  // deciding it as capabilities change on a future rescan rather than
  // freezing it at today's guess.
  const overrides = {};
  const notes = {};
  (ICONS.devices || []).forEach(function (d) {
    if (d.override && d.override !== 'auto') overrides[d.id] = d.override;
    if (d.note && d.note.trim()) notes[d.id] = d.note.trim();
  });
  const msg = document.getElementById('iconsMsg');
  msg.textContent = 'Saving...';
  fetch(ICONS_URL, {
    method: 'POST', cache: 'no-store', credentials: 'omit',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ overrides: overrides, notes: notes })
  }).then(function (r) { return r.json(); })
    .then(function (d) {
      ICONS = d;
      iconsRender('Saved. Reload the page to redraw the map.');
    })
    .catch(function (e) { msg.textContent = 'Save failed: ' + e; });
}

// Same local-file pattern as the External systems panel's backup/restore -
// no hub involvement, so nothing to go wrong on an older platform.
function iconsExport() {
  const overrides = {};
  (ICONS.devices || []).forEach(function (d) {
    if ((d.override && d.override !== 'auto') || (d.note && d.note.trim())) {
      const entry = { name: d.name };
      if (d.override && d.override !== 'auto') entry.icon = d.override;
      if (d.note && d.note.trim()) entry.note = d.note.trim();
      overrides[d.id] = entry;
    }
  });
  const payload = {
    kind: 'automation-map-device-icons',
    version: 1,
    exported: new Date().toISOString(),
    overrides: overrides
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'automation-map-device-icons.json';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
  document.getElementById('iconsMsg').textContent = 'Downloaded.';
}

function iconsImportFile(evt) {
  const file = evt.target.files && evt.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = function () {
    let parsed = null;
    try { parsed = JSON.parse(reader.result); }
    catch (e) { document.getElementById('iconsMsg').textContent = 'That file is not valid JSON.'; return; }
    const overrides = parsed && parsed.overrides ? parsed.overrides : null;
    if (!overrides) { document.getElementById('iconsMsg').textContent = 'No overrides found in that file.'; return; }
    // Matched by device id, same as the rest of this app keys devices - an
    // id from a device since removed is silently skipped rather than erroring.
    let applied = 0;
    (ICONS.devices || []).forEach(function (d) {
      const entry = overrides[d.id];
      if (!entry) return;
      if (entry.icon && (ICONS.iconKeys || []).indexOf(entry.icon) !== -1) { d.override = entry.icon; applied++; }
      if (entry.note) { d.note = String(entry.note).substring(0, 200); applied++; }
    });
    iconsRender('Loaded ' + applied + ' entr' + (applied === 1 ? 'y' : 'ies') + ' from the file. Press Save to keep them.');
  };
  reader.readAsText(file);
  evt.target.value = '';
}

// Whole-hub export as one JSON file, for an AI or other external tool to
// read - not a panel, a direct download, same pattern as the backup
// buttons elsewhere on this page. External systems and Device icon data
// are fetched fresh here (cheap GETs, the same endpoints those panels
// already use) rather than relying on whichever panel the user happens to
// have already opened this session.
function exportJSON() {
  const btn = document.getElementById('exportBtn');
  const original = btn.textContent;
  btn.textContent = 'Exporting...';
  btn.disabled = true;

  // null is ambiguous on its own - it is what a genuinely empty response and
  // a failed fetch both collapse to. failedFetches keeps the two apart so
  // the exported file can say outright that a piece of it may be missing,
  // rather than a consumer wrongly reading null as "nothing declared".
  const failedFetches = [];
  Promise.all([
    fetch(EXT_URL, { cache: 'no-store', credentials: 'omit' }).then(function (r) { return r.json(); }).catch(function () { failedFetches.push('externalSystemDeclarations'); return null; }),
    fetch(ICONS_URL, { cache: 'no-store', credentials: 'omit' }).then(function (r) { return r.json(); }).catch(function () { failedFetches.push('deviceIconOverrides'); return null; })
  ]).then(function (results) {
    const blob = new Blob([JSON.stringify(buildExportPayload(results[0], results[1], failedFetches), null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'automation-map-export-' + new Date().toISOString().slice(0, 10) + '.json';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }).catch(function (e) {
    alert('Export failed: ' + e);
  }).finally(function () {
    btn.textContent = original;
    btn.disabled = false;
  });
}

// A ref is {id, name} everywhere in this export, never a bare name and
// never a bare id - v1 used display names alone to link records together
// and that turned out to be a real, not theoretical, ambiguity: this hub
// has two apps both named "_Testy import (Rule-5.1)" (one is a clone of
// the other) and two named "Rule-5.1 (child of Rule Machine)". A name-only
// edge or a ruleFlows object keyed by name cannot tell those apart, and
// for ruleFlows it is worse than ambiguous - a second same-named rule's
// flow silently overwrites the first's, because a JS object can only hold
// one property of a given key. v2 fixes both: every edge carries fromId/
// toId alongside the display names, and ruleFlows is an array of
// {appId, appName, ...} records instead of an object keyed by name.
function ref(id, nameOf) { return { id: id, name: nameOf[id] || id }; }

// Same underlying facts as buildInsights() above, computed independently
// as plain data rather than reusing it directly - buildInsights() returns
// a rendered HTML string for the panel, which is the wrong shape to
// embed in a JSON file meant to be parsed, not displayed.
function buildExportPayload(ext, icons, failedFetches) {
  const nodeById = {};
  ALL_NODES.forEach(function (n) { nodeById[n.id] = n; });
  // n.draw is the stable identity with no live-status suffix baked in
  // (n.title is "Mode Alarm Reminder (Required Expression false) (Rule-5.1)",
  // n.draw is "Mode Alarm Reminder (Rule-5.1)" - the status is exposed
  // separately as apps[].status instead). Falls back to title for any
  // graph cached before draw existed.
  const nameOf = {};
  ALL_NODES.forEach(function (n) { nameOf[n.id] = n.draw || n.title; });

  const flowIds = {};
  Object.keys(GRAPH.flows || {}).forEach(function (id) { flowIds[id] = true; });

  // Keyed with the same 'd' prefix the graph itself uses (n.id is "d533",
  // not "533") - the icon-overrides endpoint returns bare Hubitat device
  // ids, a different convention from the graph node ids used everywhere
  // else in this export. Found live: without this, every device's room/
  // capabilities came back null, silently, because the lookup key never
  // matched anything.
  const iconById = {};
  (icons && icons.devices || []).forEach(function (d) { iconById['d' + d.id] = d; });

  const missingIds = {};
  ALL_NODES.forEach(function (n) { if (n.missing) missingIds[n.id] = true; });
  const referencesTo = {};
  ALL_EDGES.forEach(function (e) {
    if (!missingIds[e.to]) return;
    if (!referencesTo[e.to]) referencesTo[e.to] = [];
    if (referencesTo[e.to].indexOf(e.from) < 0) referencesTo[e.to].push(e.from);
  });

  const commanders = {};
  const touched = {};
  ALL_EDGES.forEach(function (e) {
    touched[e.to] = true;
    if (e.kind === 'action' && e.stateful) {
      if (!commanders[e.to]) commanders[e.to] = [];
      if (commanders[e.to].indexOf(e.from) < 0) commanders[e.to].push(e.from);
    }
  });
  const contested = Object.keys(commanders).filter(function (d) { return commanders[d].length > 1; })
    .sort(function (a, b) { return commanders[b].length - commanders[a].length; })
    .map(function (d) {
      return { device: ref(d, nameOf), commandedBy: commanders[d].map(function (a) { return ref(a, nameOf); }) };
    });
  const unreferencedDevices = ALL_NODES.filter(function (n) { return n.group === 'device' && !touched[n.id]; })
    .map(function (n) { return ref(n.id, nameOf); });
  const inertApps = ALL_NODES.filter(function (n) { return n.inert; })
    .map(function (n) { return { app: ref(n.id, nameOf), reason: n.reason || 'no reason recorded' }; });
  const brokenRuleReferences = Object.keys(missingIds).map(function (id) {
    return { target: ref(id, nameOf), referencedBy: (referencesTo[id] || []).map(function (a) { return ref(a, nameOf); }) };
  });

  const devices = ALL_NODES.filter(function (n) { return n.group === 'device'; }).map(function (n) {
    const ic = iconById[n.id];
    return {
      id: n.id, name: nameOf[n.id],
      room: ic ? ic.room : null,
      iconCategory: n.icon || 'unknown',
      capabilities: ic ? ic.capabilities : null
    };
  });
  const apps = ALL_NODES.filter(function (n) { return n.group === 'app'; }).map(function (n) {
    return {
      id: n.id, name: nameOf[n.id], appType: n.appType || null,
      status: n.missing ? 'deleted-but-referenced' : n.unreadable ? 'unreadable' : n.inactive ? 'paused-or-disabled' :
        n.unscanned ? 'unscanned' : n.inert ? 'inert' : 'active',
      parentId: n.parent || null,
      childIds: n.kids || [],
      hasDecodedFlow: !!flowIds[n.id]
    };
  });
  const externalSystems = ALL_NODES.filter(function (n) { return n.group === 'external'; }).map(function (n) {
    return { id: n.id, name: nameOf[n.id], kind: n.kindKey || null };
  });
  const hubVariables = ALL_NODES.filter(function (n) { return n.group === 'hubVariable'; }).map(function (n) {
    return { id: n.id, name: nameOf[n.id] };
  });
  const edges = ALL_EDGES.map(function (e) {
    return {
      fromId: e.from, fromName: nameOf[e.from] || e.from,
      toId: e.to, toName: nameOf[e.to] || e.to,
      relationship: e.kind,
      // Only meaningful for 'action' edges (can this app leave the device in
      // a lasting state, versus a momentary command) - null rather than
      // false everywhere else, so it does not look like a real "no" for a
      // relationship kind the field was never about.
      stateful: e.kind === 'action' ? !!e.stateful : null
    };
  });
  // Flow steps' own "devices" field is really a display list, not always
  // literally devices - a Cancel Timed Actions/Run Rule Actions step
  // carries the target RULE's name in the same field, and VRB's "This
  // Rule" self-reference sentinel can appear too. Resolving all of it
  // against one combined device+app name index, rather than assuming
  // "devices" only ever contains devices, is what the flow-decoder itself
  // already effectively does for display; this does the same resolution
  // explicitly, as data, name collisions included - a name matching more
  // than one node comes back "ambiguous" rather than silently picking one,
  // the same discipline this app already applies to every other name-based
  // decision.
  const deviceIdsByName = {};
  const appIdsByName = {};
  ALL_NODES.forEach(function (n) {
    const nm = nameOf[n.id];
    const bucket = n.group === 'device' ? deviceIdsByName : (n.group === 'app' ? appIdsByName : null);
    if (!bucket) return;
    if (!bucket[nm]) bucket[nm] = [];
    bucket[nm].push(n.id);
  });
  function resolveFlowReference(name, ownerAppId) {
    if (name === 'This Rule') return { type: 'self', id: ownerAppId, name: nameOf[ownerAppId] || 'This Rule' };
    const devIds = deviceIdsByName[name] || [];
    const appIds = appIdsByName[name] || [];
    if (devIds.length === 1 && appIds.length === 0) return { type: 'device', id: devIds[0], name: name };
    if (appIds.length === 1 && devIds.length === 0) return { type: 'app', id: appIds[0], name: name };
    if (devIds.length + appIds.length > 1) {
      return { type: 'ambiguous', id: null, name: name, candidateIds: devIds.concat(appIds) };
    }
    return { type: 'unresolved', id: null, name: name };
  }

  const ruleFlows = Object.keys(GRAPH.flows || {}).map(function (appId) {
    const n = nodeById[appId];
    const steps = (GRAPH.flows[appId] || []).map(function (step) {
      const out = {};
      Object.keys(step).forEach(function (k) { if (k !== 'devices') out[k] = step[k]; });
      out.references = Array.isArray(step.devices)
        ? step.devices.map(function (nm) { return resolveFlowReference(nm, appId); }) : [];
      // ruleTargets are Rule Machine's own app-id-only setting values (no
      // "a" prefix stored at that layer) - always resolvable, never
      // ambiguous, so these get a plain {id,name} rather than the
      // device/app/self/ambiguous/unresolved typing references above need.
      if (Array.isArray(step.ruleTargets)) {
        out.ruleTargets = step.ruleTargets.map(function (t) {
          const targetId = 'a' + t;
          return { id: targetId, name: nameOf[targetId] || null };
        });
      }
      return out;
    });
    return { appId: appId, appName: nameOf[appId] || appId, engine: n ? (n.appType || null) : null, steps: steps };
  });

  // Was a plain boolean, !scanError - technically correct but misleadingly
  // narrow: a scan with no top-level error can still have silently dropped
  // individual devices or apps that failed to read (see
  // deviceIdsUnreadable/appsUnreadable tracking added server-side).
  // "complete" now specifically means neither happened, not just that
  // nothing threw at the top level.
  const scanStatus = SCAN_META.scanError ? 'failed'
    : (SCAN_META.appsUnreadable > 0 || SCAN_META.devicesUnreadable > 0) ? 'complete-with-gaps'
    : 'complete';
  const summary = {
    deviceCount: devices.length,
    appCount: apps.length,
    externalSystemCount: externalSystems.length,
    hubVariableCount: hubVariables.length,
    edgeCount: edges.length,
    decodedRuleFlowCount: ruleFlows.length,
    contestedDeviceCount: contested.length,
    unreferencedDeviceCount: unreferencedDevices.length,
    inertAppCount: inertApps.length,
    brokenRuleReferenceCount: brokenRuleReferences.length
  };
  // What "apps[].hasDecodedFlow: false" can mean beyond "not a rule at
  // all" - named once here rather than only in the schema prose, so a
  // consumer can check membership programmatically instead of parsing
  // English out of the schema block.
  const limitations = [
    'Rules on these engines are never decoded, regardless of hasDecodedFlow: Room Lighting, Basic Rules, Simple Automation, webCoRE. They still appear in devices/apps/edges with their device relationships - only the step-by-step logic in ruleFlows is unavailable for them.',
    'Rule-to-rule edges (relationship: runs/cancelTimedActions/setspb/pauseResume) and Hub Variable read/write edges are read from Rule Machine 5.1 only - a rule on another engine will not produce these even if it does the equivalent thing.',
    'Roles/edges reflect how a device is configured into an app, not what happened at runtime - this is a static configuration snapshot from the last scan (see scan.lastScanCompletedAt), not live state.'
  ];
  // A failed fetch and a genuinely empty response both collapse to the same
  // null/[] shape below - this is the only place that distinction survives,
  // so a consumer reading externalSystemDeclarations/deviceIconOverrides in
  // isolation is told outright rather than misreading empty as confirmed-empty.
  (failedFetches || []).forEach(function (field) {
    limitations.push('Could not reach the hub for ' + field + ' when this file was generated - it is null below, not confirmed empty. Re-run AI friendly export to try again.');
  });

  // Additive field, not a breaking schema change - an older consumer that has
  // never heard of recommendedAiBehaviour simply ignores it (see the Root
  // object rule in the spec doc: unknown fields must be ignored), so this
  // does not bump exportSchemaVersion. Keep this array and
  // "Supporting Docs/ai_export_spec.md" section 15 in sync by hand; nothing
  // enforces that automatically.
  const recommendedAiBehaviour = [
    'Identify the exportSchemaVersion and graphSchemaVersion of this file before interpreting anything else.',
    'Distinguish observed configuration facts from your own inferences, and say which is which.',
    'Cite node IDs alongside names wherever ambiguity could matter - names are not guaranteed unique.',
    'Qualify any conclusion built on a gap: scan.status other than complete, or a ruleFlows reference marked unresolved or ambiguous.',
    'Use edges for topology and ruleFlows for step-by-step rule logic - do not infer logic the export did not report.',
    'Static configuration is not proof of runtime behaviour - do not claim it is.',
    'Do not frame contested devices, inert apps, or any other count as evidence the hub is in a bad state. A hub with dozens of rules and hundreds of devices will always show some of these as a normal by-product of scale - contested devices in particular are usually several ordinary rules sharing one light or switch (motion, time-of-day, manual override), not automations fighting. Avoid adversarial words - fighting, broken as an unqualified judgment, conflict - for anything the export itself does not use that word for; state the plain mechanism instead (the last app to run decides the outcome) and let the user judge whether it is intentional.',
    'State a count in proportion to the whole (e.g. "30 of 194 devices" rather than a bare "30 devices") so the user can judge scale themselves rather than be primed by an isolated number.',
    'Never infer a missing relationship solely because two names look similar.',
    'Open a first response with a short plain-language summary of what was understood - counts plus two or three specific named apps or devices as evidence the file was actually read, not a templated response.',
    'State findings before recommendations, in visibly separate sections.',
    'Surface scan-quality caveats (scan.status, unresolved or ambiguous references) in that opening summary, not after conclusions have already been presented.',
    'When more than one thing is worth pursuing, offer a short menu - two to five options, one line each on why it might matter - and ask which to explore, rather than silently picking one and going deep unprompted.',
    'If the request itself is broad or vague, let that options menu be the first response, rather than guessing scope.',
    'Every option offered must read as investigate or explain, never as an action taken or promised - nothing in this export authorises any change to the hub.'
  ];

  return {
    about: 'Automation Map export - a structured snapshot of every app and device on one Hubitat home automation hub, and how they relate to each other. Generated for an AI or other external tool to read, not for a human to read raw.',
    generatedAt: new Date().toISOString(),
    generatedBy: 'Automation Map v${APP_VERSION}',
    exportSchemaVersion: SCAN_META.exportSchemaVersion,
    graphSchemaVersion: SCAN_META.graphSchemaVersion,
    scan: {
      lastScanCompletedAt: SCAN_META.scanHeartbeatMs ? new Date(SCAN_META.scanHeartbeatMs).toISOString() : null,
      lastScanError: SCAN_META.scanError,
      status: scanStatus,
      appsUnreadable: SCAN_META.appsUnreadable || 0,
      devicesUnreadable: SCAN_META.devicesUnreadable || 0
    },
    summary: summary,
    limitations: limitations,
    recommendedAiBehaviour: recommendedAiBehaviour,
    privacyNote: 'Device, room and app names below reflect a real home. Treat this file with the same care as the underlying device list - review before sharing it outside a trusted context.',
    schema: {
      devices: 'Every device on the hub. iconCategory is a best-guess classification (lighting, doors, water, motion...), "unknown" if nothing matched. capabilities is the raw Hubitat capability list this device reports (what iconCategory was derived from); null if this device was not present in the same fetch that supplied room/capabilities (a scan run since the page loaded, in the rare case one raced this export).',
      apps: 'Every installed app, including every automation rule. status: active | paused-or-disabled | inert (installed but touches nothing) | unscanned (never reached during the scan) | unreadable (hub would not answer for it) | deleted-but-referenced (no longer exists as an app, but another rule still names it - appType is null in this one case, expected, not a decoding gap). parentId/childIds describe container apps (e.g. Button Controllers holding several Button Rules). hasDecodedFlow: true if this app has a matching entry in ruleFlows - false does not mean broken, it usually means the app is not a rule at all (an integration, a service) or is a rule on an engine this app cannot decode (Room Lighting, Basic Rules, Simple Automation, webCoRE).',
      externalSystems: 'Systems outside the hub an app depends on, drawn as nodes on the map - a mix of auto-matched community registry entries and declarations entered by the hub owner (see externalSystemDeclarations below for the raw declarations themselves, which is a different, smaller list - not every declared type becomes a node here, and not every node here came from a declaration).',
      hubVariables: 'Hub-wide variables one or more rules read or write.',
      edges: 'Every relationship between two of the above, referenced by id (fromId/toId) - names are included for readability only and are not guaranteed unique, do not use them to join. relationship meanings - trigger: app listens to this device. constraint: a condition/required expression gates the app on this device. monitor: app reads this device state only, cannot command it. action: app can command this device (see stateful). exposed: published to an external system. owns: app created this device. write/read: a rule sets/reads a Hub Variable. runs/cancelTimedActions/setspb/pauseResume: one rule acting on another rule. depends: an app needs an external system. stateful is only meaningful on action edges - true means the app can leave the device in a lasting on/off/level state, not just a momentary command, and more than one app doing this to the same device means the last one to run decides the outcome (see insights.contested) - common by design on a hub with many rules, not inherently a problem; null on every other relationship kind, where the concept does not apply.',
      ruleFlows: 'One entry per app whose logic could be decoded, an array rather than an object keyed by name because app names on this hub are not guaranteed unique - join on appId. steps is the decoded trigger/condition/action sequence for that rule. cond/label on a step can legitimately be empty - "endif"/"else" control-flow steps exist only to close or branch a block and carry no condition of their own. references replaces what would otherwise be a bare device-name list: each entry is {type, id, name} (plus candidateIds when type is "ambiguous"). type is "device" or "app" (a Cancel Timed Actions/Run Rule Actions-style step names another RULE here, not a device - check type, do not assume), "self" for VRB’s "This Rule" (id is this same step’s own appId), "ambiguous" if the name matches more than one device or app on this hub (id is null, candidateIds lists every match - do not guess which one), or "unresolved" if the name matched nothing at all (id null - typically a stale/renamed reference). ruleTargets (cross-rule action steps only) is {id, name} the same way - always resolvable, an "a"-prefixed app id, never ambiguous.',
      insights: 'Pre-computed findings, every device/app/rule reference given as {id,name} rather than a bare name. contested: devices more than one app can leave in a lasting state, so the last app to run decides the outcome - common and often intentional on a hub with many rules (a motion-triggered rule and a manual-override rule both targeting one light, for example), worth confirming is not accidental, not evidence anything is wrong. unreferencedDevices: nothing on the hub owns, watches or drives them. inertApps: installed but touch no device and link to no rule, with why - very often a container holding other apps, or a schedule-only app, both entirely normal. brokenRuleReferences: a rule still names another rule/action/pause target that no longer exists - the action silently does nothing.',
      scan: 'lastScanCompletedAt is when the data behind this whole export was last refreshed from the hub (not when this file was generated - generatedAt above is that). lastScanError is whatever the app itself reported wrong with that scan, if anything. status is "complete" (nothing failed), "complete-with-gaps" (the scan finished but appsUnreadable and/or devicesUnreadable is above zero - some apps or devices could not be read and are simply missing from this export, not just from ruleFlows), or "failed" (lastScanError is set, the whole scan aborted). appsUnreadable/devicesUnreadable are the counts behind that status - also see apps[].status for which specific apps were affected.',
      summary: 'Plain counts of every array below, for a quick sanity check or a one-line status line - not authoritative over the arrays themselves.',
      limitations: 'Known, structural gaps in what this export can ever contain, independent of any particular hub - read this before concluding a rule is "missing" logic rather than on an engine this app cannot decode.',
      recommendedAiBehaviour: 'How an AI reading this file should behave, in three parts. Epistemic: identify versions, distinguish fact from inference, cite IDs over names, qualify conclusions built on a scan gap or an unresolved/ambiguous reference, never guess a relationship from name similarity alone. Tone: counts like contested devices or inert apps are normal at scale, not evidence of a bad state - avoid adversarial words (fighting, conflict, broken as an unqualified judgment) for anything the export itself does not use that word for, and state a count in proportion to the whole rather than in isolation. Response shape: open with a short plain-language summary naming a few specific apps or devices as evidence the file was actually read, state findings before recommendations, surface scan-quality caveats up front, and when more than one thing is worth pursuing offer it as a short menu and ask which to explore rather than silently picking one - every option offered must read as investigate or explain, never as an action taken or promised, since nothing here authorises any change to the hub.'
    },
    devices: devices,
    apps: apps,
    externalSystems: externalSystems,
    hubVariables: hubVariables,
    edges: edges,
    ruleFlows: ruleFlows,
    insights: {
      contested: contested,
      unreferencedDevices: unreferencedDevices,
      inertApps: inertApps,
      brokenRuleReferences: brokenRuleReferences
    },
    externalSystemDeclarations: ext ? (ext.entries || []) : null,
    deviceIconOverrides: icons ? (icons.devices || [])
      .filter(function (d) { return d.override !== 'auto' || d.note; })
      .map(function (d) { return { deviceId: 'd' + d.id, deviceName: d.name, override: d.override, note: d.note }; }) : null
  };
}

// Legend/hint visibility for these panels is entirely syncLegendVisibility()'s
// job now (see its definition alongside bringToFront) - every button below
// just shows or hides its own panel and lets that call work out what the
// legend and hint should do.
document.getElementById('extBtn').addEventListener('click', function () {
  bringToFront(extPanel);
  extLoad();
});
document.getElementById('extClose').addEventListener('click', function () {
  extPanel.style.display = 'none';
  syncLegendVisibility();
});
document.getElementById('iconsBtn').addEventListener('click', function () {
  bringToFront(iconsPanel);
  iconsLoad();
});
document.getElementById('iconsClose').addEventListener('click', function () {
  iconsPanel.style.display = 'none';
  syncLegendVisibility();
});
document.getElementById('exportBtn').addEventListener('click', exportJSON);
document.getElementById('pivotBtn').addEventListener('click', function () {
  bringToFront(pivotPanel);
  pivotOpen();
});
document.getElementById('pivotClose').addEventListener('click', function () {
  pivotPanel.style.display = 'none';
  syncLegendVisibility();
});

// The whole-hub view is inevitably dense, so say what to do with it rather than
// dropping the user straight into a few hundred nodes with no starting point.
(function () {
  const hint = document.createElement('div');
  hint.id = 'hint';
  hint.innerHTML = '<b>Start here</b><br>' +
    'This is every app and device on your hub at once, so it looks busy - that is expected.<br><br>' +
    '<b>Click any node</b> to drill in, or use the dropdowns above to search by app or device instead. Click a rule and you also get a flowchart of how it works. Click one of its devices to see everything else touching that device.<br><br>' +
    '<b>Other panels:</b> Insights (devices several apps share), External systems, Pivot tables, Device icons.<br><br>' +
    'Take your time to explore.' +
    '<div style="margin-top:12px"><button id="hintClose" type="button">Got it</button></div>';
  document.body.appendChild(hint);
  document.getElementById('hintClose').addEventListener('click', function () { hint.style.display = 'none'; });
})();

const appSelect = fillSelect('appFilter', 'appSearch', 'app', 'All apps');
const deviceSelect = fillSelect('deviceFilter', 'deviceSearch', 'device', 'All devices');

// Clicking a node is the first thing anyone tries, so it drills in: click an
// app to see what it uses, click one of those devices to see everything else
// touching it, and so on. A search filter may have removed the option from the
// dropdown, so put it back before selecting it, otherwise the assignment is
// silently ignored and the click appears to do nothing.
function forceSelect(sel, id, label) {
  if (!sel.querySelector('option[value="' + id + '"]')) {
    const opt = document.createElement('option');
    opt.value = id; opt.textContent = label;
    sel.appendChild(opt);
  }
  sel.value = id;
}

// Browser Back is wired to the map's own focus changes rather than left
// alone. Without it, Back from anywhere in the map leaves the map entirely
// and lands you back on the app page, which is a long way to fall for
// wanting to undo one click.
//
// history.state is the ONLY source of truth for this, not a separate JS
// array. A parallel focusTrail array used to track "where would Back go",
// but every code path that changes focus had to keep it in perfect lockstep
// with the browser's own history stack by hand - Exit/Show all cleared the
// array but never touched the actual history entries, so Back after Exit
// silently did nothing while the browser's real position kept moving
// underneath it, and Forward was never reconstructed at all, because
// popstate always popped the array regardless of which direction the user
// actually navigated. Reading event.state directly instead means Back and
// Forward both work by construction, in either direction, because the
// browser - not a hand-maintained stack - is doing the bookkeeping. Each
// pushed state carries cameFrom as well as amFocus, so the specific "Back to
// X" label survives without needing a second data structure to keep in sync.
let poppingHistory = false;

function focusLabel(id) {
  if (!id) return 'the whole map';
  const n = ALL_NODES.filter(function (x) { return x.id === id; })[0];
  return n ? n.title : 'the whole map';
}

function currentFocus() {
  if (appSelect.value !== '__all__') return appSelect.value;
  if (deviceSelect.value !== '__all__') return deviceSelect.value;
  return null;
}

// Return to the unfiltered whole map in one step, regardless of how many
// levels deep a click-through session has gone. "Back" only ever undoes one
// step at a time, which is right for retracing a path but wrong for
// abandoning it - reported after drilling app -> device -> another app and
// having to click Back three times just to get out.
//
// Shared by the panel's Exit link and the top-right "Show all" button, which
// did this exact reset already; Exit is the same action, reachable from where
// the problem actually is instead of from a button that may be off screen.
function exitToWholeMap() {
  appSelect.value = '__all__';
  deviceSelect.value = '__all__';
  document.getElementById('kindFilter').value = 'all';
  // All four floating panels, not just flowPanel - this only ever closed
  // Insights/flow, so External systems, Pivot tables or Device icons could be
  // left open through a Show all/Exit. Went unnoticed while the legend stayed
  // fully hidden whenever any panel was open; once the collapsed legend was
  // fixed to stay visible through that, the leftover open panel became
  // visible alongside it - reported as "legend expands", but the actual
  // cause is this, not the legend logic itself.
  [flowPanel, extPanel, pivotPanel, iconsPanel].forEach(function (p) { if (p) p.style.display = 'none'; });
  syncLegendVisibility();
  // A real history entry, not just a local reset - so Back afterward returns
  // to wherever Exit was clicked from, correctly, by the same mechanism as
  // every other focus change rather than a special case that used to leave
  // the browser's actual position and the map's idea of it disagreeing.
  if (!poppingHistory) {
    try { history.pushState({ amFocus: null, cameFrom: null }, ''); } catch (e) { }
  }
  renderBackLink();
  applyFilters();
}

function renderBackLink() {
  const bar = document.getElementById('flowBack');
  if (!bar) return;
  if (!currentFocus()) { bar.style.display = 'none'; bar.innerHTML = ''; return; }
  const st = history.state;
  const cameFrom = (st && st.cameFrom !== undefined) ? st.cameFrom : null;
  bar.innerHTML = '<a href="#" id="flowBackLink">&larr; Back to ' + extEsc(focusLabel(cameFrom)) + '</a>' +
    '<a href="#" id="flowExit">Exit to whole map</a>';
  bar.style.display = 'flex';
  document.getElementById('flowBackLink').addEventListener('click', function (ev) {
    ev.preventDefault();
    // Goes through history so the button and browser Back cannot disagree
    // about where the trail is.
    history.back();
  });
  document.getElementById('flowExit').addEventListener('click', function (ev) {
    ev.preventDefault();
    exitToWholeMap();
  });
}

function focusNode(id) {
  const node = ALL_NODES.filter(function (n) { return n.id === id; })[0];
  if (!node) return false;
  if (!poppingHistory) {
    const from = currentFocus();
    if (from !== id) {
      try { history.pushState({ amFocus: id, cameFrom: from }, ''); } catch (e) { }
    }
  }
  if (node.group === 'app') {
    forceSelect(appSelect, node.id, node.title);
    deviceSelect.value = '__all__';
    // An inert app has no edges by definition, so filtering the graph down to
    // its neighbourhood - what applyFilters does for every other app - leaves
    // nothing to draw: the whole map collapses to that one square. Nothing is
    // broken in that state, there is just nothing connected to show. The
    // panel below already has something worth saying, so it opens without
    // touching whatever the map currently has on screen.
    // An unreadable app has empty roles/ruleLinks/endpoints for the same
    // reason an inert one does - there is nothing to filter the graph down
    // to - so it needs the same exemption or focusing one collapses the
    // whole map to a lone square the same bug this exemption already fixed
    // for inert nodes.
    if (!node.inert && !node.unreadable) applyFilters();
    showFlow(node.id);
  } else {
    forceSelect(deviceSelect, node.id, node.title);
    appSelect.value = '__all__';
    flowPanel.style.display = 'none';
    syncLegendVisibility();
    applyFilters();
  }
  const hint = document.getElementById('hint');
  if (hint) hint.style.display = 'none';
  renderBackLink();
  return true;
}

// Restores whatever view the browser just navigated to, in either direction
// - Back and Forward both land here, and both are answered the same way, by
// reading the state the browser supplies for the entry now current rather
// than by guessing which direction was pressed. Entries this page never
// pushed (state has no amFocus and no cameFrom) are somebody else's history,
// where doing nothing is correct: the browser has already gone there.
window.addEventListener('popstate', function (ev) {
  const st = ev.state;
  // amFocus === undefined (the property absent entirely) is what actually
  // means "not one of ours" - amFocus === null is our own legitimate
  // whole-map state (the base entry set by replaceState on load) and must
  // NOT be treated the same way, or Back all the way out of a drill-down
  // would silently stop working on the last step, right when it matters
  // most: the browser's position would reach the base entry while the map
  // kept showing whatever was focused before that click.
  if (!st || st.amFocus === undefined) return;
  poppingHistory = true;
  try {
    if (st.amFocus) {
      focusNode(st.amFocus);
      // focusNode's own renderBackLink call reads history.state, which the
      // browser has already updated to this entry by the time popstate
      // fires - no extra bookkeeping needed here for the label to be right.
    } else {
      appSelect.value = '__all__';
      deviceSelect.value = '__all__';
      flowPanel.style.display = 'none';
      syncLegendVisibility();
      applyFilters();
      renderBackLink();
    }
  } finally {
    poppingHistory = false;
  }
});

network.on('click', function (params) {
  if (params.nodes && params.nodes.length) focusNode(params.nodes[0]);
});

// vis does not change the cursor by itself, so nothing signals that nodes are
// clickable at all.
const canvasEl = document.getElementById('network');
network.on('hoverNode', function () { canvasEl.style.cursor = 'pointer'; });
network.on('blurNode', function () { canvasEl.style.cursor = 'default'; });

appSelect.addEventListener('change', function () {
  if (appSelect.value !== '__all__') deviceSelect.value = '__all__';
  applyFilters();
  if (appSelect.value === '__all__') {
    flowPanel.style.display = 'none';
    syncLegendVisibility();
  } else {
    showFlow(appSelect.value);
  }
});
deviceSelect.addEventListener('change', function () {
  if (deviceSelect.value !== '__all__') appSelect.value = '__all__';
  flowPanel.style.display = 'none';
  syncLegendVisibility();
  applyFilters();
});
document.getElementById('kindFilter').addEventListener('change', applyFilters);
// Short synthesised confirmation tone, agreed with Gordon 2026-08-19 - no
// audio file, no external asset, consistent with the rest of this page being
// fully self-contained. Deliberately click-only, not on page open: browsers
// block audio autoplay until the user has interacted with the page, and a
// click is exactly the interaction that satisfies that, while page load is
// not - discussed and dropped rather than shipping something that would
// silently fail to play in some browsers with nothing telling the user why.
// One note of the sequence below - its own oscillator/gain pair, since a
// single node can only ever play one pitch once.
function playTone(ctx, freq, startOffset, duration, peakGain) {
  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  osc.type = 'sine';
  osc.frequency.value = freq;
  const t0 = ctx.currentTime + startOffset;
  gain.gain.setValueAtTime(0, t0);
  gain.gain.linearRampToValueAtTime(peakGain, t0 + 0.015);
  gain.gain.exponentialRampToValueAtTime(0.0001, t0 + duration);
  osc.connect(gain);
  gain.connect(ctx.destination);
  osc.start(t0);
  osc.stop(t0 + duration);
}

// Kept as a fallback, not removed - if the MP3 has not reached this branch
// yet (pushed to hub before pushed to git, or a raw.githubusercontent.com
// hiccup), a click should still make some sound rather than silently do
// nothing.
function playSynthesizedFallback() {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    playTone(ctx, 523.25, 0, 0.12, 0.12);
    playTone(ctx, 659.25, 0.1, 0.12, 0.12);
    playTone(ctx, 783.99, 0.2, 0.5, 0.16);
    playTone(ctx, 1046.5, 0.2, 0.5, 0.12);
  } catch (e) { /* Web Audio unsupported or blocked - never breaks the click itself */ }
}

// "Frying Pan Hit" by Mike Koenig, soundbible.com, CC BY 3.0 (see README
// Credits). Hosted in this repo rather than embedded as a data URI to keep
// this already-large page from growing further - the branch below resolves
// to whichever branch this exact file is actually running on (from
// APP_NAME's existing "(Dev)" marker, the one thing this file already
// legitimately varies by branch), so this one URL is correct on both dev
// and main without the source differing between them. Computed inline here
// rather than as its own @Field: a static field's initializer referencing
// another static field (APP_NAME) compiles fine locally but is rejected
// live by Hubitat's own sandbox - found live, not caught by groovyc.
const SHOW_ALL_SOUND_URL = 'https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/${APP_NAME.contains('(Dev)') ? 'dev' : 'main'}/assets/show-all-sound.mp3';
let showAllAudio = null;
function playShowAllSound() {
  try {
    if (!showAllAudio) {
      showAllAudio = new Audio(SHOW_ALL_SOUND_URL);
      showAllAudio.volume = 0.6;
      showAllAudio.addEventListener('error', playSynthesizedFallback, { once: true });
    }
    showAllAudio.currentTime = 0;
    const p = showAllAudio.play();
    if (p && p.catch) p.catch(playSynthesizedFallback);
  } catch (e) { playSynthesizedFallback(); }
}

// "Woman Excited Cheers And Phrases Says Yes 1" by Floraphonic, via Pixabay
// (Pixabay Content License - free for this use, attribution not required,
// credited in README anyway). Same lazy-load/branch-aware/fallback pattern
// as playShowAllSound() above.
const COMMUNITY_UTILITIES_SOUND_URL = 'https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/${APP_NAME.contains('(Dev)') ? 'dev' : 'main'}/assets/community-utilities-sound.mp3';
let communityUtilitiesAudio = null;
function playCommunityUtilitiesSound() {
  try {
    if (!communityUtilitiesAudio) {
      communityUtilitiesAudio = new Audio(COMMUNITY_UTILITIES_SOUND_URL);
      communityUtilitiesAudio.volume = 0.6;
      communityUtilitiesAudio.addEventListener('error', playSynthesizedFallback, { once: true });
    }
    communityUtilitiesAudio.currentTime = 0;
    const p = communityUtilitiesAudio.play();
    if (p && p.catch) p.catch(playSynthesizedFallback);
  } catch (e) { playSynthesizedFallback(); }
}

document.getElementById('resetBtn').addEventListener('click', function () {
  playShowAllSound();
  // Deliberately NOT a real click on the legend's own toggle header - found
  // live that reusing it also overwrote the saved amLegendCollapsed
  // preference, so a normally-collapsed legend stayed force-expanded on
  // every later page load too, not just this one view. This updates the
  // same two things a click does (the CSS class, the arrow glyph/aria-state)
  // without the localStorage write, so the expand is visual-only for this
  // Show all and the user's own saved preference survives untouched.
  const legendEl = document.getElementById('legend');
  if (legendEl && legendEl.classList.contains('collapsed')) {
    legendEl.classList.remove('collapsed');
    const legendToggle = document.getElementById('legend-toggle');
    if (legendToggle) {
      legendToggle.innerHTML = '&#9662;';
      legendToggle.setAttribute('aria-expanded', 'true');
    }
  }
  exitToWholeMap();
  // Re-frame the whole map, the same fit() the opening view is built from.
  //
  // exitToWholeMap() restores every node, but the zoom stays wherever the
  // focused view left it, so returning from a drilled-in app or device landed
  // on the whole map at a close-in zoom showing labels instead of the wide
  // opening view. settle() is supposed to fit() once physics comes to rest,
  // but the event it waits on does not fire on this path, so that fit never
  // happens and the zoom is simply left alone.
  //
  // Deliberately here in the button's own handler rather than inside
  // exitToWholeMap() or settle(): both of those are shared with the map's
  // opening sequence, and changing either one is what broke the opening
  // animation twice. Nothing outside this click is affected.
  network.fit({ animation: false });
  // fit() itself already pads 10% around the nodes' bounding box (vis-
  // network's own margin, not something this app controls), but Gordon found
  // that still too tight after a focused view. Backed out further on top of
  // fit()'s own result, same centre, just a smaller scale. 0.6 measured live
  // against the actual opening scale (fit() landed at 0.295 one run, the
  // real opening scale was 0.171 - a 0.58 ratio), not guessed; physics
  // settles into a different bounding box each time, so the exact ratio
  // needed will still vary click to click, this just gets much closer on
  // average than the earlier 0.8 did.
  //
  // Position and scale captured and passed together in one moveTo call,
  // not scale alone relying on moveTo's own "default position to the
  // current one" behaviour - found live that the implicit default drifted
  // the centre off what fit() had just set, since it is resolved through a
  // canvas-to-view conversion that itself depends on the scale being
  // changed in the same call. Being explicit about both removes that.
  const fitPosition = network.getViewPosition();
  const fitScale = network.getScale();
  network.moveTo({ position: fitPosition, scale: fitScale * 0.6, animation: false });
});
// A separate site Automation Map does not control, so it opens in a new tab
// rather than replacing this one - the map is mid-session state (whatever is
// currently focused/filtered) that a plain navigation would lose. noopener
// keeps the new tab from holding a reference back to this one.
document.getElementById('communityUtilitiesBtn').addEventListener('click', function () {
  playCommunityUtilitiesSound();
  window.open('https://gordonthelander.github.io/HPM_Manifest_Crawl/', '_blank', 'noopener');
});
// Leaves the map entirely for this app's own settings page in the hub admin
// UI - a different action from Exit to whole map, which stays on this page
// and only resets the filters. app.id is filled in by Groovy at render time,
// not read from anything the browser sends.
//
// Same bug class as the scan-start fix above: a bare '/installedapp/...'
// path resolves against whatever origin the browser currently has this page
// loaded from. When that origin is the local hub itself this is correct,
// but when the map was opened through the OAuth cloud endpoint, that origin
// serves only this app's own mapped endpoints (scan/externals/icon-overrides),
// not the general hub admin UI - '/installedapp/configure' does not exist
// there. Sending the browser to the local hub's own origin instead at least
// works for anyone with LAN access to it, which a cloud-opened link does not
// rule out, rather than guaranteed-wrong navigation on the relay's own host.
document.getElementById('exitMapBtn').addEventListener('click', function () {
  var localOrigin = '${getLocalOrigin()}';
  var onLocalOrigin = false;
  try { onLocalOrigin = (new URL(localOrigin).hostname === window.location.hostname); } catch (ignore) { }
  window.location.href = (onLocalOrigin ? '' : localOrigin) + '/installedapp/configure/${app.id}';
});
</script>
</body>
</html>
"""
}
