package app.unichat.matrix;

import app.unichat.model.Chat;
import app.unichat.service.ChatService;
import app.unichat.service.PreferencesFile;
import app.unichat.service.PreferencesFile.Flag;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Preferences are decided by the file: what /sync says is only input, and only the mute rule goes back to the server. */
class PreferencesSyncTest {
    private static final String ME = "@me:x";
    private static final String ROOM = "!a:x";
    private static final String TELEGRAM_7 = "telegram:7";
    private static final Instant T = Instant.parse("2030-01-02T03:04:05Z");

    @TempDir Path dir;
    private PreferencesFile prefs;
    private SyncProcessor proc;

    @BeforeEach
    void setUp() {
        prefs = open();
        proc = processor(prefs);
    }

    private final List<PreferencesFile> opened = new ArrayList<>();

    /** Like a restart: what the earlier ones hold is on disk first. */
    private PreferencesFile open() {
        flushAll();
        PreferencesFile p = new PreferencesFile(dir.resolve("preferences.json"), dir.resolve("server-seen.json"));
        opened.add(p);
        return p;
    }

    @AfterEach
    void flushAll() {
        opened.forEach(PreferencesFile::flush);
    }

    private String saved() throws IOException {
        flushAll();
        return Files.readString(dir.resolve("preferences.json"));
    }

    private static SyncProcessor processor(PreferencesFile file) {
        SyncProcessor p = new SyncProcessor(ME);
        p.setPreferences(file);
        return p;
    }

    private static JsonNode json(String s) {
        try {
            return MatrixClient.JSON.readTree(s);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String bridge(String protocol, String channel) {
        return "{\"type\":\"uk.half-shot.bridge\",\"state_key\":\"b\",\"sender\":\"" + ME + "\",\"content\":{\"protocol\":{\"id\":\""
                + protocol + "\"},\"channel\":{\"id\":\"" + channel + "\"}}}";
    }

    private static String tags(String... names) {
        StringBuilder in = new StringBuilder();
        for (String n : names) in.append(in.isEmpty() ? "" : ",").append('"').append(n).append("\":{}");
        return "{\"type\":\"m.tag\",\"content\":{\"tags\":{" + in + "}}}";
    }

    private static String reminder(Instant at) {
        return "{\"type\":\"app.unichat.reminder\",\"content\":{\"ts\":" + at.toEpochMilli() + "}}";
    }

    /** One joined room with these state and room account data events. */
    private static JsonNode room(String id, String state, String accountData) {
        return json("{\"rooms\":{\"join\":{\"" + id + "\":{\"state\":{\"events\":[" + state + "]},\"account_data\":{\"events\":["
                + accountData + "]}}}}}");
    }

    private static JsonNode rules(String... mutedRooms) {
        StringBuilder in = new StringBuilder();
        for (String id : mutedRooms) {
            in.append(in.isEmpty() ? "" : ",").append("{\"rule_id\":\"").append(id).append("\",\"enabled\":true,\"actions\":[]}");
        }
        return json("{\"account_data\":{\"events\":[{\"type\":\"m.push_rules\",\"content\":{\"global\":{\"room\":[" + in + "]}}}]}}");
    }

    private Chat chat(String id) { return proc.room(id).chat; }

    // ---- the brief's cases

    @Test
    void tagsOnlyEnterThroughTheFile() {
        proc.process(room(ROOM, "", tags("m.favourite")), true);
        assertTrue(chat(ROOM).favorite());
        prefs.choose(chat(ROOM).prefKey(), Flag.FAVORITE, false);
        proc.process(room(ROOM, "", tags("m.favourite")), true);
        assertFalse(chat(ROOM).favorite());
    }

    @Test
    void prefKeyUsesTheBridgeIdentity() {
        proc.process(room(ROOM, bridge("telegram", "7"), ""), true);
        assertEquals(TELEGRAM_7, chat(ROOM).prefKey());
        proc.process(room("!plain:x", "", ""), true);
        assertEquals("matrix:!plain:x", chat("!plain:x").prefKey());
    }

    @Test
    void sameChatOnAnotherServerGetsItsPrefs() {
        prefs.choose(TELEGRAM_7, Flag.ARCHIVED, true);
        SyncProcessor other = processor(prefs);
        other.process(room("!other:y", bridge("telegram", "7"), ""), true);
        assertTrue(other.room("!other:y").chat.archived());
    }

    @Test
    void aChatThatArrivesLaterGetsItsPrefs() {
        prefs.choose("matrix:" + ROOM, Flag.ARCHIVED, true);
        proc.process(room("!else:x", "", ""), true);
        proc.process(room(ROOM, "", ""), true);
        assertTrue(chat(ROOM).archived());
    }

    @Test
    void bridgeStateArrivingLaterRekeys() {
        proc.process(room(ROOM, "", ""), true);
        String folder = prefs.createFolder("Trabajo");
        prefs.setInFolder(folder, "matrix:" + ROOM, true);
        prefs.choose("matrix:" + ROOM, Flag.PINNED, true);

        proc.process(room(ROOM, bridge("telegram", "7"), ""), true);

        assertEquals(TELEGRAM_7, chat(ROOM).prefKey());
        assertTrue(prefs.current(TELEGRAM_7).pinned());
        assertFalse(prefs.current("matrix:" + ROOM).pinned());
        assertEquals(List.of(TELEGRAM_7), prefs.folders().get(0).chatKeys());
        assertTrue(chat(ROOM).pinned());
    }

    @Test
    void noAccountDataChangesNothing() {
        prefs.follow(TELEGRAM_7, Flag.ARCHIVED, true);
        proc.process(room(ROOM, bridge("telegram", "7"), ""), true);
        assertTrue(chat(ROOM).archived());
        proc.process(room(ROOM, "", ""), true);
        assertTrue(chat(ROOM).archived());
    }

    @Test
    void aMuteChosenHereIsAppliedToTheServer() {
        prefs.choose("matrix:" + ROOM, Flag.MUTED, true);
        proc.process(room(ROOM, "", ""), true);
        proc.process(rules(), true);
        assertTrue(chat(ROOM).muted());
        assertEquals(Map.of(ROOM, true), proc.mutesToApply());
        proc.process(rules(ROOM), true);
        assertEquals(Map.of(), proc.mutesToApply());
    }

    @Test
    void aMuteFromThePhoneIsNotPushedBack() {
        proc.process(room(ROOM, "", ""), true);
        proc.process(rules(ROOM), true);
        assertTrue(chat(ROOM).muted());
        assertEquals(Map.of(), proc.mutesToApply());
    }

    // ---- nothing heard, and what a new server's silence means

    @Test
    void anUnmuteChosenHereIsAppliedToTheServer() {
        proc.process(room(ROOM, "", ""), true);
        proc.process(rules(ROOM), true);
        prefs.choose(chat(ROOM).prefKey(), Flag.MUTED, false);
        proc.process(rules(ROOM), true);
        assertFalse(chat(ROOM).muted());
        assertEquals(Map.of(ROOM, false), proc.mutesToApply());
    }

    @Test
    void withNoRulesHeardNothingIsWritten() {
        prefs.choose("matrix:" + ROOM, Flag.MUTED, true);
        proc.process(room(ROOM, "", ""), true);
        assertTrue(chat(ROOM).muted());
        assertEquals(Map.of(), proc.mutesToApply());
    }

    @Test
    void emptyTagsAreKnownFalseOnlyIfThisServerSaidTrue() {
        proc.process(room(ROOM, "", tags("u.archived", "u.pinned", "m.lowpriority")), true);
        assertTrue(chat(ROOM).archived() && chat(ROOM).pinned() && chat(ROOM).lowPriority());
        proc.process(room(ROOM, "", tags()), true);
        assertFalse(chat(ROOM).archived() || chat(ROOM).pinned() || chat(ROOM).lowPriority());

        prefs.follow("matrix:!new:x", Flag.ARCHIVED, true);
        proc.process(room("!new:x", "", tags()), true);
        assertTrue(chat("!new:x").archived(), "a server that never said true can't erase it");
    }

    @Test
    void aRoomArrivingAfterTheRulesKnowsItsMute() {
        proc.process(rules("!later:x"), true);
        proc.process(room("!later:x", "", ""), true);
        assertTrue(chat("!later:x").muted());
    }

    @Test
    void chatsChangedIsReportedWhenTheRulesOnlyChangeTheFile() {
        proc.process(room(ROOM, "", ""), true);
        assertTrue(proc.process(rules(ROOM), true).chatsChanged);
    }

    @Test
    void aMuteCopiedToANewServerIsAppliedThere() throws IOException {
        Files.writeString(dir.resolve("preferences.json"),
                "{\"version\":1,\"chats\":{\"telegram:7\":{\"muted\":true,\"fromServer\":[\"muted\"]}},\"folders\":[]}");
        proc = processor(open());
        proc.process(room(ROOM, bridge("telegram", "7"), ""), true);
        proc.process(rules(), true);
        assertTrue(chat(ROOM).muted());
        assertEquals(Map.of(ROOM, true), proc.mutesToApply(), "or it would count unread for ever");
    }

    @Test
    void anUnmuteFromThePhoneIsFollowedAndNotPushedBack() {
        proc.process(room(ROOM, "", ""), true);
        proc.process(rules(ROOM), true);
        proc.process(rules(), true);
        assertFalse(chat(ROOM).muted());
        assertEquals(Map.of(), proc.mutesToApply());
    }

    // ---- reminders

    @Test
    void aServerReminderOnlyEntersWhenTheFileHasNone() {
        proc.process(room(ROOM, "", reminder(T)), true);
        assertEquals(T, chat(ROOM).reminderAt());
        prefs.chooseReminder(chat(ROOM).prefKey(), null);
        proc.process(room(ROOM, "", reminder(T)), true);
        assertNull(chat(ROOM).reminderAt());
    }

    // ---- the write in flight and its retries

    @Test
    void aRoomStaysOutWhileItsWriteIsInFlight() {
        prefs.choose("matrix:" + ROOM, Flag.MUTED, true);
        proc.process(room(ROOM, "", ""), true);
        proc.process(rules(), true);
        proc.muteWriteStarted(ROOM);
        assertEquals(Map.of(), proc.mutesToApply());
    }

    @Test
    void aFinishedWriteCountsAsHeardSoItIsNotRepeated() {
        prefs.choose("matrix:" + ROOM, Flag.MUTED, true);
        proc.process(room(ROOM, "", ""), true);
        proc.process(rules(), true);
        proc.muteWriteStarted(ROOM);
        proc.muteWriteFinished(ROOM, true, true, T);
        assertEquals(Map.of(), proc.mutesToApply(T.plus(Duration.ofDays(1))));
    }

    @Test
    void aFailedWriteIsRetriedLaterAndLaterNotOnEachSync() {
        prefs.choose("matrix:" + ROOM, Flag.MUTED, true);
        proc.process(room(ROOM, "", ""), true);
        proc.process(rules(), true);
        proc.muteWriteStarted(ROOM);
        proc.muteWriteFinished(ROOM, true, false, T);
        assertEquals(Map.of(), proc.mutesToApply(T.plusSeconds(30)));
        assertEquals(Map.of(ROOM, true), proc.mutesToApply(T.plusSeconds(61)));

        proc.muteWriteStarted(ROOM);
        proc.muteWriteFinished(ROOM, true, false, T);
        assertEquals(Map.of(), proc.mutesToApply(T.plusSeconds(61)), "waits longer each time");
        assertEquals(Map.of(ROOM, true), proc.mutesToApply(T.plusSeconds(121)));
    }

    // ---- the cached /sync replayed at start

    private static JsonNode cachedSync(String room, String tags, String... mutedRooms) {
        StringBuilder rules = new StringBuilder();
        for (String id : mutedRooms) {
            rules.append(rules.isEmpty() ? "" : ",").append("{\"rule_id\":\"").append(id).append("\",\"enabled\":true,\"actions\":[]}");
        }
        return json("{\"rooms\":{\"join\":{\"" + room + "\":{\"account_data\":{\"events\":[" + tags
                + "]}}}},\"account_data\":{\"events\":[{\"type\":\"m.push_rules\",\"content\":{\"global\":{\"room\":[" + rules + "]}}}]}}");
    }

    @Test
    void aReplayedSyncPreviewsWhatTheFileDoesNotHaveAndWritesNothing() throws IOException {
        prefs.choose("matrix:" + ROOM, Flag.FAVORITE, true);
        prefs.choose("matrix:" + ROOM, Flag.PINNED, false);
        String before = saved();
        proc.replay(cachedSync(ROOM, tags("u.archived", "u.pinned"), ROOM));
        assertTrue(chat(ROOM).favorite(), "the file's value");
        assertFalse(chat(ROOM).pinned(), "the file wins over the cache");
        assertTrue(chat(ROOM).archived() && chat(ROOM).muted(), "first start after upgrading: no flash in the inbox");
        assertEquals(before, saved());
        assertFalse(Files.exists(dir.resolve("server-seen.json")), "no seen memory from old news");
        assertFalse(prefs.current("matrix:" + ROOM).archived());
    }

    @Test
    void aReplayedReminderIsPreviewedUnlessTheFileHasOne() {
        proc.replay(room(ROOM, "", reminder(T)));
        assertEquals(T, chat(ROOM).reminderAt());
        prefs.chooseReminder("matrix:" + ROOM, null);
        proc.replay(room(ROOM, "", reminder(T)));
        assertNull(chat(ROOM).reminderAt());
    }

    @Test
    void aChoiceWhileThePreviewIsUpKeepsThePreviewOfTheRest() throws Exception {
        MatrixChatService svc = service();
        svc.processor().replay(cachedSync(ROOM, tags("u.archived")));
        svc.setFavorite(ROOM, true).get(10, TimeUnit.SECONDS);
        assertTrue(svc.chat(ROOM).archived(), "the chat must not flash into the inbox");
        assertTrue(svc.chat(ROOM).favorite());
    }

    @Test
    void aStaleFalseInTheCacheErasesNothingBeforeTheServerAnswers() {
        proc.process(room(ROOM, "", tags("u.archived")), true);
        assertTrue(chat(ROOM).archived());

        PreferencesFile restarted = open();
        SyncProcessor next = processor(restarted);
        next.replay(room(ROOM, "", tags()));
        assertTrue(next.room(ROOM).chat.archived());
        assertTrue(restarted.current("matrix:" + ROOM).archived());

        next.process(room(ROOM, "", tags("u.archived")), true);
        assertTrue(next.room(ROOM).chat.archived(), "the catch-up has the tag that came after the cache");
    }

    @Test
    void theCatchUpBuildsOnWhatTheCacheSaid() {
        proc.replay(room(ROOM, "", tags("u.archived")));
        assertTrue(chat(ROOM).archived(), "previewed");
        assertFalse(prefs.current("matrix:" + ROOM).archived(), "not in the file yet");
        proc.process(json("{\"rooms\":{}}"), true);
        assertTrue(chat(ROOM).archived());
        assertTrue(prefs.current("matrix:" + ROOM).archived(), "the server had nothing newer: the cache was right");
    }

    @Test
    void aRejectedCacheTokenForgetsWhatTheCacheSaid() {
        proc.replay(cachedSync(ROOM, tags("u.archived"), ROOM));
        assertTrue(chat(ROOM).archived());
        proc.forgetServerValues();
        proc.process(json("{\"rooms\":{}}"), true);
        assertFalse(chat(ROOM).archived());
        assertFalse(chat(ROOM).muted());
        assertFalse(prefs.current("matrix:" + ROOM).archived());
    }

    // ---- through the service: what's chosen and what goes to the server

    private HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile int ruleStatus = 200;

    private MatrixChatService service() throws IOException { return service(prefs, "DEVICE"); }

    private MatrixChatService service(PreferencesFile file, String deviceId) throws IOException {
        if (server != null) return new MatrixChatService(url(), ME, "t", file, deviceId);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String call = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            calls.add(call);
            byte[] b = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(call.contains("/pushrules/") ? ruleStatus : 200, b.length);
            try (var o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        server.start();
        return new MatrixChatService(url(), ME, "t", file, deviceId);
    }

    private String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private List<String> writes() {
        return calls.stream().filter(c -> !c.startsWith("GET ")).toList();
    }

    @Test
    void choicesGoToTheFileAndNotToTheServer() throws Exception {
        MatrixChatService svc = service();
        svc.processor().process(room(ROOM, "", ""), true);
        Chat c = svc.chat(ROOM);
        svc.setFavorite(ROOM, true).get(10, TimeUnit.SECONDS);
        svc.setLowPriority(ROOM, true).get(10, TimeUnit.SECONDS);
        svc.setPinned(ROOM, true).get(10, TimeUnit.SECONDS);
        svc.setReminder(ROOM, T).get(10, TimeUnit.SECONDS);
        assertTrue(c.favorite() && c.lowPriority() && c.pinned());
        assertEquals(T, c.reminderAt());
        assertEquals(List.of(), writes());

        var saved = open().current("matrix:" + ROOM);
        assertTrue(saved.favorite() && saved.lowPriority() && saved.pinned());
        assertEquals(T, saved.reminder());
    }

    @Test
    void quietingWritesOnlyTheMuteRule() throws Exception {
        MatrixChatService svc = service();
        svc.processor().process(room(ROOM, "", ""), true);
        AtomicInteger fired = new AtomicInteger();
        svc.addListener(changes(fired));
        svc.setArchived(ROOM, true).get(10, TimeUnit.SECONDS);
        assertTrue(svc.chat(ROOM).archived() && svc.chat(ROOM).muted());
        assertTrue(fired.get() > 0);
        assertEquals(List.of("PUT /_matrix/client/v3/pushrules/global/room/!a:x"), writes());

        calls.clear();
        svc.setMuted(ROOM, false).get(10, TimeUnit.SECONDS);
        assertFalse(svc.chat(ROOM).archived() || svc.chat(ROOM).muted());
        assertEquals(List.of("DELETE /_matrix/client/v3/pushrules/global/room/!a:x",
                "DELETE /_matrix/client/v3/pushrules/global/override/!a:x"), writes());
    }

    @Test
    void anUnknownChatStillFails() throws Exception {
        MatrixChatService svc = service();
        assertTrue(svc.setFavorite("!nope:x", true).isCompletedExceptionally());
        assertTrue(svc.setMuted("!nope:x", true).isCompletedExceptionally());
    }

    @Test
    void markedUnreadStillGoesToTheServer() throws Exception {
        MatrixChatService svc = service();
        svc.processor().process(room(ROOM, "", ""), true);
        svc.setMarkedUnread(ROOM, true).get(10, TimeUnit.SECONDS);
        assertEquals(1, writes().stream().filter(c -> c.contains("/account_data/m.marked_unread")).count());
    }

    @Test
    void aFailedMuteWriteKeepsTheChoiceAndIsRetried() throws Exception {
        MatrixChatService svc = service();
        ruleStatus = 500;
        svc.processor().process(room(ROOM, "", ""), true);
        svc.processor().process(rules(), true);
        svc.setMuted(ROOM, true).get(30, TimeUnit.SECONDS);
        assertTrue(svc.chat(ROOM).muted(), "not reverted");
        assertTrue(prefs.current("matrix:" + ROOM).muted());
        assertEquals(Map.of(), svc.processor().mutesToApply());
        assertEquals(Map.of(ROOM, true), svc.processor().mutesToApply(Instant.now().plus(Duration.ofHours(2))));
    }

    @Test
    void aMutedOnThePhoneChatIsArchivedAsAFollowedValue() throws Exception {
        MatrixChatService svc = service();
        svc.processor().process(room(ROOM, "", ""), true);
        svc.processor().process(rules(ROOM), true);
        AtomicInteger fired = new AtomicInteger();
        svc.addListener(changes(fired));
        svc.applyQuietRules();
        assertTrue(svc.chat(ROOM).archived());
        assertEquals(1, fired.get());
        assertEquals(List.of(), writes());
        assertFalse(prefs.current("matrix:" + ROOM).muteChosenHere());

        svc.processor().process(room(ROOM, "", tags()), true);
        assertTrue(svc.chat(ROOM).archived(), "no server said it was: nothing erases it");
    }

    @Test
    void aPhoneMuteCannotOverrideAPersonsChoice() throws Exception {
        MatrixChatService svc = service();
        prefs.choose("matrix:" + ROOM, Flag.ARCHIVED, false);
        svc.processor().process(room(ROOM, "", ""), true);
        svc.processor().process(rules(ROOM), true);
        svc.applyQuietRules();
        assertFalse(svc.chat(ROOM).archived());
    }

    @Test
    void aChannelArrivingInAnArchivedServerGoesQuiet() throws Exception {
        MatrixChatService svc = service();
        String space = "{\"type\":\"m.room.create\",\"state_key\":\"\",\"sender\":\"" + ME + "\",\"content\":{\"type\":\"m.space\"}},"
                + "{\"type\":\"m.space.child\",\"state_key\":\"!c:x\",\"sender\":\"" + ME + "\",\"content\":{\"via\":[\"x\"]}}";
        svc.processor().process(room("!s:x", space, ""), true);
        svc.setArchived("!s:x", true).get(10, TimeUnit.SECONDS);
        svc.applyQuietRules();
        svc.processor().process(room("!c:x", "", ""), true);
        svc.applyQuietRules();
        assertTrue(svc.chat("!c:x").archived() && svc.chat("!c:x").muted());
    }

    private static final String ARCHIVED_SERVER = "{\"type\":\"m.room.create\",\"state_key\":\"\",\"sender\":\"" + ME
            + "\",\"content\":{\"type\":\"m.space\"}},{\"type\":\"m.space.child\",\"state_key\":\"!c:x\",\"sender\":\"" + ME
            + "\",\"content\":{\"via\":[\"x\"]}}";

    @Test
    void anUnarchivedChannelInAnArchivedServerStaysThatWayAcrossRestarts() throws Exception {
        prefs.choose("matrix:!s:x", Flag.ARCHIVED, true);
        prefs.choose("matrix:!s:x", Flag.MUTED, true);
        prefs.choose("matrix:!c:x", Flag.ARCHIVED, false);
        prefs.choose("matrix:!c:x", Flag.MUTED, false);

        MatrixChatService restarted = service(open(), "DEVICE");
        restarted.processor().process(room("!s:x", ARCHIVED_SERVER, ""), true);
        restarted.processor().process(room("!c:x", "", ""), true);
        restarted.processor().process(rules("!s:x"), true);
        restarted.applyQuietRules();

        assertFalse(restarted.chat("!c:x").archived() || restarted.chat("!c:x").muted());
        var saved = open().current("matrix:!c:x");
        assertFalse(saved.archived() || saved.muted());
        assertTrue(saved.muteChosenHere(), "still the person's choice");
        assertEquals(Map.of(), restarted.processor().mutesToApply());
        restarted.actions().muteWritesDone().get(10, TimeUnit.SECONDS);
        assertEquals(List.of(), writes());
    }

    @Test
    void anUntouchedChannelInAnArchivedServerFollowsAndItsRuleIsWritten() throws Exception {
        prefs.choose("matrix:!s:x", Flag.ARCHIVED, true);
        prefs.choose("matrix:!s:x", Flag.MUTED, true);
        MatrixChatService svc = service();
        svc.processor().process(room("!s:x", ARCHIVED_SERVER, ""), true);
        svc.processor().process(room("!c:x", "", ""), true);
        svc.processor().process(rules("!s:x"), true);
        svc.applyQuietRules();

        assertTrue(svc.chat("!c:x").archived() && svc.chat("!c:x").muted());
        assertFalse(prefs.current("matrix:!c:x").muteChosenHere(), "a rule, not a choice");
        svc.actions().muteWritesDone().get(10, TimeUnit.SECONDS);
        assertEquals(List.of("PUT /_matrix/client/v3/pushrules/global/room/!c:x"), writes());

        svc.processor().process(rules("!s:x", "!c:x"), true);
        svc.processor().process(rules("!s:x"), true);
        assertFalse(svc.chat("!c:x").muted(), "the phone can still change it");
    }

    private void startAndStop(MatrixChatService svc) {
        svc.start();
        svc.stop();
    }

    /** Under the lock: the stopped service's sync thread may still be finishing its last pass. */
    private static void feed(MatrixChatService svc, JsonNode sync) {
        synchronized (svc.processor()) {
            svc.processor().process(sync, true);
        }
    }

    @Test
    void anotherDeviceIdForgetsWhatTheOldSessionHeard() throws Exception {
        MatrixChatService before = service(prefs, "OLD");
        startAndStop(before);
        feed(before, room(ROOM, "", ""));
        feed(before, rules(ROOM));
        assertTrue(before.chat(ROOM).muted());

        // The local server wiped, same URL and data dir, logged in again: a new device's empty rules erase nothing.
        PreferencesFile file = open();
        MatrixChatService after = service(file, "NEW");
        startAndStop(after);
        feed(after, room(ROOM, "", ""));
        feed(after, rules());
        assertTrue(after.chat(ROOM).muted());
        assertTrue(file.current("matrix:" + ROOM).muted());
    }

    @Test
    void theSameDeviceIdKeepsWhatItHeard() throws Exception {
        MatrixChatService before = service(prefs, "SAME");
        startAndStop(before);
        feed(before, room(ROOM, "", ""));
        feed(before, rules(ROOM));

        MatrixChatService after = service(open(), "SAME");
        startAndStop(after);
        feed(after, room(ROOM, "", ""));
        feed(after, rules());
        assertFalse(after.chat(ROOM).muted(), "unmuted on the phone while closed: followed");
    }

    @Test
    void stoppingSavesTheLastChange() throws Exception {
        MatrixChatService svc = service(prefs, "DEVICE");
        svc.processor().process(room(ROOM, "", ""), true);
        svc.setFavorite(ROOM, true).get(10, TimeUnit.SECONDS);
        svc.stop();
        assertTrue(Files.readString(dir.resolve("preferences.json")).contains("\"favorite\" : true"));
    }

    @Test
    void withNoDeviceIdTheUserIdentifiesTheSession() {
        assertEquals("http://h|DEV", MatrixChatService.serverIdentity("http://h", "DEV", "@u:h"));
        assertEquals("http://h|@u:h", MatrixChatService.serverIdentity("http://h", null, "@u:h"));
        assertEquals("http://h|@u:h", MatrixChatService.serverIdentity("http://h", " ", "@u:h"));
    }

    @Test
    void aReminderThatFiresStaysGoneAndTheServersCopyCantBringItBack() throws Exception {
        MatrixChatService svc = service();
        Instant past = Instant.now().minusSeconds(60);
        svc.processor().process(room(ROOM, "", reminder(past)), true);
        svc.core().initialSyncDone = true;
        AtomicInteger due = new AtomicInteger();
        svc.addListener(new ChatService.Listener() {
            @Override public void reminderDue(Chat chat) { due.incrementAndGet(); }
        });
        svc.actions().checkReminders();
        assertEquals(1, due.get());
        assertNull(svc.chat(ROOM).reminderAt());
        assertEquals(List.of(), writes());
        assertTrue(saved().contains("\"reminderAt\" : null"));

        svc.processor().process(room(ROOM, "", reminder(past)), true);
        svc.actions().checkReminders();
        assertEquals(1, due.get());
    }

    private static ChatService.Listener changes(AtomicInteger fired) {
        return new ChatService.Listener() {
            @Override public void chatsChanged() { fired.incrementAndGet(); }
        };
    }
}
