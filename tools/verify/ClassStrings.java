import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Minimal Java class-file constant-pool reader.
 * Extracts UTF-8 constants (and therefore string literals) from .class files
 * without needing to load the class, so we can audit what a mod jar contains
 * even when its dependencies are absent.
 */
public final class ClassStrings {

    public static List<String> fromJar(Path jar) throws IOException {
        List<String> out = new ArrayList<>();
        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(Files.newInputStream(jar))) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.getName().endsWith(".class")) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = zin.read(buf)) > 0) bos.write(buf, 0, r);
                out.addAll(fromClass(bos.toByteArray()));
            }
        }
        return out;
    }

    /**
     * Same scan, but grouped by archive entry so callers can tell which class a
     * constant came from.
     */
    public static Map<String, List<String>> perClass(Path jar) throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(Files.newInputStream(jar))) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.getName().endsWith(".class")) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = zin.read(buf)) > 0) bos.write(buf, 0, r);
                out.put(e.getName(), fromClass(bos.toByteArray()));
            }
        }
        return out;
    }

    public static List<String> fromClass(byte[] b) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
        in.readUnsignedShort(); // minor
        in.readUnsignedShort(); // major

        int cpCount = in.readUnsignedShort();
        String[] utf8 = new String[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: // CONSTANT_Utf8
                    utf8[i] = in.readUTF();
                    break;
                case 7: case 8: case 16: case 19: case 20:
                    in.readUnsignedShort();
                    break;
                case 15:
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                    in.readInt();
                    break;
                case 5: case 6:
                    in.readLong();
                    i++; // takes two slots
                    break;
                default:
                    throw new IOException("unknown constant pool tag " + tag + " at index " + i);
            }
        }
        List<String> out = new ArrayList<>();
        for (String s : utf8) {
            if (s != null) out.add(s);
        }
        return out;
    }

    /** Convenience: every string literal that looks like a MateSignal payload. */
    public static List<String> payloads(Path jar) throws IOException {
        List<String> out = new ArrayList<>();
        for (String s : fromJar(jar)) {
            if (s.startsWith("{\"type\"") && s.endsWith("}")) out.add(s);
        }
        Collections.sort(out);
        return out;
    }

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        List<String> all = fromJar(jar);
        System.out.println("utf8 constants: " + all.size());
        List<String> types = new ArrayList<>();
        for (String s : all) {
            if (s.startsWith("{\"type\"")) types.add(s);
        }
        Collections.sort(types);
        System.out.println("payload templates: " + types.size());
        for (String t : types) System.out.println("  " + t);
        if (args.length > 1 && args[1].equals("--all")) {
            System.out.println("--- all constants ---");
            Collections.sort(all);
            for (String s : all) System.out.println("  " + s);
        }
    }
}
