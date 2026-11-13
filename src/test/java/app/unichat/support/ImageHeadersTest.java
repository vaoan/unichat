package app.unichat.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A video's first bytes: an iPhone's HEIC photo starts with the same ISO box as an MP4 and is a picture. */
class ImageHeadersTest {
    @TempDir Path dir;

    private Path iso(String brand) throws Exception {
        byte[] b = new byte[32];
        b[3] = 24;
        System.arraycopy(("ftyp" + brand).getBytes(StandardCharsets.US_ASCII), 0, b, 4, 8);
        return Files.write(dir.resolve(brand.strip() + ".bin"), b);
    }

    @Test
    void anMp4IsAVideoAHeicOrAvifPhotoIsNot() throws Exception {
        assertTrue(ImageHeaders.isVideo(iso("isom")), "Telegram's GIFs");
        assertTrue(ImageHeaders.isVideo(iso("mp42")));
        assertFalse(ImageHeaders.isVideo(iso("heic")), "an iPhone photo");
        assertFalse(ImageHeaders.isVideo(iso("mif1")));
        assertFalse(ImageHeaders.isVideo(iso("avif")));
    }
}
