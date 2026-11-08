package app.unichat.update;

import app.unichat.support.AppVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;

/** The rules of the update check, with no network or settings: what GitHub said, when to ask again, what to show. */
public final class UpdatePolicy {
    /** At most one question a day. */
    static final Duration EVERY = Duration.ofHours(24);
    /** Where releases are listed, when GitHub's answer has no usable page. */
    static final String RELEASES = "https://github.com/vaoan/unichat/releases";
    private static final ObjectMapper JSON = new ObjectMapper();

    private UpdatePolicy() {}

    /** The newest release in GitHub's "latest release" answer, or null when it isn't one (no tag, not JSON). */
    public static Release parse(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            String tag = root == null ? "" : root.path("tag_name").asText("").strip();
            if (tag.isEmpty()) return null;
            String url = root.path("html_url").asText("");
            // The page opens in the browser: only ever GitHub's own, never whatever an answer could put there.
            if (!url.startsWith("https://github.com/")) url = RELEASES;
            return new Release(tag, url);
        } catch (Exception e) {
            return null;
        }
    }

    /** True when a day has passed since {@code lastMillis} (0: never asked), or the clock went back past it. */
    public static boolean due(long lastMillis, long nowMillis) {
        return lastMillis <= 0 || nowMillis < lastMillis || nowMillis - lastMillis >= EVERY.toMillis();
    }

    /** Whether to tell the person about {@code latest}: newer than what runs, and not the version they dismissed. */
    public static boolean shouldShow(Release latest, String current, String dismissed) {
        return latest != null && AppVersion.isNewer(latest.version(), current) && !AppVersion.same(latest.version(), dismissed);
    }
}
