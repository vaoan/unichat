package app.unichat.ui;

import app.unichat.TestPictures;
import app.unichat.model.MediaLibrary;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static app.unichat.ui.TimelineWindow.CHAT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A reply of one's own keeps its content on its side of the chat: a sticker answering a longer message sat under the
 * quote's text, at the left of a bubble on the right, instead of at the bubble's right edge.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ReplyAlignUiTest {
    @TempDir Path dir;
    private TimelineWindow w;

    @BeforeAll
    static void toolkit() throws Exception { TimelineWindow.toolkit(); }

    @AfterEach
    void close() throws Exception { if (w != null) w.close(); }

    @Test
    void aStickerAnsweringALongerMessageSitsAtTheBubblesEdge() throws Exception {
        w = TimelineWindow.open(5);
        w.svc.receive(CHAT, "un mensaje bastante más largo que el sticker que lo va a responder");
        String answered = w.newest().id();
        Path gif = TestPictures.gif(dir.resolve("s.gif"), 64, 64, 1);
        w.svc.sendSticker(CHAT, new MediaLibrary.Item(MediaLibrary.Kind.STICKER, gif.toUri().toString(), "image/gif", 64, 64, null), answered).get();
        FxWait.until("the answer is drawn", () -> w.list().lookup(".bubble.out.sticker") != null);
        FxWait.settled();
        double[] edges = FxWait.fx(() -> {
            VBox bubble = (VBox) w.list().lookup(".bubble.out.sticker");
            Node quote = bubble.lookup(".reply-quote");
            Node sticker = bubble.getChildren().get(bubble.getChildren().indexOf(quote) + 1);
            Bounds b = bubble.localToScene(bubble.getLayoutBounds()), s = sticker.localToScene(sticker.getLayoutBounds()),
                    q = quote.localToScene(quote.getLayoutBounds());
            return new double[] {b.getMaxX() - bubble.getInsets().getRight(), s.getMaxX(), s.getWidth(), q.getWidth()};
        });
        org.junit.jupiter.api.Assertions.assertTrue(edges[3] > edges[2] + 20, "the quote is wider than the sticker: " + edges[3] + " vs " + edges[2]);
        assertEquals(edges[0], edges[1], 0.5, "the sticker at the bubble's right edge, not under the quote's text");
    }
}
