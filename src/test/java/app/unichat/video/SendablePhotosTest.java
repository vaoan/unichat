package app.unichat.video;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SendablePhotosTest {
    private static final long MB = 1024 * 1024;

    @Test
    void aPhoneWebpOrAnythingOverTelegramsLimitsIsMadeAPhoto() {
        assertFalse(SendablePhotos.alreadyPhoto("image/webp", 4000, 3000, 2 * MB), "WebP is no photo");
        assertFalse(SendablePhotos.alreadyPhoto("image/jpeg", 6048, 4032, 5 * MB), "24 MP: Telegram sends it as a file");
        assertFalse(SendablePhotos.alreadyPhoto("image/jpeg", 3000, 2000, 12 * MB), "over 10 MB");
        assertFalse(SendablePhotos.alreadyPhoto("image/png", 9000, 1100, MB), "over 10,000 px of width plus height");
        assertFalse(SendablePhotos.alreadyPhoto("image/jpeg", 0, 0, MB), "size unknown");
        assertTrue(SendablePhotos.alreadyPhoto("image/jpeg", 4032, 3024, 3 * MB), "12 MP: goes as it is");
        assertTrue(SendablePhotos.alreadyPhoto("image/png", 1170, 2532, MB), "a screenshot");
    }

    @Test
    void gifsStayAnimatedAndOnlyPicturesAreConverted() {
        assertTrue(SendablePhotos.convertible("image/webp"));
        assertTrue(SendablePhotos.convertible("image/heic"));
        assertFalse(SendablePhotos.convertible("image/gif"));
        assertFalse(SendablePhotos.convertible("image/svg+xml"));
        assertFalse(SendablePhotos.convertible("video/mp4"));
        assertFalse(SendablePhotos.convertible(null));
    }

    @Test
    void aBigPictureBecomesAJpegThatFits(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(app.unichat.support.Os.mac(), "sips: the Mac's own tool");
        Path big = dir.resolve("IMG_0001.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4000, 3000, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", big.toFile());
        Path jpeg = SendablePhotos.jpeg(big, 4000, 3000, dir.resolve("out"));
        assertEquals("IMG_0001.jpg", jpeg.getFileName().toString());
        assertEquals(List.of(2560, 1920), java.util.Arrays.stream(app.unichat.support.ImageHeaders.size(jpeg)).boxed().toList());
        Path small = dir.resolve("small.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(800, 600, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", small.toFile());
        assertEquals(List.of(800, 600), java.util.Arrays.stream(app.unichat.support.ImageHeaders.size(
                SendablePhotos.jpeg(small, 800, 600, dir.resolve("out")))).boxed().toList(), "never enlarged");
    }

    @Test
    void sipsOnlyShrinksNeverEnlarges() {
        Path in = Path.of("a.webp"), out = Path.of("a.jpg");
        assertTrue(SendablePhotos.sips(in, out, true).contains("-Z"));
        assertFalse(SendablePhotos.sips(in, out, false).contains("-Z"));
        assertEquals(List.of("a.webp", "--out", "a.jpg"), SendablePhotos.sips(in, out, false).subList(7, 10));
    }
}
