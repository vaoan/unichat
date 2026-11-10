package app.unichat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recovery for real, in processes of its own: a run that runs out of memory ends and starts the same program again,
 * which waits until the old one is gone. Only the packaged launcher itself isn't exercised (it would open a window).
 */
class RecoveryRestartTest {
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "ProcessHandle gives no arguments there: the launcher alone restarts")
    void aRunOutOfMemoryStartsTheProgramAgainOnceItEnded() throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        Process first = new ProcessBuilder(List.of(java, "-cp", System.getProperty("java.class.path"),
                RecoveryProbe.class.getName())).redirectErrorStream(true).start();
        // Read to the end: the second run writes to the same output, so it ends when both have.
        String out = new String(first.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(3, first.waitFor(), out);
        long pid = first.pid();
        assertTrue(out.contains("first run " + pid), out);
        assertTrue(out.contains("Recovery: a thread ran out of memory (probe); starting again."), out);
        assertTrue(out.contains("started again after " + pid + " as restart 1 once it had ended"), out);
    }
}
