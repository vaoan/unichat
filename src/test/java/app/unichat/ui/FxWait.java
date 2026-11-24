package app.unichat.ui;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Waits for what happens on screen, never for a length of time. The screen only changes on the FX thread, so a
 * condition is checked now and again after every pulse (each frame JavaFX lays out and draws). There's no time limit
 * here: JUnit's default timeout (junit-platform.properties) fails a wait that never comes, and says what it waited for.
 */
public final class FxWait {
    private FxWait() {}

    /** Waiting on the FX thread, which would wait for itself: a condition of {@link #until} may only look. */
    public static final class OnFxThread extends IllegalStateException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        OnFxThread(String what) {
            super("waiting for \"" + what + "\" on the FX thread (inside an until's condition?) would never end: look, don't wait");
        }
    }

    /** Work for the FX thread that may throw {@code E}: a lambda that throws nothing checked makes it no exception at all. */
    @FunctionalInterface
    public interface FxCall<T extends @Nullable Object, E extends Exception> {
        T call() throws E;
    }

    /** Runs {@code c} on the FX thread and returns its result (or rethrows what it threw). */
    @SuppressFBWarnings(value = {"BC_UNCONFIRMED_CAST_OF_RETURN_VALUE", "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION"}, justification = "the task's checked exception can only be the E its FxCall declares; the task's own unchecked exception, thrown again as it was")
    public static <T extends @Nullable Object, E extends Exception> T fx(FxCall<T, E> c) throws E, InterruptedException {
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
            // Out of the future's wrapper: what c.call() threw, E or unchecked; an Error fails the test as before.
            if (e.getCause() instanceof RuntimeException r) throw r; // NOPMD: PreserveStackTrace, the future's wrapper is dropped; its cause has the trace
            if (!(e.getCause() instanceof Exception)) throw new IllegalStateException(e);
            @SuppressWarnings("unchecked") // c.call() throws only E or unchecked: no other Exception can be its cause
            E thrown = (E) e.getCause();
            throw thrown; // NOPMD: PreserveStackTrace, the cause itself, out of the future's wrapper
        }
    }

    /** Work for the FX thread that gives nothing back and may throw {@code E}. */
    @FunctionalInterface
    public interface FxRun<E extends Exception> {
        void run() throws E;
    }

    /** Runs {@code r} on the FX thread (or rethrows what it threw), as {@link #fx} does for work with a result. */
    public static <E extends Exception> void onFx(FxRun<E> r) throws E, InterruptedException {
        fx(() -> {
            r.run();
            return null;
        });
    }

    /**
     * Until {@code condition}, run on the FX thread, is true: checked now and after every pulse. A condition that throws
     * (a node not there yet) counts as not yet.
     */
    public static void until(String what, Callable<Boolean> condition) {
        // A condition runs on the FX thread: waiting there (settled() or until() inside it) waits for itself, for good;
        // a whole test class hung to JUnit's limit, three minutes a test, saying nothing. It fails at once instead.
        if (Platform.isFxApplicationThread()) throw new OnFxThread(what);
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
            } catch (OnFxThread wrong) {
                if (timer[0] != null) timer[0].stop();
                met.completeExceptionally(wrong); // never "not yet": it would never be
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
        } catch (ExecutionException e) {
            if (e.getCause() instanceof OnFxThread wrong) throw wrong; // NOPMD: PreserveStackTrace, as above
            throw new IllegalStateException(e); // never: only OnFxThread completes it exceptionally
        } catch (InterruptedException timedOut) {
            Platform.runLater(() -> { if (timer[0] != null) timer[0].stop(); });
            Thread.currentThread().interrupt();
            AssertionError e = new AssertionError("never happened: " + what);
            if (last.get() != null) e.initCause(last.get());
            throw e; // NOPMD: PreserveStackTrace, the wait's own end; its last failure is the cause
        }
    }

    /**
     * Until the window has done all it was asked: what's queued on the FX thread so far has run, no redraw is waiting
     * ({@link Redraws}), and a pulse has laid it out. The point to check that something did <em>not</em> happen.
     */
    public static void settled() throws InterruptedException {
        fx(() -> null);
        do {
            until("the window's redraws to run", () -> Redraws.PENDING.get() == 0);
            pulses(2); // the first may have started before the last change; the second lays it out
        } while (fx(() -> Redraws.PENDING.get() != 0)); // a layout in those pulses asked for another (a rewrap)
    }

    /** Until {@code n} pulses have run from now. */
    static void pulses(int n) {
        int[] seen = {0};
        until(n + " pulses", () -> ++seen[0] > n);
    }
}
