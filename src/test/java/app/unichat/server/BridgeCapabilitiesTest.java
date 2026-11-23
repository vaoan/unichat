package app.unichat.server;

import java.io.IOException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * X's bridge says what a chat allows (editing, deleting for everyone) once, when the chat is made, and not again when it
 * gets the chat's XChat keys later: its "no edit" stays. Before it starts, its record of what it last said is marked
 * stale (only the capabilities' half), so it says it again for every chat whose answer changed.
 */
class BridgeCapabilitiesTest {
    @TempDir Path dir;

    private Path db(String bridge, String value) throws IOException, SQLException {
        Path d = dir.resolve(bridge);
        Files.createDirectories(d);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + d.resolve(bridge + ".db")); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE kv_store (bridge_id TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY (bridge_id, key))");
            if (value != null) s.execute("INSERT INTO kv_store VALUES ('', 'bridge_info_version', '" + value + "')");
            s.execute("INSERT INTO kv_store VALUES ('', 'other', 'kept')");
        }
        return d;
    }

    private static String value(Path bridgeDir, String bridge, String key) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + bridgeDir.resolve(bridge + ".db"));
             Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT value FROM kv_store WHERE key = '" + key + "'")) {
            return r.next() ? r.getString(1) : null;
        }
    }

    @Test
    void xsBridgeSaysWhatEachChatAllowsAgainItsChatsInfoLeftAlone() throws Exception {
        Path d = db("twitter", "1,8");
        BridgeCapabilities.beforeStart("twitter", d);
        assertEquals("1,-1", value(d, "twitter", "bridge_info_version"),
                "the capabilities' version stale, so the bridge says them again; the info's kept (it would resend every chat's)");
        assertEquals("kept", value(d, "twitter", "other"), "nothing else touched");
    }

    @Test
    void otherBridgesAreLeftAlone() throws Exception {
        Path d = db("telegram", "1,8");
        BridgeCapabilities.beforeStart("telegram", d);
        assertEquals("1,8", value(d, "telegram", "bridge_info_version"));
    }

    @Test
    void whatIsntThereOrIsntReadStaysAsItIs() throws Exception {
        Files.createDirectories(dir.resolve("twitter")); // installed, never started: its folder, no database
        BridgeCapabilities.beforeStart("twitter", dir.resolve("twitter"));
        assertFalse(Files.exists(dir.resolve("twitter").resolve("twitter.db")), "none made");
        Path none = db("twitter", null);
        BridgeCapabilities.beforeStart("twitter", none); // started once, but never said it
        assertNull(value(none, "twitter", "bridge_info_version"), "nothing invented: the bridge says them all then anyway");
        Files.delete(none.resolve("twitter.db"));
        Path odd = db("twitter", "uno,ocho");
        BridgeCapabilities.beforeStart("twitter", odd);
        assertEquals("uno,ocho", value(odd, "twitter", "bridge_info_version"), "a value it can't read: left for the bridge");
    }

    @Test
    void aDatabaseOfAnotherShapeIsNoProblem() throws Exception {
        Path d = dir.resolve("twitter");
        Files.createDirectories(d);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + d.resolve("twitter.db")); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE portal (id TEXT)");
        }
        BridgeCapabilities.beforeStart("twitter", d); // no kv_store: it starts as ever
    }
}
