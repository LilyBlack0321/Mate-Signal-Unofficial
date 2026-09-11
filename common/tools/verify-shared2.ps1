# Verifies the shared source tree renders correctly.
#
# Two different checks, because the two versions need different evidence:
#
#   1.20.1 - byte-for-byte comparison against ports/forge-1.20.1, which is the
#            tree that passed the full verification suite. Equality here proves
#            the marker annotations did not alter 1.20.1 in any way. That
#            reference only exists in the authoring workspace; in a plain clone
#            the step is skipped.
#
#   1.21.1 - the ports/neoforge-1.21.1 tree is NOT a reliable reference: it was
#            hand-maintained and drifted (its config screen still used the 1.20.1
#            ForgeRegistries call). Correctness is therefore established by
#            compiling the rendered tree instead, which is the property that
#            actually matters. `gradlew build` in versions/v1_21_1 does the same
#            thing and needs no cache pre-population.
#
# Neither check is required to build: the prepareShared Gradle task in each
# version project renders the tree on every build. This script exists to inspect
# the renderer on its own.
#
# Usage: verify-shared2.ps1   (set GRADLE_USER_HOME first to point at the Gradle
#                              cache that holds the NeoForge jars)
#
# Note: ErrorActionPreference stays at Continue for the javac call below. javac
# writes deprecation notes to stderr, and under 'Stop' PowerShell turns that into
# a terminating NativeCommandError even though the compile succeeded. Gradle-based
# scripts in this repo work around the same behaviour the same way.
$ErrorActionPreference = 'Stop'

$Here      = Split-Path $PSScriptRoot -Parent
$M         = Join-Path (Split-Path $Here -Parent) 'ports'
$jdk       = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $env:JAVA_HOME }
             else { 'C:\Program Files\Java\jdk-21' }
$javac     = Join-Path $jdk 'bin\javac.exe'
$java      = Join-Path $jdk 'bin\java.exe'
$buildDir  = Join-Path $Here 'build'
$sharedSrc = Join-Path $Here 'src\main'
$netBase   = if ($env:GRADLE_USER_HOME) { Join-Path $env:GRADLE_USER_HOME 'caches' }
             else { Join-Path $env:USERPROFILE '.gradle\caches' }

if (-not (Test-Path $javac)) { throw "No JDK found (looked for $javac); set JAVA_HOME" }

New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
& $javac -encoding UTF-8 -d $buildDir (Join-Path $PSScriptRoot 'PrepareShared.java')
if ($LASTEXITCODE -ne 0) { throw 'PrepareShared failed to compile' }

# --- 1.20.1 : strict byte comparison ----------------------------------------
$gen20 = Join-Path $buildDir 'gen-1.20.1'
Remove-Item -Recurse -Force $gen20 -ErrorAction SilentlyContinue
& $java -cp $buildDir PrepareShared '1.20.1' $sharedSrc $gen20
if ($LASTEXITCODE -ne 0) { throw 'PrepareShared failed for 1.20.1' }

Write-Host ''
Write-Host '=== 1.20.1 : rendered vs verified port (byte-for-byte) ===' -ForegroundColor Cyan
# This comparison only works in the authoring workspace, where ports/forge-1.20.1
# is still present. In a plain clone the tree is not there, so the check is
# skipped rather than reported as a failure - the 1.21.1 compile below is the
# check that actually generalises.
if (-not (Test-Path $M)) {
    Write-Host "  skipped: reference tree $M not present" -ForegroundColor Yellow
    $mismatch20 = 0
    $skipped20 = $true
} else {
    $skipped20 = $false
    $ref20 = Join-Path $M 'forge-1.20.1\src\main'
    $mismatch20 = 0
    foreach ($gen in Get-ChildItem $gen20 -Recurse -File) {
        $rel = $gen.FullName.Substring($gen20.Length + 1)
        $ref = Join-Path $ref20 $rel
        if (-not (Test-Path $ref)) { Write-Host "  MISSING $rel" -ForegroundColor Yellow; $mismatch20++; continue }
        $a = [System.IO.File]::ReadAllBytes($gen.FullName)
        $b = [System.IO.File]::ReadAllBytes($ref)
        $same = ($a.Length -eq $b.Length)
        if ($same) { for ($i = 0; $i -lt $a.Length; $i++) { if ($a[$i] -ne $b[$i]) { $same = $false; break } } }
        if ($same) { Write-Host "  ok   $rel" } else { Write-Host "  DIFF $rel" -ForegroundColor Red; $mismatch20++ }
    }
}

# --- 1.21.1 : compile the rendered tree -------------------------------------
$gen21 = Join-Path $buildDir 'gen-1.21.1'
Remove-Item -Recurse -Force $gen21 -ErrorAction SilentlyContinue
& $java -cp $buildDir PrepareShared '1.21.1' $sharedSrc $gen21
if ($LASTEXITCODE -ne 0) { throw 'PrepareShared failed for 1.21.1' }

Write-Host ''
Write-Host '=== 1.21.1 : rendered tree compiles against NeoForge ===' -ForegroundColor Cyan

$cp = @()
$cp += (Get-ChildItem $netBase -Recurse -Filter 'compiledWithNeoForge*.jar' -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName)
$cp += (Get-ChildItem $netBase -Recurse -Filter 'neoforge-*-universal.jar' -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName)
foreach ($pattern in @('bus-*.jar', 'loader-*.jar', 'core-3.*.jar', 'brigadier-*.jar',
                       'datafixerupper-*.jar', 'guava-*.jar', 'gson-*.jar', 'authlib-*.jar')) {
    $hit = Get-ChildItem $netBase -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch 'sources' } | Select-Object -First 1 -ExpandProperty FullName
    if ($hit) { $cp += $hit }
}
$cpj = ($cp | Where-Object { $_ }) -join ';'
if (-not $cpj) {
    Write-Host ''
    Write-Host '  skipped: NeoForge jars not found in a Gradle cache, so the render' -ForegroundColor Yellow
    Write-Host '           cannot be compiled here. Run `gradlew build` in' -ForegroundColor Yellow
    Write-Host '           versions/v1_21_1 instead - that is the same check.' -ForegroundColor Yellow
    Write-Host ''
    if ($mismatch20 -eq 0) { Write-Host 'SHARED TREE OK (render only)' -ForegroundColor Green; exit 0 }
    Write-Host 'SHARED TREE FAILED' -ForegroundColor Red
    exit 1
}

$classes21 = Join-Path $buildDir 'classes-1.21.1'
Remove-Item -Recurse -Force $classes21 -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes21 | Out-Null
$sources = (Get-ChildItem (Join-Path $gen21 'java') -Recurse -Filter '*.java').FullName
# See the note at the top of this file about stderr and ErrorActionPreference.
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$compileOutput = & $javac -encoding UTF-8 -nowarn -cp $cpj -d $classes21 $sources 2>&1
$compileExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap
$compileOk = ($compileExit -eq 0)

if ($compileOk) {
    $count = (Get-ChildItem $classes21 -Recurse -Filter '*.class').Count
    Write-Host "  ok   compiled $count class(es) with no errors"
} else {
    Write-Host '  FAIL the rendered 1.21.1 tree does not compile' -ForegroundColor Red
    $compileOutput | Select-Object -First 12 | ForEach-Object { Write-Host "    $_" }
}

Write-Host ''
if ($mismatch20 -eq 0 -and $compileOk) {
    Write-Host 'SHARED TREE OK' -ForegroundColor Green
    if ($skipped20) {
        Write-Host '  1.20.1 rendered without errors (no reference tree to compare against)'
    } else {
        Write-Host '  1.20.1 reproduces the verified port byte-for-byte'
    }
    Write-Host '  1.21.1 renders a tree that compiles against NeoForge'
} else {
    Write-Host 'SHARED TREE FAILED' -ForegroundColor Red
    exit 1
}
