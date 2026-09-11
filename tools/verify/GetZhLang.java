import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;

/**
 * Downloads the official Simplified Chinese language files for 1.12.2 and
 * 1.21.1 so the port's name tables can be checked against real translations.
 *
 * 1.12.2 ships lang files inside the client jar; the newer versions keep them
 * in the client jar too, but with JSON formatting.
 */
public class GetZhLang {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);

        // 1.12.2 client jar from Mojang's CDN (resolved via the version manifest).
        //
        // Any proxy comes from the usual system properties so nothing is tied to
        // one machine:
        //   java -Dhttps.proxyHost=... -Dhttps.proxyPort=... GetZhLang <out>
        // With no properties set this connects directly.
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL);
        String proxyHost = System.getProperty("https.proxyHost");
        String proxyPort = System.getProperty("https.proxyPort");
        if (proxyHost != null && !proxyHost.isEmpty() && proxyPort != null && !proxyPort.isEmpty()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, Integer.parseInt(proxyPort))));
            System.out.println("using proxy " + proxyHost + ":" + proxyPort);
        }
        HttpClient c = builder.build();

        String manifest = get(c, "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        String versionUrl = findVersionUrl(manifest, "1.12.2");
        System.out.println("1.12.2 version json: " + versionUrl);
        String vj = get(c, versionUrl);
        String clientUrl = findClientUrl(vj);
        System.out.println("1.12.2 client jar:  " + clientUrl);

        Path jar = out.resolve("client-1.12.2.jar");
        if (!Files.exists(jar)) {
            download(c, clientUrl, jar);
        }
        System.out.println("client jar size: " + Files.size(jar));

        // Pull out the language files we care about.
        for (String lang : new String[]{"en_us", "zh_cn"}) {
            String entry = "assets/minecraft/lang/" + lang + ".lang";
            Path dest = out.resolve(lang + ".lang");
            if (extract(jar, entry, dest)) {
                System.out.println("extracted " + entry + " -> " + dest
                        + " (" + Files.size(dest) + " bytes)");
            } else {
                System.out.println("MISSING in jar: " + entry);
            }
        }
    }

    static String findVersionUrl(String manifest, String id) throws Exception {
        int i = manifest.indexOf("\"id\":\"" + id + "\"");
        if (i < 0) i = manifest.indexOf("\"id\": \"" + id + "\"");
        if (i < 0) throw new IllegalStateException(id + " not in manifest");
        int u = manifest.indexOf("\"url\":", i);
        int q1 = manifest.indexOf('"', u + 6);
        int q2 = manifest.indexOf('"', q1 + 1);
        return manifest.substring(q1 + 1, q2);
    }

    static String findClientUrl(String versionJson) throws Exception {
        int d = versionJson.indexOf("\"client\":");
        int u = versionJson.indexOf("\"url\":", d);
        int p1 = versionJson.indexOf('"', u + 6);
        int p2 = versionJson.indexOf('"', p1 + 1);
        return versionJson.substring(p1 + 1, p2);
    }

    static String get(HttpClient c, String url) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "MateSignal-Port/1.0")
                .GET().build();
        return c.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    static void download(HttpClient c, String url, Path dest) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "MateSignal-Port/1.0")
                .GET().build();
        HttpResponse<Path> resp = c.send(r, HttpResponse.BodyHandlers.ofFile(dest));
        if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
    }

    static boolean extract(Path jar, String entry, Path dest) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.getName().equals(entry)) continue;
                Files.copy(zin, dest, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
        }
        return false;
    }
}
