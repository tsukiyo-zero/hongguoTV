# SPDX-License-Identifier: GPL-3.0-only
# Builds the portable Windows x64 ZIP: Compose app-image + bundled JRE + loopback bridge
# + the VLC runtime the player needs.
#
#   powershell -ExecutionPolicy Bypass -File scripts\build-windows.ps1 -VlcDir "C:\Program Files\VideoLAN\VLC"
#   powershell -ExecutionPolicy Bypass -File scripts\build-windows.ps1 -VlcZip .\vlc-3.0.21-win64.zip
#
# This file is intentionally ASCII-only: Windows PowerShell 5.1 reads BOM-less scripts with
# the ANSI code page, which corrupts non-ASCII strings. User-facing text is in Chinese in
# desktop-windows/README.md instead.
param(
    [string]$VlcDir = "",
    [string]$VlcZip = "",
    [string]$OutputDir = "",
    [switch]$NoZip
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$project = Join-Path $root "desktop-windows"

if (-not $env:JAVA_HOME) {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        throw "Set JAVA_HOME to a JDK 17 installation (jpackage is required)."
    }
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java.Source)
}
if (-not (Test-Path (Join-Path $env:JAVA_HOME "bin\jpackage.exe"))) {
    throw "JAVA_HOME does not point to a JDK with jpackage: $env:JAVA_HOME"
}

$versionLine = Select-String -Path (Join-Path $project "gradle.properties") -Pattern '^guoyingtv.version=(.+)$'
if (-not $versionLine) { throw "guoyingtv.version is missing from desktop-windows/gradle.properties." }
$version = $versionLine.Matches[0].Groups[1].Value.Trim()
Write-Host "Building GuoyingTV for Windows $version"

& (Join-Path $root "kotlin-tv\gradlew.bat") -p $project :app:createDistributable --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }

$app = Join-Path $project "app\build\compose\binaries\main\app\GuoyingTV"
if (-not (Test-Path (Join-Path $app "GuoyingTV.exe"))) { throw "GuoyingTV.exe was not produced." }
if (-not (Test-Path (Join-Path $app "runtime\bin\java.exe"))) {
    throw "The packaged runtime has no java.exe, so the loopback bridge cannot start."
}
if (-not (Test-Path (Join-Path $app "app\resources\bridge\bridge.jar"))) {
    throw "The loopback bridge was not packaged into app\resources\bridge."
}

# --- VLC runtime -------------------------------------------------------------------------
$temporary = $null
if ($VlcZip) {
    $temporary = Join-Path ([System.IO.Path]::GetTempPath()) ("guoying-vlc-" + [guid]::NewGuid().ToString("N"))
    Expand-Archive -Path $VlcZip -DestinationPath $temporary -Force
    $VlcDir = (Get-ChildItem $temporary -Directory | Select-Object -First 1).FullName
    if (-not $VlcDir) { $VlcDir = $temporary }
}
if (-not $VlcDir) {
    $candidates = @(
        (Join-Path $env:ProgramFiles "VideoLAN\VLC"),
        (Join-Path ([Environment]::GetEnvironmentVariable("ProgramFiles(x86)")) "VideoLAN\VLC")
    )
    $VlcDir = $candidates | Where-Object { Test-Path (Join-Path $_ "libvlc.dll") } | Select-Object -First 1
}
if (-not $VlcDir -or -not (Test-Path (Join-Path $VlcDir "libvlc.dll"))) {
    throw "VLC was not found. Install 64-bit VLC 3, or pass -VlcDir / -VlcZip (https://www.videolan.org/vlc/download-windows.html)."
}
if (-not (Test-Path (Join-Path $VlcDir "libvlccore.dll"))) {
    throw "libvlccore.dll is missing from $VlcDir; use the 64-bit VLC build."
}
$plugins = Join-Path $VlcDir "plugins"
if (-not (Test-Path $plugins)) { throw "VLC has no plugins folder, playback would fail: $VlcDir" }

$vlcTarget = Join-Path $app "vlc"
if (Test-Path $vlcTarget) { Remove-Item $vlcTarget -Recurse -Force }
New-Item -ItemType Directory -Path $vlcTarget | Out-Null
foreach ($file in @("libvlc.dll", "libvlccore.dll", "COPYING", "COPYING.LIB", "AUTHORS", "NEWS")) {
    $source = Join-Path $VlcDir $file
    if (Test-Path $source) { Copy-Item $source $vlcTarget }
}
Copy-Item $plugins (Join-Path $vlcTarget "plugins") -Recurse
# The 3.x lua extensions are not needed for playback and make the archive much larger.
$luaSource = Join-Path $VlcDir "lua"
if (Test-Path $luaSource) { Copy-Item $luaSource (Join-Path $vlcTarget "lua") -Recurse }

# --- distribution documents and launcher -------------------------------------------------
Copy-Item (Join-Path $root "LICENSE") (Join-Path $app "LICENSE")
Copy-Item (Join-Path $root "kotlin-tv\NOTICE.md") (Join-Path $app "NOTICE.md")
Copy-Item (Join-Path $root "desktop-windows\THIRD_PARTY.md") (Join-Path $app "THIRD_PARTY.md")
Copy-Item (Join-Path $root "desktop-windows\README.md") (Join-Path $app "README.md")

$launcher = @"
@echo off
setlocal
set "ROOT=%~dp0"
if exist "%ROOT%vlc\libvlc.dll" (
    set "PATH=%ROOT%vlc;%PATH%"
    set "VLC_PLUGIN_PATH=%ROOT%vlc\plugins"
)
start "" "%ROOT%GuoyingTV.exe"
"@
Set-Content -Path (Join-Path $app "GuoyingTV.cmd") -Value $launcher -Encoding ASCII

if ($temporary) { Remove-Item $temporary -Recurse -Force -ErrorAction SilentlyContinue }

$outputs = if ($OutputDir) { $OutputDir } else { Join-Path $root "outputs\windows" }
New-Item -ItemType Directory -Force -Path $outputs | Out-Null
if (-not $NoZip) {
    $zip = Join-Path $outputs "GuoyingTV-windows-x64-$version.zip"
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
    $size = [math]::Round((Get-Item $zip).Length / 1MB, 1)
    Write-Host "Archive: $zip ($size MB)"
}

Write-Host "Application directory: $app"
