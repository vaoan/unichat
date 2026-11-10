package app.unichat.assist;

import app.unichat.support.Folders;
import app.unichat.support.I18n;
import app.unichat.support.Processes;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** Runs one AI program over one prompt. The prompt and the chat never reach a log. */
public final class ReplyWriter {
    private final java.time.Duration timeout;

    public ReplyWriter() {
        this(java.time.Duration.ofMinutes(3));
    }

    /** {@code timeout}: how long the program may take before it's stopped. */
    public ReplyWriter(java.time.Duration timeout) {
        this.timeout = timeout;
    }

    /** Said to the person as it is. */
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        public Failure(String message) {
            super(message);
        }
    }

    /** The thread waiting for the program, which {@link #cancel} interrupts; both guarded by this. */
    private Thread waiting;
    private boolean cancelled;

    /** Blocks until it answers. Null if {@link #cancel} stopped it. */
    public String write(AiEngine engine, Path folder, String prompt) throws Failure {
        return write(engine, folder, prompt, false);
    }

    /** {@code web}: it may search the web ({@link AiEngine#command(Path, Path, Path, Path, boolean)}). */
    public String write(AiEngine engine, Path folder, String prompt, boolean web) throws Failure {
        Path bin = engine.find();
        if (bin == null) throw new Failure(I18n.t("ai.failure.notInstalled", engine.label()));
        if (folder != null && !Files.isDirectory(folder)) throw new Failure(I18n.t("ai.failure.noFolder", folder));
        return run(engine, bin, folder, prompt, web);
    }

    String run(AiEngine engine, Path bin, Path folder, String prompt) throws Failure {
        return run(engine, bin, folder, prompt, false);
    }

    /** Stopped (cancelled, or its thread interrupted), the program goes with its children ({@link Processes}). */
    String run(AiEngine engine, Path bin, Path folder, String prompt, boolean web) throws Failure {
        synchronized (this) {
            if (cancelled) return null;
            waiting = Thread.currentThread();
        }
        Path temp = null;
        try {
            temp = Files.createTempDirectory("unichat-ai");
            Path workDir = folder == null ? temp : folder;
            Path reply = temp.resolve("reply.txt");
            Path err = temp.resolve("err.txt");
            ProcessBuilder b = new ProcessBuilder(engine.command(bin, folder, workDir, reply, web))
                    .directory(workDir.toFile()).redirectError(err.toFile());
            b.environment().put("PATH", searchPath(bin));
            b.environment().putIfAbsent("LC_CTYPE", "en_US.UTF-8"); // a Mac app opened from Finder has no locale: accents would break
            Processes.Result r = Processes.capture(b, Processes.Limit.of(timeout.toSeconds()), prompt, null);
            if (isCancelled()) return null;
            if (!r.finished()) throw new Failure(I18n.t("ai.failure.timeout", engine.label()));
            if (r.exit() != 0) throw new Failure(I18n.t("ai.failure.failed", engine.label(), reason(err, r.out())));
            String text = ReplyPrompt.clean(Files.exists(reply) ? Files.readString(reply, StandardCharsets.UTF_8) : r.out());
            if (text.isEmpty()) throw new Failure(I18n.t("ai.failure.empty", engine.label()));
            return text;
        } catch (IOException e) {
            throw new Failure(I18n.t("ai.failure.open", engine.label()));
        } catch (InterruptedException e) {
            if (!isCancelled()) Thread.currentThread().interrupt(); // someone else's interrupt stays theirs
            return null;
        } finally {
            synchronized (this) {
                waiting = null;
                if (cancelled) Thread.interrupted(); // a cancel that came as it ended must not reach the caller's next work
            }
            Folders.deleteQuietly(temp);
        }
    }

    public synchronized void cancel() {
        cancelled = true;
        if (waiting != null) waiting.interrupt();
    }

    private synchronized boolean isCancelled() {
        return cancelled;
    }

    /** Codex is a node script: the programs next to it, and the usual places, have to be found too. */
    private static String searchPath(Path bin) {
        Set<String> folders = new LinkedHashSet<>();
        if (bin.getParent() != null) folders.add(bin.getParent().toString());
        for (String d : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!d.isBlank()) folders.add(d);
        }
        for (Path d : AiEngine.usualFolders()) folders.add(d.toString());
        return String.join(File.pathSeparator, folders);
    }

    /** The most useful line for a failure: programs end with the reason, in their errors or else their output. */
    private static String reason(Path err, String out) throws IOException {
        String why = Processes.lastLines(Files.readString(err, StandardCharsets.UTF_8), 1);
        if (why.isEmpty()) why = Processes.lastLines(out, 1);
        if (why.isEmpty()) return I18n.t("ai.failure.noReason");
        return why.length() > 200 ? why.substring(0, 200) + "…" : why;
    }
}
