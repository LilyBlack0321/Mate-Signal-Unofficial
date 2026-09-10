import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;

/**
 * Verifies that the 1.12.2 name tables are gated by language.
 *
 * The mod must be usable by players of any language. The rule under test is that
 * a language-specific table is only consulted when the player actually selected
 * that language, so an English (or Japanese, or Russian...) client keeps the
 * game's own names instead of being served Chinese.
 *
 * Usage: LangTest <classesDir> <mappedMinecraftJar>
 */
public class LangTest {
    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        Path classes = Path.of(args[0]);
        System.out.println("=== 1.12.2 language gating ===");

        // Lang.currentCode() touches Minecraft, so the mapped jar is on the
        // classpath; the test only calls the pure resolution helper.
        List<java.net.URL> urls = new ArrayList<>();
        urls.add(classes.toUri().toURL());
        for (int i = 1; i < args.length; i++) {
            Path p = Path.of(args[i]);
            if (java.nio.file.Files.exists(p)) urls.add(p.toUri().toURL());
        }
        ClassLoader cl = new java.net.URLClassLoader(
                urls.toArray(new java.net.URL[0]), LangTest.class.getClassLoader());
        Class<?> lang = Class.forName("me.shiny.matesignal.Lang", true, cl);

        Method resolve = lang.getDeclaredMethod("resolveTable", String.class);
        resolve.setAccessible(true);
        Method tableFor = lang.getDeclaredMethod("tableFor", String.class);
        tableFor.setAccessible(true);

        // --- Chinese locales get a table -------------------------------------
        for (String code : new String[]{"zh_cn", "zh_tw", "ZH_CN", " zh_cn "}) {
            Object t = resolve.invoke(null, code);
            check("table resolved for '" + code + "'", t != null, "got null");
        }

        // --- every other language must get nothing ---------------------------
        // This is the property that makes the mod safe for non-Chinese players.
        String[] others = {
                "en_us", "en_gb", "ja_jp", "ko_kr", "ru_ru", "de_de", "fr_fr",
                "es_es", "pt_br", "it_it", "pl_pl", "tr_tr", "uk_ua", "kk_kz",
                "zh", "en", "", "null",
        };
        boolean allNull = true;
        List<String> leaked = new ArrayList<>();
        for (String code : others) {
            Object t = resolve.invoke(null, code);
            if (t != null) {
                allNull = false;
                leaked.add(code);
            }
        }
        check("no table for any non-Chinese language", allNull, "leaked: " + leaked);

        Object nullTable = resolve.invoke(null, (String) null);
        check("null language resolves to no table", nullTable == null, "");

        // --- the Chinese table is complete and Chinese -----------------------
        @SuppressWarnings("unchecked")
        Map<String, String> zh = (Map<String, String>) tableFor.invoke(null, "zh_cn");
        check("zh_cn table populated", zh.size() >= 60, "size=" + zh.size());
        check("River -> 河流", "河流".equals(zh.get("River")), "got " + zh.get("River"));
        check("Ocean -> 海洋", "海洋".equals(zh.get("Ocean")), "got " + zh.get("Ocean"));
        check("Hell -> 下界", "下界".equals(zh.get("Hell")), "got " + zh.get("Hell"));

        boolean allChinese = true;
        List<String> notChinese = new ArrayList<>();
        for (Map.Entry<String, String> e : zh.entrySet()) {
            if (!isChinese(e.getValue())) notChinese.add(e.getKey());
        }
        allChinese = notChinese.isEmpty();
        check("every zh_cn value contains Chinese", allChinese, "bad: " + notChinese);

        // --- zh_tw resolves to the same table --------------------------------
        @SuppressWarnings("unchecked")
        Map<String, String> tw = (Map<String, String>) tableFor.invoke(null, "zh_tw");
        check("zh_tw table populated", tw.size() == zh.size(), "size=" + tw.size());

        // --- an empty result for other languages, not a crash ----------------
        @SuppressWarnings("unchecked")
        Map<String, String> en = (Map<String, String>) tableFor.invoke(null, "en_us");
        check("en_us tableFor is empty", en.isEmpty(), "size=" + en.size());

        System.out.println();
        System.out.println("language tests: " + pass + " passed, " + fail + " failed");
        System.out.println(fail == 0 ? "LANGUAGE OK" : "LANGUAGE FAILED");
        if (fail != 0) System.exit(1);
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
