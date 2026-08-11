<#
.SYNOPSIS
    Builds miniboard-lib and stages a drop-in distribution folder.
.DESCRIPTION
    Runs the committed Gradle wrapper (Gradle itself need not be installed).
    Output lands in build\dist\.
#>
[CmdletBinding()]
param(
    [switch]$Clean,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $root

Write-Host '=== MiniBoard54 Library Build ===' -ForegroundColor Cyan

# --- 1. Java present? ---
$javaExe = $null
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $javaExe = "$env:JAVA_HOME\bin\java.exe"
} else {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { $javaExe = $cmd.Source }
}
if (-not $javaExe) {
    throw 'No Java found. Set JAVA_HOME or put java on PATH.'
}
Write-Host "Java: $javaExe"

# --- 2. purejavacomm availability notice ---
# 0.0.29 is not published to Maven Central under any groupId; it must be supplied.
$pjc = Join-Path $root 'libs\purejavacomm-0.0.29.jar'
if (Test-Path $pjc) {
    Write-Host 'purejavacomm: using libs\purejavacomm-0.0.29.jar' -ForegroundColor Green
} else {
    Write-Host 'purejavacomm: MISSING from libs\purejavacomm-0.0.29.jar' -ForegroundColor Yellow
    Write-Host '  Not available on Maven Central - supply the jar by hand.' -ForegroundColor Yellow
    Write-Host '  Protocol code will compile; transport and discovery will not.' -ForegroundColor Yellow
}

# --- 3. Build ---
$gradleArgs = @()
if ($Clean)   { $gradleArgs += 'clean' }
$gradleArgs += 'build'
if ($Offline) { $gradleArgs += '--offline' }

$gradlew = Join-Path $root 'gradlew.bat'
Write-Host "Running: gradlew.bat $($gradleArgs -join ' ')" -ForegroundColor Cyan
& $gradlew @gradleArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host ''
    Write-Host 'BUILD FAILED.' -ForegroundColor Red
    Write-Host 'If the failure is unresolved purejavacomm:0.0.29, place the jar at:' -ForegroundColor Red
    Write-Host "  $pjc" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ''
Write-Host 'BUILD OK' -ForegroundColor Green
Write-Host "Distribution: $root\build\dist" -ForegroundColor Green
Get-ChildItem "$root\build\dist" -Recurse -Filter *.jar | ForEach-Object {
    Write-Host ('  ' + $_.FullName.Substring($root.Length + 1))
}
