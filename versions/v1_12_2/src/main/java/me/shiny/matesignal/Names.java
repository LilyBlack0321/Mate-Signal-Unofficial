package me.shiny.matesignal;

import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the player-visible names that MateSignal sends to MateEngine.
 * <p>
 * MateEngine renders these values verbatim inside its speech bubbles, so they
 * must be in the language the player actually plays in. Sending raw registry ids
 * produced bubbles that mixed languages, e.g. a Chinese sentence with an
 * English "Creeper" in the middle.
 * <p>
 * <b>The mod is language-agnostic.</b> Whatever the player selects in Minecraft
 * decides what MateEngine shows; nothing here assumes Chinese or English. The
 * game's own language lookups do most of the work, and {@link Lang} supplies
 * optional per-language tables only where 1.12.2 makes translation impossible.
 * <p>
 * 1.12.2 specifics:
 * <ul>
 *   <li>Entity names live under {@code entity.<Name>.name} and are reached
 *       through {@link EntityList#getTranslationName(ResourceLocation)}, so they
 *       follow the selected language already.</li>
 *   <li><b>Biomes cannot be translated.</b> The display name comes from an
 *       English string baked into each {@code BiomeXxx} class (e.g.
 *       {@code BiomeRiver} registers "River"), {@code en_us.lang} has no
 *       {@code biome.*} keys at all, and the client jar ships only
 *       {@code en_us}. Vanilla Minecraft itself shows English here, which is
 *       fine for English players but wrong for everyone else, so non-English
 *       names come from {@link Lang}'s tables (when the player selected that
 *       language) or from the mod's own language entries.</li>
 * </ul>
 */
final class Names {

    private Names() {}

    /** Localized entity name, following the client's selected language. */
    static String entity(Entity en, String fallbackPath) {
        ResourceLocation rl = null;
        try {
            rl = EntityList.getKey(en);
        } catch (Exception ignored) {
            // Registry lookup failed; fall through to the readable id below.
        }
        if (rl == null) {
            return toTitle(fallbackPath);
        }

        // The game's own translation, which already respects the selected
        // language (including any language pack the player installed). This is
        // why entity names need no language table: the client ships an en_us
        // fallback for every entity, so an untranslated name still reads
        // correctly in English.
        try {
            String entry = EntityList.getTranslationName(rl);
            if (entry != null && !entry.isEmpty()) {
                String s = I18n.format("entity." + entry + ".name");
                if (isUsable(s)) return s;
            }
        } catch (Exception ignored) {
            // Fall through to the readable id below.
        }

        // A modded entity with no translation anywhere: show a title-cased id.
        return toTitle(fallbackPath);
    }

    /**
     * Player-visible biome name.
     * <p>
     * Resolution order, all of it language-aware:
     * <ol>
     *   <li>the user's override file, so any biome can be named explicitly;</li>
     *   <li>the table registered for the <b>current</b> language, if any;</li>
     *   <li>the mod's own language entries, for modded biomes that ship
     *       translations ({@code biome.<modid>.<path>} and older conventions);</li>
     *   <li>otherwise the game's own name, so nothing is invented.</li>
     * </ol>
     * English needs no table: the game's own name is already English.
     */
    static String biome(Biome biome) {
        if (biome == null) {
            return "unknown";
        }
        String gameName = biome.getBiomeName();
        if (gameName == null || gameName.trim().isEmpty()) {
            gameName = "unknown";
        }

        ResourceLocation id = null;
        try {
            id = biome.getRegistryName();
        } catch (Exception ignored) {
            // Older or unusual biomes may not carry a registry name.
        }

        // 1. explicit user override wins over everything else.
        if (id != null) {
            String override = overrides().get(id.toString());
            if (override != null) return override;
        }

        // 2. the table for the language the player actually selected.
        String fromLang = Lang.lookup(gameName);
        if (fromLang != null) return fromLang;

        // 3. the mod's own translation, if it ships one.
        if (id != null) {
            // 1.12.2 predates the getPath()/getNamespace() names.
            String path = id.getResourcePath();
            String ns = id.getResourceDomain();
            String[] candidates = {
                    "biome." + ns + "." + path,
                    ns + "." + path + ".biomeName",
                    ns + "." + path + ".name",
                    path + ".biomeName",
            };
            for (String key : candidates) {
                try {
                    if (I18n.hasKey(key)) {
                        String s = I18n.format(key);
                        if (isUsable(s)) return s;
                    }
                } catch (Exception ignored) {
                    // Keep trying the remaining candidate keys.
                }
            }
            // Registry path with no translation: at least spell it readably.
            if (gameName.equalsIgnoreCase(toTitle(path))) {
                return toTitle(path);
            }
        }

        // 4. nothing matched; keep what the game shows.
        return gameName;
    }

    // --- user override file --------------------------------------------------

    private static Map<String, String> overrides = new HashMap<>();
    private static boolean overridesLoaded = false;

    /** Where the user can add names for modded biomes that ship no translation. */
    static File overrideFile() {
        return new File(Config.configDir(), "matesignal-biomes.txt");
    }

    /** Reads the override file if it has not been read yet. */
    private static Map<String, String> overrides() {
        if (!overridesLoaded) {
            reloadOverrides();
        }
        return overrides;
    }

    /**
     * (Re)reads {@code config/matesignal-biomes.txt}.
     * <p>
     * Format: one {@code registry:id=显示名} pair per line; {@code #} starts a
     * comment. Keys are matched case-insensitively and the file is safe to edit
     * while the game runs — {@code /matesignal reload} applies changes without a
     * restart.
     *
     * @return the number of entries loaded
     */
    static int reloadOverrides() {
        Map<String, String> loaded;
        File f = overrideFile();
        if (f.isFile()) {
            loaded = readOverrideFile(f);
        } else {
            writeOverrideTemplate(f);
            loaded = new LinkedHashMap<>();
        }
        overrides = loaded;
        overridesLoaded = true;
        return loaded.size();
    }

    /** Reads and parses an override file; never throws. */
    static Map<String, String> readOverrideFile(File f) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            return parseOverrides(r);
        } catch (Exception ignored) {
            // A malformed or unreadable file must never break the mod.
            return new LinkedHashMap<>();
        }
    }

    /**
     * Parses {@code registry:id=display name} lines.
     * <p>
     * {@code #} starts a comment, blank lines are skipped, keys are lowercased,
     * and the first {@code =} separates key from value (so a value may itself
     * contain {@code =}). Malformed lines are ignored rather than fatal, because
     * this file is meant to be hand-edited in any language.
     */
    static Map<String, String> parseOverrides(BufferedReader reader) throws java.io.IOException {
        Map<String, String> out = new LinkedHashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String val = line.substring(eq + 1).trim();
            if (!key.isEmpty() && !val.isEmpty()) {
                out.put(key, val);
            }
        }
        return out;
    }

    /** Creates a commented template so the file format is discoverable. */
    private static void writeOverrideTemplate(File f) {
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            java.io.Writer w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(f), StandardCharsets.UTF_8);
            try {
                w.write("# MateSignal biome name overrides\r\n");
                w.write("# Use this for modded biomes that ship no translation in your language.\r\n");
                w.write("# Format:  <registry id>=<display name>\r\n");
                w.write("# Lines starting with # are ignored. Run /matesignal reload after editing.\r\n");
                w.write("#\r\n");
                w.write("# Examples:\r\n");
                w.write("# byg:allium_fields=Allium Fields\r\n");
                w.write("# byg:allium_fields=绒球花原野\r\n");
            } finally {
                w.close();
            }
        } catch (Exception ignored) {
            // The file is optional; failing to create it is not fatal.
        }
    }

    /**
     * True when a resolved string is safe to show. Rejects empty results and
     * untranslated keys echoed back by the language manager, which would leak
     * strings like "entity.Creeper.name" into the bubble.
     */
    private static boolean isUsable(String s) {
        if (s == null || s.trim().isEmpty()) return false;
        return !s.startsWith("biome.") && !s.startsWith("entity.") && !s.startsWith("item.");
    }

    /** Turns {@code sunflower_plains} into {@code Sunflower Plains}. */
    static String toTitle(String path) {
        String[] parts = path.replace('_', ' ').trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i];
            if (s.isEmpty()) continue;
            String head = s.substring(0, 1).toUpperCase(Locale.ROOT);
            String tail = s.length() > 1 ? s.substring(1).toLowerCase(Locale.ROOT) : "";
            if (i > 0) sb.append(' ');
            sb.append(head).append(tail);
        }
        return sb.toString();
    }
}
