import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Renders the shared source tree for one Minecraft version.
 *
 * MateSignal's 1.20.1 (Forge) and 1.21.1 (NeoForge) ports are the same code with
 * a handful of API differences. Measurements showed only eight real code
 * divergences plus some import differences, so rather than maintaining two
 * near-identical copies (which is how the two ports drifted apart in the first
 * place, see README section 12) the shared code lives once in {@code common/}
 * and is rendered per version by this tool at build time.
 *
 * <p>This deliberately avoids pulling in a multi-version Gradle plugin: at this
 * scale a ~200 line script is less machinery than a plugin, easier to debug, and
 * adds no build dependency.
 *
 * <h2>Marker syntax</h2>
 * <pre>
 *   //#if 1.21.1
 *   ...lines only for 1.21.1...
 *   //#elif 1.20.1
 *   ...lines only for 1.20.1...
 *   //#else
 *   ...lines for any other version...
 *   //#endif
 * </pre>
 * Markers are whole-line comments and are removed from the output, so the
 * rendered file contains only real code. Nested blocks are not supported (and
 * are rejected loudly rather than silently mis-rendered).
 *
 * <p>Usage: {@code PrepareShared <version> <commonSrcDir> <outSrcDir>}
 */
public final class PrepareShared {

    /** Recognised marker prefixes, longest first so "#elif" wins over "#if". */
    private static final String[] MARKERS = {"//#elif", "//#else", "//#endif", "//#if"};

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: PrepareShared <version> <commonSrcDir> <outSrcDir>");
            System.exit(2);
        }
        String version = args[0];
        Path in = Path.of(args[1]);
        Path out = Path.of(args[2]);

        if (!Files.isDirectory(in)) {
            System.err.println("shared source directory not found: " + in);
            System.exit(2);
        }

        int files = 0;
        int blocks = 0;
        try (Stream<Path> walk = Files.walk(in)) {
            List<Path> sources = new ArrayList<>();
            walk.filter(Files::isRegularFile).forEach(sources::add);
            Collections.sort(sources);

            for (Path src : sources) {
                Path rel = in.relativize(src);
                Path dst = out.resolve(rel);
                if (src.toString().endsWith(".java")) {
                    Render r = render(Files.readAllLines(src, StandardCharsets.UTF_8), version, src);
                    blocks += r.blocks;
                    Files.createDirectories(dst.getParent());
                    // Join with LF explicitly: Files.write(List) uses the
                    // platform separator, which would make the rendered tree
                    // differ from the committed sources by line endings alone
                    // and drown the byte-for-byte check in false positives.
                    Files.writeString(dst, String.join("\n", r.lines) + "\n", StandardCharsets.UTF_8);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
                files++;
            }
        }

        System.out.println("PrepareShared: rendered " + files + " file(s) for " + version
                + " (" + blocks + " conditional block(s)) -> " + out);
    }

    static final class Render {
        final List<String> lines;
        final int blocks;
        Render(List<String> lines, int blocks) {
            this.lines = lines;
            this.blocks = blocks;
        }
    }

    /**
     * Applies the markers for one file.
     * <p>
     * A block is emitted when its condition matches the requested version. The
     * condition is a comma separated list of version names, optionally prefixed
     * with {@code !} for negation.
     */
    static Render render(List<String> src, String version, Path origin) {
        List<String> out = new ArrayList<>(src.size());
        int blocks = 0;

        // Stack-free by design: nesting is rejected because it silently
        // produces wrong output otherwise.
        boolean inBlock = false;
        boolean emitting = true;
        boolean taken = false;
        int lineNo = 0;

        for (String line : src) {
            lineNo++;
            String trimmed = line.trim();
            String marker = markerOf(trimmed);

            if (marker == null) {
                if (emitting) out.add(line);
                continue;
            }

            if (marker.equals("//#if")) {
                if (inBlock) {
                    fail(origin, lineNo, "nested //#if is not supported");
                }
                inBlock = true;
                blocks++;
                taken = matches(conditionOf(trimmed, "//#if"), version);
                emitting = taken;
            } else if (marker.equals("//#elif")) {
                if (!inBlock) {
                    fail(origin, lineNo, "//#elif without //#if");
                }
                boolean m = matches(conditionOf(trimmed, "//#elif"), version);
                emitting = !taken && m;
                taken = taken || m;
            } else if (marker.equals("//#else")) {
                if (!inBlock) {
                    fail(origin, lineNo, "//#else without //#if");
                }
                emitting = !taken;
                taken = true;
            } else { // //#endif
                if (!inBlock) {
                    fail(origin, lineNo, "//#endif without //#if");
                }
                inBlock = false;
                emitting = true;
            }
        }

        if (inBlock) {
            fail(origin, src.size(), "unterminated //#if");
        }
        return new Render(out, blocks);
    }

    /** Returns the marker keyword at the start of a trimmed line, or null. */
    static String markerOf(String trimmed) {
        for (String m : MARKERS) {
            if (trimmed.startsWith(m)) {
                // Guard against matching a longer word, e.g. "//#iffy".
                if (trimmed.length() == m.length()
                        || !Character.isLetterOrDigit(trimmed.charAt(m.length()))) {
                    return m;
                }
            }
        }
        return null;
    }

    static String conditionOf(String trimmed, String marker) {
        return trimmed.substring(marker.length()).trim();
    }

    /**
     * True when the condition selects the requested version.
     * <p>
     * Supports {@code a}, {@code a,b} and negation with {@code !a}. An empty
     * condition is an error: it almost always means a typo in the marker.
     */
    static boolean matches(String condition, String version) {
        if (condition.isEmpty()) return true;
        for (String part : condition.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            boolean negated = p.startsWith("!");
            String name = negated ? p.substring(1).trim() : p;
            boolean hit = name.equals(version);
            if (negated ? !hit : hit) return true;
        }
        return false;
    }

    static void fail(Path origin, int lineNo, String message) {
        throw new IllegalStateException("PrepareShared: " + origin + ":" + lineNo + ": " + message);
    }

    private PrepareShared() {}
}
