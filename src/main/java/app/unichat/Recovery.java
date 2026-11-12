package app.unichat;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.NotificationEmitter;

/**
 * Starting again by itself when Unichat can't go on: an OutOfMemoryError in any thread (it once killed the sync, so no
 * message arrived, and froze the window), the heap still nearly full right after a collection (the step before that
 * error), or a window that stopped answering ({@link FxWatch}). What happened goes to the log with every thread's
 * stack; then the same program is started again, which waits for this one to end, and this one exits. At most
 * {@link #MAX_IN_A_ROW} times in a row, so a problem at start can't loop.
 */
final class Recovery {
    static final int MAX_IN_A_ROW = 3;
    /** Running this long counts as healthy: the count of restarts in a row starts over. */
    static final Duration HEALTHY = Duration.ofMinutes(10);
    /** Share of the old generation still in use after a collection that means the heap is about to run out. */
    static final double NEARLY_FULL = 0.95;
    private static final String MARK = "--unichat-after=";

    private static final AtomicBoolean RESTARTING = new AtomicBoolean();
    private static volatile int restartsSoFar;
    private static volatile boolean installed;

    private Recovery() { }

    /** How this run was started: after which process ({@code -1}: none), the restarts in a row, and the app's arguments. */
    record Start(long waitFor, int restarts, String[] args) {
        static Start of(String[] args) {
            long after = -1;
            int restarts = 0;
            List<String> rest = new ArrayList<>();
            for (String a : args) {
                if (a.startsWith(MARK)) {
                    String[] v = a.substring(MARK.length()).split(":");
                    after = Long.parseLong(v[0]);
                    restarts = Integer.parseInt(v[1]);
                } else {
                    rest.add(a);
                }
            }
            return new Start(after, restarts, rest.toArray(String[]::new));
        }

        /** Waits for the run that started this one to end: two never share the data folder or the server's ports. */
        void waitForThePrevious() {
            if (waitFor >= 0) ProcessHandle.of(waitFor).ifPresent(p -> p.onExit().join());
        }
    }

    /** From now on, an error that leaves Unichat unable to go on starts it again. */
    static void install(Start start) {
        restartsSoFar = start.restarts();
        installed = true;
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("Uncaught in " + t.getName() + ": " + e);
            e.printStackTrace();
            if (fatal(e)) restart("a thread ran out of memory (" + t.getName() + ")");
        });
        watchMemory();
    }

    /** Watches the UI thread too, once it exists; nothing when recovery isn't on (tests, snapshot runs). */
    static void watchUi() {
        if (installed) FxWatch.start(() -> restart("the window stopped answering for a minute"));
    }

    /** Errors after which nothing in the program can be trusted to work. */
    static boolean fatal(Throwable e) {
        return e instanceof VirtualMachineError;
    }

    static boolean mayRestart(int restarts, Duration uptime) {
        return nextCount(restarts, uptime) <= MAX_IN_A_ROW;
    }

    /** The restarts in a row the next run carries. */
    static int nextCount(int restarts, Duration uptime) {
        return uptime.compareTo(HEALTHY) >= 0 ? 1 : restarts + 1;
    }

    /** The command that starts this program again: as it was started, without the previous mark, plus the new one. */
    static List<String> command(String program, List<String> args, long pid, int restarts) {
        List<String> c = new ArrayList<>();
        c.add(program);
        for (String a : args) if (!a.startsWith(MARK)) c.add(a);
        c.add(MARK + pid + ":" + restarts);
        return c;
    }

    /** Logs why with every thread's stack, starts the next run when allowed, and ends this one. Once. */
    static void restart(String why) {
        if (!RESTARTING.compareAndSet(false, true)) return;
        System.err.println("Recovery: " + why + "; starting again.");
        System.err.println(threads());
        Duration uptime = Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime());
        ProcessHandle.Info info = ProcessHandle.current().info();
        if (mayRestart(restartsSoFar, uptime) && info.command().isPresent()) {
            List<String> args = info.arguments().map(Arrays::asList).orElse(List.of());
            try {
                new ProcessBuilder(command(info.command().get(), args, ProcessHandle.current().pid(),
                        nextCount(restartsSoFar, uptime))).inheritIO().start();
            } catch (Exception e) {
                System.err.println("Recovery: could not start again: " + e);
            }
        } else {
            System.err.println("Recovery: " + MAX_IN_A_ROW + " restarts in a row, not starting again.");
        }
        // Ending can hang where things are broken: a shutdown hook flushes what was chosen, then it's cut short.
        Thread end = new Thread(() -> System.exit(3), "recovery-exit");
        end.setDaemon(true);
        end.start();
        Thread halt = new Thread(() -> {
            try {
                end.join(20_000);
            } catch (InterruptedException ignored) {
                // halting anyway
            }
            Runtime.getRuntime().halt(3);
        }, "recovery-halt");
        halt.start();
    }

    /** How many classes the log names when memory is full. */
    static final int HEAVIEST_SHOWN = 25;

    /**
     * What fills the memory: the {@code count} classes taking most of it (the JVM's class histogram, as
     * {@code jcmd GC.class_histogram} gives it). A restart for memory said only that it was full, and a probe of the
     * sticker panel with real stickers couldn't find what had filled it.
     */
    static String heaviest(int count) {
        try {
            Object out = ManagementFactory.getPlatformMBeanServer().invoke(
                    new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                    new Object[] {new String[0]}, new String[] {String[].class.getName()});
            String[] lines = String.valueOf(out).split("\n");
            return "Memory: what fills it\n" + String.join("\n", java.util.Arrays.copyOf(lines, Math.min(lines.length, count + 3)));
        } catch (Exception e) {
            return "Memory: no class histogram (" + e + ")";
        }
    }

    /** Restarts when the heap is still {@link #NEARLY_FULL} right after a collection: the step before running out. */
    private static void watchMemory() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            long max = pool.getUsage().getMax();
            if (pool.getType() != MemoryType.HEAP || !pool.isCollectionUsageThresholdSupported() || max <= 0) continue;
            if (!pool.getName().toLowerCase().contains("old")) continue;
            pool.setCollectionUsageThreshold((long) (max * NEARLY_FULL));
        }
        ((NotificationEmitter) ManagementFactory.getMemoryMXBean()).addNotificationListener((n, h) -> {
            if (!MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED.equals(n.getType()) || !CHECKING.compareAndSet(false, true)) return;
            try {
                MemoryPoolMXBean old = oldGeneration();
                if (old != null && reallyFull(System::gc, () -> old.getUsage().getUsed(), old.getCollectionUsageThreshold())) {
                    System.err.println(heaviest(HEAVIEST_SHOWN));
                    restart("memory still nearly full after a full collection");
                } else {
                    System.err.println("Memory: the old generation was mostly garbage; collected, not restarting.");
                }
            } finally {
                CHECKING.set(false);
            }
        }, null, null);
    }

    /** One check at a time: a full collection can bring more notifications while it runs. */
    private static final AtomicBoolean CHECKING = new AtomicBoolean();

    /**
     * Whether the heap is really nearly full: G1 measures the old generation after young collections too, which leave
     * its garbage (only a mixed or full cycle sweeps it), so the threshold alone took garbage for use and restarted
     * Unichat for nothing. A full collection ({@code collectAll}, a short pause, only when the threshold is crossed) decides.
     */
    static boolean reallyFull(Runnable collectAll, java.util.function.LongSupplier usedAfter, long limit) {
        collectAll.run();
        return usedAfter.getAsLong() >= limit;
    }

    private static MemoryPoolMXBean oldGeneration() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.isCollectionUsageThresholdSupported()
                    && pool.getName().toLowerCase().contains("old") && pool.getCollectionUsageThreshold() > 0) {
                return pool;
            }
        }
        return null;
    }

    private static String threads() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            out.append('"').append(e.getKey().getName()).append("\" ").append(e.getKey().getState()).append('\n');
            for (StackTraceElement s : e.getValue()) out.append("\tat ").append(s).append('\n');
        }
        return out.toString();
    }
}
