package app.unichat.ui;

import javafx.application.Platform;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a job once on the FX thread however many times it's asked for before it runs (progress from another thread, a
 * width changing): one redraw for the lot. Counted in {@link Redraws#PENDING} until it has run, so tests can wait for it.
 * {@link #request} takes any thread.
 */
public final class FxCoalescer {
    private final Runnable job;
    private final AtomicBoolean queued = new AtomicBoolean();

    public FxCoalescer(Runnable job) {
        this.job = job;
    }

    public void request() {
        if (!queued.compareAndSet(false, true)) return;
        Redraws.PENDING.incrementAndGet();
        Platform.runLater(() -> {
            queued.set(false);
            try {
                job.run();
            } finally {
                Redraws.PENDING.decrementAndGet();
            }
        });
    }
}
