package me.shiny.matesignal;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
//#if 1.20.1
import net.minecraft.world.level.biome.Biome;
//#else
import net.minecraft.world.level.biome.Biome;
//#endif

import java.util.Locale;

/**
 * Resolves the player-visible names that MateSignal sends to MateEngine.
 * <p>
 * MateEngine renders these values verbatim inside its speech bubbles, so they
 * must follow the language the player actually plays in. Sending raw registry
 * ids produced bubbles that mixed languages, e.g. a Chinese sentence with an
 * English "Creeper" in the middle.
 * <p>
 * Every lookup falls back to a readable English form when no translation is
 * available, which is the common case for modded content that ships no
 * {@code en_us} strings for the current language.
 */
final class Names {

    private Names() {}

    /** Localized entity name, e.g. {@code 苦力怕} on a Chinese client. */
    static String entity(EntityType<?> type, String fallbackPath) {
        try {
            Component desc = type.getDescription();
            if (desc != null) {
                String s = desc.getString();
                if (isUsable(s)) return s;
            }
        } catch (Exception ignored) {
            // Fall through to the registry-path rendering below.
        }
        return fallbackPath;
    }

    /**
     * Localized biome name, e.g. {@code 向日葵平原} on a Chinese client.
     * <p>
     * Biomes expose no description component, so the vanilla translation key
     * {@code biome.<namespace>.<path>} is resolved through the language manager.
     */
    static String biome(ResourceLocation id) {
        String path = id.getPath();
        try {
            String key = "biome." + id.getNamespace() + "." + id.getPath();
            if (I18n.exists(key)) {
                String s = I18n.get(key);
                if (isUsable(s)) return s;
            }
        } catch (Exception ignored) {
            // Fall through to the title-cased path.
        }
        return toTitle(path);
    }

    /**
     * True when a resolved string is safe to show. Rejects empty results and
     * untranslated keys echoed back by the language manager.
     */
    private static boolean isUsable(String s) {
        if (s == null || s.trim().isEmpty()) return false;
        // A missing translation is commonly returned as the key itself, which
        // would leak strings like "biome.minecraft.foo" into the bubble.
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
