package app.unichat.matrix;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Network;
import app.unichat.service.ArchiveWaking.Step;
import app.unichat.service.ArchiveWaking.Waking;
import app.unichat.service.PreferencesFile;
import app.unichat.settings.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static app.unichat.matrix.RestServer.*;
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
    private RestServer server;
    private SyncFilter filter;
    private ArchiveRest rest;

    @BeforeEach
    void start() throws Exception {
        Settings.get().setNotifyArchived(false);
        Settings.get().set(DiscordServers.PAUSED_GUILDS_KEY, null);
        proc.setPreferences(prefs);
        server = new RestServer();
        server.botAnswers = text -> process(botNotice(text, notices.incrementAndGet()));
        MatrixClient client = new MatrixClient(server.url(), "tok");
        MatrixCore core = new MatrixCore(client, proc, ME, "example.org", "tok");
        core.running = true;
        core.initialSyncDone = true;
        BotChat bots = new BotChat(core, new MessageSender(core, null));
        DiscordServers discord = new DiscordServers(core, bots, () -> discordConnected
                ? List.of(new Account(Network.DISCORD, "me", AccountStatus.CONNECTED, "")) : List.of());
        filter = new SyncFilter(client, ME);
        filter.onChange(() -> loop.execute(() -> server.log.add("request " + filter.forRequest(false))));
        rest = new ArchiveRest(core, filter, discord, r -> { });
        rest.addListener(() -> {
            Waking w = rest.waking();
            steps.add(w.step() + " " + w.done() + "/" + w.total());
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
            for (String id : ids) prefs.chooseQuiet(proc.prefKey(proc.room(id)), on);
            proc.settlePreferences();
        }
    }

    private static TreeSet<String> set(String... ids) {
        return new TreeSet<>(List.of(ids));
    }

    /** Waits for the loop to have sent its request with the filter as it is now. */
    private void requestSent() throws Exception {
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
        rest.update().get();
        assertTrue(server.notRoomsPosted().isEmpty(), server.log.toString());
        assertEquals(SyncFilter.BASE, filter.forRequest(false));
    }

    @Test
    void notifyArchivedOnWakesEverything() throws Exception {
        everyKind();
        rest.update().get();
        Settings.get().setNotifyArchived(true);
        server.changes.until("woken", () -> !steps.isEmpty() && steps.getLast().startsWith("DONE"));
        requestSent();
        assertTrue(server.log.contains("request " + SyncFilter.BASE), "nothing left out: " + server.log);
        assertEquals(List.of(set("!s:x", "!c1:x", "!c2:x", "!lone:x")), server.catchUps());
    }

    @Test
    void openingTheArchiveWakesThemInOrderAndLeavingPutsThemBackToRest() throws Exception {
        everyKind();
        rest.update().get();
        assertTrue(server.log.contains("bot guilds bridging-mode " + GUILD + " nothing"), "an archived server brings nothing: " + server.log);
        int before = server.log.size();

        rest.setArchiveOpen(true).get();
        List<String> waking = new ArrayList<>(server.log.subList(before, server.log.size()));
        int bridge = waking.indexOf("bot guilds bridging-mode " + GUILD + " create-on-message");
        int filtered = waking.indexOf("request " + SyncFilter.BASE);
        int caught = waking.indexOf("catch-up " + set("!s:x", "!c1:x", "!c2:x", "!lone:x"));
        assertTrue(bridge >= 0 && bridge < filtered && filtered < caught, "the bridge, the filter, then the catch-up: " + waking);
        assertEquals(List.of("SERVERS 0/1", "SERVERS 1/1", "CHATS 0/4", "CHATS 4/4", "DONE 4/4"), steps);
        assertFalse(waking.stream().anyMatch(l -> l.startsWith("bot ") && l.contains(OTHER_GUILD)), "a server that isn't archived stays: " + waking);

        before = server.log.size();
        rest.setArchiveOpen(false).get();
        requestSent();
        List<String> resting = new ArrayList<>(server.log.subList(before, server.log.size()));
        assertTrue(resting.contains("request " + idOf(0)), "the filter they rested with, kept: " + resting);
        assertTrue(resting.contains("bot guilds bridging-mode " + GUILD + " nothing"), resting.toString());
        assertTrue(resting.stream().noneMatch(l -> l.startsWith("filter") || l.startsWith("catch-up")), "nothing posted again: " + resting);
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
            assertTrue(proc.room("!c1:x").byId.containsKey("$late"), "what it missed came in");
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
                "CHATS " + 2 * ArchiveRest.CHUNK + "/" + all, "CHATS " + all + "/" + all, "DONE " + all + "/" + all)), steps.toString());
    }

    @Test
    void aFilterTheServerRefusedRestsNothing() throws Exception {
        everyKind();
        rest.update().get();
        server.refuseFilters = true;
        archive(true, "!open:x");
        rest.update().get();
        requestSent();
        assertEquals(SyncFilter.BASE, filter.forRequest(false), "on the safe side: everything comes");
        assertEquals(List.of(set("!s:x", "!c1:x", "!c2:x", "!lone:x")), server.catchUps(), "and what rested catches up");
    }

    @Test
    void withDiscordNotConnectedTheBridgeIsLeftForLater() throws Exception {
        discordConnected = false;
        everyKind();
        rest.update().get();
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
        assertTrue(steps.stream().noneMatch(s -> s.startsWith("SERVERS")), "no server woke: " + steps);
    }

    /** The id of the {@code n}th filter posted (they're numbered as the server answers them). */
    private String idOf(int n) {
        int seen = 0;
        for (String id : server.filters.keySet().stream().sorted((a, b) -> Integer.compare(num(a), num(b))).toList()) {
            if (!server.filters.get(id).path("room").has("not_rooms")) continue;
            if (seen++ == n) return id;
        }
        throw new AssertionError("no filter " + n + ": " + server.log);
    }

    private static int num(String id) { return Integer.parseInt(id.substring(1)); }
}
