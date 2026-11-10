package app.unichat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
}
