package app.unichat.bridge;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What each bridge says about its accounts, live: they POST every change to {@link StatusEndpoint} (mautrix
 * {@code status_endpoint}). Times are in "awake" milliseconds ({@link #now}): a Mac asleep for hours must not look
 * like an account stuck for hours when it wakes. Pure apart from the clock; tested.
 */
public final class BridgeStates {
    /**
     * One account (or the bridge itself, {@code remoteId} empty).
     *
     * @param event  mautrix state: CONNECTED, CONNECTING, TRANSIENT_DISCONNECT, BAD_CREDENTIALS, LOGGED_OUT…
     * @param since  when this state began (an update with the same state keeps it)
     */
    public record State(String bridge, String remoteId, String remoteName, String event, String error, String message,
                        long since) {
        public boolean account() { return !remoteId.isEmpty(); }
    }

    /** States a bridge gets out of by itself, given time (or a restart). */
    static final Set<String> RECOVERABLE = Set.of("CONNECTING", "TRANSIENT_DISCONNECT", "UNKNOWN_ERROR", "BRIDGE_UNREACHABLE");
    /** States only a new login fixes. */
    public static final Set<String> NEEDS_LOGIN = Set.of("BAD_CREDENTIALS", "LOGGED_OUT");

    private final Map<String, State> states = new HashMap<>();
    private final Map<String, Deque<Long>> drops = new HashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Milliseconds that don't advance while the computer sleeps (System.nanoTime on macOS and Windows). */
    public static long now() {
        return System.nanoTime() / 1_000_000;
    }

    public void addListener(Runnable r) { listeners.add(r); }
    public void removeListener(Runnable r) { listeners.remove(r); }

    /** A bridge's report: one state, or a {@code remoteState} map of them (the bridge's ping answer). */
    public void record(String bridge, JsonNode body, long now) {
        synchronized (this) {
            if (body.has("state_event")) put(bridge, body, now);
            for (JsonNode s : body.path("remoteState")) put(bridge, s, now);
        }
        for (Runnable r : listeners) r.run();
    }

    private void put(String bridge, JsonNode s, long now) {
        String event = s.path("state_event").asText("");
        if (event.isEmpty()) return;
        String remote = s.path("remote_id").asText("");
        String key = bridge + "|" + remote;
        State old = states.get(key);
        long since = old != null && old.event().equals(event) ? old.since() : now;
        String name = s.path("remote_name").asText("");
        if (name.isEmpty() && old != null) name = old.remoteName();
        states.put(key, new State(bridge, remote, name, event, s.path("error").asText(""), s.path("message").asText(""), since));
        if (event.equals("TRANSIENT_DISCONNECT") && (old == null || !old.event().equals(event))) {
            drops.computeIfAbsent(bridge, k -> new ArrayDeque<>()).addLast(now);
        }
    }

    /** The accounts of {@code bridge} it has reported (not the bridge-level state). */
    public synchronized List<State> accounts(String bridge) {
        List<State> out = new ArrayList<>();
        for (State s : states.values()) if (s.bridge().equals(bridge) && s.account()) out.add(s);
        out.sort((a, b) -> a.remoteId().compareTo(b.remoteId()));
        return out;
    }

    /** Since when some account of {@code bridge} has been in a state it recovers from by itself; -1 when none. */
    public synchronized long troubledSince(String bridge) {
        long since = -1;
        for (State s : states.values()) {
            if (!s.bridge().equals(bridge) || !s.account() || !RECOVERABLE.contains(s.event())) continue;
            if (since < 0 || s.since() < since) since = s.since();
        }
        return since;
    }

    /** Disconnections of {@code bridge} in the last {@code windowMs}. */
    public synchronized int dropsWithin(String bridge, long windowMs, long now) {
        Deque<Long> d = drops.get(bridge);
        if (d == null) return 0;
        while (!d.isEmpty() && now - d.peekFirst() > windowMs) d.removeFirst();
        return d.size();
    }

    /**
     * Called every 30 s: when some account is disconnected, listeners hear about it again, since what's shown
     * changes with time alone (a short drop shows as connected, a long one doesn't).
     */
    public void tick() {
        boolean troubled;
        synchronized (this) {
            troubled = states.values().stream().anyMatch(s -> s.account() && RECOVERABLE.contains(s.event()));
        }
        if (troubled) for (Runnable r : listeners) r.run();
    }

    /**
     * {@code bridge} is being restarted: its accounts show as connecting (not their last state, which no longer
     * holds) until it reports again, and its count of disconnections starts over.
     */
    public void restarting(String bridge, long now) {
        synchronized (this) {
            states.replaceAll((k, s) -> !s.bridge().equals(bridge) ? s : !s.account() ? null
                    : new State(s.bridge(), s.remoteId(), s.remoteName(), "CONNECTING", "", "", now));
            states.values().removeIf(java.util.Objects::isNull);
            drops.remove(bridge);
        }
        for (Runnable r : listeners) r.run();
    }
}
