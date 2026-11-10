package app.unichat.ui.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockIconTest {
    @Test
    void itFollowsTheSetting() {
        assertTrue(DockIcon.wanted(true, true));
        assertFalse(DockIcon.wanted(false, true), "the default: only in the menu bar");
    }

    @Test
    void withoutAMenuBarIconTheDockIsTheOnlyWayIn() {
        assertTrue(DockIcon.wanted(false, false));
    }

    /**
     * Since macOS 14 an app activates only when the active one yields (the menu-bar helper does after a click), through
     * {@code activate}; the old {@code activateIgnoringOtherApps:} is ignored, so a notification's click left the window
     * without the focus. Asking first matters: calling a selector NSApplication lacks (macOS 13) ends the program.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void itKnowsWhichWayThisMacActivates() {
        assertTrue(DockIcon.responds("activateIgnoringOtherApps:"));
        assertFalse(DockIcon.responds("noSuchSelectorAtAll:"));
        int major = Integer.parseInt(System.getProperty("os.version").split("\\.")[0]);
        assertEquals(major >= 14, DockIcon.responds("activate"), "macOS " + major);
    }
}
