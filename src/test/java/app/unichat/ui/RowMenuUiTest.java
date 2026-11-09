package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Messages arriving refresh the lists and replace their cells: a menu anchored to a cell closed under the person. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class RowMenuUiTest {
    private static final String FAMILIA = "!familia:demo";

    private MockChatService svc;
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
    }

    @AfterEach
    void closeWindow() throws Exception {
        svc.stop();
        fx(() -> {
            openMenus().forEach(ContextMenu::hide);
            ((Stage) scene.getWindow()).close();
            return null;
        });
    }

    @Test
    void aRowsMenuStaysOpenWhileTheListsRefresh() throws Exception {
        String work = svc.createFolder("Trabajo");
        svc.setInFolder(work, svc.chat(FAMILIA), true);

        assertMenuSurvivesRefresh(".chat-list");
        fx(() -> {
            scene.getRoot().lookupAll(".rail-button").stream()
                    .filter(n -> "Carpetas".equals(((javafx.scene.control.Button) n).getTooltip().getText()))
                    .findFirst().map(javafx.scene.control.Button.class::cast).orElseThrow().fire();
            return null;
        });
        FxWait.until("the folder list shows", () -> firstRow(".folder-list") != null);
        assertMenuSurvivesRefresh(".folder-list");
        fx(() -> {
            Event.fireEvent(firstRow(".folder-list"), new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, false, false, false, null));
            return null;
        });
        FxWait.until("the folder's chats show", () -> firstRow(".folder-chats") != null);
        assertMenuSurvivesRefresh(".folder-chats");
    }

    /** The person's usual state: a chat open, the list scrolled elsewhere, and other chats moving as messages arrive. */
    @Test
    void aRowsMenuStaysOpenWithAChatOpenAndTheListScrolled() throws Exception {
        fx(() -> {
            scene.getWindow().setHeight(300);
            return null;
        });
        ChatListPane pane = fx(() -> (ChatListPane) scene.getRoot().lookupAll("*").stream()
                .filter(ChatListPane.class::isInstance).findFirst().orElseThrow());
        fx(() -> {
            pane.setSelected(FAMILIA);
            return null;
        });
        FxWait.settled();
        fx(() -> {
            @SuppressWarnings("unchecked")
            javafx.scene.control.ListView<app.unichat.model.Chat> l =
                    (javafx.scene.control.ListView<app.unichat.model.Chat>) scene.getRoot().lookup(".chat-list");
            // The bottom of the list, at its last row that has a menu: a server to add has none, and in a short
            // window it may be the only row in view.
            int last = l.getItems().size() - 1;
            while (last > 0 && pane.rowMenu(l.getItems().get(last)) == null) last--;
            l.scrollTo(last);
            return null;
        });
        FxWait.settled();
        fx(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            List<Node> rows = scene.getRoot().lookupAll(".chat-list .list-cell").stream()
                    .filter(n -> n instanceof ListCell<?> c && c.getItem() != null && n.isVisible()).toList();
            // From the bottom: some rows aren't chats yet (a server to add) and have no menu.
            for (int i = rows.size() - 1; i >= 0 && openMenus().isEmpty(); i--) {
                Event.fireEvent(rows.get(i), new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, 0, 0, false, null));
            }
            return null;
        });
        assertFalse(fx(() -> openMenus().isEmpty()), () -> "no row opened a menu; rows: " + rowsDebug());
        ContextMenu menu = fx(() -> openMenus().get(0));
        Throwable[] closedBy = new Throwable[1];
        fx(() -> {
            menu.setOnHidden(e -> closedBy[0] = new Throwable());
            return null;
        });
        for (int i = 0; i < 6; i++) {
            svc.setPinned(FAMILIA, i % 2 == 0).get();
            FxWait.settled(); // the lists' refresh for it has run
        }
        assertTrue(closedBy[0] == null, "the menu closed when the list refreshed");
    }

    private void assertMenuSurvivesRefresh(String list) throws Exception {
        fx(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            Event.fireEvent(firstRow(list), new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, 0, 0, false, null));
            return null;
        });
        assertFalse(fx(RowMenuUiTest::openMenus).isEmpty(), "the menu of " + list + " opens");
        for (int i = 0; i < 3; i++) { // refreshes are coalesced: let each one land before the next
            svc.setPinned(FAMILIA, i % 2 == 0).get();
            FxWait.settled();
        }
        boolean open = fx(() -> !openMenus().isEmpty());
        fx(() -> {
            openMenus().forEach(ContextMenu::hide);
            return null;
        });
        assertTrue(open, "the menu of " + list + " closed when the list refreshed");
    }

    private Node firstRow(String list) {
        return scene.getRoot().lookupAll(list + " .list-cell").stream()
                .filter(n -> n instanceof ListCell<?> c && c.getItem() != null && n.isVisible())
                .findFirst().orElseThrow(() -> new AssertionError("no row in " + list));
    }

    private String rowsDebug() {
        try {
            return fx(() -> scene.getRoot().lookupAll(".chat-list .list-cell").stream()
                    .map(n -> n.getClass().getSimpleName() + ":" + (n instanceof ListCell<?> c && c.getItem() != null ? c.getItem() : "empty") + ":" + n.isVisible())
                    .toList().toString());
        } catch (Exception e) {
            return e.toString();
        }
    }

    private static List<ContextMenu> openMenus() {
        return Window.getWindows().stream().filter(w -> w instanceof ContextMenu && w.isShowing())
                .map(ContextMenu.class::cast).toList();
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        return FxWait.fx(c);
    }
}
