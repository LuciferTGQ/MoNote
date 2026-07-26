function Get-EmulatorSerialCandidates {
    param([string[]]$DeviceLines)

    foreach ($line in $DeviceLines) {
        if ($line -match '^(emulator-\d+)\s+(device|offline)$') {
            $Matches[1]
        }
    }
}

function Get-FirstCommandOutputLine {
    param([string[]]$Lines)

    return [string]($Lines | Select-Object -First 1)
}

function Test-MoNoteAvdProcessCommandLine {
    param([string]$CommandLine)

    return $CommandLine -match '(?:^|\s)-avd\s+monote_api30(?:\s|$)'
}

function Test-PinnedJdkInstallation {
    param([string]$JdkRoot, [string]$ExpectedVersion)

    $javac = Join-Path $JdkRoot 'bin\javac.exe'
    $releaseFile = Join-Path $JdkRoot 'release'
    if (-not (Test-Path $javac) -or -not (Test-Path $releaseFile)) {
        return $false
    }

    $releaseValues = @{}
    foreach ($line in Get-Content -LiteralPath $releaseFile) {
        if ($line -match '^(?<key>[A-Z0-9_]+)=(?<value>.*)$') {
            $value = $Matches['value'].Trim()
            if ($value.Length -ge 2 -and $value.StartsWith('"') -and $value.EndsWith('"')) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            $releaseValues[$Matches['key']] = $value
        }
    }

    if ($releaseValues['IMPLEMENTOR'] -cne 'Eclipse Adoptium') {
        return $false
    }

    foreach ($key in 'SEMANTIC_VERSION', 'FULL_VERSION', 'JAVA_RUNTIME_VERSION') {
        if ($releaseValues[$key] -ceq $ExpectedVersion) {
            return $true
        }
    }

    return $false
}
