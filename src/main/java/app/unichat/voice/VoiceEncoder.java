package app.unichat.voice;

import app.unichat.model.VoiceNote;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Sound in, Ogg/Opus voice note out, with Concentus (pure Java, nothing to install). One thread at a time. */
final class VoiceEncoder implements AutoCloseable {
    private static final int FRAMES_PER_SECOND = 50;
    private static final int BITRATE = 32_000;
    /** How many bars networks draw for a voice note. */
    private static final int WAVEFORM_BARS = 64;
    private static final int MAX_PACKET = 1500;

    private final Path file;
    private final OutputStream out;
    private final OpusEncoder encoder;
    private final OggOpusWriter ogg;
    private final int rate;
    private final int frameSize;
    private final short[] frame;
    private final byte[] packet = new byte[MAX_PACKET];
    private final List<Integer> peaks = new ArrayList<>();
    private int filled;
    private long frames;
    private boolean anySound;

    VoiceEncoder(Path file, int rate) throws IOException {
        this.file = file;
        this.rate = rate;
        this.frameSize = rate / FRAMES_PER_SECOND;
        this.frame = new short[frameSize];
        try {
            encoder = new OpusEncoder(rate, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        } catch (OpusException e) {
            throw new IOException(e);
        }
        encoder.setBitrate(BITRATE);
        encoder.setUseVBR(true);
        encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        Files.createDirectories(file.getParent());
        out = new BufferedOutputStream(Files.newOutputStream(file));
        ogg = new OggOpusWriter(out, rate, encoder.getLookahead() * (48_000 / rate));
    }

    void feed(short[] samples, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            frame[filled++] = samples[i];
            if (filled == frameSize) writeFrame();
        }
    }

    long durationMs() { return frames * 1000 / FRAMES_PER_SECOND; }

    /** False while everything so far is pure digital silence: what a Mac hands over when the microphone isn't allowed. */
    boolean anySound() { return anySound; }

    /** Loudness of the last 20 ms, 0..1. */
    double level() { return peaks.isEmpty() ? 0 : peaks.get(peaks.size() - 1) / 32767.0; }

    VoiceNote finish() throws IOException {
        if (filled > 0) {
            java.util.Arrays.fill(frame, filled, frameSize, (short) 0);
            writeFrame();
        }
        ogg.finish();
        out.close();
        return new VoiceNote(file, durationMs(), waveform(peaks));
    }

    private void writeFrame() throws IOException {
        int peak = 0;
        for (short s : frame) peak = Math.max(peak, Math.abs((int) s));
        if (peak > 0) anySound = true;
        peaks.add(Math.min(peak, 32767));
        int length;
        try {
            length = encoder.encode(frame, 0, frameSize, packet, 0, MAX_PACKET);
        } catch (OpusException e) {
            throw new IOException(e);
        }
        ogg.packet(packet, length, 960);
        frames++;
        filled = 0;
    }

    /** {@link #WAVEFORM_BARS} bars or fewer, each the loudest moment of its stretch, on the 0..1024 scale. */
    static List<Integer> waveform(List<Integer> peaks) {
        int bars = Math.min(WAVEFORM_BARS, peaks.size());
        List<Integer> out = new ArrayList<>(bars);
        for (int b = 0; b < bars; b++) {
            int from = b * peaks.size() / bars, to = (b + 1) * peaks.size() / bars, max = 0;
            for (int i = from; i < to; i++) max = Math.max(max, peaks.get(i));
            out.add(max * 1024 / 32767);
        }
        return out;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
