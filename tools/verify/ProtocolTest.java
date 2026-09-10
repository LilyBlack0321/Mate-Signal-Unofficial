import java.io.IOException;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * MateSignal protocol verifier.
 *
 * Two modes:
 * <ul>
 *   <li>{@code --listen [port]} — binds the MateEngine UDP port and prints every
 *       datagram as JSON. Used to watch what the real MateEngine process
 *       receives while a mod is active.</li>
 *   <li>{@code <jar> [classpath...]} — reflectively invokes the shipped jar's own
 *       packet-sending method and confirms every documented payload arrives
 *       verbatim on 127.0.0.1:32145.</li>
 * </ul>
 */
public class ProtocolTest {
    static final int DEFAULT_PORT = 32145;

    /** Payloads the mod is expected to be able to emit. */
    static final String[] PAYLOADS = {
            "{\"type\":\"time_day\"}",
            "{\"type\":\"time_night\"}",
            "{\"type\":\"low_health\",\"hp\":5.5}",
            "{\"type\":\"low_hunger\",\"hunger\":7}",
            "{\"type\":\"rain_start\"}",
            "{\"type\":\"death\"}",
            "{\"type\":\"drowning_half\",\"air\":150,\"max\":300}",
            "{\"type\":\"sleep_start\"}",
            "{\"type\":\"crafted\"}",
            "{\"type\":\"eat\"}",
            "{\"type\":\"kill_confirm\",\"id\":\"minecraft:zombie\",\"name\":\"zombie\"}",
            "{\"type\":\"biome_discovery\",\"biome\":\"Sunflower Plains\"}",
            "{\"type\":\"mob_proximity\",\"phase\":\"enter\",\"uuid\":\"42\",\"id\":\"minecraft:creeper\","
                    + "\"name\":\"creeper\",\"distance\":4,\"ts\":1700000000000}",
    };

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--listen")) {
            int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
            listen(port);
            return;
        }
        if (args.length == 0) {
            System.out.println("usage: ProtocolTest --listen [port] | <jar> [cp...]");
            return;
        }
        testJar(args);
    }

    /** Prints every datagram MateEngine would receive. */
    static void listen(int port) throws Exception {
        DatagramSocket s = new DatagramSocket(new InetSocketAddress("0.0.0.0", port));
        System.out.println("LISTENING on udp/" + port + " (Ctrl-C to stop)");
        System.out.flush();
        byte[] buf = new byte[8192];
        while (true) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            String msg = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
            System.out.println("RECV " + p.getAddress().getHostAddress() + ":" + p.getPort() + "  " + msg);
            System.out.flush();
        }
    }

    static void testJar(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        System.out.println("=== ProtocolTest: " + jar.getFileName() + " ===");

        List<String> classes = listClasses(jar);
        System.out.println("classes: " + classes);
        String mainClass = pickClass(classes);
        if (mainClass == null) {
            System.out.println("FAILED: no MateSignal implementation class found");
            return;
        }
        System.out.println("impl class: " + mainClass);

        URL[] urls = new URL[args.length];
        for (int i = 0; i < args.length; i++) {
            urls[i] = Path.of(args[i]).toUri().toURL();
        }
        URLClassLoader cl = new URLClassLoader(urls, ProtocolTest.class.getClassLoader());

        DatagramSocket listener;
        try {
            listener = new DatagramSocket(null);
            // MateEngine may already own the wildcard bind; on Windows a
            // specific-address bind can still coexist and capture the traffic.
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress("127.0.0.1", DEFAULT_PORT));
        } catch (SocketException e) {
            System.out.println("FAILED: cannot bind 127.0.0.1:" + DEFAULT_PORT + " -> " + e.getMessage());
            System.out.println("        (MateEngine owns the port; close it or use --listen)");
            return;
        }
        listener.setSoTimeout(3000);
        System.out.println("listening on 127.0.0.1:" + DEFAULT_PORT);

        Method sender = findSender(cl, mainClass);
        if (sender == null) {
            System.out.println("FAILED: no static packet-sending method found on " + mainClass);
            listener.close();
            return;
        }
        System.out.println("sender: " + mainClass + "." + sender.getName() + "(String)");
        sender.setAccessible(true);

        int ok = 0, bad = 0;
        byte[] buf = new byte[8192];
        for (String payload : PAYLOADS) {
            while (drain(listener)) {
                // discard stragglers
            }
            try {
                sender.invoke(null, payload);
            } catch (InvocationTargetException e) {
                System.out.println("  FAIL send threw: " + e.getCause());
                bad++;
                continue;
            }
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            try {
                listener.receive(pkt);
            } catch (SocketTimeoutException e) {
                System.out.println("  FAIL timeout, nothing received for " + shorten(payload));
                bad++;
                continue;
            }
            String got = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8);
            if (got.equals(payload)) {
                System.out.println("  ok   " + shorten(payload));
                ok++;
            } else {
                System.out.println("  FAIL expected " + payload);
                System.out.println("       got      " + got);
                bad++;
            }
        }
        listener.close();
        System.out.println("result: " + ok + " ok, " + bad + " failed");
        System.out.println(bad == 0 ? "PROTOCOL OK" : "PROTOCOL MISMATCH");
    }

    static boolean drain(DatagramSocket s) {
        try {
            s.setSoTimeout(1);
            DatagramPacket p = new DatagramPacket(new byte[8192], 8192);
            s.receive(p);
            s.setSoTimeout(3000);
            return true;
        } catch (Exception e) {
            try {
                s.setSoTimeout(3000);
            } catch (SocketException ignored) {
            }
            return false;
        }
    }

    static String shorten(String s) {
        return s.length() <= 78 ? s : s.substring(0, 75) + "...";
    }

    static List<String> listClasses(Path jar) throws IOException {
        List<String> out = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                if (n.endsWith(".class") && !n.contains("$")) {
                    out.add(n.substring(0, n.length() - 6).replace('/', '.'));
                }
            }
        }
        return out;
    }

    static String pickClass(List<String> classes) {
        for (String c : classes) {
            if (c.endsWith(".MateSignal")) return c;
        }
        return classes.isEmpty() ? null : classes.get(0);
    }

    static Method findSender(ClassLoader cl, String mainClass) {
        try {
            Class<?> c = Class.forName(mainClass, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == String.class
                        && Modifier.isStatic(m.getModifiers())
                        && (m.getName().equals("send") || m.getName().equals("sendRaw"))) {
                    return m;
                }
            }
        } catch (Throwable t) {
            System.out.println("  (class load failed: " + t + ")");
        }
        return null;
    }
}
