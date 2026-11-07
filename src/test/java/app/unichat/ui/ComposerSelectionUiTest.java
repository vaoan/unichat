package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Shift and an arrow select all the way, however many lines the text has. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ComposerSelectionUiTest {
    private static final String TEXT = "uno\ndos\ntres";

    private static <T> T fx(Callable<T> c) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(c.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(15, TimeUnit.SECONDS);
    }

    private static void shift(TextArea box, KeyCode arrow) {
        Event.fireEvent(box, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", arrow, true, false, false, false));
    }

    @Test
    void shiftAndAnArrowSelectFromTheCursorToThatEnd() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        fx(() -> {
            Composer composer = new Composer(new MockChatService(), x -> { });
            composer.setChat("!familia:demo");
            Stage stage = new Stage();
            stage.setScene(new Scene(composer, 700, 160));
            stage.show();
            TextArea box = (TextArea) composer.lookup(".text-area");
            box.setText(TEXT);

            box.positionCaret(5); // inside "dos"
            shift(box, KeyCode.UP);
            assertEquals("uno\nd", box.getSelectedText());
            assertEquals(0, box.getCaretPosition());

            shift(box, KeyCode.DOWN);
            assertEquals("os\ntres", box.getSelectedText(), "from the same place to the other end");
            assertEquals(TEXT.length(), box.getCaretPosition());

            box.positionCaret(5);
            shift(box, KeyCode.DOWN);
            assertEquals("os\ntres", box.getSelectedText());
            assertEquals(TEXT, box.getText(), "only selects");
            stage.close();
            return null;
        });
    }
}
