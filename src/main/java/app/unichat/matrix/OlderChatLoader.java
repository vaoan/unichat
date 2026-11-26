package app.unichat.matrix;

import app.unichat.bridge.BridgeDidntAnswer;
import app.unichat.bridge.ChatLimits;
import app.unichat.model.Network;
import app.unichat.service.OlderChats.Answer;
import app.unichat.service.OlderChats.Loading;
import app.unichat.service.OlderChats.Plan;
import app.unichat.service.OlderChats.Stage;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs one click of "Load older chats": for each network asked, one at a time, its step ({@link OlderChatSteps}), then
 * counts the rooms that arrive ({@link RoomArrivals}) and marks each read, so loading never raises the unread badge.
 * Only {@link #load} runs anything, and a second call while one runs gets the same future.
 */
final class OlderChatLoader {
    /** Quiet this long without a new room and the step is over; bridges never say when they're done. */
    static final Duration QUIET = Duration.ofSeconds(45);
    /** The longest a step counts arriving rooms; the rest still arrive later, uncounted. */
    static final Duration MAX = Duration.ofMinutes(15);
    /** How the WhatsApp patch's reply begins when it ran. */
    private static final String HISTORY_CHATS_CREATED = "Created ";

    /** A command to a network's bridge bot, answered with its reply; a {@link TimeoutException} when none came. */
    @FunctionalInterface
    interface Bot {
        String query(Network n, String command, Duration patience) throws Exception; // NOPMD: SignatureDeclareThrowsException, a callback: the bot chat fails as it does
    }

    /** Where the steps' settings are kept: {@link Settings} in production, a map in tests. A null value removes it. */
    interface SettingsWriter {
        @Nullable String get(String key);

        void set(String key, @Nullable String value);

        static SettingsWriter of(Settings s) {
            return new SettingsWriter() {
                @Override public @Nullable String get(String key) { return s.getString(key); }

                @Override public void set(String key, @Nullable String value) { s.set(key, value); }
            };
        }
    }

    private final OlderChatSteps steps;
    private final Bot bot;
    private final BridgeProvisioner provisioner;
    private final RoomArrivals arrivals;
    private final SettingsWriter settings;
    private final Consumer<String> markRead;
    private final Function<String, @Nullable Instant> newest;
    private final Executor io;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private @Nullable CompletableFuture<Map<Network, Answer>> running;
    /** Guards {@link #runner} against a cancel landing after the run left its thread. */
    private final Object runLock = new Object();
    private @Nullable Thread runner;
    private volatile boolean cancelled;
    private volatile @Nullable Loading loading;

    /** markRead: ChatPreferences.markRead in production; newest: a room's newest message time (null when none). */
    OlderChatLoader(OlderChatSteps steps, Bot bot, BridgeProvisioner provisioner, RoomArrivals arrivals,
                    SettingsWriter settings, Consumer<String> markRead, Function<String, @Nullable Instant> newest,
                    Executor io) {
        this.steps = steps;
        this.bot = bot;
        this.provisioner = provisioner;
        this.arrivals = arrivals;
        this.settings = settings;
        this.markRead = markRead;
        this.newest = newest;
        this.io = io;
    }

    /** The loader of a signed-in service: its steps read the settings, its bot is {@code bridges}' and its clock the real one. */
    static OlderChatLoader of(BridgeAccounts bridges, Function<Network, Set<String>> rooms, Consumer<String> markRead,
                              Function<String, @Nullable Instant> newest, Executor io) {
        RoomArrivals arrivals = new RoomArrivals(rooms, Instant::now,
                (d, r) -> CompletableFuture.delayedExecutor(d.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS, io).execute(r));
        return new OlderChatLoader(new OlderChatSteps(Settings.get()::getString, bridges::withLogins, Instant::now),
                bridges::olderChatsCommand, BridgeAccounts.live(), arrivals, SettingsWriter.of(Settings.get()), markRead, newest, io);
    }

    /** What counts this loader's arriving rooms; the service tells it when the sync changed them. */
    RoomArrivals arrivals() { return arrivals; }

    List<Plan> plans(Collection<Network> inView) { return steps.plans(inView); }

    boolean hasMore(Collection<Network> inView) { return steps.hasMore(inView); }

    /**
     * One step for each of {@code networks}, in that order, one at a time. Instagram is among them only when ticked in
     * the sheet ({@code OlderChatsSheet}, which guards it); nothing here adds it.
     */
    synchronized CompletableFuture<Map<Network, Answer>> load(List<Network> networks) {
        if (cancelled) return CompletableFuture.completedFuture(Map.of());
        CompletableFuture<Map<Network, Answer>> run = running;
        if (run != null && !run.isDone()) return run;
        List<Network> asked = List.copyOf(networks);
        run = CompletableFuture.supplyAsync(() -> runAll(asked), io);
        running = run;
        return run;
    }

    /**
     * The session ended (logout, quit): the step under way is interrupted, no other network runs, and nothing runs
     * again, so a new session's loader is never one of two.
     */
    void cancel() {
        synchronized (runLock) {
            cancelled = true;
            Thread t = runner;
            if (t != null) t.interrupt();
        }
    }

    @Nullable Loading loading() { return loading; }

    void addListener(Runnable l) { listeners.add(l); }

    void removeListener(Runnable l) { listeners.remove(l); }

    private Map<Network, Answer> runAll(List<Network> networks) {
        synchronized (runLock) {
            runner = Thread.currentThread();
        }
        try {
            return runEach(networks);
        } finally {
            synchronized (runLock) {
                runner = null;
                // A cancel's interrupt is this run's: the pool's thread goes on without it.
                if (cancelled) {
                    var _ = Thread.interrupted();
                }
            }
        }
    }

    private Map<Network, Answer> runEach(List<Network> networks) {
        Map<Network, Answer> answers = Collections.synchronizedMap(new EnumMap<>(Network.class));
        for (Network n : networks) {
            if (cancelled) break;
            Answer a;
            try {
                a = runOne(n, answers, networks.size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                a = new Answer.NotPossible(failed(e));
            }
            answers.put(n, a);
            System.err.println("Older chats: " + n.protocolId() + ": " + a.getClass().getSimpleName()
                    + (a instanceof Answer.Loaded l ? " " + l.chats() : ""));
        }
        Network last = networks.isEmpty() ? Network.MATRIX : networks.getLast();
        tell(new Loading(last, Stage.DONE, 0, answers.size(), networks.size(), copy(answers)));
        return copy(answers);
    }

    private Answer runOne(Network n, Map<Network, Answer> answers, int total) throws InterruptedException {
        OlderChatSteps.Next next = steps.next(n);
        if (next instanceof OlderChatSteps.Next.Done(Answer answer)) return answer;
        OlderChatSteps.Step step = ((OlderChatSteps.Next.Run) next).step();
        Progress p = new Progress(n, answers, total);
        Instant start = steps.now();
        RoomArrivals.Counting counting = arrivals.start(n);
        Map<String, Instant> seen = new HashMap<>();
        counting.onChange(() -> {
            markLoaded(counting.rooms(), seen, start);
            p.newChats(counting.newRooms());
        });
        boolean answered = true;
        try {
            Answer refused = step.kind() == OlderChatSteps.Kind.COMMAND ? null : raiseAndRestart(step, p);
            if (refused != null) return refused;
            String command = step.command();
            if (command != null) {
                p.stage(Stage.ASKING);
                Answer failed = null;
                try {
                    failed = ask(step, command);
                } catch (TimeoutException noReply) {
                    answered = false; // the bridge may still be at it: what arrives is the answer
                }
                if (failed != null) return failed;
                if (answered && step.kind() == OlderChatSteps.Kind.COMMAND) step.settings().forEach(settings::set);
            }
            p.stage(Stage.ARRIVING);
            int count = waitQuiet(counting);
            return counted(step, count, answered);
        } finally {
            counting.stop();
            for (String k : List.of(ChatLimits.GMESSAGES_ONCE, ChatLimits.INSTAGRAM_ONCE)) {
                if (step.settings().containsKey(k)) settings.set(k, null);
            }
        }
    }

    /**
     * The answer for {@code count} new rooms, keeping "nothing more" when the network has none left to give. A command
     * that got no answer proves nothing, so the next click asks again.
     */
    private Answer counted(OlderChatSteps.Step step, int count, boolean answered) {
        boolean groupsFirst = step.settings().containsKey(OlderChatSteps.WHATSAPP_GROUPS);
        boolean nothingLeft = OlderChatSteps.onlyOnce(step) || (count == 0 && !groupsFirst);
        if (answered && nothingLeft) settings.set(OlderChatSteps.doneKey(step.network()), "true");
        return count > 0 ? new Answer.Loaded(count) : new Answer.NothingMore();
    }

    /**
     * Writes the step's setting and restarts the bridge; null when it came back. A refusal before stopping puts every
     * setting back; a bridge that didn't come back gets its old setting and one more restart (Discord's pause stays: it did restart).
     */
    private @Nullable Answer raiseAndRestart(OlderChatSteps.Step step, Progress p) throws InterruptedException {
        Map<String, @Nullable String> old = new HashMap<>();
        for (String k : step.settings().keySet()) old.put(k, settings.get(k));
        step.settings().forEach(settings::set);
        p.stage(Stage.RESTARTING);
        try {
            provisioner.reconfigure(step.network(), OlderChatLoader::notShown);
            return null;
        } catch (InterruptedException e) {
            // Ended mid-restart (a logout): the old setting goes back, so the next config written is the one it had.
            old.forEach(settings::set);
            Thread.currentThread().interrupt();
            throw e;
        } catch (BridgeDidntAnswer e) {
            old.remove(OlderChatSteps.DISCORD_AT);
            old.forEach(settings::set);
            restartWithTheOldSetting(step.network());
            return new Answer.NotPossible(I18n.t("olderChats.didntComeBack"));
        } catch (Exception e) {
            old.forEach(settings::set);
            System.err.println("Older chats: " + step.network().protocolId() + " refused: " + e.getMessage());
            return new Answer.NotPossible(failed(e));
        }
    }

    /** One more restart, with the setting it had before; the watchdog takes over if this fails too. */
    private void restartWithTheOldSetting(Network n) throws InterruptedException {
        System.err.println("Older chats: " + n.protocolId() + " didn't come back; restarting with the old setting");
        try {
            provisioner.reconfigure(n, OlderChatLoader::notShown);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            System.err.println("Older chats: " + n.protocolId() + " restart with the old setting failed: " + e.getMessage());
        }
    }

    /** Sends the step's command; null when the bot took it, a {@link TimeoutException} when it didn't answer in time. */
    private @Nullable Answer ask(OlderChatSteps.Step step, String command) throws InterruptedException, TimeoutException {
        String reply;
        try {
            reply = bot.query(step.network(), command, step.commandPatience());
        } catch (InterruptedException | TimeoutException e) {
            throw e;
        } catch (Exception e) {
            System.err.println("Older chats: " + step.network().protocolId() + " " + command + " failed: " + e.getMessage());
            return new Answer.NotPossible(failed(e));
        }
        if (reply.toLowerCase(Locale.ROOT).contains("unknown command")) {
            return new Answer.NotPossible(command.startsWith(OlderChatSteps.WHATSAPP_HISTORY_COMMAND) ? I18n.t("olderChats.needsUpdate")
                    : I18n.t("olderChats.failed", reply));
        }
        // WhatsApp's patch says "Created <k> chats…" when it ran; anything else ("Not logged in" while it reconnects) is
        // no "nothing more", so nothing is marked done and the next click asks again.
        if (command.startsWith(OlderChatSteps.WHATSAPP_HISTORY_COMMAND) && !reply.startsWith(HISTORY_CHATS_CREATED)) {
            return new Answer.NotPossible(I18n.t("olderChats.failed", reply));
        }
        // Telegram's sync-chats says "Successfully…" when it walked the list; anything else is the bridge saying why not.
        if (step.kind() == OlderChatSteps.Kind.RAISE_RESTART_COMMAND) {
            try {
                BridgeProtocol.requireSuccess(reply);
            } catch (MatrixException e) {
                return new Answer.NotPossible(I18n.t("olderChats.failed", reply));
            }
        }
        return null;
    }

    private static int waitQuiet(RoomArrivals.Counting counting) throws InterruptedException {
        try {
            return counting.whenQuiet(QUIET, MAX).get();
        } catch (java.util.concurrent.ExecutionException e) {
            return counting.newRooms();
        }
    }

    /**
     * Marks read each room counted, once, and again when its newest message changed (its history came after it); a room
     * whose newest message is newer than the step's start is news, left as the sync says.
     */
    private void markLoaded(Set<String> counted, Map<String, Instant> seen, Instant start) {
        for (String id : counted) {
            Instant last = newest.apply(id);
            Instant key = last == null ? Instant.EPOCH : last;
            synchronized (seen) {
                if (key.equals(seen.get(id))) continue;
                seen.put(id, key);
            }
            if (last != null && last.isAfter(start)) continue;
            markRead.accept(id);
        }
    }

    /** A line of a bridge's restart: the bar says "restarting its bridge", not each line. */
    static void notShown(String line) { /* not shown */ }

    private static String failed(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? I18n.t("olderChats.failed", e.getClass().getSimpleName()) : m;
    }

    private static Map<Network, Answer> copy(Map<Network, Answer> answers) {
        synchronized (answers) {
            return answers.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(answers));
        }
    }

    private void tell(Loading l) {
        loading = l;
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException e) {
                System.err.println("Older chats listener failed: " + e);
            }
        }
    }

    /** One network's place in the run, told to the listeners as it moves. */
    private final class Progress {
        private final Network network;
        private final Map<Network, Answer> answers;
        private final int total;
        private Stage stage = Stage.RESTARTING;
        private int newChats;

        Progress(Network network, Map<Network, Answer> answers, int total) {
            this.network = network;
            this.answers = answers;
            this.total = total;
        }

        synchronized void stage(Stage s) {
            stage = s;
            tellNow();
        }

        synchronized void newChats(int n) {
            if (n == newChats) return;
            newChats = n;
            tellNow();
        }

        private void tellNow() {
            Map<Network, Answer> so = copy(answers);
            tell(new Loading(network, stage, newChats, so.size(), total, so));
        }
    }
}
