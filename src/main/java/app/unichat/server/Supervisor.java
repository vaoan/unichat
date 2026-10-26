package app.unichat.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs the server's processes (tuwunel, one per bridge) and restarts any that exits on its own, waiting a bit
 * longer after each crash (up to {@code maxBackoff}). Output is appended to each process's log file.
 */
public final class Supervisor {
    private final Duration firstBackoff;
    private final Duration maxBackoff;
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
            process = new ProcessBuilder(command).directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .start();
            Process p = process;
            p.onExit().thenRun(() -> onExit(this, p));
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
