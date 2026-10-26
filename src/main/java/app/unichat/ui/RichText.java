package app.unichat.ui;

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

    private static final Pattern TOKEN = Pattern.compile(
            "(?<link>https?://[^\\s<>\"]+[^\\s<>\".,;:!?)\\]'])"
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

    public static List<Node> nodes(String s, Consumer<String> openLink) {
        List<Node> nodes = new ArrayList<>();
        for (Span span : tokenize(s)) {
            switch (span.kind()) {
                case LINK -> {
                    Hyperlink h = new Hyperlink(span.text());
                    h.getStyleClass().add("msg-link");
                    h.setOnAction(e -> openLink.accept(span.text()));
                    nodes.add(h);
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
