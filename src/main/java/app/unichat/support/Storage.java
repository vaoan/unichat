package app.unichat.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Whether the disk has room for Unichat's server, and which of its processes couldn't write. With the disk full,
 * tuwunel and the bridges can't save what arrives, so nothing new reaches the chats while every process still answers;
 * the window says so and the watchdog starts them over once there's room ({@code server/Watchdog}).
 */
public final class Storage {
    /** Under this much free, the disk is low: a database's next write or compaction may not fit. */
    public static final long LOW_BYTES = 1L << 30;
    /** How long a process that couldn't write counts as failing, unless it starts over first. */
    public static final long FAILING_MS = 10 * 60_000;
    static final long CHECK_EVERY_MS = 30_000;

    /** The one the app watches its data folder with ({@link #watch}); never low until then (tests, snapshots). */
    public static final Storage SHARED = new Storage(() -> Long.MAX_VALUE, System::currentTimeMillis);

    private volatile LongSupplier measure;
    private final LongSupplier clock;
    private volatile long free = Long.MAX_VALUE;
    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService timer;

    /** @param measure the free bytes now; @param clock milliseconds */
    public Storage(LongSupplier measure, LongSupplier clock) {
        this.measure = measure;
        this.clock = clock;
    }

    /** Measures the disk of {@code dir} from now on, every 30 s on a thread of its own. */
    public synchronized void watch(Path dir) {
        measure = () -> {
            try {
                return Files.getFileStore(Files.exists(dir) ? dir : dir.getParent()).getUsableSpace();
            } catch (IOException | RuntimeException e) {
                return Long.MAX_VALUE; // can't tell: never a false alarm
            }
        };
        check();
        if (timer != null) return;
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "storage");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::check, CHECK_EVERY_MS, CHECK_EVERY_MS, TimeUnit.MILLISECONDS);
    }

    /** Measures again; the listeners hear when being low changed. */
    public void check() {
        boolean was = low();
        free = measure.getAsLong();
        if (low() != was) tell();
    }

    public boolean low() {
        return free < LOW_BYTES;
    }

    /** Free bytes when last measured. */
    public long free() {
        return free;
    }

    /** {@code process} couldn't write ({@link #writeFailed} on its output). */
    public void failed(String process) {
        boolean known = failedAt.containsKey(process);
        failedAt.put(process, clock.getAsLong());
        if (!known) tell();
    }

    /** {@code process} was started over: what it failed to write is behind it. */
    public void startedOver(String process) {
        if (failedAt.remove(process) != null) tell();
    }

    /** The processes that couldn't write in the last {@link #FAILING_MS}. */
    public Set<String> failing() {
        long now = clock.getAsLong();
        failedAt.values().removeIf(at -> now - at > FAILING_MS);
        return failedAt.keySet().stream().collect(Collectors.toUnmodifiableSet());
    }

    /** Called on the thread that saw the change. */
    public void addListener(Runnable l) {
        listeners.add(l);
    }

    public void removeListener(Runnable l) {
        listeners.remove(l);
    }

    private void tell() {
        for (Runnable l : listeners) l.run();
    }

    /**
     * Whether a line of a process's log says a write didn't fit: an error line (the bridges' JSON {@code level}, tuwunel's
     * {@code ERROR}) naming a full disk or a failed disk write. Message text can reach a bridge's log, so the words alone
     * don't count; what one does with it is only a restart, with the watchdog's waits.
     */
    public static boolean writeFailed(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        boolean error = line.startsWith("{")
                ? l.startsWith("{\"level\":\"error\"") || l.startsWith("{\"level\":\"fatal\"") || l.startsWith("{\"level\":\"panic\"")
                : line.contains("ERROR");
        return error && (l.contains("no space left on device") || l.contains("database or disk is full")
                || l.contains("sqlite_full") || l.contains("disk i/o error"));
    }
}
