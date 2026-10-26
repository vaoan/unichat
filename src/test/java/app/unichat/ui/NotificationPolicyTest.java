package app.unichat.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationPolicyTest {
    @Test
    void archivedChatsNotifyOnlyWhenEnabled() {
        assertFalse(NotificationPolicy.shouldNotify(true, false, false), "archived, setting off");
        assertTrue(NotificationPolicy.shouldNotify(true, true, false), "archived, setting on");
    }

    @Test
    void regularChatsAlwaysNotify() {
        assertTrue(NotificationPolicy.shouldNotify(false, false, false));
        assertTrue(NotificationPolicy.shouldNotify(false, true, false));
    }

    @Test
    void neverWhileReadingThatChat() {
        assertFalse(NotificationPolicy.shouldNotify(false, true, true));
        assertFalse(NotificationPolicy.shouldNotify(true, true, true));
    }
}
