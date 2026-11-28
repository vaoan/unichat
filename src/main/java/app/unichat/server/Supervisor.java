package app.unichat.server;

import app.unichat.support.PathParts;
import app.unichat.support.Storage;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the server's processes (tuwunel, one per bridge) and restarts any that exits on its own, waiting a bit
 * longer after each crash (up to {@code maxBackoff}). Output goes to each process's log file, redacted.
 */
public final class Supervisor {
    private final Duration firstBackoff;
    private final Duration maxBackoff;
    private static final long STABLE_MS = 60_000;
    private final Map<String, Service> services = new ConcurrentHashMap<>();

    /** Where each running process is noted as {@code <name>.pid} ("pid startMillis"), or null. */
    private final @Nullable Path runDir;
    /** Told of a process whose output says a write didn't fit ({@link Storage#writeFailed}). */
    private final Storage storage;

    public Supervisor(Path runDir) {
        this(Duration.ofSeconds(1), Duration.ofSeconds(30), runDir, Storage.SHARED);
    }

    Supervisor(Duration firstBackoff, Duration maxBackoff) {
        this(firstBackoff, maxBackoff, null, Storage.SHARED);
    }

    Supervisor(Duration firstBackoff, Duration maxBackoff, @Nullable Path runDir, Storage storage) {
        this.firstBackoff = firstBackoff;
        this.maxBackoff = maxBackoff;
        this.runDir = runDir;
        this.storage = storage;
    }

    /**
     * Stops processes a previous Unichat left running (it was killed or crashed before stopping them), so they
     * don't hold the port or the database. Only a process whose pid AND start time match its note is touched.
     */
    public void reapOrphans() {
        if (runDir == null || !Files.isDirectory(runDir)) return;
        try (var files = Files.list(runDir)) {
            for (Path f : files.filter(x -> PathParts.name(x).endsWith(".pid")).toList()) {
                String[] parts = Files.readString(f).strip().split("\\s+");
                Files.deleteIfExists(f);
                if (parts.length != 2) continue;
                long pid = Long.parseLong(parts[0]);
                long started = Long.parseLong(parts[1]);
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

    /** What happens to the processes, as it happens: lets tests wait for an event instead of polling. */
    interface Listener {
        /**
         * A line of {@code name}'s output was handed to its log. Only an urgent one (warn, error, fatal, or not JSON) is
         * on disk by now; the others wait in the buffer for the next flush.
         */
        default void logged(String name) { /* nothing by default */ }
        /** {@code name}'s process ended; {@code restarting} says whether a restart was scheduled for it. */
        default void exited(String name, boolean restarting) { /* nothing by default */ }
        /** {@code name} was started again after an exit. */
        default void restarted(String name) { /* nothing by default */ }
    }

    private volatile Listener listener = new Listener() { };

    void listen(Listener l) { listener = l; }

    private final class Service {
        final String name;
        final Path workDir;
        final List<String> command;
        final Path log;
        final Map<String, String> env;
        volatile @Nullable Process process;
        volatile boolean wanted = true;
        final AtomicInteger restarts = new AtomicInteger();
        /** Crashes in a row without a stable run in between; sets the wait before the next restart. */
        final AtomicInteger streak = new AtomicInteger();
        volatile long launchedAt;

        Service(String name, Path workDir, List<String> command, Path log, Map<String, String> env) {
            this.name = name;
            this.workDir = workDir;
            this.command = command;
            this.log = log;
            this.env = env;
        }

        @SuppressFBWarnings(value = "COMMAND_INJECTION", justification = "the server or a bridge from our install folder with fixed arguments, as a list, no shell")
        void launch() throws IOException {
            Files.createDirectories(PathParts.parent(log));
            ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true);
            builder.environment().putAll(env);
            process = builder.start();
            launchedAt = System.currentTimeMillis();
            Process p = process;
            notePid(name, p);
            // Seen before it's written: with the disk full, writing it fails too.
            Thread pump = new Thread(() -> pump(p, log, line -> {
                if (Storage.writeFailed(line)) storage.failed(name);
            }, () -> listener.logged(name)), "log-" + name);
            pump.setDaemon(true);
            pump.start();
            var _ = p.onExit().thenRun(() -> onExit(this, p));
        }
    }

    /** Copies a process's output to its log, see {@link LogPump}. */
    static void pump(Process p, Path log) {
        LogPump.run(p, log, line -> { }, () -> { });
    }

    /** {@link LogPump#run}: {@code read} gets each line as it came, {@code afterLine} runs once it is written. */
    static void pump(Process p, Path log, java.util.function.Consumer<String> read, Runnable afterLine) {
        LogPump.run(p, log, read, afterLine);
    }

    /** Starts {@code name} (replacing a previous one with that name). */
    public void start(String name, Path workDir, List<String> command, Path log) throws IOException {
        start(name, workDir, command, log, Map.of());
    }

    /** As {@link #start(String, Path, List, Path)}, with {@code env} added to the process's environment (kept over restarts). */
    public synchronized void start(String name, Path workDir, List<String> command, Path log, Map<String, String> env) throws IOException {
        stop(name);
        Service s = new Service(name, workDir, command, log, Map.copyOf(env));
        services.put(name, s);
        s.launch();
    }

    private void onExit(Service s, Process p) {
        if (!s.wanted || s.process != p || services.get(s.name) != s) {
            listener.exited(s.name, false);
            return;
        }
        // A process that ran fine for a while starts over with short waits (days-old crashes don't count).
        if (System.currentTimeMillis() - s.launchedAt > STABLE_MS) s.streak.set(0);
        scheduleRestart(s);
        listener.exited(s.name, true);
    }

    private void scheduleRestart(Service s) {
        long delay = Math.min(maxBackoff.toMillis(), firstBackoff.toMillis() << Math.min(s.streak.get(), 10));
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delay);
                if (!s.wanted || services.get(s.name) != s) return;
                s.restarts.incrementAndGet();
                s.streak.incrementAndGet();
                s.launch();
                listener.restarted(s.name);
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
        return s == null ? 0 : s.restarts.get();
    }

    /**
     * Kills {@code name} at once (it's hung or broken): it's restarted like after a crash. Unlike {@link #stop},
     * it stays wanted.
     */
    @SuppressFBWarnings(value = "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT", justification = "Process.destroyForcibly kills the process; the Process it returns is the same one")
    public void kill(String name) {
        Service s = services.get(name);
        Process p = s == null ? null : s.process;
        if (p != null) p.descendants().forEach(ProcessHandle::destroyForcibly);
        if (p != null) p.destroyForcibly();
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
