package app.unichat.voice;

import app.unichat.model.VoiceNote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** One recording: reads and encodes on its own thread until it's stopped, cancelled or reaches its limit. */
public final class VoiceRecorder {
    /** A limit, so one left running by mistake doesn't go on for ever. */
    static final long MAX_MS = 15 * 60_000;
    private static final int READ_SAMPLES_PER_SECOND_DIVISOR = 25;

    private final PcmSource source;
    private final VoiceEncoder encoder;
    private final Path file;
    private final long maxMs;
    private final Thread thread;
    private volatile long durationMs;
    private volatile double level;
    private volatile boolean anySound;
    private volatile boolean stopping;
    private volatile IOException failure;
    private final Path raw;
    private final java.io.OutputStream rawOut;

    public VoiceRecorder(PcmSource source, Path file) throws IOException {
        this(source, file, MAX_MS);
    }

    VoiceRecorder(PcmSource source, Path file, long maxMs) throws IOException {
        this.source = source;
        this.file = file;
        this.maxMs = maxMs;
        this.encoder = new VoiceEncoder(file, source.rate());
        this.raw = file.resolveSibling(file.getFileName() + ".pcm");
        this.rawOut = new java.io.BufferedOutputStream(Files.newOutputStream(raw));
        this.thread = new Thread(this::run, "voice-recorder");
        thread.setDaemon(true);
    }

    public void start() { thread.start(); }

    public long durationMs() { return durationMs; }

    /** The recording before encoding (16-bit mono at {@link #rate}); gone after {@link #deleteRaw}. */
    public Path raw() { return raw; }

    public int rate() { return source.rate(); }

    public void deleteRaw() throws IOException {
        rawOut.close();
        Files.deleteIfExists(raw);
    }

    /** Loudness right now, 0..1. */
    public double level() { return level; }

    public boolean anySound() { return anySound; }

    /** The recording stopped by itself: the limit, or the source ended. */
    public boolean ended() { return !thread.isAlive() && thread.getState() != Thread.State.NEW; }

    /** Ends it and writes the file. Blocking, briefly. */
    public VoiceNote stop() throws IOException, InterruptedException {
        join();
        rawOut.close();
        if (failure != null) throw failure;
        return encoder.finish();
    }

    /** Ends it and leaves nothing behind. */
    public void cancel() throws IOException, InterruptedException {
        join();
        encoder.close();
        deleteRaw();
        Files.deleteIfExists(file);
    }

    private void writeRaw(short[] samples, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            rawOut.write(samples[i]);
            rawOut.write(samples[i] >> 8);
        }
    }

    private void join() throws InterruptedException {
        stopping = true;
        source.close();
        if (thread.getState() != Thread.State.NEW) thread.join();
    }

    private void run() {
        short[] buffer = new short[source.rate() / READ_SAMPLES_PER_SECOND_DIVISOR];
        try {
            while (!stopping && encoder.durationMs() < maxMs) {
                int n = source.read(buffer);
                if (n < 0) return;
                encoder.feed(buffer, n);
                writeRaw(buffer, n);
                durationMs = encoder.durationMs();
                level = encoder.level();
                anySound = encoder.anySound();
            }
        } catch (IOException e) {
            failure = e;
        }
    }
}
