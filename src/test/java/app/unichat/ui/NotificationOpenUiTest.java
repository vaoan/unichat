package app.unichat.ui;

import app.unichat.ui.desktop.Notifier;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A notification's click brings the window back and opens its chat (UnichatApp: show, then openChatId). The message box
 * must come back as it was: one line, its row filling its frame.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class NotificationOpenUiTest {
    private MockChatService svc;
    private Stage stage;
    private MainView view;
    private Scene scene;

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

    @AfterEach
    void close() throws Exception {
        svc.stop();
        FxWait.fx(() -> {
            stage.close();
            return null;
        });
    }

    private void open() throws Exception {
        svc = new MockChatService();
        scene = FxWait.fx(() -> {
            stage = new Stage();
            view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 760);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            view.openChatId("!familia:demo");
            return sc;
        });
        FxWait.until("the message box", () -> scene.getRoot().lookup(".composer-row") != null);
        FxWait.settled();
    }

    /** The message box's frame against the row inside it (text and buttons), and the text box's height. */
    private String box() throws Exception {
        return FxWait.fx(() -> {
            Node frame = scene.getRoot().lookup(".composer-row");
            Node input = frame.lookup(".text-area");
            Bounds f = frame.getLayoutBounds();
            return "frame " + Math.round(f.getHeight()) + ", text " + Math.round(input.getLayoutBounds().getHeight())
                    + (input.getParent() == frame ? " on its own line" : " beside the buttons");
        });
    }

    /** As UnichatApp.showWindow then the notification's chat: two runLaters, in that order. */
    private void clickNotification(String chatId) throws Exception {
        FxWait.fx(() -> {
            Platform.runLater(() -> {
                stage.show();
                stage.setIconified(false);
                stage.toFront();
                stage.requestFocus();
            });
            Platform.runLater(() -> view.openChatId(chatId));
            return null;
        });
        FxWait.until("the chat open", () -> view.currentChatId() != null && view.currentChatId().equals(chatId));
        FxWait.settled();
    }

    /** Large text and the info panel: the chat is narrow, near where the box goes onto its own line above the buttons. */
    @Test
    void withLargeTextAndThePanelTheBoxComesBackAsItWasAtAnyWidth() throws Exception {
        double scale = app.unichat.settings.Settings.get().fontScale();
        app.unichat.settings.Settings.get().setFontScale(1.6);
        try {
            open();
            FxWait.fx(() -> { Theme.applyAll(); view.toggleInfo(); return null; });
            FxWait.settled();
            StringBuilder seen = new StringBuilder();
            for (int width = 1300; width >= 1000; width -= 25) {
                int w = width;
                FxWait.fx(() -> { stage.setWidth(w); return null; });
                FxWait.settled();
                String before = box();
                FxWait.fx(() -> { stage.hide(); return null; });
                FxWait.settled();
                clickNotification(view.currentChatId().equals("!mama:demo") ? "!familia:demo" : "!mama:demo");
                String after = box();
                if (!after.equals(before)) seen.append(w).append(": ").append(before).append(" -> ").append(after).append('\n');
            }
            assertEquals("", seen.toString(), "the box changed after a notification's click");
        } finally {
            app.unichat.settings.Settings.get().setFontScale(scale);
        }
    }

    @Test
    void fromTheMenuBarTheBoxComesBackAsItWas() throws Exception {
        open();
        String before = box();
        FxWait.fx(() -> { stage.hide(); return null; });
        FxWait.settled();
        clickNotification("!mama:demo");
        assertEquals(before, box());
    }

    @Test
    void fromMinimizedTheBoxComesBackAsItWas() throws Exception {
        open();
        String before = box();
        FxWait.fx(() -> { stage.setIconified(true); return null; });
        FxWait.settled();
        clickNotification("!mama:demo");
        assertEquals(before, box());
    }

    /**
     * A notification's click opens its chat ready to answer: the cursor in that chat's message box, wherever it was
     * before. (On macOS the window must also become the active app's: see UnichatBar.swift's yieldActivation.)
     */
    @Test
    void theNotificationsChatOpensWithTheCursorInItsBox() throws Exception {
        open();
        FxWait.fx(() -> { scene.getRoot().lookup(".list-view").requestFocus(); return null; });
        FxWait.fx(() -> { stage.hide(); return null; });
        FxWait.settled();
        clickNotification("!mama:demo");
        assertEquals("!mama:demo", FxWait.fx(view::currentChatId));
        Node box = FxWait.fx(() -> scene.getRoot().lookup(".composer-row").lookup(".text-area"));
        FxWait.until("the cursor in the chat's message box", () -> {
            Node owner = scene.getFocusOwner();
            for (Node n = owner; n != null; n = n.getParent()) if (n == box) return true;
            throw new AssertionError("the focus is on " + owner); // what a failure says
        });
    }

    /** A Discord channel's notification opens that channel, inside its server, whatever chat was open. */
    @Test
    void aChannelsNotificationOpensThatChannel() throws Exception {
        open();
        FxWait.fx(() -> { stage.hide(); return null; });
        FxWait.settled();
        FxWait.fx(() -> {
            Platform.runLater(() -> { stage.show(); stage.toFront(); });
            Platform.runLater(() -> view.openChatId("!srv-memes:demo"));
            return null;
        });
        FxWait.settled();
        FxWait.until("the channel is the open chat", () -> {
            if ("!srv-memes:demo".equals(view.currentChatId())) return true;
            throw new AssertionError("open: " + view.currentChatId()); // what a failure says
        });
        assertEquals("DISCORD channel of a server; opened", FxWait.fx(() -> view.openOutcome("!srv-memes:demo")));
    }

    /**
     * A notice that a file couldn't be saved goes to the message it came in: its click led nowhere (the chat at most,
     * where the person had to look for it).
     */
    @Test
    void aNoticeAboutAMessageGoesToThatMessage() throws Exception {
        open();
        String older = FxWait.fx(() -> svc.messages("!mama:demo").get(0).id()); // far from the newest the chat opens at
        FxWait.fx(() -> {
            view.openChatId("!mama:demo");
            view.showMessage("!mama:demo", older);
            return null;
        });
        FxWait.settled();
        FxWait.until("the message is selected", () -> {
            var m = view.thread().selectedMessage();
            if (m != null && older.equals(m.id())) return true;
            throw new AssertionError("selected: " + (m == null ? "none" : m.id())); // what a failure says
        });
    }
}
