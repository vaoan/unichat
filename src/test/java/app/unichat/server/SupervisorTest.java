package app.unichat.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupervisorTest {
    @TempDir Path dir;
    /** No wait before a restart: the tests follow the Supervisor's events, not its timing. */
    private static final Duration FIRST_BACKOFF = Duration.ZERO, MAX_BACKOFF = Duration.ZERO;
    private final Supervisor sup = new Supervisor(FIRST_BACKOFF, MAX_BACKOFF);
    private final app.unichat.service.Changes changes = new app.unichat.service.Changes();
    /** Each exit the Supervisor handled: "name:true" when it scheduled a restart. */
    private final List<String> exits = new java.util.concurrent.CopyOnWriteArrayList<>();

    {
        sup.listen(new Supervisor.Listener() {
            @Override public void logged(String name) { changes.changed(); }
            @Override public void exited(String name, boolean restarting) {
                exits.add(name + ":" + restarting);
                changes.changed();
            }
            @Override public void restarted(String name) { changes.changed(); }
        });
    }

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
        assertTrue(sup.isRunning("svc"));
        await(() -> contains(log, "started"));
        sup.stopAll();
        assertFalse(sup.isRunning("svc"));
    }

    @Test
    void aLogThatCantBeWrittenNeitherBlocksTheProcessNorEndsTheCopy() throws Exception {
        // The log path is a folder at first: every line fails, yet the output must keep being read (1 MB, far more
        // than a pipe holds) and, once the log can be written again, lines reach it.
        Path log = Files.createDirectory(dir.resolve("bridge.log"));
        String java = ProcessHandle.current().info().command().orElse("java");
        try (ServerSocket test = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            sup.start("chatty", dir, List.of(java, "-cp", System.getProperty("java.class.path"), FakeService.class.getName(),
                    "chatty", Integer.toString(test.getLocalPort())), log);
            Socket wrote = test.accept(); // it calls once its 1 MB is out, which only a reader makes possible
            Files.delete(log);
            wrote.close(); // go on
        }
        await(() -> contains(log, "after"));
        assertEquals(0, sup.restarts("chatty"), "the same process: its output was never closed under it");
    }

    @Test
    void personalDataNeverReachesTheLog() throws Exception {
        Path log = dir.resolve("bridge.log");
        String java = ProcessHandle.current().info().command().orElse("java");
        sup.start("wa", dir, List.of(java, "-cp", System.getProperty("java.class.path"), FakeService.class.getName(),
                "run", "{\"name\":\"Ana Perez\",\"message\":\"Connected\",\"jid\":\"573009998877@s.whatsapp.net\"}"), log);
        await(() -> contains(log, "Connected"));
        String text = Files.readString(log);
        assertFalse(text.contains("Ana Perez"), text);
        assertFalse(text.contains("573009998877"), text);
    }

    @Test
    void orphansFromAPreviousRunAreStopped() throws Exception {
        Path run = dir.resolve("run");
        Supervisor first = new Supervisor(FIRST_BACKOFF, MAX_BACKOFF, run);
        first.start("svc", dir, fake("run"), dir.resolve("svc.log"));
        assertTrue(first.isRunning("svc"));
        long orphan = Long.parseLong(Files.readString(run.resolve("svc.pid")).strip().split(" ")[0]);
        // Unichat dies without stopping its children (forced quit, crash): the next run cleans up.
        Supervisor next = new Supervisor(FIRST_BACKOFF, MAX_BACKOFF, run);
        next.reapOrphans();
        var left = ProcessHandle.of(orphan);
        if (left.isPresent()) left.get().onExit().get();
        assertFalse(Files.exists(run.resolve("svc.pid")));
    }

    @Test
    void unrelatedProcessesAreNeverKilled() throws Exception {
        Path run = dir.resolve("run");
        Files.createDirectories(run);
        // A pid file pointing at this test's own JVM (not started by the supervisor): must survive.
        Files.writeString(run.resolve("svc.pid"), Long.toString(ProcessHandle.current().pid()));
        new Supervisor(FIRST_BACKOFF, MAX_BACKOFF, run).reapOrphans();
        assertTrue(ProcessHandle.current().isAlive());
        assertFalse(Files.exists(run.resolve("svc.pid")));
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
        assertTrue(sup.isRunning("svc"));
        sup.stop("svc");
        // The Supervisor handled the exit: with no restart scheduled, none can come.
        changes.until("the stopped process's exit handled", () -> exits.contains("svc:false") || exits.contains("svc:true"));
        assertEquals(List.of("svc:false"), exits, "no restart scheduled");
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

    /** Until {@code cond} holds, checked again at each event of the Supervisor (a log line, an exit, a restart). */
    private void await(BooleanSupplier cond) throws InterruptedException {
        changes.until(cond);
    }
}
