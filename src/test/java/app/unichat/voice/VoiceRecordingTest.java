package app.unichat.voice;

import app.unichat.model.VoiceNote;
import app.unichat.stt.OggOpus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VoiceRecordingTest {
    @TempDir Path dir;

    /** A tone, as fast as it's asked for, for {@code seconds}. */
    private static final class Tone implements PcmSource {
        private final int rate;
        private final double hz;
        private final long total;
        private long at;
        private volatile boolean closed;

        Tone(int rate, double hz, double seconds) {
            this.rate = rate;
            this.hz = hz;
            this.total = (long) (rate * seconds);
        }

        @Override public int rate() { return rate; }

        @Override
        public int read(short[] into) {
            if (closed || at >= total) return -1;
            int n = (int) Math.min(into.length, total - at);
            for (int i = 0; i < n; i++, at++) into[i] = (short) (hz == 0 ? 0 : 12_000 * Math.sin(2 * Math.PI * hz * at / rate));
            return n;
        }

        @Override public void close() { closed = true; }
    }

    private VoiceNote record(PcmSource source) throws Exception {
        VoiceRecorder r = new VoiceRecorder(source, dir.resolve("nota.ogg"));
        r.start();
        for (int i = 0; i < 200 && !r.ended(); i++) Thread.sleep(25);
        return r.stop();
    }

    private static double rms(Path wav) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(wav)).order(ByteOrder.LITTLE_ENDIAN);
        b.position(44);
        double sum = 0;
        long n = 0;
        while (b.remaining() >= 2) {
            double v = b.getShort();
            sum += v * v;
            n++;
        }
        return Math.sqrt(sum / Math.max(1, n));
    }

    @Test
    void whatIsRecordedDecodesBackToTheSameLengthWithSound() throws Exception {
        VoiceNote note = record(new Tone(48_000, 440, 2));
        assertEquals(2000, note.durationMs(), 40);
        assertTrue(OggOpus.isOggOpus(note.file()));
        Path wav = dir.resolve("vuelta.wav");
        OggOpus.toPlayableWav(note.file(), wav);
        double seconds = (Files.size(wav) - 44) / 96_000.0;
        assertTrue(seconds > 1.9 && seconds < 2.1, "seconds " + seconds);
        assertTrue(rms(wav) > 2000, "it isn't silence: " + rms(wav));
    }

    @Test
    void lowerMicrophoneRatesWorkToo() throws Exception {
        VoiceNote note = record(new Tone(16_000, 440, 1));
        assertEquals(1000, note.durationMs(), 40);
        Path wav = dir.resolve("vuelta.wav");
        OggOpus.toPlayableWav(note.file(), wav);
        double seconds = (Files.size(wav) - 44) / 96_000.0;
        assertTrue(seconds > 0.9 && seconds < 1.1, "seconds " + seconds);
    }

    /** Someone else's reader is the real proof: page checksums and headers have to be right. */
    @Test
    void ffmpegAcceptsTheFileWithoutComplaint() throws Exception {
        Path ffmpeg = app.unichat.video.Ffmpeg.find();
        assumeTrue(ffmpeg != null, "no ffmpeg");
        VoiceNote note = record(new Tone(48_000, 440, 1));
        Process p = new ProcessBuilder(ffmpeg.toString(), "-v", "error", "-i", note.file().toString(), "-f", "null", "-").redirectErrorStream(true).start();
        String complaints = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), complaints);
        assertEquals("", complaints.strip());
    }

    @Test
    void theWaveformHasAtMost64BarsOnItsScale() throws Exception {
        VoiceNote note = record(new Tone(48_000, 440, 3));
        assertEquals(64, note.waveform().size());
        assertTrue(note.waveform().stream().allMatch(v -> v >= 0 && v <= 1024));
        assertTrue(note.waveform().stream().anyMatch(v -> v > 200));
        assertEquals(List.of(1024), VoiceEncoder.waveform(List.of(32767)));
    }

    @Test
    void pureSilenceIsNoticed() throws Exception {
        VoiceRecorder silent = new VoiceRecorder(new Tone(48_000, 0, 1), dir.resolve("s.ogg"));
        silent.start();
        for (int i = 0; i < 200 && !silent.ended(); i++) Thread.sleep(25);
        assertFalse(silent.anySound());
        silent.stop();
        VoiceRecorder heard = new VoiceRecorder(new Tone(48_000, 440, 1), dir.resolve("h.ogg"));
        heard.start();
        for (int i = 0; i < 200 && !heard.ended(); i++) Thread.sleep(25);
        assertTrue(heard.anySound());
        heard.stop();
    }

    @Test
    void cancellingLeavesNoFile() throws Exception {
        Path f = dir.resolve("c.ogg");
        VoiceRecorder r = new VoiceRecorder(new Tone(48_000, 440, 60), f);
        r.start();
        Thread.sleep(50);
        r.cancel();
        assertFalse(Files.exists(f));
    }

    @Test
    void itStopsByItselfAtTheLimit() throws Exception {
        VoiceRecorder r = new VoiceRecorder(new Tone(48_000, 440, 60), dir.resolve("l.ogg"), 1000);
        r.start();
        for (int i = 0; i < 400 && !r.ended(); i++) Thread.sleep(25);
        assertTrue(r.ended());
        assertEquals(1000, r.stop().durationMs(), 40);
    }
}
