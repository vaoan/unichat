package app.unichat;

import java.util.List;

/**
 * Quitting: what has to stop (the cloud, the service, the local server) stops on a thread of its own, in order, and only
 * then the app ends. On the UI thread it froze the window up to 10 s per bridge, or for a whole bridge install.
 */
final class Shutdown {
    private Shutdown() { }

    /** Runs {@code stops} one after another on a new thread (one failing doesn't keep the rest from stopping), then {@code end}. */
    static void stopThenEnd(List<Runnable> stops, Runnable end) {
        new Thread(() -> {
            for (Runnable stop : stops) {
                try {
                    stop.run();
                } catch (RuntimeException e) {
                    System.err.println("Quitting: " + e);
                }
            }
            end.run();
        }, "quit").start();
    }
}
