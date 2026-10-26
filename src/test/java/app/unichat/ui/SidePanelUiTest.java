package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Telegram-like side panel: Perfil and Emojis in one panel that switches without closing. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class SidePanelUiTest {
    @Test
    void oneSidePanelForProfileAndEmojis() throws Exception {
        System.setProperty("java.awt.headless", "true");
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        MainView[] view = new MainView[1];
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setX(-10000);
            view[0] = new MainView(new MockChatService(), stage, new Notifier(() -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view[0], 1200, 800);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            view[0].openChatId("!familia:demo");
            return sc;
        });
        Thread.sleep(500);
        assertFalse(fx(() -> panel(scene) != null), "closed at first");

        fx(() -> { view[0].togglePanel(SidePanel.Tab.PROFILE); return null; });
        assertEquals(SidePanel.Tab.PROFILE, fx(() -> panel(scene).current()));
        assertTrue(fx(() -> panel(scene).lookup(".info-pane") != null), "Perfil shows the chat info");

        fx(() -> { view[0].showPanel(SidePanel.Tab.EMOJIS); return null; });
        assertEquals(SidePanel.Tab.EMOJIS, fx(() -> panel(scene).current()), "switches tab without closing");
        Thread.sleep(300); // one pulse: the scroll pane builds its content

        fx(() -> {
            Button smile = (Button) panel(scene).lookupAll(".emoji-button").iterator().next();
            smile.fire();
            return null;
        });
        String typed = fx(() -> ((TextArea) scene.getRoot().lookup(".composer-input")).getText());
        assertFalse(typed.isEmpty(), "the emoji went into the message box");

        fx(() -> { view[0].togglePanel(SidePanel.Tab.EMOJIS); return null; });
        assertFalse(fx(() -> panel(scene) != null), "same button closes it");
    }

    private static SidePanel panel(Scene s) {
        for (Node n : s.getRoot().lookupAll("*")) if (n instanceof SidePanel p && p.getScene() != null) return p;
        return null;
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
