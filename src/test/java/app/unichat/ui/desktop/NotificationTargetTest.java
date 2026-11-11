package app.unichat.ui.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NotificationTargetTest {
    @Test
    void aChatOrOneMessageInIt() {
        assertEquals("!abc:localhost", NotificationTarget.of("!abc:localhost", null));
        String target = NotificationTarget.of("!abc:localhost", "$ev-1_2:localhost");
        assertEquals("!abc:localhost", NotificationTarget.chat(target));
        assertEquals("$ev-1_2:localhost", NotificationTarget.message(target));
        assertNull(NotificationTarget.message("!abc:localhost"));
    }
}
