package app.unichat.cloak;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloakJobTest {
    @TempDir
    Path dir;

    @Test
    void itReadsTheScriptsProgressLines() {
        assertEquals(0.25, CloakJob.progress("progress 9 36"), 1e-9);
        assertEquals(1.0, CloakJob.progress("progress 90 90\n"), 1e-9);
        assertEquals(-1, CloakJob.progress("done"));
        assertEquals(-1, CloakJob.progress("progress x 3"));
        assertEquals(-1, CloakJob.progress("progress 1 0"));
    }

    @Test
    void itInstallsThenProtectsThenHandsTheFileOver() throws Exception {
        List<CloakJob.Phase> seen = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Path> sent = new AtomicReference<>();
        CloakJob job = new CloakJob(fake(null, null, null));
        job.addListener(() -> seen.add(job.status().phase()));
        assertTrue(job.start(dir.resolve("foto.jpg"), dir.resolve("foto.png"), CloakLevel.MEDIUM, p -> {
            sent.set(p);
            done.countDown();
        }));
        done.await();
        assertEquals(dir.resolve("foto.png"), sent.get());
        assertEquals(CloakJob.Phase.DONE, job.status().phase());
        assertTrue(seen.indexOf(CloakJob.Phase.INSTALLING) < seen.indexOf(CloakJob.Phase.PROTECTING));
        assertEquals("foto.jpg", job.status().file());
    }

    @Test
    void onlyOneAtATimeAndCancellingSendsNothing() throws Exception {
        CountDownLatch going = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Path> sent = new AtomicReference<>();
        CloakJob job = new CloakJob(fake(going, stopped, null));
        assertTrue(job.start(dir.resolve("a.png"), dir.resolve("a-out.png"), CloakLevel.STRONG, sent::set));
        going.await();
        assertFalse(job.start(dir.resolve("b.png"), dir.resolve("b-out.png"), CloakLevel.LIGHT, sent::set), "busy");
        job.cancel();
        assertEquals(CloakJob.Phase.CANCELLED, job.status().phase());
        stopped.await(); // the run itself was stopped, not just the status changed
        assertNull(sent.get());
        assertEquals(CloakJob.Phase.CANCELLED, job.status().phase(), "the stopped run doesn't overwrite it");
        job.dismiss();
        assertEquals(CloakJob.Phase.IDLE, job.status().phase());
        assertTrue(job.start(dir.resolve("b.png"), dir.resolve("b-out.png"), CloakLevel.LIGHT, sent::set), "free again");
    }

    @Test
    void aFailureStaysWithItsReason() throws Exception {
        CloakJob job = new CloakJob(fake(null, null, new IOException("sin espacio")));
        CountDownLatch over = new CountDownLatch(1);
        job.addListener(() -> { if (job.status().phase() == CloakJob.Phase.FAILED) over.countDown(); });
        job.start(dir.resolve("a.png"), dir.resolve("o.png"), CloakLevel.LIGHT, p -> { });
        over.await();
        assertEquals("sin espacio", job.status().error());
        assertFalse(job.status().busy());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theProcessIsReadLineByLineAndMustLeaveItsFile() throws Exception {
        Path out = dir.resolve("sub").resolve("out.png");
        List<Double> got = new ArrayList<>();
        CloakJob.run(List.of("sh", "-c", "echo progress 1 4; echo hola; echo progress 4 4; echo x > \"$0\"", out.toString()),
                out, got::add);
        assertEquals(List.of(0.25, 1.0), got);
        assertTrue(Files.isRegularFile(out));
        Path none = dir.resolve("none.png");
        assertThrows(IOException.class, () -> CloakJob.run(List.of("sh", "-c", "echo progress 1 1"), none, f -> { }));
        assertThrows(IOException.class, () -> CloakJob.run(List.of("sh", "-c", "echo x > \"$0\"; exit 3", none.toString()),
                none, f -> { }));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void interruptingStopsTheProcess() throws Exception {
        Path out = dir.resolve("o.png");
        CountDownLatch running = new CountDownLatch(1);
        AtomicReference<Throwable> ended = new AtomicReference<>();
        // tail -f never ends on its own: only being stopped ends it.
        Thread t = Thread.ofPlatform().start(() -> {
            try {
                CloakJob.run(List.of("sh", "-c", "echo progress 1 9; exec tail -f /dev/null"), out, f -> running.countDown());
            } catch (Throwable e) {
                ended.set(e);
            }
        });
        running.await();
        t.interrupt();
        t.join(); // lesson-ok: test-future-join (a thread)
        assertTrue(ended.get() instanceof InterruptedException, String.valueOf(ended.get()));
    }

    /**
     * Installs at once and protects in a few steps; or, with {@code going}, reports half and blocks until stopped,
     * counting {@code stopped} down when it is.
     */
    private static CloakJob.Runner fake(CountDownLatch going, CountDownLatch stopped, IOException failure) {
        return new CloakJob.Runner() {
            @Override
            public void install(CloakTool.Progress progress) {
                progress.at("cloak.install.packages", -1);
            }

            @Override
            public void protect(Path in, Path out, CloakLevel level, DoubleConsumer progress)
                    throws IOException, InterruptedException {
                if (failure != null) throw failure;
                progress.accept(0.5);
                if (going != null) {
                    going.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } finally {
                        stopped.countDown();
                    }
                }
                progress.accept(1);
            }
        };
    }
}
