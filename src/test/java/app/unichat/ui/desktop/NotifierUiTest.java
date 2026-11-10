package app.unichat.ui.desktop;

import app.unichat.ui.FxWait;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.EventQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tray is built on AWT's own thread, never the FX one (on macOS both share the main thread, and AWT there hung the
 * app), and says when it exists. Headless only: there is no tray here, what's seen is the thread it's built on.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class NotifierUiTest {
    @Test
    void theTrayIsBuiltOnAwtsThreadAndSaysWhenItExists() throws Exception {
        CompletableFuture<Void> up = new CompletableFuture<>();
        try {
            Platform.startup(() -> up.complete(null));
        } catch (IllegalStateException running) {
            up.complete(null);
        }
        up.get();
        Platform.setImplicitExit(false);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holding = new CompletableFuture<>();
        EventQueue.invokeLater(() -> { // AWT's thread busy: the tray waits for it
            holding.complete(null);
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holding.get();
        Notifier n = FxWait.fx(() -> new Notifier(() -> { }, () -> { }, id -> { }, () -> { }));
        CompletableFuture<Boolean> ready = n.ready();
        assertFalse(ready.isDone(), "built on AWT's thread, not the FX one that made the Notifier");
        CompletableFuture<Boolean> onAwt = ready.thenApply(tray -> EventQueue.isDispatchThread());
        release.countDown();
        assertFalse(ready.get(), "no tray without a display");
        assertTrue(onAwt.get(), "it says so from AWT's thread: whoever listens hops to theirs");
        n.dispose();
    }
}
