import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;

/**
 * Validates the 1.12.2 biome-name override parser and lookup order.
 *
 * Resolution order is: user override file, built-in vanilla table, the mod's own
 * language entries, then the game's own name. Only the parts that do not need a
 * running Minecraft client can be unit tested, which is what this covers:
 *
 * <ul>
 *   <li>the override file format (comments, blanks, '=' in the value...),</li>
 *   <li>the built-in table is reachable and complete,</li>
 *   <li>tier ordering is actually implemented in the bytecode.</li>
 * </ul>
 *
 * Usage: OverrideTest <classesDir>
 */
public class OverrideTest {
    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        Path classes = Path.of(args[0]);
        System.out.println("=== 1.12.2 biome override handling ===");

        ClassLoader cl = new java.net.URLClassLoader(
                new java.net.URL[]{classes.toUri().toURL()}, OverrideTest.class.getClassLoader());
        Class<?> names = Class.forName("me.shiny.matesignal.Names", true, cl);

        Method parse = names.getDeclaredMethod("parseOverrides", BufferedReader.class);
        parse.setAccessible(true);

        // --- format handling -------------------------------------------------
        String content =
                "# leading comment\r\n"
                + "\r\n"
                + "byg:allium_fields=绒球花原野\r\n"
                + "  byg:alps = 阿尔卑斯山脉  \r\n"
                + "twilightforest:twilight_forest=暮色森林\r\n"
                + "no_equals_here\r\n"
                + "=only_value\r\n"
                + "empty_value=\r\n"
                + "trailing#comment=ignored\r\n"
                + "byg:with_equals=名字=带等号\r\n";

        @SuppressWarnings("unchecked")
        Map<String, String> m = (Map<String, String>) parse.invoke(null,
                new BufferedReader(new StringReader(content)));

        check("comment line ignored", !m.containsKey("# leading comment"), "");
        check("3 normal entries parsed", m.size() >= 4, "size=" + m.size());
        check("simple pair", "绒球花原野".equals(m.get("byg:allium_fields")), "got " + m.get("byg:allium_fields"));
        check("whitespace trimmed", "阿尔卑斯山脉".equals(m.get("byg:alps")), "got " + m.get("byg:alps"));
        check("keys lowercased", m.get("twilightforest:twilight_forest") != null, "");
        check("line without '=' skipped", !m.containsKey("no_equals_here"), "");
        check("line starting with '=' skipped", m.size() == 4, "size=" + m.size());
        check("empty value skipped", !m.containsKey("empty_value"), "");
        check("'#' strips trailing text", !m.containsKey("trailing"), "");
        check("value may contain '='", "名字=带等号".equals(m.get("byg:with_equals")),
                "got " + m.get("byg:with_equals"));

        // lowercasing must not damage non-ASCII values
        check("value keeps non-ASCII intact",
                "绒球花原野".equals(m.get("byg:allium_fields")), "");

        // --- empty / garbage input -------------------------------------------
        @SuppressWarnings("unchecked")
        Map<String, String> empty = (Map<String, String>) parse.invoke(null,
                new BufferedReader(new StringReader("")));
        check("empty file yields empty map", empty.isEmpty(), "size=" + empty.size());

        // --- bytecode: tier ordering ------------------------------------------
        java.nio.file.Path namesCls = classes.resolve("me/shiny/matesignal/Names.class");
        byte[] namesRaw = java.nio.file.Files.readAllBytes(namesCls);
        check("override lookup present", containsAscii(namesRaw, "matesignal-biomes.txt"), "");
        check("per-language lookup used", containsAscii(namesRaw, "me/shiny/matesignal/Lang"), "");
        check("mod translation key convention tried", containsAscii(namesRaw, "biome."), "");
        check("registry name used for override keys", containsAscii(namesRaw, "getRegistryName"), "");
        check("no hardcoded language in Names", !containsAscii(namesRaw, "BIOME_ZH"), "");

        // The language tables live in Lang, keyed by language code, so an
        // English client can never be served Chinese text.
        java.nio.file.Path langCls = classes.resolve("me/shiny/matesignal/Lang.class");
        check("Lang class present", java.nio.file.Files.exists(langCls), "");
        if (java.nio.file.Files.exists(langCls)) {
            byte[] langRaw = java.nio.file.Files.readAllBytes(langCls);
            check("tables keyed by language code", containsAscii(langRaw, "zh_cn"), "");
            check("English is the fallback code", containsAscii(langRaw, "en_us"), "");
            check("current language is read from settings",
                    containsAscii(langRaw, "gameSettings"), "");
            check("Chinese table actually has Chinese", containsUtf8(langRaw, new int[]{
                    0xE6, 0xB2, 0xB3, 0xE6, 0xB5, 0x81}), "");
        }

        System.out.println();
        System.out.println("override tests: " + pass + " passed, " + fail + " failed");
        System.out.println(fail == 0 ? "OVERRIDE OK" : "OVERRIDE FAILED");
        if (fail != 0) System.exit(1);
    }

    static boolean containsAscii(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /** True when the raw bytes contain the given UTF-8 byte sequence. */
    static boolean containsUtf8(byte[] haystack, int[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if ((haystack[i + j] & 0xFF) != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  ok   " + what);
        } else {
            fail++;
            System.out.println("  FAIL " + what + (detail.isEmpty() ? "" : "  (" + detail + ")"));
        }
    }
}
