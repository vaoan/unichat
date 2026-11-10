package app.unichat.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ffmpeg tells a file's format by its content, whatever its name: a "video" from a chat that is really an HLS playlist
 * (or a concat list, or a DASH manifest) names other files, and {@code -protocol_whitelist file} still lets it read
 * them, so ~/.ssh/id_rsa could end up inside the converted video, saved and uploaded to the person's cloud.
 */
class FfmpegInputTest {
    @TempDir Path dir;

    @Test
    void aListOfOtherFilesIsNeverGivenToFfmpeg() throws IOException {
        for (String head : List.of("#EXTM3U\n#EXTINF:1,\nfile:///etc/passwd\n", "ffconcat version 1.0\nfile '/etc/passwd'\n",
                "﻿  <?xml version=\"1.0\"?><MPD/>", "<MPD xmlns=\"urn:mpeg:dash\"/>")) {
            Path f = Files.writeString(dir.resolve("video.mp4"), head);
            assertThrows(IOException.class, () -> Ffmpeg.input(f), head);
        }
        Path video = Files.write(dir.resolve("real.mp4"), new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'});
        assertEquals(List.of("-protocol_whitelist", "file", "-i", video.toString()), Ffmpeg.input(video));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aPlaylistDisguisedAsAVideoIsRefusedBeforeFfmpegRuns() throws Exception {
        Path ran = dir.resolve("ran");
        Path ffmpeg = Files.writeString(dir.resolve("ffmpeg"), "#!/bin/sh\ntouch '" + ran + "'\n");
        ffmpeg.toFile().setExecutable(true);
        Path playlist = Files.writeString(dir.resolve("clip.mp4"), "#EXTM3U\n#EXTINF:1,\nfile:///etc/passwd\n");
        assertThrows(IOException.class, () -> VideoTranscoder.makePlayable(ffmpeg, playlist));
        assertFalse(Files.exists(ran), "ffmpeg never ran on it");
    }
}
