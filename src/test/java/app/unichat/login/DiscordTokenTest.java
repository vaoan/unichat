package app.unichat.login;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordTokenTest {
    @Test
    void acceptsDiscordTokenShapes() {
        assertTrue(DiscordToken.isToken("MTIzNDU2Nzg5MDEyMzQ1Njc4OQ.GaBcDe.abcdefghijklmnopqrstuvwxyz0123456789AB"));
        assertTrue(DiscordToken.isToken("mfa.abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMN_-"));
    }

    @Test
    void rejectsAnythingElse() {
        assertFalse(DiscordToken.isToken(null));
        assertFalse(DiscordToken.isToken(""));
        assertFalse(DiscordToken.isToken("null"));
        assertFalse(DiscordToken.isToken("not a token"));
        assertFalse(DiscordToken.isToken("a.b.c"));
        assertFalse(DiscordToken.isToken("MTIz.GaBcDe.abc def"), "no spaces (it goes into a bot command)");
    }
}
