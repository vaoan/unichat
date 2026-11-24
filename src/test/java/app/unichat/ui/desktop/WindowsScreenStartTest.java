package app.unichat.ui.desktop;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;
import app.unichat.Present;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WindowsScreen}'s thread with a fake window, so it runs on every OS. */
class WindowsScreenStartTest {
    @Test
    void aRegistrationThatFailsLeavesTheScreenHere() throws Exception {
        List<Boolean> told = new CopyOnWriteArrayList<>();
        List<String> log = new CopyOnWriteArrayList<>();
        WindowsScreen screen = new WindowsScreen(new WindowsScreen.MessageWindow() {
            @Override
            public void open(WindowsScreen.Handler onMessage) {
                throw new IllegalStateException("WTSRegisterSessionNotification failed");
            }

            @Override
            public void run() {
                throw new AssertionError("no loop without a window");
            }

            @Override
            public void close() { /* the fake ignores it */ }
        }, log::add);
        screen.start(told::add);
        assertFalse(screen.registered().get(), "not registered");
        Present.of(screen.thread()).join(); // lesson-ok: test-future-join (a Thread's join, interruptible)
        assertEquals(List.of(false), told, "not away, said once");
        assertEquals(1, log.size(), "one log line: " + log);
        assertTrue(log.get(0).contains("WTSRegisterSessionNotification failed"), "it says why: " + log);
    }

    @Test
    void theWindowsMessagesReachTheListener() throws Exception {
        List<Boolean> told = new CopyOnWriteArrayList<>();
        List<String> log = new CopyOnWriteArrayList<>();
        WindowsScreen screen = new WindowsScreen(new WindowsScreen.MessageWindow() {
            @SuppressFBWarnings(value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR", justification = "set by open(), which WindowsScreen calls before run()")
            @SuppressWarnings("NullAway.Init") // set by open(), which WindowsScreen calls before run()
            private WindowsScreen.Handler handler;

            @Override
            public void open(WindowsScreen.Handler onMessage) {
                handler = onMessage;
            }

            @Override
            public void run() {
                handler.message(ScreenMessages.WM_WTSSESSION_CHANGE, ScreenMessages.WTS_SESSION_LOCK,
                        ScreenMessages.NO_DISPLAY_STATE);
                handler.message(0x0010, 0, ScreenMessages.NO_DISPLAY_STATE); // not about the screen
                handler.message(ScreenMessages.WM_WTSSESSION_CHANGE, ScreenMessages.WTS_SESSION_UNLOCK,
                        ScreenMessages.NO_DISPLAY_STATE);
            }

            @Override
            public void close() { /* the fake ignores it */ }
        }, log::add);
        screen.start(told::add);
        assertTrue(screen.registered().get());
        Present.of(screen.thread()).join(); // lesson-ok: test-future-join (a Thread's join)
        assertEquals(List.of(true, false, false), told, "locked, then unlocked, then here as the watch ends");
        assertEquals(List.of(), log);
    }

    @Test
    void aListenerThatThrowsDoesNotEndTheWatch() throws Exception {
        List<Boolean> told = new CopyOnWriteArrayList<>();
        WindowsScreen screen = new WindowsScreen(new WindowsScreen.MessageWindow() {
            @SuppressFBWarnings(value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR", justification = "set by open(), which WindowsScreen calls before run()")
            @SuppressWarnings("NullAway.Init") // set by open(), which WindowsScreen calls before run()
            private WindowsScreen.Handler handler;

            @Override
            public void open(WindowsScreen.Handler onMessage) {
                handler = onMessage;
            }

            @Override
            public void run() {
                handler.message(ScreenMessages.WM_WTSSESSION_CHANGE, ScreenMessages.WTS_SESSION_LOCK,
                        ScreenMessages.NO_DISPLAY_STATE);
                handler.message(ScreenMessages.WM_WTSSESSION_CHANGE, ScreenMessages.WTS_SESSION_UNLOCK,
                        ScreenMessages.NO_DISPLAY_STATE);
            }

            @Override
            public void close() { /* the fake ignores it */ }
        }, line -> { });
        screen.start(a -> {
            told.add(a);
            if (a) throw new IllegalStateException("a listener's bug");
        });
        Present.of(screen.thread()).join(); // lesson-ok: test-future-join (a Thread's join)
        assertEquals(List.of(true, false, false), told, "the unlock still arrives");
    }
    @Test
    void aLoopThatEndsAfterALockLeavesTheScreenHere() throws Exception {
        List<Boolean> told = new CopyOnWriteArrayList<>();
        List<String> log = new CopyOnWriteArrayList<>();
        WindowsScreen screen = new WindowsScreen(new WindowsScreen.MessageWindow() {
            @SuppressFBWarnings(value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR", justification = "set by open(), which WindowsScreen calls before run()")
            @SuppressWarnings("NullAway.Init") // set by open(), which WindowsScreen calls before run()
            private WindowsScreen.Handler handler;

            @Override
            public void open(WindowsScreen.Handler onMessage) {
                handler = onMessage;
            }

            @Override
            public void run() {
                handler.message(ScreenMessages.WM_WTSSESSION_CHANGE, ScreenMessages.WTS_SESSION_LOCK,
                        ScreenMessages.NO_DISPLAY_STATE);
                throw new IllegalStateException("GetMessageW failed (error 1400)");
            }

            @Override
            public void close() { /* the fake ignores it */ }
        }, log::add);
        screen.start(told::add);
        Present.of(screen.thread()).join(); // lesson-ok: test-future-join (a Thread's join)
        assertEquals(List.of(true, false), told, "no watch left to say unlocked: the screen counts as here");
        assertEquals(1, log.size(), "one log line: " + log);
        assertTrue(log.get(0).contains("GetMessageW failed (error 1400)"), "it says why: " + log);
    }
}
