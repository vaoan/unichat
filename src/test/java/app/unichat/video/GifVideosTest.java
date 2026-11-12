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

    /** Telegram marks any silent video up to 10 MB as a GIF: one the size of a camera's is a real video, and stays one. */
    @Test
    void oneTheSizeOfACamerasIsARealVideo() throws Exception {
        Message clip = message(MessageType.VIDEO, "video/mp4", true);
        clip.setSize(1080, 1920);
        assertFalse(GifVideos.fits(clip), "an iPhone's clip, standing up");
        clip.setSize(720, 720);
        assertTrue(GifVideos.fits(clip), "up to 720 by default: a GIF can be that big");
        clip.setSize(0, 0);
        assertTrue(GifVideos.fits(clip), "size not said: the file is looked at before it's made");
        clip.setSize(480, 480);
        GifVideos.setLargestSide(320);
        try {
            assertFalse(GifVideos.fits(clip), "320 chosen in Ajustes");
        } finally {
            GifVideos.setLargestSide(720);
        }
        Path big = mp4("camera.mp4", "1280x720", false);
        assertEquals(big, GifVideos.webpUnder(big, Long.MAX_VALUE), "the file is a camera's size: it stays the video");
    }

    @Test
    void itsNameEndsInWebp() {
        assertEquals("gif.webp", GifVideos.webpName("gif.mp4"));
        assertEquals("IMG_0448.webp", GifVideos.webpName("IMG_0448.MP4"));
        assertEquals("sin extension.webp", GifVideos.webpName("sin extension"));
    }

    /** ffmpeg reads only the file, as an MP4, and takes it as it comes; img2webp gets short names. */
    @Test
    void theCommandsReadOnlyTheFileAndLimitTheLongestSide() {
        String frames = String.join(" ", AnimatedWebp.frames(Path.of("ffmpeg"), Path.of("a.mp4"), AnimatedWebp.Source.MP4, Path.of("d"),
                AnimatedWebp.AS_IT_COMES, AnimatedWebp.AS_IT_COMES));
        assertTrue(frames.contains("-protocol_whitelist file") && frames.contains("-f mov"), frames);
        assertFalse(frames.contains("fps=") || frames.contains("scale="), "as it comes: " + frames);
        String sticker = String.join(" ", AnimatedWebp.frames(Path.of("ffmpeg"), Path.of("a.webm"), AnimatedWebp.Source.WEBM, Path.of("d"), 30, 512));
        assertTrue(sticker.contains("min(512,ih)") && sticker.contains("min(512,iw)") && sticker.contains("libvpx-vp9"), "a sticker: limited standing up too");
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

    /** As it comes: its size and frames kept; over the weight limit the video goes. */
    @Test
    void itKeepsItsSizeAndFramesAndOverTheLimitTheVideoGoes() throws Exception {
        Path mp4 = mp4("big.mp4", "720x720", false); // the largest a GIF may be
        Path webp = GifVideos.webpUnder(mp4, Long.MAX_VALUE);
        byte[] b = Files.readAllBytes(webp);
        int w = 1 + ((b[24] & 0xff) | (b[25] & 0xff) << 8 | (b[26] & 0xff) << 16), h = 1 + ((b[27] & 0xff) | (b[28] & 0xff) << 8 | (b[29] & 0xff) << 16);
        assertEquals(List.of(720, 720), List.of(w, h), "its own size");
        assertEquals(30, new String(b, StandardCharsets.ISO_8859_1).split("ANMF", -1).length - 1, "its own 30 frames of a second");
        assertEquals(mp4, GifVideos.webpUnder(mp4, 10), "over 10 bytes: the video goes");
    }

    @Test
    void onlyAVeryLongOneGetsFewerFramesASecond() {
        assertEquals(AnimatedWebp.AS_IT_COMES, AnimatedWebp.rate(AnimatedWebp.AS_IT_COMES, new AnimatedWebp.Probe(false, 3, 30)), "a GIF's few seconds: as it comes");
        assertEquals(15, AnimatedWebp.rate(AnimatedWebp.AS_IT_COMES, new AnimatedWebp.Probe(false, 60, 30)), "a minute at 30: 900 frames, at most");
        assertEquals(30, AnimatedWebp.rate(30, new AnimatedWebp.Probe(false, 3, 0)), "a sticker's rate stays");
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
