package app.unichat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An OutOfMemoryError killed the sync thread (no more messages) and froze the window, and Unichat stayed like that until
 * the person killed it. It starts again by itself, the same way it was started, once the old one is gone, and never
 * in a loop.
 */
class RecoveryTest {
    @Test
    void itStartsAgainAsItWasStartedAfterTheOldOneEnds() {
        assertEquals(List.of("/Applications/Unichat.app/Contents/MacOS/Unichat", "--unichat-after=42:1"),
                Recovery.command("/Applications/Unichat.app/Contents/MacOS/Unichat", List.of(), 42, 1));
        // From sources: the same java line; the previous restart's mark is replaced, not piled.
        assertEquals(List.of("/usr/bin/java", "-cp", "x.jar", "app.unichat.Launcher", "--unichat-after=7:2"),
                Recovery.command("/usr/bin/java", List.of("-cp", "x.jar", "app.unichat.Launcher", "--unichat-after=3:1"), 7, 2));
    }

    @Test
    void theMarkIsReadAndLeftOutOfTheAppsArguments() {
        Recovery.Start s = Recovery.Start.of(new String[] {"--unichat-after=42:2", "other"});
        assertEquals(42, s.waitFor());
        assertEquals(2, s.restarts());
        assertEquals(List.of("other"), List.of(s.args()));
        Recovery.Start fresh = Recovery.Start.of(new String[0]);
        assertEquals(-1, fresh.waitFor());
        assertEquals(0, fresh.restarts());
    }

    @Test
    void neverInALoop() {
        assertTrue(Recovery.mayRestart(0, Duration.ofSeconds(30)));
        assertTrue(Recovery.mayRestart(2, Duration.ofMinutes(1)));
        assertFalse(Recovery.mayRestart(3, Duration.ofMinutes(1)), "a fourth in a row: something's wrong at start");
        assertTrue(Recovery.mayRestart(3, Duration.ofMinutes(11)), "ran well for a while: counting starts over");
        assertEquals(1, Recovery.nextCount(3, Duration.ofMinutes(11)));
        assertEquals(3, Recovery.nextCount(2, Duration.ofMinutes(1)));
    }

    @Test
    void onlyRunningOutOfMemoryRestarts() {
        assertTrue(Recovery.fatal(new OutOfMemoryError("Java heap space")));
        assertTrue(Recovery.fatal(new StackOverflowError()));
        assertFalse(Recovery.fatal(new IllegalStateException("a bug in one thread")));
    }

    /**
     * Unichat restarted itself three times in a day on "memory still nearly full after a collection" while what it held
     * was ~160 MB: G1 measures the old generation after young collections too, which leave its garbage (swept only by a
     * mixed or full cycle), so 95 % "used" was mostly garbage. A full collection decides first.
     */
    @Test
    void garbageInTheOldGenerationIsCollectedNotRestartedFor() {
        java.util.concurrent.atomic.AtomicInteger collections = new java.util.concurrent.atomic.AtomicInteger();
        long[] used = {950};
        assertFalse(Recovery.reallyFull(() -> { collections.incrementAndGet(); used[0] = 160; }, () -> used[0], 950),
                "mostly garbage: collected, no restart");
        assertEquals(1, collections.get(), "a full collection ran first");
        assertTrue(Recovery.reallyFull(() -> used[0] = 960, () -> used[0], 950), "still full after it: restart");
    }

    /** A restart for memory says what filled it, so the next one can be fixed without guessing. */
    @Test
    void whenMemoryIsFullTheLogSaysWhatFillsIt() {
        String h = Recovery.heaviest(5);
        org.junit.jupiter.api.Assertions.assertTrue(h.contains("#instances") && h.contains("java."), h);
        org.junit.jupiter.api.Assertions.assertTrue(h.lines().count() <= 5 + 4, "only the heaviest: " + h.lines().count());
    }

    @Test
    void itStartsAgainEvenWhenThereIsNoMemoryToSayWhy() {
        // 2026-11-13: the heap ran out in the sync thread, and the handler's own println ran out too, before it could
        // start Unichat again; the thread was gone, nothing was said and the top bar kept "Conectando…".
        java.io.PrintStream err = System.err;
        List<String> restarts = new java.util.ArrayList<>();
        System.setErr(new java.io.PrintStream(new java.io.OutputStream() {
            @Override public void write(int b) {
                throw new OutOfMemoryError("Java heap space");
            }
        }));
        try {
            Recovery.uncaught(new Thread("matrix-sync"), new OutOfMemoryError("Java heap space"), restarts::add);
        } finally {
            System.setErr(err);
        }
        assertEquals(List.of("a thread ran out of memory (matrix-sync)"), restarts);
    }
}
