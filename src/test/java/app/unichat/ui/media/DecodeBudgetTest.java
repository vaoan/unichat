package app.unichat.ui.media;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Memory for what's being decoded at once: opening the sticker panel decoded many big animations together and the
 * peak restarted Unichat (memory full), though what stayed was 206 MB. Past the budget a decode waits its turn; one
 * bigger than it all goes alone.
 */
class DecodeBudgetTest {
    @Test
    void whatDoesntFitWaitsForTheOneBeforeToEnd() throws Exception {
        DecodeBudget budget = new DecodeBudget(100);
        DecodeBudget.Lease first = budget.take(80);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<DecodeBudget.Lease> second = CompletableFuture.supplyAsync(() -> {
            started.countDown();
            try {
                return budget.take(80);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        started.await();
        budget.awaitWaiting(1);
        assertFalse(second.isDone(), "80 + 80 is past 100: it waits");
        first.close();
        second.get().close();
        assertEquals(0, budget.used());
    }

    @Test
    void oneBiggerThanItAllGoesAlone() throws Exception {
        DecodeBudget budget = new DecodeBudget(100);
        try (DecodeBudget.Lease big = budget.take(500)) {
            assertTrue(budget.used() >= 100, "it holds the whole budget while it decodes");
        }
        try (DecodeBudget.Lease a = budget.take(40); DecodeBudget.Lease b = budget.take(40)) {
            assertEquals(80, budget.used(), "small ones go together");
        }
    }
}
