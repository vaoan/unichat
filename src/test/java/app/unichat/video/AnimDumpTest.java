package app.unichat.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * libwebp's anim_dump composites an animated WebP as a browser does (the Java decoder got some lossy frames' colours
 * wrong: a black blotch). moving.webp: a red square moving right over a transparent background and a blue band.
 */
class AnimDumpTest {
    @TempDir Path dir;

    /** The one built for development (run-dev.sh), when this machine has it: CI has no anim_dump and skips. */
    private static Path tool() {
        Path built = Path.of(System.getProperty("user.home"), "Library/Caches/unichat-build/anim_dump");
        if (System.getProperty("unichat.animDump") == null && Files.isExecutable(built)) System.setProperty("unichat.animDump", built.toString());
        return AnimDump.find();
    }

    private Path asset() throws Exception {
        Path f = dir.resolve("moving.webp");
        try (InputStream in = AnimDumpTest.class.getResourceAsStream("/app/unichat/video/moving.webp")) {
            Files.copy(in, f);
        }
        return f;
    }

    @Test
    void everyFrameComesCompositedOverTransparency() throws Exception {
        assumeTrue(tool() != null, "no anim_dump here");
        List<BufferedImage> frames = AnimDump.frames(asset());
        assertNotNull(frames);
        assertEquals(6, frames.size());
        for (int i = 0; i < 6; i++) {
            BufferedImage f = frames.get(i);
            assertEquals(96, f.getWidth());
            int square = f.getRGB(22 + i * 10, 32), band = f.getRGB(48, 80), empty = f.getRGB(90, 5);
            assertTrue((square >>> 24) > 200 && ((square >> 16) & 255) > 150 && (square & 255) < 100, "frame " + i + ": the red square, opaque");
            assertTrue((band >>> 24) > 200 && (band & 255) > 150, "frame " + i + ": the blue band");
            assertTrue((empty >>> 24) < 20, "frame " + i + ": transparent where nothing is, never black");
        }
        int left = frames.get(5).getRGB(12, 32);
        assertTrue((left >>> 24) < 20, "where the square was, transparent again: " + Integer.toHexString(left));
    }

    @Test
    void notAWebpIsNoFrames() throws Exception {
        assumeTrue(tool() != null, "no anim_dump here");
        assertEquals(null, AnimDump.frames(Files.writeString(dir.resolve("roto.webp"), "esto no es una imagen")));
    }

    @Test
    void portableAnymapIsReadAsArgb() throws Exception {
        Path pam = dir.resolve("f.pam");
        byte[] head = "P7\nWIDTH 2\nHEIGHT 1\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n".getBytes();
        byte[] px = {(byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, (byte) 128};
        byte[] all = new byte[head.length + px.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(px, 0, all, head.length, px.length);
        Files.write(pam, all);
        BufferedImage img = AnimDump.readPam(pam);
        assertEquals(0xFFFF0000, img.getRGB(0, 0));
        assertEquals(0x800000FF, img.getRGB(1, 0));
    }

    /**
     * Each frame is made small as it's read: all of a 512 px sticker's frames whole at once (1 MB each) were the peak
     * that filled the memory when the sticker panel opened.
     */
    @Test
    void eachFrameIsMadeSmallAsItsRead() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(AnimDump.find() != null, "no anim_dump");
        List<int[]> sizes = new java.util.ArrayList<>();
        List<BufferedImage> frames = AnimDump.frames(asset(), f -> {
            sizes.add(new int[] {f.getWidth(), f.getHeight()});
            return new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        });
        assertEquals(sizes.size(), frames.size(), "every frame through it");
        assertTrue(frames.stream().allMatch(f -> f.getWidth() == 8), "only the small ones kept");
    }
}
