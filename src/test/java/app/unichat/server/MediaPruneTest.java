package app.unichat.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaPruneTest {
    @TempDir Path dir;
    private final Instant now = Instant.parse("2026-11-20T12:00:00Z");

    private Path bridgeDb() throws Exception {
        Path db = dir.resolve("discord.db");
        long old = now.minus(Duration.ofDays(30)).toEpochMilli(), recent = now.minus(Duration.ofDays(1)).toEpochMilli();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE discord_file (url TEXT, encrypted BOOLEAN, mxc TEXT NOT NULL, id TEXT, emoji_name TEXT,"
                    + " size BIGINT NOT NULL, mime_type TEXT NOT NULL, timestamp BIGINT NOT NULL)");
            s.execute("CREATE TABLE puppet (id TEXT, avatar_url TEXT)");
            s.execute("CREATE TABLE guild (dcid TEXT, avatar_url TEXT)");
            s.execute("CREATE TABLE portal (dcid TEXT, avatar_url TEXT)");
            s.execute("INSERT INTO discord_file VALUES"
                    + " ('u1', 0, 'mxc://localhost/oldBig', NULL, NULL, 5000000, 'image/png', " + old + "),"
                    + " ('u2', 0, 'mxc://localhost/newBig', NULL, NULL, 5000000, 'image/png', " + recent + "),"
                    + " ('u3', 0, 'mxc://localhost/oldSmall', NULL, NULL, 2000, 'image/png', " + old + "),"
                    + " ('u4', 0, 'mxc://localhost/emoji', 'e', 'kekw', 500000, 'image/gif', " + old + "),"
                    + " ('u5', 0, 'mxc://localhost/avatar', NULL, NULL, 400000, 'image/png', " + old + ")");
            s.execute("INSERT INTO puppet VALUES ('p', 'mxc://localhost/avatar')");
        }
        return db;
    }

    private static List<String> cached(Path db) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT mxc FROM discord_file ORDER BY mxc")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    @Test
    void onlyOldBigAttachmentsGoAndLeaveTheBridgeCache() throws Exception {
        Path db = bridgeDb();
        assertEquals(List.of("mxc://localhost/oldBig"), MediaPrune.takeOldDiscordAttachments(db, now));
        assertEquals(List.of("mxc://localhost/avatar", "mxc://localhost/emoji", "mxc://localhost/newBig",
                "mxc://localhost/oldSmall"), cached(db), "avatars, emoji, recent and small files stay");
        assertTrue(MediaPrune.takeOldDiscordAttachments(db, now).isEmpty(), "nothing twice");
    }

    @Test
    void noBridgeDatabaseMeansNothingToDo() {
        assertTrue(MediaPrune.takeOldDiscordAttachments(dir.resolve("missing.db"), now).isEmpty());
    }

    @Test
    void commandIsADeleteListCodeBlock() {
        assertEquals("media delete-list\n```\nmxc://localhost/a\nmxc://localhost/b\n```",
                MediaPrune.command(List.of("mxc://localhost/a", "mxc://localhost/b")));
    }
}
