package app.unichat.stt;

import app.unichat.support.Os;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Audio from other people goes to ffmpeg locked to its own file, and through the ffmpeg the rest of the app uses. */
class TranscriberFfmpegTest {
    @TempDir Path dir;

    @AfterEach
    void forget() { System.clearProperty("unichat.ffmpeg"); }

    @Test
    void usesTheAppsFfmpegAndKeepsItToTheFile() throws Exception {
        assumeFalse(Os.windows(), "the fake ffmpeg is a shell script");
        Path args = dir.resolve("args.txt");
        Path fake = dir.resolve("fake-ffmpeg");
        Files.writeString(fake, "#!/bin/sh\nfor a in \"$@\"; do echo \"$a\"; done > '" + args + "'\n");
        assertTrue(fake.toFile().setExecutable(true));
        System.setProperty("unichat.ffmpeg", fake.toString());
        Path aac = dir.resolve("nota.m4a");
        Files.write(aac, new byte[64]);

        assertEquals(dir.resolve("audio.wav"), Transcriber.prepare(aac, dir));

        List<String> seen = Files.readAllLines(args);
        assertTrue(seen.contains("-nostdin"), seen.toString());
        int whitelist = seen.indexOf("-protocol_whitelist");
        assertTrue(whitelist >= 0 && seen.get(whitelist + 1).equals("file"), seen.toString());
        assertTrue(whitelist < seen.indexOf("-i"), "it only applies to what comes after it: " + seen);
    }
}
