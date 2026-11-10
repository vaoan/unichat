package app.unichat.server;

import app.unichat.bridge.BridgeStates;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Brings the local server back by itself: every 30 s it asks the server and each bridge whether they answer, and
 * restarts one that doesn't (a bridge hung in write() sat a day alive but deaf), one whose accounts stay
 * disconnected too long, or one that keeps dropping its connection (mautrix-discord piles up guild subscriptions
 * on every reconnect until Discord rate-limits it, which makes it reconnect again). Accounts that need a new login
 * aren't restarted: nothing but the person can fix those. Times are awake time ({@link BridgeStates#now}).
 *
 * <p>Without internet a disconnected bridge is doing nothing wrong: restarting it only made things worse (mautrix-discord
 * started offline fails once and never tries again, and every restart lengthened the wait for the next one). So those
 * restarts wait, and when the internet returns every bridge starts again at once, with a fresh connection and its catch-up.
 */
final class Watchdog {
    static final long PROBE_EVERY_MS = 30_000;
    /** Unanswered probes in a row before a restart (90 s: a busy bridge after waking isn't hung). */
    static final int PROBE_FAILURES = 3;
    /** An account disconnected this long gets its bridge restarted. */
    static final long STUCK_MS = 10 * 60_000;
    /** This many disconnections within {@link #FLAP_WINDOW_MS} also mean a restart. */
    static final int FLAPS = 5;
    static final long FLAP_WINDOW_MS = 10 * 60_000;
    /** Wait after a restart before another one; doubles for each restart in a row, up to an hour. */
    static final long COOLDOWN_MS = 5 * 60_000;
    static final long MAX_COOLDOWN_MS = 60 * 60_000;

    /** Sessions a restart can cost (MetaSessions), and bringing back the ones already lost. */
    interface SessionKeeper {
        /** A bridge that's only restarted for its own troubles, not just because the internet returned. */
        boolean fragile(String bridge);

        boolean safeToRestart(String bridge);

        void tick(boolean online, long now);
    }

    private static final SessionKeeper NO_KEEPER = new SessionKeeper() {
        @Override public boolean fragile(String bridge) { return false; }
        @Override public boolean safeToRestart(String bridge) { return true; }
        @Override public void tick(boolean online, long now) { }
    };

    /** What's known about one process. {@code troubledSince}: -1 when every account is fine. */
    record Health(int probeFailures, long troubledSince, int drops) {}

    /** Why to restart, or null to leave it alone. */
    static String reason(Health h, long now) {
        if (h.probeFailures() >= PROBE_FAILURES) return "not responding";
        if (h.drops() >= FLAPS) return h.drops() + " disconnections in 10 min";
        if (h.troubledSince() >= 0 && now - h.troubledSince() >= STUCK_MS) return "disconnected for over 10 min";
        return null;
    }

    /** {@link #reason(Health, long)} knowing whether there's internet: without it, only a hung process counts. */
    static String reason(Health h, long now, boolean online) {
        return online || h.probeFailures() >= PROBE_FAILURES ? reason(h, now) : null;
    }

    /** Wait after the {@code restartsInARow}-th restart in a row. */
    static long cooldown(int restartsInARow) {
        return Math.min(MAX_COOLDOWN_MS, COOLDOWN_MS << Math.min(Math.max(0, restartsInARow - 1), 8));
    }

    private final Map<String, Integer> ports;
    private final Supplier<Iterable<String>> running;
    private final BridgeStates states;
    private final BiConsumer<String, String> restart;
    private final BooleanSupplier internet;
    private final SessionKeeper keeper;
    private boolean wasOnline = true;
    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, Long> lastRestart = new HashMap<>();
    private final Map<String, Integer> inARow = new HashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private ScheduledExecutorService timer;

    /**
     * @param ports   process name → port it answers HTTP on ("tuwunel" and every bridge)
     * @param running names of the processes that should be up now
     * @param restart (name, reason) kills and restarts a process
     * @param internet whether this computer reaches the internet now
     */
    Watchdog(Map<String, Integer> ports, Supplier<Iterable<String>> running, BridgeStates states,
             BiConsumer<String, String> restart, BooleanSupplier internet) {
        this(ports, running, states, restart, internet, NO_KEEPER);
    }

    Watchdog(Map<String, Integer> ports, Supplier<Iterable<String>> running, BridgeStates states,
             BiConsumer<String, String> restart, BooleanSupplier internet, SessionKeeper keeper) {
        this.ports = ports;
        this.running = running;
        this.states = states;
        this.restart = restart;
        this.internet = internet;
        this.keeper = keeper;
    }

    synchronized void start() {
        if (timer != null) return;
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watchdog");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::check, PROBE_EVERY_MS, PROBE_EVERY_MS, TimeUnit.MILLISECONDS);
    }

    synchronized void stop() {
        if (timer != null) timer.shutdownNow();
        timer = null;
    }

    void check() {
        try {
            long now = BridgeStates.now();
            boolean online = internet.getAsBoolean();
            boolean internetBack = online && !wasOnline;
            wasOnline = online;
            for (String name : running.get()) {
                Integer port = ports.get(name);
                if (port == null) continue;
                int f = HttpProbe.answers(http, port, Duration.ofSeconds(10), HttpProbe.Up.ALIVE) ? 0 : failures.getOrDefault(name, 0) + 1;
                failures.put(name, f);
                Health h = name.equals("tuwunel") ? new Health(f, -1, 0)
                        : new Health(f, states.troubledSince(name), states.dropsWithin(name, FLAP_WINDOW_MS, now));
                boolean bridge = !name.equals("tuwunel");
                boolean fresh = internetBack && bridge && !keeper.fragile(name);
                String why = fresh ? "internet is back" : reason(h, now, online);
                Long last = lastRestart.get(name);
                int row = inARow.getOrDefault(name, 0);
                // A long quiet run since the last restart: the next one starts with a short wait again.
                if (last != null && why == null && now - last > MAX_COOLDOWN_MS) inARow.put(name, row = 0);
                if (fresh) inARow.put(name, row = 0);
                else if (why == null || (last != null && now - last < cooldown(row))) continue;
                // A hung process can't lose more than it has; anything else waits until a restart can't cost its session.
                if (h.probeFailures() < PROBE_FAILURES && !keeper.safeToRestart(name)) continue;
                System.err.println("Watchdog: restarting " + name + " (" + why + ")");
                lastRestart.put(name, now);
                inARow.put(name, row + 1);
                failures.put(name, 0);
                if (bridge) states.restarting(name, now);
                restart.accept(name, why);
            }
            keeper.tick(online, now);
            states.tick();
        } catch (InterruptedException e) {
            // Stopping: an unanswered probe now says nothing about the process, so nothing is restarted.
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // The watchdog itself must never stop.
            System.err.println("Watchdog check failed: " + t);
        }
    }
}
