/*
 * Automation Map Telemetry Driver
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
 * Reports anonymous version and scan-summary data (firmware version, Automation
 * Map version, and scan counts - never device/app names, hub identity, IP or
 * location) to a fixed collection endpoint after every scan. Disclosed in
 * Automation Map's own README. No credential: the endpoint is open ingestion,
 * protected server-side by strict payload validation and Google Apps Script's
 * platform quotas, not by a secret shipped in public source - a shared secret embedded in an open-source
 * driver authenticates nothing once every installer can read it.
 *
 * Created and called only by Automation Map itself, as its own child device -
 * not intended to be installed or driven standalone.
 */

import groovy.transform.Field

@Field static final String DRIVER_VERSION = '1.0.3'
@Field static final String TELEMETRY_URL = 'https://script.google.com/macros/s/AKfycbxaVq68SM7ZB3szzIa0dH6x9CIQaIRLpMZbIy21tM4rhTvO1jArkfN4o3mqSmd1Cxdt/exec'

metadata {
    definition(
        name: "Automation Map Telemetry Driver",
        namespace: "Hubitat Integrations",
        author: "Gordon Thelander",
        importUrl: "https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/dev/drivers/automation_map_telemetry_driver.groovy"
    ) {
        capability "Actuator"
        attribute "lastStatus", "string"
        attribute "lastSentAt", "string"
        command "report", [[name: "data", type: "JSON_OBJECT", description: "firmwareVersion, appVersion, apps, devices, nodes, edges, timestamp, durationSeconds, hardwareId (optional)"]]
    }
}

void report(Map data) {
    Map validation = validateReport(data)
    if (!validation.ok) {
        sendEvent(name: "lastStatus", value: "rejected: ${validation.error}")
        sendEvent(name: "lastSentAt", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
        return
    }
    Map body = [
        firmwareVersion: data?.firmwareVersion,
        appVersion     : data?.appVersion,
        apps           : data?.apps,
        devices        : data?.devices,
        nodes          : data?.nodes,
        edges          : data?.edges,
        timestamp      : data?.timestamp,
        durationSeconds: data?.durationSeconds,
        // Optional - the app-side fetch is best-effort and silent on
        // failure, so this can legitimately be null. Not part of
        // validateReport()'s required-field check for that reason.
        hardwareId     : data?.hardwareId
    ]
    Map params = [
        uri              : TELEMETRY_URL,
        contentType      : "application/json",
        requestContentType: "application/json",
        body             : body,
        // Apps Script normally redirects its web-app response to a
        // googleusercontent URL. Ask Hubitat to follow that redirect so the
        // final JSON acknowledgement can be inspected when the platform
        // supports it. The callback also handles an exposed redirect safely.
        followRedirects  : true,
        timeout          : 10
    ]
    try {
        asynchttpPost("telemetryResponse", params)
    } catch (Exception ex) {
        sendEvent(name: "lastStatus", value: "error: ${ex.message}")
        sendEvent(name: "lastSentAt", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
    }
}

private Map validateReport(Map data) {
    if (!data) return [ok: false, error: "missing report"]
    if (!(data.firmwareVersion instanceof CharSequence) || !data.firmwareVersion.toString().trim()) {
        return [ok: false, error: "missing firmwareVersion"]
    }
    if (!(data.appVersion instanceof CharSequence) || !data.appVersion.toString().trim()) {
        return [ok: false, error: "missing appVersion"]
    }
    for (String fieldName in ['apps', 'devices', 'nodes', 'edges']) {
        def value = data[fieldName]
        if (!(value instanceof Number) || value < 0 || value >= 1000000) {
            return [ok: false, error: "invalid ${fieldName}"]
        }
    }
    // Always computable from the scan's own lock token, never an external
    // fetch that could fail - required, unlike hardwareId.
    if (!(data.durationSeconds instanceof Number) || data.durationSeconds < 0 || data.durationSeconds >= 100000) {
        return [ok: false, error: "invalid durationSeconds"]
    }
    if (!(data.timestamp instanceof CharSequence) ||
        !(data.timestamp.toString() ==~ /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/)) {
        return [ok: false, error: "invalid timestamp"]
    }
    return [ok: true]
}

void telemetryResponse(resp, Map data = null) {
    try {
        int status = (resp?.status ?: 0) as int
        boolean redirected = status in [301, 302, 303, 307, 308]
        boolean transportOk = resp != null && !resp.hasError() && status == 200
        Map responseBody = transportOk ? (resp.getJson() as Map) : null
        boolean accepted = transportOk && responseBody?.ok == true
        // Hubitat may expose Apps Script's normal redirect instead of
        // following it. The POST has reached and executed the web app at that
        // point, but the redirected JSON body is unavailable, so report the
        // precise state as submitted rather than the false error previously
        // shown on the child device.
        String detail = accepted ? "ok" : redirected ? "submitted" :
            (responseBody?.error ? "rejected: ${responseBody.error}" : "error: HTTP ${status ?: 'unknown'}")
        sendEvent(name: "lastStatus", value: detail.take(255))
    } catch (Exception ex) {
        // hasError()/status can themselves throw on some failure shapes - never
        // let a telemetry response failure surface anywhere the caller notices.
        sendEvent(name: "lastStatus", value: "error: ${ex.message}")
    }
    sendEvent(name: "lastSentAt", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}
