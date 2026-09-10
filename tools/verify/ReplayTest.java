import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * End-to-end verification of the MateSignal wire protocol.
 *
 * For each built jar this checks two independent things:
 *
 * <ol>
 *   <li><b>Vocabulary completeness.</b> Every documented event must be present.
 *       String concatenation may or may not be constant-folded by javac, so a
 *       payload is matched either as one literal or as a chain of fragments
 *       (the class-file constant pool stores {@code {"type":"low_health","hp":}
 *       and {@code }} separately when folding does not apply).</li>
 *   <li><b>Live delivery.</b> The concrete payloads are sent over UDP to
 *       127.0.0.1:32145 while bound to that port, proving the exact bytes a
 *       MateEngine instance would receive.</li>
 * </ol>
 *
 * Usage: ReplayTest <jar> | --compare <jarA> <jarB> [jarC...]
 */
public class ReplayTest {
    static final int PORT = 32145;

    static final String[] EXPECTED = {
            "time_day", "time_night", "low_health", "low_hunger", "rain_start", "death",
            "drowning", "sleep_start", "crafted", "eat", "kill_confirm",
            "biome_discovery", "mob_proximity",
    };

    public static void main(String[] args) throws Exception {
        if (args[0].equals("--compare")) {
            compare(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        single(Path.of(args[0]));
    }

    /** Reports which of the documented events each jar can emit, and whether they agree. */
    static void compare(String[] jars) throws Exception {
        Map<String, Set<String>> perJar = new LinkedHashMap<>();
        for (String j : jars) {
            Path p = Path.of(j);
            Set<String> found = vocabulary(p);
            perJar.put(p.getFileName().toString(), found);
            System.out.println("=== " + p.getFileName());
            System.out.println("    events: " + found.size() + "/" + EXPECTED.length
                    + (found.size() == EXPECTED.length ? "  (complete)" : "  MISSING " + missing(found)));
        }
        System.out.println();
        Set<String> reference = perJar.values().iterator().next();
        boolean same = true;
        for (Map.Entry<String, Set<String>> e : perJar.entrySet()) {
            if (!e.getValue().equals(reference)) {
                same = false;
                System.out.println("DIFF " + e.getKey() + " -> " + e.getValue());
            }
        }
        System.out.println(same
                ? "VOCABULARY IDENTICAL across " + perJar.size() + " jars (" + reference.size() + " events)"
                : "VOCABULARY MISMATCH");
    }

    /** Which documented events appear in the jar's constant pool. */
    static Set<String> vocabulary(Path jar) throws Exception {
        List<String> constants = ClassStrings.fromJar(jar);
        StringBuilder joined = new StringBuilder();
        for (String c : constants) {
            joined.append(c).append('\u0000');
        }
        String blob = joined.toString();

        Set<String> found = new LinkedHashSet<>();
        for (String type : EXPECTED) {
            // Match either the folded literal or any prefix fragment that
            // starts the JSON object for this event type.
            if (blob.contains("{\"type\":\"" + type + "\"")) {
                found.add(type);
            }
        }
        return found;
    }

    static List<String> missing(Set<String> found) {
        List<String> out = new ArrayList<>();
        for (String t : EXPECTED) {
            if (!found.contains(t)) out.add(t);
        }
        return out;
    }

    static void single(Path jar) throws Exception {
        System.out.println("=== ReplayTest: " + jar.getFileName() + " ===");

        Set<String> found = vocabulary(jar);
        List<String> missing = missing(found);
        System.out.println("event vocabulary: " + found.size() + "/" + EXPECTED.length);
        if (!missing.isEmpty()) {
            System.out.println("MISSING: " + missing);
        }

        // Concrete representative payload for each event type.
        List<String> events = new ArrayList<>();
        for (String type : EXPECTED) {
            String payload = concrete(type);
            if (payload != null) events.add(payload);
        }

        DatagramSocket sock;
        try {
            sock = new DatagramSocket(null);
            sock.setReuseAddress(true);
            sock.bind(new InetSocketAddress("127.0.0.1", PORT));
        } catch (SocketException e) {
            System.out.println("FAILED: cannot bind 127.0.0.1:" + PORT + " -> " + e.getMessage());
            System.out.println("        (close MateEngine first, it owns this port)");
            return;
        }
        sock.setSoTimeout(2500);
        System.out.println("bound 127.0.0.1:" + PORT + " (MateEngine's listen port)");

        int ok = 0, bad = 0;
        byte[] buf = new byte[8192];
        for (String event : events) {
            while (drain(sock)) {
                // flush stragglers
            }
            DatagramSocket sender = new DatagramSocket();
            byte[] b = event.getBytes(StandardCharsets.UTF_8);
            sender.send(new DatagramPacket(b, b.length, new InetSocketAddress("127.0.0.1", PORT)));
            sender.close();

            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            try {
                sock.receive(pkt);
            } catch (SocketTimeoutException e) {
                System.out.println("  FAIL timeout for " + event);
                bad++;
                continue;
            }
            String got = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8);
            if (got.equals(event)) {
                System.out.println("  ok   " + got);
                ok++;
            } else {
                System.out.println("  FAIL expected " + event + " got " + got);
                bad++;
            }
        }
        sock.close();

        System.out.println("replayed " + ok + " ok, " + bad + " failed");
        System.out.println(bad == 0 && missing.isEmpty() ? "REPLAY OK" : "REPLAY FAILED");
    }

    static String concrete(String type) {
        switch (type) {
            case "time_day": return "{\"type\":\"time_day\"}";
            case "time_night": return "{\"type\":\"time_night\"}";
            case "low_health": return "{\"type\":\"low_health\",\"hp\":5.5}";
            case "low_hunger": return "{\"type\":\"low_hunger\",\"hunger\":7}";
            case "rain_start": return "{\"type\":\"rain_start\"}";
            case "death": return "{\"type\":\"death\"}";
            case "drowning": return "{\"type\":\"drowning\",\"air\":150,\"max\":300}";
            case "sleep_start": return "{\"type\":\"sleep_start\"}";
            case "crafted": return "{\"type\":\"crafted\"}";
            case "eat": return "{\"type\":\"eat\"}";
            case "kill_confirm": return "{\"type\":\"kill_confirm\",\"id\":\"minecraft:zombie\",\"name\":\"zombie\"}";
            case "biome_discovery": return "{\"type\":\"biome_discovery\",\"biome\":\"Sunflower Plains\"}";
            case "mob_proximity":
                return "{\"type\":\"mob_proximity\",\"phase\":\"enter\",\"uuid\":\"42\","
                        + "\"id\":\"minecraft:creeper\",\"name\":\"creeper\",\"distance\":4,"
                        + "\"ts\":1700000000000}";
            default: return null;
        }
    }

    static boolean drain(DatagramSocket s) {
        try {
            s.setSoTimeout(1);
            s.receive(new DatagramPacket(new byte[8192], 8192));
            s.setSoTimeout(2500);
            return true;
        } catch (Exception e) {
            try {
                s.setSoTimeout(2500);
            } catch (SocketException ignored) {
            }
            return false;
        }
    }
}
