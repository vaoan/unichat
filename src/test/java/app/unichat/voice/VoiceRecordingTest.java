package app.unichat.voice;

import app.unichat.model.VoiceNote;
import app.unichat.stt.OggOpus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

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
        /** Counted down by the first read: the recording is under way. */
        final CountDownLatch reading = new CountDownLatch(1);

        Tone(int rate, double hz, double seconds) {
            this.rate = rate;
            this.hz = hz;
            this.total = (long) (rate * seconds);
        }

        @Override public int rate() { return rate; }

        @Override
        public int read(short[] into) {
            reading.countDown();
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
        awaitEnd(r);
        return r.stop();
    }

    /**
     * Until the recording stops by itself (its source ran out, or the limit): joins its thread, which is what
     * {@link VoiceRecorder#ended} looks at.
     */
    private static void awaitEnd(VoiceRecorder r) throws Exception {
        // VoiceRecorder offers no way to wait for its end; a future it completed on ending would spare this reflection.
        Field thread = VoiceRecorder.class.getDeclaredField("thread");
        thread.setAccessible(true);
        ((Thread) thread.get(r)).join();
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
        assertEquals(0, p.waitFor(), complaints);
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
        awaitEnd(silent);
        assertFalse(silent.anySound());
        silent.stop();
        VoiceRecorder heard = new VoiceRecorder(new Tone(48_000, 440, 1), dir.resolve("h.ogg"));
        heard.start();
        awaitEnd(heard);
        assertTrue(heard.anySound());
        heard.stop();
    }

    @Test
    void cancellingLeavesNoFile() throws Exception {
        Path f = dir.resolve("c.ogg");
        Tone tone = new Tone(48_000, 440, 60);
        VoiceRecorder r = new VoiceRecorder(tone, f);
        r.start();
        tone.reading.await();
        r.cancel();
        assertFalse(Files.exists(f));
    }

    @Test
    void itStopsByItselfAtTheLimit() throws Exception {
        VoiceRecorder r = new VoiceRecorder(new Tone(48_000, 440, 60), dir.resolve("l.ogg"), 1000);
        r.start();
        awaitEnd(r);
        assertTrue(r.ended());
        assertEquals(1000, r.stop().durationMs(), 40);
    }
}
