package app.unichat.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a redraw on the FX thread at most every {@code gapMs}, however many times it's asked for. Each key waits once: asking
 * again before it has run does nothing. {@link #schedule} takes any thread; the job calls {@link #ran} when it did the costly part.
 */
final class Throttle {
    /** Jobs asked for and not run yet, in every Throttle: tests wait for 0 rather than for a time (FxWait.settled). */
    static final AtomicInteger PENDING = new AtomicInteger();
    private final long gapMs;
    private final Set<Object> waiting = new HashSet<>();
    private long lastRun;

    Throttle(long gapMs) {
        this.gapMs = gapMs;
    }

    void schedule(Object key, Runnable job) {
        synchronized (waiting) {
            if (!waiting.add(key)) return;
        }
        PENDING.incrementAndGet();
        Platform.runLater(() -> {
            long wait = gapMs - (System.currentTimeMillis() - lastRun);
            if (wait <= 0) {
                run(key, job);
            } else {
                PauseTransition later = new PauseTransition(Duration.millis(wait));
                later.setOnFinished(e -> run(key, job));
                later.play();
            }
        });
    }

    /** The gap counts from here. */
    void ran() {
        lastRun = System.currentTimeMillis();
    }

    private void run(Object key, Runnable job) {
        synchronized (waiting) { waiting.remove(key); }
        try {
            job.run();
        } finally {
            PENDING.decrementAndGet();
        }
    }
}
