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
 * The window tells the service when Archivados opens and closes (what rests wakes meanwhile): from the rail, from
 * anything else that shows the archive, and when the window goes away with it open.
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
        MockChatService svc = new MockChatService() {
            @Override public void setArchiveOpen(boolean open) { told.add(open); }
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
        FxWait.fx(() -> { stage.hide(); return null; });
        assertEquals(false, told.getLast(), "nobody can see it with the window gone");
        FxWait.fx(() -> { view.dispose(); return null; });
        svc.stop();
    }

    private static ButtonBase rail(Node root, String name) throws Exception {
        return FxWait.fx(() -> (ButtonBase) root.lookupAll("*").stream()
                .filter(n -> n instanceof ButtonBase b && b.getAccessibleText() != null && b.getAccessibleText().startsWith(name))
                .filter(Node::isVisible).findFirst().orElseThrow(() -> new AssertionError("no rail button " + name)));
    }

    private static void click(ButtonBase b) throws Exception {
        FxWait.fx(() -> { b.fire(); return null; });
        FxWait.settled();
    }
}
