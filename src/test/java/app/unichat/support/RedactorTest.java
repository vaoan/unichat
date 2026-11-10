package app.unichat.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactorTest {
    private static void hidden(String line, String... secrets) {
        String out = Redactor.redact(line);
        for (String s : secrets) assertFalse(out.contains(s), "'" + s + "' still in: " + out);
    }

    @Test
    void matrixIdsAndMedia() {
        hidden("Could not join !uomHhLjEDl914pAUOaOmFp6K47NZPfOUUoevmG-awNM:localhost: M_FORBIDDEN User @yo:localhost",
                "uomHhLjEDl914p", "@yo:");
        hidden("Could not decode mxc://localhost/AbCdEf123456: bad", "AbCdEf123456");
        hidden("redacted $Xyz123abc_event:localhost", "Xyz123abc_event");
        hidden("puppet @whatsapp_573001112233:localhost joined", "573001112233");
    }

    @Test
    void phonesEmailsAndTokens() {
        hidden("login +57 300 111 2233 ok", "300 111 2233");
        hidden("jid 573009998877@s.whatsapp.net", "573009998877");
        hidden("telegram user 900000042 logged in", "900000042");
        hidden("mail ana.perez@gmail.com", "ana.perez");
        hidden("GET /sync?access_token=syt_abc123def456&since=s1", "syt_abc123def456");
        hidden("Authorization: Bearer syt_abc123def456", "syt_abc123def456");
    }

    @Test
    void secretJsonFieldsAndCookies() {
        hidden("{\"refresh_token\":\"rt_secret1\",\"client_secret\":\"cs_secret2\",\"Session\":\"ss_secret3\"}",
                "rt_secret1", "cs_secret2", "ss_secret3");
        hidden("{\"cookies\":{\"li_at\":\"AQEDAT_li\",\"JSESSIONID\":\"ajax:123\"},\"cookie\":\"c_user=42; xs=XsValue\"}",
                "AQEDAT_li", "ajax:123", "XsValue");
        hidden("request Cookie: li_at=AQEDAT_li; JSESSIONID=ajax:123", "AQEDAT_li", "ajax:123");
        hidden("Set-Cookie: sessionid=SidValue; Path=/; HttpOnly", "SidValue");
        hidden("Authorization: Basic dXNlcjpwYXNz", "dXNlcjpwYXNz");
    }

    @Test
    void secretYamlKeysAndQueryParameters() {
        hidden("  as_token: AsTokenValue", "AsTokenValue");
        hidden("hs_token: HsTokenValue", "HsTokenValue");
        hidden("    shared_secret: \"SharedValue\"", "SharedValue");
        hidden("password: Pa55word", "Pa55word");
        hidden("GET /login?token=QueryTok1&next=/", "QueryTok1");
        hidden("GET /x?a=1&token=QueryTok2", "QueryTok2");
        hidden("POST /refresh?grant=1&refresh_token=QueryTok3 200", "QueryTok3");
    }

    @Test
    void plainSentencesAboutTokensAndSessionsStayUnchanged() {
        for (String line : new String[]{"session started", "token count 3", "Password reset requested for the account",
                "Failed to get session: context canceled", "{\"session_id_count\":3,\"message\":\"Cookie jar loaded\"}"}) {
            assertEquals(line, Redactor.redact(line));
        }
    }

    @Test
    void personalJsonFieldsInBridgeLogs() {
        String line = "{\"level\":\"debug\",\"name\":\"Ana Pérez\",\"displayname\":\"Pedro\",\"body\":\"hola, nos vemos\","
                + "\"chat_name\":\"Familia\",\"topic\":\"cumple\",\"message\":\"Creating Matrix room\",\"time\":\"2026-10-26T08:28:31Z\"}";
        String out = Redactor.redact(line);
        for (String s : new String[]{"Ana Pérez", "Pedro", "hola, nos vemos", "Familia", "cumple"}) {
            assertFalse(out.contains(s), s + " in " + out);
        }
        assertTrue(out.contains("Creating Matrix room"), "what happened stays readable: " + out);
        assertTrue(out.contains("\"level\":\"debug\""), out);
    }

    @Test
    void ordinaryLinesStayReadable() {
        String line = "Matrix sync failed (3): java.net.ConnectException: Connection refused on port 8008";
        assertEquals(line, Redactor.redact(line));
        String ts = "2026-10-26 12:36:08.319 UTC [357] LOG:  syncing data directory";
        assertEquals(ts, Redactor.redact(ts));
        String iso = "{\"time\":\"2026-10-26T09:51:20.1234567-05:00\",\"message\":\"Bridge started!\"}";
        assertEquals(iso, Redactor.redact(iso), "fractions of a second are not phone numbers");
    }

    @Test
    void roomAndEventIdsWithoutAServerPartAreHidden() {
        // Room version 12 ids (tuwunel) have no ":server" part; event ids never have one since v3.
        String out = Redactor.redact("Could not join !QFT9dKh0_TUOmwxwp_6Gh4vgn-abzhHhRCiualC9di8: java.net.ConnectException "
                + "event $Ab3dEfGhIjKlMnOpQrStUvWxYz0123456789_-abcd sent");
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("QFT9dKh0"), out);
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("Ab3dEfGh"), out);
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("java.net.ConnectException"), out);
    }
}
