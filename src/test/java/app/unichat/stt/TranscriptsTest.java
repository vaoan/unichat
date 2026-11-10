package app.unichat.stt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptsTest {
    private static void await(Transcripts t, String url) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        t.addListener(() -> {
            Transcripts.State s = t.state(url);
            if (s instanceof Transcripts.Done || s instanceof Transcripts.Failed) done.countDown();
        });
        if (t.state(url) instanceof Transcripts.Done || t.state(url) instanceof Transcripts.Failed) return;
        done.await();
    }

    @Test
    void eachNoteIsTranscribedOnceAndRemembered(@TempDir Path dir) throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Transcripts t = new Transcripts(dir, (audio, status) -> {
            runs.incrementAndGet();
            status.accept("Transcribiendo…");
            return "llego a las doce";
        });
        assertNull(t.state("mxc://s/a"));
        t.request("mxc://s/a", () -> CompletableFuture.completedFuture(dir.resolve("a.ogg")));
        await(t, "mxc://s/a");
        assertEquals("llego a las doce", ((Transcripts.Done) t.state("mxc://s/a")).text());

        t.request("mxc://s/a", () -> CompletableFuture.completedFuture(dir.resolve("a.ogg")));
        assertEquals(1, runs.get(), "done notes aren't transcribed again");
        Transcripts later = new Transcripts(dir, (audio, status) -> { throw new AssertionError("not needed"); });
        assertEquals("llego a las doce", ((Transcripts.Done) later.state("mxc://s/a")).text(), "kept on disk");
    }

    @Test
    void awaitingGivesTheTextOrNullOnFailureOrDelay(@TempDir Path dir) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Transcripts t = new Transcripts(dir, (audio, status) -> {
            if (audio.endsWith("slow.ogg")) release.await();
            if (audio.endsWith("bad.ogg")) throw new java.io.IOException("no");
            return "nos vemos";
        });
        // Only the slow one is about the limit: the others may take what they take.
        java.time.Duration wait = java.time.Duration.ofDays(1);
        assertEquals("nos vemos", t.await("mxc://s/ok", () -> CompletableFuture.completedFuture(dir.resolve("ok.ogg")), wait));
        assertNull(t.await("mxc://s/bad", () -> CompletableFuture.completedFuture(dir.resolve("bad.ogg")), wait));
        // Held until released: with no time left to wait it can't be there, whatever the machine's speed.
        assertNull(t.await("mxc://s/slow", () -> CompletableFuture.completedFuture(dir.resolve("slow.ogg")), java.time.Duration.ZERO));
        release.countDown();
        assertEquals("nos vemos", t.await("mxc://s/ok", () -> { throw new AssertionError("done already"); }, wait));
        // The slow one still finishes and writes its file: waited for, or it raced the folder's deletion (a flaky test).
        await(t, "mxc://s/slow");
    }

    @Test
    void transcribingAheadDoesItOnceAndNeverRetriesAFailure(@TempDir Path dir) throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Transcripts t = new Transcripts(dir, (audio, status) -> {
            runs.incrementAndGet();
            if (audio.endsWith("bad.ogg")) throw new java.io.IOException("no");
            return "ya voy";
        });
        t.requestAhead("mxc://s/ok", () -> CompletableFuture.completedFuture(dir.resolve("ok.ogg")));
        await(t, "mxc://s/ok");
        assertEquals("ya voy", ((Transcripts.Done) t.state("mxc://s/ok")).text(), "ready before anyone asks");
        t.requestAhead("mxc://s/bad", () -> CompletableFuture.completedFuture(dir.resolve("bad.ogg")));
        await(t, "mxc://s/bad");
        int before = runs.get();
        // Asked ahead on every update of the open chat: a note that failed isn't tried again and again.
        t.requestAhead("mxc://s/bad", () -> CompletableFuture.completedFuture(dir.resolve("bad.ogg")));
        t.requestAhead("mxc://s/ok", () -> CompletableFuture.completedFuture(dir.resolve("ok.ogg")));
        assertEquals(before, runs.get());
        assertTrue(t.state("mxc://s/bad") instanceof Transcripts.Failed);
    }

    @Test
    void aFailureCanBeRetried(@TempDir Path dir) throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Transcripts t = new Transcripts(dir, (audio, status) -> {
            if (runs.incrementAndGet() == 1) throw new java.io.IOException("whisper-cli falló");
            return "ahora sí";
        });
        t.request("mxc://s/b", () -> CompletableFuture.completedFuture(dir.resolve("b.ogg")));
        await(t, "mxc://s/b");
        assertEquals("whisper-cli falló", assertInstanceOf(Transcripts.Failed.class, t.state("mxc://s/b")).error());

        t.request("mxc://s/b", () -> CompletableFuture.completedFuture(dir.resolve("b.ogg")));
        await(t, "mxc://s/b");
        assertEquals("ahora sí", assertInstanceOf(Transcripts.Done.class, t.state("mxc://s/b")).text());
    }

    /** The chat's update, the button and a draft can ask for the same note at once: it's transcribed once. */
    @Test
    void askedAtOnceFromSeveralThreadsItsTranscribedOnce(@TempDir Path dir) throws Exception {
        java.util.Map<String, AtomicInteger> runs = new java.util.concurrent.ConcurrentHashMap<>();
        Transcripts t = new Transcripts(dir, (audio, status) -> {
            runs.computeIfAbsent(audio.getFileName().toString(), k -> new AtomicInteger()).incrementAndGet();
            return "hola";
        });
        int askers = 8;
        for (int note = 0; note < 100; note++) {
            String url = "mxc://s/" + note;
            Path audio = dir.resolve(note + ".ogg");
            java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(askers);
            List<Thread> threads = new java.util.ArrayList<>();
            for (int i = 0; i < askers; i++) {
                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        together.await();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    t.request(url, () -> CompletableFuture.completedFuture(audio));
                }));
            }
            for (Thread th : threads) th.join(); // lesson-ok: test-future-join (a thread)
            await(t, url);
        }
        CountDownLatch idle = new CountDownLatch(1);
        t.request("mxc://s/last", () -> { idle.countDown(); return CompletableFuture.completedFuture(dir.resolve("last.ogg")); });
        idle.await(); // one worker in order: everything asked before has run
        await(t, "mxc://s/last");
        runs.forEach((note, n) -> assertEquals(1, n.get(), note + " transcribed " + n.get() + " times"));
    }

    @Test
    void whisperMarkersAreNotText() {
        assertEquals("Hola, ¿cómo estás?", Transcriber.clean(" Hola,\n ¿cómo estás?\n"));
        assertEquals("", Transcriber.clean("[BLANK_AUDIO]\n"));
        assertEquals("sí", Transcriber.clean("[Música] sí"));
    }
}
