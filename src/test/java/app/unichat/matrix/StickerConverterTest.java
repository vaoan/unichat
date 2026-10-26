package app.unichat.matrix;

import app.unichat.model.RoomFeatures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sticker whose format the chat refuses (a GIF on WhatsApp) goes out converted to one it takes. */
class StickerConverterTest {
    @TempDir
    Path dir;

    private static RoomFeatures features(String stickerMimes) throws Exception {
        return RoomFeatures.parse(new ObjectMapper().readTree(
                "{\"id\":\"x\",\"file\":{\"m.sticker\":{\"mime_types\":" + stickerMimes + "}}}"));
    }

    @Test
    void picksWebpThenPngAndLeavesAcceptedStickersAlone() throws Exception {
        RoomFeatures wa = features("{\"image/webp\":2,\"image/png\":1}");
        assertEquals("image/webp", StickerConverter.target("image/gif", wa));
        assertNull(StickerConverter.target("image/webp", wa), "already fine");
        assertEquals("image/png", StickerConverter.target("image/gif", features("{\"image/png\":2}")));
        assertNull(StickerConverter.target("image/gif", features("{\"video/lottie+json\":2}")), "nothing better to offer");
        assertNull(StickerConverter.target("image/gif", RoomFeatures.UNKNOWN));
    }

    private Path gif() throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        img.setRGB(3, 3, 0xff0000);
        Path f = dir.resolve("s.gif");
        ImageIO.write(img, "gif", f.toFile());
        return f;
    }

    @Test
    void convertsAGifToAnAnimatedWebp() throws Exception {
        Path tool = StickerConverter.gif2webp();
        Assumptions.assumeTrue(tool != null, "gif2webp not available here");
        StickerConverter.Result r = StickerConverter.convert(gif(), "image/webp", dir);
        assertEquals("image/webp", r.mime());
        byte[] b = Files.readAllBytes(r.file());
        assertEquals("RIFF", new String(b, 0, 4));
        assertEquals("WEBP", new String(b, 8, 4));
    }

    @Test
    void withoutTheToolAWebpTargetFallsBackToAStillPng() throws Exception {
        StickerConverter.Result r = StickerConverter.convert(gif(), "image/webp", dir, null);
        assertEquals("image/png", r.mime());
        BufferedImage back = ImageIO.read(r.file().toFile());
        assertNotNull(back);
        assertEquals(32, back.getWidth());
        assertTrue(r.file().getFileName().toString().endsWith(".png"));
    }
}
