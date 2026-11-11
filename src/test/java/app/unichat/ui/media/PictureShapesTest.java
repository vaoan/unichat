package app.unichat.ui.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A picture seen once has its shape from the start the next time the app runs: its row doesn't grow and move the chat. */
class PictureShapesTest {
    @Test
    void aShapeSeenOnceIsKnownAfterARestart(@TempDir Path dir) {
        Path file = dir.resolve("picture-shapes.tsv");
        PictureShapes first = new PictureShapes(file);
        assertEquals(16.0 / 9, first.shape("mxc://s/reel", 16.0 / 9), 1e-9, "never seen: the default");
        first.remember("mxc://s/reel", 9.0 / 16);
        first.remember("mxc://s/bad", Double.NaN);
        first.flush();
        PictureShapes again = new PictureShapes(file);
        assertEquals(9.0 / 16, again.shape("mxc://s/reel", 16.0 / 9), 1e-9);
        assertEquals(1.5, again.shape("mxc://s/bad", 1.5), 1e-9, "a shape that isn't one isn't kept");
    }

    @Test
    void onlyTheNewestAreKept(@TempDir Path dir) {
        PictureShapes shapes = new PictureShapes(dir.resolve("s.tsv"));
        for (int i = 0; i <= PictureShapes.KEPT; i++) shapes.remember("mxc://s/" + i, 1 + i);
        assertEquals(-1, shapes.shape("mxc://s/0", -1), 1e-9, "the oldest went");
        assertEquals(1 + PictureShapes.KEPT, shapes.shape("mxc://s/" + PictureShapes.KEPT, -1), 1e-9);
        shapes.flush();
    }
}
