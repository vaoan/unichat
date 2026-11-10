package app.unichat.ui.timeline;

import app.unichat.ui.FxWait;
import app.unichat.ui.MainView;
import app.unichat.ui.Theme;
import app.unichat.ui.desktop.Notifier;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ⌘I writes a draft from anywhere in the window and leaves the focus in the text box; the info panel has ⌘⇧I. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class AiShortcutUiTest {
    private static <T> T fx(Callable<T> c) throws Exception {
        return FxWait.fx(c);
    }

    private static void press(Node target, boolean shift) throws Exception {
        fx(() -> {
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.I, shift, true, false, true));
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.I, shift, true, false, true));
            return null;
        });
    }

    private static boolean infoOpen(Scene scene) throws Exception {
        return fx(() -> scene.getRoot().lookupAll(".info-pane").stream().anyMatch(n -> n.isVisible() && n.getScene() != null));
    }

    @Test
    void inTheTextBoxItWritesAndElsewhereItShowsTheInfo() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        CountDownLatch wrote = new CountDownLatch(1);
        Scene scene = fx(() -> {
            AiReplyButton.SHOWN.set(true);
            Stage stage = new Stage();
            MainView view = new MainView(new MockChatService(), stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 700);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            view.openChatId("!familia:demo");
            view.lookupAll("*").stream().filter(Composer.class::isInstance).map(Composer.class::cast).findFirst().orElseThrow()
                    .useAiWriter(() -> p -> {
                        wrote.countDown();
                        return "Borrador";
                    });
            return sc;
        });
        FxWait.until("the chat list and the text box show",
                () -> scene.getRoot().lookup(".chat-cell") != null && scene.getRoot().lookup(".composer-row .text-area") != null);
        FxWait.settled();
        TextArea box = fx(() -> (TextArea) scene.getRoot().lookup(".composer-row .text-area"));
        assertFalse(infoOpen(scene));

        Node elsewhere = fx(() -> scene.getRoot().lookup(".chat-cell"));
        fx(() -> { elsewhere.requestFocus(); return null; });
        press(elsewhere, false);
        wrote.await(); // from anywhere in the window
        assertFalse(infoOpen(scene), "the info panel stays as it was");
        FxWait.until("the draft in the box", () -> "Borrador".equals(box.getText()));
        FxWait.until("the focus in the box, ready for Enter", () -> box.isFocused() || scene.getFocusOwner() == box);

        press(scene.getRoot(), true);
        assertTrue(infoOpen(scene), "the info panel has its own key");
        press(box, true);
        assertFalse(infoOpen(scene), "also from the text box");

        fx(() -> { AiReplyButton.SHOWN.set(false); return null; });
        press(box, false);
        assertFalse(infoOpen(scene), "with the AI off, nothing");
    }
}
