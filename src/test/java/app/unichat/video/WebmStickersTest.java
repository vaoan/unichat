package app.unichat.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The real conversions, where there's an ffmpeg with VP9 (and img2webp; without them it's skipped). */
class WebmStickersTest {
    @TempDir Path dir;

    private Path webmWithTransparency(Path ffmpeg) throws Exception {
        Path webm = dir.resolve("hash.webm");
        Process p = new ProcessBuilder(ffmpeg.toString(), "-y", "-loglevel", "error", "-f", "lavfi",
                "-i", "testsrc=s=128x128:d=1:r=10,format=yuva420p,colorchannelmixer=aa=0.5", "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", webm.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(p.waitFor() == 0, "this ffmpeg can't make a WebM");
        return webm;
    }

    /** A video sticker for another network: an animated WebP that keeps its transparency, never a GIF (all or nothing). */
    @Test
    void aWebmBecomesAnAnimatedWebpWithItsTransparency() throws Exception {
        Path ffmpeg = Ffmpeg.find();
        assumeTrue(ffmpeg != null, "no ffmpeg");
        assumeTrue(AnimatedWebp.img2webp() != null, "no img2webp");
        Path webp = WebmStickers.toWebp(webmWithTransparency(ffmpeg), dir.resolve("out.webp"), p -> { });
        byte[] bytes = Files.readAllBytes(webp);
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("RIFF") && text.startsWith("WEBP", 8), "a WebP");
        assertTrue(text.contains("ANIM"), "animated");
        assertTrue(text.contains("ALPH") || (bytes[20] & 0x10) != 0, "with its transparency");
        try (var left = Files.list(dir)) {
            assertTrue(left.noneMatch(f -> f.getFileName().toString().contains(".part")), "its frames gone");
        }
    }

    @Test
    void oneThatCantBeMadeSaysWhatFfmpegSaid() throws Exception {
        assumeTrue(Ffmpeg.find() != null, "no ffmpeg");
        assumeTrue(AnimatedWebp.img2webp() != null, "no img2webp");
        Path notWebm = Files.writeString(dir.resolve("x.webm"), "not a video");
        IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> WebmStickers.toWebp(notWebm, dir.resolve("x.webp"), p -> { }));
        assertTrue(e.getMessage().contains("exit"), "the log can tell why: " + e.getMessage());
    }

    @Test
    void anAnimatedGifBecomesATelegramVideoSticker() throws Exception {
        Path ffmpeg = Ffmpeg.find();
        assumeTrue(ffmpeg != null, "no ffmpeg");
        Path gif = dir.resolve("in.gif");
        Process p = new ProcessBuilder(ffmpeg.toString(), "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=s=160x120:d=5:r=10",
                gif.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(p.waitFor() == 0, "this ffmpeg can't make a GIF");
        Path out = dir.resolve("out.webm");
        assertEquals(out, WebmStickers.fromGif(gif, out, f -> { }));
        byte[] bytes = Files.readAllBytes(out);
        assertTrue(bytes[0] == 0x1A && bytes[1] == 0x45 && (bytes[2] & 0xFF) == 0xDF && (bytes[3] & 0xFF) == 0xA3, "a WebM (EBML)");
        assertTrue(new String(bytes, StandardCharsets.ISO_8859_1).contains("webm"));
        assertTrue(bytes.length < 256 * 1024, "within Telegram's limit: " + bytes.length);
    }

    @Test
    void theTelegramCommandReadsOnlyTheGifAndFollowsTelegramsRules() {
        List<String> c = WebmStickers.fromGifCommand(Path.of("ffmpeg"), Path.of("a.gif"), Path.of("b.webm"));
        assertEquals("file", c.get(c.indexOf("-protocol_whitelist") + 1));
        assertEquals("gif", c.get(c.indexOf("-f") + 1), "a forced format: nothing probed");
        assertEquals("3", c.get(c.indexOf("-t") + 1));
        assertTrue(c.contains("-an"));
        assertEquals("yuva420p", c.get(c.indexOf("-pix_fmt") + 1), "transparent, as a sticker");
        assertTrue(c.get(c.indexOf("-vf") + 1).contains("scale=512:512"));
        assertEquals("webm", c.get(c.size() - 2));
    }
}
