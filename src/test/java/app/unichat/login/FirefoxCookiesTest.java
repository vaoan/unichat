package app.unichat.login;

import app.unichat.model.Network;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FirefoxCookiesTest {
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
    /** Far ahead: findSessions checks expiry against the real clock, not NOW (a 1-day margin broke on 2026-10-27). */
    private static final long FUTURE = NOW.getEpochSecond() + 10L * 365 * 86400;
    private final CookieSpec spec = CookieSpec.forNetwork(Network.INSTAGRAM);

    @AfterEach
    void clear() {
        System.clearProperty("unichat.browserRoots");
    }

    private void makeDb(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE moz_cookies(name TEXT, value TEXT, host TEXT, path TEXT, expiry INTEGER, isSecure INTEGER)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO moz_cookies VALUES(?,?,?,?,?,?)")) {
                row(ps, "sessionid", "VALID", ".instagram.com", FUTURE);
                row(ps, "csrftoken", "TOKEN", ".instagram.com", 0);
                row(ps, "ds_user_id", "42", ".instagram.com", FUTURE);
                row(ps, "sessionid", "OTHER", ".otra.com", FUTURE);
                row(ps, "mid", "EXPIRED", ".instagram.com", NOW.getEpochSecond() - 10);
            }
        }
    }

    private static void row(PreparedStatement ps, String n, String v, String h, long exp) throws Exception {
        ps.setString(1, n); ps.setString(2, v); ps.setString(3, h);
        ps.setString(4, "/"); ps.setLong(5, exp); ps.setInt(6, 1);
        ps.executeUpdate();
    }

    @Test
    void readsSessionFromSqlite(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("cookies.sqlite");
        makeDb(db);
        SessionCookies s = FirefoxCookies.read(db, spec, NOW);
        assertTrue(s.complete());
        assertEquals("VALID", s.values().get("sessionid"));
        assertEquals("42", s.values().get("ds_user_id"));
        assertFalse(s.values().containsKey("mid"), "expired cookie must be dropped");
        assertFalse(s.values().containsValue("OTHER"), "other domain must be ignored");
    }

    @Test
    void profilesRootIsOsSpecific() {
        assertTrue(FirefoxCookies.profilesRoot("Mac OS X").endsWith(Path.of("Firefox", "Profiles")));
        assertTrue(FirefoxCookies.profilesRoot("Windows 11").toString().contains("Mozilla"));
        assertTrue(FirefoxCookies.profilesRoot("Linux").endsWith(Path.of(".mozilla", "firefox")));
    }

    @Test
    void findSessionsCopiesDbAndLeavesOriginal(@TempDir Path root) throws Exception {
        Path profile = Files.createDirectories(root.resolve("abc.default"));
        Files.createDirectories(root.resolve("empty.profile"));
        makeDb(profile.resolve("cookies.sqlite"));
        System.setProperty("unichat.browserRoots", root.toString());
        List<BrowserSession> found = FirefoxCookies.findSessions(spec);
        assertEquals(1, found.size());
        assertEquals("Firefox", found.get(0).browser());
        assertEquals("abc.default", found.get(0).profile());
        assertTrue(found.get(0).cookies().complete());
        assertTrue(Files.exists(profile.resolve("cookies.sqlite")));
    }

    @Test
    void findSessionsLeavesNoTempFilesBehind(@TempDir Path root) throws Exception {
        Path profile = Files.createDirectories(root.resolve("p.default"));
        makeDb(profile.resolve("cookies.sqlite"));
        System.setProperty("unichat.browserRoots", root.toString());
        FirefoxCookies.findSessions(spec);
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            List<String> left = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("unichat-cookies-")).toList();
            assertTrue(left.isEmpty(), "temp cookie copies left: " + left);
        }
    }
}
