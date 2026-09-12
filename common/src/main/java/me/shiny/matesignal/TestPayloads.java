package me.shiny.matesignal;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The payloads MateSignal can emit, used by the {@code /matesignal test} command.
 *
 * <p>Entries containing player-visible names hold {@code <entity>} / {@code <biome>}
 * placeholders instead of literals, and are filled in by {@link #resolve} using
 * the very same {@link Names} lookups the detection logic uses. That matters:
 * hard-coding English names here made the test command disagree with normal
 * play, so a Chinese client saw localized names in game but English ones from
 * the command. Anything that shows up in a bubble must be resolved the same way
 * on both paths.
 *
 * <p>Everything else is byte-identical to what {@link MateSignal} sends at
 * runtime, and {@code verify.ps1} cross-checks the event vocabulary against the
 * built jars so the two cannot drift apart unnoticed.
 */
final class TestPayloads {

    private TestPayloads() {}

    /** Placeholder filled with a localized entity display name. */
    private static final String ENTITY = "<entity>";
    /** Placeholder filled with a localized biome display name. */
    private static final String BIOME = "<biome>";

    private static final Map<String, String> TEMPLATES = new LinkedHashMap<>();

    static {
        TEMPLATES.put("time_day", "{\"type\":\"time_day\"}");
        TEMPLATES.put("time_night", "{\"type\":\"time_night\"}");
        TEMPLATES.put("low_health", "{\"type\":\"low_health\",\"hp\":5.5}");
        TEMPLATES.put("low_hunger", "{\"type\":\"low_hunger\",\"hunger\":7}");
        TEMPLATES.put("rain_start", "{\"type\":\"rain_start\"}");
        TEMPLATES.put("death", "{\"type\":\"death\"}");
        TEMPLATES.put("drowning", "{\"type\":\"drowning\",\"air\":150,\"max\":300}");
        TEMPLATES.put("sleep_start", "{\"type\":\"sleep_start\"}");
        TEMPLATES.put("crafted", "{\"type\":\"crafted\"}");
        TEMPLATES.put("eat", "{\"type\":\"eat\"}");
        TEMPLATES.put("kill_confirm", "{\"type\":\"kill_confirm\",\"id\":\"minecraft:zombie\","
                + "\"name\":\"" + ENTITY + "\"}");
        TEMPLATES.put("biome_discovery", "{\"type\":\"biome_discovery\",\"biome\":\"" + BIOME + "\"}");
        TEMPLATES.put("mob_proximity", "{\"type\":\"mob_proximity\",\"phase\":\"enter\",\"uuid\":\"42\","
                + "\"id\":\"minecraft:creeper\",\"name\":\"" + ENTITY + "\",\"distance\":4,\"ts\":0}");
    }

    /** Every testable event name, in emission order. */
    static List<String> names() {
        return Collections.unmodifiableList(new ArrayList<>(TEMPLATES.keySet()));
    }

    /** The raw template, placeholders and all; used for audits. */
    static String template(String name) {
        if (name == null) return null;
        return TEMPLATES.get(name.trim().toLowerCase(Locale.ROOT));
    }

    static int size() {
        return TEMPLATES.size();
    }

    /** Shortest-matching event names for a prefix, so {@code test d} resolves. */
    static List<String> matching(String prefix) {
        List<String> out = new ArrayList<>();
        if (prefix == null || prefix.trim().isEmpty()) return out;
        String p = prefix.trim().toLowerCase(Locale.ROOT);
        for (String n : TEMPLATES.keySet()) {
            if (n.startsWith(p)) out.add(n);
        }
        return out;
    }

    /**
     * Renders one event into a concrete payload, resolving any name through the
     * same language-aware lookups the live detection uses.
     *
     * @param name   event name
     * @param player the local player, or {@code null} outside a world
     * @return the payload, or {@code null} for an unknown event name
     */
    static String resolve(String name, Player player) {
        String t = template(name);
        if (t == null) return null;

        String out = t;
        if (out.contains(ENTITY)) {
//#if 1.20.1
            out = out.replace(ENTITY, entityName(player));
//#else
            out = out.replace(ENTITY, entityName());
//#endif
        }
        if (out.contains(BIOME)) {
            out = out.replace(BIOME, biomeName(player));
        }
        return out;
    }

    /**
     * Display name used for the sample entity, resolved exactly like a real
     * proximity event. Falls back to the plain English name when the player is
     * not available.
     */
//#if 1.20.1
    private static String entityName(Player player) {
//#else
    private static String entityName() {
//#endif
        ResourceLocation id = ResourceLocation.withDefaultNamespace("creeper");
        String fallback = id.getPath();
        try {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            if (type == null) return fallback;
            return Names.entity(type, fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /**
     * Display name used for the sample biome: the one the player is standing in,
     * so {@code /matesignal test biome_discovery} reproduces a real event rather
     * than a fixed example.
     */
    private static String biomeName(Player player) {
        try {
            if (player != null && player.level() != null) {
                ResourceLocation key = player.level().getBiome(player.blockPosition())
                        .unwrapKey().map(ResourceKey::location).orElse(null);
                if (key != null) return Names.biome(key);
            }
        } catch (Exception ignored) {
            // Fall through to the neutral placeholder below.
        }
        return "Plains";
    }
}
