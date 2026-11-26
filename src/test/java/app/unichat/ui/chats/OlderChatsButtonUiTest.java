package app.unichat.ui.chats;

import app.unichat.Present;
import app.unichat.model.Account;
import app.unichat.model.Network;
import app.unichat.service.DemoOlderChats;
import app.unichat.service.MockChatService;
import app.unichat.service.OlderChats;
import app.unichat.support.I18n;
import app.unichat.ui.FxWait;
import app.unichat.ui.MainView;
import app.unichat.ui.desktop.Notifier;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Load older chats" at the end of the inbox: where it shows and where it doesn't, which networks it asks, the keys
 * that open the warning, and that it waits while a run is under way. Over the whole window: button, sheet, Load.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class OlderChatsButtonUiTest {
    /** The demo, saying what the test says about older chats, and keeping each question it was asked. */
    static final class Fake extends MockChatService implements OlderChats {
        volatile boolean more = true;
        volatile @Nullable Loading loading;
        final List<Set<Network>> askedHasMore = new CopyOnWriteArrayList<>();
        final List<Runnable> listeners = new CopyOnWriteArrayList<>();

        @Override public boolean hasMore(Collection<Network> inView) {
            askedHasMore.add(Set.copyOf(inView));
            return more;
        }

        @Override public @Nullable Loading loading() { return loading; }
        @Override public void addLoadingListener(Runnable l) { listeners.add(l); }
        @Override public void removeLoadingListener(Runnable l) { listeners.remove(l); }

        void tell(@Nullable Loading l) {
            loading = l;
            listeners.forEach(Runnable::run);
        }
    }

    private final Fake svc = new Fake();
    private final List<Collection<Network>> opened = new CopyOnWriteArrayList<>();
    private final List<Stage> stages = new ArrayList<>();

    @BeforeAll
    static void toolkit() throws InterruptedException {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
    }

    @AfterEach
    void close() throws InterruptedException {
        FxWait.onFx(() -> stages.forEach(Stage::close));
        svc.stop();
    }

    private ChatListPane show(MockChatService service) throws InterruptedException {
        ChatListPane pane = FxWait.fx(() -> {
            ChatListPane p = new ChatListPane(service, c -> { }, new FolderDialogs(service, n -> { }, () -> { }));
            p.setFooter(new Label("account alerts"));
            p.setOnLoadOlderChats(opened::add);
            Scene scene = new Scene(p, 340, 700);
            scene.getStylesheets().add(OlderChatsButtonUiTest.class.getResource("/app/unichat/css/app.css").toExternalForm());
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(scene);
            stage.show();
            stages.add(stage);
            return p;
        });
        FxWait.settled();
        return pane;
    }

    private static @Nullable Button button(ChatListPane pane) throws InterruptedException {
        return FxWait.fx(() -> (Button) pane.lookup(".older-chats-button"));
    }

    private static boolean shown(ChatListPane pane) throws InterruptedException {
        Button b = Present.of(button(pane));
        return FxWait.fx(() -> b.isVisible() && b.isManaged());
    }

    private static void go(ChatListPane pane, ChatFilter f) throws InterruptedException {
        FxWait.onFx(() -> pane.goTo(f));
        FxWait.settled();
    }

    /** Every bridged network the demo has an account on: what "all" asks. */
    private Set<Network> withAccounts() {
        return svc.accounts().stream().map(Account::network).filter(Network::isBridged).collect(Collectors.toSet());
    }

    @Test
    void showsAtTheEndOfTheInboxWhileANetworkHasMore() throws Exception {
        ChatListPane pane = show(svc);
        assertTrue(shown(pane));
        assertEquals(I18n.t("olderChats.button"), FxWait.fx(() -> Present.of(button(pane)).getText()));
        List<Node> children = FxWait.fx(() -> List.copyOf(pane.getChildren()));
        int list = children.indexOf(FxWait.fx(() -> pane.lookup(".chat-list")));
        int here = children.indexOf(button(pane));
        int alerts = children.indexOf(children.stream().filter(n -> n instanceof Label l && "account alerts".equals(l.getText())).findFirst().orElseThrow());
        assertTrue(list >= 0 && list < here && here < alerts, "below the list, above the account alerts: " + list + " " + here + " " + alerts);
    }

    @Test
    void hiddenWhenNoNetworkInViewHasMore() throws Exception {
        svc.more = false;
        ChatListPane pane = show(svc);
        Button b = Present.of(button(pane));
        assertFalse(FxWait.fx(b::isVisible));
        assertFalse(FxWait.fx(b::isManaged), "it takes no room");
    }

    @Test
    void hiddenWhileSearchingAndInFoldersArchiveFavoritesAndServers() throws Exception {
        ChatListPane pane = show(svc);
        ChatFilter inbox = FxWait.fx(() -> pane.filterProperty().get());
        go(pane, inbox.withQuery("ana"));
        assertFalse(shown(pane), "searching");
        go(pane, inbox);
        assertTrue(shown(pane), "back in the inbox");
        FxWait.onFx(pane::showFolders);
        FxWait.settled();
        assertFalse(shown(pane), "in the folders");
        go(pane, inbox);
        go(pane, inbox.withView(ChatFilter.View.ARCHIVE));
        assertFalse(shown(pane), "in the archive");
        go(pane, inbox.withView(ChatFilter.View.FAVORITES));
        assertFalse(shown(pane), "in Favorites");
        go(pane, inbox);
        FxWait.onFx(() -> pane.showSpace("!server:demo"));
        FxWait.settled();
        assertFalse(shown(pane), "in a server's channels");
        go(pane, inbox.withUnreadOnly(true));
        assertTrue(shown(pane), "a quick filter of the inbox keeps it");
    }

    @Test
    void followsTheRailsNetwork() throws Exception {
        ChatListPane pane = show(svc);
        ChatFilter inbox = FxWait.fx(() -> pane.filterProperty().get());
        go(pane, inbox.switchTo(Network.TELEGRAM, null));
        assertEquals(Set.of(Network.TELEGRAM), svc.askedHasMore.getLast(), "the rail on Telegram");
        go(pane, inbox.switchTo(null, null));
        assertEquals(withAccounts(), svc.askedHasMore.getLast(), "all: the networks with an account");
        assertTrue(withAccounts().contains(Network.WHATSAPP) && !withAccounts().contains(Network.MATRIX));
    }

    @Test
    void aClickEnterOrSpaceOpensTheSheet() throws Exception {
        ChatListPane pane = show(svc);
        go(pane, FxWait.fx(() -> pane.filterProperty().get()).switchTo(Network.TELEGRAM, null));
        Button b = Present.of(button(pane));
        FxWait.onFx(b::fire);
        assertEquals(List.of(List.of(Network.TELEGRAM)), opened, "a click");
        FxWait.onFx(b::requestFocus);
        key(b, KeyCode.ENTER);
        assertEquals(2, opened.size(), "Enter");
        key(b, KeyCode.SPACE);
        assertEquals(3, opened.size(), "Space");
        assertTrue(opened.stream().allMatch(List.of(Network.TELEGRAM)::equals), "each with the networks in view: " + opened);
    }

    @Test
    void disabledAndSaysLoadingWhileARunIsUnderWay() throws Exception {
        ChatListPane pane = show(svc);
        Button b = Present.of(button(pane));
        svc.tell(new OlderChats.Loading(Network.TELEGRAM, OlderChats.Stage.ASKING, 0, 0, 2, java.util.Map.of()));
        FxWait.settled();
        assertTrue(FxWait.fx(b::isDisabled), "a second click while one runs does nothing");
        assertEquals(I18n.t("olderChats.button.loading"), FxWait.fx(b::getText));
        assertTrue(shown(pane));
        FxWait.onFx(b::fire);
        assertTrue(opened.isEmpty());

        svc.tell(new OlderChats.Loading(Network.WHATSAPP, OlderChats.Stage.DONE, 0, 2, 2, java.util.Map.of()));
        FxWait.settled();
        assertFalse(FxWait.fx(b::isDisabled), "done: enabled again");
        assertEquals(I18n.t("olderChats.button"), FxWait.fx(b::getText));

        svc.more = false;
        svc.tell(new OlderChats.Loading(Network.WHATSAPP, OlderChats.Stage.DONE, 0, 2, 2, java.util.Map.of()));
        FxWait.settled();
        assertFalse(shown(pane), "done with nothing more: hidden");
    }

    @Test
    void itsAccessibleTextNamesTheNetworks() throws Exception {
        ChatListPane pane = show(svc);
        Button b = Present.of(button(pane));
        go(pane, FxWait.fx(() -> pane.filterProperty().get()).switchTo(Network.TELEGRAM, null));
        assertEquals(I18n.t("olderChats.button.a11y", "Telegram"), FxWait.fx(b::getAccessibleText));
        go(pane, FxWait.fx(() -> pane.filterProperty().get()).switchTo(null, null));
        String all = FxWait.fx(b::getAccessibleText);
        for (Network n : withAccounts()) assertTrue(all.contains(n.displayName()), all);
        assertTrue(FxWait.fx(b::isFocusTraversable), "reachable with Tab");
    }

    @Test
    void notThereWithoutOlderChats() throws Exception {
        MockChatService plain = new MockChatService();
        try {
            ChatListPane pane = show(plain);
            assertNull(button(pane));
        } finally {
            plain.stop();
        }
    }

    @Test
    void aLoadLeavesTheInboxBadgeAsItWas() throws Exception {
        DemoOlderChats demo = new DemoOlderChats();
        demo.skipDemoPauses();
        Window w = window(demo);
        try {
            List<String> before = badges(w.view);
            int rows = listed(w.view);
            loadThroughTheSheet(w.view);
            FxWait.until("the run ends", () -> ended(demo));
            FxWait.until("the old chats are in the window's list", () -> listed(w.view) >= rows + 6);
            FxWait.settled();
            assertEquals(before, badges(w.view), "and they came read");
        } finally {
            w.close();
            demo.stop();
        }
    }

    @Test
    void theButtonsSheetLoadsWhatWasChosen() throws Exception {
        DemoOlderChats demo = new DemoOlderChats();
        demo.skipDemoPauses();
        Window w = window(demo);
        try {
            loadThroughTheSheet(w.view);
            // Instagram unticked stays out; the rest in the sheet's order.
            assertEquals(List.of(List.of(Network.TELEGRAM, Network.WHATSAPP, Network.DISCORD, Network.GOOGLE_MESSAGES)), demo.asked());
            FxWait.until("the bar on top shows the run", () -> Present.of(w.view.lookup(".older-chats-bar")).isVisible());
            FxWait.until("the run ends", () -> ended(demo));
        } finally {
            w.close();
            demo.stop();
        }
    }

    private record Window(MainView view, Stage stage) {
        void close() throws InterruptedException {
            FxWait.onFx(() -> {
                view.dispose();
                stage.getScene().setRoot(new javafx.scene.layout.Pane());
                stage.close();
            });
        }
    }

    private static Window window(MockChatService service) throws InterruptedException {
        Window w = FxWait.fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            MainView v = new MainView(service, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, u -> { });
            stage.setScene(new Scene(v, 1000, 700));
            stage.show();
            return new Window(v, stage);
        });
        FxWait.until("the chat list shows", () -> !w.view.lookupAll(".chat-list .list-cell").isEmpty());
        FxWait.settled();
        return w;
    }

    private static boolean ended(OlderChats svc) {
        OlderChats.Loading l = svc.loading();
        return l != null && l.stage() == OlderChats.Stage.DONE;
    }

    /** The button, then Load in the sheet it opens. */
    private static void loadThroughTheSheet(MainView view) throws InterruptedException {
        FxWait.until("the button shows", () -> view.lookup(".older-chats-button") instanceof Button b && b.isVisible());
        FxWait.onFx(() -> ((Button) view.lookup(".older-chats-button")).fire());
        FxWait.until("the sheet shows", () -> view.lookup(".older-chats-sheet") != null);
        FxWait.settled();
        FxWait.onFx(() -> ((Button) Present.of(view.lookup(".older-chats-sheet")).lookup(".primary-button")).fire());
        FxWait.settled();
    }

    /** The rows of the window's chat list. */
    private static int listed(MainView view) {
        return ((javafx.scene.control.ListView<?>) Present.of(view.lookup(".chat-list"))).getItems().size();
    }

    /** What the rail's unread badges say, in order. */
    private static List<String> badges(MainView view) throws InterruptedException {
        return FxWait.fx(() -> view.lookupAll(".rail-badge").stream().filter(Node::isVisible).map(n -> ((Label) n).getText()).toList());
    }

    private static void key(Node target, KeyCode code) throws InterruptedException {
        FxWait.onFx(() -> {
            String ch = code == KeyCode.SPACE ? " " : code == KeyCode.ENTER ? "\r" : "";
            for (var type : List.of(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
                Event.fireEvent(target, new KeyEvent(type, "", ch, code, false, false, false, false));
            }
        });
        FxWait.settled();
    }
}
