package app.unichat.ui.emoji;

import java.util.ArrayList;
import java.util.List;

/** The emoji list as the rows a virtual list shows: a title, a hint, or up to {@code columns} emoji. Pure. */
final class EmojiRows {
    sealed interface Row permits Title, Hint, Emojis {}

    record Title(String text) implements Row {}

    record Hint(String text) implements Row {}

    /** {@code key}: the section and its row number, so an unchanged row keeps its cell. */
    record Emojis(String key, List<String> emoji) implements Row {}

    /** A titled group; {@code hint} shows instead when it has none (null: then it isn't shown at all). */
    record Section(String id, String title, List<String> emoji, String hint) {}

    private EmojiRows() {}

    static List<Row> rows(List<Section> sections, int columns) {
        int n = Math.max(1, columns);
        List<Row> out = new ArrayList<>();
        for (Section s : sections) {
            if (s.emoji().isEmpty() && s.hint() == null) continue;
            out.add(new Title(s.title()));
            if (s.emoji().isEmpty()) out.add(new Hint(s.hint()));
            for (int i = 0; i < s.emoji().size(); i += n) {
                out.add(new Emojis(s.id() + "/" + i / n, List.copyOf(s.emoji().subList(i, Math.min(i + n, s.emoji().size())))));
            }
        }
        return out;
    }

    /** The emoji row nearest {@code from} going {@code step} (±1), or -1: titles and hints are skipped. */
    static int nextEmojiRow(List<Row> rows, int from, int step) {
        for (int i = from + step; i >= 0 && i < rows.size(); i += step) if (rows.get(i) instanceof Emojis) return i;
        return -1;
    }
}
