package app.unichat.matrix;

import app.unichat.Present;
import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Network;
import app.unichat.service.ArchiveWaking.Waking;
import app.unichat.service.PreferencesFile;
import app.unichat.settings.Settings;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.JsonNode;

import static app.unichat.matrix.RestServer.ME;
import static app.unichat.matrix.RestServer.botNotice;
import static app.unichat.matrix.RestServer.botRoom;
import static app.unichat.matrix.RestServer.category;
import static app.unichat.matrix.RestServer.channel;
import static app.unichat.matrix.RestServer.dm;
import static app.unichat.matrix.RestServer.message;
import static app.unichat.matrix.RestServer.server;
import static app.unichat.matrix.RestServer.sync;
import static app.unichat.matrix.RestServer.telegramGroup;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Archived Discord servers and channels rest out of the sync, and wake (servers, filter, catch-up, then the bar) while
 * the archive is open. The sync loop is played here: a request goes out each time the filter changes.
 */
class ArchiveRestTest {
    private static final String GUILD = "111";
    private static final String OTHER_GUILD = "222";

    private final SyncProcessor proc = new SyncProcessor(ME);
    private final PreferencesFile prefs = PreferencesFile.inMemory();
    private final AtomicInteger notices = new AtomicInteger();
    /** The sync loop's requests: each time the filter changes, one goes out with it. */
    private final ExecutorService loop = Executors.newSingleThreadExecutor();
    private final List<String> steps = new CopyOnWriteArrayList<>();
    private boolean discordConnected = true;
    /** Each time the sync loop was asked to go round (its long poll dropped). */
    private final AtomicInteger wakes = new AtomicInteger();
    private volatile @Nullable Thread loopThread;
    /** Interrupts the rounds left for the request after them (a waking not swallowed). */
    private final AtomicInteger interruptsLeft = new AtomicInteger();
    /** Rounds of the loop under way: a future completes inside one (in beforeRequest), before its request is logged. */
    private final AtomicInteger rounds = new AtomicInteger();
    private volatile java.time.Instant now = java.time.Instant.parse("2026-11-23T12:00:00Z");
    private MatrixClient client;
    private RestServer server;
    private SyncFilter filter;
    private ArchiveRest rest;

    @BeforeEach
    void start() throws Exception {
        Settings.get().setNotifyArchived(false);
        Settings.get().set(DiscordServers.PAUSED_GUILDS_KEY, null);
        Settings.get().set(SyncFilter.KEPT_KEY, null);
        proc.setPreferences(prefs);
        server = new RestServer();
        server.botAnswers = text -> process(botNotice(text, notices.incrementAndGet()));
        client = new MatrixClient(server.url(), "tok");
        MatrixCore core = new MatrixCore(client, proc, ME, "example.org", "tok");
        core.running = true;
        core.initialSyncDone = true;
        BotChat bots = new BotChat(core, new MessageSender(core, new MediaDownloader(core.client, core.io)));
        DiscordServers discord = new DiscordServers(core, bots, () -> discordConnected
                ? List.of(new Account(Network.DISCORD, "me", AccountStatus.CONNECTED, "")) : List.of());
        filter = new SyncFilter(client, ME);
        rest = new ArchiveRest(core, filter, discord, (r, rooms) -> { }, server::point);
        rest.onWake(() -> {
            wakes.incrementAndGet();
            loop.execute(this::round);
        });
        rest.useClock(() -> now);
        rest.addListener(() -> {
            Waking w = rest.waking();
            steps.add(w == null ? "HIDDEN" : w.step() + " " + w.done() + "/" + w.total() + (w.server() == null ? "" : " " + w.server()));
            server.changes.changed();
        });
        rest.start();
    }

    @AfterEach
    void stop() {
        rest.stop();
        loop.shutdownNow();
        server.close();
        Settings.get().setNotifyArchived(false);
        Settings.get().set(DiscordServers.PAUSED_GUILDS_KEY, null);
    }

    /** The sync loop's turn: what rests and a part of the catch-up, then its request; again while more is left. */
    private void round() {
        loopThread = Thread.currentThread();
        rounds.incrementAndGet();
        try {
            turns();
        } finally {
            rounds.decrementAndGet();
            server.changes.changed();
        }
    }

    private void turns() {
        boolean more;
        do {
            more = rest.beforeRequest();
            if (Thread.interrupted()) interruptsLeft.incrementAndGet(); // the request after it takes a waking's interrupt, as the real loop's does
            server.log.add("request " + filter.forRequest(false));
        } while (more);
    }

    /** {@code f} done, and the loop's round that completed it ended too (its request logged). */
    private void settled(CompletableFuture<?> f) throws InterruptedException, ExecutionException {
        f.get();
        server.changes.until("the loop's round ended", () -> rounds.get() == 0);
    }

    private void process(String json) {
        try {
            synchronized (proc) {
                proc.process(MatrixClient.JSON.readTree(json), true);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /**
     * An archived server (!s with !c1, !c2), a server that isn't (!s2) with one archived channel (!lone) and one that
     * isn't (!open), an archived Discord DM and an archived Telegram group.
     */
    private void everyKind() {
        process(sync(botRoom(), server("!s:x", GUILD, "!c1:x", "!c2:x"), channel("!c1:x", "c1"), channel("!c2:x", "c2"),
                server("!s2:x", OTHER_GUILD, "!lone:x", "!open:x"), channel("!lone:x", "lone"), channel("!open:x", "open"),
                dm("!dm:x"), telegramGroup("!tg:x")));
        archive(true, "!s:x", "!c1:x", "!c2:x", "!lone:x", "!dm:x", "!tg:x");
    }

    private void archive(boolean on, String... ids) {
        synchronized (proc) {
            for (String id : ids) prefs.chooseQuiet(proc.prefKey(Present.of(proc.room(id))), on);
            proc.settlePreferences();
        }
    }

    private static SortedSet<String> set(String... ids) {
        return new TreeSet<>(List.of(ids));
    }

    /** Waits for the loop to have sent its request with the filter as it is now. */
    private void requestSent() throws InterruptedException, ExecutionException {
        CompletableFuture<Void> f = new CompletableFuture<>();
        loop.execute(() -> f.complete(null));
        f.get();
    }

    @Test
    void theFilterListsExactlyTheArchivedDiscordRooms() throws Exception {
        everyKind();
        rest.update().get();
        assertEquals(List.of(set("!s:x", "!c1:x", "!c2:x", "!lone:x")), server.notRoomsPosted(),
                "the archived server and channel; never a DM, another network or what isn't archived");
        requestSent();
        assertTrue(server.log.contains("request " + idOf(0)), server.log.toString());
        assertTrue(server.catchUps().isEmpty(), "resting needs no catch-up");
    }

    @Test
    void aFirstSyncIsAlwaysWhole() throws Exception {
        // The snapshot kept on disk and its token must hold every room, or a restart would lose what rested.
        everyKind();
        rest.update().get();
        assertEquals(SyncFilter.BASE, filter.forRequest(true));
    }

    @Test
    void noneRestWhileNotifyArchivedIsOn() throws Exception {
        Settings.get().setNotifyArchived(true);
        everyKind();
        rest.archivedChanged().get();
        assertTrue(server.notRoomsPosted().isEmpty(), server.log.toString());
        assertEquals(SyncFilter.BASE, filter.forRequest(false));
        assertTrue(server.log.contains("bot guilds bridging-mode " + GUILD + " nothing"),
                "the bridge as it was before resting: an archived server brings nothing, whatever the setting: " + server.log);
    }

    @Test
    void notifyArchivedOnWakesEverything() throws Exception {
        everyKind();
        rest.archivedChanged().get();
        Settings.get().setNotifyArchived(true);
        server.changes.until("woken", () -> !steps.isEmpty() && steps.getLast().startsWith("DONE"));
        requestSent();
        assertTrue(server.log.contains("request " + SyncFilter.BASE), "nothing left out: " + server.log);
        assertEquals(List.of(set("!s:x", "!c1:x", "!c2:x", "!lone:x")), server.catchUps());
        assertTrue(server.log.stream().noneMatch(l -> l.contains("create-on-message")), "the setting moves no server: " + server.log);
    }

    @Test
    void openingTheArchiveWakesTheFilterAtOnceAndSendsNoBridgeCommand() throws Exception {
        everyKind();
        rest.archivedChanged().get();
        assertTrue(server.log.contains("bot guilds bridging-mode " + GUILD + " nothing"), "an archived server brings nothing: " + server.log);
        int before = server.log.size();

        rest.setArchiveOpen(true).get();
        List<String> waking = new ArrayList<>(server.log.subList(before, server.log.size()));
        int caught = waking.indexOf("catch-up " + set("!s:x", "!c1:x", "!c2:x", "!lone:x"));
        int filtered = waking.indexOf("request " + SyncFilter.BASE);
        assertTrue(caught >= 0 && filtered > caught && waking.subList(0, caught).stream().noneMatch(l -> l.startsWith("request")),
                "the catch-up, then the first request with the filter that wakes them, nothing live between: " + waking);
        JsonNode part = server.filters.values().stream().filter(f -> f.path("room").has("rooms")).findFirst().orElseThrow();
        assertEquals(ArchiveRest.CATCH_UP_LIMIT, part.path("room").path("timeline").path("limit").asInt());
        assertEquals("[\"*\"]", part.path("account_data").path("not_types").toString(), "no account-wide data again");
        assertEquals("[\"*\"]", part.path("presence").path("not_types").toString());
        assertTrue(waking.stream().noneMatch(l -> l.startsWith("bot ")), "no server is woken at the bridge: " + waking);
        assertEquals(List.of("CHATS 0/4", "DONE 4/4"), steps);

        before = server.log.size();
        rest.setArchiveOpen(false).get();
        requestSent();
        List<String> resting = new ArrayList<>(server.log.subList(before, server.log.size()));
        assertTrue(resting.contains("request " + idOf(0)), "the filter they rested with, kept: " + resting);
        assertTrue(resting.stream().noneMatch(l -> l.startsWith("bot ")), "nothing was brought back: " + resting);
        assertTrue(resting.stream().noneMatch(l -> l.startsWith("filter") || l.startsWith("catch-up")), "nothing posted again: " + resting);
    }

    @Test
    void anArchivedServerOpenedInTheArchiveComesBackUntilLeaving() throws Exception {
        everyKind();
        rest.archivedChanged().get();
        rest.setArchiveOpen(true).get();
        int before = server.log.size();
        steps.clear();

        rest.archivedChatOpened("!c1:x").get(); // a channel inside it
        assertEquals(List.of("bot guilds bridging-mode " + GUILD + " create-on-message"), bots(before));
        assertTrue(steps.getFirst().startsWith("SERVER 0/0 ") && steps.getLast().startsWith("DONE"), steps.toString());
        rest.archivedChatOpened("!s:x").get();
        rest.archivedChatOpened("!lone:x").get(); // its server isn't archived: it brings everything already
        assertEquals(1, bots(before).size(), "one command, for the one server: " + server.log);

        rest.setArchiveOpen(false).get();
        assertEquals(List.of("bot guilds bridging-mode " + GUILD + " create-on-message", "bot guilds bridging-mode " + GUILD + " nothing"),
                bots(before), "leaving: the one brought back brings nothing again");
    }

    @Test
    void aChatOpenedOutsideTheArchiveBringsNoServerBack() throws Exception {
        everyKind();
        settled(rest.archivedChanged());
        int before = server.log.size();
        rest.archivedChatOpened("!c1:x").get();
        assertEquals(before, server.log.size(), server.log.toString());
    }

    @Test
    void leavingMidCatchUpFinishesThenRests() throws Exception {
        everyKind();
        rest.update().get();
        server.catchUpGate = new CountDownLatch(1);
        server.missed.put("!c1:x", List.of(message("$late", "@discord_1:example.org", "llegó mientras descansaba", 5000)));
        CompletableFuture<Void> opening = rest.setArchiveOpen(true);
        server.changes.until("the catch-up asked", () -> !server.catchUps().isEmpty());

        CompletableFuture<Void> leaving = rest.setArchiveOpen(false);
        assertFalse(leaving.isDone(), "the catch-up the server still holds comes first");
        server.catchUpGate.countDown();
        opening.get();
        leaving.get();
        requestSent();
        synchronized (proc) {
            assertTrue(Present.of(proc.room("!c1:x")).byId.containsKey("$late"), "what it missed came in");
        }
        assertEquals("request " + idOf(0), server.log.reversed().stream().filter(l -> l.startsWith("request")).findFirst().orElseThrow(),
                "then it rests again: " + server.log);
        int caught = server.log.indexOf("caught up");
        int rested = server.log.lastIndexOf("request " + idOf(0));
        assertTrue(caught >= 0 && rested > caught, "resting waited for the catch-up's answer: " + server.log);
    }

    @Test
    void aFilterOfThousandsOfRoomsIsPostedNotInlined() throws Exception {
        List<String> rooms = new ArrayList<>();
        String[] ids = new String[3000];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = "!ch" + i + ":x";
            rooms.add(channel(ids[i], "ch" + i));
        }
        rooms.add(server("!big:x", GUILD, ids));
        process(sync(rooms.toArray(String[]::new)));
        archive(true, ids);
        rest.update().get();
        assertEquals(1, server.notRoomsPosted().size());
        assertEquals(3000, server.notRoomsPosted().getFirst().size());
        String sent = filter.forRequest(false);
        assertTrue(sent.length() < 40, "the sync names the filter by id: " + sent.length() + " characters");

        rest.update().get();
        assertEquals(1, server.notRoomsPosted().size(), "unchanged: not posted again");
    }

    @Test
    void aCatchUpGoesInPartsCountingHowFar() throws Exception {
        String[] ids = new String[ArchiveRest.CHUNK * 2 + 1];
        List<String> rooms = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            ids[i] = "!p" + i + ":x";
            rooms.add(channel(ids[i], "p" + i));
        }
        rooms.add(server("!s:x", GUILD, ids));
        process(sync(rooms.toArray(String[]::new)));
        archive(true, ids);
        archive(true, "!s:x");
        rest.update().get();
        steps.clear();
        rest.setArchiveOpen(true).get();
        int all = ids.length + 1;
        assertEquals(3, server.catchUps().size(), server.catchUps().toString());
        assertTrue(steps.containsAll(List.of("CHATS 0/" + all, "CHATS " + ArchiveRest.CHUNK + "/" + all,
                "CHATS " + 2 * ArchiveRest.CHUNK + "/" + all, "DONE " + all + "/" + all)), steps.toString());
    }

    @Test
    void aRefusedFilterKeepsWhatRestsAndIsTriedAgainLaterNotEveryRound() throws Exception {
        everyKind();
        rest.update().get();
        String rested = filter.forRequest(false);
        server.refuseFilters = true;
        archive(true, "!open:x");
        rest.update().get();
        assertEquals(rested, filter.forRequest(false), "resting more can wait: what rested still does");
        assertTrue(server.catchUps().isEmpty(), server.log.toString());
        rest.update().get();
        assertEquals(1, server.log.stream().filter(l -> l.equals("filter refused")).count(), "not every round: " + server.log);

        server.refuseFilters = false;
        now = now.plus(Retry.FIRST);
        rest.update().get();
        assertEquals(set("!s:x", "!c1:x", "!c2:x", "!lone:x", "!open:x"), server.notRoomsPosted().getLast());
    }

    @Test
    void aRefusedFilterThatWouldWakeRoomsWakesEverything() throws Exception {
        everyKind();
        rest.update().get();
        server.refuseFilters = true;
        archive(false, "!lone:x");
        rest.update().get();
        assertEquals(SyncFilter.BASE, filter.forRequest(false), "on the safe side: everything comes");
        assertEquals(List.of(set("!s:x", "!c1:x", "!c2:x", "!lone:x")), server.catchUps(), "and what rested catches up");
    }

    @Test
    void theChatOpenInTheWindowNeverRests() throws Exception {
        everyKind();
        rest.update().get();
        rest.setOpenChat("!c1:x").get(); // opened from a search, the archive closed
        assertEquals(set("!s:x", "!c2:x", "!lone:x"), server.notRoomsPosted().getLast());
        assertEquals(List.of(set("!c1:x")), server.catchUps(), "and it caught up on what it missed");
        rest.setOpenChat("!n:x").get();
        assertEquals(idOf(0), filter.forRequest(false), "left: it rests again, with the filter it had");
    }

    @Test
    void aChannelUnderACategoryRestsAndADmInTheBridgesFolderNever() throws Exception {
        process(sync(server("!s3:x", "333", "!cat:x"), category("!cat:x", "cat", "!s3:x", "!deep:x"), channel("!deep:x", "deep"),
                server("!folder:x", "__dms", "!dm2:x"), dm("!dm2:x")));
        archive(true, "!deep:x", "!folder:x", "!dm2:x");
        rest.update().get();
        assertEquals(List.of(set("!deep:x")), server.notRoomsPosted());
    }

    @Test
    void anUnarchiveTagOnARestingRoomWakesItAndTagsNeverTouchATimeline() throws Exception {
        process(sync(server("!s:x", GUILD, "!c1:x"), channel("!c1:x", "c1"), server("!s2:x", OTHER_GUILD, "!lone:x"),
                channel("!lone:x", "lone")));
        archive(true, "!c1:x");
        process(("{'rooms':{'join':{'!lone:x':{'account_data':{'events':[{'type':'m.tag','content':{'tags':{'u.archived':{}}}}]}},"
                + "'!c1:x':{'timeline':{'events':[" + message("$old", "@discord_1:example.org", "antes", 1000).replace('"', '\'')
                + "]}}}}}").replace('\'', '"'));
        rest.update().get();
        assertTrue(server.notRoomsPosted().getLast().contains("!lone:x"), "archived elsewhere: it rests " + server.log);

        server.setTag("!lone:x", "{}"); // unarchived on another device
        server.setTag("!c1:x", "{\"u.archived\":{}}");
        rest.update().get();
        assertTrue(server.log.stream().noneMatch(l -> l.startsWith("tags ")), "not before its time");
        now = now.plus(ArchiveRest.TAGS_EVERY);
        rest.update().get();
        assertTrue(server.log.stream().anyMatch(l -> l.startsWith("tags ")), server.log.toString());
        assertTrue(server.catchUps().contains(set("!lone:x")), "it woke: " + server.log);
        synchronized (proc) {
            assertTrue(Present.of(proc.room("!c1:x")).byId.containsKey("$old"), "a tags sync never resets a timeline");
        }
    }

    @Test
    void aRetryEndsWhenWhatFailedRestsAgainAndReopeningCatchesUpAtOnce() throws Exception {
        everyKind();
        rest.update().get();
        server.failCatchUps = true;
        rest.setArchiveOpen(true).get();
        assertEquals("RETRYING 0/4", steps.getLast());
        rest.setArchiveOpen(false).get();
        assertEquals("HIDDEN", steps.getLast(), "nothing left to retry, nothing to say");

        server.failCatchUps = false;
        int before = server.catchUps().size();
        rest.setArchiveOpen(true).get(); // the clock hasn't moved: no retry waits
        assertEquals(before + 1, server.catchUps().size(), server.log.toString());
        assertEquals("DONE 4/4", steps.getLast());
    }

    @Test
    void leavingAndComingBackBeforeTheBridgeHeardKeepsTheServerBack() throws Exception {
        everyKind();
        rest.archivedChanged().get();
        rest.setArchiveOpen(true).get();
        rest.archivedChatOpened("!c1:x").get();
        CountDownLatch held = new CountDownLatch(1);
        loop.execute(() -> {
            try {
                held.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        CompletableFuture<Void> leaving = rest.setArchiveOpen(false);
        CompletableFuture<Void> back = rest.setArchiveOpen(true);
        held.countDown();
        leaving.get();
        back.get();
        List<String> bot = server.log.stream().filter(l -> l.startsWith("bot ")).toList();
        assertEquals("bot guilds bridging-mode " + GUILD + " create-on-message", bot.getLast(), "back in the archive: still back " + bot);
    }

    @Test
    void theSameRoomsNeverPostANewFilterNotEvenAfterARestart() throws Exception {
        everyKind();
        rest.update().get();
        rest.setArchiveOpen(true).get();
        rest.setArchiveOpen(false).get();
        int before = server.log.size();
        rest.setArchiveOpen(true).get();
        rest.setArchiveOpen(false).get();
        List<String> again = server.log.subList(before, server.log.size());
        assertTrue(again.stream().noneMatch(l -> l.startsWith("filter")), "every filter was posted once: " + again);
        assertTrue(again.stream().anyMatch(l -> l.startsWith("catch-up")), "it did catch up again: " + again);

        SyncFilter restarted = new SyncFilter(client, ME);
        restarted.exclude(set("!s:x", "!c1:x", "!c2:x", "!lone:x"));
        assertEquals(1, server.notRoomsPosted().size(), "kept on this computer: " + server.log);
        assertEquals(idOf(0), restarted.forRequest(false));
    }

    @Test
    void aFailedCatchUpIsTriedAgainLaterAndSaysSo() throws Exception {
        everyKind();
        rest.update().get();
        server.failCatchUps = true;
        rest.setArchiveOpen(true).get();
        assertEquals("RETRYING 0/4", steps.getLast());
        rest.update().get();
        assertEquals(1, server.catchUps().size(), "not before its time: " + server.log);

        server.failCatchUps = false;
        now = now.plus(Retry.FIRST);
        rest.update().get();
        assertEquals(2, server.catchUps().size(), server.log.toString());
        assertEquals("DONE 4/4", steps.getLast());
    }

    @Test
    void withDiscordNotConnectedTheBridgeIsLeftForLater() throws Exception {
        discordConnected = false;
        everyKind();
        rest.archivedChanged().get();
        assertTrue(server.log.stream().noneMatch(l -> l.startsWith("bot ")), server.log.toString());
        assertEquals(1, server.notRoomsPosted().size(), "the filter doesn't wait for the bridge");
    }

    @Test
    void unarchivingWakesOnlyThatRoomForGood() throws Exception {
        everyKind();
        rest.update().get();
        archive(false, "!lone:x");
        rest.update().get();
        assertEquals(List.of(set("!lone:x")), server.catchUps());
        assertEquals(set("!s:x", "!c1:x", "!c2:x"), server.notRoomsPosted().getLast());
        archive(true, "!open:x");
        rest.update().get();
        assertEquals(set("!s:x", "!c1:x", "!c2:x", "!open:x"), server.notRoomsPosted().getLast(), "archived: it rests at once");
        assertEquals(1, server.catchUps().size(), "resting needs no catch-up");
        assertTrue(steps.stream().noneMatch(s -> s.startsWith("SERVER")), "no server woke: " + steps);
    }

    /** The bot commands from {@code from} in the log on. */
    private List<String> bots(int from) {
        return List.copyOf(server.log).subList(from, server.log.size()).stream().filter(l -> l.startsWith("bot ")).toList();
    }

    /** The id of the {@code n}th filter posted (they're numbered as the server answers them). */
    private String idOf(int n) {
        int seen = 0;
        for (String id : server.filters.keySet().stream().sorted((a, b) -> Integer.compare(num(a), num(b))).toList()) {
            if (!Present.of(server.filters.get(id)).path("room").has("not_rooms")) continue;
            if (seen++ == n) return id;
        }
        throw new AssertionError("no filter " + n + ": " + server.log);
    }

    private static int num(String id) { return Integer.parseInt(id.substring(1)); }

    @Test
    void roomsThatStartRestingHaveTheirTagsAskedFromTheMainSyncsPoint() throws Exception {
        everyKind();
        rest.update().get();
        now = now.plus(ArchiveRest.TAGS_EVERY);
        rest.update().get();
        assertTrue(lastTags().matches(".*since=main\\d+"), "the first: from where the main sync left them " + server.log);
        now = now.plus(ArchiveRest.TAGS_EVERY);
        rest.update().get();
        assertTrue(lastTags().matches(".*since=t\\d+"), "then from where the last tags sync was: " + server.log);
        int tagged = server.log.size();

        archive(true, "!open:x"); // a tag on it may change before the next tags sync, while the main sync leaves it out
        rest.update().get();
        assertTrue(List.copyOf(server.log).subList(tagged, server.log.size()).stream().anyMatch(l -> l.matches(
                "tags \\[!c1:x, !c2:x, !lone:x, !s:x\\] since=t\\d+")), "those resting already first, from their own point: " + server.log);
        now = now.plus(ArchiveRest.TAGS_EVERY);
        rest.update().get();
        String last = lastTags();
        assertTrue(last.contains("!open:x") && last.matches(".*since=main\\d+"), "from where the main sync left it: " + last);
    }

    @Test
    void aTagChangedOnARoomAlreadyRestingIsHeardWhenMoreStartResting() throws Exception {
        everyKind();
        rest.update().get();
        now = now.plus(ArchiveRest.TAGS_EVERY);
        rest.update().get(); // a tags sync: from here on, only what changes after it
        server.setTag("!c1:x", "{\"u.archived\":{},\"m.favourite\":{}}"); // favourite on the phone
        archive(true, "!open:x"); // then more rests, before the next tags sync
        rest.update().get();
        now = now.plus(ArchiveRest.TAGS_EVERY);
        rest.update().get();
        synchronized (proc) {
            assertTrue(Present.of(proc.room("!c1:x")).chat.favorite(), "heard, not skipped by the jump to the main sync's point: " + server.log);
        }
    }

    @Test
    void aForcedTagsSyncThatFailsLeavesTheNextOneBringingEverything() throws Exception {
        everyKind();
        rest.update().get();
        now = now.plus(ArchiveRest.TAGS_EVERY);
        server.setTag("!open:x", "{\"m.favourite\":{}}"); // on a room about to rest, before the round's tags sync
        archive(true, "!open:x");
        server.tagsToAnswer.set(1); // the due tags sync works, the forced one after it doesn't
        rest.update().get();
        assertTrue(server.log.contains("tags failed"), server.log.toString());
        server.tagsToAnswer.set(Integer.MAX_VALUE);
        now = now.plus(ArchiveRest.TAGS_EVERY);
        rest.update().get();
        synchronized (proc) {
            assertTrue(Present.of(proc.room("!open:x")).chat.favorite(), "heard: the next tags sync started over: " + server.log);
        }
    }

    @Test
    void wakingTheComputerMidTagsSyncIsNotLostAndAsksAgain() throws Exception {
        everyKind();
        rest.update().get();
        now = now.plus(ArchiveRest.TAGS_EVERY);
        server.tagsGate = new CountDownLatch(1);
        CompletableFuture<Void> round = rest.update();
        server.changes.until("the tags sync asked", () -> server.log.stream().anyMatch(l -> l.startsWith("tags ")));
        int before = interruptsLeft.get();
        Present.of(loopThread).interrupt();
        settled(round); // done inside beforeRequest: the loop takes the interrupt just after, on its own thread
        assertEquals(before + 1, interruptsLeft.get(), "left for the request after it");
        server.tagsGate.countDown();
        rest.update().get(); // the clock unmoved
        assertEquals(2, server.log.stream().filter(l -> l.startsWith("tags ")).count(), "asked again at once: " + server.log);
    }

    @Test
    void wakingTheComputerMidFilterPostIsNoRefusal() throws Exception {
        everyKind();
        rest.update().get();
        server.filterGate = new CountDownLatch(1);
        archive(true, "!open:x");
        CompletableFuture<Void> round = rest.update();
        int asked = (int) server.log.stream().filter(l -> l.equals("filter asked")).count();
        server.changes.until("the post asked", () -> server.log.stream().filter(l -> l.equals("filter asked")).count() > asked);
        int before = interruptsLeft.get();
        Present.of(loopThread).interrupt();
        settled(round); // done inside beforeRequest: the loop takes the interrupt just after, on its own thread
        assertEquals(before + 1, interruptsLeft.get(), "left for the request after it");
        server.filterGate.countDown();
        rest.update().get(); // the clock unmoved: no retry waits
        assertEquals(set("!s:x", "!c1:x", "!c2:x", "!lone:x", "!open:x"), server.notRoomsPosted().getLast(), server.log.toString());
        String id = server.filters.entrySet().stream().filter(e -> e.getValue().path("room").path("not_rooms").size() == 5)
                .map(java.util.Map.Entry::getKey).max(java.util.Comparator.comparingInt(ArchiveRestTest::num)).orElseThrow();
        assertEquals(id, filter.forRequest(false), "posted again and used");
    }

    private String lastTags() {
        return server.log.reversed().stream().filter(l -> l.startsWith("tags ")).findFirst().orElseThrow();
    }

    @Test
    void aSideSyncThatHangsGivesUpAfterItsTimeout() throws Exception {
        everyKind();
        rest.update().get();
        rest.useTimeout(java.time.Duration.ZERO);
        server.catchUpGate = new CountDownLatch(1); // the server never answers
        rest.setArchiveOpen(true).get();
        assertEquals("RETRYING 0/4", steps.getLast(), "the round goes on: " + steps);
    }

    @Test
    void openingAChatThatNeitherRestsNorRestedLeavesTheLongPollAlone() throws Exception {
        everyKind();
        rest.update().get();
        int before = wakes.get();
        rest.setOpenChat("!open:x").get();
        rest.setOpenChat("!dm:x").get();
        assertEquals(before, wakes.get(), "not archived, or a DM: nothing rests or wakes");
        rest.setOpenChat("!c1:x").get();
        assertEquals(before + 1, wakes.get(), "a resting one wakes");
        rest.setOpenChat("!tg:x").get();
        assertEquals(before + 2, wakes.get(), "and the one before rests again");
    }

    @Test
    void wakingTheComputerMidCatchUpIsNoFailure() throws Exception {
        everyKind();
        rest.update().get();
        server.catchUpGate = new CountDownLatch(1);
        CompletableFuture<Void> opening = rest.setArchiveOpen(true);
        server.changes.until("the catch-up asked", () -> !server.catchUps().isEmpty());
        Present.of(loopThread).interrupt(); // what MatrixChatService.wake does to drop what the sync thread waits on
        server.changes.until("asked again, the clock unmoved", () -> server.catchUps().size() == 2);
        server.catchUpGate.countDown();
        opening.get();
        assertTrue(steps.stream().noneMatch(st -> st.startsWith("RETRYING")), "not a failure: " + steps);
        assertEquals("DONE 4/4", steps.getLast(), steps.toString());
        assertEquals(2, server.catchUps().size(), server.log.toString());
    }
}
