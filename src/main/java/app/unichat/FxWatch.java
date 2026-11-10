package app.unichat;

import app.unichat.support.Threads;
import app.unichat.support.WakeWatch;
import javafx.application.Platform;

import java.util.function.Consumer;

/**
 * Whether the UI thread still answers. It froze for good once (the render thread spinning after an OutOfMemoryError,
 * the UI thread waiting for a frame that never came) and nothing noticed. A question goes to the UI thread every few
 * seconds; one left unanswered for {@link #STALL_NANOS} is a frozen window, told once. Time asleep doesn't count.
 */
final class FxWatch {
    static final long STALL_NANOS = 60_000_000_000L;
    private static final long EVERY_MS = 5_000;

    private final Consumer<Runnable> ask;
    private final Runnable frozen;
    /** When the question waiting for an answer was asked (awake time), or -1 when none is waiting. */
    private volatile long askedAt = -1;
    private boolean told;

    FxWatch(Consumer<Runnable> ask, Runnable frozen) {
        this.ask = ask;
        this.frozen = frozen;
    }

    /** One look at {@code now} (awake time, ns): asks again if the last was answered, or tells once it waited too long. */
    synchronized void check(long now) {
        long asked = askedAt;
        if (asked < 0) {
            askedAt = now;
            ask.accept(() -> askedAt = -1);
        } else if (now - asked >= STALL_NANOS && !told) {
            told = true;
            frozen.run();
        }
    }

    /** The computer slept: the wait so far wasn't the window's. */
    void awoke() {
        askedAt = -1;
    }

    /** Watches the UI thread from now on, telling {@code frozen} (on the watch's own thread) if it stops answering. */
    static void start(Runnable frozen) {
        FxWatch watch = new FxWatch(Platform::runLater, frozen);
        WakeWatch.onWake(watch::awoke);
        Threads.daemon("fx-watch").newThread(() -> {
            while (true) {
                watch.check(System.nanoTime());
                try {
                    Thread.sleep(EVERY_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }).start();
    }
}
