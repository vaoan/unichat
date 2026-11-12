package app.unichat.ui.media;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

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
        assertTrue(MessageMedia.mayBeAnimated(sticker));
        assertTrue(MessageMedia.mayBeAnimated(gif));
        assertFalse(MessageMedia.mayBeAnimated(photo), "photos keep using server thumbnails");
    }

    private static boolean same(BufferedImage a, BufferedImage b) {
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
            }
        }
        return true;
    }

    @Test
    void longOrBigAnimationsKeepFewerFramesButTheSameDuration() {
        assertEquals(1, AnimatedImage.step(40, 240, 240), "a short sticker keeps every frame");
        assertEquals(3, AnimatedImage.step(300, 64, 64), "at most 120 frames");
        // 480x480 frames are 900 KB: 24 MB holds 27 of them.
        assertEquals(4, AnimatedImage.step(100, 480, 480));
        assertEquals(List.of(30, 70, 50), AnimatedImage.sample(List.of(10, 20, 30, 40, 50), 2));
        assertEquals(List.of(10, 20), AnimatedImage.sample(List.of(10, 20), 1));
    }

    @Test
    void gifFramesAreHandedOutOneByOne(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path gif = dir.resolve("a.gif");
        writeGif(gif, 5);
        assertEquals(5, app.unichat.gif.GifFrames.count(gif));
        int[] seen = {0};
        app.unichat.gif.GifFrames.read(gif, f -> {
            seen[0]++;
            assertEquals(20, f.image().getWidth());
        });
        assertEquals(5, seen[0]);
        assertTrue(AnimatedImage.isGif(gif));
        assertFalse(AnimatedImage.isGif(fixture()));
    }

    /** A {@code frames}-frame 20x20 GIF. */
    private static void writeGif(Path out, int frames) throws Exception {
        app.unichat.TestPictures.gif(out, 20, 20, frames);
    }

    @Test
    void aBigAnimationLowersItsResolutionBeforeDroppingFrames() {
        // The Verdi sticker: 83 frames of 348x360 (twice its size on screen) were over the budget, so every other frame
        // was dropped and it played at half its frame rate. Smaller frames keep them all.
        double s = AnimatedImage.shrinkToKeep(83, 348, 360);
        assertTrue(s < 1 && s >= 0.5, "smaller, not below the size on screen: " + s);
        assertEquals(1, AnimatedImage.step(83, (int) Math.round(348 * s), (int) Math.round(360 * s)), "every frame kept");
        assertEquals(1.0, AnimatedImage.shrinkToKeep(20, 200, 200), "one that fits stays sharp");
        double huge = AnimatedImage.shrinkToKeep(1000, 800, 800);
        assertEquals(0.5, huge, "never below half (its size on screen)");
        assertTrue(AnimatedImage.step(1000, 400, 400) > 1, "past that, frames go");
    }

    @Test
    void animatedWebpIsCompositedByLibwebpWhenThereIsAnimDump() throws Exception {
        Path built = Path.of(System.getProperty("user.home"), "Library/Caches/unichat-build/anim_dump");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(built), "no anim_dump here");
        System.setProperty("unichat.animDump", built.toString());
        Path f = Files.createTempFile("moving", ".webp");
        try (java.io.InputStream in = AnimatedImageTest.class.getResourceAsStream("/app/unichat/video/moving.webp")) {
            Files.copy(in, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        AnimatedImage.RawFrames raw = AnimatedImage.composeWebp(f, 96, 96);
        List<java.awt.image.BufferedImage> libwebp = app.unichat.video.AnimDump.frames(f);
        assertEquals(libwebp.size(), raw.images().size());
        for (int i = 0; i < libwebp.size(); i++) {
            assertEquals(libwebp.get(i).getRGB(40, 32), raw.images().get(i).getRGB(40, 32), "frame " + i + " as libwebp has it");
        }
        assertEquals(List.of(40, 40, 40, 40, 40, 40), raw.durationsMs());
    }

    /**
     * TwelveMonkeys' VP8 decoder ran out of data on a WebP libwebp reads fine (Discord's preview of a link to an animated
     * WebP): nothing showed but a retry button. libwebp's anim_dump reads it instead.
     */
    @Test
    void aWebpTheJavaDecoderCantReadIsReadByLibwebp() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(app.unichat.video.AnimDump.find() != null, "no anim_dump");
        Path still = Path.of(AnimatedImageTest.class.getResource("/app/unichat/ui/still.webp").toURI());
        AnimatedImage.WebpOpener failing = file -> new AnimatedImage.WebpReader() {
            @Override public java.awt.image.BufferedImage read(int i) throws java.io.IOException { throw new java.io.EOFException(); }
            @Override public void close() { }
        };
        AnimatedImage.RawFrames frames = AnimatedImage.composeWebp(still, 64, 64, failing);
        assertEquals(1, frames.images().size(), "libwebp read it");
        assertEquals(32, frames.images().getFirst().getWidth());
    }
}
