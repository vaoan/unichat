package app.unichat.server;

import app.unichat.bridge.BridgeStates;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Brings the local server back by itself: every 30 s it asks the server and each bridge whether they answer, and
 * restarts one that doesn't (a bridge hung in write() sat a day alive but deaf), one whose accounts stay
 * disconnected too long, or one that keeps dropping its connection (mautrix-discord piles up guild subscriptions
 * on every reconnect until Discord rate-limits it, which makes it reconnect again). Accounts that need a new login
 * aren't restarted: nothing but the person can fix those. Times are awake time ({@link BridgeStates#now}).
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

    /** What's known about one process. {@code troubledSince}: -1 when every account is fine. */
    record Health(int probeFailures, long troubledSince, int drops) {}

    /** Why to restart, or null to leave it alone. */
    static String reason(Health h, long now) {
        if (h.probeFailures() >= PROBE_FAILURES) return "no responde";
        if (h.drops() >= FLAPS) return h.drops() + " desconexiones en 10 min";
        if (h.troubledSince() >= 0 && now - h.troubledSince() >= STUCK_MS) return "desconectado más de 10 min";
        return null;
    }

    /** Wait after the {@code restartsInARow}-th restart in a row. */
    static long cooldown(int restartsInARow) {
        return Math.min(MAX_COOLDOWN_MS, COOLDOWN_MS << Math.min(Math.max(0, restartsInARow - 1), 8));
    }

    private final Map<String, Integer> ports;
    private final Supplier<Iterable<String>> running;
    private final BridgeStates states;
    private final BiConsumer<String, String> restart;
    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, Long> lastRestart = new HashMap<>();
    private final Map<String, Integer> inARow = new HashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private ScheduledExecutorService timer;

    /**
     * @param ports   process name → port it answers HTTP on ("tuwunel" and every bridge)
     * @param running names of the processes that should be up now
     * @param restart (name, reason) kills and restarts a process
     */
    Watchdog(Map<String, Integer> ports, Supplier<Iterable<String>> running, BridgeStates states,
             BiConsumer<String, String> restart) {
        this.ports = ports;
        this.running = running;
        this.states = states;
        this.restart = restart;
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

    private void check() {
        try {
            long now = BridgeStates.now();
            for (String name : running.get()) {
                Integer port = ports.get(name);
                if (port == null) continue;
                int f = answers(port) ? 0 : failures.getOrDefault(name, 0) + 1;
                failures.put(name, f);
                Health h = name.equals("tuwunel") ? new Health(f, -1, 0)
                        : new Health(f, states.troubledSince(name), states.dropsWithin(name, FLAP_WINDOW_MS, now));
                String why = reason(h, now);
                Long last = lastRestart.get(name);
                int row = inARow.getOrDefault(name, 0);
                // A long quiet run since the last restart: the next one starts with a short wait again.
                if (last != null && why == null && now - last > MAX_COOLDOWN_MS) inARow.put(name, row = 0);
                if (why == null || (last != null && now - last < cooldown(row))) continue;
                System.err.println("Watchdog: restarting " + name + " (" + why + ")");
                lastRestart.put(name, now);
                inARow.put(name, row + 1);
                failures.put(name, 0);
                if (!name.equals("tuwunel")) states.restarting(name, now);
                restart.accept(name, why);
            }
            states.tick();
        } catch (Throwable t) {
            // The watchdog itself must never stop.
            System.err.println("Watchdog check failed: " + t);
        }
    }

    /** Any HTTP answer counts: a hung process doesn't give one within 10 s. */
    private boolean answers(int port) {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/_matrix/client/versions"))
                    .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.discarding());
            return r.statusCode() > 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
