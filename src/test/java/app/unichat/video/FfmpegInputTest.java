package app.unichat.video;

import app.unichat.support.Processes;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Assumptions;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(List.of("-protocol_whitelist", "file", "-format_whitelist", Ffmpeg.FORMATS, "-i", video.toString()),
                Ffmpeg.input(video));
        for (String lists : List.of("hls", "concat", "dash", "image2pipe", "tty", "srt")) {
            assertFalse(List.of(Ffmpeg.FORMATS.split(",")).contains(lists), lists + " isn't one ffmpeg may read a stranger's file as");
        }
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

    /** ffmpeg reading {@code file} as a stranger's: whether it opened it, and what it said. */
    @SuppressFBWarnings(value = "COMMAND_INJECTION", justification = "a test runs its own tool (ffmpeg) with its own fixed arguments; nothing from outside")
    private static Processes.Result read(Path ffmpeg, Path file) throws IOException, InterruptedException {
        List<String> c = new java.util.ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-v", "error"));
        c.addAll(Ffmpeg.input(file));
        c.addAll(List.of("-f", "null", "-"));
        return Processes.capture(new ProcessBuilder(c).redirectErrorStream(true), Long.MAX_VALUE);
    }

    @Test
    void ffmpegRefusesAFileOfAFormatOutsideTheList() throws IOException, InterruptedException {
        Path ffmpeg = Ffmpeg.find();
        Assumptions.assumeTrue(ffmpeg != null, "needs an ffmpeg");
        // Subtitles pass the check of how lists begin, and ffmpeg would read them: a format of no use to Unichat.
        Path subtitles = Files.writeString(dir.resolve("clip"), "1\n00:00:00,000 --> 00:00:01,000\nhola\n");
        Processes.Result r = read(ffmpeg, subtitles);
        assertFalse(r.ok(), r.out());
        assertTrue(r.out().contains("whitelist"), "refused for its format: " + r.out());
        // A DASH manifest behind a comment passes the check too; the format list keeps ffmpeg from reading it as one.
        Path manifest = Files.writeString(dir.resolve("clip2"), "<!---->\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">"
                + "<Period><AdaptationSet><Representation><BaseURL>file:///etc/passwd</BaseURL></Representation>"
                + "</AdaptationSet></Period></MPD>\n");
        assertFalse(read(ffmpeg, manifest).ok(), "never read as a manifest");
    }

    @Test
    void ffmpegStillReadsEveryKindUnichatGivesIt() throws IOException, InterruptedException {
        Path ffmpeg = Ffmpeg.find();
        Assumptions.assumeTrue(ffmpeg != null, "needs an ffmpeg");
        for (String name : List.of("a.mp4", "a.mov", "a.mkv", "a.webm", "a.gif", "a.png", "a.jpg", "a.ogg", "a.mp3", "a.m4a", "a.wav",
                "a.flac", "a.aac")) {
            Path made = dir.resolve(name);
            boolean audio = name.matches(".*\\.(ogg|mp3|m4a|wav|flac|aac)");
            List<String> c = new java.util.ArrayList<>(List.of(ffmpeg.toString(), "-nostdin", "-v", "error", "-y", "-f", "lavfi", "-i",
                    audio ? "sine=d=0.2" : "testsrc=s=32x32:d=0.2:r=5"));
            if (name.endsWith(".png") || name.endsWith(".jpg")) c.addAll(List.of("-frames:v", "1"));
            c.add(made.toString());
            Processes.Result m = make(c);
            Assumptions.assumeTrue(m.ok(), "needs an ffmpeg that writes " + name + ": " + m.out());
            for (Path p : List.of(made, Files.copy(made, dir.resolve("no-extension-" + name.replace('.', '-'))))) {
                Processes.Result r = read(ffmpeg, p);
                assertTrue(r.ok(), p.getFileName() + ": " + r.out());
            }
        }
    }

    @SuppressFBWarnings(value = "COMMAND_INJECTION", justification = "a test runs its own tool (ffmpeg) with its own fixed arguments; nothing from outside")
    private static Processes.Result make(List<String> command) throws IOException, InterruptedException {
        return Processes.capture(new ProcessBuilder(command).redirectErrorStream(true), Long.MAX_VALUE);
    }

    @Test
    void aNumberPatternInThePathIsRefusedSoFfmpegNeverReadsAnotherFile() throws IOException {
        // ffmpeg's image2 reads "a%d.jpg" as a1.jpg, a2.jpg…, and "d%d/x.jpg" as d1/x.jpg (measured on 9.0.2): a stranger's
        // IMG_%04d.JPG saved beside the person's IMG_0001.JPG, then sent as a photo, would send theirs instead.
        Files.writeString(dir.resolve("a1.jpg"), "someone else's");
        Files.writeString(dir.resolve("IMG_0001.JPG"), "the person's own");
        Files.createDirectories(dir.resolve("d1"));
        Files.writeString(dir.resolve("d1/x.jpg"), "another folder's");
        Files.createDirectories(dir.resolve("d%d"));
        for (String name : List.of("a%d.jpg", "IMG_%04d.JPG", "d%d/x.jpg", "b%3d.png")) {
            Path f = Files.writeString(dir.resolve(name), "picture");
            assertThrows(IOException.class, () -> Ffmpeg.input(f), name);
        }
        for (String name : List.of("50%.jpg", "100% real.mp4", "a%%d.jpg")) {
            Path f = Files.write(dir.resolve(name), new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'});
            if (!name.contains("%%")) assertEquals(f.toString(), Ffmpeg.input(f).getLast(), "a % that isn't a pattern is fine: " + name);
        }
    }

    @Test
    void aPhotoWithAPatternInItsNameIsNeverMadeOfAnotherFile() throws IOException {
        Files.writeString(dir.resolve("IMG_0001.JPG"), "the person's own");
        Path stranger = Files.writeString(dir.resolve("IMG_%04d.JPG"), "a stranger's");
        assertThrows(IOException.class, () -> SendablePhotos.ffmpeg(dir.resolve("ffmpeg"), stranger, dir.resolve("out.jpg")),
                "the ffmpeg way isn't there for it: the photo goes as it is");
    }
}
