package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.service.MockChatService;
import app.unichat.ui.desktop.Notifier;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editing a message of mine and sending it with the same text still goes to the network: the bridge then rewrites it
 * there with exactly that text (a "d!ck" Telegram changed on its side, resent as is, did nothing). Esc sends nothing.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ComposerEditUiTest {
    private final List<String> edits = new CopyOnWriteArrayList<>();
    @SuppressWarnings("NullAway.Init") // the @BeforeEach sets it
    private MockChatService svc;
    @SuppressWarnings("NullAway.Init") // the @BeforeEach sets it
    private Stage stage;
    @SuppressWarnings("NullAway.Init") // the @BeforeEach sets it
    private MainView view;
    @SuppressWarnings("NullAway.Init") // the @BeforeEach sets it
    private String chat;
    @SuppressWarnings("NullAway.Init") // the @BeforeEach sets it
    private Message mine;

    @BeforeAll
    static void toolkit() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
    }

    @BeforeEach
    void open() throws Exception {
        svc = new MockChatService() {
            @Override
            public CompletableFuture<Void> editMessage(String chatId, String messageId, String newText) {
                edits.add(chatId + "|" + messageId + "|" + newText);
                return super.editMessage(chatId, messageId, newText);
            }
        };
        svc.noAutoReplies();
        chat = svc.chats().getFirst().id();
        svc.sendText(chat, "d!ck", null).get();
        mine = svc.messages(chat).getLast();
        FxWait.onFx(() -> {
            stage = new Stage();
            stage.setOpacity(0);
            view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            stage.setScene(new Scene(view, 900, 700));
            stage.show();
            view.openChatId(chat);
        });
        FxWait.until("the message box", () -> FxWait.fx(() -> view.lookup(".composer-input") != null));
    }

    @AfterEach
    void close() throws Exception {
        svc.stop();
        FxWait.onFx(() -> {
            view.dispose();
            stage.close();
            stage.getScene().setRoot(new javafx.scene.layout.Pane());
        });
    }

    private TextArea box() throws InterruptedException {
        return FxWait.fx(() -> (TextArea) view.lookup(".composer-input"));
    }

    /** ↑ in the empty box, as the person does it: the last text sent is open to edit. */
    private void startEditing() throws InterruptedException {
        FxWait.onFx(() -> {
            box().clear();
            Event.fireEvent(box(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.UP, false, false, false, false));
        });
        FxWait.until("the text in the box", () -> FxWait.fx(() -> box().getText().equals(mine.body())));
        FxWait.settled();
    }

    @Test
    void sendingAnEditWithTheSameTextStillEditsIt() throws Exception {
        startEditing();
        FxWait.onFx(() -> ((Button) view.lookup(".send-button")).fire());
        FxWait.settled();
        assertEquals(List.of(chat + "|" + mine.id() + "|d!ck"), edits, "the same text, once");
        assertTrue(FxWait.fx(() -> box().getText().isEmpty()), "the box is cleared as after any send");
    }

    @Test
    void escapeWhileEditingSendsNothing() throws Exception {
        startEditing();
        FxWait.onFx(() -> Event.fireEvent(box(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)));
        FxWait.settled();
        assertTrue(edits.isEmpty(), "cancelled: " + edits);
        FxWait.onFx(() -> ((Button) view.lookup(".send-button")).fire()); // nothing in edit mode now: an empty box sends nothing
        FxWait.settled();
        assertTrue(edits.isEmpty(), "and no edit after: " + edits);
    }
}
