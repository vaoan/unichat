package app.unichat.login;

import app.unichat.model.Network;
import app.unichat.ui.login.LoginUrls;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoginUrlsTest {
    @Test
    void startUrlsPerNetwork() {
        assertEquals("https://www.instagram.com/accounts/login/", LoginUrls.startUrl(Network.INSTAGRAM));
        assertEquals("https://www.facebook.com/login/", LoginUrls.startUrl(Network.MESSENGER));
        assertEquals("https://x.com/login", LoginUrls.startUrl(Network.X));
        assertEquals("https://www.linkedin.com/login", LoginUrls.startUrl(Network.LINKEDIN));
        assertEquals("https://messages.google.com/web/authentication", LoginUrls.startUrl(Network.GOOGLE_MESSAGES));
    }

    @Test
    void nonCookieNetworkRejected() {
        assertThrows(IllegalArgumentException.class, () -> LoginUrls.startUrl(Network.TELEGRAM));
    }

    @Test
    void userAgentLooksLikeARealBrowser() {
        String ua = LoginUrls.userAgent();
        assertTrue(ua.contains("Mozilla"));
        assertTrue(ua.contains("Chrome") || ua.contains("Safari"));
        assertFalse(ua.contains("JavaFX"));
    }
}
