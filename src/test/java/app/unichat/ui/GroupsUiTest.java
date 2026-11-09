package app.unichat.ui;

import app.unichat.model.Network;
import app.unichat.service.ChatService;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

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
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
    }

    private Scene open(Network n) throws Exception {
        svc = new MockChatService();
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 760);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            if (n != null) view.showNetwork(n);
            return sc;
        });
        FxWait.until("the chat list shows", () -> !listTexts(scene).isEmpty());
        FxWait.settled();
        return scene;
    }

    /** Presses {@code code} on {@code target}, then waits until the window has done what it set off. */
    private static void key(Node target, KeyCode code) throws Exception {
        fx(() -> {
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            return null;
        });
        FxWait.settled();
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
        // The names of channels you aren't in come from the server's hierarchy, asked on opening it.
        FxWait.until("the unjoined channel's name", () -> spaceTexts(scene).stream().anyMatch(t -> t.startsWith("#anuncios")));
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
    void openingADiscordServerBringsAllItsChannels() throws Exception {
        Scene scene = open(null);
        enter(scene, fx(() -> chatList(scene)), "!servidor:demo");
        assertEquals(List.of("!servidor:demo"), svc.channelLoads(), "asked the bridge for every channel");
        key(fx(() -> spaceList(scene)), KeyCode.ESCAPE);
        enter(scene, fx(() -> chatList(scene)), "!servidor:demo");
        assertEquals(1, svc.channelLoads().size(), "once");
        svc.stop();
    }

    @Test
    void joiningAChannelOpensItWhenItArrives() throws Exception {
        Scene scene = open(Network.DISCORD);
        enter(scene, fx(() -> chatList(scene)), "!servidor:demo");
        enter(scene, fx(() -> spaceList(scene)), MockChatService.UNJOINED_CHANNEL_ID);
        FxWait.until("the joined channel opens", () -> MockChatService.UNJOINED_CHANNEL_ID.equals(thread(scene).chat().id()));
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
        FxWait.until("a toast about the failed join", () -> toastText(scene).startsWith("No se pudo unir"));
        assertTrue(toastText(scene).startsWith("No se pudo unir"), toastText(scene));
        svc.stop();
    }

    @Test
    void aJoinThatArrivesTooLateDoesNotOpenByItself() throws Exception {
        Scene scene = open(Network.DISCORD);
        // TODO(reactive): the scenario is "the wait (300 ms) ends before the room arrives (900 ms)", so it still rests on
        // two timers of the app. A MockChatService gate that holds a joined room until the test lets it arrive would
        // remove both: then Spaces.joinWaitMs could be 0 and the test would release the room after the toast.
        Spaces.joinWaitMs = 300;
        try {
            svc.simulateSlowBridge(900); // the joined room arrives after the wait is over
            fx(() -> { view.openChatId("!familia:demo"); return null; });
            enter(scene, fx(() -> chatList(scene)), "!servidor:demo");
            CompletableFuture<Void> arrived = arrival(svc, MockChatService.UNJOINED_CHANNEL_ID);
            enter(scene, fx(() -> spaceList(scene)), MockChatService.UNJOINED_CHANNEL_ID);
            FxWait.until("the toast that it will show up later", () -> toastText(scene).contains("aparecerá"));
            arrived.get(); // the room came in after all, and every listener heard of it
            FxWait.settled(); // and what they asked the window to do has run
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
        svc.setFavorite("!servidor:demo", true).get();
        FxWait.until("the server's favourite tile", () -> favouriteTile(scene) != null);
        javafx.scene.Node tile = fx(() -> favouriteTile(scene));
        key(tile, KeyCode.ENTER);
        FxWait.until("the server's channels", () -> spaceTexts(scene).stream().anyMatch(t -> t.startsWith("#anuncios")));
        assertTrue(fx(() -> spaceTexts(scene)).stream().anyMatch(t -> t.startsWith("#anuncios")), fx(() -> spaceTexts(scene)).toString());
        svc.stop();
    }

    private static javafx.scene.Node favouriteTile(Scene scene) {
        return scene.getRoot().lookupAll(".favorites > *").stream()
                .filter(n -> String.valueOf(n.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.TEXT)).contains("Servidor Unichat"))
                .findFirst().orElse(null);
    }

    @Test
    void discordServersNotInUnichatYetAreRowsLikeAnyChat() throws Exception {
        Scene scene = open(null); // Todos, with every contact
        svc.dropDemoServer(); // like a fresh Discord login: the bridge brings no server by itself
        String offer = Spaces.OFFER_PREFIX + MockChatService.OFFERED_GUILD_ID;
        var list = fx(() -> chatList(scene));
        FxWait.until("the server to add listed", () -> list.getItems().stream().anyMatch(c -> c.id().equals(offer)));
        FxWait.settled();
        assertTrue(fx(list::getItems).stream().anyMatch(c -> c.id().equals(offer)), "its own row, among the chats");
        assertTrue(fx(list::getItems).stream().noneMatch(c -> c.title().startsWith("Otros servidores")), "no separate zone");
        enter(scene, list, offer);
        FxWait.until("the added server's row", () -> list.getItems().stream()
                .anyMatch(c -> c.space() && "Servidor de juegos".equals(c.title())));
        assertTrue(svc.chats().stream().anyMatch(c -> c.space() && "Servidor de juegos".equals(c.title())), "the server came in");
        assertTrue(toastText(scene).contains("Servidor de juegos"), toastText(scene));
        assertTrue(fx(list::getItems).stream().noneMatch(c -> c.id().equals(offer)), "now a real server row");
        assertTrue(fx(list::getItems).stream().anyMatch(c -> c.space() && "Servidor de juegos".equals(c.title())));
        svc.stop();
    }

    @Test
    void searchFindsServersToAdd() throws Exception {
        Scene scene = open(Network.DISCORD);
        fx(() -> { ((javafx.scene.control.TextField) scene.getRoot().lookup(".search-field")).setText("juegos"); return null; });
        String offer = Spaces.OFFER_PREFIX + MockChatService.OFFERED_GUILD_ID;
        FxWait.until("the server to add found", () -> chatList(scene).getItems().stream().anyMatch(c -> c.id().equals(offer)));
        assertTrue(fx(() -> chatList(scene).getItems()).stream().anyMatch(c -> c.id().equals(offer)));
        svc.stop();
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
        return arrived;
    }

    private static ThreadPane thread(Scene s) {
        return (ThreadPane) s.getRoot().lookupAll("*").stream().filter(n -> n instanceof ThreadPane).findFirst().orElseThrow();
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        return FxWait.fx(c);
    }

    private static void clickRail(Scene s, String accessibleText) throws Exception {
        fx(() -> {
            for (Node n : s.getRoot().lookupAll(".rail-button")) {
                String t = n.getAccessibleText();
                // "Bandeja unificada, 5 chats sin leer", "WhatsApp (2 cuentas)"
                if (t != null && (t.equals(accessibleText) || t.startsWith(accessibleText + ",") || t.startsWith(accessibleText + " "))) {
                    ((javafx.scene.control.Button) n).fire();
                    return null;
                }
            }
            throw new AssertionError("no rail button " + accessibleText);
        });
        FxWait.settled();
    }

    @Test
    void theRailAlwaysTakesYouOutOfAServer() throws Exception {
        Scene scene = open(null);
        var list = fx(() -> chatList(scene));
        enter(scene, list, "!servidor:demo");
        assertTrue(fx(() -> spaceOpen(scene)));
        clickRail(scene, "Bandeja unificada");
        assertFalse(fx(() -> spaceOpen(scene)), "Bandeja from inside a server, already on Bandeja: back to the inbox");
        assertTrue(fx(list::isVisible));

        enter(scene, list, "!servidor:demo");
        clickRail(scene, "WhatsApp");
        assertFalse(fx(() -> spaceOpen(scene)), "another network: its chats, not the server");
        assertTrue(fx(list::getItems).stream().allMatch(c -> c.network() == Network.WHATSAPP || c.id().startsWith("offer")),
                fx(list::getItems).stream().map(app.unichat.model.Chat::id).toList().toString());
        svc.stop();
    }
}
