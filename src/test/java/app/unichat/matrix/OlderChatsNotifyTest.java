package app.unichat.matrix;

import app.unichat.model.Chat;
import app.unichat.model.ConnectionState;
import app.unichat.model.Message;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import app.unichat.service.Changes;
import app.unichat.service.OlderChats;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chats a load brings arrive with their history: over a fake homeserver, they notify nothing and leave the unread
 * badge as it was (bridgev2 marks backfill read only past 720 h, so Unichat marks each loaded room read itself).
 */
class OlderChatsNotifyTest {
    private static final String ME = "@me:example.org";
    private static final String FRIEND = "@telegram_5:example.org";
    private static final String TG_BOT = "@telegrambot:example.org";

    private HttpServer server;
    private final Changes serverChanges = new Changes();
    /** The live syncs the server answers, in turn; a long poll waits for the next one. */
    private final BlockingQueue<String> live = new LinkedBlockingQueue<>();
    private final AtomicInteger liveAsked = new AtomicInteger();
    private final AtomicInteger liveAnswered = new AtomicInteger();
    /** "join !room", "read !room $event", in order. */
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile String initialSync = "{\"next_batch\":\"b0\"}";
    private final List<Message> incoming = new CopyOnWriteArrayList<>();
    private final AtomicInteger chatsChanged = new AtomicInteger();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    @SuppressWarnings("NullAway.Init") // each test makes it before use; @AfterEach stops it only if made
    private MatrixChatService svc;

    @BeforeEach
    void fakeHomeserver() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        if (svc != null) svc.stop();
        live.add("{\"next_batch\":\"end\"}");
        server.stop(0);
        io.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = String.valueOf(ex.getRequestURI().getRawQuery());
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String answer = "{}";
        try {
            if (path.endsWith("/sync")) {
                if (!query.contains("since=")) {
                    answer = initialSync;
                } else {
                    liveAsked.incrementAndGet();
                    serverChanges.changed();
                    answer = live.take();
                    liveAnswered.incrementAndGet();
                }
            } else if (path.contains("/join")) {
                requests.add("join " + java.net.URLDecoder.decode(path.replaceAll(".*/(?:join|rooms)/([^/]+).*", "$1"), StandardCharsets.UTF_8));
                answer = "{\"room_id\":\"x\"}";
            } else if (path.endsWith("/read_markers")) {
                String room = java.net.URLDecoder.decode(path.replaceAll(".*/rooms/([^/]+)/read_markers", "$1"), StandardCharsets.UTF_8);
                requests.add("read " + room + " " + MatrixClient.JSON.readTree(body).path("m.fully_read").asText());
            } else if (path.endsWith("/filter")) {
                answer = "{\"filter_id\":\"f1\"}";
            }
        } catch (InterruptedException e) {
            answer = "{\"next_batch\":\"end\"}";
        } finally {
            serverChanges.changed();
        }
        byte[] b = answer.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        } catch (IOException gone) {
            // the client dropped it
        }
    }

    private void start(String token) {
        svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), ME, token);
        svc.addListener(new ChatService.Listener() {
            @Override public void chatsChanged() { chatsChanged.incrementAndGet(); }
            @Override public void messagesChanged(String chatId) { /* not counted */ }
            @Override public void accountsChanged() { /* not counted */ }
            @Override public void connectionChanged(ConnectionState state) { /* not counted */ }
            @Override public void incomingMessage(Chat chat, Message message) { incoming.add(message); }
            @Override public void reminderDue(Chat chat) { /* not counted */ }
        });
        svc.start();
    }

    /** Answers the live sync now waiting with {@code sync}, then waits until the next one is asked: it was dispatched. */
    private void liveSync(String sync) throws InterruptedException {
        serverChanges.until("a live sync waiting", () -> liveAsked.get() > liveAnswered.get());
        int before = liveAsked.get();
        live.add(sync);
        serverChanges.until("the sync after it asked (this one dispatched)", () -> liveAsked.get() > before);
    }

    /** {@code count} messages from {@code FRIEND}, the oldest {@code oldest} ago and the newest {@code newest} ago. */
    private static String history(int count, Duration newest, Duration oldest) {
        Instant now = Instant.now();
        List<String> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Duration age = oldest.minus(oldest.minus(newest).multipliedBy(i).dividedBy(count - 1));
            events.add(RestServer.message("$h" + i, FRIEND, "old message " + i, now.minus(age).toEpochMilli()));
        }
        return String.join(",", events);
    }

    private static String state(String type, String key, String sender, String content) {
        return "{\"type\":\"" + type + "\",\"state_key\":\"" + key + "\",\"sender\":\"" + sender + "\",\"event_id\":\"$s"
                + Math.abs((long) (type + key).hashCode()) + "\",\"origin_server_ts\":1,\"content\":" + content + "}";
    }

    private static String member(String user) {
        return state("m.room.member", user, user, "{\"membership\":\"join\"}");
    }

    /** A Telegram chat joined, with its events and its unread count as the server says it. */
    private static String telegramRoom(String id, String events, int unread) {
        String bridge = state("m.bridge", "fi.mau.telegram://telegram/" + id.hashCode(), ME,
                "{\"protocol\":{\"id\":\"telegram\"},\"channel\":{\"id\":\"" + id.hashCode() + "\"}}");
        return "\"" + id + "\":{\"state\":{\"events\":[" + member(ME) + "," + member(FRIEND) + "," + bridge + "]},"
                + "\"timeline\":{\"events\":[" + events + "]},\"unread_notifications\":{\"notification_count\":" + unread + "}}";
    }

    private static String joined(String... rooms) {
        return "{\"next_batch\":\"n" + System.nanoTime() + "\",\"rooms\":{\"join\":{" + String.join(",", rooms) + "}}}";
    }

    /** What the inbox's badge sums (ui's ChatFilter.inboxUnread: unread, not a bot's chat, not muted or archived). */
    private long inboxUnread() {
        return svc.chats().stream().filter(c -> c.isUnread() && !c.bridgeBot() && !c.muted() && !c.archived() && !c.space()).count();
    }

    @Test
    void aRoomJoinedWithBackfillFiresNoIncomingMessage() throws Exception {
        start("older-notify-backfill");
        String invite = "{\"next_batch\":\"i1\",\"rooms\":{\"invite\":{\"!old:example.org\":{\"invite_state\":{\"events\":["
                + state("m.room.member", ME, FRIEND, "{\"membership\":\"invite\"}") + "]}}}}}";
        liveSync(invite);
        serverChanges.until("the invite joined", () -> requests.stream().anyMatch(r -> r.startsWith("join !old:example.org")));
        int changedBefore = chatsChanged.get();
        liveSync(joined(telegramRoom("!old:example.org", history(50, Duration.ofHours(3), Duration.ofDays(4 * 365)), 0)));
        assertEquals(50, svc.messages("!old:example.org").size());
        assertEquals(List.of(), incoming, "history notifies nothing");
        assertTrue(chatsChanged.get() > changedBefore, "the list heard of the chat");
    }

    @Test
    void aLoadLeavesTheUnreadBadgeAsItWas() throws Exception {
        initialSync = joined(telegramRoom("!known:example.org", RestServer.message("$k", FRIEND, "hi", Instant.now().minusSeconds(3600).toEpochMilli()), 2));
        start("older-notify-badge");
        serverChanges.until("the first live sync asked", () -> liveAsked.get() >= 1);
        long before = inboxUnread();
        assertEquals(1, before, "the chat already unread");

        ManualTime time = new ManualTime(Instant.now());
        OlderChatSteps steps = new OlderChatSteps(k -> null, EnumSet.of(Network.TELEGRAM), time::now);
        Map<String, String> written = new ConcurrentHashMap<>();
        OlderChatLoader loader = new OlderChatLoader(steps, (n, command, patience) -> {
            // While the bridge walks the chat list, a chat from the last 30 days arrives, unread by the server's count.
            liveSync(joined(telegramRoom("!loaded:example.org", history(50, Duration.ofDays(1), Duration.ofDays(30)), 50)));
            return "Successfully synced";
        }, new BridgeProvisioner() {
            @Override public void ensure(Network network) { /* there */ }

            @Override public void reconfigure(Network network, java.util.function.Consumer<String> progress) { /* came back */ }
        }, new RoomArrivals(svc::roomIdsOf, time::now, time::later), new OlderChatLoader.SettingsWriter() {
            @Override public @Nullable String get(String key) { return written.get(key); }

            @Override
            public void set(String key, @Nullable String value) {
                if (value == null) written.remove(key);
                else written.put(key, value);
            }
        }, svc::markRead, id -> {
            Chat c = svc.chat(id);
            return c == null ? null : c.lastActivity();
        }, io);
        svc.useOlderChats(loader);

        var run = svc.load(List.of(Network.TELEGRAM));
        time.changes.until("the quiet wait", () -> run.isDone() || time.scheduled() > 0);
        time.advance(OlderChatLoader.QUIET);
        assertEquals(Map.of(Network.TELEGRAM, new OlderChats.Answer.Loaded(1)), run.get());
        assertEquals(before, inboxUnread(), "marked read as it came");
        // Only now that it was marked: the receipt for its last event reaches the server.
        serverChanges.until("the loaded chat's read receipt", () -> requests.contains("read !loaded:example.org $h49"));

        liveSync(joined(telegramRoom("!loaded:example.org", "", 0)));
        assertEquals(before, inboxUnread(), "the badge is as it was");
        assertEquals(Set.of(), Set.copyOf(incoming), "and nothing notified");
    }

    @Test
    void theBridgeBotsReplyDoesntNotify() throws Exception {
        initialSync = "{\"next_batch\":\"b0\",\"rooms\":{\"join\":{\"!tgbot:example.org\":{\"state\":{\"events\":["
                + member(ME) + "," + member(TG_BOT) + "]},\"timeline\":{\"events\":[]}}}}}";
        start("older-notify-bot");
        liveSync("{\"next_batch\":\"r1\",\"rooms\":{\"join\":{\"!tgbot:example.org\":{\"timeline\":{\"events\":["
                + RestServer.message("$bot", TG_BOT, "Successfully synced 100 chats", Instant.now().toEpochMilli()) + "]}}}}}");
        assertEquals(1, svc.messages("!tgbot:example.org").size(), "the reply came");
        assertEquals(List.of(), incoming, "a bridge bot's reply never notifies");
    }
}
