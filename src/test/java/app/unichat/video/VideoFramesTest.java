package app.unichat.video;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A video sticker's frames straight from ffmpeg at the size they're shown: no GIF made first (up to 21 MB, seconds each). */
class VideoFramesTest {
    @TempDir Path dir;

    private Path webm(String filter, String size, int seconds, int rate) throws Exception {
        Path ffmpeg = Ffmpeg.find();
        Assumptions.assumeTrue(ffmpeg != null, "no ffmpeg");
        Path webm = dir.resolve("s.webm");
        Process p = new ProcessBuilder(ffmpeg.toString(), "-y", "-loglevel", "error", "-f", "lavfi", "-i",
                filter + "=s=" + size + ":d=" + seconds + ":r=" + rate + ",format=yuva420p", "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p",
                webm.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Assumptions.assumeTrue(p.waitFor() == 0, "this ffmpeg can't make a WebM");
        return webm;
    }

    @Test
    void itsFramesComeAtTheSizeShownWithTheirTiming() throws Exception {
        Path webm = webm("testsrc", "128x64", 1, 10);
        VideoFrames.Frames f = VideoFrames.read(webm, VideoFrames.probe(webm), 64, 32, 120);
        assertEquals(10, f.rgba().size(), "its 10 frames");
        assertEquals("64x32", f.width() + "x" + f.height());
        assertEquals(64 * 32 * 4, f.rgba().getFirst().length, "RGBA");
        assertEquals(100, f.delayMs());
    }

    @Test
    void aLongOneGetsFewerFramesASecondNotACut() throws Exception {
        Path webm = webm("testsrc", "32x32", 4, 30);
        VideoFrames.Frames f = VideoFrames.read(webm, VideoFrames.probe(webm), 32, 32, 60);
        assertTrue(f.rgba().size() <= 60 && f.rgba().size() >= 55, "about 60 frames: " + f.rgba().size());
        assertTrue(f.delayMs() >= 60, "15 a second, so the whole 4 s: " + f.delayMs());
    }
}
