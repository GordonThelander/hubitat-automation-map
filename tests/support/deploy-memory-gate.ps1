# Runs deploy-hub.ps1's own Test-HubMemory against canned hub answers. The function is taken from the
# script's syntax tree, not copied, so this tests what deploys. Invoke-WebRequest is replaced by a stub
# that answers from $script:Hub, so nothing leaves the machine.
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $root 'deploy-hub.ps1'), [ref]$null, [ref]$null)
$fn = $ast.Find({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Test-HubMemory' }, $true)
if ($null -eq $fn) { Write-Host 'FAIL  deploy-hub.ps1 defines Test-HubMemory'; exit 1 }
. ([scriptblock]::Create($fn.Extent.Text))

function Invoke-WebRequest {
    param([string]$Uri, [string]$Method, [int]$TimeoutSec, [switch]$UseBasicParsing)
    $key = ($Uri -split '/hub/advanced/')[1]
    if (-not $script:Hub.ContainsKey($key)) { throw "unreachable: $key" }
    return [pscustomobject]@{ Content = $script:Hub[$key] }
}

$MinFreeOsMemoryKb = 100000; $MaxNativeConsumedPercent = 50; $MinHubUptimeMinutes = 5
$boot = (Get-Date).AddHours(-2).ToString('MM-dd HH:mm:ss')
$now = (Get-Date).ToString('MM-dd HH:mm:ss')
function Hub([int]$bootFree, [int]$freeNow) {
    return @{
        'freeOSMemoryHistory' = "Date/time,Free OS,5m CPU avg,Total Java,Free Java,Direct Java`n$boot,$bootFree,0.5,331392,200000,0`n"
        'freeOSMemoryLast'    = "Date/time,Free OS,5m CPU avg,Total Java,Free Java,Direct Java`n$now,$freeNow,0.5,331392,110707,0`n"
    }
}

$results = @()
function Check([bool]$cond, [string]$label) { Write-Host ("{0}  {1}" -f $(if ($cond) { 'PASS' } else { 'FAIL' }), $label); $script:results += $cond }
function Refuses { try { Test-HubMemory 'http://hub' *> $null; return $false } catch { return $true } }

$WhatIf = $false; $AllowLowMemory = $false; $IgnoreHubUptime = $false
$script:Hub = Hub 1017164 733796
Check (-not (Refuses)) 'a hub at 28% of boot memory consumed is allowed'
$script:Hub = Hub 1017164 457000
Check (Refuses) 'a hub past 50% of boot memory consumed is refused'
$script:Hub = Hub 150000 95000
Check (Refuses) 'a hub below the free-memory floor is refused'
$script:Hub = @{}
Check (Refuses) 'memory that cannot be read is refused, not waved through (HAI #63)'
$h = Hub 1017164 733796; $h.Remove('freeOSMemoryHistory'); $script:Hub = $h
Check (Refuses) 'free memory without the boot baseline is refused: the percentage check cannot run'
$WhatIf = $true; $script:Hub = @{}
Check (-not (Refuses)) '-WhatIf reports an unreadable gauge and never refuses'
$WhatIf = $false; $AllowLowMemory = $true; $script:Hub = Hub 1017164 457000
Check (-not (Refuses)) '-AllowLowMemory is the deliberate way past the gate'
$AllowLowMemory = $false
$boot = (Get-Date).AddMinutes(-2).ToString('MM-dd HH:mm:ss'); $script:Hub = Hub 1017164 733796
Check (Refuses) 'a hub that came back two minutes ago is still settling and is refused'

$bad = @($results | Where-Object { -not $_ }).Count
Write-Host ("{0} passed, {1} failed" -f ($results.Count - $bad), $bad)
if ($bad -gt 0) { exit 1 }
