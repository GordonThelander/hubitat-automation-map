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
 * GENERATED FILE - do not edit directly. Produced by the production-profile
 * builder from the annotated Dev source at commit 05fa11debd410406a648d470e239bd621ef09666; developer
 * comments and Dev-only build markers are not present in this file.
 *
 * Canonical annotated source:
 * https://github.com/GordonThelander/hubitat-automation-map/blob/05fa11debd410406a648d470e239bd621ef09666/apps/automation_map.groovy
 */
import groovy.transform.Field
import groovy.json.JsonOutput
import java.util.regex.Pattern
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest

@Field static final String APP_NAME = 'Automation Map'




@Field static final String APP_FAMILY = 'Automation Map'
@Field static final String APP_VERSION = '2.3.0'

















@Field static final String BUILD_CHANNEL = 'production'
@Field static final int DIAGNOSTIC_LEVEL = 0










boolean isDevBuild() {
    return BUILD_CHANNEL == 'dev'
}



















































@Field static final String GRAPH_SCHEMA = '15'



boolean showSanta() {
    Calendar cal = Calendar.getInstance(location.timeZone)
    cal.setTime(new Date())
    int month = cal.get(Calendar.MONTH) + 1
    int day = cal.get(Calendar.DAY_OF_MONTH)
    return month == 12 && day >= 20 && day <= 25
}




@Field static final String SUPPORTED_RULE_ENGINE = 'Rule-5.1'



@Field static final String DECODED_ENGINES_TEXT = 'Rule-5.1, Notifier, and Visual Rule Builder 2.0 (in Beta)'
@Field static final Pattern URL_PATTERN = ~/^https?:\/\/[^\/]+(.+)/





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
    if (diagOn()) log.info "${app.label} installed"
    
    
    
    
    if (diagOn()) log.info "${app.label}: starting first scan"
    startScan()
    scheduleAutoScan()
    scheduleUpdateCheck()
    
    runIn(30, 'updateCheckHandler')
}

void updated() {
    if (diagOn()) log.info "${app.label} updated"
    
    
    
    migrateRemoveTelemetryDevice()
    
    
    scheduleAutoScan()
    scheduleDiagnosticLoggingExpiry()
    scheduleUpdateCheck()
}











String updateManifestUrl() {
    String branch = isDevBuild() ? 'dev' : 'main'
    return "https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/${branch}/packageManifest.json"
}



boolean isNewerVersion(String candidate, String current) {
    if (!candidate || !current) return false
    try {
        List a = candidate.tokenize('.').collect { it.toInteger() }
        List b = current.tokenize('.').collect { it.toInteger() }
        int len = Math.max(a.size(), b.size())
        for (int i = 0; i < len; i++) {
            int x = i < a.size() ? a[i] : 0
            int y = i < b.size() ? b[i] : 0
            if (x != y) return x > y
        }
        return false
    } catch (Exception ignored) {
        return false
    }
}





void scheduleUpdateCheck() {
    unschedule('updateCheckHandler')
    int minute = Math.abs((app.id as Integer)) % 60
    schedule("0 ${minute} 3 * * ?", 'updateCheckHandler')
    if (diagOn()) log.info "${app.label}: update check scheduled daily at 03:${minute.toString().padLeft(2, '0')} local"
}

void updateCheckHandler() {
    try {
        asynchttpGet('updateCheckCb', [uri: updateManifestUrl(), contentType: 'text/plain', timeout: 20])
    } catch (Exception e) {
        
        if (diagOn()) log.info "${app.label}: update check could not start: ${e.message}"
    }
}

void updateCheckCb(resp, data) {
    try {
        if (resp?.status != 200) return
        Map published = new groovy.json.JsonSlurper().parseText(resp.data as String) as Map
        String latest = published?.version as String
        if (!latest) return
        state.latestPublishedVersion = latest
    } catch (Exception e) {
        if (diagOn()) log.info "${app.label}: update check response unusable: ${e.message}"
    }
}



String updateNoticeSuffix() {
    String latest = state.latestPublishedVersion as String
    
    
    
    
    
    return isNewerVersion(latest, APP_VERSION) ? " <span style='color:#1565c0'>(update available: ${latest})</span>" : ''
}



















boolean diagOn() {
    if (settings.diagnosticLoggingEnabled != true) return false
    Long expiresAt = (state.diagnosticLoggingExpiresAt ?: 0) as Long
    return expiresAt > 0 && now() < expiresAt
}












String lockVsState() {
    String lock = SCAN_LOCKS.get("${app.id}") as String
    String gen = (state.activeGenerationToken ?: '') as String
    return "lock=${lock ? lock.tokenize('-').last() : 'none'}" +
           " gen=${gen ? gen.tokenize('-').last() : 'none'}" +
           " running=${state.scanRunning == true} phase=${state.scanPhase ?: '-'}" +
           " graph=${state.graph != null} graphVersion=${atomicState.graphVersion ?: '-'}" +
           " appInfo=${(state.appInfo instanceof Map) ? (state.appInfo as Map).size() : 0}" +
           " appResultsReady=${state.appResultsReady == true}"
}












boolean snapshotPredatesGraphCommit() {
    Long committed = (atomicState.graphCommittedAt ?: 0) as Long
    Long seen = (state.graphCommittedAtLocal ?: 0) as Long
    return committed > 0 && committed > seen
}




int phaseElapsedSeconds(Object startedAt) {
    Long started = (startedAt ?: 0) as Long
    if (started <= 0) return -1
    return ((now() - started) / 1000).intValue()
}





String webcoreDecodeSummary() {
    Map appInfo = (state.appInfo instanceof Map) ? state.appInfo as Map : [:]
    int pistons = 0
    int errored = 0
    int withReads = 0
    int unsupported = 0
    appInfo.each { Object id, Object raw ->
        if (!(raw instanceof Map)) return
        Map a = raw as Map
        if ("${a.type ?: ''}".trim() != 'webCoRE Piston') return
        pistons++
        if ("${a.webcoreVariableDecodeStatus ?: ''}" == 'error') errored++
        if (((a.webcoreDeviceReads ?: []) as List)) withReads++
        ((a.webcoreUnsupportedDeviceRefs ?: [:]) as Map).each { Object code, Object count ->
            unsupported += (count ?: 0) as Integer
        }
    }
    return "pistons=${pistons} withDeviceReads=${withReads} decodeErrors=${errored}" +
           " unresolvedDeviceRefs=${unsupported}"
}







void scheduleDiagnosticLoggingExpiry() {
    if (settings.diagnosticLoggingEnabled != true) {
        unschedule('disableDiagnosticLogging')
        state.remove('diagnosticLoggingExpiresAt')
        return
    }
    if (!state.diagnosticLoggingExpiresAt) {
        state.diagnosticLoggingExpiresAt = now() + 3600000L
        unschedule('disableDiagnosticLogging')
        runIn(3600, 'disableDiagnosticLogging')
    }
}










void disableDiagnosticLogging() {
    app.updateSetting('diagnosticLoggingEnabled', [type: 'bool', value: false])
    state.remove('diagnosticLoggingExpiresAt')
    log.info "${app.label}: diagnostic logging auto-disabled after one hour"
}















void migrateRemoveTelemetryDevice() {
    if (state.telemetryMigrationDone) return
    String dni = "${app.id}-telemetry"
    if (!getChildDevice(dni)) {
        state.telemetryMigrationDone = true
        state.remove('telemetryRemovalFailed')
        return
    }
    try {
        deleteChildDevice(dni)
        state.telemetryMigrationDone = true
        state.remove('telemetryRemovalFailed')
        
        
        
        if (diagOn()) log.info "${app.label}: removed the former telemetry device - telemetry is discontinued as of this version."
    } catch (Exception ex) {
        
        
        
        
        
        
        
        state.telemetryRemovalFailed = true
        log.warn "${app.label}: could not remove the former telemetry device automatically (${ex.message}) - it may still be referenced elsewhere (a dashboard, another app). Clear the reference and remove it manually from the Devices page; this app will retry the next time you save these settings."
    }
}









String defaultAutoScanTime() {
    return isDevBuild() ? '01:00' : '00:30'
}





String defaultAutoScanCron() {
    List parts = defaultAutoScanTime().tokenize(':')
    return "0 ${parts[1]} ${parts[0]} * * ?"
}













boolean autoScanEffectivelyEnabled() {
    return settings.autoScanEnabled != false
}








void scheduleAutoScan() {
    unschedule('scheduledScanHandler')
    if (!autoScanEffectivelyEnabled()) return
    if (settings.autoScanTime) {
        
        
        
        schedule(settings.autoScanTime as String, 'scheduledScanHandler')
    } else {
        schedule(defaultAutoScanCron(), 'scheduledScanHandler')
    }
    String defaultLabel = "${defaultAutoScanTime()} (default)"
    if (diagOn()) log.info "${app.label}: automatic scan scheduled for ${settings.autoScanTime ?: defaultLabel}"
}






void scheduledScanHandler() {
    if (state.scanRunning) {
        if (diagOn()) log.info "${app.label}: scheduled scan skipped, one is already running"
        return
    }
    if (diagOn()) log.info "${app.label}: starting scheduled overnight scan"
    
    
    Map result = startScan()
    if (!result.acquired) {
        if (diagOn()) log.info "${app.label}: scheduled scan skipped, another start already owns this instance"
    }
}

Map main() {
    
    
    
    
    boolean ready = app.installationState == 'COMPLETE'
    String oauthError = null
    if (ready && !state.accessToken) {
        try {
            createAccessToken()
        } catch (Exception e) {
            
            
            
            oauthError = 'Automation Map needs OAuth enabled to create the map link. In the ' +
                "hub's Apps Code editor, open Automation Map, click OAuth -> Enable OAuth " +
                'in App -> Update, then reopen this page.'
        }
    }
    clearAbandonedScan()
    
    
    
    
    
    
    
    
    
    
    
    
    
    scheduleDiagnosticLoggingExpiry()
    
    
    
    
    if (settings.diagnosticLoggingEnabled == true && !diagOn()) {
        disableDiagnosticLogging()
    }
    migrateGraphVersionIfNeeded()
    selfHealGraphIfNeeded()
    
    
    
    boolean scanActive = scanEffectivelyActive()

    
    
    
    
    
    
    
    
    
    
    
    return dynamicPage(name: 'main', title: "<b>${APP_NAME} v${APP_VERSION}${updateNoticeSuffix()}</b>", install: true, uninstall: ready,
                       refreshInterval: (ready && scanActive) ? 60 : 0) {
        
        
        
        
        
        if (ready) {
            section {
                if (oauthError) {
                    paragraph "<b style='color:#c0392b'>${oauthError}</b>"
                }
                
                
                
                
                
                
                
                
                
                
                
                if (state.telemetryRemovalFailed) {
                    paragraph "<b style='color:#c0392b'>Could not remove the former telemetry device automatically</b>. It may still be referenced elsewhere - a dashboard, another app. Clear the reference, then remove <b>Automation Map Telemetry</b> manually from the Devices page, or save these settings again once the reference is cleared to retry automatically."
                }
                
                
                
                
                
                
                
                paragraph scanButtonHtml(scanActive)
                if (state.scanTotal) {
                    
                    
                    
                    
                    
                    
                    ConcurrentHashMap liveScan = null
                    if (state.scanPhase == 'devices') liveScan = liveDeviceScan()
                    else if (state.scanPhase == 'apps') liveScan = liveAppScan()
                    Integer done = liveScan ? (liveScan.processed as AtomicInteger).get() : (state.scanDone ?: 0) as Integer
                    Integer total = (state.scanTotal ?: 1) as Integer
                    Integer pct = total > 0 ? ((done * 100) / total) as Integer : 0
                    boolean isDevicePhase = state.scanPhase != 'apps'
                    
                    
                    
                    
                    
                    
                    
                    
                    String phase = isDevicePhase ? 'Reading device types' : 'Reading apps'
                    Integer realDeviceTotal = (state.deviceScanTotal ?: 0) as Integer
                    String deviceContext = (isDevicePhase && realDeviceTotal > 0) ? " (${realDeviceTotal} devices)" : ''
                    String progress = "${phase}: ${done} of ${total}${deviceContext} (${pct}%)"
                    if (scanActive) {
                        progress += ' - updating live, no need to reload.'
                    } else {
                        
                        
                        
                        
                        
                        
                        
                        String when = state.scanHeartbeat ?
                            new Date(state.scanHeartbeat as Long).format('yyyy-MM-dd HH:mm', location.timeZone) : 'unknown'
                        
                        
                        
                        String durationSuffix = state.lastScanDurationSeconds != null ?
                            " (${state.lastScanDurationSeconds}s)" : ''
                        progress = "Last scan : <span style='color:#2e7d32'>${when}</span>${durationSuffix}"
                    }
                    
                    
                    
                    
                    
                    paragraph "<span id='amProgress'>${progress}</span>"
                }
                if (state.scanError) {
                    paragraph "<b style='color:#c0392b'>Scan error: ${state.scanError}</b>"
                }
                if (state.graph) {
                    Map g = state.graph as Map
                    if (scanActive) {
                        
                        
                        
                        
                        
                        
                        
                        
                    } else if (graphIsStale()) {
                        
                        
                        
                        paragraph "<b style='color:#c0392b'>This map was saved in a format this release no longer reads. Run the scan again to rebuild it.</b>"
                    } else {
                        paragraph compatibilitySummary(g)
                        href(
                            name: 'mapLink', title: "<span style='color:#1976d2'>View Automation Map</span>",
                            description: 'Open the relationship graph',
                            url: "${getLocalURL('automation-map.html')}&scan=${state.scanHeartbeat ?: 0}",
                            style: 'embedded', state: 'complete', required: false,
                       )
                    }
                } else if (!scanActive && atomicState.graphVersion != null) {
                    
                    
                    
                    
                    
                    
                    
                    paragraph "<span style='color:#2e7d32'>Scan complete - finishing up, reload in a moment to see the map.</span>"
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
                
                
                
                paragraph "Runs automatically once a day, on by default at ${defaultAutoScanTime()} - turn off below if you would rather press Scan yourself."
                
                
                
                
                
                
                
                paragraph "Separately, once a day this app reads a small file from its own GitHub repository to see whether a newer version has been published, and shows the version number at the top of this page if so. Nothing is sent, and it never installs anything - updates are still made through Hubitat Package Manager."
                input name: 'autoScanEnabled', type: 'bool',
                    title: 'Scan automatically every day',
                    defaultValue: true, submitOnChange: true
                if (autoScanEffectivelyEnabled()) {
                    
                    
                    
                    
                    
                    paragraph "Shown pre-filled at the default (${defaultAutoScanTime()}) below - leave it as-is to keep the default, or set your own time. Press Done to save whichever is showing."
                    input name: 'autoScanTime', type: 'time',
                        title: 'Time to run the scan',
                        defaultValue: defaultAutoScanTime(), required: false
                }
            }
            section {
                
                
                
                
                
                
                
                paragraph "Writes extra detail to your hub's Logs page for troubleshooting - nothing here is transmitted anywhere. Off by default, and turns itself back off automatically after one hour so it can't be left running by accident."
                input name: 'diagnosticLoggingEnabled', type: 'bool',
                    title: 'Enable diagnostic logging',
                    defaultValue: false, submitOnChange: true
                if (settings.diagnosticLoggingEnabled) {
                    paragraph "Diagnostic logging is currently <b>on</b> and will turn itself off within an hour. Turn it off here sooner if you are done before then."
                }
            }
        }

        if (!ready) {
            section {
                paragraph '<b>Press <i>Done</i> to install Automation Map.</b> <span style="color:#c0392b"><b>Your first scan then starts by itself and takes well under a minute, even on a large hub. Open the app again to watch it and to view the map.</b></span>'
                paragraph '''<span style="opacity:0.75">There is nothing to configure. Automation Map reads the hub's complete installed-app list, then scans every app and device to build their relationships.</span>'''
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



void appButtonHandler(String btn) {
    if (btn == 'runScan') startScan()
}






void migrateGraphVersionIfNeeded() {
    if (atomicState.graphVersion == null && state.graph != null && state.graphVersion != null) {
        atomicState.graphVersion = state.graphVersion
    }
}


















void selfHealGraphIfNeeded() {
    if (state.graph != null) return
    if (atomicState.graphVersion == null) return
    if (scanEffectivelyActive()) return
    if (!(state.appInfo)) return
    
    
    
    
    
    
    
    
    
    
    
    
    boolean staleSnapshot = snapshotPredatesGraphCommit()
    if (diagOn()) log.info "${app.label}: self-heal rebuilding the graph - ${lockVsState()}"
    if (staleSnapshot) {
        if (diagOn()) {
            log.info "${app.label}: rebuilding after a stale snapshot raced the graph commit - expected, not a fault"
        }
    } else {
        log.warn "${app.label}: state.graph was missing after a completed scan - rebuilding from existing scan data instead of requiring a fresh scan"
    }
    state.hubVariableInventory = fetchHubVariableInventory()
    state.graph = buildGraph()
    atomicState.graphVersion = GRAPH_SCHEMA
    Long healedAt = now()
    state.graphCommittedAtLocal = healedAt
    atomicState.graphCommittedAt = healedAt
}














boolean shouldAutoScan() {
    
    
    
    
    
    migrateGraphVersionIfNeeded()
    selfHealGraphIfNeeded()
    boolean noGraph = (atomicState.graphVersion == null)
    boolean noLiveLock = SCAN_LOCKS.get("${app.id}") == null
    boolean noDurableRunning = !state.scanRunning
    boolean noScanError = !state.scanError
    boolean result = app.installationState == 'COMPLETE' && noGraph && noLiveLock && noDurableRunning && noScanError
    return result
}












boolean scanEffectivelyActive() {
    boolean liveLock = SCAN_LOCKS.get("${app.id}") != null
    boolean durable = state.scanRunning == true
    boolean effective = liveLock || durable
    return effective
}





String scanButtonHtml(boolean scanActive) {
    String label = scanActive ? 'Scanning...' : (shouldAutoScan() ? 'Starting first scan...' : 'Scan relationships now')
    String disabled = scanActive ? ' disabled' : ''
    
    
    String cls = scanActive ? 'p-button p-component p-disabled mr-2 mb-2' : 'p-button p-component mr-2 mb-2'
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
// the single fallback the old comment claimed. Caught in review before this
// shipped past dev.
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
if (${scanActive ? 'true' : 'false'}) {
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





String autoScanScript() {
    if (!shouldAutoScan()) return ''
    return '''
document.addEventListener('DOMContentLoaded', function () { amStartScan(); });
if (document.readyState !== 'loading') { amStartScan(); }
'''
}

boolean graphIsStale() {
    migrateGraphVersionIfNeeded()
    selfHealGraphIfNeeded()
    return state.graph && atomicState.graphVersion != GRAPH_SCHEMA
}










void clearAbandonedScan() {
    if (!state.scanRunning) return
    Long beat = (state.scanHeartbeat ?: 0) as Long

    
    
    
    
    
    
    
    
    
    
    
    String activeGen = (state.activeGenerationToken ?: null) as String
    String currentLock = SCAN_LOCKS.get("${app.id}") as String
    boolean tombstoned = activeGen != null && currentLock == null && TERMINAL_TOMBSTONES.containsKey(genKey(activeGen))
    if (tombstoned) {
        if (diagOn()) log.info "${app.label}: recovery, tombstoned generation - ${lockVsState()}"
        
        
        log.info "${app.label}: clearing resurrected scan flags for an already-completed generation"
        state.scanRunning = false
        return
    }

    if (beat > 0 && (now() - beat) < 90000) return

    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    boolean asyncDeviceScanActive = state.scanPhase == 'devices' && liveDeviceScan() != null
    boolean asyncAppScanActive = state.scanPhase == 'apps' && liveAppScan() != null
    if (asyncDeviceScanActive || asyncAppScanActive) {
        return
    }

    
    
    
    
    
    
    
    
    
    if (currentLock != null && currentLock.startsWith('finishing:')) {
        List parts = currentLock.split(':') as List
        Long finishingSince = parts.size() >= 3 ? (parts[2] as Long) : 0L
        if ((now() - finishingSince) < (FINISHING_RECOVERY_SEC * 1000)) return
        log.warn "${app.label}: clearing a stranded finishing marker (${((now() - finishingSince) / 1000).intValue()}s old)"
        
        
        
        
        
        
        
        
        
        
        
        
        
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

    
    
    
    
    
    
    
    if (state.scanPhase == 'apps') {
        
        
        
        
        
        String currentToken = SCAN_LOCKS.get("${app.id}") as String
        if (currentToken == null) {
            
            
            
            
            
            
            
            
            
            
            
            String recoveryToken = "recovered-${now()}-${(int)(Math.random() * 999999)}"
            if (SCAN_LOCKS.putIfAbsent("${app.id}", recoveryToken) != null) return
            currentToken = recoveryToken
        }
        if (state.appResultsReady == true) {
            
            
            
            
            
            
            
            
            
            
            if (diagOn()) log.info "${app.label}: recovery, finishing a published-but-unfinalized scan - ${lockVsState()}"
            log.warn "${app.label}: complete app results were published but graph finalization never ran - finishing now"
            finishScan([lockToken: currentToken, logicalGen: activeGen])
        } else {
            
            
            
            
            
            if (diagOn()) log.info "${app.label}: recovery, working data lost - ${lockVsState()}"
            log.warn "${app.label}: scan working data was lost before app results were published - not building an incomplete map"
            markScanFinished(currentToken,
                'The scan working data was lost before it could be published. Press Scan to run it again.',
                activeGen)
        }
        return
    }

    
    
    
    
    
    String abandonedToken = SCAN_LOCKS.get("${app.id}") as String
    if (abandonedToken == null) {
        
        
        
        
        
        String recoveryToken = "recovered-abandon-${now()}-${(int)(Math.random() * 999999)}"
        if (SCAN_LOCKS.putIfAbsent("${app.id}", recoveryToken) != null) return
        abandonedToken = recoveryToken
    }
    
    
    
    
    markScanFinished(abandonedToken,
        'The previous scan stopped before it finished. Press Scan to run it again.',
        activeGen)
    log.warn "${app.label}: clearing an abandoned scan"
}





String compatibilitySummary(Map graph) {
    StringBuilder s = new StringBuilder()
    if (state.compatOk == false) {
        s << "<b style='color:#c0392b'>${state.compatDetail}</b><br>"
    }
    int devUnreadable = ((state.deviceIdsUnreadable ?: []) as List).size()
    if (devUnreadable > 0) {
        s << "<b style='color:#c0392b'>${devUnreadable} device(s) could not be read</b> and are missing from this map, along with any app only discoverable through them. "
    }
    int appCount = (state.appInfo ?: [:]).size()
    int deviceCount = (state.deviceLabels ?: [:]).size()
    int nodeCount = (graph.nodes ?: []).size()
    int relationshipCount = (graph.edges ?: []).size()
    int inert = (state.appsInert ?: 0) as Integer
    s << "<b>Your map contains:</b> ${appCount} apps, ${deviceCount} devices, ${nodeCount} nodes"

    
    
    
    
    Map hubVarInv = (state.hubVariableInventory ?: [:]) as Map
    String hubVarInvStatus = "${hubVarInv.status}"
    if (hubVarInvStatus == 'complete' || hubVarInvStatus == 'complete-with-gaps') {
        int hubVarCount = (hubVarInv.count ?: 0) as Integer
        int hubVarConnCount = (state.hubVariableConnectorCount ?: 0) as Integer
        String variableLabel = hubVarCount == 1 ? 'Hub Variable' : 'Hub Variables'
        String connectorLabel = hubVarConnCount == 1 ? '1 with a Connector' : "${hubVarConnCount} with Connectors"
        s << " and ${hubVarCount} ${variableLabel} (${connectorLabel})"
    }
    s << ", resulting in ${relationshipCount} relationships"
    if (inert > 0) s << ", including ${inert} freestanding apps"
    s << "."
    s << "<br><span style='opacity:0.75'>Flow decoding supports Rule Machine 5.1, Notifier, Visual Rule Builder 2.0 (in Beta) and webCoRE pistons (in Beta: statement order, branching, condition text and task parameters). Hub Variable use, local variables and direct device reads/actions are also decoded from webCoRE pistons.</span>"
    return s.toString()
}


















Map httpFetch(String uri, int timeoutSec, Map extraOpts = [:]) {
    Map out = [ok: false, data: null, error: null, timedOut: false]
    try {
        httpGet(extraOpts + [uri: uri, timeout: timeoutSec]) { resp ->
            out.data = resp.data
            out.ok = true
        }
    } catch (Exception ex) {
        out.error = "${ex.message}"
        
        
        Throwable cause = ex
        int guard = 0
        while (cause != null && guard < 8) {
            if (cause instanceof java.net.SocketTimeoutException ||
                cause instanceof java.util.concurrent.TimeoutException) { out.timedOut = true; break }
            cause = cause.getCause()
            guard++
        }
    }
    return out
}



@Field static final String LOOPBACK_BASE = 'http://127.0.0.1:8080'












@Field static final int DEVICE_ASYNC_MAX_INFLIGHT = 8
@Field static final int APP_ASYNC_MAX_INFLIGHT = 8   



@Field static final int ATTEMPT_CAP = 2
@Field static final int CLAIM_REAP_INTERVAL_SEC = 10







@Field static final long CLAIM_REAP_DEADLINE_MS = 25000



















@Field static final int DEVICE_ASYNC_WATCHDOG_SEC = 130
@Field static final int APP_ASYNC_WATCHDOG_SEC = 130







@Field static final int FINISHING_RECOVERY_SEC = 60







@Field static final ConcurrentHashMap<String, ConcurrentHashMap> DEVICE_SCANS = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, ConcurrentHashMap> APP_SCANS = new ConcurrentHashMap<>()






ConcurrentHashMap liveDeviceScan() {
    String id = state.deviceScanId as String
    return id ? DEVICE_SCANS[id] : null
}
ConcurrentHashMap liveAppScan() {
    String id = state.appScanId as String
    return id ? APP_SCANS[id] : null
}




















@Field static final ConcurrentHashMap<String, String> SCAN_LOCKS = new ConcurrentHashMap<>()
















@Field static final ConcurrentHashMap<String, Map> REGISTRY_RESULTS = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, Long> TERMINAL_TOMBSTONES = new ConcurrentHashMap<>()
@Field static final long GENERATION_RECORD_RETENTION_MS = 15 * 60 * 1000L




String genKey(String token) { return "${app.id}:${token}" }










void sweepGenerationRecords() {
    long cutoff = now() - GENERATION_RECORD_RETENTION_MS
    new ArrayList(TERMINAL_TOMBSTONES.entrySet()).each { entry ->
        if ((entry.value as Long) < cutoff) TERMINAL_TOMBSTONES.remove(entry.key, entry.value)
    }
    new ArrayList(REGISTRY_RESULTS.entrySet()).each { entry ->
        Map v = entry.value as Map
        Long createdAt = (v?.createdAt ?: 0L) as Long
        if (createdAt < cutoff) REGISTRY_RESULTS.remove(entry.key, entry.value)
    }
}






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








































boolean finishGeneration(String token, String error = null, String logicalGen = null, Closure publishWork = null) {
    if (token == null) return false
    String finishingValue = "finishing:${token}:${now()}"
    if (!SCAN_LOCKS.replace("${app.id}", token, finishingValue)) {
        return false
    }
    
    
    
    
    
    
    String tombstoneKey = genKey(logicalGen ?: token)
    try {
        if (publishWork != null) publishWork()
        if (error != null) state.scanError = error
    } catch (Exception ex) {
        log.warn "${app.label}: scan termination failed: ${ex.message}"
        state.scanError = "${ex.message}"
    } finally {
        TERMINAL_TOMBSTONES.put(tombstoneKey, now())
        state.scanRunning = false
        SCAN_LOCKS.remove("${app.id}", finishingValue)
    }
    return true
}




void markScanFinished(String token, String error = null, String logicalGen = null) {
    finishGeneration(token, error, logicalGen, null)
}










boolean ownsLock(String token) {
    return token != null && SCAN_LOCKS.get("${app.id}") == token
}






Map startScan() {
    
    
    
    
    
    
    
    
    
    String lockToken = "lock-${now()}-${(int)(Math.random() * 999999)}"
    if (SCAN_LOCKS.putIfAbsent("${app.id}", lockToken) != null) return [acquired: false]
    
    
    
    
    
    
    
    
    
    state.activeGenerationToken = lockToken
    sweepGenerationRecords()
    
    
    
    
    
    
    if (diagOn()) log.info "${app.label}: scan started"
    
    
    
    
    
    
    boolean released = false
    try {
    Map compat = probeCompatibility()
    state.compatOk = compat.ok
    state.compatDetail = compat.detail
    
    
    
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
    
    
    
    
    state.scanError = null
    state.deviceIdsUnreadable = []
    Map bulk = fetchDeviceListBulk()
    if (bulk.error) {
        markScanFinished(lockToken, "Could not list devices from the hub: ${bulk.error}")
        released = true
        return [acquired: true]
    }
    
    
    
    state.deviceLabels = bulk.labels as Map
    state.deviceRooms = bulk.rooms as Map
    state.deviceTypes = bulk.types as Map
    
    
    
    state.deviceParents = bulk.parents as Map
    
    
    state.deviceDisabled = bulk.disabledDevices as List
    state.deviceCapabilities = [:]
    
    
    
    
    
    
    List repIds = (bulk.typeGroups as Map).collect { typeKey, ids -> (ids as List)[0] }
    state.scanQueue = []
    
    
    
    
    
    
    state.deviceScanTotal = (bulk.labels as Map).size()
    state.scanTotal = repIds.size()
    state.scanDone = 0
    state.scanPhase = 'devices'
    
    
    state.devicePhaseStartedAt = now()
    state.scanRunning = true
    
    
    
    state.scanHeartbeat = now()
    state.appIds = []
    state.appInfo = [:]
    
    
    
    
    
    state.appResultsReady = false
    atomicState.graphVersion = null
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    state.graph = null
    unschedule('fetchRegistry')
    unschedule('finishScan')

    if (repIds.isEmpty()) {
        
        
        
        
        
        
        
        
        
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
    
    
    
    
    
    scan.finalizeScheduleGuard = new AtomicInteger(0)
    scan.finalizeGuard = new AtomicInteger(0)
    scan.capsByDev = new ConcurrentHashMap<String, List>()
    
    
    
    
    
    scan.roomsByDev = new ConcurrentHashMap<String, String>((bulk.rooms ?: [:]) as Map)
    scan.unreadableDevs = new ConcurrentHashMap<String, Boolean>()
    scan.lastProgressAt = now()   
    
    
    
    
    
    scan.typeGroups = new ConcurrentHashMap((bulk.typeGroups ?: [:]) as Map)
    
    
    
    scan.lockToken = lockToken
    DEVICE_SCANS[scanId] = scan
    state.deviceScanId = scanId

    
    
    
    
    
    runIn(DEVICE_ASYNC_WATCHDOG_SEC, 'deviceAsyncWatchdog', [data: [scanId: scanId]])
    runIn(CLAIM_REAP_INTERVAL_SEC, 'deviceClaimReaper', [data: [scanId: scanId]])
    
    
    
    
    
    released = true
    refillDevicePipeline(scanId)
    return [acquired: true]
    } catch (Exception ex) {
        if (!released) markScanFinished(lockToken, "Unexpected error starting scan: ${ex.message}")
        throw ex
    }
}

void refillDevicePipeline(String scanId) {
    
    
    
    
    
    
    
    while (dispatchDeviceOne(scanId)) {  }
}









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
        
        
        
        
        
        boolean owned = (scan.claims as ConcurrentHashMap).remove(repId, myClaim)
        if (owned) inFlight.decrementAndGet()
        if (owned) {
            scan.lastProgressAt = now()
            if (attemptCount < ATTEMPT_CAP) {
                (scan.pending as ConcurrentLinkedQueue) << [id: repId, attemptCount: attemptCount]
            } else {
                
                
                
                
                deviceGroupFor(scan, repId).each { String devId -> (scan.unreadableDevs as ConcurrentHashMap)[devId] = true }
                (scan.processed as AtomicInteger).incrementAndGet()
            }
        }
        maybeFinalizeDevicePhase(scanId)
        return true   
    }
}





List deviceGroupFor(ConcurrentHashMap scan, String repId) {
    Map typeGroups = (scan.typeGroups ?: [:]) as Map
    return (typeGroups.values().find { (it as List).contains(repId) } ?: [repId]) as List
}






void deviceFetchCb(resp, data) {
    String scanId = data.scanId as String
    ConcurrentHashMap scan = DEVICE_SCANS[scanId]
    if (scan == null) return  

    String repId = data.repId as String
    String attemptToken = data.attemptToken as String
    Map claim = (scan.claims as ConcurrentHashMap)[repId] as Map

    if (claim == null || claim.attemptToken != attemptToken) return   

    boolean owned = (scan.claims as ConcurrentHashMap).remove(repId, claim)
    if (!owned) return   

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
    
    
    
    scan.lastProgressAt = now()

    refillDevicePipeline(scanId)
    maybeFinalizeDevicePhase(scanId)
}









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
    if (!owned) return   

    int attemptCount = candidateClaim.attemptCount as Integer
    log.warn "${app.label}: device ${repId} claim reaped after ${ageMs}ms with no callback (attempt ${attemptCount})"

    (scan.inFlight as AtomicInteger).decrementAndGet()
    scan.lastProgressAt = now()

    if (attemptCount < ATTEMPT_CAP) {
        (scan.pending as ConcurrentLinkedQueue) << [id: repId, attemptCount: attemptCount]
    } else {
        
        
        deviceGroupFor(scan, repId).each { String devId -> (scan.unreadableDevs as ConcurrentHashMap)[devId] = true }
        (scan.processed as AtomicInteger).incrementAndGet()
    }

    refillDevicePipeline(scanId)
    maybeFinalizeDevicePhase(scanId)
}






void deviceAsyncWatchdog(data) {
    String scanId = data?.scanId as String
    ConcurrentHashMap scan = DEVICE_SCANS[scanId]
    if (scan == null) return   

    int pending = (scan.pending as ConcurrentLinkedQueue).size()
    int inFlight = (scan.inFlight as AtomicInteger).get()
    int claimsOutstanding = (scan.claims as Map).size()
    int processed = (scan.processed as AtomicInteger).get()
    int total = scan.total as Integer

    if (pending == 0 && inFlight == 0 && claimsOutstanding == 0 && processed == total) {
        
        
        
        
        finalizeDevicePhase(scanId)
        return
    }
    if (processed > total) {
        log.warn "${app.label}: device-phase scan ${scanId} invariant violation - processed=${processed} exceeds total=${total}"
    }

    
    
    
    
    
    
    if (!(scan.finalizeGuard as AtomicInteger).compareAndSet(0, 1)) return

    log.warn "${app.label}: device-phase async scan ${scanId} did not finish within ${DEVICE_ASYNC_WATCHDOG_SEC}s (${processed} of ${total} landed, pending=${pending} inFlight=${inFlight} claims=${claimsOutstanding}) - failing closed, no map published for this scan"
    DEVICE_SCANS.remove(scanId)
    unschedule('deviceClaimReaper')
    
    
    
    
    
    
    markScanFinished(scan.lockToken as String, "Device scan stalled (${processed}/${total} landed) - failed rather than publish an incomplete map")
}













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







void finalizeDevicePhaseScheduled(data) {
    String scanId = data?.scanId as String
    ConcurrentHashMap scan = DEVICE_SCANS[scanId]
    if (scan == null) return   
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










void finalizeDevicePhase(String scanId) {
    ConcurrentHashMap scan = DEVICE_SCANS[scanId]
    if (scan == null) return
    if (!(scan.finalizeGuard as AtomicInteger).compareAndSet(0, 1)) return   

    
    
    
    
    
    
    
    
    
    
    if (!ownsLock(scan.lockToken as String)) {
        if (diagOn()) log.info "${app.label}: device-phase finalize for a superseded scan generation, discarding without publishing"
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
        if (diagOn()) {
            log.info "${app.label}: device phase done in ${phaseElapsedSeconds(state.devicePhaseStartedAt)}s" +
                     " - ${scan.total} representative type(s), ${unreadable.size()} unreadable"
        }
    } catch (Exception ex) {
        log.warn "${app.label}: device-phase finalization failed: ${ex.message}"
        markScanFinished(scan.lockToken as String, "${ex.message}")
        return
    }

    startAppPhase(scan.lockToken as String)
}
















void startAppPhase(String lockToken) {
    Set appIds = new LinkedHashSet(state.appIds as List)
    
    
    Map appTypeNamespaceResult = fetchAppTypeNamespaces()
    if (appTypeNamespaceResult.error) {
        log.warn "${app.label}: namespace lookup unavailable this scan - ${appTypeNamespaceResult.error}"
    }
    Map appListing = fetchInstalledAppIds()
    if (appListing.error) {
        log.warn "${app.label}: app phase could not start: ${appListing.error}"
        markScanFinished(lockToken, "Could not list installed apps: ${appListing.error}")
        return
    }
    
    
    
    appIds.addAll(appListing.ids as List)
    
    
    
    
    if (!ownsLock(lockToken)) {
        if (diagOn()) log.info "${app.label}: app-phase start for a superseded scan generation, discarding without publishing"
        return
    }
    state.appIds = appIds as List

    state.scanPhase = 'apps'
    
    
    state.appPhaseStartedAt = now()
    state.scanTotal = appIds.size()
    state.scanDone = 0
    state.scanQueue = []

    if (appIds.isEmpty()) {
        
        
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
    
    
    scan.finalizeScheduleGuard = new AtomicInteger(0)
    scan.finalizeGuard = new AtomicInteger(0)
    scan.appInfo = new ConcurrentHashMap<String, Map>()
    
    
    
    
    
    
    
    
    
    
    
    
    
    scan.labels = new ConcurrentHashMap<String, String>((state.deviceLabels ?: [:]) as Map)
    scan.appTypeNamespaces = new ConcurrentHashMap<String, String>((appTypeNamespaceResult.namespaces ?: [:]) as Map)
    scan.decoded = new AtomicInteger(0)
    scan.unreadable = new AtomicInteger(0)
    scan.rulesDecoded = new AtomicInteger(0)
    scan.rulesSkipped = new AtomicInteger(0)
    scan.otherEngines = new ConcurrentHashMap<String, Boolean>()
    scan.lastProgressAt = now()   
    
    
    
    scan.lockToken = lockToken
    APP_SCANS[scanId] = scan
    state.appScanId = scanId

    runIn(APP_ASYNC_WATCHDOG_SEC, 'appAsyncWatchdog', [data: [scanId: scanId]])
    runIn(CLAIM_REAP_INTERVAL_SEC, 'appClaimReaper', [data: [scanId: scanId]])
    refillAppPipeline(scanId)
}

void refillAppPipeline(String scanId) {
    while (dispatchAppOne(scanId)) {  }
}




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
    
    
    scan.lastProgressAt = now()

    refillAppPipeline(scanId)
    maybeFinalizeAppPhase(scanId)
}




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

    
    
    if (!(scan.finalizeGuard as AtomicInteger).compareAndSet(0, 1)) return

    log.warn "${app.label}: app-phase async scan ${scanId} did not finish within ${APP_ASYNC_WATCHDOG_SEC}s (${processed} of ${total} landed, pending=${pending} inFlight=${inFlight} claims=${claimsOutstanding}) - failing closed, no map published for this scan"
    APP_SCANS.remove(scanId)
    unschedule('appClaimReaper')
    
    
    
    markScanFinished(scan.lockToken as String, "App scan stalled (${processed}/${total} landed) - failed rather than publish an incomplete map")
}



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



void finalizeAppPhaseScheduled(data) {
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
    if (!(pending == 0 && inFlight == 0 && claimsOutstanding == 0 && processed == total
            && appInfoSize == total && decoded + unreadable == total)) {
        log.warn "${app.label}: app-phase scan ${scanId} no longer satisfies invariants at scheduled finalize (pending=${pending} inFlight=${inFlight} claims=${claimsOutstanding} processed=${processed} appInfo=${appInfoSize} total=${total}) - leaving it to the watchdog"
        return
    }
    finalizeAppPhase(scanId)
}








void finalizeAppPhase(String scanId) {
    ConcurrentHashMap scan = APP_SCANS[scanId]
    if (scan == null) return
    if (!(scan.finalizeGuard as AtomicInteger).compareAndSet(0, 1)) return

    
    
    
    
    if (!ownsLock(scan.lockToken as String)) {
        if (diagOn()) log.info "${app.label}: app-phase finalize for a superseded scan generation, discarding without publishing"
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

        
        
        
        
        
        
        
        state.appsDecoded = (scan.decoded as AtomicInteger).get()
        state.appsUnreadable = (scan.unreadable as AtomicInteger).get()
        state.rulesDecoded = (scan.rulesDecoded as AtomicInteger).get()
        state.rulesSkipped = (scan.rulesSkipped as AtomicInteger).get()
        List others = []
        (scan.otherEngines as ConcurrentHashMap).keySet().each { String eng -> others << eng }
        state.otherEngines = others

        state.scanDone = scan.total as Integer
        
        
        
        state.appResultsReady = true
        state.scanHeartbeat = now()
        if (diagOn()) {
            log.info "${app.label}: app phase done in ${phaseElapsedSeconds(state.appPhaseStartedAt)}s" +
                     " - ${state.appsDecoded} decoded, ${state.appsUnreadable} unreadable," +
                     " ${state.rulesDecoded} rule(s) decoded, ${state.rulesSkipped} skipped"
        }
    } catch (Exception ex) {
        log.warn "${app.label}: app-phase finalization failed: ${ex.message}"
        markScanFinished(scan.lockToken as String, "${ex.message}")
        return
    }

    beginRegistryAndFinish(scan.lockToken as String)
}













void beginRegistryAndFinish(String lockToken) {
    
    
    
    
    state.registryMeta = [state: 'PENDING', fetched: null, entries: 0,
                          matched: 0, error: null, schemaVersion: null]
    runIn(1, 'fetchRegistry', [data: [lockToken: lockToken]])
    
    
    
    
    
    runIn(45, 'finishScan', [data: [lockToken: lockToken]])
}









void fetchRegistry(jobData = null) {
    String lockToken = jobData?.lockToken as String
    
    
    
    
    if (!ownsLock(lockToken)) {
        if (diagOn()) log.info "${app.label}: registry fetch for a superseded scan generation, discarding without publishing"
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
    }

    
    
    
    
    
    
    REGISTRY_RESULTS.put(genKey(lockToken), [meta: meta, matches: matches, createdAt: now()])

    
    
    
    
    if (!ownsLock(lockToken)) {
        if (diagOn()) log.info "${app.label}: registry fetch completed for a superseded scan generation, discarding without publishing"
        return
    }

    
    
    if (!meta.error) state.registryMatches = matches
    state.registryMeta = meta
    
    
    
    
    if (meta.error) {
        log.warn "${app.label}: registry unavailable, continuing without it: ${meta.error}"
    } else if (diagOn()) {
        log.info "${app.label}: registry gave ${meta.matched} dependency match(es) from ${meta.entries} entries"
    }
    runIn(1, 'finishScan', [data: [lockToken: lockToken]])
}






void finishScan(data = null) {
    String lockToken = data?.lockToken as String
    
    
    
    
    
    
    String logicalGen = (data?.logicalGen ?: lockToken) as String
    boolean finished = finishGeneration(lockToken, null, logicalGen) {
        
        
        
        
        
        
        
        
        state.hubVariableInventory = fetchHubVariableInventory()

        
        
        
        
        
        
        
        
        
        
        Map regResult = REGISTRY_RESULTS.get(genKey(lockToken)) as Map
        boolean registryTimedOut = (regResult == null)
        if (registryTimedOut) {
            state.registryMeta = [state: 'FAILED', fetched: null, entries: 0, matched: 0,
                                   error: 'the registry fetch did not complete', schemaVersion: null]
            log.warn "${app.label}: registry fetch did not complete, continuing without it"
        } else {
            Map regMeta = (regResult.meta as Map)
            
            
            
            
            
            if (!regMeta.error) state.registryMatches = (regResult.matches as List)
            state.registryMeta = regMeta
        }

        Map graph = buildGraph()
        state.scanHeartbeat = now()
        state.graph = graph
        atomicState.graphVersion = GRAPH_SCHEMA
        
        
        
        Long graphCommittedAt = now()
        state.graphCommittedAtLocal = graphCommittedAt
        atomicState.graphCommittedAt = graphCommittedAt

        
        
        
        
        Map appInfo = (state.appInfo ?: [:]) as Map
        appInfo.each { String appId, info ->
            if (info instanceof Map) (info as Map).remove('flow')
        }
        state.appInfo = appInfo
        
        
        
        
        int links = 0
        ((graph.edges ?: []) as List).each { e ->
            
            
            String kind = "${(e as Map).kind}"
            if (RULE_LINK_KIND_NAMES.contains(kind)) links++
        }
        state.ruleLinks = links

        
        
        int inertCount = 0
        ((graph.nodes ?: []) as List).each { n ->
            if ((n as Map).inert == true) inertCount++
        }
        state.appsInert = inertCount

        
        
        
        state.hubVariableConnectorCount = (graph.hubVariableConnectorCount ?: 0) as Integer

        
        
        
        
        Long scanStartedAtMs = lockToken.tokenize('-')[1] as Long
        
        
        
        
        state.lastScanDurationSeconds = ((now() - scanStartedAtMs) / 1000).intValue()

        if (diagOn()) {
            log.info "${app.label}: scan complete in ${state.lastScanDurationSeconds}s - " +
                     "${(state.appInfo as Map).size()} app(s), ${(state.deviceLabels as Map).size()} device(s)"
            log.info "${app.label}: webCoRE decode - ${webcoreDecodeSummary()}"
        }
    }
    if (!finished) {
        
        
        
        if (diagOn()) log.info "${app.label}: finishScan for a superseded scan generation, not building or publishing"
    }
}















































Map fetchDeviceListBulk() {
    Map out = [labels: [:], rooms: [:], types: [:], typeGroups: [:], parents: [:], disabledDevices: [], error: null]
    Map result = httpFetch("${LOOPBACK_BASE}/hub2/devicesList", 30)
    if (!result.ok) {
        log.warn "${app.label}: could not list devices: ${result.error}"
        out.error = result.error
        return out
    }
    Map data = (result.data instanceof Map) ? (result.data as Map) : [:]
    return aggregateDeviceTree(data)
}





















Map aggregateDeviceTree(Map data) {
    Map out = [labels: [:], rooms: [:], types: [:], typeGroups: [:], parents: [:], disabledDevices: [], error: null]
    Map<String, Map> byId = [:]
    List order = []
    List pending = []
    (data.devices ?: []).each { pending << [node: it, parentId: null] }
    while (pending) {
        Map item = pending.remove(0) as Map
        def node = item.node
        if (!(node instanceof Map)) continue
        Map entry = node as Map
        
        
        
        
        Map d = (entry.data instanceof Map) ? (entry.data as Map) : null
        String entryId = (d && d.id != null) ? "${d.id}" : null
        List kids = (entry.children instanceof List) ? (entry.children as List) : null
        if (kids) kids.each { pending << [node: it, parentId: entryId] }
        if (entryId == null || d == null) continue
        Map agg = byId[entryId]
        if (agg == null) {
            agg = [:]
            byId[entryId] = agg
            order << entryId
        }
        if (!agg.name && d.name) agg.name = "${d.name}"
        String room = d.roomName == null ? '' : "${d.roomName}".trim()
        if (!agg.room && room) agg.room = room
        if (!agg.type && d.type) agg.type = "${d.type}"
        if (agg.deviceTypeId == null && d.deviceTypeId != null) agg.deviceTypeId = "${d.deviceTypeId}"
        if (!agg.parentId && item.parentId) agg.parentId = item.parentId as String
        
        
        
        
        if (agg.disabled == null && d.containsKey('disabled')) agg.disabled = (d.disabled == true)
    }
    Map typeGroups = [:]
    order.each { String devId ->
        Map agg = byId[devId] as Map
        
        
        
        
        out.labels[devId] = (agg.name ?: "Device ${devId}") as String
        if (agg.room) out.rooms[devId] = agg.room as String
        if (agg.type) out.types[devId] = agg.type as String
        if (agg.parentId) out.parents[devId] = agg.parentId as String
        if (agg.disabled == true) (out.disabledDevices as List) << devId
        
        
        
        
        
        String typeKey = (agg.room && agg.deviceTypeId != null) ? "${agg.deviceTypeId}" : "room:${devId}"
        List group = (typeGroups[typeKey] = typeGroups[typeKey] ?: []) as List
        group << devId
    }
    out.typeGroups = typeGroups
    return out
}
























Map fetchInstalledAppIds() {
    List ids = []
    Map out = [ids: ids, error: null]
    Map result = httpFetch("${LOOPBACK_BASE}/hub2/appsList", 30)
    if (!result.ok) {
        
        
        
        out.error = result.error ?: 'the hub returned no error detail'
        return out
    }
    if (!(result.data instanceof Map) || !((result.data as Map).apps instanceof List)) {
        out.error = 'the hub returned an unexpected apps-list response'
        return out
    }
    collectAppIds((result.data as Map).apps, ids)
    ids = ids.unique()
    
    
    
    String selfId = "${app.id}"
    if (!ids.contains(selfId)) {
        out.error = "the installed-app listing omitted Automation Map (${selfId})"
        return out
    }
    out.ids = ids
    return out
}




void collectAppIds(def nodes, List ids) {
    if (!(nodes instanceof List)) return
    List pending = []
    pending.addAll(nodes as List)
    while (pending) {
        def node = pending.remove(0)
        if (!(node instanceof Map)) continue
        Map entry = node as Map
        Map data = entry.data as Map
        
        
        
        
        
        if (data?.id != null) {
            String id = "${data.id}"
            ids << id
        }
        if (entry.children instanceof List) pending.addAll(entry.children as List)
    }
}








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


















Map decodeWebcorePistonDocument(Map data) {
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
    if ((0..maximum).any { !chunks.containsKey(it) }) {
        return [status: 'error', error: 'missing-chunk']
    }
    if ((0..maximum).any { chunks[it] == null || chunks[it].isEmpty() }) {
        return [status: 'error', error: 'empty-chunk']
    }
    int encodedLength = (0..maximum).sum { chunks[it].length() } as int
    if (encodedLength > 2_000_000) {
        return [status: 'error', error: 'configuration-too-large']
    }

    byte[] decoded
    try {
        decoded = (0..maximum).collect { chunks[it] }.join('').decodeBase64()
    } catch (Exception ignored) {
        return [status: 'error', error: 'invalid-base64']
    }

    Object document
    try {
        String json = decodeWebcoreEmoji(new String(decoded, 'UTF-8'))
        document = new groovy.json.JsonSlurper().parseText(json)
    } catch (Exception ignored) {
        return [status: 'error', error: 'invalid-json']
    }
    if (!(document instanceof Map)) {
        return [status: 'error', error: 'unexpected-root']
    }
    return [status: 'complete', document: document]
}

Map decodeWebcoreHubVariableUses(Map data) {
    Map decoded = decodeWebcorePistonDocument(data)
    if (decoded.status == 'error') return [status: 'error', error: decoded.error, hubVariables: []]
    if (decoded.status != 'complete') return [status: decoded.status, hubVariables: []]
    Map<String, Set<String>> roles = [:]
    collectWebcoreHubVariableRoles(decoded.document, roles, false)
    List<String> reads = roles.findAll { String name, Set<String> found -> found.contains('read') }.keySet().sort()
    List<String> writes = roles.findAll { String name, Set<String> found -> found.contains('write') }.keySet().sort()
    List<String> unknown = roles.findAll { String name, Set<String> found -> found.contains('unknown') }.keySet().sort()
    return [status: 'complete', reads: reads, writes: writes, hubVariables: unknown]
}

void collectWebcoreHubVariableRoles(Object value, Map<String, Set<String>> roles, boolean suppressCurrentRead) {
    if (value instanceof List) {
        (value as List).each { Object child -> collectWebcoreHubVariableRoles(child, roles, false) }
        return
    }
    if (!(value instanceof Map)) return

    Map item = value as Map
    Object writeTarget = null

    
    
    
    
    if (item.c == 'setVariable' && item.p instanceof List && (item.p as List)) {
        Object first = (item.p as List)[0]
        if (first instanceof Map && (first as Map).t == 'x') {
            writeTarget = first
            addWebcoreHubVariableRole(roles, (first as Map).x, 'write')
        }
    }

    String itemType = item.t instanceof String ? item.t as String : null
    if (itemType in ['for', 'each']) {
        addWebcoreHubVariableRole(roles, item.x, 'write')
    }
    if (itemType == 'p') {
        addWebcoreHubVariableRole(roles, item.dm, 'write')
        addWebcoreHubVariableRole(roles, item.dn, 'write')
    }
    if (itemType == 'function' && "${item.n ?: ''}".equalsIgnoreCase('setVariable')) {
        Object staticTarget = webcoreStaticStringArgument(item.i instanceof List && (item.i as List) ? (item.i as List)[0] : null)
        addWebcoreHubVariableRole(roles, staticTarget, 'write')
    }

    
    
    
    
    if (!suppressCurrentRead && item.x != null && itemType in ['x', 'variable', 'device']) {
        addWebcoreHubVariableRole(roles, item.x, 'read')
    }
    
    
    if (item.d instanceof List && itemType in ['p', 'd', 'action']) {
        (item.d as List).each { Object deviceOrVariable ->
            addWebcoreHubVariableRole(roles, deviceOrVariable, 'read')
        }
    }

    item.values().each { Object child ->
        if (child instanceof List) {
            (child as List).each { Object listChild ->
                collectWebcoreHubVariableRoles(listChild, roles, listChild.is(writeTarget))
            }
        } else if (child instanceof Map) {
            collectWebcoreHubVariableRoles(child, roles, child.is(writeTarget))
        }
    }
}

void addWebcoreHubVariableRole(Map<String, Set<String>> roles, Object rawName, String role) {
    if (rawName instanceof List) {
        (rawName as List).each { Object one -> addWebcoreHubVariableRole(roles, one, role) }
        return
    }
    if (!(rawName instanceof String)) return
    String name = webcoreBaseVariableName(rawName as String)
    if (!name.startsWith('@@') || name.length() <= 2) return
    String hubName = name.substring(2)
    if (!roles[hubName]) roles[hubName] = [] as Set<String>
    roles[hubName] << role
}





Object webcoreStaticStringArgument(Object value) {
    Object current = value
    while (current instanceof Map && (current as Map).t == 'expression') {
        Object items = (current as Map).i
        if (!(items instanceof List) || (items as List).size() != 1) return null
        current = (items as List)[0]
    }
    if (current instanceof Map && (current as Map).t == 'string' && (current as Map).v instanceof String) {
        return (current as Map).v
    }
    return null
}

String decodeWebcoreEmoji(String value) {
    if (!value) return ''
    return value.replaceAll(/(:%[0-9A-F]{2}%[0-9A-F]{2}%[0-9A-F]{2}%[0-9A-F]{2}:)/) { Object match ->
        String token = (match instanceof List ? match[0] : match) as String
        URLDecoder.decode(token.substring(1, 13), 'UTF-8')
    }
}




String webcoreBaseVariableName(String name) {
    if (name && !name.startsWith('$') && name.endsWith(']')) {
        List<String> parts = name.substring(0, name.length() - 1).tokenize('[')
        if (parts.size() == 2) return parts[0]
    }
    return name ?: ''
}





String webcoreSanitizeLocalName(String name) {
    name ? name.trim().replace(' ', '_') : ''
}












Map collectWebcorePistonLocalVariables(Object document) {
    Set<String> declared = [] as LinkedHashSet<String>
    Map<String, String> declaredType = [:]
    if (document instanceof Map && (document as Map).v instanceof List) {
        ((document as Map).v as List).each { Object declaration ->
            if (!(declaration instanceof Map)) return
            Map d = declaration as Map
            String name = d.n instanceof String ? (d.n as String).trim() : null
            if (!name) return
            String base = webcoreSanitizeLocalName(name)
            declared << base
            if (!declaredType.containsKey(base)) declaredType[base] = (d.t instanceof String ? d.t as String : null)
        }
    }
    Map<String, Set<String>> roles = [:]
    collectWebcoreLocalVariableRoles(document, declared, roles, false)
    List<Map> definitions = declared.sort().collect { String name -> [name: name, engineVariableType: declaredType[name]] }
    List<String> reads = roles.findAll { String n, Set<String> f -> f.contains('read') }.keySet().sort()
    List<String> writes = roles.findAll { String n, Set<String> f -> f.contains('write') }.keySet().sort()
    return [definitions: definitions, reads: reads, writes: writes]
}

void collectWebcoreLocalVariableRoles(Object value, Set<String> declared, Map<String, Set<String>> roles, boolean suppressCurrentRead) {
    if (value instanceof List) {
        (value as List).each { Object child -> collectWebcoreLocalVariableRoles(child, declared, roles, false) }
        return
    }
    if (!(value instanceof Map)) return

    Map item = value as Map
    Object writeTarget = null

    if (item.c == 'setVariable' && item.p instanceof List && (item.p as List)) {
        Object first = (item.p as List)[0]
        if (first instanceof Map && (first as Map).t == 'x') {
            writeTarget = first
            addWebcoreLocalVariableRole(roles, declared, (first as Map).x, 'write')
        }
    }

    String itemType = item.t instanceof String ? item.t as String : null
    if (itemType in ['for', 'each']) {
        addWebcoreLocalVariableRole(roles, declared, item.x, 'write')
    }
    if (itemType == 'p') {
        addWebcoreLocalVariableRole(roles, declared, item.dm, 'write')
        addWebcoreLocalVariableRole(roles, declared, item.dn, 'write')
    }
    if (itemType == 'function' && "${item.n ?: ''}".equalsIgnoreCase('setVariable')) {
        Object staticTarget = webcoreStaticStringArgument(item.i instanceof List && (item.i as List) ? (item.i as List)[0] : null)
        addWebcoreLocalVariableRole(roles, declared, staticTarget, 'write')
    }

    if (!suppressCurrentRead && item.x != null && itemType in ['x', 'variable', 'device']) {
        addWebcoreLocalVariableRole(roles, declared, item.x, 'read')
    }
    if (item.d instanceof List && itemType in ['p', 'd', 'action']) {
        (item.d as List).each { Object deviceOrVariable ->
            addWebcoreLocalVariableRole(roles, declared, deviceOrVariable, 'read')
        }
    }

    item.values().each { Object child ->
        if (child instanceof List) {
            (child as List).each { Object listChild ->
                collectWebcoreLocalVariableRoles(listChild, declared, roles, listChild.is(writeTarget))
            }
        } else if (child instanceof Map) {
            collectWebcoreLocalVariableRoles(child, declared, roles, child.is(writeTarget))
        }
    }
}

void addWebcoreLocalVariableRole(Map<String, Set<String>> roles, Set<String> declared, Object rawName, String role) {
    if (rawName instanceof List) {
        (rawName as List).each { Object one -> addWebcoreLocalVariableRole(roles, declared, one, role) }
        return
    }
    if (!(rawName instanceof String)) return
    String name = webcoreBaseVariableName(rawName as String)
    
    
    if (name.startsWith('@') || name.startsWith('$')) return
    String sanitized = webcoreSanitizeLocalName(name)
    if (!declared.contains(sanitized)) return
    if (!roles[sanitized]) roles[sanitized] = [] as Set<String>
    roles[sanitized] << role
}








Map collectWebcorePistonDeviceReferences(Object document) {
    List<Map> reads = []
    List<Map> actions = []
    Map<String, Integer> unsupported = [:]
    walkWebcoreDeviceNodes(document, reads, actions, unsupported)
    return [reads: reads, actions: actions, unsupported: unsupported]
}

void walkWebcoreDeviceNodes(Object value, List<Map> reads, List<Map> actions, Map<String, Integer> unsupported,
                            String role = null) {
    if (value instanceof List) {
        (value as List).each { Object child -> walkWebcoreDeviceNodes(child, reads, actions, unsupported, role) }
        return
    }
    if (!(value instanceof Map)) return
    Map item = value as Map
    String itemType = item.t instanceof String ? item.t as String : null

    if (itemType == 'p') {
        String attribute = item.a instanceof String ? item.a as String : null
        classifyWebcoreDeviceList(item.d, unsupported).each { String token ->
            reads << [token: token, attribute: attribute, role: role]
        }
    } else if (itemType == 'action') {
        List<String> commands = (item.k instanceof List ? item.k as List : []).findResults { Object task ->
            (task instanceof Map && (task as Map).c instanceof String) ? (task as Map).c as String : null
        }
        classifyWebcoreDeviceList(item.d, unsupported).each { String token ->
            actions << [token: token, commands: commands]
        }
    }

    
    
    
    
    
    
    
    
    item.each { Object rawKey, Object child ->
        String key = String.valueOf(rawKey)
        if (key == 'c' && child instanceof List) {
            (child as List).each { Object entry ->
                walkWebcoreDeviceNodes(entry, reads, actions, unsupported,
                        webcoreDeviceReadRole(entry, role))
            }
            return
        }
        if (child instanceof List) {
            (child as List).each { Object listChild -> walkWebcoreDeviceNodes(listChild, reads, actions, unsupported, role) }
        } else if (child instanceof Map) {
            walkWebcoreDeviceNodes(child, reads, actions, unsupported, role)
        }
    }
}




String webcoreDeviceReadRole(Object entry, String inherited) {
    if (!(entry instanceof Map)) return inherited
    Map node = entry as Map
    String type = String.valueOf(node.t ?: '')
    if (type == 'event') return 'trigger'
    
    
    
    if (type == 'condition' || type == 'group') return webcoreFlowRole(node)
    return inherited
}










List<String> classifyWebcoreDeviceList(Object dList, Map<String, Integer> unsupported) {
    List<String> tokens = []
    (dList instanceof List ? dList as List : []).each { Object entry ->
        if (!(entry instanceof String)) {
            unsupported['malformed-device-node'] = (unsupported['malformed-device-node'] ?: 0) + 1
            return
        }
        String s = entry as String
        
        
        
        if (s ==~ /^:[0-9a-f]{32}:$/) {
            tokens << s
        } else if (s == '$currentEventDevice') {
            unsupported['runtime-selected-device'] = (unsupported['runtime-selected-device'] ?: 0) + 1
        } else if (s.startsWith('@')) {
            unsupported['variable-backed-device-list'] = (unsupported['variable-backed-device-list'] ?: 0) + 1
        } else {
            unsupported['non-physical-device'] = (unsupported['non-physical-device'] ?: 0) + 1
        }
    }
    return tokens
}














List webcoreFlowTriggerComparisons() {
    return ['arrives', 'becomes_even', 'becomes_odd', 'changes', 'changes_away_from',
            'changes_away_from_any_of', 'changes_to', 'changes_to_any_of', 'does_not_drop',
            'does_not_rise', 'drops', 'drops_below', 'drops_to_or_below', 'enters_range',
            'event_occurs', 'executes', 'exits_range', 'gets', 'gets_any', 'happens_daily_at',
            'receives', 'remains_above', 'remains_above_or_equal_to', 'remains_below',
            'remains_below_or_equal_to', 'remains_even', 'remains_inside_of_range', 'remains_odd',
            'remains_outside_of_range', 'rises', 'rises_above', 'rises_to_or_above', 'stays',
            'stays_any_of', 'stays_away_from', 'stays_away_from_any_of', 'stays_different_than',
            'stays_equal_to', 'stays_even', 'stays_greater_than', 'stays_greater_than_or_equal_to',
            'stays_inside_of_range', 'stays_less_than', 'stays_less_than_or_equal_to', 'stays_not',
            'stays_odd', 'stays_outside_of_range', 'stays_unchanged']
}






List webcoreFlowConditionComparisons() {
    return ['changed', 'did_not_change', 'is', 'is_not', 'is_any_of', 'is_not_any_of', 'is_equal_to',
            'is_different_than', 'is_less_than', 'is_less_than_or_equal_to', 'is_greater_than',
            'is_greater_than_or_equal_to', 'is_inside_of_range', 'is_outside_of_range', 'is_even',
            'is_odd', 'was', 'was_not', 'was_any_of', 'was_not_any_of', 'was_equal_to',
            'was_different_than', 'was_less_than', 'was_less_than_or_equal_to', 'was_greater_than',
            'was_greater_than_or_equal_to', 'was_inside_of_range', 'was_outside_of_range', 'was_even',
            'was_odd', 'is_any', 'is_before', 'is_after', 'is_between', 'is_not_between']
}
















String webcoreFlowRole(Map condition) {
    
    
    String co = String.valueOf(condition?.co ?: '')
    boolean triggerOp = webcoreFlowTriggerComparisons().contains(co)
    boolean conditionOp = webcoreFlowConditionComparisons().contains(co)
    if (!triggerOp && !conditionOp) return null
    String byMembership = triggerOp ? 'trigger' : 'constraint'
    String ct = String.valueOf(condition?.ct ?: '')
    if (!ct) return byMembership
    String byStored = ct == 't' ? 'trigger' : (ct == 'c' ? 'constraint' : null)
    if (byStored == null) return null
    return byStored == byMembership ? byMembership : null
}



boolean webcoreFlowIsTrigger(Map condition) {
    return webcoreFlowRole(condition) == 'trigger'
}









Map processAppRelationships(String appId, Map data, Map labels, Map appTypeNamespaces = [:]) {
    Map out = [id: appId, label: "App ${appId}", type: null, namespace: null, roles: [:], flow: [], stateful: [], ruleLinks: [], endpoints: [], hubVarWrites: [], hubVarReads: [], error: null]
    try {
            Map installedApp = data.installedApp as Map
            String rawLabel = stripReplacementChar((installedApp?.label ?: installedApp?.trueLabel ?: installedApp?.name ?: "App ${appId}") as String)
            out.label = stripTags(rawLabel)
            
            
            
            out.drawLabel = stripStatusMarkup(rawLabel)
            
            
            
            
            
            out.broken = rawLabel.contains('*BROKEN*')
            out.type = stripReplacementChar(installedApp?.name as String)
            
            
            if (installedApp?.appTypeId != null) {
                out.namespace = appTypeNamespaces["${installedApp.appTypeId}"]
            }
            
            
            
            
            
            
            if (installedApp?.parentAppId != null) out.parent = "${installedApp.parentAppId}"

            
            
            
            
            
            
            if ("${out.type}".startsWith(APP_FAMILY)) {
                out.roles = [:]
                out.flow = []
                out.ruleLinks = []
                out.endpoints = []
                out.hubVarWrites = []
                out.hubVarReads = []
                
                
                
                
                
                
                
                
                
                List selfJobs = scheduledJobList(data.scheduledJobs)
                out.inert = [
                    kids  : (data.childAppCount ?: 0) as Integer,
                    devs  : (data.childDeviceCount ?: 0) as Integer,
                    sched : selfJobs.size(),
                    schedJobs : selfJobs.collect { Map j -> [next: "${j.nextRunTime}", cron: "${j.schedule}"] },
                    subs  : countOf(data.eventSubscriptions),
                ]
                return out
            }

            
            
            
            boolean paused = false
            (data.appState ?: []).each { e ->
                if (e instanceof Map && e.name == 'paused' && e.value == true) paused = true
            }
            
            
            
            
            
            
            out.disabled = (installedApp?.disabled == true)
            out.paused = paused
            out.inactive = out.disabled || out.paused

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
                    
                    
                    if (role == 'action' && isStatefulCapability(settingType) && !stateful.contains(devId)) {
                        stateful << devId
                    }
                }
            }

            
            
            out.unusedConstraints = unusedConstraintDeviceIds(data)

            
            
            
            
            
            
            
            
            
            subscribed.each { String devId ->
                List existing = (roles[devId] ?: []) as List
                if (!existing) addRole(roles, devId, 'trigger')
            }

            
            
            
            
            
            
            
            
            
            
            
            String normalizedAppType = (out.type ?: '').toString().trim()
            if (normalizedAppType == 'webCoRE') {
                
                
                
                
                
                
                Set<String> permitted = [] as LinkedHashSet<String>
                (data.appSettings instanceof List ? data.appSettings as List : []).each { Object raw ->
                    if (!(raw instanceof Map)) return
                    Map setting = raw as Map
                    if (!("${setting.type ?: ''}").startsWith('capability')) return
                    (setting.deviceIdsForDeviceList instanceof List ? setting.deviceIdsForDeviceList as List : []).each { Object devId ->
                        if (devId != null) permitted << "${devId}"
                    }
                }
                out.webcorePermittedDeviceIds = permitted.toList()
            }
            if (normalizedAppType == 'webCoRE' || normalizedAppType == 'webCoRE Piston') {
                roles.clear()
                stateful.clear()
                out.webcoreDeviceRelationshipsSuppressed = true
            }

            out.roles = roles
            out.stateful = stateful
            out.flow = buildRuleFlow(data)
            out.ruleLinks = extractRuleLinks(data, appId)
            out.endpoints = extractRuleEndpoints(data)
            
            
            
            
            
            
            
            
            
            
            
            
            
            
            
            if ("${out.type}".startsWith('Rule-')) {
                out.hubVarWrites = extractHubVariableWrites(data)
                out.hubVarReads = extractHubVariableReads(data)
                
                
                
                
                
                
                
                out.localVariables = extractLocalVariableDefinitions(data, "a${appId}")
            }
            if ("${out.type}" == 'webCoRE Piston') {
                
                
                
                
                Map decodedPiston = decodeWebcorePistonDocument(data)
                out.webcoreVariableDecodeStatus = decodedPiston.status
                if (decodedPiston.error) out.webcoreVariableDecodeError = decodedPiston.error
                if (decodedPiston.status == 'complete') {
                    Object document = decodedPiston.document
                    Map<String, Set<String>> hubRoles = [:]
                    collectWebcoreHubVariableRoles(document, hubRoles, false)
                    out.webcoreHubVarReads = hubRoles.findAll { String n, Set<String> f -> f.contains('read') }.keySet().sort()
                    out.webcoreHubVarWrites = hubRoles.findAll { String n, Set<String> f -> f.contains('write') }.keySet().sort()
                    out.webcoreHubVarUses = hubRoles.findAll { String n, Set<String> f -> f.contains('unknown') }.keySet().sort()

                    Map localVars = collectWebcorePistonLocalVariables(document)
                    out.webcoreLocalVariableDefinitions = localVars.definitions
                    out.webcoreLocalVariableReads = localVars.reads
                    out.webcoreLocalVariableWrites = localVars.writes

                    Map deviceRefs = collectWebcorePistonDeviceReferences(document)
                    out.webcoreDeviceReads = deviceRefs.reads
                    out.webcoreDeviceActions = deviceRefs.actions
                    out.webcoreUnsupportedDeviceRefs = deviceRefs.unsupported

                    
                    
                    
                    
                    
                    
                    out.flow = buildWebcoreFlow(document)
                } else {
                    out.webcoreHubVarReads = []
                    out.webcoreHubVarWrites = []
                    out.webcoreHubVarUses = []
                    out.webcoreLocalVariableDefinitions = []
                    out.webcoreLocalVariableReads = []
                    out.webcoreLocalVariableWrites = []
                    out.webcoreDeviceReads = []
                    out.webcoreDeviceActions = []
                    out.webcoreUnsupportedDeviceRefs = [:]
                }
            }

            
            
            
            
            
            
            
            
            
            
            
            
            
            if (!roles && !out.ruleLinks && !out.endpoints) {
                
                
                
                List jobs = scheduledJobList(data.scheduledJobs)
                out.inert = [
                    kids  : (data.childAppCount ?: 0) as Integer,
                    devs  : (data.childDeviceCount ?: 0) as Integer,
                    sched : jobs.size(),
                    
                    
                    
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































int webcoreFlowMaxDepth() { return 12 }
int webcoreFlowMaxSteps() { return 400 }

List buildWebcoreFlow(Object document) {
    if (!(document instanceof Map)) return []
    Object statements = (document as Map).s
    List steps = []
    webcoreFlowStatements(statements instanceof List ? statements as List : [], steps, 0)
    return steps
}

void webcoreFlowStatements(List statements, List steps, int depth) {
    if (depth > webcoreFlowMaxDepth()) return
    statements.each { Object raw ->
        if (!(raw instanceof Map)) return
        if (steps.size() >= webcoreFlowMaxSteps()) return
        webcoreFlowStatement(raw as Map, steps, depth)
    }
}

void webcoreFlowStatement(Map st, List steps, int depth) {
    String type = "${st.t ?: ''}"
    List body = (st.s instanceof List) ? st.s as List : []
    switch (type) {
        case 'on':
            
            ((st.c instanceof List) ? st.c as List : []).each { Object raw ->
                if (!(raw instanceof Map)) return
                Map event = raw as Map
                Map lo = (event.lo instanceof Map) ? event.lo as Map : [:]
                steps << webcoreFlowNode('trigger', webcoreFlowEventLabel(event), webcoreFlowDeviceTokens(lo))
            }
            webcoreFlowStatements(body, steps, depth + 1)
            break
        case 'if':
            
            
            
            
            
            List triggerParts = webcoreFlowConditionParts(st, true)
            List conditionParts = webcoreFlowConditionParts(st, false)
            triggerParts.each { Object part ->
                steps << webcoreFlowTrigger(part as Map, "${st.o ?: 'and'}")
            }
            boolean decides = !conditionParts.isEmpty()
            if (decides) {
                steps << webcoreFlowControl('if', webcoreFlowConditionLabel(st, false),
                        conditionParts, "${st.o ?: 'and'}")
            }
            webcoreFlowStatements(body, steps, depth + 1)
            ((st.ei instanceof List) ? st.ei as List : []).each { Object raw ->
                if (!(raw instanceof Map)) return
                Map branch = raw as Map
                steps << webcoreFlowControl('elseif', webcoreFlowConditionLabel(branch, false),
                        webcoreFlowConditionParts(branch, false), "${branch.o ?: 'and'}")
                webcoreFlowStatements((branch.s instanceof List) ? branch.s as List : [], steps, depth + 1)
            }
            List elseBody = (st.e instanceof List) ? st.e as List : []
            if (elseBody) {
                if (decides) steps << webcoreFlowControl('else', '')
                webcoreFlowStatements(elseBody, steps, depth + 1)
            }
            
            
            if (decides) steps << webcoreFlowControl('endif', '')
            break
        case 'switch':
            
            
            
            
            boolean opened = false
            ((st.cs instanceof List) ? st.cs as List : []).each { Object raw ->
                if (!(raw instanceof Map)) return
                Map branch = raw as Map
                steps << webcoreFlowControl(opened ? 'elseif' : 'if', 'case not decoded')
                opened = true
                webcoreFlowStatements((branch.s instanceof List) ? branch.s as List : [], steps, depth + 1)
            }
            if (opened) steps << webcoreFlowControl('endif', '')
            break
        case 'while':
        case 'repeat':
        case 'for':
        case 'each':
            
            
            
            steps << webcoreFlowNode('action', webcoreFlowLoopLabel(type), webcoreFlowDeviceTokens(st))
            webcoreFlowStatements(body, steps, depth + 1)
            
            
            
            steps << webcoreFlowNode('action', 'end ' + webcoreFlowLoopLabel(type).replace(' (loop)', ''), [])
            break
        case 'action':
            
            
            List tokens = webcoreFlowDeviceTokens(st)
            List tasks = (st.k instanceof List) ? st.k as List : []
            if (!tasks) {
                steps << webcoreFlowNode('action', 'action', tokens)
            } else {
                tasks.each { Object raw ->
                    if (!(raw instanceof Map)) return
                    steps << webcoreFlowNode('action', webcoreFlowTaskLabel(raw as Map), tokens)
                }
            }
            break
        case 'do':
            webcoreFlowStatements(body, steps, depth + 1)
            break
        case 'break':
            steps << webcoreFlowNode('action', 'break', [])
            break
        case 'exit':
            steps << webcoreFlowNode('action', 'exit piston', [])
            break
        case 'every':
            steps << webcoreFlowNode('trigger', 'every (timer)', [])
            webcoreFlowStatements(body, steps, depth + 1)
            break
        default:
            steps << webcoreFlowNode('action', type ? "${type} not decoded" : 'not decoded',
                    webcoreFlowDeviceTokens(st))
            webcoreFlowStatements(body, steps, depth + 1)
            break
    }
}

String webcoreFlowLoopLabel(String type) {
    switch (type) {
        case 'while':  return 'while (loop)'
        case 'repeat': return 'repeat (loop)'
        case 'for':    return 'for each step (loop)'
        case 'each':   return 'for each device (loop)'
    }
    return 'loop'
}







String webcoreFlowTaskLabel(Map task) {
    String command = "${task?.c ?: ''}"
    if (!command) return 'task'
    List params = (task?.p instanceof List) ? task.p as List : []
    if (!params) return command
    List rendered = []
    for (Object raw : params) {
        if (!(raw instanceof Map)) return command
        String text = webcoreFlowOperandText(raw as Map)
        if (!text) return command
        rendered << text
    }
    return "${command}(${rendered.join(', ')})"
}




List webcoreFlowDeviceTokens(Map node) {
    Object raw = node?.d
    if (!(raw instanceof List)) return []
    List out = []
    (raw as List).each { Object token ->
        if (!(token instanceof String)) return
        String value = token as String
        if (value ==~ /^:[0-9a-f]{32}:$/ && !out.contains(value)) out << value
    }
    return out
}

String webcoreFlowEventLabel(Map event) {
    Map lo = (event?.lo instanceof Map) ? event.lo as Map : [:]
    switch ("${lo.t ?: ''}") {
        case 'p':
            String attribute = "${lo.a ?: ''}"
            return attribute ? "When ${attribute} changes" : 'When a device event fires'
        case 'v':
            String virtual = "${lo.v ?: ''}"
            return virtual ? "When ${virtual} changes" : 'When a virtual event fires'
        case 'x':
            String variable = "${lo.x ?: ''}"
            return variable ? "When ${variable} changes" : 'When a variable changes'
    }
    return 'When an event fires'
}




String webcoreFlowConditionLabel(Map st, boolean includeTriggers = true) {
    Object raw = st?.c
    int count = 0
    if (raw instanceof List) {
        (raw as List).each { Object entry ->
            if (!(entry instanceof Map)) return
            if (includeTriggers || !webcoreFlowIsTrigger(entry as Map)) count++
        }
    }
    return count > 1 ? "${count} conditions not decoded" : 'condition not decoded'
}




int webcoreFlowMaxConditionDepth() { return 6 }








List webcoreFlowConditionParts(Map st, Boolean triggersWanted = null, int depth = 0) {
    Object raw = st?.c
    if (!(raw instanceof List)) return []
    List parts = []
    (raw as List).each { Object entry ->
        if (!(entry instanceof Map)) return
        Map condition = entry as Map
        
        
        boolean isGroup = "${condition.t ?: ''}" != 'condition'
        if (triggersWanted != null) {
            boolean trigger = !isGroup && webcoreFlowIsTrigger(condition)
            if (trigger != triggersWanted.booleanValue()) return
        }
        if (isGroup) {
            
            
            List nested = depth >= webcoreFlowMaxConditionDepth() ? []
                    : webcoreFlowConditionParts(condition, null, depth + 1)
            parts << (nested ? [opaque: false, group: true, parts: nested,
                                joiner: "${condition.o ?: 'and'}"]
                             : [opaque: true])
            return
        }
        Map lo = (condition.lo instanceof Map) ? condition.lo as Map : [:]
        Map ro = (condition.ro instanceof Map) ? condition.ro as Map : [:]
        Map ro2 = (condition.ro2 instanceof Map) ? condition.ro2 as Map : [:]
        String co = String.valueOf(condition.co ?: '')
        
        
        
        Object arity = webcoreFlowComparisonValueCounts()[co]
        String value = webcoreFlowOperandText(ro)
        String value2 = webcoreFlowOperandText(ro2)
        if (arity == null || ((arity as int) >= 1 && !value) || ((arity as int) == 2 && !value2)) {
            parts << [opaque: true]
            return
        }
        parts << [opaque: false,
                  deviceTokens: webcoreFlowDeviceTokens(lo),
                  subject: webcoreFlowOperandText(lo),
                  attribute: "${lo.a ?: ''}",
                  op: co.replace('_', ' '),
                  value: (arity as int) >= 1 ? value : '',
                  value2: (arity as int) == 2 ? value2 : '']
    }
    return parts
}




Map webcoreFlowComparisonValueCounts() {
    return [arrives: 2, becomes_even: 0, becomes_odd: 0, changes: 0, changes_away_from: 1,
            changes_away_from_any_of: 1, changes_to: 1, changes_to_any_of: 1, does_not_drop: 0,
            does_not_rise: 0, drops: 0, drops_below: 1, drops_to_or_below: 1, enters_range: 2,
            event_occurs: 0, executes: 1, exits_range: 2, gets: 1, gets_any: 0, happens_daily_at: 1,
            is: 1, is_after: 1, is_any: 0, is_any_of: 1, is_before: 1, is_between: 2,
            is_different_than: 1, is_equal_to: 1, is_even: 0, is_greater_than: 1,
            is_greater_than_or_equal_to: 1, is_inside_of_range: 2, is_less_than: 1,
            is_less_than_or_equal_to: 1, is_not: 1, is_not_any_of: 1, is_not_between: 2, is_odd: 0,
            is_outside_of_range: 2, receives: 1, remains_above: 1, remains_above_or_equal_to: 1,
            remains_below: 1, remains_below_or_equal_to: 1, remains_even: 0, remains_inside_of_range: 2,
            remains_odd: 0, remains_outside_of_range: 2, rises: 0, rises_above: 1, rises_to_or_above: 1]
}




String webcoreFlowOperandText(Map operand) {
    switch ("${operand?.t ?: ''}") {
        case 'v': return "${operand.v ?: ''}"
        case 'x': return "${operand.x ?: ''}"
        case 'u': return "${operand.u ?: ''}"
        case 'c':
            Object c = operand.c
            return (c instanceof CharSequence || c instanceof Number || c instanceof Boolean) ? "${c}" : ''
    }
    return ''
}




String webcoreFlowConditionText(List parts, String joiner, Map tokenNames) {
    if (!parts) return ''
    List rendered = []
    for (Object raw : parts) {
        if (!(raw instanceof Map)) return ''
        Map part = raw as Map
        if (part.opaque) return ''
        
        
        
        if (part.group) {
            String inner = webcoreFlowConditionText((part.parts ?: []) as List,
                    "${part.joiner ?: 'and'}", tokenNames)
            if (!inner) return ''
            rendered << "(${inner})"
            continue
        }
        String subject = "${part.subject ?: ''}"
        List tokens = (part.deviceTokens ?: []) as List
        if (tokens) {
            List names = []
            tokens.each { Object token -> if (tokenNames["${token}"]) names << "${tokenNames["${token}"]}" }
            if (names.size() != tokens.size()) return ''
            subject = names.join(', ')
            String attribute = "${part.attribute ?: ''}"
            if (attribute) subject = "${subject}'s ${attribute}"
        }
        if (!subject) return ''
        String line = subject
        if ("${part.op ?: ''}") line = "${line} ${part.op}"
        if ("${part.value ?: ''}") line = "${line} ${part.value}"
        if ("${part.value2 ?: ''}") line = "${line} and ${part.value2}"
        rendered << line
    }
    return rendered.join(" ${joiner} ")
}

Map webcoreFlowNode(String kind, String label, List deviceTokens) {
    return [kind: kind, ctrl: null, cond: '', label: label, devices: [],
            deviceTokens: deviceTokens, ruleTargets: [], selfTarget: false]
}




Map webcoreFlowTrigger(Map part, String joiner) {
    return [kind: 'trigger', ctrl: null, cond: '', label: 'trigger not decoded',
            devices: [], deviceTokens: [], conditionParts: [part], conditionJoiner: joiner,
            ruleTargets: [], selfTarget: false]
}

Map webcoreFlowControl(String ctrl, String cond, List parts = [], String joiner = 'and') {
    return [kind: 'action', ctrl: ctrl, cond: cond, label: cond ?: ctrl, devices: [],
            deviceTokens: [], conditionParts: parts, conditionJoiner: joiner,
            ruleTargets: [], selfTarget: false]
}




Map webcoreCensusLimits() {
    return [maxDepth: 100, maxValues: 250000, maxUnrecognised: 50, maxPathLength: 200,
            deadlineCheckInterval: 2000, maxStructureFindings: 50]
}






String webcoreCensusBoundPath(String path, int limit) {
    if (path == null || path.length() <= limit) return path
    String marker = '<path-elided>'
    int head = (limit - marker.length()) / 2 as int
    int tail = limit - marker.length() - head
    return path.substring(0, head) + marker + path.substring(path.length() - tail)
}



List<String> webcoreCensusSchemaKeys() {
    return ['$', 'a', 'c', 'ced', 'cm', 'co', 'cs', 'ct', 'ctp', 'cto', 'd', 'di', 'e', 'ei', 'exp',
            'f', 'fs', 'g', 'i', 'id', 'k', 'l', 'lo', 'lo2', 'lo3', 'm', 'n', 'o', 'ok', 'p',
            'r', 'rn', 'ro', 'ro2', 'rop', 's', 'sm', 'str', 't', 'tcp', 'tep', 'to', 'to2',
            'ts', 'tsp', 'u', 'v', 'vt', 'w', 'wd', 'wt', 'x', 'xi', 'z']
}



List<String> webcoreCensusOpaqueKeys() {
    return ['data', 'zc']
}




String webcoreCensusFixAttr(String attr) {
    if (attr == null) return null
    if (attr in ['orientation', 'axisX', 'axisY', 'axisZ']) return 'threeAxis'
    switch (attr) {
        case 'alarmSystemStatus': return 'hsmStatus'
        case 'alarmSystemAlert': return 'hsmAlert'
        case 'alarmSystemEvent': return 'hsmSetArm'
        case 'alarmSystemRule': return 'hsmRule'
        case 'alarmSystemRules': return 'hsmRules'
    }
    return attr
}



boolean webcoreCensusExpired(Closure expired) {
    return expired != null && expired()
}

String webcoreCensusNodeKind(Object value) {
    if (value instanceof Map) return 'object'
    if (value instanceof List) return 'array'
    return 'scalar'
}

Map collectWebcoreDecodeCoverage(Object document, Map registry, Closure expired = null, Map shapes = null) {
    Map limits = webcoreCensusLimits()
    Map constructs = (registry != null && registry.constructs instanceof Map) ? (registry.constructs as Map) : [:]
    Map provenance = (registry != null && registry.provenance instanceof Map) ? (registry.provenance as Map) : [:]
    Map result = [
        status: 'complete',
        truncation: null,
        registryVersion: provenance.registryVersion,
        provenance: [
            observedWebcoreVersion: null,
            referenceSourceCommit: provenance.commit,
            compatibilityStatus: 'unknown'
        ],
        accounting: [objectsVisited: 0, arraysVisited: 0, fieldsVisited: 0, arrayElementsVisited: 0,
                     scalarsVisited: 0, constructCandidates: 0, constructsIdentified: 0,
                     defaultBranchOccurrences: 0],
        constructCounts: [:],
        constructOccurrences: [:],
        levelCounts: [L0: 0, L1: 0, L2: 0, L3: 0, L4: 0, L5: 0],
        unrecognised: [],
        unrecognisedOverflow: 0,
        structureFindings: [],
        structureFindingsOverflow: 0,
        unrecognisedOutsideStatements: null
    ]
    if (!(document instanceof Map)) {
        result.status = 'error'
        result.error = 'unexpected-root'
        return result
    }

    Map acc = [
        objectsVisited: 0, arraysVisited: 0, fieldsVisited: 0, arrayElementsVisited: 0,
        scalarsVisited: 0, constructCandidates: 0, constructsIdentified: 0,
        defaultBranchOccurrences: 0,
        counts: [:], unrecognised: [], seen: [] as Set, overflow: 0, truncated: null,
        expired: expired, sinceDeadlineCheck: 0,
        schemaKeys: webcoreCensusSchemaKeys() as Set,
        opaqueKeys: webcoreCensusOpaqueKeys() as Set,
        constructs: constructs,
        functionIndex: webcoreCensusFunctionIndex(constructs),
        limits: limits,
        shapes: shapes,
        occurrenceStack: [], occurrences: [:],
        structureFindings: [], structureSeen: [] as Set, structureOverflow: 0, outsideStatements: 0
    ]
    
    
    
    
    
    if (webcoreCensusExpired(expired)) {
        acc.truncated = 'analysis-deadline'
    } else {
        webcoreCensusWalk(document, 'root', '$', 0, acc)
        if (acc.truncated == null && webcoreCensusExpired(expired)) {
            acc.truncated = 'analysis-deadline'
        }
    }

    Map counts = [:]
    (acc.counts as Map).keySet().sort().each { Object id -> counts[id] = (acc.counts as Map)[id] }
    Map occurrences = [:]
    (acc.occurrences as Map).keySet().sort().each { Object id ->
        Map entry = (acc.occurrences as Map)[id] as Map
        Map gapCounts = [:]
        (entry.evidenceGaps as Map).keySet().sort().each { Object g -> gapCounts[g] = (entry.evidenceGaps as Map)[g] }
        occurrences[id] = [structurallyValid: entry.structurallyValid, structurallyInvalid: entry.structurallyInvalid,
                           evidenceGapped: entry.evidenceGapped, evidenceGaps: gapCounts]
    }
    Map levels = [L0: 0, L1: 0, L2: 0, L3: 0, L4: 0, L5: 0]
    counts.keySet().each { Object id ->
        Object entry = constructs[id]
        String level = (entry instanceof Map && (entry as Map).level instanceof String) ? ((entry as Map).level as String) : 'L0'
        if (shapes != null) level = webcoreCensusAchievedLevel(level, occurrences[id], counts[id])
        if (levels.containsKey(level)) levels[level] = (levels[level] as Integer) + 1
    }

    result.accounting = [
        objectsVisited: acc.objectsVisited, arraysVisited: acc.arraysVisited,
        fieldsVisited: acc.fieldsVisited, arrayElementsVisited: acc.arrayElementsVisited,
        scalarsVisited: acc.scalarsVisited, constructCandidates: acc.constructCandidates,
        constructsIdentified: acc.constructsIdentified,
        defaultBranchOccurrences: acc.defaultBranchOccurrences
    ]
    result.constructCounts = counts
    result.levelCounts = levels
    result.unrecognised = acc.unrecognised
    result.unrecognisedOverflow = acc.overflow
    result.constructOccurrences = occurrences
    result.structureFindings = acc.structureFindings
    result.structureFindingsOverflow = acc.structureOverflow
    if (shapes != null) result.unrecognisedOutsideStatements = acc.outsideStatements
    if (acc.truncated != null) {
        result.status = 'truncated'
        result.truncation = [reason: acc.truncated, maxDepth: limits.maxDepth, maxValues: limits.maxValues]
    }
    return result
}




Map webcoreCensusFunctionIndex(Map constructs) {
    Map index = [:]
    constructs.each { Object id, Object entry ->
        String key = "${id}"
        if (!key.startsWith('wc.function.')) return
        index[key.substring('wc.function.'.length()).toLowerCase()] = key
    }
    return index
}



void webcoreCensusWalk(Object value, String context, String path, int depth, Map acc) {
    if (acc.truncated != null) return
    if (depth > ((acc.limits as Map).maxDepth as Integer)) { acc.truncated = 'depth-limit'; return }
    int seen = (acc.objectsVisited as Integer) + (acc.arraysVisited as Integer) + (acc.scalarsVisited as Integer)
    if (seen >= ((acc.limits as Map).maxValues as Integer)) { acc.truncated = 'value-limit'; return }
    
    
    if (acc.expired != null) {
        acc.sinceDeadlineCheck = (acc.sinceDeadlineCheck as Integer) + 1
        if ((acc.sinceDeadlineCheck as Integer) >= ((acc.limits as Map).deadlineCheckInterval as Integer)) {
            acc.sinceDeadlineCheck = 0
            if (webcoreCensusExpired(acc.expired as Closure)) { acc.truncated = 'analysis-deadline'; return }
        }
    }

    if (value instanceof Map) {
        acc.objectsVisited = (acc.objectsVisited as Integer) + 1
        Map node = value as Map
        boolean opened = webcoreCensusOpenOccurrence(node, context, acc)
        webcoreCensusClassify(node, context, path, acc)
        Map shape = webcoreCensusValidate(node, context, path, acc)
        List entryKeys = node.keySet().toList()
        for (int i = 0; i < entryKeys.size(); i++) {
            acc.fieldsVisited = (acc.fieldsVisited as Integer) + 1
            String key = "${entryKeys[i]}"
            Object child = node[entryKeys[i]]
            
            
            if (context == 'opaque') {
                webcoreCensusWalk(child, 'opaque', "${path}.<opaque#${i}>", depth + 1, acc)
                if (acc.truncated != null) return
                continue
            }
            boolean known = (acc.schemaKeys as Set).contains(key)
            boolean opaque = !known && (acc.opaqueKeys as Set).contains(key)
            String childPath = (known || opaque) ? "${path}.${key}" : "${path}.<unknown-key#${i}>"
            if (opaque) webcoreCensusRecord(acc, childPath, 'known-opaque-field', webcoreCensusNodeKind(child))
            else if (!known) webcoreCensusRecord(acc, childPath, 'unknown-key', webcoreCensusNodeKind(child))
            String childContext = opaque ? 'opaque' : (known ? webcoreCensusChildContext(node, context, key) : null)
            if (known && childContext != null && shape != null) childContext = webcoreCensusShapeRoute(node, context, key, child, childContext, shape)
            webcoreCensusWalk(child, childContext, childPath, depth + 1, acc)
            if (acc.truncated != null) return
        }
        if (opened) webcoreCensusCloseOccurrence(acc)
        return
    }
    if (value instanceof List) {
        acc.arraysVisited = (acc.arraysVisited as Integer) + 1
        List list = value as List
        for (int i = 0; i < list.size(); i++) {
            acc.arrayElementsVisited = (acc.arrayElementsVisited as Integer) + 1
            webcoreCensusWalk(list[i], webcoreCensusElementContext(context, list[i], i), "${path}[${i}]", depth + 1, acc)
            if (acc.truncated != null) return
        }
        return
    }
    acc.scalarsVisited = (acc.scalarsVisited as Integer) + 1
    if (context == 'device-list') webcoreCensusDeviceSelector(acc, path, value)
}



String webcoreCensusChildContext(Map node, String context, String key) {
    String t = node.t instanceof String ? (node.t as String) : null
    switch (context) {
        case 'root':
            if (key == 's') return 'statement'
            if (key == 'r') return 'restriction'
            if (key == 'v') return 'variable-declaration'
            return null
        
        
        
        case 'variable-declaration':
            if (key == 'v') return 'operand'
            return null
        case 'statement':
            
            if (key == 's') return (t == 'switch') ? null : 'statement'
            if (key == 'e') return 'statement'
            if (key == 'r') return 'restriction'
            if (key == 'ei') return 'elseif'
            if (key == 'cs') return 'case'
            if (key == 'k') return 'task'
            if (key == 'lo') return 'operand'
            
            
            if ((key == 'lo2' || key == 'lo3') && (t == 'for' || t == 'every')) return 'operand'
            if (key == 'd') return 'device-list'
            
            
            if (key == 'c') return (t == 'on') ? 'event' : webcoreCensusConditionListContext(node)
            return null
        case 'elseif':
            if (key == 'c') return webcoreCensusConditionListContext(node)
            if (key == 's') return 'statement'
            return null
        case 'case':
            if (key == 'ro' || key == 'ro2') return 'operand'
            if (key == 's') return 'statement'
            return null
        
        
        case 'followed-by-later-step':
            if (key == 'wd') return 'operand'
        case 'followed-by-first-step':
        case 'condition':
            
            
            if (key == 'lo' || key == 'ro' || key == 'ro2' || key == 'to' || key == 'to2') return 'operand'
            if (key == 'c') return webcoreCensusConditionListContext(node)
            if (key == 'ts' || key == 'fs') return 'statement'
            if (key == 'd') return 'device-list'
            return null
        case 'restriction':
            if (key == 'lo' || key == 'ro' || key == 'ro2') return 'operand'
            if (key == 'r') return 'restriction'
            if (key == 'd') return 'device-list'
            return null
        case 'event':
            if (key == 'lo') return 'event-operand'
            return null
        case 'operand':
        case 'event-operand':
        case 'param':
            if (key == 'exp') return 'expression'
            if (key == 'i') return 'expression'
            if (key == 'd') return 'device-list'
            return null
        case 'task':
            if (key == 'p') return 'param'
            if (key == 'd') return 'device-list'
            return null
        case 'expression':
            if (key == 'i') return 'expression'
            if (key == 'd') return 'device-list'
            return null
    }
    return null
}



String webcoreCensusConditionListContext(Map node) {
    return (node.o instanceof String && (node.o as String) == 'followed by') ? 'followed-by-step' : 'condition'
}

void webcoreCensusClassify(Map node, String context, String path, Map acc) {
    if (context == null || context == 'opaque') return
    if (context == 'statement') {
        webcoreCensusCandidate(acc, "${path}.t", node.t, 'wc.statement.', 'unknown-statement-type')
        ['tep', 'tsp', 'tcp'].each { String key ->
            if (!node.containsKey(key)) return
            
            
            webcoreCensusCandidate(acc, "${path}.${key}", key, 'wc.policy.', 'unknown-policy-value')
        }
        return
    }
    if (context == 'operand' || context == 'event-operand' || context == 'param') {
        String prefix = (context == 'event-operand') ? 'wc.operand.event-match.' : 'wc.operand.'
        String spelling = (node.t instanceof String) ? (node.t as String) : null
        
        
        Object member = (node.t instanceof String) ? ((spelling == '') ? 'empty' : spelling) : node.t
        if (context == 'param' && !(node.t instanceof String)) {
            
            
            
            
            
            webcoreCensusCandidate(acc, "${path}.t", 'unselected', 'wc.task-parameter.', 'malformed-node')
        } else {
            webcoreCensusCandidate(acc, "${path}.t", member, prefix, 'unknown-operand-type')
        }
        if (context == 'param') webcoreCensusDefaultSite(acc, node.vt, 'wc.task.value-type.')
        if (spelling == 'v') {
            Object name = (node.v instanceof String) ? webcoreCensusFixAttr(node.v as String) : node.v
            webcoreCensusCandidate(acc, "${path}.v", name, 'wc.virtual-device.', 'unknown-operand-type')
        } else if (spelling == 's') {
            
            
            
            
            String presetType = (node.vt instanceof String) ? (node.vt as String) : null
            if (presetType != null && (acc.constructs as Map).containsKey('wc.preset.value-type.' + presetType)) {
                webcoreCensusCandidate(acc, "${path}.s", node.s, 'wc.preset.', 'unknown-operand-type')
            }
            webcoreCensusDefaultSite(acc, node.vt, 'wc.preset.value-type.')
        } else if (spelling == 'c') {
            webcoreCensusDefaultSite(acc, node.vt, 'wc.constant.value-type.')
        }
        return
    }
    if (context == 'expression') {
        String spelling = (node.t instanceof String) ? (node.t as String) : null
        
        
        if (spelling == 'operator') return
        webcoreCensusCandidate(acc, "${path}.t", node.t, 'wc.expression.result-type.', 'unknown-operand-type')
        if (spelling == 'function') webcoreCensusFunction(acc, "${path}.n", node.n)
        return
    }
}

void webcoreCensusCandidate(Map acc, String path, Object member, String prefix, String reason) {
    acc.constructCandidates = (acc.constructCandidates as Integer) + 1
    if (!(member instanceof String)) {
        webcoreCensusRecord(acc, path, 'malformed-node', webcoreCensusNodeKind(member))
        return
    }
    String id = prefix + (member as String)
    if ((acc.constructs as Map).containsKey(id)) {
        webcoreCensusIdentify(acc, id)
        return
    }
    webcoreCensusRecord(acc, path, reason, 'scalar')
}





void webcoreCensusDefaultSite(Map acc, Object member, String prefix) {
    if (member instanceof String) {
        String id = prefix + (member as String)
        if ((acc.constructs as Map).containsKey(id)) {
            acc.constructCandidates = (acc.constructCandidates as Integer) + 1
            webcoreCensusIdentify(acc, id)
            return
        }
    }
    acc.defaultBranchOccurrences = (acc.defaultBranchOccurrences as Integer) + 1
}

void webcoreCensusFunction(Map acc, String path, Object name) {
    acc.constructCandidates = (acc.constructCandidates as Integer) + 1
    if (!(name instanceof String)) {
        webcoreCensusRecord(acc, path, 'malformed-node', webcoreCensusNodeKind(name))
        return
    }
    Object id = (acc.functionIndex as Map)[(name as String).toLowerCase()]
    if (id != null) { webcoreCensusIdentify(acc, "${id}"); return }
    webcoreCensusRecord(acc, path, 'unknown-function', 'scalar')
}




void webcoreCensusDeviceSelector(Map acc, String path, Object entry) {
    acc.constructCandidates = (acc.constructCandidates as Integer) + 1
    if (entry == null || (entry instanceof String && (entry as String).isEmpty())) {
        webcoreCensusIdentify(acc, 'wc.device-selector.empty')
        return
    }
    if (!(entry instanceof String)) {
        webcoreCensusRecord(acc, path, 'malformed-node', webcoreCensusNodeKind(entry))
        return
    }
    if ((entry as String) ==~ /^:[0-9a-f]{32}:$/) {
        webcoreCensusIdentify(acc, 'wc.device-selector.direct-identifier')
        return
    }
    webcoreCensusRecord(acc, path, 'unknown-device-selector', 'scalar')
}

void webcoreCensusIdentify(Map acc, String id) {
    acc.constructsIdentified = (acc.constructsIdentified as Integer) + 1
    Map counts = acc.counts as Map
    counts[id] = ((counts[id] ?: 0) as Integer) + 1
}



void webcoreCensusRecord(Map acc, String path, String reason, String nodeKind) {
    webcoreCensusTaint(acc)
    String key = "${path}|${reason}|${nodeKind}"
    Set seen = acc.seen as Set
    if (seen.contains(key)) return
    seen << key
    if (!(acc.occurrenceStack as List)) acc.outsideStatements = (acc.outsideStatements as Integer) + 1
    List records = acc.unrecognised as List
    if (records.size() >= ((acc.limits as Map).maxUnrecognised as Integer)) {
        acc.overflow = (acc.overflow as Integer) + 1
        return
    }
    records << [path: webcoreCensusBoundPath(path, (acc.limits as Map).maxPathLength as Integer),
                reason: reason, nodeKind: nodeKind]
}





List<String> webcoreCensusStructureCategories() {
    return ['missing-discriminator', 'unknown-variant', 'missing-key', 'missing-unconsumed-key',
            'never-persisted', 'empty-persisted', 'wrong-kind', 'bad-value', 'outside-condition',
            'variant-foreign-key', 'unexpected-key']
}




String webcoreCensusElementContext(String context, Object element, int index) {
    if (context == 'followed-by-step') {
        if (element instanceof List) return null
        return index == 0 ? 'followed-by-first-step' : 'followed-by-later-step'
    }
    if (element instanceof List && context in ['statement', 'elseif', 'case', 'event', 'task', 'restriction',
                                               'condition', 'followed-by-first-step', 'followed-by-later-step']) return null
    return context
}

String webcoreCensusShapeContext(String context) {
    if (context == 'condition') return 'condition-list-member'
    if (context == 'followed-by-first-step' || context == 'followed-by-later-step') return context
    return null
}

boolean webcoreCensusOpenOccurrence(Map node, String context, Map acc) {
    if (acc.shapes == null || context != 'statement' || !(node.t instanceof String)) return false
    String id = 'wc.statement.' + (node.t as String)
    if (!(acc.constructs as Map).containsKey(id) || !(((acc.shapes as Map).statements as Map).containsKey(id))) return false
    (acc.occurrenceStack as List) << [id: id, invalid: false, gaps: [] as Set]
    return true
}

void webcoreCensusCloseOccurrence(Map acc) {
    List stack = acc.occurrenceStack as List
    Map top = stack.remove((int) (stack.size() - 1)) as Map
    Map occurrences = acc.occurrences as Map
    Map entry = (occurrences[top.id] instanceof Map) ? (occurrences[top.id] as Map) :
        [structurallyValid: 0, structurallyInvalid: 0, evidenceGapped: 0, evidenceGaps: [:]]
    String field = (top.invalid == true) ? 'structurallyInvalid' : 'structurallyValid'
    entry[field] = (entry[field] as Integer) + 1
    
    if (top.invalid != true && (top.gaps as Set)) {
        entry.evidenceGapped = (entry.evidenceGapped as Integer) + 1
        Map gapCounts = entry.evidenceGaps as Map
        (top.gaps as Set).each { Object g -> gapCounts[g] = ((gapCounts[g] ?: 0) as Integer) + 1 }
    }
    occurrences[top.id] = entry
}



void webcoreCensusTaint(Map acc) {
    List stack = acc.occurrenceStack as List
    if (stack) (stack[stack.size() - 1] as Map).invalid = true
}

void webcoreCensusMismatch(Map acc, String path, String category) {
    webcoreCensusTaint(acc)
    String key = "${path}|${category}"
    Set seen = acc.structureSeen as Set
    if (seen.contains(key)) return
    seen << key
    List records = acc.structureFindings as List
    if (records.size() >= ((acc.limits as Map).maxStructureFindings as Integer)) {
        acc.structureOverflow = (acc.structureOverflow as Integer) + 1
        return
    }
    records << [path: webcoreCensusBoundPath(path, (acc.limits as Map).maxPathLength as Integer), category: category]
}



Map webcoreCensusShapeKeys(Map node, String context, String path, Map acc) {
    Map shapes = acc.shapes as Map
    Map subs = shapes.substructures as Map
    if (context == 'statement') {
        Object entry = (node.t instanceof String) ? (shapes.statements as Map)['wc.statement.' + (node.t as String)] : null
        return (entry instanceof Map) ? [keys: (shapes.common as Map) + ((entry as Map).keys as Map), foreign: [] as Set] : null
    }
    if (context in ['elseif', 'case', 'event', 'task']) return [keys: (subs[context] as Map).keys as Map, foreign: [] as Set]
    if (webcoreCensusShapeContext(context) == null) return null
    Map condition = subs.condition as Map
    Map discriminator = condition.discriminator as Map
    String dk = discriminator.key as String
    Object variant = (node[dk] instanceof String) ? (condition.variants as Map)[node[dk] as String] : null
    if (!(variant instanceof Map)) {
        webcoreCensusMismatch(acc, path + '.' + dk, node.containsKey(dk) ? 'unknown-variant' : 'missing-discriminator')
        return [keys: [:], foreign: [] as Set, blocked: true]
    }
    Set foreign = [] as Set
    (condition.variants as Map).each { Object name, Object keys -> if (name != node[dk]) foreign.addAll((keys as Map).keySet()) }
    foreign.removeAll((variant as Map).keySet())
    return [keys: (variant as Map) + [(dk): discriminator], foreign: foreign]
}

Map webcoreCensusValidate(Map node, String context, String path, Map acc) {
    if (acc.shapes == null || !(acc.occurrenceStack as List)) return null
    Map shape = webcoreCensusShapeKeys(node, context, path, acc)
    if (shape == null || shape.blocked == true) return shape
    Map keys = shape.keys as Map
    Map lists = (acc.shapes as Map).lists as Map
    String shapeContext = webcoreCensusShapeContext(context)
    keys.each { Object k, Object s ->
        String key = "${k}"
        Map spec = s as Map
        String at = path + '.' + key
        boolean present = node.containsKey(key)
        Object v = node[key]
        if (spec.persisted == 'when' && !webcoreCensusPredicate(spec.persistedWhen as Map, node, shapeContext)) {
            if (!present) return
            if (spec.outsideWhen != 'retained-unconsumed') webcoreCensusMismatch(acc, at, 'outside-condition')
            else if (!webcoreCensusKindOk(spec.kind as String, v)) webcoreCensusMismatch(acc, at, 'wrong-kind')
            else if (spec.values instanceof List && !(spec.values as List).contains(v)) webcoreCensusMismatch(acc, at, 'bad-value')
            return
        }
        if (!present) {
            if (spec.persisted == 'always' || spec.persisted == 'when') {
                boolean unconsumed = spec.consumed == 'read' && spec.consumedWhen instanceof Map &&
                    !webcoreCensusPredicate(spec.consumedWhen as Map, node, shapeContext)
                webcoreCensusMismatch(acc, at, unconsumed ? 'missing-unconsumed-key' : 'missing-key')
            }
            return
        }
        if (spec.persisted == 'never') { webcoreCensusMismatch(acc, at, 'never-persisted'); return }
        boolean empty = v == null || (v instanceof Boolean && !(v as Boolean)) || (v instanceof String && (v as String).isEmpty())
        if (spec.persisted == 'unless-empty' && empty) { webcoreCensusMismatch(acc, at, 'empty-persisted'); return }
        if (!webcoreCensusKindOk(spec.kind as String, v)) { webcoreCensusMismatch(acc, at, 'wrong-kind'); return }
        if (spec.values instanceof List && !(spec.values as List).contains(v)) { webcoreCensusMismatch(acc, at, 'bad-value'); return }
        if (lists.containsKey(spec.kind)) {
            List items = v as List
            for (int i = 0; i < items.size(); i++) {
                if (!(items[i] instanceof Map)) webcoreCensusMismatch(acc, at + '[' + i + ']', 'wrong-kind')
            }
        }
    }
    
    Set foreign = shape.foreign as Set
    Set schemaKeys = acc.schemaKeys as Set
    node.keySet().each { Object k ->
        String key = "${k}"
        if (keys.containsKey(key)) return
        if (foreign.contains(key)) webcoreCensusMismatch(acc, path + '.' + key, 'variant-foreign-key')
        else if (schemaKeys.contains(key)) webcoreCensusMismatch(acc, path + '.' + key, 'unexpected-key')
    }
    webcoreCensusEvidenceGaps(node, context, keys, shapeContext, acc)
    return shape
}




void webcoreCensusEvidenceGaps(Map node, String context, Map keys, String shapeContext, Map acc) {
    Object declared = (acc.shapes as Map).evidenceGaps
    if (!(declared instanceof Map) || !(declared as Map)) return
    Map gaps = declared as Map
    Map statementKeys = [:]
    if (context == 'statement') {
        statementKeys = (((acc.shapes as Map).statements as Map)['wc.statement.' + (node.t as String)] as Map).keys as Map
    }
    List stack = acc.occurrenceStack as List
    Set recorded = (stack[stack.size() - 1] as Map).gaps as Set
    keys.each { Object k, Object s ->
        String key = "${k}"
        Map spec = s as Map
        String structure = (context == 'statement') ? (statementKeys.containsKey(key) ? 'wc.statement.' + (node.t as String) : 'statement') :
            (shapeContext != null ? (node.t as String) : context)
        boolean present = node.containsKey(key)
        List branches = []
        if (spec.persisted in ['unless-empty', 'user-optional', 'round-trip']) branches << (present ? 'present' : 'absent')
        if (present && spec.persisted == 'when') branches << (webcoreCensusPredicate(spec.persistedWhen as Map, node, shapeContext) ? 'present' : 'retained')
        if (present && spec.values instanceof List && spec.values != [true]) branches << ('value:' + node[key])
        if (present && spec.consumedWhen instanceof Map) branches << (webcoreCensusPredicate(spec.consumedWhen as Map, node, shapeContext) ? 'consumed' : 'unconsumed')
        branches.each { Object b ->
            String id = structure + '/' + key + '/' + b
            if (gaps.containsKey(id)) recorded << id
        }
    }
}




String webcoreCensusShapeRoute(Map node, String context, String key, Object child, String childContext, Map shape) {
    if (shape.blocked == true) return null
    Object s = (shape.keys as Map)[key]
    if (!(s instanceof Map)) return null
    Map spec = s as Map
    String shapeContext = webcoreCensusShapeContext(context)
    if (!webcoreCensusKindOk(spec.kind as String, child)) return null
    if (spec.persisted == 'when' && !webcoreCensusPredicate(spec.persistedWhen as Map, node, shapeContext)) return null
    if (spec.consumed != 'read') return null
    if (spec.consumedWhen instanceof Map && !webcoreCensusPredicate(spec.consumedWhen as Map, node, shapeContext)) return null
    return childContext
}

boolean webcoreCensusPredicate(Map predicate, Map node, String shapeContext) {
    if (predicate == null) return false
    if (predicate.all instanceof List) return (predicate.all as List).every { webcoreCensusPredicate(it as Map, node, shapeContext) }
    if (predicate.context instanceof List) return (predicate.context as List).contains(shapeContext)
    Object v = node
    for (String segment : (predicate.key as String).tokenize('.')) v = (v instanceof Map) ? (v as Map)[segment] : null
    if (predicate.oneOf instanceof List) return (predicate.oneOf as List).contains(v)
    if (predicate.noneOf instanceof List) return !(predicate.noneOf as List).contains(v)
    return false
}

boolean webcoreCensusIsScalar(Object v) {
    return v instanceof String || v instanceof Number || v instanceof Boolean
}

boolean webcoreCensusKindOk(String kind, Object v) {
    switch (kind) {
        case 'scalar': return webcoreCensusIsScalar(v)
        case 'scalar-list': return v instanceof List && (v as List).every { webcoreCensusIsScalar(it) }
        case 'operand': return v instanceof Map
        case 'operand-list': return v instanceof List && (v as List).every { it instanceof Map }
    }
    return v instanceof List
}



String webcoreCensusAchievedLevel(String ceiling, Object occurrence, Object count) {
    if (!(ceiling in ['L3', 'L4', 'L5'])) return ceiling
    if (!(occurrence instanceof Map) || !(count instanceof Number)) return 'L2'
    Object valid = (occurrence as Map).structurallyValid
    Object invalid = (occurrence as Map).structurallyInvalid
    Object gapped = (occurrence as Map).evidenceGapped
    if (!(valid instanceof Number) || !(invalid instanceof Number) || !(gapped instanceof Number)) return 'L2'
    return ((invalid as Integer) == 0 && (gapped as Integer) == 0 && (valid as Integer) == (count as Integer)) ? ceiling : 'L2'
}







Map webcoreCensusRegistry() {
    return [provenance: [commit: '0a37eee2537accd706aaaeeed5a7b4bb0c82646e', registryVersion: '1'],
            constructs: [
        'wc.constant.value-type.date': [level: 'L2'],
        'wc.constant.value-type.datetime': [level: 'L2'],
        'wc.constant.value-type.time': [level: 'L2'],
        'wc.device-selector.direct-identifier': [level: 'L2'],
        'wc.device-selector.empty': [level: 'L2'],
        'wc.device-selector.variable-device-list': [level: 'L2'],
        'wc.device-selector.variable-device-map': [level: 'L2'],
        'wc.device-selector.variable-name-cast': [level: 'L2'],
        'wc.expression.item.decimal': [level: 'L2'],
        'wc.expression.item.double': [level: 'L2'],
        'wc.expression.item.float': [level: 'L2'],
        'wc.expression.item.integer': [level: 'L2'],
        'wc.expression.item.number': [level: 'L2'],
        'wc.expression.result-type.bool': [level: 'L2'],
        'wc.expression.result-type.boolean': [level: 'L2'],
        'wc.expression.result-type.date': [level: 'L2'],
        'wc.expression.result-type.datetime': [level: 'L2'],
        'wc.expression.result-type.decimal': [level: 'L2'],
        'wc.expression.result-type.device': [level: 'L2'],
        'wc.expression.result-type.double': [level: 'L2'],
        'wc.expression.result-type.duration': [level: 'L2'],
        'wc.expression.result-type.dynamic': [level: 'L2'],
        'wc.expression.result-type.enum': [level: 'L2'],
        'wc.expression.result-type.error': [level: 'L2'],
        'wc.expression.result-type.expression': [level: 'L2'],
        'wc.expression.result-type.float': [level: 'L2'],
        'wc.expression.result-type.function': [level: 'L2'],
        'wc.expression.result-type.int32': [level: 'L2'],
        'wc.expression.result-type.int64': [level: 'L2'],
        'wc.expression.result-type.integer': [level: 'L2'],
        'wc.expression.result-type.long': [level: 'L2'],
        'wc.expression.result-type.number': [level: 'L2'],
        'wc.expression.result-type.operand': [level: 'L2'],
        'wc.expression.result-type.phone': [level: 'L2'],
        'wc.expression.result-type.string': [level: 'L2'],
        'wc.expression.result-type.text': [level: 'L2'],
        'wc.expression.result-type.time': [level: 'L2'],
        'wc.expression.result-type.uri': [level: 'L2'],
        'wc.expression.result-type.variable': [level: 'L2'],
        'wc.function.abs': [level: 'L2'],
        'wc.function.adddays': [level: 'L2'],
        'wc.function.addhours': [level: 'L2'],
        'wc.function.addminutes': [level: 'L2'],
        'wc.function.addseconds': [level: 'L2'],
        'wc.function.addweeks': [level: 'L2'],
        'wc.function.age': [level: 'L2'],
        'wc.function.arrayitem': [level: 'L2'],
        'wc.function.asin': [level: 'L2'],
        'wc.function.atan2': [level: 'L2'],
        'wc.function.avg': [level: 'L2'],
        'wc.function.bool': [level: 'L2'],
        'wc.function.boolean': [level: 'L2'],
        'wc.function.ceil': [level: 'L2'],
        'wc.function.ceiling': [level: 'L2'],
        'wc.function.celsius': [level: 'L2'],
        'wc.function.coalesce': [level: 'L2'],
        'wc.function.concat': [level: 'L2'],
        'wc.function.contains': [level: 'L2'],
        'wc.function.converttemperatureifneeded': [level: 'L2'],
        'wc.function.cos': [level: 'L2'],
        'wc.function.count': [level: 'L2'],
        'wc.function.date': [level: 'L2'],
        'wc.function.dateAdd': [level: 'L2'],
        'wc.function.datetime': [level: 'L2'],
        'wc.function.decimal': [level: 'L2'],
        'wc.function.dewpoint': [level: 'L2'],
        'wc.function.distance': [level: 'L2'],
        'wc.function.encodeuricomponent': [level: 'L2'],
        'wc.function.endswith': [level: 'L2'],
        'wc.function.eq': [level: 'L2'],
        'wc.function.exists': [level: 'L2'],
        'wc.function.fahrenheit': [level: 'L2'],
        'wc.function.float': [level: 'L2'],
        'wc.function.floor': [level: 'L2'],
        'wc.function.format': [level: 'L2'],
        'wc.function.formatdatetime': [level: 'L2'],
        'wc.function.formatduration': [level: 'L2'],
        'wc.function.ge': [level: 'L2'],
        'wc.function.gt': [level: 'L2'],
        'wc.function.hsltohex': [level: 'L2'],
        'wc.function.if': [level: 'L2'],
        'wc.function.indexof': [level: 'L2'],
        'wc.function.int': [level: 'L2'],
        'wc.function.integer': [level: 'L2'],
        'wc.function.isbetween': [level: 'L2'],
        'wc.function.isempty': [level: 'L2'],
        'wc.function.ispistonpaused': [level: 'L2'],
        'wc.function.json': [level: 'L2'],
        'wc.function.lastindexof': [level: 'L2'],
        'wc.function.le': [level: 'L2'],
        'wc.function.least': [level: 'L2'],
        'wc.function.left': [level: 'L2'],
        'wc.function.length': [level: 'L2'],
        'wc.function.log': [level: 'L2'],
        'wc.function.lower': [level: 'L2'],
        'wc.function.lt': [level: 'L2'],
        'wc.function.ltrim': [level: 'L2'],
        'wc.function.matches': [level: 'L2'],
        'wc.function.max': [level: 'L2'],
        'wc.function.median': [level: 'L2'],
        'wc.function.mid': [level: 'L2'],
        'wc.function.min': [level: 'L2'],
        'wc.function.monthname': [level: 'L2'],
        'wc.function.most': [level: 'L2'],
        'wc.function.newer': [level: 'L2'],
        'wc.function.not': [level: 'L2'],
        'wc.function.number': [level: 'L2'],
        'wc.function.older': [level: 'L2'],
        'wc.function.parsedatetime': [level: 'L2'],
        'wc.function.pow': [level: 'L2'],
        'wc.function.power': [level: 'L2'],
        'wc.function.previousage': [level: 'L2'],
        'wc.function.previousvalue': [level: 'L2'],
        'wc.function.rainbowvalue': [level: 'L2'],
        'wc.function.random': [level: 'L2'],
        'wc.function.rangevalue': [level: 'L2'],
        'wc.function.replace': [level: 'L2'],
        'wc.function.right': [level: 'L2'],
        'wc.function.round': [level: 'L2'],
        'wc.function.roundtimetominutes': [level: 'L2'],
        'wc.function.rtrim': [level: 'L2'],
        'wc.function.settzid': [level: 'L2'],
        'wc.function.setvariable': [level: 'L2'],
        'wc.function.sin': [level: 'L2'],
        'wc.function.size': [level: 'L2'],
        'wc.function.sort': [level: 'L2'],
        'wc.function.sprintf': [level: 'L2'],
        'wc.function.sqr': [level: 'L2'],
        'wc.function.sqrt': [level: 'L2'],
        'wc.function.startswith': [level: 'L2'],
        'wc.function.stdev': [level: 'L2'],
        'wc.function.string': [level: 'L2'],
        'wc.function.strlen': [level: 'L2'],
        'wc.function.substr': [level: 'L2'],
        'wc.function.substring': [level: 'L2'],
        'wc.function.sum': [level: 'L2'],
        'wc.function.tan': [level: 'L2'],
        'wc.function.text': [level: 'L2'],
        'wc.function.time': [level: 'L2'],
        'wc.function.title': [level: 'L2'],
        'wc.function.todegrees': [level: 'L2'],
        'wc.function.toradians': [level: 'L2'],
        'wc.function.trim': [level: 'L2'],
        'wc.function.trimleft': [level: 'L2'],
        'wc.function.trimright': [level: 'L2'],
        'wc.function.upper': [level: 'L2'],
        'wc.function.urlencode': [level: 'L2'],
        'wc.function.variance': [level: 'L2'],
        'wc.function.weekdayname': [level: 'L2'],
        'wc.operand.c': [level: 'L3'],
        'wc.operand.d': [level: 'L3'],
        'wc.operand.e': [level: 'L3'],
        'wc.operand.empty': [level: 'L2'],
        'wc.operand.event-match.p': [level: 'L3'],
        'wc.operand.event-match.v': [level: 'L3'],
        'wc.operand.event-match.x': [level: 'L3'],
        'wc.operand.p': [level: 'L3'],
        'wc.operand.s': [level: 'L3'],
        'wc.operand.u': [level: 'L3'],
        'wc.operand.v': [level: 'L3'],
        'wc.operand.x': [level: 'L3'],
        'wc.policy.tcp': [level: 'L2'],
        'wc.policy.tep': [level: 'L2'],
        'wc.policy.tsp': [level: 'L2'],
        'wc.preset.midnight': [level: 'L2'],
        'wc.preset.noon': [level: 'L2'],
        'wc.preset.sunrise': [level: 'L2'],
        'wc.preset.sunset': [level: 'L2'],
        'wc.preset.value-type.datetime': [level: 'L2'],
        'wc.preset.value-type.time': [level: 'L2'],
        'wc.statement.action': [level: 'L3'],
        'wc.statement.break': [level: 'L3'],
        'wc.statement.do': [level: 'L3'],
        'wc.statement.each': [level: 'L3'],
        'wc.statement.every': [level: 'L3'],
        'wc.statement.exit': [level: 'L3'],
        'wc.statement.for': [level: 'L3'],
        'wc.statement.if': [level: 'L3'],
        'wc.statement.on': [level: 'L3'],
        'wc.statement.repeat': [level: 'L3'],
        'wc.statement.switch': [level: 'L3'],
        'wc.statement.while': [level: 'L3'],
        'wc.task-parameter.unselected': [level: 'L2'],
        'wc.task.value-type.variable': [level: 'L2'],
        'wc.vcmd.adjustColorTemperature': [level: 'L2'],
        'wc.vcmd.adjustHue': [level: 'L2'],
        'wc.vcmd.adjustInfraredLevel': [level: 'L2'],
        'wc.vcmd.adjustLevel': [level: 'L2'],
        'wc.vcmd.adjustSaturation': [level: 'L2'],
        'wc.vcmd.appendFile': [level: 'L2'],
        'wc.vcmd.cancelTasks': [level: 'L2'],
        'wc.vcmd.clearFuelStream': [level: 'L2'],
        'wc.vcmd.clearTile': [level: 'L2'],
        'wc.vcmd.deleteFile': [level: 'L2'],
        'wc.vcmd.emulatedFlash': [level: 'L2'],
        'wc.vcmd.executePiston': [level: 'L2'],
        'wc.vcmd.executeRoutine': [level: 'L2'],
        'wc.vcmd.executeRule': [level: 'L2'],
        'wc.vcmd.fadeColorTemperature': [level: 'L2'],
        'wc.vcmd.fadeHue': [level: 'L2'],
        'wc.vcmd.fadeInfraredLevel': [level: 'L2'],
        'wc.vcmd.fadeLevel': [level: 'L2'],
        'wc.vcmd.fadeSaturation': [level: 'L2'],
        'wc.vcmd.flash': [level: 'L2'],
        'wc.vcmd.flashColor': [level: 'L2'],
        'wc.vcmd.flashLevel': [level: 'L2'],
        'wc.vcmd.httpRequest': [level: 'L2'],
        'wc.vcmd.iftttMaker': [level: 'L2'],
        'wc.vcmd.internal_fade': [level: 'L2'],
        'wc.vcmd.lifxBreathe': [level: 'L2'],
        'wc.vcmd.lifxPulse': [level: 'L2'],
        'wc.vcmd.lifxScene': [level: 'L2'],
        'wc.vcmd.lifxState': [level: 'L2'],
        'wc.vcmd.lifxToggle': [level: 'L2'],
        'wc.vcmd.loadStateGlobally': [level: 'L2'],
        'wc.vcmd.loadStateLocally': [level: 'L2'],
        'wc.vcmd.log': [level: 'L2'],
        'wc.vcmd.noop': [level: 'L2'],
        'wc.vcmd.parseJson': [level: 'L2'],
        'wc.vcmd.pausePiston': [level: 'L2'],
        'wc.vcmd.readFile': [level: 'L2'],
        'wc.vcmd.readFuelStream': [level: 'L2'],
        'wc.vcmd.resumePiston': [level: 'L2'],
        'wc.vcmd.saveStateGlobally': [level: 'L2'],
        'wc.vcmd.saveStateLocally': [level: 'L2'],
        'wc.vcmd.sendEmail': [level: 'L2'],
        'wc.vcmd.sendNotification': [level: 'L2'],
        'wc.vcmd.sendNotificationToContacts': [level: 'L2'],
        'wc.vcmd.sendPushNotification': [level: 'L2'],
        'wc.vcmd.sendSMSNotification': [level: 'L2'],
        'wc.vcmd.setAlarmSystemStatus': [level: 'L2'],
        'wc.vcmd.setHSLColor': [level: 'L2'],
        'wc.vcmd.setLocationMode': [level: 'L2'],
        'wc.vcmd.setState': [level: 'L2'],
        'wc.vcmd.setSwitch': [level: 'L2'],
        'wc.vcmd.setTile': [level: 'L2'],
        'wc.vcmd.setTileColor': [level: 'L2'],
        'wc.vcmd.setTileFooter': [level: 'L2'],
        'wc.vcmd.setTileOTitle': [level: 'L2'],
        'wc.vcmd.setTileText': [level: 'L2'],
        'wc.vcmd.setTileTitle': [level: 'L2'],
        'wc.vcmd.setVariable': [level: 'L2'],
        'wc.vcmd.storeMedia': [level: 'L2'],
        'wc.vcmd.toggle': [level: 'L2'],
        'wc.vcmd.toggleLevel': [level: 'L2'],
        'wc.vcmd.toggleRandom': [level: 'L2'],
        'wc.vcmd.wait': [level: 'L2'],
        'wc.vcmd.waitForDateTime': [level: 'L2'],
        'wc.vcmd.waitForTime': [level: 'L2'],
        'wc.vcmd.waitRandom': [level: 'L2'],
        'wc.vcmd.wolRequest': [level: 'L2'],
        'wc.vcmd.writeFile': [level: 'L2'],
        'wc.vcmd.writeFuelStream': [level: 'L2'],
        'wc.vcmd.writeToFuelStream': [level: 'L2'],
        'wc.virtual-device.cloudBackup': [level: 'L2'],
        'wc.virtual-device.date': [level: 'L2'],
        'wc.virtual-device.datetime': [level: 'L2'],
        'wc.virtual-device.email': [level: 'L2'],
        'wc.virtual-device.hsmAlert': [level: 'L2'],
        'wc.virtual-device.hsmRule': [level: 'L2'],
        'wc.virtual-device.hsmRules': [level: 'L2'],
        'wc.virtual-device.hsmSetArm': [level: 'L2'],
        'wc.virtual-device.hsmStatus': [level: 'L2'],
        'wc.virtual-device.ifttt': [level: 'L2'],
        'wc.virtual-device.lowMemory': [level: 'L2'],
        'wc.virtual-device.manualReboot': [level: 'L2'],
        'wc.virtual-device.mode': [level: 'L2'],
        'wc.virtual-device.pistonResume': [level: 'L2'],
        'wc.virtual-device.powerSource': [level: 'L2'],
        'wc.virtual-device.routine': [level: 'L2'],
        'wc.virtual-device.severeLoad': [level: 'L2'],
        'wc.virtual-device.sunriseTime': [level: 'L2'],
        'wc.virtual-device.sunsetTime': [level: 'L2'],
        'wc.virtual-device.systemStart': [level: 'L2'],
        'wc.virtual-device.tile': [level: 'L2'],
        'wc.virtual-device.time': [level: 'L2'],
        'wc.virtual-device.update': [level: 'L2'],
        'wc.virtual-device.zigbeeOff': [level: 'L2'],
        'wc.virtual-device.zigbeeOn': [level: 'L2'],
        'wc.virtual-device.zwaveCrashed': [level: 'L2'],
    ]]
}






Map webcoreStatementShapes() {
    return [
        contexts: ['condition-list-member', 'followed-by-first-step', 'followed-by-later-step'],
        lists: [
            'condition-list': ['element': 'condition', 'ownerKey': 'o', 'ownerOneOf': ['followed by'], 'first': 'followed-by-first-step', 'rest': 'followed-by-later-step', 'otherwise': 'condition-list-member'],
            'event-list': ['element': 'event'],
            'elseif-list': ['element': 'elseif'],
            'case-list': ['element': 'case'],
            'task-list': ['element': 'task']
        ],
        common: [
            't': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
            '$': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load'],
            'a': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read', 'values': ['0', '1']],
            'tep': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read', 'values': ['c', 'p', 'b']],
            'tsp': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read', 'values': ['a']],
            'tcp': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited', 'values': ['c', 'p', 'b']],
            'r': ['kind': 'restriction-list', 'persisted': 'always', 'consumed': 'read'],
            'rop': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
            'rn': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read', 'values': [true]],
            'di': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited', 'values': [true]],
            'z': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited'],
            'sm': ['kind': 'scalar', 'persisted': 'user-optional', 'consumed': 'not-cited']
        ],
        statements: [
            'wc.statement.action': [keys: [
                'd': ['kind': 'device-list', 'persisted': 'always', 'consumed': 'read'],
                'k': ['kind': 'task-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.if': [keys: [
                'o': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
                'n': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read', 'values': [true]],
                'c': ['kind': 'condition-list', 'persisted': 'always', 'consumed': 'read'],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read'],
                'ei': ['kind': 'elseif-list', 'persisted': 'always', 'consumed': 'read'],
                'e': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.while': [keys: [
                'o': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
                'n': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read', 'values': [true]],
                'c': ['kind': 'condition-list', 'persisted': 'always', 'consumed': 'read'],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.repeat': [keys: [
                'o': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
                'n': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read', 'values': [true]],
                'c': ['kind': 'condition-list', 'persisted': 'always', 'consumed': 'read'],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.every': [keys: [
                'lo': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                'lo2': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read', 'consumedWhen': ['key': 'lo.vt', 'oneOf': ['d', 'w', 'n', 'y']]],
                'lo3': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read', 'consumedWhen': ['key': 'lo.vt', 'oneOf': ['d', 'w', 'n', 'y']]],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.on': [keys: [
                'c': ['kind': 'event-list', 'persisted': 'always', 'consumed': 'read'],
                'o': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'not-cited', 'values': ['or']],
                'n': ['kind': 'scalar', 'persisted': 'never', 'consumed': 'not-cited'],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.each': [keys: [
                'x': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read'],
                'lo': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.for': [keys: [
                'x': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read'],
                'lo': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                'lo2': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                'lo3': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.switch': [keys: [
                'lo': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                'cs': ['kind': 'case-list', 'persisted': 'always', 'consumed': 'read'],
                'e': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read'],
                'ctp': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read', 'values': ['i', 'e']],
                'ct': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load', 'values': ['c']],
                's': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load', 'values': [true]]
            ]],
            'wc.statement.do': [keys: [
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'wc.statement.break': [keys: [:]],
            'wc.statement.exit': [keys: [
                'lo': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read']
            ]]
        ],
        substructures: [
            'elseif': [keys: [
                '$': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load'],
                'o': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
                'n': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read', 'values': [true]],
                'c': ['kind': 'condition-list', 'persisted': 'always', 'consumed': 'read'],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read']
            ]],
            'case': [keys: [
                '$': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load'],
                't': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read', 'values': ['s', 'r']],
                'ro': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                'ro2': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read', 'consumedWhen': ['key': 't', 'oneOf': ['r']]],
                's': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read'],
                'z': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited']
            ]],
            'event': [keys: [
                '$': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load'],
                't': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read', 'values': ['event']],
                'lo': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                'sm': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'not-cited'],
                'z': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited'],
                'ct': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'read', 'values': ['t']],
                's': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load', 'values': [true]]
            ]],
            'task': [keys: [
                '$': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load'],
                'c': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
                'cm': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited', 'values': [true]],
                'p': ['kind': 'operand-list', 'persisted': 'always', 'consumed': 'read'],
                'a': ['kind': 'scalar', 'persisted': 'user-optional', 'consumed': 'not-cited'],
                'm': ['kind': 'scalar-list', 'persisted': 'user-optional', 'consumed': 'read'],
                'z': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited']
            ]],
            'condition': [
                discriminator: ['key': 't', 'kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
                variants: [
                    'condition': [
                        '$': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load'],
                        'lo': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                        'co': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
                        'ro': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                        'ro2': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                        'to': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                        'to2': ['kind': 'operand', 'persisted': 'always', 'consumed': 'read'],
                        'ts': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read'],
                        'fs': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read'],
                        'sm': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'not-cited'],
                        'z': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited'],
                        'ct': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load', 'values': ['t', 'c']],
                        's': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load', 'values': [true]],
                        'wd': ['kind': 'operand', 'persisted': 'when', 'persistedWhen': ['context': ['followed-by-later-step']], 'outsideWhen': 'retained-unconsumed', 'consumed': 'read', 'consumedWhen': ['context': ['followed-by-later-step']]],
                        'wt': ['kind': 'scalar', 'persisted': 'when', 'persistedWhen': ['context': ['followed-by-later-step']], 'outsideWhen': 'retained-unconsumed', 'consumed': 'read', 'consumedWhen': ['context': ['followed-by-first-step', 'followed-by-later-step']], 'values': ['l', 's', 'n']]
                    ],
                    'group': [
                        '$': ['kind': 'scalar', 'persisted': 'round-trip', 'consumed': 'replaced-on-load'],
                        'c': ['kind': 'condition-list', 'persisted': 'always', 'consumed': 'read'],
                        'o': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'read'],
                        'n': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'read', 'values': [true]],
                        'ts': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read'],
                        'fs': ['kind': 'statement-list', 'persisted': 'always', 'consumed': 'read'],
                        'sm': ['kind': 'scalar', 'persisted': 'always', 'consumed': 'not-cited'],
                        'z': ['kind': 'scalar', 'persisted': 'unless-empty', 'consumed': 'not-cited'],
                        'wd': ['kind': 'operand', 'persisted': 'when', 'persistedWhen': ['context': ['followed-by-later-step']], 'outsideWhen': 'retained-unconsumed', 'consumed': 'read', 'consumedWhen': ['context': ['followed-by-later-step']]],
                        'wt': ['kind': 'scalar', 'persisted': 'when', 'persistedWhen': ['context': ['followed-by-later-step']], 'outsideWhen': 'retained-unconsumed', 'consumed': 'read', 'consumedWhen': ['context': ['followed-by-first-step', 'followed-by-later-step']], 'values': ['l', 's', 'n']]
                    ]
                ]
            ]
        ],
        evidenceGaps: [
            'statement/$/absent': 'editor-authored-only',
            'statement/tcp/absent': 'not-in-matrix',
            'statement/sm/present': 'observed-at-capture',
            'wc.statement.for/x/absent': 'not-in-matrix',
            'elseif/$/absent': 'editor-authored-only',
            'case/$/absent': 'editor-authored-only',
            'event/$/absent': 'editor-authored-only',
            'event/ct/absent': 'editor-authored-only',
            'event/s/absent': 'editor-authored-only',
            'task/$/absent': 'editor-authored-only',
            'task/cm/present': 'needs-physical-device',
            'task/a/present': 'observed-at-capture',
            'condition/$/absent': 'editor-authored-only',
            'condition/ct/absent': 'editor-authored-only',
            'group/$/absent': 'editor-authored-only',
            'group/wd/retained': 'not-in-matrix',
            'group/wd/unconsumed': 'not-in-matrix',
            'group/wt/retained': 'not-in-matrix',
            'group/wt/value:l': 'not-in-matrix',
            'group/wt/value:n': 'not-in-matrix',
            'group/wt/unconsumed': 'not-in-matrix'
        ]
    ]
}






Map webcoreSemanticEvidence() {
    return [
        claims: [
            'statement.if.branch-order.v1': true,
            'condition.list.negation.v1': true,
            'condition.list.operator-or.v1': true,
            'condition.followed-by.opaque-group.v1': true,
            'statement.do.sequential-block.v1': true,
            'statement.envelope.default.v1': true,
            'statement.switch.ordered-cases.v1': true,
            'statement.switch.default.v1': true,
            'statement.break.switch-scope.v1': true,
            'statement.exit.terminate-piston.v1': true,
            'statement.while.pre-condition-loop.v1': true,
            'statement.repeat.post-condition-loop.v1': true,
            'statement.for.step-iteration.v1': true,
            'statement.each.device-iteration.v1': true,
            'statement.break.loop-scope.v1': true,
            'statement.on.any-event-match.v1': true,
            'statement.every.own-timer-only.v1': true,
            'statement.tep.execution-policy.v1': true,
            'statement.tsp.scheduling-policy.v1': true,
            'statement.tcp.cancellation-policy.v1': true,
            'statement.action.device-list.v1': true,
            'statement.action.task-order.v1': true
        ],
        gaps: [
            'statement.envelope.restrictions-present': 'Restrictions gate this statement and their meaning is not yet proven',
            'statement.envelope.async': 'The execution method is not the proven synchronous default',
            'statement.envelope.tep-present': 'A task execution policy is set and its meaning is not yet proven',
            'statement.envelope.tsp-present': 'A task scheduling policy is set and its meaning is not yet proven',
            'statement.envelope.tcp-non-default': 'The task cancellation policy is not the proven default',
            'statement.if.automatic-piston-state-unresolved': 'A top-level if may set the automatic piston state, which is not yet explained',
            'statement.if.fast-forward-resumption-unresolved': 'Resumed execution may enter a branch regardless of the condition, which is not yet explained',
            'statement.action.task-order-unresolved': 'Task order is not yet proven; this action has one task, or fewer, so no capture exercises order',
            'statement.action.fast-forward-unresolved': 'Resumed execution may behave differently from a normal run, which is not yet explained',
            'statement.action.device-list.dynamic-unresolved': 'A dynamic ($currentEventDevice) device target is not yet explained further than being dynamic',
            'statement.unrecognised': 'The statement type is not recognised',
            'condition.leaf-opaque': 'A condition comparison is shown as opaque until its meaning is proven',
            'condition.operator-unproven': 'This condition operator is not yet proven',
            'condition.group-depth-unproven': 'A group inside a group is not yet proven',
            'condition.followed-by-timing-unproven': 'The timing of a followed-by sequence is not yet explained',
            'statement.switch.fast-forward-unresolved': 'Resumed execution may behave differently from a normal run, which is not yet explained',
            'statement.break.fast-forward-unresolved': 'Resumed execution may behave differently from a normal run, which is not yet explained',
            'statement.break.container-unresolved': 'This break statement is not directly inside a switch case or default, so its scope is not yet proven',
            'statement.exit.fast-forward-unresolved': 'Resumed execution may behave differently from a normal run, which is not yet explained',
            'statement.while.fast-forward-unresolved': 'Resumed execution may behave differently from a normal run, which is not yet explained',
            'statement.repeat.fast-forward-unresolved': 'Resumed execution may behave differently from a normal run, which is not yet explained',
            'statement.for.fast-forward-unresolved': 'Resumed execution may behave differently from a normal run, which is not yet explained',
            'statement.each.fast-forward-unresolved': 'Resumed execution may behave differently from a normal run, which is not yet explained',
            'statement.on.fast-forward-unresolved': 'Resumed execution may behave differently from a normal run, which is not yet explained',
            'statement.every.fast-forward-unresolved': 'Resumed execution may behave differently from a normal run, which is not yet explained',
            'claim.statement.if.branch-order.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.condition.list.negation.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.condition.list.operator-or.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.condition.followed-by.opaque-group.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.do.sequential-block.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.envelope.default.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.switch.ordered-cases.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.switch.default.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.break.switch-scope.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.exit.terminate-piston.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.while.pre-condition-loop.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.repeat.post-condition-loop.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.for.step-iteration.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.each.device-iteration.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.break.loop-scope.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.on.any-event-match.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.every.own-timer-only.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.tep.execution-policy.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.tsp.scheduling-policy.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.tcp.cancellation-policy.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.action.device-list.v1.not-promoted': 'This claim lost its evidence, for example after source drift',
            'claim.statement.action.task-order.v1.not-promoted': 'This claim lost its evidence, for example after source drift'
        ]
    ]
}







Map webcoreSemanticModel(Object document, Map evidence, int maxDepth) {
    Map acc = [occurrences: [:], truncated: false, maxDepth: maxDepth, evidence: evidence]
    if (document instanceof Map && (document as Map).s instanceof List) {
        webcoreSemanticStatements((document as Map).s as List, '$.s', 0, acc)
    }
    return [occurrences: acc.occurrences, truncated: acc.truncated]
}

void webcoreSemanticStatements(List list, String path, int depth, Map acc, String container = 'block') {
    for (int i = 0; i < list.size(); i++) {
        if (list[i] instanceof Map) webcoreSemanticStatement(list[i] as Map, path + '[' + i + ']', depth + 1, acc, container)
    }
}





void webcoreSemanticStatement(Map node, String path, int depth, Map acc, String container = 'block') {
    if (depth > (acc.maxDepth as Integer)) { acc.truncated = true; return }
    String type = (node.t instanceof String) ? (node.t as String) : "${''}"
    Set claims = [] as Set
    Set gaps = [] as Set
    Map gapReasons = (((acc.evidence as Map).gaps ?: [:]) as Map)
    boolean known = type in ['if', 'do', 'switch', 'break', 'exit', 'while', 'repeat', 'for', 'each', 'on', 'every', 'action'] ||
        gapReasons.containsKey('statement.' + type + '.not-in-increment')
    Map entry = [construct: known ? 'wc.statement.' + type : 'wc.statement.unrecognised', role: null]
    
    boolean envelope = true
    if (node.r instanceof List && (node.r as List)) { gaps << 'statement.envelope.restrictions-present'; envelope = false }
    if (node.a != '0') { gaps << 'statement.envelope.async'; envelope = false }
    if (node.containsKey('tep')) { gaps << 'statement.envelope.tep-present'; envelope = false }
    if (node.containsKey('tsp')) { gaps << 'statement.envelope.tsp-present'; envelope = false }
    if (node.tcp != 'c') { gaps << 'statement.envelope.tcp-non-default'; envelope = false }
    if (envelope) claims << 'statement.envelope.default.v1'
    
    
    
    if (node.containsKey('tep')) claims << 'statement.tep.execution-policy.v1'
    if (node.containsKey('tsp')) claims << 'statement.tsp.scheduling-policy.v1'
    if (node.tcp != null && node.tcp != 'c') claims << 'statement.tcp.cancellation-policy.v1'
    if (type == 'if') {
        entry.role = 'decision'
        claims << 'statement.if.branch-order.v1'
        gaps << 'statement.if.automatic-piston-state-unresolved'
        gaps << 'statement.if.fast-forward-resumption-unresolved'
        List branches = [[name: 'then', condition: webcoreSemanticConditionList(node, path, depth, acc, claims, gaps)]]
        List elseIfs = (node.ei instanceof List) ? (node.ei as List) : []
        for (int i = 0; i < elseIfs.size(); i++) {
            if (!(elseIfs[i] instanceof Map)) continue
            branches << [name: 'else-if[' + i + ']', condition: webcoreSemanticConditionList(elseIfs[i] as Map, path + '.ei[' + i + ']', depth, acc, claims, gaps)]
        }
        if (node.e instanceof List && (node.e as List)) branches << [name: 'else']
        entry.branches = branches
    } else if (type == 'do') {
        entry.role = 'sequential-block'
        claims << 'statement.do.sequential-block.v1'
        entry.children = (node.s instanceof List) ? (node.s as List).count { it instanceof Map } : 0
        entry.lowersStatementLevel = true
    } else if (type == 'switch') {
        entry.role = 'multi-way-decision'
        
        entry.ctp = (node.ctp == 'e') ? 'e' : 'i'
        claims << 'statement.switch.ordered-cases.v1'
        if (node.e instanceof List && (node.e as List)) claims << 'statement.switch.default.v1'
        gaps << 'statement.switch.fast-forward-unresolved'
    } else if (type == 'break') {
        if (container == 'switch-case') {
            entry.role = 'switch-scoped-control-transfer'
            claims << 'statement.break.switch-scope.v1'
            gaps << 'statement.break.fast-forward-unresolved'
        } else if (container == 'loop-body') {
            entry.role = 'loop-scoped-control-transfer'
            claims << 'statement.break.loop-scope.v1'
            gaps << 'statement.break.fast-forward-unresolved'
        } else {
            gaps << 'statement.break.container-unresolved'
        }
    } else if (type == 'exit') {
        entry.role = 'piston-terminate'
        claims << 'statement.exit.terminate-piston.v1'
        gaps << 'statement.exit.fast-forward-unresolved'
    } else if (type == 'while') {
        entry.role = 'pre-condition-loop'
        claims << 'statement.while.pre-condition-loop.v1'
        gaps << 'statement.while.fast-forward-unresolved'
        entry.condition = webcoreSemanticConditionList(node, path, depth, acc, claims, gaps)
    } else if (type == 'repeat') {
        entry.role = 'post-condition-loop'
        claims << 'statement.repeat.post-condition-loop.v1'
        gaps << 'statement.repeat.fast-forward-unresolved'
        entry.condition = webcoreSemanticConditionList(node, path, depth, acc, claims, gaps)
    } else if (type == 'for') {
        entry.role = 'step-iteration'
        claims << 'statement.for.step-iteration.v1'
        gaps << 'statement.for.fast-forward-unresolved'
    } else if (type == 'each') {
        entry.role = 'device-iteration'
        claims << 'statement.each.device-iteration.v1'
        gaps << 'statement.each.fast-forward-unresolved'
    } else if (type == 'on') {
        entry.role = 'any-event-match'
        claims << 'statement.on.any-event-match.v1'
        gaps << 'statement.on.fast-forward-unresolved'
    } else if (type == 'every') {
        entry.role = 'own-timer-only'
        claims << 'statement.every.own-timer-only.v1'
        gaps << 'statement.every.fast-forward-unresolved'
    } else if (type == 'action') {
        List taskList = (node.k instanceof List) ? (node.k as List) : []
        if (taskList.size() > 1) {
            entry.role = 'ordered-tasks'
            claims << 'statement.action.task-order.v1'
            entry.taskCount = taskList.size()
            gaps << 'statement.action.fast-forward-unresolved'
        } else {
            gaps << 'statement.action.task-order-unresolved'
        }
        List deviceList = (node.d instanceof List) ? (node.d as List) : []
        if (deviceList) {
            entry.role = 'targeted-tasks'
            claims << 'statement.action.device-list.v1'
            boolean dynamic = deviceList.size() == 1 && deviceList[0] == '$currentEventDevice'
            entry.deviceTarget = dynamic ? 'dynamic' : 'static'
            if (dynamic) gaps << 'statement.action.device-list.dynamic-unresolved'
        }
    } else {
        String gap = 'statement.' + type + '.not-in-increment'
        gaps << ((((acc.evidence as Map).gaps ?: [:]) as Map).containsKey(gap) ? gap : 'statement.unrecognised')
        webcoreSemanticConditionStatements(node.c, path, depth, acc)
    }
    Map promoted = (((acc.evidence as Map).claims ?: [:]) as Map)
    List kept = []
    for (Object c : claims) {
        if (promoted[c] == true) { kept << c } else { gaps << ('claim.' + c + '.not-promoted') }
    }
    entry.claims = kept.sort()
    entry.gaps = (gaps as List).sort()
    (acc.occurrences as Map)[path] = entry
    if (node.s instanceof List) {
        webcoreSemanticStatements(node.s as List, path + '.s', depth, acc, type in ['while', 'repeat', 'for', 'each'] ? 'loop-body' : 'block')
    }
    List elseIfLists = (node.ei instanceof List) ? (node.ei as List) : []
    for (int i = 0; i < elseIfLists.size(); i++) {
        if (elseIfLists[i] instanceof Map && (elseIfLists[i] as Map).s instanceof List) {
            webcoreSemanticStatements((elseIfLists[i] as Map).s as List, path + '.ei[' + i + '].s', depth, acc)
        }
    }
    List cases = (node.cs instanceof List) ? (node.cs as List) : []
    for (int i = 0; i < cases.size(); i++) {
        if (cases[i] instanceof Map && (cases[i] as Map).s instanceof List) {
            webcoreSemanticStatements((cases[i] as Map).s as List, path + '.cs[' + i + '].s', depth, acc, type == 'switch' ? 'switch-case' : 'block')
        }
    }
    if (node.e instanceof List) webcoreSemanticStatements(node.e as List, path + '.e', depth, acc, type == 'switch' ? 'switch-case' : 'block')
}



Map webcoreSemanticConditionList(Map owner, String path, int depth, Map acc, Set claims, Set gaps) {
    Map out = [operator: null, negated: owner.n == true, children: []]
    if (depth > (acc.maxDepth as Integer)) { acc.truncated = true; return out }
    claims << 'condition.list.negation.v1'
    if (owner.o == 'or') { out.operator = 'or'; claims << 'condition.list.operator-or.v1' } else { gaps << 'condition.operator-unproven' }
    List items = (owner.c instanceof List) ? (owner.c as List) : []
    for (int i = 0; i < items.size(); i++) {
        Object k = items[i]
        String at = path + '.c[' + i + ']'
        if (k instanceof Map && (k as Map).t == 'group') {
            Map g = k as Map
            if (g.o == 'followed by') {
                (out.children as List) << [kind: 'opaque-followed-by-group']
                claims << 'condition.followed-by.opaque-group.v1'
                gaps << 'condition.followed-by-timing-unproven'
                webcoreSemanticConditionStatements(g.c, at, depth + 1, acc)
            } else {
                List inner = (g.c instanceof List) ? (g.c as List) : []
                if (inner.any { it instanceof Map && (it as Map).t == 'group' }) gaps << 'condition.group-depth-unproven'
                (out.children as List) << ([kind: 'group'] + webcoreSemanticConditionList(g, at, depth + 1, acc, claims, gaps))
            }
        } else {
            (out.children as List) << [kind: 'opaque-condition']
            gaps << 'condition.leaf-opaque'
        }
        if (k instanceof Map) webcoreSemanticTaskLists(k as Map, at, depth, acc)
    }
    return out
}

void webcoreSemanticTaskLists(Map condition, String path, int depth, Map acc) {
    if (condition.ts instanceof List) webcoreSemanticStatements(condition.ts as List, path + '.ts', depth + 1, acc)
    if (condition.fs instanceof List) webcoreSemanticStatements(condition.fs as List, path + '.fs', depth + 1, acc)
}


void webcoreSemanticConditionStatements(Object conditions, String path, int depth, Map acc) {
    if (!(conditions instanceof List) || depth > (acc.maxDepth as Integer)) return
    List items = conditions as List
    for (int i = 0; i < items.size(); i++) {
        if (!(items[i] instanceof Map)) continue
        Map k = items[i] as Map
        String at = path + '.c[' + i + ']'
        webcoreSemanticTaskLists(k, at, depth, acc)
        if (k.t == 'group') webcoreSemanticConditionStatements(k.c, at, depth + 1, acc)
    }
}


Map webcoreSemanticAssessment(Map model, Map evidence) {
    if (!(model instanceof Map) || model.truncated == true) {
        return [status: 'not-evaluated', occurrences: 0, explained: 0, explainable: false, gaps: [], claims: []]
    }
    Map occurrences = (model.occurrences ?: [:]) as Map
    int explained = 0
    Map gapCounts = [:]
    Set claims = [] as Set
    for (Object o : occurrences.values()) {
        Map om = o as Map
        if (!(om.gaps as List)) explained++
        for (Object g : (om.gaps as List)) gapCounts[g] = ((gapCounts[g] ?: 0) as Integer) + 1
        claims.addAll(om.claims as List)
    }
    Map reasons = (evidence?.gaps ?: [:]) as Map
    return [status: 'complete', occurrences: occurrences.size(), explained: explained,
            explainable: occurrences.size() > 0 && explained == occurrences.size(),
            gaps: gapCounts.keySet().sort().collect { Object g -> [id: g, reason: reasons[g], occurrences: gapCounts[g]] },
            claims: (claims as List).sort()]
}












@Field static final String WEBCORE_COVERAGE_SCHEMA = '1'




@Field static final ConcurrentHashMap<String, Long> WEBCORE_COVERAGE_CLAIMS = new ConcurrentHashMap<>()







Map webcoreCoverageLimits() {
    return [maxAppIdLength: 12, claimTtlMs: 60000L,
            requestBudgetMs: 12000L, loopbackTimeoutSec: 6, analysisBudgetMs: 5000L]
}




Long webcoreCoverageClaim(String appId, Long stamp, Long ttlMs) {
    Long held = WEBCORE_COVERAGE_CLAIMS.putIfAbsent(appId, stamp)
    if (held == null) return stamp
    if ((stamp - held) < ttlMs) return null
    return WEBCORE_COVERAGE_CLAIMS.replace(appId, held, stamp) ? stamp : null
}

void webcoreCoverageRelease(String appId, Long stamp) {
    if (stamp != null) WEBCORE_COVERAGE_CLAIMS.remove(appId, stamp)
}





Closure webcoreCoverageDeadline(Long deadlineAt) {
    Closure expired = { Object ignored -> now() >= deadlineAt }
    return expired
}

Integer webcoreCoverageCount(Object value) { return (value instanceof Number) ? (value as Integer) : null }

String webcoreCoverageText(Object value) { return (value instanceof String) ? (value as String) : null }





Map webcoreCoverageResponse(Map body, Map constructs) {
    Map prov = (body.provenance instanceof Map) ? (body.provenance as Map) : [:]
    Map acc = (body.accounting instanceof Map) ? (body.accounting as Map) : null
    Map levels = (body.levelCounts instanceof Map) ? (body.levelCounts as Map) : [:]
    Map trunc = (body.truncation instanceof Map) ? (body.truncation as Map) : null
    Map meta = (body.meta instanceof Map) ? (body.meta as Map) : [:]

    
    
    
    
    
    
    
    
    
    Object declaredGaps = webcoreStatementShapes().evidenceGaps
    Map shapeGaps = (declaredGaps instanceof Map) ? (declaredGaps as Map) : [:]
    Map counts = [:]
    Map constructLevels = [:]
    Map occurrences = [:]
    List capped = []
    List evidenceCapped = []
    Map occurrenceIn = (body.constructOccurrences instanceof Map) ? (body.constructOccurrences as Map) : [:]
    if (body.constructCounts instanceof Map) {
        (body.constructCounts as Map).keySet().collect { "${it}" }.sort().each { String id ->
            if (!constructs.containsKey(id)) return
            Integer n = webcoreCoverageCount((body.constructCounts as Map)[id])
            Object entry = constructs[id]
            String level = (entry instanceof Map) ? webcoreCoverageText((entry as Map).level) : null
            if (n == null || !(level in ['L0', 'L1', 'L2', 'L3', 'L4', 'L5'])) return
            counts[id] = n
            Object occ = occurrenceIn[id]
            if (occ instanceof Map) {
                Integer valid = webcoreCoverageCount((occ as Map).structurallyValid)
                Integer invalid = webcoreCoverageCount((occ as Map).structurallyInvalid)
                Integer gapped = webcoreCoverageCount((occ as Map).evidenceGapped)
                if (valid != null && invalid != null && gapped != null) {
                    Map gapCounts = [:]
                    Object rawGaps = (occ as Map).evidenceGaps
                    if (rawGaps instanceof Map) {
                        (rawGaps as Map).keySet().collect { "${it}" }.sort().each { String g ->
                            Integer c = webcoreCoverageCount((rawGaps as Map)[g])
                            if (shapeGaps.containsKey(g) && c != null && c > 0) gapCounts[g] = c
                        }
                    }
                    occurrences[id] = [structurallyValid: valid, structurallyInvalid: invalid, evidenceGapped: gapped, evidenceGaps: gapCounts]
                }
            }
            String achieved = webcoreCensusAchievedLevel(level, occurrences[id], n)
            constructLevels[id] = achieved
            if (achieved == level) return
            Map o = occurrences[id] as Map
            if (o != null && o.structurallyInvalid == 0 && o.structurallyValid == n && (o.evidenceGapped as Integer) > 0) evidenceCapped << id
            else capped << id
        }
    }

    List structureRecords = []
    if (body.structureFindings instanceof List) {
        List categories = webcoreCensusStructureCategories()
        int cap = webcoreCensusLimits().maxStructureFindings as Integer
        (body.structureFindings as List).each { Object raw ->
            if (!(raw instanceof Map) || structureRecords.size() >= cap) return
            String category = webcoreCoverageText((raw as Map).category)
            if (!(category in categories)) return
            structureRecords << [path: webcoreCoverageText((raw as Map).path), category: category]
        }
    }

    List records = []
    if (body.unrecognised instanceof List) {
        (body.unrecognised as List).each { Object raw ->
            if (!(raw instanceof Map)) return
            Map r = raw as Map
            records << [path: webcoreCoverageText(r.path),
                        reason: webcoreCoverageText(r.reason),
                        nodeKind: webcoreCoverageText(r.nodeKind)]
        }
    }

    
    
    Map gapTotals = [:]
    occurrences.each { Object id, Object o ->
        ((o as Map).evidenceGaps as Map).each { Object g, Object c -> gapTotals[g] = ((gapTotals[g] ?: 0) as Integer) + (c as Integer) }
    }
    List evidenceGaps = gapTotals.keySet().sort().collect { Object g ->
        [id: g, reason: webcoreCoverageText(shapeGaps[g]), occurrences: gapTotals[g]]
    }

    
    
    
    List levelOrder = ['L0', 'L1', 'L2', 'L3', 'L4', 'L5']
    List statementIds = counts.keySet().findAll { "${it}".startsWith('wc.statement.') } as List
    Map statementAssessment = null
    if (statementIds) {
        Map totals = [occurrences: 0, structurallyValid: 0, structurallyInvalid: 0, evidenceGapped: 0]
        statementIds.each { Object id ->
            totals.occurrences = (totals.occurrences as Integer) + (counts[id] as Integer)
            Map o = occurrences[id] as Map
            if (o != null) ['structurallyValid', 'structurallyInvalid', 'evidenceGapped'].each { String f -> totals[f] = (totals[f] as Integer) + (o[f] as Integer) }
        }
        String lowest = statementIds.collect { constructLevels[it] as String }.min { levelOrder.indexOf(it) }
        statementAssessment = [level: lowest] + totals
    }
    Map nonStatementAssessment = [unrecognised: webcoreCoverageCount(body.unrecognisedOutsideStatements)]

    Map out = [
        status: webcoreCoverageText(body.status),
        appId: webcoreCoverageText(body.appId),
        registryVersion: webcoreCoverageText(body.registryVersion),
        provenance: [
            observedWebcoreVersion: webcoreCoverageText(prov.observedWebcoreVersion),
            referenceSourceCommit: webcoreCoverageText(prov.referenceSourceCommit),
            compatibilityStatus: webcoreCoverageText(prov.compatibilityStatus)
        ],
        accounting: acc == null ? null : [
            objectsVisited: webcoreCoverageCount(acc.objectsVisited),
            arraysVisited: webcoreCoverageCount(acc.arraysVisited),
            fieldsVisited: webcoreCoverageCount(acc.fieldsVisited),
            arrayElementsVisited: webcoreCoverageCount(acc.arrayElementsVisited),
            scalarsVisited: webcoreCoverageCount(acc.scalarsVisited),
            constructCandidates: webcoreCoverageCount(acc.constructCandidates),
            constructsIdentified: webcoreCoverageCount(acc.constructsIdentified),
            defaultBranchOccurrences: webcoreCoverageCount(acc.defaultBranchOccurrences)
        ],
        constructCounts: counts,
        constructLevels: constructLevels,
        constructOccurrences: occurrences,
        structurallyCapped: capped,
        evidenceCapped: evidenceCapped,
        evidenceGaps: evidenceGaps,
        statementAssessment: statementAssessment,
        nonStatementAssessment: nonStatementAssessment,
        semanticAssessment: webcoreSemanticResponse(body.semanticAssessment),
        levelCounts: [L0: webcoreCoverageCount(levels.L0), L1: webcoreCoverageCount(levels.L1),
                      L2: webcoreCoverageCount(levels.L2), L3: webcoreCoverageCount(levels.L3),
                      L4: webcoreCoverageCount(levels.L4), L5: webcoreCoverageCount(levels.L5)],
        unrecognised: records,
        unrecognisedOverflow: webcoreCoverageCount(body.unrecognisedOverflow),
        structureFindings: structureRecords,
        structureFindingsOverflow: webcoreCoverageCount(body.structureFindingsOverflow),
        truncation: trunc == null ? null : [reason: webcoreCoverageText(trunc.reason)],
        meta: [elapsedMs: webcoreCoverageCount(meta.elapsedMs),
               resultBytes: webcoreCoverageCount(meta.resultBytes),
               decoderSchema: webcoreCoverageText(meta.decoderSchema),
               cached: meta.cached == true]
    ]
    if (body.error instanceof String) out.error = body.error
    return out
}



Map webcoreSemanticResponse(Object raw) {
    Map evidence = webcoreSemanticEvidence()
    Map reasons = (evidence.gaps ?: [:]) as Map
    Map claimIds = (evidence.claims ?: [:]) as Map
    Map a = (raw instanceof Map) ? (raw as Map) : [:]
    String status = (a.status == 'complete') ? 'complete' : 'not-evaluated'
    List gaps = []
    if (a.gaps instanceof List) {
        for (Object g : (a.gaps as List)) {
            if (!(g instanceof Map)) continue
            String id = webcoreCoverageText((g as Map).id)
            Integer n = webcoreCoverageCount((g as Map).occurrences)
            if (id != null && reasons.containsKey(id) && n != null && n > 0) gaps << [id: id, reason: reasons[id], occurrences: n]
        }
    }
    List claims = []
    if (a.claims instanceof List) {
        for (Object c : (a.claims as List)) { if (c instanceof String && claimIds.containsKey(c)) claims << c }
    }
    Integer occurrences = webcoreCoverageCount(a.occurrences)
    Integer explained = webcoreCoverageCount(a.explained)
    boolean counted = status == 'complete' && occurrences != null && explained != null
    return [status: status, occurrences: counted ? occurrences : 0, explained: counted ? explained : 0,
            explainable: counted && occurrences > 0 && explained == occurrences && a.explainable == true,
            gaps: status == 'complete' ? gaps : [], claims: status == 'complete' ? claims : []]
}

String webcoreCoverageJson(Map body, Map constructs) {
    return JsonOutput.toJson(webcoreCoverageResponse(body, constructs))
}

Map webcoreCoverageOutcome(String status, String code, int http, String appId) {
    Map body = [status: status, appId: appId, registryVersion: null,
                provenance: [observedWebcoreVersion: null, referenceSourceCommit: null,
                             compatibilityStatus: 'unknown'],
                accounting: null, constructCounts: [:], constructOccurrences: [:], levelCounts: [:],
                unrecognised: [], unrecognisedOverflow: 0, structureFindings: [], structureFindingsOverflow: 0,
                truncation: null, meta: [:]]
    if (code != null) body.error = code
    return [http: http, body: body]
}

Map webcoreDecodeCoverageMapping() {
    Map result = webcoreDecodeCoverageResult("${params?.appId ?: ''}")
    return render(status: result.http as Integer, contentType: 'application/json',
                  data: webcoreCoverageJson(result.body as Map,
                                            (webcoreCensusRegistry().constructs as Map)))
}


Map webcoreDecodeCoverageResult(String rawAppId) {
    Map limits = webcoreCoverageLimits()

    
    
    clearAbandonedScan()
    if (scanEffectivelyActive()) return webcoreCoverageOutcome('busy', 'scan-active', 409, null)

    String appId = rawAppId == null ? '' : rawAppId.trim()
    if (!appId || appId.length() > (limits.maxAppIdLength as Integer) || !(appId ==~ /^[0-9]+$/)) {
        return webcoreCoverageOutcome('invalid-request', 'invalid-app-id', 400, null)
    }

    
    
    Map appInfo = (state.appInfo instanceof Map) ? (state.appInfo as Map) : [:]
    Object entry = appInfo[appId]
    if (!(entry instanceof Map)) return webcoreCoverageOutcome('invalid-request', 'unknown-app-id', 400, appId)
    if ("${(entry as Map).type ?: ''}".trim() != 'webCoRE Piston') {
        return webcoreCoverageOutcome('invalid-request', 'not-a-piston', 400, appId)
    }

    Long started = now()
    Long stamp = webcoreCoverageClaim(appId, started, limits.claimTtlMs as Long)
    if (stamp == null) return webcoreCoverageOutcome('busy', 'coverage-in-flight', 409, appId)

    try {
        Map fetched = httpFetch("${LOOPBACK_BASE}/installedapp/statusJson/${appId}",
                                limits.loopbackTimeoutSec as Integer,
                                [contentType: 'application/json'])
        if (!fetched.ok) {
            
            return fetched.timedOut ? webcoreCoverageOutcome('error', 'source-timeout', 422, appId)
                                    : webcoreCoverageOutcome('error', 'source-unavailable', 422, appId)
        }
        if (!(fetched.data instanceof Map)) return webcoreCoverageOutcome('error', 'source-malformed', 422, appId)

        Map decoded = decodeWebcorePistonDocument(fetched.data as Map)
        
        
        if (decoded.status == 'not-present') return webcoreCoverageOutcome('not-present', null, 200, appId)
        if (decoded.status != 'complete') return webcoreCoverageOutcome('error', 'decode-failed', 422, appId)

        Map registry = webcoreCensusRegistry()
        Map constructs = registry.constructs as Map
        
        
        
        Long analysisDeadline = Math.min(started + (limits.requestBudgetMs as Long),
                                         now() + (limits.analysisBudgetMs as Long))
        Map census = collectWebcoreDecodeCoverage(decoded.document, registry, webcoreCoverageDeadline(analysisDeadline),
                                                  webcoreStatementShapes())
        Map semanticModel = (census.status == 'complete') ?
            webcoreSemanticModel(decoded.document, webcoreSemanticEvidence(), webcoreCensusLimits().maxDepth as int) : null
        decoded = null

        
        
        
        if (census.status == 'truncated' &&
            ((census.truncation instanceof Map) ? (census.truncation as Map).reason : null) == 'analysis-deadline') {
            return webcoreCoverageOutcome('analysis-timeout', 'analysis-deadline', 422, appId)
        }

        Map body = [
            status: census.status,
            appId: appId,
            registryVersion: census.registryVersion,
            provenance: census.provenance,
            accounting: census.accounting,
            constructCounts: census.constructCounts,
            constructOccurrences: census.constructOccurrences,
            levelCounts: census.levelCounts,
            unrecognised: census.unrecognised,
            unrecognisedOverflow: census.unrecognisedOverflow,
            structureFindings: census.structureFindings,
            structureFindingsOverflow: census.structureFindingsOverflow,
            unrecognisedOutsideStatements: census.unrecognisedOutsideStatements,
            semanticAssessment: webcoreSemanticAssessment(semanticModel, webcoreSemanticEvidence()),
            truncation: census.truncation
        ]
        
        
        body.meta = [
            elapsedMs: (now() - started),
            resultBytes: webcoreCoverageJson(body + [meta: [:]], constructs).getBytes('UTF-8').length,
            decoderSchema: WEBCORE_COVERAGE_SCHEMA,
            cached: false
        ]
        return [http: 200, body: body]
    } catch (Exception ignored) {
        return webcoreCoverageOutcome('error', 'coverage-failed', 422, appId)
    } finally {
        webcoreCoverageRelease(appId, stamp)
    }
}


























































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

        
        
        (fam.targets as List<String>).each { String targetSetting ->
            String raw = vals[targetSetting + '.' + num] ?: ''
            if (!raw) return

            
            
            
            
            
            
            
            
            
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












List extractHubVariableWrites(Map data) {
    Map st = [:]
    (data.appState ?: []).each { e ->
        if (e instanceof Map && e.name != null) st["${e.name}"] = e.value
    }
    Map actions = (st.actions ?: [:]) as Map

    Map settingValues = [:]
    Map settingDevices = [:]
    
    
    
    
    
    
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
        
        
        
        String varName = "${settingValues["xVarV.${num}"] ?: ''}"
        if (!varName) return
        
        
        
        
        
        Map write = [variable: varName, actionNum: "${num}", field: "xVarV.${num}"]
        
        
        
        
        if (settingValues["valStringOp.${num}"] == 'Device attribute') {
            String attr = settingValues["tCustomAttr.${num}"]
            List srcDevices = settingDevices["customDev.${num}"] ?: []
            List srcDeviceIds = settingDeviceIds["customDev.${num}"] ?: []
            if (attr && srcDevices) {
                write.sourceDevice = srcDevices[0]
                write.sourceAttr = attr
                
                
                
                
                if (srcDeviceIds) write.sourceDeviceId = srcDeviceIds[0]
            }
        }
        out << write
    }
    return out
}


















List extractHubVariableReads(Map data) {
    Map st = [:]
    (data.appState ?: []).each { e ->
        if (e instanceof Map && e.name != null) st["${e.name}"] = e.value
    }
    Map evalMap = (st.eval ?: [:]) as Map
    
    
    
    
    
    
    boolean hasPredicate = st.hasPredicate == true

    Map settingValues = [:]
    (data.appSettings ?: []).each { s ->
        if (!(s instanceof Map)) return
        if (s.value != null && "${s.value}") settingValues["${s.name}"] = "${s.value}"
    }

    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    List found = []
    Set<String> foundKeys = new LinkedHashSet<>()
    evalMap.each { groupId, expr ->
        String role = ("${groupId}" == '0') ? 'required-expression' : 'condition'
        if (role == 'required-expression' && !hasPredicate) return
        (expr instanceof List ? expr as List : []).each { item ->
            String s = "${item}"
            if (settingValues["rCapab_${s}"] != 'Variable') return
            String varName = "${settingValues["xVar_${s}"] ?: ''}"
            if (!varName) return
            String field = "xVar_${s}"
            String key = "${varName}|${role}|${field}"
            if (foundKeys.add(key)) {
                found << [variable: varName, confirmed: true, usageRole: role,
                          evidenceKind: 'structured-setting', field: field]
            }
        }
    }

    
    
    
    
    
    
    
    settingValues.keySet().findAll { it ==~ /^tCapab\d+$/ }.each { String capabKey ->
        if (settingValues[capabKey] != 'Variable') return
        String num = capabKey.replaceAll('^tCapab', '')
        String varName = "${settingValues["xVar${num}"] ?: ''}"
        if (!varName) return
        String field = "xVar${num}"
        String key = "${varName}|trigger|${field}"
        if (foundKeys.add(key)) {
            found << [variable: varName, confirmed: true, usageRole: 'trigger',
                      evidenceKind: 'structured-setting', field: field]
        }
    }

    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    (data.appSettings ?: []).each { s ->
        if (!(s instanceof Map)) return
        String settingType = "${s.type}"
        if (!(settingType == 'text' || settingType == 'textarea')) return
        String val = "${s.value ?: ''}"
        (val =~ /%([A-Za-z_][A-Za-z0-9_]*)%/).findAll().each { m ->
            String varName = "${m[1]}"
            String field = "${s.name}"
            String key = "${varName}|text-token|${field}"
            if (foundKeys.add(key)) {
                found << [variable: varName, confirmed: false, usageRole: null,
                          evidenceKind: 'text-token', field: field]
            }
        }
    }

    return found
}

List buildRuleFlow(Map data) {
    Map st = [:]
    (data.appState ?: []).each { e ->
        if (e instanceof Map && e.name != null) st["${e.name}"] = e.value
    }

    List actionList = (st.actionList ?: []) as List
    if (!actionList) {
        
        
        
        if (st.graphDocument) return buildVisualRuleBuilderFlow(st)
        
        
        
        
        return buildNotifierFlow(data, st)
    }

    Map actions = (st.actions ?: [:]) as Map
    Map evalMap = (st.eval ?: [:]) as Map

    
    
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

    
    settingDevices.keySet().findAll { it.startsWith('tDev') }.sort().each { String n ->
        String num = n.replaceAll('^tDev_?', '')
        steps << [kind: 'trigger', label: (capabs[num] ?: "Trigger ${num}"), devices: settingDevices[n]]
    }

    
    if (st.hasPredicate == true) {
        String text = expressionText((evalMap['0'] ?: []) as List, capabs)
        if (text) steps << [kind: 'required', label: text, devices: requiredDevices(evalMap['0'] as List, settingDevices)]
    }

    actionList.each { a ->
        steps << actionStep("${a}", (actions["${a}"] ?: [:]) as Map, settingValues, settingDevices, evalMap, capabs)
    }
    return steps
}


















List buildVisualRuleBuilderFlow(Map st) {
    Map graphDoc = (st.graphDocument instanceof Map) ? (st.graphDocument as Map) : [:]
    List nodes = (graphDoc.nodes ?: []) as List
    List edges = (graphDoc.edges ?: []) as List
    if (!nodes) return []

    Map deviceLabels = (state.deviceLabels ?: [:]) as Map
    Map nodesById = [:]
    nodes.each { n -> if (n instanceof Map) nodesById["${(n as Map).id}"] = n as Map }

    
    
    Map outgoing = [:]
    edges.each { e ->
        if (!(e instanceof Map)) return
        Map edge = e as Map
        String from = "${edge.from}"
        List list = (outgoing[from] ?: []) as List
        list << [port: "${edge.port}", to: "${edge.to}"]
        outgoing[from] = list
    }

    
    
    
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

    
    
    List triggerNodes = nodes.findAll { it instanceof Map && "${(it as Map).kind}" == 'trigger' }
    triggerNodes.each { Map t ->
        steps << [kind: 'trigger', label: labelForNode(t), devices: resolveDevices(t.config as Map)]
    }
    if (!triggerNodes) return steps

    
    
    
    
    Set nextIds = [] as Set
    triggerNodes.each { Map t -> (outgoing["${t.id}"] ?: []).each { nextIds << "${it.to}" } }
    if (nextIds.size() != 1) return steps
    String cursor = nextIds.iterator().next()

    
    
    
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

        
        List ruleTargets = (node.type == 'runRule' && node.config instanceof Map && (node.config as Map).appId != null) ?
            ["${(node.config as Map).appId}"] : []
        steps << [kind: 'action', label: labelForNode(node), devices: resolveDevices(node.config as Map), ruleTargets: ruleTargets]
        cursor = out ? "${(out[0] as Map).to}" : null
    }

    return steps
}




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
        
        
        (requiredDevices((evalMap["${act.rule}"] ?: []) as List, settingDevices)).each {
            if (!devices.contains(it)) devices << it
        }
    }

    
    
    
    
    List ruleTargets = []
    boolean selfTarget = false
    Map linkFam = RULE_LINK_ACTIONS[method] as Map
    if (linkFam) {
        
        
        
        (linkFam.targets as List<String>).each { String targetSetting ->
            String rawTargets = settingValues["${targetSetting}.${num}"] ?: ''
            if (!rawTargets) return
            
            
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
        
        
        
        
        
        
        
        variableField: (method == 'getSetVariable') ? "xVarV.${num}" : null,
    ]
}

String actionLabel(String method, String num, Map act, Map settingValues, Map settingDevices, Map evalMap, Map capabs) {
    switch (method) {
        case 'getSetVariable':
            
            
            
            
            
            
            
            
            
            
            
            
            
            
            
            
            
            String varName = (settingValues["xVarV.${num}"] ?: '').replaceAll(/\.$/, '')
            if (!varName) return 'Set Variable [unresolved]'
            
            
            
            
            if (settingValues["valStringOp.${num}"] == 'Device attribute') {
                String attr = settingValues["tCustomAttr.${num}"]
                List srcDevices = settingDevices["customDev.${num}"] ?: []
                if (attr && srcDevices) return "Set Variable ${varName} from ${srcDevices[0]}.${attr}"
            }
            return "Set Variable ${varName}"
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
            
            
            
            
            
            
            
            
            
            
            
            return "Set Private Boolean ${settingValues["pvTF.${num}"] == 'true' ? 'False' : 'True'}"
        case 'getDefinedAction':
            return 'Run defined actions'
        case 'getSetVolume':
            
            String vol = settingValues["volumeVal.${num}"] ?: settingValues["speakVolume.${num}"]
            return vol ? "Set volume ${vol}" : 'Set volume'
        case 'getChime':
            return 'Chime'
        case 'getCapture':
            return 'Capture device state'
        case 'getRestore':
            return 'Restore device state'
        case 'getStopActions':
            
            
            
            return 'Cancel Timed Actions'
        case 'getRuleActions':
            return 'Run Actions'
        case 'getPauseResumeRules':
            
            
            
            
            
            
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























@Field static final List ICON_RULES = [
    [key: 'locks',     label: 'Locks & access',       caps: ['Lock', 'LockCodes']],
    
    
    
    
    
    
    [key: 'presence',  label: 'Location & presence',  caps: ['PresenceSensor']],
    
    
    
    
    [key: 'doors',     label: 'Doors & windows',      caps: ['ContactSensor', 'GarageDoorControl', 'DoorControl']],
    [key: 'water',     label: 'Water',                caps: ['WaterSensor', 'Valve']],
    [key: 'motion',    label: 'Motion & occupancy',   caps: ['MotionSensor']],
    [key: 'safety',    label: 'Safety',               caps: ['SmokeDetector', 'CarbonMonoxideDetector']],
    [key: 'buttons',   label: 'Buttons & remotes',    caps: ['PushableButton', 'HoldableButton',
                                                              'DoubleTapableButton', 'ReleasableButton']],
    [key: 'cameras',   label: 'Cameras & doorbells',  caps: ['ImageCapture']],
    [key: 'shades',    label: 'Shades & coverings',   caps: ['WindowShade']],
    
    
    
    
    
    
    
    [key: 'broker',    label: 'Notification gateway', caps: ['Notification']],
    
    
    
    
    
    
    [key: 'climate',   label: 'Climate control',      caps: ['Thermostat', 'ThermostatMode', 'ThermostatSetpoint',
                                                              'ThermostatCoolingSetpoint', 'ThermostatHeatingSetpoint',
                                                              'ThermostatOperatingState', 'ThermostatFanMode',
                                                              'FanControl']],
    [key: 'lighting',  label: 'Lighting',             caps: ['Light', 'ColorControl', 'ColorTemperature',
                                                              'ColorMode', 'SwitchLevel', 'LightEffects']],
    [key: 'security',  label: 'Security & alarms',    caps: ['Alarm', 'Chime', 'Tone']],
    [key: 'media',     label: 'Media & audio',        caps: ['AudioVolume', 'SpeechSynthesis', 'MediaTransport',
                                                              'MusicPlayer']],
    
    
    
    
    
    
    
    [key: 'switches',  label: 'Switches & outlets',   caps: ['Switch', 'Outlet']],
    
    
    
    
    
    [key: 'energy',    label: 'Energy',               caps: ['PowerMeter', 'EnergyMeter', 'VoltageMeasurement']],
    [key: 'environmental', label: 'Environmental sensors', caps: ['TemperatureMeasurement', 'IlluminanceMeasurement',
                                                              'RelativeHumidityMeasurement', 'PressureMeasurement',
                                                              'CarbonDioxideMeasurement', 'UltravioletIndex']],
    [key: 'sensor',    label: 'Generic sensor',       caps: ['Sensor']],
    
    
    
    
    
    
    
    
    
    
    [key: 'hub',       label: 'Hub & infrastructure', caps: []],
    [key: 'ai',        label: 'AI node',              caps: []],
    
    
    
    
    
    
    
    
    [key: 'appliance', label: 'Appliance',            caps: []],
    [key: 'network',   label: 'Internet/network',     caps: []],
    [key: 'display',   label: 'Display',              caps: []],
    
    
    
    
    
    
    [key: 'scene',     label: 'Scene',                caps: []],
    
    
    
    
    
    
    
    
    [key: 'connector', label: 'Hub Variable connector', caps: []],
]














@Field static final List ICON_NAME_HINTS = [
    [key: 'buttons',   words: ['button', 'remote']],
    [key: 'appliance', words: ['kettle', 'oven', 'fridge', 'refrigerator', 'dishwasher',
                                'washer', 'dryer', 'microwave', 'toaster']],
    [key: 'network',   words: ['internet', 'wifi', 'router', 'modem']],
    
    
    
    
    [key: 'hub',       words: ['bridge']],
    
    
    [key: 'display',   words: ['display', 'monitor', 'tablet', 'nest']],
    
    
    [key: 'climate',   words: ['heater', 'dehumidifier', 'dehumidifyer', 'humidifier', 'aircon']],
    [key: 'lighting',  words: ['light', 'lights', 'lamp', 'bulb']],
]






List nameWords(String name) {
    return (name ?: '').toLowerCase().split('[^a-z0-9]+') as List
}








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
















































@Field static final List<String> ICON_KEYS = [
    'locks', 'presence', 'doors', 'water', 'motion', 'safety', 'buttons',
    'cameras', 'shades', 'broker', 'climate', 'lighting', 'security', 'media',
    'switches', 'energy', 'environmental', 'sensor', 'hub', 'ai', 'appliance',
    'network', 'display', 'scene', 'connector', 'unknown',
]




String autoDetectIconKey(List capabilities) {
    List caps = (capabilities ?: []) as List
    for (rule in ICON_RULES) {
        Map r = rule as Map
        if ((r.caps as List).any { caps.contains(it) }) return r.key as String
    }
    return 'unknown'
}





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

















List unusedConstraintDeviceIds(Map data) {
    Map st = [:]
    (data.appState ?: []).each { e ->
        if (e instanceof Map && e.name != null) st["${e.name}"] = e.value
    }
    List actionList = (st.actionList ?: []) as List
    if (!actionList) return []

    Map actions = (st.actions ?: [:]) as Map
    Map evalMap = (st.eval ?: [:]) as Map

    Set<String> liveGroups = new LinkedHashSet<String>()
    if (st.hasPredicate == true) liveGroups << '0'
    actionList.each { a ->
        Object r = ((actions["${a}"] ?: [:]) as Map).rule
        if (r != null) liveGroups << "${r}"
    }

    Set<String> liveConditions = new LinkedHashSet<String>()
    liveGroups.each { String g ->
        (evalMap[g] instanceof List ? evalMap[g] as List : []).each { item ->
            String c = "${item}"
            if (!(c in ['AND', 'OR', 'NOT', '(', ')'])) liveConditions << c
        }
    }

    Set<String> used = new LinkedHashSet<String>()
    Set<String> idle = new LinkedHashSet<String>()
    (data.appSettings ?: []).each { s ->
        if (!(s instanceof Map)) return
        String n = "${s.name}"
        if (!n.startsWith('rDev_')) return
        Map dl = s.deviceList as Map
        if (!dl) return
        boolean live = liveConditions.contains(n.substring(5))
        dl.keySet().each { if (live) used << "${it}" else idle << "${it}" }
    }
    idle.removeAll(used)
    return idle.toList()
}

String roleForSetting(String settingName, String settingType, String devId, List subscribed) {
    
    
    if (settingName.startsWith('tDev')) return 'trigger'
    if (settingName.startsWith('rDev')) return 'constraint'
    
    
    
    
    
    
    
    if (settingType == 'capability.*') return 'exposed'
    
    if (subscribed.contains(devId)) return 'trigger'
    
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











String stripReplacementChar(String s) {
    return s ? s.replace(new String(Character.toChars(0xFFFD)), '') : s
}



int countOf(def v) {
    if (v instanceof List) return (v as List).size()
    if (v instanceof Map) return (v as Map).size()
    return 0
}








List scheduledJobList(def raw) {
    if (raw instanceof List) return raw as List
    if (raw instanceof Map && raw) return [raw as Map]
    return []
}











String stripStatusMarkup(String s) {
    if (!s) return s
    
    
    
    return stripTags(s.replaceAll('<span[^>]*>.*?</span>', '')).replaceAll(' +', ' ')
}





















Map linkedRuleName(String targetId, Map appInfo, Map cache) {
    if (cache.containsKey(targetId)) return cache[targetId] as Map
    Map target = appInfo[targetId] as Map
    String label = target?.label as String
    String draw = target?.drawLabel as String
    boolean missing = !appInfo.containsKey(targetId)
    
    
    if (!label && missing) label = "Rule ${targetId} - deleted"
    if (!label) label = "Rule ${targetId}"
    
    
    Map result = [label: label, draw: draw ?: label, missing: missing]
    cache[targetId] = result
    return result
}




List resolveFlowTargets(List flow, Map appInfo, Map cache) {
    (flow ?: []).each { step ->
        if (!(step instanceof Map)) return
        Map s = step as Map
        List targets = (s.ruleTargets ?: []) as List
        if (!targets) return
        List devices = (s.devices ?: []) as List
        
        
        
        if (s.selfTarget && !devices.contains('This Rule')) devices << 'This Rule'
        targets.each { t ->
            String nm = (linkedRuleName("${t}", appInfo, cache).label) as String
            if (!devices.contains(nm)) devices << nm
        }
        s.devices = devices
    }
    return flow
}












































Map nodeEntry(String id, String fullLabel, String group, String subtitle = null, String drawLabel = null,
              String statusSuffix = null, boolean statusInTitle = true) {
    String label = fullLabel ?: id
    String clean = drawLabel ?: label
    
    
    
    
    String shortLabel = clean
    if (shortLabel.length() > 24) shortLabel = "${shortLabel.substring(0, 22)}…"
    if (statusSuffix) shortLabel = "${shortLabel} (${statusSuffix})"
    String canonicalName = subtitle ? "${clean} (${subtitle})" : clean
    String drawText = statusSuffix ? "${canonicalName} (${statusSuffix})" : canonicalName
    String titleText = subtitle ? "${label} (${subtitle})" : label
    if (statusSuffix && statusInTitle) titleText = "${titleText} (${statusSuffix})"
    return [
        id: id,
        label: shortLabel,
        draw: drawText,
        title: titleText,
        name: canonicalName,
        group: group,
    ]
}








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

    
    
    
    String parent = parentId
    if (parent) {
        Map p = appInfo[parent] as Map
        String name = (p?.drawLabel ?: p?.label) as String
        if (name) return "child of ${name}"
    }

    return 'references nothing'
}







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



String canonicalHubVariableName(String rawName, Map inventoryVars) {
    String raw = rawName ?: ''
    if (!raw || !inventoryVars) return raw
    if (inventoryVars.containsKey(raw)) return raw

    String comparable = raw.endsWith('.') ? raw.substring(0, raw.length() - 1) : raw
    List matches = inventoryVars.keySet().findAll { Object key ->
        String candidate = "${key}"
        String candidateComparable = candidate.endsWith('.') ? candidate.substring(0, candidate.length() - 1) : candidate
        candidateComparable == comparable
    } as List
    return matches.size() == 1 ? "${matches[0]}" : raw
}





















@Field static final Set<String> AM_VAR_BUILT_IN_TOKENS = ['device', 'time', 'date', 'value', 'text'] as Set<String>





List extractLocalVariableDefinitions(Map data, String ownerAppId) {
    Map st = [:]
    (data.appState ?: []).each { e ->
        if (e instanceof Map && e.name != null) st["${e.name}"] = e.value
    }
    Map allLocalVars = (st.allLocalVars ?: [:]) as Map
    List out = []
    allLocalVars.each { name, meta ->
        String varName = "${name}"
        if (!varName) return
        Map m = (meta instanceof Map) ? (meta as Map) : [:]
        out << [
            identity: "${ownerAppId}:${varName}",
            name: varName,
            variableType: normalizeHubVariableType(m.type as String),
        ]
    }
    return out
}







Map classifyVariableReference(Map reference, Map context) {
    String rawName = amVarText(reference.name)
    if (!rawName) throw new IllegalArgumentException('reference.name is required')

    String ownerAppId = amVarText(context.ownerAppId)
    Map<String, Map> localDefinitions = amVarDefinitionsByName(context.localDefinitions)
    Map<String, Map> hubDefinitions = amVarDefinitionsByName(context.hubDefinitions)
    Set<String> builtInTokens = ((context.builtInTokens ?: AM_VAR_BUILT_IN_TOKENS) as Collection)
        .collect { amVarText(it).toLowerCase() }
        .findAll { it } as Set<String>

    String evidenceKind = amVarText(reference.evidenceKind ?: 'structured-setting')
    String provenScope = amVarText(reference.provenScope ?: reference.scopeHint).toLowerCase()
    String scopeSource = amVarText(reference.scopeSource)

    if (provenScope && !(provenScope in ['local', 'hub'])) {
        throw new IllegalArgumentException("Unsupported proven scope '${provenScope}'")
    }
    if (provenScope && !scopeSource) {
        throw new IllegalArgumentException('A proven scope requires scopeSource evidence')
    }

    
    
    
    if (evidenceKind == 'text-token' && !provenScope) {
        provenScope = amVarIndependentlyEstablishedScope(rawName, context.establishedScopes)
        if (provenScope) scopeSource = 'independently-established-reference'
    }

    if (evidenceKind == 'text-token' && !provenScope) {
        boolean builtIn = builtInTokens.contains(rawName.toLowerCase())
        return amVarBaseResult(reference, rawName, ownerAppId, evidenceKind, scopeSource) + [
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

    Map localMatch = amVarMatchWithinScope(rawName, localDefinitions)
    Map hubMatch = amVarMatchWithinScope(rawName, hubDefinitions)

    if (provenScope) {
        Map selected = provenScope == 'local' ? localMatch : hubMatch
        return amVarClassifiedForProvenScope(reference, rawName, ownerAppId, evidenceKind,
            scopeSource, provenScope, selected)
    }

    List<String> candidateScopes = []
    if (localMatch.status == 'matched') candidateScopes << 'local'
    if (hubMatch.status == 'matched') candidateScopes << 'hub'

    if (candidateScopes == ['local']) {
        return amVarResolved(reference, rawName, ownerAppId, evidenceKind, scopeSource,
            'local', localMatch.canonicalName as String, localMatch.matchKind as String)
    }
    if (candidateScopes == ['hub']) {
        return amVarResolved(reference, rawName, ownerAppId, evidenceKind, scopeSource,
            'hub', hubMatch.canonicalName as String, hubMatch.matchKind as String)
    }

    Map candidates = [local: localMatch.candidates ?: [], hub: hubMatch.candidates ?: []]
    if (candidateScopes.size() == 2 || localMatch.status == 'multiple' || hubMatch.status == 'multiple') {
        return amVarBaseResult(reference, rawName, ownerAppId, evidenceKind, scopeSource) + [
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

    return amVarBaseResult(reference, rawName, ownerAppId, evidenceKind, scopeSource) + [
        scope: 'unresolved',
        status: 'unresolved',
        canonicalName: null,
        localIdentity: null,
        candidateScopes: [],
        candidates: candidates,
        reason: 'no-definition-in-either-scope'
    ]
}

private Map amVarClassifiedForProvenScope(Map reference, String rawName, String ownerAppId,
        String evidenceKind, String scopeSource, String provenScope, Map selected) {
    if (selected.status == 'matched') {
        return amVarResolved(reference, rawName, ownerAppId, evidenceKind, scopeSource,
            provenScope, selected.canonicalName as String, selected.matchKind as String)
    }
    if (selected.status == 'multiple') {
        return amVarBaseResult(reference, rawName, ownerAppId, evidenceKind, scopeSource) + [
            scope: 'ambiguous',
            status: 'ambiguous',
            canonicalName: null,
            localIdentity: null,
            candidateScopes: [provenScope],
            candidates: [(provenScope): selected.candidates ?: []],
            reason: 'normalization-produced-multiple-candidates'
        ]
    }
    return amVarBaseResult(reference, rawName, ownerAppId, evidenceKind, scopeSource) + [
        scope: 'unresolved',
        status: 'unresolved',
        canonicalName: null,
        localIdentity: null,
        candidateScopes: [provenScope],
        candidates: [(provenScope): []],
        reason: 'definition-missing-in-proven-scope'
    ]
}

private Map amVarResolved(Map reference, String rawName, String ownerAppId, String evidenceKind,
        String scopeSource, String scope, String canonicalName, String matchKind) {
    if (scope == 'local' && !ownerAppId) {
        throw new IllegalArgumentException('context.ownerAppId is required for a Local Variable')
    }
    return amVarBaseResult(reference, rawName, ownerAppId, evidenceKind, scopeSource) + [
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

private Map amVarBaseResult(Map reference, String rawName, String ownerAppId,
        String evidenceKind, String scopeSource) {
    return [
        name: rawName,
        ownerAppId: ownerAppId ?: null,
        operation: amVarText(reference.operation ?: 'read'),
        usageRole: reference.containsKey('usageRole') ? reference.usageRole : null,
        evidence: [
            kind: evidenceKind,
            field: amVarText(reference.field) ?: null,
            scopeSource: scopeSource ?: null
        ]
    ]
}





private Map amVarMatchWithinScope(String rawName, Map<String, Map> definitions) {
    if (definitions.containsKey(rawName)) {
        return [status: 'matched', canonicalName: rawName, matchKind: 'exact', candidates: [rawName]]
    }
    String comparable = amVarRemoveOneTrailingPeriod(rawName)
    List<String> matches = definitions.keySet().findAll { String candidate ->
        amVarRemoveOneTrailingPeriod(candidate) == comparable
    }.sort()
    if (matches.size() == 1) {
        return [status: 'matched', canonicalName: matches[0], matchKind: 'normalized', candidates: matches]
    }
    if (matches.size() > 1) {
        return [status: 'multiple', canonicalName: null, matchKind: 'normalized', candidates: matches]
    }
    return [status: 'none', canonicalName: null, matchKind: null, candidates: []]
}

private Map<String, Map> amVarDefinitionsByName(Object rawDefinitions) {
    Map<String, Map> out = new LinkedHashMap<>()
    if (rawDefinitions instanceof Map) {
        (rawDefinitions as Map).each { Object name, Object definition ->
            String key = amVarText(name)
            if (key) out[key] = definition instanceof Map ? new LinkedHashMap(definition as Map) : [:]
        }
    } else if (rawDefinitions instanceof Collection) {
        (rawDefinitions as Collection).each { Object definition ->
            if (!(definition instanceof Map)) return
            String key = amVarText((definition as Map).name)
            if (key) out[key] = new LinkedHashMap(definition as Map)
        }
    }
    return out
}

private String amVarIndependentlyEstablishedScope(String rawName, Object establishedScopes) {
    if (!(establishedScopes instanceof Map)) return ''
    String scope = amVarText((establishedScopes as Map)[rawName]).toLowerCase()
    return scope in ['local', 'hub'] ? scope : ''
}

private String amVarRemoveOneTrailingPeriod(String value) {
    return value?.endsWith('.') ? value.substring(0, value.length() - 1) : value
}

private String amVarText(Object value) {
    return value == null ? '' : "${value}".trim()
}






Map classifyRuleVariableReferences(List hubVarWrites, List hubVarReads, List localDefinitions,
        Map hubDefinitions, String ownerAppId) {
    List raw = []
    (hubVarWrites ?: []).each { Map w ->
        if (!w.variable) return
        raw << [name: w.variable, operation: 'write', evidenceKind: 'structured-setting', field: w.field]
    }
    (hubVarReads ?: []).each { Map r ->
        if (!r.variable) return
        raw << [name: r.variable, operation: 'read', usageRole: r.usageRole,
                 evidenceKind: r.evidenceKind, field: r.field]
    }

    
    
    
    
    Map establishedScopes = [:]
    Map context = [ownerAppId: ownerAppId, localDefinitions: localDefinitions,
                   hubDefinitions: hubDefinitions, establishedScopes: establishedScopes]

    
    
    
    List structured = raw.findAll { it.evidenceKind != 'text-token' }
    List weakText = raw.findAll { it.evidenceKind == 'text-token' }

    List resolved = []
    List nonResolved = []
    (structured + weakText).each { Map reference ->
        Map result = classifyVariableReference(reference, context)
        if (result.status == 'resolved') {
            
            
            
            
            
            
            
            
            String canonical = result.canonicalName as String
            if (canonical && !establishedScopes.containsKey(canonical)) establishedScopes[canonical] = result.scope
            String rawResultName = result.name as String
            if (rawResultName && !establishedScopes.containsKey(rawResultName)) establishedScopes[rawResultName] = result.scope
        }
        if (result.status == 'ignored') return
        if (result.status == 'resolved') resolved << result
        else nonResolved << result
    }
    return [variableReferences: resolved, nonResolvedVariableReferences: nonResolved]
}











void correctFlowVariableLabels(Map flows, Map ruleVariables) {
    flows.each { String appNodeId, Object stepsObj ->
        if (!(stepsObj instanceof List)) return
        List refs = (ruleVariables[appNodeId]?.variableReferences ?: []) as List
        List nonResolved = (ruleVariables[appNodeId]?.nonResolvedVariableReferences ?: []) as List
        Map byField = [:]
        (refs + nonResolved).each { Map r -> if (r.evidence?.field) byField["${r.evidence.field}"] = r }
        (stepsObj as List).each { Object step ->
            if (!(step instanceof Map)) return
            Map s = step as Map
            String field = s.variableField as String
            if (!field) return
            Map r = byField[field] as Map
            if (!r) return
            String currentLabel = s.label as String
            if (!currentLabel?.startsWith('Set Variable ')) return
            String rest = currentLabel.substring('Set Variable '.length())
            int fromIdx = rest.indexOf(' from ')
            String suffix = fromIdx >= 0 ? rest.substring(fromIdx) : ''
            String varName = (r.canonicalName ?: r.name) as String
            if (r.status == 'resolved' && r.scope == 'local') {
                s.label = "Set Local Variable ${varName}${suffix}"
            } else if (r.status == 'resolved' && r.scope == 'hub') {
                s.label = "Set Hub Variable ${varName}${suffix}"
            } else if (r.status == 'ambiguous') {
                s.label = "Set Variable ${varName} (scope ambiguous)${suffix}"
            } else if (r.status == 'unresolved') {
                s.label = "Set Variable ${varName} (unresolved)${suffix}"
            }
        }
    }
}










List buildHasComponentEdges(Set nodeIds, Map deviceParents) {
    List result = []
    deviceParents.each { childId, parentId ->
        String childNodeId = "d${childId}"
        String parentNodeId = "d${parentId}"
        if (!nodeIds.contains(childNodeId) || !nodeIds.contains(parentNodeId)) return
        result << [from: parentNodeId, to: childNodeId, kind: 'hasComponent']
    }
    return result
}







String webcoreDeviceHashToken(String deviceId) {
    MessageDigest md = MessageDigest.getInstance('MD5')
    byte[] digest = md.digest("core.${deviceId}".getBytes('UTF-8'))
    StringBuilder hex = new StringBuilder()
    digest.each { byte b -> hex << String.format('%02x', b & 0xFF) }
    return ":${hex}:"
}










Map buildWebcoreDeviceHashIndexes(Map appInfo) {
    Map<String, Map<String, String>> resolvable = [:]
    Map<String, Set<String>> ambiguous = [:]
    appInfo.each { String appId, info ->
        if (!(info instanceof Map)) return
        Map appMap = info as Map
        if ("${appMap.type ?: ''}".trim() != 'webCoRE') return
        Map<String, String> index = [:]
        Set<String> collided = [] as Set<String>
        ((appMap.webcorePermittedDeviceIds ?: []) as List).each { Object devId ->
            String id = "${devId}"
            String hash = webcoreDeviceHashToken(id)
            if (index.containsKey(hash) && index[hash] != id) {
                collided << hash
            } else if (!collided.contains(hash)) {
                index[hash] = id
            }
        }
        collided.each { String hash -> index.remove(hash) }
        resolvable[appId] = index
        ambiguous[appId] = collided
    }
    return [resolvable: resolvable, ambiguous: ambiguous]
}





Map resolveWebcoreDeviceToken(String token, String parentAppId, Map hashIndexes, Map labels) {
    if (!parentAppId) return [issue: 'missing-parent-device-index']
    Map resolvableByParent = hashIndexes.resolvable as Map
    Map ambiguousByParent = hashIndexes.ambiguous as Map
    if (!resolvableByParent.containsKey(parentAppId)) return [issue: 'missing-parent-device-index']
    Set<String> ambiguousHashes = (ambiguousByParent[parentAppId] ?: []) as Set<String>
    if (ambiguousHashes.contains(token)) return [issue: 'ambiguous-device-hash']
    Map index = resolvableByParent[parentAppId] as Map
    String deviceId = index[token] as String
    if (!deviceId) return [issue: 'unresolved-device-hash']
    if (!labels.containsKey(deviceId)) return [issue: 'unresolved-device-hash']
    return [deviceId: deviceId]
}







List resolveWebcoreFlowDevices(List flow, String parentAppId, Map hashIndexes, Map labels) {
    (flow ?: []).each { step ->
        if (!(step instanceof Map)) return
        Map s = step as Map
        List tokens = (s.deviceTokens ?: []) as List
        if (tokens) {
            List devices = (s.devices ?: []) as List
            tokens.each { Object raw ->
                Map resolved = resolveWebcoreDeviceToken("${raw}", parentAppId, hashIndexes, labels)
                String name = resolved.deviceId ? "${labels[resolved.deviceId as String]}" : 'unresolved device'
                if (!devices.contains(name)) devices << name
            }
            s.devices = devices
        }
        
        
        
        List parts = (s.conditionParts ?: []) as List
        if (parts) {
            Map tokenNames = [:]
            
            
            
            Closure collectTokens
            collectTokens = { List list ->
                list.each { Object raw ->
                    if (!(raw instanceof Map)) return
                    Map part = raw as Map
                    if (part.group) { collectTokens((part.parts ?: []) as List); return }
                    ((part.deviceTokens ?: []) as List).each { Object token ->
                        String key = "${token}"
                        if (tokenNames.containsKey(key)) return
                        Map resolved = resolveWebcoreDeviceToken(key, parentAppId, hashIndexes, labels)
                        if (resolved.deviceId) tokenNames[key] = "${labels[resolved.deviceId as String]}"
                    }
                }
            }
            collectTokens(parts)
            String text = webcoreFlowConditionText(parts, "${s.conditionJoiner ?: 'and'}", tokenNames)
            
            
            if (text) {
                if (s.ctrl) s.cond = text
                else { s.label = text; s.cond = '' }
            }
        }
    }
    return flow ?: []
}

Map buildGraph() {
    Map labels = (state.deviceLabels ?: [:]) as Map
    Map deviceCaps = (state.deviceCapabilities ?: [:]) as Map
    Map deviceTypes = (state.deviceTypes ?: [:]) as Map
    Set disabledDevices = (state.deviceDisabled ?: []) as Set
    Map iconOverrides = (state.deviceIconOverrides ?: [:]) as Map
    Map iconNotes = (state.deviceIconNotes ?: [:]) as Map
    Map appInfo = (state.appInfo ?: [:]) as Map
    
    
    
    Map webcoreDeviceHashIndexes = buildWebcoreDeviceHashIndexes(appInfo)

    Map<String, Map> nodes = [:]
    List<Map> edges = []
    
    
    Set<String> seen = new LinkedHashSet<>()
    Map flows = [:]
    Map nameCache = [:]
    Map priorFlows = ((state.graph ?: [:]) as Map).flows as Map ?: [:]

    Map hubVarInventory = (state.hubVariableInventory ?: [:]) as Map
    Map hubVarInventoryVars = (hubVarInventory.variables ?: [:]) as Map

    
    
    
    
    
    
    
    
    Map ruleVariables = [:]
    appInfo.each { String appId, info ->
        if (!(info instanceof Map)) return
        Map appMap = info as Map
        
        
        
        
        
        
        
        
        
        String classifyAppNodeId = "a${appId}"
        if ("${appMap.type}".startsWith('Rule-')) {
            Map classified = classifyRuleVariableReferences(
                (appMap.hubVarWrites ?: []) as List,
                (appMap.hubVarReads ?: []) as List,
                (appMap.localVariables ?: []) as List,
                hubVarInventoryVars,
                classifyAppNodeId
            )
            ruleVariables[classifyAppNodeId] = [
                localVariables: (appMap.localVariables ?: []) as List,
                variableReferences: classified.variableReferences,
                nonResolvedVariableReferences: classified.nonResolvedVariableReferences,
            ]
        } else if ("${appMap.type ?: ''}".trim() == 'webCoRE Piston') {
            
            
            
            
            
            
            
            
            
            
            List localDefs = ((appMap.webcoreLocalVariableDefinitions ?: []) as List).collect { Map d ->
                [
                    identity: "${classifyAppNodeId}:${d.name}",
                    name: d.name,
                    variableType: null,
                    engineVariableType: d.engineVariableType,
                    engine: 'webCoRE',
                ]
            }
            Map identityByName = [:]
            localDefs.each { Map d -> identityByName[d.name as String] = d.identity }
            List refs = []
            ((appMap.webcoreLocalVariableWrites ?: []) as List).each { Object rawName ->
                String identity = identityByName["${rawName}"]
                if (identity) refs << [scope: 'local', operation: 'write', localIdentity: identity, name: "${rawName}", usageRole: null]
            }
            ((appMap.webcoreLocalVariableReads ?: []) as List).each { Object rawName ->
                String identity = identityByName["${rawName}"]
                if (identity) refs << [scope: 'local', operation: 'read', localIdentity: identity, name: "${rawName}", usageRole: 'unknown-read']
            }
            ruleVariables[classifyAppNodeId] = [
                localVariables: localDefs,
                variableReferences: refs,
                nonResolvedVariableReferences: [],
            ]
        }
    }

    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    List unresolvedHubVarReferences = []
    List webcoreVariableDecodeIssues = []
    
    
    
    
    
    
    
    Map<String, String> webcoreDeviceRelationshipCoverage = [:]
    List webcoreDeviceRelationshipIssues = []
    ruleVariables.each { String ruleAppNodeId, Map rv ->
        ((rv.nonResolvedVariableReferences ?: []) as List).each { Map r ->
            if (r.status == 'unresolved' && r.candidateScopes == ['hub']) {
                unresolvedHubVarReferences << [name: (r.canonicalName ?: r.name), appId: ruleAppNodeId, kind: r.operation]
            }
        }
    }
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
            
            
            
            
            
            
            
            
            
            
            
            
            String devNodeId = "d${connDevId}"
            boolean discovered = labels.containsKey(connDevId)
            if (!discovered && !nodes[devNodeId]) {
                nodes[devNodeId] = nodeEntry(devNodeId, "${varName} Connector" as String, 'device')
                
                
                
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
        String normalizedAppType = (appMap.type ?: '').toString().trim()
        boolean webcoreDeviceRelationshipsSuppressed = normalizedAppType == 'webCoRE' || normalizedAppType == 'webCoRE Piston'
        
        
        
        
        Map roles = webcoreDeviceRelationshipsSuppressed ? [:] : ((appMap.roles ?: [:]) as Map)
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        boolean unreadable = appMap.error != null
        
        
        
        
        
        
        
        
        
        
        
        boolean hasRuleVarRelationship = ((ruleVariables["a${appId}"]?.variableReferences ?: []) as List)
            .any { Map r -> r.scope == 'hub' }
        List webcoreRelationshipNames = []
        webcoreRelationshipNames.addAll((appMap.webcoreHubVarReads ?: []) as List)
        webcoreRelationshipNames.addAll((appMap.webcoreHubVarWrites ?: []) as List)
        webcoreRelationshipNames.addAll((appMap.webcoreHubVarUses ?: []) as List)
        boolean hasWebcoreVarRelationship = webcoreRelationshipNames.any { Object rawName ->
            String canonical = canonicalHubVariableName("${rawName}", hubVarInventoryVars)
            canonical && hubVarInventoryVars.containsKey(canonical)
        }
        boolean hasVarRelationship = hasRuleVarRelationship || hasWebcoreVarRelationship
        boolean webcoreVariableDecodeFailed = appMap.webcoreVariableDecodeStatus == 'error'
        
        
        
        
        
        
        
        
        
        boolean webcorePistonHasDeviceEvidence = normalizedAppType == 'webCoRE Piston' && (
            !((appMap.webcoreDeviceReads ?: []) as List).isEmpty() ||
            !((appMap.webcoreDeviceActions ?: []) as List).isEmpty() ||
            !((appMap.webcoreUnsupportedDeviceRefs ?: [:]) as Map).isEmpty()
        )
        boolean webcorePistonDeviceRelationshipsUndecoded = normalizedAppType == 'webCoRE Piston' &&
            (webcoreVariableDecodeFailed || webcorePistonHasDeviceEvidence)
        boolean inert = !unreadable && !webcoreVariableDecodeFailed && !webcorePistonDeviceRelationshipsUndecoded && !roles && !(appMap.ruleLinks ?: []) && !(appMap.endpoints ?: []) && !hasVarRelationship
        String appNodeId = "a${appId}"
        
        
        
        
        
        
        boolean isSelfFamily = "${appMap.type}".startsWith(APP_FAMILY)
        String subtitle = unreadable ? 'could not be read' :
            (inert ? (isSelfFamily ? 'reads the whole hub, drives nothing' : inertReason(appMap.inert as Map, appInfo, appMap.parent as String)) : (appMap.type as String))
        
        
        
        
        String statusWord = appMap.disabled ? 'Disabled' : (appMap.paused ? 'Paused' : null)
        
        
        
        
        
        nodes[appNodeId] = nodeEntry(appNodeId, appMap.label as String, 'app', subtitle,
                                      appMap.drawLabel as String, statusWord, false)
        
        
        
        
        
        
        
        nodes[appNodeId].appType = "${appMap.type}"
        
        
        
        if (appMap.namespace) nodes[appNodeId].namespace = "${appMap.namespace}"
        if (appMap.inactive) nodes[appNodeId].inactive = true
        if (appMap.disabled) nodes[appNodeId].disabled = true
        if (appMap.paused) nodes[appNodeId].paused = true
        if (appMap.broken) nodes[appNodeId].broken = true
        if (appMap.webcoreVariableDecodeStatus) {
            nodes[appNodeId].webcoreVariableDecodeStatus = "${appMap.webcoreVariableDecodeStatus}"
        }
        if (webcoreDeviceRelationshipsSuppressed) {
            nodes[appNodeId].webcoreDeviceRelationshipsSuppressed = true
        }
        if (webcoreVariableDecodeFailed) {
            String errorCode = "${appMap.webcoreVariableDecodeError ?: 'decode-failed'}"
            nodes[appNodeId].webcoreVariableDecodeError = errorCode
            webcoreVariableDecodeIssues << [appId: appNodeId, error: errorCode]
        }
        if (unreadable) {
            nodes[appNodeId].unreadable = true
            nodes[appNodeId].reason = subtitle
            nodes[appNodeId].errorDetail = "${appMap.error}"
        }
        if (inert) {
            nodes[appNodeId].inert = true
            
            
            
            
            nodes[appNodeId].reason = subtitle
            
            
            
            
            
            
            
            
            List kidIds = []
            appInfo.each { String otherId, other ->
                if (!(other instanceof Map)) return
                if ("${(other as Map).parent}" == appId) kidIds << "a${otherId}"
            }
            if (kidIds) nodes[appNodeId].kids = kidIds
            Map inertFacts = (appMap.inert ?: [:]) as Map
            
            
            
            
            if ((inertFacts.kids ?: 0) as Integer) nodes[appNodeId].holds = inertFacts.kids
            if ((inertFacts.sched ?: 0) as Integer) {
                nodes[appNodeId].sched = inertFacts.sched
                
                
                
                if (inertFacts.schedJobs) nodes[appNodeId].schedJobs = inertFacts.schedJobs
            }
            if ((inertFacts.subs ?: 0) as Integer) nodes[appNodeId].subs = inertFacts.subs
            if ((inertFacts.devs ?: 0) as Integer) nodes[appNodeId].devs = inertFacts.devs
        }
        
        
        
        
        
        
        
        
        
        
        
        
        
        if (appMap.parent) nodes[appNodeId].parent = "a${appMap.parent}"
        
        
        
        if (appMap.flow) {
            List resolvedFlow = resolveFlowTargets(appMap.flow as List, appInfo, nameCache)
            
            
            if ("${appMap.type ?: ''}" == 'webCoRE Piston') {
                resolvedFlow = resolveWebcoreFlowDevices(resolvedFlow, "${appMap.parent ?: ''}",
                        webcoreDeviceHashIndexes, labels)
            }
            flows[appNodeId] = resolvedFlow
        }
        else if (priorFlows[appNodeId]) flows[appNodeId] = priorFlows[appNodeId]

        roles.each { String devId, devRoles ->
            String devNodeId = "d${devId}"
            if (!nodes[devNodeId]) {
                String devLabel = (labels[devId] ?: "Device ${devId}") as String
                boolean devDisabled = disabledDevices.contains(devId)
                nodes[devNodeId] = nodeEntry(devNodeId, devLabel, 'device', null, null, devDisabled ? 'Disabled' : null)
                if (devDisabled) nodes[devNodeId].disabled = true
                
                
                
                nodes[devNodeId].icon = (iconOverrides[devId] as String) ?:
                    autoDetectIconKeyForDevice((labels[devId] ?: '') as String, deviceCaps[devId] as List,
                                               deviceTypes[devId] as String)
                
                
                
                String note = (iconNotes[devId] as String)?.trim()
                if (note) nodes[devNodeId].title = "${nodes[devNodeId].title} (noted: ${note})"
            }
            List statefulDevices = (appMap.stateful ?: []) as List
            List deadConstraints = (appMap.unusedConstraints ?: []) as List
            (devRoles as List).each { String role ->
                String key = "${appNodeId}|${devNodeId}|${role}"
                if (seen.contains(key)) return
                seen << key
                Map edge = [from: appNodeId, to: devNodeId, kind: role]
                if (role == 'action' && statefulDevices.contains(devId)) edge.stateful = true
                
                
                if (role == 'constraint' && deadConstraints.contains(devId)) edge.unused = true
                edges << edge
            }
        }

        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        Map writesByField = [:]
        (appMap.hubVarWrites ?: []).each { Map w -> if (w.field) writesByField["${w.field}"] = w }
        List classifiedHubRefs = ((ruleVariables[appNodeId]?.variableReferences ?: []) as List)
            .findAll { Map r -> r.scope == 'hub' }

        classifiedHubRefs.findAll { it.operation == 'write' }.each { Map r ->
            String varName = r.canonicalName as String
            if (!varName) return
            String varNodeId = "v${varName}"
            if (!nodes[varNodeId]) {
                
                
                
                
                
                nodes[varNodeId] = nodeEntry(varNodeId, varName, 'hubVariable')
                nodes[varNodeId].identitySource = 'hub-inventory'
            }
            String key = "${appNodeId}|${varNodeId}|write"
            if (seen.contains(key)) return
            seen << key
            Map original = (writesByField["${r.evidence?.field}"] as Map) ?: [:]
            Map edge = [from: appNodeId, to: varNodeId, kind: 'write']
            if (original.sourceDevice && original.sourceAttr) {
                edge.detail = "from ${original.sourceDevice}.${original.sourceAttr}"
            }
            
            
            
            if (original.sourceDeviceId && original.sourceAttr && labels.containsKey("${original.sourceDeviceId}")) {
                edge.writeSource = [kind: 'deviceAttribute', deviceId: "${original.sourceDeviceId}", attribute: "${original.sourceAttr}"]
            }
            edges << edge
        }

        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        Map readsByVarNode = [:]
        classifiedHubRefs.findAll { it.operation != 'write' }.each { Map r ->
            String varName = r.canonicalName as String
            if (!varName) return
            (readsByVarNode["v${varName}"] = (readsByVarNode["v${varName}"] ?: [])) << r
        }
        readsByVarNode.each { String varNodeId, List refs ->
            if (!nodes[varNodeId]) {
                nodes[varNodeId] = nodeEntry(varNodeId, (refs[0] as Map).canonicalName as String, 'hubVariable')
                nodes[varNodeId].identitySource = 'hub-inventory'
            }
            String key = "${appNodeId}|${varNodeId}|read"
            if (seen.contains(key)) return
            seen << key
            
            
            
            
            
            
            
            
            
            
            
            
            
            
            Set<String> distinctUsageRoles = refs.collect { (it as Map).usageRole as String } as Set<String>
            String usageRole = (distinctUsageRoles.size() == 1 && distinctUsageRoles.first() != null) ? distinctUsageRoles.first() : 'unknown-read'
            edges << [from: appNodeId, to: varNodeId, kind: 'read', usageRole: usageRole]
        }

        
        
        
        
        
        
        ((appMap.webcoreHubVarWrites ?: []) as List).each { Object rawName ->
            String originalName = "${rawName}"
            String canonicalName = canonicalHubVariableName(originalName, hubVarInventoryVars)
            if (!canonicalName || !hubVarInventoryVars.containsKey(canonicalName)) {
                unresolvedHubVarReferences << [name: originalName, appId: appNodeId, kind: 'write', engine: 'webCoRE']
                return
            }
            String varNodeId = "v${canonicalName}"
            String key = "${appNodeId}|${varNodeId}|write"
            if (seen.contains(key)) return
            seen << key
            edges << [from: appNodeId, to: varNodeId, kind: 'write']
        }
        ((appMap.webcoreHubVarReads ?: []) as List).each { Object rawName ->
            String originalName = "${rawName}"
            String canonicalName = canonicalHubVariableName(originalName, hubVarInventoryVars)
            if (!canonicalName || !hubVarInventoryVars.containsKey(canonicalName)) {
                unresolvedHubVarReferences << [name: originalName, appId: appNodeId, kind: 'read', engine: 'webCoRE']
                return
            }
            String varNodeId = "v${canonicalName}"
            String key = "${appNodeId}|${varNodeId}|read"
            if (seen.contains(key)) return
            seen << key
            edges << [from: appNodeId, to: varNodeId, kind: 'read', usageRole: 'unknown-read']
        }
        ((appMap.webcoreHubVarUses ?: []) as List).each { Object rawName ->
            String originalName = "${rawName}"
            String canonicalName = canonicalHubVariableName(originalName, hubVarInventoryVars)
            if (!canonicalName || !hubVarInventoryVars.containsKey(canonicalName)) {
                unresolvedHubVarReferences << [name: originalName, appId: appNodeId, kind: 'usesVar', engine: 'webCoRE']
                return
            }
            String varNodeId = "v${canonicalName}"
            String key = "${appNodeId}|${varNodeId}|usesVar"
            if (seen.contains(key)) return
            seen << key
            edges << [from: appNodeId, to: varNodeId, kind: 'usesVar', direction: 'unknown']
        }

        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        if ("${appMap.type ?: ''}".trim() == 'webCoRE Piston') {
            String parentAppId = appMap.parent as String
            List<String> deviceIssueCodes = []
            int deviceOperandCount = 0
            ((appMap.webcoreDeviceReads ?: []) as List).each { Map ref ->
                deviceOperandCount++
                Map resolved = resolveWebcoreDeviceToken(ref.token as String, parentAppId, webcoreDeviceHashIndexes, labels)
                if (resolved.issue) { deviceIssueCodes << (resolved.issue as String); return }
                String devNodeId = "d${resolved.deviceId}"
                
                
                
                
                
                
                String readKind = "${ref.role ?: ''}" == 'trigger' ? 'trigger'
                        : ("${ref.role ?: ''}" == 'constraint' ? 'constraint' : 'deviceRead')
                String key = "${appNodeId}|${devNodeId}|${readKind}"
                if (seen.contains(key)) return
                seen << key
                
                
                
                
                
                edges << [from: appNodeId, to: devNodeId, kind: readKind, attribute: ref.attribute]
            }
            ((appMap.webcoreDeviceActions ?: []) as List).each { Map ref ->
                deviceOperandCount++
                Map resolved = resolveWebcoreDeviceToken(ref.token as String, parentAppId, webcoreDeviceHashIndexes, labels)
                if (resolved.issue) { deviceIssueCodes << (resolved.issue as String); return }
                String devNodeId = "d${resolved.deviceId}"
                String key = "${appNodeId}|${devNodeId}|action"
                if (seen.contains(key)) return
                seen << key
                
                
                
                
                edges << [from: appNodeId, to: devNodeId, kind: 'action', stateful: null, commands: ref.commands]
            }
            
            
            
            ((appMap.webcoreUnsupportedDeviceRefs ?: [:]) as Map).each { String code, Object count ->
                int howMany = (count ?: 0) as Integer
                deviceOperandCount += howMany
                howMany.times { deviceIssueCodes << code }
            }
            String coverage
            if (appMap.webcoreVariableDecodeStatus == 'error') {
                coverage = 'error'
            } else if (deviceOperandCount == 0) {
                coverage = 'none'
            } else if (deviceIssueCodes) {
                coverage = 'partial'
            } else {
                coverage = 'complete'
            }
            webcoreDeviceRelationshipCoverage[appNodeId] = coverage
            nodes[appNodeId].webcoreDeviceRelationshipCoverage = coverage
            if (deviceIssueCodes) {
                webcoreDeviceRelationshipIssues << [appId: appNodeId, codes: deviceIssueCodes]
                nodes[appNodeId].webcoreDeviceRelationshipIssueCodes = deviceIssueCodes
            }
        }

        
        
        
        
        
        
        
        
        
        
        
        List localDefs = (ruleVariables[appNodeId]?.localVariables ?: []) as List
        localDefs.each { Map d ->
            String identity = d.identity as String
            if (!identity) return
            if (!nodes[identity]) {
                String ownerLabel = (nodes[appNodeId]?.title ?: appNodeId) as String
                nodes[identity] = nodeEntry(identity, d.name as String, 'localVariable', "Local Variable in ${ownerLabel}")
                nodes[identity].ownerAppId = appNodeId
                nodes[identity].variableType = d.variableType
                
                
                
                
                if (d.engineVariableType) nodes[identity].engineVariableType = d.engineVariableType
            }
        }

        
        
        
        
        
        
        
        List classifiedLocalRefs = ((ruleVariables[appNodeId]?.variableReferences ?: []) as List)
            .findAll { Map r -> r.scope == 'local' }

        classifiedLocalRefs.findAll { it.operation == 'write' }.each { Map r ->
            String identity = r.localIdentity as String
            if (!identity || !nodes[identity]) return
            String key = "${appNodeId}|${identity}|write"
            if (seen.contains(key)) return
            seen << key
            edges << [from: appNodeId, to: identity, kind: 'write']
        }

        Map localReadsByNode = [:]
        classifiedLocalRefs.findAll { it.operation != 'write' }.each { Map r ->
            String identity = r.localIdentity as String
            if (!identity || !nodes[identity]) return
            (localReadsByNode[identity] = (localReadsByNode[identity] ?: [])) << r
        }
        localReadsByNode.each { String identity, List refs ->
            String key = "${appNodeId}|${identity}|read"
            if (seen.contains(key)) return
            seen << key
            Set<String> distinctLocalUsageRoles = refs.collect { (it as Map).usageRole as String } as Set<String>
            String localUsageRole = (distinctLocalUsageRoles.size() == 1 && distinctLocalUsageRoles.first() != null) ? distinctLocalUsageRoles.first() : 'unknown-read'
            edges << [from: appNodeId, to: identity, kind: 'read', usageRole: localUsageRole]
        }
    }

    
    
    
    
    
    
    Set<String> referencedLocalIds = [] as Set
    edges.each { Map e ->
        String toId = e.to as String
        if (nodes[toId] && nodes[toId].group == 'localVariable') referencedLocalIds << toId
    }
    nodes.each { String nid, Map n ->
        if (n.group == 'localVariable' && !referencedLocalIds.contains(nid)) {
            n.unreferencedLocal = true
        }
    }

    
    
    
    
    
    
    labels.each { String devId, label ->
        String devNodeId = "d${devId}"
        if (nodes[devNodeId]) return

        boolean devDisabled = disabledDevices.contains(devId)
        String devLabel = (label ?: "Device ${devId}") as String
        nodes[devNodeId] = nodeEntry(devNodeId, devLabel, 'device', null, null, devDisabled ? 'Disabled' : null)
        if (devDisabled) nodes[devNodeId].disabled = true
        nodes[devNodeId].icon = (iconOverrides[devId] as String) ?:
            autoDetectIconKeyForDevice((label ?: '') as String, deviceCaps[devId] as List,
                                       deviceTypes[devId] as String)
        String note = (iconNotes[devId] as String)?.trim()
        if (note) nodes[devNodeId].title = "${nodes[devNodeId].title} (noted: ${note})"
    }

    
    
    
    
    
    
    buildHasComponentEdges(nodes.keySet(), (state.deviceParents ?: [:]) as Map).each { Map hc ->
        String key = "${hc.from}|${hc.to}|${hc.kind}"
        if (seen.contains(key)) return
        seen << key
        edges << hc
    }

    
    
    
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
                
                
                
                
                
                Map target = appInfo[targetId] as Map
                
                
                Map named = linkedRuleName(targetId, appInfo, nameCache)
                
                
                
                
                
                String subtitle = named.missing ? null : (target?.type ?: 'not scanned') as String
                nodes[toId] = nodeEntry(toId, named.label as String, 'app', subtitle, named.draw as String)
                if (!target) nodes[toId].unscanned = true
                
                
                
                
                
                if (named.missing) nodes[toId].missing = true
            }

            String key = "${fromId}|${toId}|${kind}"
            if (seen.contains(key)) return
            seen << key
            edges << [from: fromId, to: toId, kind: kind]
        }
    }

    
    
    
    
    
    
    
    
    
    
    
    List externals = []
    List userRows = userRegistry()
    List userTypes = classifiedTypes()
    List reviewedRows = reviewedExternalDefaults()
    List reviewedTypes = reviewedRows.collect { Object row -> "${(row as Map).type}" }.unique()
    registryMatches().each { row ->
        if (!(row instanceof Map)) return
        String t = "${(row as Map).type}"
        if (!userTypes.contains(t) && !reviewedTypes.contains(t)) externals << row
    }
    reviewedRows.each { row ->
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

            
            
            
            
            String nodeId = "x${host.toLowerCase().replaceAll('[^a-z0-9]', '')}${Integer.toHexString(host.hashCode())}"
            if (!nodes[nodeId]) {
                
                
                
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

    
    
    
    correctFlowVariableLabels(flows, ruleVariables)

    return [nodes: nodes.values().toList(), edges: edges, flows: flows,
            hubVariableUnresolvedReferences: unresolvedHubVarReferences,
            webcoreVariableDecodeIssues: webcoreVariableDecodeIssues,
            webcoreDeviceRelationshipIssues: webcoreDeviceRelationshipIssues,
            hubVariableConnectorCount: hubVarConnectorCount,
            
            
            
            
            
            ruleVariables: ruleVariables]
}







void rebuildStoredGraph() {
    state.graph = buildGraph()
    atomicState.graphVersion = GRAPH_SCHEMA
    
    
    Long rebuiltAt = now()
    state.graphCommittedAtLocal = rebuiltAt
    atomicState.graphCommittedAt = rebuiltAt
}

















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




@Field static final String EXTERNAL_NONE = '__none__'







@Field static final Map BUILTIN_INTERNAL_ONLY = [
    'Rule Machine'             : 'Hub-local rule engine.',
    'Basic Rules'              : 'Hub-local rule engine.',
    'Visual Rules Builder'     : 'Hub-local rule engine.',
    'Button Controllers'       : 'Hub-local button handling.',
    'Basic Button Controllers' : 'Hub-local button handling.',
    'Groups and Scenes'        : 'Hub-local device grouping.',
    'Notifications'            : 'Sends to notification devices on this hub.',
    'Export/Import/Clone'      : 'Hub-local app management.',
]



@Field static final Map REVIEWED_INTERNAL_ONLY = [
    'MCP Rule Server'                    : 'Runs on this hub.',
    'Presence Manager'                   : 'Uses participating devices already represented on this hub.',
    'Rebooter'                           : 'Runs on this hub.',
    'Rule References Rule Table'         : 'Runs on this hub.',
    'AI (MCP) Connector Integration'     : 'Runs on this hub.',
    'Averaging Master'                   : 'Uses participating devices already represented on this hub.',
    'Critical Device Monitor'            : 'Uses participating devices already represented on this hub.',
    'Hub Diagnostics'                    : 'Runs on this hub.',
    'Hubitat® Dashboard'                 : 'Runs on this hub.',
    'Kasa Integration'                   : 'Assessed for this deployment as hub-only.',
    'Maker API'                          : 'Assessed for this deployment as hub-only.',
    'Notification Proxy'                 : 'Runs on this hub.',
    'Zigbee Map 3.0.4'                   : 'Runs on this hub.',
    'mDNS Device Discovery'              : 'Runs on this hub.',
]



@Field static final List REVIEWED_EXTERNAL_DEFAULTS = [
    [type: 'CoCoHue - Hue Bridge Integration', name: 'Hue Bridge',        kind: 'local_bridge', crit: 'RUNTIME'],
    [type: 'LIFX Light Manager',               name: 'LIFX Cloud',        kind: 'internet',     crit: 'MANAGEMENT'],
    [type: 'Sensibo Integration',              name: 'Sensibo Cloud',     kind: 'internet',     crit: 'RUNTIME'],
    [type: 'Tapo Integration',                 name: 'Tapo Cloud',        kind: 'internet',     crit: 'RUNTIME'],
    [type: 'BOM Weather Alerts',               name: 'Weather Services',  kind: 'internet',     crit: 'RUNTIME'],
    [type: 'Chromecast Integration',           name: 'Google Chromecast', kind: 'local_device', crit: 'RUNTIME'],
    [type: 'Google Home',                      name: 'Google Home',       kind: 'platform',     crit: 'RUNTIME'],
    [type: 'Hubitat Package Manager',          name: 'GitHub',            kind: 'internet',     crit: 'MANAGEMENT'],
    [type: 'Meross MSG100 Garage Door Setup',  name: 'Meross Cloud',      kind: 'internet',     crit: 'SETUP_ONLY'],
]























@Field static final String REGISTRY_URL =
    'https://raw.githubusercontent.com/GordonThelander/HPM_Manifest_Crawl/main/hubitat_automation_map_app_integration_registry_slim.json'



@Field static final Map REGISTRY_CLASS_TO_KIND = [
    LOCAL_BRIDGE      : 'local_bridge',
    LOCAL_DEVICE      : 'local_device',
    LOCAL_SERVICE     : 'infra',
    INFRASTRUCTURE    : 'infra',
    EXTERNAL_PLATFORM : 'platform',
    EXTERNAL_SERVICE  : 'internet',
    UNKNOWN_EXTERNAL  : 'internet',
]














@Field static final List<String> REGISTRY_EVALUABLE_FIELDS = ['appName']
























@Field static final List<String> LOOPBACK_HOSTS = ['localhost', '127.0.0.1', '0.0.0.0', '[::1]', '::1']



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
        
        
        int portColon = s.indexOf(':', bracket)
        if (portColon > bracket) s = s.substring(0, portColon)
    } else {
        int colon = s.lastIndexOf(':')
        if (colon > 0) s = s.substring(0, colon)
    }
    s = s.trim().toLowerCase()
    return s ?: null
}


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

List reviewedExternalDefaults() {
    return REVIEWED_EXTERNAL_DEFAULTS.collect { Object row -> new LinkedHashMap(row as Map) }
}

Map reviewedInternalOnly() {
    Map out = new LinkedHashMap(BUILTIN_INTERNAL_ONLY)
    out.putAll(REVIEWED_INTERNAL_ONLY)
    return out
}

List registryMatches() {
    return (state.registryMatches ?: []) as List
}




boolean registryRuleMatches(String op, String value, String appType) {
    String n = value?.trim()?.toLowerCase()
    String h = appType?.trim()?.toLowerCase()
    if (!n || !h) return false
    if (op == 'equals') return h == n
    if (op == 'contains') return h.contains(n)
    return false
}









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




List discoveredAppTypes() {
    List types = []
    ((state.appInfo ?: [:]) as Map).each { String appId, info ->
        if (!(info instanceof Map)) return
        String t = "${(info as Map).type}"
        if (!t || t == 'null') return
        
        
        
        if (t.startsWith(APP_FAMILY)) return
        if (!types.contains(t)) types << t
    }
    return types.sort()
}







Map appTypeIdentities() {
    Map info = (state.appInfo ?: [:]) as Map
    Map byIdentity = [:]
    info.each { String appId, v ->
        if (!(v instanceof Map)) return
        Map m = v as Map
        String t = "${m.type}"
        if (!t || t == 'null') return
        if (t.startsWith(APP_FAMILY)) return

        
        
        
        
        
        Map cur = m
        Set seen = ["${appId}" as String]
        int hops = 0
        while (cur?.parent && hops < 12) {
            String pid = "${cur.parent}"
            if (seen.contains(pid)) break
            seen << pid
            Object p = info[pid]
            if (!(p instanceof Map)) break
            cur = p as Map
            hops++
        }
        String rootType = "${cur?.type}"
        if (!rootType || rootType == 'null') rootType = t

        
        
        
        
        String ns = m.namespace ? "${m.namespace}" : ''
        String key = "${t}||${ns}"
        Map e = byIdentity[key] as Map
        if (e == null) {
            e = [type: t, namespace: (ns ?: null), count: 0, rootType: t, isRoot: true]
            byIdentity[key] = e
        }
        e.count = ((e.count ?: 0) as Integer) + 1
        if (rootType != t) {
            e.isRoot = false
            e.rootType = rootType
        }
    }

    
    
    
    
    
    Map out = [:]
    byIdentity.each { String key, Object v ->
        Map e = v as Map
        Map agg = out[e.type] as Map
        if (agg == null) {
            agg = [type: e.type, namespace: null, count: 0, rootType: e.rootType, isRoot: false, identities: []]
            out[e.type] = agg
        }
        agg.count = ((agg.count ?: 0) as Integer) + ((e.count ?: 0) as Integer)
        (agg.identities as List) << e
        if (!agg.namespace && e.namespace) agg.namespace = e.namespace
        
        
        
        if (e.isRoot) {
            agg.isRoot = true
            agg.rootType = e.type
        } else if (!agg.isRoot) {
            agg.rootType = e.rootType
        }
    }
    return out
}







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








String getLocalOrigin() {
    return (fullLocalApiServerUrl =~ ORIGIN_PATTERN).findAll()[0][1]
}

String getCloudURL(String fileName) {
    return "${fullApiServerUrl}/${fileName}?access_token=${state.accessToken}"
}





mappings {
    path('/automation-map.html') { action: [ GET: 'renderMapMapping' ] }
    path('/scan') { action: [ GET: 'scanMapping' ] }
    path('/scan-status') { action: [ GET: 'scanStatusMapping' ] }
    path('/externals') { action: [ GET: 'externalsGetMapping', POST: 'externalsSaveMapping' ] }
    path('/icon-overrides') { action: [ GET: 'iconOverridesGetMapping', POST: 'iconOverridesSaveMapping' ] }
    path('/webcore-decode-coverage') { action: [ GET: 'webcoreDecodeCoverageMapping' ] }
}





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
    
    
    
    
    
    runIn(1, 'rebuildStoredGraph')
    if (diagOn()) log.info "${app.label}: saved ${incoming.size()} external system declaration(s)"
    return render(status: 200, contentType: 'application/json', data: externalsJson())
}

String externalsJson() {
    List types = discoveredAppTypes()
    List classified = classifiedTypes()
    List reviewed = reviewedExternalDefaults()
    List reviewedTypes = reviewed.collect { Object row -> "${(row as Map).type}" }.unique()
    Map internalOnly = reviewedInternalOnly()
    List reg = registryMatches()
    List regTypes = []
    reg.each { r -> String t = "${(r as Map).type}"; if (t && !regTypes.contains(t)) regTypes << t }

    Map out = [
        ok: true,
        kinds: EXTERNAL_KINDS,
        criticality: EXTERNAL_CRITICALITY,
        noneMarker: EXTERNAL_NONE,
        appTypes: types,
        
        
        
        appTypeInfo: appTypeIdentities(),
        builtinInternal: internalOnly,
        reviewed: reviewed,
        
        
        unclassified: types.findAll {
            !classified.contains(it) && !reviewedTypes.contains(it) &&
                !regTypes.contains(it) && !internalOnly.containsKey(it)
        },
        entries: userRegistry(),
        registry: reg,
        registryMeta: (state.registryMeta ?: [:]),
    ]
    return groovy.json.JsonOutput.toJson(out)
}





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
            
            
            
            if (ICON_KEYS.contains(iconKey)) incoming[devId] = iconKey
        }
        Map notes = payload.notes as Map
        (notes ?: [:]).each { k, v ->
            String devId = "${k}"
            
            
            
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
    
    
    
    runIn(1, 'rebuildStoredGraph')
    if (diagOn()) log.info "${app.label}: saved ${incoming.size()} device icon override(s), ${incomingNotes.size()} note(s)"
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












Map scanMapping() {
    
    
    
    
    
    
    if (diagOn()) log.info "${app.label}: /scan endpoint reached"
    try {
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        boolean casLost = false
        if (state.scanRunning) {
            if (diagOn()) log.info "${app.label}: /scan reached while a scan is already running, not restarting"
        } else {
            Map result = startScan()
            if (!result.acquired) {
                casLost = true
                if (diagOn()) log.info "${app.label}: /scan reached but another start already owns this instance, not restarting"
            }
        }
        
        
        
        
        
        
        
        
        
        return render(status: 200, contentType: 'application/json', data: scanStatusJson(casLost))
    } catch (Exception ex) {
        log.warn "${app.label}: scanMapping failed to start a scan: ${ex.message}"
        
        
        
        
        
        
        return render(status: 200, contentType: 'application/json',
            data: JsonOutput.toJson([ok: false, error: "${ex.class.simpleName}: ${ex.message}"]))
    }
}

Map scanStatusMapping() {
    
    
    
    
    
    
    
    
    
    clearAbandonedScan()
    return render(status: 200, contentType: 'application/json', data: scanStatusJson())
}






String scanStatusJson(boolean forceRunning = false) {
    migrateGraphVersionIfNeeded()
    selfHealGraphIfNeeded()
    
    
    
    
    
    
    ConcurrentHashMap liveScan = null
    if (state.scanPhase == 'devices') liveScan = liveDeviceScan()
    else if (state.scanPhase == 'apps') liveScan = liveAppScan()
    int queued = liveScan ? (liveScan.pending as ConcurrentLinkedQueue).size() : (state.scanQueue ?: []).size()
    
    
    
    
    
    
    
    
    
    
    def done = liveScan ? (liveScan.processed as AtomicInteger).get() : (state.scanDone ?: state.scanTotal)
    def heartbeat = liveScan ? (liveScan.lastProgressAt as Long) : state.scanHeartbeat
    return JsonOutput.toJson([
        running: forceRunning || scanEffectivelyActive(),
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
        graphVersion: atomicState.graphVersion,
    ])
}

Map renderMapMapping() {
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    clearAbandonedScan()
    if (scanEffectivelyActive()) {
        return render(
            status: 200,
            contentType: 'text/html',
            data: """<!doctype html><html><head><meta charset="utf-8"><title>Automation Map - scan in progress</title></head>
<body style="background:#062733; color:#eee; font-family:ui-sans-serif, system-ui, sans-serif; padding:2em; line-height:1.5">
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
<body style="background:#062733; color:#eee; font-family:ui-sans-serif, system-ui, sans-serif; padding:2em; line-height:1.5">
<h2>This map is out of date</h2>
<p>It was saved in a format this release no longer reads.
Relationship types have changed since then, so the graph would render without role colours.</p>
<p>Open the Automation Map app and run <b>Scan relationships now</b>, then reload this page.</p>
</body></html>"""
       )
    }
    return render(status: 200, contentType: 'text/html', data: buildMapHtml())
}











String jsonForScriptEmbed(Object obj) {
    return JsonOutput.toJson(obj).replace('<', '\\u003c')
}

String buildMapHtml() {
    Map graph = (state.graph ?: [nodes: [], edges: []]) as Map
    int deviceCount = (graph.nodes ?: []).count { it.group == 'device' }
    int appCount = (graph.nodes ?: []).count { it.group == 'app' }
    String jsonStr = jsonForScriptEmbed(graph)
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    Map hubVarInventoryMeta = (state.hubVariableInventory ?: [:]) as Map
    Map scanMeta = [
        exportSchemaVersion: 13,
        graphSchemaVersion: GRAPH_SCHEMA,
        scanHeartbeatMs: state.scanHeartbeat,
        scanError: state.scanError,
        appsUnreadable: state.appsUnreadable ?: 0,
        devicesUnreadable: ((state.deviceIdsUnreadable ?: []) as List).size(),
        
        
        
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
<!-- Never disclose the hub page URL, its private LAN origin, or OAuth-bearing
     path as referrer metadata when this page loads external libraries,
     Community Utilities data, images, fonts, audio, or opens external links.
     This changes request metadata only; it does not change any target URL,
     same-origin hub request, credential handling, or CORS behaviour. -->
<meta name="referrer" content="no-referrer">
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
  /* Mulish - matches the typeface used across gordonthelander.github.io/HPM_Manifest_Crawl/
     (Hubitat Community Utilities), per Gordon's request to bring this page's look closer to
     that one. Self-hosted from this repo rather than fetched live from Google Fonts on every
     page load - this app just removed its own telemetry driver because a call to any third
     party read as intrusive to some users, and a live Google Fonts request is the same class
     of thing even though it carries no app data. Single variable-weight WOFF2 (Latin subset
     only; this app's own UI text is English) covers every weight actually used (400/600/700/
     800) with one file - Google Fonts serves the identical file for all of them, confirmed by
     diffing the returned @font-face rules for each weight. SIL Open Font License 1.1 permits
     bundling/self-hosting freely; its one redistribution condition is carrying the licence
     text itself, not a credit line - Fonts/OFL.txt (the exact upstream file) satisfies that. */
  @font-face {
    font-family: 'Mulish';
    src: url('https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/${isDevBuild() ? 'dev' : 'main'}/Fonts/Mulish-VariableWeight-latin.woff2') format('woff2');
    font-weight: 400 800;
    font-style: normal;
    font-display: swap;
  }
  /* overflow:hidden - the page itself must never scroll. Confirmed live as
     the real cause of a panel overlapping the control rail: opening a
     modernPanel could make the page tall/wide enough to grow its own
     scrollbar, which shrinks documentElement.clientWidth by the scrollbar's
     width and shifts #controls (positioned via right:10px) left by exactly
     that amount - after sizeModernPanel() had already measured and sized
     the panel against the wider, scrollbar-free layout. Every panel already
     scrolls its own content internally via .panelBody, and #network fills
     100vh on its own - nothing here is meant to make the page itself
     taller than the viewport, so removing its ability to scroll at all
     removes the instability rather than working around it. */
  html, body { margin:0; padding:0; height:100%; overflow:hidden; background:#062733; color:#eee; font-family:'Mulish', ui-sans-serif, system-ui, sans-serif; }
  /* 6px, not a 999px pill. In this UI a pill means "clickable" - every other
     999px element is a button, select or combobox trigger - so a pill on an
     inert status readout borrowed a button's affordance and read as one. */
  /* One width for the whole left column - status bar, legend and the classic
     detail panel below them. Held in a variable so the three cannot drift
     apart. 375px, matching at the NARROW end rather than the wide one: the
     detail panel now narrows to the bars instead of the bars widening to it,
     which keeps the map's own canvas as large as possible. #flowSub's cap
     derives from this, and that cap is what drives the panel's shrink-to-fit
     width, so changing this one number moves all three together. */
  :root { --leftColWidth:375px; }
  #status { position:absolute; top:10px; left:10px; z-index:10; background:#81BC00; border:1px solid #5c8500; padding:10px 14px; border-radius:6px; font-size:0.85em; color:#121214; font-weight:600; width:var(--leftColWidth); box-sizing:border-box; text-align:center; }
  /* Fixed width, matching #status exactly (was max-width, sized to
     content) - the two need to line up regardless of viewport width, not
     just coincidentally happen to at one particular size. */
  /* Fixed width (matching #status) made rows wrap more, running the panel
     off the bottom of the screen - smaller text plus a hard max-height/
     scroll safety net so it can never do that again regardless of viewport
     height or how much the legend itself grows later. */
  /* 14px, matching #legendPanel's own explicit base - Gordon flagged the
     compact and full legend reading at two different sizes live. Both are
     now anchored to the same value rather than each picking its own. */
  #legend { position:absolute; top:55px; left:10px; z-index:10; background:rgba(0,0,0,0.55); padding:10px 14px; border-radius:14px; font-size:14px; width:var(--leftColWidth); box-sizing:border-box; max-height:calc(100vh - 70px); overflow-y:auto; }
  /* z-index:9000, not 10 - the .cb-popup fix (z-index:9000 on the popup
     itself) turned out not to be the real fix. CSS stacking is
     hierarchical: a child's z-index only wins WITHIN its own ancestor's
     stacking context, never against a sibling context. #controls and any
     .modernPanel are siblings, each establishing their own stacking
     context (both position:absolute with a real z-index) - so
     .cb-popup's 9000 was only ever competing against other children of
     #controls, never against panels at all, and #controls' own old
     z-index:10 lost to any panel (panelTopZ starts at 20+) regardless of
     what number the popup inside it claimed. Confirmed live before this
     was written: raising #controls itself, not the popup, is what
     actually put the popup on top - sampled 12 points across the popup's
     full width where a panel overlapped it, and only every element
     inside the popup rendered topmost once #controls' own z-index was
     raised. */
  #controls { position:absolute; top:10px; right:10px; z-index:9000; background:rgba(0,0,0,0.55); padding:10px 14px; border-radius:14px; font-size:14px; display:flex; flex-direction:column; gap:6px; width:300px; }
  /* Small bold letter-spaced label above each control - the same "eyebrow"
     treatment gordonthelander.github.io/HPM_Manifest_Crawl/ uses above its
     own headings (e.g. "COMMUNITY TOOLS FOR HUBITAT"), borrowed for shape/
     type only, not its light-card colours. */
  #controls label { display:block; margin-bottom:3px; font-weight:800; font-size:11px; letter-spacing:0.6px; text-transform:uppercase; color:#7fb6d6; }
  #showFilterLabel { margin-top:22px; }
  /* The one remaining native <select> (Show/kindFilter) was left to the
     browser's own default white dropdown chrome - now matches the pill
     buttons and combobox next to it instead of standing out as unstyled. */
  #controls select { width:100%; box-sizing:border-box; background:#123a52; color:#cfe9fb; border:1px solid #1e5878; border-radius:999px; padding:5px 10px; }
  #controls button, #controls select, #controls option { font-size:14px; font-family:inherit; }
  /* Left to the browser default before this, every unstyled button (Insights,
     External systems, Pivot tables, Device icons, AI friendly export, Hubitat
     release activity, Exit map) rendered as a stark light-grey pill against
     this dark panel - the only two that looked deliberate were "Show all"
     and "Community utilities", which already set their own inline colours.
     Blue accent taken from gordonthelander.github.io/HPM_Manifest_Crawl/
     (Hubitat Community Utilities) - #17699a/#eef7fc there on a light card,
     inverted here for a dark one so every plain action button reads as one
     deliberate family instead of an unstyled default. */
  /* Pill-shaped, matching the rounded buttons/badges on
     gordonthelander.github.io/HPM_Manifest_Crawl/ - shape only, this app
     stays on its own dark background rather than that site's light one. */
  #controls button { margin-top:2px; cursor:pointer; background:#123a52; color:#cfe9fb; border:1px solid #1e5878; border-radius:999px; padding:6px 14px; font-weight:600; }
  #controls button:hover { background:#1a4d6b; }
  /* Phase 1 workspace shell (backlog item 1): same handlers, same ids, just
     grouped so the panel reads as three zones - find something, act on the
     current view, open a secondary tool - instead of one long list. No
     graph/filter/export/panel-content behaviour changes here. */
  /* Every internal gap in these two zones comes from one flex `gap`, not
     margins on individual children - a per-element margin (e.g. a first-child
     top margin standing in for the summary-to-label gap) reads as uneven the
     moment a sibling's own margin differs, which is what Gordon flagged live.
     Overriding the general label margin to 0 makes gap the only source of
     spacing here.
     The gap lives on #focusList, a plain div, not on #focusSection itself:
     Chromium renders a <details> as summary + one internal ::details-content
     box wrapping everything else, so a flex gap set directly on <details>
     only ever sees those two boxes - confirmed live, it produced a correct
     10px gap after the summary and 0px between every label after that,
     since the labels were never the flex container's real children. */
  #focusSection { border-bottom:1px solid #1e5878; padding-bottom:8px; margin-bottom:2px; display:flex; flex-direction:column; gap:10px; }
  #focusSection summary { cursor:pointer; font-weight:800; font-size:11px; letter-spacing:0.6px; text-transform:uppercase; color:#7fb6d6; padding:2px; border-radius:4px; list-style:none; display:flex; justify-content:space-between; align-items:center; }
  #focusSection summary::-webkit-details-marker { display:none; }
  #focusSection summary::before { content:'>'; display:inline-block; margin-right:6px; transition:transform 0.1s; }
  #focusSection[open] summary::before { transform:rotate(90deg); }
  /* Dev-build-only marker so testers can never mistake this build for
     production at a glance - deliberately red (not the panel's usual blue
     accent) and only ever rendered when isDevBuild() is true server-side. */
  .devBadge { color:#ff5555; text-transform:none; letter-spacing:0.2px; }
  #focusSection summary:hover { background:rgba(255,255,255,0.10); }
  #focusList { display:flex; flex-direction:column; gap:10px; }
  #focusList label { margin-bottom:0; }
  #workspaceHeader { border-bottom:1px solid #1e5878; padding-bottom:8px; margin-bottom:2px; display:flex; flex-direction:column; gap:10px; }
  #workspaceHeader #showFilterLabel { margin-top:0; margin-bottom:0; }
  #headerActions { display:flex; gap:8px; }
  #headerActions button { flex:1; margin-top:2px; }
  #toolRail { display:flex; flex-direction:column; gap:8px; }
  #toolRail button { margin-top:0; }
  #toolRail #exitMapBtn { margin-top:8px; }
  /* Insights/External systems and Pivot tables/Device icons paired onto one
     row each, per Gordon's own live mark - same flex:1-split pattern
     #headerActions already uses for Show all/Fit map, factored into a
     class since this now applies to two separate row wrappers rather than
     one. */
  .toolRailRow { display:flex; gap:8px; }
  .toolRailRow button { flex:1; margin-top:0; }
  /* Combined combobox (Focus app/device/hub variable/local variable) - replaces
     the old stacked search input + <select> pair, ported from the standalone
     harness verified in Bucket/combobox-harness/. Closed control is a plain
     non-editable button; the search field lives inside the popup only. */
  /* Every combobox mounts inside a <label> (see initCombo/HTML below), and
     the new eyebrow-label styling on #controls label - tiny, bold, letter-
     spaced, uppercase, blue - is otherwise inherited by everything nested
     inside it. .cb-opt and .cb-search have no font-weight/text-transform/
     letter-spacing/colour of their own to block that (confirmed live:
     .cb-search measured at 11px/800/blue - the label's values, not its
     own), so it stops here instead, restoring normal text for the whole
     combobox subtree regardless of which label it happens to sit inside. */
  .cb { position:relative; font-weight:400; font-size:14px; letter-spacing:normal; text-transform:none; color:#eee; }
  .cb-button { width:100%; box-sizing:border-box; padding:4px 12px; font:inherit; text-align:left; border:1px solid #1e5878; border-radius:999px; background:#0a2530; color:#eee; cursor:pointer; display:flex; align-items:center; justify-content:space-between; gap:6px; }
  .cb-button:focus { outline:2px solid #4a90d9; outline-offset:-1px; }
  .cb-button-label { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .cb-arrow { flex:none; color:#cfd8dc; font-size:12px; }
  .cb-is-open .cb-arrow { transform:rotate(180deg); }
  /* Right-aligned and wider than the closed button, not left:0/right:0 - the
     150px control truncated every real app/device name to a few characters.
     #controls is pinned to the right edge of the screen, so the popup grows
     leftward off the button's right edge rather than off-screen. */
  /* z-index:9000, not 50 - panels' own z-index (panelTopZ in the JS below)
     starts at 30 and increments by 1 on every single panel open, with no
     ceiling, so across a long session it climbs past whatever fixed number
     this used to be. Confirmed live: after enough panel switches this
     session, an open panel was drawing on top of the Focus combobox popup
     instead of under it. A combobox popup is always transient, top-level
     interactive UI - nothing should ever legitimately need to sit above
     it, so this is set far enough past any realistic panelTopZ value that
     it does not need recalculating against that counter as it grows. */
  .cb-popup { position:absolute; z-index:9000; right:0; width:480px; top:calc(100% + 2px); background:#041b23; border:1px solid #1e5878; border-radius:12px; box-shadow:0 6px 22px rgba(0,0,0,0.45); overflow:hidden; }
  /* The dedicated search field - first row of the popup, auto-focused on
     open, visually its own zone (bottom border) above the options list. */
  .cb-search { display:block; width:100%; box-sizing:border-box; padding:6px 8px; font:inherit; border:0; border-bottom:1px solid #1e5878; background:#0d3446; color:#eee; }
  .cb-search::placeholder { color:#8fc4e0; font-style:italic; opacity:1; }
  .cb-search:focus { outline:none; }
  .cb-list { list-style:none; margin:0; padding:0; max-height:260px; overflow-y:auto; }
  .cb-opt { padding:4px 8px; cursor:pointer; white-space:normal; word-break:break-word; font-size:14px; line-height:1.3; }
  .cb-opt-active { background:#34506b; }
  .cb-opt-selected { font-weight:600; }
  .cb-opt-sticky { font-style:italic; opacity:.9; border-top:1px dashed #4a4f57; }
  .cb-count { padding:4px 8px; font-size:12px; color:#9aa4ad; border-top:1px solid #3a3f47; background:#031218; }
  #network { width:100%; height:100vh; }
  /* Sits behind the network canvas (earlier in DOM order, no z-index of its
     own, and vis-network's own canvas has no background fill so empty space
     around the graph shows whatever is layered underneath it). Fixed, not
     absolute - pinned to a fixed point on the actual screen regardless of
     where physics settles the graph's own bounding box. Centre top per
     Gordon's instruction (2026-09-09). Dead centre, large and faint: a real
     background watermark rather than a small opaque object competing with
     the graph for a corner. Every previous position (under Exit map, centre
     top, beside the left column) was an attempt to find somewhere it did not
     collide with something - at this size and opacity there is nowhere to
     collide with, because it reads as ground rather than figure.
     No runtime positioning: a fixed 50/50 with translate(-50%,-50%) needs no
     measurement, so positionHubWatermark() and its resize/load hooks are gone
     rather than left as no-ops. */
  #hubWatermark { position:fixed; top:50%; left:50%; transform:translate(-50%, -50%);
                  max-width:38vw; max-height:38vh; opacity:0.50; pointer-events:none;
                  user-select:none; }
  /* Hub photo as the centre watermark: large, and much fainter than the tree.
     The image is dark on transparent and sits on a dark canvas, so opacity has
     a floor below which it vanishes entirely rather than reading as subtle -
     0.18 is the starting point, tuned live rather than derived. */
  #hubWatermark.hubPhoto { max-width:34vw; max-height:34vh; opacity:0.18; }
  /* Backlog item 1 Phase 3 (A6): the legend used to be one element that was
     either a single "Legend" header row or every one of ~20 rows at once -
     permanently expensive canvas space the moment it was expanded, the exact
     thing this replaces. #legend is now always just the 3 common roles plus
     a button into the full reference, which lives in #legendPanel using the
     same shared shell as every other panel (backlog item 1 Phase 2). Every
     meaning/wording below is unchanged, just relocated - #legendPanel reuses
     the global .legend-row/.swatch/.line/.note classes as-is. */
  #legendMoreBtn { display:block; width:100%; margin-top:6px; text-align:center; }
  /* Shared pill button for chrome living outside #controls (the compact
     legend's "Full legend" button, the Resources panel's two relocated
     buttons) - same look as #controls button, factored out because it is
     no longer only #controls that needs it. */
  .pillBtn { cursor:pointer; background:#123a52; color:#cfe9fb; border:1px solid #1e5878; border-radius:999px; padding:6px 14px; font-weight:600; font-family:inherit; font-size:14px; }
  .pillBtn:hover { background:#1a4d6b; }
  /* Explicit 14px base - Gordon flagged the full legend panel's text as
     inconsistently large live. #legendPanel is not a descendant of #legend
     (which sets its own 12px), so .legend-row/.note etc had nothing to
     inherit from but the page's own 16px default. 14px matches the app's
     other standard body text (#controls and its combobox popups). */
  #legendPanel { position:absolute; top:100px; left:10px; z-index:21; background:#041b23; padding:14px 18px; border-radius:6px;
                 font-size:14px; max-width:min(60vw, 640px); max-height:90vh; display:none; flex-direction:column; box-shadow:0 4px 24px rgba(0,0,0,0.55); }
  #legendPanel h3 { margin:0 0 8px 0; font-size:0.95em; }
  /* "Collapse Legend" instead of a bare X, per Gordon's request - the verb
     pairs with "Full legend" on the compact legend's own button, making the
     compact/full relationship explicit rather than relying on a close
     glyph to imply it. Still .panelClose underneath (same close handler,
     same hover/pill styling), just wider than the single-glyph case
     .panelClose's own position:absolute assumes, so this one is a normal
     right-aligned flex child instead - #flowHeader's close button needed
     the identical override for the identical reason. */
  #legendTopBar { display:flex; align-items:center; justify-content:space-between; gap:10px; margin-bottom:8px; }
  #legendTopBar h3 { margin:0; }
  #legendPanelClose { position:static; white-space:nowrap; }
  .legend-row { display:flex; align-items:center; margin:3px 0; }
  /* Shape is per row now. The old single .swatch rule forced border-radius 50%
     on every swatch, so the legend drew a circle for an app that the map draws
     as a square, and rotating that circle 45 degrees for an external system was
     a no-op: a rotated circle is still a circle. Reported on the thread. */
  .swatch { width:12px; height:12px; margin-right:8px; display:inline-block; flex:none; }
  .sw-dot { border-radius:50%; }
  .sw-square { border-radius:2px; }
  .sw-diamond { width:10px; height:10px; border-radius:1px; transform:rotate(45deg); margin:1px 9px 1px 1px; }
  .sw-triangle { width:0; height:0; border-left:6px solid transparent; border-right:6px solid transparent; border-bottom:11px solid currentColor; background:none !important; margin-right:8px; }
  .sw-triangle-down { width:0; height:0; border-left:6px solid transparent; border-right:6px solid transparent; border-top:11px solid currentColor; background:none !important; margin-right:8px; }
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
  .note { opacity:0.75; font-size:14px; margin-top:6px; line-height:1.35; }
  /* Was easy to miss entirely - same dark background as the page itself,
     no border, tucked in a corner. A first-time visitor's eye has nowhere
     else to land on page load but the graph, so this needs to actually
     compete for attention rather than blend in. The accent border reuses
     the app-node amber already established elsewhere on the page rather
     than introducing a new colour. */
  /* right:360px, not 16px - #controls (right:10px, width:300px, no
     box-sizing:border-box so its own 14px+14px padding renders it at a real
     328px, not 300px) has grown to 11+ rows over this session's own
     additions (five focus combos, the show filter, the full tool rail) and
     can genuinely reach this far down the page on a fresh install with no
     data-dependent shortening, so a bottom-right hint anchored the old,
     closer 16px sat directly under it - #controls' z-index:9000 then
     painted over it regardless of #hint's own z-index below, since a taller
     sibling always wins that fight. Clearing it horizontally
     (10 + 328 actual rendered width + 22 gap = 360, confirmed live against
     the real rendered width, not the bare CSS number) is a permanent fix
     that does not depend on either element's height, unlike a z-index
     number would. */
  #hint { position:absolute; bottom:16px; right:360px; z-index:15; background:#0a2530; padding:14px 18px; border-radius:6px;
          max-width:320px; font-size:0.85em; line-height:1.5; border:2px solid #e8a33d;
          box-shadow:0 4px 28px rgba(0,0,0,0.6), 0 0 0 4px rgba(232,163,61,0.12); }
  #hint b:first-child { display:block; font-size:1.25em; color:#e8a33d; margin-bottom:6px; }
  #hint button { cursor:pointer; padding:5px 14px; font-weight:600; }
  /* Deliberately not made to work on a phone. A few hundred nodes, a filter
     panel and a flowchart need room and a pointer; a shrunken version would be
     frustrating rather than useful, so small screens get told plainly - the
     message should be the only thing on screen, not layered under the normal
     page's own status pill and watermark image, which #status/#hubWatermark's
     own fixed/absolute positioning was never designed to hide itself. */
  #smallscreen { display:none; }
  @media (max-width: 820px) {
    #controls, #legend, #hint, #network, #flow, #status, #hubWatermark { display:none !important; }
    #smallscreen { display:block; padding:2em 1.5em; line-height:1.5; }
  }
  /* Shared by every panel now (backlog item 1 follow-up, same day as the
     flow-only version above it): Gordon asked for the flow panel's own
     draggable-green-header treatment to become the standard for all five,
     not stay a one-off. One class each instead of five near-duplicate id
     rules - a future panel gets this for free by using the class, not by
     copying CSS. Deliberately has no width/height of its own - see
     .modernPanelLarge below for why that half is separate.
     box-sizing:border-box - without it, sizeModernPanel()'s JS-set
     width/height are content-box sizes, and the panel's own 16px/16px
     horizontal padding renders 32px wider than that - confirmed live, JS
     set width to exactly the space free before the control rail and the
     panel still rendered 32px into it, right up against border-box's
     absence rather than any error in the free-space arithmetic itself. */
  .modernPanel { position:absolute; top:100px; left:10px; z-index:20; background:rgba(4,20,27,0.96); padding:0 16px 12px 16px; border-radius:6px;
                 box-sizing:border-box; font-size:13px;
                 display:none; flex-direction:column; box-shadow:0 4px 24px rgba(0,0,0,0.5); }
  /* Large by design, matching Gordon's own live-annotated "use the full
     display area" mark - but only for panels that should actually fill it.
     Split out from .modernPanel itself (backlog item 1 follow-up, later
     the same day): Gordon flagged live that a rule flowchart opened inside
     #flow had also grown to this same huge size, which he never asked for
     - only the tool-rail panels (Insights included) were meant to. #flow
     itself now always carries plain .modernPanel, and JS adds this class
     on top only when it is about to show Insights specifically (see the
     insightsBtn handler), removing it again for a rule flowchart/inert
     app/unreferenced local variable in favour of .flowClassicSize instead
     - #ext/#pivot/#icons/#releaseActivity carry this class permanently in
     their own static markup, since they only ever have the one shape.
     Width/height here are only the pre-JS fallback, same as top/left
     already were. A guessed calc(100vw - Npx) here overlapped the control
     rail live (Gordon's yellow-box screenshot, same session) once the
     actual rail width/margins did not match the number this guessed -
     sizeModernPanel(), called from bringToFront() the first time any given
     panel opens each page load (then never again once the user has
     dragged that panel - see makePanelDraggable()/panelCustomPosition),
     measures the real #status and #controls elements with
     getBoundingClientRect() and sets left/top/width/height from that, the
     same "measure the real DOM, do not guess a number" approach
     visibleRegion() already uses for the graph's own framing. */
  .modernPanel.modernPanelLarge { width:calc(100vw - 340px); height:calc(100vh - 70px); }
  /* #flow's own bounds from before backlog item 1's "unify all five
     panels" change - restored for the rule-flowchart/inert-app/
     unreferenced-variable case specifically, per Gordon's explicit "the
     workflow panel must remain as it was". Sizes to its own content within
     these bounds (no forced width/height, unlike .modernPanelLarge) -
     sizeModernPanel() only sets left/top when this class is present
     instead of .modernPanelLarge, leaving width/height to CSS exactly as
     the original draggable-flow-panel commit did. */
  /* Capped to the left column, not min(62vw,900px). Capping #flowSub alone was
     not enough: the panel shrink-to-fits its WIDEST child, so the inert panel's
     own prose, the community card and a wide mermaid box each pushed it out to
     a different width, which is why the left column looked ragged as you moved
     between apps. Everything text now wraps inside one fixed width instead. */
  .flowClassicSize { max-width:var(--leftColWidth); max-height:90vh; }
  /* A decoded flowchart is the one child that cannot wrap - it is an SVG with
     its own intrinsic size. Scroll it inside the panel rather than letting it
     set the panel's width, which is what the cap above exists to prevent. */
  #flowChart { overflow-x:auto; }
  /* Zoomed in, the chart is wider than the panel on purpose. The panel body
     scrolls it instead, so the sideways scrollbar stays at the panel's edge. */
  #flow.flowZoomed #flowChart { overflow-x:visible; }
  /* The drag handle, and the visual cue that a panel can be dragged at all -
     solid, saturated green (the app's own established accent, same as
     Community utilities/the status pill) is deliberately not part of this
     page's otherwise dark/blue palette, so it reads as "this bar behaves
     differently" rather than blending in as ordinary chrome. Negative
     side/top margins cancel .modernPanel's own padding so the bar reaches
     the panel's true edges instead of sitting inset within it, with a
     matching border-radius on just the top two corners. */
  .modernPanelHeader { cursor:move; user-select:none; background:#81BC00; flex:none; margin:0 -16px 10px -16px; padding:8px 12px 8px 16px;
                        border-radius:6px 6px 0 0; display:flex; align-items:center; justify-content:space-between; gap:10px; }
  .modernPanelHeader h3 { color:#121214; margin:0; font-size:0.95em; }
  /* A normal flex child instead of .panelClose's own position:absolute -
     legendPanel needs the identical override for the identical reason (see
     #legendTopBar below), applied separately there since it is not one of
     the five modernPanel panels. */
  .modernPanelHeader .panelClose { position:static; color:#121214; }
  #flow h4 { margin:14px 0 4px 0; font-size:0.9em; color:#cfe3ea; }
  /* One declaration only. These were previously duplicated 26 lines apart at
     equal specificity, so the cascade merged them per-property into
     margin/font-size from the later pair and line-height from the earlier -
     a value neither rule stated, and unreadable from either one alone. */
  #flow ul { margin:4px 0 10px 0; padding-left:18px; }
  #flow li { margin:5px 0; font-size:0.85em; line-height:1.35; }
  #flow p { margin:4px 0; }
  #flow .sub { opacity:0.7; font-size:0.78em; margin-bottom:10px; }
  /* #flow has no explicit width in classic mode (.flowClassicSize is a
     max-width cap, not a width) - it shrink-to-fits, and the browser's
     shrink-to-fit measures every child's UNWRAPPED preferred width, not
     its wrapped one. This one caption line is the widest thing classic
     mode ever contains by far (the mermaid diagram itself typically
     renders well under 300px), so without a cap of its own it dragged the
     whole panel out to however wide it takes to fit "Decoded execution
     order..." on one line - confirmed live, matched the panel's rendered
     width to the pixel. #flowSub specifically, not the shared .sub class -
     Insights reuses .sub for its own "Used by"/"Controlling apps" detail
     rows at the full large-panel width, which this must not narrow. */
  #flowSub { max-width:calc(var(--leftColWidth) - 32px); }
  /* A webCoRE panel draws no mermaid, so its content sat flush at the panel
     padding while an RM panel's flow cards start about 24px further in,
     shifting everything sideways as you switch between the two. Reserves that
     same gutter. #flowSub's own cap drops by the identical amount so the
     panel's shrink-to-fit preferred width is unchanged - that cap is what
     stops one caption line dragging the whole panel wide (see above). */
  #flow.wcIndent #flowSub,
  #flow.wcIndent #ruleVariablesCard,
  #flow.wcIndent #communityCard { margin-left:24px; }
  #flow.wcIndent #decodeCoverageCard { margin-left:24px; }
  #flow.wcIndent #flowSub { max-width:calc(var(--leftColWidth) - 56px); }
  #flowSub.webcoreNotice { color:#ff6b6b; font-weight:700; }
  #flow a { color:#7fb6d6; text-decoration:none; }
  #flow a:hover { text-decoration:underline; }
  /* Above the flowchart, where a back affordance is looked for - now below
     the green header bar rather than above the title, since the title
     moved into that bar. */
  #flowBack { font-size:0.8em; margin:8px 0 6px 0; display:flex; justify-content:space-between; align-items:baseline; gap:10px; }
  /* A link, not a button, so it reads as part of the same breadcrumb line
     rather than a separate control competing for attention. */
  #flowExit { color:#7fb8d4; cursor:pointer; text-decoration:none; white-space:nowrap; }
  #flowExit:hover { text-decoration:underline; }
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
  /* A px cap, never 50%. #flow shrink-to-fits, so a percentage here is
     circular: the browser sized the panel from this card's UNWRAPPED preferred
     width and then drew the card at half of that, which is why a CUS or INT
     panel rendered about 860px wide around a 440px card with the right half
     empty. Same shrink-to-fit trap documented on #flowSub above. */
  #communityCard { margin-top:14px; padding:12px 14px; border-radius:6px; background:#eef3f5; color:#1a2733; max-width:calc(var(--leftColWidth) - 32px); box-sizing:border-box; }
  #communityCard h4 { color:#1a2733; margin-top:0; }
  #communityCard .sub { color:#4a5a63; }
  #communityCard a { color:#1565c0; }
  #communityCard .ccBadge { display:inline-block; padding:1px 7px; border-radius:3px; font-size:0.75em; margin:0 6px 6px 0; background:#d7e6ea; color:#2c4a55; }
  #communityCard .ccCaution { color:#a05a1f; }
  #communityCard .ccLinks a { margin-right:12px; }
  /* Decode coverage card (v2.2.9). Dark, unlike the light community card above,
     because it reports on this app rather than quoting an outside source. */
  #decodeCoverageCard { margin-top:14px; padding:12px 14px; border-radius:6px; border:1px solid rgba(255,255,255,0.12); background:rgba(255,255,255,0.03); max-width:calc(var(--leftColWidth) - 32px); box-sizing:border-box; }
  #decodeCoverageCard h4 { margin:0 0 6px; }
  #decodeCoverageCard h5 { margin:10px 0 4px; font-size:0.85em; }
  #decodeCoverageCard .dcCaution { color:#d9a441; }
  #decodeCoverageCard .dcPartial { font-weight:700; margin:4px 0; }
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
  #ext .sub { opacity:0.72; font-size:0.78em; margin:0 0 12px 0; line-height:1.4; }
  #ext table { border-collapse:collapse; width:100%; font-size:0.8em; }
  #ext th { text-align:left; padding:5px 8px; color:#cfe3ea; font-weight:600; white-space:nowrap; }
  #ext td { padding:4px 8px; border-bottom:1px solid #16323c; vertical-align:top; }
  #ext tr.unclassified td { background:rgba(217,83,79,0.09); }
  #ext tr.grouphdr td { background:#0a2029; border-top:1px solid #2a4a57; padding-top:9px; padding-bottom:7px; }
  #ext tr.grouphdr .sub { opacity:0.65; font-weight:400; }
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
  /* Its own panel rather than reusing #ext or #flow's markup for CONTENT - a
     table of links and a small query builder is a different shape of content
     from either (a settings form, a rule flowchart), so each panel still owns
     its own table/tag/form CSS below. The outer chrome - position, size,
     background, close button, header-fixed/content-scrolling behaviour - is
     the one thing genuinely identical across all five panels, and now lives
     entirely in .modernPanel/.modernPanelHeader/.panelBody/.panelClose
     instead of five copies of the same shell rules. */
  #pivot .sub { opacity:0.72; font-size:14px; margin:0 0 12px 0; line-height:1.4; }
  #pivot a { color:#7fb6d6; text-decoration:none; }
  #pivot a:hover { text-decoration:underline; }
  #pivot table { border-collapse:collapse; width:100%; font-size:14px; }
  #pivot th { text-align:left; padding:5px 8px; color:#cfe3ea; font-weight:600; white-space:nowrap; }
  #pivot td { padding:4px 8px; border-bottom:1px solid #16323c; vertical-align:top; }
  #pivot select { background:#0d2630; color:#e8f2f6; border:1px solid #2a4a57; border-radius:3px; padding:3px 5px; font-size:14px; font-family:inherit; }
  #pivot label { font-size:14px; display:flex; align-items:center; gap:4px; }
  #pivot .rowbtn { background:none; border:1px solid #2a4a57; color:#9fb4bc; border-radius:3px; cursor:pointer; padding:3px 8px; font-size:14px; margin:0 4px 4px 0; }
  #pivot .rowbtn:hover { border-color:#4a7a94; color:#cfe3ea; }
  /* Its own panel rather than reusing #ext's markup for CONTENT, same as
     above - this one needs a search box and can run to ~200 rows, #ext's
     does not. */
  #icons .sub { opacity:0.72; font-size:0.78em; margin:0 0 12px 0; line-height:1.4; }
  #icons input[type=search] { background:#0d2630; color:#e8f2f6; border:1px solid #2a4a57; border-radius:3px; padding:4px 7px; font-size:0.9em; font-family:inherit; width:240px; margin-bottom:10px; }
  #icons table { border-collapse:collapse; width:100%; font-size:0.8em; }
  #icons th { text-align:left; padding:5px 8px; color:#cfe3ea; font-weight:600; white-space:nowrap; }
  #icons td { padding:4px 8px; border-bottom:1px solid #16323c; vertical-align:top; }
  /* Same AMIcons glyph the map itself draws for this device (ICON_GLYPHS),
     shown here too so the effective icon is visible at a glance instead of
     only as text inside the override dropdown. */
  .devIconGlyph { font-family:'AMIcons'; display:inline-block; width:16px; margin-right:6px; text-align:center; color:#7fb6d6; }
  #icons tr.overridden td { background:rgba(79,179,169,0.09); }
  #icons select { background:#0d2630; color:#e8f2f6; border:1px solid #2a4a57; border-radius:3px; padding:3px 5px; font-size:1em; font-family:inherit; }
  #icons .bar { margin-top:14px; padding-top:12px; border-top:1px solid #2a4a57; display:flex; gap:8px; flex-wrap:wrap; align-items:center; }
  #icons .msg { font-size:0.8em; margin-left:6px; }
  /* Insights. Rendered into #flowChart, so it inherits #flow typography and
     only what is specific to the dashboard layout lives here. */
  /* An explicit readable base in px, then sizes at or near 1em of it. The
     first version stacked fractional em on fractional em - .insMeta landed
     around 0.74em and a .sub inside .insPlain around 0.62em, roughly 10px -
     and Gordon could not read it. Anchoring here stops the compounding, and
     the .sub rule below neutralises #flow's own 0.78em so a nested caption
     cannot shrink twice. */
  #insRoot { font-size:15px; line-height:1.5; }
  /* Explicit px, not em. An em here still compounds against whatever the
     ancestor resolved to - a .sub inside .insPlain measured 10.7px even after
     the base was set, because #flow's own .sub rule was applying to it first.
     13px is the floor for everything secondary in this panel. */
  #insRoot .sub, #insRoot .insPlain .sub, #insRoot .insDetail .sub { font-size:14px; }
  #insRoot .insCards { display:grid; grid-template-columns:repeat(4, 1fr); gap:8px; margin:0 0 10px 0; }
  #insRoot .insCard { background:#0d2630; border:1px solid #2a4a57; border-radius:5px; padding:10px 6px; cursor:pointer;
                      color:#e8f2f6; font-family:inherit; text-align:center; display:flex; flex-direction:column; gap:3px; }
  #insRoot .insCard:hover { border-color:#4a7a94; }
  #insRoot .insCard b { font-size:1.75em; line-height:1.1; }
  #insRoot .insCard span { font-size:14px; opacity:0.85; line-height:1.25; }
  #insRoot .insCardZero b { opacity:0.35; }
  #insRoot .insCardAlert { border-color:#a5563f; }
  #insRoot .insCardAlert b { color:#e0a95f; }
  #insRoot .insNote { font-size:14px; opacity:0.65; margin:0 0 12px 0; line-height:1.45; }
  #insRoot .insStart { background:#0d2630; border:1px solid #2a4a57; border-left:3px solid #4a7a94; border-radius:4px;
                       padding:8px 10px; margin:0 0 12px 0; font-size:14px; line-height:1.45; }
  #insRoot .insSec { border-top:1px solid #16323c; }
  #insRoot .insHead { width:100%; display:flex; align-items:center; gap:8px; background:none; border:none; cursor:pointer;
                      color:#cfe3ea; font-family:inherit; font-size:1.08em; font-weight:600; padding:10px 2px; text-align:left; }
  #insRoot .insHead:hover { color:#fff; }
  #insRoot .insHeading { flex:1; min-width:0; display:flex; flex-direction:column; gap:1px; }
  #insRoot .insTitle { display:block; }
  #insRoot .insSummary { display:block; color:#9fb4bc; font-size:14px; font-weight:400; line-height:1.35; }
  #insRoot .insChev { opacity:0.7; font-size:0.9em; }
  #insRoot .insBadge { background:#1c3540; color:#9fb4bc; border-radius:9px; padding:1px 9px; font-size:0.9em; }
  #insRoot .insBadgeZero { opacity:0.4; }
  #insRoot .insBody { padding:0 0 10px 0; }
  #insRoot .insOk { font-size:14px; opacity:0.7; margin:0 0 6px 2px; }
  #insRoot .insLead { font-size:14px; opacity:0.85; margin:8px 0 6px 2px; line-height:1.5; }
  #insRoot .insRow { display:flex; align-items:center; gap:9px; padding:6px 2px; border-bottom:1px solid #10262e; font-size:1em; }
  #insRoot .insName { flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  #insRoot .insMeta { opacity:0.65; font-size:14px; white-space:nowrap; }
  #insRoot .insBtn { background:none; border:1px solid #2a4a57; color:#9fb4bc; border-radius:3px; cursor:pointer;
                     padding:3px 9px; font-size:14px; font-family:inherit; white-space:nowrap; }
  #insRoot .insBtn:hover { border-color:#4a7a94; color:#cfe3ea; }
  #insRoot .insChevPad { display:inline-block; width:26px; }
  #insRoot .insDetail { padding:6px 2px 9px 12px; border-left:2px solid #1c3540; margin:0 0 6px 4px; }
  #insRoot .insDetail p { margin:4px 0; font-size:14px; line-height:1.5; }
  #insRoot .insDetail b { color:#cfe3ea; }
  #insRoot .insShowAll { margin:9px 0 2px 2px; }
  #insRoot .insPlain { margin:5px 0 5px 18px; padding:0; font-size:14px; }
  /* #flow li sets 0.85em and overrides inheritance from the ul, which pulled
     these list items back down to 11.5px on their own. */
  #insRoot .insPlain li { margin:3px 0; font-size:14px; }
  #insRoot a { color:#7fb6d6; text-decoration:none; }
  #insRoot a:hover { text-decoration:underline; }
  @media (max-width: 1100px) { #insRoot .insCards { grid-template-columns:repeat(2, 1fr); } }
  /* Content CSS only now - position/size joined .modernPanel with the other
     four (Gordon's live direction: every panel uses the same large area and
     look, not a one-off centred/corner-pinned mix). The iframe's own
     width:100% still needs a parent with a real resolved width to size
     against (an iframe has none of its own the browser can see) - confirmed
     live this was what was cramping the chart originally, not a CSS gap -
     and .modernPanel gives it one via its own explicit width, same as it
     did when this panel had that width set directly. */
  #releaseActivity .sub { opacity:0.72; font-size:0.78em; margin:0 0 12px 0; line-height:1.4; }
  /* Gordon flagged a scrollbar live - a fixed 500px iframe plus the sub
     line and the link row below it does not always fit .panelBody's own
     available height, which now varies with the viewer's actual window
     rather than the old ~90vh cap. .panelBody becomes a column flex
     container for this one panel only, and the iframe (flex:1, min-height:0
     so it can shrink rather than only grow) absorbs whatever vertical space
     is actually left after the sub line and the link row below it - the
     content now always exactly fits, rather than a fixed height sometimes
     not. max-width+margin:auto also shrinks and centres it horizontally,
     matching the 1100px bound this embed was actually designed/tested for
     (see the comment above) rather than stretching it edge-to-edge across
     a panel now far wider than that. */
  #releaseActivity .panelBody { display:flex; flex-direction:column; }
  /* position:relative + an explicit z-index (not "auto", the default) -
     confirmed live this is required for a live <iframe> specifically: it
     was rendering above the Focus combobox popup despite the popup's own
     z-index being far higher (9000 vs the panel's own ~90s). A browsable
     iframe gets its own compositing layer, and without an explicit z-index
     of its own some browsers paint that layer above ordinary
     higher-z-index siblings regardless of the surrounding stacking order -
     a known quirk, not something specific to this app. z-index:1 is
     already enough since it only needs to properly join its own parent's
     (#releaseActivity, z-index ~90s) stacking context rather than escape it. */
  #releaseActivity iframe { position:relative; z-index:1; border:0; display:block; width:100%; max-width:1100px; flex:1; min-height:0; border-radius:4px; margin:0 auto; align-self:center; }
  #releaseActivity a { color:#7fb6d6; text-decoration:none; }
  #releaseActivity a:hover { text-decoration:underline; }
  /* Backlog item 1 Phase 2 - the shared shell for all five panels
     (flow/Insights, ext, pivot, icons, releaseActivity). Each panel keeps its
     own id rule above for position/background/max-width/max-height (those
     genuinely differ - a centred iframe embed is not a corner-pinned table),
     but the close button and the header-fixed/content-scrolling behaviour
     were five copies of the same rule and the audit (A3) flagged the
     scrolling half of that as a real defect: with overflow on the whole
     panel, a tall Insights panel scrolled its own title out of view along
     with the findings. Each panel id rule now sets flex-direction:column
     (display stays none there - only bringToFront() flips it to flex) and
     the panel's title/back-link stay outside .panelBody as ordinary static
     children, so only .panelBody scrolls. */
  .panelClose { position:absolute; top:8px; right:10px; cursor:pointer; background:none; border:none; color:#bbb; font-size:1.1em; }
  .panelBody { flex:1; min-height:0; overflow:auto; }
  /* The panel's own bottom edge, matching its green header, so classic #flow
     terminates in a line rather than fading into dead space. A border rather
     than a content ::after: now that classic mode shrinks to content
     (sizeModernPanel), the panel edge IS where the content ends, and a border
     cannot be pushed above the padding the way an in-flow line was. Classic
     only - #flow also hosts Insights in large mode, which is a full-area
     workspace and does not want a terminator. */
  #flow.flowClassicSize { border-bottom:2px solid #81BC00; }
  #ext, #pivot, #icons, #releaseActivity { border-bottom:2px solid #81BC00; }
  /* Corner grip for resizing the normal flow view (v2.2.9). Hidden in the
     full-area Insights view, which sizes itself. */
  .panelResizeGrip { position:absolute; right:3px; bottom:3px; width:12px; height:12px; box-sizing:border-box; cursor:nwse-resize; border-right:2px solid #81BC00; border-bottom:2px solid #81BC00; opacity:0.7; }
  .panelResizeGrip:hover { opacity:1; }
  #flow.modernPanelLarge .panelResizeGrip { display:none; }
  /* Once the user has chosen a size, the text sections use the width they
     were given instead of staying capped to the left column. Placed after
     the wcIndent caps, which share this specificity, so it wins by order. */
  #flow.flowUserSized #flowSub,
  #flow.flowUserSized #ruleVariablesCard,
  #flow.flowUserSized #decodeCoverageCard,
  #flow.flowUserSized #communityCard { max-width:none; }
  /* Gordon flagged External systems/Pivot tables/Device icons's own table
     headers scrolling off with the rows above them - the panel's outer title
     (fixed by .panelBody above) was never the only header that could do
     that. One rule covers every table in every current and future panel
     body, rather than three copies. background matches these three panels'
     own #041b23 exactly, needed so rows scrolling underneath don't show
     through a sticky header with no fill of its own. box-shadow instead of
     each th's own border-bottom - border-collapse:collapse plus
     position:sticky is a known Chromium rendering bug (the collapsed border
     belongs to the table, not the offset cell, and can flicker or vanish
     while scrolling); a box-shadow paints the same 1px line without
     participating in border collapsing at all. */
  .panelBody th { position:sticky; top:0; z-index:1; background:#041b23; box-shadow:0 1px 0 #2a4a57; }${''}
</style>
</head>
<body>
<div id="status">Devices: ${deviceCount} &nbsp; Apps: ${appCount}</div>
<div id="legend">
  <div id="legendCompactBody"></div>
  <button type="button" id="legendMoreBtn" class="pillBtn">Full legend</button>
</div>
<div id="legendPanel"><div id="legendTopBar"><h3>Legend</h3><button id="legendPanelClose" class="panelClose pillBtn" type="button" title="Collapse back to the compact legend">Collapse Legend</button></div><div id="legendPanelBody" class="panelBody">
  <div class="legend-row"><span class="swatch sw-square" style="background:#e8a33d"></span>App</div>
  <div class="legend-row"><span class="swatch sw-square sw-outline"></span>Rule reached only as another rule's target</div>
  <div class="legend-row"><span class="swatch sw-square sw-missing"></span>Rule referenced but deleted - the action silently does nothing</div>
  <div class="legend-row"><span class="swatch sw-square" style="background:#6d6a5f"></span>App paused or disabled - label ends "(Paused)" or "(Disabled)"</div>
  <div class="legend-row"><span class="swatch sw-square sw-inert"></span>App with no device or rule relationship - its label says why</div>
  <div class="legend-row"><span class="swatch sw-square sw-unreadable"></span>Could not be read during the scan - rescan to retry</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#5f7d8c"></span>Device - icon by type (light, door, sensor...), grey with no app focused. Wrong? Device icons panel.</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#5f7d8c"></span>Device disabled on the hub - same colour and icon as any device, label ends "(Disabled)"</div>
  <div class="legend-row"><span class="swatch sw-diamond" style="background:#cfd8dc"></span>External system - declared, not detected</div>
  <div class="legend-row"><span class="swatch sw-triangle" style="color:#4fb3a9"></span>Hub Variable - shared state a rule writes or reads</div>
  <div class="legend-row"><span class="swatch sw-triangle-down" style="color:#7986cb"></span>Local Variable - belongs to one rule only</div>
  <div class="legend-row"><span class="swatch sw-triangle-down" style="color:#7986cb; opacity:0.55"></span>Local Variable, dashed - declared but no proven decoded reference in this rule</div>
  <div class="note" style="margin:2px 0 6px 0">Focus an app and each device instead takes the colour of its role below, shown as both a line and the dot the device itself becomes.</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#9b59b6"></span><span class="line" style="border-color:#9b59b6"></span>Trigger - app listens to this device</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#16a085"></span><span class="line" style="border-color:#16a085"></span>Constraint - condition / required expression</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#3d7ea6"></span><span class="line" style="border-color:#3d7ea6"></span>Monitor - app reads this device's state</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#7fae42"></span><span class="line" style="border-color:#7fae42"></span>Action - app can command this device</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#c98b6b"></span><span class="line" style="border-color:#c98b6b; border-top-style:dotted"></span>Exposed - published to an external system</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#8090a0"></span><span class="line" style="border-color:#8090a0; border-top-style:dashed"></span>Owns - app created this device</div>
  <div class="legend-row"><span class="swatch sw-dot" style="background:#5c6bc0"></span><span class="line" style="border-color:#5c6bc0"></span>Has component - device-owned component of a parent device (e.g. Shelly, Bond, a Matter bridge)</div>
  <div class="legend-row"><span class="line" style="border-color:#999"></span>Connector - a Hub Variable and its connector device hold the same value</div>
  <div class="legend-row"><span class="line" style="border-color:#4fb3a9"></span>Write - rule sets a Hub or Local Variable's value</div>
  <div class="legend-row"><span class="line" style="border-color:#8fd6cc"></span>Read - rule uses a Hub or Local Variable in its decoded logic</div>
  <div class="legend-row"><span class="line" style="border-color:#d9534f"></span>Runs - rule runs another rule's actions</div>
  <div class="legend-row"><span class="line" style="border-color:#d9534f; border-top-style:dashed"></span>Cancel timed actions - rule cancels another rule's pending Wait/Delay</div>
  <div class="legend-row"><span class="line" style="border-color:#d9534f; border-top-style:dotted"></span>Private Boolean - rule sets another rule's Private Boolean</div>
  <div class="legend-row"><span class="line ln-pat ln-dashdot" style="color:#d9534f"></span>Pause / resume - rule pauses or resumes another rule (focus the rule to see which)</div>
  <div class="legend-row"><span class="line ln-pat ln-thick" style="border-color:#cfd8dc; background:repeating-linear-gradient(to right,#cfd8dc 0 6px,transparent 6px 9px)"></span>Depends on - needed all the time</div>
  <div class="legend-row"><span class="line ln-pat" style="background:repeating-linear-gradient(to right,#cfd8dc 0 2px,transparent 2px 7px)"></span>Depends on - needed only to set up or manage</div>
  <div class="note">Arrows follow the flow: triggers and constraints point into the app, actions and owned devices point out of it.</div>
  <div class="note">Focus one app to colour its devices by role. A device holding two roles in one app gets two edges, and is coloured by the more significant one.</div>
</div></div>
<div id="smallscreen">
  <h2>Best viewed on a desktop</h2>
  <p>Automation Map shows every app and device on your hub at once, with filter controls and rule flowcharts alongside. That needs a large screen and a mouse, so it is not made to work on a phone.</p>
  <p>Open this same link on a computer.</p>
</div>
<div id="controls">
  <details id="focusSection" open>
    <summary>Focus${isDevBuild() ? "<span class='devBadge'>Dev ${APP_VERSION}</span>" : ''}</summary>
    <div id="focusList">
      <label>Quick search<span id="searchAllComboMount"></span></label>
      <label>Focus app<span id="appComboMount"></span></label>
      <label>Focus device<span id="deviceComboMount"></span></label>
      <label>Focus hub variable<span id="hubVarComboMount"></span></label>
      <label>Focus local variable<span id="localVarComboMount"></span></label>
    </div>
  </details>
  <div id="workspaceHeader">
    <label id="showFilterLabel">Show<select id="kindFilter">
      <option value="all">All relationships</option>
      <option value="trigger">Triggers only</option>
      <option value="constraint">Constraints only</option>
      <option value="monitor">Monitored only</option>
      <option value="action">Actions only</option>
      <option value="exposed">Exposed only</option>
      <option value="owns">Ownership only</option>
      <option value="hasComponent">Has component only</option>
      <option value="rulelinks">Rule to rule only</option>
      <option value="depends">External systems only</option>
      <option value="variables">Variable use only</option>
      <option value="synchronizedWith">Variable connectors only</option>
      <option value="deviceRead">webCoRE device state reads only</option>
    </select></label>
    <div id="headerActions">
      <button id="resetBtn" type="button" style="background:#d9822b; color:#121214; border-color:#a5701f;">Show all</button>
      <button id="fitMapBtn" type="button" title="Re-fit the current view without changing what's focused">Fit map</button>
    </div>
  </div>
  <div id="toolRail">
    <div class="toolRailRow"><button id="insightsBtn" type="button">Insights</button><button id="extBtn" type="button">External systems</button></div>
    <div class="toolRailRow"><button id="pivotBtn" type="button">Pivot tables</button><button id="iconsBtn" type="button">Device icons</button></div>
    <button id="exportBtn" type="button" title="Download the whole map as JSON, for an AI or other tool to read">AI friendly export</button>
    <button id="releaseActivityBtn" type="button" style="background:#81BC00; color:#121214; border-color:#5c8500;" title="Preview Hubitat release activity from Community Utilities">Hubitat release activity</button>
    <button id="communityUtilitiesBtn" type="button" style="background:#81BC00; color:#121214; border-color:#5c8500;" title="Open the Hubitat Community Utilities site in a new tab">Community utilities</button>
    <button id="exitMapBtn" type="button" title="Return to this app's settings screen">Exit map</button>
  </div>
</div>
<div id="flow" class="modernPanel flowClassicSize"><div id="flowHeader" class="modernPanelHeader" title="Drag to move. Double-click to reset size, position and zoom. Ctrl with the mouse wheel zooms this panel."><h3 id="flowTitle"></h3><button id="flowClose" class="panelClose" type="button" title="Close">&times;</button></div><div id="flowBack" style="display:none"></div><div class="sub" id="flowSub"></div><div class="panelBody" id="flowBody"><div id="flowZoom"><div id="flowChart"></div><div id="decodeCoverageCard" hidden></div><div id="ruleVariablesCard"></div><div id="communityCard"></div></div></div><div id="flowResize" class="panelResizeGrip" title="Drag to resize"></div></div>
<div id="ext" class="modernPanel modernPanelLarge"><div class="modernPanelHeader"><h3>External systems</h3><button id="extClose" class="panelClose" type="button" title="Close">&times;</button></div><div id="extBody" class="panelBody"></div></div>
<div id="pivot" class="modernPanel modernPanelLarge"><div class="modernPanelHeader"><h3>Pivot tables</h3><button id="pivotClose" class="panelClose" type="button" title="Close">&times;</button></div><div id="pivotBody" class="panelBody"></div></div>
<div id="icons" class="modernPanel modernPanelLarge"><div class="modernPanelHeader"><h3>Device icons</h3><button id="iconsClose" class="panelClose" type="button" title="Close">&times;</button></div><div id="iconsBody" class="panelBody"></div></div>
<div id="releaseActivity" class="modernPanel modernPanelLarge"><div class="modernPanelHeader"><h3>Hubitat releases over time</h3><button id="releaseActivityClose" class="panelClose" type="button" title="Close">&times;</button></div><div class="sub">Community Utilities release history and documented changes.</div><div id="releaseActivityBody" class="panelBody"></div></div>
<img id="hubWatermark" class="${showSanta() ? '' : 'hubPhoto'}" src="https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/${isDevBuild() ? 'dev' : 'main'}/Images/${showSanta() ? 'Merry%20Christmas.png' : 'hub-from-side.png'}" alt="">
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
                     depends: '#cfd8dc', write: '#4fb3a9', read: '#8fd6cc', usesVar: '#f0c36e', deviceRead: '#5c9bd6', hasComponent: '#5c6bc0', synchronizedWith: '#999' };
const groupColors = { app: '#e8a33d', device: '#5f7d8c', external: '#cfd8dc', hubVariable: '#4fb3a9', localVariable: '#7986cb' };

// Contextual compact legend (Gordon's live feedback on backlog item 1 Phase
// 3): the fixed Trigger/Action/Monitor rows were plain wrong for a view that
// draws none of those - a focused rule showing Trigger/Action/Runs still
// only listed Monitor among its three, with Runs (clearly on screen, red
// dashed lines) nowhere in the legend at all. Rows now reflect exactly the
// edge kinds and node groups actually drawn right now, computed from the
// same live nodes/edges DataSets the graph itself renders from - not a
// second, parallel classification that could drift from what is on screen.
// Colours read from roleColors/groupColors above rather than being
// hardcoded again, so the legend cannot show a colour the graph itself does
// not actually use.
const LEGEND_GROUP_ROWS = [
  { group: 'app', html: '<span class="swatch sw-square" style="background:' + groupColors.app + '"></span>App' },
  { group: 'device', html: '<span class="swatch sw-dot" style="background:' + groupColors.device + '"></span>Device' },
  { group: 'external', html: '<span class="swatch sw-diamond" style="background:' + groupColors.external + '"></span>External system' },
  { group: 'hubVariable', html: '<span class="swatch sw-triangle" style="color:' + groupColors.hubVariable + '"></span>Hub Variable' },
  { group: 'localVariable', html: '<span class="swatch sw-triangle-down" style="color:' + groupColors.localVariable + '"></span>Local Variable' }
];
// key matches applyFilters()'s own edge.kind, with depends split by e.crit
// exactly as the actual line rendering above splits it (dashes/width).
const LEGEND_EDGE_ROWS = [
  { key: 'trigger', html: '<span class="swatch sw-dot" style="background:' + roleColors.trigger + '"></span><span class="line" style="border-color:' + roleColors.trigger + '"></span>Trigger - app listens to this device' },
  { key: 'constraint', html: '<span class="swatch sw-dot" style="background:' + roleColors.constraint + '"></span><span class="line" style="border-color:' + roleColors.constraint + '"></span>Constraint - condition / required expression' },
  { key: 'monitor', html: '<span class="swatch sw-dot" style="background:' + roleColors.monitor + '"></span><span class="line" style="border-color:' + roleColors.monitor + '"></span>Monitor - app reads the state of this device' },
  { key: 'action', html: '<span class="swatch sw-dot" style="background:' + roleColors.action + '"></span><span class="line" style="border-color:' + roleColors.action + '"></span>Action - app can command this device' },
  { key: 'exposed', html: '<span class="swatch sw-dot" style="background:' + roleColors.exposed + '"></span><span class="line" style="border-color:' + roleColors.exposed + '; border-top-style:dotted"></span>Exposed - published to an external system' },
  { key: 'owns', html: '<span class="swatch sw-dot" style="background:' + roleColors.owns + '"></span><span class="line" style="border-color:' + roleColors.owns + '; border-top-style:dashed"></span>Owns - app created this device' },
  { key: 'hasComponent', html: '<span class="swatch sw-dot" style="background:' + roleColors.hasComponent + '"></span><span class="line" style="border-color:' + roleColors.hasComponent + '"></span>Has component - device-owned component of a parent device' },
  { key: 'synchronizedWith', html: '<span class="line" style="border-color:' + roleColors.synchronizedWith + '"></span>Connector - a Hub Variable and its connector device hold the same value' },
  { key: 'write', html: '<span class="line" style="border-color:' + roleColors.write + '"></span>Write - rule sets the value of a Hub or Local Variable' },
  { key: 'read', html: '<span class="line" style="border-color:' + roleColors.read + '"></span>Read - rule uses a Hub or Local Variable in its decoded logic' },
  { key: 'usesVar', html: '<span class="line ln-pat" style="background:repeating-linear-gradient(to right,' + roleColors.usesVar + ' 0 5px,transparent 5px 9px)"></span>Uses - webCoRE piston references a Hub Variable; direction is unknown' },
  { key: 'deviceRead', html: '<span class="swatch sw-dot" style="background:' + roleColors.deviceRead + '"></span><span class="line" style="border-color:' + roleColors.deviceRead + '"></span>Device read - webCoRE piston reads this device somewhere its role could not be attributed, such as an expression or a task parameter' },
  { key: 'runs', html: '<span class="line" style="border-color:' + roleColors.runs + '"></span>Runs - rule runs the actions of another rule' },
  { key: 'cancelTimedActions', html: '<span class="line" style="border-color:' + roleColors.cancelTimedActions + '; border-top-style:dashed"></span>Cancel timed actions - rule cancels a pending Wait/Delay on another rule' },
  { key: 'setspb', html: '<span class="line" style="border-color:' + roleColors.setspb + '; border-top-style:dotted"></span>Private Boolean - rule sets the Private Boolean of another rule' },
  { key: 'pauseResume', html: '<span class="line ln-pat ln-dashdot" style="color:' + roleColors.pauseResume + '"></span>Pause / resume - rule pauses or resumes another rule' },
  { key: 'depends:RUNTIME', html: '<span class="line ln-pat ln-thick" style="border-color:' + roleColors.depends + '; background:repeating-linear-gradient(to right,' + roleColors.depends + ' 0 6px,transparent 6px 9px)"></span>Depends on - needed all the time' },
  { key: 'depends:SETUP', html: '<span class="line ln-pat" style="background:repeating-linear-gradient(to right,' + roleColors.depends + ' 0 2px,transparent 2px 7px)"></span>Depends on - needed only to set up or manage' }
];
// Called from applyFilters() right after nodes/edges are rebuilt, and once
// at page load for the initial whole-hub view - the two places those
// DataSets actually change.
//
// nodeList takes the pre-styling node array (shownNodes in applyFilters(),
// ALL_NODES by default here) rather than reading document group off the
// live `nodes` DataSet - styledNode()'s own returned object never includes
// n.group at all (only uses it to pick color/shape/size, then drops it), so
// nodes.get() here would silently see every node as group:undefined.
// Confirmed live: an early version of this read from the live DataSet
// directly and every group row vanished, whole-hub view included. Edges are
// fine read live - the edge styling map explicitly keeps kind/crit in its
// output object.
function updateCompactLegend(nodeList) {
  const el = document.getElementById('legendCompactBody');
  if (!el) return;
  const groupsShown = {};
  (nodeList || ALL_NODES).forEach(function (n) { groupsShown[n.group] = true; });
  const kindsShown = {};
  edges.get().forEach(function (e) {
    kindsShown[e.kind] = true;
    if (e.kind === 'depends') kindsShown['depends:' + (e.crit === 'RUNTIME' ? 'RUNTIME' : 'SETUP')] = true;
  });
  let html = '';
  LEGEND_GROUP_ROWS.forEach(function (r) { if (groupsShown[r.group]) html += '<div class="legend-row">' + r.html + '</div>'; });
  LEGEND_EDGE_ROWS.forEach(function (r) { if (kindsShown[r.key]) html += '<div class="legend-row">' + r.html + '</div>'; });
  el.innerHTML = html || '<div class="note">Nothing on screen yet.</div>';
}

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
const VARIABLE_KINDS = ['write', 'read', 'usesVar'];

// Human-readable form of every edge kind, reused by the legend's own wording
// so a pivot table and the graph never describe the same relationship two
// different ways.
const KIND_LABEL = {
  trigger: 'Trigger', constraint: 'Constraint', monitor: 'Monitor', action: 'Action',
  exposed: 'Exposed', owns: 'Owns', hasComponent: 'Has component', runs: 'Runs', cancelTimedActions: 'Cancel timed actions',
  setspb: 'Private Boolean', pauseResume: 'Pause/resume', depends: 'Depends on', write: 'Write', read: 'Read',
  usesVar: 'Uses (direction unknown)', deviceRead: 'Device read (role not attributed)'
};
const GROUP_LABEL = { app: 'App', device: 'Device', external: 'External system', hubVariable: 'Hub Variable', localVariable: 'Local Variable' };

// Which edge kinds actually connect two node groups, keyed order-independently
// (device|app and app|device are the same relationship read from either end).
// Every edge on this map EXCEPT hasComponent has an app in `from` - that one
// is device-to-device, added in v2.1.7, and deliberately excluded from pivot
// support rather than given a device|device entry: the pivot feature is
// app-centric by construction (pivotColOptions() only offers multiple
// columns when rowGroup === 'app'), and a device-to-device relationship
// doesn't fit that shape. The relationship stays fully present on the graph
// and in the AI export either way - only the pivot-table view omits it.
// 'device' and 'external' still never appear as a source group here, only as
// a target - true for every OTHER edge kind, not a blanket fact about the
// data anymore.
function pivotKindOptions(g1, g2) {
  const key = [g1, g2].sort().join('|');
  if (key === 'app|app') return ['runs', 'cancelTimedActions', 'setspb', 'pauseResume'];
  if (key === 'app|device') return ['trigger', 'constraint', 'monitor', 'action', 'exposed', 'owns', 'deviceRead'];
  if (key === 'app|external') return ['depends'];
  if (key === 'app|hubVariable') return ['write', 'read', 'usesVar'];
  if (key === 'app|localVariable') return ['write', 'read'];
  return [];
}
function pivotColOptions(rowGroup) {
  return rowGroup === 'app' ? ['app', 'device', 'external', 'hubVariable', 'localVariable'] : ['app'];
}

// The fixed menu (option A from the discussion): each entry is a ready-made
// query into pivotRows below, phrased the way a person would ask for it
// rather than in row/column/kind terms.
const PIVOT_PRESETS = [
  { button: 'Rule &rarr; Rules affected', rows: 'app', cols: 'app',
    kinds: ['runs', 'cancelTimedActions', 'setspb', 'pauseResume'],
    rowLabel: 'Rule', colLabel: 'Rules affected' },
  // variableAutomationRows/Cols (v2.2.8, widened from the old ruleRows/
  // ruleCols): without a node-type filter here, "Automation -> Devices"
  // queried every app typed as an app - LIFX Light Manager or any other
  // integration with a device edge would show up under a heading that says
  // Automation. Widened from Rule-Machine-only to also admit webCoRE pistons
  // now that they can carry real deviceRead/action edges - same
  // isVariableAutomationNode() helper and rename already proven for the Hub
  // Variable pivots just below, applied here for the identical reason: the
  // new edges would otherwise exist on the graph but never appear in either
  // pivot. appType comes from buildGraph and is checked against the
  // Rule-<engine> prefix or the exact webCoRE piston type, not against the
  // display label, which the inert/unreadable states overwrite.
  { button: 'Automation &rarr; Devices', rows: 'app', cols: 'device',
    kinds: ['trigger', 'constraint', 'monitor', 'action', 'exposed', 'owns', 'deviceRead'],
    rowLabel: 'Automation', colLabel: 'Devices', opts: { variableAutomationRows: true } },
  { button: 'Device &rarr; Automations', rows: 'device', cols: 'app',
    kinds: ['trigger', 'constraint', 'monitor', 'action', 'exposed', 'owns', 'deviceRead'],
    rowLabel: 'Device', colLabel: 'Automations', opts: { variableAutomationCols: true } },
  { button: 'Automation &rarr; Hub Variables', rows: 'app', cols: 'hubVariable',
    kinds: ['write', 'read', 'usesVar'],
    rowLabel: 'Automation', colLabel: 'Hub Variables', opts: { variableAutomationRows: true } },
  { button: 'Hub Variable &rarr; Automations', rows: 'hubVariable', cols: 'app',
    kinds: ['write', 'read', 'usesVar'],
    rowLabel: 'Hub Variable', colLabel: 'Automations', opts: { variableAutomationCols: true } },
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
function isVariableAutomationNode(n) {
  return isRuleNode(n) || !!(n && n.appType === 'webCoRE Piston');
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
    if (opts.variableAutomationRows && !isVariableAutomationNode(rowNode)) return;
    if (opts.variableAutomationCols && !isVariableAutomationNode(colNode)) return;
    if (!groups[rowId]) groups[rowId] = [];
    const already = groups[rowId].some(function (t) { return t.id === colId && t.kind === e.kind; });
    if (!already) groups[rowId].push({ id: colId, title: colNode.title, kind: e.kind });
  });

  let typed = ALL_NODES.filter(function (n) { return n.group === rowGroup; });
  if (opts.ruleRows) typed = typed.filter(isRuleNode);
  if (opts.variableAutomationRows) typed = typed.filter(isVariableAutomationNode);
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
  const inbound = (e.kind === 'trigger' || e.kind === 'constraint' || e.kind === 'monitor' || e.kind === 'read' || e.kind === 'deviceRead');
  const directionUnknown = e.kind === 'usesVar';
  // A rule link always reads caller to target, and is drawn heavier than a
  // device relationship because it is the rarer and more surprising one.
  const isRuleLink = RULE_LINK_KINDS.indexOf(e.kind) !== -1;
  let dashes = false;
  if (e.kind === 'owns') dashes = true;
  else if (e.kind === 'exposed') dashes = [2, 4];
  else if (e.kind === 'cancelTimedActions') dashes = [8, 4];
  else if (e.kind === 'setspb') dashes = [2, 3];
  else if (e.kind === 'pauseResume') dashes = [12, 4, 2, 4];
  else if (e.kind === 'usesVar') dashes = [5, 4];
  // Always dashed, because a dependency on an external system is asserted by a
  // person, not read off the hub. Weight carries the part that matters
  // operationally: whether losing it stops the automation or merely stops you
  // reconfiguring it.
  else if (e.kind === 'depends') dashes = (e.crit === 'RUNTIME') ? [6, 3] : [2, 5];
  let width = isRuleLink ? 2.4 : ((e.kind === 'owns' || e.kind === 'exposed') ? 1 : 1.6);
  if (e.kind === 'depends') width = (e.crit === 'RUNTIME') ? 2.2 : 1.2;
  const edge = {
    // stateful stays three-valued (v2.2.8): true, false, or null for a webCoRE
    // action, where the command is proven but its lasting state is not. Every
    // consumer tests `=== true` or truthiness, so null still reads as false.
    id: i, from: e.from, to: e.to, kind: e.kind,
    stateful: e.stateful === null ? null : (e.stateful === true),
    crit: e.crit || null,
    // v2.2.8 decode evidence: a deviceRead's attribute, an action's command
    // names. Never task parameter values.
    attribute: e.attribute || null,
    commands: e.commands || null,
    // v2.0.14, schema 4: carried through explicitly, same as every other
    // field here - this object is a fresh rendering-specific literal, not a
    // spread of `e`, so a field not listed here is silently dropped
    // (buildExportPayload's edges mapping reads these off ALL_EDGES, not
    // GRAPH.edges directly).
    usageRole: e.usageRole || null,
    writeSource: e.writeSource || null,
    unused: e.unused === true,
    arrows: directionUnknown ? '' : (inbound ? 'from' : 'to'),
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
  // A Local Variable with no proven decoded reference in this rule (v2.1.6)
  // - not read in a trigger, condition or action, and not written - its own
  // dimmed variant, not n.inert (review 311
  // correction 3: that flag feeds inert-APP layout, focus behaviour and
  // Insights specifically, and reusing it would misreport this as an inert
  // app). Dimmed the same visual way for the same reason: still real, just
  // not connected to anything else on the map.
  if (n.unreferencedLocal) color = { background: '#262a3d', border: '#7986cb' };
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
  // Deliberately not 'diamond' or 'square' - fallbackSector() in
  // sectorLayout below reads shape alone to place a node with no matching
  // edge kind, and diamond specifically means "external system" there. A
  // Local Variable is the opposite of external; triangleDown keeps it out
  // of that check while still reading as "a variable, upside down from a
  // Hub Variable's shared triangle" at a glance.
  else if (n.group === 'localVariable') shape = 'triangleDown';
  else if (n.group === 'device') shape = 'icon';
  const styled = {
    // n.draw is the full identity without the hub's live status; n.title keeps
    // the status and is what the hover tooltip shows. The fallback matters: a
    // graph cached before draw existed has only title, and rendering undefined
    // would blank every label on the map rather than fail visibly.
    id: n.id, label: useFullLabel ? (n.group === 'localVariable' ? localVarCanvasText(n) : (n.draw || n.title)) : n.label, title: n.title, color: color,
    shape: shape,
    size: n.group === 'app' ? 17 : (n.group === 'external' ? 19 : 13),
    // 12, not 13, and a 4px stroke rather than 5. At FOCUS_MAX_ZOOM (1.05) this
    // renders at most 12.6 screen-px against #controls button text's fixed
    // 14px, so it is unambiguously smaller rather than 13.65 and arguably
    // equal. The stroke matters as much as the size: 5px around a 12px glyph
    // fattens every letter, so the text reads heavier and therefore larger
    // than its nominal size, which is what kept it looking oversized.
    font: { color: '#fff', size: 12, strokeWidth: 4, strokeColor: '#062733', vadjust: -4 },
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
  // is an app that has nothing to do it to. unreferencedLocal (v2.1.6) gets the
  // same treatment for the same visual reason, but stays its own flag - see the
  // colour block above for why it is never merged into n.inert.
  if (n.inert || n.unreferencedLocal) {
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
// Initial whole-hub view - applyFilters() covers every later change, but
// nothing runs it on first load since nodes/edges start populated directly
// via the DataSet constructor above, not through applyFilters() itself.
updateCompactLegend();

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
// The shelf and its label belong to the whole map only.
var shelfDividerShown = true;

// Device node id -> the tags to draw beside it on the next redraw, recomputed
// by applyFilters because "unused" depends on what is currently on screen, not
// on the node alone. focusNodeId is kept alongside it as the pivot the tags
// mirror about.
let nodeTags = {};
let focusNodeId = null;

function shelveInertNodes() {
  // n.unreferencedLocal (v2.1.6) shares this shelf on purpose - both flags
  // mean the same thing to this layout (no edges, physics would fling it to
  // an empty margin) even though they mean different things everywhere else
  // (review 311, correction 3). This filter is the one deliberately generalised
  // spot; state.appsInert, Insights and n.inert's colour/label meaning are
  // untouched.
  const inertIds = ALL_NODES.filter(function (n) { return n.inert || n.unreferencedLocal; })
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
// Grey plate, black text, angled 45 degrees off the icon so it clears the
// node's own label, which vis-network always draws horizontally underneath.
//
// Mirrored about the middle of the view: a node on the left carries its tags
// up-LEFT, one on the right carries them up-RIGHT, so a tag always points away
// from the crowd instead of back through it. Both halves stay readable
// left-to-right - the left side rotates the other way and lays its plates
// backwards from the icon's edge rather than flipping the letters over.
//
// Drawn here rather than baked into the node's icon image because it depends on
// the current filter, and iconImageDataURL caches one bitmap per (icon, colour)
// pair for the whole session. Sizes divide by scale for the same reason the
// shelf label below does - this is graph space, so a fixed font size shrinks to
// nothing when the view zooms out.
const TAG_FONT_PX = 9;
function drawNodeTags(ctx, scale) {
  const ids = Object.keys(nodeTags);
  if (!ids.length) return;
  const pos = network.getPositions(focusNodeId ? ids.concat(focusNodeId) : ids);

  // Which side of the view a node is on. The focused app is the natural pivot
  // when there is one, because the sector layout arranges everything around it;
  // otherwise the centre of the viewport. Both are O(1) - this runs on every
  // redraw, so asking for the position of every node on the map each frame was
  // enough to stall the renderer on the whole-hub view.
  const pivot = (focusNodeId && pos[focusNodeId]) ? pos[focusNodeId].x
                                                  : network.getViewPosition().x;

  ctx.save();
  // Uppercase, Segoe UI, letter-spaced. At 9px the letterforms are doing all
  // the work: lowercase ascenders and descenders collide, generic sans-serif
  // resolves to Arial which is not hinted for this size, and the 45 degree
  // rotation throws away subpixel rendering, so every diagonal stroke aliases.
  // Colour cannot fix any of that - black on this plate is already about 17:1.
  ctx.font = 'bold ' + (TAG_FONT_PX / scale) + 'px "Segoe UI", system-ui, sans-serif';
  // Chrome 99+; older engines ignore it rather than failing, and measureText
  // accounts for it where it applies. Scaled like every other size here.
  ctx.letterSpacing = (0.6 / scale) + 'px';
  ctx.textBaseline = 'middle';
  ctx.textAlign = 'left';
  const padX = 5 / scale;
  const padY = 3 / scale;
  const h = TAG_FONT_PX / scale;
  const gap = 3 / scale;
  ids.forEach(function (id) {
    const p = pos[id];
    if (!p) return;
    const left = p.x < pivot;
    ctx.save();
    // 15 is the device node radius set in styledNode, in these same units.
    ctx.translate(p.x + (left ? -15 : 15), p.y - 3);
    ctx.rotate(left ? (Math.PI / 4) : (-Math.PI / 4));
    let cursor = 0;
    nodeTags[id].forEach(function (tag) {
      const text = tag.toUpperCase();
      const w = ctx.measureText(text).width + padX * 2;
      const x = left ? -(cursor + w) : cursor;
      ctx.fillStyle = '#eef0f3';
      ctx.fillRect(x, -h / 2 - padY, w, h + padY * 2);
      // A defined edge, so the plate does not bleed into the dark canvas.
      ctx.strokeStyle = 'rgba(0,0,0,0.55)';
      ctx.lineWidth = 1 / scale;
      ctx.strokeRect(x, -h / 2 - padY, w, h + padY * 2);
      ctx.fillStyle = '#0d0d0d';
      ctx.fillText(text, x + padX, 0);
      cursor += w + gap;
    });
    ctx.restore();
  });
  ctx.restore();
}

network.on('afterDrawing', function (ctx) {
  const scale = network.getScale() || 1;
  drawNodeTags(ctx, scale);
  if (!shelfDivider || !shelfDividerShown) return;
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

// vis-network's fit() will not zoom in past 1.0 unless told it may, so a view
// holding a handful of nodes was left at 1:1 in a full-size canvas and read as
// tiny. Whole-hub views compute far below 1.0 anyway, so this cap only ever
// applies to a narrowed view. Held as state rather than passed at each call
// site so settle(), the resize refit and the focused-app path cannot disagree
// about the zoom of the same view.
//
// Capped at 1.05, not left at 2.0 (Gordon, 2026-09-09): node label font is
// 12 world-px (see styledNode), which lives in this same zoomable canvas
// space, not screen pixels - at the old 2.0x it rendered around 26 screen-px,
// nearly double #controls button text's fixed, non-zooming 14px. The rule is
// that automatic framing must never make canvas text outshout the chrome
// around it. 12 * 1.05 = 12.6, clearly under 14. The first attempt at this
// paired the cap with a 13px font for 13.65, which was under 14 arithmetically
// but still read as larger once the 5px label stroke was accounted for - the
// font and the stroke were reduced rather than the cap lowered further.
// This governs only the app's OWN automatic zoom-to-fit;
// the user's own manual scroll-zoom can still exceed it deliberately, the
// same way zooming into any map image enlarges its own labels.
const FOCUS_MAX_ZOOM = 1.05;
let currentFitOptions = { animation: false };

// The area of the canvas actually free to draw in, in container pixels. Every
// panel is drawn OVER the canvas rather than beside it, so framing against the
// full width slid content underneath whichever ones were open - the controls
// menu on the right in particular, which is always there and was never
// accounted for. Measured from real rects, so a panel that changes width does
// not need this to be updated.
function visibleRegion(container) {
  const box = container.getBoundingClientRect();
  const width = container.clientWidth;
  const height = container.clientHeight;
  let left = 0;
  let right = width;
  const shown = function (el) {
    if (!el) return false;
    const cs = getComputedStyle(el);
    return cs.display !== 'none' && cs.visibility !== 'hidden';
  };
  const consider = function (el) {
    if (!shown(el)) return;
    const r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return;
    const l = r.left - box.left;
    const rt = r.right - box.left;
    // Decide the side an overlay sits on by its own midpoint, so this keeps
    // working if a panel is ever re-anchored.
    if ((l + rt) / 2 < width / 2) {
      if (rt > left) left = rt;
    } else if (l < right) {
      right = l;
    }
  };
  consider(document.getElementById('legend'));
  consider(document.getElementById('controls'));
  allPanels().forEach(consider);
  return { left: left, right: right };
}

${''}
// Breathing room in screen pixels. Node labels are already inside the
// measured content box (see getBoundingBox below), so this is margin only,
// not an allowance for anything unmeasured.
const VIEW_PADDING_PX = 28;

// Frames the current nodes arithmetically instead of calling network.fit(),
// which frames centres only, will not zoom past 1.0, and knows nothing about
// the panels drawn over the canvas. Every term here is already known: the node
// bounding box, the usable area, and the zoom cap.
function fitCurrentView() {
  const container = document.getElementById('network');
  const ids = nodes.getIds();
  if (!container || !ids.length) return;
  const canvasW = container.clientWidth;
  const canvasH = container.clientHeight;
  if (!canvasW || !canvasH) return;

  // getBoundingBox() gives each node's real drawn extent INCLUDING its label,
  // which getPositions() does not: a node with a three-line label reaches about
  // 69 units below its centre against 17 above, and framing on centres alone is
  // what left those labels cut off at the canvas edge. Falls back to the bare
  // position if a node has no box yet.
  const pos = network.getPositions(ids);
  let minX = null, maxX = null, minY = null, maxY = null;
  ids.forEach(function (id) {
    let box = null;
    try { box = network.getBoundingBox(id); } catch (e) { box = null; }
    if (!box || !isFinite(box.left) || !isFinite(box.top)) {
      const p = pos[id];
      if (!p) return;
      box = { left: p.x, right: p.x, top: p.y, bottom: p.y };
    }
    if (minX === null || box.left < minX) minX = box.left;
    if (maxX === null || box.right > maxX) maxX = box.right;
    if (minY === null || box.top < minY) minY = box.top;
    if (maxY === null || box.bottom > maxY) maxY = box.bottom;
  });
  if (minX === null) return;

  const region = visibleRegion(container);
  const usableW = (region.right - region.left) - VIEW_PADDING_PX * 2;
  const usableH = canvasH - VIEW_PADDING_PX * 2;
  if (usableW <= 0 || usableH <= 0) return;

  // A single node, or a row of them, has no extent on one axis - fall back to
  // the cap on that axis rather than dividing by zero.
  const contentW = maxX - minX;
  const contentH = maxY - minY;
  const cap = currentFitOptions.maxZoomLevel || 1;
  const scale = Math.min(
    contentW > 0 ? usableW / contentW : cap,
    contentH > 0 ? usableH / contentH : cap,
    cap
  );
  if (!(scale > 0) || !isFinite(scale)) return;

  // Centre the content on the free region rather than on the canvas: shift by
  // how far that region's own centre sits from the canvas centre.
  const offsetPx = (region.left + region.right) / 2 - canvasW / 2;
  network.moveTo({
    position: { x: (minX + maxX) / 2 - offsetPx / scale, y: (minY + maxY) / 2 },
    scale: scale,
    animation: false
  });
}

// Panels are populated AFTER being shown (bringToFront then extLoad/iconsLoad),
// so framing at the moment one opens measures an empty panel and the content
// then grows over the graph. Their close buttons do not reframe either, so the
// freed space was never reclaimed. Rather than chase every open/load/close
// site and miss the next one, watch the overlays themselves and reframe
// whenever their geometry changes. Debounced, because a panel rendering a long
// table resizes many times in a row.
let panelResizeTimer = null;
function watchOverlayGeometry() {
  if (typeof ResizeObserver === 'undefined') return;
  const observer = new ResizeObserver(function () {
    if (panelResizeTimer) clearTimeout(panelResizeTimer);
    panelResizeTimer = setTimeout(fitCurrentView, 120);
  });
  // moveTo() cannot change a panel's size, so this cannot feed itself.
  [document.getElementById('legend'), document.getElementById('controls')]
    .concat(allPanels())
    .forEach(function (el) { if (el && el.nodeType === 1) observer.observe(el); });
}
// Deferred one tick: the panel consts are declared much further down this
// script, so they do not exist yet at this point in the file.
setTimeout(watchOverlayGeometry, 0);

// shelve is false for any narrowed view: the shelf belongs to the whole-hub
// map only. shelveInertNodes() reads ALL_NODES and ends in nodes.update(),
// which is an upsert, so running it against a focused dataset silently added
// every inert node back and collapsed the fit to a fraction of its scale.
// Each drawn view owns the canvas until the next one is drawn, whether or not it
// settles. vis delivers stabilizationIterationsDone to every pending listener, so
// an older settle's listener or timer would otherwise shelve, frame or reveal a
// view it no longer owns.
var settleSeq = 0;
function revealNetwork() {
  const el = document.getElementById('network');
  if (el) el.style.opacity = '';
}
function layoutView(placed, wholeMap) {
  const owner = ++settleSeq;
  if (placed) {
    fitCurrentView();
    revealNetwork();
  } else {
    network.setOptions({ physics: { enabled: true } });
    settle(wholeMap, owner);
  }
}
function settle(shelve, owner) {
  // A narrowed view rebuilds the DataSet with physics live, so the nodes are
  // watched flying apart and the framing then snaps the view back. The page's
  // own first settle never shows that because it happens before anything is
  // drawn. So hide the canvas for the duration and reveal it already framed:
  // the correction stops being something to watch.
  //
  // opacity, NEVER display:none - hiding by display collapses the container to
  // zero width, and fitCurrentView() measures that container to decide the
  // scale. It would frame against nothing and bail out.
  const canvasEl = document.getElementById('network');
  if (!shelve && canvasEl) canvasEl.style.opacity = '0';
  const mySettle = owner === undefined ? ++settleSeq : owner;
  let finished = false;
  const finish = function () {
    if (finished || mySettle !== settleSeq) return;
    finished = true;
    network.setOptions({ physics: { enabled: false } });
    if (shelve) shelveInertNodes();
    fitCurrentView();
    revealNetwork();
  };
  network.once('stabilizationIterationsDone', finish);
  // vis does not always emit that event, and when it does not the fit never
  // runs and the view keeps the previous zoom. Measured live on a device
  // focus: six nodes left at whole-hub scale in a three-pixel blob. The
  // fallback is deliberately limited to narrowed views - shelve is true only
  // for the whole-hub map, whose startup path has been broken twice by changes
  // around this and is left exactly as it was. The timer only fires when the
  // event did not, and finish() is guarded so both routes cannot run it twice.
  if (!shelve) {
    // Run the layout to rest before framing. Measured on the Dev hub, the timer
    // alone froze a Hub Variable 41px from its connector, labels overlapping.
    network.stabilize(200);
    setTimeout(finish, 1500);
    // Last resort. finish() already reveals and is guarded, but the canvas must
    // never be left invisible if anything above throws.
    setTimeout(function () { if (mySettle === settleSeq) revealNetwork(); }, 4000);
  }
}
settle(true);

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
    // A focus picked before the first settle finished is kept, not reset.
    if (currentFocus()) return;
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
  refitTimer = setTimeout(fitCurrentView, 200);
});

// Three passes, not one, so a hasComponent (device-owned component) edge
// shows up in focus views it doesn't itself touch directly:
// 1. One-hop edges touching the focus node directly - unchanged behaviour
//    for every other edge kind.
// 2. A visible hasComponent child pulls in its not-yet-visible parent, plus
//    that edge - one-directional and not recursive, so adding the parent
//    here does not itself cascade to the parent's other children. Covers an
//    app that touches a component child but never references the parent.
// 3. Backfill any hasComponent edge where both ends are already visible
//    (from pass 1, pass 2, or the focus node itself) - catches a second
//    app-touched sibling of a parent pass 2 just added, regardless of which
//    order pass 2 happened to visit edges in.
function neighborhood(nodeId, edgePool) {
  const ids = {};
  ids[nodeId] = true;
  const edgeList = [];
  const addedKeys = {};
  const addEdge = function (e) {
    const key = e.from + '|' + e.to + '|' + e.kind;
    if (addedKeys[key]) return;
    addedKeys[key] = true;
    edgeList.push(e);
  };

  edgePool.forEach(function (e) {
    if (e.from === nodeId || e.to === nodeId) {
      ids[e.from] = true; ids[e.to] = true;
      addEdge(e);
    }
  });

  edgePool.forEach(function (e) {
    if (e.kind !== 'hasComponent') return;
    if (ids[e.to] && !ids[e.from]) {
      ids[e.from] = true;
      addEdge(e);
    }
  });

  edgePool.forEach(function (e) {
    if (e.kind !== 'hasComponent') return;
    if (ids[e.from] && ids[e.to]) {
      addEdge(e);
    }
  });

  return { ids: ids, edgeList: edgeList };
}

// The inert shelf belongs to the whole map. In a narrowed view a pinned inert node
// would stay at its shelf coordinates, far from the neighbour it is shown beside.
function releaseShelfPins(styled) {
  styled.forEach(function (s) {
    if (!INERT_POS[s.id] || !s.fixed) return;
    delete s.x;
    delete s.y;
    s.fixed = false;
    s.physics = true;
  });
}

// The edges a Show filter keeps. Rule links and variable use each span several kinds.
function edgesForKindFilter(kindVal, edges) {
  if (kindVal === 'all') return edges;
  if (kindVal === 'rulelinks') return edges.filter(function (e) { return RULE_LINK_KINDS.indexOf(e.kind) !== -1; });
  if (kindVal === 'variables') return edges.filter(function (e) { return VARIABLE_KINDS.indexOf(e.kind) !== -1; });
  return edges.filter(function (e) { return e.kind === kindVal; });
}

function applyFilters() {
  const appVal = appSelect.getValue();
  const devVal = deviceSelect.getValue();
  const hubVarVal = hubVarSelect.getValue();
  const localVarVal = localVarSelect.getValue();
  const kindVal = document.getElementById('kindFilter').value;

  const pool = edgesForKindFilter(kindVal, ALL_EDGES);

  let ids = null;
  let shownEdges = pool;
  const focusId = currentFocus();
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

  // Tags drawn beside a device icon. Two kinds:
  //
  //   disabled  the hub has the device switched off, so nothing reaches it
  //   unused    every relationship visible in THIS view is a constraint that
  //             nothing evaluates
  //
  // Scoping "unused" to the drawn edges is what keeps the claim honest in both
  // views: with an app focused those edges are that app's own, which is the
  // question being asked; on the whole map a device that is a live trigger for
  // some other rule still has a live edge and so is never tagged, even though
  // one rule holds a dead condition on it.
  nodeTags = {};
  focusNodeId = focusId || null;
  // Focused views only. On the whole hub these read as free-floating labels -
  // a handful of tags scattered across 350 nodes, one of them out in open space
  // with no visible owner - and at fit-everything zoom the plate is bigger than
  // the node it belongs to, so it dominates a view whose job is shape and
  // density rather than per-device detail.
  if (focusId) {
    const deviceIds = {};
    shownNodes.forEach(function (n) {
      if (n.group !== 'device') return;
      deviceIds[n.id] = true;
      if (n.disabled) nodeTags[n.id] = ['disabled'];
    });
    const seenByNode = {};
    shownEdges.forEach(function (e) {
      [e.from, e.to].forEach(function (id) {
        if (!deviceIds[id]) return;
        if (!seenByNode[id]) seenByNode[id] = { total: 0, dead: 0 };
        seenByNode[id].total++;
        if (e.kind === 'constraint' && e.unused) seenByNode[id].dead++;
      });
    });
    Object.keys(seenByNode).forEach(function (id) {
      const c = seenByNode[id];
      if (c.total > 0 && c.total === c.dead) nodeTags[id] = (nodeTags[id] || []).concat('unused');
    });
  }
  const styled = shownNodes.map(function (n) { return styledNode(n, !!focusId, roleByDevice); });
  if (ids !== null) releaseShelfPins(styled);

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
  updateCompactLegend(shownNodes);

  // ids is null only when nothing is focused AND the relationship filter is
  // "all" - exactly the start-up / Show all view the shelf belongs to. Any
  // narrowed view skips the shelf and is allowed to magnify instead.
  const wholeMap = (ids === null);
  shelfDividerShown = wholeMap;
  currentFitOptions = wholeMap ? { animation: false }
                               : { animation: false, maxZoomLevel: FOCUS_MAX_ZOOM };

  layoutView(placed, wholeMap);
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
// Every relationship kind that has a PROVEN direction belongs in a sector. A
// kind missing here is not neutral: it falls through to fallbackSector(), which
// sends anything that is not a rule or an external system to 'outputs'. That is
// how variable reads and webCoRE device reads ended up drawn on the output side
// with the actions, so a piston (which is almost entirely reads and writes)
// collapsed into one fan on the right instead of splitting input/output the way
// every Rule Machine and VRB panel does. Affects Rule Machine variable reads
// and writes equally - it was never webCoRE-specific.
//
// usesVar is deliberately absent: its direction is explicitly unproven, and
// placing it on either side would assert one. It keeps the fallback.
const SECTORS = [
  { name: 'external', kinds: ['depends'],                                             from: 55,  to: 125, radius: 430 },
  { name: 'inputs',   kinds: ['trigger', 'constraint', 'monitor', 'read', 'deviceRead'], from: 145, to: 215, radius: 300 },
  { name: 'rules',    kinds: RULE_LINK_KINDS,                                         from: 235, to: 305, radius: 420 },
  { name: 'outputs',  kinds: ['action', 'owns', 'exposed', 'write'],                  from: 325, to: 395, radius: 320 },
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
// Gate C (v2.1.4): owner-scoped Local Variable definitions and classified
// references, keyed the same way as FLOWS. Raw Groovy shape (canonicalName/
// scope/status/candidateScopes/evidence), not the export's renamed fields -
// the AI export builds its own shape from the same GRAPH.ruleVariables
// separately, see buildExportPayload().
const RULE_VARIABLES = GRAPH.ruleVariables || {};
if (window.mermaid) {
  // A bare top-level fontSize option does nothing on this pinned mermaid
  // build (10.9.8) - confirmed live, still measured 16px with it set. The
  // theme's own themeVariables.fontSize is what actually reaches the
  // rendered node text; confirmed live via mermaid.render() directly before
  // changing this, not assumed. Added per Gordon's request - the rendered
  // node text (mermaid's own 16px default) was the dominant reason the
  // panel ran large.
  mermaid.initialize({ startOnLoad: false, theme: 'dark', flowchart: { useMaxWidth: false }, themeVariables: { fontSize: '12px' } });
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
    // The map's own constraint colour, not a fourth blue of this chart's own.
    // A condition was teal on the map and blue in the chart beside it, which is
    // the same contradiction the device-read roles fixed. A required expression
    // shares it: the map gives both one colour, and the shape tells them apart.
    else if (kind === 'cond') styles.push('  style ' + id + ' fill:#0f4f45,stroke:#16a085,color:#fff');
    else if (kind === 'end') styles.push('  style ' + id + ' fill:#2b2b2b,stroke:#777,color:#bbb');
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

  // A decision whose false path leads nowhere otherwise draws as a diamond with
  // one exit, which reads as a decision that was never made. Once more than one
  // end is open, they are joined to an explicit end node so every branch
  // visibly terminates - including the "no" that simply does nothing.
  if (tails.length > 1) {
    connect(emit('stadium', 'end', 'end'));
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

// Draggable flow panel (Gordon's live request): defaults to sitting below
// the legend on first open each page load, then stays wherever the user
// drags it for every later open - repositioning it back to default on
// every focus change would make dragging pointless, since this panel closes
// and reopens on almost every click.
// Generalized from the flow-panel-only version (same day): Gordon wants
// every modernPanel panel draggable with this same below-the-legend
// default, not just flow. A WeakMap rather than five separate booleans -
// keyed by the panel element itself, so adding a sixth panel later needs no
// new flag variable, just one more entry in the setup list near the bottom
// of this script (after every panel's own const exists).
const panelCustomPosition = new WeakMap();
// Measures the real #status pill and #controls rail rather than guessing a
// viewport-relative number for any of left/top/width/height - the CSS
// fallback that used to size this (calc(100vw - 340px)) overlapped the
// control rail live once the actual numbers did not match what 340
// assumed. left/top sit just clear of #status (top-left) rather than the
// legend now that the legend hides for every panel including flow (no
// legend visible to anchor against at open time in the common case); width
// stops short of #controls' own left edge, height reaches the bottom of
// the viewport - the same free-area idea visibleRegion() already applies
// to the graph itself, applied here to a panel instead.
// #flow is the one panel that shows genuinely different shapes of content
// (Insights, a rule flowchart, an inert-app summary, an unreferenced local
// variable) - only Insights should use the large shared display area, per
// Gordon's explicit correction live ("the workflow panel must remain as it
// was"). Called right before bringToFront(flowPanel) at every one of
// #flow's four opening call sites, so sizeModernPanel() (which reads
// modernPanelLarge) always sees the right mode for what is about to show.
function setFlowSizeMode(large) {
  flowPanel.classList.toggle('modernPanelLarge', large);
  flowPanel.classList.toggle('flowClassicSize', !large);
  // sizeModernPanel() only ever set width for the large case (height is now
  // always set, both modes - see its own comment), and had no reason to
  // clear it again, so once #flow had been opened large even once its
  // inline width stuck around afterwards. CSS max-width then merely capped
  // that leftover value instead of the panel ever going back to
  // shrink-wrapping its own content, so a narrow rule flowchart still
  // rendered at the full 900px cap - most of it empty space - instead of
  // the width its own content actually needs. Clearing it here hands width
  // back to .flowClassicSize's max-width and the content itself, exactly as
  // if the panel had never been large at all.
  if (!large) {
    flowPanel.style.width = '';
    if (!startFlowItemIfNew()) {
      restoreFlowUserPosition();
      applyFlowUserSize();
    }
  } else if (flowUserSize) {
    // Insights sizes itself to the full work area. A size chosen for the
    // normal view is not carried into it, so remember where the normal view
    // was, once, and give the panel the measured sizing of a first open.
    if (!flowUserPosition) flowUserPosition = { left: flowPanel.style.left, top: flowPanel.style.top };
    clearFlowInlineSize();
    sizeModernPanel(flowPanel);
  }
  if (large) clearFlowZoomStyle(); else applyFlowZoom();
}
function sizeModernPanel(panel) {
  const statusEl = document.getElementById('status');
  const controlsEl = document.getElementById('controls');
  const statusRect = statusEl ? statusEl.getBoundingClientRect() : null;
  const controlsRect = controlsEl ? controlsEl.getBoundingClientRect() : null;
  const gap = 14;
  const left = 10;
  // Classic-mode #flow is the one panel that opens with the legend still
  // visible beside it (syncLegendVisibility), so status-bar-only positioning
  // put it directly on top of the legend - same left:10px corner, same
  // area, legend simply painted underneath. Give it the legend's actual
  // bottom edge too (rect stays accurate even mid-transition, since
  // visibility:hidden still lays the legend out) so it opens below the
  // legend instead of over it, whatever the legend's current content height.
  const legendEl = document.getElementById('legend');
  const isFlowClassic = panel === flowPanel && !panel.classList.contains('modernPanelLarge');
  const legendBottom = (isFlowClassic && legendEl) ? legendEl.getBoundingClientRect().bottom : 0;
  const top = Math.max(statusRect ? statusRect.bottom : 45, legendBottom) + gap;
  panel.style.left = left + 'px';
  panel.style.top = top + 'px';
  // A large panel keeps a fixed height: it is a full-area workspace and should
  // fill it. Classic #flow instead shrinks to its own content and caps, so a
  // short panel ends where its content ends rather than trailing into dead
  // space down to the viewport bottom.
  //
  // Why max-height works here when .flowClassicSize's own max-height:90vh did
  // not: 90vh is measured against the viewport, not against where this panel
  // starts, so a panel opening at top:300px could still be 90vh tall and spill
  // past the bottom - which is the failure the always-set height was added to
  // fix. This value is the actual space remaining below `top`, so the cap binds
  // at the right place, the container height becomes definite once content
  // exceeds it, and .panelBody's flex:1 + min-height:0 + overflow:auto scrolls
  // exactly as before.
  const available = Math.max(200, window.innerHeight - top - 10);
  if (panel.classList.contains('modernPanelLarge')) {
    panel.style.maxHeight = '';
    panel.style.height = available + 'px';
  } else {
    panel.style.height = '';
    panel.style.maxHeight = available + 'px';
  }
  // Width stays classic mode's own business - only the large-area case
  // (.modernPanelLarge) forces one. #flow in its classic mode (a rule
  // flowchart/inert app/unreferenced variable, .flowClassicSize instead)
  // sizes its width from its own CSS max-width and its actual content, same
  // as before backlog item 1's "unify all five panels" change gave it a
  // forced pixel size it was never meant to have.
  if (!panel.classList.contains('modernPanelLarge')) return;
  const rightEdge = controlsRect ? controlsRect.left : (window.innerWidth - 320);
  panel.style.width = Math.max(200, rightEdge - left - gap) + 'px';
}
function makePanelDraggable(panel, header) {
  if (!header || !panel || typeof panel.getBoundingClientRect !== 'function') return;
  let dragging = false, startX = 0, startY = 0, startLeft = 0, startTop = 0;
  header.addEventListener('mousedown', function (e) {
    // The close button lives inside this same bar - a click there must
    // close the panel, not start a drag.
    if (e.target.closest('.panelClose')) return;
    dragging = true;
    panelCustomPosition.set(panel, true);
    const rect = panel.getBoundingClientRect();
    startX = e.clientX; startY = e.clientY;
    startLeft = rect.left; startTop = rect.top;
    e.preventDefault();
  });
  document.addEventListener('mousemove', function (e) {
    if (!dragging) return;
    panel.style.left = (startLeft + (e.clientX - startX)) + 'px';
    panel.style.top = (startTop + (e.clientY - startY)) + 'px';
  });
  document.addEventListener('mouseup', function () {
    if (!dragging) return;
    dragging = false;
    // Dragging only moves the panel, never resizes it, so the
    // ResizeObserver watchOverlayGeometry() relies on elsewhere never
    // fires for this - but visibleRegion() decides free canvas space from
    // every panel's own position too, not just its size, so a narrowed
    // view can genuinely have more or less room after a drag. One fit once
    // the drag actually ends, not on every mousemove.
    fitCurrentView();
  });
}
makePanelDraggable(flowPanel, document.getElementById('flowHeader'));
// Flow panel resize (v2.2.9). The normal flow view can carry a lot of data, so
// it can be resized from its corner grip as well as dragged by its header. A
// user size lasts for the page session, the same as a dragged position, and is
// never carried into the full-area Insights view, which sizes itself.
// Double-clicking the header puts size and position back to their defaults.
// var rather than let: setFlowSizeMode reads this, and a let would throw if a
// panel ever opened before this line had run.
var flowUserSize = null;
// Where the normal view was when Insights re-placed the panel, so returning
// from Insights puts it back rather than leaving it at the Insights position.
var flowUserPosition = null;
// The item the normal view was last placed for, and the item now being shown.
// When they differ a new item was picked, and the panel starts again from the
// default position.
var flowItemId = null;
var flowShownItemId = null;
const FLOW_MIN_WIDTH = 280;
const FLOW_MIN_HEIGHT = 160;

function clampFlowSize(width, height, rect) {
  const maxWidth = Math.max(FLOW_MIN_WIDTH, window.innerWidth - rect.left - 10);
  const maxHeight = Math.max(FLOW_MIN_HEIGHT, window.innerHeight - rect.top - 10);
  return {
    width: Math.round(Math.min(Math.max(width, FLOW_MIN_WIDTH), maxWidth)),
    height: Math.round(Math.min(Math.max(height, FLOW_MIN_HEIGHT), maxHeight))
  };
}

function applyFlowUserSize() {
  if (!flowUserSize) return;
  // The normal view caps its width to the left column and its height to the
  // space below it. A size the user chose deliberately replaces both caps, and
  // the text sections follow it instead of staying column-narrow.
  flowPanel.classList.add('flowUserSized');
  flowPanel.style.maxWidth = 'none';
  flowPanel.style.maxHeight = 'none';
  flowPanel.style.width = flowUserSize.width + 'px';
  flowPanel.style.height = flowUserSize.height + 'px';
}

function clearFlowInlineSize() {
  flowPanel.classList.remove('flowUserSized');
  flowPanel.style.maxWidth = '';
  flowPanel.style.maxHeight = '';
  flowPanel.style.width = '';
  flowPanel.style.height = '';
}

function makeFlowResizable(panel, grip) {
  if (!grip || !panel || typeof panel.getBoundingClientRect !== 'function') return;
  let resizing = false, startX = 0, startY = 0, startWidth = 0, startHeight = 0;
  grip.addEventListener('mousedown', function (e) {
    if (panel.classList.contains('modernPanelLarge')) return;
    resizing = true;
    const rect = panel.getBoundingClientRect();
    startX = e.clientX; startY = e.clientY;
    startWidth = rect.width; startHeight = rect.height;
    // Keep the panel where it is on its next open, as a drag does. Without
    // this the first-open sizing would cap the new height straight back.
    panelCustomPosition.set(panel, true);
    e.preventDefault();
    e.stopPropagation();
  });
  document.addEventListener('mousemove', function (e) {
    if (!resizing) return;
    flowUserSize = clampFlowSize(startWidth + (e.clientX - startX), startHeight + (e.clientY - startY),
      panel.getBoundingClientRect());
    applyFlowUserSize();
  });
  document.addEventListener('mouseup', function () {
    resizing = false;
  });
}

function restoreFlowUserPosition() {
  if (!flowUserPosition) return;
  flowPanel.style.left = flowUserPosition.left;
  flowPanel.style.top = flowUserPosition.top;
  flowUserPosition = null;
  clampFlowPosition();
  // The window may have changed while Insights was open.
  if (flowUserSize) flowUserSize = clampFlowSize(flowUserSize.width, flowUserSize.height, flowPanel.getBoundingClientRect());
}

// Called wherever an item opens in the flow panel, before the panel is sized.
function noteFlowItem(node) {
  flowShownItemId = node && node.id !== undefined && node.id !== null ? String(node.id) : null;
}

function flowStylePosition() {
  return { left: parseFloat(flowPanel.style.left) || 0, top: parseFloat(flowPanel.style.top) || 0 };
}

// The header is the only way to move the panel back, so it may never sit off
// screen, in either view of this panel. Reads the inline position rather than
// the rendered box, so it works while the panel is still hidden.
function clampFlowPosition() {
  const header = document.getElementById('flowHeader');
  const headerHeight = (header && header.offsetHeight) || 40;
  const width = flowPanel.offsetWidth || parseFloat(flowPanel.style.width) || 375;
  const pos = flowStylePosition();
  const left = Math.round(Math.min(Math.max(pos.left, 0), Math.max(0, window.innerWidth - width)));
  const top = Math.round(Math.min(Math.max(pos.top, 0), Math.max(0, window.innerHeight - headerHeight)));
  const changed = left !== Math.round(pos.left) || top !== Math.round(pos.top);
  flowPanel.style.left = left + 'px';
  flowPanel.style.top = top + 'px';
  return changed;
}

// Every newly picked item starts the normal view at its default size and position,
// so its edges line up with the legend. Only reopening the same item keeps a chosen size.
// Keyed on the item actually shown, not on focusGenerationSeq, which also moves
// when the panel is closed or replaced by Insights. Reopening the same item leaves
// the position alone. Returns true when it placed the panel.
function startFlowItemIfNew() {
  if (flowItemId === flowShownItemId) return false;
  flowItemId = flowShownItemId;
  flowUserPosition = null;
  flowUserSize = null;
  // Zoom is part of the default layout, exactly as resetFlowPanelLayout()
  // treats it. Without this a new item kept the previous item's zoom, which
  // looks like the panel failing to go back to its default size - the chosen
  // size was cleared but the content was still being scaled.
  flowZoom = 1;
  clearFlowZoomStyle();
  clearFlowInlineSize();
  panelCustomPosition.delete(flowPanel);
  return true;
}

function resetFlowPanelLayout() {
  flowUserSize = null;
  flowUserPosition = null;
  flowZoom = 1;
  clearFlowZoomStyle();
  panelCustomPosition.delete(flowPanel);
  clearFlowInlineSize();
  sizeModernPanel(flowPanel);
}

makeFlowResizable(flowPanel, document.getElementById('flowResize'));

const flowResizeHeader = document.getElementById('flowHeader');
if (flowResizeHeader) {
  flowResizeHeader.addEventListener('dblclick', function (e) {
    if (e.target.closest('.panelClose')) return;
    resetFlowPanelLayout();
  });
}

// Keeps the header inside the window while it is being dragged. These run after
// makePanelDraggable has moved the panel, because they were registered after it,
// so the shared drag helper and the other panels are left exactly as they were.
let flowHeaderDragging = false;
if (flowResizeHeader) {
  flowResizeHeader.addEventListener('mousedown', function (e) {
    if (!e.target.closest('.panelClose')) flowHeaderDragging = true;
  });
}
document.addEventListener('mousemove', function () {
  if (flowHeaderDragging) clampFlowPosition();
});
document.addEventListener('mouseup', function () {
  if (!flowHeaderDragging) return;
  flowHeaderDragging = false;
  // The drag helper has already re-fitted the map; do it again only if the
  // clamp moved the panel afterwards.
  if (clampFlowPosition()) fitCurrentView();
});

// A smaller window could otherwise leave the header or the grip out of reach.
window.addEventListener('resize', function () {
  if (flowPanel.style.display !== 'flex') return;
  clampFlowPosition();
  if (!flowUserSize || flowPanel.classList.contains('modernPanelLarge')) return;
  flowUserSize = clampFlowSize(flowUserSize.width, flowUserSize.height, flowPanel.getBoundingClientRect());
  applyFlowUserSize();
});
// Panel zoom. Ctrl with the mouse wheel over the normal flow view zooms its
// content instead of the whole page, which a large flowchart needs. Held while
// the same item stays open and cleared when a new one is picked, exactly like a
// chosen size, reset by the header double-click, and not applied to the
// full-area Insights view. The zoom sits on a wrapper inside the
// scrolling body: zooming the body itself would grow the panel, not its content.
var flowZoom = 1;
const FLOW_ZOOM_MIN = 0.5;
const FLOW_ZOOM_MAX = 2.5;

function applyFlowZoom() {
  const inner = document.getElementById('flowZoom');
  if (!inner) return;
  inner.style.zoom = flowZoom === 1 ? '' : String(flowZoom);
  flowPanel.classList.toggle('flowZoomed', flowZoom !== 1);
}

function clearFlowZoomStyle() {
  const inner = document.getElementById('flowZoom');
  if (inner) inner.style.zoom = '';
  flowPanel.classList.remove('flowZoomed');
}

function nextFlowZoom(current, deltaY) {
  // One mouse wheel notch is about 100; a trackpad pinch sends many small steps.
  const step = Math.max(-200, Math.min(200, deltaY));
  const next = current * Math.pow(1.0015, -step);
  return Math.round(Math.min(FLOW_ZOOM_MAX, Math.max(FLOW_ZOOM_MIN, next)) * 100) / 100;
}

function handleFlowWheel(e) {
  if (!e.ctrlKey || flowPanel.classList.contains('modernPanelLarge')) return;
  e.preventDefault();
  const previous = flowZoom;
  flowZoom = nextFlowZoom(flowZoom, e.deltaY);
  if (flowZoom === previous) return;
  const body = document.getElementById('flowBody');
  if (!body) { applyFlowZoom(); return; }
  // Keep the content under the pointer in place as it grows or shrinks.
  const box = body.getBoundingClientRect();
  const offsetX = e.clientX - box.left;
  const offsetY = e.clientY - box.top;
  const contentX = body.scrollLeft + offsetX;
  const contentY = body.scrollTop + offsetY;
  applyFlowZoom();
  const ratio = flowZoom / previous;
  body.scrollLeft = contentX * ratio - offsetX;
  body.scrollTop = contentY * ratio - offsetY;
}

// Non-passive, or the browser ignores preventDefault and zooms the page anyway.
if (typeof flowPanel.addEventListener === 'function') {
  flowPanel.addEventListener('wheel', handleFlowWheel, { passive: false });
}
// End flow panel resize.
// Legend is entirely static markup - no *Load() function, nothing to fetch
// or rebuild on open - so declared here rather than beside ext/pivot/icons's
// own dynamic-render code further down the file.
const legendPanel = document.getElementById('legendPanel') || { style: {} };

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
// Correction, same day: the legend hiding itself while a panel is open was
// tried and explicitly rejected live - Gordon wants the legend to stay put
// regardless of what else is open, not disappear. #flow is what moved
// instead (see its own CSS - floating/centred rather than corner-pinned at
// the legend's own top:10px/left:10px corner), so the two no longer share
// space in the first place and neither needs to hide for the other. #hint
// still hides for any open panel, unchanged from before any of this.
${''}
// Single source of truth for panel coordination - bringToFront,
// syncLegendVisibility and closeSecondaryPanels all read it, so a new panel is
// coordinated everywhere at once. Functions rather than a const array so
// declaration order does not matter.
//
// flowPanel is deliberately outside secondaryPanels(): its callers hide it
// themselves, since several re-open it a moment later with new content.
function secondaryPanels() { return [extPanel, pivotPanel, iconsPanel, releaseActivityPanel, legendPanel]; }
function allPanels() { return [flowPanel].concat(secondaryPanels()); }

function syncLegendVisibility() {
  const lg = document.getElementById('legend');
  const hn = document.getElementById('hint');
  // Every panel hides the legend while open, EXCEPT #flow specifically
  // when it is in its classic size mode (a rule flowchart, inert app, or
  // unreferenced local variable - see setFlowSizeMode()). That mode is
  // small and positioned to stay clear of the legend's own corner, same as
  // it was before this session's "unify all five panels" change - Gordon
  // caught this regression live: reverting #flow's classic mode back to
  // its old size (a separate, earlier fix) left this function still
  // hiding the legend for it anyway, because the comment this replaced
  // was written when every #flow open was still the large case and had no
  // reason to distinguish. #flow in its large mode (Insights) still hides
  // the legend, same as every other panel.
  const panelOpen = allPanels().some(function (p) {
    if (!p || getComputedStyle(p).display === 'none') return false;
    if (p === flowPanel && !flowPanel.classList.contains('modernPanelLarge')) return false;
    return true;
  });
  if (lg) lg.style.visibility = panelOpen ? 'hidden' : '';
  if (hn) hn.style.visibility = panelOpen ? 'hidden' : '';
}

// Legend/hint syncing used to be left to three of the four buttons to do for
// themselves (extBtn/iconsBtn/pivotBtn each set visibility:hidden before
// calling this) - Insights and a node click never did, so the legend could
// sit visibly behind those. Doing it here instead covers all four the same
// way, and only in one place.
let panelTopZ = 30;
function bringToFront(panel) {
  // Opening any other panel hides the flow panel, so a render still pending
  // for the flow panel must not come back and replace it.
  if (panel !== flowPanel) beginSelectionGeneration();
  allPanels().forEach(function (p) {
    if (p && p !== panel) p.style.display = 'none';
  });
  panelTopZ += 1;
  panel.style.zIndex = panelTopZ;
  // Sized/positioned against the real status pill and control rail, first
  // open only for each individual panel - see sizeModernPanel()/
  // panelCustomPosition's own comments for why this doesn't run again once
  // the user has dragged that panel.
  if (panel.classList && panel.classList.contains('modernPanel') && !panelCustomPosition.get(panel)) sizeModernPanel(panel);
  // flex, not block: every panel is now display:flex; flex-direction:column
  // (backlog item 1 Phase 2) so its .panelBody can be the one child that
  // scrolls while the title/close stay fixed - block would still render the
  // panel, but the column layout and .panelBody's flex:1 sizing depend on
  // the parent actually being a flex container.
  panel.style.display = 'flex';
  syncLegendVisibility();
  // The panel now covers part of the canvas, so a narrowed view needs
  // re-framing into what is left. No-ops on the whole-hub map.
  fitCurrentView();
}

// An app that references nothing has no flow to draw, but it is not true that
// there is nothing to say about it. Clicking one used to blank the map to a
// single square and open no panel at all, which reads as a broken click rather
// than as an app with nothing attached.
//
// So it gets a panel of its own: what the hub says it holds, and a way through
// to whatever it holds. For a container that turns a dead end into the most
// direct route to its children on the whole map.
function setFlowSub(text, isWebcoreNotice) {
  const el = document.getElementById('flowSub');
  el.textContent = text;
  el.classList.toggle('webcoreNotice', !!isWebcoreNotice);
  flowPanel.classList.remove('wcIndent');
}

// Called after setFlowSub, which clears the class unconditionally.
function setFlowWebcoreIndent(node) {
  const t = node && node.appType;
  flowPanel.classList.toggle('wcIndent', t === 'webCoRE' || t === 'webCoRE Piston');
}

// v2.2.8: one message per real device-decode coverage outcome, replacing the
// old blanket "no piston device relationships are ever shown" text now that
// direct reads/actions are actually decoded per piston. Deliberately free of
// apostrophes and other non-ASCII punctuation - a stray one already broke
// two other UI strings (v2.2.7) once shipped.
function webcorePistonDeviceCoverageMessage(node) {
  const coverage = node.webcoreDeviceRelationshipCoverage;
  if (coverage === 'error') {
    return 'This piston device configuration could not be decoded safely. Saved Hub Variable and local variable relationships, when present, are still shown below.';
  }
  if (coverage === 'complete') {
    return 'Direct device reads and actions below are decoded from saved configuration. A read in an event or a condition is shown under the trigger or constraint role the piston decoded it in; a read anywhere else, such as an expression or a task parameter, is shown as an unattributed device read.';
  }
  if (coverage === 'partial') {
    return 'Some direct device reads or actions below are decoded from saved configuration. At least one device reference in this piston could not be resolved and is not shown - see Insights for the coverage gap.';
  }
  return 'This piston has saved device configuration with no direct physical-device read or action found in it. A variable-backed or runtime-selected device reference, if present, is not decoded.';
}

function showInertPanel(node) {
  document.getElementById('flowTitle').textContent = appOptionText(node);
  // Two different findings that used to render identically: a fetch that
  // threw leaves the same empty roles/ruleLinks/endpoints as an app that
  // genuinely references nothing, but "the hub would not answer" and "this
  // app really does nothing" are not the same thing to tell a user.
  // v2.2.8: a webCoRE piston only ever reaches this panel when its own
  // device decode was clean AND found genuinely zero device operands (see
  // webcorePistonHasDeviceEvidence in buildGraph) - an ordinary, unremarkable
  // finding, not a coverage gap, so it does not need the red notice styling.
  setFlowSub(node.unreadable ?
    'The hub could not answer for this app during the scan. What it references is unknown, not empty - rescan to try again.' :
    (node.webcoreDeviceRelationshipsSuppressed && node.appType === 'webCoRE' ?
      'webCoRE parent device permissions are not shown because they do not prove which piston reads or controls a device. Select a piston to see its supported decoded Hub Variable and device relationships.' :
      (node.appType === 'webCoRE Piston' ?
        'This piston has saved configuration that was fully decoded and genuinely references no device, Hub Variable or declared local variable.' :
        'This app references no device, links to no rule and publishes no endpoint. What the hub does report about it is below.')), false);
  setFlowWebcoreIndent(node);

  let html = node.unreadable ?
    '<h3>Could not be read</h3><p class="sub">' + extEsc(node.errorDetail || 'No further detail was recorded.') + '</p>' :
    ((node.reason && node.title.indexOf('(' + node.reason + ')') >= 0) ? '' : '<h3>' + extEsc(node.reason || 'References nothing') + '</h3>');
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
  // Gate C (v2.1.4), review 296 correction: without this, selecting a rule
  // with variable evidence and then an inert app left the previous rule's
  // list stale underneath this panel's own content - this path never touched
  // ruleVariablesCard at all. Same centralized renderer as showFlow() uses,
  // so an inert/unreadable app (which has no relationships, variable
  // evidence included) correctly clears the container via that function's
  // own empty-state branch, not a separate ad hoc clear here.
  renderRuleVariablesCard(node.id);
  noteFlowItem(node);
  renderDecodeCoverageCard(node);
  renderCommunityCard(node);
  setFlowSizeMode(false);
  bringToFront(flowPanel);
}

// A Local Variable with no proven decoded reference in this rule (v2.1.6) -
// the same "click opened nothing" problem showInertPanel solved for an app
// that references nothing, but a deliberately separate function (review 311
// correction 3): the content here is variable-specific (which rule declared
// it), not app-specific (schedule/subscription/child-app facts
// showInertPanel reports), and conflating the two risked this eventually
// growing app-shaped fields that make no sense on a variable.
function showUnreferencedLocalPanel(node) {
  const owner = ALL_NODES.filter(function (n) { return n.id === node.ownerAppId; })[0];
  document.getElementById('flowTitle').textContent = localVarOptionText(node);
  setFlowSub(owner ? '' : 'The rule that declared it is no longer on this map.', false);
  flowChart.innerHTML = '<p class="sub">No proven decoded reference in this rule - not read in a trigger, condition or action, and not written.</p>';
  // Both correctly no-op on a non-rule/non-app node (their own group checks
  // already handle that) - called anyway so switching here from a rule with
  // stale content in either card actually clears it, the same discipline
  // the review 296 correction established for showInertPanel above.
  renderRuleVariablesCard(node.id);
  noteFlowItem(node);
  renderDecodeCoverageCard(node);
  renderCommunityCard(node);
  setFlowSizeMode(false);
  bringToFront(flowPanel);
}

function showFlow(appId) {
  // Captured after the caller began this selection's generation.
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
    document.getElementById('flowTitle').textContent = node ? appOptionText(node) : 'App details';
    const isPistonNotice = node && node.appType === 'webCoRE Piston';
    // v2.2.8: direct device reads/actions are now decoded per piston - only
    // the coverage gaps ('partial'/'error') still read as an attention-
    // worthy notice; 'complete' and 'none' are both ordinary outcomes, not
    // something to flag in red.
    // Red only for a real coverage gap. The webCoRE parent sentence is an explanation, not a fault.
    const isWebcoreNotice = isPistonNotice &&
      (node.webcoreDeviceRelationshipCoverage === 'partial' || node.webcoreDeviceRelationshipCoverage === 'error');
    setFlowSub(isPistonNotice
      ? webcorePistonDeviceCoverageMessage(node)
      : (node && node.appType === 'webCoRE' && node.webcoreDeviceRelationshipsSuppressed
        ? 'webCoRE parent device permissions are not shown because they do not prove which piston reads or controls a device. Select a piston to see its supported decoded Hub Variable and device relationships.'
        : 'This app has no decoded rule flow to show.'), isWebcoreNotice);
    setFlowWebcoreIndent(node);
    flowChart.innerHTML = '';
    // Gate C (v2.1.4): a rule can have variable evidence even when its step
    // sequence itself could not be decoded (or genuinely has none) - shown
    // regardless of which branch of this function is taken.
    renderRuleVariablesCard(appId);
    noteFlowItem(node);
    renderDecodeCoverageCard(node);
    renderCommunityCard(node);
    setFlowSizeMode(false);
    bringToFront(flowPanel);
    return;
  }
  document.getElementById('flowTitle').textContent = node ? appOptionText(node) : 'Rule flow';
  // Deliberately free of apostrophes. This page is a Groovy GString, so a
  // backslash-escaped quote is consumed by Groovy and ends the JS string early -
  // a syntax error that kills the entire page.
  setFlowSub('Decoded execution order, reconstructed from the internal state of the app. A reading aid: the app page itself remains the authority.', false);
  flowChart.innerHTML = '';
  const id = 'mmd' + Date.now();
  mermaid.render(id, mermaidFor(steps)).then(function (res) {
    // A newer selection (any type - another app, a device, a hub variable)
    // has already started since this render began. Writing flowChart or
    // re-opening the panel now would silently restore this stale selection
    // over whatever the user has actually picked since.
    if (mySelectionSeq !== focusGenerationSeq) return;
    flowChart.innerHTML = res.svg;
    renderRuleVariablesCard(appId);
    noteFlowItem(node);
    renderDecodeCoverageCard(node);
    renderCommunityCard(node);
    setFlowSizeMode(false);
    bringToFront(flowPanel);
  }).catch(function (err) {
    if (mySelectionSeq !== focusGenerationSeq) return;
    flowChart.textContent = 'Could not render this rule: ' + err.message;
    renderRuleVariablesCard(appId);
    noteFlowItem(node);
    renderDecodeCoverageCard(node);
    renderCommunityCard(node);
    setFlowSizeMode(false);
    bringToFront(flowPanel);
  });
}

// Gate C (v2.1.4). "names, operation, and usage role only, never values" -
// see Supporting Docs/local_hub_variable_identity_proposal.md and WIP/
// local_hub_variable_gate_c_integration_plan.md section 5. Reads the same
// normalized classification records the flow labels themselves were
// corrected against (see correctFlowVariableLabels() in Groovy), so this
// section and the flow chart cannot disagree. Wording for ambiguous/
// unresolved stays neutral - never "broken" unless Rule Machine's own
// persisted marker already says so on the flow step itself.
function renderRuleVariablesCard(appId) {
  const box = document.getElementById('ruleVariablesCard');
  if (!box) return;
  const rv = RULE_VARIABLES[appId];
  const refs = (rv && rv.variableReferences) || [];
  const nonResolved = (rv && rv.nonResolvedVariableReferences) || [];
  const node = ALL_NODES.filter(function (n) { return n.id === appId; })[0];
  const webcoreVariableEdges = ALL_EDGES.filter(function (e) {
    if (!(node && node.appType === 'webCoRE Piston' && e.from === appId)) return false;
    if (!(e.kind === 'read' || e.kind === 'write' || e.kind === 'usesVar')) return false;
    // Hub targets only: this list is headed "Hub" and hard-codes [HVR], and a
    // piston's own locals are already rendered by the generic Local section.
    const t = ALL_NODES.filter(function (n) { return n.id === e.to; })[0];
    return !!t && t.group === 'hubVariable';
  }).map(function (e) {
    const target = ALL_NODES.filter(function (n) { return n.id === e.to; })[0];
    return { name: target ? target.title : e.to, operation: e.kind };
  }).sort(function (a, b) { return a.name.localeCompare(b.name) || a.operation.localeCompare(b.operation); });
  // v2.2.8: direct device reads/actions, same generic edge list every other
  // relationship on this card already reads from - deviceRead is from:
  // app, to: device (matching the generic device-role edge convention);
  // action here is indistinguishable from a Rule Machine action edge by
  // design, since both mean the same thing.
  const webcoreDeviceEdges = ALL_EDGES.filter(function (e) {
    return node && node.appType === 'webCoRE Piston' && e.from === appId &&
      (e.kind === 'deviceRead' || e.kind === 'action');
  }).map(function (e) {
    const target = ALL_NODES.filter(function (n) { return n.id === e.to; })[0];
    return { name: target ? target.title : e.to, icon: target ? target.icon : null, operation: e.kind, attribute: e.attribute, commands: e.commands };
  }).sort(function (a, b) { return a.name.localeCompare(b.name) || a.operation.localeCompare(b.operation); });
  const webcoreIssue = node && node.webcoreVariableDecodeError;
  if (!refs.length && !nonResolved.length && !webcoreVariableEdges.length && !webcoreDeviceEdges.length && !webcoreIssue) { box.innerHTML = ''; return; }

  // tag is the same [XXX] convention as the Focus dropdowns (queue 305/306) -
  // LOC/WCV/HVR reflect only the already-proven scope filter below, never guessed.
  function line(name, operation, usageRole, tag) {
    const op = operation === 'write' ? 'writes' : 'reads';
    const role = usageRole ? ' (' + extEsc(usageRole) + ')' : '';
    const prefix = tag ? '[' + tag + '] ' : '';
    return '<li>' + prefix + extEsc(name) + ' - ' + op + role + '</li>';
  }

  // One visible row per variable, operation and role. The saved references stay one
  // per field for evidence and export; only identical visible rows are merged here.
  function distinctBy(list, keyOf) {
    const seen = {};
    return list.filter(function (r) {
      const key = keyOf(r);
      if (seen[key]) return false;
      seen[key] = true;
      return true;
    });
  }
  const referenceKey = function (r) { return JSON.stringify([r.scope, r.canonicalName || r.name, r.operation, r.usageRole || null]); };

  const localItems = distinctBy(refs.filter(function (r) { return r.scope === 'local'; }), referenceKey)
    .map(function (r) { return line(r.canonicalName || r.name, r.operation, r.usageRole, localVarTag(appId)); });
  const hubItems = distinctBy(refs.filter(function (r) { return r.scope === 'hub'; }), referenceKey)
    .map(function (r) { return line(r.canonicalName || r.name, r.operation, r.usageRole, 'HVR'); });
  const reviewItems = distinctBy(nonResolved, function (r) { return JSON.stringify([r.name, r.operation, r.status]); }).map(function (r) {
    const reason = r.status === 'ambiguous' ? 'scope not distinguishable from configuration' : 'no matching definition found';
    return '<li>' + extEsc(r.name) + ' - ' + (r.operation === 'write' ? 'writes' : 'reads') + ', ' + reason + '</li>';
  });

  if (!localItems.length && !hubItems.length && !reviewItems.length && !webcoreVariableEdges.length && !webcoreDeviceEdges.length && !webcoreIssue) { box.innerHTML = ''; return; }

  let html = '<h4>Variables used by this automation</h4>';
  if (localItems.length) html += '<p class="sub">Local</p><ul>' + localItems.join('') + '</ul>';
  if (hubItems.length) html += '<p class="sub">Hub</p><ul>' + hubItems.join('') + '</ul>';
  if (webcoreVariableEdges.length) {
    html += '<p class="sub">Hub, decoded from webCoRE</p><ul>' + webcoreVariableEdges.map(function (entry) {
      const operation = entry.operation === 'write' ? 'writes' :
        entry.operation === 'read' ? 'reads' : 'uses (direction unknown)';
      return '<li>[HVR] ' + extEsc(entry.name) + ' - ' + operation + '</li>';
    }).join('') + '</ul>';
  }
  if (webcoreDeviceEdges.length) {
    html += '<p class="sub">Devices, decoded from webCoRE</p><ul>' + webcoreDeviceEdges.map(function (entry) {
      const detail = entry.operation === 'deviceRead'
        ? 'reads' + (entry.attribute ? ' (' + extEsc(entry.attribute) + ')' : '')
        : 'commands' + (entry.commands && entry.commands.length ? ' (' + entry.commands.map(extEsc).join(', ') + ')' : '');
      const devTag = '[' + (DEVICE_ICON_TAGS[entry.icon] || 'UNK') + '] ';
      return '<li>' + devTag + extEsc(entry.name) + ' - ' + detail + '</li>';
    }).join('') + '</ul>';
  }
  if (reviewItems.length) html += '<p class="sub">Needs review</p><ul>' + reviewItems.join('') + '</ul>';
  if (webcoreIssue) {
    html += '<p class="sub">webCoRE Hub Variable references could not be decoded (' + extEsc(webcoreIssue) + '). Other mapped relationships for this piston remain valid.</p>';
  }
  box.innerHTML = html;
}

const flowCloseBtn = document.getElementById('flowClose');
if (flowCloseBtn) {
  flowCloseBtn.addEventListener('click', function () {
    beginSelectionGeneration();
    flowPanel.style.display = 'none';
    syncLegendVisibility();
    fitCurrentView();
  });
}

// Community Context Card (Supporting Docs/community_context_card_spec.md,
// published contract). A read-only, browser-only lookup
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
// carrying an unreasonable number of tiny records (a design review point
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
// The one selection generation. Every path that selects, closes or replaces
// the flow panel begins a new generation before touching the screen, so a
// Mermaid render or coverage request started earlier knows it is stale.
let focusGenerationSeq = 0;
function beginSelectionGeneration() { focusGenerationSeq += 1; }

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
        // bare typeof check.
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
  // Deliberately NOT reset on rejection: a page reload is the retry boundary.
  // Resetting meant every selection after a failure re-fetched and waited out
  // the full timeout again. A cached rejection resolves instantly.
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

// Package Explorer, not the Identity Resolver: the resolver takes no query
// parameter, so a link there opens a page that searches for nothing.
function ccSearchLinkHtml(name) {
  if (!name) return '';
  return '<p class="ccLinks"><a href="' + ccExplorerUrl(name) + '" target="_blank" rel="noopener noreferrer">Search Community Utilities for this app</a></p>';
}

function ccCardHtml(result, snapshotGenerated, searchName) {
  let html = '<h4>Community information</h4>';
  let clickUrl = null;
  if (result.state === 'confirmed') {
    html += ccRecordHtml(result.record, result.identityMismatch);
    const name = result.record.displayName || result.record.packageName;
    if (name) clickUrl = ccExplorerUrl(name);
    // Spec 3.3: a flagged identity should not read
    // as a plain clean match with nowhere else to check it - the reader can
    // go verify it themselves, not just take this card's word for it.
    if (result.identityMismatch) html += ccSearchLinkHtml(searchName);
  } else if (result.state === 'ambiguous') {
    html += '<p class="sub">More than one Community Utilities record matches this app by name. None is shown as confirmed - click through to investigate.</p><ul>';
    result.records.slice(0, 5).forEach(function (r) {
      html += '<li>' + extEsc(r.displayName || r.packageName || 'Unnamed') +
        (r.author ? ' &middot; ' + extEsc(r.author) : '') +
        ' <span class="ccBadge">' + extEsc(COMMUNITY_CONTEXT_AUTHORITY_LABELS[r.authority] || r.authority) + '</span></li>';
    });
    html += '</ul>';
    html += ccSearchLinkHtml(searchName);
    const first = result.records[0];
    const name = first && (first.displayName || first.packageName);
    if (name) clickUrl = ccExplorerUrl(name);
  } else {
    html += '<p class="sub">No community information found for this app.</p>';
    html += ccSearchLinkHtml(searchName);
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

// Decode coverage card (v2.2.9). On demand only: focusing a piston renders the
// button and nothing else, and one press makes one request. Every label is plain
// ASCII with no apostrophes, because this script lives inside a Groovy GString and
// a stray quote has broken this page before. Dynamic text goes through extEsc.
const COVERAGE_URL = amPickURL('${getLocalURL('webcore-decode-coverage')}', '${getCloudURL('webcore-decode-coverage')}');
let coverageRequestSeq = 0;
let coverageAppId = null;
let coverageInFlight = false;

// The graph node id carries an 'a' prefix; the hub app id does not.
function coverageHubAppId(nodeId) {
  const s = String(nodeId === null || nodeId === undefined ? '' : nodeId);
  return s.charAt(0) === 'a' ? s.slice(1) : s;
}

function decodeCoverageResultHtml(body) {
  // The chart is now the honest surface for what a piston does: anything not
  // understood is drawn as a visible "not decoded" block. What the chart cannot
  // show is a field this decoder has never seen - a webCoRE version saving
  // something new - so that is all this reports, and only when it is non-zero.
  const gaps = body.unrecognised || [];
  const unknownTotal = gaps.length + (body.unrecognisedOverflow || 0);
  const mismatches = body.structureFindings || [];
  const mismatchTotal = mismatches.length + (Number(body.structureFindingsOverflow) || 0);
  const total = unknownTotal + mismatchTotal;
  if (!total && body.status !== 'truncated') return '';
  if (body.status === 'truncated') {
    return '<h4>Decode coverage</h4><p class="sub">A safety bound was reached before the whole piston was walked.</p>';
  }
  return '<h4>Decode coverage</h4><p class="sub">' + extEsc(total) + ' ' +
    (total === 1 ? 'field' : 'fields') + ' not identified in the saved piston.</p>';
}

// Silent unless there is something to say. A piston with nothing unidentified, a
// piston never saved, a busy or failed check - none of those are things a person
// reading their automation can act on, and the chart already shows what is and
// is not understood.
function decodeCoverageOutcomeHtml(body) {
  if (body.status === 'complete' || body.status === 'truncated') return decodeCoverageResultHtml(body);
  return '';
}

// Called wherever the community card renders, so it resets on every selection.
// Bumping the request sequence here is what makes a response for an earlier
// selection arrive to nothing.
function renderDecodeCoverageCard(node) {
  const box = document.getElementById('decodeCoverageCard');
  if (!box) return;
  coverageRequestSeq++;
  coverageInFlight = false;
  if (!node || node.appType !== 'webCoRE Piston') {
    coverageAppId = null;
    box.innerHTML = '';
    box.hidden = true;
    return;
  }
  coverageAppId = node.id;
  // No button. The only thing left worth reporting is a field this decoder has
  // never seen, which a user cannot act on and should not have to ask for, so
  // the check runs on selection and the card stays hidden unless it finds one.
  box.hidden = true;
  box.innerHTML = '';
  requestDecodeCoverage();
}

function requestDecodeCoverage() {
  const box = document.getElementById('decodeCoverageCard');
  if (!box || !coverageAppId || coverageInFlight) return;
  const mySelectionSeq = focusGenerationSeq;
  const seq = ++coverageRequestSeq;
  coverageInFlight = true;
  const url = COVERAGE_URL + '&appId=' + encodeURIComponent(coverageHubAppId(coverageAppId));
  fetch(url, { cache: 'no-store', credentials: 'omit' })
    .then(function (resp) {
      return resp.json().then(function (body) { return body; }, function () { return {}; });
    })
    .then(function (body) {
      if (seq !== coverageRequestSeq || mySelectionSeq !== focusGenerationSeq) return;
      coverageInFlight = false;
      const html = decodeCoverageOutcomeHtml(body || {});
      box.innerHTML = html;
      box.hidden = !html;
    })
    .catch(function () {
      if (seq !== coverageRequestSeq || mySelectionSeq !== focusGenerationSeq) return;
      coverageInFlight = false;
      // A check that could not run is a decoder problem, not something to
      // interrupt someone reading a piston. It stays silent.
      box.innerHTML = '';
      box.hidden = true;
    });
}

function renderCommunityCard(node) {
  const box = document.getElementById('communityCard');
  if (!box) return;
  const seq = ++communityCardRequestSeq;
  // box.hidden, not just an empty innerHTML - the card's own light background
  // still painted a blank box with nothing in it (reported live: a plain
  // white rectangle with no text). Emptying the content was never enough on
  // its own to remove the box from the page.
  if (!node || node.group !== 'app') { box.innerHTML = ''; box.hidden = true; ccApplyClickable(box, null); return; }
  // A child app is an instance the user built inside an engine - a rule, a
  // button rule, a notifier. No community package exists for one, so the card
  // could only ever say "nothing found". Show nothing instead.
  if (node.parent) { box.innerHTML = ''; box.hidden = true; ccApplyClickable(box, null); return; }
  box.hidden = false;
  box.innerHTML = '<h4>Community information</h4><p class="sub">Checking Community Utilities...</p>';
  ccApplyClickable(box, null);
  loadCommunityContext().then(function (data) {
    if (seq !== communityCardRequestSeq) return;
    const rendered = ccCardHtml(matchCommunityContext(data, node), data.snapshotGenerated, node.appType);
    box.hidden = false;
    box.innerHTML = rendered.html;
    ccApplyClickable(box, rendered.clickUrl);
  }).catch(function (e) {
    if (seq !== communityCardRequestSeq) return;
    console.warn('Community information unavailable: ' + e.message);
    box.hidden = false;
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
  // webCoRE gets real tags rather than the CUS catch-all: CUS means this app
  // has not been recognised, and a piston is now the most deeply decoded
  // non-Rule-Machine type here. WCE is the parent engine holding pistons, WCP
  // one piston, mirroring how Rule Machine and Rule-5.1 are tagged separately.
  // Not INT: every INT app declares an external system and a depends edge,
  // and webCoRE declares neither, so it would be the only one that depends on
  // nothing external.
  'webCoRE': 'WCE',
  'webCoRE Piston': 'WCP',
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
  let title = n.title;
  const typeSuffix = n.appType ? ' (' + n.appType + ')' : '';
  if (typeSuffix && title.length > typeSuffix.length && title.slice(-typeSuffix.length) === typeSuffix) {
    const head = title.slice(0, -typeSuffix.length);
    // Only when the label is exactly the type name, as in Tapo Integration (Tapo Integration).
    if (head === n.appType) title = head;
  }
  return '[' + (APP_TYPE_TAGS[n.appType] || 'CUS') + '] ' + title;
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
// Same convention for the Hub Variable Focus list (Gate C follow-up, agreed
// 2026-08-29, queue 305/306). Uses HVR rather than the
// APP_TYPE_TAGS HUB code - that one already means "built-in Hubitat app", a
// different axis from variable scope, and reusing it here would conflate the
// two. connectorDeviceId is the same authoritative field buildGraph() already
// resolves onto the node - no new backend field for this.
function hubVarOptionText(n) {
  return '[' + (n.connectorDeviceId ? 'CON' : 'HVR') + '] ' + n.title;
}
// A Local Variable's visible name is not unique across rules by design (Gate
// A found two rules can each declare their own same-named one), so the
// dropdown text has to name the owning rule to tell them apart - id alone
// already does (ownerAppId-scoped identity), this is purely for a human
// reading the list. Built once here rather than looked up per option: this
// runs once per dropdown render, not once per keystroke.
const APP_TITLE_BY_ID = {};
ALL_NODES.forEach(function (n) { if (n.group === 'app') APP_TITLE_BY_ID[n.id] = n.title; });
// The one place a Local Variable's name and owner are split out of its node title,
// so the dropdowns, Quick Search, the canvas and its panel all name the owner once.
function localVarDisplay(n) {
  const marker = ' (Local Variable in ';
  const cut = n.title.indexOf(marker);
  if (cut > 0 && n.title.slice(-1) === ')') return { name: n.title.slice(0, cut), owner: n.title.slice(cut + marker.length, -1) };
  const owner = ALL_NODES.filter(function (a) { return a.id === n.ownerAppId; })[0];
  return { name: n.title, owner: owner ? owner.title : 'an unknown rule' };
}
// A webCoRE piston local is tagged WCV and every other rule local LOC (Gordon, 2026-09-11).
function localVarTag(ownerAppId) {
  const owner = ALL_NODES.filter(function (a) { return a.id === ownerAppId; })[0];
  return owner && owner.appType === 'webCoRE Piston' ? 'WCV' : 'LOC';
}
function localVarOptionText(n) {
  const d = localVarDisplay(n);
  return '[' + localVarTag(n.ownerAppId) + '] ' + d.name + ' (in ' + d.owner + (n.unreferencedLocal ? ', unused' : '') + ')';
}
function localVarCanvasText(n) {
  const d = localVarDisplay(n);
  return d.name + ' (in ' + d.owner + ')';
}
function pickOptionText(n, group) {
  if (group === 'app') return appOptionText(n);
  if (group === 'device') return deviceOptionText(n);
  if (group === 'hubVariable') return hubVarOptionText(n);
  if (group === 'localVariable') return localVarOptionText(n);
  return n.title;
}

// Combined combobox for the Focus dropdowns. Closed state is a plain,
// non-editable button (label + arrow) - opening it reveals a popup whose
// first row is a dedicated, auto-focused search field, with the scrollable
// options list directly below it. Not the closed control doubling as the
// search box - that shape was built, deployed and rejected live (Gordon's
// reference: a spreadsheet-style searchable dropdown, 2026-09-02). Built and
// verified standalone first in Bucket/combobox-harness/ (31 automated checks
// + hands-on) before this port; unchanged from that harness version.
// Framework-free ES5-ish idiom - no arrow functions, no template literals -
// because this whole page is one Groovy GString and a JS template literal's
// own interpolation syntax would collide with Groovy's.
(function (root) {
  'use strict';

  var ALL = '__all__';
  var DISABLED_COLOR = '#e57373';
  var idSeq = 0;

  function createCombobox(opts) {
    opts = opts || {};
    var uid = 'cb' + (++idSeq);
    var mount = opts.mount;
    if (!mount) { throw new Error('createCombobox: opts.mount is required'); }

    var allLabel = opts.allLabel != null ? String(opts.allLabel) : 'All';
    var onChange = typeof opts.onChange === 'function' ? opts.onChange : function () {};

    var items = [];
    var value = ALL;
    var open = false;
    var activeIndex = -1;   // index into `rows` (the currently rendered rows)
    var rows = [];          // [{ value, label, disabled, el }]

    // --- DOM -----------------------------------------------------------------
    var elRoot = document.createElement('div');
    elRoot.className = 'cb';
    elRoot.setAttribute('data-cb', uid);

    // Closed control - a plain button, not an editable field. Shows the
    // current selection and an arrow; click/Enter/Space/ArrowDown opens the
    // popup. Never receives typed text itself.
    var elButton = document.createElement('button');
    elButton.type = 'button';
    elButton.className = 'cb-button';
    elButton.setAttribute('aria-haspopup', 'listbox');
    elButton.setAttribute('aria-expanded', 'false');

    var elButtonLabel = document.createElement('span');
    elButtonLabel.className = 'cb-button-label';

    var elArrow = document.createElement('span');
    elArrow.className = 'cb-arrow';
    elArrow.setAttribute('aria-hidden', 'true');
    elArrow.innerHTML = '&#9662;';

    elButton.appendChild(elButtonLabel);
    elButton.appendChild(elArrow);

    var elPopup = document.createElement('div');
    elPopup.className = 'cb-popup';
    elPopup.hidden = true;

    // The dedicated search field - first thing in the popup, auto-focused on
    // open, always empty when it appears (never carries the selection text).
    var elSearch = document.createElement('input');
    elSearch.type = 'text';
    elSearch.className = 'cb-search';
    elSearch.autocomplete = 'off';
    elSearch.spellcheck = false;
    elSearch.setAttribute('role', 'searchbox');
    elSearch.setAttribute('aria-autocomplete', 'list');
    elSearch.setAttribute('aria-controls', uid + '-list');
    if (opts.placeholder != null) { elSearch.placeholder = String(opts.placeholder); }

    var elList = document.createElement('ul');
    elList.className = 'cb-list';
    elList.id = uid + '-list';
    elList.setAttribute('role', 'listbox');

    var elCount = document.createElement('div');
    elCount.className = 'cb-count';
    elCount.setAttribute('aria-live', 'polite');

    elPopup.appendChild(elSearch);
    elPopup.appendChild(elList);
    elPopup.appendChild(elCount);
    elRoot.appendChild(elButton);
    elRoot.appendChild(elPopup);
    mount.appendChild(elRoot);

    // --- helpers -----------------------------------------------------------
    function currentItem() {
      if (value === ALL) { return null; }
      for (var i = 0; i < items.length; i++) {
        if (items[i].id === value) { return items[i]; }
      }
      return null;
    }

    function selectionLabel() {
      var it = currentItem();
      if (!it) { return allLabel; }
      return it.optionText != null ? it.optionText : it.title;
    }

    function sortItems(list) {
      return list.slice().sort(function (a, b) {
        return String(a.title).localeCompare(String(b.title));
      });
    }

    // Filter on the text each row shows, so a visible tag such as [WCP] is
    // searchable. Keep the current selection visible even when it no longer
    // matches - same contract fillSelect() used to guarantee.
    function computeRows(term) {
      var q = (term || '').toLowerCase();
      var out = [];
      // Reset row only makes sense against the full, unfiltered list - once
      // actively searching, a plain filtered list reads cleaner.
      if (!q) { out.push({ value: ALL, label: allLabel, disabled: false }); }
      var seen = {};
      var shown = 0;
      for (var i = 0; i < items.length; i++) {
        var n = items[i];
        var rowText = n.optionText != null ? n.optionText : n.title;
        if (q && String(rowText).toLowerCase().indexOf(q) < 0) { continue; }
        out.push({
          value: n.id,
          label: rowText,
          disabled: !!(n.disabled || n.paused)
        });
        seen[n.id] = true;
        shown++;
      }
      if (value !== ALL && !seen[value]) {
        var cur = currentItem();
        if (cur) {
          out.push({
            value: cur.id,
            label: cur.optionText != null ? cur.optionText : cur.title,
            disabled: !!(cur.disabled || cur.paused),
            sticky: true
          });
        }
      }
      return { rows: out, shown: shown, total: items.length };
    }

    function renderList() {
      var res = computeRows(elSearch.value);
      rows = res.rows;
      elList.innerHTML = '';
      for (var i = 0; i < rows.length; i++) {
        var r = rows[i];
        var li = document.createElement('li');
        li.className = 'cb-opt' + (r.sticky ? ' cb-opt-sticky' : '');
        li.id = uid + '-opt-' + i;
        li.setAttribute('role', 'option');
        li.textContent = r.label + (r.sticky ? '  (current selection)' : '');
        if (r.disabled) { li.style.color = DISABLED_COLOR; }
        if (r.value === value) { li.setAttribute('aria-selected', 'true'); li.className += ' cb-opt-selected'; }
        li.setAttribute('data-idx', String(i));
        r.el = li;
        elList.appendChild(li);
      }
      elCount.textContent = res.shown + ' of ' + res.total + ' shown';
      // Keep the active row in range and reflect it.
      if (activeIndex >= rows.length) { activeIndex = rows.length - 1; }
      paintActive();
    }

    function paintActive() {
      for (var i = 0; i < rows.length; i++) {
        if (!rows[i].el) { continue; }
        if (i === activeIndex) {
          rows[i].el.classList.add('cb-opt-active');
          elSearch.setAttribute('aria-activedescendant', rows[i].el.id);
          scrollIntoView(rows[i].el);
        } else {
          rows[i].el.classList.remove('cb-opt-active');
        }
      }
      if (activeIndex < 0) { elSearch.removeAttribute('aria-activedescendant'); }
    }

    function scrollIntoView(el) {
      var top = el.offsetTop;
      var bottom = top + el.offsetHeight;
      if (top < elList.scrollTop) { elList.scrollTop = top; }
      else if (bottom > elList.scrollTop + elList.clientHeight) {
        elList.scrollTop = bottom - elList.clientHeight;
      }
    }

    function openPopup() {
      if (open) { return; }
      open = true;
      elPopup.hidden = false;
      elRoot.classList.add('cb-is-open');
      elButton.setAttribute('aria-expanded', 'true');
      // Always starts empty - the search field never carries the selection
      // text, so there is nothing to clear-on-reopen the way a merged
      // input/display would need.
      elSearch.value = '';
      renderList();
      activeIndex = indexOfValue(value);
      paintActive();
      elSearch.focus();
      document.addEventListener('mousedown', onDocMouseDown, true);
    }

    function closePopup() {
      if (!open) { return; }
      open = false;
      elPopup.hidden = true;
      elRoot.classList.remove('cb-is-open');
      elButton.setAttribute('aria-expanded', 'false');
      elSearch.removeAttribute('aria-activedescendant');
      activeIndex = -1;
      document.removeEventListener('mousedown', onDocMouseDown, true);
    }

    function indexOfValue(v) {
      for (var i = 0; i < rows.length; i++) {
        if (rows[i].value === v) { return i; }
      }
      return -1;
    }

    function commit(v, fireChange) {
      var changed = v !== value;
      value = v;
      elButtonLabel.textContent = selectionLabel();
      if (changed && fireChange !== false) {
        onChange(value, currentItem());
      }
    }

    function moveActive(delta) {
      if (!rows.length) { return; }
      var i = activeIndex;
      // Skip nothing - disabled rows are still selectable in the native
      // <select> this replaces, so keep them reachable.
      i += delta;
      if (i < 0) { i = rows.length - 1; }
      if (i >= rows.length) { i = 0; }
      activeIndex = i;
      paintActive();
    }

    // --- events ----------------------------------------------------------
    function onDocMouseDown(e) {
      if (elRoot.contains(e.target)) { return; }
      closePopup();
    }

    elButton.addEventListener('click', function () {
      if (open) { closePopup(); } else { openPopup(); }
    });

    // Enter/Space already open it via the native click a button fires on
    // activation - only ArrowDown needs its own handling here.
    elButton.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowDown' && !open) { e.preventDefault(); openPopup(); }
    });

    elSearch.addEventListener('input', function () {
      activeIndex = -1;
      renderList();
      // Point at the first real match so Enter selects something sensible -
      // skip past row 0 only when it is the reset row (computeRows omits it
      // once a filter term is active, so most keystrokes land here already
      // pointing at row 0, the actual first match).
      if (rows.length) {
        activeIndex = (rows[0].value === ALL && rows.length > 1) ? 1 : 0;
      }
      paintActive();
    });

    elSearch.addEventListener('keydown', function (e) {
      switch (e.key) {
        case 'ArrowDown': e.preventDefault(); moveActive(1); break;
        case 'ArrowUp': e.preventDefault(); moveActive(-1); break;
        case 'Home': e.preventDefault(); activeIndex = 0; paintActive(); break;
        case 'End': e.preventDefault(); activeIndex = rows.length - 1; paintActive(); break;
        case 'Enter':
          e.preventDefault();
          var pick = activeIndex >= 0 ? rows[activeIndex] : rows[0];
          if (pick) { commit(pick.value, true); }
          closePopup();
          elButton.focus();
          break;
        case 'Escape':
          e.preventDefault();
          closePopup();
          elButton.focus();
          break;
        case 'Tab':
          closePopup();
          break;
        default: break;
      }
    });

    elList.addEventListener('mousemove', function (e) {
      var li = e.target.closest ? e.target.closest('.cb-opt') : null;
      if (!li) { return; }
      var idx = parseInt(li.getAttribute('data-idx'), 10);
      if (idx === activeIndex) { return; }
      activeIndex = idx;
      paintActive();
    });

    // mousedown, not click: fires before the search field's blur so focus
    // handling stays simple.
    elList.addEventListener('mousedown', function (e) {
      var li = e.target.closest ? e.target.closest('.cb-opt') : null;
      if (!li) { return; }
      e.preventDefault();
      var idx = parseInt(li.getAttribute('data-idx'), 10);
      var pick = rows[idx];
      if (pick) { commit(pick.value, true); }
      closePopup();
      elButton.focus();
    });

    // --- init ----------------------------------------------------------
    items = sortItems(opts.items || []);
    if (opts.value != null) { value = opts.value; }
    elButtonLabel.textContent = selectionLabel();
    renderList();

    // --- public --------------------------------------------------------
    return {
      element: elRoot,
      button: elButton,
      searchInput: elSearch,
      getValue: function () { return value; },
      getItem: function () { return currentItem(); },
      setValue: function (id, label) {
        if (id == null || id === ALL) { commit(ALL, false); if (open) { renderList(); } return; }
        var found = currentItemById(id);
        if (!found && label != null) {
          // forceSelect() equivalent: re-add an item the filter had removed.
          items.push({ id: id, title: label, optionText: label });
          items = sortItems(items);
        }
        commit(id, false);
        if (open) { renderList(); }
      },
      setItems: function (next) {
        items = sortItems(next || []);
        if (value !== ALL && !currentItemById(value)) { value = ALL; }
        elButtonLabel.textContent = selectionLabel();
        if (open) { renderList(); }
      },
      open: openPopup,
      close: closePopup,
      focus: function () { elButton.focus(); }
    };

    function currentItemById(id) {
      for (var i = 0; i < items.length; i++) {
        if (items[i].id === id) { return items[i]; }
      }
      return null;
    }
  }

  root.createCombobox = createCombobox;
}(window));

// Builds one Focus combobox: filters ALL_NODES to the given group, attaches
// each node's decorated option text, and mounts it into the given element id.
// Replaces fillSelect() + forceSelect() together - createCombobox's own
// setValue(id, label) is forceSelect()'s re-add-if-filtered-out behaviour.
function initCombo(mountId, group, allLabel, placeholder, onChange) {
  const items = ALL_NODES.filter(function (n) { return n.group === group; });
  items.forEach(function (n) { n.optionText = pickOptionText(n, group); });
  return createCombobox({
    mount: document.getElementById(mountId),
    allLabel: allLabel,
    placeholder: placeholder,
    items: items,
    onChange: onChange
  });
}

// ---------------------------------------------------------------------------
// Insights. The graph answers "what is connected"; these answer the questions
// the hub itself cannot: which devices are driven by more than one app (the
// usual cause of automations fighting each other), and which devices nothing
// commands at all.
// ---------------------------------------------------------------------------
${''}
// Single derivation of every finding, as plain data with no rendering in it,
// feeding both the Insights panel and the AI export so the two cannot drift.
// Returns raw ids and maps: the panel wants display names and the export
// wants {id,name} refs, so formatting stays with each renderer.
function deriveInsightData() {
  // v2.2.8: counts only the webCoRE device-decode issue codes that represent
  // a genuine reconciliation gap (something this scan should have been able
  // to resolve and could not) - a variable-backed or runtime-selected device
  // reference is an expected, by-design coverage limit, not a gap, and must
  // not make the scan read as incomplete. Local to this function (not a
  // top-level helper) so the test harness's own extractFunction('deriveInsightData')
  // - one function, brace-matched, evaluated standalone - keeps working
  // without needing a second extraction entry.
  const WEBCORE_DEVICE_RECONCILIATION_GAP_CODES = ['unresolved-device-hash', 'ambiguous-device-hash', 'missing-parent-device-index'];
  function webcoreDeviceReconciliationGapCount(issues) {
    return (issues || []).reduce(function (sum, issue) {
      return sum + (issue.codes || []).filter(function (code) { return WEBCORE_DEVICE_RECONCILIATION_GAP_CODES.indexOf(code) !== -1; }).length;
    }, 0);
  }
  const missingIds = {};
  ALL_NODES.forEach(function (n) { if (n.missing) missingIds[n.id] = true; });
  const referencesTo = {};
  ALL_EDGES.forEach(function (e) {
    if (!missingIds[e.to]) return;
    if (!referencesTo[e.to]) referencesTo[e.to] = [];
    if (referencesTo[e.to].indexOf(e.from) < 0) referencesTo[e.to].push(e.from);
  });

  // statefulCommanders answers contention; anyCommanders answers "is this ever
  // driven at all". Using one map for both is what made read-only wrong.
  const statefulCommanders = {};
  const anyCommanders = {};
  const touched = {};
  const hubVarReaders = {};
  const hubVarWriters = {};
  const hubVarUsers = {};
  ALL_EDGES.forEach(function (e) {
    touched[e.to] = true;
    if (e.kind === 'read') {
      if (!hubVarReaders[e.to]) hubVarReaders[e.to] = [];
      if (hubVarReaders[e.to].indexOf(e.from) < 0) hubVarReaders[e.to].push(e.from);
    } else if (e.kind === 'write') {
      if (!hubVarWriters[e.to]) hubVarWriters[e.to] = [];
      if (hubVarWriters[e.to].indexOf(e.from) < 0) hubVarWriters[e.to].push(e.from);
    } else if (e.kind === 'usesVar') {
      if (!hubVarUsers[e.to]) hubVarUsers[e.to] = [];
      if (hubVarUsers[e.to].indexOf(e.from) < 0) hubVarUsers[e.to].push(e.from);
    }
    if (e.kind !== 'action') return;
    if (!anyCommanders[e.to]) anyCommanders[e.to] = [];
    if (anyCommanders[e.to].indexOf(e.from) < 0) anyCommanders[e.to].push(e.from);
    if (!e.stateful) return;
    if (!statefulCommanders[e.to]) statefulCommanders[e.to] = [];
    if (statefulCommanders[e.to].indexOf(e.from) < 0) statefulCommanders[e.to].push(e.from);
  });

  const devices = ALL_NODES.filter(function (n) { return n.group === 'device'; });
  const hubVarIds = ALL_NODES.filter(function (n) { return n.group === 'hubVariable'; }).map(function (n) { return n.id; });

  // Things that look fine but silently do nothing. Each needs a second fact
  // beyond the state itself: a paused rule nothing calls, or a disabled device
  // nothing uses, is very often paused or disabled on purpose and is not a
  // finding. Only the combination is actionable.
  const disabledDeviceIds = {};
  devices.forEach(function (n) { if (n.disabled) disabledDeviceIds[n.id] = true; });
  // pauseResume is deliberately excluded: a rule whose whole job is to pause
  // or resume another rule is the mechanism working, not a silent failure.
  const INVOKE_KINDS = { runs: 1, cancelTimedActions: 1, setspb: 1 };
  const invokedBy = {};
  const disabledDeviceUsers = {};
  ALL_EDGES.forEach(function (e) {
    if (INVOKE_KINDS[e.kind]) {
      if (!invokedBy[e.to]) invokedBy[e.to] = [];
      if (invokedBy[e.to].indexOf(e.from) < 0) invokedBy[e.to].push(e.from);
    }
    // An action a disabled device can never carry out, or a trigger it can
    // never emit. Constraint and monitor reads are excluded - a stale reading
    // is a weaker and much noisier claim than a command that cannot land.
    if ((e.kind === 'action' || e.kind === 'trigger') && disabledDeviceIds[e.to]) {
      if (!disabledDeviceUsers[e.to]) disabledDeviceUsers[e.to] = [];
      if (disabledDeviceUsers[e.to].indexOf(e.from) < 0) disabledDeviceUsers[e.to].push(e.from);
    }
  });
  const inactiveApps = ALL_NODES.filter(function (n) {
    return n.group === 'app' && (n.disabled || n.paused);
  });

  return {
    missingIds: missingIds,
    referencesTo: referencesTo,
    statefulCommanders: statefulCommanders,
    anyCommanders: anyCommanders,
    touched: touched,
    brokenTargets: Object.keys(missingIds),
    contested: Object.keys(statefulCommanders)
      .filter(function (d) { return statefulCommanders[d].length > 1; })
      .sort(function (a, b) { return statefulCommanders[b].length - statefulCommanders[a].length; }),
    untouched: devices.filter(function (n) { return !touched[n.id]; }).map(function (n) { return n.id; }),
    readOnly: devices.filter(function (n) { return touched[n.id] && !anyCommanders[n.id]; }).map(function (n) { return n.id; }),
    notifiedOnly: devices.filter(function (n) {
      return touched[n.id] && anyCommanders[n.id] && !statefulCommanders[n.id];
    }).map(function (n) { return n.id; }),
    inertNodes: ALL_NODES.filter(function (n) { return n.inert; }),
    unreadableNodes: ALL_NODES.filter(function (n) { return n.unreadable; }),
    invokedBy: invokedBy,
    disabledDeviceUsers: disabledDeviceUsers,
    // Paused or disabled, and still invoked by another rule that therefore
    // silently does nothing at that step.
    inactiveInvoked: inactiveApps
      .filter(function (n) { return invokedBy[n.id] && invokedBy[n.id].length; })
      .map(function (n) { return n.id; }),
    // Every paused/disabled rule, reported as context rather than as a fault.
    inactiveApps: inactiveApps.map(function (n) { return n.id; }),
    // Hubitat's own broken marker, not this scan's opinion.
    brokenApps: ALL_NODES.filter(function (n) { return n.broken; }).map(function (n) { return n.id; }),
    disabledDevicesInUse: Object.keys(disabledDeviceUsers),
    unreferencedLocals: ALL_NODES
      .filter(function (n) { return n.group === 'localVariable' && n.unreferencedLocal; })
      .map(function (n) { return n.id; }),
    hubVar: {
      readers: hubVarReaders,
      writers: hubVarWriters,
      users: hubVarUsers,
      noDecodedUsage: hubVarIds.filter(function (id) { return !hubVarReaders[id] && !hubVarWriters[id] && !hubVarUsers[id]; }),
      readersWithoutDecodedWriter: hubVarIds.filter(function (id) { return hubVarReaders[id] && !hubVarWriters[id]; }),
      writersWithoutDecodedReader: hubVarIds.filter(function (id) { return hubVarWriters[id] && !hubVarReaders[id]; }),
      multipleWriters: hubVarIds.filter(function (id) { return hubVarWriters[id] && hubVarWriters[id].length > 1; }),
      directionUnknownUsage: hubVarIds.filter(function (id) { return !!hubVarUsers[id]; }),
      unresolvedReferences: (GRAPH.hubVariableUnresolvedReferences || []),
      webcoreDecodeIssues: (GRAPH.webcoreVariableDecodeIssues || [])
    },
    // v2.2.8: real device-decode coverage, replacing the fixed "device
    // relationships are not decoded" era. codes distinguishes genuine
    // reconciliation gaps (unresolved/ambiguous hash, missing parent index -
    // something this scan should have been able to resolve and could not)
    // from expected, by-design coverage limits (a variable-backed or
    // runtime-selected device reference, which no static decode can ever
    // resolve) - only the former counts toward scan.status below.
    webcoreDevice: {
      issues: (GRAPH.webcoreDeviceRelationshipIssues || [])
    },
    scan: {
      status: SCAN_META.scanError ? 'failed'
        : ((SCAN_META.appsUnreadable > 0 || SCAN_META.devicesUnreadable > 0 ||
            (GRAPH.webcoreVariableDecodeIssues || []).length > 0 ||
            webcoreDeviceReconciliationGapCount(GRAPH.webcoreDeviceRelationshipIssues) > 0) ? 'complete-with-gaps' : 'complete'),
      appsUnreadable: SCAN_META.appsUnreadable || 0,
      devicesUnreadable: SCAN_META.devicesUnreadable || 0,
      webcoreVariableDecodeIssues: (GRAPH.webcoreVariableDecodeIssues || []).length,
      webcoreDeviceReconciliationGaps: webcoreDeviceReconciliationGapCount(GRAPH.webcoreDeviceRelationshipIssues),
      error: SCAN_META.scanError || null
    }
  };
}

// Shared panel/export interpretation of the facts derived above. Guidance does
// not alter classification and never authorises a hub change.
function insightGuidance() {
  return {
    categories: {
      attention: {
        label: 'Needs attention',
        summary: 'Incomplete scans or references to targets that no longer exist.',
        next: 'Resolve these first because they can hide data or leave an automation action doing nothing.'
      },
      review: {
        label: 'Shared control to confirm',
        summary: 'Shared device control and Hub Variable use that may be entirely intentional.',
        next: 'Confirm that the participants, timing and intended winner are what you expect.'
      },
      cleanup: {
        label: 'Possibly unused',
        summary: 'Devices or apps with no relationship the scan could prove.',
        next: 'Check external integrations, dashboards and schedules before removing anything.'
      },
      normal: {
        label: 'Expected patterns',
        summary: 'Common structures that usually need no action.',
        next: 'Use these explanations to understand the map, not as a cleanup list.'
      }
    },
    findings: {
      scanIncomplete: {
        meaning: 'The last scan did not produce a complete snapshot, so other findings may be missing items.',
        next: 'Run the scan again. If the same gap remains, check the Hubitat logs for the named unreadable app or device.'
      },
      brokenRuleReference: {
        meaning: 'A rule still names an app or rule target that is no longer installed. That action runs but cannot do anything.',
        next: 'Open each referencing rule and either select the intended replacement or remove the obsolete action.'
      },
      contestedDevice: {
        meaning: 'Several automations can leave this device in a lasting state, so the last one to run decides the result.',
        normal: 'Motion, schedules, scenes and manual overrides often share the same light or switch deliberately.',
        next: 'Check whether their triggers can overlap and which automation should win when they do.'
      },
      multipleVariableWriters: {
        meaning: 'More than one decoded rule writes this Hub Variable.',
        normal: 'Shared state can legitimately be updated from several places. This alone does not prove a race condition.',
        next: 'Check whether the writers can run close together and whether the final value depends on their order.'
      },
      variableReadersWithoutWriter: {
        meaning: 'Decoded rules read this Hub Variable, but no decoded rule writes it.',
        normal: 'It may be set manually, through a Connector, by an external integration or by an app engine this scan cannot decode.',
        next: 'Confirm where its value is expected to come from before treating the missing writer as a gap.'
      },
      variableWritersWithoutReader: {
        meaning: 'Decoded rules write this Hub Variable, but no decoded rule reads it.',
        normal: 'A dashboard, Connector or external integration may consume it without producing a decoded read edge.',
        next: 'Confirm whether anything outside the decoded rules still uses the value before removing the writer or variable.'
      },
      variableDirectionUnknown: {
        meaning: 'A webCoRE piston references this Hub Variable, but its saved operand alone does not prove whether that occurrence reads or writes it.',
        normal: 'The relationship is intentionally conservative. It confirms use without inventing a direction.',
        next: 'Open the named piston if you need to distinguish how it uses the variable.'
      },
      webcoreVariableDecodeIssue: {
        meaning: 'The piston was read, but its saved webCoRE variable configuration could not be decoded safely.',
        next: 'Open and save the piston, then scan again. If the issue remains, record the fixed error code before changing anything.'
      },
      unresolvedVariableReference: {
        meaning: 'A decoded rule names a Hub Variable that is absent from the hub inventory.',
        next: 'Open the referencing rule and check whether the variable was renamed or deleted. Re-scan first if the inventory was incomplete.'
      },
      unreferencedDevice: {
        meaning: 'No scanned app owns, watches or drives this device.',
        normal: 'Dashboards, voice assistants, Maker API, external automations and disabled or unsupported apps may still use it.',
        next: 'Check those external uses and the physical device before deciding it is safe to remove.'
      },
      inertApp: {
        meaning: 'The scan found no device relationship, rule link or child app held by this app.',
        normal: 'Schedule-only apps, API integrations and unsupported automation engines can look inactive to this scan.',
        next: 'Open the app and check its status, schedules and external purpose before deciding it is unused.'
      },
      notificationOnly: {
        meaning: 'These devices receive only momentary notifications, chimes or speech commands.',
        normal: 'This is expected for phones, speakers and notification brokers.',
        next: 'No action is normally required unless a lasting-state command was expected.'
      },
      monitoredOnly: {
        meaning: 'These devices are read as triggers, conditions or monitored inputs but are never commanded.',
        normal: 'This is expected for sensors and other input-only devices.',
        next: 'No action is normally required unless an automation was meant to control the device.'
      },
      containerApp: {
        meaning: 'This app organises or owns child apps rather than touching devices directly.',
        normal: 'That is the expected structure for parent apps such as rule containers.',
        next: 'Review its child apps if you need detail. The parent itself is not a cleanup candidate.'
      },
      variableWithoutDecodedUsage: {
        meaning: 'No decoded rule reads or writes this Hub Variable.',
        normal: 'It may be unused, manually maintained, externally consumed or used by an app engine this scan cannot decode.',
        next: 'Check Connectors, dashboards and external integrations before deciding it is obsolete.'
      },
      inactiveRuleInvoked: {
        meaning: 'This rule is paused or disabled, but another rule still runs it. That step in the calling rule silently does nothing.',
        normal: 'Pausing a rule on purpose is normal. It is the caller that still expects it to work which makes this worth checking.',
        next: 'Either resume this rule, or open the calling rule and remove the action that no longer does anything.'
      },
      ruleFlaggedBroken: {
        meaning: 'Hubitat itself marks this rule as broken, usually because it references something that no longer exists.',
        next: 'Open the rule in Rule Machine. Hubitat shows the broken step directly, which is faster than working back from the map.'
      },
      disabledDeviceInUse: {
        meaning: 'This device is disabled, but automations still send it commands or wait on it as a trigger. Those commands cannot land and those triggers cannot fire.',
        normal: 'A device disabled deliberately while being repaired or replaced will look like this until the automations are updated too.',
        next: 'Either re-enable the device, or update the automations listed here so they no longer depend on it.'
      },
      inactiveRule: {
        meaning: 'This rule is paused or disabled, so it will not run.',
        normal: 'Almost always deliberate - seasonal automations, rules kept for reference, or ones paused by another rule.',
        next: 'No action needed unless you expected it to be running. Anything paused that another rule still calls is listed separately under Needs attention.'
      },
      unreferencedLocalVariable: {
        meaning: 'This Local Variable is declared in its rule, but no decoded trigger, condition or action reads or writes it.',
        normal: 'It may be genuinely unused, or used in a part of the rule this scan cannot decode. The same caveat already applies to Hub Variables with no decoded usage.',
        next: 'Open the owning rule and check whether the variable is still needed before removing it.'
      }
    }
  };
}

function buildInsights() {
  const nameOf = {};
  ALL_NODES.forEach(function (n) { nameOf[n.id] = n.title; });
  const D = deriveInsightData();
  const GUIDE = insightGuidance();

  // Progressive disclosure, not a report (from Gordon's own
  // verdict on the previous version: a checklist of waffle nobody would read).
  // The whole result has to be legible in the first viewport, so the summary
  // carries counts only - deliberately no device or app names up here - and
  // every name lives behind a section the reader chose to open.
  function row(id, metaText, detailHtml) {
    let h = '<div class="insRow" data-row>';
    h += '<span class="insName">' + extEsc(nameOf[id] || id) + '</span>';
    h += '<span class="insMeta">' + extEsc(metaText) + '</span>';
    h += '<button type="button" class="insBtn" data-focus="' + extEsc(id) + '">Show on map</button>';
    h += detailHtml ? '<button type="button" class="insBtn insChev" data-toggle-row aria-expanded="false" title="More">&#9656;</button>' : '<span class="insChevPad"></span>';
    h += '</div>';
    if (detailHtml) h += '<div class="insDetail" hidden>' + detailHtml + '</div>';
    return h;
  }

  // Names of the apps behind a row, as focus links rather than dead text -
  // reaching any detail in one click is the point of the redesign.
  function appLinks(ids) {
    return (ids || []).map(function (a) {
      return '<a href="#" data-focus="' + extEsc(a) + '">' + extEsc(nameOf[a] || a) + '</a>';
    }).join(' &middot; ');
  }

  function advice(key) {
    const g = GUIDE.findings[key];
    if (!g) return '';
    let h = '<p><b>What this means:</b> ' + extEsc(g.meaning) + '</p>';
    if (g.normal) h += '<p><b>Why it may be normal:</b> ' + extEsc(g.normal) + '</p>';
    h += '<p><b>Check next:</b> ' + extEsc(g.next) + '</p>';
    return h;
  }

  const PAGE = 5;
  function rows(ids, metaFor, detailFor) {
    let h = '';
    ids.slice(0, PAGE).forEach(function (id) { h += row(id, metaFor(id), detailFor ? detailFor(id) : ''); });
    if (ids.length > PAGE) {
      h += '<div class="insMore" hidden>';
      ids.slice(PAGE).forEach(function (id) { h += row(id, metaFor(id), detailFor ? detailFor(id) : ''); });
      h += '</div>';
      h += '<button type="button" class="insBtn insShowAll" data-show-all>Show all ' + ids.length + '</button>';
    }
    return h;
  }

  function section(key, title, count, summary, openByDefault, bodyHtml, healthyText) {
    const open = openByDefault && count > 0;
    let h = '<section class="insSec" data-sec="' + key + '">';
    h += '<button type="button" class="insHead" data-toggle-sec aria-expanded="' + (open ? 'true' : 'false') + '">';
    h += '<span class="insChev">' + (open ? '&#9662;' : '&#9656;') + '</span>';
    h += '<span class="insHeading"><span class="insTitle">' + extEsc(title) + '</span>';
    h += '<span class="insSummary">' + extEsc(summary) + '</span></span>';
    h += '<span class="insBadge' + (count ? '' : ' insBadgeZero') + '">' + count + '</span>';
    h += '</button>';
    h += '<div class="insBody"' + (open ? '' : ' hidden') + '>';
    h += count ? bodyHtml : '<p class="insOk">' + extEsc(healthyText) + '</p>';
    h += '</div></section>';
    return h;
  }

  // --- Needs attention: only things genuinely wrong -----------------------
  const scanBad = D.scan.status !== 'complete';
  const attentionCount = D.brokenTargets.length + (scanBad ? 1 : 0) +
    D.brokenApps.length + D.inactiveInvoked.length + D.disabledDevicesInUse.length +
    D.hubVar.webcoreDecodeIssues.length;
  let attentionBody = '';
  if (scanBad) {
    const what = D.scan.status === 'failed'
      ? 'The last scan did not finish, so everything below is incomplete.'
      : 'The last scan finished but could not read ' + D.scan.appsUnreadable + ' app(s) and ' + D.scan.devicesUnreadable + ' device(s). Findings below may be missing those.';
    attentionBody += '<p class="insLead">' + extEsc(what) + '</p>' + advice('scanIncomplete');
  }
  if (D.brokenTargets.length) {
    attentionBody += '<p class="insLead">' + D.brokenTargets.length + ' rule target(s) no longer exist. The referencing action still runs and silently does nothing.</p>';
    attentionBody += rows(D.brokenTargets,
      function (id) { return (D.referencesTo[id] || []).length + ' referencing'; },
      function (id) { return advice('brokenRuleReference') + '<p class="sub"><b>Referenced by:</b> ' + appLinks(D.referencesTo[id]) + '</p>'; });
  }
  if (D.brokenApps.length) {
    attentionBody += '<p class="insLead">' + D.brokenApps.length + ' rule(s) are marked broken by Hubitat itself.</p>';
    attentionBody += rows(D.brokenApps, function () { return 'flagged by Hubitat'; },
      function () { return advice('ruleFlaggedBroken'); });
  }
  if (D.inactiveInvoked.length) {
    attentionBody += '<p class="insLead">' + D.inactiveInvoked.length + ' paused or disabled rule(s) are still called by another rule, which silently does nothing at that step.</p>';
    attentionBody += rows(D.inactiveInvoked,
      function (id) { return (D.invokedBy[id] || []).length + ' calling'; },
      function (id) { return advice('inactiveRuleInvoked') + '<p class="sub"><b>Called by:</b> ' + appLinks(D.invokedBy[id]) + '</p>'; });
  }
  if (D.disabledDevicesInUse.length) {
    attentionBody += '<p class="insLead">' + D.disabledDevicesInUse.length + ' disabled device(s) are still commanded or used as a trigger. Those commands cannot land and those triggers cannot fire.</p>';
    attentionBody += rows(D.disabledDevicesInUse,
      function (id) { return (D.disabledDeviceUsers[id] || []).length + ' automations'; },
      function (id) { return advice('disabledDeviceInUse') + '<p class="sub"><b>Used by:</b> ' + appLinks(D.disabledDeviceUsers[id]) + '</p>'; });
  }
  if (D.hubVar.webcoreDecodeIssues.length) {
    attentionBody += '<p class="insLead">' + D.hubVar.webcoreDecodeIssues.length + ' webCoRE piston(s) have saved variable configuration that could not be decoded safely.</p>' + advice('webcoreVariableDecodeIssue') + '<ul class="insPlain">';
    D.hubVar.webcoreDecodeIssues.slice(0, 10).forEach(function (issue) {
      attentionBody += '<li>' + extEsc(nameOf[issue.appId] || issue.appId) + ' <span class="sub">' + extEsc(issue.error || 'decode-failed') + '</span></li>';
    });
    attentionBody += '</ul>';
  }

  // --- Shared control to confirm: review prompts, not faults ---------------
  const hv = D.hubVar;
  const hubVarWorth = hv.multipleWriters.length + hv.readersWithoutDecodedWriter.length +
    hv.writersWithoutDecodedReader.length + hv.unresolvedReferences.length;
  const reviewCount = D.contested.length + hubVarWorth;
  let reviewBody = '<p class="insLead">' + D.contested.length + ' device(s) have shared control. This is often intentional.</p>';
  reviewBody += rows(D.contested,
    function (id) { return D.statefulCommanders[id].length + ' automations'; },
    function (id) {
      return advice('contestedDevice') + '<p class="sub"><b>Controlling apps:</b> ' + appLinks(D.statefulCommanders[id]) + '</p>';
    });
  if (hv.multipleWriters.length) {
    reviewBody += '<p class="insLead">' + hv.multipleWriters.length + ' hub variable(s) have more than one writer. Shared state, not automatically a race.</p>';
    reviewBody += rows(hv.multipleWriters,
      function (id) { return hv.writers[id].length + ' writers'; },
      function (id) { return advice('multipleVariableWriters') + '<p class="sub"><b>Written by:</b> ' + appLinks(hv.writers[id]) + '</p>'; });
  }
  if (hv.readersWithoutDecodedWriter.length) {
    reviewBody += '<p class="insLead">' + hv.readersWithoutDecodedWriter.length + ' hub variable(s) are read but have no decoded rule writer.</p>';
    reviewBody += rows(hv.readersWithoutDecodedWriter,
      function (id) { return hv.readers[id].length + ' readers'; },
      function (id) { return advice('variableReadersWithoutWriter') + '<p class="sub"><b>Read by:</b> ' + appLinks(hv.readers[id]) + '</p>'; });
  }
  if (hv.writersWithoutDecodedReader.length) {
    reviewBody += '<p class="insLead">' + hv.writersWithoutDecodedReader.length + ' hub variable(s) are written but have no decoded rule reader.</p>';
    reviewBody += rows(hv.writersWithoutDecodedReader,
      function (id) { return hv.writers[id].length + ' writers'; },
      function (id) { return advice('variableWritersWithoutReader') + '<p class="sub"><b>Written by:</b> ' + appLinks(hv.writers[id]) + '</p>'; });
  }
  if (hv.unresolvedReferences.length) {
    reviewBody += '<p class="insLead">' + hv.unresolvedReferences.length + ' rule reference(s) name a hub variable that is not in the hub inventory.</p>' + advice('unresolvedVariableReference') + '<ul class="insPlain">';
    hv.unresolvedReferences.slice(0, 10).forEach(function (r) {
      reviewBody += '<li>' + extEsc(r.name) + ' <span class="sub">' + extEsc(r.kind || '') + ' by ' + extEsc(nameOf[r.appId] || r.appId || 'an app') + '</span></li>';
    });
    reviewBody += '</ul>';
    if (hv.unresolvedReferences.length > 10) {
      reviewBody += '<p class="sub">Showing 10 of ' + hv.unresolvedReferences.length + '.</p>';
    }
  }

  // --- Possibly unused ----------------------------------------------------
  const orphanApps = D.inertNodes.filter(function (n) { return !n.holds && !(n.kids && n.kids.length); });
  const cleanupCount = D.untouched.length + orphanApps.length;
  let cleanupBody = '';
  if (D.untouched.length) {
    cleanupBody += '<p class="insLead">' + D.untouched.length + ' device(s) are not referenced by any scanned app.</p>';
    cleanupBody += rows(D.untouched, function () { return 'no mapped references'; }, function () { return advice('unreferencedDevice'); });
  }
  if (orphanApps.length) {
    cleanupBody += '<p class="insLead">' + orphanApps.length + ' app(s) touch no device, link to no rule and hold nothing.</p>';
    cleanupBody += rows(orphanApps.map(function (n) { return n.id; }),
      function (id) {
        const n = ALL_NODES.filter(function (x) { return x.id === id; })[0];
        return (n && n.reason) ? n.reason : 'no reason recorded';
      }, function () { return advice('inertApp'); });
  }

  // --- Normal patterns: explanations, not findings -------------------------
  const containers = D.inertNodes.filter(function (n) { return n.holds || (n.kids && n.kids.length); });
  // Paused rules already reported under Needs attention because something calls
  // them are excluded here, so one rule is never counted as both a fault and an
  // expected pattern.
  const inactiveQuiet = D.inactiveApps.filter(function (id) { return D.inactiveInvoked.indexOf(id) < 0; });
  const normalCount = D.readOnly.length + D.notifiedOnly.length + containers.length +
    hv.noDecodedUsage.length + hv.directionUnknownUsage.length + inactiveQuiet.length + D.unreferencedLocals.length;
  let normalBody = '';
  if (D.notifiedOnly.length) {
    normalBody += '<p class="insLead">' + D.notifiedOnly.length + ' device(s) are commanded only by notifications, chimes or speech - nothing that leaves a lasting state. Normal for phones, speakers and brokers.</p>';
    normalBody += rows(D.notifiedOnly,
      function (id) { return D.anyCommanders[id].length + ' automations'; },
      function (id) { return advice('notificationOnly') + '<p class="sub"><b>Used by:</b> ' + appLinks(D.anyCommanders[id]) + '</p>'; });
  }
  if (D.readOnly.length) {
    normalBody += '<p class="insLead">' + D.readOnly.length + ' device(s) are never commanded in any form - referenced only as triggers, constraints or monitored inputs. Expected for sensors.</p>';
    normalBody += rows(D.readOnly, function () { return 'monitored only'; }, function () { return advice('monitoredOnly'); });
  }
  if (containers.length) {
    normalBody += '<p class="insLead">' + containers.length + ' app(s) hold other apps rather than touching devices themselves. Expected.</p>';
    normalBody += rows(containers.map(function (n) { return n.id; }),
      function (id) {
        const n = ALL_NODES.filter(function (x) { return x.id === id; })[0];
        const held = n ? (n.holds || (n.kids || []).length) : 0;
        return 'holds ' + held;
      }, function () { return advice('containerApp'); });
  }
  if (inactiveQuiet.length) {
    normalBody += '<p class="insLead">' + inactiveQuiet.length + ' rule(s) are paused or disabled and nothing else calls them. Usually deliberate.</p>';
    normalBody += rows(inactiveQuiet,
      function (id) {
        const n = ALL_NODES.filter(function (x) { return x.id === id; })[0];
        return (n && n.disabled) ? 'disabled' : 'paused';
      }, function () { return advice('inactiveRule'); });
  }
  if (D.unreferencedLocals.length) {
    normalBody += '<p class="insLead">' + D.unreferencedLocals.length + ' local variable(s) are declared but have no decoded read or write in their own rule.</p>';
    normalBody += rows(D.unreferencedLocals, function () { return 'no decoded usage'; },
      function () { return advice('unreferencedLocalVariable'); });
  }
  if (hv.noDecodedUsage.length) {
    normalBody += '<p class="insLead">' + hv.noDecodedUsage.length + ' hub variable(s) have no decoded reader or writer. They may be unused, or used by an app this scan cannot decode.</p>';
    normalBody += rows(hv.noDecodedUsage, function () { return 'no decoded usage'; }, function () { return advice('variableWithoutDecodedUsage'); });
  }
  if (hv.directionUnknownUsage.length) {
    normalBody += '<p class="insLead">' + hv.directionUnknownUsage.length + ' hub variable(s) are referenced by webCoRE with direction intentionally left unknown.</p>';
    normalBody += rows(hv.directionUnknownUsage,
      function (id) { return hv.users[id].length + ' webCoRE piston' + (hv.users[id].length === 1 ? '' : 's'); },
      function (id) { return advice('variableDirectionUnknown') + '<p class="sub"><b>Used by:</b> ' + appLinks(hv.users[id]) + '</p>'; });
  }

  // --- Assemble ------------------------------------------------------------
  const cards = [
    { key: 'attention', label: GUIDE.categories.attention.label, count: attentionCount },
    { key: 'review', label: 'Shared control', count: reviewCount },
    { key: 'cleanup', label: GUIDE.categories.cleanup.label, count: cleanupCount },
    { key: 'normal', label: GUIDE.categories.normal.label, count: normalCount }
  ];
  let html = '<div id="insRoot">';
  html += '<div class="insCards">';
  cards.forEach(function (c) {
    html += '<button type="button" class="insCard' + (c.count ? '' : ' insCardZero') + (c.key === 'attention' && c.count ? ' insCardAlert' : '') +
      '" data-jump="' + c.key + '"><b>' + c.count + '</b><span>' + extEsc(c.label) + '</span></button>';
  });
  html += '</div>';
  const firstCategory = attentionCount ? GUIDE.categories.attention
    : (reviewCount ? GUIDE.categories.review : (cleanupCount ? GUIDE.categories.cleanup : GUIDE.categories.normal));
  html += '<div class="insStart"><b>Start here:</b> ' + extEsc(firstCategory.next) + '</div>';
  html += '<p class="insNote">Counts are review prompts, not faults, and can include more than one finding for the same item. Open a row for what it means and what to check next.</p>';

  html += section('attention', GUIDE.categories.attention.label, attentionCount, GUIDE.categories.attention.summary, true, attentionBody,
    'Scan completed cleanly and every rule reference resolves.');
  html += section('review', GUIDE.categories.review.label, reviewCount, GUIDE.categories.review.summary, !attentionCount, reviewBody,
    'No shared lasting-state control and no hub variable worth a second look.');
  html += section('cleanup', GUIDE.categories.cleanup.label, cleanupCount, GUIDE.categories.cleanup.summary, !attentionCount && !reviewCount, cleanupBody,
    'Every device is referenced and every app does something.');
  html += section('normal', GUIDE.categories.normal.label, normalCount, GUIDE.categories.normal.summary, !attentionCount && !reviewCount && !cleanupCount, normalBody,
    'Nothing to explain here.');
  html += '</div>';
  return html;
}

document.getElementById('insightsBtn').addEventListener('click', function () {
  beginSelectionGeneration();
  document.getElementById('flowTitle').textContent = 'Automation health';
  setFlowSub('', false);
  flowChart.innerHTML = buildInsights();
  // Every other write to flowChart pairs it with this - Insights was the one
  // gap, leaving a previously-focused app's community card visible under it.
  renderDecodeCoverageCard(null);
  const varsBox = document.getElementById('ruleVariablesCard');
  if (varsBox) varsBox.innerHTML = '';
  renderCommunityCard(null);
  setFlowSizeMode(true);
  bringToFront(flowPanel);
});

// One delegated listener on the panel rather than listeners bound per row.
// The panel is rebuilt wholesale on every open and can hold several hundred
// rows; binding individually would both leak across rebuilds and cost more
// than the delegation lookup ever does.
flowChart.addEventListener('click', function (ev) {
  const root = ev.target.closest ? ev.target.closest('#insRoot') : null;
  if (!root) return;

  // Focus an entity on the map. Closing the panel is deliberate: the point of
  // the control is to look at the thing, and leaving the panel covering the
  // map would defeat it. focusNode() opens its own panel for an app anyway.
  const focusEl = ev.target.closest('[data-focus]');
  if (focusEl) {
    ev.preventDefault();
    const id = focusEl.getAttribute('data-focus');
    flowPanel.style.display = 'none';
    syncLegendVisibility();
    focusNode(id);
    return;
  }

  const secHead = ev.target.closest('[data-toggle-sec]');
  if (secHead) {
    const body = secHead.parentNode.querySelector('.insBody');
    const open = secHead.getAttribute('aria-expanded') === 'true';
    secHead.setAttribute('aria-expanded', open ? 'false' : 'true');
    const chev = secHead.querySelector('.insChev');
    if (chev) chev.innerHTML = open ? '&#9656;' : '&#9662;';
    if (body) body.hidden = open;
    return;
  }

  const rowChev = ev.target.closest('[data-toggle-row]');
  if (rowChev) {
    const detail = rowChev.parentNode.nextElementSibling;
    if (detail && detail.classList.contains('insDetail')) {
      const open = rowChev.getAttribute('aria-expanded') === 'true';
      rowChev.setAttribute('aria-expanded', open ? 'false' : 'true');
      rowChev.innerHTML = open ? '&#9656;' : '&#9662;';
      detail.hidden = open;
    }
    return;
  }

  const showAll = ev.target.closest('[data-show-all]');
  if (showAll) {
    const more = showAll.parentNode.querySelector('.insMore');
    if (more) { more.hidden = false; showAll.remove(); }
    return;
  }

  // A summary card opens its section and scrolls to it, so the cards are a
  // route into the detail rather than decoration.
  const jump = ev.target.closest('[data-jump]');
  if (jump) {
    const key = jump.getAttribute('data-jump');
    const sec = root.querySelector('[data-sec="' + key + '"]');
    if (!sec) return;
    const head = sec.querySelector('[data-toggle-sec]');
    const body = sec.querySelector('.insBody');
    if (head && head.getAttribute('aria-expanded') !== 'true') {
      head.setAttribute('aria-expanded', 'true');
      const chev = head.querySelector('.insChev');
      if (chev) chev.innerHTML = '&#9662;';
      if (body) body.hidden = false;
    }
    sec.scrollIntoView({ block: 'start' });
  }
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
  extBody.innerHTML = '<p class="sub">Loading...</p>';
  fetch(EXT_URL, { cache: 'no-store', credentials: 'omit' })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      EXT = d;
      extRender('');
      // Evidence is an enhancement, so the table is rendered first and
      // re-rendered only if the lookup succeeds. A slow or failed Community
      // Utilities fetch must never delay or break the panel.
      extLoadEvidence().then(function (m) {
        if (m && Object.keys(m).length && extPanel.style.display !== 'none') extRender('');
      });
    })
    .catch(function (e) {
      extBody.innerHTML = '<p class="sub">Could not load: ' + extEsc(e) + '</p>';
    });
}

function extRowsFor(type) {
  return (EXT.entries || []).filter(function (e) { return e.type === type; });
}

function extReviewedFor(type) {
  const claimed = (EXT.entries || []).some(function (e) { return e.type === type; });
  if (claimed) return [];
  return (EXT.reviewed || []).filter(function (e) { return e.type === type; });
}

// Rows the shared registry supplied, shown only where the user has said
// nothing and no reviewed local default exists for that app type.
function extRegistryFor(type) {
  const claimed = (EXT.entries || []).some(function (e) { return e.type === type; });
  const reviewed = (EXT.reviewed || []).some(function (e) { return e.type === type; });
  if (claimed || reviewed) return [];
  return (EXT.registry || []).filter(function (e) { return e.type === type; });
}

function extDefaultsFor(type) {
  const reviewed = extReviewedFor(type);
  return reviewed.length ? reviewed : extRegistryFor(type);
}

// Which group an app type belongs in. Order matters: the first source that
// answers wins, and a user declaration always outranks the rest.
function extClassify(type) {
  const info = (EXT.appTypeInfo || {})[type] || {};
  if ((EXT.entries || []).some(function (e) { return e.type === type; })) return { group: 'declared', info: info };
  if ((EXT.reviewed || []).some(function (e) { return e.type === type; })) return { group: 'reviewed', info: info };
  // Registry is checked BEFORE inheritance. With
  // the order reversed, a child type carrying its own reviewed dependency was
  // filed as inherited and dropped from the confirmed table - while the graph
  // still drew that dependency, because registryMatches() attaches it by
  // type. The panel would have said "inherits its parent assessment" about a
  // relationship visible on the map beside it. Only a child with neither a
  // declaration nor its own reviewed dependency should inherit.
  if ((EXT.registry || []).some(function (e) { return e.type === type; })) return { group: 'registry', info: info };
  if (info.isRoot === false) return { group: 'inherited', info: info };
  if ((EXT.builtinInternal || {})[type]) return { group: 'internal', info: info };
  return { group: 'unknown', info: info };
}

// Network evidence for an unknown root, from the projection the Context Card
// already downloads once per page view - no second fetch (a design review
// point 3). Strictly a review aid: LAN/CLOUD/BOTH names no dependency and
// must never create a graph node on its own, so it is shown as a badge and
// nothing here offers to accept it.
let EXT_EVIDENCE = null;
function extLoadEvidence() {
  if (EXT_EVIDENCE) return Promise.resolve(EXT_EVIDENCE);
  return loadCommunityContext().then(function (data) {
    // Every candidate per name, not just the first.
    // Keeping only the first meant that if it carried the wrong namespace and
    // a later record was the exact match, the valid evidence was thrown away
    // before anything could compare namespaces.
    const byName = {};
    (data.records || []).forEach(function (r) {
      (r.definitionIdentities || []).forEach(function (di) {
        const k = String(di.name || '').trim().toLowerCase();
        if (!k) return;
        if (!byName[k]) byName[k] = [];
        byName[k].push({ record: r, namespace: di.namespace || null });
      });
    });
    EXT_EVIDENCE = byName;
    return byName;
  }).catch(function () { EXT_EVIDENCE = {}; return EXT_EVIDENCE; });
}

function extEvidenceBadge(type) {
  if (!EXT_EVIDENCE) return '';
  const info = (EXT.appTypeInfo || {})[type] || {};
  const cands = EXT_EVIDENCE[String(type).trim().toLowerCase()];
  if (!cands || !cands.length) return '';

  // Namespace strengthens a match but its absence never proves anything
  // - it can equally mean the join produced
  // nothing. Where both sides declare one, require an exact match; anything
  // still ambiguous after that shows no badge rather than picking a winner.
  let pool = cands;
  const ourNs = info.namespace ? String(info.namespace).trim().toLowerCase() : null;
  if (ourNs) {
    const exact = cands.filter(function (c) {
      return c.namespace && String(c.namespace).trim().toLowerCase() === ourNs;
    });
    if (!exact.length) return '';
    pool = exact;
  }
  // Distinct records only: one record can supply several identities with the
  // same name, and that is not ambiguity.
  const distinct = [];
  pool.forEach(function (c) { if (distinct.indexOf(c.record) < 0) distinct.push(c.record); });
  if (distinct.length !== 1) return '';
  const rec = distinct[0];

  const ne = rec.networkEvidence;
  if (!ne || !ne.classification) return '';
  return '<span class="tag tag-reg" title="Community Utilities network evidence. Names no dependency - review before declaring one.">' +
    extEsc(ne.classification) + (ne.reviewed ? ', reviewed' : ', not reviewed') + '</span>';
}

function extRender(message) {
  const kinds = EXT.kinds || {};
  const crits = EXT.criticality || {};
  const none = EXT.noneMarker;

  // Classify every discovered type once, then render three groups instead of
  // one flat list where 57 inherited rule instances drown the handful of real
  // integrations.
  const groups = { declared: [], reviewed: [], registry: [], unknown: [], inherited: [], internal: [] };
  (EXT.appTypes || []).forEach(function (t) { groups[extClassify(t).group].push(t); });
  // Suggestions are split out of unknown so the two are visibly different
  // tasks: one has evidence to weigh, the other has nothing yet.
  const suggested = groups.unknown.filter(function (t) { return !!extEvidenceBadge(t); });
  const bare = groups.unknown.filter(function (t) { return !extEvidenceBadge(t); });

  let h = '<p class="sub">What each app needs <b>outside</b> your hub. The hub cannot detect this, so it is declared here and drawn on the map as a diamond with a dashed line. ' +
       'Apps sharing a system share one node, which is what makes it possible to ask what breaks if that system goes down.</p>';

  // Everything already answered, collapsed to a count rather than listed:
  // these are not tasks, and listing them is what buried the ones that are.
  const autoParts = [];
  if (groups.internal.length) {
    autoParts.push(groups.internal.length + ' app type(s) assessed as needing nothing outside the hub');
  }
  if (groups.inherited.length) {
    const instances = groups.inherited.reduce(function (n, t) {
      return n + (((EXT.appTypeInfo || {})[t] || {}).count || 0);
    }, 0);
    autoParts.push(groups.inherited.length + ' child type(s) covering ' + instances +
      ' installed app(s) inheriting a parent assessment');
  }
  if (autoParts.length) {
    h += '<p class="sub"><b>Classified automatically:</b> ' + extEsc(autoParts.join('; ')) + '. ' +
         '<button class="rowbtn" id="extShowAuto" type="button">Show these</button></p>';
    if (groups.inherited.length) {
      h += '<div id="extAutoList" style="display:none"><p class="sub">Inheriting a parent: ' +
           extEsc(groups.inherited.map(function (t) {
             const i = (EXT.appTypeInfo || {})[t] || {};
             return t + ' (under ' + (i.rootType || 'a parent') + ')';
           }).join(', ')) + '. Classify the parent to change these.</p></div>';
    }
  }

  h += '<table><thead><tr><th>App type</th><th>Needs</th><th>Kind</th><th>Needed for</th><th></th></tr></thead><tbody>';

  // Three groups, as headed sections rather than an ordered flat list - the
  // previous pass ordered these correctly but rendered them as one
  // undifferentiated table, which did not deliver the grouping at all.
  const sections = [
    { label: 'Confirmed external relationships', types: groups.declared.concat(groups.reviewed, groups.registry),
      note: 'Declared by you, supplied by a reviewed default, or matched in the reviewed registry.' },
    { label: 'Suggestions to review', types: suggested,
      note: 'Community Utilities reports network activity. It does not name a dependency - confirm before declaring one.' },
    { label: 'Not assessed', types: bare,
      note: 'Nobody has reviewed these yet.' },
    // Hidden until "Show these", but rendered as real rows rather than a text
    // list, so an Automation Map assessment stays overridable exactly like a
    // registry match. User declarations must always be able to win.
    { label: 'Assessed as internal only', types: groups.internal, auto: true,
      note: 'Reviewed app types that run entirely on the hub. Override any of these if your setup differs.' }
  ];

  sections.forEach(function (sec) {
    const hide = sec.auto ? ' class="autorow" style="display:none"' : '';
    h += (sec.auto ? '<tr class="autorow grouphdr" style="display:none">' : '<tr class="grouphdr">') +
      '<td colspan="5"><b>' + extEsc(sec.label) + ' (' + sec.types.length + ')</b>' +
      (sec.note ? ' <span class="sub">' + extEsc(sec.note) + '</span>' : '') + '</td></tr>';
    if (!sec.types.length) {
      h += '<tr' + hide + '><td colspan="5"><span class="sub">None.</span></td></tr>';
      return;
    }
    sec.types.forEach(function (type) {
      if (sec.auto) {
        const why = (EXT.builtinInternal || {})[type] || '';
        h += '<tr class="autorow" style="display:none"><td>' + extEsc(type) + '</td>' +
             '<td colspan="3"><span class="tag tag-none">nothing external needed</span> <span class="sub">' + extEsc(why) + '</span></td>' +
             '<td><button class="rowbtn" data-add="' + extEsc(type) + '">override</button></td></tr>';
        return;
      }
    const rows = extRowsFor(type);
    if (!rows.length) {
      const fromReviewed = extReviewedFor(type);
      if (fromReviewed.length) {
        fromReviewed.forEach(function (r, i) {
          h += '<tr class="fromreg"><td>' + (i === 0 ? extEsc(type) : '') + '</td>' +
               '<td>' + extEsc(r.name) + '</td>' +
               '<td>' + extEsc(kinds[r.kind] || r.kind) + '</td>' +
               '<td>' + extEsc(crits[r.crit] || r.crit) + '</td>' +
               '<td>' + (i === 0 ? '<span class="tag tag-reg">reviewed default</span>' +
                                   '<button class="rowbtn" data-over="' + extEsc(type) + '">override</button>' : '') +
               '</td></tr>';
        });
        return;
      }
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
      // "Not assessed" rather than "not classified": nobody has reviewed this
      // identity, which is a different and more honest statement than the app
      // being unclassifiable. Any network evidence
      // is shown beside it as a review aid, never as an answer.
      const nsInfo = (EXT.appTypeInfo || {})[type] || {};
      const badge = extEvidenceBadge(type);
      h += '<tr class="unclassified"><td>' + extEsc(type) +
           (nsInfo.namespace ? '<br><span class="sub">' + extEsc(nsInfo.namespace) + '</span>' : '') + '</td>' +
           '<td colspan="3"><span class="tag tag-unset">not assessed</span> ' + badge + '</td>' +
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
  const showAuto = document.getElementById('extShowAuto');
  if (showAuto) {
    showAuto.addEventListener('click', function () {
      const list = document.getElementById('extAutoList');
      const rows = extBody.querySelectorAll('.autorow');
      const hidden = rows.length ? rows[0].style.display === 'none' : (list && list.style.display === 'none');
      rows.forEach(function (r) { r.style.display = hidden ? '' : 'none'; });
      if (list) list.style.display = hidden ? '' : 'none';
      showAuto.textContent = hidden ? 'Hide these' : 'Show these';
    });
  }
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

  // Overriding seeds the user's rows from the reviewed default or registry, so
  // correcting one value does not mean retyping the rest.
  extBody.querySelectorAll('[data-over]').forEach(function (b) {
    b.addEventListener('click', function () {
      const type = b.getAttribute('data-over');
      extDefaultsFor(type)
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
// published contract). A read-only iframe preview of Community Utilities'
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

// A postMessage readiness handshake, not the iframe's 'load' event: 'load'
// fires even for a blocked or failed cross-origin response, so it cannot
// prove the embed rendered. The embed posts
// { type: 'automation-map-release-activity-ready', version: 1 } only after
// its chart has rendered, and only that verified message clears the timer.
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
    // "null" is expected, not a fallback: sandboxed without allow-same-origin,
    // the embed has an opaque origin and posts as "null". Provenance rests on
    // the source check below - this page created the iframe and set its src to
    // a fixed URL, and event.source cannot be forged. The host string is kept
    // so this still works if the embed is ever framed unsandboxed.
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

// makePanelDraggable() itself is defined much earlier (with flowPanel's own
// call), but ext/pivot/releaseActivity/icons's own panel consts are each
// declared beside their own *Load()/render code, scattered through the
// file - this is the point after the last of them (iconsPanel) exists, so
// it is the one safe place to wire up all four remaining panels at once
// rather than four separate call sites each needing its own header lookup.
[
  { panel: extPanel, id: 'ext' },
  { panel: pivotPanel, id: 'pivot' },
  { panel: releaseActivityPanel, id: 'releaseActivity' },
  { panel: iconsPanel, id: 'icons' }
].forEach(function (p) {
  makePanelDraggable(p.panel, document.querySelector('#' + p.id + ' .modernPanelHeader'));
});

function iconsLoad() {
  iconsBody.innerHTML = '<p class="sub">Loading...</p>';
  fetch(ICONS_URL, { cache: 'no-store', credentials: 'omit' })
    .then(function (r) { return r.json(); })
    .then(function (d) { ICONS = d; iconsRender(''); })
    .catch(function (e) {
      iconsBody.innerHTML = '<p class="sub">Could not load: ' + extEsc(e) + '</p>';
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

  let h = '<p class="sub">Each device is drawn with an icon guessed from its capabilities - a light looks like a ' +
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
    h += '<td><span class="devIconGlyph">' + (ICON_GLYPHS[iconsEffectiveKey(d)] || ICON_GLYPHS.unknown) + '</span>' + extEsc(d.name) + '</td>';
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
  // n.name is the stable identity with no live-status suffix baked in at
  // all (n.title is "Mode Alarm Reminder (Required Expression false)
  // (Rule-5.1) (Paused)", n.draw is the same minus the hub-injected
  // "(Required Expression false)" but DOES carry "(Paused)"/"(Disabled)"
  // since v2.1.7 - the status is exposed separately as apps[].status/
  // devices[].disabled instead). Falls back to draw, then title, for any
  // graph cached before name existed (review 372: draw itself gained
  // a live-status suffix this same version, so it is no longer a safe
  // identity fallback for a *current* graph, only for one old enough to
  // predate both fields).
  const nameOf = {};
  ALL_NODES.forEach(function (n) { nameOf[n.id] = n.name || n.draw || n.title; });

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

  // Every finding below now comes from deriveInsightData(), the same call the
  // Insights panel renders from. This block used to
  // recompute all of it independently, and the two had already drifted - the
  // export gained Hub Variable findings that the panel never got. The export
  // schema and wording are unchanged by the switch; only the source of the
  // numbers is now shared. Verified by capturing insights+summary before and
  // after and diffing them byte for byte.
  const INS = deriveInsightData();
  const GUIDE = insightGuidance();
  const missingIds = INS.missingIds;
  const referencesTo = INS.referencesTo;

  const commanders = INS.statefulCommanders;
  const touched = INS.touched;
  const contested = INS.contested.map(function (d) {
    return { device: ref(d, nameOf), commandedBy: commanders[d].map(function (a) { return ref(a, nameOf); }) };
  });
  const unreferencedDevices = INS.untouched.map(function (id) { return ref(id, nameOf); });
  const inertApps = INS.inertNodes
    .map(function (n) { return { app: ref(n.id, nameOf), reason: n.reason || 'no reason recorded' }; });
  const brokenRuleReferences = INS.brokenTargets.map(function (id) {
    return { target: ref(id, nameOf), referencedBy: (referencesTo[id] || []).map(function (a) { return ref(a, nameOf); }) };
  });

  // Silent-failure findings (v2.2.1). Additive fields, so no schema bump by the
  // rule stated above recommendedAiBehaviour. Each pairs a state with the second
  // fact that makes it actionable rather than reporting the state alone.
  const inactiveRulesStillCalled = INS.inactiveInvoked.map(function (id) {
    const n = nodeById[id];
    return {
      rule: ref(id, nameOf),
      state: (n && n.disabled) ? 'disabled' : 'paused',
      calledBy: (INS.invokedBy[id] || []).map(function (a) { return ref(a, nameOf); })
    };
  });
  const inactiveRules = INS.inactiveApps.map(function (id) {
    const n = nodeById[id];
    return { rule: ref(id, nameOf), state: (n && n.disabled) ? 'disabled' : 'paused' };
  });
  const rulesFlaggedBroken = INS.brokenApps.map(function (id) { return ref(id, nameOf); });
  const disabledDevicesStillUsed = INS.disabledDevicesInUse.map(function (id) {
    return {
      device: ref(id, nameOf),
      usedBy: (INS.disabledDeviceUsers[id] || []).map(function (a) { return ref(a, nameOf); })
    };
  });
  const unreferencedLocalVariables = INS.unreferencedLocals.map(function (id) { return ref(id, nameOf); });

  // Hub Variable findings (v2.0.14, schema 4 - parent spec 8.3/11.5). Reader/
  // writer/multiple-writer findings are computed from the same GRAPH.edges
  // data as every insight above. unresolvedReferences is the one exception:
  // it describes names that never became nodes at all (parent spec 6.3), so
  // it is sourced from Groovy's buildGraph() directly
  // (GRAPH.hubVariableUnresolvedReferences) rather than derived from
  // ALL_EDGES here. There is no unresolvedConnectors finding - every
  // reported Connector deviceId is trusted unconditionally, so
  // no case exists for this export to flag as unresolved.
  const hubVarWriters = INS.hubVar.writers;
  const hubVarUsers = INS.hubVar.users;
  const noDecodedUsage = INS.hubVar.noDecodedUsage.map(function (id) { return ref(id, nameOf); });
  const readersWithoutDecodedWriter = INS.hubVar.readersWithoutDecodedWriter.map(function (id) { return ref(id, nameOf); });
  const writersWithoutDecodedReader = INS.hubVar.writersWithoutDecodedReader.map(function (id) { return ref(id, nameOf); });
  const multipleHubVarWriters = INS.hubVar.multipleWriters.map(function (id) {
    return { variable: ref(id, nameOf), writers: hubVarWriters[id].map(function (a) { return ref(a, nameOf); }) };
  });
  const unresolvedHubVarReferences = INS.hubVar.unresolvedReferences.map(function (r) {
    return { name: r.name, kind: r.kind, referencedBy: ref(r.appId, nameOf) };
  });
  const directionUnknownHubVarUsage = INS.hubVar.directionUnknownUsage.map(function (id) {
    return { variable: ref(id, nameOf), usedBy: (hubVarUsers[id] || []).map(function (a) { return ref(a, nameOf); }) };
  });
  const webcoreVariableDecodeIssues = INS.hubVar.webcoreDecodeIssues.map(function (issue) {
    return { app: ref(issue.appId, nameOf), error: issue.error || 'decode-failed' };
  });

  const devices = ALL_NODES.filter(function (n) { return n.group === 'device'; }).map(function (n) {
    const ic = iconById[n.id];
    return {
      id: n.id, name: nameOf[n.id],
      room: ic ? ic.room : null,
      iconCategory: n.icon || 'unknown',
      capabilities: ic ? ic.capabilities : null,
      disabled: !!n.disabled
    };
  });
  const apps = ALL_NODES.filter(function (n) { return n.group === 'app'; }).map(function (n) {
    return {
      id: n.id, name: nameOf[n.id], appType: n.appType || null,
      // v2.1.7, schema 8: 'disabled' and 'paused' replace the collapsed
      // 'paused-or-disabled' value - the hub reports these as two distinct
      // signals (installedApp.disabled, Rule Machine's own paused appState),
      // not one, so this export no longer merges them. disabled wins when
      // both happen to be true, matching the map's own label precedence.
      status: n.missing ? 'deleted-but-referenced' : n.unreadable ? 'unreadable' :
        n.disabled ? 'disabled' : n.paused ? 'paused' :
        n.unscanned ? 'unscanned' : n.inert ? 'inert' : 'active',
      parentId: n.parent || null,
      childIds: n.kids || [],
      hasDecodedFlow: !!flowIds[n.id],
      hubVariableDecode: n.appType === 'webCoRE Piston' ? {
        status: n.webcoreVariableDecodeStatus || 'not-present',
        relationships: ['read', 'write', 'usesVar'],
        error: n.webcoreVariableDecodeError || null
      } : null,
      // v2.2.8: real per-piston coverage, decoded directly from saved
      // configuration - complete/partial/none/error, not a blanket
      // not-decoded. The parent app never gets a value beyond
      // parent-permissions-omitted; its own permission selections are never
      // presented as a piston's actual device use.
      deviceRelationshipCoverage: n.appType === 'webCoRE Piston' ? (n.webcoreDeviceRelationshipCoverage || 'none') :
        (n.appType === 'webCoRE' ? 'parent-permissions-omitted' : null)
    };
  });
  const externalSystems = ALL_NODES.filter(function (n) { return n.group === 'external'; }).map(function (n) {
    return { id: n.id, name: nameOf[n.id], kind: n.kindKey || null };
  });
  // v2.0.14, schema 4 (parent spec 11.3): variableType/identitySource/
  // connector come straight off the node - buildGraph() (Groovy) populates
  // them from the authoritative getAllGlobalVars() inventory.
  // v2.1.4, schema 5 (Gate C): the reference-derived fallback this
  // identitySource default used to cover is retired - every hubVariable node
  // that can still exist is confirmed against authoritative inventory (Gate C
  // decision 3), so identitySource is always 'hub-inventory' in practice now.
  //
  // review 296 correction: a missing/malformed identitySource must NOT default
  // to 'hub-inventory' here - that would convert an absent or invalid
  // provenance field into a POSITIVE claim of the highest confidence level
  // this export has, exactly backwards for a defensive fallback. Falls back
  // to null instead, so a genuine backend invariant violation stays visible
  // to a consumer rather than being quietly asserted as confirmed.
  // currentValue stays null in every default export (parent spec 10) - no
  // opt-in value export exists yet.
  const hubVariables = ALL_NODES.filter(function (n) { return n.group === 'hubVariable'; }).map(function (n) {
    return {
      id: n.id, name: nameOf[n.id],
      variableType: n.variableType || null,
      identitySource: n.identitySource || null,
      connector: n.connectorDeviceId ? { deviceId: n.connectorDeviceId, connectorType: n.connectorType || null } : null,
      currentValue: null
    };
  });
  const edges = ALL_EDGES.map(function (e) {
    return {
      fromId: e.from, fromName: nameOf[e.from] || e.from,
      toId: e.to, toName: nameOf[e.to] || e.to,
      relationship: e.kind,
      direction: (e.kind === 'usesVar' || e.kind === 'deviceRead') ? 'unknown' : null,
      // Only meaningful for 'action' edges (can this app leave the device in
      // a lasting state, versus a momentary command) - null rather than
      // false everywhere else, so it does not look like a real "no" for a
      // relationship kind the field was never about. A webCoRE action edge
      // sets stateful explicitly to null (v2.2.8) - the command name is
      // proven, whether it leaves a lasting state is not, and that is a
      // genuinely different thing from Rule Machine's own confirmed-false
      // (a recognized capability this app's own STATEFUL_CAPABILITIES
      // catalogue proved momentary) - !!e.stateful alone would have silently
      // collapsed both into the same false.
      stateful: e.kind === 'action' ? (e.stateful === null ? null : !!e.stateful) : null,
      // v2.0.14, schema 4 (parent spec 11.4): usageRole is populated on
      // proven Hub or Local Variable read edges (schema 6, v2.1.6, extends
      // this to Local reads) - a single trusted role (condition/trigger/
      // etc.) when every decoded occurrence for that pair agrees, otherwise
      // 'unknown-read' rather than an invented one, per parent spec 6.2's
      // explicit preference. writeSource is Hub-write specific - populated
      // only when a Hub Variable write's source device ID resolved in the
      // discovered device set - null on every other relationship kind for
      // both fields.
      usageRole: e.usageRole || null,
      writeSource: e.writeSource || null,
      // v2.2.8: bounded evidence only - the saved attribute a piston device
      // read decoded, or the command names a webCoRE action decoded. Never task
      // parameter values, never raw hashes. null on every other edge kind. A
      // piston read now carries the trigger or constraint role it was decoded
      // in, so the attribute rides those kinds too, not deviceRead alone.
      attribute: (e.kind === 'deviceRead' || e.kind === 'trigger' || e.kind === 'constraint') ? (e.attribute || null) : null,
      commands: (e.kind === 'action' && e.commands) ? e.commands : null
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
      // variableField (Gate C, v2.1.4) is an internal join key used only to
      // correct this step's own label against its classified reference
      // before export ever runs (see correctFlowVariableLabels() in Groovy)
      // - excluded here the same way devices already is, not user-facing
      // export data.
      Object.keys(step).forEach(function (k) { if (k !== 'devices' && k !== 'variableField') out[k] = step[k]; });
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
    // Gate C (v2.1.4): owner-scoped Local Variable definitions and classified
    // references, published by buildGraph() (Groovy) as GRAPH.ruleVariables,
    // keyed by the same appId as flows. See Supporting Docs/
    // local_hub_variable_identity_proposal.md and WIP/
    // local_hub_variable_gate_c_integration_plan.md section 6.2 for the
    // design. variableReferences is resolved (local/hub) evidence only -
    // ambiguous and unresolved records live in nonResolvedVariableReferences
    // instead, and neither ever contains a definition or runtime value.
    const rv = (GRAPH.ruleVariables && GRAPH.ruleVariables[appId]) || {};
    const localVariables = (rv.localVariables || []).map(function (v) {
      return { identity: v.identity, name: v.name, variableType: v.variableType || null };
    });
    const variableReferences = (rv.variableReferences || []).map(function (r) {
      return {
        name: r.canonicalName || r.name,
        scope: r.scope,
        localIdentity: r.localIdentity || null,
        operation: r.operation,
        usageRole: r.usageRole || null,
        evidenceKind: r.evidence ? r.evidence.kind : null,
        field: r.evidence ? r.evidence.field : null
      };
    });
    const nonResolvedVariableReferences = (rv.nonResolvedVariableReferences || []).map(function (r) {
      return {
        name: r.name,
        status: r.status,
        operation: r.operation,
        usageRole: r.usageRole || null,
        candidateScopes: r.candidateScopes || [],
        evidenceKind: r.evidence ? r.evidence.kind : null,
        field: r.evidence ? r.evidence.field : null,
        reason: r.reason || null
      };
    });
    return {
      appId: appId, appName: nameOf[appId] || appId, engine: n ? (n.appType || null) : null, steps: steps,
      localVariables: localVariables,
      variableReferences: variableReferences,
      nonResolvedVariableReferences: nonResolvedVariableReferences
    };
  });

  // Was a plain boolean, !scanError - technically correct but misleadingly
  // narrow: a scan with no top-level error can still have silently dropped
  // individual devices or apps that failed to read (see
  // deviceIdsUnreadable/appsUnreadable tracking added server-side).
  // "complete" now specifically means neither happened, not just that
  // nothing threw at the top level.
  const scanStatus = SCAN_META.scanError ? 'failed'
    : (SCAN_META.appsUnreadable > 0 || SCAN_META.devicesUnreadable > 0 || webcoreVariableDecodeIssues.length > 0) ? 'complete-with-gaps'
    : 'complete';
  const webcoreAppIds = {};
  apps.forEach(function (a) { if (a.appType === 'webCoRE Piston') webcoreAppIds[a.id] = true; });
  const webcoreVariableEdges = edges.filter(function (e) {
    return webcoreAppIds[e.fromId] &&
      (e.relationship === 'read' || e.relationship === 'write' || e.relationship === 'usesVar');
  });
  const summary = {
    deviceCount: devices.length,
    appCount: apps.length,
    externalSystemCount: externalSystems.length,
    hubVariableCount: hubVariables.length,
    hubVariablesWithConnectorCount: hubVariables.filter(function (v) { return !!v.connector; }).length,
    unresolvedHubVariableReferenceCount: unresolvedHubVarReferences.length,
    webcoreHubVariableUseCount: webcoreVariableEdges.length,
    webcoreHubVariableReadCount: webcoreVariableEdges.filter(function (e) { return e.relationship === 'read'; }).length,
    webcoreHubVariableWriteCount: webcoreVariableEdges.filter(function (e) { return e.relationship === 'write'; }).length,
    webcoreHubVariableUnknownUseCount: webcoreVariableEdges.filter(function (e) { return e.relationship === 'usesVar'; }).length,
    webcoreVariableDecodeIssueCount: webcoreVariableDecodeIssues.length,
    edgeCount: edges.length,
    decodedRuleFlowCount: ruleFlows.length,
    contestedDeviceCount: contested.length,
    unreferencedDeviceCount: unreferencedDevices.length,
    inertAppCount: inertApps.length,
    brokenRuleReferenceCount: brokenRuleReferences.length,
    // v2.1.4, schema 5 (Gate C): decoded evidence from the rules this export
    // could read, NOT a hub-wide inventory the way hubVariableCount above is
    // - see the limitations entry on this distinction.
    // v2.2.8: counted directly from graph nodes, not summed from
    // ruleFlows[].localVariables - a webCoRE piston's own local variables
    // had no ruleFlows entry when that sum was written, so it silently missed
    // every one of them once webCoRE locals existed at all. Pistons do carry a
    // decoded flow now, but their locals still live in localVariables[] rather
    // than nested under ruleFlows[], so this stays computed from the nodes.
    localVariableCount: ALL_NODES.filter(function (n) { return n.group === 'localVariable'; }).length,
    nonResolvedVariableReferenceCount: ruleFlows.reduce(function (sum, f) { return sum + (f.nonResolvedVariableReferences ? f.nonResolvedVariableReferences.length : 0); }, 0)
  };
  // What "apps[].hasDecodedFlow: false" can mean beyond "not a rule at
  // all" - named once here rather than only in the schema prose, so a
  // consumer can check membership programmatically instead of parsing
  // English out of the schema block.
  const limitations = [
    'Rules on these engines are never decoded, regardless of hasDecodedFlow: Room Lighting, Basic Rules, Simple Automation. They can still appear with device relationships. webCoRE pistons now carry a decoded flow covering statement order, branching, condition text and task parameters. A condition is transcribed from its own saved spelling and never interpreted: it collapses to an explicitly undecoded step whenever any part of it cannot be named in full, such as a group this decoder cannot read, a device token that did not resolve, an operand kind with no transcription, or a comparison with a time window (was, stays, changed), whose window is not transcribed. A switch case is not decoded, a switch default branch is not drawn, and the permitted-device selections on a webCoRE parent app remain omitted as permissions rather than relationships.',
    'Rule-to-rule edges (relationship: runs/cancelTimedActions/setspb/pauseResume) and Local Variable read/write edges are read from Rule Machine 5.1 only. Hub Variable read/write edges can also come from source-backed webCoRE saved-configuration decoding. webCoRE step-by-step flow is reconstructed for statement order and branching only, and never becomes an edge.',
    'Roles/edges reflect how a device is configured into an app, not what happened at runtime - this is a static configuration snapshot from the last scan (see scan.lastScanCompletedAt), not live state.',
    // v2.0.14, schema 4 (parent spec 11.6) - Hub Variable specific notes.
    'Hub Variable names are household data. Values are absent from this export entirely unless a future explicit opt-in adds them - currentValue is always null here.',
    'A Hub Variable with no decoded read, write or usesVar relationship (insights.hubVariables.noDecodedUsage) may still be used by an app or integration this export cannot decode - absence of a decoded edge is not proof the variable is unused.',
    'webCoRE Hub Variable edges come only from statically stored structures whose names begin @@ and reconcile to the authoritative Hub Variable inventory. Evaluated variable operands are reads; explicit setVariable targets, loop counters and matching/non-matching device captures are writes. A dynamically constructed target name is invisible and never guessed.',
    'Multiple writers on a Hub Variable (insights.hubVariables.multipleWriters) are not proof of a race condition - static configuration proves shared writers, not simultaneous execution.',
    'A Hub Variable connector is a synchronized projection of the same shared state (relationship: synchronizedWith), not an independent value - do not treat the variable and its connector device as two different things to reconcile.',
    'A Hub Variable write edge with a deviceAttribute writeSource means the rule copies or derives its write from that device attribute - it does not mean the device writes the Hub Variable directly.',
    'A Connector deviceId Hubitat reports is trusted directly and always resolved into hubVariables[].connector - there is no check against a case where that Connector was later deleted or replaced outside the normal remove-connector flow. Such a stale or orphaned ID would still be reported here as a resolved connector; this export cannot distinguish that from a genuine one with the data it has.',
    // v2.1.4, schema 5 (Gate C) - Local/Hub/Connector Variable identity notes.
    'ruleFlows[].localVariables and summary.localVariableCount are decoded evidence from the rules this export could read, not a hub-wide inventory the way hubVariables[] is - a Local Variable belonging to a rule on an undecodable engine, or one this scan could not read, is simply absent, not counted as zero.',
    'A Local Variable and a Hub Variable sharing the exact same name inside one rule cannot be told apart from stored Rule Machine configuration alone - this is a genuine platform ambiguity, not a decoding gap. Such a reference appears in ruleFlows[].nonResolvedVariableReferences with reason "same-name-cross-scope" and status "ambiguous", and creates no hubVariables[] edge.',
    'A reference to a Local or Hub Variable that no longer exists appears in ruleFlows[].nonResolvedVariableReferences with status "unresolved" rather than being silently dropped or treated as broken - Rule Machine itself may separately mark the underlying action broken (see the label on that flow step), which this export reflects but does not infer on its own.',
    // v2.1.6, schema 6 - Local Variable graph nodes.
    'A write/read edge in edges[] whose toId is absent from hubVariables[] is a Local Variable reference, not a data gap - resolve it by flattening ruleFlows[].localVariables[] and matching on identity (see the edges schema entry). Do not treat an unmatched toId as an error before checking there.',
    'A Local Variable with no matching edges[] entry has no proven decoded reference in this rule - not read in a trigger, condition or action, and not written. The same "may simply be unused" caveat that already applies to a Hub Variable with insights.hubVariables.noDecodedUsage applies here too; these are now also collected in insights.unreferencedLocalVariables.',
    'insights.rulesFlaggedBroken reflects the *BROKEN* marker Hubitat itself puts on an app label, which is the only place that state is exposed. It is read, not judged: absence of the marker is not proof a rule is healthy, and this scan cannot see runtime execution errors, failed actions or exceptions at all - nothing here is evidence about whether a rule actually ran or succeeded.',
    'insights.inactiveRulesStillCalled and insights.disabledDevicesStillUsed pair a paused/disabled state with a still-live reference, which is static configuration evidence that a step cannot do anything - not evidence that it was ever reached at runtime. The calling rule may itself be paused, conditional, or never triggered.'
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
    'Treat a usesVar edge as direction unknown. It proves a saved webCoRE reference to an inventory-confirmed Hub Variable, never a read or write on its own.',
    'Static configuration is not proof of runtime behaviour - do not claim it is.',
    'Do not frame contested devices, inert apps, or any other count as evidence the hub is in a bad state. A hub with dozens of rules and hundreds of devices will always show some of these as a normal by-product of scale - contested devices in particular are usually several ordinary rules sharing one light or switch (motion, time-of-day, manual override), not automations fighting. Avoid adversarial words - fighting, broken as an unqualified judgment, conflict - for anything the export itself does not use that word for; state the plain mechanism instead (the last app to run decides the outcome) and let the user judge whether it is intentional.',
    'State a count in proportion to the whole (e.g. "30 of 194 devices" rather than a bare "30 devices") so the user can judge scale themselves rather than be primed by an isolated number.',
    'Never infer a missing relationship solely because two names look similar.',
    'Resolve a write/read edge target by its id against hubVariables[] first, then ruleFlows[].localVariables[] (matched by identity) - never by assuming every such edge targets a Hub Variable, and never by joining on the toName field alone. A usesVar edge always targets an inventory-confirmed hubVariables[] entry.',
    'Never join a ruleFlows[] localVariables or variableReferences record to anything outside its own appId by name alone - Local Variable identity is owner-scoped (see localIdentity), and the same visible name in two different rules is two different variables. A nonResolvedVariableReferences record with status "ambiguous" must be reported as genuinely ambiguous, never resolved to either scope by guessing.',
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
      webcoreVariableDecodeIssues: webcoreVariableDecodeIssues,
      // v2.2.8, schema 12: genuine device-hash reconciliation failures only -
      // an expected coverage limit (a variable-backed or runtime-selected
      // device) is not counted here and does not affect status above.
      webcoreDeviceReconciliationGaps: INS.scan.webcoreDeviceReconciliationGaps || 0,
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
        supportedEngines: ['Rule Machine 5.1 read/write', 'webCoRE saved-configuration read/write'],
        limitations: ['webCoRE direction is classified only where its persisted structure maps to a source-proven runtime read or write; dynamically constructed target names remain invisible.', 'webCoRE parent device permissions are still omitted because a permission does not prove use; piston-to-device relationships ARE decoded from saved configuration as of v2.2.8, per piston, with per-piston coverage in apps[].deviceRelationshipCoverage.', 'Other app engines may use Hub Variables without exposing a decoded edge.']
      }
    },
    summary: summary,
    limitations: limitations,
    recommendedAiBehaviour: recommendedAiBehaviour,
    insightGuidance: GUIDE,
    privacyNote: 'Device, room and app names below reflect a real home. Treat this file with the same care as the underlying device list - review before sharing it outside a trusted context.',
    schema: {
      devices: 'Every device on the hub. iconCategory is a best-guess classification (lighting, doors, water, motion...), "unknown" if nothing matched. capabilities is the raw Hubitat capability list this device reports (what iconCategory was derived from); null if this device was not present in the same fetch that supplied room/capabilities (a scan run since the page loaded, in the rare case one raced this export). iconCategory "connector" (schema 4, v2.0.14) marks a Hub Variable Connector device - a virtual device Hubitat keeps synchronized with the value of a hubVariables[] entry, not an independent physical device; find the variable it belongs to via that variable connector.deviceId field (hubVariables[]) or the synchronizedWith edge naming this device as its target (edges[]). A Connector device is represented in the same bulk device-enumeration endpoint every other device on this hub is discovered through, but nested inside its "Variable Connectors" parent entry rather than as a top-level device (a live platform finding, corrected v2.1.7) - so on a build before that fix its capabilities/room could read null even though the hub reported them, and on this build they resolve the same as any other device once the whole endpoint tree, not just its top level, is walked. Confirmed live: Hubitat also creates its own single parent device named "Variable Connectors" that lists every per-variable Connector in one place. That parent device is classified iconCategory "connector" too (the same detection rule catches it), but no hubVariables[] entry links to it and no synchronizedWith edge names it as a target - it manages the feature, it is not synchronized with one specific variable. Do not assume every "connector" device resolves to exactly one hubVariables[] entry. disabled (schema 8) reflects the per-device Disabled toggle Hubitat itself reports - true if the device is turned off entirely, independent of any app or rule state; never inferred from missing subscriptions, inactivity, orphan status, driver type or parent-child position (item 18).',
      apps: 'Every installed app, including every automation rule. status: active | disabled | paused | inert (installed but touches nothing) | unscanned (never reached during the scan) | unreadable (hub would not answer for it) | deleted-but-referenced (no longer exists as an app, but another rule still names it - appType is null in this one case, expected, not a decoding gap). disabled and paused (schema 8) are reported separately, not merged into one collapsed value as in schema 7 and earlier - disabled is a hub-level toggle reported for any app type, paused is Rule Machine-specific execution-paused state reported only for a rule that has that concept; disabled wins when both happen to be true. parentId/childIds describe container apps (e.g. Button Controllers holding several Button Rules). hasDecodedFlow: true if this app has a matching entry in ruleFlows - false does not mean broken, it usually means the app is not a rule at all (an integration, a service) or is a rule on an engine this app cannot decode (Room Lighting, Basic Rules, Simple Automation). webCoRE pistons carry a decoded flow (v2.3.0). hubVariableDecode is present for webCoRE pistons only: status is complete, not-present or error; relationships lists the bounded read/write/usesVar relationship types the decoder can emit; error is a fixed code or null. It reports only saved Hub Variable relationship decoding, not webCoRE flow decoding. deviceRelationshipCoverage (schema 12, v2.2.8) is null for other apps; for a webCoRE piston it is complete (every direct device operand resolved), partial (at least one resolved and at least one did not - see edges[] for what did resolve), none (a clean decode found zero direct device operands), or error (the whole piston decode failed); for the webCoRE container itself it is always parent-permissions-omitted, since its own permission selections are never presented as an actual piston use.',
      externalSystems: 'Systems outside the hub an app depends on, drawn as nodes on the map - a mix of auto-matched community registry entries and declarations entered by the hub owner (see externalSystemDeclarations below for the raw declarations themselves, which is a different, smaller list - not every declared type becomes a node here, and not every node here came from a declaration).',
      hubVariables: 'Hub-wide shared state - every variable the hub itself reports (identitySource "hub-inventory") when authoritative inventory was available for this scan (see scan.hubVariableInventory.status), reconciled with variables one or more rules confirmed to read or write. v2.1.4 (schema 5, Gate C): the previous "reference-derived" identitySource - a decoded rule configuration reference not confirmed against authoritative inventory - is retired. Gate A found that a bare structured reference (an xVarV/xVar_/xVar picker value) alone does not prove Hub scope at all, since the same storage shape is used for a rule-local Local Variable, so this export no longer manufactures a Hub Variable node from an unconfirmed name; identitySource is expected to always be "hub-inventory" for every entry here - a null value would mean that expectation was violated, and should be treated as a defect report rather than a third valid category. A reference this app cannot confirm against authoritative inventory appears instead in ruleFlows[].nonResolvedVariableReferences with status "unresolved", never as a hubVariables[] entry - see the ruleFlows schema entry and the limitations on Local Variable identity below. variableType is Number/Decimal/String/Boolean/DateTime, or null if not yet resolved. connector is the linked Connector device ({deviceId, connectorType}) when Hubitat reports one, else null - see the synchronizedWith edge for the same relationship in the edges array. connectorType is the type the device itself reports when the regular device inventory for this hub independently lists it, otherwise the projected Connector attribute label Hubitat reports (observed live: "Variable", "Humidity") - not necessarily the underlying driver name. currentValue is always null in this export (see limitations). v2.1.6 (schema 6): this array is no longer the only possible target of a write/read edge in edges[] - a Local Variable can be one too; see the edges schema entry for how to tell them apart.',
      localVariables: 'Rule-owned variables, flat and complete across every engine, keyed by identity (schema 12, v2.2.8). Undocumented before schema 12 even though the array itself already existed, while the edges entry pointed consumers at ruleFlows[].localVariables[] instead - that nested copy only covers engines with a decoded flow, so it silently omits every webCoRE piston local. Join write/read edges against THIS array. ownerAppId is the single app that owns the variable, and a Local Variable only ever has that one app as an edge source. engine is resolved from that owning app, not from the variable, and is "Rule Machine" or "webCoRE". engineVariableType is the declared type the engine itself states where it states one (a webCoRE define block gives integer/string/boolean/dynamic); variableType is the Hubitat-style type and is null for webCoRE, which does not use it. unreferenced true means the variable is declared but no decoded read or write references it - an observation about the coverage of this decoder, not proof the rule never uses it. Values are never exported.',
      edges: 'Every relationship between two of the above, referenced by id (fromId/toId) - names are included for readability only and are not guaranteed unique, do not use them to join. relationship meanings - trigger: app listens to this device. constraint: a condition/required expression gates the app on this device. monitor: app reads this device state only, cannot command it. action: app can command this device (see stateful). exposed: published to an external system. owns: app created this device. hasComponent (graph schema 9, export schema 7): fromId is the parent device, toId is a device-owned component of it (e.g. a Shelly/Bond/Matter-bridge child, or a Hub Variable Connector nested under its "Variable Connectors" parent) - device-to-device, no app involved, and independent of whether any app or rule references either device. write/read: a Rule Machine rule or source-backed webCoRE saved structure sets or reads a variable - the target is a Hub Variable (present in top-level hubVariables[]) if toId matches a hubVariables[] id, otherwise a Local Variable (present in top-level localVariables[], keyed by identity - use that, not ruleFlows[].localVariables[], which only covers engines that expose a decoded flow and therefore omits every webCoRE piston local). usesVar: a fail-safe relationship for an inventory-confirmed webCoRE reference whose direction cannot be proven; direction is "unknown", and no arrow or read/write role is inferred. deviceRead (graph schema 14, export schema 12, v2.2.8): a webCoRE piston has a direct, statically decoded physical-device attribute read (see attribute below) that could NOT be attributed to a role - a read inside an expression or a task parameter. A read the piston performs in an event or a condition is emitted under trigger or constraint instead, decided by the comparison block webCoRE itself puts the operator in, so it matches the role the piston flowchart draws. action from a webCoRE piston (same relationship kind Rule Machine already uses) is a direct, statically decoded device command (see commands below); stateful is deliberately null on a webCoRE action edge, never inferred false, since the command name is proven but whether it leaves a lasting state is not. Both deviceRead and webCoRE action edges are resolved only against the permitted-device list belonging to the specific webCoRE parent app that piston belongs to - never a different parent app, never the whole-hub device inventory. direction is "unknown" only on deviceRead and usesVar edges. A Local Variable target only ever has exactly one write/read edge source, its own owning rule - see usageRole/writeSource below. synchronizedWith: a Hub Variable and its Connector device expose the same synchronized state - structural, not a read/write/trigger/action, and not evidence of device control. runs/cancelTimedActions/setspb/pauseResume: one rule acting on another rule. depends: an app needs an external system. stateful is only meaningful on action edges - true means the app can leave the device in a lasting on/off/level state, not just a momentary command, and more than one app doing this to the same device means the last one to run decides the outcome (see insights.contested) - common by design on a hub with many rules, not inherently a problem; null on every other relationship kind, where the concept does not apply. usageRole is populated on proven Hub or Local Variable read edges: a single trusted role when every decoded occurrence behind that edge agrees, otherwise "unknown-read" rather than an invented one; webCoRE reads use "unknown-read" because direction is proven without reconstructing a flow role. It is null on writes and usesVar. writeSource is populated only on a Rule Machine Hub Variable write edge whose source device attribute resolved to a real device ID ({kind: "deviceAttribute", deviceId, attribute}); it is null for webCoRE writes and every other relationship kind.',
      ruleFlows: 'One entry per app whose logic could be decoded, an array rather than an object keyed by name because app names on this hub are not guaranteed unique - join on appId. steps is the decoded trigger/condition/action sequence for that rule. cond/label on a step can legitimately be empty - "endif"/"else" control-flow steps exist only to close or branch a block and carry no condition of their own. references replaces what would otherwise be a bare device-name list: each entry is {type, id, name} (plus candidateIds when type is "ambiguous"). type is "device" or "app" (a Cancel Timed Actions/Run Rule Actions-style step names another RULE here, not a device - check type, do not assume), "self" for VRB’s "This Rule" (id is this same step’s own appId), "ambiguous" if the name matches more than one device or app on this hub (id is null, candidateIds lists every match - do not guess which one), or "unresolved" if the name matched nothing at all (id null - typically a stale/renamed reference). ruleTargets (cross-rule action steps only) is {id, name} the same way - always resolvable, an "a"-prefixed app id, never ambiguous. localVariables (schema 5, v2.1.4, Gate C) is this rule’s own Local Variable definitions, owner-scoped by this entry’s own appId - identity is "appId:name", never global; no value is ever included. As of schema 6 (v2.1.6), every entry here is also a first-class node on the graph and can appear as a write/read edge target in edges[] - see that schema entry. A definition with no matching edges[] entry has no proven decoded reference in this rule - not read in a trigger, condition or action, and not written. variableReferences (schema 5) is every read/write reference this app confirmed a scope for, "local" or "hub" only, joined to a localIdentity when local; a same-named Local and Hub Variable in the SAME rule cannot be told apart from stored configuration alone (a genuine platform ambiguity, not a decoding gap), so it never appears here - see nonResolvedVariableReferences. nonResolvedVariableReferences (schema 5) covers everything variableReferences excludes: status "ambiguous" (candidateScopes lists every scope that matched, most often ["local","hub"] for the same-name case above) or status "unresolved" (candidateScopes empty - no matching definition in either scope, most often a renamed or deleted variable). Neither array ever creates or implies a hubVariables[] entry on its own - see that schema entry.',
      insights: 'Pre-computed findings, every device/app/rule reference given as {id,name} rather than a bare name. contested: devices more than one app can leave in a lasting state, so the last app to run decides the outcome - common and often intentional on a hub with many rules (a motion-triggered rule and a manual-override rule both targeting one light, for example), worth confirming is not accidental, not evidence anything is wrong. unreferencedDevices: nothing on the hub owns, watches or drives them. inertApps: installed but touch no device and link to no rule, with why - very often a container holding other apps, or a schedule-only app, both entirely normal. brokenRuleReferences: a rule still names another rule/action/pause target that no longer exists - the action silently does nothing. inactiveRulesStillCalled (v2.2.1) - {rule, state: "paused"|"disabled", calledBy[]} - the rule will not run, yet another rule still invokes it, so that step in the caller silently does nothing; pause/resume links are deliberately excluded from calledBy, since a rule whose job is to resume this one is the mechanism working rather than a failure. rulesFlaggedBroken (v2.2.1) - Hubitat itself marks the rule broken via its own label, not a judgement this scan makes. disabledDevicesStillUsed (v2.2.1) - {device, usedBy[]} - the device is disabled while automations still command it or wait on it as a trigger, so those commands cannot land and those triggers cannot fire; constraint and monitor reads are excluded as a weaker, noisier claim. inactiveRules (v2.2.1) - every paused/disabled rule as plain context, almost always deliberate, and NOT a fault list; the actionable subset is inactiveRulesStillCalled. unreferencedLocalVariables (v2.2.1) - declared in a rule with no decoded read or write anywhere, carrying the same "may simply be unused, or used in a part this scan cannot decode" caveat as hubVariables.noDecodedUsage. hubVariables (schema 9) - neutral Hub Variable findings, never automatic fault claims (see limitations): noDecodedUsage (no decoded read, write or usesVar edge at all - may simply be unused, or used by an app this scan cannot decode), readersWithoutDecodedWriter (may be set manually, externally, or by an undecoded app), writersWithoutDecodedReader (may be consumed externally, or no longer needed), multipleWriters ({variable, writers} - shared state with more than one writer, not automatically a race), directionUnknownUsage ({variable, usedBy[]} - webCoRE saved references whose read/write direction is intentionally unknown), unresolvedReferences ({name, kind, referencedBy} - a proven structured reference to a name absent from a complete authoritative inventory), and webcoreDecodeIssues ({app,error} - fixed decoder failure codes, with no decoded configuration or values). There is no unresolvedConnectors field - a reported Connector deviceId is always trusted and resolved into hubVariables[].connector; see the limitations entry on orphaned/stale Connector IDs for what this trade-off cannot detect.',
      scan: 'lastScanCompletedAt is when the data behind this whole export was last refreshed from the hub (not when this file was generated - generatedAt above is that). lastScanError is whatever the app itself reported wrong with that scan, if anything. status is "complete" (nothing failed), "complete-with-gaps" (the scan finished but an app/device read, webCoRE variable decode, or webCoRE device-hash reconciliation had a bounded failure), or "failed" (lastScanError is set, the whole scan aborted). appsUnreadable/devicesUnreadable are scan-read counts; webcoreVariableDecodeIssues lists the affected pistons and fixed decoder codes without exposing decoded content. webcoreDeviceReconciliationGaps (schema 12, v2.2.8) counts only genuine device-hash reconciliation failures (unresolved, ambiguous, or a missing parent index) - a variable-backed or runtime-selected device reference is an expected, by-design coverage limit and does not count here or push status away from "complete". hubVariableInventory (schema 4) is kept deliberately separate from the status above - it describes whether the authoritative Hub Variable list the hub itself reports (not app/device scanning) succeeded this scan: status is "complete", "complete-with-gaps", "failed" or "not-supported"; count is how many variables the hub reported. When this status is not "complete" (v2.1.4, schema 5), a structured reference this scan cannot confirm against the incomplete inventory appears in ruleFlows[].nonResolvedVariableReferences with status "unresolved" rather than as a hubVariables[] entry. hubVariableRelationships describes Rule Machine and source-backed webCoRE Hub Variable read/write coverage, plus their limitations, independently of inventory status. webCoRE device relationships (schema 12, v2.2.8) are now decoded directly for physical-device reads and actions - see edges[] deviceRead/action and apps[].deviceRelationshipCoverage; a variable-backed device list, a runtime-selected device, or a non-physical/virtual device reference remain permanently outside what a static decode can ever resolve.',
      summary: 'Plain counts of every array below, for a quick sanity check or a one-line status line - not authoritative over the arrays themselves. hubVariablesWithConnectorCount and unresolvedHubVariableReferenceCount (schema 4) are the same kind of derived count as the others. webcoreHubVariableUseCount and webcoreVariableDecodeIssueCount summarize all webCoRE variable edges and fixed-code decode gaps; schema 10 adds separate read, write and unknown-use counts. localVariableCount (schema 12, v2.2.8) is counted directly from every owner-scoped Local Variable graph node across all supported engines - see the top-level localVariables[] array - not summed from ruleFlows[].localVariables alone, since a webCoRE piston never gets a ruleFlows entry at all. nonResolvedVariableReferenceCount (schema 5, v2.1.4) still totals ruleFlows[].nonResolvedVariableReferences across every decoded rule specifically - decoded evidence from the rules this export could read, not a hub-wide inventory the way hubVariableCount is.',
      limitations: 'Known, structural gaps in what this export can ever contain, independent of any particular hub - read this before concluding a rule is "missing" logic rather than on an engine this app cannot decode.',
      recommendedAiBehaviour: 'How an AI reading this file should behave, in three parts. Epistemic: identify versions, distinguish fact from inference, cite IDs over names, qualify conclusions built on a scan gap or an unresolved/ambiguous reference, never guess a relationship from name similarity alone. Tone: counts like contested devices or inert apps are normal at scale, not evidence of a bad state - avoid adversarial words (fighting, conflict, broken as an unqualified judgment) for anything the export itself does not use that word for, and state a count in proportion to the whole rather than in isolation. Response shape: open with a short plain-language summary naming a few specific apps or devices as evidence the file was actually read, state findings before recommendations, surface scan-quality caveats up front, and when more than one thing is worth pursuing offer it as a short menu and ask which to explore unless the request or the evidence makes the next investigation unambiguous, in which case proceed with it directly - every option offered must read as investigate or explain, never as an action taken or promised, since nothing here authorises any change to the hub.',
      insightGuidance: 'The same deterministic interpretation catalogue shown in the on-hub Insights panel. categories explains each group and its recommended priority; findings gives what the observation means, why it may be normal when applicable, and what to check next. It is guidance for investigation, never authority to change the hub.'
    },
    devices: devices,
    apps: apps,
    externalSystems: externalSystems,
    hubVariables: hubVariables,
    // v2.2.8, schema 12: every owner-scoped Local Variable definition across
    // every supported engine, not only the Rule Machine projection nested in
    // ruleFlows[].localVariables (a webCoRE piston never gets a ruleFlows
    // entry at all, so that array alone silently missed every webCoRE local -
    // the same gap summary.localVariableCount below has already been fixed
    // to read from graph nodes directly rather than repeating it here).
    localVariables: ALL_NODES.filter(function (n) { return n.group === 'localVariable'; }).map(function (n) {
      // engine comes from the OWNING app node: a localVariable node has no
      // appType of its own, so reading it here labels every local Rule Machine.
      const owner = n.ownerAppId ? ALL_NODES.filter(function (o) { return o.id === n.ownerAppId; })[0] : null;
      return {
        identity: n.id,
        name: nameOf[n.id],
        ownerAppId: n.ownerAppId || null,
        ownerAppName: n.ownerAppId ? (nameOf[n.ownerAppId] || null) : null,
        engine: (owner && owner.appType === 'webCoRE Piston') ? 'webCoRE' : 'Rule Machine',
        variableType: n.variableType || null,
        engineVariableType: n.engineVariableType || null,
        unreferenced: !!n.unreferencedLocal
      };
    }),
    edges: edges,
    ruleFlows: ruleFlows,
    insights: {
      contested: contested,
      unreferencedDevices: unreferencedDevices,
      inertApps: inertApps,
      brokenRuleReferences: brokenRuleReferences,
      // v2.2.1, additive. inactiveRulesStillCalled/disabledDevicesStillUsed are
      // genuine silent failures; inactiveRules/unreferencedLocalVariables are
      // context, almost always deliberate - see this section's limitations.
      inactiveRulesStillCalled: inactiveRulesStillCalled,
      rulesFlaggedBroken: rulesFlaggedBroken,
      disabledDevicesStillUsed: disabledDevicesStillUsed,
      inactiveRules: inactiveRules,
      unreferencedLocalVariables: unreferencedLocalVariables,
      // v2.0.14, schema 4 (parent spec 8.3/11.5). Neutral findings, not fault
      // claims - see recommendedAiBehaviour and this section's own limitations
      // note above.
      hubVariables: {
        noDecodedUsage: noDecodedUsage,
        readersWithoutDecodedWriter: readersWithoutDecodedWriter,
        writersWithoutDecodedReader: writersWithoutDecodedReader,
        multipleWriters: multipleHubVarWriters,
        directionUnknownUsage: directionUnknownHubVarUsage,
        unresolvedReferences: unresolvedHubVarReferences,
        webcoreDecodeIssues: webcoreVariableDecodeIssues
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
  // Spec 4.1 - focus returns to the control that
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
// The tool rail's own "Legend" button was removed (Gordon's live call - it
// opened the exact same panel as this "Full legend" link right next to the
// compact legend, and having both was pure redundancy, not two genuinely
// different paths). This is the only opener left.
document.getElementById('legendMoreBtn').addEventListener('click', function () {
  bringToFront(legendPanel);
});
document.getElementById('legendPanelClose').addEventListener('click', function () {
  legendPanel.style.display = 'none';
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
    'This is every app, device, variable and external system on your hub at once, so it looks busy - that is expected.<br><br>' +
    '<b>Click any node</b> to drill in, or use Quick Search or the dropdowns above to jump straight to an app, device or variable. Click a rule and you also get a flowchart of how it works. Click one of its devices to see everything else touching that device.<br><br>' +
    '<b>Other panels:</b> Insights (a health check for the whole map), External systems, Pivot tables, Device icons, Hubitat release activity.<br><br>' +
    'Take your time to explore.' +
    '<div style="margin-top:12px"><button id="hintClose" type="button">Got it</button></div>';
  document.body.appendChild(hint);
  document.getElementById('hintClose').addEventListener('click', function () {
    hint.style.display = 'none';
    try { localStorage.setItem('amHintDismissed', '1'); } catch (e) { }
  });
})();

// Each combobox's onChange enforces the four-way focus exclusivity (picking
// one clears the other three) the same way the old <select> 'change'
// listeners did - setValue() never fires onChange itself, so these resets
// cannot recurse into each other.
//
// Named (not inline) as of backlog item 1 Phase 3: the grouped entity search
// prototype (searchAllSelect, below) needs to trigger the exact same focus
// behaviour as picking directly from one of these four, and reusing these
// functions by reference is how that is guaranteed identical rather than
// reimplemented and risking drift. Every reference to appSelect/deviceSelect/
// hubVarSelect/localVarSelect below still resolves correctly despite being
// used before its own const line further down - none of these functions runs
// until a later click, by which point all four consts exist, same as every
// other forward reference already in this file.
function onAppFocusChange(value) {
  beginSelectionGeneration();
  externalFocusId = null;
  if (value !== '__all__') {
    deviceSelect.setValue('__all__');
    hubVarSelect.setValue('__all__');
    localVarSelect.setValue('__all__');
    closeSecondaryPanels();
  }
  applyFilters();
  if (value === '__all__') {
    flowPanel.style.display = 'none';
    syncLegendVisibility();
  } else {
    showFlow(value);
  }
}
function onDeviceFocusChange(value) {
  beginSelectionGeneration();
  externalFocusId = null;
  if (value !== '__all__') {
    appSelect.setValue('__all__');
    hubVarSelect.setValue('__all__');
    localVarSelect.setValue('__all__');
    closeSecondaryPanels();
  }
  flowPanel.style.display = 'none';
  syncLegendVisibility();
  applyFilters();
}
function onHubVarFocusChange(value) {
  beginSelectionGeneration();
  externalFocusId = null;
  if (value !== '__all__') {
    appSelect.setValue('__all__');
    deviceSelect.setValue('__all__');
    localVarSelect.setValue('__all__');
    closeSecondaryPanels();
  }
  flowPanel.style.display = 'none';
  syncLegendVisibility();
  applyFilters();
}
function onLocalVarFocusChange(value, item) {
  beginSelectionGeneration();
  externalFocusId = null;
  if (value !== '__all__') {
    appSelect.setValue('__all__');
    deviceSelect.setValue('__all__');
    hubVarSelect.setValue('__all__');
    closeSecondaryPanels();
  }
  // Same unreferenced exemption as focusNode's own localVariable branch -
  // picking one straight from this dropdown must not collapse the map to a
  // lone dot any more than clicking its node on the canvas would.
  if (item && item.unreferencedLocal) {
    showUnreferencedLocalPanel(item);
  } else {
    flowPanel.style.display = 'none';
    syncLegendVisibility();
    applyFilters();
  }
}
const appSelect = initCombo('appComboMount', 'app', 'All apps', 'search apps...', onAppFocusChange);
const deviceSelect = initCombo('deviceComboMount', 'device', 'All devices', 'search devices...', onDeviceFocusChange);
const hubVarSelect = initCombo('hubVarComboMount', 'hubVariable', 'All hub variables', 'search hub variables...', onHubVarFocusChange);
const localVarSelect = initCombo('localVarComboMount', 'localVariable', 'All local variables', 'search local variables...', onLocalVarFocusChange);

// Grouped entity search prototype (backlog item 1 Phase 3), sitting
// alongside the four selectors above rather than replacing them - the doc's
// own gate is that this has to reproduce identical results before the old
// ones can go. It does not reimplement focus behaviour at all: on pick, it
// forwards straight into the exact same onXFocusChange function the matching
// dropdown itself uses, so any correctness the four selectors already have
// is inherited rather than re-proven.
//
// Builds its own item objects rather than reusing the ALL_NODES entries the
// four selectors above read directly - those objects get n.optionText
// overwritten by initCombo() for their own dropdown's decoration, and reusing
// the same object here would silently rewrite that dropdown's own display
// text to this one's group-prefixed version. Copies only what a combobox
// item needs, so the two can never collide.
const SEARCH_ALL_GROUP_LABEL = { app: 'App', device: 'Device', hubVariable: 'Hub Variable', localVariable: 'Local Variable', external: 'External system' };
const searchAllItems = ALL_NODES.filter(function (n) {
  return SEARCH_ALL_GROUP_LABEL.hasOwnProperty(n.group);
}).map(function (n) {
  return {
    id: n.id,
    title: n.title,
    optionText: SEARCH_ALL_GROUP_LABEL[n.group] + ' - ' + pickOptionText(n, n.group),
    disabled: n.disabled,
    paused: n.paused,
    unreferencedLocal: n.unreferencedLocal,
    group: n.group
  };
});
const searchAllSelect = createCombobox({
  mount: document.getElementById('searchAllComboMount'),
  allLabel: 'Search apps, devices, hub & local variables, external systems',
  placeholder: 'search everything...',
  items: searchAllItems,
  onChange: function (value, item) {
    if (value === '__all__' || !item) return;
    if (item.group === 'app') { appSelect.setValue(value); onAppFocusChange(value); }
    else if (item.group === 'device') { deviceSelect.setValue(value); onDeviceFocusChange(value); }
    else if (item.group === 'hubVariable') { hubVarSelect.setValue(value); onHubVarFocusChange(value); }
    else if (item.group === 'localVariable') { localVarSelect.setValue(value); onLocalVarFocusChange(value, item); }
    // External systems have no Focus dropdown; focusNode() holds one as its own
    // focus while every dropdown shows All.
    else if (item.group === 'external') { focusNode(value); }
    // A jump tool, not a fifth persistent selection state alongside the other
    // four - resets to blank immediately after dispatching. The one matching
    // dropdown above already shows the real state (set two lines up).
    searchAllSelect.setValue('__all__');
  }
});

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

// An external system focused from Quick Search or the canvas. It has no Focus
// dropdown, so it is held here while all four dropdowns show All. var, not let:
// applyFilters can run before this line does.
var externalFocusId = null;
function currentFocus() {
  if (appSelect.getValue() !== '__all__') return appSelect.getValue();
  if (deviceSelect.getValue() !== '__all__') return deviceSelect.getValue();
  if (hubVarSelect.getValue() !== '__all__') return hubVarSelect.getValue();
  if (localVarSelect.getValue() !== '__all__') return localVarSelect.getValue();
  return externalFocusId || null;
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
  fitCurrentView();
}

function exitToWholeMap() {
  beginSelectionGeneration();
  externalFocusId = null;
  appSelect.setValue('__all__');
  deviceSelect.setValue('__all__');
  hubVarSelect.setValue('__all__');
  localVarSelect.setValue('__all__');
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
  beginSelectionGeneration();
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
  externalFocusId = null;
  closeSecondaryPanels();
  if (node.group === 'app') {
    appSelect.setValue(node.id, node.title);
    deviceSelect.setValue('__all__');
    hubVarSelect.setValue('__all__');
    localVarSelect.setValue('__all__');
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
    // into that branch by default (setValue on deviceSelect), which worked
    // visually but mis-filed it as a device selection. Split out once this
    // dropdown existed to give it somewhere correct to go.
    hubVarSelect.setValue(node.id, node.title);
    appSelect.setValue('__all__');
    deviceSelect.setValue('__all__');
    localVarSelect.setValue('__all__');
    flowPanel.style.display = 'none';
    syncLegendVisibility();
    applyFilters();
  } else if (node.group === 'localVariable') {
    // Own branch, same reasoning as Hub Variable's above it - a Local
    // Variable is neither an app nor a device. Referenced (has an edge)
    // filters to its neighbourhood, which is always exactly its one owning
    // rule by construction. Unreferenced mirrors the inert-app exemption
    // just above: no edges means nothing for applyFilters to draw, so its
    // own panel opens instead of collapsing the map to a lone dot.
    localVarSelect.setValue(node.id, node.title);
    appSelect.setValue('__all__');
    deviceSelect.setValue('__all__');
    hubVarSelect.setValue('__all__');
    if (node.unreferencedLocal) {
      showUnreferencedLocalPanel(node);
    } else {
      flowPanel.style.display = 'none';
      syncLegendVisibility();
      applyFilters();
    }
  } else if (node.group === 'external') {
    externalFocusId = node.id;
    appSelect.setValue('__all__');
    deviceSelect.setValue('__all__');
    hubVarSelect.setValue('__all__');
    localVarSelect.setValue('__all__');
    flowPanel.style.display = 'none';
    syncLegendVisibility();
    applyFilters();
  } else {
    deviceSelect.setValue(node.id, node.title);
    appSelect.setValue('__all__');
    hubVarSelect.setValue('__all__');
    localVarSelect.setValue('__all__');
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
      // Delegated rather than hand-rolled: a near-copy here drifted out of
      // sync and stopped closing panels or resetting kindFilter.
      // exitToWholeMap() guards its own pushState behind !poppingHistory,
      // which is false for the duration of this handler, so no spurious
      // history entry is added.
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
const COMMUNITY_UTILITIES_SOUND_URL = 'https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/${isDevBuild() ? 'dev' : 'main'}/assets/community-utilities-sound.mp3';
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
// Re-fits whatever is currently shown (whole map or a focused view) without
// changing what's focused - fitCurrentView() already knows which case it is
// from the live DataSet, same path panel open/close and resize already use.
document.getElementById('fitMapBtn').addEventListener('click', function () {
  fitCurrentView();
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
