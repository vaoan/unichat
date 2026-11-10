package app.unichat;

import app.unichat.settings.Settings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quitting stopped the service and the local server on the UI thread: up to 10 s per bridge, or a whole bridge install,
 * of a window that wouldn't answer ("Not responding" on Windows). They stop on a thread of their own, then the app ends.
 */
class ShutdownTest {
    @Test
    void whatStopsRunsOffTheCallersThreadInOrderThenTheEnd() throws Exception {
        Thread caller = Thread.currentThread();
        List<String> done = new CopyOnWriteArrayList<>();
        CompletableFuture<Thread> ranOn = new CompletableFuture<>();
        CompletableFuture<Void> ended = new CompletableFuture<>();
        Shutdown.stopThenEnd(List.of(
                () -> { ranOn.complete(Thread.currentThread()); done.add("cloud"); },
                () -> { throw new IllegalStateException("one failing stops nothing else"); },
                () -> done.add("service"),
                () -> done.add("server")), () -> { done.add("end"); ended.complete(null); });
        ended.get();
        assertNotEquals(caller, ranOn.get());
        assertEquals(List.of("cloud", "service", "server", "end"), done);
    }

    /** Settings are written on a daemon thread, which the end doesn't wait for: what stopping changed reaches the disk first. */
    @Test
    void theSettingsAreOnDiskBeforeTheEnd() throws Exception {
        Settings settings = Settings.get();
        String value = UUID.randomUUID().toString();
        CompletableFuture<String> onDiskAtTheEnd = new CompletableFuture<>();
        try {
            Shutdown.stopThenEnd(List.of(() -> settings.set("window.test", value)), () -> {
                try {
                    onDiskAtTheEnd.complete(Files.readString(settings.dir().resolve("session.properties")));
                } catch (IOException e) {
                    onDiskAtTheEnd.completeExceptionally(e);
                }
            });
            assertTrue(onDiskAtTheEnd.get().contains(value));
        } finally {
            settings.set("window.test", null);
            settings.flush();
        }
    }
}
