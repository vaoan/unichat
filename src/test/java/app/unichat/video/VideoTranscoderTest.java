package app.unichat.video;

import app.unichat.Present;
import app.unichat.support.PathParts;
import app.unichat.support.Processes;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

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
    @SuppressFBWarnings(value = "COMMAND_INJECTION", justification = "a test runs its own tool (ffmpeg, mkfifo, sh, java) with its own fixed arguments; nothing from outside")
    void aVp9VideoBecomesH264InItsPlace() throws Exception {
        Path ffmpeg = Present.orSkip(Ffmpeg.find(), "needs an ffmpeg");
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
        try (Stream<Path> stream = Files.list(dir)) {
            assertEquals(List.of("Video by alguien [Dx1] [abc].mp4"), stream.map(PathParts::name).toList(),
                    "nothing half-written left behind");
        }

        assertEquals(result, VideoTranscoder.makePlayable(ffmpeg, result), "H.264 already: left as it is");
    }

    /** A GIF's MP4 is silent (Telegram takes only a silent video as a GIF); a playable copy keeps the sound. */
    @Test
    void aGifsMp4HasNoSoundAndPlaysEverywhere() throws Exception {
        Path in = Files.write(dir.resolve("gif.webm"), new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3});
        List<String> gif = VideoTranscoder.command(Path.of("ffmpeg"), in, dir.resolve("out.mp4"), "libx264", false);
        assertTrue(gif.contains("-an"), gif.toString());
        assertFalse(gif.contains("0:a:0?") || gif.contains("aac"), gif.toString());
        for (String part : List.of("yuv420p", "scale=trunc(iw/2)*2:trunc(ih/2)*2", "+faststart", "-format_whitelist")) {
            assertTrue(gif.contains(part), part + " in " + gif);
        }
        List<String> playable = VideoTranscoder.command(Path.of("ffmpeg"), in, dir.resolve("out.mp4"), "libx264", true);
        assertTrue(playable.contains("0:a:0?") && playable.contains("aac"), playable.toString());
        assertFalse(playable.contains("-an"));
    }

    @Test
    @SuppressFBWarnings(value = "COMMAND_INJECTION", justification = "a test runs its own tool (ffmpeg) with its own fixed arguments; nothing from outside")
    void aWebmBecomesASilentH264Mp4BesideIt() throws Exception {
        Path ffmpeg = Present.orSkip(Ffmpeg.find(), "needs an ffmpeg");
        Path webm = dir.resolve("gif.webm");
        Processes.Result made = Processes.capture(new ProcessBuilder(ffmpeg.toString(), "-nostdin", "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=63x47:rate=10:duration=1", "-f", "lavfi", "-i", "sine=d=1",
                "-c:v", "libvpx-vp9", "-c:a", "libopus", "-shortest", webm.toString()).redirectErrorStream(true), NO_LIMIT);
        Assumptions.assumeTrue(made.ok(), "needs an ffmpeg with VP9 and Opus encoders to make the sample: " + made.out());

        Path mp4 = VideoTranscoder.toGifMp4(webm, p -> { });
        assertTrue(app.unichat.support.ImageHeaders.isMp4(mp4));
        String info = Processes.capture(new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-nostdin", "-i", mp4.toString())
                .redirectErrorStream(true), NO_LIMIT).out();
        assertTrue(VideoTranscoder.playsEverywhere(info), info);
        assertFalse(info.contains("Audio:"), info);
        assertTrue(info.contains("62x46"), "even sides: " + info);
        assertEquals(mp4, VideoTranscoder.toGifMp4(webm, p -> { }), "made once");
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
    @SuppressFBWarnings(value = "COMMAND_INJECTION", justification = "a test runs its own tool (ffmpeg, mkfifo, sh, java) with its own fixed arguments; nothing from outside")
    void picturesAreLeftAlone() throws Exception {
        Path ffmpeg = Present.orSkip(Ffmpeg.find(), "needs an ffmpeg");
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
        try (Stream<Path> stream = Files.list(dir)) {
            assertEquals(List.of("animada [3].gif", "foto [1].png", "foto [2].jpg"),
                    stream.map(PathParts::name).sorted().toList(), "no .mp4 beside them");
        }
    }
}
