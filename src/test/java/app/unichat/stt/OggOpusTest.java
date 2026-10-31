package app.unichat.stt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OggOpusTest {
    /** The demo voice note: 2 s of a 440 Hz tone, Ogg/Opus like WhatsApp's. */
    private static Path fixture(Path dir) throws Exception {
        Path f = dir.resolve("nota.ogg");
        try (InputStream in = OggOpusTest.class.getResourceAsStream("/app/unichat/demo/nota-de-voz.ogg")) {
            Files.copy(in, f);
        }
        return f;
    }

    @Test
    void decodesAVoiceNoteToWhisperWav(@TempDir Path dir) throws Exception {
        Path ogg = fixture(dir), wav = dir.resolve("out.wav");
        assertTrue(OggOpus.isOggOpus(ogg));
        double seconds = OggOpus.toWav(ogg, wav);
        assertEquals(2.0, seconds, 0.05, "pre-skip removed, nothing lost");

        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(wav)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", new String(Files.readAllBytes(wav), 0, 4));
        assertEquals(1, b.getShort(22), "mono");
        assertEquals(16_000, b.getInt(24));
        assertEquals(16, b.getShort(34), "16-bit");
        assertEquals(b.getInt(40) + 44, Files.size(wav));
        // ffmpeg's sine (amplitude 1/8) at 0.3 volume: RMS ~0.027 (ffmpeg's own decoder gives 0.0269), and a 440 Hz
        // tone crosses zero ~880 times a second; silence or noise would be far off.
        double sum = 0;
        int n = b.getInt(40) / 2, crossings = 0;
        short prev = 0;
        for (int i = 0; i < n; i++) {
            short v = b.getShort(44 + i * 2);
            sum += (v / 32768.0) * (v / 32768.0);
            if ((prev < 0) != (v < 0)) crossings++;
            prev = v;
        }
        assertEquals(0.027, Math.sqrt(sum / n), 0.006);
        assertEquals(1760, crossings, 60);
        assertTrue(Transcriber.whisperReads(wav));
    }

    @Test
    void otherFilesAreNotOpus(@TempDir Path dir) throws Exception {
        Path wav = dir.resolve("x.wav");
        Files.write(wav, OggOpus.wavHeader(0));
        assertFalse(OggOpus.isOggOpus(wav));
        Path empty = Files.write(dir.resolve("e"), new byte[0]);
        assertFalse(OggOpus.isOggOpus(empty));
    }
}
