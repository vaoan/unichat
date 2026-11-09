package app.unichat.ui;

import app.unichat.service.ChatService;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
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
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
    }

    @Test
    void findAContactAndOpenOrCreateTheChat() throws Exception {
        MockChatService svc = new MockChatService();
        Scene scene = start(svc);

        shortcutN(scene);
        assertTrue(fx(() -> pane(scene) != null), "⌘N opens Nuevo chat");
        chooseNetwork(scene, "WhatsApp");
        // The contacts arrive on another thread: wait for them, then check there are exactly six.
        FxWait.until("WhatsApp's contacts listed", () -> rows(scene).size() >= 6);
        assertEquals(6, rows(scene).size(), rows(scene).toString());

        type(scene, "ana");
        FxWait.until("the search for ana", () -> names(scene).equals(List.of("Ana Restrepo", "Ana María")));
        assertEquals(List.of("Ana Restrepo", "Ana María"), names(scene));

        type(scene, "Mamá");
        FxWait.until("the search for Mamá", () -> names(scene).equals(List.of("Mamá")));
        enter(scene);
        assertEquals("!mama:demo", fx(() -> thread(scene).chat().id()), "an existing chat just opens");
        assertNull(fx(() -> pane(scene)), "and the pane closes");

        shortcutN(scene);
        chooseNetwork(scene, "WhatsApp");
        type(scene, "Carlos");
        FxWait.until("the search for Carlos", () -> names(scene).equals(List.of("Carlos Peña")));
        enter(scene);
        FxWait.until("Carlos's new chat opens", () -> thread(scene).chat().id().equals("!dm-c-carlos:demo"));
        assertEquals("!dm-c-carlos:demo", fx(() -> thread(scene).chat().id()), "a chat is created and opened");

        shortcutN(scene);
        chooseNetwork(scene, "WhatsApp");
        type(scene, "+57 300 111 2222");
        FxWait.until("the row to write to the number",
                () -> rows(scene).stream().anyMatch(r -> r.startsWith("Escribir a +57 300 111 2222")));
        assertTrue(rows(scene).stream().anyMatch(r -> r.startsWith("Escribir a +57 300 111 2222")), rows(scene).toString());

        chooseNetwork(scene, "Discord");
        FxWait.until("Discord's hint about Grupos", () -> helpText(scene).contains("Grupos"));
        String help = fx(() -> helpText(scene));
        assertTrue(help.contains("Grupos"), help);

        for (int i = 0; i < 2; i++) { // the first Esc empties the search box, the second closes (SearchFields)
            fx(() -> {
                Event.fireEvent(search(scene), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                return null;
            });
            FxWait.settled();
            if (i == 0) assertEquals("", fx(() -> search(scene).getText()), "the first Esc empties the search");
        }
        assertNull(fx(() -> pane(scene)), "then Esc closes");
        svc.stop();
    }

    private Scene start(MockChatService svc) throws Exception {
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            MainView view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 760);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            return sc;
        });
        FxWait.until("the chat list shows", () -> !scene.getRoot().lookupAll(".chat-list .list-cell").isEmpty());
        FxWait.settled();
        return scene;
    }

    @Test
    void typingBeforeASlowBridgeAnswersStillSearches() throws Exception {
        MockChatService svc = new MockChatService();
        svc.simulateSlowBridge(1200); // the scenario: the bridge says what it can do only after the person typed
        Scene scene = start(svc);
        shortcutN(scene);
        chooseNetwork(scene, "WhatsApp");
        type(scene, "Carlos");
        FxWait.until("the search for Carlos, once the bridge answered", () -> names(scene).equals(List.of("Carlos Peña")));
        assertEquals(List.of("Carlos Peña"), names(scene), "not stuck on 'no disponible'");
        svc.stop();
    }

    @Test
    void aClosedPaneDoesNotJumpToTheChatLater() throws Exception {
        MockChatService svc = new MockChatService();
        svc.simulateSlowBridge(800); // the scenario: the new chat arrives after the pane was closed
        Scene scene = start(svc);
        fx(() -> { scene.getRoot().lookupAll("*").stream().filter(n -> n instanceof MainView).map(n -> (MainView) n)
                .findFirst().orElseThrow().openChatId("!familia:demo"); return null; });
        shortcutN(scene);
        chooseNetwork(scene, "WhatsApp");
        type(scene, "Pedro");
        FxWait.until("the search for Pedro", () -> names(scene).equals(List.of("Pedro")));
        enter(scene); // the bridge creates the chat: it arrives later
        CompletableFuture<Void> arrived = arrival(svc, "!dm-c-pedro:demo");
        fx(() -> {
            Event.fireEvent(search(scene), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            return null;
        });
        fx(() -> {
            boolean mac = MainView.MAC;
            Event.fireEvent(scene.getRoot(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.K, false, !mac, false, mac));
            return null;
        });
        arrived.get(); // every listener has heard of Pedro's new chat
        FxWait.settled(); // and what they asked the window to do has run
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
        FxWait.until("Discord's hint read out with the search field",
                () -> String.valueOf(search(scene).getAccessibleHelp()).contains("Grupos"));
        String help = fx(() -> search(scene).getAccessibleHelp());
        assertTrue(help != null && help.contains("Grupos"), String.valueOf(help));
        chooseNetwork(scene, "WhatsApp");
        type(scene, "zzzz");
        FxWait.until("no results read out", () -> "Sin resultados".equals(search(scene).getAccessibleHelp()));
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

    /** The pane's first status line that says something. */
    private static String helpText(Scene s) {
        return s.getRoot().lookupAll(".new-chat-pane .settings-help").stream()
                .map(n -> ((Label) n).getText()).filter(NewChatUiTest::n).findFirst().orElse("");
    }

    /**
     * Completes once {@code chatId} reached the service and every listener heard of it: this one is added last, so the
     * window's own listeners have run (and queued their work on the FX thread) by then.
     */
    private static CompletableFuture<Void> arrival(MockChatService svc, String chatId) {
        CompletableFuture<Void> arrived = new CompletableFuture<>();
        svc.addListener(new ChatService.Listener() {
            @Override
            public void chatsChanged() {
                if (svc.chat(chatId) != null) arrived.complete(null);
            }
        });
        if (svc.chat(chatId) != null) arrived.complete(null); // already there: the scenario didn't happen as meant
        return arrived;
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
        FxWait.until("the " + name + " chip", () -> s.getRoot().lookupAll(".new-chat-network").stream()
                .anyMatch(n -> name.equals(((ToggleButton) n).getText())));
        fx(() -> {
            s.getRoot().lookupAll(".new-chat-network").stream().map(n -> (ToggleButton) n)
                    .filter(b -> name.equals(b.getText())).findFirst().orElseThrow().fire();
            return null;
        });
    }

    private static void type(Scene s, String text) throws Exception {
        FxWait.until("the search field", () -> search(s) != null);
        fx(() -> {
            search(s).setText(text);
            return null;
        });
    }

    /** Enter in the search field, then what it set off on the FX thread (opening a chat that exists) has run. */
    private static void enter(Scene s) throws Exception {
        fx(() -> {
            Event.fireEvent(search(s), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
        FxWait.settled();
    }

    private static void shortcutN(Scene s) throws Exception {
        fx(() -> {
            boolean mac = MainView.MAC;
            Event.fireEvent(s.getRoot(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.N, false, !mac, false, mac));
            return null;
        });
        FxWait.settled();
    }

    private static ThreadPane thread(Scene s) {
        return (ThreadPane) s.getRoot().lookupAll("*").stream().filter(n -> n instanceof ThreadPane).findFirst().orElseThrow();
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        return FxWait.fx(c);
    }
}
