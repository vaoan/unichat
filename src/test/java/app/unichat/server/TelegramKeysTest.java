package app.unichat.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TelegramKeysTest {
    private static final String HASH = "0123456789abcdef0123456789ABCDEF";

    @Test
    void realLookingKeysPassEvenWithStraySpaces() {
        assertNull(TelegramKeys.problem("1234567", HASH));
        assertNull(TelegramKeys.problem(" 1234567\n", " " + HASH + " "));
    }

    @Test
    void eachMistakeIsNamed() {
        assertEquals("Falta el api_id.", TelegramKeys.problem("", HASH));
        assertEquals("Falta el api_id.", TelegramKeys.problem(null, HASH));
        assertEquals("Falta el api_hash.", TelegramKeys.problem("1234567", " "));
        assertNotNull(TelegramKeys.problem("abc", HASH));
        assertNotNull(TelegramKeys.problem("-5", HASH));
        assertNotNull(TelegramKeys.problem("99999999999", HASH), "the bridge reads it as a 32-bit number");
        assertNotNull(TelegramKeys.problem("1234567", "short"));
        assertNotNull(TelegramKeys.problem("1234567", HASH.replace('a', 'z')), "not hexadecimal");
    }
}
