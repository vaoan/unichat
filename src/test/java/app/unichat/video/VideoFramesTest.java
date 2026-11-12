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
        java.util.List<Integer> sizes = new java.util.ArrayList<>();
        int ms = VideoFrames.read(webm, VideoFrames.probe(webm), 64, 32, 120, rgba -> sizes.add(rgba.length));
        assertEquals(10, sizes.size(), "its 10 frames, one at a time");
        assertTrue(sizes.stream().allMatch(n -> n == 64 * 32 * 4), "RGBA at the size shown");
        assertEquals(100, ms);
    }

    @Test
    void aLongOneGetsFewerFramesASecondNotACut() throws Exception {
        Path webm = webm("testsrc", "32x32", 4, 30);
        int[] count = {0};
        int ms = VideoFrames.read(webm, VideoFrames.probe(webm), 32, 32, 60, rgba -> count[0]++);
        assertTrue(count[0] <= 60 && count[0] >= 55, "about 60 frames: " + count[0]);
        assertTrue(ms >= 60, "15 a second, so the whole 4 s: " + ms);
    }
}
