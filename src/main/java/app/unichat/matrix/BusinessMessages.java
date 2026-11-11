package app.unichat.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.nodes.Entities;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * WhatsApp Business messages with buttons (an order to pay, a link to open, a template's replies), which Matrix has no
 * way to show: the WhatsApp bridge marks them ({@value #MARK}) and writes each button as a "&lt;label&gt;" line before
 * "Use the WhatsApp app to click buttons". The label is often the button's internal name (review_and_pay). Pure.
 */
final class BusinessMessages {
    static final String MARK = "fi.mau.whatsapp.business_message_type";
    private static final String NOTE = "Use the WhatsApp app to click buttons";
    /** What the bridge writes for an order's picture it can't bring. */
    private static final String NO_THUMBNAIL = "Unsupported thumbnail message";
    private static final Pattern BUTTON = Pattern.compile("<([^<>\\n]{1,80})>");
    /** The same ending in formatted_body: the button lines, escaped, then the note. */
    private static final Pattern HTML_ENDING = Pattern.compile(
            "(?:\\s*<br\\s*/?>)*\\s*(?:&lt;[^<>]{1,80}?&gt;\\s*<br\\s*/?>\\s*)*" + Pattern.quote(NOTE) + "\\s*$");

    /** The message's own text, its HTML (null when none), and the buttons' labels as the bridge wrote them. */
    record Parsed(String body, String html, List<String> buttons) {}

    private BusinessMessages() {}

    static boolean is(JsonNode content) {
        return content.hasNonNull(MARK);
    }

    /** {@code body} and {@code html} without the bridge's button lines and note; {@code order} names an order's picture. */
    static Parsed parse(String body, String html, String order) {
        List<String> lines = new ArrayList<>(List.of(body.split("\n", -1)));
        List<String> buttons = new ArrayList<>();
        if (!lines.isEmpty() && lines.getLast().strip().equals(NOTE)) {
            lines.removeLast();
            while (!lines.isEmpty()) {
                var m = BUTTON.matcher(lines.getLast().strip());
                if (!m.matches()) break;
                buttons.addFirst(m.group(1).strip());
                lines.removeLast();
            }
            while (!lines.isEmpty() && lines.getLast().isBlank()) lines.removeLast();
        }
        String text = String.join("\n", lines).replace(NO_THUMBNAIL, order);
        String cleanHtml = html == null ? null
                : HTML_ENDING.matcher(html).replaceFirst("").replace(NO_THUMBNAIL, Entities.escape(order));
        return new Parsed(text, cleanHtml == null || cleanHtml.isBlank() ? null : cleanHtml, List.copyOf(buttons));
    }
}
