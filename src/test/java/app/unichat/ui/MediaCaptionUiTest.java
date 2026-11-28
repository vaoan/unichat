package app.unichat.ui;

import app.unichat.TestPictures;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static app.unichat.ui.TimelineWindow.CHAT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A photo sent with its text in one event (X's bridge: the text as body, the attachment's id as filename) shows the
 * picture and the whole text under it, as X does. The text was dropped: only the picture was drawn.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class MediaCaptionUiTest {
    private static final String CAPTION = "If you want our artist to draw it for you, here are three styles to choose from. "
            + "Each comes with front and back views and a sketch of the details.\n\n"
            + "Tell us which one you like at www.example.org and we'll send the price. Thanks for asking!";

    @TempDir Path dir;
    @SuppressWarnings("NullAway.Init") // each test sets it before use
    private TimelineWindow w;

    @BeforeAll
    static void toolkit() throws Exception { TimelineWindow.toolkit(); }

    @AfterEach
    void close() throws Exception { if (w != null) w.close(); }

    private Consumer<Message> photo(String body) throws java.io.IOException {
        String url = TestPictures.still(dir.resolve("p.png"), "png", 1600, 767).toUri().toString();
        return m -> {
            m.setType(MessageType.IMAGE);
            m.setMediaUrl(url);
            m.setMimeType("image/png");
            m.setSize(1600, 767);
            m.setFileName("0a1b2c3d-0000-4000-8000-000000000001.jpg");
            m.setBody(body);
        };
    }

    /** All the words drawn in the newest message's bubble. */
    private String newestBubbleText() throws InterruptedException {
        return FxWait.fx(() -> {
            Node bubble = w.list().lookupAll(".bubble.media").stream().reduce((a, b) -> b).orElseThrow();
            return bubble.lookupAll(".media-caption").stream()
                    .flatMap(c -> c.lookupAll("Text").stream())
                    .map(t -> ((Text) t).getText()).collect(Collectors.joining());
        });
    }

    @Test
    void aPhotoWithALongTextShowsThePictureAndTheWholeTextUnderIt() throws Exception {
        w = TimelineWindow.open(3);
        w.svc.receive(CHAT, photo(CAPTION));
        FxWait.until("the photo is drawn", () -> w.list().lookup(".bubble.media") != null);
        FxWait.until("its picture shows", () -> w.list().lookupAll(".bubble.media ImageView").stream()
                .anyMatch(n -> n instanceof ImageView v && v.getImage() != null && !v.getImage().isError()));
        FxWait.settled();
        assertEquals(CAPTION, newestBubbleText(), "the whole text, under the picture");
        boolean below = FxWait.fx(() -> {
            Node bubble = w.list().lookup(".bubble.media");
            Node picture = bubble.lookup(".bubble.media ImageView");
            Node caption = bubble.lookup(".media-caption");
            return caption.localToScene(caption.getLayoutBounds()).getMinY()
                    >= picture.localToScene(picture.getLayoutBounds()).getMaxY() - 0.5;
        });
        assertTrue(below, "the text is under the picture");
        boolean wraps = FxWait.fx(() -> {
            Node caption = w.list().lookup(".bubble.media .media-caption");
            Node picture = w.list().lookup(".bubble.media ImageView");
            return caption instanceof TextFlow f && f.getLayoutBounds().getHeight() > 3 * 16
                    && f.getLayoutBounds().getWidth() <= picture.getLayoutBounds().getWidth() + 1;
        });
        assertTrue(wraps, "the long text wraps in lines as wide as the picture");
    }

    @Test
    void aPhotoWhoseBodyIsItsNameShowsNoText() throws Exception {
        w = TimelineWindow.open(3);
        w.svc.receive(CHAT, photo("0a1b2c3d-0000-4000-8000-000000000001.jpg"));
        FxWait.until("the photo is drawn", () -> w.list().lookup(".bubble.media") != null);
        FxWait.settled();
        assertFalse(FxWait.fx(() -> w.list().lookup(".media-caption") != null), "a name is not a caption");
    }
}
