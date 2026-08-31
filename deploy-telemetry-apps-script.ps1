[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [string]$ScriptId = '1XSrFjiGNUlnSpY1xlO_U8nxn2cvACId92PQWCuQbv7cz-Tgq9kvEPHlD',
    [string]$DeploymentId = 'AKfycbxaVq68SM7ZB3szzIa0dH6x9CIQaIRLpMZbIy21tM4rhTvO1jArkfN4o3mqSmd1Cxdt',
    [string]$SourcePath = (Join-Path $PSScriptRoot 'Supporting Docs\automation_map_telemetry_apps_script.gs'),
    [string]$Description
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Clasp {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = & $script:ClaspPath @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "clasp $($Arguments -join ' ') failed:`n$($output -join [Environment]::NewLine)"
    }
    return @($output)
}

$script:Clasp = Get-Command clasp -ErrorAction SilentlyContinue
if ($script:Clasp) {
    $script:ClaspPath = $script:Clasp.Source
}
if (-not $script:Clasp) {
    throw 'clasp was not found. Install Node.js 20 or newer, run npm install --global @google/clasp, then clasp login with spam.me.here.rather@gmail.com.'
}

if (-not (Test-Path -LiteralPath $SourcePath -PathType Leaf)) {
    throw "Telemetry source was not found: $SourcePath"
}

$source = Get-Content -Raw -LiteralPath $SourcePath
$versionMatch = [regex]::Match($source, "const SCRIPT_VERSION\s*=\s*'([^']+)';")
if (-not $versionMatch.Success) {
    throw 'SCRIPT_VERSION was not found in the telemetry source.'
}
$scriptVersion = $versionMatch.Groups[1].Value

$placeholder = 'REPLACE_WITH_YOUR_SPREADSHEET_ID'
$sheetConstantPattern = "const SHEET_ID\s*=\s*'$([regex]::Escape($placeholder))';"
$sheetConstantRegex = [regex]::new($sheetConstantPattern)
if ($sheetConstantRegex.Matches($source).Count -ne 1) {
    throw 'The repository source must contain exactly one spreadsheet ID placeholder constant.'
}

if (-not $Description) {
    $Description = "Automation Map Telemetry $scriptVersion"
}

$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$workingDirectory = Join-Path $tempRoot ("automation-map-clasp-" + [guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $workingDirectory -WhatIf:$false)

try {
    $claspConfig = @{ scriptId = $ScriptId; rootDir = '.' } | ConvertTo-Json
    Set-Content -LiteralPath (Join-Path $workingDirectory '.clasp.json') -Value $claspConfig -Encoding utf8NoBOM -WhatIf:$false

    Push-Location $workingDirectory
    try {
        [void](Invoke-Clasp -Arguments @('pull'))

        $remoteCodePath = Join-Path $workingDirectory 'Code.js'
        if (-not (Test-Path -LiteralPath $remoteCodePath -PathType Leaf)) {
            $remoteCodePath = Join-Path $workingDirectory 'Code.gs'
        }
        if (-not (Test-Path -LiteralPath $remoteCodePath -PathType Leaf)) {
            throw 'The remote project did not contain Code.js or Code.gs.'
        }

        $remoteSource = Get-Content -Raw -LiteralPath $remoteCodePath
        $sheetMatch = [regex]::Match($remoteSource, "const SHEET_ID\s*=\s*'([^']+)';")
        if (-not $sheetMatch.Success -or $sheetMatch.Groups[1].Value -eq $placeholder) {
            throw 'The remote project does not contain a configured spreadsheet ID. Nothing was uploaded.'
        }
        $sheetId = $sheetMatch.Groups[1].Value

        $deploymentListing = Invoke-Clasp -Arguments @('deployments')
        if (($deploymentListing -join "`n") -notmatch [regex]::Escape($DeploymentId)) {
            throw "Deployment ID $DeploymentId does not belong to script $ScriptId. Nothing was uploaded."
        }

        $uploadSource = $sheetConstantRegex.Replace($source, "const SHEET_ID = '$sheetId';", 1)
        Set-Content -LiteralPath $remoteCodePath -Value $uploadSource -Encoding utf8NoBOM -WhatIf:$false

        Write-Host "Target script:      $ScriptId"
        Write-Host "Target deployment:  $DeploymentId"
        Write-Host "Script version:     $scriptVersion"

        if (-not $PSCmdlet.ShouldProcess($DeploymentId, "Upload and deploy telemetry script $scriptVersion")) {
            return
        }

        [void](Invoke-Clasp -Arguments @('push', '--force'))
        $versionOutput = Invoke-Clasp -Arguments @('version', $Description)
        $createdVersion = [regex]::Matches(($versionOutput -join "`n"), '(?i)version\s+(\d+)') |
            Select-Object -Last 1
        if (-not $createdVersion) {
            throw "clasp created a version but its number could not be parsed:`n$($versionOutput -join [Environment]::NewLine)"
        }
        $versionNumber = $createdVersion.Groups[1].Value

        [void](Invoke-Clasp -Arguments @('redeploy', $DeploymentId, $versionNumber, $Description))

        $endpoint = "https://script.google.com/macros/s/$DeploymentId/exec"
        $live = Invoke-RestMethod -Uri $endpoint -MaximumRedirection 10 -TimeoutSec 30
        if (-not $live.ok -or -not $live.configured -or $live.scriptVersion -ne $scriptVersion) {
            throw "Deployment verification failed. Expected $scriptVersion, received $($live.scriptVersion)."
        }

        Write-Host "Verified live scriptVersion $($live.scriptVersion) at $endpoint"
    }
    finally {
        Pop-Location
    }
}
finally {
    $resolvedWorkingDirectory = [IO.Path]::GetFullPath($workingDirectory)
    if ($resolvedWorkingDirectory.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolvedWorkingDirectory)) {
        Remove-Item -LiteralPath $resolvedWorkingDirectory -Recurse -Force -WhatIf:$false
    }
}
