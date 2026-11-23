package app.unichat.ui.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real message-only window and its registrations, on Windows (CI's windows-latest job). */
@EnabledOnOs(OS.WINDOWS)
class WindowsScreenTest {
    @Test
    void itRegistersAndStops() throws Exception {
        WindowsScreen screen = new WindowsScreen();
        screen.start(away -> { });
        assertTrue(screen.registered().get(), "the window is made and both notifications registered");
        assertTrue(screen.thread().isAlive(), "its thread waits on GetMessage");
        assertTrue(screen.thread().isDaemon(), "it never keeps Unichat from quitting");
        screen.stop();
        screen.thread().join(); // lesson-ok: test-future-join (a Thread's join, ends once the window is destroyed)
        assertFalse(screen.thread().isAlive());
    }

    @Test
    void twoWatchesInOneProcessEachRegister() throws Exception {
        WindowsScreen first = new WindowsScreen();
        WindowsScreen second = new WindowsScreen();
        first.start(away -> { });
        second.start(away -> { });
        try {
            assertTrue(first.registered().get());
            assertTrue(second.registered().get(), "a second window class of its own");
        } finally {
            first.stop();
            second.stop();
            first.thread().join(); // lesson-ok: test-future-join (a Thread's join)
            second.thread().join(); // lesson-ok: test-future-join (a Thread's join)
        }
    }
}
