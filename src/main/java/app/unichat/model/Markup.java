package app.unichat.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The marks of {@link TextFormat} in a message, as the HTML Matrix sends ({@code formatted_body}): bridges write it the
 * way their network does. As in WhatsApp, a mark needs text right against it and nothing of a word outside it, so
 * {@code 2*3*4} or {@code mi_archivo} stay as they are. Pure.
 */
public final class Markup {
    /** What's inside a mark: text that starts and ends right against it. */
    private static final String IN = "\\S(?:.*?\\S)?";
    /** What can't touch a mark from outside: a word, or another mark. */
    private static final String OUT = "[\\w*_~|`]";
    private static final Pattern INLINE = Pattern.compile("(?U)(?<link>https?://\\S+)|`(?<code>[^`\\n]+)`"
            + "|(?<!" + OUT + ")(?:\\*\\*(?<b2>" + IN + ")\\*\\*|__(?<u>" + IN + ")__"
            + "|~~(?<s2>" + IN + ")~~|\\|\\|(?<sp>" + IN + ")\\|\\||\\*(?<b>" + IN + ")\\*|_(?<i>" + IN + ")_"
            + "|~(?<s>" + IN + ")~)(?!" + OUT + ")");
    private static final Pattern FENCE = Pattern.compile("```[\\w+-]*");

    private Markup() {}

    /** The HTML for {@code text}, or null when it has no marks (it then goes as plain text, as always). */
    public static String html(String text) {
        if (text == null || text.isEmpty()) return null;
        String[] lines = text.split("\n", -1);
        List<String[]> parts = new ArrayList<>(); // {kind, html}: kind "line" or "block"
        boolean any = false;
        for (int i = 0; i < lines.length; i++) {
            if (FENCE.matcher(lines[i].strip()).matches()) {
                int end = i + 1;
                while (end < lines.length && !lines[end].strip().equals("```")) end++;
                if (end < lines.length) {
                    parts.add(new String[]{"block", "<pre><code>" + escape(String.join("\n", java.util.Arrays.copyOfRange(lines, i + 1, end))) + "</code></pre>"});
                    any = true;
                    i = end;
                    continue;
                }
            }
            if (lines[i].startsWith("> ")) {
                StringBuilder q = new StringBuilder();
                for (; i < lines.length && lines[i].startsWith("> "); i++) {
                    if (!q.isEmpty()) q.append("<br>");
                    q.append(inline(lines[i].substring(2)));
                }
                i--;
                parts.add(new String[]{"block", "<blockquote>" + q + "</blockquote>"});
                any = true;
                continue;
            }
            String html = inline(lines[i]);
            any |= !html.equals(escape(lines[i]));
            parts.add(new String[]{"line", html});
        }
        if (!any) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0 && parts.get(i - 1)[0].equals("line") && parts.get(i)[0].equals("line")) out.append("<br>");
            out.append(parts.get(i)[1]);
        }
        return out.toString();
    }

    private static String inline(String s) {
        StringBuilder out = new StringBuilder();
        Matcher m = INLINE.matcher(s);
        int pos = 0;
        while (m.find()) {
            out.append(escape(s.substring(pos, m.start())));
            if (m.group("link") != null) out.append(escape(m.group("link")));
            else if (m.group("code") != null) out.append("<code>").append(escape(m.group("code"))).append("</code>");
            else out.append(tag(m));
            pos = m.end();
        }
        return out.append(escape(s.substring(pos))).toString();
    }

    private static String tag(Matcher m) {
        if (m.group("b2") != null) return "<strong>" + inline(m.group("b2")) + "</strong>";
        if (m.group("b") != null) return "<strong>" + inline(m.group("b")) + "</strong>";
        if (m.group("i") != null) return "<em>" + inline(m.group("i")) + "</em>";
        if (m.group("u") != null) return "<u>" + inline(m.group("u")) + "</u>";
        if (m.group("sp") != null) return "<span data-mx-spoiler>" + inline(m.group("sp")) + "</span>";
        String strike = m.group("s2") != null ? m.group("s2") : m.group("s");
        return "<del>" + inline(strike) + "</del>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
