package app.unichat.ui.timeline;

import app.unichat.ui.FxWait;
import app.unichat.ui.chats.FolderDialogs;
import app.unichat.ui.media.AudioPlayback;
import app.unichat.ui.media.MessageMedia;
import app.unichat.model.MediaLibrary;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opening the side panel (stickers) narrows the timeline: its photos must not load again. Rows are rebuilt only when
 * the bubbles' width really changes, and a photo decoded once shows at once in a rebuilt row.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class TimelineWidthUiTest {
    @TempDir
    Path dir;

    private static void toolkit() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
    }

    private static Message photo(MockChatService svc) {
        return svc.messages("!familia:demo").stream().filter(m -> m.type() == MessageType.IMAGE).findFirst().orElseThrow();
    }

    @Test
    void aPhotoDecodedOnceShowsAtOnceInANewRow() throws Exception {
        toolkit();
        MockChatService svc = new MockChatService();
        Message m = photo(svc);
        ImageView first = new ImageView();
        FxWait.fx(() -> MessageMedia.loadMedia(svc, first, m, 400, 300));
        FxWait.until("decoded the first time", () -> first.getImage() != null);
        ImageView again = new ImageView();
        // Right when the call returns, on the UI thread: no blank frame while it's decoded again.
        assertSame(first.getImage(), FxWait.fx(() -> {
            MessageMedia.loadMedia(svc, again, m, 400, 300);
            return again.getImage();
        }));
        svc.stop();
    }

    @Test
    void aWidthThatGivesTheSameBubblesRebuildsNothing() throws Exception {
        toolkit();
        assertEquals(MessageRowView.bubbleWidth(900), MessageRowView.bubbleWidth(1000), "both wide: the same bubbles");
        MockChatService svc = new MockChatService();
        Stage[] stage = new Stage[1];
        ThreadPane thread = FxWait.fx(() -> {
            ThreadPane t = new ThreadPane(svc, u -> { }, () -> { }, x -> { }, new AudioPlayback(svc, x -> { }),
                    new FolderDialogs(svc, n -> { }, () -> { }), new MediaLibrary(dir.resolve("media-library.json")));
            stage[0] = new Stage();
            stage[0].setOpacity(0);
            stage[0].setScene(new Scene(t, 1000, 800));
            stage[0].show();
            t.open(svc.chat("!familia:demo"));
            return t;
        });
        String id = photo(svc).id();
        FxWait.until("the photo's row shows", () -> row(thread, id) != null);
        FxWait.settled();
        Node before = FxWait.fx(() -> row(thread, id));
        FxWait.fx(() -> { stage[0].setWidth(900); return null; });
        FxWait.settled();
        assertSame(before, FxWait.fx(() -> row(thread, id)), "the same row: not rebuilt, its photo not loaded again");
        assertNotNull(before);
        FxWait.fx(() -> { stage[0].close(); return null; });
        svc.stop();
    }

    @Test
    void goingToAMessageFromTheProfileSelectsItInTheTimeline() throws Exception {
        toolkit();
        MockChatService svc = new MockChatService();
        Stage[] stage = new Stage[1];
        ThreadPane thread = FxWait.fx(() -> {
            ThreadPane t = new ThreadPane(svc, u -> { }, () -> { }, x -> { }, new AudioPlayback(svc, x -> { }),
                    new FolderDialogs(svc, n -> { }, () -> { }), new MediaLibrary(dir.resolve("media-library.json")));
            stage[0] = new Stage();
            stage[0].setOpacity(0);
            stage[0].setScene(new Scene(t, 900, 800));
            stage[0].show();
            t.open(svc.chat("!familia:demo"));
            return t;
        });
        Message target = photo(svc);
        FxWait.until("the timeline shows", () -> row(thread, target.id()) != null || !thread.lookupAll(".row-cell").isEmpty());
        FxWait.fx(() -> { thread.jumpToMessage(target); return null; });
        FxWait.settled();
        Object selected = FxWait.fx(() -> ((javafx.scene.control.ListView<?>) thread.lookup(".timeline")).getSelectionModel().getSelectedItem());
        assertTrue(selected instanceof ThreadRows.MessageRow r && r.message() == target, "selected: " + selected);
        FxWait.fx(() -> { stage[0].close(); return null; });
        svc.stop();
    }

    /** What the timeline shows for message {@code id}. */
    private static Node row(ThreadPane thread, String id) {
        return thread.lookupAll(".row-cell").stream().map(n -> (ListCell<?>) n)
                .filter(c -> c.getItem() instanceof ThreadRows.MessageRow r && r.message().id().equals(id))
                .map(ListCell::getGraphic).findFirst().orElse(null);
    }
}
