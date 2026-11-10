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
import java.util.Set;

/**
 * Pinned versions of the native server stack ({@code versions.env}, bundled with the app so updating Unichat
 * brings new bridge versions). Bridge lines: {@code <name>=<owner/repo>@<tag>:<asset prefix>}. Where a platform has
 * no official builds (Windows), the bridges come from Unichat's own release ({@code NATIVES=<owner/repo>@<tag>},
 * made by .github/workflows/natives.yml), all but those in {@code NATIVES_UNBUILT}. The SHA-256 of every file it may
 * download is pinned beside it ({@code sha256sums.txt}, written by native/pin-hashes.sh): one {@code <sha256>  <key>}
 * line per {@link #pinKey}.
 */
public final class Manifest {
    /** One bridge release; the downloaded file is {@code <assetPrefix>-<os>-<arch>}. */
    public record Component(String name, String repo, String tag, String assetPrefix) {
        public String asset(Platform p) {
            return assetPrefix + "-" + p.os() + "-" + p.arch();
        }

        public String releaseUrl() {
            return url(repo + "@" + tag);
        }
    }

    private final Map<String, Component> bridges;
    private final Map<String, String> settings;
    private final Map<String, String> pinned;

    private Manifest(Map<String, Component> bridges, Map<String, String> settings, Map<String, String> pinned) {
        this.bridges = bridges;
        this.settings = settings;
        this.pinned = pinned;
    }

    public static Manifest bundled() {
        return parse(resource("versions.env"), resource("sha256sums.txt"));
    }

    private static String resource(String name) {
        try (InputStream in = Objects.requireNonNull(Manifest.class.getResourceAsStream(name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A manifest with nothing pinned: it can't install anything. */
    public static Manifest parse(String text) {
        return parse(text, "");
    }

    /** {@code pinnedSums}: the pinned hashes, in the format of {@link #parseSha256Sums}, keyed by {@link #pinKey}. */
    public static Manifest parse(String text, String pinnedSums) {
        Map<String, Component> bridges = new LinkedHashMap<>();
        Map<String, String> settings = new HashMap<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 1) throw new IllegalArgumentException("Bad manifest line: " + line);
            String key = line.substring(0, eq), value = line.substring(eq + 1);
            if (key.equals(key.toUpperCase())) {
                settings.put(key, value);
                continue;
            }
            int at = value.indexOf('@'), colon = value.lastIndexOf(':');
            if (at < 1 || colon < at) throw new IllegalArgumentException("Bad manifest line: " + line);
            bridges.put(key, new Component(key, value.substring(0, at), value.substring(at + 1, colon), value.substring(colon + 1)));
        }
        return new Manifest(Collections.unmodifiableMap(bridges), settings, parseSha256Sums(pinnedSums));
    }

    /** Null when the bridge isn't in the manifest. */
    public Component bridge(String name) { return bridges.get(name); }
    public Map<String, Component> bridges() { return bridges; }
    public String tuwunelVersion() { return settings.get("TUWUNEL_VERSION"); }

    /** The homeserver as a download: Unichat's own build, in the natives release (tuwunel has none for Mac or Windows). */
    public Component tuwunel() {
        return new Component("tuwunel", "", tuwunelVersion(), "tuwunel");
    }

    /** Where {@code c}'s files for {@code p} are: the bridge's own release, or Unichat's where it has none. */
    public String releaseUrl(Component c, Platform p) {
        return url(release(c, p));
    }

    /** Unichat's release with what it builds itself: the homeserver, and the bridges for Windows. */
    public String nativesUrl() {
        return url(settings.get("NATIVES"));
    }

    /** The release {@code c}'s files for {@code p} come from, as {@code <owner/repo>@<tag>}. */
    private String release(Component c, Platform p) {
        return p.officialBuilds() && !"tuwunel".equals(c.name()) ? c.repo() + "@" + c.tag() : settings.get("NATIVES");
    }

    private static String url(String release) {
        if (release == null || release.indexOf('@') < 1) return null;
        int at = release.indexOf('@');
        return "https://github.com/" + release.substring(0, at) + "/releases/download/" + release.substring(at + 1);
    }

    /**
     * How {@code c}'s file for {@code p} is named in the pinned sums: {@code <owner/repo>@<tag>/<asset>}, e.g.
     * {@code mautrix/whatsapp@v0.2610.0/mautrix-whatsapp-darwin-arm64}, so a version bump needs a new pin.
     */
    public String pinKey(Component c, Platform p) {
        return release(c, p) + "/" + c.asset(p);
    }

    /** The SHA-256 the app expects for {@code c}'s file for {@code p}, or null when it isn't pinned (never installed). */
    public String sha256(Component c, Platform p) {
        return pinned.get(pinKey(c, p));
    }

    /** Whether {@code bridge} runs on {@code p}: Signal's doesn't build for Windows yet. */
    public boolean builds(String bridge, Platform p) {
        return bridges.containsKey(bridge) && (p.officialBuilds() || !unbuilt().contains(bridge));
    }

    private Set<String> unbuilt() {
        return Set.copyOf(java.util.Arrays.asList(settings.getOrDefault("NATIVES_UNBUILT", "").strip().split("\\s*,\\s*")));
    }

    /** Checksum file: {@code <sha256>  <name>} or {@code <sha256> *<name>} per line; {@code #} starts a comment. */
    public static Map<String, String> parseSha256Sums(String text) {
        Map<String, String> out = new HashMap<>();
        for (String line : text.split("\n")) {
            String[] parts = line.strip().split("\\s+", 2);
            if (parts.length < 2 || parts[0].startsWith("#")) continue;
            String name = parts[1].startsWith("*") ? parts[1].substring(1) : parts[1];
            out.put(name, parts[0].toLowerCase());
        }
        return out;
    }
}
