package app.unichat.server;

import app.unichat.Redactor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs the server's processes (tuwunel, one per bridge) and restarts any that exits on its own, waiting a bit
 * longer after each crash (up to {@code maxBackoff}). Output goes to each process's log file, redacted.
 */
public final class Supervisor {
    private final Duration firstBackoff;
    private final Duration maxBackoff;
    static final long MAX_LOG = 5L * 1024 * 1024;
    private final Map<String, Service> services = new ConcurrentHashMap<>();

    public Supervisor() {
        this(Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    Supervisor(Duration firstBackoff, Duration maxBackoff) {
        this.firstBackoff = firstBackoff;
        this.maxBackoff = maxBackoff;
    }

    private final class Service {
        final String name;
        final Path workDir;
        final List<String> command;
        final Path log;
        volatile Process process;
        volatile boolean wanted = true;
        volatile int restarts;

        Service(String name, Path workDir, List<String> command, Path log) {
            this.name = name;
            this.workDir = workDir;
            this.command = command;
            this.log = log;
        }

        void launch() throws IOException {
            Files.createDirectories(log.getParent());
            process = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true).start();
            Process p = process;
            Thread pump = new Thread(() -> pump(p, log), "log-" + name);
            pump.setDaemon(true);
            pump.start();
            p.onExit().thenRun(() -> onExit(this, p));
        }
    }

    /**
     * Copies a process's output to its log with personal data removed ({@link Redactor}); past
     * {@link #MAX_LOG} the log is moved to {@code <log>.1} (one copy kept).
     */
    static void pump(Process p, Path log) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            OutputStream out = Files.newOutputStream(log, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    out.write((Redactor.redact(line) + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    if (Files.size(log) > MAX_LOG) {
                        out.close();
                        Files.move(log, log.resolveSibling(log.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
                        out = Files.newOutputStream(log, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    }
                }
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
            // Process gone or log not writable: nothing else to copy.
        }
    }

    /** Starts {@code name} (replacing a previous one with that name). */
    public synchronized void start(String name, Path workDir, List<String> command, Path log) throws IOException {
        stop(name);
        Service s = new Service(name, workDir, command, log);
        services.put(name, s);
        s.launch();
    }

    private void onExit(Service s, Process p) {
        if (!s.wanted || s.process != p || services.get(s.name) != s) return;
        long delay = Math.min(maxBackoff.toMillis(), firstBackoff.toMillis() << Math.min(s.restarts, 10));
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delay);
                if (!s.wanted || services.get(s.name) != s) return;
                s.restarts++;
                s.launch();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                System.err.println("Could not restart " + s.name + ": " + e);
            }
        }, "restart-" + s.name);
        t.setDaemon(true);
        t.start();
    }

    public boolean isRunning(String name) {
        Service s = services.get(name);
        return s != null && s.process != null && s.process.isAlive();
    }

    public int restarts(String name) {
        Service s = services.get(name);
        return s == null ? 0 : s.restarts;
    }

    /** Asks the process to exit, then kills it after 5 s. */
    public void stop(String name) {
        Service s = services.remove(name);
        if (s == null) return;
        s.wanted = false;
        Process p = s.process;
        if (p == null || !p.isAlive()) return;
        p.destroy();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stopAll() {
        for (String name : List.copyOf(services.keySet())) stop(name);
    }
}
