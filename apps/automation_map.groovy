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
 *   /device/fullJson/<id>         parentApp + appsUsingForDialog, used only to
 *                                 DISCOVER which app ids exist
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
 *  - Apps are discovered via each scanned device's appsUsingForDialog list,
 *    which the hub truncates when a device is used by many apps, so an app that
 *    only ever appears in a truncated list may be missed.
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
@Field static final String APP_VERSION = '1.7.4'
// Bumped ONLY when the shape of the scanned graph changes, so that a rendering
// or scanning fix does not needlessly invalidate a good scan and force the user
// to re-crawl every device and app.
// Bumped for the stops->cancelTimedActions / pauses->pauseResume kind rename
// and the addition of node.missing - a cached graph from schema 2 would
// otherwise render with edge kinds that no longer match any colour/dash
// lookup, degrading silently to the '#999' fallback instead of forcing the
// rescan that already exists for exactly this situation.
@Field static final String GRAPH_SCHEMA = '3'
// Rule flow decoding reads Rule Machine's private internals, so it is pinned to
// the version it was verified against. Rules on any other engine still appear
// in the graph with their device relationships; they are counted and reported
// rather than silently producing an empty flow.
@Field static final String SUPPORTED_RULE_ENGINE = 'Rule-5.1'
@Field static final Pattern URL_PATTERN = ~/^https?:\/\/[^\/]+(.+)/
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
}

void updated() {
    log.info "${app.label} updated"
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
                    }
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
  fetch('${getLocalURL('scan')}', { cache: 'no-store', credentials: 'omit' })
    .then(function (r) { return r.json(); })
    .then(function () { m.textContent = 'Scanning - this page updates itself.'; setTimeout(function () { location.reload(); }, 2000); })
    .catch(function (e) { b.disabled = false; m.textContent = 'Could not start the scan: ' + e.message; });
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
    s << "Read ${decoded} app(s)"
    if (unreadable > 0) s << ", <b>${unreadable} could not be read</b>"
    s << ". Decoded ${rules} ${SUPPORTED_RULE_ENGINE} flow(s)."

    int links = (state.ruleLinks ?: 0) as Integer
    if (links > 0) {
        s << " Found ${links} rule-to-rule link(s)."
    } else {
        s << " No rule-to-rule links found - no rule on this hub runs, cancels timed actions on, pauses/resumes, or sets the Private Boolean of another."
    }

    int skipped = (state.rulesSkipped ?: 0) as Integer
    if (skipped > 0) {
        List engines = (state.otherEngines ?: []) as List
        s << "<br><b style='color:#b9770e'>${skipped} rule(s) on ${engines.join(', ')} were not decoded</b> - flow decoding supports ${SUPPORTED_RULE_ENGINE} only. They still appear in the map with their device relationships."
    } else {
        s << "<br><span style='opacity:0.75'>Flow decoding supports ${SUPPORTED_RULE_ENGINE}. Apps that are not rules appear in the map with their device relationships.</span>"
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
    state.appsDecoded = 0
    state.appsUnreadable = 0
    state.rulesDecoded = 0
    state.rulesSkipped = 0
    state.ruleLinks = 0
    state.otherEngines = []
    state.scanQueue = fetchAllDeviceIds()
    state.scanTotal = (state.scanQueue as List).size()
    state.scanDone = 0
    state.scanPhase = 'devices'
    state.scanRunning = true
    state.scanError = null
    // Stamped here as well as in scanBatch, so a scan that never manages to run
    // a single batch still has a timestamp for clearAbandonedScan to age out.
    state.scanHeartbeat = now()
    state.deviceLabels = [:]
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
    List appIds = state.appIds as List
    int size = queue.size() < DEVICE_BATCH_SIZE ? queue.size() : DEVICE_BATCH_SIZE

    // This app's own device picker references every selected device, which would
    // otherwise draw ~200 meaningless "acts on" edges from Automation Map itself.
    String selfId = "${app.id}"

    queue.take(size).each { String devId ->
        Map info = fetchDeviceApps(devId)
        if (info.label) labels[devId] = info.label
        (info.appIds as List).each { String appId ->
            if (appId != selfId && !appIds.contains(appId)) appIds << appId
        }
    }

    state.deviceLabels = labels
    state.appIds = appIds
    state.scanQueue = queue.drop(size)
    state.scanDone = (state.scanDone ?: 0) + size
}

void startAppPhase() {
    state.scanPhase = 'apps'
    state.scanQueue = (state.appIds as List)
    state.scanTotal = (state.appIds as List).size()
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

// Phase 1: only needs the app ids this device is attached to.
Map fetchDeviceApps(String devId) {
    Map out = [label: null, appIds: []]
    try {
        httpGet([uri: "http://127.0.0.1:8080/device/fullJson/${devId}", timeout: 10]) { resp ->
            Map data = (resp.data instanceof Map) ? (resp.data as Map) : [:]
            String breadcrumb = data.extraBreadcrumb as String
            if (breadcrumb) out.label = stripTags(breadcrumb)

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
    }
    return out
}

// Phase 2: the real relationship data. Also harvests device labels for devices
// the user did not select, since settings carry {id: name} maps.
Map fetchAppRelationships(String appId, Map labels) {
    Map out = [id: appId, label: "App ${appId}", type: null, roles: [:], flow: [], stateful: [], ruleLinks: [], endpoints: [], error: null]
    try {
        httpGet([uri: "http://127.0.0.1:8080/installedapp/statusJson/${appId}", timeout: 20]) { resp ->
            Map data = (resp.data instanceof Map) ? (resp.data as Map) : [:]

            Map installedApp = data.installedApp as Map
            String rawLabel = (installedApp?.label ?: installedApp?.trueLabel ?: installedApp?.name ?: "App ${appId}") as String
            out.label = stripTags(rawLabel)
            // Kept alongside the full label rather than replacing it: the
            // status is real information, it just does not belong painted
            // across the canvas. See nodeEntry for which form goes where.
            out.drawLabel = stripStatusMarkup(rawLabel)
            out.type = installedApp?.name

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

List buildRuleFlow(Map data) {
    Map st = [:]
    (data.appState ?: []).each { e ->
        if (e instanceof Map && e.name != null) st["${e.name}"] = e.value
    }

    List actionList = (st.actionList ?: []) as List
    if (!actionList) {
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
        label: actionLabel(method, num, act, settingValues, evalMap, capabs),
        devices: devices,
        ruleTargets: ruleTargets,
        selfTarget: selfTarget,
    ]
}

String actionLabel(String method, String num, Map act, Map settingValues, Map evalMap, Map capabs) {
    switch (method) {
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

Map buildGraph() {
    Map labels = (state.deviceLabels ?: [:]) as Map
    Map appInfo = (state.appInfo ?: [:]) as Map

    Map<String, Map> nodes = [:]
    List<Map> edges = []
    List<String> seen = []
    Map flows = [:]
    Map nameCache = [:]
    Map priorFlows = ((state.graph ?: [:]) as Map).flows as Map ?: [:]

    appInfo.each { String appId, info ->
        if (!(info instanceof Map)) return
        Map appMap = info as Map
        Map roles = (appMap.roles ?: [:]) as Map
        // A rule whose only relationship is to another rule has no device roles
        // at all, and used to be dropped here before it could be drawn.
        if (!roles && !(appMap.ruleLinks ?: []) && !(appMap.endpoints ?: [])) return

        String appNodeId = "a${appId}"
        String appLabel = appMap.inactive ? "${appMap.label} [paused]" : (appMap.label as String)
        // [paused] is this app's own annotation, not the hub's, so it belongs on
        // the drawn label too. drawLabel is absent from a scan taken before it
        // existed, hence the fallback rather than a forced rescan.
        String appDraw = (appMap.drawLabel ?: appMap.label) as String
        if (appMap.inactive) appDraw = "${appDraw} [paused]"
        nodes[appNodeId] = nodeEntry(appNodeId, appLabel, 'app', appMap.type as String, appDraw)
        if (appMap.inactive) nodes[appNodeId].inactive = true
        // Flows come from appInfo during a scan, and from the previously built
        // graph on a rebuild - see finishScan, which strips them from appInfo
        // once they are here, so the same 60KB is not held twice.
        if (appMap.flow) flows[appNodeId] = resolveFlowTargets(appMap.flow as List, appInfo, nameCache)
        else if (priorFlows[appNodeId]) flows[appNodeId] = priorFlows[appNodeId]

        roles.each { String devId, devRoles ->
            String devNodeId = "d${devId}"
            if (!nodes[devNodeId]) {
                nodes[devNodeId] = nodeEntry(devNodeId, (labels[devId] ?: "Device ${devId}") as String, 'device')
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

            String extNodeId = "x${name.toLowerCase().replaceAll('[^a-z0-9]', '')}"
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

            String nodeId = "x${host.toLowerCase().replaceAll('[^a-z0-9]', '')}"
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
@Field static final List<String> REGISTRY_EVALUABLE_FIELDS = ['appName', 'parentAppName']

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

// ===================================================================================================================
// Map page
// ===================================================================================================================

mappings {
    path('/automation-map.html') { action: [ GET: 'renderMapMapping' ] }
    path('/scan') { action: [ GET: 'scanMapping' ] }
    path('/scan-status') { action: [ GET: 'scanStatusMapping' ] }
    path('/externals') { action: [ GET: 'externalsGetMapping', POST: 'externalsSaveMapping' ] }
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

// Starting a scan from a URL rather than only from the page button, so a stalled
// scan can be restarted (and diagnosed) without sitting in the app UI.
Map scanMapping() {
    startScan()
    return render(status: 200, contentType: 'application/json', data: scanStatusJson())
}

Map scanStatusMapping() {
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

String buildMapHtml() {
    Map graph = (state.graph ?: [nodes: [], edges: []]) as Map
    int deviceCount = (graph.nodes ?: []).count { it.group == 'device' }
    int appCount = (graph.nodes ?: []).count { it.group == 'app' }
    String jsonStr = JsonOutput.toJson(graph).replace('</script>', '<\\/script>')

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
<script src="https://unpkg.com/vis-network/standalone/umd/vis-network.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
<style>
  html, body { margin:0; padding:0; height:100%; background:#062733; color:#eee; font-family:sans-serif; }
  #status { position:absolute; top:10px; left:10px; z-index:10; background:rgba(0,0,0,0.55); padding:10px 14px; border-radius:6px; font-size:0.85em; }
  #legend { position:absolute; bottom:10px; left:10px; z-index:10; background:rgba(0,0,0,0.55); padding:10px 14px; border-radius:6px; font-size:0.8em; max-width:340px; }
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
  .sw-outline { background:#2b2b2b; border:2px solid #e8a33d; box-sizing:border-box; }
  /* Deliberately not a variant of sw-outline. A deleted target and an unscanned
     rule are different findings, and sharing a style is what made them
     indistinguishable on the map in the first place. */
  .sw-missing { background:#2b2b2b; border:2px solid #d9534f; box-sizing:border-box; }
  /* Dash patterns drawn to match the canvas. border-top-style has no dash-dot,
     which is why pause/resume used to look identical to stops in the legend.
     These variants take their colour from the row's inline color, not from
     border-color, so a row using one must set color rather than border-color. */
  .ln-pat { height:2px; border-top:none; }
  .ln-dashdot { background:repeating-linear-gradient(to right, currentColor 0 12px, transparent 12px 15px, currentColor 15px 17px, transparent 17px 22px); }
  .ln-thick { height:3px; }
  .line { width:22px; height:0; border-top:2px solid #fff; margin-right:8px; display:inline-block; flex:none; }
  .note { opacity:0.75; font-size:0.9em; margin-top:6px; line-height:1.35; }
  #hint { position:absolute; bottom:16px; right:16px; z-index:15; background:rgba(4,20,27,0.96); padding:14px 18px; border-radius:6px;
          max-width:320px; font-size:0.82em; line-height:1.45; box-shadow:0 4px 24px rgba(0,0,0,0.5); }
  #hint button { cursor:pointer; padding:4px 10px; }
  /* Deliberately not made to work on a phone. A few hundred nodes, a filter
     panel and a flowchart need room and a pointer; a shrunken version would be
     frustrating rather than useful, so small screens get told plainly. */
  #smallscreen { display:none; }
  @media (max-width: 820px) {
    #controls, #legend, #hint, #network, #flow { display:none !important; }
    #smallscreen { display:block; padding:2em 1.5em; line-height:1.5; }
  }
  #flow { position:absolute; top:10px; left:10px; z-index:20; background:rgba(4,20,27,0.96); padding:12px 16px; border-radius:6px;
          max-width:min(62vw, 900px); max-height:90vh; overflow:auto; display:none; box-shadow:0 4px 24px rgba(0,0,0,0.5); }
  #flow h3 { margin:0 0 4px 0; font-size:0.95em; }
  #flow h4 { margin:14px 0 4px 0; font-size:0.9em; color:#cfe3ea; }
  #flow ul { margin:4px 0 0 0; padding-left:18px; }
  #flow li { margin:5px 0; font-size:0.82em; line-height:1.35; }
  #flow p { margin:4px 0; }
  #flow .sub { opacity:0.7; font-size:0.78em; margin-bottom:10px; }
  #flowClose { position:absolute; top:8px; right:10px; cursor:pointer; background:none; border:none; color:#bbb; font-size:1.1em; }
  /* Fully opaque, not near-opaque: at 0.97 the legend behind it still showed
     through as ghost text across the middle of the table. */
  #ext { position:absolute; top:10px; left:10px; z-index:21; background:#041b23; padding:14px 18px; border-radius:6px;
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
  <div class="legend-row"><span class="swatch sw-dot" style="background:#5f7d8c"></span>Device - grey with no app focused</div>
  <div class="legend-row"><span class="swatch sw-diamond" style="background:#cfd8dc"></span>External system - declared, not detected</div>
  <div class="note" style="margin:2px 0 6px 0">Focus an app and each device instead takes the colour of its role below, shown as both a line and the dot the device itself becomes.</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#9b59b6"></span><span class="line" style="border-color:#9b59b6"></span>Trigger - app listens to this device</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#16a085"></span><span class="line" style="border-color:#16a085"></span>Constraint - condition / required expression</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#3d7ea6"></span><span class="line" style="border-color:#3d7ea6"></span>Monitor - app reads this device's state</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#7fae42"></span><span class="line" style="border-color:#7fae42"></span>Action - app can command this device</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#c98b6b"></span><span class="line" style="border-color:#c98b6b; border-top-style:dotted"></span>Exposed - published to an external system</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#8090a0"></span><span class="line" style="border-color:#8090a0; border-top-style:dashed"></span>Owns - app created this device</div>
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
    hd.addEventListener('click', function () { apply(!lg.classList.contains('collapsed')); });
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
  <button id="resetBtn" type="button">Show all</button>
  <button id="insightsBtn" type="button">Insights</button>
  <button id="extBtn" type="button">External systems</button>
</div>
<div id="flow"><button id="flowClose" type="button" title="Close">&times;</button><h3 id="flowTitle"></h3><div class="sub" id="flowSub"></div><div id="flowChart"></div></div>
<div id="ext"><button id="extClose" type="button" title="Close">&times;</button><div id="extBody"></div></div>
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
const GRAPH = ${jsonStr};
const roleColors = { trigger: '#9b59b6', constraint: '#16a085', monitor: '#3d7ea6', action: '#7fae42', owns: '#8090a0', exposed: '#c98b6b',
                     runs: '#d9534f', cancelTimedActions: '#d9534f', setspb: '#d9534f', pauseResume: '#d9534f',
                     depends: '#cfd8dc' };
const groupColors = { app: '#e8a33d', device: '#5f7d8c', external: '#cfd8dc' };

// Rule-to-rule kinds. These join two apps rather than an app and a device, so
// they must never take part in colouring a device by its role.
const RULE_LINK_KINDS = ['runs', 'cancelTimedActions', 'setspb', 'pauseResume'];

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
  const inbound = (e.kind === 'trigger' || e.kind === 'constraint' || e.kind === 'monitor');
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
  // External systems get their own shape as well as their own colour, because
  // they are the only nodes on the map that nobody measured.
  let shape = 'dot';
  if (n.group === 'app') shape = 'square';
  else if (n.group === 'external') shape = 'diamond';
  const styled = {
    // n.draw is the full identity without the hub's live status; n.title keeps
    // the status and is what the hover tooltip shows. The fallback matters: a
    // graph cached before draw existed has only title, and rendering undefined
    // would blank every label on the map rather than fail visibly.
    id: n.id, label: useFullLabel ? (n.draw || n.title) : n.label, title: n.title, color: color,
    shape: shape,
    size: n.group === 'app' ? 17 : (n.group === 'external' ? 19 : 13),
    // maxWdt wraps a long label over several lines instead of drawing one wide
    // ribbon of text. vis.js does no label collision avoidance at all, so width
    // is the only lever there is: on a crowded sector three long names were
    // painting straight through each other. 160px is a little under the arc
    // spacing sectorLayout uses at its tightest.
    font: { color: '#fff', size: 13, strokeWidth: 5, strokeColor: '#062733', vadjust: -4, maxWdt: 160 }
  };
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

function settle() {
  network.once('stabilizationIterationsDone', function () {
    network.setOptions({ physics: { enabled: false } });
    network.fit({ animation: false });
  });
}
settle();

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

function showFlow(appId) {
  const steps = FLOWS[appId];
  if (!steps || !steps.length || !window.mermaid) { flowPanel.style.display = 'none'; return; }
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
    flowPanel.style.display = 'block';
  }).catch(function (err) {
    flowChart.textContent = 'Could not render this rule: ' + err.message;
    flowPanel.style.display = 'block';
  });
}

const flowCloseBtn = document.getElementById('flowClose');
if (flowCloseBtn) {
  flowCloseBtn.addEventListener('click', function () { flowPanel.style.display = 'none'; });
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
      opt.value = n.id; opt.textContent = n.title;
      sel.appendChild(opt);
      shown++;
    });
    // Keep the current selection visible even if it no longer matches, so
    // typing does not silently reset the view.
    if (keep && keep !== '__all__' && !sel.querySelector('option[value="' + keep + '"]')) {
      const cur = items.filter(function (n) { return n.id === keep; })[0];
      if (cur) {
        const opt = document.createElement('option');
        opt.value = cur.id; opt.textContent = cur.title;
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
      html += '<li><b>' + nameOf[d] + '</b> &mdash; ' + commanders[d].length + ' apps<br><span class="sub">' +
        commanders[d].map(function (a) { return nameOf[a]; }).join(' &middot; ') + '</span></li>';
    });
    html += '</ul>';
  }

  html += '<h4>Devices nothing references (' + untouched.length + ')</h4>';
  if (!untouched.length) {
    html += '<p class="sub">Every device in the map is referenced by at least one app.</p>';
  } else {
    html += '<p class="sub">No app owns, watches or drives these. Candidates for removal, or gaps in automation.</p><ul>';
    untouched.slice(0, 60).forEach(function (d) { html += '<li>' + nameOf[d] + '</li>'; });
    html += '</ul>';
  }

  html += '<h4>Read but never driven (' + readOnly.length + ')</h4>';
  html += '<p class="sub">Referenced only as triggers, constraints or monitored inputs. Expected for sensors.</p>';

  html += '<h4>Broken rule references (' + brokenTargets.length + ')</h4>';
  if (!brokenTargets.length) {
    html += '<p class="sub">No rule references a target that no longer exists.</p>';
  } else {
    html += '<p class="sub">These rule/action/pause/private-boolean targets no longer resolve to anything. The referencing action still runs and silently does nothing.</p><ul>';
    brokenTargets.forEach(function (id) {
      html += '<li><b>' + nameOf[id] + '</b><br><span class="sub">Referenced by ' +
        (referencesTo[id] || []).map(function (a) { return nameOf[a]; }).join(' &middot; ') + '</span></li>';
    });
    html += '</ul>';
  }

  return html;
}

document.getElementById('insightsBtn').addEventListener('click', function () {
  document.getElementById('flowTitle').textContent = '';
  document.getElementById('flowSub').textContent = '';
  flowChart.innerHTML = buildInsights();
  flowPanel.style.display = 'block';
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
const EXT_URL = '${getLocalURL('externals')}';
const extPanel = document.getElementById('ext');
const extBody = document.getElementById('extBody');
let EXT = null;

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

// The legend is hidden while this panel is open rather than relied on to sit
// underneath it. It was showing through as ghost text across the table even
// with an opaque background and a higher z-index, and chasing that was not
// worth it when a colour key is useless while editing a table anyway.
document.getElementById('extBtn').addEventListener('click', function () {
  const lg = document.getElementById('legend');
  const hn = document.getElementById('hint');
  if (lg) lg.style.visibility = 'hidden';
  if (hn) hn.style.visibility = 'hidden';
  extPanel.style.display = 'block';
  extLoad();
});
document.getElementById('extClose').addEventListener('click', function () {
  const lg = document.getElementById('legend');
  const hn = document.getElementById('hint');
  // visibility rather than display, so a hint the user already dismissed
  // stays dismissed instead of reappearing.
  if (lg) lg.style.visibility = '';
  if (hn) hn.style.visibility = '';
  extPanel.style.display = 'none';
});

// The whole-hub view is inevitably dense, so say what to do with it rather than
// dropping the user straight into a few hundred nodes with no starting point.
(function () {
  const hint = document.createElement('div');
  hint.id = 'hint';
  hint.innerHTML = '<b>Start here</b><br>' +
    'This is every app and device on your hub at once, so it looks busy - that is expected.<br><br>' +
    '<b>Click any node</b> to drill into it. Click an app to see what it uses - if it is a rule you also get a flowchart of how it works. Then click one of its devices to see everything else touching that device.<br><br>' +
    'The dropdowns top right do the same thing, and have search boxes.<br><br>' +
    'Or press <b>Insights</b> for devices controlled by several apps at once.' +
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

function focusNode(id) {
  const node = ALL_NODES.filter(function (n) { return n.id === id; })[0];
  if (!node) return false;
  if (node.group === 'app') {
    forceSelect(appSelect, node.id, node.title);
    deviceSelect.value = '__all__';
    applyFilters();
    showFlow(node.id);
  } else {
    forceSelect(deviceSelect, node.id, node.title);
    appSelect.value = '__all__';
    flowPanel.style.display = 'none';
    applyFilters();
  }
  const hint = document.getElementById('hint');
  if (hint) hint.style.display = 'none';
  return true;
}

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
  if (appSelect.value === '__all__') flowPanel.style.display = 'none'; else showFlow(appSelect.value);
});
deviceSelect.addEventListener('change', function () {
  if (deviceSelect.value !== '__all__') appSelect.value = '__all__';
  flowPanel.style.display = 'none';
  applyFilters();
});
document.getElementById('kindFilter').addEventListener('change', applyFilters);
document.getElementById('resetBtn').addEventListener('click', function () {
  appSelect.value = '__all__';
  deviceSelect.value = '__all__';
  document.getElementById('kindFilter').value = 'all';
  flowPanel.style.display = 'none';
  applyFilters();
});
</script>
</body>
</html>
"""
}
