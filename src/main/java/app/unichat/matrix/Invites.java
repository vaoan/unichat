package app.unichat.matrix;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Bridges invite us to portal rooms: every invite is joined, once (non-bridge invites show up as chats too). */
final class Invites {
    private final MatrixCore core;
    private final Set<String> attempted = ConcurrentHashMap.newKeySet();

    Invites(MatrixCore core) {
        this.core = core;
    }

    void join(String roomId) {
        if (!attempted.add(roomId)) return;
        core.io.execute(() -> {
            try {
                core.client.join(roomId);
            } catch (Exception e) {
                System.err.println("Could not join " + roomId + ": " + e);
            }
        });
    }
}
