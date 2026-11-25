package app.unichat.ui.chats;

import app.unichat.ui.FxWait;
import app.unichat.ui.MainView;
import app.unichat.ui.Theme;
import app.unichat.ui.desktop.Notifier;
import app.unichat.model.Network;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for "Favoritos / Prioridad baja are lost when switching network": clicks the real filter bar and rail
 * buttons. Needs a display: {@code ./mvnw test -Dtest=FilterUiTest -Dunichat.uiTests=true}. The window is invisible
 * and off-screen.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class FilterUiTest {

    @Test
    void railKeepsTheChosenChip() throws Exception {
        System.setProperty("java.awt.headless", "true"); // no tray icon in tests
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            MainView view = new MainView(new MockChatService(), stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }),
                    () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 700);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            return sc;
        });
        FxWait.until("the chat list shows", () -> !scene.getRoot().lookupAll(".chat-list .list-cell").isEmpty());
        FxWait.settled();
        ChatListPane list = fx(() -> (ChatListPane) scene.getRoot().lookupAll("*").stream()
                .filter(n -> n instanceof ChatListPane).findFirst().orElseThrow());

        click(chip(scene, "Favoritos"));
        click(rail(scene, "WhatsApp"));
        ChatFilter f = fx(() -> list.filterProperty().get());
        assertEquals(Network.WHATSAPP, f.network());
        assertEquals(ChatFilter.View.FAVORITES, f.view(), "Favoritos survives the network switch");
        assertTrue(fx(() -> chip(scene, "Favoritos").isSelected()));

        click(chip(scene, "Prioridad baja"));
        click(rail(scene, "Bandeja unificada"));
        f = fx(() -> list.filterProperty().get());
        assertNull(f.network());
        assertEquals(ChatFilter.View.LOW_PRIORITY, f.view(), "Prioridad baja survives going back to the inbox");

        click(rail(scene, "Archivados"));
        click(rail(scene, "WhatsApp"));
        f = fx(() -> list.filterProperty().get());
        assertEquals(ChatFilter.View.INBOX, f.view(), "leaving the archive goes back to the inbox");
        assertTrue(fx(() -> chip(scene, "Todos").isSelected()), "chip follows the filter");
    }

    /** A part of the quick filter bar by its name: "Todos" is its text, the icons say theirs to screen readers. */
    private static ToggleButton chip(Scene scene, String name) {
        return (ToggleButton) scene.getRoot().lookupAll(".filter-part").stream()
                .filter(n -> n instanceof ToggleButton t && (name.equals(t.getText()) || name.equals(t.getAccessibleText())))
                .findFirst().orElseThrow(() -> new AssertionError("no filter " + name));
    }

    /** Rail button whose screen-reader text starts with {@code name} ("WhatsApp (2 cuentas)", "Archivados"…). */
    private static ButtonBase rail(Scene scene, String name) {
        return (ButtonBase) scene.getRoot().lookupAll("*").stream()
                .filter(n -> n instanceof ButtonBase b && b.getAccessibleText() != null && b.getAccessibleText().startsWith(name))
                .filter(Node::isVisible).findFirst().orElseThrow(() -> new AssertionError("no rail button " + name));
    }

    private static void click(ButtonBase b) throws InterruptedException {
        FxWait.onFx(() -> {
            b.fire();
        });
        FxWait.settled();
    }

    private static <T extends @Nullable Object, E extends Exception> T fx(FxWait.FxCall<T, E> c) throws E, InterruptedException {
        return FxWait.fx(c);
    }
}
