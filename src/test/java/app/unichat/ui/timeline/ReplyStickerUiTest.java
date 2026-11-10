package app.unichat.ui.timeline;

import app.unichat.ui.emoji.StickerGrid;
import app.unichat.ui.FxWait;
import app.unichat.model.Chat;
import app.unichat.model.MediaLibrary;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A sticker sent from the panel goes with what the chat has active: in reply mode, it answers. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ReplyStickerUiTest {
    private static final String CHAT = "!andres:demo";
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;

    @BeforeAll
    static void toolkit() throws Exception {
        System.setProperty("java.awt.headless", "true");
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        return FxWait.fx(c);
    }

    private static void press(Node tile) {
        tile.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
    }

    @Test
    void inReplyModeTheStickerAnswersAndTheModeEnds() throws Exception {
        MockChatService svc = new MockChatService();
        MediaLibrary library = new MediaLibrary(dir.resolve("media-library.json"));
        library.addRecent(new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "demo://sticker", "image/png", 64, 64, null));
        Composer composer = fx(() -> new Composer(svc, m -> { }));
        Chat chat = svc.chat(CHAT);
        fx(() -> {
            composer.setChat(CHAT);
            return null;
        });
        Message original = svc.messages(CHAT).get(0);
        StickerGrid grid = fx(() -> {
            StickerGrid g = new StickerGrid(svc, library, () -> chat, composer::takeReply, m -> { });
            g.refresh();
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(new Scene(g, 400, 400));
            stage.show();
            return g;
        });
        FxWait.until("the sticker's tile", () -> grid.lookup(".sticker-tile") != null);
        fx(() -> {
            composer.reply(original);
            press(grid.lookup(".sticker-tile"));
            return null;
        });
        List<Message> messages = fx(() -> svc.messages(CHAT));
        Message first = messages.get(messages.size() - 1);
        assertEquals(MessageType.STICKER, first.type());
        assertEquals(original.id(), first.replyToId());
        assertNull(fx(composer::takeReply), "the mode ended, as after a text");

        fx(() -> {
            press(grid.lookup(".sticker-tile"));
            return null;
        });
        List<Message> after = fx(() -> svc.messages(CHAT));
        assertNull(after.get(after.size() - 1).replyToId(), "the next one answers nothing");
    }
}
