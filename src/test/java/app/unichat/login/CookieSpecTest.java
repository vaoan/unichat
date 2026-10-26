package app.unichat.login;

import app.unichat.model.Network;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CookieSpecTest {

    @Test
    void instagramRequiresSessionIdAndCsrf() {
        assertEquals(Set.of("sessionid", "csrftoken"), CookieSpec.forNetwork(Network.INSTAGRAM).required());
    }

    @Test
    void selectKeepsRequiredAndOptionalDropsOthers() {
        SessionCookies s = CookieSpec.forNetwork(Network.INSTAGRAM).select(Map.of(
                "sessionid", "a", "csrftoken", "b", "ds_user_id", "c", "unrelated", "d"));
        assertFalse(s.values().containsKey("unrelated"));
        assertEquals(3, s.values().size());
        assertTrue(s.complete());
    }

    @Test
    void missingNamesWhenIncomplete() {
        CookieSpec spec = CookieSpec.forNetwork(Network.INSTAGRAM);
        Map<String, String> in = Map.of("csrftoken", "b");
        assertEquals(List.of("sessionid"), spec.missing(in));
        assertFalse(spec.select(in).complete());
    }

    @Test
    void bridgeJsonIsNameValueObject() throws Exception {
        SessionCookies s = CookieSpec.forNetwork(Network.INSTAGRAM)
                .select(Map.of("sessionid", "x", "csrftoken", "y"));
        JsonNode n = new ObjectMapper().readTree(s.toBridgeJson());
        assertTrue(n.isObject());
        assertEquals("x", n.get("sessionid").asText());
        assertEquals("y", n.get("csrftoken").asText());
    }

    @Test
    void domainMatchForEachNetwork() {
        assertTrue(CookieSpec.forNetwork(Network.MESSENGER).isForNetwork(".facebook.com"));
        assertFalse(CookieSpec.forNetwork(Network.MESSENGER).isForNetwork(".instagram.com"));
        CookieSpec g = CookieSpec.forNetwork(Network.GOOGLE_MESSAGES);
        assertTrue(g.isForNetwork(".google.com"));
        assertTrue(g.isForNetwork("messages.google.com"));
        assertFalse(g.isForNetwork("notgoogle.com"));
        assertTrue(CookieSpec.forNetwork(Network.X).isForNetwork(".x.com"));
        assertTrue(CookieSpec.forNetwork(Network.LINKEDIN).isForNetwork("www.linkedin.com"));
    }

    @Test
    void networkTable() {
        assertEquals(Set.of("c_user", "xs"), CookieSpec.forNetwork(Network.MESSENGER).required());
        assertEquals(Set.of("datr", "fr"), CookieSpec.forNetwork(Network.MESSENGER).optional());
        assertEquals(Set.of("auth_token", "ct0"), CookieSpec.forNetwork(Network.X).required());
        assertEquals(Set.of("li_at", "JSESSIONID"), CookieSpec.forNetwork(Network.LINKEDIN).required());
        assertEquals(Set.of("SAPISID", "SID", "__Secure-1PSID"),
                CookieSpec.forNetwork(Network.GOOGLE_MESSAGES).required());
        assertEquals(Set.of("HSID", "SSID", "APISID", "__Secure-3PSID"),
                CookieSpec.forNetwork(Network.GOOGLE_MESSAGES).optional());
        assertThrows(IllegalArgumentException.class, () -> CookieSpec.forNetwork(Network.WHATSAPP));
    }
}
