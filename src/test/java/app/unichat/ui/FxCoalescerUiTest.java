package app.unichat.ui;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Many requests from any thread run the job once on the FX thread, and the window counts it as a redraw to come. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class FxCoalescerUiTest {
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
    void manyRequestsRunOnceAndCountAsARedrawToCome() throws Exception {
        FxWait.settled();
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger onFx = new AtomicInteger();
        FxCoalescer redraw = new FxCoalescer(() -> {
            runs.incrementAndGet();
            if (Platform.isFxApplicationThread()) onFx.incrementAndGet();
        });
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holding = new CompletableFuture<>();
        Platform.runLater(() -> { // the FX thread busy, so the requests pile up behind it
            holding.complete(null);
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holding.get();
        try { // the FX thread is let go whatever fails here: held, it would hang every test after this one
            int before = Redraws.PENDING.get();
            CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 5; i++) redraw.request();
            }).get();
            redraw.request();
            assertTrue(redraw.queued(), "one redraw to come");
            // Others may add to the shared count meanwhile, but nothing leaves it while the FX thread is held.
            assertTrue(Redraws.PENDING.get() >= before + 1, "counted, so FxWait.settled waits for it");
            assertEquals(0, runs.get(), "not yet: the FX thread is busy");
        } finally {
            release.countDown();
        }
        FxWait.settled();
        assertEquals(1, runs.get(), "once for all the requests");
        assertEquals(1, onFx.get(), "on the FX thread");
        assertFalse(redraw.queued(), "nothing left to come");
        redraw.request();
        FxWait.settled();
        assertEquals(2, runs.get(), "a request after it ran runs it again");
    }
}
