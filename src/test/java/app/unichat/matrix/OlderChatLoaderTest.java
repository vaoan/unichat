package app.unichat.matrix;

import app.unichat.bridge.BridgeDidntAnswer;
import app.unichat.model.Network;
import app.unichat.service.OlderChats.Answer;
import app.unichat.service.OlderChats.Loading;
import app.unichat.service.OlderChats.Stage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OlderChatLoaderTest {
    private static final Instant START = Instant.parse("2026-11-24T12:00:00Z");

    private final ManualTime time = new ManualTime(START);
    /** Everything the loader did, in order: "set k=v", "remove k", "reconfigure NET k=v…", "command NET text patience". */
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final Map<String, String> settings = new ConcurrentHashMap<>();
    private final Map<Network, Set<String>> rooms = new EnumMap<>(Network.class);
    private final Map<String, Instant> newest = new ConcurrentHashMap<>();
    private final List<String> marked = new CopyOnWriteArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<Network> accounts = EnumSet.of(Network.TELEGRAM, Network.WHATSAPP, Network.DISCORD, Network.LINKEDIN,
            Network.GOOGLE_MESSAGES, Network.INSTAGRAM);

    /** What the bot does with a command (by its text): answers, throws, or never answers. */
    private final Map<String, BotReply> replies = new ConcurrentHashMap<>();
    /** What the provisioner throws on each reconfigure of a network, in turn (none left: it comes back). */
    private final Map<Network, List<Exception>> refusals = new ConcurrentHashMap<>();
    private volatile CountDownLatch reconfigureHeld = new CountDownLatch(0);
    /** What the bridge brings once it came back (rooms arriving). */
    private volatile Runnable cameBack = () -> { };

    @FunctionalInterface
    private interface BotReply { String answer() throws Exception; } // NOPMD: SignatureDeclareThrowsException, a fake bot fails as the real one does

    // Through this, not the fields: a reference to a field's method is made before the field is (SpotBugs' CT_CONSTRUCTOR_THROW).
    private Instant now() { return time.now(); }

    private void later(Duration d, Runnable r) { time.later(d, r); }

    private @Nullable String setting(String key) { return settings.get(key); }

    private void mark(String id) { marked.add(id); }

    private @Nullable Instant newestOf(String id) { return newest.get(id); }

    private final RoomArrivals arrivals = new RoomArrivals(this::roomsOf, this::now, this::later);
    private final OlderChatSteps steps = new OlderChatSteps(this::setting, accounts, this::now);
    private final OlderChatLoader loader = new OlderChatLoader(steps, this::command, new FakeProvisioner(), arrivals,
            new OlderChatLoader.SettingsWriter() {
                @Override public @Nullable String get(String key) { return settings.get(key); }

                @Override
                public void set(String key, @Nullable String value) {
                    if (value == null) {
                        settings.remove(key);
                        events.add("remove " + key);
                    } else {
                        settings.put(key, value);
                        events.add("set " + key + "=" + value);
                    }
                    time.changes.changed();
                }
            }, this::mark, this::newestOf, io);

    @AfterEach
    void stopThreads() {
        reconfigureHeld.countDown();
        io.shutdownNow();
    }

    private synchronized Set<String> roomsOf(Network n) {
        return Set.copyOf(rooms.getOrDefault(n, Set.of()));
    }

    /** A room of {@code n} appears (with its newest message, or none yet) and the sync says so. */
    private void arrive(Network n, String id, @Nullable Instant last) {
        synchronized (this) {
            rooms.computeIfAbsent(n, k -> ConcurrentHashMap.newKeySet()).add(id);
        }
        if (last != null) newest.put(id, last);
        arrivals.roomsChanged();
    }

    private String command(Network n, String text, Duration patience) throws Exception { // NOPMD: SignatureDeclareThrowsException, the fake bot fails as a script says
        events.add("command " + n + " " + text + " " + patience);
        time.changes.changed();
        BotReply r = replies.get(text);
        return r == null ? "Successfully synced" : r.answer();
    }

    private final class FakeProvisioner implements BridgeProvisioner {
        @Override public void ensure(Network network) { /* every bridge is there */ }

        @Override
        public void reconfigure(Network network, Consumer<String> progress) throws Exception {
            List<String> olderChats = new ArrayList<>();
            settings.forEach((k, v) -> { if (k.startsWith("olderChats.") && !k.endsWith(".at")) olderChats.add(k + "=" + v); });
            olderChats.sort(null);
            events.add(("reconfigure " + network + " " + String.join(" ", olderChats)).strip());
            time.changes.changed();
            reconfigureHeld.await();
            List<Exception> left = refusals.getOrDefault(network, List.of());
            if (!left.isEmpty()) throw left.removeFirst();
            cameBack.run();
        }

        @Override
        public void rewrite(Network network) {
            List<String> olderChats = new ArrayList<>();
            settings.forEach((k, v) -> { if (k.startsWith("olderChats.") && !k.endsWith(".at")) olderChats.add(k + "=" + v); });
            olderChats.sort(null);
            events.add(("rewrite " + network + " " + String.join(" ", olderChats)).strip()
                    + (Thread.currentThread().isInterrupted() ? " (interrupted)" : ""));
            time.changes.changed();
        }
    }

    /** Runs the clock past every quiet wait until the load answers. */
    private Map<Network, Answer> answers(CompletableFuture<Map<Network, Answer>> run) throws InterruptedException, ExecutionException {
        var _ = run.whenComplete((a, e) -> time.changes.changed());
        while (!run.isDone()) {
            time.changes.until("the load answered or waits on the clock", () -> run.isDone() || time.scheduled() > 0);
            time.advance(Duration.ofSeconds(45));
        }
        return run.get();
    }

    private Map<Network, Answer> load(Network... networks) throws InterruptedException, ExecutionException {
        return answers(loader.load(List.of(networks)));
    }

    private void refuse(Network n, Exception e) {
        refusals.computeIfAbsent(n, k -> new CopyOnWriteArrayList<>()).add(e);
    }

    private int firstStarting(String prefix) {
        for (int i = 0; i < events.size(); i++) if (events.get(i).startsWith(prefix)) return i;
        return -1;
    }

    private List<String> eventsStarting(String prefix) {
        return events.stream().filter(e -> e.startsWith(prefix)).toList();
    }

    @Test
    void telegramRaisesBothLimitsRestartsThenSyncsChats() throws Exception {
        replies.put("sync-chats", () -> {
            for (int i = 0; i < 30; i++) arrive(Network.TELEGRAM, "!tg" + i, START.minus(Duration.ofDays(40)));
            return "Successfully synced 30 chats";
        });
        assertEquals(Map.of(Network.TELEGRAM, new Answer.Loaded(30)), load(Network.TELEGRAM));
        assertEquals(List.of("set olderChats.telegram.extra=100", "reconfigure TELEGRAM olderChats.telegram.extra=100",
                "command TELEGRAM sync-chats PT10M"), events);
    }

    @Test
    void whatsappSendsSyncGroupsWithoutARestart() throws Exception {
        replies.put("sync groups", () -> {
            arrive(Network.WHATSAPP, "!group", START.minus(Duration.ofDays(3)));
            return "Queued syncs for 1 groups";
        });
        assertEquals(Map.of(Network.WHATSAPP, new Answer.Loaded(1)), load(Network.WHATSAPP));
        assertEquals(List.of("command WHATSAPP sync groups PT2M", "set olderChats.whatsapp.groups=done"), events);
    }

    @Test
    void whatsappSyncGroupsRefusedIsNotPossibleAndAskedAgainLater() throws Exception {
        // fnSync answers "Queued syncs for <n> groups" when it ran; "Login not found" or "Failed to get joined groups" is no.
        replies.put("sync groups", () -> "Failed to get joined groups: websocket not connected");
        Answer a = load(Network.WHATSAPP).get(Network.WHATSAPP);
        assertEquals(new Answer.NotPossible("no funcionó: Failed to get joined groups: websocket not connected"), a);
        assertEquals(List.of(), eventsStarting("set"), "groups not marked done: the next click asks again");
    }

    @Test
    void whatsappHistoryChatsThatCreatedSomeButBroughtNoneInTimeIsNotDone() throws Exception {
        // The bridge made the chats, but their rooms came after the quiet wait: no proof there's nothing more.
        settings.put("olderChats.whatsapp.groups", "done");
        replies.put("sync-history-chats 100", () -> "Created 7 chats from history sync");
        assertEquals(Map.of(Network.WHATSAPP, new Answer.NothingMore()), load(Network.WHATSAPP));
        assertEquals(List.of(), eventsStarting("set"), "not done: the next click asks again");
    }

    @Test
    void whatsappAsksTheHistoryChatsOfThePatch() throws Exception {
        settings.put("olderChats.whatsapp.groups", "done");
        var _ = load(Network.WHATSAPP);
        assertEquals(List.of("command WHATSAPP sync-history-chats 100 PT2M"), eventsStarting("command"));
    }

    @Test
    void whatsappWithoutThePatchIsNotPossible() throws Exception {
        settings.put("olderChats.whatsapp.groups", "done");
        replies.put("sync-history-chats 100", () -> "Unknown command, use the `help` command for help.");
        Answer a = load(Network.WHATSAPP).get(Network.WHATSAPP);
        assertEquals(new Answer.NotPossible("necesita la próxima actualización de Unichat"), a);
        assertEquals(List.of(), eventsStarting("set"), "nothing marked done: the patched build can still be asked later");
    }

    @Test
    void whatsappHistoryChatsRefusedIsNotPossibleAndAskedAgainLater() throws Exception {
        // The patch says "Not logged in" while WhatsApp reconnects: that is no "nothing more", thousands may be left.
        settings.put("olderChats.whatsapp.groups", "done");
        replies.put("sync-history-chats 100", () -> "Not logged in");
        Answer a = load(Network.WHATSAPP).get(Network.WHATSAPP);
        assertEquals(new Answer.NotPossible("no funcionó: Not logged in"), a);
        assertEquals(List.of(), eventsStarting("set"), "nothing marked done: the next click asks again");
    }

    @Test
    void whatsappHistoryChatsThatCreatedNoneIsNothingMore() throws Exception {
        settings.put("olderChats.whatsapp.groups", "done");
        replies.put("sync-history-chats 100", () -> "Created 0 chats from history sync");
        assertEquals(Map.of(Network.WHATSAPP, new Answer.NothingMore()), load(Network.WHATSAPP));
        assertEquals(List.of("set olderChats.whatsapp.done=true"), eventsStarting("set"));
    }

    @Test
    void aOneShotLimitClearedIsWrittenOutOfTheBridgesConfigToo() throws Exception {
        // The watchdog restarts a bridge with the config on disk: left there, the one-shot would be asked again.
        var _ = load(Network.GOOGLE_MESSAGES);
        int cleared = events.indexOf("remove olderChats.gmessages.once");
        int rewritten = events.indexOf("rewrite GOOGLE_MESSAGES olderChats.gmessages.done=true");
        assertTrue(cleared >= 0 && rewritten > cleared, events.toString());
        assertEquals(1, eventsStarting("reconfigure").size(), "rewritten, not restarted");
    }

    @Test
    void aOneShotLimitIsWrittenOutEvenWhenTheRunWasCancelled() throws Exception {
        reconfigureHeld = new CountDownLatch(1);
        CompletableFuture<Map<Network, Answer>> run = loader.load(List.of(Network.INSTAGRAM));
        time.changes.until("Instagram's restart", () -> !eventsStarting("reconfigure").isEmpty());
        loader.cancel();
        var _ = run.get();
        assertEquals(List.of("rewrite INSTAGRAM"), eventsStarting("rewrite"), "written out, and not refused by the cancel's interrupt");
    }

    @Test
    void aStepWithoutAOneShotRewritesNothing() throws Exception {
        var _ = load(Network.DISCORD);
        assertEquals(List.of(), eventsStarting("rewrite"));
    }

    @Test
    void aFailureSaysSoInThePersonsWordsAndLogsTheDetail() throws Exception {
        replies.put("sync-chats", () -> { throw new IOException("HTTP 502 Bad Gateway from 127.0.0.1:6167"); });
        Answer a = load(Network.TELEGRAM).get(Network.TELEGRAM);
        assertEquals(new Answer.NotPossible("algo falló; el registro dice qué"), a);
    }

    @Test
    void discordRaisesFiftyAndRestartsOnce() throws Exception {
        var _ = load(Network.DISCORD);
        assertEquals(List.of("reconfigure DISCORD olderChats.discord.extra=50"), eventsStarting("reconfigure"));
        assertEquals(START.toString(), settings.get("olderChats.discord.at"));
    }

    @Test
    void aSecondDiscordStepWithinTenMinutesDoesntRestart() throws Exception {
        cameBack = () -> arrive(Network.DISCORD, "!dm", START.minus(Duration.ofDays(9)));
        assertEquals(Map.of(Network.DISCORD, new Answer.Loaded(1)), load(Network.DISCORD));
        time.advance(Duration.ofMinutes(5));
        Answer second = load(Network.DISCORD).get(Network.DISCORD);
        assertInstanceOf(Answer.NotPossible.class, second, "Discord's pause");
        assertEquals(1, eventsStarting("reconfigure").size(), "no second restart");
    }

    @Test
    void instagramRunsOnlyWhenIncluded() throws Exception {
        // The sheet puts Instagram in the list only when ticked (Task 8 guards the tick): left out, nothing touches it.
        Map<Network, Answer> without = answers(loader.load(List.of(Network.TELEGRAM)));
        assertFalse(without.containsKey(Network.INSTAGRAM), "Instagram's answer is absent");
        assertTrue(events.stream().noneMatch(e -> e.contains("INSTAGRAM") || e.contains("instagram")), events.toString());
        var _ = answers(loader.load(List.of(Network.INSTAGRAM)));
        List<String> restarts = eventsStarting("reconfigure INSTAGRAM");
        assertEquals(1, restarts.size(), events.toString());
        assertTrue(restarts.getFirst().contains("olderChats.instagram.once=true"), restarts.toString());
    }

    @Test
    void oneShotSettingsAreClearedWhenTheirStepEnds() throws Exception {
        arrive(Network.GOOGLE_MESSAGES, "!sms-before", null);
        refuse(Network.INSTAGRAM, new app.unichat.bridge.BridgeRefused("Esta versión del puente de Instagram no lo permite"));
        Map<Network, Answer> a = answers(loader.load(List.of(Network.GOOGLE_MESSAGES, Network.INSTAGRAM)));
        assertEquals(new Answer.NotPossible("Esta versión del puente de Instagram no lo permite"), a.get(Network.INSTAGRAM));
        assertEquals(new Answer.NothingMore(), a.get(Network.GOOGLE_MESSAGES));
        assertNull(settings.get("olderChats.gmessages.once"));
        assertNull(settings.get("olderChats.instagram.once"));
        assertEquals("true", settings.get("olderChats.gmessages.done"), "done on success");
        assertNull(settings.get("olderChats.instagram.done"), "not done when it failed");
    }

    @Test
    void aChatArrivingThroughALoadIsMarkedRead() throws Exception {
        arrive(Network.TELEGRAM, "!already", START.minus(Duration.ofDays(1)));
        replies.put("sync-chats", () -> {
            for (String id : List.of("!a", "!b", "!c")) arrive(Network.TELEGRAM, id, null);
            arrive(Network.WHATSAPP, "!wa", START.minus(Duration.ofDays(1)));
            newest.put("!a", START.minus(Duration.ofDays(2)));
            newest.put("!b", START.minus(Duration.ofDays(30)));
            newest.put("!c", START.minus(Duration.ofDays(730)));
            arrivals.roomsChanged(); // their history arrives
            return "Successfully synced";
        });
        assertEquals(new Answer.Loaded(3), load(Network.TELEGRAM).get(Network.TELEGRAM));
        for (String id : List.of("!a", "!b", "!c")) {
            assertEquals(2, marked.stream().filter(id::equals).count(), id + ": marked when it came, and again with its history");
        }
        assertFalse(marked.contains("!already"));
        assertFalse(marked.contains("!wa"));
    }

    @Test
    void aLoadedChatWithANewMessageIsLeftUnread() throws Exception {
        replies.put("sync-chats", () -> {
            arrive(Network.TELEGRAM, "!old", START.minus(Duration.ofDays(5)));
            arrive(Network.TELEGRAM, "!news", START.plusSeconds(1));
            return "Successfully synced";
        });
        var _ = load(Network.TELEGRAM);
        assertEquals(List.of("!old"), marked);
    }

    @Test
    void noNewRoomsMeansNothingMore() throws Exception {
        assertEquals(Map.of(Network.TELEGRAM, new Answer.NothingMore()), load(Network.TELEGRAM));
        assertEquals("true", settings.get("olderChats.telegram.done"));
        assertFalse(loader.hasMore(List.of(Network.TELEGRAM)));
    }

    @Test
    void networksRunOneAtATime() throws Exception {
        reconfigureHeld = new CountDownLatch(1);
        CompletableFuture<Map<Network, Answer>> run = loader.load(List.of(Network.TELEGRAM, Network.DISCORD));
        time.changes.until("Telegram's restart", () -> !eventsStarting("reconfigure").isEmpty());
        assertEquals(List.of("reconfigure TELEGRAM olderChats.telegram.extra=100"), eventsStarting("reconfigure"));
        assertTrue(events.stream().noneMatch(e -> e.contains("DISCORD")), "Discord waits for Telegram");
        reconfigureHeld.countDown();
        var _ = answers(run);
        int telegramDone = events.indexOf("set olderChats.telegram.done=true");
        int discord = firstStarting("reconfigure DISCORD");
        assertTrue(telegramDone >= 0 && discord > telegramDone, events.toString());
    }

    @Test
    void aSecondClickWhileRunningIsIgnored() throws Exception {
        reconfigureHeld = new CountDownLatch(1);
        CompletableFuture<Map<Network, Answer>> run = loader.load(List.of(Network.TELEGRAM));
        time.changes.until("Telegram's restart", () -> !eventsStarting("reconfigure").isEmpty());
        List<String> before = List.copyOf(events);
        assertSame(run, loader.load(List.of(Network.DISCORD)));
        assertEquals(before, events, "nothing new recorded");
        reconfigureHeld.countDown();
        var _ = answers(run);
    }

    @Test
    void aBridgeThatDoesntComeBackGetsItsOldLimitBack() throws Exception {
        settings.put("olderChats.telegram.extra", "100");
        refuse(Network.TELEGRAM, new BridgeDidntAnswer("El puente de Telegram no volvió"));
        Answer a = load(Network.TELEGRAM).get(Network.TELEGRAM);
        assertEquals(new Answer.NotPossible("el puente no volvió; el ajuste anterior está de vuelta"), a);
        assertEquals(List.of("reconfigure TELEGRAM olderChats.telegram.extra=200", "reconfigure TELEGRAM olderChats.telegram.extra=100"),
                eventsStarting("reconfigure"));
        assertEquals("100", settings.get("olderChats.telegram.extra"));
        assertEquals(List.of(), eventsStarting("command"), "no sync-chats after a failed restart");
    }

    @Test
    void aMissingKeyIsNotPossibleAndChangesNothing() throws Exception {
        refuse(Network.DISCORD, new app.unichat.bridge.BridgeRefused("Esta versión del puente de Discord no lo permite"));
        Answer a = load(Network.DISCORD).get(Network.DISCORD);
        assertEquals(new Answer.NotPossible("Esta versión del puente de Discord no lo permite"), a);
        assertEquals(1, eventsStarting("reconfigure").size(), "no second restart");
        assertNull(settings.get("olderChats.discord.extra"));
        assertNull(settings.get("olderChats.discord.at"), "nothing restarted, so no pause either");
    }

    @Test
    void aCommandThatTimesOutAnswersWhatArrived() throws Exception {
        replies.put("sync-chats", () -> {
            for (int i = 0; i < 12; i++) arrive(Network.TELEGRAM, "!t" + i, START.minus(Duration.ofDays(60)));
            throw new TimeoutException("no reply");
        });
        CompletableFuture<Map<Network, Answer>> run = loader.load(List.of(Network.TELEGRAM));
        var _ = run.whenComplete((r, e) -> time.changes.changed());
        time.changes.until("the quiet wait, or an answer", () -> run.isDone() || time.scheduled() > 0);
        assertFalse(run.isDone(), "the timeout is no end: it waits for what is still arriving");
        arrive(Network.TELEGRAM, "!late", START.minus(Duration.ofDays(90)));
        assertEquals(new Answer.Loaded(13), answers(run).get(Network.TELEGRAM));
        assertTrue(marked.contains("!late"), "the room that came after the timeout is marked read too");
        assertNull(settings.get("olderChats.telegram.done"));
    }

    @Test
    void aTimeoutWithNothingArrivedDoesntMarkDone() throws Exception {
        replies.put("sync-chats", () -> { throw new TimeoutException("no reply"); });
        assertEquals(new Answer.NothingMore(), load(Network.TELEGRAM).get(Network.TELEGRAM));
        assertNull(settings.get("olderChats.telegram.done"), "unanswered is no proof there's nothing more: the next click asks again");
    }

    @Test
    void aCancelDuringTheRestartPutsTheOldLimitBack() throws Exception {
        settings.put("olderChats.telegram.extra", "100");
        reconfigureHeld = new CountDownLatch(1);
        CompletableFuture<Map<Network, Answer>> run = loader.load(List.of(Network.TELEGRAM));
        time.changes.until("Telegram's restart", () -> !eventsStarting("reconfigure").isEmpty());
        assertEquals("200", settings.get("olderChats.telegram.extra"), "raised for the restart");
        loader.cancel();
        var _ = run.get();
        assertEquals("100", settings.get("olderChats.telegram.extra"), "the logout came mid-restart: the old limit is back");
    }

    @Test
    void stopEndsARunBeforeTheNextNetwork() throws Exception {
        reconfigureHeld = new CountDownLatch(1);
        CompletableFuture<Map<Network, Answer>> run = loader.load(List.of(Network.TELEGRAM, Network.DISCORD));
        time.changes.until("Telegram's restart", () -> !eventsStarting("reconfigure").isEmpty());
        loader.cancel();
        Map<Network, Answer> a = run.get();
        assertFalse(a.containsKey(Network.DISCORD), a.toString());
        assertTrue(events.stream().noneMatch(e -> e.contains("DISCORD")), events.toString());
        assertEquals(Map.of(), loader.load(List.of(Network.DISCORD)).get(), "a stopped loader runs nothing again");
        assertTrue(events.stream().noneMatch(e -> e.contains("DISCORD")), events.toString());
    }

    @Test
    void aStepThatFailsDoesntStopTheNextNetwork() throws Exception {
        replies.put("sync-chats", () -> { throw new IOException("the server is down"); });
        Map<Network, Answer> a = load(Network.TELEGRAM, Network.DISCORD);
        assertInstanceOf(Answer.NotPossible.class, a.get(Network.TELEGRAM));
        assertEquals(new Answer.NothingMore(), a.get(Network.DISCORD));
        assertEquals(1, eventsStarting("reconfigure DISCORD").size());
    }

    @Test
    void progressSaysTheNetworkStageAndNOfM() throws Exception {
        List<Loading> seen = new CopyOnWriteArrayList<>();
        loader.addListener(() -> seen.add(app.unichat.Present.of(loader.loading())));
        replies.put("sync-chats", () -> {
            arrive(Network.TELEGRAM, "!x", START.minus(Duration.ofDays(4)));
            arrive(Network.TELEGRAM, "!y", START.minus(Duration.ofDays(4)));
            return "Successfully synced";
        });
        var _ = load(Network.TELEGRAM, Network.DISCORD);
        List<String> stages = seen.stream().map(l -> l.network() + " " + l.stage() + " " + l.newChats() + " " + l.done() + "/" + l.total())
                .distinct().toList();
        assertEquals(List.of("TELEGRAM RESTARTING 0 0/2", "TELEGRAM ASKING 0 0/2", "TELEGRAM ASKING 1 0/2", "TELEGRAM ASKING 2 0/2",
                "TELEGRAM ARRIVING 2 0/2", "DISCORD RESTARTING 0 1/2", "DISCORD ARRIVING 0 1/2", "DISCORD DONE 0 2/2"), stages);
        Loading last = app.unichat.Present.of(loader.loading());
        assertEquals(Map.of(Network.TELEGRAM, new Answer.Loaded(2), Network.DISCORD, new Answer.NothingMore()), last.answers());
        assertEquals(Stage.DONE, last.stage());
    }

    @Test
    void nothingRunsWithoutBeingAsked() {
        arrive(Network.TELEGRAM, "!a", START);
        arrivals.roomsChanged();
        var _ = loader.plans(List.copyOf(accounts));
        var _ = loader.hasMore(List.copyOf(accounts));
        assertEquals(List.of(), events, "no command, no reconfigure, no setting written");
        assertNull(loader.loading());
        assertEquals(List.of(), marked);
    }
}
