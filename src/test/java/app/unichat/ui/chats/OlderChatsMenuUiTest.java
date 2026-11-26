package app.unichat.ui.chats;

import app.unichat.Present;
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
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Load older chats" in the rail's network menu and the command bar (⌘K): where each is offered and where not, which
 * networks it asks, that it waits while a run is under way, and that the old row under the inbox list is gone. Over the
 * whole window: menu or command, sheet, Load.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class OlderChatsMenuUiTest {
    /** The demo, saying what the test says about older chats, and keeping each question it was asked. */
    static final class Fake extends MockChatService implements OlderChats {
        /** The networks with a step to run, while {@link #more}. */
        static final List<Network> OFFERED = List.of(Network.TELEGRAM, Network.WHATSAPP);
        volatile boolean more = true;
        volatile @Nullable Loading loading;
        final List<Set<Network>> askedPlans = new CopyOnWriteArrayList<>();
        final List<Runnable> listeners = new CopyOnWriteArrayList<>();

        @Override public List<Plan> plans(Collection<Network> inView) {
            askedPlans.add(Set.copyOf(inView));
            if (!more) return List.of();
            return OFFERED.stream().filter(inView::contains).map(n -> new Plan(n, 100, true, false, false, null)).toList();
        }

        @Override public boolean hasMore(Collection<Network> inView) { return !plans(inView).isEmpty(); }

        @Override public @Nullable Loading loading() { return loading; }
        @Override public void addLoadingListener(Runnable l) { listeners.add(l); }
        @Override public void removeLoadingListener(Runnable l) { listeners.remove(l); }

        void tell(@Nullable Loading l) {
            loading = l;
            listeners.forEach(Runnable::run);
        }
    }

    /** What the person types to find the command, in the language the tests run in. */
    private static final String OLDER = I18n.t("olderChats.menu").replace("…", "");

    private final Fake svc = new Fake();
    private final List<Win> wins = new ArrayList<>();

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
        FxWait.onFx(() -> {
            openMenus().forEach(ContextMenu::hide);
        });
        for (Win w : wins) w.close();
        svc.stop();
    }

    private record Win(MainView view, Stage stage) {
        void close() throws InterruptedException {
            FxWait.onFx(() -> {
                view.dispose();
                stage.getScene().setRoot(new javafx.scene.layout.Pane());
                stage.close();
            });
        }
    }

    private static Win window(MockChatService service) throws InterruptedException {
        Win w = FxWait.fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            MainView v = new MainView(service, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, u -> { });
            stage.setScene(new Scene(v, 1000, 700));
            stage.show();
            return new Win(v, stage);
        });
        FxWait.until("the chat list shows", () -> !w.view.lookupAll(".chat-list .list-cell").isEmpty());
        FxWait.settled();
        return w;
    }

    private Win shown(MockChatService service) throws InterruptedException {
        Win w = window(service);
        wins.add(w);
        return w;
    }

    private static List<ContextMenu> openMenus() {
        return javafx.stage.Window.getWindows().stream().filter(w -> w instanceof ContextMenu && w.isShowing())
                .map(ContextMenu.class::cast).toList();
    }

    /** The menu a right-click on {@code n}'s rail button opens. */
    private static ContextMenu railMenu(Win w, Network n) throws InterruptedException {
        FxWait.onFx(() -> {
            Button b = w.view.lookupAll(".rail-button").stream().map(Button.class::cast)
                    .filter(x -> x.getTooltip().getText().startsWith(n.displayName())).findFirst().orElseThrow();
            Event.fireEvent(b, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, 0, 0, false, null));
        });
        FxWait.until("the menu is open", () -> !openMenus().isEmpty());
        return FxWait.fx(() -> openMenus().getFirst());
    }

    private static @Nullable MenuItem older(ContextMenu menu) throws InterruptedException {
        return FxWait.fx(() -> menu.getItems().stream()
                .filter(i -> i.getText() != null && (i.getText().equals(I18n.t("olderChats.menu"))
                        || i.getText().equals(I18n.t("olderChats.menu.loading"))))
                .findFirst().orElse(null));
    }

    @Test
    void theNetworksMenuOffersItAndOpensTheSheetForThatNetworkOnly() throws Exception {
        DemoOlderChats demo = new DemoOlderChats();
        demo.skipDemoPauses();
        Win w = shown(demo);
        MenuItem item = Present.of(older(railMenu(w, Network.TELEGRAM)));
        assertEquals(I18n.t("olderChats.menu"), FxWait.fx(item::getText));
        assertFalse(FxWait.fx(item::isDisable));
        List<String> order = FxWait.fx(() -> item.getParentPopup().getItems().stream().map(MenuItem::getText).toList());
        assertEquals(I18n.t("olderChats.menu"), order.get(order.size() - 3), "after the accounts, before the separator and Add another");
        FxWait.onFx(item::fire);
        pressLoad(w.view);
        assertEquals(List.of(List.of(Network.TELEGRAM)), demo.asked(), "just the network whose menu it was");
        FxWait.until("the run ends", () -> ended(demo));
    }

    @Test
    void theNetworksMenuHasNoItemWithoutAStepOrWithoutOlderChats() throws Exception {
        Win w = shown(svc);
        assertNull(older(railMenu(w, Network.SIGNAL)), "Signal has no step");
        FxWait.onFx(() -> openMenus().forEach(ContextMenu::hide));
        assertNotNull(older(railMenu(w, Network.WHATSAPP)), "WhatsApp has");
        FxWait.onFx(() -> openMenus().forEach(ContextMenu::hide));
        svc.more = false;
        assertNull(older(railMenu(w, Network.WHATSAPP)), "nothing more to load: no item (read as it opens)");
        FxWait.onFx(() -> openMenus().forEach(ContextMenu::hide));

        MockChatService plain = new MockChatService();
        try {
            Win p = shown(plain);
            assertNull(older(railMenu(p, Network.WHATSAPP)), "a service without older chats");
        } finally {
            plain.stop();
        }
    }

    @Test
    void duringARunTheItemIsThereDisabledAndSaysLoading() throws Exception {
        Win w = shown(svc);
        svc.tell(new OlderChats.Loading(Network.TELEGRAM, OlderChats.Stage.ASKING, 0, 0, 2, java.util.Map.of()));
        MenuItem item = Present.of(older(railMenu(w, Network.TELEGRAM)));
        assertTrue(FxWait.fx(item::isDisable));
        assertEquals(I18n.t("olderChats.menu.loading"), FxWait.fx(item::getText));
        FxWait.onFx(() -> openMenus().forEach(ContextMenu::hide));

        svc.tell(new OlderChats.Loading(Network.WHATSAPP, OlderChats.Stage.DONE, 0, 2, 2, java.util.Map.of()));
        MenuItem again = Present.of(older(railMenu(w, Network.TELEGRAM)));
        assertFalse(FxWait.fx(again::isDisable), "done: enabled again");
        assertEquals(I18n.t("olderChats.menu"), FxWait.fx(again::getText));
    }

    /** The command bar's entries for "older". */
    private static List<String> commands(Win w) throws InterruptedException {
        FxWait.onFx(w.view::showCommandBar);
        FxWait.until("the bar shows", () -> w.view.lookup(".command-field") != null);
        FxWait.onFx(() -> ((TextField) Present.of(w.view.lookup(".command-field"))).setText(OLDER));
        FxWait.settled();
        return FxWait.fx(() -> w.view.lookupAll(".command-label").stream().map(n -> ((Label) n).getText()).toList());
    }

    @Test
    void theCommandBarListsItWhileANetworkHasAStep() throws Exception {
        assertEquals(List.of(I18n.t("olderChats.menu")), commands(shown(svc)));
    }

    @Test
    void theCommandBarDoesNotListItWhileARunIsUnderWay() throws Exception {
        svc.tell(new OlderChats.Loading(Network.TELEGRAM, OlderChats.Stage.ASKING, 0, 0, 2, java.util.Map.of()));
        assertEquals(List.of(), commands(shown(svc)), "the bar has no disabled entries");
    }

    @Test
    void theCommandBarDoesNotListItWithNoStep() throws Exception {
        svc.more = false;
        assertEquals(List.of(), commands(shown(svc)));
    }

    @Test
    void theCommandBarHasNothingWithoutOlderChats() throws Exception {
        MockChatService plain = new MockChatService();
        try {
            assertEquals(List.of(), commands(shown(plain)));
        } finally {
            plain.stop();
        }
    }

    @Test
    void commandKThenTheCommandAsksEveryNetworkWithAStep() throws Exception {
        DemoOlderChats demo = new DemoOlderChats();
        demo.skipDemoPauses();
        Win w = shown(demo);
        boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
        FxWait.onFx(() -> Event.fireEvent(w.view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.K, false, !mac, false, mac)));
        FxWait.until("the bar shows", () -> w.view.lookup(".command-field") != null);
        FxWait.onFx(() -> ((TextField) Present.of(w.view.lookup(".command-field"))).setText(OLDER));
        FxWait.settled();
        key(Present.of(w.view.lookup(".command-field")), KeyCode.ENTER);
        pressLoad(w.view);
        // Instagram is unticked in the sheet; the rest in its order.
        assertEquals(List.of(List.of(Network.TELEGRAM, Network.WHATSAPP, Network.DISCORD, Network.GOOGLE_MESSAGES)), demo.asked());
        FxWait.until("the bar on top shows the run", () -> Present.of(w.view.lookup(".older-chats-bar")).isVisible());
        FxWait.until("the run ends", () -> ended(demo));
    }

    @Test
    void theCommandAsksOnlyTheRailsNetworkWhenOneIsChosen() throws Exception {
        DemoOlderChats demo = new DemoOlderChats();
        demo.skipDemoPauses();
        Win w = shown(demo);
        FxWait.onFx(() -> w.view.showNetwork(Network.WHATSAPP));
        FxWait.settled();
        commands(w);
        key(Present.of(w.view.lookup(".command-field")), KeyCode.ENTER);
        pressLoad(w.view);
        assertEquals(List.of(List.of(Network.WHATSAPP)), demo.asked());
        FxWait.until("the run ends", () -> ended(demo));
    }

    @Test
    void theListNoLongerHasTheRow() throws Exception {
        Win w = shown(svc);
        assertNull(FxWait.fx(() -> w.view.lookup(".older-chats-button")));
        assertFalse(FxWait.fx(() -> w.view.list().getChildren().stream().anyMatch(n -> n.getStyleClass().contains("older-chats-button"))));
        assertTrue(Files.readString(Path.of("src/main/resources/app/unichat/css/app.css")).indexOf("older-chats-button") < 0,
                "its styles are gone too");
    }

    @Test
    void aLoadLeavesTheInboxBadgeAsItWas() throws Exception {
        DemoOlderChats demo = new DemoOlderChats();
        demo.skipDemoPauses();
        Win w = shown(demo);
        List<String> before = badges(w.view);
        int rows = listed(w.view);
        MenuItem item = Present.of(older(railMenu(w, Network.TELEGRAM)));
        FxWait.onFx(item::fire);
        pressLoad(w.view);
        FxWait.until("the run ends", () -> ended(demo));
        FxWait.until("the old chats are in the window's list", () -> listed(w.view) >= rows + 1);
        FxWait.settled();
        assertEquals(before, badges(w.view), "and they came read");
    }

    private static boolean ended(OlderChats svc) {
        OlderChats.Loading l = svc.loading();
        return l != null && l.stage() == OlderChats.Stage.DONE;
    }

    /** Load in the sheet that opened. */
    private static void pressLoad(MainView view) throws InterruptedException {
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
