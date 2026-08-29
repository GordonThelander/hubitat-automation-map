# Telemetry Apps Script deployment

Use `deploy-telemetry-apps-script.ps1` instead of editing or deploying through the Apps Script browser
editor.

## One-time setup

1. Install Node.js 20 or newer.
2. Install Google's official Apps Script CLI:

   ```powershell
   npm install --global @google/clasp
   ```

3. Enable the Apps Script API at `https://script.google.com/home/usersettings`.
4. Authenticate with the account that owns the telemetry project:

   ```powershell
   clasp login
   ```

   Select `spam.me.here.rather@gmail.com`.

Do not commit `.clasprc.json`, `.clasp.json`, OAuth tokens, or a configured spreadsheet ID.

## Preview

```powershell
.\deploy-telemetry-apps-script.ps1 -WhatIf
```

The preview pulls the current remote project, confirms the configured spreadsheet and deployment
identity, and reports the intended script version without uploading anything.

## Deploy

```powershell
.\deploy-telemetry-apps-script.ps1 -Confirm:$false
```

The script:

1. pulls the current remote project through the Apps Script API;
2. preserves its configured spreadsheet ID only in a temporary upload;
3. confirms that the expected existing deployment belongs to the project;
4. uploads the repository source;
5. creates an immutable Apps Script version;
6. updates the existing web deployment, preserving its public URL;
7. reads the public endpoint and verifies the expected `scriptVersion` and configured state;
8. removes the temporary working directory.

The repository source must retain `REPLACE_WITH_YOUR_SPREADSHEET_ID`. The deployment script fails
closed if the remote project is unconfigured, the deployment ID is not listed, version creation
cannot be verified, or the public endpoint does not return the expected version.
