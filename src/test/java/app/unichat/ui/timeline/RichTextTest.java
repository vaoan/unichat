package app.unichat.ui.timeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static app.unichat.ui.timeline.RichText.Kind.BOLD;
import static app.unichat.ui.timeline.RichText.Kind.CODE;
import static app.unichat.ui.timeline.RichText.Kind.ITALIC;
import static app.unichat.ui.timeline.RichText.Kind.LINK;
import static app.unichat.ui.timeline.RichText.Kind.TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RichTextTest {
    @Test
    void tokenizesLinksAndMarkdown() {
        List<RichText.Span> spans = RichText.tokenize("Escribe **login** en `bot`, ver https://ejemplo.com/a?b=1. _ya_");
        assertEquals(List.of(
                new RichText.Span(TEXT, "Escribe "),
                new RichText.Span(BOLD, "login"),
                new RichText.Span(TEXT, " en "),
                new RichText.Span(CODE, "bot"),
                new RichText.Span(TEXT, ", ver "),
                new RichText.Span(LINK, "https://ejemplo.com/a?b=1"),
                new RichText.Span(TEXT, ". "),
                new RichText.Span(ITALIC, "ya")), spans);
    }

    @Test
    void doesNotTreatSnakeCaseOrMathAsItalic() {
        assertEquals(List.of(new RichText.Span(TEXT, "user_name_here 2*3*4")), RichText.tokenize("user_name_here 2*3*4"));
    }

    @Test
    void jumboEmoji() {
        assertTrue(RichText.isJumboEmoji("😂"));
        assertTrue(RichText.isJumboEmoji("❤️ 👍"));
        assertTrue(RichText.isJumboEmoji("👍🏽"));
        assertFalse(RichText.isJumboEmoji("ok 👍"));
        assertFalse(RichText.isJumboEmoji("😂😂😂😂"));
    }

    @Test
    void aLongAddressIsShortenedOnlyToShow() {
        String url = "https://a.example/" + "x".repeat(100);
        assertEquals(List.of(url), RichText.addresses("mira " + url + "."));
        assertEquals(30, RichText.shown(url, 30).length());
        assertEquals("https://a.example/", RichText.shown("https://a.example/", 60));
    }
}
