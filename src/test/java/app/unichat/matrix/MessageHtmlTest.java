package app.unichat.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void aPreviewBecomesHtmlAndItsTextCantBecomeTags() {
        String html = MessageHtml.previews(json(Map.of("com.beeper.linkpreviews", List.of(
                Map.of("matched_url", "https://a.example/?a=1&b=2", "og:title", "<script>x</script>", "og:description", "Texto", "og:image", "mxc://s/p"),
                Map.of("matched_url", "https://vacio.example/")))));
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("https://a.example/?a=1&amp;b=2"));
        assertTrue(html.contains("<img src=\"mxc://s/p\">"));
        assertFalse(html.contains("vacio"), "nothing to show for the second");
        assertNull(MessageHtml.previews(json(Map.of("com.beeper.linkpreviews", List.of()))));
    }
}
