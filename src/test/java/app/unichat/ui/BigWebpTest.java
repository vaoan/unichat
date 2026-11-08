package app.unichat.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A phone's photo as WebP is told apart by its header alone, so it's never decoded in Unichat's memory. */
class BigWebpTest {
    @TempDir Path dir;

    /** RIFF + WEBP + one chunk header and {@code body} (the bytes after the chunk's size). */
    private Path webp(String chunk, int... body) throws Exception {
        byte[] d = new byte[40];
        System.arraycopy("RIFF".getBytes(), 0, d, 0, 4);
        System.arraycopy("WEBP".getBytes(), 0, d, 8, 4);
        System.arraycopy(chunk.getBytes(), 0, d, 12, 4);
        for (int i = 0; i < body.length; i++) d[20 + i] = (byte) body[i];
        return Files.write(dir.resolve(chunk.trim() + body.length + ".webp"), d);
    }

    /** VP8X: flags, 3 reserved, then width-1 and height-1 in 24 bits each. */
    private Path vp8x(int flags, int w, int h) throws Exception {
        int a = w - 1, b = h - 1;
        return webp("VP8X", flags, 0, 0, 0, a & 0xFF, (a >> 8) & 0xFF, (a >> 16) & 0xFF, b & 0xFF, (b >> 8) & 0xFF, (b >> 16) & 0xFF);
    }

    @Test
    void anIphonePhotoIsBigAndAStickerIsNot() throws Exception {
        Path photo = vp8x(0x00, 4284, 5712);
        assertArrayEquals(new int[]{4284, 5712}, AnimatedImage.webpSize(photo));
        assertTrue(AnimatedImage.isBigWebp(photo));
        assertFalse(AnimatedImage.isBigWebp(vp8x(0x00, 512, 512)), "a sticker");
        assertFalse(AnimatedImage.isBigWebp(vp8x(0x02, 4000, 4000)), "animated: decoded frame by frame as before");
    }

    @Test
    void readsTheSizeOfSimpleLossyAndLosslessFiles() throws Exception {
        // VP8 : frame tag (3), start code 9d 01 2a, then 14-bit width and height.
        Path lossy = webp("VP8 ", 0, 0, 0, 0x9d, 0x01, 0x2a, 4032 & 0xFF, 4032 >> 8, 3024 & 0xFF, 3024 >> 8);
        assertArrayEquals(new int[]{4032, 3024}, AnimatedImage.webpSize(lossy));
        // VP8L: signature 0x2f, then 14 bits width-1 and 14 bits height-1.
        int w = 1999, h = 2999;
        Path lossless = webp("VP8L", 0x2f, w & 0xFF, ((w >> 8) & 0x3F) | ((h & 0x03) << 6), (h >> 2) & 0xFF, (h >> 10) & 0x0F);
        assertArrayEquals(new int[]{2000, 3000}, AnimatedImage.webpSize(lossless));
        assertNull(AnimatedImage.webpSize(Files.write(dir.resolve("x.png"), new byte[40])));
    }
}
