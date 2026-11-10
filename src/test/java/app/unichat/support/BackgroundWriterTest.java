package app.unichat.support;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BackgroundWriterTest {
    /** Many requests during a round make one more round, not one each; flush waits for it. */
    @Test
    void requestsDuringARoundBecomeOneMore() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger rounds = new AtomicInteger();
        BackgroundWriter writer = new BackgroundWriter("test-writer", new Object(), () -> {
            if (rounds.incrementAndGet() == 1) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        writer.request();
        started.await();
        for (int i = 0; i < 5; i++) writer.request();
        release.countDown();
        writer.flush();
        assertEquals(2, rounds.get());
    }

    @Test
    void theRoundRunsOnItsOwnThreadAndAFailureDoesntStopTheNext() {
        AtomicInteger rounds = new AtomicInteger();
        String[] thread = new String[1];
        BackgroundWriter writer = new BackgroundWriter("test-writer", new Object(), () -> {
            thread[0] = Thread.currentThread().getName();
            if (rounds.incrementAndGet() == 1) throw new IllegalStateException("disk full");
        });
        writer.request();
        writer.flush();
        writer.request();
        writer.flush();
        assertEquals(2, rounds.get());
        assertEquals("test-writer", thread[0]);
        assertNotEquals(Thread.currentThread().getName(), thread[0]);
    }

    /** A round needs the owner's lock to copy what it writes: flushing while holding it lets it go to wait. */
    @Test
    void flushHoldingTheOwnersLockLetsTheRoundTakeIt() {
        Object owner = new Object();
        AtomicInteger rounds = new AtomicInteger();
        BackgroundWriter writer = new BackgroundWriter("test-writer", owner, () -> {
            synchronized (owner) {
                rounds.incrementAndGet();
            }
        });
        synchronized (owner) {
            writer.request();
            writer.flush();
            assertEquals(1, rounds.get());
        }
    }

    @Test
    void flushWithNothingAskedReturnsAtOnce() {
        new BackgroundWriter("test-writer", new Object(), () -> { }).flush();
    }
}
