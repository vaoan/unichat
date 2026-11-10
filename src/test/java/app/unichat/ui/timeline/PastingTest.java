package app.unichat.ui.timeline;

import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PastingTest {
    @TempDir Path dir;

    @Test
    void aPictureCopiedFromTheWebGoesAsAPhoto() throws Exception {
        WritableImage copied = new WritableImage(40, 30);
        copied.getPixelWriter().setColor(5, 7, Color.RED);
        Pasting.Send send = Pasting.toSend(new Pasting.Copied(List.of(), null, copied), dir);
        assertEquals(app.unichat.service.SendAs.PHOTO, send.as());
        File png = send.files().get(0);
        assertTrue(png.toPath().startsWith(dir) && png.getName().endsWith(".png"), png.toString());
        BufferedImage read = ImageIO.read(png);
        assertEquals(40, read.getWidth());
        assertEquals(30, read.getHeight());
        assertEquals(0xFFFF0000, read.getRGB(5, 7), "the same pixels");
    }

    @Test
    void copiedFilesGoAsTheyAre() throws Exception {
        List<File> files = List.of(dir.resolve("a.pdf").toFile(), dir.resolve("b.jpg").toFile());
        // Finder also puts the names as text, and an icon as a picture: the files win.
        Pasting.Send send = Pasting.toSend(new Pasting.Copied(files, "a.pdf\nb.jpg", new WritableImage(8, 8)), dir);
        assertEquals(files, send.files());
        assertEquals(app.unichat.service.SendAs.AS_IS, send.as());
    }

    @Test
    void textIsPastedAsText() throws Exception {
        assertNull(Pasting.toSend(new Pasting.Copied(List.of(), "hola", null), dir));
        // Office copies a picture of the text along with it: the text is what was meant.
        assertNull(Pasting.toSend(new Pasting.Copied(List.of(), "una celda", new WritableImage(8, 8)), dir));
        assertNull(Pasting.toSend(new Pasting.Copied(List.of(), null, null), dir), "nothing to paste");
    }
}
