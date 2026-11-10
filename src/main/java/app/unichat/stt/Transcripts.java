package app.unichat.stt;

import app.unichat.support.I18n;
import app.unichat.support.Threads;
import app.unichat.settings.Settings;
import app.unichat.support.Errors;
import app.unichat.support.Hashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Transcripts of voice notes, keyed by the audio's media URL and kept on disk ({@code <data>/transcripts/}) so each
 * note is transcribed once. One at a time (Whisper uses several cores). Listeners run on the worker thread.
 */
public final class Transcripts {
    public sealed interface State permits Working, Done, Failed {}
    /** {@code what}: "Transcribiendo…", model download progress… */
    public record Working(String what) implements State {}
    /** {@code text} is empty when nothing was said. */
    public record Done(String text) implements State {}
    public record Failed(String error) implements State {}

    public interface Engine {
        String transcribe(Path audio, java.util.function.Consumer<String> status) throws Exception;
    }

    private static Transcripts instance;

    private final Path dir;
    private final Engine engine;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Threads.daemon("transcriber"));

    Transcripts(Path dir, Engine engine) {
        this.dir = dir;
        this.engine = engine;
    }

    public static synchronized Transcripts get() {
        if (instance == null) instance = new Transcripts(Settings.get().dir().resolve("transcripts"), Transcriber::transcribe);
        return instance;
    }

    public void addListener(Runnable r) { listeners.add(r); }
    public void removeListener(Runnable r) { listeners.remove(r); }

    /** Null when this audio was never transcribed. */
    public State state(String mediaUrl) {
        if (mediaUrl == null) return null;
        State s = states.get(mediaUrl);
        if (s != null) return s;
        Path f = file(mediaUrl);
        if (!Files.isRegularFile(f)) return null;
        try {
            State done = new Done(Files.readString(f, StandardCharsets.UTF_8));
            states.putIfAbsent(mediaUrl, done);
            return done;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * {@link #request}, ahead of being needed (a draft will want it): asked on every update of the open chat, so a note
     * that failed isn't tried again here; the transcribe button and a draft still can.
     */
    public void requestAhead(String mediaUrl, Supplier<CompletableFuture<Path>> audio) {
        if (state(mediaUrl) instanceof Failed) return;
        request(mediaUrl, audio);
    }

    /** Starts transcribing unless it's done or under way; {@code audio} downloads the file when its turn comes. */
    public void request(String mediaUrl, Supplier<CompletableFuture<Path>> audio) {
        state(mediaUrl); // what's on disk counts as done
        State queued = new Working(I18n.t("stt.queued"));
        // Checked and claimed in one step: asked from two threads at once, both saw nothing and both transcribed it.
        if (states.compute(mediaUrl, (k, cur) -> cur instanceof Done || cur instanceof Working ? cur : queued) != queued) return;
        for (Runnable r : listeners) r.run();
        worker.execute(() -> {
            try {
                set(mediaUrl, new Working(I18n.t("stt.downloadingAudio")));
                Path p = audio.get().get();
                String text = engine.transcribe(p, what -> set(mediaUrl, new Working(what)));
                Files.createDirectories(dir);
                Files.writeString(file(mediaUrl), text, StandardCharsets.UTF_8);
                set(mediaUrl, new Done(text));
            } catch (Exception e) {
                set(mediaUrl, new Failed(Errors.message(e)));
            }
        });
    }

    /**
     * What {@code mediaUrl} says, transcribing it first if needed. Blocking; null if it fails or isn't done within
     * {@code timeout} (it carries on, and the next ask finds it).
     */
    public String await(String mediaUrl, Supplier<CompletableFuture<Path>> audio, java.time.Duration timeout) throws InterruptedException {
        CompletableFuture<String> text = new CompletableFuture<>();
        Runnable check = () -> {
            State s = state(mediaUrl);
            if (s instanceof Done d) text.complete(d.text());
            else if (s instanceof Failed) text.complete(null);
        };
        addListener(check);
        try {
            State before = state(mediaUrl);
            if (before instanceof Done d) return d.text();
            request(mediaUrl, audio);
            check.run();
            return text.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return null;
        } finally {
            removeListener(check);
        }
    }

    private void set(String mediaUrl, State s) {
        states.put(mediaUrl, s);
        for (Runnable r : listeners) r.run();
    }

    private Path file(String mediaUrl) {
        return dir.resolve(Hashes.sha256(mediaUrl) + ".txt");
    }
}
