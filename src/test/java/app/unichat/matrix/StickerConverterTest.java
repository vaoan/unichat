package app.unichat.matrix;

import app.unichat.model.Network;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void telegramsBridgeTakesAsTheyArePngJpegWebmAndLottieOnly() {
        for (String m : new String[] {"image/png", "image/jpeg", "video/webm", "video/lottie+json"}) {
            assertNull(NetworkFormats.sticker(Network.TELEGRAM, m, true, false, RoomFeatures.UNKNOWN), m);
        }
        assertEquals("video/webm", NetworkFormats.sticker(Network.TELEGRAM, "image/gif", true, false, RoomFeatures.UNKNOWN),
                "an animated GIF: a video sticker");
        assertEquals("image/png", NetworkFormats.sticker(Network.TELEGRAM, "image/webp", false, false, RoomFeatures.UNKNOWN),
                "a WebP would go through an ffmpeg that may not write WebP: a PNG it encodes itself");
        assertNull(NetworkFormats.sticker(Network.TELEGRAM, "image/gif", true, true, RoomFeatures.UNKNOWN),
                "its own sticker: the bridge sends it from its pack");
    }

    /** One table, every network: a WebM there is a video to press play on, so it goes as an animated WebP. */
    @Test
    void aWebmStickerGoesAsAnAnimatedWebpEverywhereButTelegram() {
        for (Network n : new Network[] {Network.DISCORD, Network.WHATSAPP, Network.INSTAGRAM}) {
            assertEquals("image/webp", NetworkFormats.sticker(n, "video/webm", true, false, RoomFeatures.UNKNOWN), n.name());
        }
        assertNull(NetworkFormats.sticker(Network.TELEGRAM, "video/webm", true, false, RoomFeatures.UNKNOWN), "Telegram's own video stickers");
        assertNull(NetworkFormats.sticker(Network.DISCORD, "image/webp", true, false, RoomFeatures.UNKNOWN), "Discord plays an animated WebP by itself");
        assertEquals("image/webp", NetworkFormats.sticker(Network.DISCORD, "image/gif", true, false, RoomFeatures.UNKNOWN),
                "a GIF (Telegram's Lottie stickers come so) as the lighter animated WebP");
        assertEquals("image/png", NetworkFormats.sticker(Network.DISCORD, "image/gif", false, false, RoomFeatures.UNKNOWN), "a still one as a PNG");
        assertTrue(NetworkFormats.of(Network.DISCORD).takesIfUnmade("image/gif"), "no gif2webp: the GIF still plays there");
        assertFalse(NetworkFormats.of(Network.WHATSAPP).takesIfUnmade("image/gif"), "WhatsApp's stickers can't be GIFs");
        assertEquals("image/webp", NetworkFormats.sticker(Network.DISCORD, "video/webm", null, false, RoomFeatures.UNKNOWN),
                "not read yet: it will change");
    }

    @Test
    void gifsThatCameAsVideosGoAsTheyAreOnlyWhereTheBridgeSaysSo() {
        assertTrue(NetworkFormats.of(Network.TELEGRAM).gifVideosAsIs());
        assertTrue(NetworkFormats.of(Network.WHATSAPP).gifVideosAsIs());
        assertFalse(NetworkFormats.of(Network.DISCORD).gifVideosAsIs(), "Discord shows the MP4 as a video to press play on");
    }

    @Test
    void aWebmBecomesAnAnimatedWebpWithItsTransparency() throws Exception {
        Path ffmpeg = app.unichat.video.Ffmpeg.find();
        Assumptions.assumeTrue(ffmpeg != null, "no ffmpeg");
        Assumptions.assumeTrue(app.unichat.video.AnimatedWebp.img2webp() != null, "no img2webp");
        Path webm = dir.resolve("s.webm");
        Process p = new ProcessBuilder(ffmpeg.toString(), "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=s=64x64:d=1:r=10,format=yuva420p",
                "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", webm.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Assumptions.assumeTrue(p.waitFor() == 0, "this ffmpeg can't make a WebM");
        StickerConverter.Result r = StickerConverter.forChat(webm, "video/webm", Network.DISCORD, false, RoomFeatures.UNKNOWN, dir);
        assertNotNull(r);
        assertEquals("image/webp", r.mime());
        String b = new String(Files.readAllBytes(r.file()), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(b.contains("ANIM") && b.contains("ALPH"), "it still moves, and keeps its transparency");
    }

    @Test
    void picksWebpThenPngAndLeavesAcceptedStickersAlone() throws Exception {
        RoomFeatures wa = features("{\"image/webp\":2,\"image/png\":1}");
        assertEquals("image/webp", NetworkFormats.byFeatures("image/gif", wa));
        assertNull(NetworkFormats.byFeatures("image/webp", wa), "already fine");
        assertEquals("image/png", NetworkFormats.byFeatures("image/gif", features("{\"image/png\":2}")));
        assertNull(NetworkFormats.byFeatures("image/gif", features("{\"video/lottie+json\":2}")), "nothing better to offer");
        assertNull(NetworkFormats.byFeatures("image/gif", RoomFeatures.UNKNOWN));
        assertEquals("image/webp", NetworkFormats.sticker(Network.WHATSAPP, "image/gif", true, false, wa), "WhatsApp: what its bridge says");
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
