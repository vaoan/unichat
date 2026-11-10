package app.unichat.video;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YtDlpTest {
    @Test
    void oneVideoIntoTheFolderAndTheLinkCantPassForAnOption() {
        List<String> c = YtDlp.command(Path.of("/t/yt-dlp"), Path.of("/t/ffmpeg"), Path.of("/d/Familia"), "--exec=rm", "abc123");
        assertTrue(c.contains("--no-playlist"));
        assertEquals("/d/Familia", c.get(c.indexOf("-P") + 1));
        assertEquals(List.of("--", "--exec=rm"), c.subList(c.size() - 2, c.size()), "after --: a link, whatever it says");
        assertEquals("/t/ffmpeg", c.get(c.indexOf("--ffmpeg-location") + 1));
        assertTrue(!YtDlp.command(Path.of("y"), null, Path.of("d"), "https://x", null).contains("--ffmpeg-location"));
        assertEquals("%(title).150B [%(id)s] [abc123].%(ext)s", c.get(c.indexOf("-o") + 1), "the message's id in the name too");
    }

    @Test
    void theReasonIsYtDlpsOwnErrorLine() {
        assertEquals("Video unavailable", YtDlp.reason("[youtube] abc: Downloading webpage\nERROR: [youtube] abc: Video unavailable\n"));
        assertEquals("Unsupported URL: https://x.org", YtDlp.reason("WARNING: x\nERROR: Unsupported URL: https://x.org"));
        assertEquals("algo raro", YtDlp.reason("algo raro\n"), "no ERROR line: the last one");
    }
}
