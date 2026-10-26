package app.unichat.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pinned versions of the native server stack ({@code versions.env}, bundled with the app so updating Unichat
 * brings new bridge versions). Bridge lines: {@code <name>=<owner/repo>@<tag>:<asset prefix>}.
 */
public final class Manifest {
    /** One bridge release; the downloaded file is {@code <assetPrefix>-<os>-<arch>}. */
    public record Component(String name, String repo, String tag, String assetPrefix) {
        public String asset(Platform p) {
            return assetPrefix + "-" + p.os() + "-" + p.arch();
        }

        public String releaseUrl() {
            return "https://github.com/" + repo + "/releases/download/" + tag;
        }

        public String assetUrl(Platform p) {
            return releaseUrl() + "/" + asset(p);
        }
    }

    private final Map<String, Component> bridges;
    private final String tuwunelVersion;

    private Manifest(Map<String, Component> bridges, String tuwunelVersion) {
        this.bridges = bridges;
        this.tuwunelVersion = tuwunelVersion;
    }

    public static Manifest bundled() {
        try (InputStream in = Objects.requireNonNull(Manifest.class.getResourceAsStream("versions.env"))) {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Manifest parse(String text) {
        Map<String, Component> bridges = new LinkedHashMap<>();
        String tuwunel = null;
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 1) throw new IllegalArgumentException("Bad manifest line: " + line);
            String key = line.substring(0, eq), value = line.substring(eq + 1);
            if (key.equals("TUWUNEL_VERSION")) {
                tuwunel = value;
                continue;
            }
            int at = value.indexOf('@'), colon = value.lastIndexOf(':');
            if (at < 1 || colon < at) throw new IllegalArgumentException("Bad manifest line: " + line);
            bridges.put(key, new Component(key, value.substring(0, at), value.substring(at + 1, colon), value.substring(colon + 1)));
        }
        return new Manifest(Collections.unmodifiableMap(bridges), tuwunel);
    }

    /** Null when the bridge isn't in the manifest. */
    public Component bridge(String name) { return bridges.get(name); }
    public Map<String, Component> bridges() { return bridges; }
    public String tuwunelVersion() { return tuwunelVersion; }

    /** Release checksum file: {@code <sha256>  <name>} or {@code <sha256> *<name>} per line. */
    public static Map<String, String> parseSha256Sums(String text) {
        Map<String, String> out = new HashMap<>();
        for (String line : text.split("\n")) {
            String[] parts = line.strip().split("\\s+", 2);
            if (parts.length < 2) continue;
            String name = parts[1].startsWith("*") ? parts[1].substring(1) : parts[1];
            out.put(name, parts[0].toLowerCase());
        }
        return out;
    }
}
