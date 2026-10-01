param(
    [string]$HubUrl = 'http://10.0.0.125',
    [string]$AppFile = 'apps/automation_map.groovy',
    [int]$InstalledAppId = 0,
    [switch]$SkipValidation,
    [switch]$WhatIf
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
