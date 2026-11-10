package app.unichat.ui.emoji;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmojiRowsTest {
    private static final EmojiRows.Section FAVS = new EmojiRows.Section("fav", "Favoritos", List.of(), "Clic derecho…");
    private static final EmojiRows.Section RECENT = new EmojiRows.Section("rec", "Recientes", List.of(), null);
    private static final EmojiRows.Section FACES = new EmojiRows.Section("faces", "Caras", List.of("😀", "😃", "😄", "😁", "😆"), null);

    @Test
    void eachSectionIsATitleThenRowsOfAtMostTheColumns() {
        assertEquals(List.of(
                new EmojiRows.Title("Favoritos"), new EmojiRows.Hint("Clic derecho…"),
                new EmojiRows.Title("Caras"),
                new EmojiRows.Emojis("faces/0", List.of("😀", "😃")),
                new EmojiRows.Emojis("faces/1", List.of("😄", "😁")),
                new EmojiRows.Emojis("faces/2", List.of("😆"))),
                EmojiRows.rows(List.of(FAVS, RECENT, FACES), 2), "an empty section without a hint isn't there");
        assertEquals(6, EmojiRows.rows(List.of(FACES), 0).size(), "never fewer than one column: a title and five rows of one");
    }

    @Test
    void theArrowsSkipTitlesAndHints() {
        List<EmojiRows.Row> rows = EmojiRows.rows(List.of(FAVS, FACES, FACES), 5);
        // 0 title, 1 hint, 2 title, 3 emoji, 4 title, 5 emoji
        assertEquals(5, EmojiRows.nextEmojiRow(rows, 3, 1));
        assertEquals(3, EmojiRows.nextEmojiRow(rows, 5, -1));
        assertEquals(-1, EmojiRows.nextEmojiRow(rows, 3, -1), "nothing above the first");
        assertEquals(-1, EmojiRows.nextEmojiRow(rows, 5, 1));
    }
}
