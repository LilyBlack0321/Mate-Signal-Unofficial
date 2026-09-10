# Annotates the shared source tree with per-version markers.
#
# !! ONE-SHOT, ALREADY APPLIED - do not run this again. !!
#
# The annotations are committed in common/src/main; this script records how they
# were produced and is kept for reference only. Re-running it against the current
# tree fails: it expects the *unannotated* 1.20.1 text, which is what it looks
# for, and the committed text already has the markers in place. It exits with an
# explicit "expected N match(es) ... but found 0" error rather than corrupting
# anything, but there is no reason to run it.
#
# If you ever need to change a divergence, edit common/src/main directly: the
# markers are part of the source now, and PrepareShared renders them.
#
# The shared tree started as a copy of the 1.20.1 sources. This rewrote each of
# the API differences between the two ports as an //#if / //#else / //#endif
# block. There are only a handful; see README section 12 for the list.
#
# Two implementation notes, both learned the hard way:
#   * Newlines are built from [char]10 rather than escape sequences inside
#     strings, so the script behaves identically no matter how the file is saved.
#   * Blocks are passed as string arrays and joined here, because PowerShell
#     collapses a single-element array into a space-joined string when it is
#     handed to a .NET method, which silently destroys multi-line matches.
#
# Every edit asserts that its text was actually found, so drift fails loudly.
#
# Usage: annotate-shared.ps1
$ErrorActionPreference = 'Stop'

$LF   = [string][char]10
$Here = Split-Path $PSScriptRoot -Parent          # ...\repo\common
$Dir  = Join-Path $Here 'src\main\java\me\shiny\matesignal'
$enc  = New-Object System.Text.UTF8Encoding($false)

function Edit([string] $File, [string] $Old, [string] $New, [int] $expected = 1) {
    $path = Join-Path $Dir $File
    $t = [System.IO.File]::ReadAllText($path)
    $count = ([regex]::Matches($t, [regex]::Escape($Old))).Count
    if ($count -ne $expected) {
        $preview = $Old.Substring(0, [Math]::Min(70, $Old.Length)) -replace "`n", '\n'
        throw "$File : expected $expected match(es) of <$preview> but found $count"
    }
    # All occurrences are wrapped, not just the first. An earlier revision
    # replaced only the first when $expected -gt 1, which left the second call
    # site unmarked and produced a 1.21.1 tree that did not compile.
    [System.IO.File]::WriteAllText($path, $t.Replace($Old, $New), $enc)
}

# Wraps a 1.20.1-only block and its 1.21.1 counterpart.
function Mark([string] $File, [string[]] $OldA, [string[]] $OldB, [int] $expected = 1) {
    $a = $OldA -join $LF
    $b = $OldB -join $LF
    Edit $File $a ('//#if 1.20.1' + $LF + $a + $LF + '//#else' + $LF + $b + $LF + '//#endif') $expected
}

Write-Host 'Annotating the shared tree:'

# --- Config.java : ForgeConfigSpec vs ModConfigSpec ---------------------------
# Rendered in one pass rather than by repeated String.Replace, because the
# replacement text itself contains the token being replaced; doing it in stages
# nested a marker block inside itself twice before this was fixed.
$cfgPath = Join-Path $Dir 'Config.java'
$cfgLines = [System.IO.File]::ReadAllLines($cfgPath)
$cfgOut = New-Object System.Collections.Generic.List[string]
$cfgTypes = 0
$cfgImport = 0
$token = 'ForgeConfigSpec'
foreach ($line in $cfgLines) {
    if ($line -ceq 'import net.minecraftforge.common.ForgeConfigSpec;') {
        $cfgImport++
        $cfgOut.Add('//#if 1.20.1')
        $cfgOut.Add($line)
        $cfgOut.Add('//#else')
        $cfgOut.Add('import net.neoforged.neoforge.common.ModConfigSpec;')
        $cfgOut.Add('//#endif')
        continue
    }
    # A single line can reference the type twice (the builder line does), so walk
    # the occurrences rather than assuming one per line.
    $hits = ([regex]::Matches($line, [regex]::Escape($token))).Count
    if ($hits -eq 0) { $cfgOut.Add($line); continue }
    $cfgTypes += $hits
    $cfgOut.Add('//#if 1.20.1')
    $cfgOut.Add($line)
    $cfgOut.Add('//#else')
    $cfgOut.Add($line.Replace($token, 'ModConfigSpec'))
    $cfgOut.Add('//#endif')
}
if ($cfgTypes -ne 17 -or $cfgImport -ne 1) {
    throw "Config.java : expected 17 type references and 1 import, found $cfgTypes and $cfgImport"
}
[System.IO.File]::WriteAllLines($cfgPath, $cfgOut, $enc)
Write-Host '  Config.java                 : config spec type (17 uses + import)'

# --- Names.java : Biome import is 1.20.1-only ---------------------------------
Edit 'Names.java' 'import net.minecraft.world.level.biome.Biome;' `
    ('//#if 1.20.1' + $LF + 'import net.minecraft.world.level.biome.Biome;' + $LF + '//#endif')
Write-Host '  Names.java                  : biome import'

# --- TestPayloads.java : entityName() takes a Player only on 1.20.1 -----------
Mark 'TestPayloads.java' @('            out = out.replace(ENTITY, entityName(player));') @('            out = out.replace(ENTITY, entityName());')
Mark 'TestPayloads.java' @('    private static String entityName(Player player) {') @('    private static String entityName() {')
Write-Host '  TestPayloads.java           : entityName signature'

# --- MateSignalConfigScreen.java : registry access + scroll arity -------------
Mark 'MateSignalConfigScreen.java' `
    @('import net.minecraftforge.registries.ForgeRegistries;') `
    @('import net.minecraft.core.registries.BuiltInRegistries;')
Mark 'MateSignalConfigScreen.java' `
    @('        List<EntityType<?>> all = new ArrayList<>(ForgeRegistries.ENTITY_TYPES.getValues());') `
    @('List<EntityType<?>> all = new ArrayList<>();',
      '        BuiltInRegistries.ENTITY_TYPE.forEach(all::add);')
# Two call sites exist (mobile-list build and the per-entry lookup), so both are
# wrapped; leaving the second one unmarked is what made the rendered 1.21.1 tree
# fail to compile the first time.
Edit 'MateSignalConfigScreen.java' `
    'ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(t);' `
    ('//#if 1.20.1' + $LF + '            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(t);' + $LF + '//#else' + $LF + '            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(t);' + $LF + '//#endif') 2
Mark 'MateSignalConfigScreen.java' `
    @('    public boolean mouseScrolled(double mx, double my, double dy) {') `
    @('    public boolean mouseScrolled(double mx, double my, double dx, double dy) {')
Write-Host '  MateSignalConfigScreen.java : registry + scroll'

# --- MateSignal.java : entry point, event bus, food test, entity lookup -------
$impA = @(
'import net.minecraft.core.registries.Registries;'
'import net.minecraft.commands.CommandSourceStack;'
'import net.minecraft.commands.Commands;'
'import net.minecraft.network.chat.Component;'
'import net.minecraft.resources.ResourceKey;'
'import net.minecraft.resources.ResourceLocation;'
'import net.minecraft.world.damagesource.DamageSource;'
'import net.minecraft.world.entity.Entity;'
'import net.minecraft.world.entity.EntityType;'
'import net.minecraft.world.entity.LivingEntity;'
'import net.minecraft.world.entity.MobCategory;'
'import net.minecraft.world.entity.player.Player;'
'import net.minecraft.world.entity.projectile.Projectile;'
'import net.minecraft.world.food.FoodData;'
'import net.minecraft.world.inventory.AbstractContainerMenu;'
'import net.minecraft.world.item.ItemStack;'
'import net.minecraft.world.level.Level;'
'import net.minecraft.world.phys.AABB;'
'import net.minecraftforge.client.ConfigScreenHandler;'
'import net.minecraftforge.client.event.RegisterClientCommandsEvent;'
'import net.minecraftforge.common.MinecraftForge;'
'import net.minecraftforge.event.TickEvent;'
'import net.minecraftforge.eventbus.api.SubscribeEvent;'
'import net.minecraftforge.fml.ModLoadingContext;'
'import net.minecraftforge.fml.common.Mod;'
'import net.minecraftforge.fml.config.ModConfig;'
'import net.minecraftforge.registries.ForgeRegistries;'
)
$impB = @(
'import net.minecraft.commands.CommandSourceStack;'
'import net.minecraft.commands.Commands;'
'import net.minecraft.network.chat.Component;'
'import net.minecraft.resources.ResourceKey;'
'import net.minecraft.resources.ResourceLocation;'
'import net.minecraft.world.damagesource.DamageSource;'
'import net.minecraft.world.entity.Entity;'
'import net.minecraft.world.entity.EntityType;'
'import net.minecraft.world.entity.LivingEntity;'
'import net.minecraft.world.entity.MobCategory;'
'import net.minecraft.world.entity.player.Player;'
'import net.minecraft.world.entity.projectile.Projectile;'
'import net.minecraft.world.food.FoodData;'
'import net.minecraft.world.inventory.AbstractContainerMenu;'
'import net.minecraft.world.item.ItemStack;'
'import net.minecraft.world.level.Level;'
'import net.minecraft.world.phys.AABB;'
'import net.minecraft.core.registries.BuiltInRegistries;'
'import net.neoforged.bus.api.IEventBus;'
'import net.neoforged.fml.ModContainer;'
'import net.neoforged.fml.common.Mod;'
'import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;'
'import net.neoforged.neoforge.client.gui.IConfigScreenFactory;'
'import net.neoforged.neoforge.common.NeoForge;'
'import net.neoforged.neoforge.event.tick.LevelTickEvent;'
)
Edit 'MateSignal.java' ($impA -join $LF) ('//#if 1.20.1' + $LF + ($impA -join $LF) + $LF + '//#else' + $LF + ($impB -join $LF) + $LF + '//#endif')

$ctorA = @(
'    public MateSignal() {'
'        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.SPEC);'
'        ModLoadingContext.get().registerExtensionPoint('
'                ConfigScreenHandler.ConfigScreenFactory.class,'
'                () -> new ConfigScreenHandler.ConfigScreenFactory('
'                        (mc, parent) -> new MateSignalConfigScreen(parent)));'
''
'        // Forge 47 exposes the client tick through the classic event-bus form'
'        // (TickEvent.ClientTickEvent on the global forge bus); the ".Post"'
'        // sub-event only exists from 1.21 onwards.'
'        MinecraftForge.EVENT_BUS.register(this);'
'    }'
)
$ctorB = @(
'    public MateSignal(IEventBus modEventBus, ModContainer modContainer) {'
'        modContainer.registerConfig('
'                net.neoforged.fml.config.ModConfig.Type.CLIENT, Config.SPEC);'
'        modContainer.registerExtensionPoint('
'                IConfigScreenFactory.class,'
'                (IConfigScreenFactory) (mc, parent) -> new MateSignalConfigScreen(parent));'
''
'        // NeoForge has two buses and they are not interchangeable: the mod bus'
'        // accepts only IModBusEvent subclasses, while gameplay events such as'
'        // LevelTickEvent.Post live on the game bus.'
'        NeoForge.EVENT_BUS.addListener(this::onLevelTick);'
'        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);'
'    }'
)
Mark 'MateSignal.java' $ctorA $ctorB

Mark 'MateSignal.java' `
    @('    @SubscribeEvent', '    public void onRegisterCommands(RegisterClientCommandsEvent event) {') `
    @('    private void onRegisterCommands(RegisterClientCommandsEvent event) {')
Mark 'MateSignal.java' `
    @('    @SubscribeEvent', '    public void onClientTick(TickEvent.ClientTickEvent event) {') `
    @('    private void onLevelTick(LevelTickEvent.Post event) {')
Mark 'MateSignal.java' `
    @('        if (event.phase != TickEvent.Phase.END) {', '            return;', '        }', '        onClientTick();') `
    @('        if (!event.getLevel().isClientSide()) {', '            return;', '        }')
Mark 'MateSignal.java' `
    @('        boolean usingFoodNow = p.isUsingItem() && !use.isEmpty() && use.isEdible();') `
    @('        boolean usingFoodNow = p.isUsingItem() && !use.isEmpty()',
      '                && use.has(net.minecraft.core.component.DataComponents.FOOD);')
Edit 'MateSignal.java' 'List<Entity> ents = level.getEntities(null, box);' `
    ('//#if 1.20.1' + $LF + '        List<Entity> ents = level.getEntities(null, box);' + $LF + '//#else' + $LF + '        List<Entity> ents = level.getEntities((Entity) null, box);' + $LF + '//#endif')
Edit 'MateSignal.java' 'ResourceLocation rid = ForgeRegistries.ENTITY_TYPES.getKey(type);' `
    ('//#if 1.20.1' + $LF + '            ResourceLocation rid = ForgeRegistries.ENTITY_TYPES.getKey(type);' + $LF + '//#else' + $LF + '            ResourceLocation rid = BuiltInRegistries.ENTITY_TYPE.getKey(type);' + $LF + '//#endif')
Edit 'MateSignal.java' 'ResourceLocation rid = ForgeRegistries.ENTITY_TYPES.getKey(le.getType());' `
    ('//#if 1.20.1' + $LF + '            ResourceLocation rid = ForgeRegistries.ENTITY_TYPES.getKey(le.getType());' + $LF + '//#else' + $LF + '            ResourceLocation rid = BuiltInRegistries.ENTITY_TYPE.getKey(le.getType());' + $LF + '//#endif')
Write-Host '  MateSignal.java             : entry point + bus + APIs'

Write-Host ''
Write-Host 'Done. Run verify-shared.ps1 to confirm both versions render identically.'
