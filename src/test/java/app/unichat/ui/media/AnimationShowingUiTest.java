package app.unichat.ui.media;

import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An animation runs only while its window shows: a closed window keeps its scene, and a GIF in a closed photo viewer
 * went on running for good, held by JavaFX's timer with its whole window and its share of the animation budget.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class AnimationShowingUiTest {
    @Test
    void aClosedWindowsAnimationStopsAndGivesBackItsBudget() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        AnimatedImage.Frames frames = new AnimatedImage.Frames(
                List.of(new WritableImage(40, 40), new WritableImage(40, 40), new WritableImage(40, 40)), List.of(40, 40, 40));
        long before = FxWait.fx(AnimationBudget.SHARED::used);
        Stage[] stage = new Stage[1];
        FxWait.fx(() -> {
            ImageView view = new ImageView();
            stage[0] = new Stage();
            stage[0].setOpacity(0);
            stage[0].setScene(new Scene(new StackPane(view), 100, 100));
            stage[0].show();
            AnimatedImage.play(view, frames);
            return null;
        });
        assertEquals(before + frames.bytes(), FxWait.fx(AnimationBudget.SHARED::used), "playing while it shows");

        FxWait.fx(() -> { stage[0].close(); return null; });
        assertEquals(before, FxWait.fx(AnimationBudget.SHARED::used), "stopped once its window closed");

        FxWait.fx(() -> { stage[0].show(); return null; });
        assertEquals(before + frames.bytes(), FxWait.fx(AnimationBudget.SHARED::used), "playing again when shown again");
        FxWait.fx(() -> { stage[0].close(); return null; });
    }
}
