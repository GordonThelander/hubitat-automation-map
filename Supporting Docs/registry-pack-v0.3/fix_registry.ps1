# Produces v0.4 of the app/integration registry from v0.3, applying the fixes
# evidenced by the HPM crawl validation report.
#
# Only changes that the evidence supports are applied. Anything needing human
# judgement is recorded in the changelog as an open question rather than
# guessed at, which is the same discipline the crawl spec imposed on itself.
#
#   .\fix_registry.ps1 -In <v0.3.json> -Out <v0.4.json> -Log <changelog.md>

param(
  [string]$In  = "$PSScriptRoot\hubitat_automation_map_app_integration_registry_v0.3.json",
  [string]$Out = "$PSScriptRoot\hubitat_automation_map_app_integration_registry_v0.4.json",
  [string]$Log = "$PSScriptRoot\REGISTRY_v0.4_CHANGES.md"
)

$reg = Get-Content $In -Raw | ConvertFrom-Json -AsHashtable
$changes = [System.Collections.ArrayList]@()
function Note($cat, $id, $what) { [void]$changes.Add([pscustomobject]@{ Category=$cat; Entry=$id; Change=$what }) }

# ---------------------------------------------------------------------------
# 1. Declare the entry classes that were used but never declared.
#    These are real classifications in use; the omission was in nodeClasses.
# ---------------------------------------------------------------------------
$missingNodeClasses = @('DASHBOARD','PLATFORM_UTILITY','SECURITY_ORCHESTRATOR','VIRTUALISATION_ORCHESTRATOR')
foreach ($c in $missingNodeClasses) {
  if ($reg.nodeClasses -notcontains $c) { $reg.nodeClasses += $c; Note 'schema' '(nodeClasses)' "declared missing entry class $c" }
}

# ---------------------------------------------------------------------------
# 2. Replace the three hedge dependency classes.
#    A class meaning "one of two things" defeats the taxonomy, and `transport`
#    already carries that ambiguity properly with values like LAN_OR_CLOUD.
# ---------------------------------------------------------------------------
$hedgeMap = @{
  'EXTERNAL_OR_LOCAL_SERVICE' = 'EXTERNAL_SERVICE'
  'LOCAL_OR_EXTERNAL_SERVICE' = 'EXTERNAL_SERVICE'
  'LOCAL_DEVICE_OR_BRIDGE'    = 'LOCAL_DEVICE'
}
foreach ($e in $reg.entries) {
  foreach ($d in @($e.dependencies)) {
    $cls = "$($d.class)"
    if ($hedgeMap.ContainsKey($cls)) {
      $d.class = $hedgeMap[$cls]
      if (-not $d.transport -or "$($d.transport)" -eq '') { $d.transport = 'LAN_OR_CLOUD' }
      Note 'schema' "$($e.id)" "dependency class $cls -> $($d.class), ambiguity moved to transport"
    }
  }
}

# ---------------------------------------------------------------------------
# 3. Rule Machine. The most important fix in the file.
#    `contains "Rule Machine"` does not fail quietly: it MATCHES the community
#    package "Rule Machine Manager", so it attaches Rule Machine's identity to
#    an unrelated app. Hubs report the built-in app as `Rule-5.1`, confirmed on
#    a live C-8 running 2.5.1.142 across 45 rules.
# ---------------------------------------------------------------------------
$rm = $reg.entries | Where-Object { "$($_.id)" -eq 'rule-machine' } | Select-Object -First 1
if ($rm) {
  $rm.matchRules = @(
    @{ field='appName'; operator='equals'; value='Rule-5.1';        confidence=100 },
    @{ field='appName'; operator='equals'; value='Button Rule-5.1'; confidence=100 },
    @{ field='appName'; operator='equals'; value='Rule-4.1';        confidence=90  }
  )
  Note 'false-positive' 'rule-machine' 'was contains "Rule Machine", which matches the unrelated package "Rule Machine Manager". Now equals the strings hubs actually report: Rule-5.1, Button Rule-5.1, Rule-4.1'
}

# ---------------------------------------------------------------------------
# 4. External platforms reached only through a user mapping.
#    There is no Hubitat app called "Node-RED" or "IFTTT", so an appName rule
#    for one can never match anything and can only ever misfire. These are
#    user-declared destinations, not installed software.
# ---------------------------------------------------------------------------
foreach ($id in @('node-red','ifttt','sharp-tools')) {
  $e = $reg.entries | Where-Object { "$($_.id)" -eq $id } | Select-Object -First 1
  if (-not $e) { continue }
  $before = @($e.matchRules).Count
  $e.matchRules = @($e.matchRules | Where-Object { "$($_.field)" -eq 'userMapping' })
  if (@($e.matchRules).Count -eq 0) {
    $e.matchRules = @(@{ field='userMapping'; operator='equals'; value="$($e.name)"; confidence=100 })
  }
  $e.matchMode = 'ALL'
  Note 'unmatchable' $id "dropped $($before - @($e.matchRules).Count) rule(s) naming a Hubitat app that does not exist; reachable only by user mapping now"
}

# ---------------------------------------------------------------------------
# 5. Tighten rules broad enough to capture unrelated packages.
#    "Group" matches Group Metering and others; "MQTT" matches any MQTT driver
#    on the hub, which is not evidence of a Shelly.
# ---------------------------------------------------------------------------
$tighten = @(
  @{ id='groups-scenes'; field='appName';    from='Group'; to='Groups and Scenes' },
  @{ id='shelly-mqtt';   field='driverName'; from='MQTT';  to='Shelly MQTT' }
)
foreach ($t in $tighten) {
  $e = $reg.entries | Where-Object { "$($_.id)" -eq $t.id } | Select-Object -First 1
  if (-not $e) { continue }
  foreach ($r in $e.matchRules) {
    if ("$($r.field)" -eq $t.field -and "$($r.value)" -eq $t.from) {
      $r.value = $t.to
      Note 'over-broad' $t.id "$($t.field) contains '$($t.from)' matched unrelated packages; narrowed to '$($t.to)'"
    }
  }
}

# ---------------------------------------------------------------------------
# 6. Record verification status rather than deleting anything.
#    An entry absent from HPM is not necessarily wrong: Hubitat built-ins are
#    not published there at all, one repository was skipped for being
#    non-HTTPS, and some packages are simply not in the index.
# ---------------------------------------------------------------------------
$verif = @{
  'hubconnect'          = 'NOT_IN_INDEX_CRAWLER_LIMITATION: its repository is published over http, which the crawler rejects. Absence here is not evidence the entry is wrong.'
  'insteon'             = 'NOT_IN_INDEX: nothing matching found in 900 HPM packages.'
  'volvo-cars'          = 'NOT_IN_INDEX: nothing matching found in 900 HPM packages.'
  'bom-weather-alerts'  = 'NOT_IN_INDEX_BY_DESIGN: published on the author repo, not registered with HPM.'
  'mdns-discovery'      = 'NOT_IN_INDEX_BY_DESIGN: published on the author repo, not registered with HPM.'
  'honeywell-tcc'       = 'NEEDS_REVIEW: no package matched. HPM has Honeywell T6 Pro and Envisalink packages, which are different things.'
  'homekit-import'      = 'NEEDS_REVIEW: HPM package is "HomeKit Import (Local)" with drivers prefixed "HomeKit HAP". Registry values may be stale.'
  'tuya-cloud'          = 'NEEDS_REVIEW: HPM package is "Tuya IoT Platform (Cloud)". Registry values may be stale.'
  'unifi-network'       = 'NEEDS_REVIEW: HPM spells it "Unifi", lower-case f, in package "UniFi-Driver".'
  'shelly-device-manager' = 'NEEDS_REVIEW: no app of that name; HPM has "Shelly Webhook/Websocket Drivers".'
}
foreach ($id in $verif.Keys) {
  $e = $reg.entries | Where-Object { "$($_.id)" -eq $id } | Select-Object -First 1
  if (-not $e) { continue }
  $e.verification = $verif[$id]
  Note 'verification' $id $verif[$id].Split(':')[0]
}

# ---------------------------------------------------------------------------
# 7. Provenance, so a later reader knows what this was checked against.
# ---------------------------------------------------------------------------
$reg.schemaVersion = '0.4'
$reg.generatedDate = (Get-Date -Format 'yyyy-MM-dd')
$reg.validatedAgainst = @{
  source      = 'https://github.com/GordonThelander/HPM_Manifest_Crawl'
  snapshot    = '2026-08-13T02:00:24Z'
  packages    = 900
  repositories = 216
  note        = 'HPM identity index only. Hubitat built-in apps are not published to HPM, so absence from the index is not evidence an entry is wrong.'
}
$reg.designNotes += 'Matching MUST be case-insensitive. Real package naming is inconsistent (BOND vs Bond, Ecowitt vs EcoWitt, kasaDoorbell vs Kasa) and case-sensitive matching produced 17 false negatives against live HPM data.'

$reg | ConvertTo-Json -Depth 12 | Set-Content $Out -Encoding UTF8
Write-Output "wrote $Out"

$md = [System.Collections.ArrayList]@()
[void]$md.Add("# Registry v0.4 changes")
[void]$md.Add("")
[void]$md.Add("Generated $(Get-Date -Format 'yyyy-MM-dd') from v0.3, applying fixes evidenced by the HPM crawl.")
[void]$md.Add("")
[void]$md.Add("| Category | Entry | Change |")
[void]$md.Add("| --- | --- | --- |")
foreach ($c in $changes) { [void]$md.Add("| $($c.Category) | ``$($c.Entry)`` | $($c.Change) |") }
[void]$md.Add("")
[void]$md.Add("Total changes: **$($changes.Count)**")
$md -join "`n" | Set-Content $Log -Encoding UTF8
Write-Output "wrote $Log ($($changes.Count) changes)"
