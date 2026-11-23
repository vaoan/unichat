package app.unichat.ui.desktop;

import app.unichat.support.ScreenAway;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Windows: a locked session or a display that is off counts as nobody seeing Unichat. A message-only window on a
 * daemon thread of its own hears both ({@link WindowsScreenWindow}), with nothing polling; if it can't be made or
 * registered, one log line says why, the screen counts as here once, and Unichat goes on with its windows alone.
 */
public final class WindowsScreen implements ScreenAway {
    /** One message to the window: its code, wParam, and the display state when it carries one. */
    interface Handler {
        void message(int msg, long wParam, int displayState);
    }

    /** The native window; {@link #open} and {@link #run} are called on the watch's one thread. */
    interface MessageWindow {
        /** Makes the window and registers it; throws with why when it can't. */
        void open(Handler onMessage) throws Exception;

        /** Hands every message to the handler until {@link #close}. */
        void run();

        /** From any thread, once open: ends {@link #run}. */
        void close();
    }

    private final MessageWindow window;
    private final Consumer<String> log;
    private final CompletableFuture<Boolean> registered = new CompletableFuture<>();
    private volatile Thread thread;

    public WindowsScreen() {
        this(new WindowsScreenWindow(), System.err::println);
    }

    WindowsScreen(MessageWindow window, Consumer<String> log) {
        this.window = window;
        this.log = log;
    }

    @Override
    public void start(Consumer<Boolean> away) {
        Thread t = new Thread(() -> watch(away), "windows-screen");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private void watch(Consumer<Boolean> away) {
        ScreenMessages messages = new ScreenMessages();
        try {
            window.open((msg, wParam, displayState) -> {
                Boolean now = messages.away(msg, wParam, displayState);
                if (now != null) tell(away, now);
            });
        } catch (Throwable e) {
            log.accept("Screen lock and display watch didn't start, so only Unichat's windows count: " + e);
            registered.complete(false);
            tell(away, false);
            return;
        }
        registered.complete(true);
        window.run();
    }

    /** A listener's bug mustn't end the watch, nor reach the window procedure (JNA would only print it). */
    private void tell(Consumer<Boolean> away, boolean now) {
        try {
            away.accept(now);
        } catch (RuntimeException e) {
            log.accept("Screen lock and display watch: the listener failed: " + e);
        }
    }

    /** Completes true once the window is made and registered, false if that failed. */
    CompletableFuture<Boolean> registered() {
        return registered;
    }

    Thread thread() {
        return thread;
    }

    /** Ends the watch (for tests; the app's lasts until it quits). Call after {@link #registered} completes. */
    void stop() {
        window.close();
    }
}
