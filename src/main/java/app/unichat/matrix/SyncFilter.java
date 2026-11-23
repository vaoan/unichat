package app.unichat.matrix;

import app.unichat.settings.Settings;
import app.unichat.support.Hashes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

import static app.unichat.matrix.MatrixClient.CLIENT;
import static app.unichat.support.Urls.enc;

/**
 * The sync's filter: the usual one, less the rooms that rest ({@code room.not_rooms}). One that names rooms is posted
 * to the server ({@code /user/{id}/filter}) and the sync gives its id: inlined in the URL, a filter of the person's
 * thousands of rooms took tuwunel seconds a sync. The sync loop takes it for each request ({@link #forRequest}), and
 * whoever changed it hears when a request has gone out with it. A {@code since} token stays good across filters. Each
 * filter is posted once: its id is kept by its content's hash, here and in {@link #KEPT_KEY} (this computer's), so the
 * same rest set or catch-up part never posts a new one, not even after a restart.
 */
final class SyncFilter {
    /** The usual filter, inlined. */
    static final String BASE =
            "{\"room\":{\"state\":{\"lazy_load_members\":true},\"timeline\":{\"limit\":30,\"lazy_load_members\":true}}}";

    /** Filter ids kept, by their content's hash: the newest this many. */
    static final int KEPT = 128;
    /** "hash=id,…", oldest first; {@code matrix.} keys stay on this computer, as filter ids belong to its server. */
    static final String KEPT_KEY = "matrix.syncFilters";

    private final MatrixClient client;
    private final String userId;
    /** Hash → id, for this run; guarded by itself. */
    private final Map<String, String> ids = new LinkedHashMap<>();
    /** What the sync's {@code filter=} says from the next request on: {@link #BASE} or a posted filter's id. */
    private String current = BASE;
    /** Waiting for a request to go out with {@link #current}. */
    private final List<CompletableFuture<Void>> waiting = new ArrayList<>();
    private volatile Runnable onChange = () -> { };

    SyncFilter(MatrixClient client, String userId) {
        this.client = client;
        this.userId = userId;
    }

    /** Told when the filter changed, so a long poll open with the one before can be dropped. */
    void onChange(Runnable r) { onChange = r; }

    /**
     * The {@code filter=} value for the request going out now (not yet encoded). The first sync ({@code initial}) is
     * always whole: the snapshot kept on disk and its token must have every room, or a restart loses what rested.
     */
    synchronized String forRequest(boolean initial) {
        for (CompletableFuture<Void> f : waiting) f.complete(null);
        waiting.clear();
        return initial ? BASE : current;
    }

    /**
     * Leaves {@code rooms} out of the sync from the next request on; the future completes once a request has gone out
     * with it. Throws when the server didn't keep the filter: nothing changes then.
     */
    CompletableFuture<Void> exclude(Collection<String> rooms) throws IOException {
        String value = rooms.isEmpty() ? BASE : id(withNotRooms(rooms));
        CompletableFuture<Void> sent = new CompletableFuture<>();
        synchronized (this) {
            current = value;
            waiting.add(sent);
        }
        onChange.run();
        return sent;
    }

    /** The server's id for {@code filter}, posted the first time it's seen (on this server, as this user). */
    String id(ObjectNode filter) throws IOException {
        String hash = Hashes.sha256(client.baseUrl() + "|" + userId + "|" + filter).substring(0, 24);
        synchronized (ids) {
            String known = ids.computeIfAbsent(hash, SyncFilter::kept);
            if (known != null) return known;
        }
        JsonNode answer = client.post(CLIENT + "/user/" + enc(userId) + "/filter", filter);
        String id = answer.path("filter_id").asText(null);
        if (id == null || !id.matches("[A-Za-z0-9._~-]{1,200}")) throw new IOException("the server gave no usable filter id");
        synchronized (ids) {
            ids.put(hash, id);
            keep(hash, id);
        }
        return id;
    }

    /** The id kept for {@code hash} on this computer, or null. */
    private static String kept(String hash) {
        for (String e : Settings.get().getSet(KEPT_KEY)) if (e.startsWith(hash + "=")) return e.substring(hash.length() + 1);
        return null;
    }

    private static void keep(String hash, String id) {
        List<String> all = new ArrayList<>(Settings.get().getSet(KEPT_KEY));
        all.removeIf(e -> e.startsWith(hash + "="));
        all.add(hash + "=" + id);
        while (all.size() > KEPT) all.removeFirst();
        Settings.get().set(KEPT_KEY, String.join(",", all));
    }

    /** The usual filter, to build on. */
    static ObjectNode base() {
        try {
            return (ObjectNode) MatrixClient.JSON.readTree(BASE);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sorted, so the same rooms are the same filter whatever order they came in. */
    static void roomList(ObjectNode into, String field, Collection<String> rooms) {
        ArrayNode list = ((ObjectNode) into.path("room")).putArray(field);
        for (String r : new TreeSet<>(rooms)) list.add(r);
    }

    private static ObjectNode withNotRooms(Collection<String> rooms) {
        ObjectNode f = base();
        roomList(f, "not_rooms", rooms);
        return f;
    }
}
