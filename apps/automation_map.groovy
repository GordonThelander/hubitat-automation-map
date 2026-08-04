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

@Field static final String APP_NAME = 'Automation Map'
@Field static final String APP_VERSION = '1.11.0'
// Bumped ONLY when the shape of the scanned graph changes, so that a rendering
// or scanning fix does not needlessly invalidate a good scan and force the user
// to re-crawl every device and app.
@Field static final String GRAPH_SCHEMA = '7'
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
}

void updated() {
    log.info "${app.label} updated"
}

Map main() {
    if (!state.accessToken) createAccessToken()

    // A full scan takes a couple of minutes. Without this the page looked frozen
    // - the progress line only moved if you closed and reopened it, which reads
    // as a hang rather than as work in progress.
    return dynamicPage(name: 'main', title: "<b>${APP_NAME} v${APP_VERSION}</b>", install: true, uninstall: true,
                       refreshInterval: state.scanRunning ? 4 : 0) {
        section {
            paragraph 'Pick the devices to scan, then press Scan. "Select All" is the normal choice.'
            paragraph '<span style="opacity:0.75">Automation Map finds your apps by looking at which apps each device belongs to, which is why it needs devices selected. Any extra device an app mentions is added to the map for you.</span>'
            input name: 'devices', type: 'capability.*', title: 'Devices to scan', multiple: true, required: true, submitOnChange: true
        }
        if (devices) {
            section {
                paragraph "${devices.size()} device(s) selected."
                input name: 'runScan', type: 'button', title: state.scanRunning ? 'Scanning...' : 'Scan relationships now'
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
                    if (graphIsStale()) {
                        // A graph built by an older version can carry relationship
                        // kinds this version no longer renders, which silently draws
                        // as uncoloured edges rather than failing visibly.
                        paragraph "<b style='color:#c0392b'>This map was built by version ${state.graphVersion ?: 'an earlier release'} and will not display correctly. Run the scan again.</b>"
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
    }
}

void appButtonHandler(String btn) {
    if (btn == 'runScan') startScan()
}

boolean graphIsStale() {
    return state.graph && state.graphVersion != GRAPH_SCHEMA
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
    state.otherEngines = []
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
            finishScan()
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
        if (info.error || !(info.roles as Map)) {
            state.appsUnreadable = (state.appsUnreadable ?: 0) + 1
        } else {
            state.appsDecoded = (state.appsDecoded ?: 0) + 1
        }
        if (info.flow) {
            state.rulesDecoded = (state.rulesDecoded ?: 0) + 1
        } else if ("${info.type}".startsWith('Rule-')) {
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

void finishScan() {
    state.scanRunning = false
    state.graph = buildGraph()
    state.graphVersion = GRAPH_SCHEMA
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
    Map out = [id: appId, label: "App ${appId}", type: null, roles: [:], flow: [], stateful: [], error: null]
    try {
        httpGet([uri: "http://127.0.0.1:8080/installedapp/statusJson/${appId}", timeout: 20]) { resp ->
            Map data = (resp.data instanceof Map) ? (resp.data as Map) : [:]

            Map installedApp = data.installedApp as Map
            out.label = stripTags((installedApp?.label ?: installedApp?.trueLabel ?: installedApp?.name ?: "App ${appId}") as String)
            out.type = installedApp?.name

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

    return [
        kind: 'action',
        ctrl: ctrl,
        cond: cond,
        label: actionLabel(method, num, act, settingValues, evalMap, capabs),
        devices: devices,
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
            return 'Set Private Boolean'
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
            return 'Stop actions'
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
        String appLabel = appMap.inactive ? "${appMap.label} [paused]" : (appMap.label as String)
        nodes[appNodeId] = nodeEntry(appNodeId, appLabel, 'app', appMap.type as String)
        if (appMap.inactive) nodes[appNodeId].inactive = true
        if (appMap.flow) flows[appNodeId] = appMap.flow

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
    path('/scan') { action: [ GET: 'scanMapping' ] }
    path('/scan-status') { action: [ GET: 'scanStatusMapping' ] }
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
<p>It was built for an older data format than this version of Automation Map expects.
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
  .legend-row { display:flex; align-items:center; margin:4px 0; }
  .swatch { width:12px; height:12px; border-radius:50%; margin-right:8px; display:inline-block; flex:none; }
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
</style>
</head>
<body>
<div id="status">Devices: ${deviceCount} &nbsp; Apps: ${appCount}</div>
<div id="legend">
  <div class="legend-row"><span class="swatch" style="background:#e8a33d"></span>App</div>
  <div class="legend-row"><span class="line" style="border-color:#9b59b6"></span>Trigger - app listens to this device</div>
  <div class="legend-row"><span class="line" style="border-color:#16a085"></span>Constraint - condition / required expression</div>
  <div class="legend-row"><span class="line" style="border-color:#3d7ea6"></span>Monitor - app reads this device's state</div>
  <div class="legend-row"><span class="line" style="border-color:#7fae42"></span>Action - app can command this device</div>
  <div class="legend-row"><span class="line" style="border-color:#c98b6b; border-top-style:dotted"></span>Exposed - published to an external system</div>
  <div class="legend-row"><span class="line" style="border-color:#8090a0; border-top-style:dashed"></span>Owns - app created this device</div>
  <div class="note">Arrows follow the flow: triggers and constraints point into the app, actions and owned devices point out of it.</div>
  <div class="note">Focus one app to colour its devices by role. A device holding two roles in one app gets two edges, and is coloured by the more significant one.</div>
</div>
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
  </select></label>
  <button id="resetBtn" type="button">Show all</button>
  <button id="insightsBtn" type="button">Insights</button>
</div>
<div id="flow"><button id="flowClose" type="button" title="Close">&times;</button><h3 id="flowTitle"></h3><div class="sub" id="flowSub"></div><div id="flowChart"></div></div>
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
const roleColors = { trigger: '#9b59b6', constraint: '#16a085', monitor: '#3d7ea6', action: '#7fae42', owns: '#8090a0', exposed: '#c98b6b' };
const groupColors = { app: '#e8a33d', device: '#5f7d8c' };

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
  return {
    id: i, from: e.from, to: e.to, kind: e.kind, stateful: e.stateful === true,
    arrows: inbound ? 'from' : 'to',
    dashes: e.kind === 'owns' ? true : (e.kind === 'exposed' ? [2, 4] : false),
    color: roleColors[e.kind] || '#999',
    width: (e.kind === 'owns' || e.kind === 'exposed') ? 1 : 1.6,
    smooth: { type: 'curvedCW', roundness: 0.12 + (dupIndex * 0.22) }
  };
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

  return html;
}

document.getElementById('insightsBtn').addEventListener('click', function () {
  document.getElementById('flowTitle').textContent = '';
  document.getElementById('flowSub').textContent = '';
  flowChart.innerHTML = buildInsights();
  flowPanel.style.display = 'block';
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
