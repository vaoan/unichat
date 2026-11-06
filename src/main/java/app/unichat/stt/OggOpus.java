package app.unichat.stt;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Voice notes (WhatsApp, Telegram, Signal, Discord: Ogg/Opus) to the 16 kHz mono WAV Whisper reads. whisper-cli
 * can't decode Opus and Unichat ships no ffmpeg, so the Ogg pages are read here, Concentus (pure Java libopus)
 * decodes at 48 kHz and a low-pass filter brings it to 16 kHz. Not decoded at 16 kHz directly: Concentus returns
 * silence for the first CELT frames at that rate (the start of what was said was lost).
 */
public final class OggOpus {
    public static final int RATE = 16_000;
    private static final int DECODE_RATE = 48_000, FACTOR = DECODE_RATE / RATE;
    /** Longest Opus frame: 120 ms. */
    private static final int MAX_FRAME = DECODE_RATE * 120 / 1000;
    /** Windowed-sinc low-pass at 7.2 kHz (below 16 kHz's Nyquist) for the 48 → 16 kHz decimation. */
    private static final double[] LOW_PASS = lowPass(63, 7_200.0 / DECODE_RATE);

    private OggOpus() {}

    /** Whether {@code file} starts like an Ogg stream carrying Opus. */
    public static boolean isOggOpus(Path file) throws IOException {
        byte[] head = new byte[64];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(head, 0, head.length);
        }
        if (n < 36 || head[0] != 'O' || head[1] != 'g' || head[2] != 'g' || head[3] != 'S') return false;
        int body = 27 + (head[26] & 0xFF);
        return body + 8 <= n && new String(head, body, 8, java.nio.charset.StandardCharsets.US_ASCII).equals("OpusHead");
    }

    /** The decoded samples at 48 kHz: {@code length} of {@code samples} are in use. */
    private record Decoded(short[] samples, int length) {}

    /** Decodes {@code in} to a 16-bit 16 kHz mono WAV at {@code out}; returns the duration in seconds. */
    public static double toWav(Path in, Path out) throws IOException {
        Decoded d = decode(in);
        byte[] data = decimate(d.samples(), d.length());
        write(out, data, RATE);
        return data.length / 2.0 / RATE;
    }

    /** For listening: the 48 kHz as decoded (16 kHz is what Whisper reads, and it sounds like a telephone). */
    public static void toPlayableWav(Path in, Path out) throws IOException {
        Decoded d = decode(in);
        ByteBuffer data = ByteBuffer.allocate(d.length() * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < d.length(); i++) data.putShort(d.samples()[i]);
        write(out, data.array(), DECODE_RATE);
    }

    private static void write(Path out, byte[] data, int rate) throws IOException {
        try (OutputStream o = Files.newOutputStream(out)) {
            o.write(wavHeader(data.length, rate));
            o.write(data);
        }
    }

    private static Decoded decode(Path in) throws IOException {
        List<byte[]> packets = packets(Files.readAllBytes(in));
        if (packets.size() < 2 || !startsWith(packets.get(0), "OpusHead")) throw new IOException("No es un audio Opus");
        byte[] head = packets.get(0);
        // Samples to drop at the start (encoder delay), always in 48 kHz units.
        int preSkip = (head[10] & 0xFF) | (head[11] & 0xFF) << 8;
        OpusDecoder decoder;
        try {
            decoder = new OpusDecoder(DECODE_RATE, 1);
        } catch (OpusException e) {
            throw new IOException(e);
        }
        short[] all = new short[MAX_FRAME * 8];
        int total = 0;
        short[] frame = new short[MAX_FRAME];
        long samples = 0;
        // packets[1] is OpusTags.
        for (int i = 2; i < packets.size(); i++) {
            byte[] p = packets.get(i);
            int n;
            try {
                n = decoder.decode(p, 0, p.length, frame, 0, MAX_FRAME, false);
            } catch (OpusException e) {
                continue; // a damaged packet: skip it, keep the rest
            }
            int from = (int) Math.max(0, Math.min(n, preSkip - samples));
            samples += n;
            if (total + n > all.length) all = java.util.Arrays.copyOf(all, Math.max(all.length * 2, total + n));
            System.arraycopy(frame, from, all, total, n - from);
            total += n - from;
        }
        return new Decoded(all, total);
    }

    /** 48 kHz → 16 kHz: low-pass, then every third sample. 16-bit little-endian bytes. */
    static byte[] decimate(short[] in, int length) {
        int half = LOW_PASS.length / 2, outLen = length / FACTOR;
        byte[] out = new byte[outLen * 2];
        for (int o = 0; o < outLen; o++) {
            int c = o * FACTOR;
            double acc = 0;
            for (int k = 0; k < LOW_PASS.length; k++) {
                int idx = c + k - half;
                if (idx >= 0 && idx < length) acc += in[idx] * LOW_PASS[k];
            }
            int v = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(acc)));
            out[o * 2] = (byte) v;
            out[o * 2 + 1] = (byte) (v >> 8);
        }
        return out;
    }

    /** Blackman-windowed sinc, {@code taps} long, cutoff as a fraction of the sample rate; unity gain. */
    private static double[] lowPass(int taps, double cutoff) {
        double[] h = new double[taps];
        double sum = 0;
        int m = taps - 1;
        for (int i = 0; i < taps; i++) {
            double x = i - m / 2.0;
            double sinc = x == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x);
            double w = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / m) + 0.08 * Math.cos(4 * Math.PI * i / m);
            h[i] = sinc * w;
            sum += h[i];
        }
        for (int i = 0; i < taps; i++) h[i] /= sum;
        return h;
    }

    /** Packets of the first logical stream, reassembled across pages (lacing values of 255 continue a packet). */
    static List<byte[]> packets(byte[] ogg) throws IOException {
        List<byte[]> out = new ArrayList<>();
        ByteArrayOutputStream cur = new ByteArrayOutputStream();
        int pos = 0;
        Integer serial = null;
        while (pos + 27 <= ogg.length) {
            if (ogg[pos] != 'O' || ogg[pos + 1] != 'g' || ogg[pos + 2] != 'g' || ogg[pos + 3] != 'S') {
                throw new IOException("Página Ogg dañada");
            }
            int pageSerial = ByteBuffer.wrap(ogg, pos + 14, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int segments = ogg[pos + 26] & 0xFF;
            int dataPos = pos + 27 + segments;
            if (dataPos > ogg.length) break;
            boolean ours = serial == null || serial == pageSerial;
            if (serial == null) serial = pageSerial;
            for (int s = 0; s < segments; s++) {
                int len = ogg[pos + 27 + s] & 0xFF;
                if (dataPos + len > ogg.length) return out; // truncated file: keep what's complete
                if (ours) {
                    cur.write(ogg, dataPos, len);
                    if (len < 255) {
                        out.add(cur.toByteArray());
                        cur.reset();
                    }
                }
                dataPos += len;
            }
            pos = dataPos;
        }
        return out;
    }

    static byte[] wavHeader(int dataBytes) {
        return wavHeader(dataBytes, RATE);
    }

    private static byte[] wavHeader(int dataBytes, int rate) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + dataBytes)
                .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(dataBytes);
        return b.array();
    }

    private static boolean startsWith(byte[] b, String prefix) {
        if (b.length < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) if (b[i] != prefix.charAt(i)) return false;
        return true;
    }
}
