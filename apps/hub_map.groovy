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
@Field static final String APP_VERSION = '0.2.0'
@Field static final Pattern URL_PATTERN = ~/^https?:\/\/[^\/]+(.+)/
@Field static final Integer BATCH_SIZE = 15

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
    Map out = [id: id, label: "Device ${id}", apps: [], parentApp: null, error: null]
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
        }
    } catch (Exception ex) {
        out.error = ex.message
    }
    return out
}

String stripTags(String s) {
    return s ? s.replaceAll('<[^>]*>', '').trim() : s
}

Map buildGraph(Map results) {
    Map<String, Map> nodes = [:]
    List<Map> edges = []

    String hubName = 'Hub'
    if (location?.hubs) hubName = (location.hubs[0]?.name ?: hubName)
    nodes['hub'] = [id: 'hub', label: hubName, group: 'hub']

    results.each { String key, Map info ->
        if (info == null) return
        String devNodeId = "d${info.id}"
        nodes[devNodeId] = [id: devNodeId, label: (info.label ?: "Device ${info.id}"), group: 'device']

        if (info.parentApp) {
            String appNodeId = "a${info.parentApp.id}"
            if (!nodes[appNodeId]) nodes[appNodeId] = [id: appNodeId, label: info.parentApp.label, group: 'app']
            edges << [from: appNodeId, to: devNodeId, kind: 'owns']
        } else {
            edges << [from: 'hub', to: devNodeId, kind: 'owns']
        }

        (info.apps ?: []).each { Map usingApp ->
            boolean isParent = info.parentApp && info.parentApp.id == usingApp.id
            if (isParent) return
            String appNodeId = "a${usingApp.id}"
            if (!nodes[appNodeId]) nodes[appNodeId] = [id: appNodeId, label: usingApp.label, group: 'app']
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
  #network { width:100%; height:100vh; }
  .legend-row { display:flex; align-items:center; margin:4px 0; }
  .swatch { width:12px; height:12px; border-radius:50%; margin-right:8px; display:inline-block; }
  .line { width:20px; height:0; border-top:2px solid #fff; margin-right:8px; display:inline-block; }
  .dashed { border-top-style:dashed; }
</style>
</head>
<body>
<div id="status">Devices: ${deviceCount} &nbsp; Apps: ${appCount}</div>
<div id="legend">
  <div class="legend-row"><span class="swatch" style="background:#3498db"></span>Hub</div>
  <div class="legend-row"><span class="swatch" style="background:#e8a33d"></span>App</div>
  <div class="legend-row"><span class="swatch" style="background:#7fae42"></span>Device</div>
  <div class="legend-row"><span class="line"></span>Owns (created device)</div>
  <div class="legend-row"><span class="line dashed"></span>Uses (referenced in config)</div>
</div>
<div id="network"></div>
<script>
const GRAPH = ${jsonStr};
const groupColors = { hub: '#3498db', app: '#e8a33d', device: '#7fae42' };
const nodes = new vis.DataSet(GRAPH.nodes.map(n => ({
  id: n.id, label: n.label, color: groupColors[n.group],
  shape: n.group === 'hub' ? 'diamond' : 'dot',
  size: n.group === 'hub' ? 22 : (n.group === 'app' ? 16 : 12)
})));
const edges = new vis.DataSet(GRAPH.edges.map((e, i) => ({
  id: i, from: e.from, to: e.to, arrows: 'to',
  dashes: e.kind === 'uses', color: e.kind === 'uses' ? '#888' : '#ccc'
})));
const network = new vis.Network(document.getElementById('network'), { nodes, edges }, {
  physics: { stabilization: true, barnesHut: { gravitationalConstant: -12000, springLength: 120 } },
  interaction: { hover: true },
  edges: { smooth: { type: 'continuous' } }
});
</script>
</body>
</html>
"""
}
