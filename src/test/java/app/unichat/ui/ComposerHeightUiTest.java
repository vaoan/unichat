package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The message box grows to show all it holds (up to a limit), whatever the text size or width: nothing cut. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ComposerHeightUiTest {
    private static final String LONG = "Una línea bastante larga que en una caja angosta ocupa dos líneas";

    private static double hidden(TextArea box) {
        ScrollPane sp = (ScrollPane) box.lookup(".scroll-pane");
        Region content = (Region) sp.getContent();
        return content.getHeight() - (sp.getHeight() - sp.snappedTopInset() - sp.snappedBottomInset());
    }

    @Test
    void everyLineShowsWithoutScrolling() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        Stage[] stage = new Stage[1];
        Composer[] composer = new Composer[1];
        TextArea box = FxWait.fx(() -> {
            Composer c = new Composer(new MockChatService(), x -> { });
            c.setChat("!familia:demo");
            stage[0] = new Stage();
            composer[0] = c;
            c.setMaxWidth(420);
            // Tall enough that the box (up to 40 % of it) never reaches its cap: there it can't shrink, and on Linux,
            // whose font is taller, 600 put it there.
            Scene scene = new Scene(new javafx.scene.layout.StackPane(c), 900, 950);
            scene.getRoot().setStyle("-fx-font-size: 21px;"); // 160 % text
            stage[0].setScene(scene);
            stage[0].show();
            TextArea t = (TextArea) c.lookup(".text-area");
            t.setText(LONG + "\nuno\ndos");
            return t;
        });
        FxWait.settled();
        FxWait.until("all of it on screen", () -> hidden(box) <= 1);
        double tall = FxWait.fx(box::getHeight);
        assertTrue(tall < 0.4 * 950 - 2, "the narrow box isn't at its cap (" + tall + " px), or it couldn't shrink");

        FxWait.fx(() -> { composer[0].setMaxWidth(880); return null; }); // wider: fewer lines, a shorter box
        FxWait.settled();
        FxWait.until("all of it on screen, wider", () -> hidden(box) <= 1);
        // Measured after the layout that re-wraps it: one more pass on some systems (Linux), so wait for it, not a time.
        FxWait.until("it shrinks back when it needs less", () -> box.getHeight() < tall);

        FxWait.fx(() -> { box.setText("hola"); return null; });
        FxWait.settled();
        double one = FxWait.fx(box::getHeight);
        FxWait.fx(() -> { box.setText("hola\n".repeat(40)); return null; });
        FxWait.settled();
        assertTrue(FxWait.fx(box::getHeight) < 950 * 0.45, "a long text stops growing and scrolls");
        assertTrue(FxWait.fx(box::getHeight) > one);
        FxWait.fx(() -> { stage[0].close(); return null; });
    }
}
