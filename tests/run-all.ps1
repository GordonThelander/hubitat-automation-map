#Requires -Version 7
<#
Every suite in tests/, in one run: tests/*.groovy with groovy and tests/*.js with node, from the
repository root as each suite expects. Exits non-zero if any suite fails, and prints each suite's count
so a suite that quietly stopped asserting shows up as a number that went down.

There were 55 suites and no runner; they were run by hand, which is how a red one goes unnoticed.

Usage, from anywhere in the repository:

    pwsh tests/run-all.ps1                  every suite
    pwsh tests/run-all.ps1 -Only ham        only suites whose file name contains "ham"
    pwsh tests/run-all.ps1 -Groovy C:\groovy\bin\groovy.bat -Node node

The exit code is the verdict. The count is read from the suite's own output - its "N passed" summary
line if it prints one, otherwise its PASS lines - and is reported, not trusted: a suite that exits 0 is
green whatever it prints, and one that exits non-zero is red whatever it prints.
#>
param(
    [string]$Groovy = 'groovy',
    [string]$Node = 'node',
    [string]$Only = '',
    [int]$TimeoutSeconds = 600,
    [int]$Parallel = 6
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

foreach ($tool in $Groovy, $Node) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool is required and was not found." }
}

$suites = @(Get-ChildItem -Path 'tests' -File | Where-Object { $_.Extension -in '.groovy', '.js' } |
    Where-Object { -not $Only -or $_.Name -like "*$Only*" } | Sort-Object Name)
if ($suites.Count -eq 0) { Write-Output "NO SUITE MATCHES '$Only'"; exit 1 }

$results = $suites | ForEach-Object -ThrottleLimit $Parallel -Parallel {
    Set-Location $using:root
    $suite = $_
    $exe = if ($suite.Extension -eq '.groovy') { $using:Groovy } else { $using:Node }
    $started = Get-Date
    $job = Start-Job -WorkingDirectory $using:root -ScriptBlock {
        param($exe, $path)
        $out = & $exe $path 2>&1 | Out-String
        [pscustomobject]@{ Code = $LASTEXITCODE; Text = $out }
    } -ArgumentList $exe, "tests/$($suite.Name)"
    $done = Wait-Job $job -Timeout $using:TimeoutSeconds
    if ($null -eq $done) {
        Stop-Job $job; Remove-Job $job -Force
        return [pscustomobject]@{ Name = $suite.Name; Ok = $false; Count = '?'; Why = "timed out after $($using:TimeoutSeconds)s"; Text = '' }
    }
    $r = Receive-Job $job; Remove-Job $job -Force
    $text = "$($r.Text)"
    $summary = [regex]::Matches($text, '(?m)^\s*(\d+)\b[^\r\n]*\bpassed\b')
    $count = if ($summary.Count -gt 0) { $summary[$summary.Count - 1].Groups[1].Value }
             else { ([regex]::Matches($text, '(?m)^\s*PASS\b')).Count }
    $secs = [int]((Get-Date) - $started).TotalSeconds
    [pscustomobject]@{ Name = $suite.Name; Ok = ($r.Code -eq 0); Count = $count; Why = "exit $($r.Code), ${secs}s"; Text = $text }
}

$failed = @()
foreach ($r in ($results | Sort-Object Name)) {
    $mark = if ($r.Ok) { 'ok  ' } else { 'FAIL' }
    Write-Output ('{0}  {1,-48} {2,5} passed   ({3})' -f $mark, $r.Name, $r.Count, $r.Why)
    if (-not $r.Ok) { $failed += $r }
}

foreach ($r in $failed) {
    Write-Output ''
    Write-Output "==== $($r.Name) ===="
    # The failing lines, or the tail when a suite crashed without printing one.
    $lines = @($r.Text -split "`r?`n" | Where-Object { $_ -match '^\s*(FAIL|not ok|Caught:|Error|.*Exception)' })
    if ($lines.Count -eq 0) { $lines = @(($r.Text -split "`r?`n") | Select-Object -Last 15) }
    $lines | Select-Object -First 25 | ForEach-Object { Write-Output "  $_" }
}

Write-Output ''
if ($failed.Count -gt 0) {
    Write-Output "SUITES FAILED: $($failed.Count) of $($results.Count) - $(($failed | ForEach-Object Name) -join ', ')"
    exit 1
}
$scope = if ($Only) { " matching '$Only' - NOT a full run" } else { '' }
Write-Output "all $($results.Count) suites passed$scope"
exit 0
