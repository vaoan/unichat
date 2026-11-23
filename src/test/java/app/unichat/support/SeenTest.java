package app.unichat.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeenTest {
    private final Seen seen = Seen.get();

    @AfterEach
    void back() {
        Seen.reset(true);
    }

    @Test
    void startsAsSet() {
        Seen.reset(true);
        assertTrue(seen.now());
        Seen.reset(false);
        assertFalse(seen.now());
    }

    @Test
    void windowsHiddenIsUnseen() {
        Seen.reset(true);
        seen.windows(false);
        assertFalse(seen.now(), "every window in the tray");
        seen.windows(true);
        assertTrue(seen.now(), "a window shown again");
    }

    @Test
    void screenAwayIsUnseen() {
        Seen.reset(true);
        seen.screenAway(true);
        assertFalse(seen.now(), "locked, or the display asleep, with the window shown");
        seen.screenAway(false);
        assertTrue(seen.now(), "unlocked with the window shown");
    }

    @Test
    void unlockingWithTheWindowInTheTrayStaysUnseen() {
        Seen.reset(true);
        seen.windows(false);
        seen.screenAway(true);
        seen.screenAway(false);
        assertFalse(seen.now(), "the screen is back but no window is");
    }

    @Test
    void listenersHearOnlyChanges() {
        Seen.reset(true);
        List<Boolean> heard = new ArrayList<>();
        Consumer<Boolean> l = heard::add;
        seen.addListener(l);
        try {
            seen.windows(false);
            seen.windows(false);
            seen.screenAway(true); // still unseen: no change
            seen.windows(true);    // still away: no change
            seen.screenAway(false);
        } finally {
            seen.removeListener(l);
        }
        seen.windows(false); // no longer listening
        assertEquals(List.of(false, true), heard);
    }

    @Test
    void theScreenSourceOfNoneNeverSaysAway() {
        Seen.reset(true);
        ScreenAway.none().start(seen::screenAway);
        assertTrue(seen.now());
    }
}
