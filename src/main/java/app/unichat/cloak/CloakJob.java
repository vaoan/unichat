package app.unichat.cloak;

import app.unichat.support.I18n;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * The picture being protected, one at a time for the whole app (it takes minutes and a good part of the memory), so the
 * bar on top survives the window being rebuilt. Installs first if needed; {@code onDone} gets the protected PNG.
 */
public final class CloakJob {
    public enum Phase { IDLE, INSTALLING, PROTECTING, DONE, FAILED, CANCELLED }

    /**
     * What the bar shows.
     * @param stepKey while installing, the step's text key
     * @param fraction 0..1, or -1 when it can't tell
     * @param startedMs when the current phase started, for the time left
     */
    public record Status(Phase phase, String file, CloakLevel level, String stepKey, double fraction, long startedMs,
                         String error) {
        static final Status IDLE = new Status(Phase.IDLE, null, null, null, 0, 0, null);

        public boolean busy() { return phase == Phase.INSTALLING || phase == Phase.PROTECTING; }
    }

    /** What actually protects: blocking, reports 0..1, stopped by interrupting its thread. */
    interface Runner {
        void install(CloakTool.Progress progress) throws IOException, InterruptedException;

        void protect(Path in, Path out, CloakLevel level, DoubleConsumer progress) throws IOException, InterruptedException;
    }

    private static final CloakJob INSTANCE = new CloakJob(processRunner(CloakTool.standard()));
    /** A run that stops printing for this long is stuck (a step takes a few seconds). */
    static final long SILENCE_SECONDS = 10 * 60;

    private final Runner runner;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Status status = Status.IDLE;
    private Thread worker;

    CloakJob(Runner runner) {
        this.runner = runner;
    }

    public static CloakJob get() { return INSTANCE; }

    public Status status() { return status; }

    public void addListener(Runnable l) { listeners.add(l); }

    public void removeListener(Runnable l) { listeners.remove(l); }

    /**
     * Starts protecting {@code in} into {@code out}; false, doing nothing, while another is going.
     * @param onDone gets {@code out} once it's written, on the worker thread
     */
    public synchronized boolean start(Path in, Path out, CloakLevel level, Consumer<Path> onDone) {
        if (status.busy()) return false;
        String name = in.getFileName().toString();
        set(new Status(Phase.INSTALLING, name, level, "cloak.install.checking", -1, System.currentTimeMillis(), null));
        worker = Thread.ofPlatform().daemon().name("cloak").start(() -> work(in, out, level, onDone, name));
        return true;
    }

    /** Stops what's going; the bar then goes away. */
    public synchronized void cancel() {
        if (!status.busy()) return;
        set(new Status(Phase.CANCELLED, status.file(), status.level(), null, 0, 0, null));
        if (worker != null) worker.interrupt();
    }

    /** Takes a finished, failed or cancelled job off the bar. */
    public synchronized void dismiss() {
        if (!status.busy()) set(Status.IDLE);
    }

    private void work(Path in, Path out, CloakLevel level, Consumer<Path> onDone, String name) {
        try {
            runner.install((key, f) -> update(new Status(Phase.INSTALLING, name, level, key, f, status.startedMs(), null)));
            long started = System.currentTimeMillis();
            update(new Status(Phase.PROTECTING, name, level, null, 0, started, null));
            runner.protect(in, out, level, f -> update(new Status(Phase.PROTECTING, name, level, null, f, started, null)));
            if (!finish(new Status(Phase.DONE, name, level, null, 1, 0, null))) return;
            onDone.accept(out);
        } catch (InterruptedException e) {
            // Cancelled: the status already says so.
        } catch (IOException | RuntimeException e) {
            System.err.println("Image protection failed: " + e.getMessage());
            finish(new Status(Phase.FAILED, name, level, null, 0, 0,
                    e.getMessage() == null ? I18n.t("cloak.error.run") : e.getMessage()));
        }
    }

    /** Progress, unless it was cancelled meanwhile. */
    private synchronized void update(Status s) {
        if (status.busy()) set(s);
    }

    private synchronized boolean finish(Status s) {
        if (!status.busy()) return false;
        set(s);
        return true;
    }

    private void set(Status s) {
        status = s;
        for (Runnable l : listeners) l.run();
    }

    /** "progress 12 90" → 12/90; -1 for any other line. */
    static double progress(String line) {
        String[] p = line.strip().split(" ");
        if (p.length != 3 || !p[0].equals("progress")) return -1;
        try {
            double done = Double.parseDouble(p[1]), total = Double.parseDouble(p[2]);
            return total > 0 ? Math.min(1, done / total) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static Runner processRunner(CloakTool tool) {
        return new Runner() {
            @Override
            public void install(CloakTool.Progress progress) throws IOException, InterruptedException {
                tool.install(progress);
            }

            @Override
            public void protect(Path in, Path out, CloakLevel level, DoubleConsumer progress)
                    throws IOException, InterruptedException {
                run(tool.protectCommand(in, out, level), out, progress);
            }
        };
    }

    /** Runs the script, reading its progress; killed when interrupted or silent too long. */
    static void run(List<String> command, Path out, DoubleConsumer progress) throws IOException, InterruptedException {
        Files.createDirectories(out.getParent());
        Files.deleteIfExists(out);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String[] last = {""};
        long[] heard = {System.currentTimeMillis()};
        Thread reader = Thread.ofPlatform().daemon().name("cloak-output").start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    heard[0] = System.currentTimeMillis();
                    double f = progress(line);
                    if (f >= 0) progress.accept(f);
                    else if (!line.isBlank()) last[0] = line;
                }
            } catch (IOException ended) {
                // Killed.
            }
        });
        try {
            while (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                if (System.currentTimeMillis() - heard[0] > SILENCE_SECONDS * 1000) throw new IOException(I18n.t("cloak.error.run"));
            }
            reader.join(2_000);
            if (p.exitValue() != 0 || !Files.isRegularFile(out)) {
                System.err.println("Image protection: the script failed (exit " + p.exitValue() + "): " + last[0]);
                throw new IOException(I18n.t("cloak.error.run"));
            }
        } finally {
            if (p.isAlive()) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }
}
