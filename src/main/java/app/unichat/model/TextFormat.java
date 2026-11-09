package app.unichat.model;

/**
 * The text formats networks take, as the person writes them in the message box: WhatsApp's marks, and Discord's for
 * what WhatsApp lacks (underline, spoiler). {@link Markup} makes them HTML; each bridge writes that its network's way.
 */
public enum TextFormat {
    BOLD("bold", "*"), ITALIC("italic", "_"), UNDERLINE("underline", "__"), STRIKE("strikethrough", "~"),
    CODE("inline_code", "`"), SPOILER("spoiler", "||"), CODE_BLOCK("code_block", "```"), QUOTE("blockquote", "> ");

    private final String feature;
    private final String mark;

    TextFormat(String feature, String mark) {
        this.feature = feature;
        this.mark = mark;
    }

    /** Its name in the bridges' {@code formatting} room feature. */
    public String feature() { return feature; }

    /** The text after it's applied, and what's selected then. */
    public record Edit(String text, int from, int to) {}

    /** Applies it to {@code from..to} of {@code text}, or takes it off when it's already there. */
    public Edit apply(String text, int from, int to) {
        if (this == QUOTE) return quote(text, from, to);
        if (this == CODE_BLOCK) return block(text, from, to);
        return inline(text, from, to);
    }

    private Edit inline(String text, int from, int to) {
        while (from < to && Character.isWhitespace(text.charAt(from))) from++; // marks go right against the text
        while (to > from && Character.isWhitespace(text.charAt(to - 1))) to--;
        int n = mark.length();
        String sel = text.substring(from, to);
        if (sel.length() > 2 * n && sel.startsWith(mark) && sel.endsWith(mark) && sel.indexOf('\n') < 0) {
            return new Edit(text.substring(0, from) + sel.substring(n, sel.length() - n) + text.substring(to), from, to - 2 * n);
        }
        if (from >= n && text.startsWith(mark, from - n) && text.startsWith(mark, to) && from < to) {
            return new Edit(text.substring(0, from - n) + sel + text.substring(to + n), from - n, to - n);
        }
        if (from == to) return new Edit(text.substring(0, from) + mark + mark + text.substring(to), from + n, from + n);
        StringBuilder out = new StringBuilder();
        String[] lines = sel.split("\n", -1);
        for (int i = 0; i < lines.length; i++) { // a mark can't cross lines: each one gets its own
            if (i > 0) out.append('\n');
            String line = lines[i];
            String core = line.strip();
            if (core.isEmpty()) {
                out.append(line);
                continue;
            }
            int lead = line.indexOf(core);
            out.append(line, 0, lead).append(mark).append(core).append(mark).append(line.substring(lead + core.length()));
        }
        return new Edit(text.substring(0, from) + out + text.substring(to), from + n, from + out.length() - n);
    }

    private static Edit quote(String text, int from, int to) {
        int start = text.lastIndexOf('\n', Math.max(0, from - 1)) + 1;
        if (from == 0) start = 0;
        int end = text.indexOf('\n', to);
        if (end < 0 || (to > from && text.charAt(to - 1) == '\n')) end = end < 0 ? text.length() : to - 1;
        String[] lines = text.substring(start, end).split("\n", -1);
        boolean quoted = true;
        for (String l : lines) quoted &= l.startsWith("> ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(quoted ? lines[i].substring(2) : "> " + lines[i]);
        }
        return new Edit(text.substring(0, start) + out + text.substring(end), start, start + out.length());
    }

    private static Edit block(String text, int from, int to) {
        String open = "```\n", close = "\n```";
        if (from >= open.length() && text.startsWith(open, from - open.length()) && text.startsWith(close, to)) {
            return new Edit(text.substring(0, from - open.length()) + text.substring(from, to) + text.substring(to + close.length()),
                    from - open.length(), to - open.length());
        }
        String before = from > 0 && text.charAt(from - 1) != '\n' ? "\n" + open : open; // a block starts a line
        String after = to < text.length() && text.charAt(to) != '\n' ? close + "\n" : close;
        String out = text.substring(0, from) + before + text.substring(from, to) + after + text.substring(to);
        return new Edit(out, from + before.length(), to + before.length());
    }
}
