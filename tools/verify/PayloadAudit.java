import java.nio.file.Path;
import java.util.*;

/**
 * Cross-checks the test-command payload table against the payloads the mod
 * actually sends.
 *
 * {@code TestPayloads} duplicates the JSON literals that {@code MateSignal}
 * emits, which is exactly the kind of duplication that silently rots: someone
 * renames an event in one place and the test command keeps exercising the old
 * name. This check compares the two constant-pool sets inside the built jar, so
 * a drift fails verification instead of shipping.
 *
 * Usage: PayloadAudit <jar>
 */
public class PayloadAudit {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        System.out.println("=== payload table vs emitted payloads ===");
        System.out.println("jar: " + jar.getFileName());

        Set<String> emitted = typesIn(jar, "MateSignal");
        Set<String> tested = typesIn(jar, "TestPayloads");

        System.out.println("  emitted event types : " + emitted.size());
        System.out.println("  testable event types: " + tested.size());

        Set<String> untested = new TreeSet<>(emitted);
        untested.removeAll(tested);
        Set<String> phantom = new TreeSet<>(tested);
        phantom.removeAll(emitted);

        int fail = 0;

        if (untested.isEmpty()) {
            System.out.println("  ok   every emitted event is reachable via the test command");
        } else {
            fail = 1;
            System.out.println("  FAIL emitted but not testable: " + untested);
        }

        if (phantom.isEmpty()) {
            System.out.println("  ok   the test command invents no events the mod cannot send");
        } else {
            fail = 1;
            System.out.println("  FAIL testable but never emitted: " + phantom);
        }

        // Also confirm the specific literal that regressed once already.
        if (emitted.contains("drowning") && !emitted.contains("drowning_half")) {
            System.out.println("  ok   drowning event uses the name MateEngine understands");
        } else {
            fail = 1;
            System.out.println("  FAIL drowning event name is wrong: " + emitted);
        }

        // Player-visible names must be placeholders, not baked-in English.
        // Hard-coding them made the test command show English names while normal
        // play showed localized ones on the same client.
        Set<String> nameLiterals = new TreeSet<>();
        for (Map.Entry<String, List<String>> e : ClassStrings.perClass(jar).entrySet()) {
            if (!e.getKey().endsWith("/TestPayloads.class")) continue;
            for (String s : e.getValue()) {
                if (s.contains("\"name\":\"") || s.contains("\"biome\":\"")) {
                    nameLiterals.add(s);
                }
            }
        }
        boolean usesPlaceholders = true;
        for (String s : nameLiterals) {
            if (!s.contains("<entity>") && !s.contains("<biome>")) {
                usesPlaceholders = false;
            }
        }
        if (!nameLiterals.isEmpty() && usesPlaceholders) {
            System.out.println("  ok   player-visible names are placeholders (" + nameLiterals.size() + " templates)");
        } else {
            fail = 1;
            System.out.println("  FAIL hard-coded display names in TestPayloads: " + nameLiterals);
        }

        System.out.println(fail == 0 ? "PAYLOAD TABLE OK" : "PAYLOAD TABLE FAILED");
        if (fail != 0) System.exit(1);
    }

    /**
     * Collects the {@code type} values of every JSON payload literal in the
     * named class. Handles the two shapes javac produces: a fully folded string
     * literal, and the leading fragment of a runtime concatenation.
     */
    static Set<String> typesIn(Path jar, String simpleClassName) throws Exception {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, List<String>> e : ClassStrings.perClass(jar).entrySet()) {
            if (!e.getKey().endsWith("/" + simpleClassName + ".class")) continue;
            for (String s : e.getValue()) {
                if (!looksLikePayload(s)) continue;
                String type = extractType(s);
                if (type != null) out.add(type);
            }
        }
        return out;
    }

    static boolean looksLikePayload(String s) {
        return s != null && s.startsWith("{\"type\":\"");
    }

    /** Pulls the value of the leading {@code "type":"..."} field. */
    static String extractType(String payload) {
        String prefix = "{\"type\":\"";
        if (!payload.startsWith(prefix)) return null;
        int end = payload.indexOf('"', prefix.length());
        if (end < 0) return null;
        return payload.substring(prefix.length(), end);
    }
}
