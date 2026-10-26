package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Animated WebP stickers (WhatsApp) must keep all their frames instead of a static thumbnail. */
class AnimatedImageTest {
    private static Path fixture() throws Exception {
        // 48x40, 4 frames at 4 fps, generated with ffmpeg's libwebp_anim (testsrc pattern).
        return Path.of(AnimatedImageTest.class.getResource("/app/unichat/ui/anim.webp").toURI());
    }

    @Test
    void parsesCanvasAndFrameChunks() throws Exception {
        AnimatedImage.WebpLayout layout = AnimatedImage.parseLayout(Files.readAllBytes(fixture()));
        assertEquals(48, layout.canvasWidth());
        assertEquals(40, layout.canvasHeight());
        assertEquals(4, layout.frames().size());
        assertTrue(layout.frames().stream().allMatch(f -> f.durationMs() == 250), layout.toString());
    }

    @Test
    void composesEveryFrameScaledToTheDisplaySize() throws Exception {
        assertTrue(AnimatedImage.isWebp(fixture()));
        AnimatedImage.RawFrames frames = AnimatedImage.composeWebp(fixture(), 24, 24);
        assertEquals(4, frames.images().size(), "all frames, not a static first frame");
        BufferedImage a = frames.images().get(0), b = frames.images().get(1);
        assertTrue(a.getWidth() <= 24 && a.getHeight() <= 24, "scaled down: " + a.getWidth() + "x" + a.getHeight());
        assertEquals(a.getWidth(), b.getWidth(), "frames share the canvas size");
        assertFalse(same(a, b), "frames differ: the sticker moves");
        assertEquals(250, frames.durationsMs().get(0));
    }

    @Test
    void stickersGifsAndWebpSkipStaticThumbnails() {
        Message sticker = new Message("1", "!c", "@a", "a", false, Instant.now());
        sticker.setType(MessageType.STICKER);
        Message gif = new Message("2", "!c", "@a", "a", false, Instant.now());
        gif.setType(MessageType.IMAGE);
        gif.setMimeType("image/gif");
        Message photo = new Message("3", "!c", "@a", "a", false, Instant.now());
        photo.setType(MessageType.IMAGE);
        photo.setMimeType("image/jpeg");
        assertTrue(ThreadPane.mayBeAnimated(sticker));
        assertTrue(ThreadPane.mayBeAnimated(gif));
        assertFalse(ThreadPane.mayBeAnimated(photo), "photos keep using server thumbnails");
    }

    private static boolean same(BufferedImage a, BufferedImage b) {
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
            }
        }
        return true;
    }
}
