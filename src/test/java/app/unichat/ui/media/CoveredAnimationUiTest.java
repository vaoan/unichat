package app.unichat.ui.media;

import app.unichat.TestPictures;
import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behind a sheet in the window's overlay, animations stand still: every frame of a sticker under the card's shadow made
 * JavaFX draw the shadow again, and on the Mac the stickers flashed black. What's in the overlay itself still moves.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class CoveredAnimationUiTest {
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
    void behindASheetTheyStandStillAndMoveAgainWhenItCloses() throws Exception {
        AnimatedImage.Frames a = AnimatedImage.decode(TestPictures.gif(dir.resolve("a.gif"), 32, 32, 4), 32, 32);
        AnimatedImage.Frames b = AnimatedImage.decode(TestPictures.gif(dir.resolve("b.gif"), 32, 32, 4), 32, 32);
        ImageView behind = new ImageView(), inSheet = new ImageView();
        StackPane overlay = new StackPane(inSheet);
        Stage stage = FxWait.fx(() -> {
            Stage s = new Stage();
            s.setOpacity(0);
            s.setScene(new Scene(new StackPane(behind, overlay), 200, 200));
            s.show();
            AnimatedImage.play(behind, a);
            AnimatedImage.play(inSheet, b);
            return s;
        });
        try {
            FxWait.until("both move", () -> AnimatedImage.moving(behind) && AnimatedImage.moving(inSheet));
            FxWait.fx(() -> { AnimatedImage.cover(stage.getScene(), overlay); return null; });
            FxWait.until("the one behind stands still", () -> !AnimatedImage.moving(behind));
            assertTrue(FxWait.fx(() -> AnimatedImage.moving(inSheet)), "the sheet's own still moves");
            assertTrue(FxWait.fx(() -> behind.getImage() != null), "showing the picture it had");
            FxWait.fx(() -> { AnimatedImage.cover(stage.getScene(), null); return null; });
            FxWait.until("it moves again", () -> AnimatedImage.moving(behind));
            assertFalse(FxWait.fx(() -> behind.getImage() == null));
        } finally {
            FxWait.fx(() -> { stage.close(); return null; });
        }
    }

    /** The inline videos (Telegram's GIFs are MP4s that play by themselves) follow the same rule, at once. */
    @Test
    void whatPlaysBehindASheetIsNotSeenAndIsToldAtOnce() throws Exception {
        javafx.scene.layout.Region behind = new javafx.scene.layout.Region(), inSheet = new javafx.scene.layout.Region();
        behind.setPrefSize(20, 20);
        inSheet.setPrefSize(20, 20);
        StackPane overlay = new StackPane(inSheet);
        int[] told = {0};
        Runnable watcher = () -> told[0]++;
        Stage stage = FxWait.fx(() -> {
            Stage s = new Stage();
            s.setOpacity(0);
            s.setScene(new Scene(new StackPane(behind, overlay), 200, 200));
            s.show();
            AnimatedImage.watchCover(watcher);
            return s;
        });
        try {
            assertTrue(FxWait.fx(() -> AnimatedImage.seen(behind)));
            FxWait.fx(() -> { AnimatedImage.cover(stage.getScene(), overlay); return null; });
            assertFalse(FxWait.fx(() -> AnimatedImage.seen(behind)), "behind the sheet");
            assertTrue(FxWait.fx(() -> AnimatedImage.seen(inSheet)), "in it");
            assertEquals(1, told[0], "the players hear it as it happens, not at their next second's look");
            FxWait.fx(() -> { AnimatedImage.cover(stage.getScene(), null); return null; });
            assertTrue(FxWait.fx(() -> AnimatedImage.seen(behind)));
        } finally {
            FxWait.fx(() -> { AnimatedImage.unwatchCover(watcher); stage.close(); return null; });
        }
    }
}
