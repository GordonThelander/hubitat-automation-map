param(
    [Parameter(Mandatory = $true)]
    [string]$Baseline,

    [Parameter(Mandatory = $true)]
    [string]$Candidate
)

$ErrorActionPreference = 'Stop'

function Read-AutomationMapExport {
    param([string]$Path, [string]$Role)

    $resolved = Resolve-Path -LiteralPath $Path -ErrorAction Stop
    try {
        $export = Get-Content -LiteralPath $resolved -Raw | ConvertFrom-Json
    }
    catch {
        throw "$Role export is not valid JSON: $resolved ($($_.Exception.Message))"
    }

    if ($null -eq $export.devices -or $null -eq $export.scan) {
        throw "$Role file is not an Automation Map AI-friendly export: $resolved"
    }

    return [pscustomobject]@{
        Path = $resolved.Path
        Data = $export
    }
}

function Has-Text {
    param($Value)
    return -not [string]::IsNullOrWhiteSpace([string]$Value)
}

function Capability-Count {
    param($Value)
    return @($Value).Count
}

$baselineExport = Read-AutomationMapExport -Path $Baseline -Role 'Baseline'
$candidateExport = Read-AutomationMapExport -Path $Candidate -Role 'Candidate'

$baselineById = @{}
foreach ($device in $baselineExport.Data.devices) {
    if (-not (Has-Text $device.id)) {
        throw "Baseline export contains a device without an id: $($baselineExport.Path)"
    }
    if ($baselineById.ContainsKey([string]$device.id)) {
        throw "Baseline export contains duplicate device id $($device.id)"
    }
    $baselineById[[string]$device.id] = $device
}

$candidateById = @{}
foreach ($device in $candidateExport.Data.devices) {
    if (-not (Has-Text $device.id)) {
        throw "Candidate export contains a device without an id: $($candidateExport.Path)"
    }
    if ($candidateById.ContainsKey([string]$device.id)) {
        throw "Candidate export contains duplicate device id $($device.id)"
    }
    $candidateById[[string]$device.id] = $device
}

$regressions = @()
foreach ($id in $baselineById.Keys) {
    if (-not $candidateById.ContainsKey($id)) {
        continue
    }

    $before = $baselineById[$id]
    $after = $candidateById[$id]
    $baselineRoom = ([string]$before.room).Trim()
    $candidateRoom = ([string]$after.room).Trim()
    $lostRoom = (Has-Text $baselineRoom) -and -not (Has-Text $candidateRoom)
    $changedRoom = (Has-Text $baselineRoom) -and (Has-Text $candidateRoom) -and
        $baselineRoom -cne $candidateRoom
    $lostCapabilities = (Capability-Count $before.capabilities) -gt 0 -and
        (Capability-Count $after.capabilities) -eq 0

    if ($lostRoom -or $changedRoom -or $lostCapabilities) {
        $regressions += [pscustomobject]@{
            id = $id
            name = [string]$after.name
            lostRoom = $lostRoom
            changedRoom = $changedRoom
            baselineRoom = $baselineRoom
            candidateRoom = $candidateRoom
            lostCapabilities = $lostCapabilities
            baselineCapabilities = Capability-Count $before.capabilities
            candidateCapabilities = Capability-Count $after.capabilities
        }
    }
}

$baselineVersion = [string]$baselineExport.Data.generatedBy
$candidateVersion = [string]$candidateExport.Data.generatedBy
$sharedCount = @($baselineById.Keys | Where-Object { $candidateById.ContainsKey($_) }).Count

Write-Host "Baseline:  $baselineVersion ($($baselineById.Count) devices)"
Write-Host "Candidate: $candidateVersion ($($candidateById.Count) devices)"
Write-Host "Compared:  $sharedCount shared device ids"

if ($regressions.Count -gt 0) {
    $regressions |
        Sort-Object name |
        Format-Table id, name, lostRoom, changedRoom, baselineRoom, candidateRoom, lostCapabilities,
            baselineCapabilities, candidateCapabilities -AutoSize |
        Out-Host

    $completeClaim = [string]$candidateExport.Data.scan.status -eq 'complete'
    $claim = if ($completeClaim) { ' Candidate also claims scan.status=complete.' } else { '' }
    [Console]::Error.WriteLine(
        "$($regressions.Count) shared device(s) lost previously populated metadata.$claim"
    )
    exit 1
}

Write-Host 'PASS: no shared device lost previously populated room or capability metadata.'
exit 0
