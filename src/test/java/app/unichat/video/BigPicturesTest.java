package app.unichat.video;

import app.unichat.support.Os;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BigPicturesTest {
    @TempDir Path dir;

    private Path picture() throws IOException {
        Path p = dir.resolve("foto.png");
        ImageIO.write(new BufferedImage(2400, 1800, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
        return p;
    }

    /** ffmpeg is ~28 MB to download: on a Mac, sips (always there) makes the copy and ffmpeg isn't even looked for. */
    @Test
    void onAMacSipsDoesItAndFfmpegIsntFetched() throws Exception {
        assumeTrue(Os.mac(), "sips is the Mac's");
        Path out = BigPictures.shrunk(picture(), () -> { throw new AssertionError("ffmpeg was fetched while sips could do it"); });
        assertTrue(Files.size(out) > 0);
        Path again = SendablePhotos.jpeg(picture(), 2400, 1800, dir.resolve("out"),
                () -> { throw new AssertionError("ffmpeg was fetched while sips could do it"); });
        assertTrue(Files.size(again) > 0);
    }

    @Test
    void whenNothingElseCanFfmpegIsFetchedAndNoneMeansItCant() throws Exception {
        Path notAPicture = Files.writeString(dir.resolve("roto.png"), "no es una foto");
        AtomicInteger fetched = new AtomicInteger();
        assertThrows(IOException.class, () -> BigPictures.shrunk(notAPicture, () -> { fetched.incrementAndGet(); return null; }));
        assertEquals(1, fetched.get());
        try (var left = Files.list(dir)) {
            assertEquals(1, left.count(), "no half-written copy is left beside it");
        }
    }
}
