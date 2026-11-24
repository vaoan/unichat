package app.unichat.ui.desktop;

import app.unichat.ui.FxWait;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.EventQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tray is built on AWT's own thread, never the FX one (on macOS both share the main thread, and AWT there hung the
 * app), and says when it exists. Headless only: there is no tray here, what's seen is the thread it's built on.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class NotifierUiTest {
    @BeforeAll
    static void toolkit() throws Exception {
        CompletableFuture<Void> up = new CompletableFuture<>();
        try {
            Platform.startup(() -> up.complete(null));
        } catch (IllegalStateException running) {
            up.complete(null);
        }
        up.get();
        Platform.setImplicitExit(false);
    }

    @Test
    void theTrayIsBuiltOnAwtsThreadAndSaysWhenItExists() throws Exception {
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

    /** A click on the tray before any notice handed the window a null chat, and opening it threw. */
    @Test
    void theTrayClickedBeforeAnyNoticeBringsTheWindowAlone() throws InterruptedException {
        AtomicInteger shown = new AtomicInteger();
        List<String> opened = new CopyOnWriteArrayList<>();
        Notifier n = FxWait.fx(() -> new Notifier(shown::incrementAndGet, () -> { }, opened::add, () -> { }));
        try {
            n.openLast();
            assertEquals(1, shown.get(), "the window comes");
            assertEquals(List.of(), opened, "no chat to open");
        } finally {
            n.dispose();
        }
    }
}
