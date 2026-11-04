package app.unichat.stt;

import app.unichat.support.BundledTools;
import app.unichat.support.Os;
import app.unichat.settings.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Speech to text with what the user already has, no paid service: whisper.cpp shipped with the app (model
 * downloaded on first use), or the user's own command (Ajustes → Voz a texto). Everything runs on this computer.
 */
public final class Transcriber {
    public enum Engine { WHISPER, CUSTOM }

    /** Example for "Comando propio"; {archivo} is a WAV/MP3/FLAC file, {idioma} "es", "en"… or "auto". */
    public static final String EXAMPLE_COMMAND = "whisper-cli -m /ruta/al/modelo.bin -f {archivo} -l {idioma} -nt -np";

    private Transcriber() {}

    /** whisper-cli: -Dunichat.whisper (development), app/server/whisper/ inside the app, or the PATH. */
    public static Path whisperBinary() {
        return BundledTools.find("unichat.whisper", "whisper", "whisper-cli");
    }

    public static Path modelsDir() {
        return Settings.get().dir().resolve("models");
    }

    /** Transcribes {@code audio}. Blocking (seconds to minutes); {@code status} gets progress texts. */
    public static String transcribe(Path audio, Consumer<String> status) throws IOException, InterruptedException {
        Settings s = Settings.get();
        Path work = Files.createTempDirectory(Files.createDirectories(s.cacheDir().resolve("stt")), "t");
        try {
            Path input = prepare(audio, work);
            String lang = s.sttLanguage();
            List<String> cmd;
            if (SttSettings.engine() == Engine.CUSTOM) {
                String template = s.sttCommand();
                if (template.isBlank()) throw new IOException("Escribe tu comando en Ajustes → Voz a texto");
                cmd = CommandLine.expand(template, Map.of("archivo", input.toString(), "original", audio.toString(), "idioma", lang));
            } else {
                Path bin = whisperBinary();
                if (bin == null) throw new IOException("Whisper no viene en esta instalación; usa un comando propio en Ajustes → Voz a texto");
                WhisperModel model = SttSettings.model();
                if (!model.isInstalled(modelsDir())) status.accept("Descargando el modelo de voz (solo la primera vez)…");
                int[] shown = {-1};
                Path m = model.ensure(modelsDir(), p -> {
                    int pct = (int) Math.round(p * 100);
                    if (pct != shown[0]) status.accept("Descargando el modelo de voz… " + (shown[0] = pct) + " %");
                });
                int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
                cmd = List.of(bin.toString(), "-m", m.toString(), "-f", input.toString(), "-l", lang,
                        "-t", Integer.toString(threads), "-nt", "-np");
            }
            status.accept("Transcribiendo…");
            return clean(run(cmd, work));
        } finally {
            deleteTree(work);
        }
    }

    /**
     * A file Whisper reads (WAV, MP3, FLAC, Ogg Vorbis): Ogg/Opus voice notes are decoded here; anything else (AAC
     * from Signal or Instagram…) goes through afconvert (macOS) or an ffmpeg on the PATH.
     */
    static Path prepare(Path audio, Path work) throws IOException, InterruptedException {
        if (OggOpus.isOggOpus(audio)) {
            Path wav = work.resolve("audio.wav");
            OggOpus.toWav(audio, wav);
            return wav;
        }
        if (whisperReads(audio)) return audio;
        Path wav = work.resolve("audio.wav");
        Path ffmpeg = BundledTools.onPath(Os.windows() ? "ffmpeg.exe" : "ffmpeg");
        List<String> cmd;
        if (ffmpeg != null) {
            cmd = List.of(ffmpeg.toString(), "-loglevel", "error", "-y", "-i", audio.toString(), "-ar", "16000", "-ac", "1", wav.toString());
        } else if (Os.mac()) {
            cmd = List.of("/usr/bin/afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", audio.toString(), wav.toString());
        } else {
            throw new IOException("Este formato de audio necesita ffmpeg instalado");
        }
        run(cmd, work);
        return wav;
    }

    /** WAV, MP3, FLAC or Ogg (not Opus, checked first), by their first bytes. */
    static boolean whisperReads(Path f) throws IOException {
        byte[] h = new byte[12];
        int n;
        try (InputStream in = Files.newInputStream(f)) {
            n = in.readNBytes(h, 0, h.length);
        }
        if (n < 4) return false;
        String s = new String(h, 0, n, StandardCharsets.ISO_8859_1);
        return (s.startsWith("RIFF") && n >= 12 && s.startsWith("WAVE", 8)) || s.startsWith("ID3") || s.startsWith("fLaC")
                || s.startsWith("OggS") || ((h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0);
    }

    /** Runs {@code cmd}; stdout on success, the last error line otherwise. */
    private static String run(List<String> cmd, Path work) throws IOException, InterruptedException {
        Path out = work.resolve("stdout.txt"), err = work.resolve("stderr.txt");
        Process p;
        try {
            p = new ProcessBuilder(cmd).directory(work.toFile())
                    .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        } catch (IOException e) {
            throw new IOException("No se pudo ejecutar " + Path.of(cmd.get(0)).getFileName() + ": " + e.getMessage());
        }
        if (!p.waitFor(15, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("La transcripción tardó demasiado");
        }
        if (p.exitValue() != 0) {
            List<String> lines = new ArrayList<>(Files.readAllLines(err, StandardCharsets.UTF_8));
            lines.removeIf(String::isBlank);
            String last = lines.isEmpty() ? "código " + p.exitValue() : lines.get(lines.size() - 1).strip();
            throw new IOException(Path.of(cmd.get(0)).getFileName() + " falló: " + last);
        }
        return Files.readString(out, StandardCharsets.UTF_8);
    }

    /** One paragraph; Whisper's bracketed markers ("[BLANK_AUDIO]", "[Música]") removed. Empty when nothing was said. */
    static String clean(String stdout) {
        String t = stdout.replaceAll("\\[[^\\]]*]", " ").replaceAll("\\s+", " ").strip();
        return t.matches("[\\p{Punct}\\s]*") ? "" : t;
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {
                    // temp folder: cleaned up with the cache
                }
            });
        } catch (IOException ignored) {
            // nothing to clean
        }
    }
}
