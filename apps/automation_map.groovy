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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@Field static final String APP_NAME = 'Automation Map (Dev)'
// Every build of this app excludes all of its own variants from the map,
// whatever each one calls itself. A dev copy installed beside the release would
// otherwise show up as an app referencing every device on the hub, and the
// release would do the same from the dev copy's point of view.
@Field static final String APP_FAMILY = 'Automation Map'
@Field static final String APP_VERSION = '2.0.14'
// Bumped ONLY when the shape of the scanned graph changes, so that a rendering
// or scanning fix does not needlessly invalidate a good scan and force the user
// to re-crawl every device and app.
// Bumped for the stops->cancelTimedActions / pauses->pauseResume kind rename
// and the addition of node.missing - a cached graph from schema 2 would
// otherwise render with edge kinds that no longer match any colour/dash
// lookup, degrading silently to the '#999' fallback instead of forcing the
// rescan that already exists for exactly this situation.
@Field static final String GRAPH_SCHEMA = '5'

// Gates the watermark's Dec 20-25 swap to the Christmas tree image
// (see hubWatermark below) - the only thing showSanta() controls now.
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
    page name: 'baselineComparisonPage'
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

// On by default (00:30 production, 01:00 Dev when the time is left blank) -
// the app-wide scan-first-then-explore experience this app is built around
// is better served by a map that keeps itself current than by one that goes
// stale until someone remembers to press Scan. The two defaults differ only
// so a Dev install running alongside production on the same hub doesn't
// compete with it for the same loopback endpoints at the same second - an
// explicitly chosen time on either instance is never overridden. The toggle
// below is still there to opt out entirely. Rescheduled (not just scheduled
// once) every time this runs, so turning the toggle off actually cancels a
// previously-running schedule rather than leaving it firing.
void scheduleAutoScan() {
    unschedule('scheduledScanHandler')
    if (!settings.autoScanEnabled) return
    if (settings.autoScanTime) {
        // schedule() accepts the exact string a Hubitat "time" input stores
        // and reschedules it daily - standard, documented platform pattern,
        // not yet confirmed live against this specific input on this hub.
        schedule(settings.autoScanTime as String, 'scheduledScanHandler')
    } else if (APP_NAME.contains('(Dev)')) {
        // A Dev install sitting on its own explicit "01:00 (default)" text
        // (see the settings page) - kept off production's 00:30 so the two
        // don't compete for the same hub CPU/loopback endpoints at the same
        // second when both are installed side by side, as they are on
        // Gordon's own hub. Only the blank-time fallback differs; an
        // explicitly chosen time on either instance is untouched above.
        schedule('0 0 1 * * ?', 'scheduledScanHandler')
    } else {
        // Cron default: 00:30:00 every day (sec min hour day month weekday).
        schedule('0 30 0 * * ?', 'scheduledScanHandler')
    }
    String defaultLabel = APP_NAME.contains('(Dev)') ? '01:00 (default)' : '00:30 (default)'
    log.info "${app.label}: automatic scan scheduled for ${settings.autoScanTime ?: defaultLabel}"
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
    // state.scanRunning above is a fast-path check only, harmless if stale -
    // startScan()'s own atomic lock is what actually decides this correctly.
    Map result = startScan()
    if (!result.acquired) {
        log.info "${app.label}: scheduled scan skipped, another start already owns this instance"
    }
}

Map main() {
    // Until Done is pressed the instance does not exist yet, and Hubitat cannot
    // schedule work for it: runIn() silently does nothing, so a scan started
    // from this page would set itself running and then never execute a single
    // batch. So the scan is not offered at all until installation completes.
    boolean ready = app.installationState == 'COMPLETE'
    String oauthError = null
    if (ready && !state.accessToken) {
        try {
            createAccessToken()
        } catch (Exception e) {
            // OAuth is off for this app, so createAccessToken() throws. Without this
            // catch the whole page threw here - the first screen a hand-installer sees
            // if they skip the OAuth step, with no indication what went wrong.
            oauthError = 'Automation Map needs OAuth enabled to create the map link. In the ' +
                "hub's Apps Code editor, open Automation Map, click OAuth -> Enable OAuth " +
                'in App -> Update, then reopen this page.'
        }
    }
    clearAbandonedScan()

    // A full scan takes a couple of minutes. Without this the page looked frozen
    // - the progress line only moved if you closed and reopened it, which reads
    // as a hang rather than as work in progress.
    // Live progress now comes from amProgressPoll() below (a lightweight
    // fetch of /scan-status updating one span in place), not from Hubitat's
    // own full-page refreshInterval - a scan now typically finishes in
    // 15-25s, and a 4-second full-page reload against that window produced
    // 2-3 jarring whole-page flashes rather than a smooth progress display.
    // A long fallback interval is kept, not removed entirely, in case the
    // JS poll itself ever fails to start or silently stalls - the same
    // belt-and-suspenders reasoning as the async pipeline's own watchdogs.
    return dynamicPage(name: 'main', title: "<b>${APP_NAME} v${APP_VERSION}</b>", install: true, uninstall: ready,
                       refreshInterval: (ready && state.scanRunning) ? 60 : 0) {
        // Scan status, the map link and the Scan button all sit ABOVE the device
        // picker. The picker renders as a list of every device on the hub, so
        // anything below it is off the bottom of the screen - which is where the
        // progress line and the link to the map used to be on every visit after
        // the first scan.
        if (ready) {
            section {
                if (oauthError) {
                    paragraph "<b style='color:#c0392b'>${oauthError}</b>"
                }
                // The scan is started by fetching the app's own /scan endpoint
                // rather than from a Hubitat button. runIn() called out of
                // appButtonHandler does not reliably schedule anything: on a
                // clean install the queue was populated, scanRunning was true,
                // no job was scheduled, and the async pipeline never advanced
                // even once - its heartbeat was never written. Driving it through the endpoint
                // runs the scan in an ordinary app execution, which works.
                paragraph scanButtonHtml()
                if (state.scanTotal) {
                    // state.scanDone is only ever written once per phase now
                    // (by the phase-starting execution and by its finalize) -
                    // callbacks/reapers stay entirely state-free, so this
                    // would read frozen at 0 for the whole active phase
                    // without reading the live scan accumulator instead. Same
                    // fix as scanStatusJson().
                    ConcurrentHashMap liveScan = null
                    if (state.scanPhase == 'devices') liveScan = DEVICE_SCANS[state.deviceScanId as String]
                    else if (state.scanPhase == 'apps') liveScan = APP_SCANS[state.appScanId as String]
                    Integer done = liveScan ? (liveScan.processed as AtomicInteger).get() : (state.scanDone ?: 0) as Integer
                    Integer total = (state.scanTotal ?: 1) as Integer
                    Integer pct = total > 0 ? ((done * 100) / total) as Integer : 0
                    boolean isDevicePhase = state.scanPhase != 'apps'
                    // total/done during the device phase count driver-type
                    // representatives (34 on this hub), not individual devices
                    // (194) - dispatchDeviceOne fetches capabilities once per
                    // representative and applies the result to its whole group,
                    // so there is no finer-grained "device 47 of 194" progress
                    // to report even in principle. Labelled and shown alongside
                    // the real device count instead of mislabelling the
                    // representative count as a device count.
                    String phase = isDevicePhase ? 'Reading device types' : 'Reading apps'
                    Integer realDeviceTotal = (state.deviceScanTotal ?: 0) as Integer
                    String deviceContext = (isDevicePhase && realDeviceTotal > 0) ? " (${realDeviceTotal} devices)" : ''
                    String progress = "${phase}: ${done} of ${total}${deviceContext} (${pct}%)"
                    if (state.scanRunning) {
                        progress += ' - updating live, no need to reload.'
                    } else {
                        // scanHeartbeat is stamped at the start of finishScan(), so it lands a
                        // few seconds ahead of this page reporting the scan as finished - close
                        // enough for a "when did this last run" display. Same value already
                        // backs the AI export's lastScanCompletedAt. Counts moved out of this
                        // line entirely - see the "Map contains" paragraph below, which covers
                        // apps/devices/nodes/relationships together in one place instead of
                        // splitting apps in here and nodes/edges down there.
                        String when = state.scanHeartbeat ?
                            new Date(state.scanHeartbeat as Long).format('yyyy-MM-dd HH:mm', location.timeZone) : 'unknown'
                        progress = "Last scan : <span style='color:#2e7d32'>${when}</span>"
                    }
                    // Wrapped in an id'd span, not a bare paragraph - amProgressPoll()
                    // below replaces this element's text in place once JS takes over,
                    // rather than needing a full page reload to show new numbers. This
                    // server-rendered text is still the correct first paint and the
                    // no-JS fallback, not dead markup.
                    paragraph "<span id='amProgress'>${progress}</span>"
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
                        paragraph "Map contains: ${(state.appInfo ?: [:]).size()} apps, ${(state.deviceLabels ?: [:]).size()} devices, " +
                            "${(g.nodes ?: []).size()} nodes, ${(g.edges ?: []).size()} relationships."
                        paragraph compatibilitySummary()
                        href(
                            name: 'mapLink', title: "<span style='color:#1976d2'>View Automation Map</span>",
                            description: 'Open the relationship graph',
                            url: "${getLocalURL('automation-map.html')}&scan=${state.scanHeartbeat ?: 0}",
                            style: 'embedded', state: 'complete', required: false,
                        )
                    }
                }
                href(
                    name: 'baselineComparisonLink', title: "<span style='color:#1976d2'>Baseline Comparison</span>",
                    description: 'Compare discovered apps and devices between two Automation Map exports',
                    page: 'baselineComparisonPage', style: 'embedded', required: false,
                )
                href(
                    name: 'communityUtilitiesLink', title: "<span style='color:#1976d2'>Community Utilities</span>",
                    description: 'Open the Hubitat Community Utilities site',
                    url: 'https://gordonthelander.github.io/HPM_Manifest_Crawl/',
                    style: 'embedded', required: false,
                )
                // Hubitat's external href style calls openWindow(), which
                // creates a sized popup rather than the full browser tab the
                // user requested. Keep the normal full-width href row, then
                // make its real anchor an ordinary target=_blank link.
                paragraph '''<script type="text/javascript">
(function () {
  var link = document.querySelector('a[href="https://gordonthelander.github.io/HPM_Manifest_Crawl/"]');
  if (!link) return;
  link.removeAttribute('onclick');
  link.setAttribute('target', '_blank');
  link.setAttribute('rel', 'noopener noreferrer');
})();
</script>'''
                paragraph "Need help or found a problem? Visit the <a href='https://community.hubitat.com/t/release-hubitat-automation-map/165524' target='_blank'><b>Automation Map community thread</b></a> for Community discussion or raise an <a href='https://github.com/GordonThelander/hubitat-automation-map/issues' target='_blank'><b>Issue</b></a> on GitHub."
            }
            section {
                input name: 'autoScanEnabled', type: 'bool',
                    title: 'Scan automatically every day',
                    description: "On by default at ${APP_NAME.contains('(Dev)') ? '01:00' : '00:30'}. Turn off if you would rather press Scan yourself.",
                    defaultValue: true, submitOnChange: true
                if (settings.autoScanEnabled) {
                    input name: 'autoScanTime', type: 'time',
                        title: 'Time to run the scan',
                        description: "Leave blank for ${APP_NAME.contains('(Dev)') ? '01:00' : '00:30'}.", required: false
                }
            }
        }

        if (!ready) {
            section {
                paragraph '<b>Press <i>Done</i> to install Automation Map.</b> <span style="color:#c0392b"><b>Your first scan then starts by itself and takes well under a minute, even on a large hub. Open the app again to watch it and to view the map.</b></span>'
                paragraph '<span style="opacity:0.75">There is nothing to configure. Every device on the hub is scanned, and the apps are found by asking each device which apps use it.</span>'
            }
        }
    }
}

Map baselineComparisonPage() {
    return dynamicPage(name: 'baselineComparisonPage', title: '<b>Baseline Comparison</b>',
                       install: false, uninstall: false) {
        section {
            href(
                name: 'baselineComparisonBack', title: 'Back to Automation Map',
                description: 'Return to the Automation Map main page',
                page: 'main', style: 'button', required: false,
            )
            // A Hubitat dynamic subpage adds its own bottom-right Done/Cancel
            // action even though this page has nothing to save. On the tall
            // comparator page that control is both misleading and far away
            // from the requested navigation. The explicit Back control above
            // is the only page-exit action this page needs.
            paragraph '''<style type="text/css">
button.cancel, button.done, button[name="_action_done"] { display:none !important; }
button[name^="_action_href_baselineComparisonBack|"] {
  background:#2e7d32 !important;
  border-color:#2e7d32 !important;
  color:#fff !important;
}
button[name^="_action_href_baselineComparisonBack|"] span { color:#fff !important; }
</style>'''
        }
        section {
            paragraph comparatorFrameHtml()
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
function amIsLocalAccess() {
  try {
    return new URL('${getLocalOrigin()}').hostname === window.location.hostname;
  } catch (ignore) { }
  return false;
}
function amPickURL(localPath, cloudUrl) {
  return amIsLocalAccess() ? localPath : cloudUrl;
}
function amShowRemoteProgress() {
  var el = document.getElementById('amProgress');
  if (el) el.textContent = 'Remote scanning, this page will refresh once done.';
  var msg = document.getElementById('amScanMsg');
  if (msg) msg.textContent = '';
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
  // schedule nothing and the async pipeline would never execute. Authenticating with the
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
    m.textContent = 'Remote scanning, this page will refresh once done.';
    var remoteProgress = document.getElementById('amProgress');
    if (remoteProgress) remoteProgress.textContent = m.textContent;
    var f = document.createElement('iframe');
    f.style.display = 'none';
    f.src = scanUrl;
    document.body.appendChild(f);
    // Marks a scan as pending-confirmation, read by the bootstrap retry
    // block below on the NEXT page load. startScan() runs two real HTTP
    // calls before it commits scanRunning=true, so this fixed 4-second
    // reload can legitimately land before that commit - without this
    // marker, that reload would render from stale pre-scan state (button
    // enabled, no polling started) and just sit there, stale, until the
    // user notices and reloads again by hand.
    try { sessionStorage.setItem('amScanPending', '0'); } catch (ignore) { }
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
    .then(function () { m.textContent = 'Scanning - progress below updates live.'; amSawRunning = true; amProgressPoll(); })
    .catch(function (e) {
      b.disabled = false;
      var where = '(could not parse the attempted URL)';
      try { var u = new URL(scanUrl, window.location.href); where = u.origin + u.pathname; } catch (ignore) { }
      m.textContent = 'Could not start the scan: ' + e.message + ' | tried: ' + where;
    });
}
// Live progress for the span scanButtonHtml's caller renders as
// <span id="amProgress">. Reuses amPickURL's same local/cloud origin
// detection amStartScan already relies on - the cloud/CORS case has no live
// polling available for the same documented reason amStartScan falls back
// to a hidden-iframe navigation, so it does nothing further here and leaves
// the page's own refreshInterval (see dynamicPage below) as the sole
// fallback. amSawRunning is the guard against reloading a page that was
// never actually watching a live scan - only a poll that itself observed
// running:true, in THIS page view, triggers the one-time reload once the
// scan finishes; a page opened after the fact just shows the static
// "Last scan" text with no polling at all.
//
// Previously scheduled its own setTimeout(reload, 4000) here on the cloud
// path. Every reload re-renders the page while scanRunning is still true,
// which re-enters this same function on load and reschedules another
// reload - an unbounded four-second reload chain for the whole scan, not
// the single fallback the old comment claimed. Caught in the Bucket/Queue
// remote-access review (2026-08-23) before this shipped past dev.
var amPolling = false;
var amSawRunning = false;
function amProgressPoll() {
  if (amPolling) return;
  amPolling = true;
  var statusUrl = amPickURL('${getLocalURL('scan-status')}', '${getCloudURL('scan-status')}');
  if (statusUrl.indexOf('http') === 0) {
    amPolling = false;
    return;
  }
  fetch(statusUrl, { cache: 'no-store', credentials: 'omit' })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      amPolling = false;
      var el = document.getElementById('amProgress');
      if (!el) return;
      if (d.running) {
        amSawRunning = true;
        var isDevicePhase = d.phase !== 'apps';
        if (!isDevicePhase && d.total > 0 && d.done >= d.total) {
          // Every app is read, but scanRunning is still true - fetchRegistry
          // and finishScan (the graph build) are their own separately
          // scheduled executions after this, not instant. Without this the
          // page sat on "106 of 106 (100%)" looking finished for that whole
          // gap, which read as stuck rather than as the next real step.
          el.textContent = 'Building map - please wait...';
        } else {
          var phaseLabel = isDevicePhase ? 'Reading device types' : 'Reading apps';
          var deviceContext = (isDevicePhase && d.devices) ? ' (' + d.devices + ' devices)' : '';
          var pct = d.total > 0 ? Math.floor((d.done * 100) / d.total) : 0;
          el.textContent = phaseLabel + ': ' + d.done + ' of ' + d.total + deviceContext +
                            ' (' + pct + '%) - updating live, no need to reload.';
        }
        setTimeout(amProgressPoll, 1500);
      } else if (amSawRunning) {
        // Live-updating this one span cannot reveal the map link/insights
        // sections, which only render at all when state.graph exists in the
        // page's original server-rendered HTML - one reload is still needed
        // to show those, just once, at the actual end, not every 4 seconds
        // for the whole scan.
        el.textContent = 'Scan complete - reloading...';
        location.reload();
      }
    })
    .catch(function () {
      amPolling = false;
      // Silent - the 60s refreshInterval fallback still covers a poll that
      // keeps failing, and a transient failure just gets retried below. Not
      // worth a user-visible error for a background progress poll.
      setTimeout(amProgressPoll, 3000);
    });
}
${autoScanScript()}
if (${state.scanRunning ? 'true' : 'false'}) {
  // A scan was already running when this page was rendered (reopened mid-
  // scan, or the 60s fallback refreshInterval fired) - resume live polling
  // immediately rather than wait for the user to notice and reload again.
  // Confirmed now, so the pending-retry marker below has done its job.
  try { sessionStorage.removeItem('amScanPending'); } catch (ignore) { }
  amSawRunning = true;
  if (amIsLocalAccess()) {
    document.addEventListener('DOMContentLoaded', amProgressPoll);
    if (document.readyState !== 'loading') amProgressPoll();
  } else {
    document.addEventListener('DOMContentLoaded', amShowRemoteProgress);
    if (document.readyState !== 'loading') amShowRemoteProgress();
  }
} else {
  // Bounded retry for the amStartScan() cloud path's own fixed 4-second
  // reload, which can legitimately land before startScan()'s two HTTP
  // calls finish and scanRunning commits. Without this, that one reload
  // renders from stale pre-scan state and never gets another chance -
  // the amProgressPoll cascade this file used to have was the wrong fix
  // for that (see amProgressPoll's own comment), but leaving the page
  // permanently stale is not the right fix either. One extra reload only,
  // not a chain: the counter caps it, and scanRunning true above (not
  // this branch) is what takes over once the scan is actually confirmed.
  try {
    var amPendingRaw = sessionStorage.getItem('amScanPending');
    if (amPendingRaw !== null) {
      var amPendingAttempts = parseInt(amPendingRaw, 10) || 0;
      if (amPendingAttempts < 1) {
        sessionStorage.setItem('amScanPending', String(amPendingAttempts + 1));
        setTimeout(function () { location.reload(); }, 3000);
      } else {
        sessionStorage.removeItem('amScanPending');
      }
    }
  } catch (ignore) { }
}
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
// finished installing. state.scanHeartbeat is stamped once, when a phase
// starts, not refreshed by callbacks/reapers any more - those stay entirely
// state-free. Whether a scan is genuinely still active is answered below by
// checking DEVICE_SCANS/APP_SCANS for a live entry, not by heartbeat
// recency; the 90s check is only a cheap first filter before that lookup.
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
    //
    // Callbacks and reapers never write state.scanHeartbeat any more (see
    // deviceFetchCb/appFetchCb - it moved to a scan-local timestamp field,
    // scanStatusJson reads it live). So the staleness check above no longer
    // means "no progress in 90s" during an active async phase - it just
    // means "90s since this scan STARTED", which fires on every long-running
    // scan whether it is healthy or not. Deferring to whichever phase's own
    // watchdog/reaper is live is therefore not a belt-and-suspenders
    // addition any more, it is the only thing standing between a perfectly
    // healthy in-progress scan and this function marking it abandoned.
    boolean asyncDeviceScanActive = state.scanPhase == 'devices' && DEVICE_SCANS.containsKey(state.deviceScanId as String)
    boolean asyncAppScanActive = state.scanPhase == 'apps' && APP_SCANS.containsKey(state.appScanId as String)
    if (asyncDeviceScanActive || asyncAppScanActive) return

    // A "finishing:<token>:<since>" value means finishGeneration() is
    // already mid-publish for the current generation (see its own comment) -
    // actively wrapping up, not stuck. Recovering it here on sight would
    // race the in-flight publish, and finishGeneration()'s own finally is
    // about to release it as its very next real step regardless - UNLESS
    // the execution holding it was killed outright (platform timeout, hub
    // reboot) rather than merely throwing, which no in-process finally can
    // cover. FINISHING_RECOVERY_SEC bounds how long this function trusts
    // "actively finishing" before treating it as stranded instead.
    String currentLock = SCAN_LOCKS.get("${app.id}") as String
    if (currentLock != null && currentLock.startsWith('finishing:')) {
        List parts = currentLock.split(':') as List
        Long finishingSince = parts.size() >= 3 ? (parts[2] as Long) : 0L
        if ((now() - finishingSince) < (FINISHING_RECOVERY_SEC * 1000)) return
        log.warn "${app.label}: clearing a stranded finishing marker (${((now() - finishingSince) / 1000).intValue()}s old)"
        // Not a direct remove-then-mutate - caught in review as the exact
        // race the unified terminal protocol exists to remove, reintroduced
        // in this one recovery-only path: after a plain conditional remove,
        // a brand new scan C could acquire before the writes below ran, and
        // recovery would then stomp C's just-started state. Atomically
        // replaces the exact aged value with a distinct "recovering:"
        // sentinel first - still occupying the slot, still blocking a new
        // acquisition - writes the recovery state while that sentinel
        // holds, then removes it last, in a finally. Not routed through
        // finishGeneration()/markScanFinished(): this is recovering an
        // already-stranded value, not a normal generation's own token
        // handoff, and must not itself claim a fresh "finishing:" transition
        // (which would nest and break this same parsing on a future pass).
        String recoveryValue = "recovering:${currentLock}:${now()}"
        if (SCAN_LOCKS.replace("${app.id}", currentLock, recoveryValue)) {
            try {
                state.scanError = 'The previous scan stopped before it finished. Press Scan to run it again.'
                state.scanRunning = false
            } finally {
                SCAN_LOCKS.remove("${app.id}", recoveryValue)
            }
        }
        return
    }

    // Once the async app accumulator is gone, durable appResultsReady is the
    // only proof that finalizeAppPhase committed the complete appInfo map.
    // state.scanQueue is always empty in this async implementation, including
    // before the first app result is published, so it must never be used as a
    // completion invariant. Reproduced live in v2.0.8: updating the app code
    // erased APP_SCANS/SCAN_LOCKS, recovery saw an empty queue, and published
    // the still-empty state.appInfo as a successful zero-app graph.
    if (state.scanPhase == 'apps') {
        // Live SCAN_LOCKS snapshot, not a remembered token - same reasoning
        // as this function's own genuinely-abandoned branch below: this is a
        // recovery path, so it trusts the freshest ground truth for "what is
        // the currently active generation" rather than a value written
        // earlier that could have gone stale by the time recovery runs.
        String currentToken = SCAN_LOCKS.get("${app.id}") as String
        if (currentToken == null) {
            // Reproduced live 2026-08-24: an app code reload resets the
            // static SCAN_LOCKS map to empty, but durable state.scanRunning
            // survives the reload untouched, so this branch kept re-entering
            // with a null token. finishScan()'s own claim always correctly
            // rejected that null and discarded the graph it had just built -
            // but with no token left to hand it, nothing ever recovered, and
            // every status poll repeated the entire wasted rebuild forever.
            // No original generation to reclaim ownership FROM here, so
            // putIfAbsent claims the empty slot fresh instead of replace()
            // transitioning an existing value - only one concurrent recovery
            // attempt can win it.
            String recoveryToken = "recovered-${now()}-${(int)(Math.random() * 999999)}"
            if (SCAN_LOCKS.putIfAbsent("${app.id}", recoveryToken) != null) return
            currentToken = recoveryToken
        }
        if (state.appResultsReady == true) {
            log.warn "${app.label}: complete app results were published but graph finalization never ran - finishing now"
            finishScan([lockToken: currentToken])
        } else {
            // The async results lived only in the lost static accumulator and
            // cannot be reconstructed safely. Terminate truthfully and require
            // a new scan rather than publish a valid-looking empty/partial map.
            log.warn "${app.label}: scan working data was lost before app results were published - not building an incomplete map"
            markScanFinished(currentToken,
                'The scan working data was lost before it could be published. Press Scan to run it again.')
        }
        return
    }

    // Snapshot-then-conditional-remove, not a remembered token - this path
    // exists specifically for "something is stuck, recover it", so it uses
    // the freshest possible ground truth (whatever SCAN_LOCKS currently
    // holds) rather than trusting a value written earlier that the very
    // malfunction being recovered from might have left stale.
    String abandonedToken = SCAN_LOCKS.get("${app.id}") as String
    if (abandonedToken == null) {
        // Still claim the empty slot before touching durable state. A plain
        // lock-free clear has a TOCTOU gap: a new scan can acquire after the
        // null read but before these writes, and this stale recovery execution
        // would then clear the new scan. putIfAbsent either gives recovery
        // exclusive ownership or proves another execution got there first.
        String recoveryToken = "recovered-abandon-${now()}-${(int)(Math.random() * 999999)}"
        if (SCAN_LOCKS.putIfAbsent("${app.id}", recoveryToken) != null) return
        abandonedToken = recoveryToken
    }
    markScanFinished(abandonedToken,
        'The previous scan stopped before it finished. Press Scan to run it again.')
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

    // v2.0.14: only shown when the authoritative inventory itself succeeded
    // (complete or complete-with-gaps) - a failed/not-supported inventory has
    // no real count to report, and showing 0 would misrepresent "could not
    // ask the hub" as "the hub has none".
    Map hubVarInv = (state.hubVariableInventory ?: [:]) as Map
    String hubVarInvStatus = "${hubVarInv.status}"
    if (hubVarInvStatus == 'complete' || hubVarInvStatus == 'complete-with-gaps') {
        int hubVarCount = (hubVarInv.count ?: 0) as Integer
        int hubVarConnCount = (state.hubVariableConnectorCount ?: 0) as Integer
        s << " Found ${hubVarCount} Hub Variable(s)"
        s << (hubVarConnCount > 0 ? ", ${hubVarConnCount} with a Connector." : ".")
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

// Every hub-facing fetch in this file (six loopback, one to REGISTRY_URL on
// GitHub) used to carry its own inline try/catch, its own timeout literal,
// and its own copy of this exact shape - the failure contract was defined
// seven times and was not quite the same twice, which is why
// fetchAllDeviceIds() needed an explicit "sets scanError itself and returns
// on failure" comment at its own call site to be usable safely. This is the
// one place that shape is written now.
//
// data comes back exactly as the response sent it, not coerced to Map here -
// different endpoints return different top-level shapes (a bare List or a
// Map), so that decision stays with whoever actually knows their own
// endpoint's shape. extraOpts exists only for fetchRegistry's contentType;
// every loopback caller passes none.
Map httpFetch(String uri, int timeoutSec, Map extraOpts = [:]) {
    Map out = [ok: false, data: null, error: null]
    try {
        httpGet(extraOpts + [uri: uri, timeout: timeoutSec]) { resp ->
            out.data = resp.data
            out.ok = true
        }
    } catch (Exception ex) {
        out.error = "${ex.message}"
    }
    return out
}

// The one thing every loopback caller shares - not shared with fetchRegistry,
// which hits REGISTRY_URL on GitHub instead.
@Field static final String LOOPBACK_BASE = 'http://127.0.0.1:8080'

// Hardened async dispatch pipeline (v2.0.5) - reintroduces the
// concurrent-fetch work from the reverted 2.0.5 attempt, this time with
// the fixes verified against the live hub in the isolated dispatch-test
// harness: try/catch rollback around every asynchttpGet call, per-item
// claim/attempt-token tracking, a claim reaper for callbacks that never
// arrive at all, atomic conditional-removal ownership so a callback and
// the reaper can never both resolve the same attempt, exact completion
// invariants, exactly-once finalization, and a fail-closed watchdog that
// marks a stalled scan failed rather than publish a partial result
// labeled complete. See BACKLOG.md and the Bucket/ collaboration record
// for the full history of what was found and why each piece exists.
@Field static final int DEVICE_ASYNC_MAX_INFLIGHT = 8
@Field static final int APP_ASYNC_MAX_INFLIGHT = 8   // Hubitat's documented concurrent-async-per-app cap
// Bounded retry for a synchronous dispatch throw or an aged, unresolved
// claim - not for an ordinary HTTP-level failure, which already resolves
// in a single callback with no dangling claim and needs no retry.
@Field static final int ATTEMPT_CAP = 2
@Field static final int CLAIM_REAP_INTERVAL_SEC = 10
// Must exceed the longest real request timeout used by either phase
// (device fetch: 10s, app fetch: 20s) plus scheduling margin - a
// deadline shorter than a real request's own timeout makes premature
// reaping possible by construction, not just by bad luck. Confirmed as
// a real defect at 8000ms (below the test harness's own 10s "good"
// timeout) during the isolated test's second review round; raised well
// clear of this pipeline's real 20s maximum.
@Field static final long CLAIM_REAP_DEADLINE_MS = 25000
// Both phases share the same claim deadline/reap interval/attempt cap, so
// the worst-case time to terminally resolve one item is the same for
// either phase: ATTEMPT_CAP reap cycles, each up to (deadline + one poll
// interval) before the next attempt even starts - here, 2 * (25s + 10s) =
// 70s. A watchdog shorter than that envelope can fail a pipeline that is
// behaving exactly as designed - confirmed as a real defect (60s device
// watchdog against this same ~70s worst case) during the production-diff
// review, not just a theoretical gap. 60s of real margin added on top of
// the 70s envelope, not just clearing the minimum: 130s each.
//
// Written as plain literals, not computed from ATTEMPT_CAP/
// CLAIM_REAP_DEADLINE_MS/CLAIM_REAP_INTERVAL_SEC above - the hub's own
// loader rejects one @Field static final referencing another one's value
// ("was found in a static scope but doesn't refer to a local variable,
// static field or class"), even though local groovyc accepts it and even
// though the referenced field is declared earlier in the file. Confirmed
// against the hub directly, not assumed. If either the deadline, the
// interval, or the attempt cap above ever changes, these two must be
// recalculated by hand and this comment's math updated to match.
@Field static final int DEVICE_ASYNC_WATCHDOG_SEC = 130
@Field static final int APP_ASYNC_WATCHDOG_SEC = 130
// How long a "finishing" SCAN_LOCKS sentinel (see finishGeneration()) may
// occupy the slot before clearAbandonedScan() treats it as stranded rather
// than actively in progress. Generous relative to the actual work done under
// that sentinel (in-memory state writes only, no HTTP calls) - this bound
// exists for the case a language-level finally cannot cover at all: the
// execution itself being killed outright (platform timeout, hub reboot)
// mid-publish, not an ordinary thrown exception.
@Field static final int FINISHING_RECOVERY_SEC = 60
// Per-scan accumulator, keyed by scanId - never read from or written to
// state directly by a callback. Callbacks only ever touch these static
// maps; state is written exactly once per phase, by finalizeDevicePhase/
// finalizeAppPhase, in a single execution. This is what avoids the
// concurrent-write race this whole design exists to avoid: Hubitat's
// last-write-wins state persistence means two callbacks racing to write
// state directly could silently drop whichever wrote last.
@Field static final ConcurrentHashMap<String, ConcurrentHashMap> DEVICE_SCANS = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, ConcurrentHashMap> APP_SCANS = new ConcurrentHashMap<>()
// Single-flight lock keyed by app instance id. state.scanRunning alone cannot
// serve this role: two scanMapping()/scheduledScanHandler() executions can
// each read it before either one's own state commit lands (state only
// commits durably at the end of a whole execution), so both proceed into
// startScan(). putIfAbsent() is checked against this live, shared map
// instead - not subject to that per-execution commit delay - so only one
// concurrent caller can ever win it.
//
// The value is a unique per-attempt token, not a plain boolean - caught in
// review before this shipped: a boolean lock's release (SCAN_LOCKS.remove)
// has no ownership identity, so a LATE execution belonging to an OLDER scan
// generation (a delayed watchdog, a delayed finalizer) could unconditionally
// remove a NEWER generation's still-legitimate lock if it fires after that
// newer generation has already acquired the same app id. Every release must
// go through markScanFinished(token, ...) using the token THAT execution's
// own generation was given at acquire time - remove(key, exactToken) then
// only succeeds if this generation is still the current owner, and safely
// no-ops otherwise. Reading SCAN_LOCKS's current value fresh at release time
// instead of using a remembered token would defeat this entirely - that is
// exactly how a late execution could adopt a newer generation's identity.
@Field static final ConcurrentHashMap<String, String> SCAN_LOCKS = new ConcurrentHashMap<>()

// Everything this app knows comes from undocumented hub endpoints, so on a hub
// unlike the one it was written against it must say WHY it found nothing rather
// than presenting an empty map as if that were the answer. The most likely
// environmental difference is hub login security, which makes the internal
// endpoints answer with a login page instead of JSON.
Map probeCompatibility() {
    Map out = [ok: false, detail: '']
    Map result = httpFetch("${LOOPBACK_BASE}/installedapp/statusJson/${app.id}", 10)
    if (!result.ok) {
        out.detail = "Could not reach the hub's internal app endpoint (${result.error}). This Hubitat version may not expose /installedapp/statusJson."
    } else if (result.data instanceof Map && (result.data as Map).installedApp) {
        out.ok = true
        out.detail = 'Hub internal endpoints reachable.'
    } else {
        out.detail = 'The hub answered, but not with app JSON. If Hub Login Security is enabled, Automation Map cannot read app configuration.'
    }
    return out
}

// The sole place any scan generation's ownership actually ends - every
// failure/watchdog/bootstrap-exception path (no data to publish, via the
// markScanFinished() wrapper below) and finishScan()'s own success path
// (graph/counters to publish, via the publishWork closure) alike, so the
// two can never drift into separately-safe terminal protocols. Caught in
// review across several rounds before this went near a hub:
//
// - An early version removed the lock conditionally but mutated
//   state.scanRunning/state.scanError unconditionally underneath that
//   check - safe for the lock, not for state a late execution could still
//   stomp on a legitimately-running newer generation.
// - A later version fixed that ordering but still released the lock BEFORE
//   a caller's own further publish writes (finishScan's graph/counters) -
//   a brand new scan could acquire and start resetting state while the old
//   one was still mid-publish.
// - Fixed by claiming an atomic original->finishing transition FIRST -
//   nothing here or in publishWork touches state until this token is
//   confirmed to still be the current owner at that exact instant. The
//   finishing sentinel then continues occupying the slot for the whole of
//   publishWork, blocking any new acquisition, and the real release -
//   state.scanRunning=false, then removing the sentinel - is the LAST
//   thing that happens, inside a finally, so an exception inside
//   publishWork still releases rather than stranding the slot.
//
// The finishing value carries a timestamp (see FINISHING_RECOVERY_SEC) -
// finally cannot cover the execution being killed outright (platform
// timeout, hub reboot) mid-publish, only an ordinary thrown exception, so
// clearAbandonedScan() has a bounded, timestamp-gated path to recover a
// truly stranded sentinel rather than trusting the string forever.
//
// token must be the exact value this caller's own generation was given at
// acquire time (see SCAN_LOCKS's comment for why a fresh re-read is not
// safe here) - clearAbandonedScan() is the one deliberate exception, since
// its whole job is recovering whatever generation is CURRENTLY stuck, and it
// snapshots SCAN_LOCKS's live value itself before calling this.
//
// Returns true if this call actually became the one to terminate the
// generation, false if the token no longer owns the lock - a stale/
// superseded caller, correctly discarded, not an error.
boolean finishGeneration(String token, String error = null, Closure publishWork = null) {
    if (token == null) return false
    String finishingValue = "finishing:${token}:${now()}"
    if (!SCAN_LOCKS.replace("${app.id}", token, finishingValue)) return false
    try {
        if (publishWork != null) publishWork()
        if (error != null) state.scanError = error
    } catch (Exception ex) {
        log.warn "${app.label}: scan termination failed: ${ex.message}"
        state.scanError = "${ex.message}"
    } finally {
        state.scanRunning = false
        SCAN_LOCKS.remove("${app.id}", finishingValue)
    }
    return true
}

// Thin wrapper for every terminal path with nothing to publish - kept as
// the name the rest of the file already calls, so none of those nine call
// sites needed to change again for this round.
void markScanFinished(String token, String error = null) {
    finishGeneration(token, error, null)
}

// Cheap "is my token still the current owner" check - caught in review as
// the other half of the terminal-release fix above: token propagation only
// prevents identity ADOPTION, it does nothing by itself to stop a late
// execution belonging to an abandoned generation from mutating state on its
// way TO a terminal call. Every separately-scheduled, late-capable handler
// that publishes intermediate state (not just a terminal markScanFinished)
// must check this before its first write, and again after any HTTP call in
// between - abandonment and a fresh acquisition can interleave in that gap
// exactly as easily as around the terminal release itself.
boolean ownsLock(String token) {
    return token != null && SCAN_LOCKS.get("${app.id}") == token
}

// Returns [acquired: true] once this call has won the single-flight lock and
// (successfully or not) run the scan to its next state, or [acquired: false]
// if another execution already holds it - callers must not treat that as an
// error, and must not assume state.scanRunning reflects it (state.scanRunning
// is this LOSING execution's own stale snapshot, not the winner's).
Map startScan() {
    // Generated before the acquire attempt, not after - putIfAbsent needs
    // the value ready to insert. Carried forward explicitly from here on -
    // as a parameter into startAppPhase(), on the DEVICE_SCANS/APP_SCANS
    // accumulator for the watchdog/finalizer paths, and through runIn()'s
    // own data payload for the registry/finish tail - never stashed in
    // state for a later execution to read back. A late execution reading
    // "the current token" from state instead of carrying its OWN generation's
    // token is exactly how it could adopt a newer generation's identity;
    // see markScanFinished()'s comment for the full reasoning.
    String lockToken = "lock-${now()}-${(int)(Math.random() * 999999)}"
    if (SCAN_LOCKS.putIfAbsent("${app.id}", lockToken) != null) return [acquired: false]
    // The one unambiguous "a scan genuinely began" line, at the single choke
    // point every entry path (manual /scan, the scheduled overnight trigger,
    // any future caller) already funnels through - distinct from "/scan
    // endpoint reached", which only proves the HTTP request arrived and says
    // nothing about whether it actually started one (it may have lost the
    // race, or found one already running).
    log.info "${app.label}: scan started"
    // Ownership-transfer flag, not an unconditional finally - success must
    // keep holding the lock for the whole scan, not release it here. Flipped
    // true only once responsibility has genuinely passed to markScanFinished
    // (an early failure) or to the scheduled watchdog/reaper machinery (a
    // real dispatch was handed off) - an exception before either point means
    // this call is the only thing that will ever release the lock.
    boolean released = false
    try {
    Map compat = probeCompatibility()
    state.compatOk = compat.ok
    state.compatDetail = compat.detail
    // Recorded but never checked - a hub that cannot return usable statusJson
    // was still allowed into phases that depend on that exact endpoint,
    // rather than failing here where the cause is still known.
    if (!compat.ok) {
        markScanFinished(lockToken, "${compat.detail}")
        released = true
        return [acquired: true]
    }
    state.appsDecoded = 0
    state.appsUnreadable = 0
    state.rulesDecoded = 0
    state.rulesSkipped = 0
    state.ruleLinks = 0
    state.appsInert = 0
    state.otherEngines = []
    // Cleared BEFORE fetchDeviceListBulk runs, not after. That call sets
    // scanError itself on failure - clearing it afterward silently erased
    // the one error a user most needed to see, the enumeration that made
    // the whole scan pointless before a single app was even queued.
    state.scanError = null
    state.deviceIdsUnreadable = []
    Map bulk = fetchDeviceListBulk()
    if (bulk.error) {
        markScanFinished(lockToken, "Could not list devices from the hub: ${bulk.error}")
        released = true
        return [acquired: true]
    }
    // Label/room/type are already known for every device from this one call -
    // NOT batched, unlike capabilities below. Only capabilities need a
    // per-driver-type follow-up fetch.
    state.deviceLabels = bulk.labels as Map
    state.deviceRooms = bulk.rooms as Map
    state.deviceTypes = bulk.types as Map
    state.deviceCapabilities = [:]
    // Map of representative device id -> every device id sharing its driver
    // (deviceTypeId), including the representative itself. dispatchDeviceOne
    // fetches capabilities once per representative and applies the result to
    // the whole group - see fetchDeviceListBulk's comment for why this is
    // safe (capabilities are a driver property, verified identical within a
    // driver on this hub).
    List repIds = (bulk.typeGroups as Map).collect { typeKey, ids -> (ids as List)[0] }
    state.scanQueue = []
    // state.scanTotal is the representative/driver-type count dispatchDeviceOne
    // actually iterates over (34 on this hub, not 194) - correct for the
    // dispatch/invariant machinery, but showing it labelled as a device count
    // on the settings page reads as "only 34 devices found", which is wrong
    // and confusing. deviceScanTotal is the real device count, kept
    // separately purely for that display - see main()'s progress paragraph.
    state.deviceScanTotal = (bulk.labels as Map).size()
    state.scanTotal = repIds.size()
    state.scanDone = 0
    state.scanPhase = 'devices'
    state.scanRunning = true
    // Stamped here as well as in the async callbacks, so a scan that never
    // manages to land a single callback still has a timestamp for
    // clearAbandonedScan to age out.
    state.scanHeartbeat = now()
    state.appIds = []
    state.appInfo = [:]
    // Durable proof that the async app accumulator has been published in
    // full. state.scanQueue is deliberately always empty in the async design,
    // so it cannot distinguish "all apps committed" from "the @Field static
    // accumulator disappeared on a code reload". Recovery may build a graph
    // only after this marker is committed true by finalizeAppPhase.
    state.appResultsReady = false
    state.graphVersion = null
    // Dropped, not merely marked stale. Holding the previous graph while
    // appInfo fills doubles peak state for the whole scan - this is Gordon's
    // own hub, and on 2026-08-13, at 74 apps, that was enough to kill a scan
    // two apps from the end: no error logged, no job scheduled, just a
    // heartbeat that stopped (see commits 9ef2359/95a2a10). The old graph is
    // unusable during a scan anyway, since graphVersion is cleared above.
    //
    // Deliberately not attempting double-buffering (holding the old graph
    // live in state while a new one fills) as part of this hardening pass -
    // that is exactly the pattern the 2026-08-13 fix removed, not an
    // untested scale question. This hub is now at 105 apps, larger than the
    // 74 that crashed it, and has run cleanly under the current drop-not-hold
    // design repeatedly, including this hardening pass's own dev-soak test.
    // Re-introducing double buffering would be a real, different design
    // change, and would need its own peak-memory measurement before trusting
    // it - not because this hub is too small to have exercised the old
    // failure at all, but because nothing here has re-tested holding two
    // copies since the fix that stopped doing that.
    state.graph = null
    unschedule('fetchRegistry')
    unschedule('finishScan')

    if (repIds.isEmpty()) {
        // No devices at all - go straight to the app phase, same as an
        // empty representative queue would after finishing normally.
        // released is set AFTER startAppPhase() returns, not before - caught
        // in review: startAppPhase() installs its own recovery machinery
        // (an app accumulator/watchdog, or the registry/finish handoff)
        // partway through its own body, not at its very first line. An
        // exception thrown before that point must still be caught by THIS
        // execution's own catch below, or the lock would be left permanently
        // held with nothing left to ever release it.
        startAppPhase(lockToken)
        released = true
        return [acquired: true]
    }

    String scanId = "devices-${now()}-${(int)(Math.random() * 9999)}"
    ConcurrentHashMap scan = new ConcurrentHashMap()
    scan.total = repIds.size()
    scan.inFlight = new AtomicInteger(0)
    scan.processed = new AtomicInteger(0)
    scan.pending = new ConcurrentLinkedQueue(repIds)
    scan.claims = new ConcurrentHashMap()
    scan.tokenSeq = new AtomicInteger(0)
    // Two separate guards, deliberately not one - finalizeScheduleGuard only
    // proves "a scheduling runIn() was issued", finalizeGuard only proves
    // "the actual state publish happened". Conflating them would let a
    // scheduled job that never runs permanently block the watchdog's own
    // recovery path from ever publishing.
    scan.finalizeScheduleGuard = new AtomicInteger(0)
    scan.finalizeGuard = new AtomicInteger(0)
    scan.capsByDev = new ConcurrentHashMap<String, List>()
    // Seeded from the bulk response and completed by callbacks for devices
    // whose bulk row omitted roomName. Those devices are deliberately queued
    // as one-device groups by fetchDeviceListBulk(), so their fullJson response
    // can recover a room the bulk endpoint omitted without guessing whether an
    // actually blank room means "unassigned".
    scan.roomsByDev = new ConcurrentHashMap<String, String>((bulk.rooms ?: [:]) as Map)
    scan.unreadableDevs = new ConcurrentHashMap<String, Boolean>()
    scan.lastProgressAt = now()   // plain Long, not AtomicLong - Hubitat's sandbox blocks that import; a per-key ConcurrentHashMap write is already atomic enough for a pure overwrite-with-latest-timestamp
    // Copied in, not read from state by a callback - callbacks and reapers
    // must never touch state at all, not even to read. typeGroups was
    // written once, moments ago, in this same execution, so this copy is
    // exactly as current as a read would have been, with none of the
    // question of whether a concurrent execution can safely read state.
    scan.typeGroups = new ConcurrentHashMap((bulk.typeGroups ?: [:]) as Map)
    // This generation's single-flight lock token, so deviceAsyncWatchdog and
    // finalizeDevicePhase - both callback/reap-adjacent contexts that must
    // never touch state - can release the correct lock without reading state.
    scan.lockToken = lockToken
    DEVICE_SCANS[scanId] = scan
    state.deviceScanId = scanId

    // Single-scan-at-a-time within this app instance: reapers/watchdogs are
    // scheduled and unscheduled by handler name, and Hubitat's runIn()
    // replaces rather than stacks a prior job under the same name. Automation
    // Map already enforces one live scan via the UI/clearAbandonedScan, so
    // this matches an existing constraint rather than introducing a new one.
    runIn(DEVICE_ASYNC_WATCHDOG_SEC, 'deviceAsyncWatchdog', [data: [scanId: scanId]])
    runIn(CLAIM_REAP_INTERVAL_SEC, 'deviceClaimReaper', [data: [scanId: scanId]])
    // Handed off here, before dispatch - the watchdog/reaper just scheduled
    // above are what recover this scan from this point on, including if
    // refillDevicePipeline() itself throws on its very first synchronous
    // call. A stall they can't explain is exactly deviceAsyncWatchdog's own
    // existing job, not a new failure mode this lock introduces.
    released = true
    refillDevicePipeline(scanId)
    return [acquired: true]
    } catch (Exception ex) {
        if (!released) markScanFinished(lockToken, "Unexpected error starting scan: ${ex.message}")
        throw ex
    }
}

void refillDevicePipeline(String scanId) {
    // Iterative, not recursive: dispatchDeviceOne() returns true whenever it
    // made progress (a successful dispatch, or a rollback+requeue/terminal-fail
    // after a synchronous throw), so looping here bounds the work to the
    // pending queue's size instead of growing the call stack on repeated
    // synchronous failures. Verified against real repeated synchronous
    // failures in the isolated dispatch-test harness before being relied on
    // here.
    while (dispatchDeviceOne(scanId)) { /* keep refilling */ }
}

// CAS-bounded dispatch: reserves a slot in the in-flight pool (<=
// DEVICE_ASYNC_MAX_INFLIGHT), pops the next pending representative device id
// (or requeued retry, carrying its prior attempt count), records a claim
// before ever calling asynchttpGet, and issues an async fullJson fetch for
// that representative's capabilities. Every failure path below rolls back
// the reservation and the claim via conditional removal - see the isolated
// test's dispatch-throw/claim-reaper findings for why an unconditional
// remove is not safe once callbacks and the reaper can genuinely overlap.
boolean dispatchDeviceOne(String scanId) {
    ConcurrentHashMap scan = DEVICE_SCANS[scanId]
    if (scan == null) return false

    AtomicInteger inFlight = scan.inFlight as AtomicInteger
    while (true) {
        int n = inFlight.get()
        if (n >= DEVICE_ASYNC_MAX_INFLIGHT) return false
        if (inFlight.compareAndSet(n, n + 1)) break
    }

    def raw = (scan.pending as ConcurrentLinkedQueue).poll()
    if (raw == null) {
        inFlight.decrementAndGet()
        return false
    }
    String repId = (raw instanceof Map) ? (raw as Map).id as String : raw as String
    int attemptCount = ((raw instanceof Map) ? ((raw as Map).attemptCount ?: 0) as Integer : 0) + 1

    String attemptToken = "tok-${(scan.tokenSeq as AtomicInteger).incrementAndGet()}"
    Map myClaim = [attemptToken: attemptToken, dispatchedAt: now(), attemptCount: attemptCount]
    (scan.claims as ConcurrentHashMap)[repId] = myClaim

    try {
        asynchttpGet('deviceFetchCb',
            [uri: "${LOOPBACK_BASE}/device/fullJson/${repId}", contentType: 'application/json', timeout: 10],
            [scanId: scanId, repId: repId, attemptToken: attemptToken])
        return true
    } catch (Exception ex) {
        log.warn "${app.label}: device ${repId} dispatch threw: ${ex.message}"
        // Ownership proven the same way the callback/reaper prove it below -
        // only this execution could plausibly hold this exact claim object
        // (nothing else has had a chance to touch it since it was created a
        // few lines above), but the conditional remove costs nothing and
        // keeps every retirement path in this pipeline consistent.
        boolean owned = (scan.claims as ConcurrentHashMap).remove(repId, myClaim)
        if (owned) inFlight.decrementAndGet()
        if (owned) {
            scan.lastProgressAt = now()
            if (attemptCount < ATTEMPT_CAP) {
                (scan.pending as ConcurrentLinkedQueue) << [id: repId, attemptCount: attemptCount]
            } else {
                // Every device sharing this representative's driver, not just
                // the representative itself - a driver-group capability
                // failure is a failure for the whole group, same as the real
                // callback path below marks it.
                deviceGroupFor(scan, repId).each { String devId -> (scan.unreadableDevs as ConcurrentHashMap)[devId] = true }
                (scan.processed as AtomicInteger).incrementAndGet()
            }
        }
        maybeFinalizeDevicePhase(scanId)
        return true   // made progress; refillDevicePipeline's loop tries again
    }
}

// Shared by dispatchDeviceOne's terminal-throw path, deviceFetchCb, and
// reapDeviceClaim's terminal path - every device sharing repId's driver,
// from the scan-local copy of typeGroups (never state - see the comment
// where scan.typeGroups is populated in startScan).
List deviceGroupFor(ConcurrentHashMap scan, String repId) {
    Map typeGroups = (scan.typeGroups ?: [:]) as Map
    return (typeGroups.values().find { (it as List).contains(repId) } ?: [repId]) as List
}

// Async callback for /device/fullJson/{representative id}. Applies the
// result to every device sharing that representative's driver, releases the
// claim and the in-flight slot (only if this execution actually owns the
// claim - see the ownership note in dispatchDeviceOne), then refills and
// checks for completion.
void deviceFetchCb(resp, data) {
    String scanId = data.scanId as String
    ConcurrentHashMap scan = DEVICE_SCANS[scanId]
    if (scan == null) return  // callback from a prior, already-finalized scan

    String repId = data.repId as String
    String attemptToken = data.attemptToken as String
    Map claim = (scan.claims as ConcurrentHashMap)[repId] as Map

    if (claim == null || claim.attemptToken != attemptToken) return   // stale or duplicate

    boolean owned = (scan.claims as ConcurrentHashMap).remove(repId, claim)
    if (!owned) return   // lost the race - deviceClaimReaper already retired this exact attempt

    List group = deviceGroupFor(scan, repId)

    List caps = null
    try {
        if (resp?.status == 200) {
            Map respData = (resp.json instanceof Map) ? (resp.json as Map) : [:]
            Map dev = respData.device as Map
            if (dev) {
                caps = (dev.capabilities ?: []) as List
                if (dev.roomName) {
                    String room = "${dev.roomName}".trim()
                    // A room belongs to this device, not to its driver. Normal
                    // capability groups contain many devices whose rooms differ,
                    // so broadcasting the representative's room corrupts every
                    // peer in that group. Missing-bulk-room devices are already
                    // queued as singleton groups; writing only repId recovers
                    // their fullJson room without touching any other device.
                    ConcurrentHashMap roomsByDev = scan.roomsByDev as ConcurrentHashMap
                    if (room && !roomsByDev.containsKey(repId)) roomsByDev[repId] = room
                }
            }
        }
    } catch (Exception ex) {
        log.warn "${app.label}: device ${repId} lookup failed: ${ex.message}"
    }

    if (caps == null) {
        group.each { String devId -> (scan.unreadableDevs as ConcurrentHashMap)[devId] = true }
    } else {
        group.each { String devId -> (scan.capsByDev as ConcurrentHashMap)[devId] = caps }
    }

    (scan.processed as AtomicInteger).incrementAndGet()
    (scan.inFlight as AtomicInteger).decrementAndGet()
    // Scan-local progress marker, not state - see the top-of-pipeline
    // comment on why callbacks/reapers never write state at all, not even a
    // best-effort progress field. scanStatusJson() reads this live.
    scan.lastProgressAt = now()

    refillDevicePipeline(scanId)
    maybeFinalizeDevicePhase(scanId)
}

// Active recovery for claims that were dispatched but never got any callback
// at all - not a per-item HTTP failure (those already resolve in one
// callback via caps==null above), but genuine platform-level silence.
// Self-reschedules via runIn every CLAIM_REAP_INTERVAL_SEC until the phase
// finalizes. Ownership is proven via conditional removal against a claim
// value snapshotted at scan time, exactly as verified in the isolated
// dispatch-test harness - see that harness's claimReaper()/reapOne() for the
// full reasoning this mirrors.
void deviceClaimReaper(data) {
    String scanId = data?.scanId as String
    ConcurrentHashMap scan = DEVICE_SCANS[scanId]
    if (scan == null) return
    if ((scan.finalizeGuard as AtomicInteger).get() == 1) return

    long nowMs = now()
    Map claims = scan.claims as ConcurrentHashMap
    List<Map> staleCandidates = []
    claims.each { repId, claim ->
        long dispatchedAt = (claim as Map).dispatchedAt as Long
        if (nowMs - dispatchedAt >= CLAIM_REAP_DEADLINE_MS) {
            staleCandidates << [repId: repId as String, claim: claim as Map]
        }
    }
    staleCandidates.each { c -> reapDeviceClaim(scanId, c.repId as String, c.claim as Map) }

    ConcurrentHashMap scan2 = DEVICE_SCANS[scanId]
    if (scan2 != null && (scan2.finalizeGuard as AtomicInteger).get() != 1) {
        runIn(CLAIM_REAP_INTERVAL_SEC, 'deviceClaimReaper', [data: [scanId: scanId]])
    }
}

void reapDeviceClaim(String scanId, String repId, Map candidateClaim) {
    ConcurrentHashMap scan = DEVICE_SCANS[scanId]
    if (scan == null) return

    long ageMs = now() - (candidateClaim.dispatchedAt as Long)
    if (ageMs < CLAIM_REAP_DEADLINE_MS) return

    Map claims = scan.claims as ConcurrentHashMap
    boolean owned = claims.remove(repId, candidateClaim)
    if (!owned) return   // resolved or replaced since the scan - not this attempt's to reap

    int attemptCount = candidateClaim.attemptCount as Integer
    log.warn "${app.label}: device ${repId} claim reaped after ${ageMs}ms with no callback (attempt ${attemptCount})"

    (scan.inFlight as AtomicInteger).decrementAndGet()
    scan.lastProgressAt = now()

    if (attemptCount < ATTEMPT_CAP) {
        (scan.pending as ConcurrentLinkedQueue) << [id: repId, attemptCount: attemptCount]
    } else {
        // Whole driver group, not just the representative - same reasoning
        // as dispatchDeviceOne's terminal-throw path.
        deviceGroupFor(scan, repId).each { String devId -> (scan.unreadableDevs as ConcurrentHashMap)[devId] = true }
        (scan.processed as AtomicInteger).incrementAndGet()
    }

    refillDevicePipeline(scanId)
    maybeFinalizeDevicePhase(scanId)
}

// Diagnostic-only safety net for a pipeline that has stalled well past what
// any real callback or reap cycle should take. Deliberately does NOT
// finalize with whatever was collected - see finalizeDevicePhase/
// maybeFinalizeDevicePhase for why publishing a partial result labeled
// complete is worse than failing visibly. Marks the scan failed instead.
void deviceAsyncWatchdog(data) {
    String scanId = data?.scanId as String
    ConcurrentHashMap scan = DEVICE_SCANS[scanId]
    if (scan == null) return   // already finalized normally, nothing to do

    int pending = (scan.pending as ConcurrentLinkedQueue).size()
    int inFlight = (scan.inFlight as AtomicInteger).get()
    int claimsOutstanding = (scan.claims as Map).size()
    int processed = (scan.processed as AtomicInteger).get()
    int total = scan.total as Integer

    if (pending == 0 && inFlight == 0 && claimsOutstanding == 0 && processed == total) {
        // Invariants already satisfied - this is just the watchdog and the
        // last legitimate resolution landing at nearly the same moment.
        // finalizeDevicePhase is itself exactly-once via finalizeGuard, so
        // calling it here is safe either way.
        finalizeDevicePhase(scanId)
        return
    }
    if (processed > total) {
        log.warn "${app.label}: device-phase scan ${scanId} invariant violation - processed=${processed} exceeds total=${total}"
    }

    // CAS the same guard finalizeDevicePhase uses, BEFORE writing anything -
    // a legitimate finalize from a callback/reap landing concurrently with
    // this watchdog execution must win this race, not have its successful
    // publication overwritten by this failure path landing after it. If the
    // CAS fails, a real finalize already happened or is in progress and this
    // execution has nothing left to do.
    if (!(scan.finalizeGuard as AtomicInteger).compareAndSet(0, 1)) return

    log.warn "${app.label}: device-phase async scan ${scanId} did not finish within ${DEVICE_ASYNC_WATCHDOG_SEC}s (${processed} of ${total} landed, pending=${pending} inFlight=${inFlight} claims=${claimsOutstanding}) - failing closed, no map published for this scan"
    DEVICE_SCANS.remove(scanId)
    unschedule('deviceClaimReaper')
    // Honest, not "kept the previous map" - startScan already wiped
    // state.graph/appInfo/deviceCapabilities before this scan's first
    // dispatch even went out, so there is no previous map left to retain by
    // this point, only that no NEW, possibly-incomplete one gets published
    // in its place. Real retention (double buffering) is deliberately not
    // attempted - see BACKLOG.md.
    markScanFinished(scan.lockToken as String, "Device scan stalled (${processed}/${total} landed) - failed rather than publish an incomplete map")
}

// Called once invariants are exactly satisfied, from whichever path notices
// that first (a callback or a reap). Deliberately does NOT call
// finalizeDevicePhase() inline - this function still runs as part of a
// callback/reap execution, and per the production-diff review, even a
// terminal callback with zero other callbacks outstanding is still an async
// execution whose own state snapshot could be stale relative to some other
// concurrent execution. Schedules a fresh, dedicated execution to do the
// actual publish instead, the same discipline this app already uses for the
// registry/graph-build handoff (see beginRegistryAndFinish). finalizeSchedule
// Guard only proves "a scheduling runIn() was issued" - it is not the
// publish-ownership proof, that is still finalizeGuard, checked again inside
// the scheduled execution itself.
void maybeFinalizeDevicePhase(String scanId) {
    ConcurrentHashMap scan = DEVICE_SCANS[scanId]
    if (scan == null) return
    int pending = (scan.pending as ConcurrentLinkedQueue).size()
    int inFlight = (scan.inFlight as AtomicInteger).get()
    int claimsOutstanding = (scan.claims as Map).size()
    int processed = (scan.processed as AtomicInteger).get()
    int total = scan.total as Integer
    if (processed > total) {
        log.warn "${app.label}: device-phase scan ${scanId} invariant violation - processed=${processed} exceeds total=${total}"
        return
    }
    if (pending == 0 && inFlight == 0 && claimsOutstanding == 0 && processed == total) {
        if ((scan.finalizeScheduleGuard as AtomicInteger).compareAndSet(0, 1)) {
            runIn(1, 'finalizeDevicePhaseScheduled', [data: [scanId: scanId]])
        }
    }
}

// The dedicated execution maybeFinalizeDevicePhase schedules - re-verifies
// invariants (state may only have improved since scheduling, since claims
// only ever get reaped/resolved, never added back once the pending queue is
// drained, but re-checking costs nothing and matches step 2/5 of the
// reviewed design) before handing off to finalizeDevicePhase's own
// finalizeGuard CAS, which remains the actual exactly-once publish proof.
void finalizeDevicePhaseScheduled(data) {
    String scanId = data?.scanId as String
    ConcurrentHashMap scan = DEVICE_SCANS[scanId]
    if (scan == null) return   // already finalized - the watchdog's own recovery path got there first
    int pending = (scan.pending as ConcurrentLinkedQueue).size()
    int inFlight = (scan.inFlight as AtomicInteger).get()
    int claimsOutstanding = (scan.claims as Map).size()
    int processed = (scan.processed as AtomicInteger).get()
    int total = scan.total as Integer
    if (!(pending == 0 && inFlight == 0 && claimsOutstanding == 0 && processed == total)) {
        log.warn "${app.label}: device-phase scan ${scanId} no longer satisfies invariants at scheduled finalize (pending=${pending} inFlight=${inFlight} claims=${claimsOutstanding} processed=${processed} total=${total}) - leaving it to the watchdog"
        return
    }
    finalizeDevicePhase(scanId)
}

// Merges the scan's static-accumulated capability results into state in one
// single execution via plain assignment - never putAll onto whatever state
// already held. A merge can only ever add entries, never correct a bad
// starting state; this specific mistake (putAll instead of =) was the second
// confirmed bug behind the data-integrity finding that led to this whole
// rewrite. Guarded exactly-once via finalizeGuard so the scheduled finalizer
// and the watchdog's own already-satisfied recovery path can never both run
// this - both are freshly scheduled executions, never called inline from a
// callback/reap.
void finalizeDevicePhase(String scanId) {
    ConcurrentHashMap scan = DEVICE_SCANS[scanId]
    if (scan == null) return
    if (!(scan.finalizeGuard as AtomicInteger).compareAndSet(0, 1)) return   // already finalized

    // finalizeGuard only proves this is the one execution allowed to attempt
    // publishing THIS scanId's results - it says nothing about whether this
    // scanId's generation is still the CURRENT one for the app instance.
    // clearAbandonedScan() can release this generation's lock without
    // touching DEVICE_SCANS or unscheduling its watchdog/reaper, so a late
    // finalize for an already-abandoned generation can still reach here
    // after a newer one has started. Checked before the first state write,
    // not just relied on at an eventual markScanFinished() call - this path
    // hands off to startAppPhase() on success and never calls
    // markScanFinished() at all in that case.
    if (!ownsLock(scan.lockToken as String)) {
        log.info "${app.label}: device-phase finalize for a superseded scan generation, discarding without publishing"
        DEVICE_SCANS.remove(scanId)
        unschedule('deviceAsyncWatchdog')
        unschedule('deviceClaimReaper')
        return
    }

    DEVICE_SCANS.remove(scanId)
    unschedule('deviceAsyncWatchdog')
    unschedule('deviceClaimReaper')

    try {
        state.deviceCapabilities = new LinkedHashMap(scan.capsByDev as Map)
        state.deviceRooms = new LinkedHashMap(scan.roomsByDev as Map)

        List unreadable = []
        (scan.unreadableDevs as ConcurrentHashMap).keySet().each { String devId -> unreadable << devId }
        state.deviceIdsUnreadable = unreadable

        state.scanDone = scan.total as Integer
        state.scanHeartbeat = now()
    } catch (Exception ex) {
        log.warn "${app.label}: device-phase finalization failed: ${ex.message}"
        markScanFinished(scan.lockToken as String, "${ex.message}")
        return
    }

    startAppPhase(scan.lockToken as String)
}

// Apps are discovered entirely from /hub2/appsList - device-led discovery
// (walking appsUsing on every device) was dropped from the device-phase
// fetch, verified on this hub to contribute zero apps the listing didn't
// already have. state.appIds is therefore always empty entering this
// function.
//
// lockToken is passed explicitly by both callers, deliberately not read
// back from a shared/state field here - caught in review: this function can
// run from finalizeDevicePhase(), a separately scheduled execution that
// could in principle fire late, after its own generation was abandoned and
// a NEWER one has since started. Reading "the current token" from anywhere
// shared at that point would let this late execution build an app-phase
// accumulator labelled with the WRONG (newer) generation's token, which
// could then legitimately release a scan it has nothing to do with.
// Carrying the caller's own remembered token instead closes that.
void startAppPhase(String lockToken) {
    Set appIds = new LinkedHashSet(state.appIds as List)
    String selfId = "${app.id}"
    // Best-effort, not fatal - see fetchAppTypeNamespaces()'s own comment.
    // Fetched once here rather than per-app, same reasoning as appsList.
    Map appTypeNamespaceResult = fetchAppTypeNamespaces()
    if (appTypeNamespaceResult.error) {
        log.info "${app.label}: namespace lookup unavailable this scan - ${appTypeNamespaceResult.error}"
    }
    Map appListing = fetchInstalledAppIds()
    if (appListing.error) {
        log.warn "${app.label}: app phase could not start: ${appListing.error}"
        markScanFinished(lockToken, "Could not list installed apps: ${appListing.error}")
        return
    }
    (appListing.ids as List).each { String appId ->
        if (appId != selfId) appIds << appId
    }
    // Re-checked here, not only trusted from the caller's own earlier check -
    // fetchInstalledAppIds() is a real HTTP call, and abandonment plus a
    // fresh acquisition can interleave during it exactly as easily as around
    // any other gap in this pipeline.
    if (!ownsLock(lockToken)) {
        log.info "${app.label}: app-phase start for a superseded scan generation, discarding without publishing"
        return
    }
    state.appIds = appIds as List

    state.scanPhase = 'apps'
    state.scanTotal = appIds.size()
    state.scanDone = 0
    state.scanQueue = []

    if (appIds.isEmpty()) {
        // A genuinely app-less hub has a complete empty result. Commit the
        // same invariant finalizeAppPhase sets for a non-empty app scan.
        state.appResultsReady = true
        beginRegistryAndFinish(lockToken)
        return
    }

    String scanId = "apps-${now()}-${(int)(Math.random() * 9999)}"
    ConcurrentHashMap scan = new ConcurrentHashMap()
    scan.total = appIds.size()
    scan.inFlight = new AtomicInteger(0)
    scan.processed = new AtomicInteger(0)
    scan.pending = new ConcurrentLinkedQueue(appIds)
    scan.claims = new ConcurrentHashMap()
    scan.tokenSeq = new AtomicInteger(0)
    // Two separate guards - see the device-scan creation comment on why
    // scheduling proof and publish proof must not share one CAS.
    scan.finalizeScheduleGuard = new AtomicInteger(0)
    scan.finalizeGuard = new AtomicInteger(0)
    scan.appInfo = new ConcurrentHashMap<String, Map>()
    // Seeded from the device phase's complete bulk label inventory, not an
    // empty map - the device phase already correctly found every device's
    // label; app callbacks only ever ADD to or improve on that (a device
    // referenced by an app setting, childDevice or subscription), they never
    // need to be the sole source. Seeding from a fresh copy of what
    // finalizeDevicePhase just published avoids the alternative bug: an
    // empty starting map here meant finalizeAppPhase's plain-assignment
    // replace of state.deviceLabels would DROP every device not referenced
    // by any app, even though the bulk fetch had correctly found it. This
    // is not the same "merge onto possibly-stale state" hazard the appInfo/
    // capsByDev replace-not-merge fix guards against - this copy is taken
    // from data this exact scan generation just finished publishing, not
    // from whatever an unrelated earlier scan left behind.
    scan.labels = new ConcurrentHashMap<String, String>((state.deviceLabels ?: [:]) as Map)
    scan.appTypeNamespaces = new ConcurrentHashMap<String, String>((appTypeNamespaceResult.namespaces ?: [:]) as Map)
    scan.decoded = new AtomicInteger(0)
    scan.unreadable = new AtomicInteger(0)
    scan.rulesDecoded = new AtomicInteger(0)
    scan.rulesSkipped = new AtomicInteger(0)
    scan.otherEngines = new ConcurrentHashMap<String, Boolean>()
    scan.lastProgressAt = now()   // plain Long, not AtomicLong - Hubitat's sandbox blocks that import; a per-key ConcurrentHashMap write is already atomic enough for a pure overwrite-with-latest-timestamp
    // The caller's own remembered token, carried forward - see this
    // function's own comment for why it is deliberately never read back
    // from anywhere shared/mutable instead.
    scan.lockToken = lockToken
    APP_SCANS[scanId] = scan
    state.appScanId = scanId

    runIn(APP_ASYNC_WATCHDOG_SEC, 'appAsyncWatchdog', [data: [scanId: scanId]])
    runIn(CLAIM_REAP_INTERVAL_SEC, 'appClaimReaper', [data: [scanId: scanId]])
    refillAppPipeline(scanId)
}

void refillAppPipeline(String scanId) {
    while (dispatchAppOne(scanId)) { /* keep refilling */ }
}

// CAS-bounded dispatch, same shape and same rollback/claim discipline as
// dispatchDeviceOne above - see that function's comment for the full
// reasoning, not repeated per phase.
boolean dispatchAppOne(String scanId) {
    ConcurrentHashMap scan = APP_SCANS[scanId]
    if (scan == null) return false

    AtomicInteger inFlight = scan.inFlight as AtomicInteger
    while (true) {
        int n = inFlight.get()
        if (n >= APP_ASYNC_MAX_INFLIGHT) return false
        if (inFlight.compareAndSet(n, n + 1)) break
    }

    def raw = (scan.pending as ConcurrentLinkedQueue).poll()
    if (raw == null) {
        inFlight.decrementAndGet()
        return false
    }
    String appId = (raw instanceof Map) ? (raw as Map).id as String : raw as String
    int attemptCount = ((raw instanceof Map) ? ((raw as Map).attemptCount ?: 0) as Integer : 0) + 1

    String attemptToken = "tok-${(scan.tokenSeq as AtomicInteger).incrementAndGet()}"
    Map myClaim = [attemptToken: attemptToken, dispatchedAt: now(), attemptCount: attemptCount]
    (scan.claims as ConcurrentHashMap)[appId] = myClaim

    try {
        asynchttpGet('appFetchCb',
            [uri: "${LOOPBACK_BASE}/installedapp/statusJson/${appId}", contentType: 'application/json', timeout: 20],
            [scanId: scanId, appId: appId, attemptToken: attemptToken])
        return true
    } catch (Exception ex) {
        log.warn "${app.label}: app ${appId} dispatch threw: ${ex.message}"
        boolean owned = (scan.claims as ConcurrentHashMap).remove(appId, myClaim)
        if (owned) inFlight.decrementAndGet()
        if (owned) {
            scan.lastProgressAt = now()
            if (attemptCount < ATTEMPT_CAP) {
                (scan.pending as ConcurrentLinkedQueue) << [id: appId, attemptCount: attemptCount]
            } else {
                Map info = [id: appId, label: "App ${appId}", type: null, namespace: null, roles: [:], flow: [], stateful: [],
                            ruleLinks: [], endpoints: [], hubVarWrites: [], hubVarReads: [],
                            error: "dispatch threw ${attemptCount}x: ${ex.message}"]
                (scan.appInfo as ConcurrentHashMap)[appId] = info
                (scan.unreadable as AtomicInteger).incrementAndGet()
                (scan.processed as AtomicInteger).incrementAndGet()
            }
        }
        maybeFinalizeAppPhase(scanId)
        return true
    }
}

// Async callback for /installedapp/statusJson/{id}. Runs the same
// processAppRelationships() the old synchronous batch used, then releases
// the claim and slot (only if this execution actually owns the claim).
void appFetchCb(resp, data) {
    String scanId = data.scanId as String
    ConcurrentHashMap scan = APP_SCANS[scanId]
    if (scan == null) return

    String appId = data.appId as String
    String attemptToken = data.attemptToken as String
    Map claim = (scan.claims as ConcurrentHashMap)[appId] as Map

    if (claim == null || claim.attemptToken != attemptToken) return

    boolean owned = (scan.claims as ConcurrentHashMap).remove(appId, claim)
    if (!owned) return

    Map info
    try {
        if (resp?.status == 200) {
            Map respData = (resp.json instanceof Map) ? (resp.json as Map) : [:]
            info = processAppRelationships(appId, respData, scan.labels as ConcurrentHashMap, scan.appTypeNamespaces as Map)
        } else {
            info = [id: appId, label: "App ${appId}", type: null, namespace: null, roles: [:], flow: [], stateful: [],
                    ruleLinks: [], endpoints: [], hubVarWrites: [], hubVarReads: [],
                    error: "HTTP ${resp?.status ?: 'n/a'}"]
        }
    } catch (Exception ex) {
        info = [id: appId, label: "App ${appId}", type: null, namespace: null, roles: [:], flow: [], stateful: [],
                ruleLinks: [], endpoints: [], hubVarWrites: [], hubVarReads: [], error: "${ex.message}"]
    }
    (scan.appInfo as ConcurrentHashMap)[appId] = info

    if (info.error) {
        (scan.unreadable as AtomicInteger).incrementAndGet()
    } else {
        (scan.decoded as AtomicInteger).incrementAndGet()
    }
    if (info.flow) {
        (scan.rulesDecoded as AtomicInteger).incrementAndGet()
    } else if ("${info.type}".startsWith('Rule-') && "${info.type}" != SUPPORTED_RULE_ENGINE) {
        (scan.otherEngines as ConcurrentHashMap)["${info.type}"] = true
        (scan.rulesSkipped as AtomicInteger).incrementAndGet()
    }

    (scan.processed as AtomicInteger).incrementAndGet()
    (scan.inFlight as AtomicInteger).decrementAndGet()
    // Scan-local progress marker, not state - callbacks/reapers never write
    // state at all. scanStatusJson() reads this live.
    scan.lastProgressAt = now()

    refillAppPipeline(scanId)
    maybeFinalizeAppPhase(scanId)
}

// Active recovery for app claims that never got any callback - mirrors
// deviceClaimReaper/reapDeviceClaim exactly, see those for the full
// reasoning.
void appClaimReaper(data) {
    String scanId = data?.scanId as String
    ConcurrentHashMap scan = APP_SCANS[scanId]
    if (scan == null) return
    if ((scan.finalizeGuard as AtomicInteger).get() == 1) return

    long nowMs = now()
    Map claims = scan.claims as ConcurrentHashMap
    List<Map> staleCandidates = []
    claims.each { appId, claim ->
        long dispatchedAt = (claim as Map).dispatchedAt as Long
        if (nowMs - dispatchedAt >= CLAIM_REAP_DEADLINE_MS) {
            staleCandidates << [appId: appId as String, claim: claim as Map]
        }
    }
    staleCandidates.each { c -> reapAppClaim(scanId, c.appId as String, c.claim as Map) }

    ConcurrentHashMap scan2 = APP_SCANS[scanId]
    if (scan2 != null && (scan2.finalizeGuard as AtomicInteger).get() != 1) {
        runIn(CLAIM_REAP_INTERVAL_SEC, 'appClaimReaper', [data: [scanId: scanId]])
    }
}

void reapAppClaim(String scanId, String appId, Map candidateClaim) {
    ConcurrentHashMap scan = APP_SCANS[scanId]
    if (scan == null) return

    long ageMs = now() - (candidateClaim.dispatchedAt as Long)
    if (ageMs < CLAIM_REAP_DEADLINE_MS) return

    Map claims = scan.claims as ConcurrentHashMap
    boolean owned = claims.remove(appId, candidateClaim)
    if (!owned) return

    int attemptCount = candidateClaim.attemptCount as Integer
    log.warn "${app.label}: app ${appId} claim reaped after ${ageMs}ms with no callback (attempt ${attemptCount})"

    (scan.inFlight as AtomicInteger).decrementAndGet()
    scan.lastProgressAt = now()

    if (attemptCount < ATTEMPT_CAP) {
        (scan.pending as ConcurrentLinkedQueue) << [id: appId, attemptCount: attemptCount]
    } else {
        Map info = [id: appId, label: "App ${appId}", type: null, namespace: null, roles: [:], flow: [], stateful: [],
                    ruleLinks: [], endpoints: [], hubVarWrites: [], hubVarReads: [],
                    error: "no callback within ${CLAIM_REAP_DEADLINE_MS}ms (attempt ${attemptCount})"]
        (scan.appInfo as ConcurrentHashMap)[appId] = info
        (scan.unreadable as AtomicInteger).incrementAndGet()
        (scan.processed as AtomicInteger).incrementAndGet()
    }

    refillAppPipeline(scanId)
    maybeFinalizeAppPhase(scanId)
}

// Diagnostic-only safety net, same fail-closed shape as deviceAsyncWatchdog -
// see that function's comment for the full reasoning behind not finalizing
// with a partial result.
void appAsyncWatchdog(data) {
    String scanId = data?.scanId as String
    ConcurrentHashMap scan = APP_SCANS[scanId]
    if (scan == null) return

    int pending = (scan.pending as ConcurrentLinkedQueue).size()
    int inFlight = (scan.inFlight as AtomicInteger).get()
    int claimsOutstanding = (scan.claims as Map).size()
    int processed = (scan.processed as AtomicInteger).get()
    int total = scan.total as Integer
    int appInfoSize = (scan.appInfo as Map).size()
    int decoded = (scan.decoded as AtomicInteger).get()
    int unreadable = (scan.unreadable as AtomicInteger).get()

    if (pending == 0 && inFlight == 0 && claimsOutstanding == 0 && processed == total
            && appInfoSize == total && decoded + unreadable == total) {
        finalizeAppPhase(scanId)
        return
    }
    if (processed > total || appInfoSize > total) {
        log.warn "${app.label}: app-phase scan ${scanId} invariant violation - processed=${processed} appInfo=${appInfoSize} total=${total}"
    }

    // CAS the same guard finalizeAppPhase uses, BEFORE writing anything - see
    // deviceAsyncWatchdog's comment for the full race this closes.
    if (!(scan.finalizeGuard as AtomicInteger).compareAndSet(0, 1)) return

    log.warn "${app.label}: app-phase async scan ${scanId} did not finish within ${APP_ASYNC_WATCHDOG_SEC}s (${processed} of ${total} landed, pending=${pending} inFlight=${inFlight} claims=${claimsOutstanding}) - failing closed, no map published for this scan"
    APP_SCANS.remove(scanId)
    unschedule('appClaimReaper')
    // Honest, not "kept the previous map" - see deviceAsyncWatchdog's
    // comment; the same applies here, state.appInfo was already wiped to
    // [:] in startScan before this scan's first dispatch went out.
    markScanFinished(scan.lockToken as String, "App scan stalled (${processed}/${total} landed) - failed rather than publish an incomplete map")
}

// Does NOT call finalizeAppPhase() inline - see maybeFinalizeDevicePhase's
// comment for why, identical reasoning applies here.
void maybeFinalizeAppPhase(String scanId) {
    ConcurrentHashMap scan = APP_SCANS[scanId]
    if (scan == null) return
    int pending = (scan.pending as ConcurrentLinkedQueue).size()
    int inFlight = (scan.inFlight as AtomicInteger).get()
    int claimsOutstanding = (scan.claims as Map).size()
    int processed = (scan.processed as AtomicInteger).get()
    int total = scan.total as Integer
    int appInfoSize = (scan.appInfo as Map).size()
    int decoded = (scan.decoded as AtomicInteger).get()
    int unreadable = (scan.unreadable as AtomicInteger).get()
    if (processed > total || appInfoSize > total) {
        log.warn "${app.label}: app-phase scan ${scanId} invariant violation - processed=${processed} appInfo=${appInfoSize} total=${total}"
        return
    }
    if (pending == 0 && inFlight == 0 && claimsOutstanding == 0 && processed == total
            && appInfoSize == total && decoded + unreadable == total) {
        if ((scan.finalizeScheduleGuard as AtomicInteger).compareAndSet(0, 1)) {
            runIn(1, 'finalizeAppPhaseScheduled', [data: [scanId: scanId]])
        }
    }
}

// The dedicated execution maybeFinalizeAppPhase schedules - see
// finalizeDevicePhaseScheduled's comment, identical reasoning and structure.
void finalizeAppPhaseScheduled(data) {
    String scanId = data?.scanId as String
    ConcurrentHashMap scan = APP_SCANS[scanId]
    if (scan == null) return   // already finalized - the watchdog's own recovery path got there first
    int pending = (scan.pending as ConcurrentLinkedQueue).size()
    int inFlight = (scan.inFlight as AtomicInteger).get()
    int claimsOutstanding = (scan.claims as Map).size()
    int processed = (scan.processed as AtomicInteger).get()
    int total = scan.total as Integer
    int appInfoSize = (scan.appInfo as Map).size()
    int decoded = (scan.decoded as AtomicInteger).get()
    int unreadable = (scan.unreadable as AtomicInteger).get()
    if (!(pending == 0 && inFlight == 0 && claimsOutstanding == 0 && processed == total
            && appInfoSize == total && decoded + unreadable == total)) {
        log.warn "${app.label}: app-phase scan ${scanId} no longer satisfies invariants at scheduled finalize (pending=${pending} inFlight=${inFlight} claims=${claimsOutstanding} processed=${processed} appInfo=${appInfoSize} total=${total}) - leaving it to the watchdog"
        return
    }
    finalizeAppPhase(scanId)
}

// Merges the scan's static-accumulated results into state in one single
// execution via plain assignment - never putAll. See finalizeDevicePhase's
// comment; the same confirmed bug applied here, on state.appInfo, and is
// fixed the same way. CAS-guarded exactly-once via finalizeGuard - the
// scheduled finalizer and the watchdog's own already-satisfied recovery path
// are both freshly scheduled executions, never called inline from a
// callback/reap.
void finalizeAppPhase(String scanId) {
    ConcurrentHashMap scan = APP_SCANS[scanId]
    if (scan == null) return
    if (!(scan.finalizeGuard as AtomicInteger).compareAndSet(0, 1)) return

    // See finalizeDevicePhase()'s identical comment - finalizeGuard proves
    // only exactly-once for THIS scanId, not that this generation is still
    // current, and this path hands off to beginRegistryAndFinish() on
    // success without ever calling markScanFinished().
    if (!ownsLock(scan.lockToken as String)) {
        log.info "${app.label}: app-phase finalize for a superseded scan generation, discarding without publishing"
        APP_SCANS.remove(scanId)
        unschedule('appAsyncWatchdog')
        unschedule('appClaimReaper')
        return
    }

    APP_SCANS.remove(scanId)
    unschedule('appAsyncWatchdog')
    unschedule('appClaimReaper')

    try {
        state.appInfo = new LinkedHashMap(scan.appInfo as Map)
        state.deviceLabels = new LinkedHashMap(scan.labels as Map)

        // Plain values, not accumulation - scan.decoded.get() etc. are
        // already the COMPLETE count for this scan (every callback in the
        // pipeline incremented the same counter), not a delta to add onto
        // whatever state already held. += here was the first confirmed bug
        // behind the data-integrity finding: caught live when two scan
        // generations overlapped during testing and appsDecoded ended up
        // reading higher than the total app count on the hub.
        state.appsDecoded = (scan.decoded as AtomicInteger).get()
        state.appsUnreadable = (scan.unreadable as AtomicInteger).get()
        state.rulesDecoded = (scan.rulesDecoded as AtomicInteger).get()
        state.rulesSkipped = (scan.rulesSkipped as AtomicInteger).get()
        List others = []
        (scan.otherEngines as ConcurrentHashMap).keySet().each { String eng -> others << eng }
        state.otherEngines = others

        state.scanDone = scan.total as Integer
        // Last app-result write in this execution. Hubitat commits the whole
        // state snapshot atomically when the execution returns, so recovery
        // can never observe true with only a partial appInfo publication.
        state.appResultsReady = true
        state.scanHeartbeat = now()
    } catch (Exception ex) {
        log.warn "${app.label}: app-phase finalization failed: ${ex.message}"
        markScanFinished(scan.lockToken as String, "${ex.message}")
        return
    }

    beginRegistryAndFinish(scan.lockToken as String)
}

// The registry-fetch-then-graph-build handoff, shared by the normal
// end-of-app-phase path and the empty-app-list path in startAppPhase.
//
// lockToken travels through runIn()'s own data payload from here all the
// way to fetchRegistry and finishScan, rather than either of them reading
// a value back from state - both are separately scheduled executions that could
// in principle fire late, after their own generation was abandoned and a
// newer one has since overwritten state with its own token. Carrying the
// value forward explicitly means a late execution still only ever knows
// its OWN generation's token, never whatever happens to be current by the
// time it runs - see startAppPhase()'s comment for the identical reasoning
// one hop earlier in this same chain.
void beginRegistryAndFinish(String lockToken) {
    // The PENDING marker is written here, not inside fetchRegistry, because
    // state is only committed at the END of an execution - an execution
    // that dies mid-fetch discards everything it wrote, so fetchRegistry
    // structurally cannot record that it started.
    state.registryMeta = [state: 'PENDING', fetched: null, entries: 0,
                          matched: 0, error: null, schemaVersion: null]
    runIn(1, 'fetchRegistry', [data: [lockToken: lockToken]])
    // Watchdog. finishScan is chained off fetchRegistry, so a fetch that
    // dies takes the graph build down with it and the scan never completes
    // at all. Scheduling finishScan again for the same handler replaces
    // this job, so the normal path cancels the watchdog simply by
    // rescheduling it one second out.
    runIn(45, 'finishScan', [data: [lockToken: lockToken]])
}

// Runs as its own scheduled execution between the app phase and the graph
// build. It fetches ~170KB over the internet and parses it, which is far too
// much to bolt onto a batch that is already doing HTTP work - the lesson from
// finishScan, which died when it was called inline.
//
// Failure here is not fatal. The registry is a convenience; the user's own
// declarations are the authority, and an unclassified app type is an explicit,
// visible state rather than a silent absence.
void fetchRegistry(jobData = null) {
    String lockToken = jobData?.lockToken as String
    // Checked before the very first write, including the heartbeat stamp -
    // this is a separately scheduled execution that can in principle fire
    // late, after its own generation was abandoned and a newer one has
    // since started.
    if (!ownsLock(lockToken)) {
        log.info "${app.label}: registry fetch for a superseded scan generation, discarding without publishing"
        return
    }
    state.scanHeartbeat = now()
    List types = discoveredAppTypes()
    List matches = []
    Map meta = [state: 'OK', fetched: null, entries: 0, matched: 0, error: null, schemaVersion: null]

    try {
        Map result = httpFetch(REGISTRY_URL, 30, [contentType: 'application/json'])
        if (!result.ok) throw new Exception(result.error)
        Map data = (result.data instanceof Map) ? (result.data as Map) : [:]
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
    } catch (Exception ex) {
        meta.state = 'ERROR'
        meta.error = "${ex.message}"
        log.warn "${app.label}: registry fetch failed, continuing without it: ${ex.message}"
    }

    // Re-checked here, not only trusted from the entry check above - the
    // registry fetch is a real 30-second-timeout HTTP call, and abandonment
    // plus a fresh acquisition can interleave during it exactly as easily as
    // around any other gap in this pipeline.
    if (!ownsLock(lockToken)) {
        log.info "${app.label}: registry fetch completed for a superseded scan generation, discarding without publishing"
        return
    }

    // Only on success, so a failed fetch keeps the last good set rather than
    // silently emptying the map of everything the registry contributed.
    if (!meta.error) state.registryMatches = matches
    state.registryMeta = meta
    log.info "${app.label}: registry ${meta.error ? 'unavailable' : "gave ${meta.matched} dependency match(es) from ${meta.entries} entries"}"
    runIn(1, 'finishScan', [data: [lockToken: lockToken]])
}

// data.lockToken travels from beginRegistryAndFinish() via fetchRegistry(),
// or (when clearAbandonedScan() calls this directly, bypassing the
// scheduler entirely) is a live SCAN_LOCKS snapshot taken at that call
// site, the same recovery-path reasoning as clearAbandonedScan()'s other
// branch - see markScanFinished()'s comment.
void finishScan(data = null) {
    String lockToken = data?.lockToken as String
    // Nothing is written to state above this point, deliberately - caught
    // in review: an earlier version stamped state.scanHeartbeat and
    // conditionally rewrote state.registryMeta before ever checking
    // ownership, so a stale/superseded execution could still corrupt a
    // newer generation's registry status even though it would correctly
    // fail the finishing claim moments later and publish nothing else.
    // Read-and-compute-locally is fine before the claim (nothing is
    // committed by it), only writing state is not.

    // Still PENDING means fetchRegistry never reached its own bookkeeping, so
    // this execution is the watchdog firing rather than the normal chain. Say
    // so. The alternative is what shipped before: an app that had tried and
    // failed reporting that it had never tried, which is worse than an error.
    // Computed here, written only inside the protected publish below - a real
    // defensive copy, not just a local variable NAME. Caught in review:
    // `state.registryMeta` returns the SAME live Map object state already
    // holds, not a snapshot, so mutating it in place (even before ever
    // reassigning state.registryMeta = regMeta) risks the same "mutate a
    // state-held collection directly" hazard this file already treats as a
    // confirmed bug class elsewhere (see the appInfo/capsByDev
    // replace-not-merge comments) - a stale execution could alter a NEWER
    // generation's registry status through this shared reference before its
    // own finishGeneration() claim ever gets checked, let alone fails.
    Map regMeta = new LinkedHashMap((state.registryMeta ?: [:]) as Map)
    boolean registryStalled = "${regMeta.state}" == 'PENDING'
    if (registryStalled) {
        regMeta.state = 'FAILED'
        regMeta.error = 'the registry fetch did not complete'
    }

    // buildGraph() moved INSIDE the protected closure below, not run before
    // the claim - caught in review (reproduced live 2026-08-24): ownership
    // was previously claimed only once building finished, so nothing
    // stopped every 1.5s status poll's own recovery attempt from building
    // the same graph redundantly in parallel, all racing for one claim that
    // at most one of them could ever win. Worse, when the static lock had
    // been reset entirely (an app code reload wipes @Field state but not
    // durable state.scanRunning) every single attempt lost the claim, so
    // the graph was rebuilt and discarded forever with no path to recovery.
    // Claiming first means at most one execution ever runs buildGraph() at
    // all for a given generation; every other concurrent caller's claim
    // fails immediately and does no work.
    boolean finished = finishGeneration(lockToken, null) {
        // v2.0.14: authoritative Hub Variable inventory. A synchronous,
        // in-process call (getAllGlobalVars() - Bucket/Queue/091-098) with no
        // async round trip of its own, so it is called and published here,
        // inside the same protected closure as buildGraph()/state.graph below
        // - never outside it (Codex review 097 point 7). A failed inventory
        // still publishes (status: 'failed'), so buildGraph() always has a
        // definite, current-generation answer to read rather than stale state
        // from a previous scan.
        state.hubVariableInventory = fetchHubVariableInventory()
        Map graph = buildGraph()
        state.scanHeartbeat = now()
        if (registryStalled) {
            state.registryMeta = regMeta
            log.warn "${app.label}: registry fetch did not complete, continuing without it"
        }
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

        // v2.0.14: read by compatibilitySummary() for the "N with a Connector"
        // count. Captured from the built graph the same way ruleLinks/appsInert
        // above are, rather than recomputed at page-render time.
        state.hubVariableConnectorCount = (graph.hubVariableConnectorCount ?: 0) as Integer

        log.info "${app.label}: scan complete - ${(state.appInfo as Map).size()} app(s), ${(state.deviceLabels as Map).size()} device(s)"
    }
    if (!finished) {
        // The claim is now checked before buildGraph() runs, so a
        // superseded generation never builds a graph at all any more - it
        // just loses the claim immediately and does nothing further.
        log.info "${app.label}: finishScan for a superseded scan generation, not building or publishing"
    }
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
// Bulk device enumeration. Replaces the old fetchAllDeviceIds() + per-device
// fetchDeviceApps() pair for everything except capabilities: this one call
// carries label, room and driver name for every device at once - fields that
// previously cost a /device/fullJson round trip each. Verified field mapping
// against the per-device endpoint for the same device before relying on it:
// data.name is the device's LABEL (not its type - a different bulk endpoint,
// /device/list/data, calls the label "label" and puts the type in "name",
// the opposite way round), data.type is the driver name, data.roomName is the
// room. Confirmed flat - no nested children - and enumerates the identical
// device set as the old /device/listJson?capability=capability.* call.
//
// Also groups devices by deviceTypeId (driver): capabilities are a property
// of the driver, not the individual device, and every device sharing a
// driver was confirmed (sampled) to report identical capabilities - see the
// scan-speed item in BACKLOG.md. typeGroups remains scan-local: it holds one
// representative device id per driver, mapped to every device id sharing
// that driver; deviceFetchCb fetches capabilities for the representative
// only and applies the result to the whole group. The exception is a device
// whose bulk row omits roomName: it gets a one-device group so fullJson can
// recover an endpoint-omitted room (or confirm it is genuinely unassigned)
// without broadcasting one atypical response across its whole driver group.
Map fetchDeviceListBulk() {
    Map out = [labels: [:], rooms: [:], types: [:], typeGroups: [:], error: null]
    Map result = httpFetch("${LOOPBACK_BASE}/hub2/devicesList", 30)
    if (!result.ok) {
        log.warn "${app.label}: could not list devices: ${result.error}"
        out.error = result.error
        return out
    }
    Map data = (result.data instanceof Map) ? (result.data as Map) : [:]
    Map typeGroups = [:]
    (data.devices ?: []).each { entry ->
        Map d = (entry instanceof Map) ? (entry.data as Map) : null
        if (!d || d.id == null) return
        String devId = "${d.id}"
        if (d.name) out.labels[devId] = "${d.name}"
        String room = d.roomName == null ? '' : "${d.roomName}".trim()
        if (room) out.rooms[devId] = room
        if (d.type) out.types[devId] = "${d.type}"
        String typeKey = room ? "${d.deviceTypeId}" : "room:${devId}"
        List group = (typeGroups[typeKey] = typeGroups[typeKey] ?: []) as List
        group << devId
    }
    out.typeGroups = typeGroups
    return out
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
Map fetchInstalledAppIds() {
    List ids = []
    Map out = [ids: ids, error: null]
    Map result = httpFetch("${LOOPBACK_BASE}/hub2/appsList", 30)
    if (!result.ok) {
        // The old comment claimed this could fall back to device-led app
        // discovery. That path was removed by the async rewrite, so returning
        // [] here silently publishes a zero-app map. Fail visibly instead.
        out.error = result.error ?: 'the hub returned no error detail'
        return out
    }
    if (!(result.data instanceof Map) || !((result.data as Map).apps instanceof List)) {
        out.error = 'the hub returned an unexpected apps-list response'
        return out
    }
    collectAppIds((result.data as Map).apps, ids)
    ids = ids.unique()
    // This app is necessarily installed and /hub2/appsList is the complete
    // installed-app inventory. Its absence proves the response is empty or
    // incomplete, even on a hub with no other user apps.
    String selfId = "${app.id}"
    if (!ids.contains(selfId)) {
        out.error = "the installed-app listing omitted Automation Map (${selfId})"
        return out
    }
    out.ids = ids
    return out
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

// installedApp itself carries no namespace (confirmed live 2026-08-26 against
// this hub's /installedapp/statusJson/{id} - Bucket/Queue/107). It does carry
// appTypeId, which /hub2/userAppTypes - a separate, definition-level bulk
// endpoint, one call regardless of app count - maps to the real namespace.
// Failure here degrades to every app having a null namespace rather than
// failing the scan; namespace is an enhancement for the Community Context
// Card match, not something core scanning depends on.
Map fetchAppTypeNamespaces() {
    Map out = [status: 'ok', error: null, namespaces: [:]]
    Map result = httpFetch("${LOOPBACK_BASE}/hub2/userAppTypes", 30)
    if (!result.ok) {
        out.status = 'failed'
        out.error = result.error ?: 'the hub returned no error detail'
        return out
    }
    if (!(result.data instanceof List)) {
        out.status = 'failed'
        out.error = 'the hub returned an unexpected userAppTypes response'
        return out
    }
    Map namespaces = [:]
    (result.data as List).each { entry ->
        if (!(entry instanceof Map)) return
        Map e = entry as Map
        if (e.id == null || !e.namespace) return
        namespaces["${e.id}"] = "${e.namespace}"
    }
    out.namespaces = namespaces
    return out
}

// Pure processing, split out of fetchAppRelationships so the async scan
// pipeline's callback can run it directly on data it already has, with no
// second fetch. Mutates labels in place, same as before the split - the
// async pipeline gives each concurrent callback its own per-scan labels map
// to mutate safely, merged into state.deviceLabels only at finalization, not
// state.deviceLabels itself (concurrent callbacks racing on state directly
// is exactly the failure mode this whole rewrite exists to avoid).
Map processAppRelationships(String appId, Map data, Map labels, Map appTypeNamespaces = [:]) {
    Map out = [id: appId, label: "App ${appId}", type: null, namespace: null, roles: [:], flow: [], stateful: [], ruleLinks: [], endpoints: [], hubVarWrites: [], hubVarReads: [], error: null]
    try {
            Map installedApp = data.installedApp as Map
            String rawLabel = stripReplacementChar((installedApp?.label ?: installedApp?.trueLabel ?: installedApp?.name ?: "App ${appId}") as String)
            out.label = stripTags(rawLabel)
            // Kept alongside the full label rather than replacing it: the
            // status is real information, it just does not belong painted
            // across the canvas. See nodeEntry for which form goes where.
            out.drawLabel = stripStatusMarkup(rawLabel)
            out.type = stripReplacementChar(installedApp?.name as String)
            // definitionName's namespace, for the Community Context Card match
            // only (spec section 4.1) - never added to the AI-friendly export.
            if (installedApp?.appTypeId != null) {
                out.namespace = appTypeNamespaces["${installedApp.appTypeId}"]
            }
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
                return out
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
    } catch (Exception ex) {
        out.error = ex.message
        log.warn "${app.label}: app ${appId} processing failed: ${ex.message}"
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
    // Device-list KEYS (Hubitat device IDs), parallel to settingDevices'
    // stripped-label values, same iteration order (LinkedHashMap). Added for
    // v2.0.14's writeSource (Codex review 097 point 1, confirmed against this
    // exact code 2026-08-26): settingDevices alone only ever held the display
    // label, so write.sourceDevice could never be joined authoritatively to
    // devices[] - schema 4 forbids joining writeSource by display name.
    Map settingDeviceIds = [:]
    (data.appSettings ?: []).each { s ->
        if (!(s instanceof Map)) return
        String n = "${s.name}"
        Map dl = s.deviceList as Map
        if (dl) {
            settingDevices[n] = dl.values().collect { stripTags("${it}") }
            settingDeviceIds[n] = dl.keySet().collect { "${it}" }
        }
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
            List srcDeviceIds = settingDeviceIds["customDev.${num}"] ?: []
            if (attr && srcDevices) {
                write.sourceDevice = srcDevices[0]
                write.sourceAttr = attr
                // ID-based, for the structured writeSource edge field. May be
                // absent even when sourceDevice/sourceAttr are present (an
                // older cached scan, or a shape this has not seen) - the
                // structured field is only ever emitted when this resolves.
                if (srcDeviceIds) write.sourceDeviceId = srcDeviceIds[0]
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
    // Empty caps like the four above, but detected off deviceType (the
    // driver name) rather than name - see autoDetectIconKeyForDevice(). A
    // Scene device (CoCoHue Scene, Hubitat's own Groups and Scenes) declares
    // PushableButton same as a real button/remote, so capability alone
    // resolves it to 'buttons', and its own name ("Dining - Relax") carries
    // no hint either.
    [key: 'scene',     label: 'Scene',                caps: []],
    // Empty caps, detected off deviceType like 'scene' above - v2.0.14's
    // synthesized Connector device nodes (buildGraph(), for a Hub Variable
    // Connector Hubitat's own /hub2/devicesList does not list - see
    // Supporting Docs/hub_variable_v2014_implementation_spec.md) hardcode this
    // key directly, since the caller already knows it is a connector. A real,
    // independently-discovered Connector device would also match here via its
    // driver name containing "Connector" (Hubitat's own Variable Connector
    // driver family), the same deviceType-substring pattern 'scene' uses.
    [key: 'connector', label: 'Hub Variable connector', caps: []],
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
    // 'bridge' specifically, not the broader "Hub & infrastructure" grouping
    // the class comment above rules out by capability - CoCoHue Bridge and
    // similar devices consistently carry "Bridge" in their own name even
    // though nothing in their capability list says so.
    [key: 'hub',       words: ['bridge']],
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

// deviceType is the driver name (e.g. "CoCoHue Scene"), checked before both
// the name hints and capability - it is the one signal here that is not the
// user's own naming choice, so a substring match on it carries far less
// false-positive risk than the same match would against a device's name.
// Needed specifically for Scene devices: they declare PushableButton same as
// a real button/remote, and their names ("Dining - Relax") say nothing about
// being a scene at all, so neither of the other two signals can find them.
String autoDetectIconKeyForDevice(String name, List capabilities, String deviceType = null) {
    if (deviceType && deviceType.toLowerCase().contains('scene')) return 'scene'
    if (deviceType && deviceType.toLowerCase().contains('connector')) return 'connector'
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
// - Hub & infrastructure (bridges, repeaters, network monitors) has no
//   capability signal: NetworkDevice, the one that looks relevant, is also
//   carried by ordinary media devices (the Chromecast speakers all declare
//   it), so using it here would misclassify them. Checked directly against
//   CoCoHue Bridge and Hub Information Driver when Gordon first asked for a
//   "Hub" category: CoCoHue Bridge reports only [Actuator, Refresh,
//   Initialize] - no capability distinguishes a bridge/hub device from any
//   other integration-managed actuator. Bridges specifically are handled by
//   name instead (ICON_NAME_HINTS' 'bridge' -> hub entry below) - repeaters
//   and network monitors still have no signal, capability or name, and
//   remain unclassified.
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
    'network', 'display', 'scene', 'connector', 'unknown',
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

// Name (and deleted status) of a rule referenced by another rule. The app
// phase creates an appInfo entry for every id returned by the complete
// /hub2/appsList inventory, including an explicit fallback entry when an
// app's relationship endpoint is unreadable. Therefore a target absent from
// appInfo was not installed at scan time and is a dangling/deleted reference.
// Resolving that fact from the completed inventory keeps buildGraph entirely
// in-memory: graph finalization and abandoned-scan recovery can never stall on
// a sequence of synchronous 10-second loopback lookups. Cached because a rule
// can be both a flowchart target and a graph edge target.
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
    boolean missing = !appInfo.containsKey(targetId)
    // Named so the user can act on it. "Rule 2328" invites a hunt for a
    // rule that is not there; saying so turns it into a finding.
    if (!label && missing) label = "Rule ${targetId} - deleted"
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

// Authoritative Hub Variable inventory via Hubitat's in-process SmartApp API,
// NOT an HTTP endpoint - confirmed live 2026-08-26 after a dedicated search for
// a loopback endpoint came back seven 404s (Bucket/Queue/091-095). Called only
// from finishScan()'s finishGeneration() closure, synchronously, so a failed or
// stale inventory is published or discarded atomically with the rest of that
// scan generation - never mixed into a different generation's graph (Codex
// review 097 point 7).
Map fetchHubVariableInventory() {
    try {
        Map allVars = getAllGlobalVars()
        if (allVars == null) {
            return [status: 'failed', error: 'getAllGlobalVars() returned null', count: 0,
                    source: 'authoritative-hub-inventory', variables: [:]]
        }
        return [status: 'complete', error: null, count: allVars.size(),
                source: 'authoritative-hub-inventory', variables: allVars]
    } catch (Exception e) {
        log.warn "${app.label}: getAllGlobalVars() failed - ${e.message}"
        return [status: 'failed', error: "${e.message}", count: 0,
                source: 'authoritative-hub-inventory', variables: [:]]
    }
}

// Map a platform Hub Variable type spelling to the canonical schema-4 value,
// case-insensitively. Confirmed live 2026-08-26 against real test variables of
// all five types (Bucket/Queue/094 for "string"; a v2.0.14 export of TestNumber/
// TestDecimal/TestBoolean/TestDateTime for the rest): getAllGlobalVars()
// returns Groovy runtime type names, not the UI's declared-type labels -
// "integer" for Number, "bigdecimal" for Decimal, "boolean" and "datetime"
// matching directly. The first live export (schema 4) showed variableType:
// null for TestNumber/TestDecimal because this mapping only recognized
// "number"/"decimal" at the time - the safe fallback worked exactly as
// designed (no crash, no wrong guess - Codex review 097 point 6), and this is
// the confirmed correction, not a guess. Unrecognized input still returns
// null.
String normalizeHubVariableType(String rawType) {
    switch ("${rawType}".toLowerCase()) {
        case 'integer': return 'Number'
        case 'bigdecimal': return 'Decimal'
        case 'string': return 'String'
        case 'boolean': return 'Boolean'
        case 'datetime': return 'DateTime'
        default: return null
    }
}

Map buildGraph() {
    Map labels = (state.deviceLabels ?: [:]) as Map
    Map deviceCaps = (state.deviceCapabilities ?: [:]) as Map
    Map deviceTypes = (state.deviceTypes ?: [:]) as Map
    Map iconOverrides = (state.deviceIconOverrides ?: [:]) as Map
    Map iconNotes = (state.deviceIconNotes ?: [:]) as Map
    Map appInfo = (state.appInfo ?: [:]) as Map

    Map<String, Map> nodes = [:]
    List<Map> edges = []
    // Set, not List: dedup key membership is checked once per candidate edge,
    // and a linear contains() over a growing List made the whole build quadratic.
    Set<String> seen = new LinkedHashSet<>()
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

    // Authoritative Hub Variable inventory (v2.0.14), published by finishScan()
    // into state.hubVariableInventory inside its finishGeneration() closure -
    // read here as already-durable, generation-consistent state, the same way
    // appInfo/deviceLabels above are. Seeded into `nodes` BEFORE the per-app
    // write/read loop below, so that loop's existing `if (!nodes[varNodeId])`
    // guards naturally treat an inventory-sourced node as already present
    // (parent spec 6.3 reconciliation) instead of overwriting it.
    Map hubVarInventory = (state.hubVariableInventory ?: [:]) as Map
    boolean hubVarInventoryComplete = "${hubVarInventory.status}" == 'complete'
    Map hubVarInventoryVars = (hubVarInventory.variables ?: [:]) as Map
    // Findings: a proven structured reference to a name absent from a COMPLETE
    // inventory (not promoted to a node - parent spec 6.3, Codex review 097
    // point 5). There is no equivalent "Connector missing" finding: a
    // reported Connector deviceId is trusted unconditionally below (Codex
    // review 103 confirmed this against live hub data), so no code path can
    // ever fail to resolve one - see the synthesized-node comment just below
    // for why, and the orphan/stale-ID limitation this trade-off leaves.
    List unresolvedHubVarReferences = []
    int hubVarConnectorCount = 0
    hubVarInventoryVars.each { String varName, meta ->
        if (!varName) return
        String varNodeId = "v${varName}"
        Map m = (meta instanceof Map) ? (meta as Map) : [:]
        nodes[varNodeId] = nodeEntry(varNodeId, varName, 'hubVariable')
        nodes[varNodeId].variableType = normalizeHubVariableType(m.type as String)
        nodes[varNodeId].identitySource = 'hub-inventory'
        String connDevId = m.deviceId ? "${m.deviceId}" : null
        if (connDevId) {
            // Diagnosed live 2026-08-26 against three real connectors: Hub
            // Variable Connector devices do not appear in /hub2/devicesList,
            // the bulk endpoint this app's own device discovery uses -
            // `labels` stayed at its pre-connector size across three
            // consecutive scans while getGlobalVar() correctly reported all
            // three deviceIds throughout. getGlobalVar()'s deviceId is still
            // Hubitat's own authoritative confirmation that this Connector
            // exists - an ID-based reference, not the display-name join
            // parent spec 7.2 actually warns against - so it is trusted
            // directly. When independent discovery DID find the device
            // (labels.containsKey), its real label/type is used; otherwise a
            // minimal device node is synthesized here rather than silently
            // dropping the relationship. `labels` itself is never written -
            // it may share state.deviceLabels' backing object (the
            // mutate-a-state-held-collection hazard this file treats as a
            // confirmed bug class elsewhere), so the synthesized node goes
            // straight into `nodes`, which buildGraph() already owns.
            String devNodeId = "d${connDevId}"
            boolean discovered = labels.containsKey(connDevId)
            if (!discovered && !nodes[devNodeId]) {
                nodes[devNodeId] = nodeEntry(devNodeId, "${varName} Connector" as String, 'device')
                // Hardcoded, not autoDetectIconKeyForDevice() - this call site
                // already knows it is a connector (that is why the node was
                // synthesized at all), so there is nothing to detect.
                nodes[devNodeId].icon = 'connector'
            }
            nodes[varNodeId].connectorDeviceId = connDevId
            nodes[varNodeId].connectorType = (deviceTypes[connDevId] as String) ?: (m.attribute as String) ?: null
            hubVarConnectorCount++
            String connEdgeKey = "${varNodeId}|${devNodeId}|synchronizedWith"
            if (!seen.contains(connEdgeKey)) {
                seen << connEdgeKey
                edges << [from: varNodeId, to: devNodeId, kind: 'synchronizedWith']
            }
        }
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
        // Browser-local Community Context Card matching only (spec section
        // 4.1) - deliberately absent from buildExportPayload()'s apps[]
        // mapping, so it never reaches the AI-friendly export.
        if (appMap.namespace) nodes[appNodeId].namespace = "${appMap.namespace}"
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
                    autoDetectIconKeyForDevice((labels[devId] ?: '') as String, deviceCaps[devId] as List,
                                               deviceTypes[devId] as String)
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
            // A proven structured write naming a variable absent from a
            // COMPLETE authoritative inventory is an unresolved reference, not
            // a node - it is not promoted (parent spec 6.3, Codex review 097
            // point 5). When inventory failed/is absent, behaviour below is
            // unchanged from before v2.0.14 (reference-derived fallback).
            if (hubVarInventoryComplete && !hubVarInventoryVars.containsKey(varName)) {
                unresolvedHubVarReferences << [name: varName, appId: appNodeId, kind: 'write']
                return
            }
            if (!nodes[varNodeId]) {
                nodes[varNodeId] = nodeEntry(varNodeId, varName, 'hubVariable')
                nodes[varNodeId].identitySource = 'reference-derived'
            }
            String key = "${appNodeId}|${varNodeId}|write"
            if (seen.contains(key)) return
            seen << key
            Map edge = [from: appNodeId, to: varNodeId, kind: 'write']
            if (w.sourceDevice && w.sourceAttr) edge.detail = "from ${w.sourceDevice}.${w.sourceAttr}"
            // Structured, ID-based writeSource - only emitted when the source
            // device ID resolves in the discovered device set, never joined by
            // display label alone (parent spec 7.2, Codex review 097 point 1).
            if (w.sourceDeviceId && w.sourceAttr && labels.containsKey("${w.sourceDeviceId}")) {
                edge.writeSource = [kind: 'deviceAttribute', deviceId: "${w.sourceDeviceId}", attribute: "${w.sourceAttr}"]
            }
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
            // Same unresolved-reference reconciliation as writes above.
            if (hubVarInventoryComplete && !hubVarInventoryVars.containsKey(varName)) {
                unresolvedHubVarReferences << [name: varName, appId: appNodeId, kind: 'read']
                return
            }
            if (!nodes[varNodeId]) {
                nodes[varNodeId] = nodeEntry(varNodeId, varName, 'hubVariable')
                nodes[varNodeId].identitySource = 'reference-derived'
            }
            String key = "${appNodeId}|${varNodeId}|read"
            if (seen.contains(key)) return
            seen << key
            // usageRole: 'unknown-read' for every currently-decoded read; this
            // app does not yet classify trigger/condition/action-input/
            // text-substitution roles. Parent spec 6.2 explicitly prefers
            // unknown-read over an invented role - first-class inventory is
            // not delayed on complete role classification.
            edges << [from: appNodeId, to: varNodeId, kind: 'read', usageRole: 'unknown-read']
        }
    }

    // Device discovery is hub-wide, while the role pass above only encounters
    // devices referenced by an app. Preserve every existing referenced device
    // node exactly as built above, then append only discovered devices that had
    // no relationship. This keeps existing node order, icons and edges stable
    // while making the graph, Focus device list and AI export agree with the
    // scan's device count.
    labels.each { String devId, label ->
        String devNodeId = "d${devId}"
        if (nodes[devNodeId]) return

        nodes[devNodeId] = nodeEntry(devNodeId, (label ?: "Device ${devId}") as String, 'device')
        nodes[devNodeId].icon = (iconOverrides[devId] as String) ?:
            autoDetectIconKeyForDevice((label ?: '') as String, deviceCaps[devId] as List,
                                       deviceTypes[devId] as String)
        String note = (iconNotes[devId] as String)?.trim()
        if (note) nodes[devNodeId].title = "${nodes[devNodeId].title} (noted: ${note})"
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

    return [nodes: nodes.values().toList(), edges: edges, flows: flows,
            hubVariableUnresolvedReferences: unresolvedHubVarReferences,
            hubVariableConnectorCount: hubVarConnectorCount]
}

// Scheduled rather than called inline from a save handler so the web request
// is limited to persisting the user's change. Scheduling it 1 second out, the
// same pattern beginRegistryAndFinish already uses for fetchRegistry, answers
// the POST immediately and lets the rebuild happen independently.
// Runs by handler name, so two saves close together simply reschedule the
// same job rather than queuing two rebuilds.
void rebuildStoredGraph() {
    state.graph = buildGraph()
    state.graphVersion = GRAPH_SCHEMA
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
    int bracket = s.lastIndexOf(']')
    if (bracket >= 0) {
        // Bracketed IPv6 literal, e.g. [::1]:8080 - a port colon only ever
        // appears after the closing bracket, never inside the literal itself.
        int portColon = s.indexOf(':', bracket)
        if (portColon > bracket) s = s.substring(0, portColon)
    } else {
        int colon = s.lastIndexOf(':')
        if (colon > 0) s = s.substring(0, colon)
    }
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
    // Rebuilt from stored scan data rather than rescanning - the declarations
    // changed, the hub did not - and rebuilt off the request entirely (see
    // rebuildStoredGraph()) rather than inline. externalsJson() below does not
    // read state.graph, so the response is unaffected by the rebuild being
    // deferred.
    runIn(1, 'rebuildStoredGraph')
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
    // Same reasoning as externalsSaveMapping: rebuilt off the request via
    // rebuildStoredGraph(), not inline - the overrides changed, the hub did
    // not, and iconOverridesJson() below does not read state.graph.
    runIn(1, 'rebuildStoredGraph')
    log.info "${app.label}: saved ${incoming.size()} device icon override(s), ${incomingNotes.size()} note(s)"
    return render(status: 200, contentType: 'application/json', data: iconOverridesJson())
}

String iconOverridesJson() {
    Map labels = (state.deviceLabels ?: [:]) as Map
    Map rooms = (state.deviceRooms ?: [:]) as Map
    Map caps = (state.deviceCapabilities ?: [:]) as Map
    Map types = (state.deviceTypes ?: [:]) as Map
    Map overrides = (state.deviceIconOverrides ?: [:]) as Map
    Map notes = (state.deviceIconNotes ?: [:]) as Map

    List devices = labels.collect { String devId, label ->
        [
            id: devId,
            name: label,
            room: rooms[devId] == null ? '' : "${rooms[devId]}".trim(),
            detected: autoDetectIconKeyForDevice(label as String, caps[devId] as List, types[devId] as String),
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
    log.info "${app.label}: /scan endpoint reached"
    try {
        // Guarded the same way scheduledScanHandler() already is - a repeat
        // GET while a scan is running must not restart it. Restarting orphans
        // the previous scan's own fetchRegistry/finishScan jobs, which is
        // exactly the stale-watchdog failure startScan()'s own unschedule()
        // calls now guard against; skipping here closes the other half of
        // that same gap. Answering with the current status instead of an
        // error keeps the page's poll loop working unchanged either way.
        //
        // state.scanRunning here is a fast-path check only, harmless if
        // stale - startScan()'s own atomic lock is the real guard. A second
        // execution can read this as false before the winner's own commit
        // lands (state only commits durably at the end of a whole
        // execution), call startScan(), and lose that lock instead - not a
        // failure, just this execution finding out it isn't the owner, and
        // worth its own distinct log line rather than folding into the
        // fast-path message below.
        //
        // casLost is tracked separately from "did this request skip
        // starting a scan" - caught in review: an ordinary request arriving
        // while a scan is already fully running (the state.scanRunning==true
        // branch) is NOT the same situation as losing the CAS race, and must
        // not be forced into the same "alreadyStarting" response. Only the
        // genuine race case needs the override; the ordinary case already
        // has an accurate live state.scanRunning to report as-is.
        boolean casLost = false
        if (state.scanRunning) {
            log.info "${app.label}: /scan reached while a scan is already running, not restarting"
        } else {
            Map result = startScan()
            if (!result.acquired) {
                casLost = true
                log.info "${app.label}: /scan reached but another start already owns this instance, not restarting"
            }
        }
        // Inside the try, not after it. Left outside, an exception in
        // scanStatusJson() or render() escaped this handler entirely and
        // produced the same unexplained HTML page the handler exists to stop.
        //
        // forceRunning (the casLost case) exists because this execution's
        // own state.scanRunning can still read false here even though a
        // scan genuinely is starting - it is the LOSING execution's stale
        // snapshot, not the winner's, and scanStatusJson() would otherwise
        // report a truthfully-wrong "running:false".
        return render(status: 200, contentType: 'application/json', data: scanStatusJson(casLost))
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

// forceRunning exists for exactly one caller: scanMapping() when this
// execution lost the startScan() single-flight lock to a concurrent one.
// This execution's own state.scanRunning is that losing execution's stale
// pre-commit snapshot, not the winner's - reporting it as false would be a
// truthfully-wrong "nothing is happening" while a scan genuinely starts.
String scanStatusJson(boolean forceRunning = false) {
    // state.scanQueue/scanDone/scanHeartbeat are only ever written once, by
    // the phase-starting execution itself - never by a callback or reaper,
    // which must stay entirely state-free. During an active phase the true
    // live counters are DEVICE_SCANS/APP_SCANS[scanId]'s own accumulator;
    // read directly from there rather than report a frozen, misleadingly-
    // early snapshot for the whole phase.
    ConcurrentHashMap liveScan = null
    if (state.scanPhase == 'devices') liveScan = DEVICE_SCANS[state.deviceScanId as String]
    else if (state.scanPhase == 'apps') liveScan = APP_SCANS[state.appScanId as String]
    int queued = liveScan ? (liveScan.pending as ConcurrentLinkedQueue).size() : (state.scanQueue ?: []).size()
    def done = liveScan ? (liveScan.processed as AtomicInteger).get() : state.scanDone
    def heartbeat = liveScan ? (liveScan.lastProgressAt as Long) : state.scanHeartbeat
    return JsonOutput.toJson([
        running: forceRunning || (state.scanRunning as boolean),
        alreadyStarting: forceRunning,
        phase: state.scanPhase,
        done: done,
        total: state.scanTotal,
        queued: queued,
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
        heartbeat: heartbeat,
        graphVersion: state.graphVersion,
    ])
}

Map renderMapMapping() {
    // The settings-page link is already hidden during a scan, but an existing
    // tab, bookmark or copied URL can still request this endpoint directly.
    // startScan deliberately retains the previous graph while the replacement
    // is assembled, and its supporting state maps are being replaced phase by
    // phase, so rendering during that window produces an internally mixed map.
    // Refuse the endpoint until publication finishes rather than presenting
    // stale and current data as one coherent result.
    if (state.scanRunning) {
        return render(
            status: 200,
            contentType: 'text/html',
            data: """<!doctype html><html><head><meta charset="utf-8"><title>Automation Map - scan in progress</title></head>
<body style="background:#062733; color:#eee; font-family:sans-serif; padding:2em; line-height:1.5">
<h2>Scan in progress</h2>
<p>The map is temporarily unavailable while Automation Map discovers and publishes the new data.</p>
<p>Return to the Automation Map app when the scan has completed, then open the map again.</p>
<button type="button" onclick="history.back()" style="padding:0.65em 1em; cursor:pointer">Back</button>
</body></html>"""
        )
    }
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
    // v2.0.14: schema 4 - hubVariables changes meaning from an inferred
    // referenced subset to an authoritative inventory when available, plus
    // Connector topology and completeness metadata (parent spec 11.1). Not a
    // dual-export mode - schema 3 files remain readable by their own
    // consumers, but this app now generates schema 4 only (Codex review 097
    // point 4).
    Map hubVarInventoryMeta = (state.hubVariableInventory ?: [:]) as Map
    Map scanMeta = [
        exportSchemaVersion: 4,
        graphSchemaVersion: GRAPH_SCHEMA,
        scanHeartbeatMs: state.scanHeartbeat,
        scanError: state.scanError,
        appsUnreadable: state.appsUnreadable ?: 0,
        devicesUnreadable: ((state.deviceIdsUnreadable ?: []) as List).size(),
        // Inventory completeness kept separate from relationship-decoder
        // completeness (parent spec 6.1) - a consumer must not assume one
        // implies the other.
        hubVariableInventoryStatus: hubVarInventoryMeta.status ?: 'not-supported',
        hubVariableInventoryError: hubVarInventoryMeta.error,
        hubVariableInventoryCount: hubVarInventoryMeta.count ?: 0,
        hubVariableInventorySource: hubVarInventoryMeta.source,
    ]
    String scanMetaJsonStr = jsonForScriptEmbed(scanMeta)
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
     deliberately, not by whatever the CDN resolves to on a given day.

     integrity is pinned alongside the version for the same reason a version
     pin alone was not enough: this page is served from the hub's own origin
     with a live OAuth access token in the URL, so anything that executes
     here can read that token and reach the hub admin UI same-origin. A
     version number pins which release SHOULD load; integrity pins the exact
     bytes that actually did, so a compromised or tampered CDN response fails
     closed (the browser refuses to execute it) instead of running with the
     hub's own trust. Regenerate both hashes if either version above is ever
     bumped - they are tied to these exact files, not the package version. -->
<script src="https://unpkg.com/vis-network@10.1.1/standalone/umd/vis-network.min.js" integrity="sha384-hQiS3pHN272vQg3Yxv+h9eJDB+peejHT2uA031YxhWTxH7miNr5arcgJD2Ytx3uS" crossorigin="anonymous"></script>
<script src="https://cdn.jsdelivr.net/npm/mermaid@10.9.8/dist/mermaid.min.js" integrity="sha384-N3QqR/7q+xm3BGX+CBbNI8AUmRRqcsDzToy+0z1NLDI0QmTKW8zvwLvqulJgk3dP" crossorigin="anonymous"></script>
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
  /* Sits behind the network canvas (earlier in DOM order, no z-index of its
     own, and vis-network's own canvas has no background fill so empty space
     around the graph shows whatever is layered underneath it). Fixed, not
     absolute - pinned to a fixed point on the actual screen regardless of
     where physics settles the graph's own bounding box. Moved off dead
     centre (was 50/50) since a fully-populated graph's own node cluster
     tends to sit left-of-centre; positioned below #controls specifically
     (not just anywhere clear of the graph) per Gordon's own instruction,
     confirmed against a live screenshot rather than guessed. */
  #hubWatermark { position:fixed; top:68%; left:82%; transform:translate(-50%, -50%);
                  max-width:38vw; max-height:38vh; opacity:0.50; pointer-events:none;
                  user-select:none; }
  /* Hub photo specifically shown at half the Christmas tree's size, per
     Gordon's request - the tree's own dimensions (38vw/38vh) are unaffected. */
  #hubWatermark.hubPhoto { max-width:19vw; max-height:19vh; }
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
  /* Below whatever showFlow()/showInertPanel() put in #flowChart, not inside
     it - #flowChart gets fully overwritten on every re-render (a fresh
     mermaid SVG, or a fresh inert-app summary), which would wipe this out if
     it shared that container. Most of its typography rides #flow's own
     h4/p/.sub/a rules above; only what is specific to this card is added
     here. */
  /* Deliberately lighter than the surrounding #flow panel's near-black, not
     just a lighter accent within it - visually this is public, external
     evidence about the package, not something the hub itself reported (spec
     3.1's "cannot be mistaken for data read from the hub"), and the contrast
     against #flow's own dark theme is the clearest way to say so at a glance.
     Overrides every #flow-inherited color (h4/.sub/a) that would otherwise
     stay light-on-light here. */
  #communityCard { margin-top:14px; padding:12px 14px; border-radius:6px; background:#eef3f5; color:#1a2733; }
  #communityCard h4 { color:#1a2733; margin-top:0; }
  #communityCard .sub { color:#4a5a63; }
  #communityCard a { color:#1565c0; }
  #communityCard .ccBadge { display:inline-block; padding:1px 7px; border-radius:3px; font-size:0.75em; margin:0 6px 6px 0; background:#d7e6ea; color:#2c4a55; }
  #communityCard .ccCaution { color:#a05a1f; }
  #communityCard .ccLinks a { margin-right:12px; }
  #communityCard.ccClickable { cursor:pointer; }
  #communityCard.ccClickable:hover { background:#e3ecef; }${''}
  /* Fully opaque, not near-opaque: at 0.97 the legend behind it still showed
     through as ghost text across the middle of the table. Marker just above:
     the whole <style> block is one unbroken GString literal (no interpolation
     anywhere in it) - the JVM caps a single compiled string constant at 65535
     UTF-8 code units, and this block is close enough to that ceiling that
     adding this card's CSS crossed it. This empty interpolation splits the
     constant in two without changing anything rendered; needed again if this
     block grows much further. */
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
  /* Same "one panel's own CSS" convention as #ext/#pivot/#icons above, not a
     reused class - see those panels' own comments for why. */
  /* An explicit width, not just max-width like the other panels here - this
     one needs it for a real reason, not copied without thought. The others
     size themselves from their own content (a table's natural column
     widths); an iframe has none of its own the browser can see, so
     "width:100%" on it had nothing concrete to resolve against inside a
     shrink-to-fit, width-less parent and silently fell back to a browser
     default around 300px regardless of max-width - confirmed live, this is
     exactly what was cramping the chart, not the CSS gap that later comment
     used to describe as the whole story. Matches #pivot's own max-width
     figure - the widest existing panel - which also happens to match the
     spec's own stated upper design bound of 1100 CSS pixels
     (community_release_activity_embed_spec.md section 3.3). */
  /* Centered, unlike #flow/#ext/#pivot/#icons' shared top:100px/left:10px
     corner placement - a deliberate departure for this one panel, not an
     oversight of the convention. A rule flowchart or a data table reads
     fine pinned to a corner; a wide chart the user is meant to actually
     look at does not. 92vw (up from 80vw) reaches the 1100px cap on more
     realistic window widths - the cap itself stays at 1100px, the embed's
     own stated design bound (section 3.3), since widening the panel past
     what the chart itself was built and tested for would add empty space
     around it, not a bigger chart. */
  #releaseActivity { position:absolute; top:50%; left:50%; transform:translate(-50%, -50%); z-index:21; background:#041b23; padding:14px 18px; border-radius:6px;
           width:min(92vw, 1100px); max-height:90vh; overflow:auto; display:none; box-shadow:0 4px 24px rgba(0,0,0,0.55); }
  #releaseActivity h3 { margin:0 0 4px 0; font-size:0.95em; }
  #releaseActivity .sub { opacity:0.72; font-size:0.78em; margin:0 0 12px 0; line-height:1.4; }
  #releaseActivity iframe { border:0; display:block; width:100%; height:500px; border-radius:4px; }
  #releaseActivity a { color:#7fb6d6; text-decoration:none; }
  #releaseActivity a:hover { text-decoration:underline; }
  #releaseActivityClose { position:absolute; top:8px; right:10px; cursor:pointer; background:none; border:none; color:#bbb; font-size:1.1em; }${''}
</style>
</head>
<body>
<div id="status">Devices: ${deviceCount} &nbsp; Apps: ${appCount}</div>
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
  <label>Focus hub variable<input id="hubVarSearch" type="search" placeholder="search hub variables..." autocomplete="off"><select id="hubVarFilter" size="1"><option value="__all__">All hub variables</option></select></label>
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
  <button id="releaseActivityBtn" type="button" title="Preview Hubitat release activity from Community Utilities">Hubitat release activity</button>
  <button id="communityUtilitiesBtn" type="button" style="background:#81BC00; color:#121214;" title="Open the Hubitat Community Utilities site in a new tab">Community utilities</button>
  <button id="exitMapBtn" type="button" title="Return to this app's settings screen">Exit map</button>
</div>
<div id="flow"><button id="flowClose" type="button" title="Close">&times;</button><div id="flowBack" style="display:none"></div><h3 id="flowTitle"></h3><div class="sub" id="flowSub"></div><div id="flowChart"></div><div id="communityCard"></div></div>
<div id="ext"><button id="extClose" type="button" title="Close">&times;</button><div id="extBody"></div></div>
<div id="pivot"><button id="pivotClose" type="button" title="Close">&times;</button><div id="pivotBody"></div></div>
<div id="icons"><button id="iconsClose" type="button" title="Close">&times;</button><div id="iconsBody"></div></div>
<div id="releaseActivity"><button id="releaseActivityClose" type="button" title="Close">&times;</button><h3>Hubitat releases over time</h3><div class="sub">Community Utilities release history and documented changes.</div><div id="releaseActivityBody"></div></div>
<img id="hubWatermark" class="${showSanta() ? '' : 'hubPhoto'}" src="https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/${APP_NAME.contains('(Dev)') ? 'dev' : 'main'}/Images/${showSanta() ? 'Merry%20Christmas.png' : 'hub-from-side.png'}" alt="">
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
  // 'sliders' (was 'sliders-h' pre-FA6) - a preset/adjustable-levels glyph,
  // reasonable fit for a saved lighting scene. Not verified against a
  // rendered page - if this shows as a blank box instead of a glyph, the
  // codepoint is wrong and needs picking again from the actual font file.
  scene: '\uf1de',
  // fa-link (stable FA4-6 codepoint) - chosen for the same reason ICON_RULES'
  // 'connector' entry exists: a Hub Variable Connector device, distinct from
  // an ordinary physical/integration device.
  connector: '\uf0c1',
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
    // v2.0.14, schema 4: carried through explicitly, same as every other
    // field here - this object is a fresh rendering-specific literal, not a
    // spread of `e`, so a field not listed here is silently dropped
    // (buildExportPayload's edges mapping reads these off ALL_EDGES, not
    // GRAPH.edges directly).
    usageRole: e.usageRole || null,
    writeSource: e.writeSource || null,
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
  const hubVarVal = document.getElementById('hubVarFilter').value;
  const kindVal = document.getElementById('kindFilter').value;

  let pool = ALL_EDGES;
  if (kindVal === 'rulelinks') {
    pool = ALL_EDGES.filter(function (e) { return RULE_LINK_KINDS.indexOf(e.kind) !== -1; });
  } else if (kindVal !== 'all') {
    pool = ALL_EDGES.filter(function (e) { return e.kind === kindVal; });
  }

  let ids = null;
  let shownEdges = pool;
  const focusId = appVal !== '__all__' ? appVal : (devVal !== '__all__' ? devVal : (hubVarVal !== '__all__' ? hubVarVal : null));
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
${''}
// Single source of truth for panel coordination. Three separate hardcoded
// copies of this list used to exist - bringToFront, syncLegendVisibility and
// closeSecondaryPanels - and adding the release-activity panel to only two of
// them shipped a real bug Gordon hit immediately: "Show all" and browser Back
// both left that panel sitting open over the map. Functions rather than a
// const array purely so declaration order does not matter: several of these
// panel consts are declared much further down the file than the coordination
// functions that use them.
//
// secondaryPanels() is everything closeSecondaryPanels() may close on its own.
// flowPanel is deliberately NOT in it - its callers hide it themselves,
// because several of them re-open it a moment later with new content.
function secondaryPanels() { return [extPanel, pivotPanel, iconsPanel, releaseActivityPanel]; }
function allPanels() { return [flowPanel].concat(secondaryPanels()); }

function syncLegendVisibility() {
  const lg = document.getElementById('legend');
  const hn = document.getElementById('hint');
  const panelOpen = allPanels().some(function (p) {
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
  allPanels().forEach(function (p) {
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
        (j.cron ? '<br><span class="sub">Schedule: <code>' + extEsc(j.cron) + '</code></span>' : '') + '</li>';
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
  renderCommunityCard(node);
  bringToFront(flowPanel);
}

function showFlow(appId) {
  // Captured after focusNode() has already bumped it for the selection that
  // led here - see focusGenerationSeq's own comment for why this exists.
  const mySelectionSeq = focusGenerationSeq;
  const node = ALL_NODES.filter(function (n) { return n.id === appId; })[0];
  if (node && (node.inert || node.unreadable)) { showInertPanel(node); return; }
  const steps = FLOWS[appId];
  if (!steps || !steps.length || !window.mermaid) {
    // No decoded flow to draw is not the same as nothing to say - the
    // Community Context Card below still applies to every app, decoded flow
    // or not (this used to just hide the panel and show nothing at all,
    // which is exactly what selecting an app like LIFX Light Manager did
    // before the card existed).
    document.getElementById('flowTitle').textContent = node ? node.title : 'App details';
    document.getElementById('flowSub').textContent = 'This app has no decoded rule flow to show.';
    flowChart.innerHTML = '';
    renderCommunityCard(node);
    bringToFront(flowPanel);
    return;
  }
  document.getElementById('flowTitle').textContent = node ? node.title : 'Rule flow';
  // Deliberately free of apostrophes. This page is a Groovy GString, so a
  // backslash-escaped quote is consumed by Groovy and ends the JS string early -
  // a syntax error that kills the entire page.
  document.getElementById('flowSub').textContent = 'Decoded execution order, reconstructed from the internal state of the app. A reading aid: the app page itself remains the authority.';
  flowChart.innerHTML = '';
  const id = 'mmd' + Date.now();
  mermaid.render(id, mermaidFor(steps)).then(function (res) {
    // A newer selection (any type - another app, a device, a hub variable)
    // has already started since this render began. Writing flowChart or
    // re-opening the panel now would silently restore this stale selection
    // over whatever the user has actually picked since.
    if (mySelectionSeq !== focusGenerationSeq) return;
    flowChart.innerHTML = res.svg;
    renderCommunityCard(node);
    bringToFront(flowPanel);
  }).catch(function (err) {
    if (mySelectionSeq !== focusGenerationSeq) return;
    flowChart.textContent = 'Could not render this rule: ' + err.message;
    renderCommunityCard(node);
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

// Community Context Card (Supporting Docs/community_context_card_spec.md,
// contract locked in Bucket/Queue 112/113). A read-only, browser-only lookup
// against a public HPM_Manifest_Crawl projection - never told which app is
// selected, never affects scanning, the map or the export. Lazy-loaded once
// per page view (spec 3.2) and cached in memory only; a failed or invalid
// response degrades this one card to "unavailable", nothing else.
const COMMUNITY_CONTEXT_URL = 'https://gordonthelander.github.io/HPM_Manifest_Crawl/integrations/automation-map/community_context_index.json';
// Generation targets 750 KiB (spec 5.4); this is a client-side ceiling on
// what a response is even allowed to be before it is parsed, not the
// generator's own budget - deliberately looser so a modest catalogue growth
// between Automation Map releases does not start failing this check.
const COMMUNITY_CONTEXT_MAX_BYTES = 1536 * 1024;
// Well above today's 476 records - a bound against a compact response
// carrying an unreasonable number of tiny records (Codex review 115 point
// 1), not a forecast of real catalogue growth.
const COMMUNITY_CONTEXT_MAX_RECORDS = 5000;
const COMMUNITY_CONTEXT_TIMEOUT_MS = 8000;
const COMMUNITY_CONTEXT_AUTHORITY_LABELS = {
  HUBITAT_BUILT_IN: 'Hubitat built-in',
  HPM_PACKAGE: 'HPM package',
  REVIEWED_MANUAL_PROJECT: 'Reviewed manual project',
  COMMUNITY_CATALOGUE_LISTING: 'Community catalogue listing'
};
const COMMUNITY_CONTEXT_LINK_LABELS = { record: 'Full record', documentation: 'Documentation', community: 'Community support', source: 'Source' };
let communityContextPromise = null;
let communityCardRequestSeq = 0;
// Bumped once per focusNode() call, any selection type - separate from
// communityCardRequestSeq above, which only guards the community-context
// fetch itself. This guards showFlow()'s asynchronous Mermaid render: that
// promise can still be pending when a LATER, different selection (another
// app, or a device/hub variable) has already changed what is on screen: if
// the earlier render is allowed to write flowChart / re-open the panel /
// re-render the community card once it finally settles, it silently
// restores a stale selection over the current one (Codex review 115 point
// 3, reproduced by inspection - the community card's own sequence number
// cannot help here, since renderCommunityCard() for a decoded flow is not
// even called until AFTER the stale Mermaid promise resolves).
let focusGenerationSeq = 0;

// One request for the whole page view, whichever app is selected first -
// later selections reuse this same promise (spec 3.2 steps 2-4).
function loadCommunityContext() {
  if (communityContextPromise) return communityContextPromise;
  communityContextPromise = new Promise(function (resolve, reject) {
    const controller = ('AbortController' in window) ? new AbortController() : null;
    const timer = controller ? setTimeout(function () { controller.abort(); }, COMMUNITY_CONTEXT_TIMEOUT_MS) : null;
    fetch(COMMUNITY_CONTEXT_URL, { credentials: 'omit', signal: controller ? controller.signal : undefined })
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.text();
      })
      .then(function (text) {
        // Bytes, not JS string length - text.length undercounts a multi-byte
        // UTF-8 response, the exact case a size gate exists to catch.
        if (new Blob([text]).size > COMMUNITY_CONTEXT_MAX_BYTES) throw new Error('response exceeds the size gate');
        const data = JSON.parse(text);
        if (!data || typeof data !== 'object') throw new Error('not a JSON object');
        if (data.schemaVersion !== '1.0') throw new Error('unsupported schemaVersion ' + data.schemaVersion);
        if (data.dataset !== 'automation-map-community-context') throw new Error('unexpected dataset ' + data.dataset);
        if (!Array.isArray(data.records)) throw new Error('missing records[]');
        // recordCount must itself be a genuine non-negative integer, not
        // merely "a number" - NaN, Infinity and a negative value all pass a
        // bare typeof check (Codex review 115 point 1).
        if (typeof data.recordCount !== 'number' || !isFinite(data.recordCount) ||
            data.recordCount < 0 || Math.floor(data.recordCount) !== data.recordCount) {
          throw new Error('recordCount is not a non-negative integer');
        }
        if (data.records.length !== data.recordCount) {
          throw new Error('recordCount ' + data.recordCount + ' does not match records.length ' + data.records.length);
        }
        if (data.recordCount > COMMUNITY_CONTEXT_MAX_RECORDS) {
          throw new Error('recordCount ' + data.recordCount + ' exceeds the maximum allowed');
        }
        resolve(data);
      })
      .catch(reject)
      .finally(function () { if (timer) clearTimeout(timer); });
  });
  // Deliberately NOT reset on rejection - a page reload is the retry
  // boundary for v1 (Codex review 115 point 2). The first prior version of
  // this comment argued the opposite (a bad response should not poison
  // every later selection), but that meant every app selection after one
  // failure re-fetched and re-waited through the full timeout, which is
  // worse: it contradicts the "downloads the index at most once" gate. A
  // cached rejection still resolves instantly for every later caller -
  // Promise.catch() on an already-settled promise does not re-run anything.
  return communityContextPromise;
}

// Definition identity only (spec section 4), never the user-editable
// instance label - node.appType/node.namespace come from
// processAppRelationships()'s installedApp.name/namespace-via-appTypeId
// join, not node.title (which is the label the user sees and can rename).
// A trailing whitespace-separated version number, optionally "v"-prefixed -
// "Zigbee Map 3.0.4" -> "Zigbee Map", but "Rule Machine Manager" (no
// trailing digits) is untouched. Confirmed live and necessary: this
// installed app's own definitionName IS "Zigbee Map 3.0.4" (the author bakes
// the version into the app's own declared name), while the catalogue's
// manifestIdentity for the same real package - correct namespace and all -
// is plain "Zigbee Map". Deliberately narrow (a numeric-version pattern, not
// word-similarity) so it does not relax spec section 6's "do not infer
// identity from a similar-looking label" rule for anything else.
function ccStripVersionSuffix(name) {
  // No regex literal here at all, on purpose - this whole block is a Groovy
  // GString, and a JS-side regex needs backslash escapes doubled or Groovy's
  // own escape processing consumes the single backslash before the browser
  // ever sees it (check_template.sh's whitelist documents this same doubling
  // for the file's other JS-side regexes, one entry per pattern). Plain
  // character checks sidestep the whole class of hazard rather than adding
  // one more pattern to keep track of.
  function isAllDigits(s) {
    if (!s.length) return false;
    for (let i = 0; i < s.length; i++) {
      const c = s.charAt(i);
      if (c < '0' || c > '9') return false;
    }
    return true;
  }
  const idx = name.lastIndexOf(' ');
  if (idx < 0) return name;
  const tail = name.slice(idx + 1);
  const parts = tail.split('.');
  if (!parts.length || parts.length > 4) return name;
  const versionLike = parts.every(function (p, i) {
    const body = (i === 0 && (p.charAt(0) === 'v' || p.charAt(0) === 'V')) ? p.slice(1) : p;
    return isAllDigits(body);
  });
  return versionLike ? name.slice(0, idx) : name;
}

function ccRunMatchLadder(name, namespace, records) {
  function identitiesMatchingName(r) {
    return (r.definitionIdentities || []).filter(function (di) {
      return String(di.name || '').trim().toLowerCase() === name;
    });
  }
  function confirmed(record) {
    const mismatch = (record.qualityFlags || []).indexOf('IDENTITY_MISMATCH') >= 0;
    return { state: 'confirmed', record: record, identityMismatch: mismatch };
  }

  // Tier 1 (spec 6.1): built-in, exact name only - a built-in has no
  // namespace to match against.
  const builtIns = records.filter(function (r) {
    return r.authority === 'HUBITAT_BUILT_IN' && identitiesMatchingName(r).length > 0;
  });
  if (builtIns.length === 1) return confirmed(builtIns[0]);
  if (builtIns.length > 1) return { state: 'ambiguous', records: builtIns };

  const nameMatches = records.filter(function (r) { return identitiesMatchingName(r).length > 0; });

  if (namespace) {
    // Tier 2 (spec 6.2): exact name and exact namespace.
    const nsMatches = nameMatches.filter(function (r) {
      return identitiesMatchingName(r).some(function (di) {
        return di.namespace && String(di.namespace).trim().toLowerCase() === namespace;
      });
    });
    if (nsMatches.length === 1) return confirmed(nsMatches[0]);
    if (nsMatches.length > 1) return { state: 'ambiguous', records: nsMatches };
    // Tier 3 (spec 6.3) explicitly requires namespace to be absent - it does
    // not apply here. A same-named app under a different namespace is not
    // evidence of the same identity, so this does not fall back to bare-name
    // uniqueness; that would risk a false confident match.
    return { state: 'none' };
  }

  // Tier 3: namespace absent on our side - bare name uniqueness is the last
  // confirming tier. Tier 4 (ambiguous) otherwise.
  if (nameMatches.length === 1) return confirmed(nameMatches[0]);
  if (nameMatches.length > 1) return { state: 'ambiguous', records: nameMatches };
  return { state: 'none' };
}

function matchCommunityContext(data, node) {
  const name = String(node.appType || '').trim().toLowerCase();
  if (!name) return { state: 'none' };
  const namespace = node.namespace ? String(node.namespace).trim().toLowerCase() : null;

  const result = ccRunMatchLadder(name, namespace, data.records);
  if (result.state !== 'none') return result;

  const strippedName = ccStripVersionSuffix(name);
  if (strippedName === name) return result;
  return ccRunMatchLadder(strippedName, namespace, data.records);
}
${''}
// Empty interpolation above: this entire embedded <script> is one unbroken
// GString literal from const SCAN_META down to the next real interpolation,
// well over a thousand lines with no split point anywhere in it - close
// enough to the JVM's 65535-UTF-8-code-unit single-constant ceiling already
// that this feature's own JS pushed the whole style block over it (see the
// matching marker in <style> above). Splitting here defensively rather than
// waiting for a second failed deploy to prove it was needed.
function ccHumanizeCheckKey(k) {
  // A replacer function, not a numbered-backreference replacement string -
  // this whole block is a Groovy GString, so a literal backreference marker
  // in JS source is consumed as an attempted Groovy interpolation and fails
  // to compile. Same hazard class as this file's known apostrophe trap.
  return String(k).replace(/([a-z0-9])([A-Z])/g, function (m, a, b) { return a + ' ' + b; })
    .replace(/^./, function (c) { return c.toUpperCase(); });
}

// https-only (spec section 7) checked again here regardless of what the
// projection already filtered server-side - defense in depth, not the sole
// enforcement point. Iterates the object's own keys rather than a fixed
// four, since the schema leaves links open-ended (additionalProperties).
function ccSafeLinks(links) {
  const out = [];
  Object.keys(links || {}).forEach(function (key) {
    const value = links[key];
    try {
      if (value && new URL(value).protocol === 'https:') {
        out.push({ label: COMMUNITY_CONTEXT_LINK_LABELS[key] || key, url: value });
      }
    } catch (e) { /* not a valid absolute URL - silently skipped, not shown broken */ }
  });
  return out;
}

function ccIsStale(iso) {
  const t = Date.parse(iso);
  if (isNaN(t)) return false;
  return (Date.now() - t) > (7 * 24 * 60 * 60 * 1000);
}

function ccFormatDate(iso) {
  const d = new Date(iso);
  return isNaN(d.getTime()) ? String(iso) : d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

function ccRecordHtml(record, identityMismatch) {
  let html = '<span class="ccBadge">' + extEsc(COMMUNITY_CONTEXT_AUTHORITY_LABELS[record.authority] || record.authority) + '</span>';
  if (identityMismatch) {
    html += '<p class="sub ccCaution">Community Utilities flagged this package - its declared identity did not match its own source code at last check. Treat this match with extra care.</p>';
  }
  html += '<p><b>' + extEsc(record.displayName || record.packageName || 'Unnamed') + '</b>' +
    (record.author ? ' &middot; ' + extEsc(record.author) : '') + '</p>';
  if (record.summary) html += '<p class="sub">' + extEsc(record.summary) + '</p>';
  if (record.evidenceChecks) {
    const checks = Object.keys(record.evidenceChecks).map(function (k) {
      return extEsc(ccHumanizeCheckKey(k)) + ': ' + extEsc(record.evidenceChecks[k]);
    });
    if (checks.length) html += '<p class="sub">Evidence checks - ' + checks.join(', ') + '</p>';
  }
  if (record.networkEvidence) {
    html += '<p class="sub">Network evidence: ' + extEsc(record.networkEvidence.classification) +
      (record.networkEvidence.reviewed ? ' (reviewed)' : ' (not yet reviewed)') + '</p>';
  }
  const links = ccSafeLinks(record.links);
  if (links.length) {
    html += '<p class="ccLinks">' + links.map(function (l) {
      return '<a href="' + extEsc(l.url) + '" target="_blank" rel="noopener noreferrer">' + extEsc(l.label) + '</a>';
    }).join('') + '</p>';
  }
  return html;
}

// Package Explorer supports a plain ?query= filter (verified against its own
// app.js: matches() requires every query token present in the record's
// searchable text, score() gives an exact name match top relevance) - safer
// than deep-linking by record id, since this projection's ids
// ("hpm:...", "manifest:...") are not the same id space Package Explorer's
// own dataset uses, confirmed by comparing both directly rather than assumed.
const COMMUNITY_EXPLORER_URL = 'https://gordonthelander.github.io/HPM_Manifest_Crawl/package-explorer/';
function ccExplorerUrl(name) {
  return COMMUNITY_EXPLORER_URL + '?query=' + encodeURIComponent(name);
}

// Constant, never carrying app or hub identity in its query string (spec
// 3.3's No match row, Codex review 115 point 4) - a plain link to the
// general tool, for the reader to search by hand, not a deep link.
const COMMUNITY_IDENTITY_RESOLVER_URL = 'https://gordonthelander.github.io/HPM_Manifest_Crawl/identity-resolver/';
function ccIdentityResolverLinkHtml() {
  return '<p class="ccLinks"><a href="' + COMMUNITY_IDENTITY_RESOLVER_URL + '" target="_blank" rel="noopener noreferrer">Search the Identity Resolver</a></p>';
}

function ccCardHtml(result, snapshotGenerated) {
  let html = '<h4>Community information</h4>';
  let clickUrl = null;
  if (result.state === 'confirmed') {
    html += ccRecordHtml(result.record, result.identityMismatch);
    const name = result.record.displayName || result.record.packageName;
    if (name) clickUrl = ccExplorerUrl(name);
    // Spec 3.3/Codex review 115 point 4: a flagged identity should not read
    // as a plain clean match with nowhere else to check it - the reader can
    // go verify it themselves, not just take this card's word for it.
    if (result.identityMismatch) html += ccIdentityResolverLinkHtml();
  } else if (result.state === 'ambiguous') {
    html += '<p class="sub">More than one Community Utilities record matches this app by name. None is shown as confirmed - click through to investigate.</p><ul>';
    result.records.slice(0, 5).forEach(function (r) {
      html += '<li>' + extEsc(r.displayName || r.packageName || 'Unnamed') +
        (r.author ? ' &middot; ' + extEsc(r.author) : '') +
        ' <span class="ccBadge">' + extEsc(COMMUNITY_CONTEXT_AUTHORITY_LABELS[r.authority] || r.authority) + '</span></li>';
    });
    html += '</ul>';
    html += ccIdentityResolverLinkHtml();
    const first = result.records[0];
    const name = first && (first.displayName || first.packageName);
    if (name) clickUrl = ccExplorerUrl(name);
  } else {
    html += '<p class="sub">No community information found for this app.</p>';
    html += ccIdentityResolverLinkHtml();
  }
  if (snapshotGenerated) {
    html += '<p class="sub ccSnapshot">Catalogue snapshot: ' + extEsc(ccFormatDate(snapshotGenerated)) +
      (ccIsStale(snapshotGenerated) ? ' - may be out of date' : '') + '</p>';
  }
  html += '<p class="sub">External community evidence - not read from your hub, and never affects the map above.</p>';
  if (clickUrl) html += '<p class="sub">Click this card to open it on the Community Utilities site.</p>';
  return { html: html, clickUrl: clickUrl };
}

// Called for every app selection (spec 3.1: "below Automation Map's own
// discovered facts", for every app, not only ones with a decoded flow or an
// inert reason). communityCardRequestSeq makes a late response from a
// PREVIOUS selection a no-op once a newer one has started (spec section 7's
// race-safety requirement) without needing a second AbortController per
// card - loadCommunityContext()'s single in-flight fetch is shared, only
// which selection gets to use its result changes.
function ccApplyClickable(box, url) {
  // Direct property assignment, not addEventListener - this box is reused
  // across every selection, and a plain assignment always replaces whatever
  // handler (or none) the previous render left behind, with nothing to leak
  // or double-fire the way accumulating listeners would.
  box.classList.toggle('ccClickable', !!url);
  box.onclick = url ? function (ev) {
    // A click landing on one of this card's own links (Source, etc.) must
    // still just follow that link - only a click on the card background
    // itself opens the explorer.
    if (ev.target.closest('a')) return;
    window.open(url, '_blank', 'noopener,noreferrer');
  } : null;
}

function renderCommunityCard(node) {
  const box = document.getElementById('communityCard');
  if (!box) return;
  const seq = ++communityCardRequestSeq;
  if (!node || node.group !== 'app') { box.innerHTML = ''; ccApplyClickable(box, null); return; }
  box.innerHTML = '<h4>Community information</h4><p class="sub">Checking Community Utilities...</p>';
  ccApplyClickable(box, null);
  loadCommunityContext().then(function (data) {
    if (seq !== communityCardRequestSeq) return;
    const rendered = ccCardHtml(matchCommunityContext(data, node), data.snapshotGenerated);
    box.innerHTML = rendered.html;
    ccApplyClickable(box, rendered.clickUrl);
  }).catch(function (e) {
    if (seq !== communityCardRequestSeq) return;
    console.warn('Community information unavailable: ' + e.message);
    box.innerHTML = '<h4>Community information</h4><p class="sub">Community information is temporarily unavailable.</p>';
    ccApplyClickable(box, null);
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
// already maintains, not a new scheme invented for this picklist. Started as
// 17 categories mapped to a fixed three-letter code, agreed with Gordon
// 2026-08-19; 'scene' added 2026-08-21. Six more of ICON_KEYS (locks, safety,
// cameras, shades, sensor, ai) still have no entry here and fall through to
// UNK same as genuine 'unknown' does - not an oversight in this pass, just
// not the one Gordon asked about; worth a follow-up if any of those turn out
// to matter here the way scene did.
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
  network: 'NET',
  scene: 'SCN',
  connector: 'CON'
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

  // Two separate commander maps, because one map cannot answer both questions
  // and using it for both is what made "Read but never driven" wrong.
  //
  // statefulCommanders drives contention only: two apps notifying the same
  // phone is normal, two apps driving the same light is the finding.
  //
  // anyCommanders is every action relationship regardless of statefulness, and
  // is what "is this device ever driven at all" has to be answered from.
  // Measured on Gordon's hub before this fix: 19 of the 140 devices listed as
  // "Referenced only as triggers, constraints or monitored inputs" were in
  // fact commanded - Mobile Proxy by 27 apps, the Security Speaker by 10 -
  // because a notification or chime target has action edges but no stateful
  // ones, so it fell through the stateful-only map into the read-only list.
  const statefulCommanders = {};
  const anyCommanders = {};
  const touched = {};      // device -> any relationship at all
  ALL_EDGES.forEach(function (e) {
    touched[e.to] = true;
    if (e.kind !== 'action') return;
    if (!anyCommanders[e.to]) anyCommanders[e.to] = [];
    if (anyCommanders[e.to].indexOf(e.from) < 0) anyCommanders[e.to].push(e.from);
    if (!e.stateful) return;
    if (!statefulCommanders[e.to]) statefulCommanders[e.to] = [];
    if (statefulCommanders[e.to].indexOf(e.from) < 0) statefulCommanders[e.to].push(e.from);
  });

  const contested = Object.keys(statefulCommanders)
    .filter(function (d) { return statefulCommanders[d].length > 1; })
    .sort(function (a, b) { return statefulCommanders[b].length - statefulCommanders[a].length; });

  const untouched = ALL_NODES
    .filter(function (n) { return n.group === 'device' && !touched[n.id]; })
    .map(function (n) { return n.id; });

  // Genuinely never driven: no action edge of any kind.
  const readOnly = ALL_NODES.filter(function (n) {
    if (n.group !== 'device' || !touched[n.id]) return false;
    return !anyCommanders[n.id];
  }).map(function (n) { return n.id; });

  // Driven, but only by commands that leave nothing behind - notifications,
  // chimes, speech. Normal behaviour for a phone or speaker, so it is stated
  // as a category of its own rather than left to look like either a conflict
  // or a sensor.
  const notifiedOnly = ALL_NODES.filter(function (n) {
    if (n.group !== 'device' || !touched[n.id]) return false;
    return anyCommanders[n.id] && !statefulCommanders[n.id];
  }).map(function (n) { return n.id; });

  let html = '<h3>Insights</h3>';
  html += '<div class="sub">Derived from the current scan. Contested counts only apps that can leave a device in a lasting state - notifications, chimes and speech are counted separately below, because repeating those is not a conflict.</div>';

  html += '<h4>Contested devices (' + contested.length + ')</h4>';
  if (!contested.length) {
    html += '<p class="sub">No device is commanded by more than one app.</p>';
  } else {
    html += '<p class="sub">More than one app can leave these in a lasting state. Where two disagree, the last to run wins. Notifications and chimes are excluded - repeating those is not a conflict.</p><ul>';
    contested.slice(0, 40).forEach(function (d) {
      html += '<li><b>' + extEsc(nameOf[d]) + '</b> &mdash; ' + statefulCommanders[d].length + ' apps<br><span class="sub">' +
        statefulCommanders[d].map(function (a) { return extEsc(nameOf[a]); }).join(' &middot; ') + '</span></li>';
    });
    html += '</ul>';
    if (contested.length > 40) {
      html += '<p class="sub">Showing the 40 most contested. ' + (contested.length - 40) + ' more not listed.</p>';
    }
  }

  html += '<h4>Devices nothing references (' + untouched.length + ')</h4>';
  if (!untouched.length) {
    html += '<p class="sub">Every device in the map is referenced by at least one app.</p>';
  } else {
    html += '<p class="sub">No app owns, watches or drives these. Candidates for removal, or gaps in automation.</p><ul>';
    untouched.slice(0, 60).forEach(function (d) { html += '<li>' + extEsc(nameOf[d]) + '</li>'; });
    html += '</ul>';
    if (untouched.length > 60) {
      html += '<p class="sub">Showing 60. ' + (untouched.length - 60) + ' more not listed.</p>';
    }
  }

  html += '<h4>Notified or signalled only (' + notifiedOnly.length + ')</h4>';
  if (!notifiedOnly.length) {
    html += '<p class="sub">No device is driven purely by notifications, chimes or speech.</p>';
  } else {
    html += '<p class="sub">Apps do command these, but only with commands that leave nothing behind - a notification, a chime, speech. Normal for phones, speakers and message brokers, and deliberately not counted as contention above.</p><ul>';
    notifiedOnly.slice(0, 60).forEach(function (d) {
      html += '<li>' + extEsc(nameOf[d]) + ' <span class="sub">&mdash; ' + anyCommanders[d].length + ' app' + (anyCommanders[d].length === 1 ? '' : 's') + '</span></li>';
    });
    html += '</ul>';
    if (notifiedOnly.length > 60) {
      html += '<p class="sub">Showing 60. ' + (notifiedOnly.length - 60) + ' more not listed.</p>';
    }
  }

  html += '<h4>Read but never driven (' + readOnly.length + ')</h4>';
  html += '<p class="sub">No app commands these at all, in any form - they are referenced only as triggers, constraints or monitored inputs. Expected for sensors.</p>';

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
// Community Release Activity embed (Supporting Docs/community_release_activity_embed_spec.md,
// live contract from Bucket/Queue 122). A read-only iframe preview of Community Utilities'
// releases-over-time chart - never told which app/device/hub is in use, never affects scanning,
// the map or the export. Created at most once per page view, on first open only - no repeated
// background loading, no automatic retry within the same page session (spec 4.1/4.2).
const RELEASE_ACTIVITY_URL = 'https://gordonthelander.github.io/HPM_Manifest_Crawl/embed/release-activity/';
const RELEASE_ACTIVITY_TRACKER_URL = 'https://gordonthelander.github.io/HPM_Manifest_Crawl/feature-tracker/?ref=automation-map-release-preview';
const RELEASE_ACTIVITY_TIMEOUT_MS = 8000;
const releaseActivityPanel = document.getElementById('releaseActivity');
const releaseActivityBody = document.getElementById('releaseActivityBody');
let releaseActivityLoaded = false;

// Hubitat's own Release Notes category - the upstream authority this chart is
// built from, not a third-party view of it. Confirmed from the generating
// dataset itself, which declares source.authority "Hubitat Community Release
// Notes" and harvests this exact category: every point on the chart traces
// back to a post here. Offered alongside the Community Utilities tracker so
// the panel closes the loop to the primary source rather than only to a
// secondary presentation of it.
const RELEASE_ACTIVITY_HUBITAT_URL = 'https://community.hubitat.com/c/news/release-notes/55';

// Both destinations in one line, each labelled for what it actually is, so
// neither reads as the other: one is Hubitat, one is a community project.
function releaseActivityLinksHtml(trackerLabel) {
  return '<p class="sub"><a href="' + RELEASE_ACTIVITY_TRACKER_URL + '" target="_blank" rel="noopener noreferrer">' + trackerLabel + '</a>' +
    ' &middot; <a href="' + RELEASE_ACTIVITY_HUBITAT_URL + '" target="_blank" rel="noopener noreferrer">Hubitat release notes</a></p>';
}

// A postMessage readiness handshake, not the iframe's own 'load' event -
// 'load' fires even for a blocked or failed cross-origin response (a 404,
// a future framing refusal), which would otherwise be treated as a
// successful preview forever (Codex review 124 point 1). The published
// embed (HPM_Manifest_Crawl commit 2690d3b, contract in Bucket/Queue 126)
// sends { type: 'automation-map-release-activity-ready', version: 1 } via
// window.parent.postMessage only after its own chart/range/freshness text
// has actually rendered - only that verified message may clear the timer.
// 'load' is not observed at all here; it proves nothing this handshake
// does not already prove better.
function releaseActivityLoad() {
  if (releaseActivityLoaded) return;
  releaseActivityLoaded = true;
  releaseActivityBody.innerHTML = '<p class="sub">Loading...</p>';
  const iframe = document.createElement('iframe');
  iframe.title = 'Hubitat releases over time';
  iframe.loading = 'lazy';
  iframe.setAttribute('sandbox', 'allow-scripts allow-popups allow-popups-to-escape-sandbox');
  iframe.referrerPolicy = 'no-referrer';
  let settled = false;

  function fail() {
    if (settled) return;
    settled = true;
    clearTimeout(timer);
    window.removeEventListener('message', onMessage);
    releaseActivityBody.innerHTML = '<p class="sub">The release preview could not be loaded.</p>' +
      releaseActivityLinksHtml('Open the Community Utilities Update Tracker');
  }

  // The embed's own origin is fixed and known here (unlike the embed
  // itself, which cannot know its private Hubitat parent's origin and so
  // must post with a wildcard target) - every check below is required, not
  // any one alone: origin proves who sent it, contentWindow proves it came
  // from this iframe specifically and not some other frame on the page,
  // and the type/version match proves it is this handshake and not an
  // unrelated message this page happens to receive.
  function onMessage(ev) {
    if (settled) return;
    // "null" is the expected origin here, not a fallback: this iframe is
    // sandboxed WITHOUT allow-same-origin, so its document has an opaque
    // origin and everything it posts arrives as origin "null". Requiring the
    // literal host (as the original contract did) could therefore never
    // match - measured live against the real embed - so the handshake never
    // completed, the timer always fired, and a chart that had rendered
    // perfectly was torn down and replaced by the unavailable message every
    // single time. The real proof of provenance is the source check below:
    // this page created that iframe and set its src to a fixed,
    // source-controlled HTTPS URL, and only that document can be its
    // contentWindow - event.source cannot be forged by another frame. The
    // host string is still accepted so this keeps working unchanged if the
    // embed is ever framed without the sandbox.
    if (ev.origin !== 'https://gordonthelander.github.io' && ev.origin !== 'null') return;
    if (ev.source !== iframe.contentWindow) return;
    const d = ev.data;
    if (!d || d.type !== 'automation-map-release-activity-ready' || d.version !== 1) return;
    settled = true;
    clearTimeout(timer);
    window.removeEventListener('message', onMessage);
  }

  const timer = setTimeout(fail, RELEASE_ACTIVITY_TIMEOUT_MS);
  window.addEventListener('message', onMessage);
  iframe.addEventListener('error', fail);
  // src set after the listener is attached, so a synchronous/cached
  // response cannot post its ready message before this code is listening.
  iframe.src = RELEASE_ACTIVITY_URL;
  releaseActivityBody.innerHTML = '';
  releaseActivityBody.appendChild(iframe);
  // Present alongside the embed regardless of its own load outcome (spec's
  // suggested presentation shows this as a standing part of the panel, not
  // only a failure fallback) - the failure branch above replaces this
  // whole body anyway, so there is never a duplicate link on screen.
  const cta = document.createElement('div');
  cta.innerHTML = releaseActivityLinksHtml('Open the full Update Tracker');
  releaseActivityBody.appendChild(cta);
}

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

  // Hub Variable findings (v2.0.14, schema 4 - parent spec 8.3/11.5). Reader/
  // writer/multiple-writer findings are computed from the same GRAPH.edges
  // data as every insight above. unresolvedReferences is the one exception:
  // it describes names that never became nodes at all (parent spec 6.3), so
  // it is sourced from Groovy's buildGraph() directly
  // (GRAPH.hubVariableUnresolvedReferences) rather than derived from
  // ALL_EDGES here. There is no unresolvedConnectors finding (Codex review
  // 103) - every reported Connector deviceId is trusted unconditionally, so
  // no case exists for this export to flag as unresolved.
  const hubVarReaders = {};
  const hubVarWriters = {};
  ALL_EDGES.forEach(function (e) {
    if (e.kind === 'read') {
      if (!hubVarReaders[e.to]) hubVarReaders[e.to] = [];
      if (hubVarReaders[e.to].indexOf(e.from) < 0) hubVarReaders[e.to].push(e.from);
    } else if (e.kind === 'write') {
      if (!hubVarWriters[e.to]) hubVarWriters[e.to] = [];
      if (hubVarWriters[e.to].indexOf(e.from) < 0) hubVarWriters[e.to].push(e.from);
    }
  });
  const hubVarIds = ALL_NODES.filter(function (n) { return n.group === 'hubVariable'; }).map(function (n) { return n.id; });
  const noDecodedUsage = hubVarIds.filter(function (id) { return !hubVarReaders[id] && !hubVarWriters[id]; })
    .map(function (id) { return ref(id, nameOf); });
  const readersWithoutDecodedWriter = hubVarIds.filter(function (id) { return hubVarReaders[id] && !hubVarWriters[id]; })
    .map(function (id) { return ref(id, nameOf); });
  const writersWithoutDecodedReader = hubVarIds.filter(function (id) { return hubVarWriters[id] && !hubVarReaders[id]; })
    .map(function (id) { return ref(id, nameOf); });
  const multipleHubVarWriters = hubVarIds.filter(function (id) { return hubVarWriters[id] && hubVarWriters[id].length > 1; })
    .map(function (id) {
      return { variable: ref(id, nameOf), writers: hubVarWriters[id].map(function (a) { return ref(a, nameOf); }) };
    });
  const unresolvedHubVarReferences = (GRAPH.hubVariableUnresolvedReferences || []).map(function (r) {
    return { name: r.name, kind: r.kind, referencedBy: ref(r.appId, nameOf) };
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
  // v2.0.14, schema 4 (parent spec 11.3): variableType/identitySource/
  // connector come straight off the node - buildGraph() (Groovy) populates
  // them from the authoritative getAllGlobalVars() inventory when available,
  // or leaves them at their reference-derived defaults otherwise.
  // currentValue stays null in every default export (parent spec 10) - no
  // opt-in value export exists yet.
  const hubVariables = ALL_NODES.filter(function (n) { return n.group === 'hubVariable'; }).map(function (n) {
    return {
      id: n.id, name: nameOf[n.id],
      variableType: n.variableType || null,
      identitySource: n.identitySource || 'reference-derived',
      connector: n.connectorDeviceId ? { deviceId: n.connectorDeviceId, connectorType: n.connectorType || null } : null,
      currentValue: null
    };
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
      stateful: e.kind === 'action' ? !!e.stateful : null,
      // v2.0.14, schema 4 (parent spec 11.4): usageRole is populated only for
      // proven Hub Variable reads (Groovy currently always emits
      // 'unknown-read' - role classification is not yet built, per parent
      // spec 6.2's explicit preference for unknown-read over an invented
      // role). writeSource is populated only when the write's source device
      // ID resolved in the discovered device set (Codex review 097 point 1) -
      // both null on every other relationship kind.
      usageRole: e.usageRole || null,
      writeSource: e.writeSource || null
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
    hubVariablesWithConnectorCount: hubVariables.filter(function (v) { return !!v.connector; }).length,
    unresolvedHubVariableReferenceCount: unresolvedHubVarReferences.length,
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
    'Roles/edges reflect how a device is configured into an app, not what happened at runtime - this is a static configuration snapshot from the last scan (see scan.lastScanCompletedAt), not live state.',
    // v2.0.14, schema 4 (parent spec 11.6) - Hub Variable specific notes.
    'Hub Variable names are household data. Values are absent from this export entirely unless a future explicit opt-in adds them - currentValue is always null here.',
    'A Hub Variable with no decoded reader or writer (insights.hubVariables.noDecodedUsage) may still be used by an app or integration this export cannot decode - absence of a decoded edge is not proof the variable is unused.',
    'Multiple writers on a Hub Variable (insights.hubVariables.multipleWriters) are not proof of a race condition - static configuration proves shared writers, not simultaneous execution.',
    'A Hub Variable connector is a synchronized projection of the same shared state (relationship: synchronizedWith), not an independent value - do not treat the variable and its connector device as two different things to reconcile.',
    'A Hub Variable write edge with a deviceAttribute writeSource means the rule copies or derives its write from that device attribute - it does not mean the device writes the Hub Variable directly.',
    'A Connector deviceId Hubitat reports is trusted directly and always resolved into hubVariables[].connector - there is no check against a case where that Connector was later deleted or replaced outside the normal remove-connector flow. Such a stale or orphaned ID would still be reported here as a resolved connector; this export cannot distinguish that from a genuine one with the data it has.'
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
    'When more than one thing is worth pursuing, offer a short menu - two to five options, one line each on why it might matter - and ask which to explore, unless the request or the evidence makes the next investigation unambiguous, in which case proceed with it directly rather than forcing an unnecessary choice.',
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
      devicesUnreadable: SCAN_META.devicesUnreadable || 0,
      // v2.0.14, schema 4 (parent spec 6.1/11.2): inventory completeness kept
      // separate from relationship-decoder completeness - a consumer must not
      // assume one implies the other.
      hubVariableInventory: {
        status: SCAN_META.hubVariableInventoryStatus || 'not-supported',
        error: SCAN_META.hubVariableInventoryError || null,
        count: SCAN_META.hubVariableInventoryCount || 0,
        source: SCAN_META.hubVariableInventorySource || null
      },
      hubVariableRelationships: {
        status: 'partial',
        supportedEngines: ['Rule Machine 5.1'],
        limitations: ['Other app engines may use Hub Variables without exposing a decoded edge.']
      }
    },
    summary: summary,
    limitations: limitations,
    recommendedAiBehaviour: recommendedAiBehaviour,
    privacyNote: 'Device, room and app names below reflect a real home. Treat this file with the same care as the underlying device list - review before sharing it outside a trusted context.',
    schema: {
      devices: 'Every device on the hub. iconCategory is a best-guess classification (lighting, doors, water, motion...), "unknown" if nothing matched. capabilities is the raw Hubitat capability list this device reports (what iconCategory was derived from); null if this device was not present in the same fetch that supplied room/capabilities (a scan run since the page loaded, in the rare case one raced this export). iconCategory "connector" (schema 4, v2.0.14) marks a Hub Variable Connector device - a virtual device Hubitat keeps synchronized with the value of a hubVariables[] entry, not an independent physical device; find the variable it belongs to via that variable connector.deviceId field (hubVariables[]) or the synchronizedWith edge naming this device as its target (edges[]). A Connector device does not appear in the same bulk device-enumeration endpoint every other device on this hub is discovered through (a live platform finding), so its capabilities/room are null unless the regular device inventory for this hub happens to also list it independently, in which case its real reported data is used same as any other device. Confirmed live: Hubitat also creates its own single parent device named "Variable Connectors" that lists every per-variable Connector in one place. That parent device is classified iconCategory "connector" too (the same detection rule catches it), but no hubVariables[] entry links to it and no synchronizedWith edge names it as a target - it manages the feature, it is not synchronized with one specific variable. Do not assume every "connector" device resolves to exactly one hubVariables[] entry.',
      apps: 'Every installed app, including every automation rule. status: active | paused-or-disabled | inert (installed but touches nothing) | unscanned (never reached during the scan) | unreadable (hub would not answer for it) | deleted-but-referenced (no longer exists as an app, but another rule still names it - appType is null in this one case, expected, not a decoding gap). parentId/childIds describe container apps (e.g. Button Controllers holding several Button Rules). hasDecodedFlow: true if this app has a matching entry in ruleFlows - false does not mean broken, it usually means the app is not a rule at all (an integration, a service) or is a rule on an engine this app cannot decode (Room Lighting, Basic Rules, Simple Automation, webCoRE).',
      externalSystems: 'Systems outside the hub an app depends on, drawn as nodes on the map - a mix of auto-matched community registry entries and declarations entered by the hub owner (see externalSystemDeclarations below for the raw declarations themselves, which is a different, smaller list - not every declared type becomes a node here, and not every node here came from a declaration).',
      hubVariables: 'Hub-wide shared state - schema 4 (v2.0.14): every variable the hub itself reports (identitySource "hub-inventory") when authoritative inventory was available for this scan (see scan.hubVariableInventory.status), reconciled with variables one or more rules read or write. A variable found only via a decoded reference, with no matching inventory entry (inventory was unavailable for this scan), has identitySource "reference-derived" instead - a weaker guarantee: it was found in a decoded rule configuration at scan time, not confirmed against the authoritative variable list the hub itself reports. variableType is Number/Decimal/String/Boolean/DateTime, or null if not yet resolved. connector is the linked Connector device ({deviceId, connectorType}) when Hubitat reports one, else null - see the synchronizedWith edge for the same relationship in the edges array. connectorType is the type the device itself reports when the regular device inventory for this hub independently lists it, otherwise the projected Connector attribute label Hubitat reports (observed live: "Variable", "Humidity") - not necessarily the underlying driver name. currentValue is always null in this export (see limitations).',
      edges: 'Every relationship between two of the above, referenced by id (fromId/toId) - names are included for readability only and are not guaranteed unique, do not use them to join. relationship meanings - trigger: app listens to this device. constraint: a condition/required expression gates the app on this device. monitor: app reads this device state only, cannot command it. action: app can command this device (see stateful). exposed: published to an external system. owns: app created this device. write/read: a rule sets/reads a Hub Variable (see usageRole/writeSource below). synchronizedWith: a Hub Variable and its Connector device expose the same synchronized state - structural, not a read/write/trigger/action, and not evidence of device control. runs/cancelTimedActions/setspb/pauseResume: one rule acting on another rule. depends: an app needs an external system. stateful is only meaningful on action edges - true means the app can leave the device in a lasting on/off/level state, not just a momentary command, and more than one app doing this to the same device means the last one to run decides the outcome (see insights.contested) - common by design on a hub with many rules, not inherently a problem; null on every other relationship kind, where the concept does not apply. usageRole (schema 4) is populated only on proven Hub Variable read edges - "unknown-read" is the only value this app currently produces (a real read was proven, its narrower role - trigger/condition/action-input/text-substitution - was not); null on every other edge. writeSource (schema 4) is populated only on a Hub Variable write edge whose source device attribute resolved to a real device ID ({kind: "deviceAttribute", deviceId, attribute}); null otherwise, including when a source detail exists but could not be resolved to an ID.',
      ruleFlows: 'One entry per app whose logic could be decoded, an array rather than an object keyed by name because app names on this hub are not guaranteed unique - join on appId. steps is the decoded trigger/condition/action sequence for that rule. cond/label on a step can legitimately be empty - "endif"/"else" control-flow steps exist only to close or branch a block and carry no condition of their own. references replaces what would otherwise be a bare device-name list: each entry is {type, id, name} (plus candidateIds when type is "ambiguous"). type is "device" or "app" (a Cancel Timed Actions/Run Rule Actions-style step names another RULE here, not a device - check type, do not assume), "self" for VRB’s "This Rule" (id is this same step’s own appId), "ambiguous" if the name matches more than one device or app on this hub (id is null, candidateIds lists every match - do not guess which one), or "unresolved" if the name matched nothing at all (id null - typically a stale/renamed reference). ruleTargets (cross-rule action steps only) is {id, name} the same way - always resolvable, an "a"-prefixed app id, never ambiguous.',
      insights: 'Pre-computed findings, every device/app/rule reference given as {id,name} rather than a bare name. contested: devices more than one app can leave in a lasting state, so the last app to run decides the outcome - common and often intentional on a hub with many rules (a motion-triggered rule and a manual-override rule both targeting one light, for example), worth confirming is not accidental, not evidence anything is wrong. unreferencedDevices: nothing on the hub owns, watches or drives them. inertApps: installed but touch no device and link to no rule, with why - very often a container holding other apps, or a schedule-only app, both entirely normal. brokenRuleReferences: a rule still names another rule/action/pause target that no longer exists - the action silently does nothing. hubVariables (schema 4) - neutral Hub Variable findings, never automatic fault claims (see limitations): noDecodedUsage (no decoded reader or writer at all - may simply be unused, or used by an app this scan cannot decode), readersWithoutDecodedWriter (may be set manually, externally, or by an undecoded app), writersWithoutDecodedReader (may be consumed externally, or no longer needed), multipleWriters ({variable, writers} - shared state with more than one writer, not automatically a race), unresolvedReferences ({name, kind, referencedBy} - a proven structured reference to a name absent from a complete authoritative inventory; the rule may reference a renamed/deleted variable, or inventory may have been incomplete for this scan). There is no unresolvedConnectors field - a reported Connector deviceId is always trusted and resolved into hubVariables[].connector; see the limitations entry on orphaned/stale Connector IDs for what this trade-off cannot detect.',
      scan: 'lastScanCompletedAt is when the data behind this whole export was last refreshed from the hub (not when this file was generated - generatedAt above is that). lastScanError is whatever the app itself reported wrong with that scan, if anything. status is "complete" (nothing failed), "complete-with-gaps" (the scan finished but appsUnreadable and/or devicesUnreadable is above zero - some apps or devices could not be read and are simply missing from this export, not just from ruleFlows), or "failed" (lastScanError is set, the whole scan aborted). appsUnreadable/devicesUnreadable are the counts behind that status - also see apps[].status for which specific apps were affected. hubVariableInventory (schema 4) is kept deliberately separate from the status above - it describes whether the authoritative Hub Variable list the hub itself reports (not app/device scanning) succeeded this scan: status is "complete", "complete-with-gaps", "failed" or "not-supported"; count is how many variables the hub reported; a variable in hubVariables[] with identitySource "reference-derived" instead of "hub-inventory" means this status was not "complete" when it was found. hubVariableRelationships describes which app engines Hub Variable read/write edges can be decoded from (currently Rule Machine 5.1 only) - independent of inventory status.',
      summary: 'Plain counts of every array below, for a quick sanity check or a one-line status line - not authoritative over the arrays themselves. hubVariablesWithConnectorCount and unresolvedHubVariableReferenceCount (schema 4) are the same kind of derived count as the others - see hubVariables[].connector and insights.hubVariables.unresolvedReferences for the underlying data.',
      limitations: 'Known, structural gaps in what this export can ever contain, independent of any particular hub - read this before concluding a rule is "missing" logic rather than on an engine this app cannot decode.',
      recommendedAiBehaviour: 'How an AI reading this file should behave, in three parts. Epistemic: identify versions, distinguish fact from inference, cite IDs over names, qualify conclusions built on a scan gap or an unresolved/ambiguous reference, never guess a relationship from name similarity alone. Tone: counts like contested devices or inert apps are normal at scale, not evidence of a bad state - avoid adversarial words (fighting, conflict, broken as an unqualified judgment) for anything the export itself does not use that word for, and state a count in proportion to the whole rather than in isolation. Response shape: open with a short plain-language summary naming a few specific apps or devices as evidence the file was actually read, state findings before recommendations, surface scan-quality caveats up front, and when more than one thing is worth pursuing offer it as a short menu and ask which to explore unless the request or the evidence makes the next investigation unambiguous, in which case proceed with it directly - every option offered must read as investigate or explain, never as an action taken or promised, since nothing here authorises any change to the hub.'
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
      brokenRuleReferences: brokenRuleReferences,
      // v2.0.14, schema 4 (parent spec 8.3/11.5). Neutral findings, not fault
      // claims - see recommendedAiBehaviour and this section's own limitations
      // note above.
      hubVariables: {
        noDecodedUsage: noDecodedUsage,
        readersWithoutDecodedWriter: readersWithoutDecodedWriter,
        writersWithoutDecodedReader: writersWithoutDecodedReader,
        multipleWriters: multipleHubVarWriters,
        unresolvedReferences: unresolvedHubVarReferences
      }
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
document.getElementById('releaseActivityBtn').addEventListener('click', function () {
  bringToFront(releaseActivityPanel);
  releaseActivityLoad();
});
document.getElementById('releaseActivityClose').addEventListener('click', function () {
  releaseActivityPanel.style.display = 'none';
  syncLegendVisibility();
  // Spec 4.1 / Codex review 124 point 2 - focus returns to the control that
  // opened this panel, not left on the just-hidden close button.
  document.getElementById('releaseActivityBtn').focus();
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
// Shown once ever, not once per page load - same persisted-preference pattern
// as the legend's own amLegendCollapsed, so a user who has already read this
// does not see it again on every visit. Auto-hiding on a node click (see
// focusNode()) is a separate, existing nicety and stays session-only,
// unpersisted - only the explicit Got it button counts as "closed" here.
(function () {
  let dismissed = false;
  try { dismissed = localStorage.getItem('amHintDismissed') === '1'; } catch (e) { }
  if (dismissed) return;
  const hint = document.createElement('div');
  hint.id = 'hint';
  hint.innerHTML = '<b>Start here</b><br>' +
    'This is every app and device on your hub at once, so it looks busy - that is expected.<br><br>' +
    '<b>Click any node</b> to drill in, or use the dropdowns above to search by app or device instead. Click a rule and you also get a flowchart of how it works. Click one of its devices to see everything else touching that device.<br><br>' +
    '<b>Other panels:</b> Insights (devices several apps share), External systems, Pivot tables, Device icons.<br><br>' +
    'Take your time to explore.' +
    '<div style="margin-top:12px"><button id="hintClose" type="button">Got it</button></div>';
  document.body.appendChild(hint);
  document.getElementById('hintClose').addEventListener('click', function () {
    hint.style.display = 'none';
    try { localStorage.setItem('amHintDismissed', '1'); } catch (e) { }
  });
})();

const appSelect = fillSelect('appFilter', 'appSearch', 'app', 'All apps');
const deviceSelect = fillSelect('deviceFilter', 'deviceSearch', 'device', 'All devices');
const hubVarSelect = fillSelect('hubVarFilter', 'hubVarSearch', 'hubVariable', 'All hub variables');

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
  if (hubVarSelect.value !== '__all__') return hubVarSelect.value;
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
// External systems/Pivot tables/Device icons/Hubitat release activity - the
// full secondaryPanels() list, which is the single source of truth rather
// than a copy maintained here - are unrelated to whatever was
// just chosen (Focus app/device dropdowns, a node click, browser Back/
// Forward restoring one, a link-through from Pivot tables) and would
// otherwise sit open over it. flowPanel is deliberately NOT touched here -
// it may be the panel this same selection is about to open itself
// (showFlow, for an app with a decoded rule) or bringToFront() already
// handles it when that happens, so closing it here too would just be
// redundant with, or race, that.
//
// Also force-expands the legend if collapsed, not just leaves it visible
// the way syncLegendVisibility() alone does - visual only, the same
// non-persisting approach as Show all's own legend handling (a real click
// on the toggle would overwrite the saved amLegendCollapsed preference,
// found live to be the wrong behaviour there and equally wrong here).
function closeSecondaryPanels() {
  secondaryPanels().forEach(function (p) { if (p) p.style.display = 'none'; });
  const legendEl = document.getElementById('legend');
  if (legendEl && legendEl.classList.contains('collapsed')) {
    legendEl.classList.remove('collapsed');
    const legendToggle = document.getElementById('legend-toggle');
    if (legendToggle) {
      legendToggle.innerHTML = '&#9662;';
      legendToggle.setAttribute('aria-expanded', 'true');
    }
  }
  syncLegendVisibility();
}

function exitToWholeMap() {
  appSelect.value = '__all__';
  deviceSelect.value = '__all__';
  hubVarSelect.value = '__all__';
  document.getElementById('kindFilter').value = 'all';
  flowPanel.style.display = 'none';
  closeSecondaryPanels();
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
  focusGenerationSeq += 1;
  if (!poppingHistory) {
    const from = currentFocus();
    if (from !== id) {
      try { history.pushState({ amFocus: id, cameFrom: from }, ''); } catch (e) { }
    }
  }
  // Unconditional, ahead of the app/device branching below: a device
  // selection never touches these panels otherwise, and for an app with a
  // decoded rule, showFlow()'s own bringToFront(flowPanel) closes them again
  // a moment later anyway - redundant there, but harmless, and it means
  // this one call is correct for every path through this function rather
  // than needing to be threaded into each branch separately.
  closeSecondaryPanels();
  if (node.group === 'app') {
    forceSelect(appSelect, node.id, node.title);
    deviceSelect.value = '__all__';
    hubVarSelect.value = '__all__';
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
  } else if (node.group === 'hubVariable') {
    // Own branch, not the device else below - a Hub Variable used to fall
    // into that branch by default (forceSelect(deviceSelect, ...)), which
    // worked visually but mis-filed it as a device selection. Split out once
    // this dropdown existed to give it somewhere correct to go.
    forceSelect(hubVarSelect, node.id, node.title);
    appSelect.value = '__all__';
    deviceSelect.value = '__all__';
    flowPanel.style.display = 'none';
    syncLegendVisibility();
    applyFilters();
  } else {
    forceSelect(deviceSelect, node.id, node.title);
    appSelect.value = '__all__';
    hubVarSelect.value = '__all__';
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
      // Delegated to exitToWholeMap() rather than repeating its steps here.
      // This branch used to hand-roll its own reset and had silently drifted
      // out of sync with it: it never called closeSecondaryPanels(), so Back
      // to the whole map left External systems / Pivot / Device icons /
      // release activity open on top of it, and it never reset kindFilter, so
      // the "Show" dropdown kept a filter the restored view was not actually
      // showing. Both are exactly the "navigation gets confused going in and
      // out" Gordon reported. exitToWholeMap() already guards its own
      // history.pushState behind !poppingHistory. poppingHistory is true for
      // the duration of this handler, so that guard is false and delegating
      // adds no spurious history entry.
      exitToWholeMap();
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
  if (appSelect.value !== '__all__') {
    deviceSelect.value = '__all__';
    hubVarSelect.value = '__all__';
    closeSecondaryPanels();
  }
  applyFilters();
  if (appSelect.value === '__all__') {
    flowPanel.style.display = 'none';
    syncLegendVisibility();
  } else {
    showFlow(appSelect.value);
  }
});
deviceSelect.addEventListener('change', function () {
  if (deviceSelect.value !== '__all__') {
    appSelect.value = '__all__';
    hubVarSelect.value = '__all__';
    closeSecondaryPanels();
  }
  flowPanel.style.display = 'none';
  syncLegendVisibility();
  applyFilters();
});
hubVarSelect.addEventListener('change', function () {
  if (hubVarSelect.value !== '__all__') {
    appSelect.value = '__all__';
    deviceSelect.value = '__all__';
    closeSecondaryPanels();
  }
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

// "Woman Excited Cheers And Phrases Says Yes 1" by Floraphonic, via Pixabay
// (Pixabay Content License - free for this use, attribution not required,
// credited in README anyway). Same lazy-load/branch-aware/fallback pattern
// as playSynthesizedFallback() above.
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
  // Panel-closing and the legend force-expand both now live inside
  // exitToWholeMap() itself (via closeSecondaryPanels()), shared with every
  // other place a selection changes - no longer duplicated here.
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

String comparatorFrameHtml() {
    // Hubitat inserts paragraph HTML after the host page has loaded. Browsers do
    // not execute script elements added that way, so render the comparator as a
    // standalone iframe document whose script is parsed and executed normally.
    String document = """<!doctype html>
<html>
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body>${comparatorHtml()}</body>
</html>"""
    String sourceDocument = document
        .replace('&', '&amp;')
        .replace('"', '&quot;')
    return """<iframe title="Automation Map Export Comparator" srcdoc="${sourceDocument}"
        style="display:block;width:100%;height:900px;border:0;background:#fff;"></iframe>"""
}

String comparatorHtml() {
    return '''
<style>
  #amc-root { max-width: 1180px; color: #252525; font-family: Arial, sans-serif; }
  #amc-root * { box-sizing: border-box; }
  #amc-root .amc-note { margin: 0 0 16px; color: #555; line-height: 1.45; }
  #amc-root .amc-grid { display: grid; grid-template-columns: repeat(2, minmax(280px, 1fr)); gap: 14px; }
  #amc-root .amc-card { border: 1px solid #d7dce2; border-radius: 7px; padding: 14px; background: #fff; }
  #amc-root .amc-card h3 { margin: 0 0 9px; font-size: 16px; }
  #amc-root .amc-file { width: 100%; padding: 8px; border: 1px solid #c8ced6; border-radius: 5px; background: #f8f9fa; }
  #amc-root .amc-meta { margin-top: 8px; min-height: 38px; color: #58616b; font-size: 13px; line-height: 1.4; }
  #amc-root .amc-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 9px; margin: 16px 0; }
  #amc-root button { border: 0; border-radius: 5px; padding: 9px 14px; background: #1976d2; color: #fff; cursor: pointer; font-weight: 600; }
  #amc-root button:disabled { opacity: .48; cursor: default; }
  #amc-root button.amc-secondary { background: #58616b; }
  #amc-root .amc-filter { display: inline-flex; align-items: center; gap: 5px; margin-left: 5px; font-size: 13px; }
  #amc-root .amc-error { display: none; margin: 12px 0; padding: 10px 12px; border-left: 4px solid #c62828; background: #ffebee; color: #8e1717; white-space: pre-wrap; }
  #amc-root .amc-summary { display: none; margin: 14px 0; }
  #amc-root .amc-summary-grid { display: grid; grid-template-columns: repeat(4, minmax(120px, 1fr)); gap: 9px; }
  #amc-root .amc-stat { padding: 11px; border-radius: 6px; background: #f1f4f7; }
  #amc-root .amc-stat strong { display: block; font-size: 21px; margin-bottom: 3px; }
  #amc-root .amc-stat span { color: #58616b; font-size: 12px; }
  #amc-root .amc-scope { margin: 10px 0; color: #58616b; font-size: 13px; }
  #amc-root .amc-table-wrap { display: none; overflow-x: auto; border: 1px solid #d7dce2; border-radius: 7px; }
  #amc-root table { width: 100%; border-collapse: collapse; font-size: 13px; }
  #amc-root th { position: sticky; top: 0; background: #eef2f6; text-align: left; padding: 9px; border-bottom: 1px solid #cdd3da; white-space: nowrap; }
  #amc-root td { padding: 9px; border-bottom: 1px solid #e4e7eb; vertical-align: top; }
  #amc-root tr:last-child td { border-bottom: 0; }
  #amc-root .amc-added { color: #1b7f37; font-weight: 700; }
  #amc-root .amc-removed { color: #b3261e; font-weight: 700; }
  #amc-root .amc-changed { color: #9a6700; font-weight: 700; }
  #amc-root .amc-unchanged { color: #66717d; }
  #amc-root .amc-detail { min-width: 260px; line-height: 1.45; }
  #amc-root .amc-empty { padding: 20px; color: #58616b; text-align: center; }
  @media (max-width: 760px) {
    #amc-root .amc-grid { grid-template-columns: 1fr; }
    #amc-root .amc-summary-grid { grid-template-columns: repeat(2, 1fr); }
  }
</style>

<div id="amc-root">
  <p class="amc-note">
    Select two Automation Map AI-friendly JSON exports. Comparison happens entirely in this browser;
    the files are not uploaded to the hub or sent anywhere else. Only discovered apps, devices,
    Connectors, and Hub Variables are compared. A Hub Variable Connector is shown as its own
    Connector category, separate from Devices, since it represents synchronized shared state and
    not an independent physical device. Relationships, flows, insights, external systems and other
    derived data are deliberately ignored. An export from before Hub Variables existed compares with
    zero Connectors and Hub Variables rather than failing.
  </p>

  <div class="amc-grid">
    <div class="amc-card">
      <h3>Earlier or baseline export</h3>
      <input id="amc-left-file" class="amc-file" type="file" accept="application/json,.json">
      <div id="amc-left-meta" class="amc-meta">No file selected.</div>
    </div>
    <div class="amc-card">
      <h3>Later or comparison export</h3>
      <input id="amc-right-file" class="amc-file" type="file" accept="application/json,.json">
      <div id="amc-right-meta" class="amc-meta">No file selected.</div>
    </div>
  </div>

  <div class="amc-actions">
    <button id="amc-compare" type="button" disabled>Compare discovered items</button>
    <button id="amc-csv" class="amc-secondary" type="button" disabled>Export differences to CSV</button>
    <label class="amc-filter"><input id="amc-show-apps" type="checkbox" checked> Apps</label>
    <label class="amc-filter"><input id="amc-show-devices" type="checkbox" checked> Devices</label>
    <label class="amc-filter"><input id="amc-show-connectors" type="checkbox" checked> Connectors</label>
    <label class="amc-filter"><input id="amc-show-hubvariables" type="checkbox" checked> Hub Variables</label>
    <label class="amc-filter"><input id="amc-show-unchanged" type="checkbox"> Include unchanged</label>
  </div>

  <div id="amc-error" class="amc-error"></div>

  <div id="amc-summary" class="amc-summary">
    <div class="amc-summary-grid">
      <div class="amc-stat"><strong id="amc-added-count">0</strong><span>Added</span></div>
      <div class="amc-stat"><strong id="amc-removed-count">0</strong><span>Removed</span></div>
      <div class="amc-stat"><strong id="amc-changed-count">0</strong><span>Changed</span></div>
      <div class="amc-stat"><strong id="amc-same-count">0</strong><span>Unchanged</span></div>
    </div>
    <div id="amc-scope" class="amc-scope"></div>
  </div>

  <div id="amc-table-wrap" class="amc-table-wrap">
    <table>
      <thead>
        <tr>
          <th>Item</th>
          <th>Result</th>
          <th>Stable ID</th>
          <th>Baseline</th>
          <th>Comparison</th>
          <th>Direct field differences</th>
        </tr>
      </thead>
      <tbody id="amc-rows"></tbody>
    </table>
  </div>
</div>

<script type="text/javascript">
function amcInit() {
  'use strict';

  var left = null;
  var right = null;
  var results = [];

  var TYPE_LABELS = { app: 'App', device: 'Device', connector: 'Connector', hubVariable: 'Hub Variable' };
  var TYPE_FILTER_IDS = {
    app: 'amc-show-apps', device: 'amc-show-devices',
    connector: 'amc-show-connectors', hubVariable: 'amc-show-hubvariables'
  };

  var byId = function (id) { return document.getElementById(id); };
  var compareButton = byId('amc-compare');
  var csvButton = byId('amc-csv');
  var errorBox = byId('amc-error');

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function readJsonFile(file) {
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () {
        try { resolve(JSON.parse(reader.result)); }
        catch (e) { reject(new Error('Invalid JSON: ' + e.message)); }
      };
      reader.onerror = function () { reject(new Error('The browser could not read this file.')); };
      reader.readAsText(file);
    });
  }

  function validateExport(data, filename) {
    if (!data || typeof data !== 'object' || Array.isArray(data)) {
      throw new Error(filename + ' is not a JSON object.');
    }
    if (!Array.isArray(data.apps) || !Array.isArray(data.devices)) {
      throw new Error(filename + ' is not an Automation Map export with apps[] and devices[].');
    }
    // A Connector device is real in devices[] (iconCategory "connector") but is
    // split out here into its own bucket rather than left mixed into devices -
    // it represents synchronized shared state, not an independent physical
    // device. hubVariables[] does not exist on an export from before it shipped
    // (schema 3 or earlier); treated as empty rather than a validation failure,
    // so an old baseline still compares on apps/devices/connectors.
    var realDevices = data.devices.filter(function (d) { return d.iconCategory !== 'connector'; });
    var connectorDevices = data.devices.filter(function (d) { return d.iconCategory === 'connector'; });
    return {
      filename: filename,
      generatedBy: String(data.generatedBy || 'Unknown Automation Map version'),
      generatedAt: String(data.generatedAt || ''),
      exportSchemaVersion: data.exportSchemaVersion,
      apps: data.apps,
      devices: realDevices,
      connectors: connectorDevices,
      hubVariables: Array.isArray(data.hubVariables) ? data.hubVariables : []
    };
  }

  function metaText(x) {
    var when = x.generatedAt ? ' | ' + x.generatedAt : '';
    return x.generatedBy + when + '<br>' + x.apps.length + ' apps, ' + x.devices.length + ' devices, ' +
      x.connectors.length + ' connectors, ' + x.hubVariables.length + ' hub variables';
  }

  function setError(message) {
    errorBox.textContent = message || '';
    errorBox.style.display = message ? 'block' : 'none';
  }

  function onFile(side, file, metaId) {
    setError('');
    if (!file) {
      if (side === 'left') left = null; else right = null;
      byId(metaId).textContent = 'No file selected.';
      compareButton.disabled = !(left && right);
      return;
    }
    readJsonFile(file).then(function (data) {
      var parsed = validateExport(data, file.name);
      if (side === 'left') left = parsed; else right = parsed;
      byId(metaId).innerHTML = metaText(parsed);
      compareButton.disabled = !(left && right);
    }).catch(function (e) {
      if (side === 'left') left = null; else right = null;
      byId(metaId).textContent = 'Could not use this file.';
      compareButton.disabled = true;
      setError(e.message);
    });
  }

  function scalar(value) {
    return value == null ? '' : String(value).trim();
  }

  function sortedStrings(value) {
    if (!Array.isArray(value)) return [];
    return value.map(function (x) { return String(x).trim(); }).sort();
  }

  function directFields(type, item) {
    if (type === 'app') {
      return {
        name: scalar(item.name),
        appType: scalar(item.appType),
        status: scalar(item.status),
        parentId: scalar(item.parentId)
      };
    }
    if (type === 'hubVariable') {
      return {
        name: scalar(item.name),
        variableType: scalar(item.variableType),
        connectorDeviceId: scalar(item.connector ? item.connector.deviceId : null),
        connectorType: scalar(item.connector ? item.connector.connectorType : null)
      };
    }
    // device and connector share this shape - a Connector is a real entry in
    // devices[] before validateExport() splits it into its own bucket above.
    return {
      name: scalar(item.name),
      room: scalar(item.room),
      capabilities: sortedStrings(item.capabilities)
    };
  }

  function displayName(item) {
    return item && item.name ? String(item.name) : '';
  }

  function indexItems(items, type, sourceName) {
    var index = Object.create(null);
    items.forEach(function (item, position) {
      if (!item || item.id == null || String(item.id).trim() === '') {
        throw new Error(sourceName + ' has a discovered ' + type + ' without an ID at position ' + position + '.');
      }
      var id = String(item.id);
      if (index[id]) throw new Error(sourceName + ' contains duplicate ' + type + ' ID ' + id + '.');
      index[id] = item;
    });
    return index;
  }

  function equalValue(a, b) {
    return JSON.stringify(a) === JSON.stringify(b);
  }

  function valueForDisplay(value) {
    if (Array.isArray(value)) return value.join(' | ');
    return scalar(value);
  }

  function compareType(type, leftItems, rightItems) {
    var a = indexItems(leftItems, type, left.filename);
    var b = indexItems(rightItems, type, right.filename);
    var ids = Object.keys(a).concat(Object.keys(b)).filter(function (id, i, all) {
      return all.indexOf(id) === i;
    }).sort(function (x, y) {
      var xn = displayName(a[x] || b[x]).toLowerCase();
      var yn = displayName(a[y] || b[y]).toLowerCase();
      return xn.localeCompare(yn) || x.localeCompare(y);
    });

    return ids.map(function (id) {
      var oldItem = a[id] || null;
      var newItem = b[id] || null;
      if (!oldItem) {
        return { type: type, change: 'added', id: id, oldItem: null, newItem: newItem, differences: [] };
      }
      if (!newItem) {
        return { type: type, change: 'removed', id: id, oldItem: oldItem, newItem: null, differences: [] };
      }
      var oldFields = directFields(type, oldItem);
      var newFields = directFields(type, newItem);
      var differences = Object.keys(oldFields).filter(function (field) {
        return !equalValue(oldFields[field], newFields[field]);
      }).map(function (field) {
        return { field: field, oldValue: oldFields[field], newValue: newFields[field] };
      });
      return {
        type: type,
        change: differences.length ? 'changed' : 'unchanged',
        id: id,
        oldItem: oldItem,
        newItem: newItem,
        differences: differences
      };
    });
  }

  function render() {
    var showByType = {};
    Object.keys(TYPE_FILTER_IDS).forEach(function (type) {
      showByType[type] = byId(TYPE_FILTER_IDS[type]).checked;
    });
    var showUnchanged = byId('amc-show-unchanged').checked;
    var visible = results.filter(function (r) {
      return showByType[r.type] && (showUnchanged || r.change !== 'unchanged');
    });
    var body = byId('amc-rows');
    if (!visible.length) {
      body.innerHTML = '<tr><td class="amc-empty" colspan="6">No items match the current filters.</td></tr>';
    } else {
      body.innerHTML = visible.map(function (r) {
        var detail = '';
        if (r.change === 'added') detail = 'Present only in the comparison export.';
        else if (r.change === 'removed') detail = 'Present only in the baseline export.';
        else if (r.change === 'unchanged') detail = 'Direct discovery fields match.';
        else detail = r.differences.map(function (d) {
          return '<b>' + escapeHtml(d.field) + '</b>: ' + escapeHtml(valueForDisplay(d.oldValue)) +
                 ' &rarr; ' + escapeHtml(valueForDisplay(d.newValue));
        }).join('<br>');
        return '<tr>' +
          '<td>' + TYPE_LABELS[r.type] + '</td>' +
          '<td class="amc-' + r.change + '">' + r.change.charAt(0).toUpperCase() + r.change.slice(1) + '</td>' +
          '<td>' + escapeHtml(r.id) + '</td>' +
          '<td>' + escapeHtml(displayName(r.oldItem)) + '</td>' +
          '<td>' + escapeHtml(displayName(r.newItem)) + '</td>' +
          '<td class="amc-detail">' + detail + '</td>' +
        '</tr>';
      }).join('');
    }
  }

  function compare() {
    setError('');
    try {
      results = compareType('app', left.apps, right.apps)
        .concat(compareType('device', left.devices, right.devices))
        .concat(compareType('connector', left.connectors, right.connectors))
        .concat(compareType('hubVariable', left.hubVariables, right.hubVariables));
      var counts = { added: 0, removed: 0, changed: 0, unchanged: 0 };
      results.forEach(function (r) { counts[r.change] += 1; });
      byId('amc-added-count').textContent = counts.added;
      byId('amc-removed-count').textContent = counts.removed;
      byId('amc-changed-count').textContent = counts.changed;
      byId('amc-same-count').textContent = counts.unchanged;
      byId('amc-scope').textContent = left.generatedBy + ' (' + left.apps.length + ' apps, ' + left.devices.length +
        ' devices, ' + left.connectors.length + ' connectors, ' + left.hubVariables.length +
        ' hub variables) compared with ' + right.generatedBy + ' (' + right.apps.length + ' apps, ' +
        right.devices.length + ' devices, ' + right.connectors.length + ' connectors, ' +
        right.hubVariables.length + ' hub variables). Stable IDs are used for matching.';
      byId('amc-summary').style.display = 'block';
      byId('amc-table-wrap').style.display = 'block';
      csvButton.disabled = !results.some(function (r) { return r.change !== 'unchanged'; });
      render();
    } catch (e) {
      results = [];
      csvButton.disabled = true;
      setError(e.message);
    }
  }

  function csvCell(value) {
    var s = scalar(value).replace(/\\r?\\n/g, ' ');
    return '"' + s.replace(/"/g, '""') + '"';
  }

  function exportCsv() {
    var rows = [[
      'itemType', 'change', 'stableId', 'baselineVersion', 'comparisonVersion',
      'baselineName', 'comparisonName', 'field', 'baselineValue', 'comparisonValue'
    ]];
    results.filter(function (r) { return r.change !== 'unchanged'; }).forEach(function (r) {
      if (r.change === 'changed') {
        r.differences.forEach(function (d) {
          rows.push([
            r.type, r.change, r.id, left.generatedBy, right.generatedBy,
            displayName(r.oldItem), displayName(r.newItem), d.field,
            valueForDisplay(d.oldValue), valueForDisplay(d.newValue)
          ]);
        });
      } else {
        rows.push([
          r.type, r.change, r.id, left.generatedBy, right.generatedBy,
          displayName(r.oldItem), displayName(r.newItem), 'presence',
          r.oldItem ? 'present' : 'absent', r.newItem ? 'present' : 'absent'
        ]);
      }
    });
    var csv = '\\ufeff' + rows.map(function (row) { return row.map(csvCell).join(','); }).join('\\r\\n');
    var blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = 'automation-map-discovery-diff-' + new Date().toISOString().slice(0, 10) + '.csv';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
  }

  byId('amc-left-file').addEventListener('change', function () {
    onFile('left', this.files && this.files[0], 'amc-left-meta');
  });
  byId('amc-right-file').addEventListener('change', function () {
    onFile('right', this.files && this.files[0], 'amc-right-meta');
  });
  compareButton.addEventListener('click', compare);
  csvButton.addEventListener('click', exportCsv);
  byId('amc-show-apps').addEventListener('change', render);
  byId('amc-show-devices').addEventListener('change', render);
  byId('amc-show-connectors').addEventListener('change', render);
  byId('amc-show-hubvariables').addEventListener('change', render);
  byId('amc-show-unchanged').addEventListener('change', render);
}

// Hubitat can evaluate a paragraph's inline script before it has inserted the
// paragraph's HTML into the document. Initialising immediately then sees null
// controls and attaches no file-change listeners. Wait briefly for the root,
// whether this runs before DOMContentLoaded or during later DOM insertion.
var amcBootAttempts = 0;
function amcBoot() {
  var root = document.getElementById('amc-root');
  if (!root) {
    amcBootAttempts += 1;
    if (amcBootAttempts < 50) setTimeout(amcBoot, 50);
    return;
  }
  if (root.getAttribute('data-amc-ready') === 'true') return;
  root.setAttribute('data-amc-ready', 'true');
  amcInit();
}
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', amcBoot);
setTimeout(amcBoot, 0);
</script>
'''
}
