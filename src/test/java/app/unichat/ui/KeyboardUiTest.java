package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.service.MockChatService;
import app.unichat.settings.Settings;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real UI with synthetic key events. Needs a display, so it only runs with
 * {@code ./mvnw test -Dtest=KeyboardUiTest -Dunichat.uiTests=true}.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class KeyboardUiTest {

    @Test
    void keyboardOnlyNavigationReplyReactAndZoom() throws Exception {
        System.setProperty("java.awt.headless", "true"); // no tray icon in tests
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }

        MockChatService svc = new MockChatService();
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            Notifier notifier = new Notifier(() -> { }, () -> { }, id -> { }, () -> { });
            MainView view = new MainView(svc, stage, notifier, () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 700);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            view.openChatId("!familia:demo");
            return sc;
        });
        Thread.sleep(500);

        String cellText = fx(() -> scene.getRoot().lookupAll(".chat-cell").stream()
                .map(n -> (String) n.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.TEXT))
                .filter(t -> t != null && t.startsWith("Familia")).findFirst().orElse(""));
        assertTrue(cellText.contains("WhatsApp") && cellText.contains("grupo"), cellText);

        assertEquals("composer", fx(() -> zone(scene)));
        press(scene, KeyCode.F6, false, false);
        assertEquals("rail", fx(() -> zone(scene)));
        press(scene, KeyCode.F6, false, false);
        assertEquals("list", fx(() -> zone(scene)));
        press(scene, KeyCode.F6, false, false);
        assertEquals("timeline", fx(() -> zone(scene)));
        press(scene, KeyCode.F6, true, false);
        assertEquals("list", fx(() -> zone(scene)), "Shift+F6 goes back");
        press(scene, KeyCode.F6, false, false);

        String msgText = fx(() -> scene.getRoot().lookupAll(".row-cell").stream()
                .filter(n -> n instanceof javafx.scene.control.ListCell<?> c && c.isSelected())
                .map(n -> (String) n.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.TEXT))
                .findFirst().orElse(""));
        assertTrue(msgText.startsWith("Laura, ") && msgText.contains("¿Quién trae qué el domingo?"), msgText);

        // The last message is selected when the timeline gets focus; "1" reacts with the first quick emoji.
        List<Message> msgs = svc.messages("!familia:demo");
        Message last = msgs.get(msgs.size() - 1);
        press(scene, KeyCode.DIGIT1, false, false);
        assertTrue(last.reactions().containsKey(EmojiPicker.QUICK[0]), "1 reacts to the selected message");

        press(scene, KeyCode.R, false, false);
        assertEquals("composer", fx(() -> zone(scene)), "R moves to the composer");
        assertTrue(fx(() -> scene.getRoot().lookupAll(".banner-title").stream()
                .anyMatch(n -> n.isVisible() && ((javafx.scene.control.Label) n).getText().startsWith("Respondiendo a"))));

        // A letter pressed anywhere else (the chat list here) writes in the message box, as in Telegram.
        press(scene, KeyCode.F6, false, false);
        press(scene, KeyCode.F6, false, false);
        assertEquals("list", fx(() -> zone(scene)));
        typeLetter(scene, "h");
        assertEquals("composer", fx(() -> zone(scene)), "typing goes to the message box");
        assertTrue(fx(() -> ((javafx.scene.control.TextArea) scene.getRoot().lookup(".composer-input")).getText().endsWith("h")));

        double before = Settings.get().fontScale();
        press(scene, KeyCode.EQUALS, false, true);
        assertEquals(Math.min(Settings.MAX_FONT_SCALE, before + 0.1), Settings.get().fontScale(), 0.001);
        press(scene, KeyCode.DIGIT0, false, true);
        assertEquals(1.0, Settings.get().fontScale(), 0.001);
        svc.stop();
    }

    @Test
    void shortcutsFollowWhatTheNetworkAllows() throws Exception {
        System.setProperty("java.awt.headless", "true");
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        MockChatService svc = new MockChatService();
        svc.chat("!familia:demo").setFeatures(app.unichat.model.RoomFeatures.parse(
                new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"reply\":0,\"edit\":0,\"reaction\":0}")));
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            MainView view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 700);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            view.openChatId("!familia:demo");
            return sc;
        });
        Thread.sleep(500);
        for (int i = 0; i < 3; i++) press(scene, KeyCode.F6, false, false);
        assertEquals("timeline", fx(() -> zone(scene)));
        press(scene, KeyCode.R, false, false);
        assertEquals("timeline", fx(() -> zone(scene)), "R does nothing where the network has no replies");
        assertTrue(fx(() -> scene.getRoot().lookupAll(".hover-actions .button").stream()
                .noneMatch(n -> "Responder".equals(n.getAccessibleText()) || "Reaccionar".equals(n.getAccessibleText()))),
                "no reply/react buttons on hover either");
        svc.stop();
    }

    /** Which F6 zone holds the keyboard focus. */
    private static String zone(Scene scene) {
        for (Node n = scene.getFocusOwner(); n != null; n = n.getParent()) {
            if (n instanceof Rail) return "rail";
            if (n instanceof ChatListPane) return "list";
            if (n instanceof ListView<?> lv && lv.getStyleClass().contains("timeline")) return "timeline";
            if (n instanceof Composer) return "composer";
        }
        return "none";
    }

    /** As the system sends a letter: pressed, then typed, each to whatever has the focus at that moment. */
    private static void typeLetter(Scene scene, String letter) throws Exception {
        KeyCode code = KeyCode.getKeyCode(letter.toUpperCase());
        for (var type : List.of(KeyEvent.KEY_PRESSED, KeyEvent.KEY_TYPED)) {
            fx(() -> {
                Node target = scene.getFocusOwner() != null ? scene.getFocusOwner() : scene.getRoot();
                boolean typed = type == KeyEvent.KEY_TYPED;
                Event.fireEvent(target, new KeyEvent(type, typed ? letter : "", typed ? "" : letter,
                        typed ? KeyCode.UNDEFINED : code, false, false, false, false));
                return null;
            });
        }
        Thread.sleep(150);
    }

    private static void press(Scene scene, KeyCode code, boolean shift, boolean shortcut) throws Exception {
        fx(() -> {
            Node target = scene.getFocusOwner() != null ? scene.getFocusOwner() : scene.getRoot();
            boolean mac = MainView.MAC;
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift,
                    shortcut && !mac, false, shortcut && mac));
            return null;
        });
        Thread.sleep(150);
    }

    private static <T> T fx(Supplier<T> s) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(s.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(10, TimeUnit.SECONDS);
    }
}
