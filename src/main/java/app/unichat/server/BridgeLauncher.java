package app.unichat.server;

import app.unichat.bridge.BridgeDidntAnswer;
import app.unichat.support.I18n;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * When {@link LocalServer} starts, restarts and stops each bridge, as {@link BridgeDemand} says: at launch only those with
 * an account, one more when its sign-in is opened, one fewer when its last account logs out. The watchdog watches
 * {@link #watched}.
 */
final class BridgeLauncher {
    /** The bridges' processes ({@link Supervisor}) and what's done around them. */
    interface Processes {
        boolean isRunning(String bridge);

        /** Writes its config (needed before a start it didn't have at launch: Telegram's keys). */
        void prepare(String bridge, Consumer<String> progress) throws IOException, InterruptedException;

        void start(String bridge) throws IOException;

        void stop(String bridge);

        /** Says it's restarting, so its accounts don't look disconnected meanwhile. */
        void restarting(String bridge);

        Path log(String bridge);

        /** Its config.yaml. */
        Path config(String bridge);
    }

    /** The bridges in use that can run now (Telegram only with its keys). */
    @FunctionalInterface
    interface Usable {
        Set<String> get() throws IOException;
    }

    private final Processes processes;
    private final BridgeDemand demand;
    private final Usable usable;

    BridgeLauncher(Processes processes, BridgeDemand demand, Usable usable) {
        this.processes = processes;
        this.demand = demand;
        this.usable = usable;
    }

    /**
     * At launch, before the server starts: writes the config of each usable bridge with an account and installs its new
     * build. One that fails is said and left as it is (it starts with the build it has): one failed download stopped
     * the whole server. The others are prepared when their sign-in opens.
     */
    void prepareAtLaunch(Consumer<String> progress) throws IOException, InterruptedException {
        for (String b : demand.withAccounts(usable.get())) {
            try {
                processes.prepare(b, progress);
            } catch (IOException | RuntimeException e) {
                System.err.println("Could not prepare " + b + ", it starts as it was: " + e);
            }
        }
    }

    /** At launch: starts the usable bridges that have an account, each on its own (one that can't never stops the rest). */
    void launch() throws IOException {
        for (String b : demand.withAccounts(usable.get())) {
            try {
                if (!processes.isRunning(b)) processes.start(b);
            } catch (IOException | RuntimeException e) {
                System.err.println("Could not start " + b + ": " + e);
            }
        }
    }

    /** The bridges that should be up now. */
    List<String> watched() {
        try {
            return demand.meant(usable.get());
        } catch (IOException e) {
            return List.of(); // no list yet
        }
    }

    /** Whether {@code bridge} may be restarted (its session restored): it's meant to run. */
    boolean mayRestart(String bridge) throws IOException {
        return usable.get().contains(bridge) && demand.isMeant(bridge);
    }

    /**
     * New settings for {@code bridge}: one that runs restarts with them; one that couldn't run before (Telegram without
     * keys) and has an account starts now; one without an account gets them when its sign-in starts it.
     */
    void applySettings(String bridge, Consumer<String> progress) throws IOException, InterruptedException {
        if (!usable.get().contains(bridge)) return;
        boolean wasMeant = demand.isMeant(bridge);
        if (!wasMeant && demand.withAccounts(List.of(bridge)).isEmpty()) return;
        processes.prepare(bridge, progress);
        if (wasMeant) {
            processes.stop(bridge);
            processes.restarting(bridge);
        }
        processes.start(bridge);
    }

    /**
     * New chat limits for {@code bridge} ("Load older chats"): its config is rewritten and it alone restarts
     * ({@link #applySettings}), and this returns once it answers again. Refused before anything stops when it isn't
     * meant to run (no account) or its config lacks a limit key (a bridge version that renamed one); a bridge that
     * stops running or says nothing within the wait is {@link BridgeDidntAnswer}.
     */
    void reconfigure(String bridge, String network, Consumer<String> progress) throws IOException, InterruptedException {
        if (!usable.get().contains(bridge) || demand.withAccounts(List.of(bridge)).isEmpty()) {
            throw new IOException(I18n.t("server.bridgeNotInUse", network));
        }
        List<String> missing = BridgeConfig.missingLimitKeys(BridgeConfig.read(Files.readString(processes.config(bridge))), bridge);
        if (!missing.isEmpty()) {
            System.err.println("Not reconfiguring " + bridge + ", its config lacks " + missing);
            throw new IOException(I18n.t("server.bridgeLimitKeyMissing", network));
        }
        applySettings(bridge, progress);
        if (!demand.answers(bridge, processes::isRunning)) {
            throw new BridgeDidntAnswer(I18n.t("server.bridgeDidntAnswer", network, processes.log(bridge)));
        }
    }

    /** Opening {@code bridge}'s sign-in: starts it and returns once it answers, for the sign-in's first call. */
    void open(String bridge, String network, Consumer<String> progress) throws IOException, InterruptedException {
        boolean answered = demand.open(bridge, processes::isRunning, b -> {
            processes.prepare(b, progress);
            processes.start(b);
        });
        if (!answered) throw new IOException(I18n.t("server.bridgeNoStart", network, processes.log(bridge)));
    }

    /** The person logged out of {@code bridge}'s last account: it stops. */
    void loggedOut(String bridge) {
        if (demand.release(bridge)) processes.stop(bridge);
    }
}
