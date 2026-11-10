package app.unichat.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppWindowsTest {
    private static KeyEvent key(KeyCode code, String text, boolean shortcut, boolean shift, boolean alt) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, text, text, code, shift, false, alt, shortcut);
    }

    @Test
    void theNextWindowGoesRoundBothWays() {
        assertEquals(1, AppWindows.step(0, 3, false));
        assertEquals(0, AppWindows.step(2, 3, false));
        assertEquals(2, AppWindows.step(0, 3, true));
    }

    @Test
    void commandBackquoteOrTheKeyBesideZOnASpanishKeyboard() {
        assertTrue(AppWindows.isCycleKey(key(KeyCode.BACK_QUOTE, "`", true, false, false)));
        assertTrue(AppWindows.isCycleKey(key(KeyCode.LESS, "<", true, false, false)));
        assertTrue(AppWindows.isCycleKey(key(KeyCode.UNDEFINED, "º", true, true, false)), "with Shift: backwards");
        assertFalse(AppWindows.isCycleKey(key(KeyCode.BACK_QUOTE, "`", false, false, false)), "a plain ` is typing");
        assertFalse(AppWindows.isCycleKey(key(KeyCode.A, "a", true, false, false)));
        assertFalse(AppWindows.isCycleKey(key(KeyCode.BACK_QUOTE, "`", true, false, true)), "⌥ makes another character");
    }

    @Test
    void aPhotoOrVideoWindowBringsTheDockIconSoCommandTabReachesIt() {
        assertTrue(DockIcon.regular(false, true, false, true), "main hidden, a photo open");
        assertTrue(DockIcon.regular(true, true, false, true));
        assertFalse(DockIcon.regular(true, false, false, true), "only the main window, no Dock icon chosen");
        assertTrue(DockIcon.regular(true, false, true, true), "chosen in Ajustes");
        assertFalse(DockIcon.regular(false, false, true, true), "nothing open");
        assertTrue(DockIcon.regular(true, false, false, false), "no menu bar icon: the Dock is the way back");
    }
}
