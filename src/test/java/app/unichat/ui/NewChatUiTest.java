package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Nuevo chat (⌘N / Ctrl+N): reach any contact of a network, with or without an existing chat. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class NewChatUiTest {
    @BeforeAll
    static void toolkit() throws Exception {
        System.setProperty("java.awt.headless", "true");
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
    }

    @Test
    void findAContactAndOpenOrCreateTheChat() throws Exception {
        MockChatService svc = new MockChatService();
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            MainView view = new MainView(svc, stage, new Notifier(() -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 760);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            return sc;
        });
        Thread.sleep(400);

        shortcutN(scene);
        assertTrue(fx(() -> pane(scene) != null), "⌘N opens Nuevo chat");
        chooseNetwork(scene, "WhatsApp");
        assertEquals(6, rows(scene).size(), rows(scene).toString());

        type(scene, "ana");
        Thread.sleep(700); // 300 ms debounce + search
        assertEquals(List.of("Ana Restrepo", "Ana María"), names(scene));

        type(scene, "Mamá");
        Thread.sleep(700);
        enter(scene);
        assertEquals("!mama:demo", fx(() -> thread(scene).chat().id()), "an existing chat just opens");
        assertNull(fx(() -> pane(scene)), "and the pane closes");

        shortcutN(scene);
        chooseNetwork(scene, "WhatsApp");
        type(scene, "Carlos");
        Thread.sleep(700);
        enter(scene);
        Thread.sleep(300);
        assertEquals("!dm-c-carlos:demo", fx(() -> thread(scene).chat().id()), "a chat is created and opened");

        shortcutN(scene);
        chooseNetwork(scene, "WhatsApp");
        type(scene, "+57 300 111 2222");
        Thread.sleep(700);
        assertTrue(rows(scene).stream().anyMatch(r -> r.startsWith("Escribir a +57 300 111 2222")), rows(scene).toString());

        chooseNetwork(scene, "Discord");
        Thread.sleep(300);
        String help = fx(() -> scene.getRoot().lookupAll(".new-chat-pane .settings-help").stream()
                .map(n -> ((Label) n).getText()).filter(t -> n(t)).findFirst().orElse(""));
        assertTrue(help.contains("Grupos"), help);

        fx(() -> {
            Event.fireEvent(search(scene), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            return null;
        });
        Thread.sleep(200);
        assertNull(fx(() -> pane(scene)), "Esc closes");
        svc.stop();
    }

    private Scene start(MockChatService svc) throws Exception {
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            MainView view = new MainView(svc, stage, new Notifier(() -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 760);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            return sc;
        });
        Thread.sleep(400);
        return scene;
    }

    @Test
    void typingBeforeASlowBridgeAnswersStillSearches() throws Exception {
        MockChatService svc = new MockChatService();
        svc.simulateSlowBridge(1200);
        Scene scene = start(svc);
        shortcutN(scene);
        chooseNetwork(scene, "WhatsApp");
        type(scene, "Carlos");
        Thread.sleep(2000);
        assertEquals(List.of("Carlos Peña"), names(scene), "not stuck on 'no disponible'");
        svc.stop();
    }

    @Test
    void aClosedPaneDoesNotJumpToTheChatLater() throws Exception {
        MockChatService svc = new MockChatService();
        svc.simulateSlowBridge(800);
        Scene scene = start(svc);
        fx(() -> { scene.getRoot().lookupAll("*").stream().filter(n -> n instanceof MainView).map(n -> (MainView) n)
                .findFirst().orElseThrow().openChatId("!familia:demo"); return null; });
        shortcutN(scene);
        chooseNetwork(scene, "WhatsApp");
        Thread.sleep(1200);
        type(scene, "Pedro");
        Thread.sleep(1500);
        enter(scene);
        fx(() -> {
            Event.fireEvent(search(scene), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            return null;
        });
        fx(() -> {
            boolean mac = MainView.MAC;
            Event.fireEvent(scene.getRoot(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.K, false, !mac, false, mac));
            return null;
        });
        Thread.sleep(1500);
        assertTrue(fx(() -> scene.getRoot().lookup(".command-bar") != null && scene.getRoot().lookup(".command-bar").getScene() != null),
                "the command bar opened afterwards stays open");
        assertEquals("!familia:demo", fx(() -> thread(scene).chat().id()), "no jump to the chat you gave up on");
        svc.stop();
    }

    @Test
    void statusIsReadOutWithTheSearchField() throws Exception {
        MockChatService svc = new MockChatService();
        Scene scene = start(svc);
        shortcutN(scene);
        chooseNetwork(scene, "Discord");
        Thread.sleep(300);
        String help = fx(() -> search(scene).getAccessibleHelp());
        assertTrue(help != null && help.contains("Grupos"), String.valueOf(help));
        chooseNetwork(scene, "WhatsApp");
        type(scene, "zzzz");
        Thread.sleep(700);
        assertEquals("Sin resultados", fx(() -> search(scene).getAccessibleHelp()));
        svc.stop();
    }

    private static boolean n(String t) {
        return t != null && !t.isBlank();
    }

    private static Node pane(Scene s) {
        Node n = s.getRoot().lookup(".new-chat-pane");
        return n != null && n.isVisible() && n.getScene() != null ? n : null;
    }

    private static TextField search(Scene s) {
        return (TextField) s.getRoot().lookup(".new-chat-search");
    }

    @SuppressWarnings("unchecked")
    private static ListView<Object> list(Scene s) {
        return (ListView<Object>) s.getRoot().lookup(".new-chat-list");
    }

    private static List<String> rows(Scene s) throws Exception {
        return fx(() -> s.getRoot().lookupAll(".new-chat-list .list-cell").stream()
                .map(n -> (String) n.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.TEXT))
                .filter(NewChatUiTest::n).toList());
    }

    private static List<String> names(Scene s) throws Exception {
        return rows(s).stream().map(r -> r.split(",")[0]).toList();
    }

    private static void chooseNetwork(Scene s, String name) throws Exception {
        // The pane is built on the next pulse after ⌘N: wait for its network chips.
        for (int i = 0; i < 30 && !fx(() -> s.getRoot().lookupAll(".new-chat-network").stream()
                .anyMatch(n -> name.equals(((ToggleButton) n).getText()))); i++) Thread.sleep(100);
        fx(() -> {
            s.getRoot().lookupAll(".new-chat-network").stream().map(n -> (ToggleButton) n)
                    .filter(b -> name.equals(b.getText())).findFirst().orElseThrow().fire();
            return null;
        });
        Thread.sleep(300);
    }

    private static void type(Scene s, String text) throws Exception {
        fx(() -> {
            search(s).setText(text);
            return null;
        });
    }

    private static void enter(Scene s) throws Exception {
        fx(() -> {
            Event.fireEvent(search(s), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
        Thread.sleep(300);
    }

    private static void shortcutN(Scene s) throws Exception {
        fx(() -> {
            boolean mac = MainView.MAC;
            Event.fireEvent(s.getRoot(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.N, false, !mac, false, mac));
            return null;
        });
        Thread.sleep(300);
    }

    private static ThreadPane thread(Scene s) {
        return (ThreadPane) s.getRoot().lookupAll("*").stream().filter(n -> n instanceof ThreadPane).findFirst().orElseThrow();
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(c.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(10, TimeUnit.SECONDS);
    }
}
