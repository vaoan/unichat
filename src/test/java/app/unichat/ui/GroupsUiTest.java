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
            if (n != null) view.showNetwork(n);
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
        // Cells are recycled, so lookup order isn't row order: find the server row by its text.
        assertTrue(rows.stream().anyMatch(r -> r.startsWith("Servidor Unichat") && r.contains("3 canales") && r.contains("2 sin leer")), rows.toString());
        assertEquals("!servidor:demo", fx(() -> tree.getTreeItem(0).getValue().id()), "servers first, loose chats last");

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
    void theBandejaGruposChoiceIsHardToMiss() throws Exception {
        Scene scene = open(Network.DISCORD);
        double toggleWidth = fx(() -> toggle(scene).getBoundsInParent().getWidth());
        double paneWidth = fx(() -> toggle(scene).getParent().getLayoutBounds().getWidth());
        assertTrue(toggleWidth > paneWidth * 0.8, "full-width tabs, not a small label: " + toggleWidth + " of " + paneWidth);
        assertTrue(fx(() -> button(scene, "Grupos").getHeight()) >= 26, "a real target to click");
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

    @Test
    void selectionSurvivesASyncAndAClickExpandsAServer() throws Exception {
        Scene scene = open(Network.DISCORD);
        fx(() -> { button(scene, "Grupos").fire(); return null; });
        Thread.sleep(300);
        TreeView<SpaceTree.Node> tree = fx(() -> tree(scene));
        javafx.scene.control.TreeCell<?> serverCell = fx(() -> (javafx.scene.control.TreeCell<?>) scene.getRoot()
                .lookupAll(".groups-tree .tree-cell").stream()
                .filter(n -> n instanceof javafx.scene.control.TreeCell<?> c && c.getItem() instanceof SpaceTree.Node sn && sn.id().equals("!servidor:demo"))
                .findFirst().orElseThrow());
        fx(() -> {
            Event.fireEvent(serverCell, new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED, 5, 5, 5, 5,
                    javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true, false, false, null));
            return null;
        });
        Thread.sleep(200);
        assertTrue(fx(() -> tree.getTreeItem(0).isExpanded()), "a click on a server opens it");

        int memes = fx(() -> indexOf(tree, "!srv-memes:demo"));
        fx(() -> { tree.requestFocus(); tree.getSelectionModel().select(memes); return null; });
        svc.setMuted("!srv-general:demo", true); // any change → chatsChanged → the tree is redrawn
        Thread.sleep(500);
        assertEquals("!srv-memes:demo", fx(() -> tree.getSelectionModel().getSelectedItem().getValue().id()),
                "arrowing through channels isn't reset by incoming changes");
        svc.stop();
    }

    private static List<String> listTexts(Scene s) {
        return s.getRoot().lookupAll(".chat-list .list-cell").stream()
                .map(n -> (String) n.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.TEXT))
                .filter(t -> t != null && !t.isEmpty()).toList();
    }

    @SuppressWarnings("unchecked")
    private static javafx.scene.control.ListView<app.unichat.model.Chat> chatList(Scene s) {
        return (javafx.scene.control.ListView<app.unichat.model.Chat>) s.getRoot().lookup(".chat-list");
    }

    private static List<String> spaceTexts(Scene s) {
        return s.getRoot().lookupAll(".space-list .list-cell").stream()
                .map(n -> (String) n.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.TEXT))
                .filter(t -> t != null && !t.isEmpty()).toList();
    }

    @Test
    void aServerIsOneRowWhoseChannelsCoverTheList() throws Exception {
        Scene scene = open(null); // Todos: every network
        List<String> rows = fx(() -> listTexts(scene));
        assertTrue(rows.stream().anyMatch(t -> t.startsWith("Servidor Unichat, servidor de Discord") && t.contains("2 sin leer")), rows.toString());
        assertTrue(fx(() -> chatList(scene).getItems()).stream().noneMatch(c -> c.id().equals("!srv-general:demo")),
                "its channels live inside it, like a Telegram forum");
        var list = fx(() -> chatList(scene));
        int server = fx(() -> list.getItems().indexOf(svc.chat("!servidor:demo")));
        fx(() -> { list.requestFocus(); list.getSelectionModel().select(server); return null; });
        key(list, KeyCode.ENTER);
        Thread.sleep(200);
        assertTrue(fx(() -> scene.getRoot().lookup(".space-view").isVisible()));
        assertFalse(fx(() -> list.isVisible()), "the channels cover the list");
        List<String> channels = fx(() -> spaceTexts(scene));
        assertTrue(channels.stream().anyMatch(t -> t.startsWith("#general")), channels.toString());
        assertTrue(channels.stream().anyMatch(t -> t.startsWith("#anuncios, canal sin unirte")), channels.toString());

        var spaceList = fx(() -> (javafx.scene.control.ListView<?>) scene.getRoot().lookup(".space-list"));
        int general = fx(() -> spaceList.getItems().indexOf(svc.chat("!srv-general:demo")));
        fx(() -> { spaceList.getSelectionModel().select(general); return null; });
        key(spaceList, KeyCode.ENTER);
        assertEquals("!srv-general:demo", fx(() -> thread(scene).chat().id()), "a channel opens like a chat");
        assertTrue(fx(() -> scene.getRoot().lookup(".space-view").isVisible()), "and the server stays open");

        key(spaceList, KeyCode.ESCAPE);
        assertFalse(fx(() -> scene.getRoot().lookup(".space-view").isVisible()), "Esc goes back");
        assertTrue(fx(() -> list.isVisible()));
        svc.stop();
    }

    @Test
    void aFavouriteServerOpensItsChannels() throws Exception {
        Scene scene = open(null);
        svc.setFavorite("!servidor:demo", true);
        Thread.sleep(400);
        javafx.scene.Node tile = fx(() -> scene.getRoot().lookupAll(".favorites > *").stream()
                .filter(n -> String.valueOf(n.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.TEXT)).contains("Servidor Unichat"))
                .findFirst().orElseThrow());
        key(tile, KeyCode.ENTER);
        Thread.sleep(200);
        assertTrue(fx(() -> spaceTexts(scene)).stream().anyMatch(t -> t.startsWith("#anuncios")), fx(() -> spaceTexts(scene)).toString());
        svc.stop();
    }

    private void enterOnAnuncios(Scene scene) throws Exception {
        fx(() -> { button(scene, "Grupos").fire(); return null; });
        Thread.sleep(300);
        TreeView<SpaceTree.Node> tree = fx(() -> tree(scene));
        fx(() -> { tree.requestFocus(); tree.getSelectionModel().select(0); return null; });
        key(tree, KeyCode.RIGHT);
        int anuncios = fx(() -> indexOf(tree, MockChatService.UNJOINED_CHANNEL_ID));
        fx(() -> { tree.getSelectionModel().select(anuncios); return null; });
        key(tree, KeyCode.ENTER);
    }

    private static String toastText(Scene s) throws Exception {
        return fx(() -> {
            javafx.scene.Node t = s.getRoot().lookup(".toast");
            return t != null && t.isVisible() ? ((javafx.scene.control.Label) t).getText() : "";
        });
    }

    @Test
    void aFailedJoinSaysSo() throws Exception {
        Scene scene = open(Network.DISCORD);
        svc.failJoins(true);
        enterOnAnuncios(scene);
        Thread.sleep(300);
        assertTrue(toastText(scene).startsWith("No se pudo unir"), toastText(scene));
        svc.stop();
    }

    @Test
    void aJoinThatArrivesTooLateDoesNotOpenByItself() throws Exception {
        Scene scene = open(Network.DISCORD);
        GroupsTree.joinWaitMs = 300;
        try {
            svc.simulateSlowBridge(900); // the joined room arrives after the wait is over
            fx(() -> { view.openChatId("!familia:demo"); return null; });
            enterOnAnuncios(scene);
            Thread.sleep(1500);
            assertEquals("!familia:demo", fx(() -> thread(scene).chat().id()), "no surprise jump minutes later");
            assertTrue(toastText(scene).contains("aparecerá"), toastText(scene));
        } finally {
            GroupsTree.joinWaitMs = 30_000;
        }
        svc.stop();
    }

    @Test
    void screenReaderHearsExpandedAndCollapsed() throws Exception {
        Scene scene = open(Network.DISCORD);
        fx(() -> { button(scene, "Grupos").fire(); return null; });
        Thread.sleep(300);
        TreeView<SpaceTree.Node> tree = fx(() -> tree(scene));
        fx(() -> { tree.requestFocus(); tree.getSelectionModel().select(0); return null; });
        assertTrue(fx(() -> rowTexts(scene)).stream().anyMatch(r -> r.startsWith("Servidor Unichat") && r.endsWith("contraído")));
        key(tree, KeyCode.RIGHT);
        assertTrue(fx(() -> rowTexts(scene)).stream().anyMatch(r -> r.startsWith("Servidor Unichat") && r.endsWith("desplegado")),
                fx(() -> rowTexts(scene)).toString());
        svc.stop();
    }

    @Test
    void discordWithNoServerInUnichatStillShowsYourServers() throws Exception {
        Scene scene = open(Network.DISCORD);
        svc.dropDemoServer(); // like a fresh Discord login: the bridge brings no server by itself
        Thread.sleep(400);
        assertTrue(fx(() -> toggle(scene) != null && toggle(scene).isVisible()), "Grupos offered for the servers you're in");
        fx(() -> { button(scene, "Grupos").fire(); return null; });
        Thread.sleep(300);
        List<String> rows = fx(() -> rowTexts(scene));
        assertTrue(rows.stream().anyMatch(r -> r.startsWith("Otros servidores") && r.contains("1 servidores")), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> r.startsWith("Servidor de juegos") && r.contains("Agregar")), rows.toString());
        svc.stop();
    }

    @Test
    void addingAServerBringsItWithItsChannels() throws Exception {
        Scene scene = open(Network.DISCORD);
        fx(() -> { button(scene, "Grupos").fire(); return null; });
        Thread.sleep(300);
        TreeView<SpaceTree.Node> tree = fx(() -> tree(scene));
        int offer = fx(() -> indexOf(tree, GroupsTree.OFFER_PREFIX + MockChatService.OFFERED_GUILD_ID));
        fx(() -> { tree.requestFocus(); tree.getSelectionModel().select(offer); return null; });
        key(tree, KeyCode.ENTER);
        Thread.sleep(500);
        assertTrue(svc.chats().stream().anyMatch(c -> c.space() && "Servidor de juegos".equals(c.title())), "the server came in");
        List<String> rows = fx(() -> rowTexts(scene));
        assertTrue(rows.stream().anyMatch(r -> r.startsWith("Servidor de juegos") && r.contains("1 canales")), rows.toString());
        assertTrue(rows.stream().noneMatch(r -> r.startsWith("Otros servidores")), "no longer offered: " + rows);
        assertTrue(toastText(scene).contains("Servidor de juegos"), toastText(scene));
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
