$ErrorActionPreference = 'Stop'

$scriptsRoot = $PSScriptRoot
. (Join-Path $scriptsRoot 'tooling-logic.ps1')

function Assert-Equal {
    param($Expected, $Actual, [string]$Message)

    if ("$Expected" -ne "$Actual") {
        throw "$Message Expected '$Expected'; got '$Actual'."
    }
}

function Assert-True {
    param([bool]$Condition, [string]$Message)

    if (-not $Condition) {
        throw $Message
    }
}

$deviceLines = @(
    'List of devices attached',
    'emulator-5554 device',
    'emulator-5556 offline',
    'R58M1234567 device'
)
$serials = Get-EmulatorSerialCandidates $deviceLines
Assert-Equal 'emulator-5554,emulator-5556' ($serials -join ',') 'Emulator candidate discovery must include offline emulators and exclude physical devices.'
Assert-True (Test-MoNoteAvdProcessCommandLine 'qemu-system-x86_64.exe -avd monote_api30 -no-snapshot') 'MoNote AVD process must be recognized while ADB is offline.'
Assert-True (-not (Test-MoNoteAvdProcessCommandLine 'qemu-system-x86_64.exe -avd api35_other')) 'Other AVD processes must not match MoNote.'
Assert-Equal 'monote_api30' (Get-FirstCommandOutputLine @('monote_api30', 'OK')) 'ADB response parsing must use the first output line.'

$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ("monote-jdk-test-" + [Guid]::NewGuid())
$resolvedTemporaryParent = ([IO.Path]::GetFullPath([IO.Path]::GetTempPath())).TrimEnd('\') + '\'
$resolvedTemporaryRoot = [IO.Path]::GetFullPath($temporaryRoot)
if (-not $resolvedTemporaryRoot.StartsWith($resolvedTemporaryParent, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to use a temporary JDK test path outside the temp directory: $resolvedTemporaryRoot"
}

function New-TestJdkRoot {
    param([string]$Name, [string]$Implementor, [string]$RuntimeVersion)

    $root = Join-Path $temporaryRoot $Name
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'bin') | Out-Null
    New-Item -ItemType File -Force -Path (Join-Path $root 'bin\javac.exe') | Out-Null
    @(
        "IMPLEMENTOR=`"$Implementor`"",
        "JAVA_RUNTIME_VERSION=`"$RuntimeVersion`"",
        "JAVA_VERSION=`"$($RuntimeVersion.Split('+')[0])`"",
        "FULL_VERSION=`"$RuntimeVersion`"",
        "SEMANTIC_VERSION=`"$RuntimeVersion`""
    ) | Set-Content -LiteralPath (Join-Path $root 'release') -Encoding ascii
    return $root
}

New-Item -ItemType Directory -Force -Path $temporaryRoot | Out-Null
try {
    $matchingJdk = New-TestJdkRoot 'matching' 'Eclipse Adoptium' '17.0.16+8'
    $wrongVersionJdk = New-TestJdkRoot 'wrong-version' 'Eclipse Adoptium' '17.0.19+10'
    $wrongImplementorJdk = New-TestJdkRoot 'wrong-implementor' 'Acme JDK' '17.0.16+8'
    Assert-True (Test-PinnedJdkInstallation $matchingJdk '17.0.16+8') 'Matching Temurin JDK must be reusable.'
    Assert-True (-not (Test-PinnedJdkInstallation $wrongVersionJdk '17.0.16+8')) 'A JDK with javac but a different version must be rejected.'
    Assert-True (-not (Test-PinnedJdkInstallation $wrongImplementorJdk '17.0.16+8')) 'A JDK with a different implementor must be rejected.'
} finally {
    if (Test-Path $resolvedTemporaryRoot) {
        Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force
    }
}

$bootstrap = Get-Content -Raw (Join-Path $scriptsRoot 'bootstrap-android.ps1')
$wrapper = Get-Content -Raw (Join-Path $scriptsRoot '..\gradle\wrapper\gradle-wrapper.properties')
Assert-True ($bootstrap -match 'Get-FileHash') 'Bootstrap must verify downloaded archive hashes.'
Assert-True ($bootstrap -match 'jdk-17\.0\.16\+8') 'Bootstrap must pin the Temurin JDK version.'
Assert-True ($wrapper -match '(?m)^distributionSha256Sum=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78$') 'Wrapper must pin Gradle 8.13 SHA-256.'
Assert-True (-not ($wrapper -match '(?m)^validateDistributionUrl=false$')) 'Wrapper URL validation must remain enabled.'

Write-Output 'Tooling tests passed.'
