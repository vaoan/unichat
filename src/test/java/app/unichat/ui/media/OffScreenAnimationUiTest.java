package app.unichat.ui.media;

import app.unichat.TestPictures;
import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Only the animations that can be seen hold their frames: a sticker set of 50 big GIFs kept every tile's frames, moving
 * or not (past the budget, scrolled away), and ran a 1 GB heap out. Scrolled away they let go and keep the picture they
 * show; scrolled back they read their frames again from the file.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class OffScreenAnimationUiTest {
    private static final int COUNT = 30, SIDE = 64;

    @TempDir Path dir;

    @BeforeAll
    static void toolkit() throws Exception {
        CompletableFuture<Void> up = new CompletableFuture<>();
        try {
            Platform.startup(() -> up.complete(null));
        } catch (IllegalStateException running) {
            up.complete(null);
        }
        up.get();
        Platform.setImplicitExit(false);
    }

    @Test
    void scrolledAwayTheyLetGoOfTheirFramesAndReadThemAgainWhenBack() throws Exception {
        List<AnimatedImage.Frames> decoded = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) {
            decoded.add(AnimatedImage.decode(TestPictures.gif(dir.resolve("s" + i + ".gif"), SIDE, SIDE, 4), SIDE, SIDE));
        }
        List<ImageView> views = new ArrayList<>();
        Stage stage = FxWait.fx(() -> {
            VBox column = new VBox(4);
            for (AnimatedImage.Frames f : decoded) {
                ImageView v = new ImageView();
                views.add(v);
                column.getChildren().add(v);
            }
            ScrollPane scroll = new ScrollPane(column);
            Stage s = new Stage();
            s.setOpacity(0);
            s.setScene(new Scene(scroll, 200, 300));
            s.show();
            for (int i = 0; i < COUNT; i++) AnimatedImage.play(views.get(i), decoded.get(i));
            return s;
        });
        try {
            ImageView first = views.get(0), last = views.get(COUNT - 1);
            FxWait.until("the first ones move", () -> AnimatedImage.moving(first));
            FxWait.settled();
            assertFalse(FxWait.fx(() -> AnimatedImage.holdsFrames(last)), "out of view: no frames held");
            assertTrue(FxWait.fx(() -> last.getImage() != null), "but its picture still shows when scrolled to");

            FxWait.fx(() -> { ((ScrollPane) stage.getScene().getRoot()).setVvalue(1); return null; });
            FxWait.until("the last one moves, read again from its file", () -> AnimatedImage.moving(last));
            FxWait.until("the first one let go", () -> !AnimatedImage.holdsFrames(first));
            assertTrue(FxWait.fx(() -> first.getImage() != null), "keeping the picture it showed");
            long moving = FxWait.fx(() -> views.stream().filter(AnimatedImage::moving).count());
            assertTrue(moving < COUNT / 2, "only what's in view moves: " + moving);
        } finally {
            FxWait.fx(() -> { stage.close(); return null; });
        }
    }
}
