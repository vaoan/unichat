package app.unichat.ui.media;

import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.Circle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The loading ring's arc runs on its track: a StackPane centred the arc's own bounds (a quarter circle), so it sat as a
 * ")" in the middle of the ring, and spun around that quarter's middle instead of the ring's; and the arc's round ends,
 * wider than the track, moved bounds-based centring off by a fraction of a pixel.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ProgressRingUiTest {
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

    private static Point2D centre(javafx.scene.Node n, double x, double y) {
        return n.localToScene(x, y);
    }

    @Test
    void theArcGoesRoundOnTheTrackWhetherTheShareIsKnownOrNot() throws Exception {
        for (double progress : new double[] {-1, 0.25, 0.8}) {
            Point2D[] centres = FxWait.fx(() -> {
                ProgressRing ring = new ProgressRing();
                ring.setProgress(progress);
                StackPane root = new StackPane(ring);
                app.unichat.ui.Theme.register(new Scene(root, 100, 100)); // the app's strokes: the arc's has round ends
                root.applyCss();
                root.layout();
                Circle track = (Circle) ring.lookup(".media-loading-track");
                Arc arc = (Arc) ring.lookup(".media-loading-arc");
                return new Point2D[] {centre(track, track.getCenterX(), track.getCenterY()), centre(arc, arc.getCenterX(), arc.getCenterY()),
                        ring.localToScene(ring.getWidth() / 2, ring.getHeight() / 2)};
            });
            assertEquals(centres[0].getX(), centres[1].getX(), 0.01, "progress " + progress + ": the arc's centre is the track's");
            assertEquals(centres[0].getY(), centres[1].getY(), 0.01, "progress " + progress + ": the arc's centre is the track's");
            assertEquals(centres[2].getX(), centres[0].getX(), 0.01, "progress " + progress + ": in the middle of the ring");
            assertEquals(centres[2].getY(), centres[0].getY(), 0.01, "progress " + progress + ": in the middle of the ring");
        }
    }
}
