package app.unichat.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Entities;

/** What a message says in HTML: formatted_body as it came (ui/HtmlView draws it), and link previews written the same way. */
final class MessageHtml {
    private static final String BRIDGED_EMBED = "class=\"discord-embed";

    private MessageHtml() {}

    static String of(JsonNode content) {
        return "org.matrix.custom.html".equals(content.path("format").asText()) ? content.path("formatted_body").asText(null) : null;
    }

    /** Previews of com.beeper.linkpreviews, each a quote with its title, text and picture; null if there are none. */
    static String previews(JsonNode content) {
        StringBuilder out = new StringBuilder();
        for (JsonNode p : content.path("com.beeper.linkpreviews")) {
            String title = p.path("og:title").asText("");
            String text = p.path("og:description").asText("");
            String image = p.path("og:image").asText("");
            if (title.isEmpty() && text.isEmpty() && image.isEmpty()) continue;
            String url = p.path("og:url").asText(p.path("matched_url").asText(""));
            out.append("<blockquote>");
            if (!title.isEmpty()) {
                out.append("<p><a href=\"").append(Entities.escape(url)).append("\"><strong>").append(Entities.escape(title)).append("</strong></a></p>");
            }
            if (!text.isEmpty()) out.append("<p>").append(Entities.escape(text)).append("</p>");
            if (!image.isEmpty()) {
                // Its size when the bridge gives it, so the box is the picture's shape before it loads (no jump).
                int w = p.path("og:image:width").asInt(0), h = p.path("og:image:height").asInt(0);
                out.append("<img src=\"").append(Entities.escape(image)).append('"');
                if (w > 0 && h > 0) out.append(" width=\"").append(w).append("\" height=\"").append(h).append('"');
                out.append('>');
            }
            out.append("</blockquote>");
        }
        return out.isEmpty() ? null : out.toString();
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
