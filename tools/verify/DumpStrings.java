import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Prints the string constants of one class, one per line, so a shell can grep
 * for exact values. Uses ClassStrings, which reads the constant pool the way the
 * JVM does (length-prefixed UTF-8) instead of scraping raw bytes, so short
 * literals that a byte scan misses are reported reliably.
 *
 * Usage: DumpStrings <classesDirOrJar> <binaryClassName> [filter]
 */
public class DumpStrings {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        String cls = args[1];
        String filter = args.length > 2 ? args[2] : null;

        List<String> all;
        if (Files.isDirectory(root)) {
            Path f = root.resolve(cls.replace('.', '/') + ".class");
            all = ClassStrings.fromClass(Files.readAllBytes(f));
        } else {
            all = ClassStrings.perClass(root).getOrDefault(cls.replace('.', '/') + ".class", List.of());
        }

        for (String s : all) {
            if (filter == null || s.contains(filter)) {
                System.out.println(s);
            }
        }
    }
}
