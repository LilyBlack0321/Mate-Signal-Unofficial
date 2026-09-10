# Renders common/src/main for one Minecraft version, without building anything.
#
# This is the same work the `prepareShared` Gradle task does at the start of every
# build; it is split out so the renderer can be run and inspected on its own, and
# so setup.ps1 can smoke-test it before a full build.
#
# Usage:
#   .\render-shared.ps1                       # render 1.21.1 into common/build/gen-1.21.1
#   .\render-shared.ps1 -Version 1.20.1
#   .\render-shared.ps1 -Version 1.20.1 -Out C:\tmp\gen
#
# JAVA_HOME must point at a JDK 17+ (any of them can run the renderer, which is
# plain Java; the rendered output is what has to match the target's language
# level).
[CmdletBinding()]
param(
    [ValidateSet('1.20.1', '1.21.1')]
    [string] $Version = '1.21.1',
    [string] $Out
)

$ErrorActionPreference = 'Continue'

$common    = Split-Path $PSScriptRoot -Parent              # <repo>\common
$sharedSrc = Join-Path $common 'src\main'
$buildDir  = Join-Path $common 'build'
$classes   = Join-Path $buildDir 'renderer-classes'
if (-not $Out) { $Out = Join-Path $buildDir "gen-$Version" }

# Locate a JDK. JAVA_HOME wins; otherwise fall back to a `java` on PATH.
$jdk = $env:JAVA_HOME
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) {
    $jc = Get-Command javac -ErrorAction SilentlyContinue
    if ($jc) { $jdk = Split-Path (Split-Path $jc.Source -Parent) -Parent }
}
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) {
    Write-Host 'No JDK found. Set JAVA_HOME to a JDK 17 or newer.' -ForegroundColor Red
    exit 1
}
$javac = Join-Path $jdk 'bin\javac.exe'
$java  = Join-Path $jdk 'bin\java.exe'

New-Item -ItemType Directory -Force -Path $classes | Out-Null
Remove-Item -Recurse -Force (Join-Path $classes '*') -ErrorAction SilentlyContinue

Write-Host "Compiling the renderer with $jdk"
& $javac -encoding UTF-8 -d $classes (Join-Path $PSScriptRoot 'PrepareShared.java')
if ($LASTEXITCODE -ne 0) { Write-Host 'PrepareShared.java did not compile' -ForegroundColor Red; exit 1 }

Remove-Item -Recurse -Force $Out -ErrorAction SilentlyContinue
Write-Host "Rendering $Version -> $Out"
& $java -cp $classes PrepareShared $Version $sharedSrc $Out
if ($LASTEXITCODE -ne 0) { Write-Host 'PrepareShared failed' -ForegroundColor Red; exit 1 }

$javaFiles = @(Get-ChildItem (Join-Path $Out 'java') -Recurse -Filter '*.java' -ErrorAction SilentlyContinue)
$resFiles  = @(Get-ChildItem (Join-Path $Out 'resources') -Recurse -File -ErrorAction SilentlyContinue)
Write-Host ("  {0} source file(s), {1} resource file(s)" -f $javaFiles.Count, $resFiles.Count) -ForegroundColor Green

# Sanity: the renderer must always produce these two, or the build will fail with
# a far less obvious message.
foreach ($rel in @('java\me\shiny\matesignal\MateSignal.java', 'resources\LICENSE.md')) {
    if (-not (Test-Path (Join-Path $Out $rel))) {
        Write-Host "  MISSING $rel" -ForegroundColor Red
        exit 1
    }
}
