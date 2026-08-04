/*
 * Automation Map
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
 * Role assignment was derived by probing two apps whose source is known
 * (Presence Manager, LIFX Light Manager) plus one Rule Machine rule, and
 * cross-checking against both the source and the rule's own UI:
 *
 *   childDevices            -> owns       (LIFX: 12 child lights, no subs)
 *   setting named tDev*     -> trigger    (RM trigger devices)
 *   setting named rDev*     -> constraint (RM conditions + required expression)
 *   device is subscribed    -> trigger    (general case: an app subscribes to
 *                                          what it listens to. Presence
 *                                          Manager's 5 subs matched its
 *                                          subscribeEvidenceDevices() exactly)
 *   any other device setting-> action     (onOffSwitch.*, volume.*, note.*,
 *                                          siren.*, chime.*, speakDevice.*)
 *
 * tDev/rDev are Rule Machine's private naming and could change if Rule Machine
 * changes; the subscription and childDevices signals are structural and apply
 * to every app.
 *
 * Known limitation: apps are discovered from the devices you select, via each
 * device's appsUsingForDialog list. That list is truncated by the hub when a
 * device is used by many apps (it carries an "and N more" count), so an app
 * that only ever appears in a truncated list may be missed. Selecting all
 * devices makes this unlikely but not impossible.
 */
import groovy.transform.Field
import groovy.json.JsonOutput
import java.util.regex.Pattern

@Field static final String APP_NAME = 'Automation Map'
@Field static final String APP_VERSION = '1.3.0'
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
}

void updated() {
    log.info "${app.label} updated"
}

Map main() {
    if (!state.accessToken) createAccessToken()

    return dynamicPage(name: 'main', title: "<b>${APP_NAME} v${APP_VERSION}</b>", install: true, uninstall: true) {
        section {
            paragraph 'Select the devices to scan (use "Select All"). Devices referenced by an app are added to the map automatically, even if not selected here - the selection only decides which devices are used to discover apps.'
            input name: 'devices', type: 'capability.*', title: 'Devices to scan', multiple: true, required: true, submitOnChange: true
        }
        if (devices) {
            section {
                paragraph "${devices.size()} device(s) selected."
                input name: 'runScan', type: 'button', title: state.scanRunning ? 'Scanning...' : 'Scan relationships now'
                if (state.scanTotal) {
                    String phase = state.scanPhase == 'apps' ? 'apps' : 'devices'
                    String progress = "Scanning ${phase}: ${state.scanDone ?: 0} / ${state.scanTotal}"
                    if (state.scanRunning) progress += ' (close and reopen this page to refresh)'
                    paragraph progress
                }
                if (state.scanError) {
                    paragraph "<b style='color:#c0392b'>Scan error: ${state.scanError}</b>"
                }
                if (state.graph) {
                    Map g = state.graph as Map
                    if (graphIsStale()) {
                        // A graph built by an older version can carry relationship
                        // kinds this version no longer renders, which silently draws
                        // as uncoloured edges rather than failing visibly.
                        paragraph "<b style='color:#c0392b'>This map was built by version ${state.graphVersion ?: 'an earlier release'} and will not display correctly. Run the scan again.</b>"
                    } else {
                        paragraph "Map ready: ${(g.nodes ?: []).size()} nodes, ${(g.edges ?: []).size()} relationships."
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
    }
}

void appButtonHandler(String btn) {
    if (btn == 'runScan') startScan()
}

boolean graphIsStale() {
    return state.graph && state.graphVersion != APP_VERSION
}

// ===================================================================================================================
// Scanning - phase 1 discovers app ids from devices, phase 2 pulls each app's real relationships
// ===================================================================================================================

void startScan() {
    state.scanQueue = devices.collect { "${it.id}" }.unique()
    state.scanTotal = (state.scanQueue as List).size()
    state.scanDone = 0
    state.scanPhase = 'devices'
    state.scanRunning = true
    state.scanError = null
    state.deviceLabels = [:]
    state.appIds = []
    state.appInfo = [:]
    state.graphVersion = null
    unschedule('scanBatch')
    runIn(1, 'scanBatch')
}

void scanBatch() {
    try {
        if (state.scanPhase == 'devices') {
            scanDeviceBatch()
        } else {
            scanAppBatch()
        }
    } catch (Exception ex) {
        log.warn "${app.label}: scanBatch failed: ${ex.message}"
        state.scanError = ex.message
        state.scanQueue = []
        finishScan()
        return
    }

    if (state.scanQueue) {
        runIn(1, 'scanBatch')
    } else if (state.scanPhase == 'devices') {
        startAppPhase()
    } else {
        finishScan()
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
    }

    state.appInfo = appInfo
    state.deviceLabels = labels
    state.scanQueue = queue.drop(size)
    state.scanDone = (state.scanDone ?: 0) + size
}

void finishScan() {
    state.scanRunning = false
    state.graph = buildGraph()
    state.graphVersion = APP_VERSION
    log.info "${app.label}: scan complete - ${(state.appInfo as Map).size()} app(s), ${(state.deviceLabels as Map).size()} device(s)"
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
            (data.appsUsingForDialog ?: []).each { u ->
                if (u?.id != null) ids << "${u.id}"
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
    Map out = [id: appId, label: "App ${appId}", type: null, roles: [:], flow: [], error: null]
    try {
        httpGet([uri: "http://127.0.0.1:8080/installedapp/statusJson/${appId}", timeout: 20]) { resp ->
            Map data = (resp.data instanceof Map) ? (resp.data as Map) : [:]

            Map installedApp = data.installedApp as Map
            out.label = stripTags((installedApp?.label ?: installedApp?.trueLabel ?: installedApp?.name ?: "App ${appId}") as String)
            out.type = installedApp?.name

            Map roles = [:]

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
                deviceList.each { devIdKey, devName ->
                    String devId = "${devIdKey}"
                    if (devName && !labels[devId]) labels[devId] = stripTags(devName as String)
                    addRole(roles, devId, roleForSetting(settingName, devId, subscribed))
                }
            }

            // A subscribed device with no setting of its own is still a trigger,
            // unless this app owns it (a child device it also listens to).
            subscribed.each { String devId ->
                List existing = (roles[devId] ?: []) as List
                if (!existing) addRole(roles, devId, 'trigger')
            }

            out.roles = roles
            out.flow = buildRuleFlow(data)
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

List buildRuleFlow(Map data) {
    Map st = [:]
    (data.appState ?: []).each { e ->
        if (e instanceof Map && e.name != null) st["${e.name}"] = e.value
    }

    List actionList = (st.actionList ?: []) as List
    if (!actionList) return []

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

    String label = prettyMethod(method)
    if (method == 'getOnOffSwitch') {
        label = settingValues["onOff.${num}"] == 'true' ? 'On' : 'Off'
    } else if (method == 'getSetColorTemp') {
        label = "Colour temperature ${settingValues["ctL.${num}"] ?: ''}K".trim()
        String level = settingValues["ctLevel.${num}"]
        if (level) label += ", level ${level}"
    } else if (method == 'getWaitRule') {
        String cond = expressionText((evalMap["${act.rule}"] ?: []) as List, capabs)
        label = cond ? "Wait for: ${cond}" : 'Wait'
        if (act.delay) label += " (timeout ${act.delay})"
        // A wait's devices come from the condition it waits on, not from an
        // action setting numbered after it.
        (requiredDevices((evalMap["${act.rule}"] ?: []) as List, settingDevices)).each {
            if (!devices.contains(it)) devices << it
        }
    } else if (method == 'getDelay' && act.delay) {
        label = "Delay ${act.delay}"
    }

    return [kind: 'action', label: label, devices: devices, indent: "${act.indent ?: ''}".length()]
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

String roleForSetting(String settingName, String devId, List subscribed) {
    // Rule Machine's private naming: tDev<n> = trigger device, rDev_<n> =
    // condition device (both plain IF conditions and the required expression).
    if (settingName.startsWith('tDev')) return 'trigger'
    if (settingName.startsWith('rDev')) return 'constraint'
    // General signal: an app subscribes to what it listens to.
    if (subscribed.contains(devId)) return 'trigger'
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

// ===================================================================================================================
// Graph building
// ===================================================================================================================

Map nodeEntry(String id, String fullLabel, String group, String subtitle = null) {
    String label = fullLabel ?: id
    String shortLabel = label
    if (shortLabel.length() > 24) shortLabel = "${shortLabel.substring(0, 22)}…"
    return [id: id, label: shortLabel, title: subtitle ? "${label} (${subtitle})" : label, group: group]
}

Map buildGraph() {
    Map labels = (state.deviceLabels ?: [:]) as Map
    Map appInfo = (state.appInfo ?: [:]) as Map

    Map<String, Map> nodes = [:]
    List<Map> edges = []
    List<String> seen = []
    Map flows = [:]

    appInfo.each { String appId, info ->
        if (!(info instanceof Map)) return
        Map appMap = info as Map
        Map roles = (appMap.roles ?: [:]) as Map
        if (!roles) return

        String appNodeId = "a${appId}"
        nodes[appNodeId] = nodeEntry(appNodeId, appMap.label as String, 'app', appMap.type as String)
        if (appMap.flow) flows[appNodeId] = appMap.flow

        roles.each { String devId, devRoles ->
            String devNodeId = "d${devId}"
            if (!nodes[devNodeId]) {
                nodes[devNodeId] = nodeEntry(devNodeId, (labels[devId] ?: "Device ${devId}") as String, 'device')
            }
            (devRoles as List).each { String role ->
                String key = "${appNodeId}|${devNodeId}|${role}"
                if (seen.contains(key)) return
                seen << key
                edges << [from: appNodeId, to: devNodeId, kind: role]
            }
        }
    }

    return [nodes: nodes.values().toList(), edges: edges, flows: flows]
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
}

Map renderMapMapping() {
    if (graphIsStale()) {
        return render(
            status: 200,
            contentType: 'text/html',
            data: """<!doctype html><html><head><meta charset="utf-8"><title>Automation Map</title></head>
<body style="background:#062733; color:#eee; font-family:sans-serif; padding:2em; line-height:1.5">
<h2>This map is out of date</h2>
<p>It was built by version ${state.graphVersion ?: 'an earlier release'}, but Automation Map is now version ${APP_VERSION}.
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
  #controls button { margin-top:2px; cursor:pointer; }
  #network { width:100%; height:100vh; }
  .legend-row { display:flex; align-items:center; margin:4px 0; }
  .swatch { width:12px; height:12px; border-radius:50%; margin-right:8px; display:inline-block; flex:none; }
  .line { width:22px; height:0; border-top:2px solid #fff; margin-right:8px; display:inline-block; flex:none; }
  .note { opacity:0.75; font-size:0.9em; margin-top:6px; line-height:1.35; }
  #flow { position:absolute; top:10px; left:10px; z-index:20; background:rgba(4,20,27,0.96); padding:12px 16px; border-radius:6px;
          max-width:min(46vw, 620px); max-height:88vh; overflow:auto; display:none; box-shadow:0 4px 24px rgba(0,0,0,0.5); }
  #flow h3 { margin:0 0 4px 0; font-size:0.95em; }
  #flow .sub { opacity:0.7; font-size:0.78em; margin-bottom:10px; }
  #flowClose { position:absolute; top:8px; right:10px; cursor:pointer; background:none; border:none; color:#bbb; font-size:1.1em; }
</style>
</head>
<body>
<div id="status">Devices: ${deviceCount} &nbsp; Apps: ${appCount}</div>
<div id="legend">
  <div class="legend-row"><span class="swatch" style="background:#e8a33d"></span>App</div>
  <div class="legend-row"><span class="line" style="border-color:#9b59b6"></span>Trigger - app listens to this device</div>
  <div class="legend-row"><span class="line" style="border-color:#16a085"></span>Constraint - condition / required expression</div>
  <div class="legend-row"><span class="line" style="border-color:#7fae42"></span>Action - app commands this device</div>
  <div class="legend-row"><span class="line" style="border-color:#8090a0; border-top-style:dashed"></span>Owns - app created this device</div>
  <div class="note">Arrows follow the flow: triggers and constraints point into the app, actions and owned devices point out of it.</div>
  <div class="note">Focus one app to colour its devices by role. A device holding two roles in one app gets two edges, and is coloured by the more significant one.</div>
</div>
<div id="controls">
  <label>Focus app<select id="appFilter"><option value="__all__">All apps</option></select></label>
  <label>Focus device<select id="deviceFilter"><option value="__all__">All devices</option></select></label>
  <label>Show<select id="kindFilter">
    <option value="all">All relationships</option>
    <option value="trigger">Triggers only</option>
    <option value="constraint">Constraints only</option>
    <option value="action">Actions only</option>
    <option value="owns">Ownership only</option>
  </select></label>
  <button id="resetBtn" type="button">Show all</button>
</div>
<div id="flow"><button id="flowClose" type="button" title="Close">&times;</button><h3 id="flowTitle"></h3><div class="sub" id="flowSub"></div><div id="flowChart"></div></div>
<div id="network"></div>
<script>
const GRAPH = ${jsonStr};
const roleColors = { trigger: '#9b59b6', constraint: '#16a085', action: '#7fae42', owns: '#8090a0' };
const groupColors = { app: '#e8a33d', device: '#5f7d8c' };

// Most-significant role first. Used to colour a device that holds more than one
// role in the same app - e.g. a motion sensor that is both a rule's trigger and
// part of that rule's Wait-for-Expression condition.
const ROLE_ORDER = ['trigger', 'constraint', 'action', 'owns'];

const ALL_NODES = GRAPH.nodes;

// Parallel edges between the same pair would otherwise be drawn exactly on top
// of each other, hiding the fact that a device holds two roles in one app.
const pairSeen = {};
const ALL_EDGES = GRAPH.edges.map(function (e, i) {
  const pairKey = e.from + '|' + e.to;
  const dupIndex = pairSeen[pairKey] === undefined ? 0 : pairSeen[pairKey] + 1;
  pairSeen[pairKey] = dupIndex;
  // Arrows follow the flow: a trigger or constraint feeds INTO the app, an
  // action or an owned device is driven BY it.
  const inbound = (e.kind === 'trigger' || e.kind === 'constraint');
  return {
    id: i, from: e.from, to: e.to, kind: e.kind,
    arrows: inbound ? 'from' : 'to',
    dashes: e.kind === 'owns',
    color: roleColors[e.kind] || '#999',
    width: e.kind === 'owns' ? 1 : 1.6,
    smooth: { type: 'curvedCW', roundness: 0.12 + (dupIndex * 0.22) }
  };
});

// When one app is focused, its devices are coloured by the role they play in
// THAT app - a device can legitimately be a trigger for one app and a target
// for another, so this colouring only makes sense scoped to a single app.
function styledNode(n, useFullLabel, roleByDevice) {
  const role = roleByDevice ? roleByDevice[n.id] : null;
  const color = n.group === 'device'
    ? (role && roleColors[role] ? roleColors[role] : groupColors.device)
    : groupColors[n.group];
  return {
    id: n.id, label: useFullLabel ? n.title : n.label, title: n.title, color: color,
    shape: n.group === 'app' ? 'square' : 'dot',
    size: n.group === 'app' ? 17 : 13,
    font: { color: '#fff', size: 13, strokeWidth: 5, strokeColor: '#062733', vadjust: -4 }
  };
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

  let pool = kindVal === 'all' ? ALL_EDGES : ALL_EDGES.filter(function (e) { return e.kind === kindVal; });

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
      const prev = roleByDevice[e.to];
      if (!prev || ROLE_ORDER.indexOf(e.kind) < ROLE_ORDER.indexOf(prev)) roleByDevice[e.to] = e.kind;
    });
  }

  const shownNodes = ids ? ALL_NODES.filter(function (n) { return ids[n.id]; }) : ALL_NODES;
  nodes.clear(); nodes.add(shownNodes.map(function (n) { return styledNode(n, !!focusId, roleByDevice); }));
  edges.clear(); edges.add(shownEdges);
  network.setOptions({ physics: { enabled: true } });
  settle();
}

// ---------------------------------------------------------------------------
// Rule flow panel. A force-directed graph cannot express order, so when the
// focused app is a rule its decoded steps are drawn as a real flowchart.
// ---------------------------------------------------------------------------
const FLOWS = GRAPH.flows || {};
if (window.mermaid) {
  mermaid.initialize({ startOnLoad: false, theme: 'dark', flowchart: { useMaxWidth: true } });
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

function mermaidFor(steps) {
  const lines = ['flowchart TD'];
  const ids = [];
  steps.forEach(function (s, i) {
    const id = 'S' + i;
    ids.push(id);
    let text = mermaidEscape(s.label);
    if (s.devices && s.devices.length) text += '<br/><i>' + mermaidEscape(s.devices.join(', ')) + '</i>';
    // Stadium for triggers, hexagon for the gating expression, box for actions.
    if (s.kind === 'trigger') lines.push('  ' + id + '(["' + text + '"])');
    else if (s.kind === 'required') lines.push('  ' + id + '{{"' + text + '"}}');
    else lines.push('  ' + id + '["' + text + '"]');
  });
  for (let i = 0; i < ids.length - 1; i++) lines.push('  ' + ids[i] + ' --> ' + ids[i + 1]);
  steps.forEach(function (s, i) {
    if (s.kind === 'trigger') lines.push('  style S' + i + ' fill:#4a2f5e,stroke:#9b59b6,color:#fff');
    else if (s.kind === 'required') lines.push('  style S' + i + ' fill:#0f4f45,stroke:#16a085,color:#fff');
    else lines.push('  style S' + i + ' fill:#33502a,stroke:#7fae42,color:#fff');
  });
  return lines.join('\n');
}

const flowPanel = document.getElementById('flow');
const flowChart = document.getElementById('flowChart');

function showFlow(appId) {
  const steps = FLOWS[appId];
  if (!steps || !steps.length || !window.mermaid) { flowPanel.style.display = 'none'; return; }
  const node = ALL_NODES.filter(function (n) { return n.id === appId; })[0];
  document.getElementById('flowTitle').textContent = node ? node.title : 'Rule flow';
  document.getElementById('flowSub').textContent = 'Decoded execution order. Rule Machine internals, so treat as a reading aid rather than the authority.';
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

document.getElementById('flowClose').addEventListener('click', function () {
  flowPanel.style.display = 'none';
});

function fillSelect(selectId, group) {
  const sel = document.getElementById(selectId);
  ALL_NODES.filter(function (n) { return n.group === group; })
    .slice().sort(function (a, b) { return a.title.localeCompare(b.title); })
    .forEach(function (n) {
      const opt = document.createElement('option');
      opt.value = n.id; opt.textContent = n.title;
      sel.appendChild(opt);
    });
  return sel;
}

const appSelect = fillSelect('appFilter', 'app');
const deviceSelect = fillSelect('deviceFilter', 'device');

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
