/*
 * Hub Map
 *
 * Visualizes how installed Hubitat apps and devices relate to each other -
 * which app created/owns each device (ownership), and which apps reference
 * each device in their own configuration (usage, e.g. a Rule Machine rule
 * that controls it) - as an interactive force-directed graph, in the same
 * visual style as Dan Danache's Zigbee Map app.
 *
 * There is no official Hubitat API for "list every app and what devices it
 * uses". This data comes from the hub's own internal /device/fullJson/<id>
 * endpoint (the one the hub's own web UI calls), fetched via a self-request
 * to 127.0.0.1 - an established community technique, not a public API.
 * Field names below were inferred from ONE real device's JSON response and
 * are defensive (fall back to a placeholder) rather than assumed to
 * generalize perfectly across every driver type.
 *
 * Device enumeration deliberately uses the standard official device picker
 * (select devices, use "Select All") rather than depending on a separately
 * configured Maker API instance and its own access token.
 */
import groovy.transform.Field
import groovy.json.JsonOutput
import java.util.regex.Pattern

@Field static final String APP_NAME = 'Hub Map'
@Field static final String APP_VERSION = '0.6.0'
@Field static final Pattern URL_PATTERN = ~/^https?:\/\/[^\/]+(.+)/
@Field static final Integer BATCH_SIZE = 15

// Best-effort "is this device actionable / trigger-like / constraint-like"
// signal, used only to guess a device's likely role for display - inferred
// from commands, capability names (if present), and device name keywords,
// NOT from any real rule logic. A rule's actual trigger/action/condition
// wiring lives inside its own config, which no endpoint we've found exposes.
@Field static final List<String> ACTUATOR_COMMANDS = [
    'on', 'off', 'setLevel', 'setColorTemperature', 'setColor', 'setHue', 'setSaturation',
    'open', 'close', 'lock', 'unlock', 'setPosition', 'strobe', 'siren', 'both',
    'setThermostatMode', 'setHeatingSetpoint', 'setCoolingSetpoint', 'start', 'stop', 'pause',
    'setSpeed', 'setVolume', 'mute', 'unmute', 'arm', 'disarm', 'beep',
]
@Field static final List<String> ACTUATOR_CAPABILITIES = [
    'Actuator', 'Switch', 'SwitchLevel', 'ColorControl', 'ColorTemperature', 'Lock',
    'GarageDoorControl', 'DoorControl', 'WindowShade', 'Thermostat', 'ThermostatMode',
    'FanControl', 'SpeakerVolume', 'AudioVolume', 'AlarmControl', 'Chime', 'Valve',
]
@Field static final List<String> CONSTRAINT_CAPABILITIES = [
    'IlluminanceMeasurement', 'TemperatureMeasurement', 'RelativeHumidityMeasurement',
    'PowerMeter', 'EnergyMeter', 'VoltageMeasurement', 'PressureMeasurement', 'Battery',
    'UltravioletIndex', 'CarbonDioxideMeasurement', 'PM25Measurement',
]
@Field static final List<String> CONSTRAINT_KEYWORDS = [
    'illuminance', 'lux', 'temperature', 'humidity', 'battery', 'power', 'voltage',
    'average', 'pressure', 'co2', 'aqi',
]

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
            paragraph 'Select the devices you want mapped (use "Select All" in the picker - all 199 is fine).'
            input name: 'devices', type: 'capability.*', title: 'Devices to include in the map', multiple: true, required: true, submitOnChange: true
        }
        if (devices) {
            section {
                paragraph "${devices.size()} device(s) selected."
                input name: 'runScan', type: 'button', title: state.scanRunning ? 'Scanning...' : 'Scan relationships now'
                if (state.scanTotal) {
                    String progress = "Scanned ${state.scanDone ?: 0} / ${state.scanTotal}"
                    if (state.scanRunning) progress += ' (in progress - close and reopen this page to refresh)'
                    paragraph progress
                }
                if (state.scanError) {
                    paragraph "<b style='color:#c0392b'>Scan error: ${state.scanError}</b>"
                }
                if (state.graph) {
                    href(
                        name: 'mapLink', title: 'View Hub Map',
                        description: 'Open the relationship graph',
                        url: getLocalURL('hub-map.html'),
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

void startScan() {
    List ids = devices.collect { it.id }.unique()
    state.scanQueue = ids
    state.scanTotal = ids.size()
    state.scanDone = 0
    state.scanRunning = true
    state.scanResults = [:]
    state.scanError = null
    unschedule('scanBatch')
    runIn(1, 'scanBatch')
}

void scanBatch() {
    List queue = state.scanQueue ?: []
    if (!queue) {
        finishScan()
        return
    }
    try {
        Map results = state.scanResults ?: [:]
        int batchSize = queue.size() < BATCH_SIZE ? queue.size() : BATCH_SIZE
        List batch = queue.take(batchSize)
        batch.each { id -> results[id.toString()] = fetchDeviceRelationships(id) }
        state.scanResults = results
        state.scanQueue = queue.drop(batchSize)
        state.scanDone = (state.scanDone ?: 0) + batchSize
    } catch (Exception ex) {
        log.warn "${app.label}: scanBatch failed: ${ex.message}"
        state.scanError = ex.message
        state.scanQueue = []
    }
    if (state.scanQueue) {
        runIn(1, 'scanBatch')
    } else {
        finishScan()
    }
}

void finishScan() {
    state.scanRunning = false
    state.graph = buildGraph(state.scanResults ?: [:])
    log.info "${app.label}: scan complete, ${state.scanTotal} device(s) processed"
}

Map fetchDeviceRelationships(id) {
    Map out = [id: id, label: "Device ${id}", apps: [], parentApp: null, commands: [], capabilities: [], error: null]
    try {
        httpGet([uri: "http://127.0.0.1:8080/device/fullJson/${id}", timeout: 8]) { resp ->
            Map data = (resp.data instanceof Map) ? (resp.data as Map) : [:]
            String breadcrumb = data.extraBreadcrumb as String
            if (breadcrumb) out.label = stripTags(breadcrumb)

            List appsUsing = (data.appsUsingForDialog ?: []) as List
            out.apps = appsUsing.findAll { it?.id != null }.collect {
                [id: it.id, label: stripTags((it.label ?: it.trueLabel ?: it.name ?: "App ${it.id}") as String)]
            }

            Map parentApp = data.parentApp as Map
            if (parentApp?.id != null) {
                out.parentApp = [id: parentApp.id, label: stripTags((parentApp.label ?: parentApp.name ?: "App ${parentApp.id}") as String)]
            }

            List cmds = (data.commands ?: []) as List
            out.commands = cmds.findAll { it?.name }.collect { it.name as String }

            Map deviceMap = data.device as Map
            List rawCaps = (data.capabilities ?: deviceMap?.capabilities ?: []) as List
            out.capabilities = rawCaps.findAll { it instanceof String }.collect { it as String }
        }
    } catch (Exception ex) {
        out.error = ex.message
    }
    return out
}

String deviceRole(List commands, List capabilities, String label) {
    boolean actionable = (commands && commands.any { ACTUATOR_COMMANDS.contains(it) }) ||
        (capabilities && capabilities.any { ACTUATOR_CAPABILITIES.contains(it) })
    if (actionable) return 'target'

    boolean constraintCap = capabilities && capabilities.any { CONSTRAINT_CAPABILITIES.contains(it) }
    String lowerLabel = (label ?: '').toLowerCase()
    boolean constraintKeyword = CONSTRAINT_KEYWORDS.any { lowerLabel.contains(it) }
    if (constraintCap || constraintKeyword) return 'constraint'

    return 'trigger'
}

String stripTags(String s) {
    return s ? s.replaceAll('<[^>]*>', '').trim() : s
}

Map nodeEntry(String id, String fullLabel, String group, String role = null) {
    String label = fullLabel ?: id
    String shortLabel = label
    if (shortLabel.length() > 22) shortLabel = "${shortLabel.substring(0, 20)}…"
    return [id: id, label: shortLabel, title: label, group: group, role: role]
}

Map buildGraph(Map results) {
    Map<String, Map> nodes = [:]
    List<Map> edges = []

    String hubName = 'Hub'
    if (location?.hubs) hubName = (location.hubs[0]?.name ?: hubName)
    nodes['hub'] = nodeEntry('hub', hubName, 'hub')

    results.each { String key, Map info ->
        if (info == null) return
        String devNodeId = "d${info.id}"
        nodes[devNodeId] = nodeEntry(devNodeId, (info.label ?: "Device ${info.id}"), 'device', deviceRole(info.commands as List, info.capabilities as List, info.label as String))

        if (info.parentApp) {
            String appNodeId = "a${info.parentApp.id}"
            if (!nodes[appNodeId]) nodes[appNodeId] = nodeEntry(appNodeId, info.parentApp.label as String, 'app')
            edges << [from: appNodeId, to: devNodeId, kind: 'owns']
        } else {
            edges << [from: 'hub', to: devNodeId, kind: 'owns']
        }

        (info.apps ?: []).each { Map usingApp ->
            boolean isParent = info.parentApp && info.parentApp.id == usingApp.id
            if (isParent) return
            String appNodeId = "a${usingApp.id}"
            if (!nodes[appNodeId]) nodes[appNodeId] = nodeEntry(appNodeId, usingApp.label as String, 'app')
            edges << [from: appNodeId, to: devNodeId, kind: 'uses']
        }
    }

    return [nodes: nodes.values().toList(), edges: edges]
}

String getLocalURL(String fileName) {
    String fullURL = "${fullLocalApiServerUrl}/${fileName}?access_token=${state.accessToken}"
    return (fullURL =~ URL_PATTERN).findAll()[0][1]
}

mappings {
    path('/hub-map.html') { action: [ GET: 'renderMapMapping' ] }
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
<title>Hub Map</title>
<script src="https://unpkg.com/vis-network/standalone/umd/vis-network.min.js"></script>
<style>
  html, body { margin:0; padding:0; height:100%; background:#062733; color:#eee; font-family:sans-serif; }
  #status { position:absolute; top:10px; left:10px; z-index:10; background:rgba(0,0,0,0.55); padding:10px 14px; border-radius:6px; font-size:0.85em; }
  #legend { position:absolute; bottom:10px; left:10px; z-index:10; background:rgba(0,0,0,0.55); padding:10px 14px; border-radius:6px; font-size:0.8em; }
  #controls { position:absolute; top:10px; right:10px; z-index:10; background:rgba(0,0,0,0.55); padding:10px 14px; border-radius:6px; font-size:0.8em; display:flex; flex-direction:column; gap:6px; width:220px; }
  #controls label { display:block; margin-bottom:2px; }
  #controls select { width:100%; box-sizing:border-box; }
  #controls button { margin-top:2px; cursor:pointer; }
  #network { width:100%; height:100vh; }
  .legend-row { display:flex; align-items:center; margin:4px 0; }
  .swatch { width:12px; height:12px; border-radius:50%; margin-right:8px; display:inline-block; }
  .swatch.square { border-radius:2px; }
  .line { width:20px; height:0; border-top:2px solid #fff; margin-right:8px; display:inline-block; }
  .dashed { border-top-style:dashed; }
</style>
</head>
<body>
<div id="status">Devices: ${deviceCount} &nbsp; Apps: ${appCount}</div>
<div id="legend">
  <div class="legend-row"><span class="swatch" style="background:#3498db"></span>Hub</div>
  <div class="legend-row"><span class="swatch" style="background:#e8a33d"></span>App</div>
  <div class="legend-row"><span class="swatch square" style="background:#7fae42"></span>Device - target/action</div>
  <div class="legend-row"><span class="swatch" style="background:#9b59b6"></span>Device - trigger</div>
  <div class="legend-row"><span class="swatch" style="background:#16a085"></span>Device - constraint</div>
  <div class="legend-row"><span class="line"></span>Owns (created device)</div>
  <div class="legend-row"><span class="line dashed"></span>Uses (referenced in config)</div>
  <div class="legend-row" style="opacity:0.75; font-size:0.9em">Role is guessed from device commands/capabilities/name, not the rule's real trigger/action/condition wiring.</div>
</div>
<div id="controls">
  <label>Focus app<select id="appFilter"><option value="__all__">All apps</option></select></label>
  <label>Focus device<select id="deviceFilter"><option value="__all__">All devices</option></select></label>
  <label>Show<select id="kindFilter">
    <option value="all">Ownership + Usage</option>
    <option value="owns">Ownership only</option>
    <option value="uses">Usage only</option>
  </select></label>
  <button id="resetBtn" type="button">Show all</button>
</div>
<div id="network"></div>
<script>
const GRAPH = ${jsonStr};
const groupColors = { hub: '#3498db', app: '#e8a33d', device: '#7fae42' };
const roleColors = { target: '#7fae42', trigger: '#9b59b6', constraint: '#16a085' };

function nodeShape(n) {
  if (n.group === 'hub') return 'diamond';
  if (n.group === 'device' && n.role === 'target') return 'square';
  return 'dot';
}
function nodeColor(n) {
  if (n.group === 'device' && n.role && roleColors[n.role]) return roleColors[n.role];
  return groupColors[n.group];
}
function styledNode(n, useFullLabel) {
  return {
    id: n.id, label: useFullLabel ? n.title : n.label, title: n.title, color: nodeColor(n),
    shape: nodeShape(n),
    size: n.group === 'hub' ? 24 : (n.group === 'app' ? 17 : 13),
    font: {
      color: '#fff', size: n.group === 'hub' ? 16 : 13,
      strokeWidth: 5, strokeColor: '#062733', vadjust: -4
    }
  };
}
function styledEdge(e, i) {
  return {
    id: i, from: e.from, to: e.to, arrows: 'to',
    dashes: e.kind === 'uses', color: e.kind === 'uses' ? '#5a6b73' : '#9fb3bb', width: e.kind === 'uses' ? 1 : 1.5
  };
}

const ALL_NODES = GRAPH.nodes;
const ALL_EDGES = GRAPH.edges.map(styledEdge);

const nodes = new vis.DataSet(ALL_NODES.map(function (n) { return styledNode(n, false); }));
const edges = new vis.DataSet(ALL_EDGES);

const network = new vis.Network(document.getElementById('network'), { nodes, edges }, {
  physics: {
    stabilization: { iterations: 300 },
    barnesHut: { gravitationalConstant: -25000, springLength: 220, springConstant: 0.02, avoidOverlap: 1 }
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

function neighborhood(nodeId) {
  const ids = new Set([nodeId]);
  const edgeList = [];
  ALL_EDGES.forEach(function (e) {
    if (e.from === nodeId || e.to === nodeId) {
      ids.add(e.from); ids.add(e.to);
      edgeList.push(e);
    }
  });
  return { ids: ids, edgeList: edgeList };
}

function applyFilters() {
  const appVal = document.getElementById('appFilter').value;
  const devVal = document.getElementById('deviceFilter').value;
  const kindVal = document.getElementById('kindFilter').value;

  let nodeIds = null;
  let edgeSubset = ALL_EDGES;

  if (appVal !== '__all__') {
    const focus = neighborhood(appVal);
    nodeIds = focus.ids; edgeSubset = focus.edgeList;
  } else if (devVal !== '__all__') {
    const focus = neighborhood(devVal);
    nodeIds = focus.ids; edgeSubset = focus.edgeList;
  }

  if (kindVal !== 'all') {
    edgeSubset = edgeSubset.filter(function (e) { return e.kind === kindVal; });
    if (nodeIds) {
      const keep = new Set();
      edgeSubset.forEach(function (e) { keep.add(e.from); keep.add(e.to); });
      if (appVal !== '__all__') keep.add(appVal);
      if (devVal !== '__all__') keep.add(devVal);
      nodeIds = keep;
    }
  }

  const shownNodes = nodeIds ? ALL_NODES.filter(function (n) { return nodeIds.has(n.id); }) : ALL_NODES;
  const shownEdges = nodeIds ? edgeSubset : (kindVal === 'all' ? ALL_EDGES : ALL_EDGES.filter(function (e) { return e.kind === kindVal; }));
  const focused = (appVal !== '__all__' || devVal !== '__all__');

  nodes.clear(); nodes.add(shownNodes.map(function (n) { return styledNode(n, focused); }));
  edges.clear(); edges.add(shownEdges);
  network.setOptions({ physics: { enabled: true } });
  settle();
}

const appSelect = document.getElementById('appFilter');
ALL_NODES.filter(function (n) { return n.group === 'app'; })
  .slice().sort(function (a, b) { return a.title.localeCompare(b.title); })
  .forEach(function (n) {
    const opt = document.createElement('option');
    opt.value = n.id; opt.textContent = n.title;
    appSelect.appendChild(opt);
  });

const deviceSelect = document.getElementById('deviceFilter');
ALL_NODES.filter(function (n) { return n.group === 'device'; })
  .slice().sort(function (a, b) { return a.title.localeCompare(b.title); })
  .forEach(function (n) {
    const opt = document.createElement('option');
    opt.value = n.id; opt.textContent = n.title;
    deviceSelect.appendChild(opt);
  });

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
