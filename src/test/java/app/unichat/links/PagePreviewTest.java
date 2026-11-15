package app.unichat.links;

import app.unichat.model.LinkPreview;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PagePreviewTest {
    @Test
    void aPageSaysWhatItIsInItsOwnTags() {
        LinkPreview p = PagePreview.parse("""
                <html><head><title>Título de la pestaña</title>
                <meta property="og:site_name" content="Ejemplo">
                <meta property="og:title" content="Un artículo">
                <meta name="description" content="Lo que dice">
                <meta property="og:image" content="/foto.jpg">
                <meta property="og:image:width" content="1200"><meta property="og:image:height" content="630">
                </head></html>""", "https://a.example/nota");
        assertEquals("Ejemplo", p.site());
        assertEquals("Un artículo", p.title());
        assertEquals("Lo que dice", p.description());
        assertEquals("https://a.example/foto.jpg", p.image(), "made whole against the page");
        assertEquals(1200, p.width());
        assertEquals(630, p.height());
    }

    @Test
    void withoutItsTagsAPageIsItsTitleAndNothingElseOfAStrangersIsTakenAsItCame() {
        LinkPreview p = PagePreview.parse("<title>Solo título</title><meta property=\"og:image\" content=\"file:///etc/passwd\">"
                + "<meta property=\"og:image:width\" content=\"99999999\">", "https://a.example/");
        assertEquals("Solo título", p.title());
        assertNull(p.image(), "only a web address");
        assertEquals(0, p.width(), "a size no picture has");
        String longText = "a".repeat(5000);
        LinkPreview big = PagePreview.parse("<meta property=\"og:description\" content=\"" + longText + "\">", "https://a.example/");
        assertTrue(big.description().length() <= PagePreview.MAX_TEXT + 1, "a card shows a few lines");
        assertTrue(big.description().endsWith("…"));
    }
}
