param(
    [string]$HubUrl = 'http://10.0.0.125',
    [string]$AppFile = 'apps/automation_map.groovy',
    [int]$InstalledAppId = 0,
    [switch]$SkipValidation,
    [switch]$WhatIf,
    # The memory gate, ported from HAI's engine/deploy-hub.ps1 (HAI #63). This app is the larger write to
    # the hub (about 1.4 MB, twice HAI's engine), and the failure it guards against has happened: on
    # 2026-10-05 a write failed with OutOfMemoryError on Metaspace while the hub still reported plenty
    # free. Metaspace comes back only with a reboot.
    [int]$MinFreeOsMemoryKb = 100000,
    [int]$MaxNativeConsumedPercent = 50,
    [int]$MinHubUptimeMinutes = 5,
    [switch]$AllowLowMemory,
    [switch]$IgnoreHubUptime
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$expectedAppName = 'Automation Map (Dev)'
$utf8NoBomStrict = New-Object System.Text.UTF8Encoding($false, $true)

function Get-BytesSha256([byte[]]$Bytes) {
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha256.ComputeHash($Bytes)
        return ([BitConverter]::ToString($hash) -replace '-', '').ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

function Get-Sha256([string]$Text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    return Get-BytesSha256 $bytes
}

function Get-Utf8Text([string]$Path) {
    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    return [IO.File]::ReadAllText($resolvedPath, $utf8NoBomStrict)
}

function Get-FileSha256([string]$Path) {
    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    return Get-BytesSha256 ([IO.File]::ReadAllBytes($resolvedPath))
}

# Refuses a write the hub may not have the memory to take, before anything is contacted for writing.
# Reads /hub/advanced/freeOSMemoryLast (falling back to /freeOSMemory) for free memory now, and the first
# row of /hub/advanced/freeOSMemoryHistory for what was free at boot; the history resets at boot, so
# nothing has to be remembered between runs. A reading that cannot be taken refuses too: a gate that goes
# quiet looks exactly like a gate that passed. -WhatIf reports and never refuses.
function Test-HubMemory([string]$HubBase) {
    $freeKb = $null
    $heap = ''
    try {
        $last = (Invoke-WebRequest -Uri "$HubBase/hub/advanced/freeOSMemoryLast" -Method Get -TimeoutSec 15 -UseBasicParsing).Content
        $lastRow = @("$last" -split "`n" | Where-Object { $_ -match '^\d\d-\d\d ' }) | Select-Object -Last 1
        if ($lastRow) {
            $f = "$lastRow" -split ','
            if ($f.Count -ge 5) {
                $freeKb = [int]$f[1].Trim()
                $heap = ", Java heap {0:N0} of {1:N0} KB free" -f [int]$f[4].Trim(), [int]$f[3].Trim()
            }
        }
    } catch { }
    if ($null -eq $freeKb) {
        try {
            $raw = (Invoke-WebRequest -Uri "$HubBase/hub/advanced/freeOSMemory" -Method Get -TimeoutSec 15 -UseBasicParsing).Content
            if ("$raw".Trim() -match '^\d+$') { $freeKb = [int]("$raw".Trim()) }
        } catch { $freeKb = $null }
    }

    $bootFreeKb = $null
    $bootAt = $null
    try {
        $hist = (Invoke-WebRequest -Uri "$HubBase/hub/advanced/freeOSMemoryHistory" -Method Get -TimeoutSec 30 -UseBasicParsing).Content
        $firstRow = @("$hist" -split "`n" | Where-Object { $_ -match '^\d\d-\d\d ' }) | Select-Object -First 1
        if ($firstRow) {
            $bf = "$firstRow" -split ','
            if ($bf.Count -ge 2) { $bootFreeKb = [int]$bf[1].Trim() }
            # The stamp carries no year: assume this one, and step back if that puts the boot in the future.
            if ($bf[0] -match '^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)') {
                try {
                    $bootAt = Get-Date -Year (Get-Date).Year -Month ([int]$Matches[1]) -Day ([int]$Matches[2]) `
                                       -Hour ([int]$Matches[3]) -Minute ([int]$Matches[4]) -Second ([int]$Matches[5])
                    if ($bootAt -gt (Get-Date).AddDays(1)) { $bootAt = $bootAt.AddYears(-1) }
                } catch { $bootAt = $null }
            }
        }
    } catch { $bootFreeKb = $null }

    # A hub still bringing its apps up is busy, not short of memory; the first renders after a boot compile
    # this one-megabyte app cold, which is what widened the scan race of 2026-10-09 (HAI #58).
    if ($null -ne $bootAt) {
        $upMin = ((Get-Date) - $bootAt).TotalMinutes
        Write-Host ("Hub has been up {0:N1} minute(s) (settling period {1})." -f $upMin, $MinHubUptimeMinutes)
        if ($upMin -lt $MinHubUptimeMinutes -and -not $IgnoreHubUptime -and -not $WhatIf) {
            throw ("Refusing: the hub came back {0:N1} minute(s) ago and is still bringing its apps up. Wait until {1:HH:mm}, or pass -IgnoreHubUptime. Nothing was written." -f $upMin, $bootAt.AddMinutes($MinHubUptimeMinutes))
        }
    }

    $unread = @()
    if ($null -eq $freeKb) { $unread += 'free memory (/hub/advanced/freeOSMemoryLast and /freeOSMemory)' }
    if ($null -eq $bootFreeKb) { $unread += 'the memory free at boot (/hub/advanced/freeOSMemoryHistory)' }
    if ($unread.Count -gt 0) {
        $what = $unread -join ' or '
        if (-not $AllowLowMemory -and -not $WhatIf) {
            throw ("Refusing: the hub's {0} could not be read, so the memory check cannot run. Check the endpoint by hand, or pass -AllowLowMemory. Nothing was written." -f $what)
        }
        Write-Warning ("The hub's {0} could not be read; the memory check did not run." -f $what)
    }

    if ($null -ne $freeKb) {
        Write-Host ("Hub free OS memory: {0:N0} KB (threshold {1:N0}){2}" -f $freeKb, $MinFreeOsMemoryKb, $heap)
        if ($null -ne $bootFreeKb) {
            $gone = $bootFreeKb - $freeKb
            $pct = if ($bootFreeKb -gt 0) { [math]::Round(100.0 * $gone / $bootFreeKb) } else { 0 }
            Write-Host ("Native memory consumed since the hub booted: {0:N0} KB of {1:N0} ({2}%), threshold {3}%." -f $gone, $bootFreeKb, $pct, $MaxNativeConsumedPercent)
            if ($pct -ge $MaxNativeConsumedPercent -and -not $AllowLowMemory -and -not $WhatIf) {
                throw ("Refusing: {0}% of the hub's free memory at boot has gone, mostly class metadata from earlier app-code saves. Metaspace has its own cap, so a write can fail with OutOfMemoryError while the hub still reports {1:N0} KB free. Reboot the hub, or pass -AllowLowMemory. Nothing was written." -f $pct, $freeKb)
            }
            if ($pct -ge $MaxNativeConsumedPercent) {
                Write-Warning ("{0}% of the memory the hub had free at boot has gone; continuing because {1}." -f $pct, $(if ($WhatIf) { 'this is -WhatIf' } else { '-AllowLowMemory was given' }))
            }
        }
        if ($freeKb -lt $MinFreeOsMemoryKb -and -not $AllowLowMemory -and -not $WhatIf) {
            throw ("Refusing: the hub has {0:N0} KB free, below {1:N0}. Reboot it, or pass -AllowLowMemory. Nothing was written." -f $freeKb, $MinFreeOsMemoryKb)
        }
    }
}

Push-Location $repoRoot
try {
    if (-not (Test-Path -LiteralPath $AppFile -PathType Leaf)) {
        throw "App source not found: $AppFile"
    }

    if (-not $SkipValidation) {
        & (Join-Path $repoRoot 'validate.ps1') -AppFile $AppFile
        if ($LASTEXITCODE -ne 0) {
            throw 'validate.ps1 failed.'
        }

        $bashPath = (Get-Command bash -ErrorAction SilentlyContinue).Source
        if (-not $bashPath) {
            $gitCommand = Get-Command git -ErrorAction SilentlyContinue
            if ($null -ne $gitCommand) {
                $gitRoot = Split-Path (Split-Path $gitCommand.Source -Parent) -Parent
                $gitBash = Join-Path $gitRoot 'bin\bash.exe'
                if (Test-Path -LiteralPath $gitBash) {
                    $bashPath = $gitBash
                }
            }
        }
        if (-not $bashPath) {
            throw 'bash is required to run check_template.sh. Use -SkipValidation only after running both validators separately.'
        }
        & $bashPath './check_template.sh' $AppFile
        if ($LASTEXITCODE -ne 0) {
            throw 'check_template.sh failed.'
        }
    }

    $source = Get-Utf8Text $AppFile
    $fileHash = Get-FileSha256 $AppFile
    $sourceHash = Get-Sha256 $source
    if ($fileHash -ne $sourceHash) {
        throw 'App source must be valid BOM-less UTF-8. Refusing to deploy bytes that do not round-trip exactly.'
    }
    if ($source -notmatch "APP_NAME\s*=\s*'$([regex]::Escape($expectedAppName))'") {
        throw "Refusing deployment: source is not $expectedAppName."
    }

    $hubBase = $HubUrl.TrimEnd('/')
    Test-HubMemory $hubBase
    $apps = Invoke-RestMethod -Uri "$hubBase/hub2/appsList" -Method Get -TimeoutSec 20
    $matches = @($apps.userAppTypes | Where-Object { $_.name -eq $expectedAppName })
    if ($matches.Count -gt 1 -and $InstalledAppId -gt 0) {
        $installedStatus = [string](Invoke-RestMethod -Uri "$hubBase/installedapp/statusJson/$InstalledAppId" -Method Get -TimeoutSec 20)
        $installedName = [regex]::Match($installedStatus, '"installedApp"\s*:\s*\{[^}]*"name"\s*:\s*"([^"]+)"').Groups[1].Value
        $appTypeIdText = [regex]::Match($installedStatus, '"appTypeId"\s*:\s*(\d+)').Groups[1].Value
        if ($installedName -ne $expectedAppName -or -not $appTypeIdText) {
            throw "Installed app $InstalledAppId did not identify itself as '$expectedAppName' with an Apps Code ID. Production was not touched."
        }
        $installedAppTypeId = [int]$appTypeIdText
        $matches = @($matches | Where-Object { [int]$_.id -eq $installedAppTypeId })
        Write-Host "Resolved duplicate Apps Code names through installed Dev app $InstalledAppId -> Apps Code ID $installedAppTypeId."
    }
    if ($matches.Count -ne 1) {
        $guidance = if ($InstalledAppId -gt 0) { " Installed app $InstalledAppId did not resolve exactly one match." } else { ' Supply -InstalledAppId to correlate an installed Dev instance.' }
        throw "Expected exactly one '$expectedAppName' Apps Code entry, found $($matches.Count).$guidance Production was not touched."
    }

    $appId = [int]$matches[0].id
    $current = Invoke-RestMethod -Uri "$hubBase/app/ajax/code?id=$appId" -Method Get -TimeoutSec 20
    if ([string]::IsNullOrWhiteSpace([string]$current.source)) {
        throw "Apps Code entry $appId returned empty source. Refusing deployment."
    }

    $localHash = $sourceHash
    $currentHash = Get-Sha256 ([string]$current.source)
    Write-Host "Target: $expectedAppName, Apps Code ID $appId, revision $($current.version)"
    Write-Host "Local SHA-256:  $localHash"
    Write-Host "Hub SHA-256:    $currentHash"

    if ($localHash -eq $currentHash) {
        Write-Host 'Hub source already matches the local file. Nothing to deploy.'
        return
    }
    if ($WhatIf) {
        Write-Host 'WhatIf: validation and target discovery passed. No hub changes were made.'
        return
    }

    $backupDir = Join-Path $repoRoot '.hubitat-backups'
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backupPath = Join-Path $backupDir "automation-map-dev-app-$appId-rev-$($current.version)-$stamp.groovy"
    [IO.File]::WriteAllText($backupPath, [string]$current.source, $utf8NoBomStrict)

    $body = 'id={0}&version={1}&source={2}' -f @(
        [Net.WebUtility]::UrlEncode([string]$appId)
        [Net.WebUtility]::UrlEncode([string][int]$current.version)
        [Net.WebUtility]::UrlEncode($source)
    )
    $updateError = $null
    try {
        $result = Invoke-RestMethod -Uri "$hubBase/app/ajax/update" -Method Post -Body $body `
            -ContentType 'application/x-www-form-urlencoded; charset=utf-8' -TimeoutSec 60
        if ($result.status -and $result.status -ne 'success') {
            $updateError = "Hub rejected the update: $($result | ConvertTo-Json -Compress -Depth 5)"
        }
    } catch {
        $updateError = "Hub update request did not return cleanly: $($_.Exception.Message)"
    }

    $saved = $null
    $savedHash = $null
    for ($attempt = 1; $attempt -le 6; $attempt++) {
        try {
            $saved = Invoke-RestMethod -Uri "$hubBase/app/ajax/code?id=$appId" -Method Get -TimeoutSec 20
            $savedHash = Get-Sha256 ([string]$saved.source)
            if ($savedHash -eq $localHash -and [int]$saved.version -gt [int]$current.version) {
                break
            }
        } catch {
            $messages = @($updateError, "Readback attempt $attempt failed: $($_.Exception.Message)") |
                Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }
            $updateError = $messages -join ' '
        }
        if ($attempt -lt 6) {
            Start-Sleep -Seconds 2
        }
    }
    if ($null -eq $saved -or $savedHash -ne $localHash -or [int]$saved.version -le [int]$current.version) {
        $detail = if ($updateError) { " $updateError" } else { '' }
        throw "Post-deployment verification failed after exact readback.$detail Backup: $backupPath"
    }

    Write-Host "Deployed and verified revision $($current.version) -> $($saved.version)."
    if ($updateError) {
        Write-Warning "$updateError Exact readback nevertheless proved the requested source was saved once."
    }
    Write-Host "Backup: $backupPath"
} finally {
    Pop-Location
}
