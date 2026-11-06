package app.unichat.voice;

import app.unichat.model.VoiceNote;
import app.unichat.stt.OggOpus;
import app.unichat.video.Ffmpeg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VoiceEnhancerTest {
    private static final int RATE = 48_000;
    @TempDir Path dir;

    @BeforeEach
    void defaults() {
        VoiceEnhancer.setEnabled(true);
        Ffmpeg.setAutoInstall(false); // nothing here reaches the internet unless a test says so
    }

    @AfterEach
    void restore() {
        System.clearProperty("unichat.ffmpeg");
        Ffmpeg.setAutoInstall(true);
    }

    /** One second of tone and one of "silence", all over a noise floor; returned as raw samples. */
    private Path noisyRecording() throws Exception {
        Random random = new Random(7);
        ByteBuffer b = ByteBuffer.allocate(RATE * 2 * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < RATE * 2; i++) {
            double voice = i < RATE ? 9_000 * Math.sin(2 * Math.PI * 300 * i / RATE) : 0;
            b.putShort((short) (voice + 900 * (random.nextDouble() - 0.5)));
        }
        return Files.write(dir.resolve("nota.ogg.pcm"), b.array());
    }

    private VoiceNote recorded() throws Exception {
        Path ogg = dir.resolve("nota.ogg");
        Files.write(ogg, new byte[]{1, 2, 3});
        return new VoiceNote(ogg, 2000, List.of(1, 2));
    }

    @Test
    void whenItsOffTheNoteGoesAsRecorded() throws Exception {
        VoiceEnhancer.setEnabled(false);
        VoiceNote note = recorded();
        assertSame(note, VoiceEnhancer.improve(note, noisyRecording(), RATE, p -> { }));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(note.file()));
    }

    @Test
    void withoutFfmpegTheNoteGoesAsRecorded() throws Exception {
        System.setProperty("unichat.ffmpeg", dir.resolve("no-existe").toString());
        VoiceNote note = recorded();
        assertSame(note, VoiceEnhancer.improve(note, noisyRecording(), RATE, p -> { }));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFilterThatFailsLeavesTheNoteAsRecorded() throws Exception {
        Path bad = Files.writeString(dir.resolve("ffmpeg-malo"), "#!/bin/sh\nexit 1\n");
        bad.toFile().setExecutable(true);
        System.setProperty("unichat.ffmpeg", bad.toString());
        VoiceNote note = recorded();
        assertSame(note, VoiceEnhancer.improve(note, noisyRecording(), RATE, p -> { }));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(note.file()));
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().contains(".clean")), "no leftovers");
        }
    }

    @Test
    void theCommandReadsOnlyTheRawFileAndNamesTheModelBare() {
        List<String> c = VoiceEnhancer.command(Path.of("ffmpeg"), Path.of("a.pcm"), 16_000, Path.of("b.pcm"), true);
        assertEquals("file", c.get(c.indexOf("-protocol_whitelist") + 1));
        assertEquals("16000", c.get(c.indexOf("-ar") + 1), "the input's rate comes first");
        assertTrue(c.get(c.indexOf("-af") + 1).contains("arnndn=m=sh.rnnn"));
        assertFalse(VoiceEnhancer.command(Path.of("ffmpeg"), Path.of("a.pcm"), 48_000, Path.of("b.pcm"), false).get(13).contains("arnndn"));
    }

    /** ffmpeg's own filter, needing no model: the result is a valid note of the same length. */
    @Test
    void itLeavesAValidNoteOfTheSameLength() throws Exception {
        assumeTrue(Ffmpeg.find() != null, "no ffmpeg");
        VoiceNote note = recorded();
        VoiceNote out = VoiceEnhancer.improve(note, noisyRecording(), RATE, p -> { });
        assertEquals(note.file(), out.file());
        assertTrue(OggOpus.isOggOpus(out.file()), "encoded again");
        assertEquals(2000, out.durationMs(), 100);
        assertFalse(out.waveform().isEmpty());
    }

    /** Speech from the Mac's own voice, with a long pause, over pink noise (a tone isn't speech: RNNoise leaves it alone). */
    private void speechAndNoise(Path clean, Path noisy) throws Exception {
        Path aiff = dir.resolve("voz.aiff");
        run("say", "-o", aiff.toString(), "Oye, te cuento que perdí el vuelo. [[slnc 4000]] Te llamo cuando llegue.");
        String ffmpeg = Ffmpeg.find().toString();
        run(ffmpeg, "-y", "-loglevel", "error", "-i", aiff.toString(), "-f", "s16le", "-ar", "48000", "-ac", "1", clean.toString());
        run(ffmpeg, "-y", "-loglevel", "error", "-f", "s16le", "-ar", "48000", "-ac", "1", "-i", clean.toString(),
                "-f", "lavfi", "-i", "anoisesrc=c=pink:a=0.05:r=48000",
                "-filter_complex", "[0][1]amix=inputs=2:duration=first:normalize=0", "-f", "s16le", "-ar", "48000", "-ac", "1", noisy.toString());
    }

    private static void run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0, command[0] + " can't make the sample");
    }

    /** Average power in dB of the 100 ms windows where the clean speech is ({@code speech}) or isn't talking. */
    private static double power(short[] audio, short[] clean, boolean speech) {
        int window = RATE / 10;
        double sum = 0;
        int n = 0;
        for (int w = 0; (w + 1) * window <= Math.min(audio.length, clean.length); w++) {
            double c = 0, a = 0;
            for (int i = w * window; i < (w + 1) * window; i++) {
                c += clean[i] * (double) clean[i];
                a += audio[i] * (double) audio[i];
            }
            if ((Math.sqrt(c / window) >= 40) == speech) {
                sum += a / window;
                n++;
            }
        }
        return 10 * Math.log10(Math.max(1e-3, sum / Math.max(1, n)) / (32768.0 * 32768.0));
    }

    private static short[] samples(byte[] bytes, int from) {
        short[] out = new short[(bytes.length - from) / 2];
        ByteBuffer.wrap(bytes, from, out.length * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
        return out;
    }

    /** The real download, the real model, real speech. {@code -Dunichat.networkTests=true}, on a Mac. */
    @Test
    @EnabledOnOs(OS.MAC)
    @EnabledIfSystemProperty(named = "unichat.networkTests", matches = "true")
    void theRealModelQuietsTheNoiseAndKeepsTheVoice() throws Exception {
        assumeTrue(Ffmpeg.find() != null, "no ffmpeg");
        Ffmpeg.setAutoInstall(true);
        Path clean = dir.resolve("limpia.pcm"), noisy = dir.resolve("nota.ogg.pcm");
        speechAndNoise(clean, noisy);
        VoiceNote out = VoiceEnhancer.improve(recorded(), noisy, RATE, p -> { });
        assertTrue(Files.isRegularFile(app.unichat.settings.Settings.get().dir().resolve("tools/rnnoise/sh.rnnn")));
        Path wav = dir.resolve("vuelta.wav");
        OggOpus.toPlayableWav(out.file(), wav);

        short[] reference = samples(Files.readAllBytes(clean), 0);
        short[] before = samples(Files.readAllBytes(noisy), 0);
        short[] after = samples(Files.readAllBytes(wav), 44);
        double noiseBefore = power(before, reference, false), noiseAfter = power(after, reference, false);
        assertTrue(noiseAfter < noiseBefore - 8, "noise " + noiseBefore + " -> " + noiseAfter + " dB");
        assertTrue(power(after, reference, true) >= power(before, reference, true) - 1, "the voice stays as loud");
    }
}
