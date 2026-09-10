package me.shiny.matesignal;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

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
     * @param name  event name
     * @param world the client world, or {@code null} outside a world
     * @return the payload, or {@code null} for an unknown event name
     */
    static String resolve(String name, World world, net.minecraft.entity.player.EntityPlayer player) {
        String t = template(name);
        if (t == null) return null;

        String out = t;
        if (out.contains(ENTITY)) {
            out = out.replace(ENTITY, entityName(world));
        }
        if (out.contains(BIOME)) {
            out = out.replace(BIOME, biomeName(world, player));
        }
        return out;
    }

    /**
     * Display name used for the sample entity, resolved exactly like a real
     * proximity event. Falls back to the plain English name when no world is
     * loaded (the command works from the main menu too).
     */
    private static String entityName(World world) {
        String fallback = "creeper";
        try {
            if (world == null) return fallback;
            ResourceLocation id = new ResourceLocation("minecraft", "creeper");
            Class<? extends Entity> cls = EntityList.getClass(id);
            if (cls == null) return fallback;
            Entity probe = EntityList.newEntity(cls, world);
            if (probe == null) return fallback;
            // Names.entity resolves through the language pack and the bundled
            // table, but does not register the probe, so it is safe to discard.
            return Names.entity(probe, fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /**
     * Display name used for the sample biome: the one the player is standing in,
     * so {@code /matesignal test biome_discovery} reproduces a real event rather
     * than a fixed example.
     */
    private static String biomeName(World world, net.minecraft.entity.player.EntityPlayer player) {
        try {
            if (world != null && player != null) {
                return Names.biome(world.getBiome(player.getPosition()));
            }
        } catch (Exception ignored) {
            // Fall through to the neutral placeholder below.
        }
        return "Plains";
    }
}
