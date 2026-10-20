<#
.SYNOPSIS
    Builds the debug APK, installs it on a USB-connected phone, launches it and shows its logs.

.EXAMPLE
    .\scripts\run.ps1                # build, install, launch and follow logcat
    .\scripts\run.ps1 -NoBuild       # reinstall the last built APK
    .\scripts\run.ps1 -NoLogcat      # stop after launching the app
    .\scripts\run.ps1 -Serial ZY22LXMCJK
#>
[CmdletBinding()]
param(
    [switch]$NoBuild,
    [switch]$NoLaunch,
    [switch]$NoLogcat,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'

$packageName = 'com.irondroid'
$activity = "$packageName/.MainActivity"
$repoRoot = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'

function Write-Step([string]$message) {
    Write-Host "==> $message" -ForegroundColor Cyan
}

# Terminals opened before the toolchain was installed do not see the user variables.
foreach ($name in 'JAVA_HOME', 'ANDROID_HOME') {
    if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) {
        $value = [Environment]::GetEnvironmentVariable($name, 'User')
        if (-not $value) { throw "$name is not set. See docs/build-environment.md." }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found at $adb. See docs/build-environment.md." }

function Invoke-Adb {
    $adbArgs = @()
    if ($Serial) { $adbArgs += @('-s', $Serial) }
    & $adb @($adbArgs + $args)
    if ($LASTEXITCODE -ne 0) { throw "adb $($args -join ' ') failed with exit code $LASTEXITCODE" }
}

if (-not $NoBuild) {
    Write-Step 'Building debug APK'
    Push-Location $repoRoot
    try {
        & .\gradlew.bat assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path $apk)) { throw "APK not found at $apk. Run without -NoBuild." }

Write-Step 'Looking for a connected device'
$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] }
if (-not $devices) {
    throw 'No authorized device found. Connect the phone by USB, enable USB debugging and accept the prompt on the phone.'
}
if (-not $Serial) {
    if (@($devices).Count -gt 1) { throw "Several devices connected ($($devices -join ', ')). Choose one with -Serial." }
    $Serial = @($devices)[0]
}
Write-Host "Device: $Serial"

Write-Step 'Installing APK (keeps app data)'
Invoke-Adb install -r $apk

if ($NoLaunch) { return }

Write-Step 'Launching app'
Invoke-Adb logcat -c
Invoke-Adb shell am start -n $activity | Out-Null

if ($NoLogcat) { return }

$appPid = $null
for ($i = 0; $i -lt 20 -and -not $appPid; $i++) {
    Start-Sleep -Milliseconds 250
    $appPid = (& $adb -s $Serial shell pidof -s $packageName) -as [string]
    if ($appPid) { $appPid = $appPid.Trim() }
}
if (-not $appPid) { throw "The app did not start. Check 'adb -s $Serial logcat' for a crash." }

Write-Step "Showing logs for $packageName (pid $appPid). Press Ctrl+C to stop."
& $adb -s $Serial logcat --pid=$appPid
