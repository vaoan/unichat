package app.unichat.matrix;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for "every network shows up grey after switching to the local server": the Docker server and the
 * local one are both "localhost" with user "@yo:localhost", so the new session loaded the old session's chats.
 */
class SyncCacheKeyTest {
    @Test
    void anotherSessionOfTheSameUserGetsItsOwnCache() {
        Path docker = new MatrixChatService("http://localhost:8008", "@yo:localhost", "syt_docker").syncCacheFile();
        Path local = new MatrixChatService("http://127.0.0.1:8008", "@yo:localhost", "syt_local").syncCacheFile();
        assertNotEquals(docker, local);
        assertEquals(local, new MatrixChatService("http://127.0.0.1:8008", "@yo:localhost", "syt_local").syncCacheFile(),
                "same session, same cache across restarts");
    }

    @Test
    void signingInDropsOtherSessionsCaches() throws Exception {
        MatrixChatService old = new MatrixChatService("http://localhost:8008", "@yo:localhost", "syt_old");
        Path stale = old.syncCacheFile();
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "{}");
        Path unrelated = stale.resolveSibling("avatar-123.png");
        Files.writeString(unrelated, "x");
        SyncCache.drop();
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(unrelated), "only sync caches are removed");
        Files.delete(unrelated);
    }
}
