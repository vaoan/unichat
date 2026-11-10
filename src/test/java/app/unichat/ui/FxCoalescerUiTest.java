package app.unichat.ui;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        int before = Redraws.PENDING.get();
        CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 5; i++) redraw.request();
        }).get();
        redraw.request();
        assertEquals(before + 1, Redraws.PENDING.get(), "one redraw to come, so FxWait.settled waits for it");
        release.countDown();
        FxWait.settled();
        assertEquals(1, runs.get(), "once for all the requests");
        assertEquals(1, onFx.get(), "on the FX thread");
        assertEquals(before, Redraws.PENDING.get(), "nothing left to come");
        redraw.request();
        FxWait.settled();
        assertEquals(2, runs.get(), "a request after it ran runs it again");
    }
}
