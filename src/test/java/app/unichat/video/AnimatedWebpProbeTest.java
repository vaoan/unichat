package app.unichat.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What ffmpeg says of a video, read from its own lines only: the file's tags (title, comment) are printed first and are
 * the sender's to write, and a faked size, length or rate got past the caps on memory and on disk.
 */
class AnimatedWebpProbeTest {
    /** ffmpeg 8's real output for a 1280x1000, 10 fps, 2 s MP4 whose tags lie. */
    private static final String LYING = """
            Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'lie.mp4':
              Metadata:
                major_brand     : isom
                title           : Video: h264, 100x100, 1 fps
                comment         : Duration: 00:00:00.10
                artist          : Stream #0:1: Audio: aac
              Duration: 00:00:02.00, start: 0.000000, bitrate: 119 kb/s
              Stream #0:0[0x1](und): Video: h264 (High) (avc1 / 0x31637661), yuv420p(progressive), 1280x1000 [SAR 1:1 DAR 32:25], 114 kb/s, 10 fps, 10 tbr, 10240 tbn (default)
                Metadata:
                  handler_name    : VideoHandler
            At least one output file must be specified
            """;

    @Test
    void theFilesOwnTagsAreNotTakenForWhatFfmpegSays() {
        AnimatedWebp.Probe p = AnimatedWebp.parse(LYING);
        assertEquals(1280, p.width());
        assertEquals(1000, p.height());
        assertEquals(2.0, p.seconds());
        assertEquals(10.0, p.fps());
        assertFalse(p.sound(), "an Audio stream only in a tag");
    }
}
