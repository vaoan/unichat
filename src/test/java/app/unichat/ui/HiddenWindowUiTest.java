package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With the window closed to the menu bar nothing is drawn, so JavaFX never empties the scene's list of changed nodes: every
 * node created then stayed in memory until the app ran out (a real heap dump: 1 GB in 113,880 entries).
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class HiddenWindowUiTest {
    private static final String FAMILIA = "!familia:demo";

    private MockChatService svc;
    private Stage stage;
    private Scene scene;

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
        Platform.setImplicitExit(false);
    }

    @BeforeEach
    void openWindow() throws Exception {
        svc = new MockChatService();
        scene = fx(() -> {
            stage = new Stage();
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
    }

    @AfterEach
    void closeWindow() throws Exception {
        svc.stop();
        fx(() -> {
            stage.close();
            return null;
        });
    }

    private int changedNodes() throws Exception {
        Field f = Scene.class.getDeclaredField("dirtyNodesSize");
        f.setAccessible(true);
        return fx(() -> {
            try {
                return f.getInt(scene);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void changeChatsAndMessages(int rounds) throws Exception {
        for (int i = 0; i < rounds; i++) {
            svc.setPinned(FAMILIA, i % 2 == 0);
            svc.setFavorite(FAMILIA, i % 2 == 0);
            svc.sendText(FAMILIA, "hola " + i, null).get();
            FxWait.settled(); // every refresh asked for has run
        }
    }

    @Test
    void aClosedWindowDoesNotPileUpNodes() throws Exception {
        fx(() -> {
            stage.hide();
            return null;
        });
        FxWait.settled();
        changeChatsAndMessages(3); // the nodes that already exist are listed once, whatever changes them
        int settled = changedNodes();
        assertTrue(settled < 20, settled + " nodes listed after 3 rounds");
        changeChatsAndMessages(12);
        assertEquals(settled, changedNodes(), "what's built with no window showing is never released");
    }

    @Test
    void openingTheWindowShowsWhatChangedMeanwhile() throws Exception {
        boolean was = svc.chat(FAMILIA).favorite();
        fx(() -> {
            stage.hide();
            return null;
        });
        FxWait.settled();
        svc.setFavorite(FAMILIA, !was).get();
        FxWait.settled();
        int tiles = fx(() -> scene.getRoot().lookupAll(".favorite-tile").size());
        fx(() -> {
            stage.show();
            return null;
        });
        int expected = tiles + (was ? -1 : 1);
        FxWait.until("the favorites row brought up to date on opening",
                () -> scene.getRoot().lookupAll(".favorite-tile").size() == expected);
    }

    @Test
    void theMenuBarCountStaysCurrentWithTheWindowClosed() throws Exception {
        fx(() -> {
            stage.hide();
            return null;
        });
        FxWait.settled();
        svc.setMarkedUnread(FAMILIA, false);
        svc.markRead(FAMILIA);
        long unread = svc.chats().stream().filter(ChatFilter::inboxUnread).count();
        String expected = unread > 0 ? "Unichat (" + unread + ")" : "Unichat";
        FxWait.until("the title counts " + unread + " unread", () -> expected.equals(stage.getTitle()));
    }

    @Test
    void theRailKeepsItsButtons() throws Exception {
        List<Node> first = fx(this::railButtons);
        assertFalse(first.isEmpty());
        svc.setPinned(FAMILIA, true).get();
        FxWait.settled();
        List<Node> second = fx(this::railButtons);
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) assertSame(first.get(i), second.get(i), "rebuilt on every change");
    }

    @Test
    void aReusedRailButtonStillShowsItsUnread() throws Exception {
        fx(() -> {
            Button whatsapp = railButtons().stream().map(Button.class::cast)
                    .filter(b -> b.getTooltip().getText().startsWith("WhatsApp")).findFirst().orElseThrow();
            assertTrue(whatsapp.getAccessibleText().startsWith("WhatsApp"));
            return null;
        });
    }

    private List<Node> railButtons() {
        return scene.getRoot().lookupAll(".rail-button").stream()
                .filter(n -> n.getStyleClass().contains("rail-button") && n instanceof Button b && b.getGraphic() != null
                        && inRail(b))
                .toList();
    }

    /** The networks sit in a scroll area inside the rail (Rail.networkScroll), not directly in it. */
    private static boolean inRail(Node n) {
        for (Node p = n.getParent(); p != null; p = p.getParent()) if (p instanceof Rail) return true;
        return false;
    }

    private static <T> T fx(java.util.concurrent.Callable<T> c) throws Exception {
        return FxWait.fx(c);
    }
}
