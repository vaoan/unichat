package app.unichat;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void aJavaLineTheSystemDoesNotGiveIsRebuiltFromTheJvmItself() {
        // Linux gave no arguments for a long java line (CI: "Unrecognized option: --unichat-after=7372:1").
        assertEquals(List.of("-Xmx1g", "-cp", "a.jar:b.jar", "app.unichat.Launcher", "--x"),
                Recovery.javaLine("/usr/bin/java", List.of("-Xmx1g"), "a.jar:b.jar", "app.unichat.Launcher --x"));
        assertEquals(List.of("-jar", "unichat.jar", "--x"),
                Recovery.javaLine("C:\\jdk\\bin\\java.exe", List.of(), "unichat.jar", "unichat.jar --x"));
        assertEquals(List.of(), Recovery.javaLine("/Applications/Unichat.app/Contents/MacOS/Unichat", List.of("-Xmx1g"),
                "x.jar", "app.unichat.Launcher"), "a launcher's arguments aren't a java line's");
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

    /** A mark that doesn't read as "pid:count" (typed by hand, cut short) starts Unichat as with none, never a crash at start. */
    @Test
    void aMarkThatDoesntReadIsNoMarkAtAll() {
        for (String bad : List.of("--unichat-after=x:1", "--unichat-after=42:y", "--unichat-after=42", "--unichat-after=")) {
            Recovery.Start s = Recovery.Start.of(new String[] {bad, "other"});
            assertEquals(-1, s.waitFor(), bad);
            assertEquals(0, s.restarts(), bad);
            assertEquals(List.of("other"), List.of(s.args()), bad);
        }
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
        assertFalse(Recovery.reallyFull(() -> {
            collections.incrementAndGet();
            used[0] = 160;
        }, () -> used[0], 950),
                "mostly garbage: collected, no restart");
        assertEquals(1, collections.get(), "a full collection ran first");
        assertTrue(Recovery.reallyFull(() -> used[0] = 960, () -> used[0], 950), "still full after it: restart");
    }

    /** A restart for memory says what filled it, so the next one can be fixed without guessing. */
    @Test
    void whenMemoryIsFullTheLogSaysWhatFillsIt() {
        String h = Recovery.heaviest(5);
        assertTrue((h.contains(" MB") || h.contains(" KB")) && h.contains("java.") && !h.contains("<number>"), h);
        assertTrue(h.lines().count() <= 5 + 4, "only the heaviest: " + h.lines().count());
    }

    @Test
    @SuppressFBWarnings(value = "DM_USELESS_THREAD", justification = "a thread named as the sync's, never started: the handler only reads its name")
    void itStartsAgainEvenWhenThereIsNoMemoryToSayWhy() {
        // 2026-11-13: the heap ran out in the sync thread, and the handler's own println ran out too, before it could
        // start Unichat again; the thread was gone, nothing was said and the top bar kept "Conectando…".
        java.io.PrintStream err = System.err; // NOPMD: CloseResource, put back below
        List<String> restarts = new java.util.ArrayList<>();
        System.setErr(new java.io.PrintStream(new java.io.OutputStream() { // NOPMD: RelianceOnDefaultCharset, it never writes a byte
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

    private static final String HISTOGRAM = """
             num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:        298123       40476672  [B (java.base@25.0.5.0.3)
               2:          5100        120000  java.lang.String (java.base@25.0.5.0.3)
               3:            12           800  app.Foo
            Total      12345678     987654321
            """;

    @Test
    void theHistogramRowsAreReadableInUnits() {
        assertEquals("  1. [B (java.base) - 38.6 MB, 298k objects",
                Recovery.heaviestRow("   1:        298123       40476672  [B (java.base@25.0.5.0.3)"));
        assertEquals("  3. app.Foo - 800 B, 12 objects", Recovery.heaviestRow("   3:            12           800  app.Foo"));
        assertEquals("  2. x - 117.2 KB, 5k objects", Recovery.heaviestRow("   2:  5100  120000  x"));
        assertNull(Recovery.heaviestRow("-------------------------------"));
        assertNull(Recovery.heaviestRow(" num     #instances         #bytes  class name (module)"));
    }

    @Test
    void theWholeHistogramKeepsItsRowsAndTotalAndDropsTheHeaderLines() {
        String out = Recovery.readable(HISTOGRAM, 2);
        assertTrue(out.startsWith("Memory: what fills it"), out);
        assertTrue(out.contains("1. [B (java.base) - 38.6 MB, 298k objects"), out);
        assertTrue(out.contains("2. java.lang.String (java.base) - 117.2 KB, 5k objects"), out);
        assertFalse(out.contains("app.Foo"), "only the count heaviest: " + out);
        assertTrue(out.contains("Total: 941.9 MB, 12.3M objects"), out);
        assertFalse(out.contains("#instances") || out.contains("-----"), out);
    }

    @Test
    void theLogKeepsEveryNumberAfterTheRedactor() {
        String out = Recovery.readable(HISTOGRAM, 25);
        assertEquals(out, app.unichat.support.Redactor.redact(out));
        assertFalse(out.contains("<number>"));
        String huge = Recovery.readable("   1:     50000000     2147483648  [B (java.base@25)\nTotal 80000000 3000000000", 5);
        assertEquals(huge, app.unichat.support.Redactor.redact(huge));
        assertTrue(huge.contains("2048.0 MB, 50.0M objects"), huge);
    }
}
