package app.unichat.support;

import java.io.IOException;
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

    private Path iso(String brand) throws IOException {
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

    /** Telegram's and WhatsApp's bridges take a GIF video only as an MP4: a WebM is a video, not an MP4. */
    @Test
    void aWebmIsAVideoButNoMp4() throws Exception {
        Path webm = Files.write(dir.resolve("gif.mp4"), new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0, 0, 0, 0, 0});
        assertTrue(ImageHeaders.isVideo(webm));
        assertFalse(ImageHeaders.isMp4(webm), "whatever its name says");
        assertTrue(ImageHeaders.isMp4(iso("isom")));
        assertFalse(ImageHeaders.isMp4(iso("heic")));
    }

    /** A phone's 3GP and an MPEG-7 brand are MP4s too: taken for something else they'd be converted for nothing. */
    @Test
    void threeGpAndMp71BrandsAreMp4s() throws Exception {
        for (String brand : java.util.List.of("3gp4", "3gp5", "mp71")) {
            assertTrue(ImageHeaders.isMp4(iso(brand)), brand);
        }
    }
}
