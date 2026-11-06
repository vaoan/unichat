package app.unichat.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The real conversion, where there's an ffmpeg with VP9 and WebP (without one it's skipped). */
class WebmStickersTest {
    @TempDir Path dir;

    private Path webmWithTransparency(Path ffmpeg) throws Exception {
        Path webm = dir.resolve("hash.webm");
        Process p = new ProcessBuilder(ffmpeg.toString(), "-y", "-loglevel", "error", "-f", "lavfi",
                "-i", "testsrc=s=128x128:d=1:r=10,format=yuva420p,colorchannelmixer=aa=0.5", "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", webm.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0, "this ffmpeg can't make a WebM");
        return webm;
    }

    private static void assertAnimatedWithTransparency(Path picture) throws Exception {
        byte[] bytes = Files.readAllBytes(picture);
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        if (text.startsWith("GIF")) {
            assertTrue((bytes[10] & 0x80) != 0 || text.contains("NETSCAPE2.0"), "a GIF");
            assertTrue(text.contains("NETSCAPE2.0"), "looping");
            assertTrue(text.contains("\u0021\u00F9"), "with frames");
            return;
        }
        assertTrue(text.startsWith("RIFF") && text.startsWith("WEBP", 8), "a WebP");
        assertTrue(text.contains("ANIM"), "animated");
        assertTrue((bytes[20] & 0x10) != 0, "with alpha");
    }

    @Test
    void aWebmBecomesAnAnimatedPictureOnce() throws Exception {
        Path ffmpeg = Ffmpeg.find();
        assumeTrue(ffmpeg != null, "no ffmpeg");
        Path webm = webmWithTransparency(ffmpeg);
        Path picture = WebmStickers.animated(webm, p -> { });
        assertAnimatedWithTransparency(picture);
        assertEquals(dir, picture.getParent(), "beside the original: the cache limit covers it");

        Files.delete(webm);
        assertEquals(picture, WebmStickers.animated(webm, p -> { }), "converted once");
        assertTrue(Files.list(dir).noneMatch(f -> f.getFileName().toString().contains(".part")));
    }

    @Test
    void anFfmpegWithoutWebpStillGivesAGif() throws Exception {
        Path ffmpeg = Ffmpeg.find();
        assumeTrue(ffmpeg != null, "no ffmpeg");
        Path webm = webmWithTransparency(ffmpeg);
        Path gif = dir.resolve("out.gif");
        Process p = new ProcessBuilder(WebmStickers.command(ffmpeg, webm, gif, "gif", true)).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0);
        assertAnimatedWithTransparency(gif);
    }

    @Test
    void theCommandOnlyReadsThatFileAndKeepsItSmall() {
        List<String> c = WebmStickers.command(Path.of("ffmpeg"), Path.of("a.webm"), Path.of("b"), "webp", true);
        assertEquals("file", c.get(c.indexOf("-protocol_whitelist") + 1));
        assertEquals("matroska", c.get(c.indexOf("-f") + 1));
        assertTrue(c.indexOf("libvpx-vp9") < c.indexOf("-i"), "the decoder goes before the input");
        assertEquals("30", c.get(c.indexOf("-t") + 1));
        assertEquals("webp", c.get(c.size() - 2));
        assertTrue(WebmStickers.command(Path.of("ffmpeg"), Path.of("a.webm"), Path.of("b"), "gif", false).stream().noneMatch(a -> a.contains("libvpx")));
    }
}
