package app.unichat.server;

import app.unichat.bridge.BridgePorts;
import app.unichat.support.I18n;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Which installed bridges run. One with an account starts at launch; one without doesn't (Google Messages ran with
 * "No user logins found", for nothing) until the person opens its sign-in, and from then on it runs for the session.
 * The watchdog watches only these ({@link #meant}).
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
    private final int tries;
    private final Set<String> meant = ConcurrentHashMap.newKeySet();

    /** @param pause between unanswered probes; @param tries probes before the sign-in gives up */
    BridgeDemand(Predicate<String> hasAccount, Probe probe, Duration pause, int tries) {
        this.hasAccount = hasAccount;
        this.probe = probe;
        this.pause = pause;
        this.tries = tries;
    }

    /** The bridges among {@code usable} to start at launch: those with an account, and those opened this session. */
    List<String> atLaunch(Collection<String> usable) {
        for (String b : usable) if (!meant.contains(b) && hasAccount.test(b)) meant.add(b);
        return meant(usable);
    }

    /** The bridges among {@code usable} meant to run now. */
    List<String> meant(Collection<String> usable) {
        return usable.stream().filter(meant::contains).toList();
    }

    boolean isMeant(String bridge) {
        return meant.contains(bridge);
    }

    /**
     * Opening {@code bridge}'s sign-in: it runs from now on, {@code start} starts it when it isn't {@code running}, and
     * this returns once it answers (the sign-in's first call needs it), or throws when it never does.
     */
    void open(String bridge, boolean running, Start start) throws IOException, InterruptedException {
        meant.add(bridge);
        if (!running) start.run(bridge);
        for (int i = 0; i < tries; i++) {
            if (probe.answers(bridge)) return;
            if (i + 1 < tries) Thread.sleep(pause);
        }
        throw new IOException(I18n.t("server.bridgeNoStart", BridgePorts.BRIDGE_PORTS.get(bridge)));
    }
}
