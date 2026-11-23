package app.unichat;

import app.unichat.support.Seen;
import app.unichat.support.Threads;
import app.unichat.support.WakeWatch;
import javafx.application.Platform;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Whether the UI thread still answers. It froze for good once (the render thread spinning after an OutOfMemoryError,
 * the UI thread waiting for a frame that never came) and nothing noticed. A question goes to the UI thread every few
 * seconds; one left unanswered for {@link #STALL_NANOS} is a frozen window, told once. Time asleep doesn't count.
 * While nobody can see Unichat ({@link Seen}) it asks once a minute: a hang then is still caught (notifications must
 * not die with the UI thread), only later, and the UI thread isn't woken every few seconds for nothing.
 */
final class FxWatch {
    static final long STALL_NANOS = 60_000_000_000L;
    static final long EVERY_MS = 5_000;
    static final long UNSEEN_EVERY_MS = 60_000;

    /** How the watch waits between looks ({@link Thread#sleep} but in tests). */
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

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

    /** The wait so far wasn't the window's: the computer slept, or nobody could see Unichat. Never blocks. */
    void forget() {
        askedAt = -1;
    }

    /**
     * Watches from the calling thread until it's interrupted: every {@link #EVERY_MS} while someone can see Unichat,
     * every {@link #UNSEEN_EVERY_MS} while not, and the last question forgotten when it's seen again (as on waking).
     */
    void run(LongSupplier clock, Sleeper sleeper) {
        Consumer<Boolean> seenAgain = seen -> {
            if (seen) forget();
        };
        Seen.get().addListener(seenAgain);
        try {
            while (true) {
                check(clock.getAsLong());
                sleeper.sleep(Seen.get().now() ? EVERY_MS : UNSEEN_EVERY_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            Seen.get().removeListener(seenAgain);
        }
    }

    /** Watches the UI thread from now on, telling {@code frozen} (on the watch's own thread) if it stops answering. */
    static void start(Runnable frozen) {
        FxWatch watch = new FxWatch(Platform::runLater, frozen);
        WakeWatch.onWake(watch::forget);
        Threads.daemon("fx-watch").newThread(() -> watch.run(System::nanoTime, Thread::sleep)).start();
    }
}
