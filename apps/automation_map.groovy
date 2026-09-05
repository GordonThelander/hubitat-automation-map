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
 * builder from the annotated Dev source at commit f07799d33df079ce04cdf7c7343606c4e8bb33fd; developer
 * comments and Dev-only build markers are not present in this file.
 *
 * Canonical annotated source:
 * https://github.com/GordonThelander/hubitat-automation-map/blob/f07799d33df079ce04cdf7c7343606c4e8bb33fd/apps/automation_map.groovy
 */
import groovy.transform.Field
import groovy.json.JsonOutput
import java.util.regex.Pattern
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@Field static final String APP_NAME = 'Automation Map'




@Field static final String APP_FAMILY = 'Automation Map'
@Field static final String APP_VERSION = '2.2.3'

















@Field static final String BUILD_CHANNEL = 'production'
@Field static final int DIAGNOSTIC_LEVEL = 0










boolean isDevBuild() {
    return BUILD_CHANNEL == 'dev'
}






























@Field static final String GRAPH_SCHEMA = '10'



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
    log.warn "${app.label}: state.graph was missing after a completed scan - rebuilding from existing scan data instead of requiring a fresh scan"
    state.hubVariableInventory = fetchHubVariableInventory()
    state.graph = buildGraph()
    atomicState.graphVersion = GRAPH_SCHEMA
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
        log.warn "${app.label}: clearing resurrected scan flags for an already-completed generation"
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
            
            
            
            
            
            
            
            
            
            
            log.warn "${app.label}: complete app results were published but graph finalization never ran - finishing now"
            finishScan([lockToken: currentToken, logicalGen: activeGen])
        } else {
            
            
            
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
        log.warn "${app.label}: registry fetch failed, continuing without it: ${ex.message}"
    }

    
    
    
    
    
    
    REGISTRY_RESULTS.put(genKey(lockToken), [meta: meta, matches: matches, createdAt: now()])

    
    
    
    
    if (!ownsLock(lockToken)) {
        if (diagOn()) log.info "${app.label}: registry fetch completed for a superseded scan generation, discarding without publishing"
        return
    }

    
    
    if (!meta.error) state.registryMatches = matches
    state.registryMeta = meta
    
    
    
    if (meta.error) {
        log.warn "${app.label}: registry unavailable, continuing without it"
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

        if (diagOn()) log.info "${app.label}: scan complete - ${(state.appInfo as Map).size()} app(s), ${(state.deviceLabels as Map).size()} device(s)"

        
        
        
        
        Long scanStartedAtMs = lockToken.tokenize('-')[1] as Long
        
        
        state.lastScanDurationSeconds = ((now() - scanStartedAtMs) / 1000).intValue()
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

            
            
            
            
            
            
            
            
            
            subscribed.each { String devId ->
                List existing = (roles[devId] ?: []) as List
                if (!existing) addRole(roles, devId, 'trigger')
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

Map buildGraph() {
    Map labels = (state.deviceLabels ?: [:]) as Map
    Map deviceCaps = (state.deviceCapabilities ?: [:]) as Map
    Map deviceTypes = (state.deviceTypes ?: [:]) as Map
    Set disabledDevices = (state.deviceDisabled ?: []) as Set
    Map iconOverrides = (state.deviceIconOverrides ?: [:]) as Map
    Map iconNotes = (state.deviceIconNotes ?: [:]) as Map
    Map appInfo = (state.appInfo ?: [:]) as Map

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
        
        
        
        
        
        
        
        
        
        if (!"${appMap.type}".startsWith('Rule-')) return
        String classifyAppNodeId = "a${appId}"
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
    }

    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    List unresolvedHubVarReferences = []
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
        Map roles = (appMap.roles ?: [:]) as Map
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        boolean unreadable = appMap.error != null
        
        
        
        
        
        
        
        
        
        
        
        boolean hasVarRelationship = ((ruleVariables["a${appId}"]?.variableReferences ?: []) as List)
            .any { Map r -> r.scope == 'hub' }
        boolean inert = !unreadable && !roles && !(appMap.ruleLinks ?: []) && !(appMap.endpoints ?: []) && !hasVarRelationship
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
        
        
        
        if (appMap.flow) flows[appNodeId] = resolveFlowTargets(appMap.flow as List, appInfo, nameCache)
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
            (devRoles as List).each { String role ->
                String key = "${appNodeId}|${devNodeId}|${role}"
                if (seen.contains(key)) return
                seen << key
                Map edge = [from: appNodeId, to: devNodeId, kind: role]
                if (role == 'action' && statefulDevices.contains(devId)) edge.stateful = true
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

        
        
        
        
        
        
        
        
        
        
        
        List localDefs = (ruleVariables[appNodeId]?.localVariables ?: []) as List
        localDefs.each { Map d ->
            String identity = d.identity as String
            if (!identity) return
            if (!nodes[identity]) {
                String ownerLabel = (nodes[appNodeId]?.title ?: appNodeId) as String
                nodes[identity] = nodeEntry(identity, d.name as String, 'localVariable', "Local Variable in ${ownerLabel}")
                nodes[identity].ownerAppId = appNodeId
                nodes[identity].variableType = d.variableType
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
            hubVariableConnectorCount: hubVarConnectorCount,
            
            
            
            
            
            ruleVariables: ruleVariables]
}







void rebuildStoredGraph() {
    state.graph = buildGraph()
    atomicState.graphVersion = GRAPH_SCHEMA
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
        exportSchemaVersion: 8,
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
  html, body { margin:0; padding:0; height:100%; background:#062733; color:#eee; font-family:'Mulish', ui-sans-serif, system-ui, sans-serif; }
  #status { position:absolute; top:10px; left:10px; z-index:10; background:#81BC00; border:1px solid #5c8500; padding:10px 14px; border-radius:999px; font-size:0.85em; color:#121214; font-weight:600; width:375px; box-sizing:border-box; text-align:center; }
  /* Fixed width, matching #status exactly (was max-width, sized to
     content) - the two need to line up regardless of viewport width, not
     just coincidentally happen to at one particular size. */
  /* Fixed width (matching #status) made rows wrap more, running the panel
     off the bottom of the screen - smaller text plus a hard max-height/
     scroll safety net so it can never do that again regardless of viewport
     height or how much the legend itself grows later. */
  #legend { position:absolute; top:55px; left:10px; z-index:10; background:rgba(0,0,0,0.55); padding:10px 14px; border-radius:14px; font-size:12px; width:375px; box-sizing:border-box; max-height:calc(100vh - 70px); overflow-y:auto; }
  #controls { position:absolute; top:10px; right:10px; z-index:10; background:rgba(0,0,0,0.55); padding:10px 14px; border-radius:14px; font-size:14px; display:flex; flex-direction:column; gap:6px; width:300px; }
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
  .cb-popup { position:absolute; z-index:50; right:0; width:480px; top:calc(100% + 2px); background:#041b23; border:1px solid #1e5878; border-radius:12px; box-shadow:0 6px 22px rgba(0,0,0,0.45); overflow:hidden; }
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
     where physics settles the graph's own bounding box. Moved off dead
     centre (was 50/50) since a fully-populated graph's own node cluster
     tends to sit left-of-centre; positioned below #controls specifically,
     horizontally centred under the Exit map button, per Gordon's own
     instruction, confirmed against a live screenshot rather than guessed.
     right, not left: #controls itself is anchored right:10px and 300px
     wide, so its own horizontal centre is a fixed distance from the
     viewport's RIGHT edge (10px + 150px = 160px) regardless of viewport
     width - a left:X% value has no way to track that reliably, which is
     why the panel's own 150px->300px widening (item, live 2026-09-02) threw
     the previous left:82% off centre under Exit map. */
  #hubWatermark { position:fixed; top:76%; right:160px; transform:translate(50%, -50%);
                  max-width:38vw; max-height:38vh; opacity:0.50; pointer-events:none;
                  user-select:none; }
  /* Hub photo specifically shown at half the Christmas tree's size, per
     Gordon's request - the tree's own dimensions (38vw/38vh) are unaffected. */
  #hubWatermark.hubPhoto { max-width:19vw; max-height:19vh; }
  /* First shipped as a bare 1em glyph with no background - reported as "had to
     go hunting for it". A visible pill with its own border and a hover state
     reads as a button; a lone triangle in a wall of text does not. */
  /* Matches the right-hand panel's own theme: blue accent border/background
     on the toggle pill, letter-spaced accent-blue heading text. */
  #legend-head { display:flex; align-items:center; gap:8px; cursor:pointer; user-select:none; font-weight:800; letter-spacing:0.4px; color:#7fb6d6; padding:2px; border-radius:4px; }
  #legend-head:hover { background:rgba(255,255,255,0.10); }
  #legend-toggle { background:#123a52; border:1px solid #1e5878; color:#cfe9fb; font-size:1.15em; line-height:1; width:22px; height:22px; border-radius:999px; padding:0; cursor:pointer; }
  #legend.collapsed #legend-body { display:none; }
  #legend.collapsed { padding:6px 10px; }
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
  #hint { position:absolute; bottom:16px; right:16px; z-index:15; background:#0a2530; padding:14px 18px; border-radius:6px;
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
  #communityCard { margin-top:14px; padding:12px 14px; border-radius:6px; background:#eef3f5; color:#1a2733; max-width:50%; }
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
  #extClose { position:absolute; top:8px; right:10px; cursor:pointer; background:none; border:none; color:#bbb; font-size:1.1em; }
  /* Its own panel rather than reusing #ext or #flow's markup - a table of
     links and a small query builder is a different shape of content from
     either (a settings form, a rule flowchart), and this file's convention
     throughout is one panel's CSS per panel rather than a shared class. */
  #pivot { position:absolute; top:100px; left:10px; z-index:21; background:#041b23; padding:14px 18px; border-radius:6px;
           max-width:min(80vw, 1100px); max-height:90vh; overflow:auto; display:none; box-shadow:0 4px 24px rgba(0,0,0,0.55); }
  #pivot h3 { margin:0 0 4px 0; font-size:16px; }
  #pivot .sub { opacity:0.72; font-size:14px; margin:0 0 12px 0; line-height:1.4; }
  #pivot a { color:#7fb6d6; text-decoration:none; }
  #pivot a:hover { text-decoration:underline; }
  #pivot table { border-collapse:collapse; width:100%; font-size:14px; }
  #pivot th { text-align:left; padding:5px 8px; border-bottom:1px solid #2a4a57; color:#cfe3ea; font-weight:600; white-space:nowrap; }
  #pivot td { padding:4px 8px; border-bottom:1px solid #16323c; vertical-align:top; }
  #pivot select { background:#0d2630; color:#e8f2f6; border:1px solid #2a4a57; border-radius:3px; padding:3px 5px; font-size:14px; font-family:inherit; }
  #pivot label { font-size:14px; display:flex; align-items:center; gap:4px; }
  #pivot .rowbtn { background:none; border:1px solid #2a4a57; color:#9fb4bc; border-radius:3px; cursor:pointer; padding:3px 8px; font-size:14px; margin:0 4px 4px 0; }
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
  /* Same AMIcons glyph the map itself draws for this device (ICON_GLYPHS),
     shown here too so the effective icon is visible at a glance instead of
     only as text inside the override dropdown. */
  .devIconGlyph { font-family:'AMIcons'; display:inline-block; width:16px; margin-right:6px; text-align:center; color:#7fb6d6; }
  #icons tr.overridden td { background:rgba(79,179,169,0.09); }
  #icons select { background:#0d2630; color:#e8f2f6; border:1px solid #2a4a57; border-radius:3px; padding:3px 5px; font-size:1em; font-family:inherit; }
  #icons .bar { margin-top:14px; padding-top:12px; border-top:1px solid #2a4a57; display:flex; gap:8px; flex-wrap:wrap; align-items:center; }
  #icons .msg { font-size:0.8em; margin-left:6px; }
  #iconsClose { position:absolute; top:8px; right:10px; cursor:pointer; background:none; border:none; color:#bbb; font-size:1.1em; }
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
  <div class="legend-row"><span class="line" style="border-color:#4fb3a9"></span>Write - rule sets a Hub or Local Variable's value</div>
  <div class="legend-row"><span class="line" style="border-color:#8fd6cc"></span>Read - rule uses a Hub or Local Variable in its decoded logic</div>
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
  <label>Focus app<span id="appComboMount"></span></label>
  <label>Focus device<span id="deviceComboMount"></span></label>
  <label>Focus hub variable<span id="hubVarComboMount"></span></label>
  <label>Focus local variable<span id="localVarComboMount"></span></label>
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
  </select></label>
  <button id="resetBtn" type="button" style="background:#d9822b; color:#121214; border-color:#a5701f;">Show all</button>
  <button id="insightsBtn" type="button">Insights</button>
  <button id="extBtn" type="button">External systems</button>
  <button id="pivotBtn" type="button">Pivot tables</button>
  <button id="iconsBtn" type="button">Device icons</button>
  <button id="exportBtn" type="button" title="Download the whole map as JSON, for an AI or other tool to read">AI friendly export</button>
  <button id="releaseActivityBtn" type="button" title="Preview Hubitat release activity from Community Utilities">Hubitat release activity</button>
  <button id="communityUtilitiesBtn" type="button" style="background:#81BC00; color:#121214; border-color:#5c8500;" title="Open the Hubitat Community Utilities site in a new tab">Community utilities</button>
  <button id="exitMapBtn" type="button" title="Return to this app's settings screen">Exit map</button>
</div>
<div id="flow"><button id="flowClose" type="button" title="Close">&times;</button><div id="flowBack" style="display:none"></div><h3 id="flowTitle"></h3><div class="sub" id="flowSub"></div><div id="flowChart"></div><div id="ruleVariablesCard"></div><div id="communityCard"></div></div>
<div id="ext"><button id="extClose" type="button" title="Close">&times;</button><div id="extBody"></div></div>
<div id="pivot"><button id="pivotClose" type="button" title="Close">&times;</button><div id="pivotBody"></div></div>
<div id="icons"><button id="iconsClose" type="button" title="Close">&times;</button><div id="iconsBody"></div></div>
<div id="releaseActivity"><button id="releaseActivityClose" type="button" title="Close">&times;</button><h3>Hubitat releases over time</h3><div class="sub">Community Utilities release history and documented changes.</div><div id="releaseActivityBody"></div></div>
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
                     depends: '#cfd8dc', write: '#4fb3a9', read: '#8fd6cc', hasComponent: '#5c6bc0' };
const groupColors = { app: '#e8a33d', device: '#5f7d8c', external: '#cfd8dc', hubVariable: '#4fb3a9', localVariable: '#7986cb' };

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
  exposed: 'Exposed', owns: 'Owns', hasComponent: 'Has component', runs: 'Runs', cancelTimedActions: 'Cancel timed actions',
  setspb: 'Private Boolean', pauseResume: 'Pause/resume', depends: 'Depends on', write: 'Write', read: 'Read'
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
  if (key === 'app|device') return ['trigger', 'constraint', 'monitor', 'action', 'exposed', 'owns'];
  if (key === 'app|external') return ['depends'];
  if (key === 'app|hubVariable') return ['write', 'read'];
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

// vis-network's fit() will not zoom in past 1.0 unless told it may, so a view
// holding a handful of nodes was left at 1:1 in a full-size canvas and read as
// tiny. Whole-hub views compute far below 1.0 anyway, so this cap only ever
// applies to a narrowed view. Held as state rather than passed at each call
// site so settle(), the resize refit and the focused-app path cannot disagree
// about the zoom of the same view.
const FOCUS_MAX_ZOOM = 2.0;
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
function settle(shelve) {
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
  const reveal = function () { if (canvasEl) canvasEl.style.opacity = ''; };
  if (!shelve && canvasEl) canvasEl.style.opacity = '0';
  let finished = false;
  const finish = function () {
    if (finished) return;
    finished = true;
    network.setOptions({ physics: { enabled: false } });
    if (shelve) shelveInertNodes();
    fitCurrentView();
    reveal();
  };
  network.once('stabilizationIterationsDone', finish);
  // vis does not always emit that event, and when it does not the fit never
  // runs and the view keeps the previous zoom. Measured live on a device
  // focus: six nodes left at whole-hub scale in a three-pixel blob. The
  // fallback is deliberately limited to narrowed views - shelve is true only
  // for the whole-hub map, whose startup path has been broken twice by changes
  // around this and is left exactly as it was. A handful of nodes settles well
  // inside this delay, so the timer only fires when the event genuinely did
  // not, and finish() is guarded so both routes cannot run it twice.
  if (!shelve) {
    setTimeout(finish, 1500);
    // Last resort. finish() already reveals and is guarded, but the canvas must
    // never be left invisible if anything above throws.
    setTimeout(reveal, 4000);
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

function applyFilters() {
  const appVal = appSelect.getValue();
  const devVal = deviceSelect.getValue();
  const hubVarVal = hubVarSelect.getValue();
  const localVarVal = localVarSelect.getValue();
  const kindVal = document.getElementById('kindFilter').value;

  let pool = ALL_EDGES;
  if (kindVal === 'rulelinks') {
    pool = ALL_EDGES.filter(function (e) { return RULE_LINK_KINDS.indexOf(e.kind) !== -1; });
  } else if (kindVal !== 'all') {
    pool = ALL_EDGES.filter(function (e) { return e.kind === kindVal; });
  }

  let ids = null;
  let shownEdges = pool;
  const focusId = appVal !== '__all__' ? appVal : (devVal !== '__all__' ? devVal : (hubVarVal !== '__all__' ? hubVarVal : (localVarVal !== '__all__' ? localVarVal : null)));
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

  // ids is null only when nothing is focused AND the relationship filter is
  // "all" - exactly the start-up / Show all view the shelf belongs to. Any
  // narrowed view skips the shelf and is allowed to magnify instead.
  const wholeMap = (ids === null);
  currentFitOptions = wholeMap ? { animation: false }
                               : { animation: false, maxZoomLevel: FOCUS_MAX_ZOOM };

  if (placed) {
    fitCurrentView();
  } else {
    network.setOptions({ physics: { enabled: true } });
    settle(wholeMap);
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
// Gate C (v2.1.4): owner-scoped Local Variable definitions and classified
// references, keyed the same way as FLOWS. Raw Groovy shape (canonicalName/
// scope/status/candidateScopes/evidence), not the export's renamed fields -
// the AI export builds its own shape from the same GRAPH.ruleVariables
// separately, see buildExportPayload().
const RULE_VARIABLES = GRAPH.ruleVariables || {};
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
// Single source of truth for panel coordination - bringToFront,
// syncLegendVisibility and closeSecondaryPanels all read it, so a new panel is
// coordinated everywhere at once. Functions rather than a const array so
// declaration order does not matter.
//
// flowPanel is deliberately outside secondaryPanels(): its callers hide it
// themselves, since several re-open it a moment later with new content.
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
  // Gate C (v2.1.4), review 296 correction: without this, selecting a rule
  // with variable evidence and then an inert app left the previous rule's
  // list stale underneath this panel's own content - this path never touched
  // ruleVariablesCard at all. Same centralized renderer as showFlow() uses,
  // so an inert/unreadable app (which has no relationships, variable
  // evidence included) correctly clears the container via that function's
  // own empty-state branch, not a separate ad hoc clear here.
  renderRuleVariablesCard(node.id);
  renderCommunityCard(node);
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
  document.getElementById('flowTitle').textContent = node.title + ' (Local Variable)';
  document.getElementById('flowSub').textContent = 'Declared in ' + (owner ? owner.title : 'a rule no longer on this map') + '.';
  flowChart.innerHTML = '<p class="sub">No proven decoded reference in this rule - not read in a trigger, condition or action, and not written.</p>';
  // Both correctly no-op on a non-rule/non-app node (their own group checks
  // already handle that) - called anyway so switching here from a rule with
  // stale content in either card actually clears it, the same discipline
  // the review 296 correction established for showInertPanel above.
  renderRuleVariablesCard(node.id);
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
    // Gate C (v2.1.4): a rule can have variable evidence even when its step
    // sequence itself could not be decoded (or genuinely has none) - shown
    // regardless of which branch of this function is taken.
    renderRuleVariablesCard(appId);
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
    renderRuleVariablesCard(appId);
    renderCommunityCard(node);
    bringToFront(flowPanel);
  }).catch(function (err) {
    if (mySelectionSeq !== focusGenerationSeq) return;
    flowChart.textContent = 'Could not render this rule: ' + err.message;
    renderRuleVariablesCard(appId);
    renderCommunityCard(node);
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
  if (!refs.length && !nonResolved.length) { box.innerHTML = ''; return; }

  // tag is the same [XXX] convention as the Focus dropdowns (queue 305/306) -
  // LOC/HVR reflect only the already-proven scope filter below, never guessed.
  function line(name, operation, usageRole, tag) {
    const op = operation === 'write' ? 'writes' : 'reads';
    const role = usageRole ? ' (' + extEsc(usageRole) + ')' : '';
    const prefix = tag ? '[' + tag + '] ' : '';
    return '<li>' + prefix + extEsc(name) + ' - ' + op + role + '</li>';
  }

  const localItems = refs.filter(function (r) { return r.scope === 'local'; })
    .map(function (r) { return line(r.canonicalName || r.name, r.operation, r.usageRole, 'LOC'); });
  const hubItems = refs.filter(function (r) { return r.scope === 'hub'; })
    .map(function (r) { return line(r.canonicalName || r.name, r.operation, r.usageRole, 'HVR'); });
  const reviewItems = nonResolved.map(function (r) {
    const reason = r.status === 'ambiguous' ? 'scope not distinguishable from configuration' : 'no matching definition found';
    return '<li>' + extEsc(r.name) + ' - ' + (r.operation === 'write' ? 'writes' : 'reads') + ', ' + reason + '</li>';
  });

  if (!localItems.length && !hubItems.length && !reviewItems.length) { box.innerHTML = ''; return; }

  let html = '<h4>Variables used by this rule</h4>';
  if (localItems.length) html += '<p class="sub">Local</p><ul>' + localItems.join('') + '</ul>';
  if (hubItems.length) html += '<p class="sub">Hub</p><ul>' + hubItems.join('') + '</ul>';
  if (reviewItems.length) html += '<p class="sub">Needs review</p><ul>' + reviewItems.join('') + '</ul>';
  box.innerHTML = html;
}

const flowCloseBtn = document.getElementById('flowClose');
if (flowCloseBtn) {
  flowCloseBtn.addEventListener('click', function () {
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
// Bumped once per focusNode() call, any selection type. Guards showFlow()'s
// async Mermaid render: that promise can still be pending when a later
// selection has changed the screen, and letting it write flowChart or reopen
// the panel would silently restore a stale selection.
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
function localVarOptionText(n) {
  const ownerTitle = APP_TITLE_BY_ID[n.ownerAppId] || 'an unknown rule';
  const unused = n.unreferencedLocal ? ', unused' : '';
  return '[LOC] ' + n.title + ' (in ' + ownerTitle + unused + ')';
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

    // Filter on title only, keep the current selection visible even when it
    // no longer matches - same contract fillSelect() used to guarantee.
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
        if (q && String(n.title).toLowerCase().indexOf(q) < 0) { continue; }
        out.push({
          value: n.id,
          label: n.optionText != null ? n.optionText : n.title,
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
  ALL_EDGES.forEach(function (e) {
    touched[e.to] = true;
    if (e.kind === 'read') {
      if (!hubVarReaders[e.to]) hubVarReaders[e.to] = [];
      if (hubVarReaders[e.to].indexOf(e.from) < 0) hubVarReaders[e.to].push(e.from);
    } else if (e.kind === 'write') {
      if (!hubVarWriters[e.to]) hubVarWriters[e.to] = [];
      if (hubVarWriters[e.to].indexOf(e.from) < 0) hubVarWriters[e.to].push(e.from);
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
      noDecodedUsage: hubVarIds.filter(function (id) { return !hubVarReaders[id] && !hubVarWriters[id]; }),
      readersWithoutDecodedWriter: hubVarIds.filter(function (id) { return hubVarReaders[id] && !hubVarWriters[id]; }),
      writersWithoutDecodedReader: hubVarIds.filter(function (id) { return hubVarWriters[id] && !hubVarReaders[id]; }),
      multipleWriters: hubVarIds.filter(function (id) { return hubVarWriters[id] && hubVarWriters[id].length > 1; }),
      unresolvedReferences: (GRAPH.hubVariableUnresolvedReferences || [])
    },
    scan: {
      status: SCAN_META.scanError ? 'failed'
        : ((SCAN_META.appsUnreadable > 0 || SCAN_META.devicesUnreadable > 0) ? 'complete-with-gaps' : 'complete'),
      appsUnreadable: SCAN_META.appsUnreadable || 0,
      devicesUnreadable: SCAN_META.devicesUnreadable || 0,
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
    D.brokenApps.length + D.inactiveInvoked.length + D.disabledDevicesInUse.length;
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
    hv.noDecodedUsage.length + inactiveQuiet.length + D.unreferencedLocals.length;
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
  document.getElementById('flowTitle').textContent = 'Automation health';
  document.getElementById('flowSub').textContent = '';
  flowChart.innerHTML = buildInsights();
  // Every other write to flowChart pairs it with this - Insights was the one
  // gap, leaving a previously-focused app's community card visible under it.
  renderCommunityCard(null);
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
      extBody.innerHTML = '<h3>External systems</h3><p class="sub">Could not load: ' + extEsc(e) + '</p>';
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

  let h = '<h3>External systems</h3>';
  h += '<p class="sub">What each app needs <b>outside</b> your hub. The hub cannot detect this, so it is declared here and drawn on the map as a diamond with a dashed line. ' +
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
  const noDecodedUsage = INS.hubVar.noDecodedUsage.map(function (id) { return ref(id, nameOf); });
  const readersWithoutDecodedWriter = INS.hubVar.readersWithoutDecodedWriter.map(function (id) { return ref(id, nameOf); });
  const writersWithoutDecodedReader = INS.hubVar.writersWithoutDecodedReader.map(function (id) { return ref(id, nameOf); });
  const multipleHubVarWriters = INS.hubVar.multipleWriters.map(function (id) {
    return { variable: ref(id, nameOf), writers: hubVarWriters[id].map(function (a) { return ref(a, nameOf); }) };
  });
  const unresolvedHubVarReferences = INS.hubVar.unresolvedReferences.map(function (r) {
    return { name: r.name, kind: r.kind, referencedBy: ref(r.appId, nameOf) };
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
      hasDecodedFlow: !!flowIds[n.id]
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
      // Only meaningful for 'action' edges (can this app leave the device in
      // a lasting state, versus a momentary command) - null rather than
      // false everywhere else, so it does not look like a real "no" for a
      // relationship kind the field was never about.
      stateful: e.kind === 'action' ? !!e.stateful : null,
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
    brokenRuleReferenceCount: brokenRuleReferences.length,
    // v2.1.4, schema 5 (Gate C): decoded evidence from the rules this export
    // could read, NOT a hub-wide inventory the way hubVariableCount above is
    // - see the limitations entry on this distinction.
    localVariableCount: ruleFlows.reduce(function (sum, f) { return sum + (f.localVariables ? f.localVariables.length : 0); }, 0),
    nonResolvedVariableReferenceCount: ruleFlows.reduce(function (sum, f) { return sum + (f.nonResolvedVariableReferences ? f.nonResolvedVariableReferences.length : 0); }, 0)
  };
  // What "apps[].hasDecodedFlow: false" can mean beyond "not a rule at
  // all" - named once here rather than only in the schema prose, so a
  // consumer can check membership programmatically instead of parsing
  // English out of the schema block.
  const limitations = [
    'Rules on these engines are never decoded, regardless of hasDecodedFlow: Room Lighting, Basic Rules, Simple Automation, webCoRE. They still appear in devices/apps/edges with their device relationships - only the step-by-step logic in ruleFlows is unavailable for them.',
    'Rule-to-rule edges (relationship: runs/cancelTimedActions/setspb/pauseResume) and Hub and Local Variable read/write edges are read from Rule Machine 5.1 only - a rule on another engine will not produce these even if it does the equivalent thing.',
    'Roles/edges reflect how a device is configured into an app, not what happened at runtime - this is a static configuration snapshot from the last scan (see scan.lastScanCompletedAt), not live state.',
    // v2.0.14, schema 4 (parent spec 11.6) - Hub Variable specific notes.
    'Hub Variable names are household data. Values are absent from this export entirely unless a future explicit opt-in adds them - currentValue is always null here.',
    'A Hub Variable with no decoded reader or writer (insights.hubVariables.noDecodedUsage) may still be used by an app or integration this export cannot decode - absence of a decoded edge is not proof the variable is unused.',
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
    'Static configuration is not proof of runtime behaviour - do not claim it is.',
    'Do not frame contested devices, inert apps, or any other count as evidence the hub is in a bad state. A hub with dozens of rules and hundreds of devices will always show some of these as a normal by-product of scale - contested devices in particular are usually several ordinary rules sharing one light or switch (motion, time-of-day, manual override), not automations fighting. Avoid adversarial words - fighting, broken as an unqualified judgment, conflict - for anything the export itself does not use that word for; state the plain mechanism instead (the last app to run decides the outcome) and let the user judge whether it is intentional.',
    'State a count in proportion to the whole (e.g. "30 of 194 devices" rather than a bare "30 devices") so the user can judge scale themselves rather than be primed by an isolated number.',
    'Never infer a missing relationship solely because two names look similar.',
    'Resolve a write/read edge target by its id against hubVariables[] first, then ruleFlows[].localVariables[] (matched by identity) - never by assuming every such edge targets a Hub Variable, and never by joining on the toName field alone.',
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
    insightGuidance: GUIDE,
    privacyNote: 'Device, room and app names below reflect a real home. Treat this file with the same care as the underlying device list - review before sharing it outside a trusted context.',
    schema: {
      devices: 'Every device on the hub. iconCategory is a best-guess classification (lighting, doors, water, motion...), "unknown" if nothing matched. capabilities is the raw Hubitat capability list this device reports (what iconCategory was derived from); null if this device was not present in the same fetch that supplied room/capabilities (a scan run since the page loaded, in the rare case one raced this export). iconCategory "connector" (schema 4, v2.0.14) marks a Hub Variable Connector device - a virtual device Hubitat keeps synchronized with the value of a hubVariables[] entry, not an independent physical device; find the variable it belongs to via that variable connector.deviceId field (hubVariables[]) or the synchronizedWith edge naming this device as its target (edges[]). A Connector device is represented in the same bulk device-enumeration endpoint every other device on this hub is discovered through, but nested inside its "Variable Connectors" parent entry rather than as a top-level device (a live platform finding, corrected v2.1.7) - so on a build before that fix its capabilities/room could read null even though the hub reported them, and on this build they resolve the same as any other device once the whole endpoint tree, not just its top level, is walked. Confirmed live: Hubitat also creates its own single parent device named "Variable Connectors" that lists every per-variable Connector in one place. That parent device is classified iconCategory "connector" too (the same detection rule catches it), but no hubVariables[] entry links to it and no synchronizedWith edge names it as a target - it manages the feature, it is not synchronized with one specific variable. Do not assume every "connector" device resolves to exactly one hubVariables[] entry. disabled (schema 8) reflects the per-device Disabled toggle Hubitat itself reports - true if the device is turned off entirely, independent of any app or rule state; never inferred from missing subscriptions, inactivity, orphan status, driver type or parent-child position (item 18).',
      apps: 'Every installed app, including every automation rule. status: active | disabled | paused | inert (installed but touches nothing) | unscanned (never reached during the scan) | unreadable (hub would not answer for it) | deleted-but-referenced (no longer exists as an app, but another rule still names it - appType is null in this one case, expected, not a decoding gap). disabled and paused (schema 8) are reported separately, not merged into one collapsed value as in schema 7 and earlier - disabled is a hub-level toggle reported for any app type, paused is Rule Machine-specific execution-paused state reported only for a rule that has that concept; disabled wins when both happen to be true. parentId/childIds describe container apps (e.g. Button Controllers holding several Button Rules). hasDecodedFlow: true if this app has a matching entry in ruleFlows - false does not mean broken, it usually means the app is not a rule at all (an integration, a service) or is a rule on an engine this app cannot decode (Room Lighting, Basic Rules, Simple Automation, webCoRE).',
      externalSystems: 'Systems outside the hub an app depends on, drawn as nodes on the map - a mix of auto-matched community registry entries and declarations entered by the hub owner (see externalSystemDeclarations below for the raw declarations themselves, which is a different, smaller list - not every declared type becomes a node here, and not every node here came from a declaration).',
      hubVariables: 'Hub-wide shared state - every variable the hub itself reports (identitySource "hub-inventory") when authoritative inventory was available for this scan (see scan.hubVariableInventory.status), reconciled with variables one or more rules confirmed to read or write. v2.1.4 (schema 5, Gate C): the previous "reference-derived" identitySource - a decoded rule configuration reference not confirmed against authoritative inventory - is retired. Gate A found that a bare structured reference (an xVarV/xVar_/xVar picker value) alone does not prove Hub scope at all, since the same storage shape is used for a rule-local Local Variable, so this export no longer manufactures a Hub Variable node from an unconfirmed name; identitySource is expected to always be "hub-inventory" for every entry here - a null value would mean that expectation was violated, and should be treated as a defect report rather than a third valid category. A reference this app cannot confirm against authoritative inventory appears instead in ruleFlows[].nonResolvedVariableReferences with status "unresolved", never as a hubVariables[] entry - see the ruleFlows schema entry and the limitations on Local Variable identity below. variableType is Number/Decimal/String/Boolean/DateTime, or null if not yet resolved. connector is the linked Connector device ({deviceId, connectorType}) when Hubitat reports one, else null - see the synchronizedWith edge for the same relationship in the edges array. connectorType is the type the device itself reports when the regular device inventory for this hub independently lists it, otherwise the projected Connector attribute label Hubitat reports (observed live: "Variable", "Humidity") - not necessarily the underlying driver name. currentValue is always null in this export (see limitations). v2.1.6 (schema 6): this array is no longer the only possible target of a write/read edge in edges[] - a Local Variable can be one too; see the edges schema entry for how to tell them apart.',
      edges: 'Every relationship between two of the above, referenced by id (fromId/toId) - names are included for readability only and are not guaranteed unique, do not use them to join. relationship meanings - trigger: app listens to this device. constraint: a condition/required expression gates the app on this device. monitor: app reads this device state only, cannot command it. action: app can command this device (see stateful). exposed: published to an external system. owns: app created this device. hasComponent (graph schema 9, export schema 7): fromId is the parent device, toId is a device-owned component of it (e.g. a Shelly/Bond/Matter-bridge child, or a Hub Variable Connector nested under its "Variable Connectors" parent) - device-to-device, no app involved, and independent of whether any app or rule references either device. write/read: a rule sets or reads a variable - the target is a Hub Variable (present in top-level hubVariables[]) if toId matches a hubVariables[] id, otherwise a Local Variable (present only nested, in ruleFlows[].localVariables[], keyed by identity - flatten that collection once rather than assuming hubVariables[] alone is complete). A Local Variable target only ever has exactly one write/read edge source, its own owning rule - see usageRole/writeSource below. synchronizedWith: a Hub Variable and its Connector device expose the same synchronized state - structural, not a read/write/trigger/action, and not evidence of device control. runs/cancelTimedActions/setspb/pauseResume: one rule acting on another rule. depends: an app needs an external system. stateful is only meaningful on action edges - true means the app can leave the device in a lasting on/off/level state, not just a momentary command, and more than one app doing this to the same device means the last one to run decides the outcome (see insights.contested) - common by design on a hub with many rules, not inherently a problem; null on every other relationship kind, where the concept does not apply. usageRole (schema 4, extended to Local Variable reads in schema 6) is populated on proven Hub or Local Variable read edges: a single trusted role (e.g. "condition", "trigger") when every decoded occurrence behind that edge agrees, otherwise "unknown-read" rather than an invented one; null on every other edge, including writes. writeSource (schema 4) is Hub-write specific - populated only on a Hub Variable write edge whose source device attribute resolved to a real device ID ({kind: "deviceAttribute", deviceId, attribute}); null otherwise, including on every Local Variable edge and when a source detail exists but could not be resolved to an ID.',
      ruleFlows: 'One entry per app whose logic could be decoded, an array rather than an object keyed by name because app names on this hub are not guaranteed unique - join on appId. steps is the decoded trigger/condition/action sequence for that rule. cond/label on a step can legitimately be empty - "endif"/"else" control-flow steps exist only to close or branch a block and carry no condition of their own. references replaces what would otherwise be a bare device-name list: each entry is {type, id, name} (plus candidateIds when type is "ambiguous"). type is "device" or "app" (a Cancel Timed Actions/Run Rule Actions-style step names another RULE here, not a device - check type, do not assume), "self" for VRB’s "This Rule" (id is this same step’s own appId), "ambiguous" if the name matches more than one device or app on this hub (id is null, candidateIds lists every match - do not guess which one), or "unresolved" if the name matched nothing at all (id null - typically a stale/renamed reference). ruleTargets (cross-rule action steps only) is {id, name} the same way - always resolvable, an "a"-prefixed app id, never ambiguous. localVariables (schema 5, v2.1.4, Gate C) is this rule’s own Local Variable definitions, owner-scoped by this entry’s own appId - identity is "appId:name", never global; no value is ever included. As of schema 6 (v2.1.6), every entry here is also a first-class node on the graph and can appear as a write/read edge target in edges[] - see that schema entry. A definition with no matching edges[] entry has no proven decoded reference in this rule - not read in a trigger, condition or action, and not written. variableReferences (schema 5) is every read/write reference this app confirmed a scope for, "local" or "hub" only, joined to a localIdentity when local; a same-named Local and Hub Variable in the SAME rule cannot be told apart from stored configuration alone (a genuine platform ambiguity, not a decoding gap), so it never appears here - see nonResolvedVariableReferences. nonResolvedVariableReferences (schema 5) covers everything variableReferences excludes: status "ambiguous" (candidateScopes lists every scope that matched, most often ["local","hub"] for the same-name case above) or status "unresolved" (candidateScopes empty - no matching definition in either scope, most often a renamed or deleted variable). Neither array ever creates or implies a hubVariables[] entry on its own - see that schema entry.',
      insights: 'Pre-computed findings, every device/app/rule reference given as {id,name} rather than a bare name. contested: devices more than one app can leave in a lasting state, so the last app to run decides the outcome - common and often intentional on a hub with many rules (a motion-triggered rule and a manual-override rule both targeting one light, for example), worth confirming is not accidental, not evidence anything is wrong. unreferencedDevices: nothing on the hub owns, watches or drives them. inertApps: installed but touch no device and link to no rule, with why - very often a container holding other apps, or a schedule-only app, both entirely normal. brokenRuleReferences: a rule still names another rule/action/pause target that no longer exists - the action silently does nothing. inactiveRulesStillCalled (v2.2.1) - {rule, state: "paused"|"disabled", calledBy[]} - the rule will not run, yet another rule still invokes it, so that step in the caller silently does nothing; pause/resume links are deliberately excluded from calledBy, since a rule whose job is to resume this one is the mechanism working rather than a failure. rulesFlaggedBroken (v2.2.1) - Hubitat itself marks the rule broken via its own label, not a judgement this scan makes. disabledDevicesStillUsed (v2.2.1) - {device, usedBy[]} - the device is disabled while automations still command it or wait on it as a trigger, so those commands cannot land and those triggers cannot fire; constraint and monitor reads are excluded as a weaker, noisier claim. inactiveRules (v2.2.1) - every paused/disabled rule as plain context, almost always deliberate, and NOT a fault list; the actionable subset is inactiveRulesStillCalled. unreferencedLocalVariables (v2.2.1) - declared in a rule with no decoded read or write anywhere, carrying the same "may simply be unused, or used in a part this scan cannot decode" caveat as hubVariables.noDecodedUsage. hubVariables (schema 4) - neutral Hub Variable findings, never automatic fault claims (see limitations): noDecodedUsage (no decoded reader or writer at all - may simply be unused, or used by an app this scan cannot decode), readersWithoutDecodedWriter (may be set manually, externally, or by an undecoded app), writersWithoutDecodedReader (may be consumed externally, or no longer needed), multipleWriters ({variable, writers} - shared state with more than one writer, not automatically a race), unresolvedReferences ({name, kind, referencedBy} - a proven structured reference to a name absent from a complete authoritative inventory; the rule may reference a renamed/deleted variable, or inventory may have been incomplete for this scan). There is no unresolvedConnectors field - a reported Connector deviceId is always trusted and resolved into hubVariables[].connector; see the limitations entry on orphaned/stale Connector IDs for what this trade-off cannot detect.',
      scan: 'lastScanCompletedAt is when the data behind this whole export was last refreshed from the hub (not when this file was generated - generatedAt above is that). lastScanError is whatever the app itself reported wrong with that scan, if anything. status is "complete" (nothing failed), "complete-with-gaps" (the scan finished but appsUnreadable and/or devicesUnreadable is above zero - some apps or devices could not be read and are simply missing from this export, not just from ruleFlows), or "failed" (lastScanError is set, the whole scan aborted). appsUnreadable/devicesUnreadable are the counts behind that status - also see apps[].status for which specific apps were affected. hubVariableInventory (schema 4) is kept deliberately separate from the status above - it describes whether the authoritative Hub Variable list the hub itself reports (not app/device scanning) succeeded this scan: status is "complete", "complete-with-gaps", "failed" or "not-supported"; count is how many variables the hub reported. When this status is not "complete" (v2.1.4, schema 5), a structured reference this scan cannot confirm against the incomplete inventory appears in ruleFlows[].nonResolvedVariableReferences with status "unresolved" rather than as a hubVariables[] entry - see that schema entry for why a weaker-guarantee node is no longer manufactured here. hubVariableRelationships describes which app engines Hub Variable read/write edges can be decoded from (currently Rule Machine 5.1 only) - independent of inventory status.',
      summary: 'Plain counts of every array below, for a quick sanity check or a one-line status line - not authoritative over the arrays themselves. hubVariablesWithConnectorCount and unresolvedHubVariableReferenceCount (schema 4) are the same kind of derived count as the others - see hubVariables[].connector and insights.hubVariables.unresolvedReferences for the underlying data. localVariableCount and nonResolvedVariableReferenceCount (schema 5, v2.1.4) total ruleFlows[].localVariables and ruleFlows[].nonResolvedVariableReferences across every decoded rule - decoded evidence from the rules this export could read, not a hub-wide inventory the way hubVariableCount is.',
      limitations: 'Known, structural gaps in what this export can ever contain, independent of any particular hub - read this before concluding a rule is "missing" logic rather than on an engine this app cannot decode.',
      recommendedAiBehaviour: 'How an AI reading this file should behave, in three parts. Epistemic: identify versions, distinguish fact from inference, cite IDs over names, qualify conclusions built on a scan gap or an unresolved/ambiguous reference, never guess a relationship from name similarity alone. Tone: counts like contested devices or inert apps are normal at scale, not evidence of a bad state - avoid adversarial words (fighting, conflict, broken as an unqualified judgment) for anything the export itself does not use that word for, and state a count in proportion to the whole rather than in isolation. Response shape: open with a short plain-language summary naming a few specific apps or devices as evidence the file was actually read, state findings before recommendations, surface scan-quality caveats up front, and when more than one thing is worth pursuing offer it as a short menu and ask which to explore unless the request or the evidence makes the next investigation unambiguous, in which case proceed with it directly - every option offered must read as investigate or explain, never as an action taken or promised, since nothing here authorises any change to the hub.',
      insightGuidance: 'The same deterministic interpretation catalogue shown in the on-hub Insights panel. categories explains each group and its recommended priority; findings gives what the observation means, why it may be normal when applicable, and what to check next. It is guidance for investigation, never authority to change the hub.'
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

// Each combobox's onChange enforces the four-way focus exclusivity (picking
// one clears the other three) the same way the old <select> 'change'
// listeners did - setValue() never fires onChange itself, so these resets
// cannot recurse into each other.
const appSelect = initCombo('appComboMount', 'app', 'All apps', 'search apps...', function (value) {
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
});
const deviceSelect = initCombo('deviceComboMount', 'device', 'All devices', 'search devices...', function (value) {
  if (value !== '__all__') {
    appSelect.setValue('__all__');
    hubVarSelect.setValue('__all__');
    localVarSelect.setValue('__all__');
    closeSecondaryPanels();
  }
  flowPanel.style.display = 'none';
  syncLegendVisibility();
  applyFilters();
});
const hubVarSelect = initCombo('hubVarComboMount', 'hubVariable', 'All hub variables', 'search hub variables...', function (value) {
  if (value !== '__all__') {
    appSelect.setValue('__all__');
    deviceSelect.setValue('__all__');
    localVarSelect.setValue('__all__');
    closeSecondaryPanels();
  }
  flowPanel.style.display = 'none';
  syncLegendVisibility();
  applyFilters();
});
const localVarSelect = initCombo('localVarComboMount', 'localVariable', 'All local variables', 'search local variables...', function (value, item) {
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

function currentFocus() {
  if (appSelect.getValue() !== '__all__') return appSelect.getValue();
  if (deviceSelect.getValue() !== '__all__') return deviceSelect.getValue();
  if (hubVarSelect.getValue() !== '__all__') return hubVarSelect.getValue();
  if (localVarSelect.getValue() !== '__all__') return localVarSelect.getValue();
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
  fitCurrentView();
}

function exitToWholeMap() {
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
