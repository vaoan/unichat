package app.unichat.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SupervisorTest {
    @TempDir Path dir;
    private final Supervisor sup = new Supervisor(Duration.ofMillis(50), Duration.ofMillis(200));

    @AfterEach
    void stop() {
        sup.stopAll();
    }

    /** A child JVM running {@link FakeService}: portable on macOS and Windows. */
    private static List<String> fake(String mode) {
        String java = ProcessHandle.current().info().command().orElse("java");
        return List.of(java, "-cp", System.getProperty("java.class.path"), FakeService.class.getName(), mode);
    }

    @Test
    void runsAndLogsAndStops() throws Exception {
        Path log = dir.resolve("svc.log");
        sup.start("svc", dir, fake("run"), log);
        await(() -> sup.isRunning("svc"));
        await(() -> contains(log, "started"));
        sup.stopAll();
        assertFalse(sup.isRunning("svc"));
    }

    @Test
    void restartsAfterACrash() throws Exception {
        Path log = dir.resolve("crash.log");
        sup.start("crash", dir, fake("crash"), log);
        await(() -> sup.restarts("crash") >= 2);
        assertTrue(Files.readString(log).split("started", -1).length - 1 >= 2, "log is appended across restarts");
    }

    @Test
    void stoppedServicesAreNotRestarted() throws Exception {
        sup.start("svc", dir, fake("run"), dir.resolve("svc.log"));
        await(() -> sup.isRunning("svc"));
        sup.stop("svc");
        Thread.sleep(400);
        assertFalse(sup.isRunning("svc"));
        assertEquals(0, sup.restarts("svc"));
    }

    private static boolean contains(Path f, String s) {
        try {
            return Files.exists(f) && Files.readString(f).contains(s);
        } catch (Exception e) {
            return false;
        }
    }

    private static void await(BooleanSupplier cond) throws InterruptedException {
        long end = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("condition not met in 15 s");
    }
}
