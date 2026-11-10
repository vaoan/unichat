package app.unichat.video;

import app.unichat.support.Processes;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a link saves always opens on a Mac or Windows: VP9 (Instagram's) becomes H.264, in its place. */
class VideoTranscoderTest {
    /** The test ends when ffmpeg does; JUnit's own limit is the only one. */
    private static final long NO_LIMIT = Long.MAX_VALUE;

    @TempDir
    Path dir;

    @Test
    void whatEveryComputerPlays() {
        assertTrue(VideoTranscoder.playsEverywhere("""
                  Stream #0:0[0x1](und): Video: h264 (High) (avc1 / 0x31637661), yuv420p, 720x1280
                  Stream #0:1[0x2](und): Audio: aac (HE-AAC) (mp4a / 0x6134706D), 44100 Hz, stereo"""));
        assertFalse(VideoTranscoder.playsEverywhere("""
                  Stream #0:0[0x1](und): Video: vp9 (Profile 0) (vp09 / 0x39307076), yuv420p, 1080x1080
                  Stream #0:1[0x2](und): Audio: aac (LC) (mp4a / 0x6134706D), 44100 Hz"""), "a Mac doesn't open VP9");
        assertFalse(VideoTranscoder.playsEverywhere("  Stream #0:0: Video: h264\n  Stream #0:1: Audio: opus, 48000 Hz"), "nor Opus in an mp4");
        assertFalse(VideoTranscoder.playsEverywhere("Invalid data found when processing input"), "nothing read: not known to play");
    }

    @Test
    void aVp9VideoBecomesH264InItsPlace() throws Exception {
        Path ffmpeg = Ffmpeg.find();
        Assumptions.assumeTrue(ffmpeg != null, "needs an ffmpeg");
        Path vp9 = dir.resolve("Video by alguien [Dx1] [abc].mp4");
        Processes.Result made = Processes.capture(new ProcessBuilder(ffmpeg.toString(), "-nostdin", "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=64x64:rate=10:duration=1", "-c:v", "libvpx-vp9", vp9.toString())
                .redirectErrorStream(true), NO_LIMIT);
        Assumptions.assumeTrue(made.ok(), "needs an ffmpeg with a VP9 encoder to make the sample: " + made.out());

        Path result = VideoTranscoder.makePlayable(ffmpeg, vp9);
        assertEquals(vp9, result, "same name: the message's id stays in it");
        String info = Processes.capture(new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-nostdin", "-i", result.toString())
                .redirectErrorStream(true), NO_LIMIT).out();
        assertTrue(VideoTranscoder.playsEverywhere(info), info);
        assertEquals(List.of("Video by alguien [Dx1] [abc].mp4"), Files.list(dir).map(p -> p.getFileName().toString()).toList(),
                "nothing half-written left behind");

        assertEquals(result, VideoTranscoder.makePlayable(ffmpeg, result), "H.264 already: left as it is");
    }
}
