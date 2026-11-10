package app.unichat;

import app.unichat.settings.Settings;

import java.util.List;

/**
 * Quitting: what has to stop (the cloud, the service, the local server) stops on a thread of its own, in order, and only
 * then the app ends. On the UI thread it froze the window up to 10 s per bridge, or for a whole bridge install.
 */
final class Shutdown {
    private Shutdown() { }

    /**
     * Runs {@code stops} one after another on a new thread (one failing doesn't keep the rest from stopping), then waits
     * for the settings to be on disk (they're written on a daemon thread, and stopping may change them), then {@code end}.
     */
    static void stopThenEnd(List<Runnable> stops, Runnable end) {
        List<Runnable> all = new java.util.ArrayList<>(stops);
        all.add(Settings.get()::flush);
        new Thread(() -> {
            for (Runnable stop : all) {
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
