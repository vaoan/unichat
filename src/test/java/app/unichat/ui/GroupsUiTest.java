package app.unichat.ui;

import app.unichat.model.Network;
import app.unichat.service.MockChatService;
import app.unichat.settings.Settings;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bandeja | Grupos: a network with servers shows them as a tree you can open and join from. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class GroupsUiTest {
    private MockChatService svc;
    private MainView view;

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

    private Scene open(Network n) throws Exception {
        for (Network x : Network.values()) Settings.get().set("list.view." + x.name(), null);
        svc = new MockChatService();
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            view = new MainView(svc, stage, new Notifier(() -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 760);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            view.showNetwork(n);
            return sc;
        });
        Thread.sleep(500);
        return scene;
    }

    private static Node toggle(Scene s) {
        return s.getRoot().lookup(".list-view-toggle");
    }

    private static ToggleButton button(Scene s, String text) {
        return (ToggleButton) s.getRoot().lookupAll(".list-view-toggle .toggle-button").stream()
                .filter(n -> text.equals(((ToggleButton) n).getText())).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static TreeView<SpaceTree.Node> tree(Scene s) {
        return (TreeView<SpaceTree.Node>) s.getRoot().lookup(".groups-tree");
    }

    private static List<String> rowTexts(Scene s) {
        return s.getRoot().lookupAll(".groups-tree .tree-cell").stream()
                .map(n -> (String) n.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.TEXT))
                .filter(t -> t != null && !t.isEmpty()).toList();
    }

    private static void key(Node target, KeyCode code) throws Exception {
        fx(() -> {
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            return null;
        });
        Thread.sleep(150);
    }

    @Test
    void groupsViewShowsServersAndChannels() throws Exception {
        Scene scene = open(Network.DISCORD);
        assertTrue(fx(() -> toggle(scene) != null && toggle(scene).isVisible()), "Bandeja | Grupos shown for Discord");
        fx(() -> { button(scene, "Grupos").fire(); return null; });
        Thread.sleep(300);
        TreeView<SpaceTree.Node> tree = fx(() -> tree(scene));
        assertTrue(fx(tree::isVisible));
        List<String> rows = fx(() -> rowTexts(scene));
        assertTrue(rows.get(0).startsWith("Servidor Unichat") && rows.get(0).contains("3 canales") && rows.get(0).contains("2 sin leer"), rows.toString());

        fx(() -> { tree.requestFocus(); tree.getSelectionModel().select(0); return null; });
        key(tree, KeyCode.RIGHT);
        rows = fx(() -> rowTexts(scene));
        assertTrue(rows.stream().anyMatch(r -> r.startsWith("#general")), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> r.startsWith("#anuncios") && r.contains("Unirse")), rows.toString());

        int general = fx(() -> indexOf(tree, "!srv-general:demo"));
        fx(() -> { tree.getSelectionModel().select(general); return null; });
        key(tree, KeyCode.ENTER);
        assertEquals("!srv-general:demo", fx(() -> thread(scene).chat().id()));

        int anuncios = fx(() -> indexOf(tree, MockChatService.UNJOINED_CHANNEL_ID));
        fx(() -> { tree.requestFocus(); tree.getSelectionModel().select(anuncios); return null; });
        key(tree, KeyCode.ENTER);
        Thread.sleep(300);
        assertTrue(svc.chat(MockChatService.UNJOINED_CHANNEL_ID) != null, "Enter joined the channel");
        assertEquals(MockChatService.UNJOINED_CHANNEL_ID, fx(() -> thread(scene).chat().id()), "and opened it");
        svc.stop();
    }

    @Test
    void toggleHiddenWhenNetworkHasNoSpaces() throws Exception {
        Scene scene = open(Network.WHATSAPP);
        assertFalse(fx(() -> toggle(scene) != null && toggle(scene).isVisible()));
        svc.stop();
    }

    @Test
    void choiceIsRememberedPerNetwork() throws Exception {
        Scene scene = open(Network.DISCORD);
        fx(() -> { button(scene, "Grupos").fire(); return null; });
        assertEquals("GROUPS", Settings.get().getString("list.view.DISCORD", null));
        fx(() -> { view.showNetwork(Network.WHATSAPP); return null; });
        Thread.sleep(200);
        assertFalse(fx(() -> tree(scene).isVisible()), "WhatsApp keeps its Bandeja");
        fx(() -> { view.showNetwork(Network.DISCORD); return null; });
        Thread.sleep(200);
        assertTrue(fx(() -> tree(scene).isVisible()), "Discord comes back in Grupos");
        svc.stop();
    }

    private static int indexOf(TreeView<SpaceTree.Node> tree, String id) {
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            if (tree.getTreeItem(i) != null && id.equals(tree.getTreeItem(i).getValue().id())) return i;
        }
        throw new AssertionError("no row " + id);
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
