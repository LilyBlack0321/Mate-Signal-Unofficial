package me.shiny.matesignal;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.config.Configuration;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration for the 1.12.2 port.
 * <p>
 * 1.12.2 predates the {@code ForgeConfigSpec} system, so this uses the classic
 * {@link Configuration} API. Option names and defaults mirror MateSignal 1.1.0
 * so that a config file stays recognisable across platforms.
 */
public final class Config {
    private static final String CATEGORY = "general";

    private static final boolean[] DEFAULTS = {
            true,  // day
            true,  // night
            true,  // low health
            true,  // low hunger
            true,  // death
            true,  // rain
            true,  // drowning
            true,  // sleep
            true,  // crafting
            true,  // eat
            true,  // kill
            true,  // biome
    };

    private static final String[] KEYS = {
            "dayMessage",
            "nightMessage",
            "lowHealthMessage",
            "lowHungerMessage",
            "deathMessage",
            "rainStartMessage",
            "drowningHalfMessage",
            "sleepMessage",
            "craftingMessage",
            "eatMessage",
            "killMessage",
            "biomeMessage",
    };

    private static final boolean[] values = DEFAULTS.clone();
    private static int radius = 10;
    private static List<String> mobs = new ArrayList<>(
            Arrays.asList("minecraft:creeper", "minecraft:zombie"));

    private static Configuration config;
    private static File configFile;

    private Config() {}

    /** Loads (or creates) the config file supplied by Forge. */
    public static void load(File file) {
        configFile = file;
        config = new Configuration(file);
        sync();
    }

    /**
     * Directory holding this mod's config files, used for the biome name
     * override file. Falls back to {@code config/} when Forge has not supplied
     * a location yet.
     */
    public static File configDir() {
        File f = configFile;
        if (f != null && f.getParentFile() != null) {
            return f.getParentFile();
        }
        return new File("config");
    }

    /** Re-reads every option from disk into the cached values. */
    public static void sync() {
        if (config == null) {
            return;
        }
        try {
            config.load();
            radius = config.getInt("radius", CATEGORY, 10, 3, 64,
                    "Block radius used for hostile mob detection.");
            String[] mobArray = config.getStringList("mobs", CATEGORY,
                    new String[]{"minecraft:creeper", "minecraft:zombie"},
                    "Hostile mob ids (or bare names) that trigger proximity messages. Empty means all.");
            mobs = new ArrayList<>(Arrays.asList(mobArray));
            for (int i = 0; i < KEYS.length; i++) {
                values[i] = config.getBoolean(KEYS[i], CATEGORY, DEFAULTS[i], KEYS[i]);
            }
        } finally {
            if (config.hasChanged()) {
                config.save();
            }
        }
    }

    /** Writes the in-memory values back to disk. */
    public static void save() {
        if (config == null) {
            return;
        }
        config.get(CATEGORY, "radius", 10).set(radius);
        config.get(CATEGORY, "mobs", new String[]{"minecraft:creeper", "minecraft:zombie"}).set(mobs.toArray(new String[0]));
        for (int i = 0; i < KEYS.length; i++) {
            config.get(CATEGORY, KEYS[i], DEFAULTS[i]).set(values[i]);
        }
        config.save();
    }

    public static boolean get(int option) {
        return option >= 0 && option < values.length && values[option];
    }

    public static void set(int option, boolean value) {
        if (option >= 0 && option < values.length) {
            values[option] = value;
        }
    }

    public static int getRadius() {
        return radius;
    }

    public static void setRadius(int r) {
        radius = Math.max(3, Math.min(64, r));
    }

    public static List<String> getMobs() {
        return mobs;
    }

    public static void setMobs(List<String> list) {
        mobs = new ArrayList<>(list);
    }

    /** Registry id for an entity, e.g. {@code minecraft:creeper}. */
    public static String entityId(Entity en) {
        ResourceLocation rl = EntityList.getKey(en);
        return rl == null ? "unknown" : rl.toString();
    }

    /** Path component of the registry id, e.g. {@code creeper}. */
    public static String entityName(Entity en) {
        ResourceLocation rl = EntityList.getKey(en);
        // 1.12.2 predates ResourceLocation#getPath.
        return rl == null ? "unknown" : rl.getResourcePath();
    }

    /** Every registered hostile-mob id, sorted; used by the config screen. */
    public static List<ResourceLocation> hostileMobIds() {
        List<ResourceLocation> ids = new ArrayList<>();
        for (ResourceLocation rl : EntityList.getEntityNameList()) {
            Class<?> cls = EntityList.getClass(rl);
            if (cls == null) continue;
            if (net.minecraft.entity.monster.IMob.class.isAssignableFrom(cls)) {
                ids.add(rl);
            }
        }
        ids.sort((a, b) -> a.toString().compareTo(b.toString()));
        return ids;
    }
}
