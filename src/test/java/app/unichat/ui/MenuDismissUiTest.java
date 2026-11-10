package app.unichat.ui;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A menu closes with a click outside it or Esc, also while its window isn't the active one (as a test's window never
 * is, and Unichat's often isn't): JavaFX's auto-hide left it open. Real display: the real mouse and keyboard.
 */
// The real mouse and keyboard and macOS activating the app: only on the real display, so only when the person runs it.
@EnabledIfSystemProperty(named = "unichat.realInputTests", matches = "true")
class MenuDismissUiTest {
    @Test
    void aClickOutsideOrEscapeClosesTheMenu() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("menus");
        app.unichat.model.MediaLibrary library = new app.unichat.model.MediaLibrary(dir.resolve("media-library.json"));
        Stage[] stage = new Stage[1];
        Button emoji = FxWait.fx(() -> {
            Button b = EmojiPicker.button("😀", e -> { }, library, t -> { });
            VBox root = new VBox(b);
            root.setPrefSize(400, 300);
            stage[0] = new Stage();
            stage[0].setScene(new Scene(root));
            stage[0].setX(200);
            stage[0].setY(200);
            stage[0].show();
            AppWindows.install(stage[0]);
            return b;
        });
        FxWait.settled();
        for (String how : new String[] {"a click outside", "Esc"}) {
            FxWait.fx(() -> {
                var p = emoji.localToScreen(5, 5);
                Event.fireEvent(emoji, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, p.getX(), p.getY(), false, null));
                return null;
            });
            FxWait.until("the menu is open", () -> open() != null);
            FxWait.fx(() -> {
                Robot r = new Robot();
                if (how.equals("Esc")) {
                    r.keyPress(KeyCode.ESCAPE);
                    r.keyRelease(KeyCode.ESCAPE);
                } else {
                    r.mouseMove(stage[0].getX() + 300, stage[0].getY() + 250);
                    r.mouseClick(MouseButton.PRIMARY);
                }
                return null;
            });
            FxWait.until(how + " closes it", () -> open() == null);
        }
        FxWait.fx(() -> { stage[0].hide(); return null; });
        assertFalse(FxWait.fx(() -> open() != null));
    }

    private static ContextMenu open() throws Exception {
        return FxWait.fx(() -> Window.getWindows().stream().filter(w -> w instanceof ContextMenu && w.isShowing())
                .map(w -> (ContextMenu) w).findFirst().orElse(null));
    }
}
