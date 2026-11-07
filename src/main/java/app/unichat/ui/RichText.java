package app.unichat.ui;

import app.unichat.support.I18n;

import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal message formatting: URLs, bold, italic and inline code (Markdown-style). Tokenizer is pure and tested. */
public final class RichText {
    private RichText() {}

    public enum Kind { TEXT, BOLD, ITALIC, CODE, LINK }

    public record Span(Kind kind, String text) {}

    private static final String URL = "https?://[^\\s<>\"]+[^\\s<>\".,;:!?)\\]']";
    private static final Pattern ADDRESS = Pattern.compile(URL);
    private static final int MAX_SHOWN_URL = 60;
    private static final int MIN_SHOWN_URL = 20;
    /** A generous average for an address at normal text size, in pixels: it can't be measured before it's on screen. */
    private static final double URL_CHAR_WIDTH = 7.5;
    private static final Pattern TOKEN = Pattern.compile(
            "(?<link>" + URL + ")"
                    + "|`(?<code>[^`\\n]+)`"
                    + "|\\*\\*(?<bold>[^*\\n]+)\\*\\*"
                    + "|(?<![\\w*])\\*(?<ital>[^*\\n]+)\\*(?![\\w*])"
                    + "|(?<![\\w_])_(?<ital2>[^_\\n]+)_(?![\\w_])");

    public static List<Span> tokenize(String s) {
        List<Span> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        Matcher m = TOKEN.matcher(s);
        int pos = 0;
        while (m.find()) {
            if (m.start() > pos) out.add(new Span(Kind.TEXT, s.substring(pos, m.start())));
            if (m.group("link") != null) out.add(new Span(Kind.LINK, m.group("link")));
            else if (m.group("code") != null) out.add(new Span(Kind.CODE, m.group("code")));
            else if (m.group("bold") != null) out.add(new Span(Kind.BOLD, m.group("bold")));
            else if (m.group("ital") != null) out.add(new Span(Kind.ITALIC, m.group("ital")));
            else out.add(new Span(Kind.ITALIC, m.group("ital2")));
            pos = m.end();
        }
        if (pos < s.length()) out.add(new Span(Kind.TEXT, s.substring(pos)));
        return out;
    }

    /** True if the text is only 1–3 emoji (rendered big, without a bubble, like Beeper). */
    public static boolean isJumboEmoji(String s) {
        if (s == null) return false;
        String t = s.strip();
        if (t.isEmpty() || t.length() > 24) return false;
        int count = 0;
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0x200D || cp == 0xFE0F || (cp >= 0x1F3FB && cp <= 0x1F3FF) || Character.isWhitespace(cp)) continue;
            int type = Character.getType(cp);
            boolean emoji = type == Character.OTHER_SYMBOL || (cp >= 0x1F000 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF);
            if (!emoji) return false;
            count++;
        }
        return count >= 1 && count <= 3;
    }

    /** Plain text, with its web addresses apart. */
    static List<Span> addressSpans(String s) {
        List<Span> out = new ArrayList<>();
        Matcher m = ADDRESS.matcher(s);
        int pos = 0;
        while (m.find()) {
            if (m.start() > pos) out.add(new Span(Kind.TEXT, s.substring(pos, m.start())));
            out.add(new Span(Kind.LINK, m.group()));
            pos = m.end();
        }
        if (pos < s.length()) out.add(new Span(Kind.TEXT, s.substring(pos)));
        return out;
    }

    /** The web addresses written out in plain text. */
    public static List<String> addresses(String s) {
        return s == null ? List.of() : addressSpans(s).stream().filter(x -> x.kind() == Kind.LINK).map(Span::text).toList();
    }

    /** What a long address shows: it doesn't wrap, and would be cut at the bubble's edge. */
    static String shown(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }

    private static int urlLimit(double width) {
        double chars = width / (URL_CHAR_WIDTH * app.unichat.settings.Settings.get().fontScale());
        return (int) Math.max(MIN_SHOWN_URL, Math.min(MAX_SHOWN_URL, chars));
    }

    /** A link in the running text. When its text doesn't say where it leads, it says so on hover and to a screen reader. */
    static Hyperlink link(String label, String url, Consumer<String> openLink, double width) {
        Hyperlink h = new Hyperlink(shown(label, urlLimit(width)));
        h.getStyleClass().add("msg-link");
        h.setOnAction(e -> openLink.accept(url));
        if (!h.getText().equals(url)) {
            h.setTooltip(new javafx.scene.control.Tooltip(url));
            h.setAccessibleText(I18n.t("richText.linkTo", h.getText(), url));
        }
        return h;
    }

    public static List<Node> nodes(String s, Consumer<String> openLink, double width) {
        List<Node> nodes = new ArrayList<>();
        for (Span span : tokenize(s)) {
            switch (span.kind()) {
                case LINK -> {
                    nodes.add(link(span.text(), span.text(), openLink, width));
                }
                default -> {
                    Text t = new Text(span.text());
                    t.getStyleClass().add("msg-text");
                    if (span.kind() == Kind.BOLD) t.getStyleClass().add("bold");
                    if (span.kind() == Kind.ITALIC) t.getStyleClass().add("italic");
                    if (span.kind() == Kind.CODE) t.getStyleClass().add("code");
                    nodes.add(t);
                }
            }
        }
        return nodes;
    }
}
