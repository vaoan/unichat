package app.unichat.server;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Which installed bridges are meant to run. One with an account is, from launch; one without isn't (Google Messages
 * ran with "No user logins found", for nothing) until the person opens its sign-in, and then for the session, or
 * until they log out of its last account.
 */
final class BridgeDemand {
    /** Starts a bridge that isn't running. */
    @FunctionalInterface
    interface Start {
        void run(String bridge) throws IOException, InterruptedException;
    }

    /** Whether the bridge answers now (its {@link HttpProbe}). */
    @FunctionalInterface
    interface Probe {
        boolean answers(String bridge) throws InterruptedException;
    }

    private final Predicate<String> hasAccount;
    private final Probe probe;
    private final Duration pause;
    private final Duration limit;
    private final Set<String> meant = ConcurrentHashMap.newKeySet();

    /** @param pause between unanswered probes; @param limit how long a sign-in waits for its bridge in all */
    BridgeDemand(Predicate<String> hasAccount, Probe probe, Duration pause, Duration limit) {
        this.hasAccount = hasAccount;
        this.probe = probe;
        this.pause = pause;
        this.limit = limit;
    }

    /** The bridges among {@code bridges} meant to run, asking whether they have an account those not meant yet. */
    List<String> withAccounts(Collection<String> bridges) {
        for (String b : bridges) if (!meant.contains(b) && hasAccount.test(b)) meant.add(b);
        return meant(bridges);
    }

    /** The bridges among {@code bridges} meant to run now. */
    List<String> meant(Collection<String> bridges) {
        return bridges.stream().filter(meant::contains).toList();
    }

    boolean isMeant(String bridge) {
        return meant.contains(bridge);
    }

    /**
     * Opening {@code bridge}'s sign-in: {@code start} starts it when it isn't {@code running}, it's meant to run from then
     * on, and this returns whether it answered: false as soon as it isn't running, or after the limit.
     */
    boolean open(String bridge, Predicate<String> running, Start start) throws IOException, InterruptedException {
        if (!running.test(bridge)) start.run(bridge);
        meant.add(bridge);
        long end = System.nanoTime() + limit.toNanos();
        while (true) {
            if (probe.answers(bridge)) return true;
            if (!running.test(bridge) || System.nanoTime() - end >= 0) return false;
            Thread.sleep(pause);
        }
    }

    /** Its last account logged out: not meant to run any more. Whether it was. */
    boolean release(String bridge) {
        return meant.remove(bridge);
    }
}
