package app.unichat;

import app.unichat.ui.desktop.MacScreen;
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
    void elsewhereNobodySaysAndTheScreenIsAlwaysHere() {
        for (boolean windows : new boolean[] {true, false}) {
            var source = UnichatApp.screenAway(false, windows);
            assertFalse(source instanceof MacScreen);
            List<Boolean> told = new ArrayList<>();
            source.start(told::add);
            assertEquals(List.of(), told, "nothing told: the screen stays here");
        }
    }
}
