package app.unichat.assist;

import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * The short sound that says a draft or a fix is ready (Ajustes → Escribir con IA). Made here, in pure Java, so it
 * sounds the same on every system and there's no file to ship.
 */
public final class ReadySound {
    public enum Tone { CHIME, SOFT, BELL, OFF }

    static final int RATE = 44_100;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);
    /** Where the samples go; tests swap it. Off the UI thread. */
    public static volatile Consumer<short[]> player = ReadySound::playNow;

    private ReadySound() {}

    /** The chosen tone at the chosen volume; nothing when it's off. Returns at once. */
    public static void play() {
        play(AiSettings.readyTone(), AiSettings.readyVolume());
    }

    public static void play(Tone tone, int volume) {
        short[] s = samples(tone, volume);
        if (s.length == 0) return;
        Consumer<short[]> p = player;
        Thread.ofVirtual().name("ready-sound").start(() -> p.accept(s));
    }

    /** 16-bit mono at {@link #RATE}; empty for {@link Tone#OFF} or volume 0. */
    static short[] samples(Tone tone, int volume) {
        if (tone == Tone.OFF || volume <= 0) return new short[0];
        double v = Math.min(volume, 100) / 100.0;
        double amp = v * v * 0.45 * Short.MAX_VALUE; // squared: the slider then feels even to the ear
        return switch (tone) {
            case CHIME -> join(note(880, 0.12, 0.04, amp, false), note(1318.5, 0.26, 0.08, amp, false));
            case SOFT -> note(659.3, 0.32, 0.12, amp * 0.9, false);
            case BELL -> note(1046.5, 0.6, 0.18, amp * 0.8, true);
            case OFF -> new short[0];
        };
    }

    /** A sine with a 5 ms rise and an exponential fade ({@code decay} seconds), ending at silence. */
    private static short[] note(double hz, double seconds, double decay, double amp, boolean bell) {
        int n = (int) (seconds * RATE);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double env = Math.min(1, t / 0.005) * Math.exp(-t / decay) * (1 - (double) i / n);
            double wave = Math.sin(2 * Math.PI * hz * t);
            if (bell) wave = (wave + 0.4 * Math.sin(2 * Math.PI * hz * 2.76 * t) + 0.2 * Math.sin(2 * Math.PI * hz * 5.4 * t)) / 1.6;
            out[i] = (short) Math.round(amp * env * wave);
        }
        return out;
    }

    private static short[] join(short[] a, short[] b) {
        short[] out = new short[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static void playNow(short[] s) {
        if (Boolean.getBoolean("unichat.silent")) return; // tests
        byte[] bytes = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            bytes[2 * i] = (byte) s[i];
            bytes[2 * i + 1] = (byte) (s[i] >> 8);
        }
        try (SourceDataLine line = AudioSystem.getSourceDataLine(FORMAT)) {
            line.open(FORMAT);
            line.start();
            line.write(bytes, 0, bytes.length);
            line.drain();
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            System.err.println("Could not play the ready sound: " + e.getClass().getSimpleName());
        }
    }
}
