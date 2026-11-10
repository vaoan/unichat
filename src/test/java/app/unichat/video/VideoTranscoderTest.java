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

    @Test
    void picturesAreToldFromVideos() {
        assertTrue(VideoTranscoder.picture("Input #0, gif, from 'a.gif':\n  Stream #0:0: Video: gif, bgra, 32x32"));
        assertTrue(VideoTranscoder.picture("Input #0, png_pipe, from 'a.png':\n  Stream #0:0: Video: png, rgb24"));
        assertTrue(VideoTranscoder.picture("Input #0, jpeg_pipe, from 'a.jpg':\n  Stream #0:0: Video: mjpeg (Baseline)"));
        assertTrue(VideoTranscoder.picture("Input #0, webp_pipe, from 'a.webp':\n  Stream #0:0: Video: webp"));
        assertTrue(VideoTranscoder.picture("Input #0, apng, from 'a.png':\n  Stream #0:0: Video: apng"));
        assertTrue(VideoTranscoder.picture("Input #0, image2, from 'a.jpg':\n  Stream #0:0: Video: mjpeg"));
        assertFalse(VideoTranscoder.picture("Input #0, avi, from 'cam.avi':\n  Stream #0:0: Video: mjpeg (MJPG)"),
                "a camera's MJPEG video is a video");
        assertFalse(VideoTranscoder.picture("Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'v.mp4':\n  Stream #0:0: Video: vp9"));
        assertFalse(VideoTranscoder.picture("Invalid data found when processing input"));
    }

    /** gallery-dl saves a link's pictures: each stays the picture it is, never a one-frame MP4. */
    @Test
    void picturesAreLeftAlone() throws Exception {
        Path ffmpeg = Ffmpeg.find();
        Assumptions.assumeTrue(ffmpeg != null, "needs an ffmpeg");
        for (String name : List.of("foto [1].png", "foto [2].jpg", "animada [3].gif")) {
            Path picture = dir.resolve(name);
            List<String> make = new java.util.ArrayList<>(List.of(ffmpeg.toString(), "-nostdin", "-y", "-loglevel", "error",
                    "-f", "lavfi", "-i", "testsrc=size=32x32:rate=5:duration=0.4"));
            if (!name.endsWith(".gif")) make.addAll(List.of("-frames:v", "1"));
            make.add(picture.toString());
            Processes.Result made = Processes.capture(new ProcessBuilder(make).redirectErrorStream(true), NO_LIMIT);
            Assumptions.assumeTrue(made.ok(), "needs an ffmpeg that writes " + name + ": " + made.out());
            byte[] before = Files.readAllBytes(picture);

            assertEquals(picture, VideoTranscoder.makePlayable(ffmpeg, picture), name);
            org.junit.jupiter.api.Assertions.assertArrayEquals(before, Files.readAllBytes(picture), name + " untouched");
        }
        assertEquals(List.of("animada [3].gif", "foto [1].png", "foto [2].jpg"),
                Files.list(dir).map(p -> p.getFileName().toString()).sorted().toList(), "no .mp4 beside them");
    }
}
