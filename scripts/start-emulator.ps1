param([string]$AndroidSdk = 'D:\Android\android-sdk')

$ErrorActionPreference = 'Stop'

$adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
$emulator = Join-Path $AndroidSdk 'emulator\emulator.exe'
. (Join-Path $PSScriptRoot 'tooling-logic.ps1')

function Get-MoNoteEmulatorSerial {
    $deviceLines = @(& $adb devices 2>$null)
    foreach ($candidate in (Get-EmulatorSerialCandidates $deviceLines)) {
        $avdName = Get-FirstCommandOutputLine @(& $adb -s $candidate emu avd name 2>$null)
        if ($avdName -eq 'monote_api30') {
            return $candidate
        }
    }

    return $null
}

function Test-MoNoteEmulatorProcessRunning {
    $emulatorProcesses = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -in 'emulator.exe', 'qemu-system-x86_64.exe' })
    return [bool]($emulatorProcesses | Where-Object {
        Test-MoNoteAvdProcessCommandLine $_.CommandLine
    } | Select-Object -First 1)
}

$deadline = [DateTime]::UtcNow.AddSeconds(120)
$serial = Get-MoNoteEmulatorSerial
if ($null -eq $serial -and -not (Test-MoNoteEmulatorProcessRunning)) {
    $startedEmulator = Start-Process -FilePath $emulator `
        -ArgumentList '-avd', 'monote_api30', '-no-snapshot', '-no-boot-anim' `
        -WindowStyle Hidden -PassThru
}

$serial = $null
while ([DateTime]::UtcNow -lt $deadline) {
    if ($null -ne $startedEmulator) {
        $startedEmulator.Refresh()
        if ($startedEmulator.HasExited) {
            throw "MoNote emulator process exited early with code $($startedEmulator.ExitCode)."
        }
    }

    $serial = Get-MoNoteEmulatorSerial
    if ($null -ne $serial) {
        $bootCompleted = Get-FirstCommandOutputLine @(& $adb -s $serial shell getprop sys.boot_completed 2>$null)
        if ($bootCompleted -eq '1') {
            exit 0
        }
    }

    $remainingSeconds = [Math]::Ceiling(($deadline - [DateTime]::UtcNow).TotalSeconds)
    if ($remainingSeconds -gt 0) {
        Start-Sleep -Seconds ([Math]::Min(1, $remainingSeconds))
    }
}

throw 'MoNote Android emulator did not connect and finish booting within 120 seconds.'
