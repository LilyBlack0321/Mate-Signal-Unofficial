# Sets up a clone for development.
#
# Nothing here is required to build: each version project renders the shared
# sources itself through its own `prepareShared` task. This script exists to
# (a) check that the renderer works before you spend time on a full build, and
# (b) create the per-version Gradle wrapper, so you can use `gradlew` instead of
# a system-wide Gradle.
#
# A wrapper cannot live at the repository root because the ports need different
# Gradle versions (8.14.3 for 1.20.1/1.21.1, 4.10.3 for 1.12.2), so one is
# generated inside each version directory.
#
# Usage:
#   .\setup.ps1              # check the renderer, then create wrappers
#   .\setup.ps1 -NoWrapper   # only check the renderer
[CmdletBinding()]
param([switch] $NoWrapper)

$ErrorActionPreference = 'Continue'

$root = $PSScriptRoot
Write-Host 'MateSignal ports - setup' -ForegroundColor Cyan
Write-Host ''

# --- 1. renderer smoke test ---------------------------------------------------
Write-Host '==> Rendering the shared sources'
& (Join-Path $root 'common\tools\render-shared.ps1') -Version 1.21.1 |
    Out-Host
if ($LASTEXITCODE -ne 0) {
    Write-Host 'The renderer failed. Fix that before building.' -ForegroundColor Red
    exit 1
}

if ($NoWrapper) { Write-Host ''; Write-Host 'Done.' -ForegroundColor Green; exit 0 }

# --- 2. per-version gradle wrappers -------------------------------------------
$ports = @(
    @{ Dir = 'versions\v1_20_1'; Gradle = '8.14.3'; EnvVar = 'GRADLE_8_HOME' },
    @{ Dir = 'versions\v1_21_1'; Gradle = '8.14.3'; EnvVar = 'GRADLE_8_HOME' },
    @{ Dir = 'versions\v1_12_2'; Gradle = '4.10.3'; EnvVar = 'GRADLE_LEGACY_HOME' }
)

# Gradle is looked up through the same rules as tools\build-all.ps1: an explicit
# environment variable, a `gradle` on PATH of the right version, then a sibling
# _tools directory.
function Resolve-Gradle([string] $envVar, [string] $wanted) {
    if ((Get-Item "env:$envVar" -ErrorAction SilentlyContinue)) {
        $p = (Get-Item "env:$envVar").Value
        if ($p -and (Test-Path (Join-Path $p 'bin\gradle.bat'))) { return (Join-Path $p 'bin\gradle.bat') }
    }
    $cmd = Get-Command gradle -ErrorAction SilentlyContinue
    if ($cmd) {
        $ver = (& $cmd.Source --version 2>&1 | Select-String '^Gradle [0-9]' | Select-Object -First 1)
        if ($ver -and $ver.ToString().Contains($wanted)) { return $cmd.Source }
    }
    $sibling = Join-Path (Split-Path $root -Parent) "_tools\gradle-$wanted\bin\gradle.bat"
    if (Test-Path $sibling) { return $sibling }
    return $null
}

Write-Host ''
foreach ($port in $ports) {
    $dir = Join-Path $root $port.Dir
    if (Test-Path (Join-Path $dir 'gradlew.bat')) {
        Write-Host ("==> {0}: wrapper already present" -f $port.Dir)
        continue
    }
    $gradle = Resolve-Gradle $port.EnvVar $port.Gradle
    if (-not $gradle) {
        Write-Host ("==> {0}: Gradle {1} not found - set {2}, then re-run" -f
                    $port.Dir, $port.Gradle, $port.EnvVar) -ForegroundColor Yellow
        continue
    }
    Write-Host ("==> {0}: generating wrapper for Gradle {1}" -f $port.Dir, $port.Gradle)
    Push-Location $dir
    & $gradle wrapper --gradle-version $port.Gradle --console=plain
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { Write-Host '    wrapper generation failed' -ForegroundColor Yellow }
}

Write-Host ''
Write-Host 'Setup complete. Build a port with:' -ForegroundColor Green
Write-Host '  cd versions\v1_20_1 ; .\gradlew build'
Write-Host '  cd versions\v1_21_1 ; .\gradlew build'
Write-Host '  cd versions\v1_12_2 ; .\gradlew build   (needs JDK 8)'
Write-Host 'or build everything at once with  .\tools\build-all.ps1'
