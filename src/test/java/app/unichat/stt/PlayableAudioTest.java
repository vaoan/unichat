package app.unichat.stt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayableAudioTest {
    @TempDir Path dir;

    private Path voiceNote() throws Exception {
        Path f = dir.resolve("nota.ogg");
        try (InputStream in = PlayableAudioTest.class.getResourceAsStream("/app/unichat/demo/nota-de-voz.ogg")) {
            Files.copy(in, f);
        }
        return f;
    }

    @Test
    void aVoiceNoteBecomesAWavAt48kHzOnce() throws Exception {
        Path ogg = voiceNote();
        Path wav = PlayableAudio.of(ogg);
        assertNotEquals(ogg, wav);
        assertEquals(dir, wav.getParent(), "beside the original: the cache limit covers it");
        byte[] b = Files.readAllBytes(wav);
        ByteBuffer h = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(48_000, h.getInt(24), "sample rate");
        assertEquals(96_000, h.getInt(28), "bytes per second");
        double seconds = (b.length - 44) / 96_000.0;
        assertTrue(seconds > 1.5 && seconds < 2.5, "the demo note lasts 2 s: " + seconds);

        long written = Files.getLastModifiedTime(wav).toMillis();
        Files.delete(ogg);
        Files.copy(PlayableAudioTest.class.getResourceAsStream("/app/unichat/demo/nota-de-voz.ogg"), ogg);
        assertEquals(wav, PlayableAudio.of(ogg));
        assertTrue(Files.getLastModifiedTime(wav).toMillis() >= written);
        assertTrue(Files.list(dir).noneMatch(f -> f.getFileName().toString().endsWith(".part")));
    }

    @Test
    void whatTheyCanAlreadyPlayIsLeftAlone() throws Exception {
        Path mp3 = Files.write(dir.resolve("a.mp3"), new byte[100]);
        assertEquals(mp3, PlayableAudio.of(mp3));
    }
}
