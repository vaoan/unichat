package app.unichat.login;

import app.unichat.model.Network;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CookieTxtTest {
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
    private final CookieSpec spec = CookieSpec.forNetwork(Network.INSTAGRAM);

    private Path sample() throws Exception {
        return Paths.get(getClass().getResource("sample-cookies.txt").toURI());
    }

    @Test
    void readsRequiredInstagramCookies() throws Exception {
        SessionCookies s = CookieTxt.read(sample(), spec, NOW);
        assertTrue(s.complete());
        assertEquals("VALID", s.values().get("sessionid"));
        assertEquals("TOKEN", s.values().get("csrftoken"));
    }

    @Test
    void ignoresExpiredAndOtherDomains() throws Exception {
        SessionCookies s = CookieTxt.read(sample(), spec, NOW);
        assertFalse(s.values().containsKey("mid"));
        assertFalse(s.values().containsKey("foo"));
    }

    @Test
    void reportsMissing(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("c.txt");
        Files.writeString(f, ".instagram.com\tTRUE\t/\tTRUE\t0\tcsrftoken\tT\n");
        SessionCookies s = CookieTxt.read(f, spec, NOW);
        assertFalse(s.complete());
        assertEquals(List.of("sessionid"), spec.missing(s.values()));
    }
}
