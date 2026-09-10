package me.shiny.matesignal;

import net.minecraft.client.Minecraft;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Language handling for the names MateSignal sends to MateEngine.
 * <p>
 * The mod must not assume any particular language. Whatever the player selects
 * in Minecraft decides what MateEngine displays: an English client gets English
 * names, a Chinese client gets Chinese ones, and so on.
 * <p>
 * Most names need no help here because the game's own language lookups already
 * follow the selected language. The complication is 1.12.2, which cannot
 * translate biome names at all (the name is an English string hard-coded into
 * each {@code BiomeXxx} class) and ships only {@code en_us} inside the client
 * jar. For those cases this class supplies optional per-language tables that are
 * keyed by language code, so adding support for another language is a matter of
 * adding a table rather than changing logic.
 */
final class Lang {

    private Lang() {}

    /** Fallback when the language cannot be determined. */
    private static final String DEFAULT_CODE = "en_us";

    /**
     * Name tables keyed by the Minecraft language code they apply to.
     * <p>
     * A table is only consulted when the player has actually selected that
     * language, so an English player never receives Chinese text.
     */
    private static final Map<String, Map<String, String>> BIOME_TABLES = new HashMap<>();

    static {
        BIOME_TABLES.put("zh_cn", chineseBiomes());
        BIOME_TABLES.put("zh_tw", chineseBiomes());
    }

    /** The language code currently selected in the client, e.g. {@code en_us}. */
    static String currentCode() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.gameSettings != null) {
                String code = mc.gameSettings.language;
                if (code != null && !code.trim().isEmpty()) {
                    return code.trim().toLowerCase(Locale.ROOT);
                }
            }
        } catch (Exception ignored) {
            // Fall through to the default below.
        }
        return DEFAULT_CODE;
    }

    /** True when the client is running in any Chinese locale. */
    static boolean isChinese() {
        return currentCode().startsWith("zh");
    }

    /**
     * Looks a name up in the table registered for the current language.
     *
     * @return the localized name, or {@code null} when this language has no
     *         table or the key is absent from it
     */
    static String lookup(String key) {
        if (key == null) return null;
        Map<String, String> table = resolveTable(currentCode());
        if (table == null) return null;
        return table.get(key);
    }

    /**
     * Maps a language code to its table, or {@code null} when that language has
     * none.
     * <p>
     * This is the gate that keeps one language's text out of another's bubbles:
     * an English or Japanese client resolves to {@code null} and therefore keeps
     * the game's own names. Split out from {@link #lookup(String)} so the rule
     * can be tested without a running client.
     */
    static Map<String, String> resolveTable(String code) {
        if (code == null) return null;
        return BIOME_TABLES.get(code.trim().toLowerCase(Locale.ROOT));
    }

    /** Reads the raw table for a language code; used by tests. */
    static Map<String, String> tableFor(String code) {
        Map<String, String> t = resolveTable(code);
        return t == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(t);
    }

    /** Language codes that ship a biome name table. */
    static java.util.Set<String> tableCodes() {
        return Collections.unmodifiableSet(BIOME_TABLES.keySet());
    }

    /**
     * Chinese names for the vanilla 1.12.2 biomes.
     * <p>
     * Keyed by the English display name returned by {@code Biome#getBiomeName()}.
     * The key set was derived from the hard-coded strings inside the vanilla
     * {@code net.minecraft.world.biome} classes and the values follow the
     * official Simplified Chinese names used by later Minecraft releases.
     */
    private static Map<String, String> chineseBiomes() {
        Map<String, String> m = new HashMap<>();
        // Oceans and rivers
        m.put("Ocean", "海洋");
        m.put("Deep Ocean", "深海");
        m.put("FrozenOcean", "冻洋");
        m.put("FrozenRiver", "冻河");
        m.put("River", "河流");
        // Temperate
        m.put("Plains", "平原");
        m.put("Sunflower Plains", "向日葵平原");
        m.put("Forest", "森林");
        m.put("ForestHills", "森林丘陵");
        m.put("Flower Forest", "繁花森林");
        m.put("Birch Forest", "桦木森林");
        m.put("Birch Forest Hills", "桦木森林丘陵");
        m.put("Birch Forest M", "高大桦木森林");
        m.put("Birch Forest Hills M", "高大桦木丘陵");
        m.put("Roofed Forest", "黑森林");
        m.put("Roofed Forest M", "黑森林丘陵");
        m.put("Swampland", "沼泽");
        m.put("Swampland M", "沼泽丘陵");
        // Mountains
        m.put("Extreme Hills", "山地");
        m.put("Extreme Hills Edge", "山地边缘");
        m.put("Extreme Hills M", "沙砾山地");
        m.put("Extreme Hills+", "繁茂的山地");
        m.put("Extreme Hills+ M", "沙砾山地+");
        // Cold
        m.put("Taiga", "针叶林");
        m.put("TaigaHills", "针叶林丘陵");
        m.put("Taiga M", "针叶林山地");
        m.put("Cold Taiga", "积雪的针叶林");
        m.put("Cold Taiga Hills", "积雪的针叶林丘陵");
        m.put("Mega Taiga", "巨型针叶林");
        m.put("Mega Taiga Hills", "巨型针叶林丘陵");
        m.put("Redwood Taiga Hills M", "巨型云杉针叶林丘陵");
        m.put("Ice Plains", "积雪的平原");
        m.put("Ice Plains Spikes", "冰刺平原");
        m.put("Ice Mountains", "雪山");
        // Dry
        m.put("Desert", "沙漠");
        m.put("DesertHills", "沙漠丘陵");
        m.put("Desert M", "沙漠湖泊");
        m.put("Savanna", "热带草原");
        m.put("Savanna M", "破碎的热带草原");
        m.put("Savanna Plateau", "热带高原");
        m.put("Savanna Plateau M", "破碎的热带高原");
        m.put("Mesa", "恶地");
        m.put("Mesa Plateau", "恶地高原");
        m.put("Mesa Plateau F", "疏林恶地");
        m.put("Mesa Plateau F M", "疏林恶地高原");
        m.put("Mesa Plateau M", "被风蚀的恶地");
        m.put("Mesa (Bryce)", "被风蚀的恶地");
        // Jungle
        m.put("Jungle", "丛林");
        m.put("JungleHills", "丛林丘陵");
        m.put("JungleEdge", "丛林边缘");
        m.put("JungleEdge M", "丛林边缘");
        m.put("Jungle M", "竹林");
        m.put("Jungle M Edge", "竹林边缘");
        // Coasts and islands
        m.put("Beach", "沙滩");
        m.put("Cold Beach", "积雪的沙滩");
        m.put("Stone Beach", "石岸");
        m.put("MushroomIsland", "蘑菇岛");
        m.put("MushroomIslandShore", "蘑菇岛岸");
        // Other dimensions
        m.put("Hell", "下界");
        m.put("The End", "末地");
        m.put("Sky", "末地");
        m.put("The Void", "虚空");
        m.put("Void", "虚空");
        return m;
    }
}
