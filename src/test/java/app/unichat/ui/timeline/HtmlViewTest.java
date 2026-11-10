package app.unichat.ui.timeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HtmlViewTest {
    @Test
    void itFindsEveryWebAddressAndOnlyThose() {
        assertEquals(List.of("https://a.example/v", "https://b.example/c.mp3", "https://c.example/d.ogg", "https://suelto.example/x"),
                HtmlView.links("https://suelto.example/x\n<mx-reply><a href=\"https://citado.example/\">no</a></mx-reply>"
                        + "<blockquote><a href=\"https://a.example/v\">ver</a><a href=\"matrix:u/x\">no</a><a href=\"javascript:x\">no</a></blockquote>"
                        + "<audio src=\"https://b.example/c.mp3\"></audio><video><source src=\"https://c.example/d.ogg\"></video>"));
    }
}
