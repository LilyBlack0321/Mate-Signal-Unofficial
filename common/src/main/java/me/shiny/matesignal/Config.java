package me.shiny.matesignal;

//#if 1.20.1
import net.minecraftforge.common.ForgeConfigSpec;
//#else
import net.neoforged.neoforge.common.ModConfigSpec;
//#endif

import java.util.List;

/**
 * Client configuration spec for the Forge 1.20.1 port.
 * <p>
 * Mirrors MateSignal 1.1.0 (the official 1.21.1 Forge build) so that the
 * on-disk config keys stay identical across every ported platform.
 */
public final class Config {
//#if 1.20.1
    public static final ForgeConfigSpec SPEC;
//#else
    public static final ModConfigSpec SPEC;
//#endif
//#if 1.20.1
    public static final ForgeConfigSpec.IntValue RADIUS;
//#else
    public static final ModConfigSpec.IntValue RADIUS;
//#endif
//#if 1.20.1
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> MOBS;
//#else
    public static final ModConfigSpec.ConfigValue<List<? extends String>> MOBS;
//#endif
//#if 1.20.1
    public static final ForgeConfigSpec.BooleanValue DAY_MESSAGE;
//#else
    public static final ModConfigSpec.BooleanValue DAY_MESSAGE;
//#endif
//#if 1.20.1
    public static final ForgeConfigSpec.BooleanValue NIGHT_MESSAGE;
//#else
    public static final ModConfigSpec.BooleanValue NIGHT_MESSAGE;
//#endif
//#if 1.20.1
    public static final ForgeConfigSpec.BooleanValue LOW_HEALTH_MESSAGE;
//#else
    public static final ModConfigSpec.BooleanValue LOW_HEALTH_MESSAGE;
//#endif
//#if 1.20.1
    public static final ForgeConfigSpec.BooleanValue LOW_HUNGER_MESSAGE;
//#else
    public static final ModConfigSpec.BooleanValue LOW_HUNGER_MESSAGE;
//#endif
//#if 1.20.1
    public static final ForgeConfigSpec.BooleanValue DEATH_MESSAGE;
//#else
    public static final ModConfigSpec.BooleanValue DEATH_MESSAGE;
//#endif
//#if 1.20.1
    public static final ForgeConfigSpec.BooleanValue RAIN_START_MESSAGE;
//#else
    public static final ModConfigSpec.BooleanValue RAIN_START_MESSAGE;
//#endif
//#if 1.20.1
    public static final ForgeConfigSpec.BooleanValue DROWNING_HALF_MESSAGE;
//#else
    public static final ModConfigSpec.BooleanValue DROWNING_HALF_MESSAGE;
//#endif
//#if 1.20.1
    public static final ForgeConfigSpec.BooleanValue SLEEP_MESSAGE;
//#else
    public static final ModConfigSpec.BooleanValue SLEEP_MESSAGE;
//#endif
//#if 1.20.1
    public static final ForgeConfigSpec.BooleanValue CRAFTING_MESSAGE;
//#else
    public static final ModConfigSpec.BooleanValue CRAFTING_MESSAGE;
//#endif
//#if 1.20.1
    public static final ForgeConfigSpec.BooleanValue EAT_MESSAGE;
//#else
    public static final ModConfigSpec.BooleanValue EAT_MESSAGE;
//#endif
//#if 1.20.1
    public static final ForgeConfigSpec.BooleanValue KILL_MESSAGE;
//#else
    public static final ModConfigSpec.BooleanValue KILL_MESSAGE;
//#endif
//#if 1.20.1
    public static final ForgeConfigSpec.BooleanValue BIOME_MESSAGE;
//#else
    public static final ModConfigSpec.BooleanValue BIOME_MESSAGE;
//#endif

    static {
//#if 1.20.1
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
//#else
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
//#endif
        RADIUS = b.defineInRange("radius", 10, 3, 64);
        MOBS = b.defineListAllowEmpty(
                List.of("mobs"),
                () -> List.of("minecraft:creeper", "minecraft:zombie"),
                o -> o instanceof String s && !s.isBlank()
        );
        DAY_MESSAGE = b.define("dayMessage", true);
        NIGHT_MESSAGE = b.define("nightMessage", true);
        LOW_HEALTH_MESSAGE = b.define("lowHealthMessage", true);
        LOW_HUNGER_MESSAGE = b.define("lowHungerMessage", true);
        DEATH_MESSAGE = b.define("deathMessage", true);
        RAIN_START_MESSAGE = b.define("rainStartMessage", true);
        DROWNING_HALF_MESSAGE = b.define("drowningHalfMessage", true);
        SLEEP_MESSAGE = b.define("sleepMessage", true);
        CRAFTING_MESSAGE = b.define("craftingMessage", true);
        EAT_MESSAGE = b.define("eatMessage", true);
        KILL_MESSAGE = b.define("killMessage", true);
        BIOME_MESSAGE = b.define("biomeMessage", true);
        SPEC = b.build();
    }

    private Config() {}
}
