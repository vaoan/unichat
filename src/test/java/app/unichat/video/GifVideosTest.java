package app.unichat.video;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telegram keeps every GIF as a silent MP4 and its bridge sends it so, marked as a GIF: a friend's GIF was saved as a
 * video and forwarded to Discord as one, with a play button. As a real GIF it's what was sent.
 */
class GifVideosTest {
    @TempDir Path dir;

    private static Message message(MessageType type, String mime, boolean gifMark) throws Exception {
        Message m = new Message("$1", "!c", "@x", "X", false, Instant.EPOCH);
        m.setType(type);
        m.setMimeType(mime);
        m.setMediaUrl("mxc://s/v");
        if (gifMark) m.setMediaInfo(new ObjectMapper().readTree("{\"fi.mau.gif\":true,\"fi.mau.loop\":true}"));
        return m;
    }

    @Test
    void aVideoMarkedAsAGifIsOneThatCameAsAVideo() throws Exception {
        assertTrue(GifVideos.is(message(MessageType.VIDEO, "video/mp4", true)));
        assertFalse(GifVideos.is(message(MessageType.VIDEO, "video/mp4", false)), "a plain video stays one");
        assertFalse(GifVideos.is(message(MessageType.IMAGE, "image/gif", false)), "a real GIF needs nothing");
        assertFalse(GifVideos.is(message(MessageType.STICKER, "video/webm", true)), "stickers go their own way");
    }

    /** The GIF made is cut at 30 s and loses colours: a long "GIF" (a silent video Telegram marked) stays its video. */
    @Test
    void onlyAShortOneBecomesAGif() throws Exception {
        Message shortOne = message(MessageType.VIDEO, "video/mp4", true);
        assertTrue(GifVideos.becomesGif(shortOne), "no length known: a GIF is short");
        shortOne.setMediaInfo(new ObjectMapper().readTree("{\"fi.mau.gif\":true,\"duration\":5000}"));
        assertTrue(GifVideos.becomesGif(shortOne));
        Message longOne = message(MessageType.VIDEO, "video/mp4", true);
        longOne.setMediaInfo(new ObjectMapper().readTree("{\"fi.mau.gif\":true,\"duration\":20000}"));
        assertFalse(GifVideos.becomesGif(longOne), "past 15 s it would lose its end, or weigh too much");
        assertFalse(GifVideos.becomesGif(message(MessageType.VIDEO, "video/mp4", false)));
    }

    @Test
    void turnedOffTheyGoAsTheyCame() throws Exception {
        Message m = message(MessageType.VIDEO, "video/mp4", true);
        GifVideos.setOn(false);
        try {
            assertFalse(GifVideos.becomesGif(m), "Ajustes says: as it came");
        } finally {
            GifVideos.setOn(true);
        }
        assertTrue(GifVideos.becomesGif(m));
    }

    @Test
    void itsNameEndsInGif() {
        assertEquals("gif.gif", GifVideos.gifName("gif.mp4"));
        assertEquals("IMG_0448.gif", GifVideos.gifName("IMG_0448.MP4"));
        assertEquals("charmander-cas-van-de-pol.gif", GifVideos.gifName("charmander-cas-van-de-pol.mp4"));
        assertEquals("sin extension.gif", GifVideos.gifName("sin extension"));
    }

    @Test
    void anMp4BecomesAnAnimatedGif() throws Exception {
        Path ffmpeg = Ffmpeg.find();
        Assumptions.assumeTrue(ffmpeg != null, "no ffmpeg");
        Path mp4 = dir.resolve("gif.mp4");
        Process p = new ProcessBuilder(ffmpeg.toString(), "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=s=64x64:d=1:r=10",
                "-pix_fmt", "yuv420p", "-an", mp4.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Assumptions.assumeTrue(p.waitFor() == 0, "this ffmpeg can't make an MP4");
        Path gif = GifVideos.toGif(mp4, x -> { });
        byte[] head = Files.readAllBytes(gif);
        assertEquals("GIF89a", new String(head, 0, 6, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(gif, GifVideos.toGif(mp4, x -> { }), "converted once, beside the video");
    }
}
