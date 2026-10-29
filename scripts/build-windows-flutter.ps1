# SPDX-License-Identifier: GPL-3.0-only
# Builds the portable Windows x64 ZIP of the Flutter client:
#   Flutter release build + jlink JRE + loopback bridge jars (libmpv comes from media_kit).
#
#   powershell -ExecutionPolicy Bypass -File scripts\build-windows-flutter.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\build-windows-flutter.ps1 -NoZip
#
# This file is intentionally ASCII-only: Windows PowerShell 5.1 reads BOM-less scripts with
# the ANSI code page, which corrupts non-ASCII strings. User-facing text is Chinese in
# desktop-windows-flutter/README.md instead.
param(
    [string]$OutputDir = "",
    [switch]$NoZip
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$project = Join-Path $root "desktop-windows-flutter"
$outputs = if ($OutputDir) { $OutputDir } else { Join-Path $root "outputs\windows-flutter" }
New-Item -ItemType Directory -Force -Path $outputs | Out-Null

# Everything is mirrored into build.log so a failed CI run can be inspected without the
# (authentication-gated) Actions log. The workflow uploads this file on failure.
$logPath = Join-Path $outputs "build.log"
if (Test-Path $logPath) { Remove-Item $logPath -Force }

function Write-Log([string]$Text) {
    Write-Host $Text
    Add-Content -Path $logPath -Value $Text
}

# Gradle, Flutter and jlink all write progress to stderr. With
# $ErrorActionPreference = "Stop" that output would surface as a terminating
# NativeCommandError, so native calls are run with the preference relaxed and are
# judged by their exit code instead.
function Invoke-Native([string]$Label, [string]$File, [string[]]$NativeArguments) {
    Write-Log "==> $Label"
    Write-Log "    $File $($NativeArguments -join ' ')"
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $File @NativeArguments 2>&1 | ForEach-Object { Write-Log "$_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) {
        throw "$Label failed with exit code $code (see $logPath)."
    }
}

try {
    if (-not $env:JAVA_HOME) {
        $java = Get-Command java -ErrorAction SilentlyContinue
        if (-not $java) { throw "Set JAVA_HOME to a JDK 17 installation." }
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java.Source)
    }
    $jlink = Join-Path $env:JAVA_HOME "bin\jlink.exe"
    if (-not (Test-Path $jlink)) { throw "JAVA_HOME does not point to a JDK with jlink: $env:JAVA_HOME" }
    Invoke-Native "JDK version" (Join-Path $env:JAVA_HOME "bin\java.exe") @("-version")
    Write-Log "JDK: $env:JAVA_HOME"

    $flutterCommand = Get-Command flutter -ErrorAction SilentlyContinue
    if (-not $flutterCommand) { throw "flutter was not found on PATH." }
    $flutter = $flutterCommand.Source

    $versionLine = Select-String -Path (Join-Path $project "pubspec.yaml") -Pattern '^version:\s*([0-9]+\.[0-9]+\.[0-9]+)(?:\+([0-9]+))?\s*$'
    if (-not $versionLine) { throw "No version line found in desktop-windows-flutter/pubspec.yaml." }
    $version = $versionLine.Matches[0].Groups[1].Value
    $build = $versionLine.Matches[0].Groups[2].Value
    if (-not $build) { $build = "1" }
    Write-Log "Building GuoyingTV Windows (Flutter) $version+$build"

    # --- loopback bridge jar -------------------------------------------------------------
    $gradlew = Join-Path $root "kotlin-tv\gradlew.bat"
    Invoke-Native "Gradle :bridge:installDist" $gradlew @(
        "-p", (Join-Path $root "desktop-windows"), ":bridge:installDist", "--console=plain")

    $bridgeLib = Join-Path $root "desktop-mac\backend\build\install\bridge\lib"
    if (-not (Test-Path (Join-Path $bridgeLib "bridge.jar"))) {
        throw "bridge.jar was not produced at $bridgeLib."
    }

    # --- Flutter release build -----------------------------------------------------------
    Push-Location $project
    try {
        Invoke-Native "flutter pub get" $flutter @("pub", "get")
        Invoke-Native "flutter build windows" $flutter @(
            "build", "windows", "--release", "--build-name", $version, "--build-number", $build)
    } finally {
        Pop-Location
    }

    $release = Join-Path $project "build\windows\x64\runner\Release"
    if (-not (Test-Path (Join-Path $release "guoyingtv_windows.exe"))) {
        throw "guoyingtv_windows.exe was not produced."
    }
    if (-not (Get-ChildItem $release -Recurse -Filter "libmpv-2.dll" -ErrorAction SilentlyContinue)) {
        throw "libmpv-2.dll is missing from the Flutter build; the player would fail."
    }

    # --- staging -------------------------------------------------------------------------
    $app = Join-Path $outputs "GuoyingTV"
    if (Test-Path $app) { Remove-Item $app -Recurse -Force }
    New-Item -ItemType Directory -Path $app | Out-Null

    Get-ChildItem $release -Force | Copy-Item -Destination $app -Recurse -Force

    $runtime = Join-Path $app "runtime"
    Invoke-Native "jlink runtime" $jlink @(
        "--add-modules", "java.base,java.logging,jdk.httpserver,jdk.crypto.ec,jdk.unsupported,java.naming",
        "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=2", "--output", $runtime)
    if (-not (Test-Path (Join-Path $runtime "bin\java.exe"))) {
        throw "The trimmed runtime has no java.exe, so the bridge cannot start."
    }

    $bridgeTarget = Join-Path $app "bridge"
    New-Item -ItemType Directory -Path $bridgeTarget | Out-Null
    Copy-Item (Join-Path $bridgeLib "*.jar") $bridgeTarget

    Copy-Item (Join-Path $root "LICENSE") (Join-Path $app "LICENSE")
    Copy-Item (Join-Path $root "kotlin-tv\NOTICE.md") (Join-Path $app "NOTICE.md")
    Copy-Item (Join-Path $project "THIRD_PARTY.md") (Join-Path $app "THIRD_PARTY.md")
    Copy-Item (Join-Path $project "README.md") (Join-Path $app "README.md")

    $size = [math]::Round((Get-ChildItem $app -Recurse -File | Measure-Object Length -Sum).Sum / 1MB, 1)
    Write-Log "Application directory: $app ($size MB)"

    # --- archive -------------------------------------------------------------------------
    if (-not $NoZip) {
        $zip = Join-Path $outputs "GuoyingTV-windows-flutter-x64-$version.zip"
        if (Test-Path $zip) { Remove-Item $zip -Force }
        # Written entry by entry: Compress-Archive is slow here, and
        # ZipFile.CreateFromDirectory on .NET Framework stores backslash separators.
        Add-Type -AssemblyName System.IO.Compression
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $stream = [System.IO.File]::Create($zip)
        try {
            $archive = New-Object System.IO.Compression.ZipArchive(
                $stream, [System.IO.Compression.ZipArchiveMode]::Create)
            try {
                foreach ($file in Get-ChildItem $app -Recurse -File) {
                    $relative = $file.FullName.Substring($app.Length + 1).Replace("\", "/")
                    $entry = $archive.CreateEntry(
                        "GuoyingTV/$relative", [System.IO.Compression.CompressionLevel]::Optimal)
                    $entry.LastWriteTime = $file.LastWriteTime
                    $target = $entry.Open()
                    try {
                        $source = [System.IO.File]::OpenRead($file.FullName)
                        try { $source.CopyTo($target) } finally { $source.Dispose() }
                    } finally { $target.Dispose() }
                }
            } finally { $archive.Dispose() }
        } finally { $stream.Dispose() }
        $zipSize = [math]::Round((Get-Item $zip).Length / 1MB, 1)
        Write-Log "Archive: $zip ($zipSize MB)"
    }
} catch {
    Write-Log "BUILD FAILED: $($_.Exception.Message)"
    Write-Log $_.ScriptStackTrace
    exit 1
}
