package app.unichat.ui.desktop;

import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

import static app.unichat.ui.desktop.ScreenMessages.PBT_POWERSETTINGCHANGE;
import static app.unichat.ui.desktop.ScreenMessages.WM_POWERBROADCAST;
import static app.unichat.ui.desktop.ScreenMessages.WM_WTSSESSION_CHANGE;
import static app.unichat.ui.desktop.ScreenMessages.WTS_SESSION_LOCK;
import static app.unichat.ui.desktop.ScreenMessages.WTS_SESSION_UNLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What each Windows message says about the screen: locked and display off count apart, either one is away. */
class ScreenMessagesTest {
    private static @Nullable Boolean session(ScreenMessages m, int event) {
        return m.away(WM_WTSSESSION_CHANGE, event, ScreenMessages.NO_DISPLAY_STATE);
    }

    private static @Nullable Boolean display(ScreenMessages m, int state) {
        return m.away(WM_POWERBROADCAST, PBT_POWERSETTINGCHANGE, state);
    }

    @Test
    void lockThenUnlock() {
        ScreenMessages m = new ScreenMessages();
        assertEquals(true, session(m, WTS_SESSION_LOCK));
        assertEquals(false, session(m, WTS_SESSION_UNLOCK));
    }

    @Test
    void displayOffOnAndDimmed() {
        ScreenMessages m = new ScreenMessages();
        assertEquals(true, display(m, 0), "off");
        assertEquals(false, display(m, 1), "on");
        assertEquals(true, display(m, 0));
        assertEquals(false, display(m, 2), "dimmed still shows Unichat");
    }

    @Test
    void lockedStaysAwayWhenTheDisplayComesOnUntilUnlocked() {
        ScreenMessages m = new ScreenMessages();
        assertEquals(true, session(m, WTS_SESSION_LOCK));
        assertEquals(true, display(m, 0));
        assertEquals(true, display(m, 1), "the lock screen shows, not Unichat");
        assertEquals(false, session(m, WTS_SESSION_UNLOCK));
    }

    @Test
    void displayOffStaysAwayWhenUnlockedUntilOn() {
        ScreenMessages m = new ScreenMessages();
        assertEquals(true, display(m, 0));
        assertEquals(true, session(m, WTS_SESSION_LOCK));
        assertEquals(true, session(m, WTS_SESSION_UNLOCK));
        assertEquals(false, display(m, 1));
    }

    @Test
    void otherMessagesSayNothing() {
        ScreenMessages m = new ScreenMessages();
        assertNull(session(m, 1), "WTS_CONSOLE_CONNECT");
        assertNull(m.away(WM_POWERBROADCAST, 0x0004, ScreenMessages.NO_DISPLAY_STATE), "PBT_APMSUSPEND");
        assertNull(display(m, ScreenMessages.NO_DISPLAY_STATE), "a setting that isn't the display's");
        assertNull(display(m, 3), "a display state Windows doesn't send");
        assertNull(m.away(0x0010, 0, 0), "WM_CLOSE");
        assertEquals(true, session(m, WTS_SESSION_LOCK), "and what was said before still counts");
    }
}
