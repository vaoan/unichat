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
}
