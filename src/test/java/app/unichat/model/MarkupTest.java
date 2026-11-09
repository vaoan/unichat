package app.unichat.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MarkupTest {
    @Test
    void marksBecomeHtmlTheBridgesTranslate() {
        assertEquals("hola <strong>mundo</strong>", Markup.html("hola *mundo*"));
        assertEquals("<em>ya</em> y <del>no</del>", Markup.html("_ya_ y ~no~"));
        assertEquals("<u>sub</u>, <span data-mx-spoiler>final</span>", Markup.html("__sub__, ||final||"));
        assertEquals("<code>a*b*c</code>", Markup.html("`a*b*c`"), "nothing inside code");
        assertEquals("<strong>dos <em>juntos</em></strong>", Markup.html("*dos _juntos_*"), "nested");
        assertEquals("<strong>también</strong> <del>así</del>", Markup.html("**también** ~~así~~"), "Markdown's double marks too");
    }

    @Test
    void blocksAndLines() {
        assertEquals("<pre><code>x = 1\ny = *2*</code></pre>", Markup.html("```\nx = 1\ny = *2*\n```"));
        assertEquals("<blockquote>dijo <strong>esto</strong></blockquote>yo no", Markup.html("> dijo *esto*\nyo no"));
        assertEquals("<strong>a</strong><br>b &lt;i&gt; &amp;", Markup.html("*a*\nb <i> &"), "the rest stays text");
    }

    @Test
    void plainTextStaysPlain() {
        assertNull(Markup.html("hola, ¿cómo vas?"), "no marks: sent as it always was");
        assertNull(Markup.html("2*3*4 = 24"), "inside a word it isn't a mark");
        assertNull(Markup.html("mi_archivo_final.txt"));
        assertNull(Markup.html("https://example.com/a_b_c/*x*"), "links are left alone");
        assertNull(Markup.html("* no * ni _ esto _"), "a mark needs text right next to it, as in WhatsApp");
        assertNull(Markup.html("<b>no</b>"), "HTML typed by the person isn't markup");
    }
}
