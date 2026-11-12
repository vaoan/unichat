package app.unichat.autosave;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.video.Ffmpeg;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A GIF a friend sent from Telegram was saved as an MP4 (how Telegram keeps every GIF): it's saved as the GIF it was. */
class SavedFileTest {
    @TempDir Path dir;

    @Test
    void aGifThatCameAsAVideoIsSavedAsAGif() throws Exception {
        Path ffmpeg = Ffmpeg.find();
        Assumptions.assumeTrue(ffmpeg != null, "no ffmpeg");
        Path mp4 = Files.createDirectories(dir.resolve("cache")).resolve("abc.mp4");
        Process p = new ProcessBuilder(ffmpeg.toString(), "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=s=64x64:d=1:r=10",
                "-pix_fmt", "yuv420p", "-an", mp4.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Assumptions.assumeTrue(p.waitFor() == 0, "this ffmpeg can't make an MP4");
        Message m = new Message("$gifvideo", "!c", "@telegram_1", "Buga", false, Instant.EPOCH);
        m.setType(MessageType.VIDEO);
        m.setFileName("gif.mp4");
        m.setMimeType("video/mp4");
        m.setMediaUrl("mxc://s/v");
        m.setMediaInfo(new ObjectMapper().readTree("{\"fi.mau.gif\":true}"));
        AutoSave.Saver saver = new AutoSave.Saver() {
            @Override public Path media(String url, java.util.function.DoubleConsumer progress) { return mp4; }
            @Override public Failure link(String url, Path into, String tag, java.util.function.DoubleConsumer progress) { return null; }
        };
        Path into = dir.resolve("Buga");
        assertTrue(SavedFile.copyInto(saver, m, into, new Path[1], x -> { }));
        List<String> names;
        try (var in = Files.list(into)) {
            names = in.map(f -> f.getFileName().toString()).toList();
        }
        assertEquals(1, names.size());
        assertTrue(names.getFirst().startsWith("gif [") && names.getFirst().endsWith("].gif"), names.toString());
        assertEquals("GIF89a", new String(Files.readAllBytes(into.resolve(names.getFirst())), 0, 6, java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(FileNames.savedIn(into, m), "known again: not saved twice");
    }

    @Test
    void aLongOneIsSavedAsItsVideoWhole() throws Exception {
        Path mp4 = Files.createDirectories(dir.resolve("cache")).resolve("long.mp4");
        Files.writeString(mp4, "not decoded: never converted");
        Message m = new Message("$long", "!c", "@telegram_1", "Buga", false, Instant.EPOCH);
        m.setType(MessageType.VIDEO);
        m.setFileName("IMG_0448.MP4");
        m.setMimeType("video/mp4");
        m.setMediaUrl("mxc://s/l");
        m.setMediaInfo(new ObjectMapper().readTree("{\"fi.mau.gif\":true,\"duration\":40000}"));
        AutoSave.Saver saver = new AutoSave.Saver() {
            @Override public Path media(String url, java.util.function.DoubleConsumer progress) { return mp4; }
            @Override public Failure link(String url, Path into, String tag, java.util.function.DoubleConsumer progress) { return null; }
        };
        Path into = dir.resolve("Buga");
        app.unichat.video.GifVideos.setLongestSeconds(15); // a limit chosen in Ajustes (none by default, as Telegram)
        try {
            assertTrue(SavedFile.copyInto(saver, m, into, new Path[1], x -> { }));
        } finally {
            app.unichat.video.GifVideos.setLongestSeconds(app.unichat.video.GifVideos.NO_LIMIT);
        }
        try (var in = Files.list(into)) {
            String name = in.map(f -> f.getFileName().toString()).findFirst().orElseThrow();
            assertTrue(name.endsWith("].MP4"), "past the limit it's saved as its video, whole: " + name);
        }
    }
}
