import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/**
 * Unit test for the day/night crossing detector used by every MateSignal port.
 *
 * The detector is a private static method, so it is exercised reflectively. This
 * proves the two required properties:
 *
 * <ol>
 *   <li><b>Natural flow still fires.</b> Watching time tick 1:1 from day into
 *       night (and night into day) must emit exactly one crossing.</li>
 *   <li><b>Jumps never fire.</b> Joining a world at an arbitrary time,
 *       {@code /time set}, or a lag spike must not be mistaken for a crossing.
 *       This is the bug that spammed "night is coming" bubbles.</li>
 * </ol>
 *
 * Usage: CrossingTest <classesDir> [classesDir...]
 *
 * The mod class references Minecraft types in its method signatures, so the
 * mapped Minecraft/Forge jar must also be on the classpath. Pass it with
 * -Dmc.jar=<path> (see verify.ps1, which resolves it per port).
 */
public class CrossingTest {
    static final long NIGHT_THRESHOLD = 12750L;
    static final long DAY_THRESHOLD = 23500L;

    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        // Extra classpath entries (mapped Minecraft/Forge jars) come from -Dmc.jar,
        // separated by ';'.
        List<java.net.URL> extra = new ArrayList<>();
        String mcJar = System.getProperty("mc.jar", "");
        for (String p : mcJar.split(";")) {
            if (!p.isBlank() && Files.exists(Path.of(p))) {
                extra.add(Path.of(p).toUri().toURL());
            }
        }

        for (String dir : args) {
            System.out.println("=== " + dir + " ===");
            Path root = Path.of(dir);
            if (!Files.isDirectory(root)) {
                System.out.println("  (not a directory, skipped)");
                continue;
            }
            List<java.net.URL> urls = new ArrayList<>(extra);
            urls.add(root.toUri().toURL());
            ClassLoader cl = new java.net.URLClassLoader(
                    urls.toArray(new java.net.URL[0]), CrossingTest.class.getClassLoader());
            Class<?> c;
            try {
                c = Class.forName("me.shiny.matesignal.MateSignal", false, cl);
            } catch (Throwable t) {
                System.out.println("  (class not found: " + t + ")");
                continue;
            }
            testPort(c);
            System.out.println();
        }
        System.out.println("crossing tests: " + pass + " passed, " + fail + " failed");
        System.out.println(fail == 0 ? "CROSSING OK" : "CROSSING FAILED");
        if (fail != 0) System.exit(1);
    }

    static void testPort(Class<?> cls) throws Exception {
        // Look the method up by name and parameter types only: enumerating all
        // declared methods would force resolution of unrelated signatures such
        // as the command API's, which drags in extra dependencies.
        Method crossed;
        try {
            crossed = cls.getDeclaredMethod("crossed", long.class, long.class, long.class);
        } catch (NoSuchMethodException e) {
            System.out.println("  ! crossed(dayNow, threshold, elapsed) not found");
            fail++;
            return;
        }
        crossed.setAccessible(true);

        // The tick-level gate the port applies before consulting crossed().
        long gate = gateOf(cls);

        // --- 1. natural flow must fire exactly once -------------------------
        // Walk one full day tick-by-tick and count night crossings.
        int nightHits = 0, dayHits = 0;
        for (long t = 0; t < 24000; t++) {
            if (hit(crossed, t, NIGHT_THRESHOLD, 1L)) nightHits++;
            if (hit(crossed, t, DAY_THRESHOLD, 1L)) dayHits++;
        }
        check("natural flow fires time_night exactly once", nightHits == 1, "got " + nightHits);
        check("natural flow fires time_day exactly once", dayHits == 1, "got " + dayHits);

        // --- 2. the exact reported symptom: a time jump ---------------------
        // The port only calls crossed() when elapsed passes the gate, so the
        // realistic simulation is "advance from A to B the way the game would".
        check("jump/world-join 12000 -> 13000 does NOT fire night",
                !advance(crossed, 12000, 13000, NIGHT_THRESHOLD, gate), "");
        check("jump 6000 -> 18000 does NOT fire night",
                !advance(crossed, 6000, 18000, NIGHT_THRESHOLD, gate), "");
        check("jump 18000 -> 1000 does NOT fire day",
                !advance(crossed, 18000, 1000, DAY_THRESHOLD, gate), "");
        // Control: with the gate disabled (the old upstream behaviour) the same
        // jump DOES fire, which is exactly the bug that was reported.
        check("control: gate disabled means the jump DOES fire (old bug)",
                advance(crossed, 12000, 13000, NIGHT_THRESHOLD, 0L), "");
        check("world join at 13000 (no previous sample) does NOT fire",
                !hit(crossed, 13000, NIGHT_THRESHOLD, 0L), "");
        check("backwards time does NOT fire", !hit(crossed, 12000, NIGHT_THRESHOLD, -6000L), "");

        // --- 3. a small natural window still fires --------------------------
        // dusk at 12750: window (12749, 12750]
        check("window (12749,12750] fires night", hit(crossed, 12750, NIGHT_THRESHOLD, 1L), "");
        // a slightly larger but still legitimate window
        check("window (12700,12755] fires night", hit(crossed, 12755, NIGHT_THRESHOLD, 55L), "");
        // window that ends just before the threshold must not fire
        check("window (12700,12749] does NOT fire night", !hit(crossed, 12749, NIGHT_THRESHOLD, 49L), "");

        // --- 4. midnight wrap-around during natural flow --------------------
        // 23998 -> 1 crossing midnight; the dawn threshold is 23500, already passed,
        // so day must NOT re-fire.
        check("natural wrap 23998->1 does NOT re-fire day", !hit(crossed, 1, DAY_THRESHOLD, 3L), "");

        // --- 5. the gate constant itself ------------------------------------
        check("MAX_TIME_ADVANCE_PER_TICK is 200", gate == 200L, "got " + gate);
    }

    /**
     * Simulates one tick of the port's day/night logic moving from {@code from}
     * to {@code to}: the gate is applied first, then crossed() is consulted.
     * {@code gateOverride == 0} disables the gate (the old buggy behaviour).
     */
    static boolean advance(Method crossed, long from, long to, long threshold, long gateOverride)
            throws Exception {
        long elapsed = to - from;
        if (gateOverride > 0L) {
            if (elapsed < 1L || elapsed > gateOverride) {
                return false; // treated as a resync, not a crossing
            }
        }
        return hit(crossed, to, threshold, elapsed);
    }

    /** Reads MAX_TIME_ADVANCE_PER_TICK so the test tracks the real constant. */
    static long gateOf(Class<?> cls) {
        try {
            Field f = cls.getDeclaredField("MAX_TIME_ADVANCE_PER_TICK");
            f.setAccessible(true);
            return f.getLong(null);
        } catch (Throwable t) {
            return -1L;
        }
    }

    static boolean hit(Method crossed, long dayNow, long threshold, long elapsed) throws Exception {
        return (Boolean) crossed.invoke(null, dayNow, threshold, elapsed);
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
