package app.unichat.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tells when the computer wakes up. A request left open before sleeping waits out its timeout in awake time once the
 * computer is back (macOS stops System.nanoTime while asleep), on a connection that died meanwhile: a minute or more of
 * stale chats. Whoever listens can drop it and ask again at once.
 *
 * <p>A ticker looks every {@link #TICK_MS}; it slept when the wall clock moved much more than awake time (macOS), or when
 * the ticker itself didn't run for a long while (where awake time keeps counting during sleep).
 */
public final class WakeWatch {
    static final long TICK_MS = 2_000;
    static final long GAP_MS = 15_000;

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static Thread ticker;

    private WakeWatch() {}

    /** Calls {@code r} (on the ticker's thread) each time the computer wakes. */
    public static synchronized void onWake(Runnable r) {
        LISTENERS.add(r);
        if (ticker == null) {
            ticker = Threads.daemon("wake-watch").newThread(WakeWatch::tick);
            ticker.start();
        }
    }

    public static void removeListener(Runnable r) { LISTENERS.remove(r); }

    /** Whether a tick that should have taken {@link #TICK_MS} means the computer slept in between. */
    static boolean slept(long wallMs, long awakeMs) {
        return wallMs - awakeMs > GAP_MS || awakeMs > GAP_MS;
    }

    private static void tick() {
        long wall = System.currentTimeMillis(), awake = System.nanoTime();
        while (true) {
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                return;
            }
            long w = System.currentTimeMillis(), a = System.nanoTime();
            if (slept(w - wall, (a - awake) / 1_000_000)) {
                for (Runnable r : LISTENERS) {
                    try {
                        r.run();
                    } catch (RuntimeException e) {
                        System.err.println("Wake listener failed: " + e);
                    }
                }
            }
            wall = w;
            awake = a;
        }
    }
}
