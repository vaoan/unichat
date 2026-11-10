package app.unichat.matrix;

import app.unichat.settings.Settings;
import app.unichat.support.Hashes;
import app.unichat.support.SafeFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;


/** The last full /sync on disk, shown instantly on the next start. Bot chats' messages never reach it ({@link #redact}). */
final class SyncCache {
    private final SyncProcessor proc;
    private final String serverName;
    private final String userId;
    private final String sessionKey;

    SyncCache(SyncProcessor proc, String serverName, String userId, String sessionKey) {
        this.proc = proc;
        this.serverName = serverName;
        this.userId = userId;
        this.sessionKey = sessionKey;
    }

    /** Last full /sync response for this account; shown instantly on the next start (like Element's cache). */
    Path file() {
        // Per session, not only per user: two servers can share the name and user (Docker and the local server are
        // both "localhost" / @yo:localhost), and a new session must never show another one's chats.
        return Settings.get().cacheDir().resolve("sync-" + Hashes.sha256(serverName + "|" + userId).substring(0, 16) + "-" + sessionKey + ".json");
    }

    /** Removes every session's cached sync (called on sign-in: the new session builds its own). */
    static void drop() {
        try (var files = Files.list(Settings.get().cacheDir())) {
            for (Path f : files.filter(x -> x.getFileName().toString().matches("sync-.*\\.json")).toList()) Files.deleteIfExists(f);
        } catch (IOException ignored) {
            // No cache dir yet.
        }
    }

    /**
     * A /sync response with bridge-bot room message content blanked, so login credentials (cookies, tokens, QR
     * data) sent in those DMs never reach the on-disk cache. Input is not mutated, and it isn't copied whole either
     * (a full sync of thousands of rooms is hundreds of MB as a tree; a deep copy ran the heap out at startup): only
     * the path down to each bot room is new, everything else is shared.
     */
    static JsonNode redact(JsonNode fullSync, Set<String> botRoomIds) {
        if (!(fullSync instanceof ObjectNode root) || !(fullSync.path("rooms") instanceof ObjectNode rooms)) return fullSync;
        ObjectNode outRooms = null;
        for (String section : new String[]{"join", "invite", "leave"}) {
            if (!(rooms.path(section) instanceof ObjectNode sec)) continue;
            ObjectNode outSec = null;
            for (String id : botRoomIds) {
                if (!(sec.get(id) instanceof ObjectNode room)) continue;
                if (outSec == null) outSec = sec.objectNode().setAll(sec);
                ObjectNode copy = room.deepCopy();
                blank(copy.path("timeline").path("events"));
                blank(copy.path("state").path("events"));
                blank(copy.path("invite_state").path("events"));
                outSec.set(id, copy);
            }
            if (outSec == null) continue;
            if (outRooms == null) outRooms = rooms.objectNode().setAll(rooms);
            outRooms.set(section, outSec);
        }
        if (outRooms == null) return fullSync;
        ObjectNode out = root.objectNode().setAll(root);
        out.set("rooms", outRooms);
        return out;
    }

    /** Blanks message-type events (no state_key); state events keep their content so the room still resolves. */
    private static void blank(JsonNode events) {
        if (!events.isArray()) return;
        for (JsonNode ev : events) {
            if (!(ev instanceof ObjectNode o)) continue;
            if (o.has("state_key")) continue;
            o.set("content", MatrixClient.JSON.createObjectNode());
        }
    }

    void save(JsonNode fullSync) {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Set<String> botRoomIds = new java.util.HashSet<>();
            synchronized (proc) {
                for (MatrixRoom r : proc.rooms()) if (r.chat.bridgeBot()) botRoomIds.add(r.id);
            }
            SafeFile.replace(f, MatrixClient.JSON.writeValueAsBytes(redact(fullSync, botRoomIds)), true);
        } catch (Exception e) {
            System.err.println("Could not save sync cache: " + e);
        }
    }
}
