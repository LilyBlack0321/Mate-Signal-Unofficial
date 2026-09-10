# One-shot maintenance script for the MateSignal ports repository.
#
# Builds every ported platform. The ports need different Gradle and JDK
# combinations, so each one gets its own wrapper-less Gradle and its own
# GRADLE_USER_HOME:
#
#   1.12.2 Forge    Gradle 4.10.3 + JDK 8  + ForgeGradle 3
#   1.20.1 Forge    Gradle 8.14.3 + JDK 21 + ForgeGradle 6
#   1.21.1 NeoForge Gradle 8.14.3 + JDK 21 + ModDevGradle
#
# Gradle is located in this order, so the script works on a normal machine and in
# the authoring workspace alike:
#
#   1. GRADLE_8_HOME / GRADLE_LEGACY_HOME, if set
#   2. a `gradle` on PATH (via `gradle wrapper`-style discovery of its own home)
#   3. a sibling ../_tools/gradle-<version>
#
# Usage:
#   .\build-all.ps1                 # build every port
#   .\build-all.ps1 -Only 1.20.1    # build one target
#   .\build-all.ps1 -Only 1.20.1,1.21.1
#   .\build-all.ps1 -Clean          # wipe build outputs first
#
# Note: Gradle writes progress to stderr, which PowerShell surfaces as
# NativeCommandError. We therefore run with ErrorActionPreference=Continue and
# rely on $LASTEXITCODE, which is also what the original gradlew-based scripts do.

[CmdletBinding()]
param(
    [string[]] $Only,
    [switch]   $Clean
)

$ErrorActionPreference = 'Continue'

# This script lives in <repo>\tools, so the repository root is one level up.
$Root  = Split-Path $PSScriptRoot -Parent
$Dist  = Join-Path $Root 'dist'

# --- proxy / TLS overrides ----------------------------------------------------
# Some build environments cannot open outbound TLS directly and must route HTTP
# through a local proxy; ForgeGradle's certificate probe also fails behind such a
# proxy. Those settings are properties of the machine, not of the mod, so they
# live in an untracked `gradle.properties.local` (see the .example at the repo
# root). Every systemProp.X=Y found there becomes a -D option for the Gradle JVM.
function Get-LocalSystemProps([string] $portDir) {
    $file = Join-Path $portDir 'gradle.properties.local'
    if (-not (Test-Path $file)) { return '' }
    $props = @()
    foreach ($line in Get-Content $file) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        if ($t -match '^systemProp\.(.+)$') { $props += "-D$($Matches[1])" }
    }
    return ($props -join ' ')
}

# --- toolchain discovery ------------------------------------------------------
function Find-GradleHome([string] $envVar, [string] $wantedVersion) {
    if ((Get-Item "env:$envVar" -ErrorAction SilentlyContinue)) {
        $p = (Get-Item "env:$envVar").Value
        if ($p -and (Test-Path $p)) { return $p }
    }

    $cmd = Get-Command gradle -ErrorAction SilentlyContinue
    if ($cmd) {
        $bin = Split-Path $cmd.Source -Parent
        $home = Split-Path $bin -Parent
        $ver = (& $cmd.Source --version 2>&1 | Select-String '^Gradle [0-9]' | Select-Object -First 1)
        if ($ver -and $ver.ToString().Contains($wantedVersion)) { return $home }
    }

    $sibling = Join-Path (Split-Path $Root -Parent) "_tools\gradle-$wantedVersion"
    if (Test-Path $sibling) { return $sibling }
    return $null
}

$Gradle8Home      = Find-GradleHome 'GRADLE_8_HOME'      '8.14.3'
$GradleLegacyHome = Find-GradleHome 'GRADLE_LEGACY_HOME' '4.10.3'

# JDKs: prefer an explicit environment variable, then the known local paths.
function Find-Jdk([string] $envVar, [string[]] $candidates) {
    if ((Get-Item "env:$envVar" -ErrorAction SilentlyContinue)) {
        $p = (Get-Item "env:$envVar").Value
        if ($p -and (Test-Path (Join-Path $p 'bin\java.exe'))) { return $p }
    }
    foreach ($c in $candidates) { if ($c -and (Test-Path (Join-Path $c 'bin\java.exe'))) { return $c } }
    return $null
}

$Jdk8 = Find-Jdk 'JDK8_HOME' @(
    (Join-Path (Split-Path $Root -Parent) '_tools\jdk8u504-b01'),
    'C:\Program Files\Java\jdk1.8.0_202',
    'C:\Program Files\Eclipse Adoptium\jdk-8u452-b09'
)
$Jdk21 = Find-Jdk 'JDK21_HOME' @(
    'C:\Program Files\Java\jdk-21',
    'C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot'
)

# name -> @(projectDir, gradleHome, javaHome, gradleUserHome)
$SharedGradleHome = Join-Path $Root '.gradle-user-home'

$Targets = [ordered]@{
    '1.20.1' = @(
        (Join-Path $Root 'versions\v1_20_1'), $Gradle8Home,      $Jdk21, (Join-Path $SharedGradleHome 'v1_20_1'))
    '1.21.1' = @(
        (Join-Path $Root 'versions\v1_21_1'), $Gradle8Home,      $Jdk21, (Join-Path $SharedGradleHome 'v1_21_1'))
    '1.12.2' = @(
        (Join-Path $Root 'versions\v1_12_2'), $GradleLegacyHome, $Jdk8,  (Join-Path $SharedGradleHome 'v1_12_2'))
}

New-Item -ItemType Directory -Force -Path $Dist | Out-Null

# --- preflight ----------------------------------------------------------------
$missing = @()
if (-not $Gradle8Home)      { $missing += 'Gradle 8.14.3 (set GRADLE_8_HOME, or put gradle on PATH)' }
if (-not $GradleLegacyHome) { $missing += 'Gradle 4.10.3 (set GRADLE_LEGACY_HOME) - required by ForgeGradle 3' }
if (-not $Jdk8)             { $missing += 'JDK 8 (set JDK8_HOME) - required by the 1.12.2 toolchain' }
if (-not $Jdk21)            { $missing += 'JDK 21 (set JDK21_HOME) - required by the 1.20.1 and 1.21.1 toolchains' }
if ($missing.Count -gt 0) {
    Write-Host 'Missing toolchain components:' -ForegroundColor Red
    $missing | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Write-Host 'See README.md ("Building") for how to fetch them.' -ForegroundColor Yellow
    exit 1
}

$results = @()

# Reads the Gradle version. A directory named gradle-<version> is the common
# layout and needs no subprocess; otherwise run the launcher and keep the first
# line of its banner. Note that PowerShell 7 can drop gradle.bat's stderr through
# a native-command pipeline, so the banner is only a fallback.
function Get-GradleVersion([string] $gradleHome, [string] $gradleBat) {
    if ((Split-Path $gradleHome -Leaf) -match '^gradle-(\d[\w.]*)$') { return "Gradle $($Matches[1])" }
    try {
        $line = & $gradleBat --version 2>&1 | Select-String '^Gradle [0-9]' | Select-Object -First 1
        if ($line) { return ($line.ToString() -replace '\s+', ' ').Trim() }
    } catch {
        # cosmetic only
    }
    return 'unknown'
}

foreach ($name in $Targets.Keys) {
    if ($Only -and ($Only -notcontains $name)) { continue }

    $dir, $gradleHome, $javaHome, $gradleUserHome = $Targets[$name]
    $gradle = Join-Path $gradleHome 'bin\gradle.bat'

    # JAVA_HOME must be exported before Gradle starts: gradle.bat reads it to
    # locate a JVM.
    $env:JAVA_HOME = $javaHome

    Write-Host ''
    Write-Host '===========================================' -ForegroundColor Cyan
    Write-Host " Building MateSignal for Minecraft $name" -ForegroundColor Cyan
    Write-Host "   project : $dir"
    Write-Host "   gradle  : $(Get-GradleVersion $gradleHome $gradle)"
    Write-Host "   java    : $javaHome"
    Write-Host '===========================================' -ForegroundColor Cyan

    New-Item -ItemType Directory -Force -Path $gradleUserHome | Out-Null
    $env:GRADLE_USER_HOME  = $gradleUserHome

    $extra = Get-LocalSystemProps $dir
    if ($extra) {
        # JAVA_TOOL_OPTIONS is used rather than GRADLE_OPTS on purpose: gradle.bat
        # forwards GRADLE_OPTS by pasting it into a command line, which splits on
        # spaces and produces "'127.0.0.1' is not recognized as an internal or
        # external command". JAVA_TOOL_OPTIONS is read and split by the JVM
        # itself, so spaces are safe. The JVM echoes "Picked up
        # JAVA_TOOL_OPTIONS: ..." on stderr for each one it starts; that line is
        # expected, not an error.
        $env:JAVA_TOOL_OPTIONS = $extra
        Write-Host "   proxy   : from gradle.properties.local" -ForegroundColor DarkGray
    } else {
        $env:JAVA_TOOL_OPTIONS = ''
    }

    Push-Location $dir
    if ($Clean) {
        & $gradle clean build --console=plain
    } else {
        & $gradle build --console=plain
    }
    $code = $LASTEXITCODE
    Pop-Location

    if ($code -ne 0) {
        Write-Host "  FAILED (exit $code)" -ForegroundColor Red
        $results += [pscustomobject]@{ Version = $name; Status = 'FAILED'; Jar = '' }
        continue
    }

    # Collect the runnable jar (never sources/dev jars).
    $jar = Get-ChildItem (Join-Path $dir 'build\libs') -Filter '*.jar' -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch 'sources|dev|javadoc' } |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1

    if (-not $jar) {
        Write-Host '  Build succeeded but no jar was produced' -ForegroundColor Yellow
        $results += [pscustomobject]@{ Version = $name; Status = 'NO JAR'; Jar = '' }
        continue
    }

    Copy-Item $jar.FullName (Join-Path $Dist $jar.Name) -Force
    Write-Host "  OK -> dist\$($jar.Name)" -ForegroundColor Green
    $results += [pscustomobject]@{ Version = $name; Status = 'OK'; Jar = $jar.Name }
}

# --- summary -----------------------------------------------------------------
Write-Host ''
Write-Host '===========================================' -ForegroundColor Cyan
Write-Host ' Build summary' -ForegroundColor Cyan
$results | Format-Table -AutoSize | Out-String | Write-Host

$failed = @($results | Where-Object { $_.Status -ne 'OK' })
if ($failed.Count -gt 0) {
    Write-Host "Failures: $($failed.Version -join ', ')" -ForegroundColor Red
    exit 1
}

Write-Host 'All targets built. Jars are in .\dist' -ForegroundColor Green
