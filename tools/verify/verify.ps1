# MateSignal port verifier
#
# Compiles the small verification harness and checks the built jars:
#
#   1. vocabulary  - every documented event exists in each jar's constant pool
#   2. parity      - all ports expose an identical event vocabulary
#   3. live replay - the exact payloads are delivered to udp/127.0.0.1:32145,
#                    the port a MateEngine instance listens on
#
# Usage:
#   .\verify.ps1                 # verify every jar in ..\dist
#   .\verify.ps1 -SkipLive       # skip the UDP replay (e.g. MateEngine running)

[CmdletBinding()]
param(
    [string] $Dist = (Join-Path $PSScriptRoot '..\..\dist'),
    [switch] $SkipLive
)

$ErrorActionPreference = 'Stop'
$here  = $PSScriptRoot
$out   = Join-Path $here 'classes'

# Locates a JDK. JAVA_HOME wins, then a known local install.
$jdkBin = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    Join-Path $env:JAVA_HOME 'bin'
} else {
    'C:\Program Files\Java\jdk-21\bin'
}
$java  = Join-Path $jdkBin 'java.exe'
$javac = Join-Path $jdkBin 'javac.exe'
$jarExe = Join-Path $jdkBin 'jar.exe'
$javap  = Join-Path $jdkBin 'javap.exe'

# The Gradle caches hold the decompiled Minecraft jars that some checks need on
# the classpath, because the mod classes reference Minecraft types in their
# signatures. There is no single cache that serves every port: the 1.12.2 tool
# chain is ForgeGradle 3 on Gradle 4, while 1.20.1 is ForgeGradle 6 on Gradle 8,
# and they keep their mapped jars in separate Gradle homes.
#
# Resolution order per port: an explicit environment variable, then the
# GRADLE_USER_HOME the build used, then the well-known sibling directories of the
# authoring workspace.
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$workspace = Split-Path $repoRoot -Parent

function _PickCache([string] $envVar, [string] $preferred, [string] $sibling) {
    if ((Get-Item "env:$envVar" -ErrorAction SilentlyContinue)) {
        $p = (Get-Item "env:$envVar").Value
        if ($p -and (Test-Path $p)) { return $p }
    }
    if ($preferred -and (Test-Path $preferred)) { return $preferred }
    if ($sibling   -and (Test-Path $sibling))   { return $sibling }
    return $preferred
}

$gradleUserHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME }
                  else { Join-Path $env:USERPROFILE '.gradle' }

$ForgeCaches  = _PickCache 'MATESIGNAL_FORGE_CACHES'  (Join-Path $gradleUserHome 'caches') `
                          (Join-Path $gradleUserHome 'caches')
$LegacyCaches = _PickCache 'MATESIGNAL_LEGACY_CACHES' (Join-Path $gradleUserHome 'caches') `
                          (Join-Path $gradleUserHome 'caches')

if (-not (Test-Path $javac)) {
    Write-Host "No JDK found at $jdkBin - set JAVA_HOME to a JDK 21." -ForegroundColor Red
    exit 1
}

# Finds the first jar matching a pattern below the given directory, or $null.
#
# The directory may be relative to this script or already absolute. The
# distinction matters: Join-Path does NOT replace an absolute child with itself,
# it concatenates - Join-Path 'C:\a' 'D:\b' yields 'C:\a\D:\b'. Passing an
# absolute cache path through Join-Path therefore produced a nonexistent
# directory, silently returned $null for every jar, and surfaced much later as
# NoClassDefFoundError from the harness.
function _FindJar([string] $relDir, [string] $pattern) {
    $dir = if ([System.IO.Path]::IsPathRooted($relDir)) { $relDir } else { Join-Path $here $relDir }
    if (-not (Test-Path $dir)) { return $null }
    $hit = Get-ChildItem $dir -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch 'sources|javadoc' } |
           Select-Object -First 1
    if ($hit) { return $hit.FullName }
    return $null
}

# True when the raw bytes contain the ASCII string (class constant-pool scan).
function Test-BytesAscii([byte[]] $haystack, [string] $needle) {
    $n = [System.Text.Encoding]::ASCII.GetBytes($needle)
    if ($n.Length -eq 0 -or $haystack.Length -lt $n.Length) { return $false }
    for ($i = 0; $i -le $haystack.Length - $n.Length; $i++) {
        $ok = $true
        for ($j = 0; $j -lt $n.Length; $j++) {
            if ($haystack[$i + $j] -ne $n[$j]) { $ok = $false; break }
        }
        if ($ok) { return $true }
    }
    return $false
}

# True when the raw bytes contain the given UTF-8 byte sequence.
function Test-BytesUtf8([byte[]] $haystack, [int[]] $needle) {
    if ($needle.Count -eq 0 -or $haystack.Length -lt $needle.Count) { return $false }
    for ($i = 0; $i -le $haystack.Length - $needle.Count; $i++) {
        $ok = $true
        for ($j = 0; $j -lt $needle.Count; $j++) {
            if ($haystack[$i + $j] -ne $needle[$j]) { $ok = $false; break }
        }
        if ($ok) { return $true }
    }
    return $false
}

New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Host '=== compiling harness ===' -ForegroundColor Cyan
& $javac -encoding UTF-8 -d $out (Join-Path $here 'ClassStrings.java')
& $javac -encoding UTF-8 -cp $out -d $out (Join-Path $here 'ReplayTest.java')
& $javac -encoding UTF-8 -cp $out -d $out (Join-Path $here 'ProtocolTest.java')
& $javac -encoding UTF-8 -cp $out -d $out (Join-Path $here 'CrossingTest.java')
& $javac -encoding UTF-8 -cp $out -d $out (Join-Path $here 'OverrideTest.java')
& $javac -encoding UTF-8 -cp $out -d $out (Join-Path $here 'LangTest.java')
& $javac -encoding UTF-8 -cp $out -d $out (Join-Path $here 'PayloadAudit.java')

# --- day/night crossing logic (compiled port classes, not the jars) ----------
# The detector is private static, so it is exercised reflectively. The mod class
# references Minecraft types in its signatures, hence the mapped Minecraft jars.
Write-Host ''
Write-Host '=== day/night crossing logic ===' -ForegroundColor Cyan
$portSpecs = @(
    @{ Name = '1.20.1'; Classes = '..\..\versions\v1_20_1\build\classes\java\main'
       Jars = @(
         (_FindJar (Join-Path $ForgeCaches 'forge_gradle\minecraft_user_repo') '*mapped_official*.jar'),
         (_FindJar (Join-Path $ForgeCaches 'modules-2') 'fmlcore-*.jar'),
         (_FindJar (Join-Path $ForgeCaches 'modules-2') 'fmlloader-*.jar'),
         (_FindJar (Join-Path $ForgeCaches 'modules-2') 'eventbus-*.jar'),
         (_FindJar (Join-Path $ForgeCaches 'modules-2') 'core-3.6.4.jar'),
         (_FindJar (Join-Path $ForgeCaches 'modules-2') 'toml-3.6.4.jar'),
         # The command API pulls brigadier into the class's signatures.
         (_FindJar (Join-Path $ForgeCaches 'modules-2') 'brigadier-*.jar')) }
    # 1.21.1 is intentionally absent here: NeoForge's runtime classpath is
    # spread across many artifacts, so the class cannot be loaded reliably.
    # It is covered by the bytecode audit below instead.
    @{ Name = '1.12.2'; Classes = '..\..\versions\v1_12_2\build\classes\java\main'
       Jars = @((_FindJar (Join-Path $LegacyCaches 'forge_gradle\minecraft_user_repo') '*mapped*.jar')) }
)

$crossingExit = 0
foreach ($spec in $portSpecs) {
    $cp = ($spec.Jars | Where-Object { $_ } ) -join ';'
    $cls = Join-Path $here $spec.Classes
    if (-not (Test-Path $cls)) {
        Write-Host "  ($($spec.Name): classes not built, skipped)" -ForegroundColor DarkGray
        continue
    }
    & $java "-Dmc.jar=$cp" -cp $out CrossingTest $cls 2>&1 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { $crossingExit = 1 }
}

# --- day/night time sources (bytecode audit) ---------------------------------
# NeoForge's runtime classpath is spread across many artifacts, so the 1.21.1
# class cannot always be loaded reflectively. Instead every port's compiled
# bytecode is checked for the required calls: an always-advancing clock for
# elapsed time and the day-clock for the threshold comparison.
Write-Host ''
Write-Host '=== day/night time sources (bytecode) ===' -ForegroundColor Cyan
$timeChecks = @(
    @{ Name = '1.20.1'; Clock = 'getGameTime';      DayClock = 'getDayTime' }
    @{ Name = '1.21.1'; Clock = 'getGameTime';      DayClock = 'getDayTime' }
    @{ Name = '1.12.2'; Clock = 'getTotalWorldTime'; DayClock = 'getWorldTime' }
)
$timeExit = 0
foreach ($tc in $timeChecks) {
    $dir = switch ($tc.Name) {
        '1.20.1' { '..\..\versions\v1_20_1\build\classes\java\main' }
        '1.21.1' { '..\..\versions\v1_21_1\build\classes\java\main' }
        '1.12.2' { '..\..\versions\v1_12_2\build\classes\java\main' }
    }
    $clsDir = Join-Path $here $dir
    if (-not (Test-Path $clsDir)) { continue }
    $disasm = & $javap -p -c -classpath $clsDir me.shiny.matesignal.MateSignal 2>$null | Out-String
    $hasClock = $disasm -match [regex]::Escape($tc.Clock + ':')
    $hasDay   = $disasm -match [regex]::Escape($tc.DayClock + ':')
    # Guard against shipping the old event name. Match the JSON payload shape
    # rather than the bare word, so the explanatory source comment is ignored.
    $leaked   = $disasm -match '\{"type":"drowning_half'
    $ok = $hasClock -and $hasDay -and (-not $leaked)
    if ($ok) {
        Write-Host ("  ok   {0,-7} uses {1}() + {2}(), emits drowning (not drowning_half)" -f $tc.Name, $tc.Clock, $tc.DayClock)
    } else {
        $timeExit = 1
        Write-Host ("  FAIL {0,-7} clock={1} day={2} drowning_half_leak={3}" -f $tc.Name, $hasClock, $hasDay, $leaked) -ForegroundColor Red
    }
}

# --- localized display names -------------------------------------------------
# MateEngine prints the entity/biome name straight into its bubble, so the mod
# must resolve them through the game language rather than sending registry ids.
# Each port gets its own API, so each is checked for the call it must contain.
Write-Host ''
Write-Host '=== localized display names (bytecode) ===' -ForegroundColor Cyan
$nameChecks = @(
    @{ Name = '1.20.1'
       Dir  = '..\..\versions\v1_20_1\build\classes\java\main'
       Must = @('getDescription', 'biome\.')
       Why  = 'EntityType.getDescription + biome.<ns>.<path>' }
    @{ Name = '1.21.1'
       Dir  = '..\..\versions\v1_21_1\build\classes\java\main'
       Must = @('getDescription', 'biome\.')
       Why  = 'EntityType.getDescription + biome.<ns>.<path>' }
    @{ Name = '1.12.2'
       Dir  = '..\..\versions\v1_12_2\build\classes\java\main'
       Must = @('getTranslationName', 'getBiomeName')
       Why  = 'EntityList.getTranslationName + Biome.getBiomeName' }
)
$nameExit = 0
foreach ($nc in $nameChecks) {
    $clsDir = Join-Path $here $nc.Dir
    if (-not (Test-Path $clsDir)) { continue }
    $namesDisasm = & $javap -p -c -classpath $clsDir me.shiny.matesignal.Names 2>$null | Out-String
    $callerDisasm = & $javap -p -c -classpath $clsDir me.shiny.matesignal.MateSignal 2>$null | Out-String
    $missing = @()
    foreach ($m in $nc.Must) {
        if ($namesDisasm -notmatch $m -and $callerDisasm -notmatch $m) { $missing += $m }
    }
    $resolves = $callerDisasm -match 'me/shiny/matesignal/Names'
    if ($missing.Count -eq 0 -and $resolves) {
        Write-Host ("  ok   {0,-7} {1}" -f $nc.Name, $nc.Why)
    } else {
        $nameExit = 1
        Write-Host ("  FAIL {0,-7} missing={1} usesNames={2}" -f $nc.Name, ($missing -join ','), $resolves) -ForegroundColor Red
    }
}

# --- language tables live in their own class (1.12.2) ------------------------
# The per-language name tables moved out of Names into Lang so that a language
# is a data entry rather than a code path. LangTest and OverrideTest cover the
# contents; this only confirms the split is intact in the compiled output.
Write-Host ''
Write-Host '=== language table class (1.12.2) ===' -ForegroundColor Cyan
$zhExit = 0
$langCls = Join-Path $here '..\..\versions\v1_12_2\build\classes\java\main\me\shiny\matesignal\Lang.class'
$zhNames = Join-Path $here '..\..\versions\v1_12_2\build\classes\java\main\me\shiny\matesignal\Names.class'
if (Test-Path $langCls) {
    $langRaw = [System.IO.File]::ReadAllBytes($langCls)
    $namesRaw = [System.IO.File]::ReadAllBytes($zhNames)
    $hasLangTables = Test-BytesAscii $langRaw 'BIOME_TABLES'
    # U+6CB3 U+6D41 = the Chinese name for River.
    $hasRiverZh = Test-BytesUtf8 $langRaw @(0xE6, 0xB2, 0xB3, 0xE6, 0xB5, 0x81)
    # Names must not carry its own language data any more.
    $namesIsNeutral = -not (Test-BytesAscii $namesRaw 'BIOME_TABLES')
    if ($hasLangTables -and $hasRiverZh -and $namesIsNeutral) {
        Write-Host '  ok   Lang holds the tables; Names stays language-neutral'
    } else {
        Write-Host ('  FAIL langTables={0} riverZh={1} namesNeutral={2}' -f $hasLangTables, $hasRiverZh, $namesIsNeutral) -ForegroundColor Red
        $zhExit = 1
    }
}

# --- biome name override parser (1.12.2) -------------------------------------
# Modded biomes ship no Chinese name, so names can be overridden through a
# user-editable file. Its parser is unit tested against the compiled class.
Write-Host ''
Write-Host '=== biome name override parser (1.12.2) ===' -ForegroundColor Cyan
$ovExit = 0
$ovClasses = Join-Path $here '..\..\versions\v1_12_2\build\classes\java\main'
if (Test-Path $ovClasses) {
    $ovCp = $out
    $mc1122 = _FindJar (Join-Path $LegacyCaches 'forge_gradle\minecraft_user_repo') '*mapped*.jar'
    if ($mc1122) { $ovCp = "$out;$mc1122" }
    & $java -cp $ovCp OverrideTest $ovClasses 2>&1 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { $ovExit = 1 }
}

# --- language gating (1.12.2) -------------------------------------------------
# The mod must stay usable for players of any language, so a language-specific
# name table may only apply when that language is actually selected. This is the
# property that keeps Chinese out of an English player's bubbles.
Write-Host ''
Write-Host '=== language gating (1.12.2) ===' -ForegroundColor Cyan
$langExit = 0
if (Test-Path $ovClasses) {
    $mcJar = _FindJar (Join-Path $LegacyCaches 'forge_gradle\minecraft_user_repo') '*mapped*.jar'
    $langArgs = @($ovClasses)
    if ($mcJar) { $langArgs += $mcJar }
    & $java -cp $out LangTest @langArgs 2>&1 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { $langExit = 1 }
}

# --- modern ports carry no hardcoded language --------------------------------
# 1.20.1 and 1.21.1 resolve every name through the game's own language manager,
# so they are multilingual by construction. Confirm no name table sneaked in.
Write-Host ''
Write-Host '=== modern ports are language-neutral ===' -ForegroundColor Cyan
$neutralExit = 0
foreach ($p in @(@{N='1.20.1'; D='..\..\versions\v1_20_1\build\classes\java\main'},
                 @{N='1.21.1'; D='..\..\versions\v1_21_1\build\classes\java\main'})) {
    $namesCls = Join-Path $here ($p.D + '\me\shiny\matesignal\Names.class')
    if (-not (Test-Path $namesCls)) { continue }
    $raw = [System.IO.File]::ReadAllBytes($namesCls)
    $hasTable = (Test-BytesAscii $raw 'BIOME_ZH') -or (Test-BytesAscii $raw 'ENTITY_ZH')
    $usesI18n = Test-BytesAscii $raw 'I18n'
    if ($usesI18n -and (-not $hasTable)) {
        Write-Host ("  ok   {0,-7} names come from the game language manager only" -f $p.N)
    } else {
        $neutralExit = 1
        Write-Host ("  FAIL {0,-7} usesI18n={1} hasHardcodedTable={2}" -f $p.N, $usesI18n, $hasTable) -ForegroundColor Red
    }
}

# --- event bus registration ---------------------------------------------------
# Gameplay events must go on the GAME bus; the mod bus only accepts
# IModBusEvent subclasses. Getting this wrong throws during mod construction and
# the game never opens its window, which no amount of metadata checking catches.
# This check was added after exactly that crash on 1.21.1.
Write-Host ''
Write-Host '=== event bus registration (bytecode) ===' -ForegroundColor Cyan
$busChecks = @(
    @{ N = '1.20.1'; D = '..\..\versions\v1_20_1\build\classes\java\main'
       GameBus = 'net/minecraftforge/common/MinecraftForge.EVENT_BUS' }
    @{ N = '1.21.1'; D = '..\..\versions\v1_21_1\build\classes\java\main'
       GameBus = 'net/neoforged/neoforge/common/NeoForge.EVENT_BUS' }
    @{ N = '1.12.2'; D = '..\..\versions\v1_12_2\build\classes\java\main'
       GameBus = 'net/minecraftforge/common/MinecraftForge.EVENT_BUS' }
)
$busExit = 0
foreach ($bc in $busChecks) {
    $dir = Join-Path $here $bc.D
    if (-not (Test-Path $dir)) { continue }
    $dis = & $javap -p -c -classpath $dir me.shiny.matesignal.MateSignal 2>$null | Out-String
    # The game bus must be loaded before the listener is handed to a bus.
    $loadsGameBus = $dis -match [regex]::Escape($bc.GameBus)
    # NeoForge registers via addListener; the Forge ports register the whole
    # listener object via register(). Both are correct on the game bus.
    $registers = ($dis -match 'IEventBus\.addListener') -or ($dis -match 'IEventBus\.register') `
                 -or ($dis -match 'EventBus\.register')
    # Guard the specific mistake that crashed 1.21.1: handing the constructor's
    # injected mod bus to the registration call.
    $usesModBus = $dis -match 'aload_1[\s\S]{0,120}?(IEventBus\.addListener|EventBus\.register|IEventBus\.register)'
    if ($loadsGameBus -and $registers -and (-not $usesModBus)) {
        Write-Host ("  ok   {0,-7} listener registered on the game bus" -f $bc.N)
    } else {
        $busExit = 1
        Write-Host ("  FAIL {0,-7} gameBus={1} registers={2} modBusUsed={3}" -f $bc.N, $loadsGameBus, $registers, $usesModBus) -ForegroundColor Red
    }
}

# --- tick wiring ---------------------------------------------------------------
# Registering on the right bus is only half the job: the handler also has to call
# the code that does the work. Merging the two ports into one shared source tree
# produced a 1.21.1 handler whose client-side branch was empty -
#
#     if (!event.getLevel().isClientSide()) { return; }
#     onClientTick();                            <- this line was missing
#
# - which compiles cleanly, registers correctly, and silently sends nothing. This
# check asserts the wiring by bytecode, not by compilation.
Write-Host ''
Write-Host '=== tick wiring (bytecode) ===' -ForegroundColor Cyan
$wireChecks = @(
    @{ N = '1.20.1'; D = '..\..\versions\v1_20_1\build\classes\java\main'; Handler = 'onClientTick\(net\.minecraftforge' }
    @{ N = '1.21.1'; D = '..\..\versions\v1_21_1\build\classes\java\main'; Handler = 'onLevelTick\(net\.neoforged' }
    @{ N = '1.12.2'; D = '..\..\versions\v1_12_2\build\classes\java\main'; Handler = 'onClientTick\(net\.minecraftforge' }
)
$wireExit = 0
foreach ($wc in $wireChecks) {
    $dir = Join-Path $here $wc.D
    if (-not (Test-Path $dir)) { continue }
    $dis = & $javap -p -c -classpath $dir me.shiny.matesignal.MateSignal 2>$null | Out-String

    # The event handler must actually call the tick worker. Match its body up to
    # the next member declaration and require an onClientTick call inside it.
    $handler = [regex]::Match($dis, "(?s)\s$($wc.Handler).*?(?=\n  [a-z@])")
    $handlerCalls = $handler.Success -and ($handler.Value -match 'onClientTick')

    # And the tick path must drain the queue that /matesignal test feeds. Two
    # shapes are valid: a separate no-arg worker (1.20.1, 1.21.1, where the event
    # signature forces the split) or the handler itself (1.12.2, whose handler
    # takes the tick event directly).
    $worker = [regex]::Match($dis, '(?s)private void onClientTick\(\);.*?(?=\n  [a-z@])')
    if (-not $worker.Success) { $worker = [regex]::Match($dis, '(?s)void onClientTick\(\);.*?(?=\n  [a-z@])') }
    $workerQueues = $handler.Success -and ($handler.Value -match 'tickTestQueue')
    if ($worker.Success) { $workerQueues = $workerQueues -or ($worker.Value -match 'tickTestQueue') }

    if ($handlerCalls -and $workerQueues) {
        Write-Host ("  ok   {0,-7} handler reaches onClientTick(), which drains the test queue" -f $wc.N)
    } else {
        $wireExit = 1
        Write-Host ("  FAIL {0,-7} handlerCallsWorker={1} workerDrainsQueue={2}" -f $wc.N, $handlerCalls, $workerQueues) -ForegroundColor Red
    }
}

$jars = Get-ChildItem $Dist -Filter 'matesignal-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|dev' } |
        Sort-Object Name

if (-not $jars) {
    Write-Host "no jars found in $Dist" -ForegroundColor Red
    exit 1
}

# --- license compliance -------------------------------------------------------
# MateSignal is distributed under the MateEngine Pro License v2.0, whose
# sections 3 and 4 require derivative works to stay under the same license,
# remain free of charge, attribute the original authors, and publish their
# source. This check was added after the ports carried an incorrect
# license="MIT" copied from the upstream build metadata.
Write-Host ''
Write-Host '=== license compliance ===' -ForegroundColor Cyan
$licenseExit = 0
$unresolved = @()
foreach ($jar in $jars) {
    $tmp = Join-Path $env:TEMP ("ms-lic-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Push-Location $tmp
    & $jarExe --extract --file $jar.FullName 2>$null
    Pop-Location

    $hasLicenseText = Test-Path (Join-Path $tmp 'LICENSE.md')
    $hasCredits     = Test-Path (Join-Path $tmp 'CREDITS.txt')

    $metaFiles = @()
    foreach ($rel in @('META-INF\mods.toml', 'META-INF\neoforge.mods.toml', 'mcmod.info')) {
        $p = Join-Path $tmp $rel
        if (Test-Path $p) { $metaFiles += $p }
    }

    $metaText = ''
    foreach ($m in $metaFiles) { $metaText += [System.IO.File]::ReadAllText($m) }

    $licenseOk   = $metaText -match 'MateEngine Pro License v2\.0'
    $noMit       = -not ($metaText -match '(?i)license\s*[=:]\s*"MIT"')
    $attribution = ($metaText -match 'shinyflvre/Mate-Signal') -and ($metaText -match 'VeridonNetzwerk')
    $unofficial  = $metaText -match 'UNOFFICIAL'
    # Section 4 requires a public source link; a placeholder means it is not set.
    if ($metaText -match 'REPLACE_WITH_YOUR_PUBLIC_SOURCE_URL') {
        $unresolved += $jar.Name
    }

    if ($hasLicenseText -and $hasCredits -and $licenseOk -and $noMit -and $attribution -and $unofficial) {
        Write-Host ("  ok   {0}" -f $jar.Name)
    } else {
        $licenseExit = 1
        Write-Host ("  FAIL {0} licenseText={1} credits={2} licenseOk={3} noMIT={4} attribution={5} unofficial={6}" `
            -f $jar.Name, $hasLicenseText, $hasCredits, $licenseOk, $noMit, $attribution, $unofficial) -ForegroundColor Red
    }
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

if ($unresolved.Count -gt 0) {
    Write-Host ''
    Write-Host '  NOTE: the public source URL is still a placeholder in:' -ForegroundColor Yellow
    foreach ($u in $unresolved) { Write-Host "        $u" -ForegroundColor Yellow }
    Write-Host '        MateEngine Pro License v2.0 section 4 requires the complete source' -ForegroundColor Yellow
    Write-Host '        to be published and linked before distributing the mod.' -ForegroundColor Yellow
    Write-Host '        Set mod_description / mcmod.info url, then rebuild.' -ForegroundColor Yellow
}

Write-Host ''
Write-Host '=== vocabulary parity (constant-pool audit) ===' -ForegroundColor Cyan
$paths = $jars | ForEach-Object { $_.FullName }
& $java -cp $out ReplayTest --compare @paths
$parityExit = $LASTEXITCODE

# The /matesignal test command duplicates the payload literals, which is the
# kind of duplication that rots silently. Compare the two sets per jar.
Write-Host ''
Write-Host '=== test command payload table ===' -ForegroundColor Cyan
$payloadExit = 0
foreach ($jar in $jars) {
    $auditOut = & $java -cp $out PayloadAudit $jar.FullName 2>&1
    $auditOut | Where-Object { $_ -match '^\s+(ok|FAIL)' } | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) {
        $payloadExit = 1
        Write-Host ("  FAIL {0}" -f $jar.Name) -ForegroundColor Red
        $auditOut | ForEach-Object { Write-Host "    $_" }
    } else {
        Write-Host ("  ok   {0}" -f $jar.Name)
    }
}

if (-not $SkipLive) {
    Write-Host ''
    Write-Host '=== live UDP replay to 127.0.0.1:32145 ===' -ForegroundColor Cyan
    Write-Host '(MateEngine must be closed; it owns that port)' -ForegroundColor DarkGray
    foreach ($jar in $jars) {
        & $java -cp $out ReplayTest $jar.FullName
        Write-Host ''
    }
}

Write-Host '=== per-jar metadata ===' -ForegroundColor Cyan
foreach ($jar in $jars) {
    $tmp = Join-Path $env:TEMP ("matesignal-verify-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Push-Location $tmp
    & $jarExe --extract --file $jar.FullName 2>$null
    Pop-Location

    $meta = @()
    if (Test-Path (Join-Path $tmp 'META-INF\mods.toml'))          { $meta += 'META-INF/mods.toml' }
    if (Test-Path (Join-Path $tmp 'META-INF\neoforge.mods.toml')) { $meta += 'META-INF/neoforge.mods.toml' }
    if (Test-Path (Join-Path $tmp 'mcmod.info'))                  { $meta += 'mcmod.info' }
    if (Test-Path (Join-Path $tmp 'fabric.mod.json'))             { $meta += 'fabric.mod.json' }

    $major = & $javap -v -classpath $tmp me.shiny.matesignal.MateSignal 2>$null |
             Select-String 'major version' | Select-Object -First 1
    Write-Host ("{0,-46} meta=[{1}] {2}" -f $jar.Name, ($meta -join ', '), ($major -replace '\s+', ' ').Trim())
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

Write-Host ''
if ($parityExit -eq 0 -and $crossingExit -eq 0 -and $timeExit -eq 0 -and $nameExit -eq 0 `
        -and $zhExit -eq 0 -and $ovExit -eq 0 -and $langExit -eq 0 -and $neutralExit -eq 0 `
        -and $busExit -eq 0 -and $wireExit -eq 0 -and $payloadExit -eq 0 -and $licenseExit -eq 0) {
    Write-Host 'VERIFY OK' -ForegroundColor Green
} else {
    Write-Host 'VERIFY FAILED' -ForegroundColor Red
    exit 1
}

