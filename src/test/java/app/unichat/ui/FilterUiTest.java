package app.unichat.ui;

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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for "Favoritos / Prioridad baja are lost when switching network": clicks the real chips and rail
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
            started.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setX(-10000);
            MainView view = new MainView(new MockChatService(), stage, new Notifier(() -> { }, id -> { }, () -> { }),
                    () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 700);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            return sc;
        });
        Thread.sleep(500);
        ChatListPane list = fx(() -> (ChatListPane) scene.getRoot().lookupAll("*").stream()
                .filter(n -> n instanceof ChatListPane).findFirst().orElseThrow());

        click(scene, chip(scene, "Favoritos"));
        click(scene, rail(scene, "WhatsApp"));
        ChatFilter f = fx(() -> list.filterProperty().get());
        assertEquals(Network.WHATSAPP, f.network());
        assertEquals(ChatFilter.View.FAVORITES, f.view(), "Favoritos survives the network switch");
        assertTrue(fx(() -> chip(scene, "Favoritos").isSelected()));

        click(scene, chip(scene, "Prioridad baja"));
        click(scene, rail(scene, "Bandeja unificada"));
        f = fx(() -> list.filterProperty().get());
        assertEquals(null, f.network());
        assertEquals(ChatFilter.View.LOW_PRIORITY, f.view(), "Prioridad baja survives going back to the inbox");

        click(scene, rail(scene, "Archivados"));
        click(scene, rail(scene, "WhatsApp"));
        f = fx(() -> list.filterProperty().get());
        assertEquals(ChatFilter.View.INBOX, f.view(), "leaving the archive goes back to the inbox");
        assertTrue(fx(() -> chip(scene, "Todos").isSelected()), "chip follows the filter");
    }

    private static ToggleButton chip(Scene scene, String text) {
        return (ToggleButton) scene.getRoot().lookupAll(".chip").stream()
                .filter(n -> n instanceof ToggleButton t && t.getText().equals(text)).findFirst().orElseThrow();
    }

    /** Rail button whose screen-reader text starts with {@code name} ("WhatsApp (2 cuentas)", "Archivados"…). */
    private static ButtonBase rail(Scene scene, String name) {
        return (ButtonBase) scene.getRoot().lookupAll("*").stream()
                .filter(n -> n instanceof ButtonBase b && b.getAccessibleText() != null && b.getAccessibleText().startsWith(name))
                .filter(Node::isVisible).findFirst().orElseThrow(() -> new AssertionError("no rail button " + name));
    }

    private static void click(Scene scene, ButtonBase b) throws Exception {
        fx(() -> {
            b.fire();
            return null;
        });
        Thread.sleep(100);
    }

    private static <T> T fx(Supplier<T> s) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(s.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(10, TimeUnit.SECONDS);
    }
}
