package app.unichat.server;

import app.unichat.bridge.BridgeStates;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A session the bridge wiped by mistake comes back; one really closed, or closed on purpose, doesn't. */
class MetaSessionsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COOKIES = "{\"xs\":\"secreto\",\"c_user\":\"1\"}";
    @TempDir Path dir;
    private final BridgeStates states = new BridgeStates();
    private final List<String> restored = new ArrayList<>();
    private boolean redirecting;
    private MetaSessions sessions;
    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(dir.resolve("facebook"));
        Files.createDirectories(dir.resolve("instagram"));
        sql("facebook", "CREATE TABLE user_login (bridge_id TEXT NOT NULL, user_mxid TEXT NOT NULL, id TEXT NOT NULL, remote_name TEXT NOT NULL,"
                + " remote_profile jsonb, space_room TEXT, metadata jsonb NOT NULL, PRIMARY KEY (bridge_id, id))");
        sessions = new MetaSessions(dir, states, bridge -> redirecting, bridge -> {
            restored.add(bridge);
            sessions.restore(bridge);
        });
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private Path db() { return dir.resolve("facebook/facebook.db"); }

    private void sql(String bridge, String statement) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(bridge + "/" + bridge + ".db"));
             Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private void login(String id, String cookies) throws Exception {
        sql("facebook", "INSERT INTO user_login (bridge_id, user_mxid, id, remote_name, metadata) VALUES ('b','@yo:localhost','" + id
                + "','Yo','{\"platform\":\"facebook\",\"cookies\":" + cookies + ",\"wa_device_id\":7}')");
    }

    private String metadata(String id) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db());
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT metadata FROM user_login WHERE id='" + id + "'")) {
            return r.next() ? r.getString(1) : null;
        }
    }

    private void wipe(String id) throws Exception {
        sql("facebook", "UPDATE user_login SET metadata='{\"platform\":\"facebook\",\"cookies\":null,\"wa_device_id\":7}' WHERE id='" + id + "'");
    }

    @Test
    void aWipedSessionComesBackAndKeepsTheRest() throws Exception {
        login("1", COOKIES);
        sessions.tick(true, 0);
        wipe("1");
        sessions.tick(true, 1000);
        assertEquals(List.of("facebook"), restored);
        var m = JSON.readTree(metadata("1"));
        assertEquals("secreto", m.path("cookies").path("xs").asText());
        assertEquals(7, m.path("wa_device_id").asInt(), "the rest of the record stays");
    }

    @Test
    void itWaitsWhileFacebookIsRedirectingAndWithoutInternet() throws Exception {
        login("1", COOKIES);
        sessions.tick(true, 0);
        wipe("1");
        redirecting = true;
        sessions.tick(true, 1000);
        sessions.tick(false, 2000);
        assertEquals(List.of(), restored, "it would be wiped again");
        redirecting = false;
        sessions.tick(true, 3000);
        assertEquals(List.of("facebook"), restored);
    }

    @Test
    void aLoginRemovedOnPurposeNeverComesBack() throws Exception {
        login("1", COOKIES);
        sessions.tick(true, 0);
        sql("facebook", "DELETE FROM user_login WHERE id='1'");
        sessions.tick(true, MetaSessions.BACKUP_EVERY_MS + 1);
        assertEquals(List.of(), restored);
        assertFalse(Files.readString(dir.resolve("facebook/session-backup.json")).contains("secreto"), "and its copy is gone");
    }

    @Test
    void aSessionReallyClosedIsTriedTwiceThenLeftForThePerson() throws Exception {
        login("1", COOKIES);
        sessions.tick(true, 0);
        long at = 1000;
        for (int round = 0; round < 6; round++) {
            wipe("1");
            sessions.tick(true, at);
            at += MetaSessions.RETRY_AFTER_MS + 1;
        }
        assertEquals(MetaSessions.MAX_ATTEMPTS, restored.size());
    }

    @Test
    void anEmptySessionIsNeverSavedOverAGoodOne() throws Exception {
        login("1", COOKIES);
        sessions.tick(true, 0);
        wipe("1");
        redirecting = true;
        sessions.tick(true, MetaSessions.BACKUP_EVERY_MS + 1);
        redirecting = false;
        sessions.tick(true, MetaSessions.BACKUP_EVERY_MS * 2 + 2);
        assertEquals("secreto", JSON.readTree(metadata("1")).path("cookies").path("xs").asText());
    }

    @Test
    void aNewLoginReplacesTheCopy() throws Exception {
        login("1", COOKIES);
        sessions.tick(true, 0);
        sql("facebook", "DELETE FROM user_login WHERE id='1'");
        login("2", "{\"xs\":\"nuevo\"}");
        sessions.tick(true, MetaSessions.BACKUP_EVERY_MS + 1);
        String copy = Files.readString(dir.resolve("facebook/session-backup.json"));
        assertTrue(copy.contains("nuevo"));
        assertFalse(copy.contains("secreto"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theCopyIsPrivate() throws Exception {
        login("1", COOKIES);
        sessions.tick(true, 0);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(dir.resolve("facebook/session-backup.json")));
    }

    @Test
    void aBridgeNotInstalledIsNoProblem() {
        sessions.tick(true, 0);
        assertEquals(List.of(), restored);
    }

    @Test
    void onlyMetaBridgesAreFragile() {
        assertTrue(sessions.fragile("facebook"));
        assertTrue(sessions.fragile("instagram"));
        assertFalse(sessions.fragile("discord"));
        assertTrue(sessions.safeToRestart("discord"), "the others aren't affected");
        redirecting = true;
        assertFalse(sessions.safeToRestart("facebook"));
        assertTrue(sessions.safeToRestart("telegram"));
    }

    private URI serving(int status, String location) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            if (location != null) ex.getResponseHeaders().add("Location", location);
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/messages");
    }

    @Test
    void toAnotherHostIsTheDangerousRedirect() throws Exception {
        assertTrue(MetaSessions.redirectsElsewhere(serving(302, "https://web.facebook.com/messages?_rdc=1")));
    }

    @Test
    void toLoginOnTheSameHostIsWhatAnyoneWithoutCookiesGets() throws Exception {
        URI u = serving(302, "/login.php?next=x");
        assertFalse(MetaSessions.redirectsElsewhere(u));
    }

    @Test
    void anAnswerWithoutRedirectIsFine() throws Exception {
        assertFalse(MetaSessions.redirectsElsewhere(serving(200, null)));
    }

    @Test
    void notBeingAbleToAskCountsAsNotSafe() {
        assertTrue(MetaSessions.redirectsElsewhere(URI.create("http://127.0.0.1:1/messages")));
    }
}
