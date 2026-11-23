package app.unichat.ui.chats;

import app.unichat.service.MockChatService;
import app.unichat.ui.FxWait;
import app.unichat.ui.MainView;
import app.unichat.ui.Theme;
import app.unichat.ui.desktop.Notifier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The window tells the service when Archivados opens and closes (what rests wakes meanwhile), from the rail or anything
 * else that shows the archive; a hidden window doesn't close it. An archived chat or server opened there is told too.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ArchiveOpenUiTest {
    @Test
    void openingAndLeavingArchivadosIsTold() throws Exception {
        System.setProperty("java.awt.headless", "true"); // no tray icon in tests
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        List<Boolean> told = new CopyOnWriteArrayList<>();
        List<String> opened = new CopyOnWriteArrayList<>();
        List<String> shown = new CopyOnWriteArrayList<>();
        MockChatService svc = new MockChatService() {
            @Override public void setArchiveOpen(boolean open) { told.add(open); }
            @Override public void archivedChatOpened(String chatId) { opened.add(chatId); }
            @Override public void setOpenChat(String chatId) { shown.add(chatId); }
        };
        Stage stage = FxWait.fx(Stage::new);
        MainView view = FxWait.fx(() -> {
            stage.setOpacity(0);
            MainView v = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(v, 1100, 700);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            return v;
        });
        FxWait.settled();
        assertEquals(false, told.getLast(), "the inbox: closed");

        click(rail(view, "Archivados"));
        assertEquals(true, told.getLast(), "the rail opens it");
        click(rail(view, "Bandeja unificada"));
        assertEquals(false, told.getLast(), "and leaving closes it");

        ChatListPane list = FxWait.fx(() -> (ChatListPane) view.lookupAll("*").stream()
                .filter(n -> n instanceof ChatListPane).findFirst().orElseThrow());
        FxWait.fx(() -> { list.filterProperty().set(list.filterProperty().get().withView(ChatFilter.View.ARCHIVE)); return null; });
        assertEquals(true, told.getLast(), "any way to the archive (the command bar, an archived chat opened)");
        app.unichat.model.Chat archived = svc.chats().stream().filter(c -> c.archived() && !c.space()).findFirst().orElseThrow();
        FxWait.fx(() -> { view.openChat(archived); return null; });
        assertEquals(List.of(archived.id()), opened, "an archived chat opened in the archive");
        assertEquals(archived.id(), shown.getLast(), "the chat shown never rests");
        app.unichat.model.Chat space = svc.chats().stream().filter(app.unichat.model.Chat::space).findFirst().orElseThrow();
        svc.setArchived(space.id(), true).get();
        FxWait.fx(() -> { list.open(space); return null; });
        assertEquals(space.id(), opened.getLast(), "a server opened in the archive");
        FxWait.settled();
        assertEquals(true, told.getLast(), "still in the archive, its server's channels showing: " + told);
        int toldBefore = told.size();
        FxWait.fx(() -> { stage.hide(); return null; });
        FxWait.settled();
        assertEquals(toldBefore, told.size(), "a hidden window doesn't leave the archive: " + told);
        FxWait.fx(() -> { view.dispose(); return null; });
        svc.stop();
    }

    private static ButtonBase rail(Node root, String name) throws InterruptedException {
        return FxWait.fx(() -> (ButtonBase) root.lookupAll("*").stream()
                .filter(n -> n instanceof ButtonBase b && b.getAccessibleText() != null && b.getAccessibleText().startsWith(name))
                .filter(Node::isVisible).findFirst().orElseThrow(() -> new AssertionError("no rail button " + name)));
    }

    private static void click(ButtonBase b) throws InterruptedException {
        FxWait.fx(() -> { b.fire(); return null; });
        FxWait.settled();
    }
}
