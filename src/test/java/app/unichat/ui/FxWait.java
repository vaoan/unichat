package app.unichat.ui;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Waits for what happens on screen, never for a length of time. The screen only changes on the FX thread, so a
 * condition is checked now and again after every pulse (each frame JavaFX lays out and draws). There's no time limit
 * here: JUnit's default timeout (junit-platform.properties) fails a wait that never comes, and says what it waited for.
 */
final class FxWait {
    private FxWait() {}

    /** Runs {@code c} on the FX thread and returns its result (or rethrows what it threw). */
    static <T> T fx(Callable<T> c) throws Exception {
        if (Platform.isFxApplicationThread()) return c.call();
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(c.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            return f.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception x) throw x;
            throw e;
        }
    }

    /**
     * Until {@code condition}, run on the FX thread, is true: checked now and after every pulse. A condition that throws
     * (a node not there yet) counts as not yet.
     */
    static void until(String what, Callable<Boolean> condition) throws Exception {
        CompletableFuture<Void> met = new CompletableFuture<>();
        AtomicReference<Throwable> last = new AtomicReference<>();
        AnimationTimer[] timer = new AnimationTimer[1];
        Runnable check = () -> {
            if (met.isDone()) return;
            try {
                if (Boolean.TRUE.equals(condition.call())) {
                    if (timer[0] != null) timer[0].stop();
                    met.complete(null);
                }
            } catch (Throwable t) {
                last.set(t);
            }
        };
        Platform.runLater(() -> {
            check.run();
            if (met.isDone()) return;
            timer[0] = new AnimationTimer() {
                @Override public void handle(long now) { check.run(); }
            };
            timer[0].start();
        });
        try {
            met.get();
        } catch (InterruptedException timedOut) {
            Platform.runLater(() -> { if (timer[0] != null) timer[0].stop(); });
            Thread.currentThread().interrupt();
            AssertionError e = new AssertionError("never happened: " + what);
            if (last.get() != null) e.initCause(last.get());
            throw e;
        }
    }

    /**
     * Until the window has done all it was asked: what's queued on the FX thread so far has run, no {@link Throttle} has a
     * redraw waiting, and a pulse has laid it out. The point to check that something did <em>not</em> happen.
     */
    static void settled() throws Exception {
        fx(() -> null);
        until("the window's redraws to run", () -> Throttle.PENDING.get() == 0);
        pulses(2); // the first may have started before the last change; the second lays it out
    }

    /** Until {@code n} pulses have run from now. */
    static void pulses(int n) throws Exception {
        int[] seen = {0};
        until(n + " pulses", () -> ++seen[0] > n);
    }
}
