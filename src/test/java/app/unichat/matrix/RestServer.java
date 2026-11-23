package app.unichat.matrix;

import app.unichat.service.Changes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A homeserver for the resting tests. It keeps the filters posted, holds long polls open, answers a catch-up sync (no
 * {@code since}, a filter naming its rooms) with what each room got while it rested, and its Discord bot answers
 * bridging-mode commands. What it was asked is in {@link #log}, in order, and {@link #changes} hears each request.
 */
final class RestServer implements AutoCloseable {
    static final String ME = "@me:example.org";
    static final String BOT = "@discordbot:example.org";
    static final String BOT_ROOM = "!discordbot:example.org";

    /** "filter not_rooms=[…]", "filter rooms=[…]", "sync <filter> since timeout=…", "catch-up […]", "bot <command>". */
    final List<String> log = new CopyOnWriteArrayList<>();
    final Changes changes = new Changes();
    /** Filter id → the filter posted. */
    final Map<String, JsonNode> filters = new ConcurrentHashMap<>();
    /** Each room's events while it rested (JSON), for the catch-up that names it. */
    final Map<String, List<String>> missed = new ConcurrentHashMap<>();
    /** Each room's unread count, as a catch-up says it. */
    final Map<String, Integer> unread = new ConcurrentHashMap<>();
    /** A catch-up sync waits for it (leaving the archive mid catch-up). */
    volatile CountDownLatch catchUpGate = new CountDownLatch(0);
    /** Each room's tags ({@code m.tag}'s "tags" object), for a tags-only sync. */
    final Map<String, String> tags = new ConcurrentHashMap<>();
    /** Events the first live sync answered after a catch-up brings (newer than it). */
    final Map<String, List<String>> liveAfterCatchUp = new ConcurrentHashMap<>();
    /** Catch-up syncs fail (the server busy). */
    volatile boolean failCatchUps;
    /** The server refuses to keep filters. */
    volatile boolean refuseFilters;
    /** The answer to the first sync (no {@code since}, the usual filter). */
    volatile String initialSync = "{\"next_batch\":\"b0\"}";
    /** Hands the bot's answer to whoever processes syncs. */
    volatile Consumer<String> botAnswers = text -> { };

    private final HttpServer server;
    private final CountDownLatch hang = new CountDownLatch(1);
    private final AtomicInteger ids = new AtomicInteger();
    private final ExecutorService botThread = Executors.newSingleThreadExecutor();

    RestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Override
    public void close() {
        hang.countDown();
        catchUpGate.countDown();
        server.stop(0);
        botThread.shutdownNow();
    }

    /** The filters posted that leave rooms out, in order. */
    List<TreeSet<String>> notRoomsPosted() {
        List<TreeSet<String>> out = new ArrayList<>();
        for (String line : log) {
            if (line.startsWith("filter not_rooms=")) out.add(rooms(line.substring("filter not_rooms=".length())));
        }
        return out;
    }

    /** The rooms each catch-up sync asked for, in order. */
    List<TreeSet<String>> catchUps() {
        List<TreeSet<String>> out = new ArrayList<>();
        for (String line : log) if (line.startsWith("catch-up ")) out.add(rooms(line.substring("catch-up ".length())));
        return out;
    }

    private static TreeSet<String> rooms(String list) {
        TreeSet<String> out = new TreeSet<>();
        for (String r : list.replace("[", "").replace("]", "").split(",")) if (!r.isBlank()) out.add(r.strip());
        return out;
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.endsWith("/filter") && "POST".equals(ex.getRequestMethod())) {
                postFilter(ex, body);
            } else if (path.endsWith("/sync")) {
                sync(ex, query(ex.getRequestURI().getRawQuery()));
            } else if (path.contains("/rooms/" + BOT_ROOM + "/send/")) {
                String command = MatrixClient.JSON.readTree(body).path("body").asText();
                log.add("bot " + command.substring(command.indexOf("guilds"))); // without the bridge's prefix
                String mode = command.substring(command.lastIndexOf(' ') + 1);
                botThread.execute(() -> botAnswers.accept("Set guild bridging mode to " + mode));
                answer(ex, 200, "{\"event_id\":\"$cmd" + ids.incrementAndGet() + "\"}");
            } else {
                answer(ex, 200, "{}");
            }
        } finally {
            changes.changed();
        }
    }

    private void postFilter(HttpExchange ex, String body) throws IOException {
        if (refuseFilters) {
            log.add("filter refused");
            answer(ex, 500, "{\"errcode\":\"M_UNKNOWN\"}");
            return;
        }
        JsonNode f = MatrixClient.JSON.readTree(body);
        JsonNode room = f.path("room");
        log.add(!room.has("rooms") ? "filter not_rooms=" + names(room.path("not_rooms"))
                : (isTags(f) ? "filter tags=" : "filter rooms=") + names(room.get("rooms")));
        String id = "f" + ids.incrementAndGet();
        filters.put(id, f);
        answer(ex, 200, "{\"filter_id\":\"" + id + "\"}");
    }

    private static String names(JsonNode list) {
        TreeSet<String> out = new TreeSet<>();
        for (JsonNode r : list) out.add(r.asText());
        return out.toString();
    }

    private static boolean isTags(JsonNode f) {
        return f.path("room").path("timeline").path("limit").asInt(-1) == 0;
    }

    private void sync(HttpExchange ex, Map<String, String> q) throws IOException {
        String filter = q.getOrDefault("filter", "");
        boolean since = q.containsKey("since");
        JsonNode posted = filter.startsWith("{") ? MatrixClient.JSON.readTree(filter) : filters.get(filter);
        if (posted == null && !filter.isEmpty()) {
            log.add("unknown filter " + filter);
            answer(ex, 404, "{\"errcode\":\"M_NOT_FOUND\",\"error\":\"No such filter\"}");
            return;
        }
        if (posted != null && posted.path("room").has("rooms")) {
            if (isTags(posted)) tags(ex, posted.path("room").get("rooms"));
            else catchUp(ex, posted.path("room").get("rooms"));
            return;
        }
        log.add("sync " + (filter.startsWith("{") ? "inline" + (filter.contains("not_rooms") ? " not_rooms" : "") : filter)
                + (since ? " since=" + q.get("since") : "") + " timeout=" + q.get("timeout"));
        if (!since) {
            answer(ex, 200, initialSync);
            return;
        }
        boolean news = !liveAfterCatchUp.isEmpty() && log.contains("caught up");
        if (!news && !"0".equals(q.get("timeout"))) {
            changes.changed();
            try {
                hang.await(); // a long poll left open: only dropping it ends it
            } catch (InterruptedException e) {
                return;
            }
        }
        ObjectNode resp = MatrixClient.JSON.createObjectNode().put("next_batch", "b" + ids.incrementAndGet());
        if (!liveAfterCatchUp.isEmpty() && log.contains("caught up")) {
            ObjectNode join = resp.putObject("rooms").putObject("join");
            for (var e : liveAfterCatchUp.entrySet()) {
                join.set(e.getKey(), MatrixClient.JSON.readTree("{\"timeline\":{\"events\":[" + String.join(",", e.getValue()) + "]}}"));
            }
            liveAfterCatchUp.clear();
            log.add("live answered");
        }
        answer(ex, 200, resp.toString());
    }

    /** Resting rooms' account data, as {@link #tags} has it. */
    private void tags(HttpExchange ex, JsonNode rooms) throws IOException {
        log.add("tags " + names(rooms));
        ObjectNode join = MatrixClient.JSON.createObjectNode();
        for (JsonNode r : rooms) {
            String t = tags.get(r.asText());
            if (t != null) join.set(r.asText(), MatrixClient.JSON.readTree("{\"timeline\":{\"limited\":true,\"events\":[]},"
                    + "\"account_data\":{\"events\":[{\"type\":\"m.tag\",\"content\":{\"tags\":" + t + "}}]}}"));
        }
        ObjectNode resp = MatrixClient.JSON.createObjectNode().put("next_batch", "t" + ids.incrementAndGet());
        resp.putObject("rooms").set("join", join);
        answer(ex, 200, resp.toString());
    }

    private void catchUp(HttpExchange ex, JsonNode rooms) throws IOException {
        log.add("catch-up " + names(rooms));
        changes.changed();
        try {
            catchUpGate.await();
        } catch (InterruptedException e) {
            return;
        }
        log.add("caught up"); // the held catch-up answers from here on
        if (failCatchUps) {
            answer(ex, 503, "{\"errcode\":\"M_UNKNOWN\"}");
            return;
        }
        ObjectNode join = MatrixClient.JSON.createObjectNode();
        for (JsonNode r : rooms) {
            String id = r.asText();
            String events = String.join(",", missed.getOrDefault(id, List.of()));
            join.set(id, MatrixClient.JSON.readTree("{\"timeline\":{\"limited\":true,\"prev_batch\":\"p-" + id + "\",\"events\":["
                    + events + "]},\"unread_notifications\":{\"notification_count\":" + unread.getOrDefault(id, 0) + "}}"));
        }
        ObjectNode resp = MatrixClient.JSON.createObjectNode().put("next_batch", "catch-up");
        resp.putObject("rooms").set("join", join);
        answer(ex, 200, resp.toString());
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new ConcurrentHashMap<>();
        if (raw == null) return out;
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) out.put(kv.substring(0, eq), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void answer(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        } catch (IOException gone) {
            // the client dropped it (a long poll it no longer wanted)
        }
    }

    // ---------------------------------------------------------------- rooms

    /** A Discord server's space ({@code guild}) listing its channels. */
    static String server(String id, String guild, String... channels) {
        StringBuilder s = new StringBuilder(member(ME) + "," + state("m.room.create", "", "{\"type\":\"m.space\"}") + ","
                + bridge("discord", guild));
        for (String c : channels) s.append(",").append(state("m.space.child", c, "{\"via\":[\"example.org\"]}"));
        return room(id, s.toString());
    }

    /** A category: a space inside server {@code parent}, which it names as its parent, listing its channels. */
    static String category(String id, String channelId, String parent, String... channels) {
        StringBuilder s = new StringBuilder(member(ME) + "," + state("m.room.create", "", "{\"type\":\"m.space\"}") + ","
                + bridge("discord", channelId) + "," + state("m.space.parent", parent, "{\"via\":[\"example.org\"]}"));
        for (String c : channels) s.append(",").append(state("m.space.child", c, "{\"via\":[\"example.org\"]}"));
        return room(id, s.toString());
    }

    /** A Discord channel (in a server when one lists it). */
    static String channel(String id, String channelId) {
        return room(id, member(ME) + "," + member("@discord_1:example.org") + "," + member("@discord_2:example.org") + ","
                + bridge("discord", channelId) + "," + state("m.room.name", "", "{\"name\":\"#" + channelId + "\"}"));
    }

    /** A Discord DM: in no server. */
    static String dm(String id) {
        return room(id, member(ME) + "," + member("@discord_7:example.org") + "," + bridge("discord", "dm" + id.hashCode()));
    }

    static String telegramGroup(String id) {
        return room(id, member(ME) + "," + member("@telegram_1:example.org") + "," + member("@telegram_2:example.org") + ","
                + bridge("telegram", "tg" + id.hashCode()) + "," + state("m.room.name", "", "{\"name\":\"Telegram\"}"));
    }

    static String botRoom() {
        return room(BOT_ROOM, member(ME) + "," + member(BOT));
    }

    /** A sync answer with these rooms joined. */
    static String sync(String... rooms) {
        return "{\"next_batch\":\"b0\",\"rooms\":{\"join\":{" + String.join(",", rooms) + "}}}";
    }

    static String message(String eventId, String sender, String body, long ts) {
        return "{\"type\":\"m.room.message\",\"event_id\":\"" + eventId + "\",\"sender\":\"" + sender + "\",\"origin_server_ts\":"
                + ts + ",\"content\":{\"msgtype\":\"m.text\",\"body\":\"" + body + "\"}}";
    }

    /** A bot's notice, as the sync brings it. */
    static String botNotice(String text, int n) {
        return "{\"rooms\":{\"join\":{\"" + BOT_ROOM + "\":{\"timeline\":{\"events\":[{\"type\":\"m.room.message\",\"event_id\":\"$r"
                + n + "\",\"sender\":\"" + BOT + "\",\"origin_server_ts\":9,\"content\":{\"msgtype\":\"m.notice\",\"body\":\""
                + text + "\"}}]}}}}}";
    }

    private static String room(String id, String stateEvents) {
        return "\"" + id + "\":{\"state\":{\"events\":[" + stateEvents + "]},\"timeline\":{\"events\":[]}}";
    }

    private static String bridge(String protocol, String channel) {
        return state("m.bridge", "fi.mau." + protocol + "://" + protocol + "/" + channel,
                "{\"protocol\":{\"id\":\"" + protocol + "\"},\"channel\":{\"id\":\"" + channel + "\"}}");
    }

    private static String member(String user) {
        return "{\"type\":\"m.room.member\",\"state_key\":\"" + user + "\",\"sender\":\"" + user + "\",\"event_id\":\"$m"
                + Math.abs(user.hashCode()) + "\",\"origin_server_ts\":1,\"content\":{\"membership\":\"join\"}}";
    }

    private static String state(String type, String key, String content) {
        return "{\"type\":\"" + type + "\",\"state_key\":\"" + key + "\",\"sender\":\"" + ME + "\",\"event_id\":\"$s"
                + Math.abs((type + key).hashCode()) + "\",\"origin_server_ts\":1,\"content\":" + content + "}";
    }
}
