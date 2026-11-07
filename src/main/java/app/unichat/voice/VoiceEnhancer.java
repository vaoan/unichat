package app.unichat.voice;

import app.unichat.model.VoiceNote;
import app.unichat.settings.Settings;
import app.unichat.support.Downloads;
import app.unichat.support.I18n;
import app.unichat.video.Ffmpeg;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Cleans a voice note before it goes out, on this computer: no rumble, background noise off (RNNoise, a small neural
 * network made for speech), even volume. Works on the raw recording, so nothing is compressed twice. ffmpeg and the
 * model are downloaded the first time they're needed; whatever goes wrong, the note still goes out as it was recorded.
 */
public final class VoiceEnhancer {
    private static final String KEY = "voice.enhance";
    private static final String MODEL = "sh.rnnn";
    /** The author's "speech over recording noise" model. Pinned to one commit. */
    private static final String MODEL_URL = "https://raw.githubusercontent.com/GregorR/rnnoise-models/3eee541a283fd3b8f81b85b1748e3b9ccbefa04d/somnolent-hogwash-2018-09-01/sh.rnnn";
    private static final long MODEL_SIZE = 297_646L;
    private static final String MODEL_SHA256 = "70bb6685eb0c2a1d18e2918dca3fbfbd39317010b1802eb1b6ea73a92f3fdec0";
    private static final int OUTPUT_RATE = 48_000;
    private static final long TIMEOUT_SECONDS = 120;
    private static final String RUMBLE = "highpass=f=80", VOLUME = "speechnorm=e=2:r=0.0001:l=1";

    private VoiceEnhancer() {}

    public static boolean enabled() { return Settings.get().getBool(KEY, true); }

    public static void setEnabled(boolean on) { Settings.get().setBool(KEY, on); }

    /**
     * The note to send: the cleaned one, or {@code note} itself when that's off or can't be done. Blocking.
     *
     * @param raw the recording before encoding: 16-bit mono samples at {@code rate}
     * @param installing called with 0..1 if something has to be downloaded first
     */
    public static VoiceNote improve(VoiceNote note, Path raw, int rate, DoubleConsumer installing) throws InterruptedException {
        if (!enabled()) return note;
        Path cleanPcm = note.file().resolveSibling(note.file().getFileName() + ".clean.pcm");
        Path cleanOgg = note.file().resolveSibling(note.file().getFileName() + ".clean.ogg");
        try {
            Path ffmpeg = Ffmpeg.whenNeeded(installing);
            if (ffmpeg == null) return note;
            Path model = model(installing);
            if (!Ffmpeg.run(command(ffmpeg, raw, rate, cleanPcm, model != null), TIMEOUT_SECONDS, model == null ? null : model.getParent())) {
                System.err.println("Voice note left as recorded: the audio filter failed");
                return note;
            }
            VoiceNote clean = encode(cleanPcm, cleanOgg);
            Files.move(cleanOgg, note.file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new VoiceNote(note.file(), clean.durationMs(), clean.waveform());
        } catch (IOException e) {
            System.err.println("Voice note left as recorded: " + e.getClass().getSimpleName());
            return note;
        } finally {
            deleteQuietly(cleanPcm);
            deleteQuietly(cleanOgg);
        }
    }

    /** Run from the model's folder and named bare: a full path would need escaping for ffmpeg's filter syntax, per system. */
    static List<String> command(Path ffmpeg, Path raw, int rate, Path out, boolean withModel) {
        String noise = withModel ? "arnndn=m=" + MODEL : "afftdn=nr=20:nf=-35";
        return List.of(ffmpeg.toString(), "-nostdin", "-y", "-loglevel", "error", "-protocol_whitelist", "file",
                "-f", "s16le", "-ar", Integer.toString(rate), "-ac", "1", "-i", raw.toString(),
                "-af", RUMBLE + "," + noise + "," + VOLUME,
                "-f", "s16le", "-ar", Integer.toString(OUTPUT_RATE), "-ac", "1", out.toString());
    }

    /** The model, downloaded if allowed; null means ffmpeg's plain noise filter, which needs none. */
    private static Path model(DoubleConsumer installing) throws InterruptedException {
        Path f = Settings.get().dir().resolve("tools").resolve("rnnoise").resolve(MODEL);
        if (Files.isRegularFile(f)) return f;
        if (!Ffmpeg.autoInstall()) return null;
        try {
            Downloads.fetch(MODEL_URL, MODEL_SIZE, MODEL_SHA256, f, I18n.t("download.what.noiseModel"), installing);
            return f;
        } catch (IOException e) {
            System.err.println("No noise model: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static VoiceNote encode(Path pcm, Path ogg) throws IOException {
        try (VoiceEncoder encoder = new VoiceEncoder(ogg, OUTPUT_RATE);
             InputStream in = new BufferedInputStream(Files.newInputStream(pcm))) {
            byte[] bytes = new byte[8192];
            short[] samples = new short[bytes.length / 2];
            for (int n; (n = in.readNBytes(bytes, 0, bytes.length)) > 1; ) {
                ByteBuffer.wrap(bytes, 0, n & ~1).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples, 0, n / 2);
                encoder.feed(samples, n / 2);
            }
            return encoder.finish();
        }
    }

    private static void deleteQuietly(Path f) {
        try {
            Files.deleteIfExists(f);
        } catch (IOException e) {
            System.err.println("Could not delete a temporary voice file: " + e.getClass().getSimpleName());
        }
    }
}
