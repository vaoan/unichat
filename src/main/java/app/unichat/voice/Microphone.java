package app.unichat.voice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;

/** The computer's default microphone. */
public final class Microphone implements PcmSource {
    /** The rates Opus takes, best first. */
    private static final float[] RATES = {48_000, 24_000, 16_000, 12_000, 8_000};

    private final TargetDataLine line;
    private final int rate;
    private byte[] bytes = new byte[0];

    private Microphone(TargetDataLine line, int rate) {
        this.line = line;
        this.rate = rate;
    }

    /** Opening may wait for the person to answer the system's permission question: never on the FX thread. */
    public static Microphone open() throws IOException {
        for (float rate : RATES) {
            AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) continue;
            try {
                TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.start();
                return new Microphone(line, (int) rate);
            } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
                // Try the next rate; the error below covers having none.
            }
        }
        throw new IOException("No se encontró un micrófono disponible");
    }

    @Override public int rate() { return rate; }

    @Override
    public int read(short[] into) {
        if (bytes.length != into.length * 2) bytes = new byte[into.length * 2];
        int n = line.read(bytes, 0, bytes.length);
        if (n <= 0) return line.isOpen() ? 0 : -1;
        for (int i = 0; i + 1 < n; i += 2) into[i / 2] = (short) ((bytes[i] & 0xFF) | bytes[i + 1] << 8);
        return n / 2;
    }

    @Override
    public void close() {
        line.stop();
        line.close();
    }
}
