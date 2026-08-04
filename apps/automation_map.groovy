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
@Field static final String APP_VERSION = '1.0.0'
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

void appButtonHandler(String btn) {
    if (btn == 'runScan') startScan()
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

    queue.take(size).each { String devId ->
        Map info = fetchDeviceApps(devId)
        if (info.label) labels[devId] = info.label
        (info.appIds as List).each { String appId ->
            if (!appIds.contains(appId)) appIds << appId
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
    Map out = [id: appId, label: "App ${appId}", type: null, roles: [:], error: null]
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
        }
    } catch (Exception ex) {
        out.error = ex.message
        log.warn "${app.label}: app ${appId} lookup failed: ${ex.message}"
    }
    return out
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

    appInfo.each { String appId, info ->
        if (!(info instanceof Map)) return
        Map appMap = info as Map
        Map roles = (appMap.roles ?: [:]) as Map
        if (!roles) return

        String appNodeId = "a${appId}"
        nodes[appNodeId] = nodeEntry(appNodeId, appMap.label as String, 'app', appMap.type as String)

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

    return [nodes: nodes.values().toList(), edges: edges]
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
  <div class="note">Focus one app to colour its devices by role.</div>
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
<div id="network"></div>
<script>
const GRAPH = ${jsonStr};
const roleColors = { trigger: '#9b59b6', constraint: '#16a085', action: '#7fae42', owns: '#8090a0' };
const groupColors = { app: '#e8a33d', device: '#5f7d8c' };

const ALL_NODES = GRAPH.nodes;
const ALL_EDGES = GRAPH.edges.map(function (e, i) {
  return {
    id: i, from: e.from, to: e.to, kind: e.kind, arrows: 'to',
    dashes: e.kind === 'owns',
    color: roleColors[e.kind] || '#999',
    width: e.kind === 'owns' ? 1 : 1.6
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
    roleByDevice = {};
    shownEdges.forEach(function (e) { if (e.from === appVal) roleByDevice[e.to] = e.kind; });
  }

  const shownNodes = ids ? ALL_NODES.filter(function (n) { return ids[n.id]; }) : ALL_NODES;
  nodes.clear(); nodes.add(shownNodes.map(function (n) { return styledNode(n, !!focusId, roleByDevice); }));
  edges.clear(); edges.add(shownEdges);
  network.setOptions({ physics: { enabled: true } });
  settle();
}

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
});
deviceSelect.addEventListener('change', function () {
  if (deviceSelect.value !== '__all__') appSelect.value = '__all__';
  applyFilters();
});
document.getElementById('kindFilter').addEventListener('change', applyFilters);
document.getElementById('resetBtn').addEventListener('click', function () {
  appSelect.value = '__all__';
  deviceSelect.value = '__all__';
  document.getElementById('kindFilter').value = 'all';
  applyFilters();
});
</script>
</body>
</html>
"""
}
