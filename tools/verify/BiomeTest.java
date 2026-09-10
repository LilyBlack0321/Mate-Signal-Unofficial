import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/**
 * Validates the 1.12.2 biome-name translation table.
 *
 * 1.12.2 cannot translate biome names, so Names keeps a table keyed by the
 * English name that {@code Biome#getBiomeName()} returns. This test checks that
 * table against the authoritative list of vanilla 1.12.2 biome names, which was
 * extracted from the hard-coded strings in the vanilla
 * {@code net.minecraft.world.biome} classes.
 *
 * A missing key would silently leak an English word into a Chinese bubble,
 * which is exactly the bug being fixed here.
 *
 * Usage: BiomeTest <classesDir> <vanillaBiomeNamesFile>
 */
public class BiomeTest {
    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        Path classes = Path.of(args[0]);
        List<String> vanilla = Files.readAllLines(Path.of(args[1]));

        System.out.println("=== 1.12.2 biome translation table ===");
        System.out.println("vanilla biome names to cover: " + vanilla.size());

        ClassLoader cl = new java.net.URLClassLoader(
                new java.net.URL[]{classes.toUri().toURL()}, BiomeTest.class.getClassLoader());
        Class<?> names = Class.forName("me.shiny.matesignal.Names", true, cl);

        // Read the private static BIOME_ZH map.
        Field f = names.getDeclaredField("BIOME_ZH");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> table = (Map<String, String>) f.get(null);
        System.out.println("table entries: " + table.size());
        System.out.println();

        // 1. every vanilla biome must be covered
        List<String> uncovered = new ArrayList<>();
        for (String b : vanilla) {
            if (!table.containsKey(b)) uncovered.add(b);
        }
        if (uncovered.isEmpty()) {
            check("all " + vanilla.size() + " vanilla biome names covered", true, "");
        } else {
            check("all " + vanilla.size() + " vanilla biome names covered", false,
                    "missing " + uncovered.size() + ": " + uncovered);
        }

        // 2. every value must be real Chinese (no leftover English)
        List<String> notChinese = new ArrayList<>();
        for (Map.Entry<String, String> e : table.entrySet()) {
            if (!isChinese(e.getValue())) notChinese.add(e.getKey() + " -> " + e.getValue());
        }
        check("every value contains Chinese characters", notChinese.isEmpty(),
                notChinese.isEmpty() ? "" : notChinese.toString());

        // 3. spot checks against the official Simplified Chinese names
        checkValue(table, "River", "河流");
        checkValue(table, "Ocean", "海洋");
        checkValue(table, "Plains", "平原");
        checkValue(table, "Desert", "沙漠");
        checkValue(table, "Forest", "森林");
        checkValue(table, "Jungle", "丛林");
        checkValue(table, "Swampland", "沼泽");
        checkValue(table, "Hell", "下界");
        checkValue(table, "MushroomIsland", "蘑菇岛");
        checkValue(table, "Mesa", "恶地");

        // 4. the lookup helper must fall back for unknown (modded) biomes
        Method biome = names.getDeclaredMethod("biome", String.class);
        biome.setAccessible(true);
        check("known biome resolves", "河流".equals(biome.invoke(null, "River")), "");
        check("unknown biome keeps its own name",
                "Twilight Forest".equals(biome.invoke(null, "Twilight Forest")), "");
        check("null biome becomes unknown", "unknown".equals(biome.invoke(null, (String) null)), "");
        check("empty biome becomes unknown", "unknown".equals(biome.invoke(null, "   ")), "");

        System.out.println();
        System.out.println("biome tests: " + pass + " passed, " + fail + " failed");
        System.out.println(fail == 0 ? "BIOME OK" : "BIOME FAILED");
        if (fail != 0) System.exit(1);
    }

    static void checkValue(Map<String, String> table, String en, String expectedZh) {
        String got = table.get(en);
        check("\"" + en + "\" -> \"" + expectedZh + "\"",
                expectedZh.equals(got), "got " + got);
    }

    static boolean isChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x4E00 && s.charAt(i) <= 0x9FFF) return true;
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
