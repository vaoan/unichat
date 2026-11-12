package app.unichat.video;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telegram keeps every GIF as a silent MP4 and its bridge sends it so, marked as a GIF: forwarded to Discord it was a
 * video with a play button, and made a GIF it weighed 50 times the video. As an animated WebP made from the MP4 it
 * plays by itself, in every colour, and stays light.
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

    private static Message lasting(int ms) throws Exception {
        Message m = message(MessageType.VIDEO, "video/mp4", true);
        m.setMediaInfo(new ObjectMapper().readTree("{\"fi.mau.gif\":true,\"duration\":" + ms + "}"));
        return m;
    }

    @Test
    void aVideoMarkedAsAGifIsOneThatCameAsAVideo() throws Exception {
        assertTrue(GifVideos.is(message(MessageType.VIDEO, "video/mp4", true)));
        assertFalse(GifVideos.is(message(MessageType.VIDEO, "video/mp4", false)), "a plain video stays one");
        assertFalse(GifVideos.is(message(MessageType.IMAGE, "image/gif", false)), "a real GIF needs nothing");
        assertFalse(GifVideos.is(message(MessageType.STICKER, "video/webm", true)), "stickers go their own way");
    }

    /** As Telegram, any length by default; with a limit chosen, a longer one stays its video. */
    @Test
    void anyLengthByDefaultAsTelegramOrUpToTheLimitChosen() throws Exception {
        assertTrue(GifVideos.fits(lasting(26_000)), "Telegram made a GIF of it: so does Unichat");
        GifVideos.setLongestSeconds(15);
        try {
            assertFalse(GifVideos.fits(lasting(26_000)), "past the limit chosen");
            assertTrue(GifVideos.fits(message(MessageType.VIDEO, "video/mp4", true)), "no length known: taken as short");
            app.unichat.settings.Settings.get().set("video.gifLongestSeconds", "99999");
            assertEquals(GifVideos.NO_LIMIT, GifVideos.longestSeconds(), "a value that isn't a choice (it travels: anyone's) is the default");
        } finally {
            GifVideos.setLongestSeconds(GifVideos.NO_LIMIT);
        }
    }

    @Test
    void itsNameEndsInWebp() {
        assertEquals("gif.webp", GifVideos.webpName("gif.mp4"));
        assertEquals("IMG_0448.webp", GifVideos.webpName("IMG_0448.MP4"));
        assertEquals("sin extension.webp", GifVideos.webpName("sin extension"));
    }

    /** ffmpeg reads only the file, as an MP4; the longest side is limited standing up too; img2webp gets short names. */
    @Test
    void theCommandsReadOnlyTheFileAndLimitTheLongestSide() {
        String frames = String.join(" ", AnimatedWebp.frames(Path.of("ffmpeg"), Path.of("a.mp4"), AnimatedWebp.Source.MP4, Path.of("d"), 15, 480));
        assertTrue(frames.contains("-protocol_whitelist file") && frames.contains("-f mov"), frames);
        assertTrue(frames.contains("fps=15") && frames.contains("min(480,ih)") && frames.contains("min(480,iw)"), frames);
        List<String> webp = AnimatedWebp.webp(Path.of("img2webp"), List.of("f00001.pam", "f00002.pam"), 66, 70);
        assertTrue(webp.containsAll(List.of("-loop", "0", "-lossy", "f00001.pam", "f00002.pam")) && webp.getLast().equals("out.webp"), webp.toString());
    }

    private Path mp4(String name, String size, boolean sound) throws Exception {
        Path ffmpeg = Ffmpeg.find();
        Assumptions.assumeTrue(ffmpeg != null, "no ffmpeg");
        Assumptions.assumeTrue(AnimatedWebp.img2webp() != null, "no img2webp");
        Path mp4 = dir.resolve(name);
        List<String> c = new java.util.ArrayList<>(List.of(ffmpeg.toString(), "-y", "-loglevel", "error", "-f", "lavfi", "-i",
                "testsrc=s=" + size + ":d=1:r=30"));
        if (sound) c.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-shortest"));
        else c.add("-an");
        c.addAll(List.of("-pix_fmt", "yuv420p", mp4.toString()));
        Process p = new ProcessBuilder(c).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Assumptions.assumeTrue(p.waitFor() == 0, "this ffmpeg can't make an MP4");
        return mp4;
    }

    @Test
    void anMp4BecomesAnAnimatedWebp() throws Exception {
        Path mp4 = mp4("gif.mp4", "64x64", false);
        Path webp = GifVideos.webpUnder(mp4, Long.MAX_VALUE);
        byte[] b = Files.readAllBytes(webp);
        assertEquals("RIFF", new String(b, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WEBP", new String(b, 8, 4, StandardCharsets.US_ASCII));
        assertTrue(new String(b, StandardCharsets.ISO_8859_1).contains("ANIM"), "animated: it loops by itself");
        assertEquals(webp, GifVideos.webpUnder(mp4, Long.MAX_VALUE), "made once, beside the video");
    }

    /** Too heavy for the limit, each smaller way is tried; past them all, the video goes. */
    @Test
    void forALimitItGetsSmallerOrStaysTheVideo() throws Exception {
        Path mp4 = mp4("big.mp4", "720x720", false);
        Path first = GifVideos.webpUnder(mp4, Long.MAX_VALUE);
        Path smaller = GifVideos.webpUnder(mp4, Files.size(first) - 1);
        assertTrue(!smaller.equals(mp4) && Files.size(smaller) < Files.size(first), "a smaller way fitted");
        assertEquals(mp4, GifVideos.webpUnder(mp4, 10), "nothing fits 10 bytes: the video goes");
    }

    /** A video with sound would lose it as a picture: marked so by the bridge or found in the file, it stays a video. */
    @Test
    void oneWithSoundStaysAVideo() throws Exception {
        Message marked = message(MessageType.VIDEO, "video/mp4", true);
        marked.setMediaInfo(new ObjectMapper().readTree("{\"fi.mau.gif\":true,\"fi.mau.no_audio\":false}"));
        assertFalse(GifVideos.fits(marked), "the bridge says it has sound");
        Path withSound = mp4("sound.mp4", "64x64", true);
        assertEquals(withSound, GifVideos.webpUnder(withSound, Long.MAX_VALUE), "the file has sound: it stays the video");
    }

    @Test
    void turnedOffItGoesAsItCame() {
        GifVideos.setOn(false);
        try {
            assertFalse(GifVideos.on(), "Ajustes says: as it came");
        } finally {
            GifVideos.setOn(true);
        }
    }
}
