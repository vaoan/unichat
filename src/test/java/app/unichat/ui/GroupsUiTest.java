package app.unichat.ui;

import app.unichat.model.Network;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
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

/** Servers/groups are rows of the Bandeja whose channels open over the list, like a Telegram group. */
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

    private static void key(Node target, KeyCode code) throws Exception {
        fx(() -> {
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            return null;
        });
        Thread.sleep(150);
    }

    private static List<String> listTexts(Scene s) {
        return s.getRoot().lookupAll(".chat-list .list-cell").stream()
                .filter(n -> !n.getStyleClass().contains("space-list"))
                .map(n -> (String) n.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.TEXT))
                .filter(t -> t != null && !t.isEmpty()).toList();
    }

    @SuppressWarnings("unchecked")
    private static javafx.scene.control.ListView<app.unichat.model.Chat> chatList(Scene s) {
        return (javafx.scene.control.ListView<app.unichat.model.Chat>) s.getRoot().lookup(".chat-list");
    }

    @SuppressWarnings("unchecked")
    private static javafx.scene.control.ListView<app.unichat.model.Chat> spaceList(Scene s) {
        return (javafx.scene.control.ListView<app.unichat.model.Chat>) s.getRoot().lookup(".space-list");
    }

    private static List<String> spaceTexts(Scene s) {
        return s.getRoot().lookupAll(".space-list .list-cell").stream()
                .map(n -> (String) n.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.TEXT))
                .filter(t -> t != null && !t.isEmpty()).toList();
    }

    private static boolean spaceOpen(Scene s) {
        return s.getRoot().lookup(".space-view").isVisible();
    }

    /** Opens a list row by id with Enter. */
    private void enter(Scene scene, javafx.scene.control.ListView<app.unichat.model.Chat> list, String id) throws Exception {
        int i = fx(() -> {
            for (int k = 0; k < list.getItems().size(); k++) if (list.getItems().get(k).id().equals(id)) return k;
            throw new AssertionError("no row " + id + " in " + list.getItems().stream().map(app.unichat.model.Chat::id).toList());
        });
        fx(() -> { list.requestFocus(); list.getSelectionModel().select(i); list.scrollTo(i); return null; });
        key(list, KeyCode.ENTER);
        Thread.sleep(200);
    }

    @Test
    void noBandejaGruposTabsDiscordIsLikeTelegram() throws Exception {
        Scene scene = open(Network.DISCORD);
        assertTrue(fx(() -> scene.getRoot().lookup(".list-view-toggle") == null), "no Bandeja | Grupos tabs");
        assertTrue(fx(() -> scene.getRoot().lookup(".groups-tree") == null));
        svc.stop();
    }

    @Test
    void aServerIsOneRowWhoseChannelsCoverTheList() throws Exception {
        Scene scene = open(null); // Todos: every network
        List<String> rows = fx(() -> listTexts(scene));
        assertTrue(rows.stream().anyMatch(t -> t.startsWith("Servidor Unichat, servidor de Discord") && t.contains("2 sin leer")), rows.toString());
        var list = fx(() -> chatList(scene));
        assertTrue(fx(list::getItems).stream().noneMatch(c -> c.id().equals("!srv-general:demo")),
                "its channels live inside it, like a Telegram group");
        enter(scene, list, "!servidor:demo");
        assertTrue(fx(() -> spaceOpen(scene)));
        assertFalse(fx(list::isVisible), "the channels cover the list");
        List<String> channels = fx(() -> spaceTexts(scene));
        assertTrue(channels.stream().anyMatch(t -> t.startsWith("#general")), channels.toString());
        assertTrue(channels.stream().anyMatch(t -> t.startsWith("#anuncios, canal sin unirte")), channels.toString());

        var spaces = fx(() -> spaceList(scene));
        enter(scene, spaces, "!srv-general:demo");
        assertEquals("!srv-general:demo", fx(() -> thread(scene).chat().id()), "a channel opens like a chat");
        assertTrue(fx(() -> spaceOpen(scene)), "and the server stays open");

        key(spaces, KeyCode.ESCAPE);
        assertFalse(fx(() -> spaceOpen(scene)), "Esc goes back");
        assertTrue(fx(list::isVisible));
        svc.stop();
    }

    @Test
    void joiningAChannelOpensItWhenItArrives() throws Exception {
        Scene scene = open(Network.DISCORD);
        enter(scene, fx(() -> chatList(scene)), "!servidor:demo");
        enter(scene, fx(() -> spaceList(scene)), MockChatService.UNJOINED_CHANNEL_ID);
        Thread.sleep(300);
        assertTrue(svc.chat(MockChatService.UNJOINED_CHANNEL_ID) != null, "Enter joined the channel");
        assertEquals(MockChatService.UNJOINED_CHANNEL_ID, fx(() -> thread(scene).chat().id()), "and opened it");
        svc.stop();
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
        enter(scene, fx(() -> chatList(scene)), "!servidor:demo");
        enter(scene, fx(() -> spaceList(scene)), MockChatService.UNJOINED_CHANNEL_ID);
        Thread.sleep(300);
        assertTrue(toastText(scene).startsWith("No se pudo unir"), toastText(scene));
        svc.stop();
    }

    @Test
    void aJoinThatArrivesTooLateDoesNotOpenByItself() throws Exception {
        Scene scene = open(Network.DISCORD);
        Spaces.joinWaitMs = 300;
        try {
            svc.simulateSlowBridge(900); // the joined room arrives after the wait is over
            fx(() -> { view.openChatId("!familia:demo"); return null; });
            enter(scene, fx(() -> chatList(scene)), "!servidor:demo");
            enter(scene, fx(() -> spaceList(scene)), MockChatService.UNJOINED_CHANNEL_ID);
            Thread.sleep(1500);
            assertEquals("!familia:demo", fx(() -> thread(scene).chat().id()), "no surprise jump minutes later");
            assertTrue(toastText(scene).contains("aparecerá"), toastText(scene));
        } finally {
            Spaces.joinWaitMs = 30_000;
        }
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

    @Test
    void discordServersNotInUnichatYetCanBeAddedFromTheList() throws Exception {
        Scene scene = open(Network.DISCORD);
        svc.dropDemoServer(); // like a fresh Discord login: the bridge brings no server by itself
        Thread.sleep(400);
        List<String> rows = fx(() -> listTexts(scene));
        assertTrue(rows.stream().anyMatch(t -> t.startsWith("Otros servidores de Discord, 1")), rows.toString());
        enter(scene, fx(() -> chatList(scene)), ChatListPane.OFFERS_ROW + Network.DISCORD.name());
        assertTrue(fx(() -> spaceTexts(scene)).stream().anyMatch(t -> t.startsWith("Servidor de juegos") && t.contains("Agregar")),
                fx(() -> spaceTexts(scene)).toString());
        enter(scene, fx(() -> spaceList(scene)), Spaces.OFFER_PREFIX + MockChatService.OFFERED_GUILD_ID);
        Thread.sleep(500);
        assertTrue(svc.chats().stream().anyMatch(c -> c.space() && "Servidor de juegos".equals(c.title())), "the server came in");
        assertTrue(toastText(scene).contains("Servidor de juegos"), toastText(scene));
        key(fx(() -> spaceList(scene)), KeyCode.ESCAPE);
        assertTrue(fx(() -> listTexts(scene)).stream().anyMatch(t -> t.startsWith("Servidor de juegos, servidor de Discord")),
                "it's a row of the Bandeja now");
        assertTrue(fx(() -> listTexts(scene)).stream().noneMatch(t -> t.startsWith("Otros servidores")), "nothing left to add");
        svc.stop();
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
