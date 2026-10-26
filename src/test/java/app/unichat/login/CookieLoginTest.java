package app.unichat.login;

import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CookieLoginTest {
    @Test
    void cookieNetworksAreRecognised() {
        for (Network n : List.of(Network.INSTAGRAM, Network.MESSENGER, Network.X,
                Network.LINKEDIN, Network.GOOGLE_MESSAGES)) {
            assertTrue(CookieLogin.isCookieNetwork(n), n.name());
        }
        for (Network n : List.of(Network.WHATSAPP, Network.TELEGRAM, Network.SIGNAL, Network.DISCORD)) {
            assertFalse(CookieLogin.isCookieNetwork(n), n.name());
        }
    }

    @Test
    void sampleCookiesTxtIsOk() throws Exception {
        Path f = Paths.get(getClass().getResource("/app/unichat/login/sample-cookies.txt").toURI());
        CookieLogin.TxtResult r = CookieLogin.fromCookieTxt(f, Network.INSTAGRAM);
        assertEquals(CookieLogin.TxtResult.Kind.OK, r.kind());
        assertTrue(r.cookies().complete());
    }

    @Test
    void incompleteCookiesTxtReportsMissing() throws Exception {
        Path f = Files.createTempFile("cookies", ".txt");
        try {
            Files.writeString(f, ".instagram.com\tTRUE\t/\tTRUE\t0\tcsrftoken\tT\n");
            CookieLogin.TxtResult r = CookieLogin.fromCookieTxt(f, Network.INSTAGRAM);
            assertEquals(CookieLogin.TxtResult.Kind.MISSING, r.kind());
            assertEquals(List.of("sessionid"), r.missing());
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void unreadableFileIsError() {
        CookieLogin.TxtResult r = CookieLogin.fromCookieTxt(Path.of("no-such-file.txt"), Network.INSTAGRAM);
        assertEquals(CookieLogin.TxtResult.Kind.ERROR, r.kind());
        assertFalse(r.message().isBlank());
    }

    @Test
    void findBrowserSessionsIsEmptyWithoutBrowsers() throws Exception {
        Path empty = Files.createTempDirectory("unichat-roots");
        String prev = System.getProperty("unichat.browserRoots");
        System.setProperty("unichat.browserRoots", empty.toString());
        try {
            List<BrowserSession> r = CookieLogin.findBrowserSessions(Network.INSTAGRAM).get(30, TimeUnit.SECONDS);
            assertTrue(r.isEmpty());
        } finally {
            if (prev == null) System.clearProperty("unichat.browserRoots");
            else System.setProperty("unichat.browserRoots", prev);
            Files.deleteIfExists(empty);
        }
    }
}
