package app.unichat.assist;

import app.unichat.support.Folders;
import app.unichat.support.I18n;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Runs one AI program over one prompt. The prompt and the chat never reach a log. */
public final class ReplyWriter {
    private static final long TIMEOUT_MINUTES = 3;

    /** Said to the person as it is. */
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        public Failure(String message) {
            super(message);
        }
    }

    private volatile Process running;
    private volatile boolean cancelled;

    /** Blocks until it answers. Null if {@link #cancel} stopped it. */
    public String write(AiEngine engine, Path folder, String prompt) throws Failure {
        Path bin = engine.find();
        if (bin == null) throw new Failure(I18n.t("ai.failure.notInstalled", engine.label()));
        if (folder != null && !Files.isDirectory(folder)) throw new Failure(I18n.t("ai.failure.noFolder", folder));
        return run(engine, bin, folder, prompt);
    }

    String run(AiEngine engine, Path bin, Path folder, String prompt) throws Failure {
        Path temp = null;
        try {
            temp = Files.createTempDirectory("unichat-ai");
            Path workDir = folder == null ? temp : folder;
            Path reply = temp.resolve("reply.txt");
            Path out = temp.resolve("out.txt");
            Path err = temp.resolve("err.txt");
            ProcessBuilder b = new ProcessBuilder(engine.command(bin, folder, workDir, reply))
                    .directory(workDir.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
            b.environment().put("PATH", searchPath(bin));
            b.environment().putIfAbsent("LC_CTYPE", "en_US.UTF-8"); // a Mac app opened from Finder has no locale: accents would break
            Process p = b.start();
            running = p;
            if (cancelled) stop(p);
            Thread.ofPlatform().daemon().name("ai-prompt").start(() -> feed(p, prompt));
            if (!p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                stop(p);
                throw new Failure(I18n.t("ai.failure.timeout", engine.label()));
            }
            if (cancelled) return null;
            if (p.exitValue() != 0) throw new Failure(I18n.t("ai.failure.failed", engine.label(), lastLine(err, out)));
            String text = ReplyPrompt.clean(Files.readString(Files.exists(reply) ? reply : out, StandardCharsets.UTF_8));
            if (text.isEmpty()) throw new Failure(I18n.t("ai.failure.empty", engine.label()));
            return text;
        } catch (IOException e) {
            throw new Failure(I18n.t("ai.failure.open", engine.label()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            running = null;
            Folders.deleteQuietly(temp);
        }
    }

    public void cancel() {
        cancelled = true;
        Process p = running;
        if (p != null) stop(p);
    }

    private static void stop(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static void feed(Process p, String prompt) {
        try (OutputStream in = p.getOutputStream()) {
            in.write(prompt.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // It exited without reading it: its exit code says why.
        }
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

    /** The most useful line for a failure: programs end with the reason. */
    private static String lastLine(Path... files) throws IOException {
        for (Path f : files) {
            List<String> lines = new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8));
            lines.removeIf(String::isBlank);
            if (!lines.isEmpty()) {
                String l = lines.get(lines.size() - 1).strip();
                return l.length() > 200 ? l.substring(0, 200) + "…" : l;
            }
        }
        return I18n.t("ai.failure.noReason");
    }
}
