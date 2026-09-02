param(
    [string]$AppFile = 'apps/automation_map.groovy',
    [string]$ManifestFile = 'packageManifest.json',
    [string]$RepositoryFile = 'repository.json',
    # Exercises each gate below against a known-bad fixture and against the
    # real source, then exits. A gate nobody has seen fail is not evidence of
    # anything.
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$errors = New-Object 'System.Collections.Generic.List[string]'

# The JVM caps a single compiled string constant at 65535 *bytes* in the
# class file's modified UTF-8, not characters. Warn well below it: this file
# reached 99.2% of the ceiling undetected once already, and the failure only
# ever shows up as a rejected hub write with "String too long".
$GStringByteLimit = 65535
$GStringByteWarn = 55000

function Add-ValidationError([string]$Message) {
    $script:errors.Add($Message)
}

# Modified UTF-8, as the class-file format actually counts it - deliberately
# not [Text.Encoding]::UTF8.GetByteCount alone, which undercounts the two
# cases where the JVM's encoding differs: a supplementary character (one
# surrogate pair) is 6 bytes rather than 4, and NUL is 2 bytes rather than 1.
# Undercounting here would let the file sail past the real ceiling.
function Get-ModifiedUtf8ByteCount([string]$Text) {
    if ([string]::IsNullOrEmpty($Text)) { return 0 }
    $bytes = [System.Text.Encoding]::UTF8.GetByteCount($Text)
    $surrogatePairs = 0
    $nulls = 0
    for ($i = 0; $i -lt $Text.Length; $i++) {
        $c = $Text[$i]
        if ([char]::IsHighSurrogate($c) -and ($i + 1) -lt $Text.Length -and [char]::IsLowSurrogate($Text[$i + 1])) {
            $surrogatePairs++
            $i++
        } elseif ($c -eq [char]0) {
            $nulls++
        }
    }
    return $bytes + (2 * $surrogatePairs) + $nulls
}

# Largest run of literal text with no ${...} interpolation to break it into a
# separate constant. Deliberately conservative: a run measured here may span a
# string-literal boundary, in which case the real constants are smaller. It
# over-reports rather than under-reports, which is the safe direction for a
# ceiling check.
function Get-LargestGStringSegment([string]$Text) {
    $pieces = $Text -split '\$\{'
    $worstBytes = 0
    $worstIndex = 0
    $offset = 0
    for ($i = 0; $i -lt $pieces.Count; $i++) {
        $segmentBytes = Get-ModifiedUtf8ByteCount $pieces[$i]
        if ($segmentBytes -gt $worstBytes) {
            $worstBytes = $segmentBytes
            $worstIndex = $offset
        }
        $offset += $pieces[$i].Length + 2
    }
    $line = 1
    if ($worstIndex -gt 0 -and $worstIndex -le $Text.Length) {
        $line = ($Text.Substring(0, $worstIndex) -split "`n").Count
    }
    return [pscustomobject]@{ Bytes = $worstBytes; Line = $line }
}

# Inside a GString, a $ must introduce ${...} or a Java identifier. Anything
# else - a JS regex backreference like '$1 $2', a regex end-anchor - is a
# compile error the local tooling never used to catch: the hub compiler did,
# after a failed deploy. Scoped to triple-quoted GString regions so ordinary
# Groovy code (slashy regexes, single-quoted strings) is not flagged.
function Find-SuspectDollarSigns([string]$Text) {
    $findings = New-Object 'System.Collections.Generic.List[string]'
    $inGString = $false
    $i = 0
    while ($i -lt $Text.Length) {
        if ($i + 2 -lt $Text.Length -and $Text.Substring($i, 3) -eq '"""') {
            $inGString = -not $inGString
            $i += 3
            continue
        }
        if ($inGString -and $Text[$i] -eq '$') {
            $isEscaped = ($i -gt 0 -and $Text[$i - 1] -eq '\')
            $next = if (($i + 1) -lt $Text.Length) { $Text[$i + 1] } else { [char]0 }
            $valid = ($next -eq '{') -or [char]::IsLetter($next) -or ($next -eq '_')
            if (-not $isEscaped -and -not $valid) {
                $line = ($Text.Substring(0, $i) -split "`n").Count
                $snippetStart = [Math]::Max(0, $i - 40)
                $snippetLength = [Math]::Min(80, $Text.Length - $snippetStart)
                $snippet = ($Text.Substring($snippetStart, $snippetLength) -replace "`r?`n", ' ')
                $findings.Add("line $($line): ...$snippet...")
            }
        }
        $i++
    }
    return $findings
}

# Finds every inline <script>...</script> block in the rendered HTML (skips
# any tag with a src= attribute - those load external files, nothing to
# check locally). Returns each block's body plus the source line its first
# character sits on, so a syntax error can be pointed back at real source.
function Get-InlineScriptBlocks([string]$Text) {
    $blocks = New-Object 'System.Collections.Generic.List[pscustomobject]'
    $regex = [regex]::new('(?is)<script(?<attrs>[^>]*)>(?<body>.*?)</script>')
    foreach ($m in $regex.Matches($Text)) {
        if ($m.Groups['attrs'].Value -match '\bsrc\s*=') { continue }
        $body = $m.Groups['body'].Value
        if ($body.Trim().Length -eq 0) { continue }
        $line = ($Text.Substring(0, $m.Groups['body'].Index) -split "`n").Count
        $blocks.Add([pscustomobject]@{ Line = $line; Body = $body })
    }
    return $blocks
}

# Stands in for every Groovy ${...} interpolation with an inert JS literal
# (0), brace-depth aware so a closure inside the interpolation - e.g.
# ${items.collect{ it.foo }.join(',')} - doesn't truncate the match at the
# first inner '}'. The placeholder value is never meant to be meaningful,
# only to leave the surrounding static JS text syntactically checkable.
function Remove-GStringInterpolations([string]$Text) {
    $sb = New-Object System.Text.StringBuilder
    $i = 0
    while ($i -lt $Text.Length) {
        if ($Text[$i] -eq '$' -and ($i + 1) -lt $Text.Length -and $Text[$i + 1] -eq '{' -and ($i -eq 0 -or $Text[$i - 1] -ne '\')) {
            $depth = 1
            $j = $i + 2
            while ($j -lt $Text.Length -and $depth -gt 0) {
                if ($Text[$j] -eq '{') { $depth++ }
                elseif ($Text[$j] -eq '}') { $depth-- }
                $j++
            }
            [void]$sb.Append('0')
            $i = $j
        } else {
            [void]$sb.Append($Text[$i])
            $i++
        }
    }
    return $sb.ToString()
}

# Runs each inline <script> block through `node --check` (syntax only,
# nothing executes). This is the gate that would have caught the revision-35
# apostrophe bug: a stray apostrophe inside a single-quoted JS string ended
# the string early and broke the entire 444KB script, and neither the
# GString byte-size nor dollar-sign gate looks for that - both are
# Groovy-template concerns, not JS-string-escaping ones. Returns a single
# sentinel finding '__NODE_MISSING__' if node isn't on PATH, so callers can
# warn-and-skip instead of failing validation over an environment gap.
function Test-InlineScriptSyntax([string]$Text, [string]$SourceLabel) {
    $findings = New-Object 'System.Collections.Generic.List[string]'
    $nodeCmd = Get-Command node -ErrorAction SilentlyContinue
    if (-not $nodeCmd) {
        $findings.Add('__NODE_MISSING__')
        return $findings
    }
    foreach ($block in (Get-InlineScriptBlocks $Text)) {
        $jsText = Remove-GStringInterpolations $block.Body
        $tmp = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "validate-js-$([guid]::NewGuid().ToString('N')).js")
        try {
            Set-Content -LiteralPath $tmp -Value $jsText -NoNewline -Encoding UTF8
            # node --check exits non-zero and writes to stderr on a syntax
            # error - exactly the case this gate exists to catch. Under
            # $ErrorActionPreference = 'Stop', Windows PowerShell (and
            # PS7.3+'s $PSNativeCommandUseErrorActionPreference) promotes
            # that into a terminating NativeCommandError before $LASTEXITCODE
            # can be inspected, aborting the whole gate instead of returning
            # a finding (caught live in a separate validation environment,
            # not just theorised). Scope ErrorActionPreference to 'Continue'
            # for this one call only, so a bad exit code is data, not a
            # thrown exception.
            $previousEap = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
                $stderr = & $nodeCmd.Source '--check' $tmp 2>&1
            } finally {
                $ErrorActionPreference = $previousEap
            }
            if ($LASTEXITCODE -ne 0) {
                $stderrText = ($stderr | Out-String).Trim()
                $lineOffset = 0
                $lm = [regex]::Match($stderrText, [regex]::Escape($tmp) + ':(\d+)')
                if ($lm.Success) { $lineOffset = [int]$lm.Groups[1].Value - 1 }
                $realLine = $block.Line + $lineOffset
                $errorSummary = ($stderrText -split "`r?`n" | Where-Object { $_ -match 'Error' } | Select-Object -First 1)
                $findings.Add("$SourceLabel - script block starting at line $($block.Line), error near line $realLine`: $errorSummary")
            }
        } finally {
            Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
        }
    }
    return $findings
}

function Match-Value([string]$Text, [string]$Pattern, [string]$Description) {
    $match = [regex]::Match($Text, $Pattern)
    if (-not $match.Success) {
        Add-ValidationError "Could not read $Description from the app source."
        return $null
    }
    return $match.Groups[1].Value
}

Push-Location $repoRoot
try {
    if (-not (Test-Path -LiteralPath $AppFile)) {
        throw "App source not found: $AppFile"
    }

    if ($SelfTest) {
        $failures = New-Object 'System.Collections.Generic.List[string]'
        function Assert-True([bool]$Condition, [string]$What) {
            if ($Condition) { Write-Host "  PASS  $What" -ForegroundColor Green }
            else { Write-Host "  FAIL  $What" -ForegroundColor Red; $failures.Add($What) }
        }

        Write-Host 'Self-test: GString byte gate' -ForegroundColor Cyan
        # A supplementary character is 4 bytes in standard UTF-8 but 6 in the
        # JVM's modified UTF-8 - the exact case a naive count gets wrong.
        $astral = [char]::ConvertFromUtf32(0x1F600)
        Assert-True ((Get-ModifiedUtf8ByteCount $astral) -eq 6) 'supplementary char counts as 6 modified-UTF-8 bytes (not 4)'
        Assert-True ((Get-ModifiedUtf8ByteCount 'abc') -eq 3) 'ASCII counts one byte per character'
        Assert-True ((Get-ModifiedUtf8ByteCount ([string][char]0)) -eq 2) 'NUL counts as 2 modified-UTF-8 bytes (not 1)'

        $overLimit = '"""' + ('x' * ($GStringByteLimit + 10)) + '"""'
        Assert-True ((Get-LargestGStringSegment $overLimit).Bytes -ge $GStringByteLimit) 'detects a constant over the JVM limit'
        $split = '"""' + ('x' * 40000) + '${' + "''" + '}' + ('x' * 40000) + '"""'
        Assert-True ((Get-LargestGStringSegment $split).Bytes -lt $GStringByteWarn) 'a ${..} split marker is recognised as breaking the constant'

        Write-Host 'Self-test: GString dollar-sign gate' -ForegroundColor Cyan
        # The exact form that reached the hub and failed to compile.
        Assert-True ((Find-SuspectDollarSigns ('"""' + 'x.replace(/a/, ' + "'" + '$1 $2' + "'" + ')' + '"""')).Count -gt 0) 'catches a $1/$2 regex backreference inside a GString'
        Assert-True ((Find-SuspectDollarSigns ('"""' + 'var re = /foo$/;' + '"""')).Count -gt 0) 'catches a regex end-anchor inside a GString'
        Assert-True ((Find-SuspectDollarSigns ('"""' + 'const a = ${value}; const b = $ident;' + '"""')).Count -eq 0) 'permits valid ${...} and $identifier interpolation'
        Assert-True ((Find-SuspectDollarSigns ("var re = /foo`$/;")).Count -eq 0) 'ignores dollars in ordinary Groovy code outside a GString'

        Write-Host 'Self-test: real source accepted by both gates' -ForegroundColor Cyan
        $realText = Get-Content -LiteralPath $AppFile -Raw
        $realLargest = Get-LargestGStringSegment $realText
        Assert-True ($realLargest.Bytes -lt $GStringByteWarn) "current source largest constant ($($realLargest.Bytes) bytes) is under the $GStringByteWarn-byte warning threshold"
        Assert-True ((Find-SuspectDollarSigns $realText).Count -eq 0) 'current source has no invalid dollar sign inside a GString'

        Write-Host 'Self-test: inline <script> JS syntax gate' -ForegroundColor Cyan
        if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
            Write-Host '  SKIP  node is not on PATH - JS syntax gate assertions skipped' -ForegroundColor Yellow
        } else {
            $goodFixture = '<script>var x = 1; function f(){ return x + ${count}; }</script>'
            Assert-True ((Test-InlineScriptSyntax $goodFixture 'fixture').Count -eq 0) 'valid JS with a ${...} interpolation passes'

            # Reproduces the actual revision-35 incident: an apostrophe inside a
            # single-quoted JS string terminates it early.
            $badFixture = '<script>var schema = { devices: ''walks the endpoint''s full tree'' };</script>'
            Assert-True ((Test-InlineScriptSyntax $badFixture 'fixture').Count -gt 0) 'a stray apostrophe inside a single-quoted JS string is caught'

            $nestedFixture = '<script>var y = ${items.collect{ it.foo }.join(",")};</script>'
            Assert-True ((Test-InlineScriptSyntax $nestedFixture 'fixture').Count -eq 0) 'a ${...} interpolation containing a nested closure brace is matched correctly, not truncated early'

            $srcFixture = '<script src="https://example.com/x.js"></script>'
            Assert-True ((Test-InlineScriptSyntax $srcFixture 'fixture').Count -eq 0) 'a script tag with src= is skipped, not treated as an empty inline block'

            $realJsFindings = Test-InlineScriptSyntax $realText $AppFile
            Assert-True ($realJsFindings.Count -eq 0) 'current source: every inline <script> block is syntactically valid JS'
        }

        if ($failures.Count -gt 0) {
            Write-Host "Self-test FAILED: $($failures.Count) assertion(s)." -ForegroundColor Red
            exit 1
        }
        Write-Host 'Self-test passed.' -ForegroundColor Green
        exit 0
    }

    $appText = Get-Content -LiteralPath $AppFile -Raw
    $manifest = $null
    $repository = $null
    try {
        $manifest = Get-Content -LiteralPath $ManifestFile -Raw | ConvertFrom-Json
    } catch {
        Add-ValidationError "$ManifestFile is not valid JSON: $($_.Exception.Message)"
    }
    try {
        $repository = Get-Content -LiteralPath $RepositoryFile -Raw | ConvertFrom-Json
    } catch {
        Add-ValidationError "$RepositoryFile is not valid JSON: $($_.Exception.Message)"
    }

    $appName = Match-Value $appText "@Field\s+static\s+final\s+String\s+APP_NAME\s*=\s*'([^']+)'" 'APP_NAME'
    $appVersion = Match-Value $appText "@Field\s+static\s+final\s+String\s+APP_VERSION\s*=\s*'([^']+)'" 'APP_VERSION'
    $namespace = Match-Value $appText "namespace:\s*'([^']+)'" 'definition namespace'

    if ($null -ne $manifest) {
        if ($manifest.packageName -ne $appName) {
            Add-ValidationError "Package name '$($manifest.packageName)' does not match APP_NAME '$appName'."
        }
        if ($manifest.version -ne $appVersion) {
            Add-ValidationError "Manifest version '$($manifest.version)' does not match APP_VERSION '$appVersion'."
        }
        if (@($manifest.apps).Count -ne 1) {
            Add-ValidationError 'The package manifest must contain exactly one app entry.'
        } else {
            $manifestApp = @($manifest.apps)[0]
            if ($manifestApp.name -ne $appName) {
                Add-ValidationError "Manifest app name '$($manifestApp.name)' does not match APP_NAME '$appName'."
            }
            if ($manifestApp.version -ne $appVersion) {
                Add-ValidationError "Manifest app version '$($manifestApp.version)' does not match APP_VERSION '$appVersion'."
            }
            if ($manifestApp.namespace -ne $namespace) {
                Add-ValidationError "Manifest namespace '$($manifestApp.namespace)' does not match '$namespace'."
            }
        }
        if ($manifest.releaseNotes -notmatch [regex]::Escape($appVersion)) {
            Add-ValidationError "Manifest release notes do not mention current version $appVersion."
        }
    }

    # Hubitat currently documents these as required fields with empty values.
    # Validate their presence, not whether they point to an image.
    if ($appText -notmatch "(?m)^\s*iconUrl:\s*(['""]).*?\1,?\s*$") {
        Add-ValidationError 'definition() is missing iconUrl.'
    }
    if ($appText -notmatch "(?m)^\s*iconX2Url:\s*(['""]).*?\1,?\s*$") {
        Add-ValidationError 'definition() is missing iconX2Url.'
    }

    $branch = $null
    if (Get-Command git -ErrorAction SilentlyContinue) {
        $branch = (& git branch --show-current 2>$null | Select-Object -First 1).Trim()
        if ($LASTEXITCODE -eq 0 -and $branch -in @('dev', 'main')) {
            $expectedSegment = "/$branch/"
            if ($null -ne $manifest) {
                if (@($manifest.apps)[0].location -notlike "*$expectedSegment*") {
                    Add-ValidationError "Manifest app location does not point to the $branch branch."
                }
                if ($manifest.documentationLink -notlike "*$expectedSegment*") {
                    Add-ValidationError "Manifest documentation link does not point to the $branch branch."
                }
                # @($null) is a one-element array in PowerShell, not empty -
                # a manifest with no drivers key at all (v2.1.8: telemetry's
                # driver entry removed) would otherwise iterate once with a
                # null $manifestDriver and fail below. Guard on the property
                # actually being present first.
                foreach ($manifestDriver in @($manifest.drivers | Where-Object { $_ })) {
                    if ($manifestDriver.location -notlike "*$expectedSegment*") {
                        Add-ValidationError "Manifest driver '$($manifestDriver.name)' location does not point to the $branch branch."
                    }
                    $driverFileName = Split-Path -Leaf ([uri]$manifestDriver.location).AbsolutePath
                    $driverPath = Join-Path 'drivers' $driverFileName
                    if (-not (Test-Path -LiteralPath $driverPath)) {
                        Add-ValidationError "Manifest driver source not found locally: $driverPath"
                        continue
                    }
                    $driverText = Get-Content -LiteralPath $driverPath -Raw
                    $driverVersionMatch = [regex]::Match($driverText, "@Field\s+static\s+final\s+String\s+DRIVER_VERSION\s*=\s*'([^']+)'")
                    if (-not $driverVersionMatch.Success) {
                        Add-ValidationError "Could not read DRIVER_VERSION from $driverPath."
                    } elseif ($manifestDriver.version -ne $driverVersionMatch.Groups[1].Value) {
                        Add-ValidationError "Manifest driver version '$($manifestDriver.version)' does not match DRIVER_VERSION '$($driverVersionMatch.Groups[1].Value)'."
                    }
                    $driverImportMatch = [regex]::Match($driverText, 'importUrl:\s*"([^"]+)"')
                    if (-not $driverImportMatch.Success -or $driverImportMatch.Groups[1].Value -notlike "*$expectedSegment*") {
                        Add-ValidationError "Driver '$($manifestDriver.name)' importUrl does not point to the $branch branch."
                    }
                }
            }
            if ($null -ne $repository -and @($repository.packages)[0].location -notlike "*$expectedSegment*") {
                Add-ValidationError "Repository package location does not point to the $branch branch."
            }
        }

        $trackedClasses = @(& git ls-files -- '*.class' 2>$null)
        if ($trackedClasses.Count -gt 0) {
            Add-ValidationError "Tracked compiler artefacts found: $($trackedClasses -join ', ')"
        }
    } else {
        Add-ValidationError 'git is unavailable, so branch URLs and tracked compiler artefacts could not be checked.'
    }

    # Backslashes inside the HTML/JavaScript GStrings require deliberate
    # escaping. Each known-safe source form is listed explicitly.
    $allowedBackslashPatterns = @(
        'Pattern URL_PATTERN',
        'Pattern ORIGIN_PATTERN',
        'replaceAll\(/\\\(\[\^\)\]\*\\\)/',
        'replaceAll\(/\\s\+/',
        'replaceAll\(/\\\.\$/',
        '==~ /\^tCapab\\d\+\$/',
        '^\s*return """\\\s*$',
        "join\('\\\\n'\)",
        '\[",\\\\n\]',
        'replace\(/\\\\s\+/g',
        'access_token=\[\^&\\\\s\]',
        "^\s*[a-z]+:\s*'\\[ue][0-9a-f]{4}'",
        'u003c',
        'replace\(/\\\\r\?\\\\n/g',
        "'\\\\ufeff'.*join\('\\\\r\\\\n'\)"
    )

    $suspectBackslashes = New-Object 'System.Collections.Generic.List[string]'
    $lineNumber = 0
    foreach ($line in Get-Content -LiteralPath $AppFile) {
        $lineNumber++
        if (-not $line.Contains('\')) { continue }
        $allowed = $false
        foreach ($pattern in $allowedBackslashPatterns) {
            if ($line -match $pattern) {
                $allowed = $true
                break
            }
        }
        if (-not $allowed) {
            $suspectBackslashes.Add("$($lineNumber):$line")
        }
    }
    if ($suspectBackslashes.Count -gt 0) {
        $detail = $suspectBackslashes -join [Environment]::NewLine
        Add-ValidationError ("Suspect backslashes in the Groovy/HTML template:" + [Environment]::NewLine + $detail)
    }

    # Gate: single string constant approaching the JVM ceiling.
    $largest = Get-LargestGStringSegment $appText
    if ($largest.Bytes -ge $GStringByteLimit) {
        Add-ValidationError ("Largest GString constant is $($largest.Bytes) bytes near line $($largest.Line), at or over the JVM limit of $GStringByteLimit. The hub will reject this with 'String too long'. Insert a `${''}` split marker to break the constant.")
    } elseif ($largest.Bytes -ge $GStringByteWarn) {
        $pct = [Math]::Round(100.0 * $largest.Bytes / $GStringByteLimit, 1)
        Add-ValidationError ("Largest GString constant is $($largest.Bytes) bytes near line $($largest.Line) - $pct% of the $GStringByteLimit-byte JVM limit, leaving only $($GStringByteLimit - $largest.Bytes) bytes. Insert a `${''}` split marker before adding more there.")
    }

    # Gate: a $ inside a GString that is neither ${...} nor an identifier.
    $suspectDollars = Find-SuspectDollarSigns $appText
    if ($suspectDollars.Count -gt 0) {
        $detail = $suspectDollars -join [Environment]::NewLine
        Add-ValidationError ("Invalid dollar sign inside a GString template (Groovy will try to interpolate it):" + [Environment]::NewLine + $detail)
    }

    # Gate: every inline <script> block must be syntactically valid JS once
    # ${...} interpolation markers are stood in for. Skips (warns, doesn't
    # fail) when node isn't available - this is a real check, not a
    # simulation, so it degrades rather than pretending to pass.
    $jsFindings = Test-InlineScriptSyntax $appText $AppFile
    if ($jsFindings.Count -gt 0 -and $jsFindings[0] -eq '__NODE_MISSING__') {
        Write-Host 'Skipping JS syntax gate: node is not on PATH. Inline <script> blocks were not checked.' -ForegroundColor Yellow
    } elseif ($jsFindings.Count -gt 0) {
        $detail = $jsFindings -join [Environment]::NewLine
        Add-ValidationError ("Inline <script> block failed a JavaScript syntax check:" + [Environment]::NewLine + $detail)
    }

    if ($errors.Count -gt 0) {
        Write-Host 'Validation failed:' -ForegroundColor Red
        foreach ($validationError in $errors) {
            Write-Host "  - $validationError" -ForegroundColor Red
        }
        exit 1
    }

    Write-Host "Validation clean: $appName v$appVersion on branch $branch" -ForegroundColor Green
} finally {
    Pop-Location
}
