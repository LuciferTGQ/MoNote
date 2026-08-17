param([string]$AndroidSdk = 'D:\Android\android-sdk')

$ErrorActionPreference = 'Stop'

$workspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolsRoot = Join-Path $workspaceRoot '.tools'
$jdkRoot = Join-Path $toolsRoot 'jdk-17'
$jdkStaging = Join-Path $toolsRoot 'jdk-17-staging'
$gradleRoot = Join-Path $toolsRoot 'gradle-8.13'
$jdkVersion = '17.0.16+8'
# Source and checksum: Adoptium API's Temurin jdk-17.0.16+8 Windows x64 HotSpot GA asset.
$jdkUrl = 'https://api.adoptium.net/v3/binary/version/jdk-17.0.16%2B8/windows/x64/jdk/hotspot/normal/eclipse'
$jdkSha256 = '8c7cfff78a55c56ebaf470ed6a89c6466b47d8274bdabdda997d7507c20325c5'
# Source and checksum: Gradle's official Gradle 8.13 binary distribution and .sha256 endpoint.
$gradleUrl = 'https://services.gradle.org/distributions/gradle-8.13-bin.zip'
$gradleSha256 = '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
. (Join-Path $PSScriptRoot 'tooling-logic.ps1')
New-Item -ItemType Directory -Force -Path $toolsRoot | Out-Null

function Assert-PathWithinTools {
    param([string]$Path)

    $resolvedToolsRoot = ([IO.Path]::GetFullPath($toolsRoot)).TrimEnd('\') + '\'
    $resolvedPath = [IO.Path]::GetFullPath($Path)
    if (-not $resolvedPath.StartsWith($resolvedToolsRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside .tools: $resolvedPath"
    }

    return $resolvedPath
}

function Remove-ValidatedToolDirectory {
    param([string]$Path)

    $validatedPath = Assert-PathWithinTools $Path
    if (Test-Path $validatedPath) {
        Remove-Item -LiteralPath $validatedPath -Recurse -Force
    }
}

function Assert-Sha256 {
    param([string]$Path, [string]$ExpectedHash, [string]$Description)

    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
    if ($actualHash -ne $ExpectedHash.ToLowerInvariant()) {
        throw "$Description SHA-256 mismatch. Expected $ExpectedHash; got $actualHash."
    }
}

if (-not (Test-PinnedJdkInstallation $jdkRoot $jdkVersion)) {
    # Only an incomplete .tools/jdk-17 target may be removed before installation.
    Remove-ValidatedToolDirectory $jdkRoot
    Remove-ValidatedToolDirectory $jdkStaging
    $jdkZip = Join-Path $toolsRoot 'temurin-jdk-17.0.16_8.zip'
    try {
        Invoke-WebRequest $jdkUrl -OutFile $jdkZip
        Assert-Sha256 $jdkZip $jdkSha256 "Temurin JDK $jdkVersion archive"
        Expand-Archive $jdkZip -DestinationPath $jdkStaging -Force
        $extractedJdk = Get-ChildItem -LiteralPath $jdkStaging -Directory |
            Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
            Select-Object -First 1
        if ($null -eq $extractedJdk) {
            throw 'The verified JDK archive did not contain bin\\javac.exe.'
        }
        Move-Item -LiteralPath $extractedJdk.FullName -Destination $jdkRoot
        if (-not (Test-PinnedJdkInstallation $jdkRoot $jdkVersion)) {
            throw "JDK installation did not produce Temurin $jdkVersion at .tools\\jdk-17."
        }
    } finally {
        if (Test-Path $jdkZip) {
            Remove-Item -LiteralPath $jdkZip -Force
        }
        Remove-ValidatedToolDirectory $jdkStaging
    }
}

$env:JAVA_HOME = $jdkRoot
$env:ANDROID_HOME = $AndroidSdk
$sdkManager = Join-Path $AndroidSdk 'cmdline-tools\7.0\bin\sdkmanager.bat'
$avdManager = Join-Path $AndroidSdk 'cmdline-tools\7.0\bin\avdmanager.bat'
$emulator = Join-Path $AndroidSdk 'emulator\emulator.exe'

if (-not (Test-Path $sdkManager)) {
    throw "Android SDK manager was not found at $sdkManager"
}
if (-not (Test-Path $avdManager)) {
    throw "Android AVD manager was not found at $avdManager"
}

((1..100 | ForEach-Object { 'y' }) -join [Environment]::NewLine) |
    & $sdkManager --sdk_root=$AndroidSdk --licenses | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "sdkmanager --licenses failed with exit code $LASTEXITCODE."
}

& $sdkManager --sdk_root=$AndroidSdk `
    'platforms;android-36' `
    'build-tools;35.0.0' `
    'platform-tools' `
    'emulator' `
    'system-images;android-30;google_apis;x86_64'
if ($LASTEXITCODE -ne 0) {
    throw "sdkmanager package installation failed with exit code $LASTEXITCODE."
}

$existingAvds = @(& $emulator -list-avds)
if ($LASTEXITCODE -ne 0) {
    throw "emulator -list-avds failed with exit code $LASTEXITCODE."
}
if ($existingAvds -notcontains 'monote_api30') {
    'no' | & $avdManager create avd --force --name 'monote_api30' `
        --package 'system-images;android-30;google_apis;x86_64' --device 'pixel_4'
    if ($LASTEXITCODE -ne 0) {
        throw "avdmanager create avd monote_api30 failed with exit code $LASTEXITCODE."
    }
    $existingAvds = @(& $emulator -list-avds)
    if ($LASTEXITCODE -ne 0) {
        throw "emulator -list-avds verification failed with exit code $LASTEXITCODE."
    }
}

if (-not (Test-Path (Join-Path $gradleRoot 'bin\gradle.bat'))) {
    $gradleZip = Join-Path $toolsRoot 'gradle-8.13-bin.zip'
    try {
        Invoke-WebRequest $gradleUrl -OutFile $gradleZip
        Assert-Sha256 $gradleZip $gradleSha256 'Gradle 8.13 archive'
        Expand-Archive $gradleZip -DestinationPath $toolsRoot -Force
    } finally {
        if (Test-Path $gradleZip) {
            Remove-Item -LiteralPath $gradleZip -Force
        }
    }
}

$wrapperProperties = Join-Path $workspaceRoot 'gradle\wrapper\gradle-wrapper.properties'
$wrapperReady = (Test-Path (Join-Path $workspaceRoot 'gradlew.bat')) -and
    (Test-Path $wrapperProperties) -and
    ((Get-Content -LiteralPath $wrapperProperties -Raw) -match "(?m)^distributionSha256Sum=$gradleSha256`$")
if (-not $wrapperReady) {
    Push-Location $workspaceRoot
    try {
        & (Join-Path $gradleRoot 'bin\gradle.bat') wrapper --gradle-version 8.13 `
            --gradle-distribution-sha256-sum $gradleSha256
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle wrapper generation failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
}

$requiredPaths = @(
    (Join-Path $jdkRoot 'bin\javac.exe'),
    (Join-Path $AndroidSdk 'platforms\android-36\android.jar'),
    (Join-Path $AndroidSdk 'build-tools\35.0.0'),
    (Join-Path $AndroidSdk 'platform-tools\adb.exe'),
    $emulator,
    (Join-Path $AndroidSdk 'system-images\android-30\google_apis\x86_64\system.img'),
    (Join-Path $workspaceRoot 'gradlew.bat'),
    (Join-Path $workspaceRoot 'gradle\wrapper\gradle-wrapper.jar'),
    $wrapperProperties
)
$missingPaths = @($requiredPaths | Where-Object { -not (Test-Path $_) })
if ($missingPaths.Count -gt 0) {
    throw "Bootstrap verification failed; missing: $($missingPaths -join ', ')"
}
if ($existingAvds -notcontains 'monote_api30') {
    throw 'Bootstrap verification failed; AVD monote_api30 is missing.'
}
