package app.unichat.ui.desktop;

import org.junit.jupiter.api.Test;

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
}
