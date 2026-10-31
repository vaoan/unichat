package app.unichat.server;

import app.unichat.Redactor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
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
    private static final long STABLE_MS = 60_000;
    private final Map<String, Service> services = new ConcurrentHashMap<>();

    /** Where each running process is noted as {@code <name>.pid} ("pid startMillis"), or null. */
    private final Path runDir;

    public Supervisor(Path runDir) {
        this(Duration.ofSeconds(1), Duration.ofSeconds(30), runDir);
    }

    Supervisor(Duration firstBackoff, Duration maxBackoff) {
        this(firstBackoff, maxBackoff, null);
    }

    Supervisor(Duration firstBackoff, Duration maxBackoff, Path runDir) {
        this.firstBackoff = firstBackoff;
        this.maxBackoff = maxBackoff;
        this.runDir = runDir;
    }

    /**
     * Stops processes a previous Unichat left running (it was killed or crashed before stopping them), so they
     * don't hold the port or the database. Only a process whose pid AND start time match its note is touched.
     */
    public void reapOrphans() {
        if (runDir == null || !Files.isDirectory(runDir)) return;
        try (var files = Files.list(runDir)) {
            for (Path f : files.filter(x -> x.getFileName().toString().endsWith(".pid")).toList()) {
                String[] parts = Files.readString(f).strip().split("\\s+");
                Files.deleteIfExists(f);
                if (parts.length != 2) continue;
                long pid = Long.parseLong(parts[0]), started = Long.parseLong(parts[1]);
                ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                        .filter(h -> h.info().startInstant().map(i -> i.toEpochMilli() == started).orElse(false))
                        .ifPresent(h -> {
                            h.destroy();
                            try {
                                h.onExit().get(5, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                h.destroyForcibly();
                            }
                        });
            }
        } catch (IOException | NumberFormatException e) {
            System.err.println("Could not check leftover processes: " + e);
        }
    }

    private void notePid(String name, Process p) {
        if (runDir == null) return;
        try {
            Files.createDirectories(runDir);
            long started = p.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
            Files.writeString(runDir.resolve(name + ".pid"), p.pid() + " " + started);
        } catch (IOException e) {
            System.err.println("Could not note pid of " + name + ": " + e);
        }
    }

    private void forgetPid(String name) {
        if (runDir == null) return;
        try {
            Files.deleteIfExists(runDir.resolve(name + ".pid"));
        } catch (IOException ignored) {
            // Removed on the next start anyway.
        }
    }

    private final class Service {
        final String name;
        final Path workDir;
        final List<String> command;
        final Path log;
        volatile Process process;
        volatile boolean wanted = true;
        volatile int restarts;
        /** Crashes in a row without a stable run in between; sets the wait before the next restart. */
        volatile int streak;
        volatile long launchedAt;

        Service(String name, Path workDir, List<String> command, Path log) {
            this.name = name;
            this.workDir = workDir;
            this.command = command;
            this.log = log;
        }

        void launch() throws IOException {
            Files.createDirectories(log.getParent());
            process = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true).start();
            launchedAt = System.currentTimeMillis();
            Process p = process;
            notePid(name, p);
            Thread pump = new Thread(() -> pump(p, log), "log-" + name);
            pump.setDaemon(true);
            pump.start();
            p.onExit().thenRun(() -> onExit(this, p));
        }
    }

    /**
     * Copies a process's output to its log with personal data removed ({@link Redactor}); past
     * {@link #MAX_LOG} the log is moved to {@code <log>.1} (one copy kept).
     * <p>
     * Whatever fails here (log not writable, disk full, out of memory), the output keeps being read: with nobody
     * reading, the pipe fills and the process blocks on its next log line, alive but deaf (the Discord bridge sat
     * a day in write() after an OutOfMemoryError killed this thread). A line that can't be written is lost and the
     * next one tries again.
     */
    static void pump(Process p, Path log) {
        InputStream raw = p.getInputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8));
        OutputStream out = null;
        boolean reported = false;
        try {
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    if (out == null) out = Files.newOutputStream(log, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    out.write((Redactor.redact(line) + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    if (Files.size(log) > MAX_LOG) {
                        out.close();
                        out = null;
                        Files.move(log, log.resolveSibling(log.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
                    }
                    reported = false;
                } catch (Throwable t) {
                    out = closeQuietly(out);
                    if (!reported) System.err.println("Log copy failing (" + log.getFileName() + "): " + t);
                    reported = true;
                }
            }
        } catch (IOException e) {
            // Output closed: the process is gone.
        } catch (Throwable t) {
            // Reading itself failed (out of memory on a huge line): from here on only drain, into a fixed buffer.
            System.err.println("Log copy stopped (" + log.getFileName() + "): " + t);
            byte[] buf = new byte[8192];
            while (true) {
                try {
                    if (raw.read(buf) < 0) break;
                } catch (IOException e) {
                    break;
                } catch (Throwable again) {
                    // keep draining
                }
            }
        } finally {
            closeQuietly(out);
        }
    }

    private static OutputStream closeQuietly(OutputStream out) {
        if (out != null) {
            try {
                out.close();
            } catch (Throwable ignored) {
                // nothing to save
            }
        }
        return null;
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
        // A process that ran fine for a while starts over with short waits (days-old crashes don't count).
        if (System.currentTimeMillis() - s.launchedAt > STABLE_MS) s.streak = 0;
        scheduleRestart(s);
    }

    private void scheduleRestart(Service s) {
        long delay = Math.min(maxBackoff.toMillis(), firstBackoff.toMillis() << Math.min(s.streak, 10));
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delay);
                if (!s.wanted || services.get(s.name) != s) return;
                s.restarts++;
                s.streak++;
                s.launch();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // Not launched, so no exit will come to retry it (full disk, binary being replaced): try again later.
                System.err.println("Could not restart " + s.name + ": " + e);
                scheduleRestart(s);
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
        forgetPid(name);
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
