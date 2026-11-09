package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opening another chat redraws what changed (its unread state): not every row's network icon, the favorites or the rail's badges. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ListRepaintUiTest {
    private MockChatService svc;
    private Stage stage;
    private Scene scene;
    private MainView view;

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
        svc = new MockChatService();
        scene = FxWait.fx(() -> {
            stage = new Stage();
            view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 760);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            return sc;
        });
        FxWait.until("the chat list shows", () -> !scene.getRoot().lookupAll(".chat-list .list-cell .network-badge").isEmpty());
        FxWait.settled();
    }

    @AfterEach
    void close() throws Exception {
        svc.stop();
        FxWait.fx(() -> {
            stage.close();
            return null;
        });
    }

    /** Each row (cell) → the chat it shows and its network icon node. A cell may show another chat after a scroll. */
    private Map<Node, Object[]> badgeIcons() throws Exception {
        return FxWait.fx(() -> {
            Map<Node, Object[]> m = new IdentityHashMap<>();
            for (Node n : scene.getRoot().lookupAll(".chat-list .list-cell")) {
                if (!(n instanceof ChatRowCell cell) || cell.getItem() == null) continue;
                Node badge = cell.lookup(".network-badge");
                if (badge != null) m.put(cell, new Object[] {cell.getItem().id(), ((javafx.scene.control.Label) badge).getGraphic()});
            }
            return m;
        });
    }

    private List<Node> favoriteTiles() throws Exception {
        return FxWait.fx(() -> List.copyOf(scene.getRoot().lookupAll(".favorite-tile")));
    }

    @Test
    void openingAnotherChatKeepsTheRowsIconsAndTheFavorites() throws Exception {
        FxWait.fx(() -> {
            view.openChatId("!familia:demo");
            return null;
        });
        FxWait.settled();
        Map<Node, Object[]> before = badgeIcons();
        List<Node> railBadges = FxWait.fx(() -> List.copyOf(scene.getRoot().lookupAll(".rail-badge")));
        List<Node> favorites = favoriteTiles();
        assertFalse(before.isEmpty());
        assertFalse(favorites.isEmpty(), "the demo has favorites");

        FxWait.fx(() -> {
            view.openChatId("!mama:demo"); // unread: opening it marks it read, a change to the list
            return null;
        });
        FxWait.settled();

        Map<Node, Object[]> after = badgeIcons();
        int compared = 0;
        for (Map.Entry<Node, Object[]> e : before.entrySet()) {
            Object[] now = after.get(e.getKey());
            if (now == null || !now[0].equals(e.getValue()[0])) continue; // that cell shows another chat now
            compared++;
            assertSame(e.getValue()[1], now[1], "the network icon of " + now[0] + " was built again");
        }
        assertTrue(compared > 3, "rows compared: " + compared);
        for (Node n : FxWait.fx(() -> List.copyOf(scene.getRoot().lookupAll(".rail-badge")))) {
            assertTrue(railBadges.contains(n), "a rail unread badge was built again");
        }
        List<Node> favoritesAfter = favoriteTiles();
        assertEquals(favorites.size(), favoritesAfter.size());
        for (int i = 0; i < favorites.size(); i++) assertSame(favorites.get(i), favoritesAfter.get(i), "the favorites were built again");
    }
}
