package app.unichat.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdatePolicyTest {
    private static final long HOUR = 3_600_000L;
    /** 2026-11-08, any day works: the rules only look at differences. */
    private static final long NOW = 1_794_000_000_000L;

    @Test
    void readsTheTagAndPageOfGitHubsAnswer() {
        String json = """
                {"url":"https://api.github.com/repos/vaoan/unichat/releases/1","tag_name":"v1.2.0",
                 "name":"Unichat 1.2","draft":false,"prerelease":false,
                 "html_url":"https://github.com/vaoan/unichat/releases/tag/v1.2.0","assets":[]}""";
        assertEquals(new Release("v1.2.0", "https://github.com/vaoan/unichat/releases/tag/v1.2.0"), UpdatePolicy.parse(json));
    }

    @Test
    void anAnswerWithoutATagIsNoRelease() {
        assertNull(UpdatePolicy.parse("{\"message\":\"Not Found\",\"documentation_url\":\"https://docs.github.com\"}"));
        assertNull(UpdatePolicy.parse("{\"tag_name\":\"  \"}"));
        assertNull(UpdatePolicy.parse("<html>rate limited</html>"));
        assertNull(UpdatePolicy.parse(""));
    }

    @Test
    void onlyGitHubsOwnPageIsEverOpened() {
        assertEquals(UpdatePolicy.RELEASES, UpdatePolicy.parse("{\"tag_name\":\"v2\",\"html_url\":\"javascript:alert(1)\"}").url());
        assertEquals(UpdatePolicy.RELEASES, UpdatePolicy.parse("{\"tag_name\":\"v2\",\"html_url\":\"https://github.com.evil.test/x\"}").url());
        assertEquals(UpdatePolicy.RELEASES, UpdatePolicy.parse("{\"tag_name\":\"v2\"}").url());
    }

    @Test
    void asksAtMostOnceADay() {
        assertTrue(UpdatePolicy.due(0, NOW), "never asked");
        assertFalse(UpdatePolicy.due(NOW - HOUR, NOW));
        assertFalse(UpdatePolicy.due(NOW - 23 * HOUR, NOW));
        assertTrue(UpdatePolicy.due(NOW - 24 * HOUR, NOW));
        assertTrue(UpdatePolicy.due(NOW - 30 * 24 * HOUR, NOW));
    }

    @Test
    void aClockThatWentBackDoesntSilenceItForever() {
        assertTrue(UpdatePolicy.due(NOW + 5 * 24 * HOUR, NOW));
    }

    @Test
    void showsOnlyANewerVersionNotDismissed() {
        Release r = new Release("v1.2.0", UpdatePolicy.RELEASES);
        assertTrue(UpdatePolicy.shouldShow(r, "1.0.0", null));
        assertFalse(UpdatePolicy.shouldShow(r, "1.2.0", null), "the one running");
        assertFalse(UpdatePolicy.shouldShow(r, "1.3.0", null), "older than the one running");
        assertFalse(UpdatePolicy.shouldShow(r, "1.0.0", "v1.2.0"), "dismissed");
        assertFalse(UpdatePolicy.shouldShow(r, "1.0.0", "1.2"), "dismissed, written another way");
        assertTrue(UpdatePolicy.shouldShow(new Release("v1.3.0", UpdatePolicy.RELEASES), "1.0.0", "v1.2.0"), "a later one than dismissed");
        assertFalse(UpdatePolicy.shouldShow(null, "1.0.0", null));
        assertFalse(UpdatePolicy.shouldShow(new Release("v1.1.0-beta.1", UpdatePolicy.RELEASES), "1.1.0", null));
    }
}
