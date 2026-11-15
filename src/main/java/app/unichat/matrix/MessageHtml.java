package app.unichat.matrix;

import app.unichat.model.LinkPreview;
import app.unichat.model.Mxc;
import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.Jsoup;

import java.util.ArrayList;
import java.util.List;

/** What a message says in HTML: formatted_body as it came (ui/HtmlView draws it), and the bridge's link previews. */
final class MessageHtml {
    private static final String BRIDGED_EMBED = "class=\"discord-embed";

    private MessageHtml() {}

    static String of(JsonNode content) {
        return "org.matrix.custom.html".equals(content.path("format").asText()) ? content.path("formatted_body").asText(null) : null;
    }

    /**
     * The bridge's com.beeper.linkpreviews, for the cards under the message. Pictures only from our server: a stranger's
     * event chooses these addresses.
     */
    static List<LinkPreview> previews(JsonNode content) {
        List<LinkPreview> out = new ArrayList<>();
        for (JsonNode p : content.path("com.beeper.linkpreviews")) {
            String image = p.path("og:image").asText("");
            LinkPreview preview = new LinkPreview(p.path("og:url").asText(p.path("matched_url").asText("")), p.path("og:site_name").asText(""),
                    p.path("og:title").asText(""), p.path("og:description").asText(""), Mxc.parse(image) != null ? image : null,
                    p.path("og:image:width").asInt(0), p.path("og:image:height").asInt(0), null, false);
            if (!preview.isEmpty()) out.add(preview);
        }
        return List.copyOf(out);
    }

    /** The person's own words, for the chat list, replies and editing: the bridge repeats an embed as "> " lines last. */
    static String withoutEmbedText(String body, String html) {
        if (body == null || html == null || !html.contains(BRIDGED_EMBED)) return body;
        String[] lines = body.split("\n", -1);
        int end = lines.length;
        while (end > 0 && (lines[end - 1].isBlank() || lines[end - 1].startsWith(">"))) end--;
        return String.join("\n", java.util.Arrays.copyOf(lines, end));
    }

    static String text(String html) {
        return Jsoup.parse(html).text();
    }
}
