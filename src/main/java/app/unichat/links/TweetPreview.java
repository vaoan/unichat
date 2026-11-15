package app.unichat.links;

import app.unichat.model.LinkMedia;
import app.unichat.model.LinkPreview;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A post on X, by FxTwitter's public API: X shows its pages only to a browser that runs them, and FixupX gives the
 * page's video only to the bots it knows (Telegram's), sending everyone else on to x.com.
 */
final class TweetPreview {
    private static final Set<String> HOSTS = Set.of("x.com", "twitter.com", "mobile.twitter.com", "fixupx.com", "fxtwitter.com",
            "vxtwitter.com", "fixvx.com", "twittpr.com", "nitter.net");
    private static final Pattern PATH = Pattern.compile("^/([A-Za-z0-9_]{1,15})/status(?:es)?/(\\d{1,25})(?:/.*)?$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private TweetPreview() {}

    /** Where FxTwitter tells about the post a link leads to; null when the link isn't a post on X. */
    static String api(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            Matcher m = PATH.matcher(u.getPath() == null ? "" : u.getPath());
            if (!HOSTS.contains(host) || !m.matches()) return null;
            return "https://api.fxtwitter.com/" + m.group(1) + "/status/" + m.group(2);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static LinkPreview parse(String json, String url) throws IOException {
        JsonNode t = JSON.readTree(json).path("tweet");
        if (t.isMissingNode()) return null;
        JsonNode author = t.path("author");
        String name = author.path("name").asText(""), handle = author.path("screen_name").asText("");
        String title = handle.isEmpty() ? name : name + " (@" + handle + ")";
        JsonNode media = t.path("media").path("all").path(0);
        String type = media.path("type").asText("");
        boolean moving = type.equals("gif") || type.equals("video");
        String image = web(moving ? media.path("thumbnail_url").asText("") : media.path("url").asText(""));
        LinkMedia video = null;
        String videoUrl = web(media.path("url").asText(""));
        if (moving && videoUrl != null) video = new LinkMedia(LinkMedia.Kind.VIDEO, videoUrl, "video/mp4", 0);
        return new LinkPreview(url, "X", PagePreview.cut(title), PagePreview.cut(t.path("text").asText("")), image,
                PagePreview.number(media.path("width").asText("")), PagePreview.number(media.path("height").asText("")),
                video, type.equals("gif"));
    }

    private static String web(String url) {
        return url.startsWith("https://") || url.startsWith("http://") ? url : null;
    }
}
