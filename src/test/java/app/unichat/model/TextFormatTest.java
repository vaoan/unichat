package app.unichat.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextFormatTest {
    private static String applied(TextFormat f, String text, int from, int to) {
        TextFormat.Edit e = f.apply(text, from, to);
        return e.text().substring(0, e.from()) + "[" + e.text().substring(e.from(), e.to()) + "]" + e.text().substring(e.to());
    }

    @Test
    void wrapsTheSelectionAndKeepsItSelected() {
        assertEquals("hola *[mundo]*", applied(TextFormat.BOLD, "hola mundo", 5, 10));
        assertEquals("*[hola]* ", applied(TextFormat.BOLD, "hola ", 0, 5), "spaces stay outside, as WhatsApp needs");
        assertEquals("||[final]||", applied(TextFormat.SPOILER, "final", 0, 5));
    }

    @Test
    void withNothingSelectedTheMarksWaitForTheText() {
        assertEquals("ok _[]_", applied(TextFormat.ITALIC, "ok ", 3, 3));
    }

    @Test
    void pressingAgainTakesTheFormatOff() {
        assertEquals("hola [mundo]", applied(TextFormat.BOLD, "hola *mundo*", 6, 11));
        assertEquals("hola [mundo]", applied(TextFormat.BOLD, "hola *mundo*", 5, 12), "marks selected too");
    }

    @Test
    void linesAndBlocks() {
        assertEquals("*[uno*\n*dos]*", applied(TextFormat.BOLD, "uno\ndos", 0, 7), "a mark can't cross lines: each gets its own");
        assertEquals("[> uno\n> dos]", applied(TextFormat.QUOTE, "uno\ndos", 0, 7));
        assertEquals("```\n[x = 1]\n```", applied(TextFormat.CODE_BLOCK, "x = 1", 0, 5));
    }

    @Test
    void eachMatchesTheBridgesFeatureName() {
        assertEquals("strikethrough", TextFormat.STRIKE.feature());
        assertEquals("inline_code", TextFormat.CODE.feature());
        assertEquals("blockquote", TextFormat.QUOTE.feature());
    }
}
