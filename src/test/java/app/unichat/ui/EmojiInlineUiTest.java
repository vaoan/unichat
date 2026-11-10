package app.unichat.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A message shows flags, joined emoji and skin tones whole: as pictures among its text, nothing lost. Mac's emoji font. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
@EnabledOnOs(OS.MAC)
class EmojiInlineUiTest {
    @Test
    void piecesBecomePicturesAndTheTextStaysWhole() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        String text = "Vamos 🇨🇴, de una 👍🏽 jaja 😂 ❤️‍🔥";
        List<EmojiArt.Segment> segs = FxWait.fx(() -> EmojiArt.segments(text));
        assertEquals(text, segs.stream().map(EmojiArt.Segment::text).collect(Collectors.joining()), "nothing lost");
        for (String whole : List.of("🇨🇴", "👍🏽", "❤️‍🔥")) {
            assertNotNull(segs.stream().filter(s -> s.text().equals(whole)).findFirst().orElseThrow().picture(), whole + " as a picture");
        }
        assertNull(segs.stream().filter(s -> s.text().contains("😂")).findFirst().orElseThrow().picture(), "a plain emoji stays text");
        List<Node> nodes = FxWait.fx(() -> EmojiInline.nodes(text, Text::new));
        assertEquals(3, nodes.stream().filter(n -> n instanceof Label).count());
        assertEquals(List.of(new EmojiArt.Segment("solo texto", null)), FxWait.fx(() -> EmojiArt.segments("solo texto")),
                "no emoji: one run, as before");
    }
}
