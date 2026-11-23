package app.unichat;

import app.unichat.ui.desktop.MacScreen;
import app.unichat.ui.desktop.WindowsScreen;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Who says the screen is locked or asleep is chosen by the OS. */
class ScreenAwaySourceTest {
    @Test
    void onMacTheMenuBarHelperSays() {
        assertInstanceOf(MacScreen.class, UnichatApp.screenAway(true, false));
    }

    @Test
    void onWindowsTheSessionAndDisplayWatchSays() {
        assertInstanceOf(WindowsScreen.class, UnichatApp.screenAway(false, true));
    }

    @Test
    void elsewhereNobodySaysAndTheScreenIsAlwaysHere() {
        var source = UnichatApp.screenAway(false, false);
        assertFalse(source instanceof MacScreen);
        assertFalse(source instanceof WindowsScreen);
        List<Boolean> told = new ArrayList<>();
        source.start(told::add);
        assertEquals(List.of(), told, "nothing told: the screen stays here");
    }
}
