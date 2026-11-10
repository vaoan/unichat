package app.unichat.ui.desktop;

import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;

/** ⌘` from one window brings the next one forward, and ⌘⇧` the previous. Real display: focus is the point. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class AppWindowsUiTest {
    @Test
    void commandBackquoteMovesBetweenTheWindows() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        Stage[] s = FxWait.fx(() -> {
            Stage main = new Stage();
            main.setScene(new Scene(new StackPane(), 300, 200));
            main.setTitle("main");
            main.show();
            AppWindows.install(main);
            Stage photo = new Stage();
            photo.setScene(new Scene(new StackPane(), 300, 200));
            photo.setTitle("photo");
            photo.show();
            main.toFront();
            main.requestFocus();
            return new Stage[] {main, photo};
        });
        // Every window takes the shortcut (a test process never gets the focus, so it's checked by what's reached).
        for (Stage st : s) {
            boolean[] reached = {false};
            FxWait.fx(() -> {
                st.getScene().getRoot().addEventHandler(KeyEvent.KEY_PRESSED, e -> reached[0] = true);
                Event.fireEvent(st.getScene().getRoot(),
                        // The shortcut key: ⌘ on a Mac, Ctrl elsewhere; both held so it's pressed on any system.
                        new KeyEvent(KeyEvent.KEY_PRESSED, "`", "`", KeyCode.BACK_QUOTE, false, true, false, true));
                return null;
            });
            org.junit.jupiter.api.Assertions.assertFalse(reached[0], "taken by the shortcut in " + st.getTitle());
        }
        // Other tests' windows may be open too: the order is checked among these two. Which one has the focus is up to
        // the system, so what's expected is worked out from it in the same step.
        java.util.List<javafx.stage.Window> mine = java.util.List.of(s);
        for (boolean backwards : new boolean[] {false, true}) {
            Stage[] expectedAndGot = FxWait.fx(() -> {
                Stage other = s[1].isFocused() ? s[0] : s[1];
                return new Stage[] {other, AppWindows.next(mine, backwards)};
            });
            org.junit.jupiter.api.Assertions.assertSame(expectedAndGot[0], expectedAndGot[1], "with two, either way is the other one");
        }
        FxWait.fx(() -> { s[0].hide(); s[1].hide(); return null; });
    }
}
