package app.unichat.login;

import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeCookiesTest {
    private static BridgeLogin.CookieField cookie(String id, String name, String domain, boolean required) {
        return new BridgeLogin.CookieField(id, required, List.of(new BridgeLogin.Source("cookie", name, domain)));
    }

    private static final BridgeLogin.CookieParams INSTAGRAM = new BridgeLogin.CookieParams("https://www.instagram.com/", null,
            List.of(cookie("sessionid", "sessionid", ".instagram.com", true), cookie("csrftoken", "csrftoken", "instagram.com", true),
                    cookie("mid", "mid", null, false)), null, null);

    @Test
    void specComesFromWhatTheBridgeAsks() {
        CookieSpec s = BridgeCookies.spec(Network.INSTAGRAM, INSTAGRAM);
        assertEquals(Set.of("sessionid", "csrftoken"), s.required());
        assertEquals(Set.of("mid"), s.optional());
        assertEquals(Set.of("instagram.com"), s.domains());
        assertTrue(s.isForNetwork(".www.instagram.com"));
    }

    @Test
    void foundCookiesGoBackUnderTheBridgesFieldIds() {
        BridgeLogin.CookieParams p = new BridgeLogin.CookieParams("https://x.com", null,
                List.of(cookie("auth", "auth_token", "x.com", true)), null, null);
        Map<String, String> v = BridgeCookies.values(p, new SessionCookies(Network.X, Map.of("auth_token", "abc", "other", "z")));
        assertEquals(Map.of("auth", "abc"), v, "keyed by field id, only what was asked");
    }

    @Test
    void headerOnlyFieldsCantBeCollectedFromCookies() {
        BridgeLogin.CookieParams linkedin = new BridgeLogin.CookieParams("https://www.linkedin.com/", null, List.of(
                cookie("cookie", "li_at", "linkedin.com", true),
                new BridgeLogin.CookieField("x-li-track", true, List.of(new BridgeLogin.Source("request_header", "X-LI-Track", null)))),
                null, null);
        assertFalse(BridgeCookies.canCollect(linkedin));
        assertTrue(BridgeCookies.canCollect(INSTAGRAM));
    }

    /** What mautrix-linkedin asks for. */
    private static BridgeLogin.CookieParams linkedin() {
        return new BridgeLogin.CookieParams("https://linkedin.com/login", "UA", List.of(
                new BridgeLogin.CookieField("cookie_header", true, List.of(new BridgeLogin.Source("request_header", "Cookie", null)), "\\bJSESSIONID=[^;]+"),
                new BridgeLogin.CookieField("track", true, List.of(new BridgeLogin.Source("request_header", "X-LI-Track", null)), "clientVersion"),
                new BridgeLogin.CookieField("page", true, List.of(new BridgeLogin.Source("request_header", "X-LI-Page-Instance", null)), "urn:li:page")),
                null, "^https://www\\.linkedin\\.com/feed.*$");
    }

    private static final Map<String, String> SESSION = new java.util.TreeMap<>(Map.of("JSESSIONID", "ajax", "li_at", "abc"));
    private static final Map<String, String> SENT = Map.of("x-li-track", "{\"clientVersion\":\"1\"}", "x-li-page-instance", "urn:li:page:feed");

    @Test
    void headersTakeTheWindowNotABrowserProfile() {
        assertFalse(BridgeCookies.canCollect(linkedin()));
        assertTrue(BridgeCookies.needsWindowCapture(linkedin()));
        assertEquals(java.util.Set.of("x-li-track", "x-li-page-instance"), BridgeCookies.headersToWatch(linkedin()), "Cookie comes from the store");
        assertEquals("linkedin.com", BridgeCookies.cookieDomain(linkedin()));
    }

    @Test
    void itsDoneOnlyWithEverythingAndAtTheEnd() {
        String feed = "https://www.linkedin.com/feed/";
        Map<String, String> v = BridgeCookies.captured(linkedin(), feed, SESSION, SENT);
        assertEquals("JSESSIONID=ajax; li_at=abc", v.get("cookie_header"));
        assertEquals("urn:li:page:feed", v.get("page"));
        assertTrue(v.get("track").contains("clientVersion"));
        assertEquals(null, BridgeCookies.captured(linkedin(), "https://www.linkedin.com/checkpoint/2fa", SESSION, SENT), "still in 2FA");
        assertEquals(null, BridgeCookies.captured(linkedin(), feed, SESSION, Map.of()), "no request seen yet");
        assertEquals(null, BridgeCookies.captured(linkedin(), feed, Map.of("li_at", "abc"), SENT), "the shape the bridge wants");
    }

    @Test
    void onlyThatSitesCookies() {
        assertTrue(BridgeCookies.belongs(".linkedin.com", "linkedin.com"));
        assertTrue(BridgeCookies.belongs("www.linkedin.com", "linkedin.com"));
        assertFalse(BridgeCookies.belongs("notlinkedin.com", "linkedin.com"));
    }
}
