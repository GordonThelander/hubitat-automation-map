/**
 *  Automation Map Telemetry, Google Apps Script webhook
 *
 *  Receives one anonymous row per scan from every Automation Map installation:
 *  hub firmware version, Automation Map version, and scan counts. No token -
 *  the endpoint is open ingestion, protected only by the payload-shape check
 *  below, not by a secret. A secret shipped in public driver source
 *  authenticates no one; worst case of abuse here is junk rows in the sheet.
 *
 *  @version 1.3.0
 *  @author  Gordon Thelander
 *  @see     https://github.com/GordonThelander/hubitat-automation-map
 *
 *  Copyright 2026 Gordon Thelander
 *  Licensed under the Apache License, Version 2.0. You may obtain a copy at:
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  Distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 *
 *  Deployment checklist:
 *  1. Create a Google Sheet and copy its ID from the URL between /d/ and /edit.
 *  2. Replace REPLACE_WITH_YOUR_SPREADSHEET_ID below.
 *  3. Replace the entire Apps Script editor contents with this complete file and save.
 *  4. Confirm the function picker lists doGet, doPost and setupTelemetrySheet.
 *     If it still lists only myFunction, the complete file was not saved.
 *  5. Run setupTelemetrySheet once and approve the requested spreadsheet access.
 *  6. Deploy as a Web App: execute as yourself, access set to Anyone.
 *  7. Open the /exec URL. Do not continue until it returns JSON containing
 *     "ok":true, "configured":true, and a "scriptVersion" matching
 *     SCRIPT_VERSION below. A mismatched scriptVersion means the deployment is
 *     serving older code: saving the editor does NOT update a live deployment.
 *     Use Deploy -> Manage deployments -> edit -> Version: New version.
 *  8. Put that verified /exec URL into TELEMETRY_URL in the Hubitat driver.
 *
 *  Note on the header row: setupTelemetrySheet only writes HEADERS into an
 *  EMPTY sheet. Adding a column to HEADERS later does not relabel an existing
 *  sheet, so add the new header cell by hand. Data still lands in the new
 *  column either way; only the label is missing.
 */

// Bumped whenever this file changes. doGet returns it, so a single GET on the
// /exec URL proves which version is actually DEPLOYED - editing and saving the
// editor does not update a live deployment, and without this marker a stale
// deployment is indistinguishable from a current one.
const SCRIPT_VERSION = '1.3.0';

const SHEET_ID = '1-DCdtaMa4c70AeHwj7Y8ai_Jl_XO2MPxQpkmwVozjtU';
const SHEET_NAME = 'Telemetry';
const MAX_STRING_LENGTH = 40;
// hardwareId leads, so hub identity reads before the time-related columns
// rather than being buried among the version/count fields.
const HEADERS = [
  'hardwareId', 'receivedAt', 'scanTimestamp', 'durationSeconds',
  'firmwareVersion', 'appVersion', 'apps', 'devices', 'nodes', 'edges'
];

function doGet(e) {
  return json_({
    ok: true,
    service: 'Automation Map Telemetry',
    method: 'GET',
    scriptVersion: SCRIPT_VERSION,
    configured: isConfigured_(),
    columns: HEADERS.length,
    time: new Date().toISOString()
  });
}

function doPost(e) {
  try {
    const payload = parseJson_(e);
    const row = validatedRow_(payload);

    if (!isConfigured_()) throw new Error('SHEET_ID has not been configured');

    const lock = LockService.getScriptLock();
    lock.waitLock(10000);
    try {
      const sheet = getTelemetrySheet_();
      sheet.appendRow(row);
    } finally {
      lock.releaseLock();
    }

    return json_({ ok: true });

  } catch (err) {
    return json_({
      ok: false,
      error: String(err && err.message ? err.message : err)
    });
  }
}

function parseJson_(e) {
  if (!e || !e.postData || !e.postData.contents) {
    throw new Error('Missing POST body');
  }
  return JSON.parse(e.postData.contents);
}

// Strict shape check - reject anything that does not look like a real
// report rather than trying to coerce or partially accept it.
function validatedRow_(payload) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
    throw new Error('POST body must be a JSON object');
  }
  const firmwareVersion = sanitiseString_(payload.firmwareVersion);
  const appVersion = sanitiseString_(payload.appVersion);
  const apps = requireInt_(payload.apps, 'apps');
  const devices = requireInt_(payload.devices, 'devices');
  const nodes = requireInt_(payload.nodes, 'nodes');
  const edges = requireInt_(payload.edges, 'edges');
  const timestamp = sanitiseString_(payload.timestamp);
  // Always computable from the scan's own lock token on the Hubitat side,
  // never an external fetch - required, unlike hardwareId below.
  const durationSeconds = requireInt_(payload.durationSeconds, 'durationSeconds');
  // Optional - the Hubitat-side fetch is best-effort and can legitimately be
  // absent. Not required here for that reason, unlike firmwareVersion/appVersion.
  const hardwareId = sanitiseString_(payload.hardwareId);

  if (!firmwareVersion || !appVersion) {
    throw new Error('Missing firmwareVersion or appVersion');
  }
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(timestamp)) {
    throw new Error('Invalid timestamp');
  }

  // Order must match HEADERS exactly.
  return [
    hardwareId,
    new Date(),        // server-side receipt time, authoritative
    timestamp,          // client-reported scan time
    durationSeconds,
    firmwareVersion,
    appVersion,
    apps,
    devices,
    nodes,
    edges
  ];
}

function sanitiseString_(value) {
  if (value === null || value === undefined) return '';
  return String(value).replace(/[\r\n]/g, ' ').trim().slice(0, MAX_STRING_LENGTH);
}

function requireInt_(value, fieldName) {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0 || value >= 1000000) {
    throw new Error(`Invalid ${fieldName}`);
  }
  return value;
}

function isConfigured_() {
  return SHEET_ID && SHEET_ID !== 'REPLACE_WITH_YOUR_SPREADSHEET_ID';
}

function getTelemetrySheet_() {
  const spreadsheet = SpreadsheetApp.openById(SHEET_ID);
  let sheet = spreadsheet.getSheetByName(SHEET_NAME);
  if (!sheet) sheet = spreadsheet.insertSheet(SHEET_NAME);
  if (sheet.getLastRow() === 0) sheet.appendRow(HEADERS);
  return sheet;
}

// Run this once from the Apps Script editor before deploying. It verifies the
// spreadsheet ID and creates the Telemetry tab and header row when required.
// Its presence in the function picker also confirms that the full file was
// pasted and saved, alongside doGet and doPost.
function setupTelemetrySheet() {
  if (!isConfigured_()) throw new Error('Replace SHEET_ID before running setup');
  const sheet = getTelemetrySheet_();
  return `Ready: ${sheet.getParent().getName()} / ${sheet.getName()}`;
}

function json_(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
