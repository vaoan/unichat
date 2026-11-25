package app.unichat.server;

import app.unichat.Present;
import app.unichat.bridge.BridgeDidntAnswer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bridge with no account cost battery for nothing (Google Messages ran with "No user logins found"): it doesn't run
 * until the person opens its sign-in, one with an account always runs, and logging out of the last account stops it.
 */
class BridgeLauncherTest {
    /** Long enough never to be reached: these waits end by what the fake bridge does. */
    private static final Duration NO_LIMIT = Duration.ofDays(1);

    /** Written by the thread a reconfigure runs on too. */
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> running = new LinkedHashSet<>();
    private final Set<String> usable = new LinkedHashSet<>(List.of("whatsapp", "gmessages"));
    private Set<String> withAccount = Set.of("whatsapp");
    /** How many probes go unanswered before the bridge answers. */
    private int unanswered;
    /** The bridge dies as soon as it's started (it stops at every start, as gmessages did). */
    private boolean diesAtStart;
    /** Bridges whose download or config fails, and bridges that can't be launched. */
    private final Set<String> failsToPrepare = new LinkedHashSet<>();
    private final Set<String> failsToStart = new LinkedHashSet<>();
    /** When both are set, a probe says it's probing and waits for the test before it answers. */
    private @Nullable CompletableFuture<Void> probing;
    private @Nullable CountDownLatch answer;

    @TempDir
    Path configs;

    private final BridgeLauncher.Processes processes = new BridgeLauncher.Processes() {
        @Override public boolean isRunning(String b) { return running.contains(b); }
        @Override public void prepare(String b, Consumer<String> progress) throws IOException {
            calls.add("prepare " + b);
            if (failsToPrepare.contains(b)) throw new IOException("download failed");
        }
        @Override public void start(String b) throws IOException {
            calls.add("start " + b);
            if (failsToStart.contains(b)) throw new IOException("no binary");
            if (!diesAtStart) running.add(b);
        }
        @Override public void stop(String b) {
            calls.add("stop " + b);
            running.remove(b);
        }
        @Override public void restarting(String b) { calls.add("restarting " + b); }
        @Override public Path log(String b) { return Path.of("server", b, "bridge.log"); }
        @Override public Path config(String b) { return configs.resolve(b + ".yaml"); }
    };

    private BridgeLauncher launcher(Duration limit) {
        BridgeDemand demand = new BridgeDemand(b -> withAccount.contains(b), b -> { // NOPMD: LambdaCanBeMethodReference, withAccount changes below; the lambda reads it then
            calls.add("probe " + b);
            CompletableFuture<Void> p = probing;
            CountDownLatch a = answer;
            if (p != null && a != null) {
                p.complete(null);
                a.await();
            }
            return unanswered-- <= 0;
        }, Duration.ZERO, limit);
        return new BridgeLauncher(processes, demand, () -> Set.copyOf(usable));
    }

    @Test
    void aBridgeWithoutALoginIsNotStartedAtLaunch() throws Exception {
        BridgeLauncher l = launcher(NO_LIMIT);
        l.launch();
        assertEquals(List.of("start whatsapp"), calls);
        assertEquals(List.of("whatsapp"), l.watched().stream().sorted().toList());
    }

    @Test
    void aBridgeWithALoginStartsAtLaunch() throws Exception {
        withAccount = Set.of("whatsapp", "gmessages");
        BridgeLauncher l = launcher(NO_LIMIT);
        l.launch();
        assertEquals(Set.of("start whatsapp", "start gmessages"), Set.copyOf(calls));
    }

    @Test
    void openingTheSignInStartsTheBridge() throws Exception {
        BridgeLauncher l = launcher(NO_LIMIT);
        l.launch();
        calls.clear();
        unanswered = 2;
        l.open("gmessages", "Google Messages", msg -> { });
        calls.add("sign-in's first call");
        assertEquals(List.of("prepare gmessages", "start gmessages", "probe gmessages", "probe gmessages", "probe gmessages",
                "sign-in's first call"), calls, "started first, and the sign-in waits until it answers");
        assertTrue(l.watched().contains("gmessages"), "a login made in this session keeps its bridge running");
    }

    @Test
    void aBridgeAlreadyRunningIsOnlyWaitedFor() throws Exception {
        BridgeLauncher l = launcher(NO_LIMIT);
        l.launch();
        calls.clear();
        l.open("whatsapp", "WhatsApp", msg -> { });
        assertEquals(List.of("probe whatsapp"), calls);
    }

    @Test
    void aSignInWhoseBridgeStopsSaysSoAtOnceNamingItsLog() throws Exception {
        BridgeLauncher l = launcher(NO_LIMIT);
        diesAtStart = true;
        unanswered = 5; // a wait that went on would get an answer, and no error
        IOException e = assertThrows(IOException.class, () -> l.open("gmessages", "Google Messages", msg -> { }));
        assertEquals(List.of("prepare gmessages", "start gmessages", "probe gmessages"), calls, "no waiting on a bridge that isn't running");
        assertTrue(Present.of(e.getMessage()).contains("Google Messages"), e.getMessage());
        assertTrue(Present.of(e.getMessage()).contains(Path.of("server", "gmessages", "bridge.log").toString()), e.getMessage());
    }

    @Test
    void aSignInWhoseBridgeNeverAnswersGivesUpAtTheLimit() {
        BridgeLauncher l = launcher(Duration.ZERO);
        unanswered = Integer.MAX_VALUE;
        assertThrows(IOException.class, () -> l.open("gmessages", "Google Messages", msg -> { }));
        assertEquals(List.of("prepare gmessages", "start gmessages", "probe gmessages"), calls);
    }

    @Test
    void aBridgeThatCouldntStartIsntMeantToRun() throws Exception {
        BridgeLauncher.Processes failing = new BridgeLauncher.Processes() {
            @Override public boolean isRunning(String b) { return false; }
            @Override public void prepare(String b, Consumer<String> progress) throws IOException { throw new IOException("no keys"); }
            @Override public void start(String b) { calls.add("start " + b); }
            @Override public void stop(String b) { /* the fake ignores it */ }
            @Override public void restarting(String b) { /* the fake ignores it */ }
            @Override public Path log(String b) { return Path.of(b); }
            @Override public Path config(String b) { return Path.of(b); }
        };
        BridgeLauncher l = new BridgeLauncher(failing, new BridgeDemand(b -> false, b -> true, Duration.ZERO, NO_LIMIT),
                () -> Set.copyOf(usable));
        assertThrows(IOException.class, () -> l.open("gmessages", "Google Messages", msg -> { }));
        assertFalse(l.watched().contains("gmessages"));
    }

    @Test
    void telegramWithAnAccountStartsWhenItsKeysAreSaved() throws Exception {
        withAccount = Set.of("whatsapp", "telegram");
        BridgeLauncher l = launcher(NO_LIMIT);
        l.launch(); // no keys yet: telegram isn't usable
        usable.add("telegram");
        calls.clear();
        l.applySettings("telegram", msg -> { });
        assertEquals(List.of("prepare telegram", "start telegram"), calls);
        assertTrue(l.watched().contains("telegram"));
    }

    @Test
    void newSettingsRestartARunningBridgeAndLeaveOneWithoutAccountsAlone() throws Exception {
        BridgeLauncher l = launcher(NO_LIMIT);
        l.launch();
        calls.clear();
        l.applySettings("whatsapp", msg -> { });
        assertEquals(List.of("prepare whatsapp", "stop whatsapp", "restarting whatsapp", "start whatsapp"), calls);
        calls.clear();
        l.applySettings("gmessages", msg -> { });
        assertEquals(List.of(), calls);
        assertFalse(l.mayRestart("gmessages"), "nor is its session restored");
        assertTrue(l.mayRestart("whatsapp"));
    }

    @Test
    void loggingOutOfTheLastAccountStopsTheBridge() throws Exception {
        BridgeLauncher l = launcher(NO_LIMIT);
        l.launch();
        l.open("gmessages", "Google Messages", msg -> { });
        calls.clear();
        l.loggedOut("gmessages");
        assertEquals(List.of("stop gmessages"), calls);
        assertFalse(l.watched().contains("gmessages"), "and the watchdog doesn't bring it back");
        calls.clear();
        l.loggedOut("gmessages");
        assertEquals(List.of(), calls, "once");
    }

    @Test
    void onlyBridgesWithAnAccountArePreparedAtLaunch() throws Exception {
        BridgeLauncher l = launcher(NO_LIMIT);
        l.prepareAtLaunch(msg -> { });
        assertEquals(List.of("prepare whatsapp"), calls, "gmessages is prepared when its sign-in opens");
    }

    @Test
    void aBridgeWhoseNewBuildFailsStillStartsWithItsInstalledOneAndTheOthersToo() throws Exception {
        withAccount = Set.of("whatsapp", "gmessages");
        failsToPrepare.add("whatsapp");
        BridgeLauncher l = launcher(NO_LIMIT);
        l.prepareAtLaunch(msg -> { });
        l.launch();
        assertEquals(Set.of("prepare whatsapp", "prepare gmessages", "start whatsapp", "start gmessages"), Set.copyOf(calls));
    }

    @Test
    void aBridgeThatCantStartLeavesTheOthersStarting() throws Exception {
        withAccount = Set.of("whatsapp", "gmessages");
        failsToStart.add("whatsapp");
        BridgeLauncher l = launcher(NO_LIMIT);
        l.launch();
        assertEquals(Set.of("start whatsapp", "start gmessages"), Set.copyOf(calls));
    }

    private static final String DISCORD_CONFIG = """
            bridge:
                startup_private_channel_create_limit: 50
            """;

    /** Discord with an account, running, and its config on disk. */
    private BridgeLauncher discordRunning() throws IOException {
        usable.add("discord");
        withAccount = Set.of("whatsapp", "discord");
        BridgeLauncher l = launcher(NO_LIMIT);
        l.launch();
        Files.writeString(configs.resolve("discord.yaml"), DISCORD_CONFIG);
        calls.clear();
        return l;
    }

    @Test
    void reconfiguringRestartsOnlyThatBridgeAndWaitsForItsAnswer() throws Exception {
        BridgeLauncher l = discordRunning();
        CompletableFuture<Void> p = new CompletableFuture<>();
        CountDownLatch a = new CountDownLatch(1);
        probing = p;
        answer = a;
        CompletableFuture<Void> done = CompletableFuture.runAsync(() -> {
            try {
                l.reconfigure("discord", "Discord", msg -> { });
            } catch (IOException | InterruptedException e) {
                throw new CompletionException(e);
            }
        });
        try {
            CompletableFuture.anyOf(p, done).get(); // probing, or returned without waiting (the failure)
            assertFalse(done.isDone(), "it returns only once the bridge answers");
        } finally {
            a.countDown();
        }
        done.get();
        assertEquals(List.of("prepare discord", "stop discord", "restarting discord", "start discord", "probe discord"), calls,
                "only discord, restarted with its new config, then waited for");
    }

    /** A bridge version that renamed the key would run with the limit it had: said before anything stops. */
    @Test
    void aLimitKeyMissingRefusesBeforeStopping() throws Exception {
        BridgeLauncher l = discordRunning();
        Files.writeString(configs.resolve("discord.yaml"), "bridge:\n    other: 1\n");
        IOException e = assertThrows(IOException.class, () -> l.reconfigure("discord", "Discord", msg -> { }));
        assertFalse(e instanceof BridgeDidntAnswer, e.toString());
        assertTrue(Present.of(e.getMessage()).contains("Discord"), e.getMessage());
        assertEquals(List.of(), calls, "nothing stopped, nothing rewritten");
    }

    @Test
    void aBridgeThatDoesntAnswerAfterReconfiguringIsReported() throws Exception {
        BridgeLauncher l = discordRunning();
        diesAtStart = true;
        unanswered = 5; // a wait that went on would get an answer
        BridgeDidntAnswer e = assertThrows(BridgeDidntAnswer.class, () -> l.reconfigure("discord", "Discord", msg -> { }));
        assertTrue(Present.of(e.getMessage()).contains("Discord"), e.getMessage());
        assertEquals(List.of("prepare discord", "stop discord", "restarting discord", "start discord", "probe discord"), calls,
                "no waiting on a bridge that stopped running");
    }

    @Test
    void aBridgeNotMeantToRunIsntStartedByReconfigure() throws Exception {
        BridgeLauncher l = launcher(NO_LIMIT);
        l.launch();
        Files.writeString(configs.resolve("gmessages.yaml"), "network:\n    initial_chat_sync_count: 25\n");
        calls.clear();
        IOException e = assertThrows(IOException.class, () -> l.reconfigure("gmessages", "Google Messages", msg -> { }));
        assertTrue(Present.of(e.getMessage()).contains("Google Messages"), e.getMessage());
        assertEquals(List.of(), calls, "a bridge without accounts isn't started for this");
        assertFalse(l.watched().contains("gmessages"));
    }
}
