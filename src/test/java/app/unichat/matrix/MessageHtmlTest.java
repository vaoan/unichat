package app.unichat.matrix;

import app.unichat.model.LinkPreview;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageHtmlTest {
    private static JsonNode json(Map<String, Object> m) {
        return new ObjectMapper().valueToTree(m);
    }

    @Test
    void onlyDeclaredHtmlIsHtml() {
        assertEquals("<b>x</b>", MessageHtml.of(json(Map.of("format", "org.matrix.custom.html", "formatted_body", "<b>x</b>"))));
        assertNull(MessageHtml.of(json(Map.of("formatted_body", "<b>x</b>"))));
    }

    @Test
    void theBodyKeepsOnlyWhatThePersonWrote() {
        String embed = "x<blockquote class=\"discord-embed\"></blockquote>";
        assertEquals("mira https://a.example", MessageHtml.withoutEmbedText("mira https://a.example\n> [Ana](https://a.example)\n> \n> Hola\n> \n\n> \n\n>", embed));
        assertEquals("", MessageHtml.withoutEmbedText("> Hola", embed), "a bot's message is only its embed");
        assertEquals("> citado\nhola", MessageHtml.withoutEmbedText("> citado\nhola", "<blockquote>citado</blockquote>hola"), "a person's own quote");
        assertEquals("> citado", MessageHtml.withoutEmbedText("> citado", null));
    }

    @Test
    void aBridgesPreviewBecomesACardWithAPictureOnlyFromOurServer() {
        List<LinkPreview> cards = MessageHtml.previews(json(Map.of("com.beeper.linkpreviews", List.of(
                Map.of("matched_url", "https://a.example/?a=1&b=2", "og:title", "<script>x</script>", "og:description", "Texto",
                        "og:site_name", "Ejemplo", "og:image", "mxc://s/p", "og:image:width", 1080, "og:image:height", 1920),
                Map.of("matched_url", "https://vacio.example/"),
                Map.of("matched_url", "https://b.example/", "og:title", "B", "og:image", "file:///etc/passwd")))));
        assertEquals(2, cards.size(), "nothing to show for the second");
        LinkPreview a = cards.get(0);
        assertEquals("https://a.example/?a=1&b=2", a.url());
        assertEquals("<script>x</script>", a.title(), "kept as text: a card draws labels, never markup");
        assertEquals("Ejemplo", a.site());
        assertEquals("mxc://s/p", a.image());
        assertEquals(1080, a.width(), "its size, so the box is the picture's shape before it loads");
        assertEquals(1920, a.height());
        assertNull(cards.get(1).image(), "a stranger's event chooses the address: only our server's");
        assertTrue(MessageHtml.previews(json(Map.of("com.beeper.linkpreviews", List.of()))).isEmpty());
    }
}
